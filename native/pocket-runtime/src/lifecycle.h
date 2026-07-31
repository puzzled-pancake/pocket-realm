// lifecycle.h — the actual CMaNGOS/realmd drive functions, decoupled from the
// standalone main()/Master::Run() blocking loop.
//
// These run on the Realm worker thread. Each returns a lifecycle_result that
// the Realm state machine maps onto (realm_state, health conditions, realm_err).
//
// Key principle (DECISIONS #8): nothing here installs a signal handler, reads
// stdin, calls exit(), or blocks forever. Stop is driven by setting World::
// StopNow / the realmd stop flag from request_stop() (cooperative), which the
// worker observes and drains.
#pragma once

#include "pocket_realm.h"

#include <string>

namespace pocket_realm {

// What a lifecycle phase observed. Mirrors realm_err but carries a diagnostic
// string for the log/health blocker. Startup phases set the per-condition
// health outcomes directly on the caller's array.
struct lifecycle_result
{
    realm_err err{REALM_E_OK};
    std::string detail;          // human-readable; logged and surfaced on FAILED

    // True if `err` is the client-data gate (O10) rather than a generic fault.
    // The health mapper uses this to report BLOCKED_ON_CLIENT_DATA vs FALSE.
    bool client_data_gate{false};
};

// Read & load a config file into sConfig. Returns false (with detail) if the
// file is missing/invalid. Prefix is "Mangosd_" for the world conf, "Realmd_"
// for the auth conf.
lifecycle_result load_config(const char* path, const char* prefix);

// Drive Master::_StartDB() for all four SQLite databases, plus the version
// checks. Does NOT call SetInitialWorldSettings (that needs client data — see
// start_world_machinery). Updates conditions[REALM_COND_DATABASE_OPEN] and
// conditions[REALM_COND_SCHEMA_COMPATIBLE].
lifecycle_result start_databases(realm_cond_state conditions[REALM_COND_COUNT]);

// Bring realmd up: load its config, verify LoginDatabase (the world _StartDB
// already opened it), initialize the realm list, bind the AuthSocket listener
// on loopback. Runs the listener on a facade-owned io_context. Sets conditions
// [REALM_COND_AUTH_READY] when the listener is bound. Does NOT install signals.
//
// Returns an opaque realmd_state* the caller owns; pass it to stop_realmd().
// Defined in lifecycle_realmd.cpp (it owns an AuthSocket listener compiled
// under BUILD_LOGIN_SERVER); forward-declared here to keep asio out of callers.
struct realmd_state;
lifecycle_result start_realmd(realmd_state** out);
void stop_realmd(realmd_state* s);

// Attempt the world machinery: World::SetInitialWorldSettings + WorldRunnable +
// the world/auth listeners. This is the phase that hits the client-data gates
// (.map / .dbc). Under POCKET_EMBEDDED those gates throw fatal_error instead
// of exit()ing; this function catches the throw and returns a lifecycle_result
// with client_data_gate=true. It also sets the world-loop triad of conditions
// to BLOCKED_ON_CLIENT_DATA in that case.
//
// Returns a world_session* (always non-null on REALM_E_OK, may be non-null on
// REALM_E_BLOCKED_ON_CLIENT_DATA if some threads were already started) so the
// caller can drain them via stop_world_machinery().
struct world_session;
lifecycle_result start_world_machinery(world_session** out,
                                       realm_cond_state conditions[REALM_COND_COUNT],
                                       uint32_t world_threads);
void stop_world_machinery(world_session* s);

// Reset the process-global state that CMaNGOS leaves behind after a stop, so a
// second start cycle can re-initialize the singletons. This is the Strategy A
// re-entrancy path: it resets World::m_stopEvent/m_ExitCode, the four Database
// globals' delay-thread flags, and re-arms the singleton "not yet created"
// state. Returns false (with detail) if reset is not safely possible — in which
// case the caller reports REALM_E_BUSY (Strategy B). Safe to call after a
// failed/partial start.
lifecycle_result reset_for_reinit(std::string* detail);

} // namespace pocket_realm
