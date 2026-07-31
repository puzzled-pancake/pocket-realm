// lifecycle.cpp — drives the real CMaNGOS/realmd startup phases on the worker
// thread, without signals, console, blocking, or process exit.
//
// This file bridges the facade's abstract lifecycle_result to the concrete
// CMaNGOS calls (sMaster.StartDatabasesEmbedded, sMaster.InitWorldEmbedded,
// sMaster.StartNetworkEmbedded, sMaster.StopEmbedded, realmd listener setup).
//
// The client-data gate (O10) is the central honesty mechanism: when the world
// machinery throws a POCKET_FATAL whose message indicates missing .dbc/.map
// data, we classify it as a client-data gate (not a fatal startup error) so
// the realm can report BLOCKED_ON_CLIENT_DATA honestly rather than FAILED.
#include "lifecycle.h"
#include "embed.h"

#include "pocket_realm.h"

// CMaNGOS headers — pulled from the submodule via the include paths the CMake
// target sets up (native/cmangos/src/{game,shared,framework,...}).
#include "Common.h"
#include "Config/Config.h"
#include "Log/Log.h"
#include "Database/DatabaseEnv.h"
#include "World/World.h"
#include "Master.h"
#include "revision_sql.h"
#include "Server/WorldSocket.h"
#include "Network/AsyncListener.hpp"

#include <openssl/provider.h>
#include <openssl/err.h>

#include <atomic>
#include <memory>
#include <thread>
#include <vector>

namespace pocket_realm {

// ---------------------------------------------------------------------------
// realmd_session / world_session are concrete structs owned by the facade.
// They hold the boost::asio resources that must outlive the listener bind but
// be torn down cooperatively at stop.
// ---------------------------------------------------------------------------

// realmd_session is forward-declared in lifecycle.h and defined in
// lifecycle_realmd.cpp (it owns an AuthSocket listener, which is only
// compiled under BUILD_LOGIN_SERVER). lifecycle.cpp treats it as opaque.

struct world_session
{
    bool world_started{false};   // true if we got past SetInitialWorldSettings
    uint32_t net_threads{1};
};

// ---------------------------------------------------------------------------
static bool is_client_data_message(const std::string& msg)
{
    return msg.find("O10") != std::string::npos ||
           msg.find("client data") != std::string::npos ||
           msg.find(".dbc") != std::string::npos ||
           msg.find(".map") != std::string::npos ||
           msg.find("DBC") != std::string::npos;
}

// OpenSSL providers must be loaded once per process before any crypto use.
// Main.cpp does this for the standalone; we do it here for the embedded path.
static std::atomic<bool> g_providers_loaded{false};
static void ensure_openssl_providers()
{
    if (g_providers_loaded.exchange(true)) return;
    OSSL_PROVIDER_load(nullptr, "legacy");   // best-effort; errors logged but non-fatal for our path
    OSSL_PROVIDER_load(nullptr, "default");
}

// ---------------------------------------------------------------------------
lifecycle_result load_config(const char* path, const char* prefix)
{
    lifecycle_result r;
    if (!path || !prefix)
    {
        r.err = REALM_E_INVALID_ARG;
        r.detail = "load_config: null path/prefix";
        return r;
    }
    ensure_openssl_providers();
    sLog.Initialize();
    if (!sConfig.SetSource(path, prefix))
    {
        r.err = REALM_E_INVALID_ARG;
        r.detail = std::string("could not load config: ") + path;
    }
    return r;
}

lifecycle_result start_databases(realm_cond_state conditions[REALM_COND_COUNT])
{
    lifecycle_result r;
    try
    {
        // sMaster is the MaNGOS::Singleton<Master>::Instance(); StartDatabasesEmbedded
        // wraps _StartDB (opens all four SQLite DBs, runs CheckRequiredField on each,
        // reads realmID, clears online accounts). Returns false on any failure.
        if (!sMaster.StartDatabasesEmbedded())
        {
            conditions[REALM_COND_DATABASE_OPEN] = REALM_COND_FALSE;
            conditions[REALM_COND_SCHEMA_COMPATIBLE] = REALM_COND_FALSE;
            r.err = REALM_E_DB;
            r.detail = "Master::StartDatabasesEmbedded failed (db open or schema/version check)";
            return r;
        }
        conditions[REALM_COND_DATABASE_OPEN] = REALM_COND_TRUE;
        conditions[REALM_COND_SCHEMA_COMPATIBLE] = REALM_COND_TRUE;
    }
    catch (...)
    {
        std::exception_ptr ep = std::current_exception();
        std::string msg;
        if (is_fatal_error(ep, &msg))
        {
            r.detail = msg;
            r.err = REALM_E_FATAL_STARTUP;
        }
        else
        {
            r.detail = "exception during database startup";
            r.err = REALM_E_DB;
        }
        conditions[REALM_COND_DATABASE_OPEN] = REALM_COND_FALSE;
        conditions[REALM_COND_SCHEMA_COMPATIBLE] = REALM_COND_FALSE;
    }
    return r;
}

lifecycle_result start_world_machinery(world_session** out,
                                       realm_cond_state conditions[REALM_COND_COUNT],
                                       uint32_t world_threads)
{
    lifecycle_result r;
    auto* ws = new world_session;
    ws->net_threads = world_threads;
    *out = ws;

    try
    {
        bool client_gate = false;
        // SetInitialWorldSettings: this is where the .map/.dbc gates live.
        // Under POCKET_EMBEDDED they throw fatal_error (POCKET_FATAL) rather
        // than exit(). We catch and classify.
        sMaster.InitWorldEmbedded(&client_gate);
        ws->world_started = true;

        // Past the gates: start the world thread + loopback listeners.
        sMaster.StartNetworkEmbedded(world_threads);
        conditions[REALM_COND_WORLD_LOOP_RUNNING] = REALM_COND_TRUE;
        conditions[REALM_COND_LOCAL_ENDPOINTS_LISTENING] = REALM_COND_TRUE;
        conditions[REALM_COND_BOT_SUBSYSTEM_INITIALIZED] = REALM_COND_TRUE;
        return r;
    }
    catch (...)
    {
        std::exception_ptr ep = std::current_exception();
        std::string msg;
        if (is_fatal_error(ep, &msg))
        {
            r.detail = msg;
            if (is_client_data_message(msg))
            {
                // Honest: machinery not up because the client data import (O10)
                // has not run. Report the world-loop triad as blocked, not false.
                r.err = REALM_E_BLOCKED_ON_CLIENT_DATA;
                r.client_data_gate = true;
                conditions[REALM_COND_WORLD_LOOP_RUNNING] = REALM_COND_BLOCKED_ON_CLIENT_DATA;
                conditions[REALM_COND_LOCAL_ENDPOINTS_LISTENING] = REALM_COND_BLOCKED_ON_CLIENT_DATA;
                conditions[REALM_COND_BOT_SUBSYSTEM_INITIALIZED] = REALM_COND_BLOCKED_ON_CLIENT_DATA;
            }
            else
            {
                r.err = REALM_E_FATAL_STARTUP;
            }
        }
        else
        {
            r.detail = "unchecked exception during world machinery startup";
            r.err = REALM_E_INTERNAL;
        }
    }
    return r;
}

void stop_world_machinery(world_session* s)
{
    if (!s) return;
    try
    {
        if (s->world_started)
        {
            // Request cooperative stop and let Master drain the world thread +
            // network threads. World::StopNow flips m_stopEvent; WorldRunnable
            // notices and exits its loop; StartNetworkEmbedded's threads stop.
            World::StopNow(SHUTDOWN_EXIT_CODE);
            sMaster.StopEmbedded();
        }
        else
        {
            // The world machinery never fully started (e.g. it threw on the
            // client-data gate, or _StartDB failed). But _StartDB may still
            // have opened some databases and left their connection pools /
            // delay threads live. StopServer() fully resets each DatabaseType
            // (connections + delay thread) so a second cycle's Initialize sees
            // a fresh object. Mirrors Master::StopEmbedded's DB halt without
            // the world-thread/network drain (there is nothing to drain).
            CharacterDatabase.StopServerEmbedded();
            WorldDatabase.StopServerEmbedded();
            LoginDatabase.StopServerEmbedded();
            LogsDatabase.StopServerEmbedded();
            World::ResetForReinit();
        }
    }
    catch (...)
    {
        // Boundary: never let a stop-phase exception escape. It is logged
        // upstream by the caller's catch in worker_main.
    }
    delete s;
}

lifecycle_result reset_for_reinit(std::string* detail)
{
    lifecycle_result r;
    // Strategy A: fully stop the four mangosd databases so a second _StartDB ->
    // Initialize sees a fresh DatabaseType object. Database::Initialize is NOT
    // re-entrant (it appends to m_pQueryConnections and overwrites m_pAsyncConn/
    // m_pResultQueue without clearing), so we must call StopServer() (which
    // halts the delay thread AND deletes/clears every connection) rather than
    // just HaltDelayThread(). Then clear World's static stop/exit flags so a
    // second WorldRunnable cycle can run. This is the real re-entrancy reset.
    //
    // StopServer is safe on a partially-initialized DB (HaltDelayThread guards
    // on null m_delayThread; the deletes are null-safe), so this works whether
    // cycle 1 opened the DBs fully, partially, or not at all.
    try
    {
        CharacterDatabase.StopServerEmbedded();
        WorldDatabase.StopServerEmbedded();
        LoginDatabase.StopServerEmbedded();
        LogsDatabase.StopServerEmbedded();
        World::ResetForReinit();
    }
    catch (...)
    {
        // StopServer must not throw, but never let an exception escape across
        // this helper (it runs at the ABI boundary).
        if (detail) *detail = "exception during reinit reset";
        r.err = REALM_E_BUSY;
        r.detail = "reinit reset threw; second cycle not safe in this process";
    }
    return r;
}

} // namespace pocket_realm
