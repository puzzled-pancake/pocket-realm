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
     */
    external fun launchWineViaProotNative(
        nativeDir: String, wineTarget: String, prefixDir: String,
        display: String, wineArgs: String, extraEnv: String
    ): Long
}
