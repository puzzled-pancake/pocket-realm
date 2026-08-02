package com.pocketrealm.server

internal object ServerRuntimeContract {
    const val ABI_VERSION = 1L
    const val CONTROL_SCHEMA = 1
    const val RUNTIME_BUILD_ID = "o09-cmangos-c096bada-nobots-v1"
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
}
