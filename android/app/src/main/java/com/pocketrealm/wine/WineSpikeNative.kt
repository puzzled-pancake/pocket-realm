package com.pocketrealm.wine

import com.pocketrealm.log.AppLog

/**
 * JNI bridge to libwine_spike.so (native/wine-spike). This library runs in the
 * Android/Bionic namespace; Wine runs in the Linux/glibc namespace after execve.
 *
 * No stubs: if libwine_spike.so is absent, [load] throws UnsatisfiedLinkError
 * (the spike cannot proceed without the native helper).
 */
object WineSpikeNative {
    private const val TAG = "WineSpikeNative"

    fun load() {
        System.loadLibrary("wine_spike")
        AppLog.i(TAG, "libwine_spike.so loaded")
    }

    /** Build the symlink-only logical Wine tree. Returns WINE_SPIKE_* rc (0=OK). */
    external fun buildSymlinkTreeNative(treeDir: String, nativeDir: String, manifest: String): Int

    /**
     * Launch Wine via the APK-managed glibc loader. Returns the child PID, or -1 on failure.
     * [wineTarget] is the absolute path to the wine binary (via the symlink tree).
     * [wineArgs] is a space-separated string of extra args (e.g. "--version").
     */
    external fun launchWineNative(
        nativeDir: String, wineTarget: String, prefixDir: String, display: String,
        wineArgs: String
    ): Long

    /**
     * S-5 extended launch with an extra env string ("KEY=VAL;KEY=VAL;...").
     * Used to inject GLIBC_TUNABLES (e.g. `glibc.pthread.rseq=0`) for the
     * narrow glibc-startup fallback before reaching for proot.
     */
    external fun launchWineExNative(
        nativeDir: String, wineTarget: String, prefixDir: String, display: String,
        wineArgs: String, extraEnv: String
    ): Long

    /**
     * Probe /proc/<pid>/maps for the effective loader. Returns a structured string:
     * "OK|<loader_path>|<interp>|<apk_mapping_count>" or "FAIL|rc=N|loader=...|interp=...|apk=N".
     */
    external fun probeLoaderNative(pid: Long, expectedNativeDir: String): String

    /** Enumerate child PIDs of [parentPid] (wine spawns wineserver). */
    external fun enumChildrenNative(parentPid: Long): IntArray

    /** Materialize PE cache from assets. Returns WINE_SPIKE_* rc (0=OK). */
    external fun materializePeCacheNative(cacheDir: String, manifest: String, assetsDir: String): Int

    /** Verify PE cache hashes. Returns WINE_SPIKE_* rc (0=OK, 5=mismatch). */
    external fun verifyPeCacheNative(cacheDir: String, manifest: String): Int

    /** Human-readable error string for a result code. */
    external fun errStrNative(code: Int): String

    /**
     * S-5(0): Trace the glibc loader under PTRACE and capture the SIGSYS cause.
     * Returns a structured string (see jni_shim.cpp diagSigsysNative):
     *   "OK|exit=N|sig=N|si_code=N|syscall=M|name=rseq|arch=0xc|cause=C"
     * cause: 0=UNRESOLVED 1=SECCOMP 2=USER 3=KERNEL 4=NONE
     *
     * This corrects the a512b71 diagnosis: exit 159 alone does not establish
     * SELinux as the cause. We capture si_code + syscall nr before classifying.
     */
    external fun diagSigsysNative(
        nativeDir: String, wineTarget: String, prefixDir: String,
        display: String, wineArgs: String
    ): String

    /**
     * S-5(a): Launch Wine via the APK-packaged Bionic trampoline PIE
     * (libwine_trampoline.so). The trampoline re-execve's the glibc loader.
     * Returns the child PID, or -1 on failure. Evidence from this path is kept
     * SEPARATE from the PKG-01 control and the direct S-1 path.
     */
    external fun launchWineViaTrampolineNative(
        nativeDir: String, wineTarget: String, prefixDir: String,
        display: String, wineArgs: String
    ): Long

    /** S-5(a) extended: trampoline launch with extra_env (GLIBC_TUNABLES etc.). */
    external fun launchWineViaTrampolineExNative(
        nativeDir: String, wineTarget: String, prefixDir: String,
        display: String, wineArgs: String, extraEnv: String
    ): Long

    /**
     * S-5(b): Launch Wine via proot (syscall interception). proot runs in the
     * Android/Bionic namespace and ptrace-traces the glibc child, translating
     * access(2)->faccessat(2) to work around the seccomp filter. Returns the
     * proot process PID, or -1 on failure. The -b <tmp>:/tmp bind handles
     * wineserver's hardcoded /tmp/.wine-<uid> path.
     *
     * NOTE: this is the async fire-and-forget launch (returns a live PID).
     * Prefer [runWineViaProotNative] for S-1/S-2 acceptance, which runs to
     * completion and returns exit status + captured output + recursive
     * descendant /proc maps proof.
     */
    external fun launchWineViaProotNative(
        nativeDir: String, wineTarget: String, prefixDir: String,
        display: String, wineArgs: String, extraEnv: String
    ): Long

    /**
     * S-1/S-2 synchronous proot run with logical argv[0] preservation and full
     * process-tree loader proof. This is the corrected launcher:
     *  - argv0Override preserves the logical Wine command name (wine/wineboot/
     *    winecfg) via glibc-loader --argv0, so Wine dispatches correctly even
     *    though the real ELF is libwine_preloader.so.
     *  - Runs proot to completion (or timeoutMs), capturing stdout + stderr.
     *  - Snapshots EVERY descendant's PID/PPID/comm/cmdline + /proc/<pid>/maps
     *    proof while the tree is alive — the S-1 acceptance requirement (wine +
     *    wineserver + every native child must map the APK-managed loader).
     *  - On timeout, recursively kills + reaps the whole process tree.
     *
     * Returns a structured string (parse with parseProotRunResult):
     *   header line: "RC=<int>|EXIT=<int>|TIMED_OUT=<0|1>|DESCS=<n>"
     *   one line per descendant: "  pid=<ll>|ppid=<ll>|comm=<s>|maps=<proof>|cmdline=<s>"
     *   "@@@STDOUT@@@\n<stdout>"
     *   "@@@STDERR@@@\n<stderr>"
     *
     * EXIT is the raw waitpid status of the top proot process; the caller
     * checks WIFEXITED + WEXITSTATUS. maps proof per descendant is one of:
     *   "OK|<loader_path>|<apk_count>" (APK-managed loader proven)
     *   "FAIL|rc=<n>|apk=<n>"           (loader not APK-managed)
     *   "GONE"                          (process exited before snapshot)
     */
    external fun runWineViaProotNative(
        nativeDir: String, wineTarget: String, argv0Override: String,
        prefixDir: String, display: String, wineArgs: String,
        extraEnv: String, timeoutMs: Int
    ): String

    /** Synchronous no-PRoot Wine run using the APK rtld compatibility patch,
     * glibc path shim, and Bionic wineserver exec bridge. [peTarget] is an
     * explicit PE path; the native side invokes the final Wine loader with
     * logical argv0=wine so no writable Unix ELF re-exec is needed. */
    external fun runWineDirectNative(
        nativeDir: String, peTarget: String, prefixDir: String,
        display: String, wineArgs: String, extraEnv: String, timeoutMs: Int
    ): String

    /** Kill the currently active direct Wine process group, if any. */
    external fun cancelActiveDirectNative(): Boolean

    /**
     * S-2 tree-aware PE cache materialize: like [materializePeCacheNative] but
     * additionally symlinks each manifest entry's logical_path into the wine
     * tree, so Wine can find the cached PE modules at their expected paths.
     * Pass treeDir=null for the legacy (cache-files-only) behavior.
     */
    external fun materializePeCacheIntoTreeNative(
        cacheDir: String, manifest: String, assetsDir: String, treeDir: String?
    ): Int

    /**
     * S-2 mismatch-repair: resolve the cache path for a PE module asset basename
     * (e.g. "kernel32.dll"). Returns "" if no match.
     */
    external fun resolveCachePathNative(
        cacheDir: String, manifest: String, assetName: String
    ): String
}
