// lifecycle_realmd.cpp — drives realmd's auth listener in-process.
//
// realmd's main() (realmd/Main.cpp) is a blocking monolith with file-scope
// globals (stopEvent, context, LoginDatabase) and signal handlers. We replicate
// just the listener setup here, on a facade-owned io_context, with a facade-
// owned stop flag instead of signals. This makes AUTH_READY genuinely green:
// a real AuthSocket listener is bound on loopback, ready to accept the test
// login that PLAN.md A2's exit criterion requires.
//
// realmd's LoginDatabase global is distinct from mangosd's; both point at the
// same SQLite realmd.db file (read/write). The realm's _StartDB already opened
// LoginDatabase for the world side; realmd's StartDB re-initializes it. Since
// DatabaseSqlite opens its own sqlite3* per connection, this is safe.
#include "lifecycle.h"
#include "embed.h"

#include "Common.h"
#include "Config/Config.h"
#include "Log/Log.h"
#include "Database/DatabaseEnv.h"
#include "RealmList.h"
#include "AuthCodes.h"
#include "AuthSocket.h"
#include "SystemConfig.h"
#include "revision_sql.h"
#include "Network/AsyncListener.hpp"

#include <atomic>
#include <memory>
#include <thread>
#include <vector>

// realmd's globals are defined in realmd/Main.cpp; for the embedded path we
// don't link that TU (it has main()), so we provide our own stop flag here.
// realmd's StartDB is also file-local; we re-implement the minimal version.

namespace pocket_realm {

struct realmd_state
{
    // shared (de-vibe N7): listener threads capture the io_context by value;
    // unique_ptr here would dangle with the raw state ownership below.
    std::shared_ptr<boost::asio::io_context> io;
    std::unique_ptr<MaNGOS::AsyncListener<AuthSocket>> listener;
    std::vector<std::thread> threads;
    std::atomic<bool> stop{false};
};

// realmd uses the SAME LoginDatabase the world _StartDB already opened (there
// is one global LoginDatabase; realmd's own definition is gated out under
// POCKET_EMBEDDED). So we do NOT call LoginDatabase.Initialize here — that would
// re-init an already-initialized DatabaseType and trip its !m_delayThread assert.
// We only verify the version check (a read-only query) is satisfied.
static bool realmd_start_db()
{
    if (!LoginDatabase.CheckRequiredField("realmd_db_version", REVISION_DB_REALMD))
    {
        sLog.outError("realmd: realmd_db_version check failed (login DB not ready "
                      "or wrong revision — world _StartDB must open LoginDatabase first)");
        return false;
    }
    return true;
}

lifecycle_result start_realmd(realmd_state** out)
{
    lifecycle_result r;
    // Raw state ownership stays with the facade (stop_realmd deletes it);
    // only the io_context is shared (de-vibe N7: the listener threads
    // previously captured this stack-local pointer BY REFERENCE, dangling the
    // moment start_realmd returned on every successful start; the throw path
    // additionally destroyed a joinable std::thread at `delete st`,
    // i.e. std::terminate).
    realmd_state* st = new realmd_state;
    st->io.reset(new boost::asio::io_context);

    try
    {
        if (!realmd_start_db())
        {
            r.err = REALM_E_DB;
            r.detail = "realmd: database version check failed";
            delete st;
            return r;
        }

        sRealmList.Initialize(sConfig.GetIntDefault("RealmsStateUpdateDelay", 20));
        if (sRealmList.size() == 0)
        {
            r.err = REALM_E_DB;
            r.detail = "realmd: no valid realms configured";
            delete st;
            return r;
        }

        // Ban cleanup (mirrors realmd Main.cpp).
        LoginDatabase.BeginTransaction();
        LoginDatabase.Execute(
            "UPDATE account_banned SET active = 0 WHERE expires_at<=0 AND expires_at<>banned_at");
        LoginDatabase.Execute(
            "DELETE FROM ip_banned WHERE expires_at<=0 AND expires_at<>banned_at");
        LoginDatabase.CommitTransaction();

        uint32_t networkThreadCount = static_cast<uint32_t>(
            sConfig.GetIntDefault("ListenerThreads", 1));
        st->listener.reset(new MaNGOS::AsyncListener<AuthSocket>(
            *st->io,
            sConfig.GetStringDefault("BindIP", "127.0.0.1"),
            sConfig.GetIntDefault("RealmServerPort", DEFAULT_REALMSERVER_PORT)));

        const std::shared_ptr<boost::asio::io_context> io = st->io;
        for (uint32_t i = 0; i < networkThreadCount; ++i)
            st->threads.emplace_back([io]() { io->run(); });

        *out = st; // no reinterpret_cast: realmd_state is the real type
    }
    catch (...)
    {
        std::exception_ptr ep = std::current_exception();
        std::string msg;
        if (is_fatal_error(ep, &msg))
        {
            r.err = REALM_E_FATAL_STARTUP;
            r.detail = msg;
        }
        else
        {
            r.err = REALM_E_INTERNAL;
            r.detail = "realmd: exception during listener startup";
        }
        // st is still owned here: stop the io_context so the (possibly
        // partially started) threads exit their run() loops, join them, then
        // free the state (raw ownership: plain delete).
        try { st->io->stop(); } catch (...) {}
        for (auto& thread : st->threads)
            if (thread.joinable()) thread.join();
        delete st;
    }
    return r;
}

void stop_realmd(realmd_state* s)
{
    if (!s) return;
    try
    {
        s->stop.store(true);
        s->io->stop();
        for (auto& t : s->threads)
            if (t.joinable()) t.join();
        s->listener.reset();
        // LoginDatabase's delay thread is halted by the world side's teardown
        // (Master::StopEmbedded / stop_world_machinery); do not double-halt here.
    }
    catch (...)
    {
        // Boundary: never let a stop-phase exception escape.
    }
    delete s;
}

} // namespace pocket_realm
