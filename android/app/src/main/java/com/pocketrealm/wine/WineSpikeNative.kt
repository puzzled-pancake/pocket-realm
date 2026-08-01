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
}
