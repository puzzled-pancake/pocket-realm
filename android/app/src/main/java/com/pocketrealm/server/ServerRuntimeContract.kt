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

    /**
     * The remote-account provisioning listener's TCP port
     * (design-remote-accounts §6, open question §13.4 resolved here: the
     * lane ships one exact port, adjacent to the realm port, so the
     * reference firewall rule names three ports — 3724/8085/3725 — and no
     * range). The listener is a property of a hosting session with
     * friend access enabled: bound only while such a start is up, never
     * while friend access is off.
     */
    const val PROVISION_PORT = 3725

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

    /**
     * Design-tunnel §4.4.2: the bind value the conf writers and the server
     * services receive. The typed path parses through the strict
     * [RealmEndpoint] grammar (loopback or canonical private/link-local
     * IPv4); the ONE extra literal accepted is
     * [RealmEndpoint.WILDCARD_BIND] — "0.0.0.0", the raw conf value tunnel
     * hosting binds so the advertised tunnel address is actually reachable
     * (design-tunnel §4.4.2 keeps it out of the typed path deliberately:
     * it is never a [RealmEndpoint]). Digits and dots only, interpolated
     * into `BindIP = "..."`; anything else is refused before any conf
     * exists. A wildcard bind is a NON-loopback bind, so the D11 lockout
     * below rides every tunnel start too.
     */
    fun resolveBindAddress(bindAddress: String): String =
        if (bindAddress == com.pocketrealm.supervisor.RealmEndpoint.WILDCARD_BIND) {
            com.pocketrealm.supervisor.RealmEndpoint.WILDCARD_BIND
        } else {
            com.pocketrealm.supervisor.RealmEndpoint.parseStored(bindAddress).address
        }

    /**
     * The realmd login-lockout emission (design-remote-accounts §4.3/§6.3,
     * the android twin of the desktop ServerRuntimeFiles D11 block): the
     * WrongPass keys are emitted WHEN AND ONLY WHEN the bind is not
     * loopback. Every lane that exposes TCP 3724 to anything but loopback
     * gets realmd's own IP-autoban lockout; the loopback-only LOCAL lane
     * omits it, because the autoban inserts the SOURCE IP into ip_banned
     * (AuthSocket.cpp:692-694) and the challenge's ip_banned gate
     * (:415-423) matches loopback too — an unconditional emission would let
     * ten mistyped LOCAL passwords ban 127.0.0.1 for BanTime seconds.
     * [bindAddress] is the canonical address already parsed by
     * com.pocketrealm.supervisor.RealmEndpoint upstream; loopback here is
     * the exact [RealmEndpoint.LOOPBACK_ADDRESS] literal.
     */
    fun wrongPassLockoutConfLines(bindAddress: String): String {
        if (bindAddress == com.pocketrealm.supervisor.RealmEndpoint.LOOPBACK_ADDRESS) return ""
        return """
            WrongPass.MaxCount = 10
            WrongPass.BanTime = 900
            WrongPass.BanType = 0
        """.trimIndent() + "\n"
    }
}
