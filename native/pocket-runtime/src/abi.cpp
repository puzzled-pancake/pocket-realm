// abi.cpp — the extern "C" ABI surface. Thin wrapper that owns the opaque
// realm_handle lifetime and converts every C++ throw into a realm_err.
#include "pocket_realm.h"
#include "realm.h"

#include <cstring>
#include <new>
#include <stdexcept>

using pocket_realm::Realm;

// Catch-all boundary: any C++ exception crossing the ABI is a bug (native.md
// forbids it), but we must never let it propagate into JNI/FFI undefined
// behavior. Map to REALM_E_INTERNAL.
#define POCKET_CATCH_ALL                                  \
    catch (std::bad_alloc&) { return REALM_E_INTERNAL; } \
    catch (...) { return REALM_E_INTERNAL; }

static inline Realm* h2p(realm_handle h) { return reinterpret_cast<Realm*>(h); }

extern "C" {

realm_err realm_create(const realm_config* config, realm_handle* out)
{
    if (!config || !out) return REALM_E_INVALID_ARG;
    *out = nullptr;
    try
    {
        Realm* r = new Realm();
        realm_err e = r->configure(*config);
        if (e != REALM_E_OK)
        {
            delete r;
            return e;
        }
        *out = reinterpret_cast<realm_handle>(r);
        return REALM_E_OK;
    }
    POCKET_CATCH_ALL
}

realm_err realm_start(realm_handle h)
{
    if (!h) return REALM_E_INVALID_ARG;
    try { return h2p(h)->start(); }
    POCKET_CATCH_ALL
}

realm_err realm_get_health(realm_handle h, realm_health* out)
{
    if (!h || !out) return REALM_E_INVALID_ARG;
    try { return h2p(h)->get_health(out); }
    POCKET_CATCH_ALL
}

realm_err realm_command(realm_handle h, const char* cmd, int len)
{
    if (!h || !cmd) return REALM_E_INVALID_ARG;
    try { return h2p(h)->command(cmd, len); }
    POCKET_CATCH_ALL
}

realm_err realm_save(realm_handle h, realm_save_mode mode)
{
    if (!h) return REALM_E_INVALID_ARG;
    try { return h2p(h)->save(mode); }
    POCKET_CATCH_ALL
}

realm_err realm_checkpoint(realm_handle h)
{
    if (!h) return REALM_E_INVALID_ARG;
    try { return h2p(h)->checkpoint(); }
    POCKET_CATCH_ALL
}

realm_err realm_request_stop(realm_handle h, realm_stop_reason reason)
{
    if (!h) return REALM_E_INVALID_ARG;
    try { return h2p(h)->request_stop(reason); }
    POCKET_CATCH_ALL
}

realm_err realm_join(realm_handle h, uint64_t timeout_ms)
{
    if (!h) return REALM_E_INVALID_ARG;
    try { return h2p(h)->join(timeout_ms); }
    POCKET_CATCH_ALL
}

realm_err realm_get_state(realm_handle h, realm_state* out)
{
    if (!h || !out) return REALM_E_INVALID_ARG;
    try { return h2p(h)->get_state(out); }
    POCKET_CATCH_ALL
}

void realm_destroy(realm_handle h)
{
    if (!h) return;
    try { delete h2p(h); } catch (...) {}
}

const char* realm_err_str(realm_err e)
{
    switch (e)
    {
        case REALM_E_OK: return "ok";
        case REALM_E_INVALID_ARG: return "invalid argument";
        case REALM_E_WRONG_STATE: return "wrong state for this call";
        case REALM_E_FATAL_STARTUP: return "fatal startup error";
        case REALM_E_DB: return "database error";
        case REALM_E_BLOCKED_ON_CLIENT_DATA: return "blocked on client data import (O10)";
        case REALM_E_TIMEOUT: return "timed out";
        case REALM_E_BUSY: return "realm busy (re-init not available in this process)";
        case REALM_E_INTERNAL: return "internal error";
    }
    return "unknown error";
}

const char* realm_state_str(realm_state s)
{
    switch (s)
    {
        case REALM_STATE_CREATED: return "created";
        case REALM_STATE_STARTING: return "starting";
        case REALM_STATE_RUNNING: return "running";
        case REALM_STATE_SAVING: return "saving";
        case REALM_STATE_STOPPING: return "stopping";
        case REALM_STATE_STOPPED: return "stopped";
        case REALM_STATE_FAILED: return "failed";
    }
    return "";
}

} // extern "C"
