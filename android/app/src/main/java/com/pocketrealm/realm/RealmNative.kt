package com.pocketrealm.realm

/**
 * JNI shim for the embeddable realm C ABI (native/pocket-runtime, O04).
 *
 * This is the Kotlin side of the versioned C boundary defined in
 * `schemas/abi/pocket_realm.h`. The native `libpocketrealm.so` is the
 * embeddable CMaNGOS/Playerbots lifecycle facade: it owns the realm worker
 * thread, drives start/health/save/stop without process exit / signals /
 * console, and reports the six PLAN.md A2 health conditions honestly.
 *
 * O04 delivers this shim + a guarded round-trip test. Full supervisor↔native
 * wiring (health-gated Running promotion, foreground-service native bring-up,
 * loopback enforcement) is O05.
 *
 * Threading: the native calls are safe to invoke from any thread. `start` is
 * non-blocking (spawns the native worker); `join` blocks. The supervisor will
 * marshal these onto its own scope in O05. Handle lifetime is explicit: each
 * `create` must be paired with exactly one `destroy`.
 *
 * If the native library is absent (e.g. running in a host JVM unit test without
 * the .so), [load] throws UnsatisfiedLinkError; callers that want graceful
 * degradation catch it. This mirrors the "no fake success" rule: a missing
 * native layer is reported, not silently stubbed.
 */
object RealmNative {

    /** Mirror of realm_state in the C ABI. Do not renumber — matches the C enum. */
    object State {
        const val CREATED = 0
        const val STARTING = 1
        const val RUNNING = 2
        const val SAVING = 3
        const val STOPPING = 4
        const val STOPPED = 5
        const val FAILED = 6
    }

    /** Mirror of realm_err. 0 = OK. */
    object Err {
        const val OK = 0
        const val INVALID_ARG = 1
        const val WRONG_STATE = 2
        const val FATAL_STARTUP = 3
        const val DB = 4
        const val BLOCKED_ON_CLIENT_DATA = 5
        const val TIMEOUT = 6
        const val BUSY = 7
        const val INTERNAL = 8
    }

    /** The six health condition indices. Matches realm_condition. */
    object Cond {
        const val DATABASE_OPEN = 0
        const val SCHEMA_COMPATIBLE = 1
        const val AUTH_READY = 2
        const val WORLD_LOOP_RUNNING = 3
        const val LOCAL_ENDPOINTS_LISTENING = 4
        const val BOT_SUBSYSTEM_INITIALIZED = 5
        const val COUNT = 6
    }

    object CondState {
        const val FALSE = 0
        const val TRUE = 1
        const val UNKNOWN = 2
        const val BLOCKED_ON_CLIENT_DATA = 3
    }

    /** Load the native library. Call once at app/process start. */
    fun load() {
        System.loadLibrary("pocketrealm")
    }

    // ---- C ABI surface (see schemas/abi/pocket_realm.h) ----
    // Handles are Long (the C realm_t*). Strings are UTF-8. Lengths explicit.
    // Each returns a realm_err code; 0 = REALM_E_OK.

    external fun realmCreate(cfg: RealmConfig, out: LongArray): Int
    external fun realmStart(handle: Long): Int
    external fun realmGetHealth(handle: Long, out: RealmHealth): Int
    external fun realmCommand(handle: Long, cmd: String): Int
    external fun realmSave(handle: Long, mode: Int): Int
    external fun realmCheckpoint(handle: Long): Int
    external fun realmRequestStop(handle: Long, reason: Int): Int
    external fun realmJoin(handle: Long, timeoutMs: Long): Int
    external fun realmGetState(handle: Long, out: IntArray): Int
    external fun realmDestroy(handle: Long)

    /**
     * Config for realmCreate. Mirrors the C realm_config struct. Fields must
     * stay in lockstep with schemas/abi/pocket_realm.h (the JNI side reads
     * them by name via JNI field ids — order here is not load-bearing, but the
     * names must match the C struct exactly).
     */
    class RealmConfig(
        @JvmField var abiVersion: Int = ABI_VERSION,
        @JvmField var dataDir: String = "",
        @JvmField var dbDir: String = "",
        @JvmField var worldConf: String = "",
        @JvmField var realmdConf: String = "",
        @JvmField var playerbotConf: String? = null,
        @JvmField var worldThreads: Int = 1,
    )

    /**
     * Health snapshot filled by realmGetHealth. `conditions[i]` is the
     * CondState for condition i; `allReady` is 1 iff every condition is TRUE.
     * `blockerText` is null when allReady, else a short reason.
     */
    class RealmHealth(
        @JvmField var conditions: IntArray = IntArray(Cond.COUNT),
        @JvmField var allReady: Int = 0,
        @JvmField var blockerText: String? = null,
    )

    const val ABI_VERSION = 1
}
