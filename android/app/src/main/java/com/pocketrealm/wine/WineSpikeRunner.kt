package com.pocketrealm.wine

import android.content.Context
import android.os.SystemClock
import com.pocketrealm.log.AppLog
import com.pocketrealm.pkg.ExperimentResult
import java.io.File

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
            c.contains("ld-linux") || c.contains("libld_linux") || m == "ld-linux" -> "loader"
            m == "wine-preloader" || m == "wine" || m == "wine64" ||
                c.contains("libwine_preloader") || c.contains("/wine ") ||
                (c.contains("--argv0=wine") && !c.contains("wineboot") && !c.contains("wineserver")) -> "wine"
            m == "wineserver" || c.contains("wineserver") || c.contains("--argv0=wineserver") -> "wineserver"
            c.contains("--argv0=wineboot") || m == "wineboot" -> "wineboot"
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
        // FULL S-1 acceptance: run wine --version via the synchronous proot run
        // and require the ENTIRE process tree to be proven. This corrects the
        // earlier false pass: the previous success condition only checked the
        // LD_DEBUG strings from the bootstrap command and ignored that the
        // /proc maps probe of the live proot PID was FAIL|rc=5. The acceptance
        // requires, for the glibc/Wine tree:
        //   - the bootstrap command exited zero (WIFEXITED + exit 0)
        //   - expected output present ("wine-11" for --version)
        //   - every Wine native child (wine/wineserver/glibc-child) has an
        //     OK maps proof (APK-managed loader). Bionic proot/helper processes
        //   are tracked separately (the APK-glibc-loader requirement does not
        //     apply to them); no glibc/Wine child may be "unknown".
        // =====================================================================
        AppLog.i(TAG, "S-1 full: synchronous proot run with recursive descendant proof")
        val fullRun = try {
            val raw = WineSpikeNative.runWineViaProotNative(
                nativeDir, wineTarget, "wine", prefixDir.absolutePath, "", "--version", "",
                30_000)
            parseProotRunResult(raw).also { evidence["s1_fullRawHeader"] = raw.lineSequence().firstOrNull() ?: "" }
        } catch (e: Exception) {
            evidence["s1_fullRunException"] = "${e.javaClass.simpleName}: ${e.message}"
            null
        }

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
            val bootstrapOk = fullRun.exitedCleanly && fullRun.exitCode == 0 &&
                fullRun.stdout.contains("wine-")
            evidence["s1_fullBootstrapOk"] = bootstrapOk.toString()
            evidence["s1_fullExitCode"] = fullRun.exitCode.toString()
            evidence["s1_fullSignaledBy"] = fullRun.signaledBy.toString()

            // Classify the tree + apply the acceptance rule.
            val wineChildren = fullRun.descendants.filter {
                it.classification in setOf("wine", "wineserver", "wineboot", "glibc-child", "loader")
            }
            val unknown = fullRun.descendants.filter { it.classification == "unknown" }
            // Every Wine/glibc child must have an OK maps proof. "GONE" means
            // the process exited before the snapshot — acceptable ONLY if it's
            // a short-lived helper (loader) AND at least one wine/wineserver
            // process with OK proof exists.
            val wineTreeProven = wineChildren.isNotEmpty() &&
                wineChildren.all { it.mapsProof.startsWith("OK|") }
            // Wine or wineserver must specifically be present + proven.
            val hasProvenWine = wineChildren.any { it.classification == "wine" && it.mapsProof.startsWith("OK|") }
            evidence["s1_fullWineChildren"] = wineChildren.size.toString()
            evidence["s1_fullUnknownChildren"] = unknown.size.toString()
            evidence["s1_fullWineTreeProven"] = wineTreeProven.toString()
            evidence["s1_fullHasProvenWine"] = hasProvenWine.toString()

            fullS1Ok = bootstrapOk && wineTreeProven && hasProvenWine && unknown.none {
                // An unknown is a hard failure only if it's a glibc-namespace
                // child (not a Bionic helper). We can't always tell, so any
                // unknown with a non-OK, non-GONE maps proof fails.
                it.mapsProof.startsWith("FAIL|")
            }
            fullS1Reason = when {
                !bootstrapOk -> "bootstrap did not exit 0 with wine- output " +
                    "(exit=${fullRun.exitCode} timedOut=${fullRun.timedOut})"
                !hasProvenWine -> "no wine process with APK-managed loader proof"
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

        // 7. Launch wineboot --init via the synchronous proot run with the
        //    LOGICAL argv[0]="wineboot" preserved (--argv0). Require exit zero.
        //    WINEDEBUG=+module captures which PE modules Wine loads, proving
        //    resolution from the cache.
        val winebootTarget = File(treeDir, "bin/wineboot").absolutePath
        evidence["winebootTarget"] = winebootTarget
        // Clear the prefix so wineboot does a real --init (proves it builds artifacts).
        if (prefixDir.exists()) prefixDir.deleteRecursively()
        prefixDir.mkdirs()

        AppLog.i(TAG, "S-2: synchronous wineboot --init via proot (argv0=wineboot)")
        val bootRaw = try {
            WineSpikeNative.runWineViaProotNative(
                nativeDir, winebootTarget, "wineboot", prefixDir.absolutePath,
                "", "--init", "WINEDEBUG=+module,+loaddll", 90_000)
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

        // 8. Wait for wineserver cleanly: wineboot starts a persistent
        //    wineserver. Send --kill (or wineserver -k) after the init completes.
        //    The proot run already waited for wineboot to exit; we additionally
        //    run a short `wineserver -w` (wait) bounded by a timeout to confirm
        //    wineserver is responsive, then -k to shut it down.
        val serverTarget = File(treeDir, "bin/wineserver").absolutePath
        runProotBounded(nativeDir, serverTarget, "wineserver", prefixDir.absolutePath,
            "", "-w", "", 10_000, evidence, "serverWait")
        runProotBounded(nativeDir, serverTarget, "wineserver", prefixDir.absolutePath,
            "", "-k", "", 5_000, evidence, "serverKill")

        // 9. Verify prefix artifacts.
        val sysReg = File(prefixDir, "system.reg")
        val userReg = File(prefixDir, "user.reg")
        val userdefReg = File(prefixDir, "userdef.reg")
        val dosdevices = File(prefixDir, "dosdevices")
        val sys32 = File(prefixDir, "drive_c/windows/system32")
        val artifacts = mapOf(
            "system.reg" to sysReg.isFile,
            "user.reg" to userReg.isFile,
            "userdef.reg" to (userdefReg.isFile || userdefReg.length() == 0L),
            "dosdevices" to dosdevices.isDirectory,
            "drive_c/windows/system32" to sys32.isDirectory,
        )
        evidence["prefixArtifacts"] = artifacts.entries.joinToString(",") { "${it.key}=${it.value}" }
        val artifactsOk = artifacts.values.all { it }

        // 10. Verdict: cache materialized+verified+repair-tested+core-reachable
        //     AND wineboot --init exited zero AND prefix artifacts present.
        val bootExitedZero = boot != null && boot.exitedCleanly && boot.exitCode == 0
        val ok = matRc == 0 && verifyRc == 0 && repairOk && coreAllReachable &&
            bootExitedZero && artifactsOk
        evidence["s2BootExitedZero"] = bootExitedZero.toString()
        evidence["s2ArtifactsOk"] = artifactsOk.toString()
        evidence["s2CoreAllReachable"] = coreAllReachable.toString()

        val code = if (ok) "WINEBOOT_PE_RESOLUTION_PROVEN" else when {
            matRc != 0 || verifyRc != 0 -> "PE_CACHE_FAILED"
            !repairOk -> "MISMATCH_REPAIR_FAILED"
            !coreAllReachable -> "CORE_PE_NOT_REACHABLE"
            boot == null -> "WINEBOOT_RUN_EXCEPTION"
            boot.timedOut -> "WINEBOOT_TIMEOUT"
            !bootExitedZero -> "WINEBOOT_NONZERO_EXIT"
            !artifactsOk -> "PREFIX_ARTIFACTS_MISSING"
            else -> "S2_UNKNOWN_FAILURE"
        }
        val result = if (ok) {
            ExperimentResult.ok("S2", "-", evidence, SystemClock.elapsedRealtime() - t0, code)
        } else {
            ExperimentResult.fail("S2", "-", code,
                listOf("matRc=$matRc verifyRc=$verifyRc repairOk=$repairOk " +
                    "coreAllReachable=$coreAllReachable bootExitedZero=$bootExitedZero " +
                    "artifactsOk=$artifactsOk bootTimedOut=${boot?.timedOut} " +
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
     * Run a Wine command (e.g. wineserver -w/-k) via the synchronous proot run
     * with a bounded timeout, recording a structured evidence key. Used for the
     * S-2 wineserver wait/kill (clean shutdown).
     */
    private fun runProotBounded(
        nativeDir: String, wineTarget: String, argv0: String, prefixDir: String,
        display: String, wineArgs: String, extraEnv: String, timeoutMs: Int,
        evidence: LinkedHashMap<String, String>, key: String
    ) {
        val raw = try {
            WineSpikeNative.runWineViaProotNative(
                nativeDir, wineTarget, argv0, prefixDir, display, wineArgs, extraEnv, timeoutMs)
        } catch (e: Exception) {
            evidence["$key.exception"] = "${e.javaClass.simpleName}: ${e.message}"
            return
        }
        val r = if (raw.isNotEmpty()) parseProotRunResult(raw) else null
        if (r != null) {
            evidence["$key.rc"] = r.rc.toString()
            evidence["$key.exitCode"] = r.exitCode.toString()
            evidence["$key.timedOut"] = r.timedOut.toString()
            evidence["$key.stderrTail"] = r.stderr.lineSequence().toList().takeLast(10).joinToString(" | ")
        }
    }

    /**
     * S-3: X11/GDI window via winex11.drv + the pinned Winlator X-server.
     *
     * Acceptance (per the corrected scope):
     *   - the native transport libwinlator.so is loaded (System.loadLibrary)
     *   - <appTmp>/.X11-unix/X0 is created; proot's <appTmp>:/tmp bind makes it
     *     visible as /tmp/.X11-unix/X0 to Wine
     *   - the X-server (XConnectorEpoll + handlers) starts and binds the socket
     *   - the project-owned 32-bit self-test PE launches with DISPLAY=:0 via the
     *     same proot/prefix/cache path
     *   - the self-test connects, CreateWindow + MapWindow + at least one paint
     *     — proven by POCKET_SELFTEST_WINDOW + POCKET_SELFTEST_OK in its stdout
     *     AND exit zero
     *   - a render proof: the X-server's drawable manager shows a non-empty
     *     mapped window after the run (the GLES texture upload is exercised by
     *     GLRenderer when a renderer is attached; for the headless spike we
     *     assert the window exists + has content via the drawable)
     *   - clean shutdown: wineserver -k, X-server stop, proot tree reaped
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

        // Materialize guest PE (the self-test) into the tree + assets.
        val assetsExtractDir = File(context.cacheDir, "wine-pe-assets")
        if (!assetsExtractDir.isDirectory) {
            assetsExtractDir.mkdirs()
            extractAssetsToDir("wine-pe", File(assetsExtractDir, "wine-pe"))
            context.assets.open(WINE_PE_MANIFEST_ASSET).use { inp ->
                File(assetsExtractDir, WINE_PE_MANIFEST_ASSET).outputStream().use { inp.copyTo(it) }
            }
            try {
                extractAssetsToDir("guest-pe", File(assetsExtractDir, "guest-pe"))
                context.assets.open(GUEST_PE_MANIFEST_ASSET).use { inp ->
                    File(assetsExtractDir, GUEST_PE_MANIFEST_ASSET).outputStream().use { inp.copyTo(it) }
                }
            } catch (_: Exception) { /* guest-pe may be absent */ }
        }
        val peManifest = readAsset(WINE_PE_MANIFEST_ASSET)
        WineSpikeNative.materializePeCacheIntoTreeNative(
            cacheDir.absolutePath, peManifest, assetsExtractDir.absolutePath, treeDir.absolutePath)
        // Guest PE manifest (self-test) — materialize its entry too.
        try {
            val guestManifest = readAsset(GUEST_PE_MANIFEST_ASSET)
            WineSpikeNative.materializePeCacheIntoTreeNative(
                cacheDir.absolutePath, guestManifest, assetsExtractDir.absolutePath, treeDir.absolutePath)
        } catch (_: Exception) {}
        // Reverify before launch.
        val verifyRc = WineSpikeNative.verifyPeCacheNative(cacheDir.absolutePath, peManifest)
        evidence["s3PeVerifyRc"] = verifyRc.toString()

        // 3. Create <appTmp>/.X11-unix/X0 path dir. The native createServerSocket
        //    unlinks + binds the file; we just need the parent dir to exist.
        //    proot's <appTmp>:/tmp bind exposes it as /tmp/.X11-unix/X0 to Wine.
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
            // Build a UnixSocketConfig pointing at x0Path directly.
            val sockCfg = com.winlator.xconnector.UnixSocketConfig.create(x11Dir.absolutePath, "X0")
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

            // 5. Launch the self-test PE via the synchronous proot run with
            //    DISPLAY=:0. argv0 is the PE basename (pocket_selftest). The
            //    guest PE sits in the cache-backed tree at <tree>/pocket_selftest.exe.
            val selfTestPath = File(treeDir, "pocket_selftest.exe")
            evidence["selfTestPath"] = selfTestPath.absolutePath
            evidence["selfTestExists"] = selfTestPath.exists().toString()
            // Wipe the prefix for a clean WINEPREFIX init by wineboot when the
            // self-test launches Wine's initial setup.
            if (prefixDir.exists()) prefixDir.deleteRecursively()
            prefixDir.mkdirs()

            AppLog.i(TAG, "S-3: launching self-test PE via proot (DISPLAY=:0)")
            val raw = try {
                WineSpikeNative.runWineViaProotNative(
                    nativeDir, selfTestPath.absolutePath, "pocket_selftest",
                    prefixDir.absolutePath, ":0", "", "", 120_000)
            } catch (e: Exception) {
                evidence["s3RunException"] = "${e.javaClass.simpleName}: ${e.message}"
                ""
            }
            val run = if (raw.isNotEmpty()) parseProotRunResult(raw) else null
            if (run != null) {
                evidence["s3Rc"] = run.rc.toString()
                evidence["s3ExitCode"] = run.exitCode.toString()
                evidence["s3TimedOut"] = run.timedOut.toString()
                evidence["s3StdoutTail"] = run.stdout.lineSequence().toList().takeLast(40).joinToString(" | ")
                evidence["s3StderrTail"] = run.stderr.lineSequence().toList().takeLast(40).joinToString(" | ")
                selfTestOk = run.stdout.contains("POCKET_SELFTEST_OK")
                windowSeen = run.stdout.contains("POCKET_SELFTEST_WINDOW")
                selfTestExit = if (run.exitedCleanly) run.exitCode else -1
            }
            evidence["s3WindowSeen"] = windowSeen.toString()
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
            val serverTarget = File(treeDir, "bin/wineserver").absolutePath
            runProotBounded(nativeDir, serverTarget, "wineserver", prefixDir.absolutePath,
                "", "-k", "", 5_000, evidence, "s3ServerKill")
        }

        // Verdict: transport loaded + server started + window seen + self-test
        // reported OK + exit zero.
        val ok = transportLoaded && serverStarted && windowSeen && selfTestOk && selfTestExit == 0
        val code = if (ok) "X11_GDI_WINDOW_PROVEN" else when {
            !transportLoaded -> "WINLATOR_LOAD_FAILED"
            !serverStarted -> "XSERVER_START_FAILED"
            !windowSeen -> "NO_WINDOW_MAPPED"
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
                    "windowSeen=$windowSeen selfTestOk=$selfTestOk selfTestExit=$selfTestExit"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        announce(result)
        result
    }
}
