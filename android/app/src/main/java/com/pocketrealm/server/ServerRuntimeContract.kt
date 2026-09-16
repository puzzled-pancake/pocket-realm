package com.pocketrealm.server

import com.pocketrealm.BuildConfig

internal object ServerRuntimeContract {
    const val ABI_VERSION = 1L
    const val CONTROL_SCHEMA = 1

    /**
     * Runtime telltale for the staged native realm runtime. Generated at
     * build time from the lane lockfile (schemas/realm-runtime-
     * lockfile*.json, see validateNativeRuntimeFreshness in
     * android/app/build.gradle.kts) and baked into BuildConfig, so it changes
     * whenever the pinned .so bytes or the cmangos/playerbots source pins
     * change. A harness session compares this id (relay ping, realm-status,
     * world-status) against the id derivable from the CURRENT lockfile and
     * refuses to continue on mismatch, catching a stale APK at attach time
     * instead of discovering missing JNI ops mid-run.
     */
    val RUNTIME_BUILD_ID: String = BuildConfig.NATIVE_RUNTIME_BUILD_ID

    /** Full CMaNGOS source commit pin the packaged runtime was built from. */
    val NATIVE_CMANGOS_COMMIT: String = BuildConfig.NATIVE_CMANGOS_COMMIT

    /** Full playerbots source commit pin the packaged runtime was built from. */
    val NATIVE_PLAYERBOTS_COMMIT: String = BuildConfig.NATIVE_PLAYERBOTS_COMMIT

    const val REALM_PORT = 3724
    const val WORLD_PORT = 8085
    const val CONTROL_TIMEOUT_MS = 30_000L

    const val STOPPED = 0L
    const val STARTING = 1L
    const val READY = 2L
    const val SAVING = 3L
    const val STOPPING = 4L
    const val FAILED = 5L

    private val states = arrayOf("STOPPED", "STARTING", "READY", "SAVING", "STOPPING", "FAILED")
    private val errors = arrayOf(
        "OK", "INVALID_ARGUMENT", "WRONG_STATE", "CONFIG", "DB_CONNECT", "DB_REVISION",
        "DATA_MISSING", "DATA_BUILD", "PORT_IN_USE", "TIMEOUT", "ACCOUNT_EXISTS",
        "ACCOUNT_REJECTED", "INTERNAL",
    )

    fun stateName(value: Long): String = states.getOrNull(value.toInt()) ?: "UNKNOWN"
    fun errorName(value: Long): String = errors.getOrNull(value.toInt()) ?: "UNKNOWN"

    fun requireAccountToken(label: String, value: String) {
        require(value.length in 1..16) { "$label must contain 1..16 characters" }
        require(value.all { it.isLetterOrDigit() && it.code < 128 }) {
            "$label must contain ASCII letters or digits only"
        }
    }

    fun requireCharacterName(value: String) {
        require(value.length in 2..12) { "characterName must contain 2..12 characters" }
        require(value.all { it.isLetter() && it.code < 128 }) {
            "characterName must contain ASCII letters only"
        }
    }
}
