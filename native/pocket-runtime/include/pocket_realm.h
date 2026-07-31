/*
 * pocket_realm.h — Pocket Realm embeddable realm lifecycle C ABI.
 *
 * This is the versioned boundary between the Kotlin/Compose Android supervisor
 * (and, later, the connected Rust Realm Kernel) and the native CMaNGOS/Playerbots
 * realm. It is the ONLY crossing point for the embedded path.
 *
 * Hard invariants enforced by the implementation (.claude/rules/native.md and
 * DECISIONS.md #7/#8):
 *   - Opaque handles (realm_t*). The caller never dereferences realm internals.
 *   - Explicit ownership: realm_create allocates, realm_destroy frees. No aliasing.
 *   - Error codes, never errno/exceptions. No C++ exception, STL type, or thread
 *     crosses this boundary; the facade catches every native throw and converts
 *     it to a realm_err.
 *   - Bounded buffers: every string parameter carries an explicit length, or is
 *     NUL-terminated with a documented max. Log text is delivered through a
 *     callback with an explicit (buf,len) pair.
 *   - No process exit(), console-only control, or signal-only shutdown is
 *     reachable through these calls. realm_request_stop drives World::StopNow /
 *     realmd's stop flag directly; realm_join blocks the caller until teardown.
 *
 * Versioning: realm_config::abi_version must equal POCKET_REALM_ABI_VERSION or
 * realm_create returns REALM_E_INVALID_ARG. Adding trailing struct fields or new
 * enum values is a minor bump; changing existing field layout is a major bump
 * (ABI version increments, old call sites fail loudly).
 *
 * Stability: callers (Kotlin JNI, Rust FFI) depend on this layout. Do not
 * renumber existing enum values. This header is canonical; the build copies it
 * into the native runtime include dir.
 */
#ifndef POCKET_REALM_ABI_H
#define POCKET_REALM_ABI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Bump on any ABI-breaking layout change. See versioning note above. */
#define POCKET_REALM_ABI_VERSION 1

/* Opaque realm handle. NULL is the "no realm" sentinel. */
typedef struct realm_t* realm_handle;

/* ----------------------------------------------------------------- logging */

/*
 * Log severity, mirroring com.pocketrealm.log.AppLog.Level order so the Kotlin
 * side can map directly. Delivered via realm_config::log.
 */
typedef enum {
    REALM_LOG_DEBUG = 0,
    REALM_LOG_INFO = 1,
    REALM_LOG_WARN = 2,
    REALM_LOG_ERROR = 3
} realm_log_level;

/*
 * Caller-provided log sink. The runtime calls it with NUL-terminated text in
 * `msg` (length `len`, which equals strlen(msg)); `len` is passed explicitly so
 * binary-safe sinks are possible. Returning non-zero is ignored today (reserved
 * for future flow-control). Called on arbitrary native worker threads; the sink
 * must be thread-safe and non-blocking.
 */
typedef void (*realm_log_fn)(void* user, realm_log_level level,
                             const char* msg, int len);

/* ----------------------------------------------------------------- state */

/*
 * Mirrors com.pocketrealm.realm.RealmState. The supervisor on the Kotlin side
 * owns the authoritative state machine; this is the native projection used for
 * health/state queries before O05 wires the two together. Do not renumber.
 */
typedef enum {
    REALM_STATE_CREATED = 0,
    REALM_STATE_STARTING = 1,
    REALM_STATE_RUNNING = 2,          /* machinery up; health may still be degraded */
    REALM_STATE_SAVING = 3,
    REALM_STATE_STOPPING = 4,
    REALM_STATE_STOPPED = 5,
    REALM_STATE_FAILED = 6
} realm_state;

/* --------------------------------------------------------------- health */

/*
 * The six PLAN.md A2 health conditions. Mirrors
 * com.pocketrealm.realm.HealthCondition. Indices are stable; do not renumber.
 */
typedef enum {
    REALM_COND_DATABASE_OPEN = 0,
    REALM_COND_SCHEMA_COMPATIBLE = 1,
    REALM_COND_AUTH_READY = 2,
    REALM_COND_WORLD_LOOP_RUNNING = 3,
    REALM_COND_LOCAL_ENDPOINTS_LISTENING = 4,
    REALM_COND_BOT_SUBSYSTEM_INITIALIZED = 5,
    REALM_COND_COUNT = 6
} realm_condition;

/*
 * Per-condition status. UNKNOWN means "not yet evaluated" (e.g. queried mid-
 * startup before the condition is reached). BLOCKED_ON_CLIENT_DATA is the
 * honest status for conditions that hard-fail on missing proprietary client
 * data (.dbc/.map, the O10 import); the runtime never reports TRUE for a
 * condition it has not genuinely observed. Do not renumber.
 */
typedef enum {
    REALM_COND_FALSE = 0,
    REALM_COND_TRUE = 1,
    REALM_COND_UNKNOWN = 2,
    REALM_COND_BLOCKED_ON_CLIENT_DATA = 3
} realm_cond_state;

/*
 * Health snapshot. `conditions[i]` corresponds to realm_condition i.
 * `all_ready` is 1 iff every condition is TRUE. For O04 the world-loop triad
 * reports BLOCKED_ON_CLIENT_DATA, so all_ready is honestly 0 until O10; the
 * `blocker_text` (a caller-owned buffer of `blocker_cap` bytes, NUL-terminated)
 * explains why. The runtime copies the current blocker text into `blocker_text`
 * under the health lock, so the caller owns the buffer for as long as it needs
 * — there is no dangling-pointer lifetime hazard across calls.
 */
typedef struct {
    realm_cond_state conditions[REALM_COND_COUNT];
    int all_ready;
    char* blocker_text;  /* caller-allocated; runtime strncpy's into it */
    int blocker_cap;     /* capacity of blocker_text, including NUL */
} realm_health;

/* --------------------------------------------------------------- errors */

/*
 * All calls return REALM_E_OK on success or one of these. realm_err_str()
 * returns a short NUL-terminated description (static storage).
 */
typedef enum {
    REALM_E_OK = 0,
    REALM_E_INVALID_ARG = 1,          /* NULL handle/bad pointer/wrong abi_version */
    REALM_E_WRONG_STATE = 2,          /* call not legal for current realm_state */
    REALM_E_FATAL_STARTUP = 3,        /* native startup threw (exit() rerouted); detail in log */
    REALM_E_DB = 4,                   /* database open/schema/version check failed */
    REALM_E_BLOCKED_ON_CLIENT_DATA = 5,/* reached a hard client-data gate (O10) */
    REALM_E_TIMEOUT = 6,              /* realm_join deadline elapsed before teardown finished */
    REALM_E_BUSY = 7,                 /* re-init blocked (see DECISIONS.md if Strategy B) */
    REALM_E_INTERNAL = 8              /* unchecked native exception caught at the boundary */
} realm_err;

/* --------------------------------------------------------------- config */

/*
 * realm_create configuration. All `const char*` paths are NUL-terminated UTF-8
 * and copied by the runtime; the caller may free/reuse them after realm_create
 * returns. Paths use '/' separators.
 *
 *   data_dir      immutable content root (the realm's DataDir). For O04 this
 *                 is where O10 will later place dbc/ and maps/; its absence is
 *                 what makes the world-loop health conditions report
 *                 BLOCKED_ON_CLIENT_DATA rather than the process exiting.
 *   db_dir        directory for the four SQLite databases (mangos/characters/
 *                 realmd/logs). The runtime writes/reads <db_dir>/<name>.sqlite.
 *   world_conf    path to a generated mangosd.conf (loopback bind, etc.).
 *   realmd_conf   path to a generated realmd.conf.
 *   playerbot_conf path to a generated aiplayerbot.conf (may be NULL to skip).
 *   world_threads network thread count (>=1); 0 -> runtime default.
 *
 * `log` may be NULL (logs are then dropped). `log_user` is passed back verbatim.
 */
typedef struct {
    uint32_t abi_version;
    const char* data_dir;
    const char* db_dir;
    const char* world_conf;
    const char* realmd_conf;
    const char* playerbot_conf;
    uint32_t world_threads;
    realm_log_fn log;
    void* log_user;
} realm_config;

/* --------------------------------------------------------------- save */

/* realm_save mode. 0 = normal periodic save; 1 = shutdown flush (drains all). */
typedef enum {
    REALM_SAVE_NORMAL = 0,
    REALM_SAVE_SHUTDOWN_FLUSH = 1
} realm_save_mode;

/* realm_request_stop reason. Mirrors SaveReason where applicable. */
typedef enum {
    REALM_STOP_USER_SAVE_EXIT = 0,
    REALM_STOP_FORCED = 1,
    REALM_STOP_RESTART = 2
} realm_stop_reason;

/* --------------------------------------------------------------- API */

/*
 * Allocate an opaque realm in REALM_STATE_CREATED. Does NOT start anything.
 * On success *out is non-NULL. On failure *out is set to NULL.
 */
realm_err realm_create(const realm_config* config, realm_handle* out);

/*
 * Begin bring-up. NON-BLOCKING: spawns the native worker and returns once the
 * realm has moved to REALM_STATE_STARTING (or REALM_STATE_FAILED). Poll
 * realm_state/realm_health to observe readiness. Legal from CREATED/STOPPED/
 * FAILED; REALM_E_WRONG_STATE otherwise.
 */
realm_err realm_start(realm_handle h);

/*
 * Snapshot current health into *out. Legal in any state; conditions not yet
 * evaluated report UNKNOWN (or BLOCKED_ON_CLIENT_DATA for the world-loop triad
 * once the client-data gate has been reached). The caller must set out->
 * blocker_text (a buffer) and out->blocker_cap before calling; the runtime
 * NUL-terminates the blocker text (or an empty string) into it.
 */
realm_err realm_get_health(realm_handle h, realm_health* out);

/*
 * Issue a server console command (the same `.server`/chat syntax the RA/SOAP
 * channels funnel through sWorld.QueueCliCommand). `len` is the command length
 * (or -1 if `cmd` is NUL-terminated). Output is delivered asynchronously via
 * the log callback. Legal only in RUNNING/SAVING.
 */
realm_err realm_command(realm_handle h, const char* cmd, int len);

/*
 * Request a save. NORMAL queues a periodic save; SHUTDOWN_FLUSH drains every
 * durable writer. Legal in RUNNING/SAVING.
 */
realm_err realm_save(realm_handle h, realm_save_mode mode);

/* Force a database checkpoint now (WAL flush). Legal in RUNNING/SAVING. */
realm_err realm_checkpoint(realm_handle h);

/*
 * Request cooperative stop. Sets the world/realmd stop flags; the worker
 * drains and tears down. Pair with realm_join to wait for completion. Legal
 * from STARTING/RUNNING/SAVING; transitions to STOPPING.
 */
realm_err realm_request_stop(realm_handle h, realm_stop_reason reason);

/*
 * BLOCKING: wait until the realm reaches STOPPED (or FAILED) or `timeout_ms`
 * elapses. timeout_ms == 0 means "return current state immediately";
 * UINT64_MAX means "wait forever". Returns REALM_E_TIMEOUT if the deadline
 * elapsed before teardown completed (the realm keeps tearing down in the
 * background; call realm_join again or realm_state to poll).
 */
realm_err realm_join(realm_handle h, uint64_t timeout_ms);

/* Write the current realm_state to *out. */
realm_err realm_get_state(realm_handle h, realm_state* out);

/*
 * Release the handle. Must be paired with exactly one realm_create. No-op if h
 * is NULL. If the realm is not yet STOPPED, this requests a cooperative stop
 * and joins the worker before freeing — the worker is always fully joined
 * (never detached), since it captures the handle's internals. A destroy that
 * cannot complete teardown within its internal bound is a fatal invariant
 * violation (surfaced, not silently leaked). Pair realm_request_stop +
 * realm_join before realm_destroy for bounded caller-controlled teardown.
 */
void realm_destroy(realm_handle h);

/* NUL-terminated short description of an error code (static storage). */
const char* realm_err_str(realm_err e);

/* NUL-terminated short name of a state (static storage); "" if invalid. */
const char* realm_state_str(realm_state s);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* POCKET_REALM_ABI_H */
