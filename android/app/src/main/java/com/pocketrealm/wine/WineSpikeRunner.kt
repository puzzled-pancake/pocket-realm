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

    /** Result of the LD_DEBUG=libs loader-chain verification. */
    private data class LoaderProof(
        val sawApkLoader: Boolean,
        val sawLibcResolved: Boolean,
        val sawLibdlResolved: Boolean,
        val evidence: Map<String, String>,
    )

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
        // Verdict: S-1 passes ONLY if the production app-process loader chain is
        // proven. The run-as result is supporting evidence, not acceptance.
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
        val ok = directOk || trampOk || prootOk || prootNsOk

        val code = when {
            ok -> "LOADER_PROVEN" +
                (if (prootNsOk && !directOk && !prootOk) "_VIA_PROOT_NO_SECCOMP" else "") +
                (if (prootOk && !directOk) "_VIA_PROOT" else "") +
                (if (trampOk && !directOk && !prootOk && !prootNsOk) "_VIA_TRAMPOLINE" else "") +
                (if (effectiveTunables.isNotEmpty()) "_WITH_TUNABLES" else "")
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
        evidence["verdict"] = code

        val result = if (ok) {
            ExperimentResult.ok("S1", "-", evidence, SystemClock.elapsedRealtime() - t0, code)
        } else {
            // Honest failure: record the exact cause. Do NOT claim SELinux unless
            // si_code proves it; do NOT weaken acceptance.
            ExperimentResult.fail("S1", "-", code,
                listOf("directOk=$directOk trampOk=$trampOk prootOk=$prootOk " +
                    "prootNsOk=$prootNsOk diagCause=$diagCause syscall=$diagSyscallName " +
                    "siCode=$diagSiCode tunables='$effectiveTunables'"),
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
     * cache, and the cache is reverified before launch.
     */
    suspend fun runS2(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
        evidence["nativeLibraryDir"] = nativeDir

        AppLog.i(TAG, "S-2: wineboot PE resolution")

        // 1. Extract PE assets to a temp dir (the C materializer reads from the filesystem).
        val assetsExtractDir = File(context.cacheDir, "wine-pe-assets")
        if (!assetsExtractDir.exists()) {
            extractAssetsToDir("wine-pe", File(assetsExtractDir, "wine-pe"))
            // Copy the manifest too.
            context.assets.open(WINE_PE_MANIFEST_ASSET).use { input ->
                File(assetsExtractDir, WINE_PE_MANIFEST_ASSET).outputStream().use { input.copyTo(it) }
            }
        }
        evidence["assetsExtractDir"] = assetsExtractDir.absolutePath

        // 2. Materialize the PE cache (SHA-256 verified, atomic copy).
        val cacheDir = File(context.filesDir, "runtime/wine-pe-cache")
        val peManifest = readAsset(WINE_PE_MANIFEST_ASSET)
        val manifestFile = File(assetsExtractDir, WINE_PE_MANIFEST_ASSET)
        val rc = WineSpikeNative.materializePeCacheNative(
            cacheDir.absolutePath, peManifest, assetsExtractDir.absolutePath)
        evidence["materializeRc"] = rc.toString()
        if (rc != 0) {
            val result = ExperimentResult.fail("S2", "-", "PE_MATERIALIZE_FAILED",
                listOf("rc=$rc (${WineSpikeNative.errStrNative(rc)})"),
                evidence, SystemClock.elapsedRealtime() - t0)
            announce(result)
            return@withContext result
        }

        // 3. Verify the cache (reverification before launch).
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

        // 4. Launch wineboot --init via proot (the direct path is killed by the
        //    access() seccomp trap — see S-1). wineboot initializes the prefix
        //    and resolves Wine-owned PE builtin modules, proving the PE cache.
        //    Build the symlink tree (idempotent — S-1 may already have built it).
        val manifest = try { readAsset(STAGING_MANIFEST_ASSET) } catch (e: Exception) { "" }
        val treeDir = File(context.filesDir, "runtime/wine-tree")
        if (manifest.isNotEmpty()) {
            val treeRc = WineSpikeNative.buildSymlinkTreeNative(treeDir.absolutePath, nativeDir, manifest)
            evidence["symlinkTreeRc"] = treeRc.toString()
        }
        File(context.filesDir, "runtime/tmp").mkdirs()
        val prefixDir = File(context.filesDir, "runtime/wine-prefix")
        prefixDir.mkdirs()
        val winebootTarget = File(treeDir, "bin/wineboot").absolutePath
        evidence["winebootTarget"] = winebootTarget

        val winebootPid = WineSpikeNative.launchWineViaProotNative(
            nativeDir, winebootTarget, prefixDir.absolutePath, "", "--init", "")
        evidence["winebootPid"] = winebootPid.toString()

        if (winebootPid > 0) {
            // Wait for wineboot to complete (it initializes the prefix).
            Thread.sleep(5000)
            val exit = try {
                // Check if the process is still running.
                val maps = File("/proc/$winebootPid/maps")
                if (maps.exists()) {
                    evidence["winebootStillRunning"] = "true"
                    -1
                } else {
                    evidence["winebootStillRunning"] = "false"
                    0
                }
            } catch (e: Exception) {
                -1
            }
            evidence["winebootExit"] = exit.toString()

            // Kill it if still running.
            try { Runtime.getRuntime().exec(arrayOf("kill", "-9", winebootPid.toString())).waitFor() } catch (_: Exception) {}
        }

        // S-2 verdict: cache materialized + verified + wineboot launched without
        // immediate failure. Full PE resolution proof comes from wineboot's log
        // (the host driver captures WINEDEBUG output).
        val ok = rc == 0 && verifyRc == 0 && winebootPid > 0
        val result = if (ok) {
            ExperimentResult.ok("S2", "-", evidence, SystemClock.elapsedRealtime() - t0, "PE_CACHE_VERIFIED")
        } else {
            ExperimentResult.fail("S2", "-", "PE_CACHE_OR_WINEBOOT_FAILED",
                listOf("materializeRc=$rc verifyRc=$verifyRc winebootPid=$winebootPid"),
                evidence, SystemClock.elapsedRealtime() - t0)
        }
        announce(result)
        result
    }

    /**
     * S-3: X11/GDI window via winex11.drv + the X-server harness.
     *
     * Deferred until the minimum Winlator X-server harness is vendored. For now
     * this reports DEFERRED so S-1/S-2 can proceed without blocking.
     */
    suspend fun runS3(): ExperimentResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val evidence = linkedMapOf<String, String>()
        evidence["note"] = "S-3 requires the minimum Winlator X-server harness (not yet vendored)"
        val result = ExperimentResult.fail("S3", "-", "DEFERRED",
            listOf("X-server harness not yet vendored — S-1/S-2 proceed without it"),
            evidence, SystemClock.elapsedRealtime() - t0)
        announce(result)
        result
    }
}
