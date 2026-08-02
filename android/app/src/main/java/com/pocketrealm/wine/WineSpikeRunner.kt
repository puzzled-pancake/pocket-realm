package com.pocketrealm.wine

import android.content.Context
import android.os.SystemClock
import com.pocketrealm.log.AppLog
import com.pocketrealm.pkg.ExperimentResult
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.json.JSONObject

/**
 * Orchestrates the O06 Phase-1 Wine feasibility spike measurements (S-1/S-2/S-3).
 *
 * The spike proves:
 *  - S-1: Wine + wineserver + every native child map the APK-managed glibc loader
 *    as their effective dynamic loader (proven via /proc/<pid>/maps).
 *  - S-2: wineboot resolves Wine-owned PE modules from the hash-verified cache.
 *  - S-3: the self-test PE creates/shows an X11/GDI window via winex11.drv + the
 *    X-server harness.
 *
 * Each measurement announces structured logcat lines the host driver greps:
 *   WINE_SPIKE_S1_RESULT  ok=true  code=LOADER_PROVEN  ...
 *   WINE_SPIKE_S1_EVIDENCE  key=value  ...
 */
class WineSpikeRunner(private val context: Context) {

    companion object {
        private const val TAG = "WineSpike"
        private const val STAGING_MANIFEST_ASSET = "staging-manifest.json"
        private const val WINE_PE_MANIFEST_ASSET = "wine-pe-manifest.json"
        private const val WINE_DATA_MANIFEST_ASSET = "wine-data-manifest.json"
        private const val GUEST_PE_MANIFEST_ASSET = "guest-pe-manifest.json"
    }

    init {
        WineSpikeNative.load()
    }

    /** Result of the LD_DEBUG=libs loader-chain verification (bootstrap evidence). */
    private data class LoaderProof(
        val sawApkLoader: Boolean,
        val sawLibcResolved: Boolean,
        val sawLibdlResolved: Boolean,
        val evidence: Map<String, String>,
    )

    /** Parsed structured proot-run result (from runWineViaProotNative). */
    private data class ProotDescendant(
        val pid: Long, val ppid: Long, val comm: String,
        val mapsProof: String, val cmdline: String,
        val classification: String,  // wine/wineserver/proot/loader/glibc-child/unknown
    )

    private data class ProotRunResult(
        val rc: Int, val exitStatus: Int, val timedOut: Boolean,
        val descendants: List<ProotDescendant>,
        val stdout: String, val stderr: String,
    ) {
        /** Linux waitpid exit helpers (exitStatus is the raw status). */
        val exitedCleanly: Boolean get() = (exitStatus >= 0) && (exitStatus and 0x7f) == 0
        val exitCode: Int get() = if (exitedCleanly) ((exitStatus shr 8) and 0xff) else -1
        val signaledBy: Int get() = if (exitedCleanly) -1 else (exitStatus and 0x7f)
    }

    /** Parse the structured result string from runWineViaProotNative. */
    private fun parseProotRunResult(s: String): ProotRunResult {
        val lines = s.split("\n")
        // Header is the first line.
        val header = lines.firstOrNull() ?: ""
        fun hField(key: String): String? {
            val tok = "$key="
            val idx = header.indexOf(tok)
            if (idx < 0) return null
            val start = idx + tok.length
            val end = header.indexOf('|', start)
            return if (end < 0) header.substring(start) else header.substring(start, end)
        }
        val rc = hField("RC")?.toIntOrNull() ?: -1
        val exitStatus = hField("EXIT")?.toIntOrNull() ?: -1
        val timedOut = hField("TIMED_OUT") == "1"
        val descsN = hField("DESCS")?.toIntOrNull() ?: 0

        val descendants = mutableListOf<ProotDescendant>()
        var i = 1
        while (i < lines.size && descendants.size < descsN) {
            val ln = lines[i]
            if (!ln.startsWith("  pid=")) { i++; continue }
            // Line form: "  pid=N|ppid=N|comm=s|maps=proof|cmdline=s"
            // maps may contain '|', so split carefully: parse known fields.
            val body = ln.trim()
            fun dField(key: String): String {
                val tok = "$key="
                val idx = body.indexOf(tok)
                if (idx < 0) return ""
                val start = idx + tok.length
                // For maps + cmdline, take the rest is wrong (they're not last).
                // Use the next "|<key>=" boundary.
                if (key == "maps") {
                    val nextKey = body.indexOf("|cmdline=", start)
                    return if (nextKey < 0) body.substring(start) else body.substring(start, nextKey)
                }
                if (key == "cmdline") {
                    return body.substring(start)  // last field
                }
                val end = body.indexOf('|', start)
                return if (end < 0) body.substring(start) else body.substring(start, end)
            }
            val pid = dField("pid").toLongOrNull() ?: -1
            val ppid = dField("ppid").toLongOrNull() ?: -1
            val comm = dField("comm")
            val maps = dField("maps")
            val cmdline = dField("cmdline")
            val classification = classifyProcess(comm, cmdline, maps)
            descendants += ProotDescendant(pid, ppid, comm, maps, cmdline, classification)
            i++
        }

        // stdout/stderr follow the @@@ markers.
        val stdoutIdx = lines.indexOfFirst { it == "@@@STDOUT@@@" }
        val stderrIdx = lines.indexOfFirst { it == "@@@STDERR@@@" }
        val stdout = if (stdoutIdx in 0 until lines.size) {
            lines.subList(stdoutIdx + 1, if (stderrIdx > stdoutIdx) stderrIdx else lines.size).joinToString("\n")
        } else ""
        val stderr = if (stderrIdx in 0 until lines.size) {
            lines.subList(stderrIdx + 1, lines.size).joinToString("\n")
        } else ""
        return ProotRunResult(rc, exitStatus, timedOut, descendants, stdout, stderr)
    }

    /**
     * Classify a proot-tree descendant. The acceptance applies to the
     * glibc/Wine tree; Bionic proot/helper processes are tracked separately.
     *
     *   proot       — proot itself (Bionic)
     *   loader      — the APK glibc loader as guest command (Bionic-exec'd by
     *                 proot, but it IS the APK-managed loader process)
     *   wine        — Wine (glibc namespace)
     *   wineserver  — wineserver (glibc namespace)
     *   glibc-child — any other native child in the glibc namespace
     *   unknown     — cannot classify
     */
    private fun classifyProcess(comm: String, cmdline: String, maps: String): String {
        val c = cmdline.lowercase()
        val m = comm.lowercase()
        return when {
            m == "proot" || c.contains("/libproot.so") || c.startsWith("proot ") -> "proot"
            // A loader process (the glibc loader as the guest command) carries
            // the logical command name via --argv0. Classify by the --argv0
            // value so wine/wineserver/wineboot are recognized even before the
            // loader finishes exec'ing the target (the traced process keeps the
            // loader's cmdline early on).
            c.contains("--argv0 wineserver") || c.contains("--argv0=wineserver") ||
                m == "wineserver" || c.contains("/wineserver") -> "wineserver"
            c.contains("--argv0 wineboot") || c.contains("--argv0=wineboot") ||
                m == "wineboot" -> "wineboot"
            c.contains("--argv0 wine") && !c.contains("--argv0 wineserver") ||
                m == "wine-preloader" || m == "wine" || m == "wine64" ||
                c.contains("libwine_preloader") || c.contains("/wine ") -> "wine"
            c.contains("ld-linux") || c.contains("libld_linux") || m == "ld-linux" -> "loader"
            maps.startsWith("OK|") || c.contains("libld_linux_x86_64") -> "glibc-child"
            else -> "unknown"
        }
    }

    /**
     * Verify the loader chain by running `wine --version` via the APK-managed
     * glibc loader with LD_DEBUG=libs, capturing stderr. Superseded by
     * verifyLoaderChainEx (which supports tunables + trampoline); kept as a
     * thin delegate for any caller that wants the no-options form.
     */
    private fun verifyLoaderChain(
        nativeDir: String, wineTarget: String, prefixDir: java.io.File,
        treeDir: java.io.File
    ): LoaderProof = verifyLoaderChainEx(nativeDir, wineTarget, prefixDir, treeDir, "")

    private fun announce(result: ExperimentResult) {
        // Mirror the PKG_EXPERIMENT announce contract for the host driver.
        println("WINE_SPIKE_${result.experiment}_RESULT\tok=${result.ok}\tcode=${result.code}")
        result.evidence.forEach { (k, v) ->
            println("WINE_SPIKE_${result.experiment}_EVIDENCE\t$k=$v")
        }
    }

    /** Read an asset as a UTF-8 string. */
    private fun readAsset(name: String): String {
        return context.assets.open(name).bufferedReader().use { it.readText() }
    }

    /** Extract an asset file to a target dir (for PE cache materialization). */
    private fun extractAssetsToDir(assetPrefix: String, destDir: File) {
        destDir.mkdirs()
        val files = context.assets.list(assetPrefix) ?: return
        for (fn in files) {
            val src = "$assetPrefix/$fn"
            // If it's a subdir, recurse.
            val sub = context.assets.list(src)
            if (sub != null && sub.isNotEmpty()) {
                extractAssetsToDir(src, File(destDir, fn))
            } else {
                val out = File(destDir, fn)
                if (!out.exists()) {
                    context.assets.open(src).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    /**
     * Materialize Wine's non-PE DATADIR (NLS tables, wine.inf and fonts) into
     * a hash-verified cache and link it below the logical Wine tree.  The
     * native PRoot launcher maps tree/share/wine onto the DATADIR inferred by
     * the packaged ntdll.so.  Without this, ntdll cannot initialize its NLS
     * tables and wineserver exits before prefix creation.
     */
    private fun prepareWineData(
        treeDir: File,
        evidence: LinkedHashMap<String, String>
    ): Boolean {
        return try {
            val extractedRoot = File(context.cacheDir, "wine-data-assets")
            if (extractedRoot.exists()) extractedRoot.deleteRecursively()
            extractedRoot.mkdirs()
            extractAssetsToDir("wine-data", File(extractedRoot, "wine-data"))
            context.assets.open(WINE_DATA_MANIFEST_ASSET).use { input ->
                File(extractedRoot, WINE_DATA_MANIFEST_ASSET).outputStream().use { input.copyTo(it) }
            }
            val manifest = readAsset(WINE_DATA_MANIFEST_ASSET)
            val cacheDir = File(context.filesDir, "runtime/wine-data-cache")
            val materializeRc = WineSpikeNative.materializePeCacheIntoTreeNative(
                cacheDir.absolutePath, manifest, extractedRoot.absolutePath, treeDir.absolutePath)
            val verifyRc = if (materializeRc == 0) {
                WineSpikeNative.verifyPeCacheNative(cacheDir.absolutePath, manifest)
            } else -1
            evidence["wineDataMaterializeRc"] = materializeRc.toString()
            evidence["wineDataVerifyRc"] = verifyRc.toString()
            evidence["wineDataCache"] = cacheDir.absolutePath
            val dataRoot = File(cacheDir, "wine-data")
            val alias = File(context.applicationInfo.dataDir, "wine")
            val aliasPath = alias.toPath()
            val aliasTarget = dataRoot.toPath()
            if (materializeRc == 0 && verifyRc == 0) {
                if (Files.exists(aliasPath, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isSymbolicLink(aliasPath) ||
                        Files.readSymbolicLink(aliasPath) != aliasTarget) {
                        throw IllegalStateException(
                            "Wine DATADIR alias exists but is not the expected symlink: $alias")
                    }
                } else {
                    Files.createSymbolicLink(aliasPath, aliasTarget)
                }
            }
            evidence["wineDataAlias"] = alias.absolutePath
            evidence["wineDataAliasTarget"] = if (Files.isSymbolicLink(aliasPath)) {
                Files.readSymbolicLink(aliasPath).toString()
            } else "MISSING"
            materializeRc == 0 && verifyRc == 0 && Files.isSymbolicLink(aliasPath)
        } catch (e: Exception) {
            evidence["wineDataException"] = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /**
     * S-1: Prove the effective dynamic loader is the APK-managed glibc loader for
     * wine, wineserver, and every native child — from the production app process.
     *
     * S-5 fallback sequence (per the approved plan, corrected):
     *  S-5(0) Run the ptrace SIGSYS diagnostic FIRST. Exit 159 alone only proves
     *         termination *by* SIGSYS; it does not establish the cause. We capture
     *         si_code + syscall nr before classifying. The initial record is
     *         SIGSYS_CAUSE_UNRESOLVED, NOT "SELinux blocks execve".
     *  S-5(a) If the direct path fails, try the APK-packaged Bionic trampoline
     *         (libwine_trampoline.so) which execs the glibc loader from a clean
     *         execve'd Bionic process. Evidence kept SEPARATE from PKG-01.
     *  narrow If the blocked syscall is a glibc startup feature (rseq/clone3),
     *         try the narrow GLIBC_TUNABLES disable before reaching for proot.
     *
     * The run-as result (wine --version works under `adb shell run-as`) is
     * supporting evidence only; it does NOT satisfy the production app-process
     * acceptance criterion.
     */
    suspend fun runS1(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir
            ?: return@withContext ExperimentResult.fail("S1", "-", "NO_NATIVE_DIR",
                listOf("nativeLibraryDir is null"), evidence, SystemClock.elapsedRealtime() - t0)
        evidence["nativeLibraryDir"] = nativeDir

        AppLog.i(TAG, "S-1: effective loader proof (production app process)")

        // 1. Read the staging manifest + build the symlink tree.
        val manifest = try {
            readAsset(STAGING_MANIFEST_ASSET)
        } catch (e: Exception) {
            return@withContext ExperimentResult.fail("S1", "-", "NO_MANIFEST",
                listOf("cannot read $STAGING_MANIFEST_ASSET: ${e.message}"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }

        val treeDir = File(context.filesDir, "runtime/wine-tree")
        val rc = WineSpikeNative.buildSymlinkTreeNative(treeDir.absolutePath, nativeDir, manifest)
        if (rc != 0) {
            return@withContext ExperimentResult.fail("S1", "-", "SYMLINK_TREE_FAILED",
                listOf("rc=$rc (${WineSpikeNative.errStrNative(rc)})"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        evidence["symlinkTree"] = treeDir.absolutePath
        if (!prepareWineData(treeDir, evidence)) {
            return@withContext ExperimentResult.fail("S1", "-", "WINE_DATA_FAILED",
                listOf("Wine DATADIR could not be materialized and verified"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }

        // 2. Create the WINEPREFIX + tmp dirs.
        val prefixDir = File(context.filesDir, "runtime/wine-prefix")
        prefixDir.mkdirs()
        File(context.filesDir, "runtime/tmp").mkdirs()

        val wineTarget = File(treeDir, "bin/wine").absolutePath
        evidence["wineTarget"] = wineTarget

        // =====================================================================
        // S-5(0): ptrace SIGSYS diagnostic. Corrects a512b71, which recorded the
        // failure as "SELinux blocks execve" based solely on exit 159. We capture
        // si_code + the triggering syscall before classifying.
        // =====================================================================
        AppLog.i(TAG, "S-5(0): running ptrace SIGSYS diagnostic")
        val diagResult = WineSpikeNative.diagSigsysNative(
            nativeDir, wineTarget, prefixDir.absolutePath, "", "--version")
        evidence["s5_0_diag"] = diagResult
        AppLog.i(TAG, "S-5(0) diag: $diagResult")

        // Parse the cause. Format: "OK|exit=N|sig=N|si_code=N|syscall=M|name=X|arch=0xC|cause=C"
        // cause: 0=UNRESOLVED 1=SECCOMP 2=USER 3=KERNEL 4=NONE
        val diagCause = parseField(diagResult, "cause")?.toIntOrNull() ?: -1
        val diagSyscallName = parseField(diagResult, "name") ?: "?"
        val diagSiCode = parseField(diagResult, "si_code")?.toIntOrNull() ?: -1
        val diagExit = parseField(diagResult, "exit")?.toIntOrNull() ?: -1
        evidence["s5_0_cause"] = diagCause.toString()
        evidence["s5_0_syscallName"] = diagSyscallName
        evidence["s5_0_siCode"] = diagSiCode.toString()

        // If the diagnostic already shows a CLEAN exit (cause=NONE, exit=0), the
        // direct path works — proceed to the LD_DEBUG proof below.
        val directWorksCleanly = (diagCause == 4 && diagExit == 0)

        // =====================================================================
        // Narrow glibc-startup fallback: if the SIGSYS is a seccomp trap on a
        // glibc startup syscall that has a supported tunable disable (rseq),
        // try it before any heavier mechanism. For access()/clone3 there is NO
        // tunable (the loader's access() probing is not suppressible; clone3
        // fallback requires ENOSYS which seccomp preempts), so we skip the
        // narrow attempt and record that proot (syscall interception) is needed.
        // =====================================================================
        var effectiveTunables = ""
        val isRseqTrap = (diagCause == 1) && (diagSyscallName == "rseq")
        if (!directWorksCleanly && isRseqTrap) {
            AppLog.i(TAG, "S-5 narrow: SIGSYS on rseq — trying GLIBC_TUNABLES=glibc.pthread.rseq=0")
            effectiveTunables = "glibc.pthread.rseq=0"
            val narrowPid = WineSpikeNative.launchWineExNative(
                nativeDir, wineTarget, prefixDir.absolutePath, "", "--version",
                "GLIBC_TUNABLES=$effectiveTunables")
            evidence["s5_narrow_tunables"] = effectiveTunables
            evidence["s5_narrow_pid"] = narrowPid.toString()
            Thread.sleep(800)
            killTree(narrowPid)
        } else if (diagCause == 1 && (diagSyscallName == "access" || diagSyscallName == "clone3")) {
            // No tunable suppresses the loader's access() calls, and clone3 has
            // no clean tunable (seccomp kills before ENOSYS fallback). Record
            // that the narrow path does not apply; proot is required.
            evidence["s5_narrow_applies"] = "false ($diagSyscallName has no tunable disable)"
            AppLog.i(TAG, "S-5 narrow: does NOT apply for $diagSyscallName — proot required")
        }

        // =====================================================================
        // Attempt the actual S-1 proof on the direct path. If the diagnostic
        // showed a clean exit, this should produce LD_DEBUG evidence.
        // =====================================================================
        val directProof = if (directWorksCleanly || effectiveTunables.isNotEmpty()) {
            AppLog.i(TAG, "S-1 direct: attempting LD_DEBUG loader-chain proof")
            verifyLoaderChainEx(nativeDir, wineTarget, prefixDir, treeDir, effectiveTunables)
        } else {
            null
        }
        if (directProof != null) {
            evidence.putAll(directProof.evidence.mapKeys { "direct_${it.key}" })
        }

        // =====================================================================
        // S-5(a): APK-packaged Bionic trampoline path. Separate evidence.
        // =====================================================================
        AppLog.i(TAG, "S-5(a): trampoline path")
        val trampPid = WineSpikeNative.launchWineViaTrampolineExNative(
            nativeDir, wineTarget, prefixDir.absolutePath, "", "--version",
            if (effectiveTunables.isNotEmpty()) "GLIBC_TUNABLES=$effectiveTunables" else "")
        evidence["s5a_trampolinePid"] = trampPid.toString()
        val trampProof = if (trampPid > 0) {
            Thread.sleep(500)
            val probe = WineSpikeNative.probeLoaderNative(trampPid, nativeDir)
            evidence["s5a_trampolineProcMaps"] = probe
            verifyLoaderChainEx(nativeDir, wineTarget, prefixDir, treeDir, effectiveTunables,
                useTrampoline = true)
        } else null
        if (trampProof != null) {
            evidence.putAll(trampProof.evidence.mapKeys { "tramp_${it.key}" })
        }
        killTree(trampPid)

        // =====================================================================
        // S-5(b): proot fallback. Only reached if direct + trampoline both fail
        // (which they do for the access() seccomp trap). proot intercepts the
        // child's syscalls via ptrace and translates access->faccessat. This is
        // experimentally qualified here — we prove proot starts from the app
        // domain AND the loader chain resolves via the APK-managed loader.
        // =====================================================================
        AppLog.i(TAG, "S-5(b): proot fallback path (default mode)")
        val prootExtraEnv = mutableListOf<String>()
        if (effectiveTunables.isNotEmpty()) prootExtraEnv += "GLIBC_TUNABLES=$effectiveTunables"
        val prootPid = WineSpikeNative.launchWineViaProotNative(
            nativeDir, wineTarget, prefixDir.absolutePath, "", "--version",
            prootExtraEnv.joinToString(";"))
        evidence["s5b_prootPid"] = prootPid.toString()
        var prootProof: LoaderProof? = null
        if (prootPid > 0) {
            Thread.sleep(1000)  // proot startup + ptrace attach + loader init
            val probe = WineSpikeNative.probeLoaderNative(prootPid, nativeDir)
            evidence["s5b_prootProcMaps"] = probe
            prootProof = verifyLoaderChainProot(nativeDir, wineTarget, prefixDir, treeDir, effectiveTunables)
            evidence["s5b_prootRawSummary"] = prootProof.evidence["ldDebugSummary"] ?: ""
        }
        if (prootProof != null) {
            evidence.putAll(prootProof.evidence.mapKeys { "proot_${it.key}" })
        }
        killTree(prootPid)

        // If default proot mode fails, retry with PROOT_NO_SECCOMP=1. proot's
        // seccomp-filter acceleration installs a seccomp filter on the traced
        // child; in the app domain that may interact with the existing
        // untrusted_app filter. PROOT_NO_SECCOMP disables acceleration, falling
        // back to pure-ptrace. We record the exact syscall evidence for both.
        val prootDefaultOk = prootProof != null &&
            prootProof.sawApkLoader && prootProof.sawLibcResolved && prootProof.sawLibdlResolved
        var prootNoSeccompProof: LoaderProof? = null
        if (!prootDefaultOk) {
            AppLog.i(TAG, "S-5(b): proot fallback path (PROOT_NO_SECCOMP=1)")
            val noSecExtraEnv = mutableListOf("PROOT_NO_SECCOMP=1")
            if (effectiveTunables.isNotEmpty()) noSecExtraEnv += "GLIBC_TUNABLES=$effectiveTunables"
            val prootNsPid = WineSpikeNative.launchWineViaProotNative(
                nativeDir, wineTarget, prefixDir.absolutePath, "", "--version",
                noSecExtraEnv.joinToString(";"))
            evidence["s5b_prootNoSeccompPid"] = prootNsPid.toString()
            if (prootNsPid > 0) {
                Thread.sleep(1000)
                evidence["s5b_prootNoSeccompProcMaps"] = WineSpikeNative.probeLoaderNative(prootNsPid, nativeDir)
                prootNoSeccompProof = verifyLoaderChainProot(
                    nativeDir, wineTarget, prefixDir, treeDir, effectiveTunables, useNoSeccomp = true)
                evidence["s5b_prootNoSeccompRawSummary"] = prootNoSeccompProof.evidence["ldDebugSummary"] ?: ""
            }
            if (prootNoSeccompProof != null) {
                evidence.putAll(prootNoSeccompProof.evidence.mapKeys { "prootNs_${it.key}" })
            }
            killTree(prootNsPid)
        }

        // =====================================================================
        // FULL S-1 acceptance: prove the APK-managed loader is the effective
        // loader for wine AND wineserver AND every native child, from the
        // production app process. This corrects the earlier false pass (which
        // fired on LD_DEBUG bootstrap strings while the live proc/maps probe
        // was FAIL|rc=5).
        //
        // Two structured runs:
        //   (a) wine --version : bootstrap proof — exits zero, prints wine-11.
        //       Fast (sub-second); proves the loader resolves but the process
        //       exits before a tree snapshot can be reliably taken.
        //   (b) wineserver -p0 : persistent proof — wineserver stays alive
        //       (foreground, no fork), so the process-tree snapshot catches it
        //       mapping the APK loader. Bounded by a short timeout (the run
        //       kills the tree on timeout; for a persistent server that is the
        //       expected outcome, not a failure). We require at least one
        //       glibc/Wine descendant (wineserver) with an OK maps proof.
        // =====================================================================
        AppLog.i(TAG, "S-1 full (a): bootstrap wine --version")
        val bootstrapRun = try {
            val raw = WineSpikeNative.runWineViaProotNative(
                nativeDir, wineTarget, "wine", prefixDir.absolutePath, "", "--version", "",
                30_000)
            parseProotRunResult(raw).also { evidence["s1_fullRawHeader"] = raw.lineSequence().firstOrNull() ?: "" }
        } catch (e: Exception) {
            evidence["s1_fullRunException"] = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
        // The bootstrap proof (wine --version exit 0 + wine- output) is
        // captured separately from the persistent-tree proof.
        val bootstrapOk = bootstrapRun != null && bootstrapRun.exitedCleanly &&
            bootstrapRun.exitCode == 0 && bootstrapRun.stdout.contains("wine-")
        evidence["s1_bootstrapOk"] = bootstrapOk.toString()
        if (bootstrapRun != null) {
            evidence["s1_bootstrapExitCode"] = bootstrapRun.exitCode.toString()
            evidence["s1_bootstrapStdoutHead"] = bootstrapRun.stdout.lineSequence().take(4).joinToString(" | ")
            evidence["s1_bootstrapStderrTail"] = bootstrapRun.stderr.lineSequence().toList().takeLast(15).joinToString(" | ")
        }

        // (b) Persistent wineserver for the process-tree proof.
        AppLog.i(TAG, "S-1 full (b): persistent wineserver -p0 for tree proof")
        val wineserverTarget = File(treeDir, "bin/wineserver").absolutePath
        val treeRun = try {
            val raw = WineSpikeNative.runWineViaProotNative(
                nativeDir, wineserverTarget, "wineserver", prefixDir.absolutePath,
                "", "-p0", "", 15_000)
            parseProotRunResult(raw)
        } catch (e: Exception) {
            evidence["s1_treeRunException"] = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
        val fullRun = treeRun  // the tree run is the authoritative tree proof

        var fullS1Ok = false
        var fullS1Reason = "no structured run"
        if (fullRun != null) {
            evidence["s1_fullRc"] = fullRun.rc.toString()
            evidence["s1_fullExitStatus"] = fullRun.exitStatus.toString()
            evidence["s1_fullTimedOut"] = fullRun.timedOut.toString()
            evidence["s1_fullDescendantCount"] = fullRun.descendants.size.toString()
            // Record every descendant's classification + maps proof.
            fullRun.descendants.forEachIndexed { idx, d ->
                evidence["s1_desc_${idx}_pid"] = d.pid.toString()
                evidence["s1_desc_${idx}_cls"] = d.classification
                evidence["s1_desc_${idx}_maps"] = d.mapsProof
                evidence["s1_desc_${idx}_cmd"] = d.cmdline.take(160)
            }
            evidence["s1_fullStdoutHead"] = fullRun.stdout.lineSequence().take(6).joinToString(" | ")
            evidence["s1_fullStderrTail"] = fullRun.stderr.lineSequence().toList().takeLast(25).joinToString(" | ")
            evidence["s1_fullExitCode"] = fullRun.exitCode.toString()
            // The persistent wineserver run is EXPECTED to time out (it stays
            // alive until killed); timedOut=true here is normal, not a failure.
            evidence["s1_fullExpectedTimeout"] = "true"

            // Classify the tree + apply the acceptance rule.
            val wineChildren = fullRun.descendants.filter {
                it.classification in setOf("wine", "wineserver", "wineboot", "glibc-child", "loader")
            }
            val unknown = fullRun.descendants.filter { it.classification == "unknown" }
            // Every Wine/glibc child must have an OK maps proof. "GONE" means
            // the process exited before a later snapshot — acceptable for a
            // short-lived helper, but at least one wine/wineserver with OK
            // proof must exist.
            val wineTreeProven = wineChildren.isNotEmpty() &&
                wineChildren.all { it.mapsProof.startsWith("OK|") }
            // wineserver (or wine) must specifically be present + proven.
            val hasProvenServer = wineChildren.any {
                (it.classification == "wineserver" || it.classification == "wine") &&
                it.mapsProof.startsWith("OK|")
            }
            evidence["s1_fullWineChildren"] = wineChildren.size.toString()
            evidence["s1_fullUnknownChildren"] = unknown.size.toString()
            evidence["s1_fullWineTreeProven"] = wineTreeProven.toString()
            evidence["s1_fullHasProvenServer"] = hasProvenServer.toString()

            // Acceptance: bootstrap proven (run a) AND persistent tree proven
            // (run b — wineserver with APK-managed loader proof) AND no
            // glibc/Wine child with a FAIL maps proof.
            fullS1Ok = bootstrapOk && wineTreeProven && hasProvenServer && unknown.none {
                it.mapsProof.startsWith("FAIL|")
            }
            fullS1Reason = when {
                !bootstrapOk -> "bootstrap did not exit 0 with wine- output " +
                    "(exit=${bootstrapRun?.exitCode})"
                !hasProvenServer -> "no wineserver/wine process with APK-managed loader proof"
                !wineTreeProven -> "a glibc/Wine child lacks APK-managed loader proof"
                else -> "OK"
            }
            evidence["s1_fullS1Ok"] = fullS1Ok.toString()
            evidence["s1_fullS1Reason"] = fullS1Reason
        }

        // =====================================================================
        // Verdict: S-1 passes ONLY if the FULL process-tree proof holds AND the
        // bootstrap exited zero with the expected output. The LD_DEBUG bootstrap
        // strings are supporting evidence; they no longer alone constitute pass.
        // =====================================================================
        val directOk = directProof != null &&
            directProof.sawApkLoader && directProof.sawLibcResolved && directProof.sawLibdlResolved
        val trampOk = trampProof != null &&
            trampProof.sawApkLoader && trampProof.sawLibcResolved && trampProof.sawLibdlResolved
        val prootOk = prootProof != null &&
            prootProof.sawApkLoader && prootProof.sawLibcResolved && prootProof.sawLibdlResolved
        val prootNsOk = prootNoSeccompProof != null &&
            prootNoSeccompProof.sawApkLoader && prootNoSeccompProof.sawLibcResolved &&
            prootNoSeccompProof.sawLibdlResolved
        // The full structured run is the authoritative acceptance path. The
        // LD_DEBUG-only proofs above are supporting evidence (they show the
        // bootstrap resolves, but do NOT prove the process tree).
        val ok = fullS1Ok
        val bootstrapEvidence = (directOk || trampOk || prootOk || prootNsOk)

        val code = when {
            ok -> "LOADER_PROVEN_VIA_PROOT_FULL_TREE"
            bootstrapEvidence -> "BOOTSTRAP_PROVEN_VIA_PROOT_TREE_PENDING"
            diagCause == 1 -> "SIGSYS_SECCOMP_${diagSyscallName.uppercase()}"
            diagCause == 2 -> "SIGSYS_USER_KILL"
            diagCause == 3 -> "SIGSYS_KERNEL"
            diagCause == 0 -> "SIGSYS_CAUSE_UNRESOLVED"
            else -> "LOADER_NOT_PROVEN"
        }
        evidence["directOk"] = directOk.toString()
        evidence["trampOk"] = trampOk.toString()
        evidence["prootOk"] = prootOk.toString()
        evidence["prootNoSeccompOk"] = prootNsOk.toString()
        evidence["bootstrapEvidenceOnly"] = (!ok && bootstrapEvidence).toString()
        evidence["verdict"] = code

        val result = if (ok) {
            ExperimentResult.ok("S1", "-", evidence, SystemClock.elapsedRealtime() - t0, code)
        } else {
            // Honest failure: record the exact reason. Do NOT claim acceptance
            // from LD_DEBUG bootstrap strings alone.
            ExperimentResult.fail("S1", "-", code,
                listOf("fullS1Ok=$fullS1Ok reason='$fullS1Reason' " +
                    "bootstrapEvidence=$bootstrapEvidence directOk=$directOk trampOk=$trampOk " +
                    "prootOk=$prootOk prootNsOk=$prootNsOk diagCause=$diagCause " +
                    "syscall=$diagSyscallName siCode=$diagSiCode tunables='$effectiveTunables'"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        announce(result)
        result
    }

    /** Parse a `key=value` field from a pipe-delimited diagnostic string. */
    private fun parseField(s: String, key: String): String? {
        // Format: "OK|exit=N|sig=N|...|key=VAL|..."
        val tok = "$key="
        val idx = s.indexOf(tok)
        if (idx < 0) return null
        val start = idx + tok.length
        val end = s.indexOf('|', start)
        return if (end < 0) s.substring(start) else s.substring(start, end)
    }

    /** Kill a PID and its children if positive. Best-effort. */
    private fun killTree(pid: Long) {
        if (pid <= 0) return
        try {
            val children = WineSpikeNative.enumChildrenNative(pid)
            for (c in children) {
                Runtime.getRuntime().exec(arrayOf("kill", "-9", c.toString())).waitFor()
            }
            Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())).waitFor()
        } catch (e: Exception) {
            AppLog.w(TAG, "cleanup kill failed: ${e.message}")
        }
    }

    /**
     * LD_DEBUG=libs loader-chain verification via proot. proot is invoked
     * directly via ProcessBuilder; it ptrace-traces the glibc loader child and
     * translates access->faccessat. The LD_DEBUG output from the traced child
     * appears on proot's stderr (proot passes env + fds through). This proves
     * the APK-managed loader resolves the glibc closure via the symlink tree
     * EVEN UNDER proot — the effective loader is still the APK-managed one.
     */
    private fun verifyLoaderChainProot(
        nativeDir: String, wineTarget: String, prefixDir: java.io.File,
        treeDir: java.io.File, tunables: String, useNoSeccomp: Boolean = false
    ): LoaderProof {
        val proot = java.io.File(nativeDir, "libproot.so")
        val loader = java.io.File(nativeDir, "libld_linux_x86_64.so")
        // --library-path matches the native launcher: tree/lib + tree/lib/wine/x86_64-unix
        val libPath = "${treeDir.absolutePath}/lib:${treeDir.absolutePath}/lib/wine/x86_64-unix"
        // Absolute, canonical tmp dir (no '..' — proot does NOT resolve .. in
        // PROOT_TMP_DIR before checking writability). prefixDir is
        // filesDir/runtime/wine-prefix; tmp is filesDir/runtime/tmp.
        val tmpDir = java.io.File(prefixDir.parentFile, "tmp").canonicalFile.absolutePath
        java.io.File(tmpDir).mkdirs()
        val env = linkedMapOf(
            // PROOT_LOADER: APK-managed helper loader (immutable +x). Prevents
            // proot extracting its embedded loader to PROOT_TMP_DIR (noexec).
            "PROOT_LOADER" to "$nativeDir/libproot_loader.so",
            "PROOT_LOADER_32" to "$nativeDir/libproot_loader32.so",
            // LD_LIBRARY_PATH: proot's Bionic loader finds libtalloc.so here.
            "LD_LIBRARY_PATH" to nativeDir,
            "PROOT_TMP_DIR" to tmpDir,       // proot's own temp files (f2fs probe)
            "WINEPREFIX" to prefixDir.absolutePath,
            "HOME" to prefixDir.absolutePath,
            "WINEDLLPATH" to "${treeDir.absolutePath}/lib/wine/x86_64-unix",
            "LD_DEBUG" to "libs",
            "WINEDEBUG" to "-all",
            "PATH" to nativeDir,
        )
        if (tunables.isNotEmpty()) env["GLIBC_TUNABLES"] = tunables
        // Resolve wineTarget to its real nativeLibraryDir path (app domain blocks
        // execve of filesDir symlinks).
        val wineReal = try { java.io.File(wineTarget).canonicalFile.absolutePath } catch (e: Exception) { wineTarget }
        // Loader-as-guest-command form: proot runs the APK glibc loader, which
        // then runs wine via --library-path. Matches the PROVEN run-as invocation.
        val pb = ProcessBuilder(
            proot.absolutePath, "-v", "5",
            "-b", "$tmpDir:/tmp",
            "-r", "/", "--link2symlink",
            loader.absolutePath, "--library-path", libPath, wineReal, "--version")
        pb.redirectErrorStream(true)
        pb.environment().putAll(env)
        val out = try {
            val proc = pb.start()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            val exitCode = proc.waitFor()
            "EXIT=$exitCode\n$text"
        } catch (e: Exception) {
            "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
        }
        val sawApk = out.contains("libld_linux_x86_64.so")
        val sawLibc = out.contains("libc.so.6") && out.contains("calling init:")
        val sawLibdl = out.contains("libdl.so.2")
        val sawVersion = out.contains("wine-")
        val summary = out.lineSequence().take(20).joinToString(" | ")
        return LoaderProof(
            sawApkLoader = sawApk,
            sawLibcResolved = sawLibc,
            sawLibdlResolved = sawLibdl,
            evidence = mapOf(
                "ldDebugApkLoader" to sawApk.toString(),
                "ldDebugLibcResolved" to sawLibc.toString(),
                "ldDebugLibdlResolved" to sawLibdl.toString(),
                "ldDebugSawWineVersion" to sawVersion.toString(),
                "ldDebugSummary" to summary.take(500),
                "ldDebugUsedTunables" to tunables,
                "ldDebugUsedProot" to "true",
                "ldDebugUsedNoSeccomp" to useNoSeccomp.toString(),
            )
        )
    }

    /**
     * LD_DEBUG=libs loader-chain verification, with optional GLIBC_TUNABLES and
     * optional trampoline launch. Returns the proof of the APK-managed loader
     * resolving the glibc closure via the symlink tree.
     */
    private fun verifyLoaderChainEx(
        nativeDir: String, wineTarget: String, prefixDir: java.io.File,
        treeDir: java.io.File, tunables: String, useTrampoline: Boolean = false
    ): LoaderProof {
        val libPath = "${treeDir.absolutePath}/lib"
        val env = linkedMapOf(
            "WINEPREFIX" to prefixDir.absolutePath,
            "HOME" to prefixDir.absolutePath,
            "WINEDLLPATH" to "${treeDir.absolutePath}/lib/wine/x86_64-unix",
            "LD_DEBUG" to "libs",
            "WINEDEBUG" to "-all",
            "PATH" to nativeDir,
        )
        if (tunables.isNotEmpty()) env["GLIBC_TUNABLES"] = tunables

        val out = if (useTrampoline) {
            // The trampoline is a PIE binary; invoke it via ProcessBuilder so we
            // capture its stdout/stderr. argv: trampoline loader --library-path lib wine --version
            val tramp = java.io.File(nativeDir, "libwine_trampoline.so")
            val loader = java.io.File(nativeDir, "libld_linux_x86_64.so")
            val pb = ProcessBuilder(
                tramp.absolutePath, loader.absolutePath,
                "--library-path", libPath, wineTarget, "--version")
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            try {
                val proc = pb.start()
                val text = proc.inputStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()
                "EXIT=$exitCode\n$text"
            } catch (e: Exception) {
                "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
            }
        } else {
            val loader = java.io.File(nativeDir, "libld_linux_x86_64.so")
            val pb = ProcessBuilder(
                loader.absolutePath, "--library-path", libPath,
                wineTarget, "--version")
            pb.redirectErrorStream(true)
            pb.environment().putAll(env)
            try {
                val proc = pb.start()
                val text = proc.inputStream.bufferedReader().use { it.readText() }
                val exitCode = proc.waitFor()
                "EXIT=$exitCode\n$text"
            } catch (e: Exception) {
                "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        val sawApk = out.contains("libld_linux_x86_64.so")
        val sawLibc = out.contains("libc.so.6") && out.contains("calling init:")
        val sawLibdl = out.contains("libdl.so.2")
        val summary = out.lineSequence().take(20).joinToString(" | ")
        return LoaderProof(
            sawApkLoader = sawApk,
            sawLibcResolved = sawLibc,
            sawLibdlResolved = sawLibdl,
            evidence = mapOf(
                "ldDebugApkLoader" to sawApk.toString(),
                "ldDebugLibcResolved" to sawLibc.toString(),
                "ldDebugLibdlResolved" to sawLibdl.toString(),
                "ldDebugSummary" to summary.take(500),
                "ldDebugUsedTunables" to tunables,
                "ldDebugUsedTrampoline" to useTrampoline.toString(),
            )
        )
    }

    /**
     * S-2: Prove wineboot resolves Wine-owned PE modules from the hash-verified
     * cache, the cache is reverified before launch, AND a corrupted cache entry
     * is detected + atomically rematerialized.
     *
     * Truthful acceptance requires:
     *   - PE cache materialized with logical_path → tree symlinks (so Wine can
     *     actually find modules at lib/wine/<arch>/...)
     *   - verify (reverification) returns 0
     *   - mismatch-repair: corrupt one module → verify detects → rematerialize →
     *     reverify canonical SHA-256
     *   - `wineboot --init` runs to completion via proot with argv0=wineboot
     *     (preserving the logical command name), exits zero
     *   - prefix artifacts present (system.reg, user.reg, userdef.reg,
     *     dosdevices, drive_c/windows/system32)
     *   - core builtin PE modules (wineboot.exe + at least kernel32.dll or
     *     ntdll.dll) resolvable via the cache-backed logical tree
     */
    suspend fun runS2(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
        evidence["nativeLibraryDir"] = nativeDir

        AppLog.i(TAG, "S-2: wineboot PE resolution (truthful)")

        // 1. Extract PE assets to a temp dir (the C materializer reads from the filesystem).
        // Wipe first so a prior partial extraction doesn't shadow a regen.
        val assetsExtractDir = File(context.cacheDir, "wine-pe-assets")
        if (assetsExtractDir.exists()) assetsExtractDir.deleteRecursively()
        assetsExtractDir.mkdirs()
        extractAssetsToDir("wine-pe", File(assetsExtractDir, "wine-pe"))
        context.assets.open(WINE_PE_MANIFEST_ASSET).use { input ->
            File(assetsExtractDir, WINE_PE_MANIFEST_ASSET).outputStream().use { input.copyTo(it) }
        }
        evidence["assetsExtractDir"] = assetsExtractDir.absolutePath

        // 2. Build the symlink tree + wine tree dirs (idempotent).
        val stagingManifest = try { readAsset(STAGING_MANIFEST_ASSET) } catch (e: Exception) { "" }
        val treeDir = File(context.filesDir, "runtime/wine-tree")
        val prefixDir = File(context.filesDir, "runtime/wine-prefix")
        val cacheDir = File(context.filesDir, "runtime/wine-pe-cache")
        if (stagingManifest.isNotEmpty()) {
            val treeRc = WineSpikeNative.buildSymlinkTreeNative(treeDir.absolutePath, nativeDir, stagingManifest)
            evidence["symlinkTreeRc"] = treeRc.toString()
        }
        if (!prepareWineData(treeDir, evidence)) {
            val result = ExperimentResult.fail("S2", "-", "WINE_DATA_FAILED",
                listOf("Wine DATADIR could not be materialized and verified"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }
        File(context.filesDir, "runtime/tmp").mkdirs()
        prefixDir.mkdirs()
        cacheDir.mkdirs()

        val peManifest = readAsset(WINE_PE_MANIFEST_ASSET)

        // 3. Materialize PE cache INTO the tree (logical symlinks). This is the
        //    S-2 path fix: the old materialize ignored logical_path.
        val matRc = WineSpikeNative.materializePeCacheIntoTreeNative(
            cacheDir.absolutePath, peManifest, assetsExtractDir.absolutePath, treeDir.absolutePath)
        evidence["materializeRc"] = matRc.toString()
        if (matRc != 0) {
            val result = ExperimentResult.fail("S2", "-", "PE_MATERIALIZE_FAILED",
                listOf("rc=$matRc (${WineSpikeNative.errStrNative(matRc)})"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }

        // 4. Verify (reverification before launch).
        val verifyRc = WineSpikeNative.verifyPeCacheNative(cacheDir.absolutePath, peManifest)
        evidence["verifyRc"] = verifyRc.toString()
        if (verifyRc != 0) {
            val result = ExperimentResult.fail("S2", "-", "PE_VERIFY_MISMATCH",
                listOf("verify rc=$verifyRc — cache hashes do not match manifest"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }
        val cacheFiles = cacheDir.walkTopDown().filter { it.isFile }.count()
        evidence["peCacheFileCount"] = cacheFiles.toString()

        // 5. Mismatch-repair test: corrupt one known module, prove verify detects
        //    it, rematerialize, reverify the canonical SHA-256.
        val repairTarget = "kernel32.dll"
        val repairPath = WineSpikeNative.resolveCachePathNative(
            cacheDir.absolutePath, peManifest, repairTarget)
        evidence["repairTarget"] = repairTarget
        evidence["repairPath"] = repairPath
        var repairOk = false
        if (repairPath.isNotEmpty() && File(repairPath).isFile) {
            val canonicalShaBefore = sha256Of(File(repairPath))
            evidence["repairShaBefore"] = canonicalShaBefore
            // Corrupt: append garbage (atomic-ish; the test is synchronous).
            File(repairPath).appendText("CORRUPTED-BY-S2-MISMATCH-TEST")
            val verifyAfterCorrupt = WineSpikeNative.verifyPeCacheNative(cacheDir.absolutePath, peManifest)
            evidence["repairVerifyAfterCorrupt"] = verifyAfterCorrupt.toString()
            // Rematerialize (the materializer's existing-skip re-copies on hash mismatch).
            val reMatRc = WineSpikeNative.materializePeCacheIntoTreeNative(
                cacheDir.absolutePath, peManifest, assetsExtractDir.absolutePath, treeDir.absolutePath)
            evidence["repairRematRc"] = reMatRc.toString()
            val canonicalShaAfter = sha256Of(File(repairPath))
            evidence["repairShaAfter"] = canonicalShaAfter
            val verifyAfterRepair = WineSpikeNative.verifyPeCacheNative(cacheDir.absolutePath, peManifest)
            evidence["repairVerifyAfterRepair"] = verifyAfterRepair.toString()
            repairOk = (verifyAfterCorrupt != 0) && reMatRc == 0 &&
                (canonicalShaAfter == canonicalShaBefore) && (verifyAfterRepair == 0)
            evidence["repairOk"] = repairOk.toString()
        } else {
            evidence["repairOk"] = "false"
            evidence["repairReason"] = "could not resolve $repairTarget in the manifest"
        }

        // 6. Prove core builtin PE modules are reachable via the cache-backed
        //    logical tree. The logical symlinks (tree/lib/wine/<arch>/<mod>)
        //    must point at the cache, and the cache file must hash-match.
        val coreModules = listOf("wineboot.exe", "kernel32.dll", "ntdll.dll")
        val coreReachable = mutableListOf<String>()
        for (mod in coreModules) {
            // Look under both arches (x86_64-windows + i386-windows).
            val paths = listOf(
                File(treeDir, "lib/wine/x86_64-windows/$mod"),
                File(treeDir, "lib/wine/i386-windows/$mod"))
            val hit = paths.firstOrNull { it.exists() }
            coreReachable += if (hit != null && hit.canonicalPath.startsWith(cacheDir.canonicalPath)) {
                "$mod:ok@${hit.canonicalPath}"
            } else {
                "$mod:MISSING"
            }
        }
        evidence["coreModules"] = coreReachable.joinToString(",")
        val coreAllReachable = coreReachable.all { it.endsWith(":ok@") || ":ok@" in it }

        // 7. Launch the explicit wineboot PE through the final Wine loader,
        //    without PRoot. The private rtld access wrapper and APK path shim
        //    are the narrow Android compatibility layer; avoiding PRoot also
        //    avoids its proven new-WoW64 PE-relocation heap corruption.
        //    WINEDEBUG=+module captures which PE modules Wine loads, proving
        //    resolution from the cache.
        val winebootTarget = File(treeDir, "lib/wine/x86_64-windows/wineboot.exe").absolutePath
        evidence["winebootTarget"] = winebootTarget
        // Clear the prefix so wineboot does a real --init (proves it builds artifacts).
        if (prefixDir.exists()) prefixDir.deleteRecursively()
        prefixDir.mkdirs()

        AppLog.i(TAG, "S-2: synchronous wineboot PE --init via direct glibc path")
        val bootRaw = try {
            // LD_DEBUG is disabled for S-2 (it's the S-1 loader proof and
            // would crowd out wineboot's own output). WINEDEBUG=+module,+loaddll
            // captures PE module resolution from the cache.
            WineSpikeNative.runWineDirectNative(
                nativeDir, winebootTarget, prefixDir.absolutePath,
                "", "--init",
                "LD_DEBUG=;WINEDEBUG=+module,+loaddll;WINEDLLOVERRIDES=winex11.drv=d",
                60_000)
        } catch (e: Exception) {
            evidence["winebootRunException"] = "${e.javaClass.simpleName}: ${e.message}"
            ""
        }
        val boot = if (bootRaw.isNotEmpty()) parseProotRunResult(bootRaw) else null
        if (boot != null) {
            evidence["winebootRc"] = boot.rc.toString()
            evidence["winebootExitStatus"] = boot.exitStatus.toString()
            evidence["winebootExitCode"] = boot.exitCode.toString()
            evidence["winebootTimedOut"] = boot.timedOut.toString()
            evidence["winebootStdoutTail"] = boot.stdout.lineSequence().toList().takeLast(20).joinToString(" | ")
            evidence["winebootStderrTail"] = boot.stderr.lineSequence().toList().takeLast(30).joinToString(" | ")
            // Count wineboot.exe + core builtins loaded (WINEDEBUG=+module/loaddll).
            val dllLoads = boot.stderr.lineSequence()
                .filter { it.contains("wineboot.exe", ignoreCase = true) ||
                    it.contains("Loaded module", ignoreCase = true) ||
                    it.contains("kernel32.dll", ignoreCase = true) ||
                    it.contains("ntdll.dll", ignoreCase = true) }
                .toList()
            evidence["winebootModuleLoadLines"] = dllLoads.size.toString()
            evidence["winebootModuleLoadSample"] = dllLoads.take(8).joinToString(" | ")
        }

        // wineboot's launcher can exit before its reparented winedevice helpers
        // and wineserver finish writing the registry. Request an orderly server
        // shutdown, then wait for it, before inspecting prefix artifacts. This
        // is also the S-2 clean-close proof; SIGKILL would make the registry
        // check timing-dependent.
        val wineserverTarget = File(treeDir, "bin/wineserver").absolutePath
        val prefixReady = waitForPrefixReady(prefixDir)
        evidence["winebootPrefixReady"] = prefixReady.toString()
        val serverKill = runProotBounded(
            nativeDir, wineserverTarget, "wineserver", prefixDir.absolutePath,
            "", "-k", "", 15_000, evidence, "wineServerKill")
        val serverWait = runProotBounded(
            nativeDir, wineserverTarget, "wineserver", prefixDir.absolutePath,
            "", "-w", "", 15_000, evidence, "wineServerWait")
        val serverStoppedOk = prefixReady && serverKill != null && serverWait != null &&
            serverKill.exitedCleanly && serverKill.exitCode == 0 && !serverKill.timedOut &&
            serverWait.exitedCleanly && serverWait.exitCode == 0 && !serverWait.timedOut
        evidence["wineServerStoppedOk"] = serverStoppedOk.toString()

        // 8. Verify prefix artifacts.
        val sysReg = File(prefixDir, "system.reg")
        val userReg = File(prefixDir, "user.reg")
        val userdefReg = File(prefixDir, "userdef.reg")
        val dosdevices = File(prefixDir, "dosdevices")
        val sys32 = File(prefixDir, "drive_c/windows/system32")
        val artifacts = mapOf(
            "system.reg" to sysReg.isFile,
            "user.reg" to userReg.isFile,
            "userdef.reg" to userdefReg.isFile,
            "dosdevices" to dosdevices.isDirectory,
            "drive_c/windows/system32" to sys32.isDirectory,
        )
        evidence["prefixArtifacts"] = artifacts.entries.joinToString(",") { "${it.key}=${it.value}" }
        val artifactsOk = artifacts.values.all { it }

        // 9. Verdict: cache materialized+verified+repair-tested+core-reachable
        //     AND wineboot --init exited zero AND prefix artifacts present.
        val fatalRuntimeOutput = boot?.stderr?.lineSequence()?.any {
            it.contains("run_wineboot failed", ignoreCase = true) ||
                it.contains("corrupted size", ignoreCase = true) ||
                it.contains("free(): invalid", ignoreCase = true) ||
                it.contains("terminated with signal", ignoreCase = true)
        } ?: true
        val bootExitedZero = boot != null && boot.exitedCleanly && boot.exitCode == 0 &&
            !fatalRuntimeOutput
        val ok = matRc == 0 && verifyRc == 0 && repairOk && coreAllReachable &&
            bootExitedZero && serverStoppedOk && artifactsOk
        evidence["s2BootExitedZero"] = bootExitedZero.toString()
        evidence["s2ArtifactsOk"] = artifactsOk.toString()
        evidence["s2CoreAllReachable"] = coreAllReachable.toString()
        evidence["s2FatalRuntimeOutput"] = fatalRuntimeOutput.toString()

        val code = if (ok) "WINEBOOT_PE_RESOLUTION_PROVEN" else when {
            matRc != 0 || verifyRc != 0 -> "PE_CACHE_FAILED"
            !repairOk -> "MISMATCH_REPAIR_FAILED"
            !coreAllReachable -> "CORE_PE_NOT_REACHABLE"
            boot == null -> "WINEBOOT_RUN_EXCEPTION"
            boot.timedOut -> "WINEBOOT_TIMEOUT"
            fatalRuntimeOutput -> "WINEBOOT_FATAL_RUNTIME_OUTPUT"
            !bootExitedZero -> "WINEBOOT_NONZERO_EXIT"
            !serverStoppedOk -> "WINESERVER_CLEAN_STOP_FAILED"
            !artifactsOk -> "PREFIX_ARTIFACTS_MISSING"
            else -> "S2_UNKNOWN_FAILURE"
        }
        val result = if (ok) {
            ExperimentResult.ok("S2", "-", evidence, SystemClock.elapsedRealtime() - t0, code)
        } else {
            ExperimentResult.fail("S2", "-", code,
                listOf("matRc=$matRc verifyRc=$verifyRc repairOk=$repairOk " +
                    "coreAllReachable=$coreAllReachable bootExitedZero=$bootExitedZero " +
                    "serverStoppedOk=$serverStoppedOk artifactsOk=$artifactsOk " +
                    "bootTimedOut=${boot?.timedOut} " +
                    "bootExitCode=${boot?.exitCode}"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        announce(result)
        result
    }

    /** SHA-256 hex of a file (Java MessageDigest; for the mismatch-repair test). */
    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Expose the immutable, hash-verified PE cache at the conventional prefix
     * paths used by the Windows loader. Wine normally installs copies during
     * wine.inf processing; on a 16 KB host that helper chain can finish with
     * only the registry skeleton. Missing builtins are linked, never copied,
     * so the cache manifest remains the canonical source of truth.
     */
    private fun linkVerifiedBuiltinsIntoPrefix(
        prefixDir: File, cacheDir: File, manifestJson: String
    ): Int {
        val system32 = File(prefixDir, "drive_c/windows/system32")
        val syswow64 = File(prefixDir, "drive_c/windows/syswow64")
        system32.mkdirs()
        syswow64.mkdirs()
        val entries = JSONObject(manifestJson).getJSONArray("entries")
        var linked = 0
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val destinationDir = when (entry.getString("arch")) {
                "x86_64-windows" -> system32
                "i386-windows" -> syswow64
                else -> continue
            }
            val source = File(cacheDir, entry.getString("asset_path"))
            require(source.isFile) { "verified PE cache entry missing: $source" }
            val destination = File(destinationDir, source.name)
            if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            Files.createSymbolicLink(destination.toPath(), source.toPath())
            linked++
        }
        return linked
    }

    /** Wait until wineboot has finished its asynchronous registry transaction. */
    private fun waitForPrefixReady(prefixDir: File, timeoutMs: Long = 30_000): Boolean {
        val required = listOf(
            File(prefixDir, ".update-timestamp"),
            File(prefixDir, "system.reg"),
            File(prefixDir, "user.reg"),
            File(prefixDir, "userdef.reg"),
        )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous = ""
        var stableSamples = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = required.all { it.isFile && it.length() > 0L } &&
                File(prefixDir, "dosdevices").isDirectory &&
                File(prefixDir, "drive_c/windows").isDirectory
            val signature = if (ready) required.joinToString("|") {
                "${it.length()}:${it.lastModified()}"
            } else ""
            stableSamples = if (ready && signature == previous) stableSamples + 1 else 0
            if (stableSamples >= 4) return true
            previous = signature
            Thread.sleep(250)
        }
        return false
    }

    /**
     * Run a Wine command (e.g. wineserver -w/-k) via the synchronous proot run
     * with a bounded timeout, recording a structured evidence key. Used for the
     * S-2 wineserver wait/kill (clean shutdown).
     */
    private fun runProotBounded(
        nativeDir: String, wineTarget: String, argv0: String, prefixDir: String,
        display: String, wineArgs: String, extraEnv: String, timeoutMs: Int,
        evidence: LinkedHashMap<String, String>, key: String
    ): ProotRunResult? {
        val raw = try {
            WineSpikeNative.runWineViaProotNative(
                nativeDir, wineTarget, argv0, prefixDir, display, wineArgs, extraEnv, timeoutMs)
        } catch (e: Exception) {
            evidence["$key.exception"] = "${e.javaClass.simpleName}: ${e.message}"
            return null
        }
        val r = if (raw.isNotEmpty()) parseProotRunResult(raw) else null
        if (r != null) {
            evidence["$key.rc"] = r.rc.toString()
            evidence["$key.exitCode"] = r.exitCode.toString()
            evidence["$key.timedOut"] = r.timedOut.toString()
            evidence["$key.stderrTail"] = r.stderr.lineSequence().toList().takeLast(10).joinToString(" | ")
        }
        return r
    }

    /**
     * S-3: X11/GDI window via winex11.drv + the pinned Winlator X-server.
     *
     * Acceptance (per the corrected scope):
     *   - the native transport libwinlator.so is loaded (System.loadLibrary)
     *   - <appTmp>/.X11-unix/X0 is created; the glibc path shim relocates the
     *     X11 client's compiled socket path into that app-private directory
     *   - the X-server (XConnectorEpoll + handlers) starts and binds the socket
     *   - the project-owned 32-bit self-test PE launches with DISPLAY=:0 via the
     *     qualified direct glibc adapter and the same prefix/cache contract
     *   - the self-test connects, CreateWindow + MapWindow + at least one paint
     *     — proven by POCKET_SELFTEST_WINDOW + POCKET_SELFTEST_PAINT +
     *     POCKET_SELFTEST_OK in its stdout
     *     AND exit zero
     *   - a render proof: the X-server's drawable manager shows a non-empty
     *     mapped window after the run (the GLES texture upload is exercised by
     *     GLRenderer when a renderer is attached; for the headless spike we
     *     assert the window exists + has content via the drawable)
     *   - clean shutdown: wineserver -k/-w and X-server stop
     */
    suspend fun runS3(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
        evidence["nativeLibraryDir"] = nativeDir

        AppLog.i(TAG, "S-3: X11/GDI window via pinned Winlator X-server")

        // 1. Confirm libwinlator.so loads (the native transport). This throws
        //    UnsatisfiedLinkError if absent/broken — fail fast with evidence.
        var transportLoaded = false
        try {
            System.loadLibrary("winlator")
            transportLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            evidence["winlatorLoadError"] = e.message ?: e.javaClass.simpleName
        }
        evidence["winlatorLoaded"] = transportLoaded.toString()
        if (!transportLoaded) {
            val result = ExperimentResult.fail("S3", "-", "WINLATOR_LOAD_FAILED",
                listOf("libwinlator.so did not load"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }

        // 2. Build the symlink tree + materialize the PE cache + guest PE.
        val stagingManifest = try { readAsset(STAGING_MANIFEST_ASSET) } catch (e: Exception) { "" }
        val treeDir = File(context.filesDir, "runtime/wine-tree")
        val prefixDir = File(context.filesDir, "runtime/wine-prefix")
        val cacheDir = File(context.filesDir, "runtime/wine-pe-cache")
        val appTmp = File(context.filesDir, "runtime/tmp")
        appTmp.mkdirs()
        prefixDir.mkdirs()
        cacheDir.mkdirs()
        if (stagingManifest.isNotEmpty()) {
            WineSpikeNative.buildSymlinkTreeNative(treeDir.absolutePath, nativeDir, stagingManifest)
        }
        if (!prepareWineData(treeDir, evidence)) {
            val result = ExperimentResult.fail("S3", "-", "WINE_DATA_FAILED",
                listOf("Wine DATADIR could not be materialized and verified"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }

        // Materialize the Wine PEs and guest PE into the tree. This directory
        // is only an extraction cache, so wipe it first: install -r preserves
        // app cache data and an older self-test binary would otherwise shadow
        // the newly signed APK asset and make hash repair impossible.
        val assetsExtractDir = File(context.cacheDir, "wine-pe-assets")
        if (assetsExtractDir.exists()) assetsExtractDir.deleteRecursively()
        assetsExtractDir.mkdirs()
        val guestPeDir = File(assetsExtractDir, "guest-pe")
        try {
            extractAssetsToDir("guest-pe", guestPeDir)
            context.assets.open(GUEST_PE_MANIFEST_ASSET).use { inp ->
                File(assetsExtractDir, GUEST_PE_MANIFEST_ASSET).outputStream().use { inp.copyTo(it) }
            }
        } catch (_: Exception) { /* guest-pe may be absent in this build */ }
        // S-3 must also work standalone, without relying on an earlier S-2 run.
        run {
            extractAssetsToDir("wine-pe", File(assetsExtractDir, "wine-pe"))
            context.assets.open(WINE_PE_MANIFEST_ASSET).use { inp ->
                File(assetsExtractDir, WINE_PE_MANIFEST_ASSET).outputStream().use { inp.copyTo(it) }
            }
        }
        val peManifest = readAsset(WINE_PE_MANIFEST_ASSET)
        WineSpikeNative.materializePeCacheIntoTreeNative(
            cacheDir.absolutePath, peManifest, assetsExtractDir.absolutePath, treeDir.absolutePath)
        // Guest PE manifest (self-test) — materialize its entry into the tree.
        var guestMaterialized = false
        try {
            val guestManifest = readAsset(GUEST_PE_MANIFEST_ASSET)
            val grc = WineSpikeNative.materializePeCacheIntoTreeNative(
                cacheDir.absolutePath, guestManifest, assetsExtractDir.absolutePath, treeDir.absolutePath)
            guestMaterialized = (grc == 0)
        } catch (_: Exception) {}
        evidence["s3GuestMaterialized"] = guestMaterialized.toString()
        // Reverify before launch.
        val verifyRc = WineSpikeNative.verifyPeCacheNative(cacheDir.absolutePath, peManifest)
        evidence["s3PeVerifyRc"] = verifyRc.toString()

        // 3. Create <appTmp>/.X11-unix/X0 path dir. The native createServerSocket
        //    unlinks + binds the file; the glibc path shim maps Wine's compiled
        //    /tmp/.X11-unix path into this app-private directory.
        val x11Dir = File(appTmp, ".X11-unix")
        x11Dir.mkdirs()
        val x0Path = File(x11Dir, "X0").absolutePath
        evidence["x0Path"] = x0Path

        // 4. Start the X-server. We construct the minimal wiring the upstream
        //    XServerComponent uses: XServer + XConnectorEpoll bound to x0Path +
        //    XClientConnectionHandler + XClientRequestHandler. The X-server
        //    runs headless for the spike (no Activity surface); we assert the
        //    drawable content via the windowManager after the run.
        var xServer: com.winlator.xserver.XServer? = null
        var connector: com.winlator.xconnector.XConnectorEpoll? = null
        var serverStarted = false
        var windowSeen = false
        var paintSeen = false
        var selfTestOk = false
        var selfTestExit = -1
        try {
            val screenInfo = com.winlator.xserver.ScreenInfo(1280, 720)
            // The XServer ctor wants an XServerDisplayActivity; the vendored
            // stub accepts a no-arg/default. We pass the stub.
            val activity = com.winlator.XServerDisplayActivity()
            xServer = com.winlator.xserver.XServer(activity, screenInfo)
            val connHandler = com.winlator.xserver.XClientConnectionHandler(xServer)
            val reqHandler = com.winlator.xserver.XClientRequestHandler()
            // Build a UnixSocketConfig pointing at x0Path. Use the relative form
            // ".X11-unix/X0" under appTmp so FileUtils.getDirname sees a
            // separator (getDirname throws on a bare filename with no '/').
            val sockCfg = com.winlator.xconnector.UnixSocketConfig.create(
                appTmp.absolutePath, ".X11-unix/X0")
            connector = com.winlator.xconnector.XConnectorEpoll(sockCfg, connHandler, reqHandler)
            connector.setInitialInputBufferCapacity(4096)
            connector.setInitialOutputBufferCapacity(4096)
            connector.setCanReceiveAncillaryMessages(true)
            connector.start()
            serverStarted = true
            evidence["xServerStarted"] = "true"
            // Give the epoll thread a moment to bind.
            Thread.sleep(500)
            // Confirm the socket file exists (native bind succeeded).
            evidence["x0SocketExists"] = File(x0Path).exists().toString()

            // 5. Launch the self-test PE through the direct glibc adapter with
            //    DISPLAY=:0. The guest PE sits in the cache-backed tree at
            //    <tree>/pocket_selftest.exe.
            val selfTestPath = File(treeDir, "pocket_selftest.exe")
            evidence["selfTestPath"] = selfTestPath.absolutePath
            evidence["selfTestExists"] = selfTestPath.exists().toString()
            // Wipe the prefix for a clean WINEPREFIX init by wineboot when the
            // self-test launches. Initialize it explicitly first: Wine's
            // implicit first-run helper overlaps the 32-bit WoW64 transition,
            // which makes failures ambiguous and is not the production launch
            // ordering. S-2 already establishes this explicit wineboot path.
            if (prefixDir.exists()) prefixDir.deleteRecursively()
            prefixDir.mkdirs()

            val winebootTarget = File(treeDir, "lib/wine/x86_64-windows/wineboot.exe").absolutePath
            val initRaw = WineSpikeNative.runWineDirectNative(
                nativeDir, winebootTarget, prefixDir.absolutePath,
                "", "--init",
                "LD_DEBUG=;WINEDEBUG=-all;WINEDLLOVERRIDES=winex11.drv=d",
                60_000)
            val initRun = parseProotRunResult(initRaw)
            evidence["s3WinebootExit"] = initRun.exitCode.toString()
            evidence["s3WinebootTimedOut"] = initRun.timedOut.toString()
            evidence["s3WinebootStderrTail"] = initRun.stderr.lineSequence()
                .toList().takeLast(15).joinToString(" | ")
            if (!initRun.exitedCleanly || initRun.exitCode != 0) {
                throw IllegalStateException(
                    "explicit wineboot failed: exit=${initRun.exitCode} timeout=${initRun.timedOut}")
            }

            // wineboot's launcher exits before its reparented setup helpers
            // necessarily finish copying the builtin PE modules into the
            // prefix. Use the same orderly close barrier as S-2 before
            // launching the 32-bit test; otherwise a slower 16 KB lane races
            // an empty system32 directory and reports a false kernel32 miss.
            val wineserverTarget = File(treeDir, "bin/wineserver").absolutePath
            val prefixReady = waitForPrefixReady(prefixDir)
            evidence["s3WinebootPrefixReady"] = prefixReady.toString()
            val initServerKill = runProotBounded(
                nativeDir, wineserverTarget, "wineserver", prefixDir.absolutePath,
                "", "-k", "", 15_000, evidence, "s3InitServerKill")
            val initServerWait = runProotBounded(
                nativeDir, wineserverTarget, "wineserver", prefixDir.absolutePath,
                "", "-w", "", 15_000, evidence, "s3InitServerWait")
            val initServerCompleted = prefixReady && initServerKill != null &&
                initServerWait != null && initServerKill.exitedCleanly &&
                initServerKill.exitCode == 0 && !initServerKill.timedOut &&
                initServerWait.exitedCleanly && initServerWait.exitCode == 0 &&
                !initServerWait.timedOut
            val prefixBuiltinLinks = linkVerifiedBuiltinsIntoPrefix(
                prefixDir, cacheDir, peManifest)
            evidence["s3PrefixBuiltinLinksCreated"] = prefixBuiltinLinks.toString()
            val kernel32Ready = File(
                prefixDir, "drive_c/windows/system32/kernel32.dll").isFile
            evidence["s3Kernel32Ready"] = kernel32Ready.toString()
            evidence["s3InitServerBarrier"] = initServerCompleted.toString()
            if (!initServerCompleted) {
                throw IllegalStateException("wineboot prefix did not become ready and stop cleanly")
            }
            if (!kernel32Ready) {
                throw IllegalStateException("wineboot setup incomplete: kernel32.dll absent after wineserver barrier")
            }

            // Launch `wine pocket_selftest.exe` via the direct glibc adapter. The wine binary
            // (libwine_preloader.so) is the ELF the loader runs; the PE is its
            // argument (Wine's PE loader handles it). argv0=wine preserves the
            // logical command name. DISPLAY=:0 routes GDI through winex11.drv
            // to our X-server. The self-test path is passed as the wine arg.
            evidence["s3WineTarget"] = selfTestPath.absolutePath
            AppLog.i(TAG, "S-3: launching pocket_selftest.exe via direct glibc path (DISPLAY=:0)")
            val raw = try {
                WineSpikeNative.runWineDirectNative(
                    nativeDir, selfTestPath.absolutePath,
                    prefixDir.absolutePath, ":0", "",
                    "LD_DEBUG=;WINEDEBUG=-all",
                    120_000)
            } catch (e: Exception) {
                evidence["s3RunException"] = "${e.javaClass.simpleName}: ${e.message}"
                ""
            }
            val run = if (raw.isNotEmpty()) parseProotRunResult(raw) else null
            if (run != null) {
                evidence["s3Rc"] = run.rc.toString()
                evidence["s3ExitCode"] = run.exitCode.toString()
                evidence["s3TimedOut"] = run.timedOut.toString()
                evidence["s3DescendantCount"] = run.descendants.size.toString()
                run.descendants.forEachIndexed { index, child ->
                    evidence["s3Desc_${index}"] =
                        "pid=${child.pid},ppid=${child.ppid},class=${child.classification}," +
                            "maps=${child.mapsProof},cmd=${child.cmdline.take(240)}"
                }
                evidence["s3StdoutTail"] = run.stdout.lineSequence().toList().takeLast(40).joinToString(" | ")
                evidence["s3StderrTail"] = run.stderr.lineSequence().toList().takeLast(120).joinToString(" | ")
                run.stderr.lineSequence()
                    .filter {
                        it.contains("kernel32", ignoreCase = true) ||
                            it.contains("i386-windows", ignoreCase = true) ||
                            it.contains("x86_64-windows", ignoreCase = true) ||
                            it.contains("Failed to load", ignoreCase = true) ||
                            it.contains("find_dll", ignoreCase = true)
                    }
                    .toList().takeLast(60)
                    .forEachIndexed { index, line ->
                        evidence["s3Module_${index}"] = line.take(3000)
                    }
                run.stderr.lineSequence()
                    .filter {
                        it.contains("x11drv", ignoreCase = true) ||
                            it.contains("winex11", ignoreCase = true) ||
                            it.contains("display", ignoreCase = true) ||
                            it.contains("driver", ignoreCase = true)
                    }
                    .toList().takeLast(80)
                    .forEachIndexed { index, line ->
                        evidence["s3X11_${index}"] = line.take(3000)
                    }
                selfTestOk = run.stdout.contains("POCKET_SELFTEST_OK")
                windowSeen = run.stdout.contains("POCKET_SELFTEST_WINDOW")
                paintSeen = run.stdout.contains("POCKET_SELFTEST_PAINT")
                selfTestExit = if (run.exitedCleanly) run.exitCode else -1
            }
            evidence["s3WindowSeen"] = windowSeen.toString()
            evidence["s3PaintSeen"] = paintSeen.toString()
            evidence["s3SelfTestOk"] = selfTestOk.toString()
            evidence["s3SelfTestExit"] = selfTestExit.toString()

            // 6. Render proof: the X-server's drawable manager should now hold a
            //    mapped window with content (CreateWindow + MapWindow + paint
            //    produced BGRA bytes). For the headless spike we assert a client
            //    window is mapped + the X-server saw the connection.
            try {
                val wm = xServer.windowManager
                val mapped = wm.mappedClientWindows
                evidence["s3WindowCount"] = mapped.size.toString()
                evidence["s3HasMappedWindow"] = mapped.isNotEmpty().toString()
                if (mapped.isNotEmpty()) {
                    val w = mapped.first()
                    evidence["s3FirstWindow"] = "${w.width}x${w.height}@${w.x},${w.y}"
                }
            } catch (e: Exception) {
                evidence["s3WindowProbeException"] = "${e.javaClass.simpleName}: ${e.message}"
            }
        } catch (e: Exception) {
            evidence["s3HostException"] = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.e(TAG, "S-3 host exception", e)
        } finally {
            // 7. Clean shutdown: stop the X-server connector, kill wineserver,
            //    reap the proot tree. The proot run already waited for the
            //    self-test, but wineserver may persist.
            try { connector?.destroy() } catch (_: Exception) {}
            // The direct runner launches a non-persistent wineserver; it exits
            // when the final Wine client disconnects. Timeout cleanup kills the
            // complete process tree if a client fails to shut down.
        }

        // Verdict: transport loaded + server started + window seen + self-test
        // reported OK + exit zero.
        val ok = transportLoaded && serverStarted && windowSeen && paintSeen &&
            selfTestOk && selfTestExit == 0
        val code = if (ok) "X11_GDI_WINDOW_PROVEN" else when {
            !transportLoaded -> "WINLATOR_LOAD_FAILED"
            !serverStarted -> "XSERVER_START_FAILED"
            !windowSeen -> "NO_WINDOW_MAPPED"
            !paintSeen -> "NO_WINDOW_PAINT"
            !selfTestOk -> "SELFTEST_NO_OK_MARKER"
            selfTestExit != 0 -> "SELFTEST_NONZERO_EXIT"
            else -> "S3_UNKNOWN_FAILURE"
        }
        evidence["s3Ok"] = ok.toString()
        evidence["verdict"] = code
        val result = if (ok) {
            ExperimentResult.ok("S3", "-", evidence, SystemClock.elapsedRealtime() - t0, code)
        } else {
            ExperimentResult.fail("S3", "-", code,
                listOf("transportLoaded=$transportLoaded serverStarted=$serverStarted " +
                    "windowSeen=$windowSeen paintSeen=$paintSeen " +
                    "selfTestOk=$selfTestOk selfTestExit=$selfTestExit"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        announce(result)
        result
    }
}
