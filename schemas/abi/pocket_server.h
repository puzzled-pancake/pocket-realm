/*
 * Versioned O09 component ABI. Each implementation is loaded in its own
 * Android process; no C++ object or unbounded caller-owned payload crosses
 * this boundary.
 */
#ifndef POCKET_SERVER_ABI_H
#define POCKET_SERVER_ABI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define POCKET_SERVER_ABI_VERSION 1u
#define POCKET_SERVER_ERROR_MAX 512u

typedef enum {
    POCKET_SERVER_STOPPED = 0,
    POCKET_SERVER_STARTING = 1,
    POCKET_SERVER_READY = 2,
    POCKET_SERVER_SAVING = 3,
    POCKET_SERVER_STOPPING = 4,
    POCKET_SERVER_FAILED = 5
} pocket_server_state;

typedef enum {
    POCKET_SERVER_OK = 0,
    POCKET_SERVER_INVALID_ARGUMENT = 1,
    POCKET_SERVER_WRONG_STATE = 2,
    POCKET_SERVER_CONFIG = 3,
    POCKET_SERVER_DB_CONNECT = 4,
    POCKET_SERVER_DB_REVISION = 5,
    POCKET_SERVER_DATA_MISSING = 6,
    POCKET_SERVER_DATA_BUILD = 7,
    POCKET_SERVER_PORT_IN_USE = 8,
    POCKET_SERVER_TIMEOUT = 9,
    POCKET_SERVER_ACCOUNT_EXISTS = 10,
    POCKET_SERVER_ACCOUNT_REJECTED = 11,
    POCKET_SERVER_INTERNAL = 12
} pocket_server_error;

typedef struct {
    uint32_t abi_version;
    pocket_server_state state;
    pocket_server_error error;
    uint64_t heartbeat_ms;
    uint64_t tick_count;
    uint32_t last_tick_ms;
    uint32_t max_tick_ms;
    uint32_t active_sessions;
    char detail[POCKET_SERVER_ERROR_MAX];
} pocket_server_status;

const char* pocket_server_error_string(pocket_server_error error);

#ifdef __cplusplus
}
#endif
#endif
