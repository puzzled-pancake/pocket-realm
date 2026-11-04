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

    /** The world's player limit (mangosd PlayerLimit, both conf twins):
     * past it the realm queues logins. Public listings show it. */
    const val WORLD_PLAYER_LIMIT = 100

    /**
     * The remote-account provisioning listener's TCP port
     * (design-remote-accounts, open question resolved here: the
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

    /** Whether an account-creation seam must write the per-flavor expansion
     * for this native result (R1-arch-1, android-core,
     * serious): the HOST RETRY LANE is designed — a failed relay op returns
     * the invite row to SUBMITTED and the host retries, so the second
     * attempt lands on ACCOUNT_EXISTS (10) with the row present. The old
     * `if (rc == 0)` guard skipped the addon write on exactly that leg,
     * birthing a silently classic friend on a tbc/wotlk host while the
     * accepted answer reported the active flavor's expansion. The decision
     * mirrors the accepted shape of accountResult (rc == 0, or
     * ACCOUNT_EXISTS with the row verified present): an accepted answer
     * always carries a real addon write, never a skipped one. Pure and
     * JVM-testable; [accountExists] is the accountInfoNative presence check
     * the seam computes before deciding. */
    fun shouldWriteExpansion(createRc: Int, accountExists: Boolean): Boolean =
        createRc == 0 || (createRc == ACCOUNT_EXISTS_CODE && accountExists)

    const val ACCOUNT_EXISTS_CODE = 10

    /** The VERIFIER lane's expansion-write decision (R3-1, android-core
     * ): on ACCOUNT_EXISTS the addon write converges
     * the row ONLY under the host's attempt-bookkeeping authorization —
     * (rc, exists) alone cannot distinguish the designed HOST RETRY (the row
     * is our attempt-1 create) from an IDENTITY COLLISION (a username that
     * was already on the realm; the submitted s/v were never proven against
     * it). The round-2 seam OR-ed this conjunctive leg with
     * [shouldWriteExpansion], whose rc==10 leg is authorization-blind — the
     * disjunction made the gate a no-op and every collision still wrote the
     * existing row and answered ok=true. Behavior: (0,_,_)=true;
     * (10,true,false)=false (the collision refusal);
     * (10,true,true)=true (the designed retry); (10,false,*)=false;
     * every other rc=false. Pure and JVM-testable — the behavioral truth
     * table is pinned in AccountExpansionWiringTest. */
    fun verifierExpansionWrite(
        createRc: Int,
        accountExists: Boolean,
        allowExistingAccount: Boolean,
    ): Boolean =
        createRc == 0 ||
            (createRc == ACCOUNT_EXISTS_CODE && accountExists && allowExistingAccount)

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
     * Design-tunnel: the bind value the conf writers and the server
     * services receive. The typed path parses through the strict
     * [RealmEndpoint] grammar (loopback or canonical private/link-local
     * IPv4); the ONE extra literal accepted is
     * [RealmEndpoint.WILDCARD_BIND] — "0.0.0.0", the raw conf value tunnel
     * hosting binds so the advertised tunnel address is actually reachable
     * (design-tunnel keeps it out of the typed path deliberately:
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
     * The realmd login-lockout emission (design-remote-accounts /,
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
