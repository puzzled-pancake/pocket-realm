package com.pocketrealm.database

import com.pocketrealm.log.AppLog

/** Process-local JNI boundary used only by the non-exported `:database` service. */
internal object DatabaseNative {
    private const val TAG = "DatabaseNative"

    fun load() {
        System.loadLibrary("wine_spike")
        AppLog.i(TAG, "native database process supervisor loaded")
    }

    external fun runGlibcProgramNative(
        nativeDir: String,
        executable: String,
        argv0: String,
        workingDir: String,
        runtimeRoot: String,
        libraryPath: String,
        argsBlob: String,
        envBlob: String,
        stdinPath: String,
        timeoutMs: Int,
        trackAsDaemon: Boolean,
    ): String

    /** Direct APK-managed Bionic runner used by the arm64 live-device lane. */
    external fun runBionicProgramNative(
        nativeDir: String,
        executable: String,
        argv0: String,
        workingDir: String,
        runtimeRoot: String,
        libraryPath: String,
        argsBlob: String,
        envBlob: String,
        stdinPath: String,
        timeoutMs: Int,
        trackAsDaemon: Boolean,
    ): String

    external fun cancelActiveGlibcProgramNative(): Boolean
}
