// realm.cpp — Realm state machine, worker thread, and ABI method bodies.
#include "realm.h"
#include "embed.h"
#include "lifecycle.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>

namespace pocket_realm {

Realm::Realm() = default;

Realm::~Realm()
{
    // The worker captures `this`, so the std::thread MUST be joined before this
    // object is destroyed — otherwise ~thread() on a joinable thread calls
    // std::terminate, and a detached worker would use-after-free the members it
    // polls. Realm::join() only waits on the terminal condition variable; it
    // does NOT join m_worker. So we explicitly join the thread here. The worker
    // is structured to always reach a terminal state (every phase observes
    // m_stop_requested), so this join is bounded.
    if (m_worker.joinable())
    {
        if (current_state() != REALM_STATE_STOPPED && current_state() != REALM_STATE_FAILED)
        {
            m_stop_requested.store(true, std::memory_order_release);
        }
        // Wait for the worker to signal terminal (bounded), then join the OS
        // thread to reclaim it. If the signal never comes within the ceiling,
        // join the thread directly anyway — the worker always terminates.
        (void)join(30000);
        if (m_worker.joinable())
            m_worker.join();
    }
}

realm_err Realm::configure(const realm_config& cfg)
{
    if (cfg.abi_version != POCKET_REALM_ABI_VERSION)
        return REALM_E_INVALID_ARG;
    if (!cfg.data_dir || !cfg.db_dir || !cfg.world_conf || !cfg.realmd_conf)
        return REALM_E_INVALID_ARG;

    m_data_dir = cfg.data_dir;
    m_db_dir = cfg.db_dir;
    m_world_conf = cfg.world_conf;
    m_realmd_conf = cfg.realmd_conf;
    m_playerbot_conf = cfg.playerbot_conf ? cfg.playerbot_conf : "";
    m_world_threads = cfg.world_threads ? cfg.world_threads : 1;
    m_log_fn = cfg.log;
    m_log_user = cfg.log_user;

    // All conditions unknown until the worker evaluates them.
    for (int i = 0; i < REALM_COND_COUNT; ++i)
        m_conditions[i] = REALM_COND_UNKNOWN;

    return REALM_E_OK;
}

void Realm::log(realm_log_level level, const char* msg) const
{
    if (m_log_fn && msg)
        m_log_fn(m_log_user, level, msg, static_cast<int>(std::char_traits<char>::length(msg)));
}

bool Realm::can_restart_from_current() const
{
    realm_state s = current_state();
    return s == REALM_STATE_CREATED || s == REALM_STATE_STOPPED || s == REALM_STATE_FAILED;
}

realm_err Realm::start()
{
    if (!can_restart_from_current())
        return REALM_E_WRONG_STATE;

    // Strategy A evidence gate: the second cycle requires reset_for_reinit to
    // have run after the first. If the previous cycle left process-global state
    // that can't be reset, we fail loud here rather than starting into a
    // half-torn-down world. (See lifecycle.cpp reset_for_reinit.)
    if (m_cycle_started.exchange(true, std::memory_order_acq_rel))
    {
        std::string detail;
        lifecycle_result rr = reset_for_reinit(&detail);
        if (rr.err != REALM_E_OK)
        {
            log(REALM_LOG_ERROR, detail.c_str());
            return rr.err; // typically REALM_E_BUSY (Strategy B)
        }
    }

    {
        std::lock_guard<std::mutex> lk(m_mutex);
        for (int i = 0; i < REALM_COND_COUNT; ++i)
            m_conditions[i] = REALM_COND_UNKNOWN;
        m_blocker_text.clear();
    }
    // Reset the terminal flag under its own mutex so a realm_join issued during
    // cycle 2's STARTING window doesn't see a stale "true" from cycle 1 and
    // return OK before the new worker has actually reached terminal.
    {
        std::lock_guard<std::mutex> tlk(m_terminal_mutex);
        m_terminal.store(false, std::memory_order_release);
    }
    m_stop_requested.store(false, std::memory_order_release);
    m_state.store(REALM_STATE_STARTING, std::memory_order_release);

    // Detach any previous joined worker; std::thread must be not-a-thread to
    // reassign.
    if (m_worker.joinable())
        m_worker.join();
    m_worker = std::thread([this] { this->worker_main(); });

    return REALM_E_OK;
}

void Realm::worker_main()
{
    // Capture any exception that crosses a CMaNGOS boundary (POCKET_FATAL or
    // otherwise) and convert to a structured terminal state, never letting it
    // escape the worker.
    realm_cond_state conds[REALM_COND_COUNT];
    for (int i = 0; i < REALM_COND_COUNT; ++i)
        conds[i] = REALM_COND_UNKNOWN;

    std::string fatal_detail;
    realm_err final_err = REALM_E_OK;
    bool client_blocked = false;

    // Resource handles declared at function scope so EVERY exit path (goto
    // terminal, catch, happy path) can tear them down. A phase that hasn't run
    // leaves its pointer null, which the stop_* helpers accept as a no-op.
    realmd_state* rs = nullptr;
    world_session* ws = nullptr;

    try
    {
        // --- Phase 1: world DBs ---
        // --- Phase 1: world DBs ---
        auto r1 = load_config(m_world_conf.c_str(), "Mangosd_");
        if (r1.err != REALM_E_OK) { final_err = r1.err; fatal_detail = r1.detail; goto terminal; }
        r1 = start_databases(conds);
        if (r1.err != REALM_E_OK) { final_err = r1.err; fatal_detail = r1.detail; goto terminal; }

        // --- Phase 2: realmd (auth) ---
        auto r2 = load_config(m_realmd_conf.c_str(), "Realmd_");
        if (r2.err == REALM_E_OK)
            r2 = start_realmd(&rs);
        if (r2.err != REALM_E_OK)
        {
            final_err = r2.err;
            fatal_detail = r2.detail;
            goto terminal;
        }

        // --- Phase 3: world machinery (hits the client-data gate) ---
        auto r3 = start_world_machinery(&ws, conds, m_world_threads);
        client_blocked = r3.client_data_gate;
        if (r3.err == REALM_E_BLOCKED_ON_CLIENT_DATA)
        {
            // Honest degraded health: machinery started, world data missing.
            // The realm is "running" in the sense that it brought up what it
            // could; O05 surfaces this as a distinct non-playable state.
            {
                std::lock_guard<std::mutex> lk(m_mutex);
                for (int i = 0; i < REALM_COND_COUNT; ++i)
                    m_conditions[i] = conds[i];
                m_blocker_text = "world loop blocked: .dbc/.map client data import required (O10)";
            }
            m_state.store(REALM_STATE_RUNNING, std::memory_order_release);

            // Wait cooperatively for stop. The world/auth listeners + realmd
            // are live; we drain them when requested.
            while (!m_stop_requested.load(std::memory_order_acquire) &&
                   current_state() == REALM_STATE_RUNNING)
            {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }

            m_state.store(REALM_STATE_STOPPING, std::memory_order_release);
            stop_world_machinery(ws); ws = nullptr;
            stop_realmd(rs); rs = nullptr;
            m_state.store(REALM_STATE_STOPPED, std::memory_order_release);
        }
        else if (r3.err != REALM_E_OK)
        {
            final_err = r3.err;
            fatal_detail = r3.detail;
            goto terminal; // terminal label tears down ws + rs
        }
        else
        {
            // Full real bring-up succeeded (will only happen once O10 lands).
            {
                std::lock_guard<std::mutex> lk(m_mutex);
                for (int i = 0; i < REALM_COND_COUNT; ++i)
                    m_conditions[i] = conds[i];
            }
            m_state.store(REALM_STATE_RUNNING, std::memory_order_release);
            while (!m_stop_requested.load(std::memory_order_acquire) &&
                   current_state() == REALM_STATE_RUNNING)
            {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            m_state.store(REALM_STATE_STOPPING, std::memory_order_release);
            stop_world_machinery(ws); ws = nullptr;
            stop_realmd(rs); rs = nullptr;
            m_state.store(REALM_STATE_STOPPED, std::memory_order_release);
        }
        goto terminal;
    }
    catch (...)
    {
        std::exception_ptr ep = std::current_exception();
        std::string msg;
        if (is_fatal_error(ep, &msg))
        {
            fatal_detail = msg;
            client_blocked = (msg.find("O10") != std::string::npos ||
                              msg.find("client data") != std::string::npos);
            final_err = client_blocked ? REALM_E_BLOCKED_ON_CLIENT_DATA
                                       : REALM_E_FATAL_STARTUP;
        }
        else
        {
            fatal_detail = "unchecked native exception during realm startup";
            final_err = REALM_E_INTERNAL;
        }
        // Reflect client-data gate into the world-loop triad so health is honest.
        if (client_blocked)
        {
            std::lock_guard<std::mutex> lk(m_mutex);
            m_conditions[REALM_COND_WORLD_LOOP_RUNNING] = REALM_COND_BLOCKED_ON_CLIENT_DATA;
            m_conditions[REALM_COND_LOCAL_ENDPOINTS_LISTENING] = REALM_COND_BLOCKED_ON_CLIENT_DATA;
            m_conditions[REALM_COND_BOT_SUBSYSTEM_INITIALIZED] = REALM_COND_BLOCKED_ON_CLIENT_DATA;
            m_blocker_text = fatal_detail;
        }
        m_state.store(REALM_STATE_FAILED, std::memory_order_release);
        goto terminal;
    }

terminal:
    // Tear down whatever resources this cycle opened, on EVERY path (happy,
    // blocked, fatal, exception). The stop_* helpers are null-safe and reset
    // the database objects so a second cycle can re-initialize. The try guard
    // ensures no throw escapes the worker thread (which would std::terminate).
    try
    {
        if (ws) { stop_world_machinery(ws); ws = nullptr; }
        if (rs) { stop_realmd(rs); rs = nullptr; }

        if (final_err != REALM_E_OK)
        {
            {
                std::lock_guard<std::mutex> lk(m_mutex);
                m_last_fatal_msg = fatal_detail;
            }
            log(REALM_LOG_ERROR, fatal_detail.c_str());
            m_state.store(REALM_STATE_FAILED, std::memory_order_release);
        }
    }
    catch (...)
    {
        // Never let a teardown/log exception escape the worker thread.
        m_state.store(REALM_STATE_FAILED, std::memory_order_release);
    }
    {
        std::lock_guard<std::mutex> lk(m_terminal_mutex);
        m_terminal = true;
    }
    m_terminal_cv.notify_all();
}

realm_err Realm::get_health(realm_health* out)
{
    if (!out) return REALM_E_INVALID_ARG;
    std::lock_guard<std::mutex> lk(m_mutex);
    bool all = true;
    for (int i = 0; i < REALM_COND_COUNT; ++i)
    {
        out->conditions[i] = m_conditions[i];
        if (m_conditions[i] != REALM_COND_TRUE) all = false;
    }
    out->all_ready = all ? 1 : 0;
    // Copy the blocker text into the caller-owned buffer under the lock, so the
    // caller owns the memory it reads (no dangling-reference hazard if the
    // worker mutates m_blocker_text after we return). snprintf bounds the copy.
    if (out->blocker_text && out->blocker_cap > 0)
    {
        out->blocker_text[0] = '\0';
        if (!m_blocker_text.empty())
        {
            std::snprintf(out->blocker_text, static_cast<size_t>(out->blocker_cap),
                          "%s", m_blocker_text.c_str());
        }
    }
    return REALM_E_OK;
}

realm_err Realm::command(const char* cmd, int len)
{
    if (!cmd) return REALM_E_INVALID_ARG;
    (void)len;
    realm_state s = current_state();
    if (s != REALM_STATE_RUNNING && s != REALM_STATE_SAVING)
        return REALM_E_WRONG_STATE;
    // O04: the world command processor (sWorld.QueueCliCommand) is wired in O05.
    // Until then, reject commands honestly rather than fake-OK-ing them: a
    // degraded realm without the world loop running has no command processor.
    // (No fake success.) The packaging work replaces this with the real queue path.
    return REALM_E_BLOCKED_ON_CLIENT_DATA;
}

realm_err Realm::save(realm_save_mode mode)
{
    realm_state s = current_state();
    if (s != REALM_STATE_RUNNING && s != REALM_STATE_SAVING)
        return REALM_E_WRONG_STATE;
    // The actual save goes through World::SaveAllPlayers / CharacterDatabase
    // flush in the full bring-up; in the degraded (client-blocked) state there
    // are no players to save, so this is a valid no-op that the supervisor can
    // still call on the Save&Exit path.
    m_state.store(REALM_STATE_SAVING, std::memory_order_release);
    // O05 will drive the real save here. For O04 we transition back to RUNNING
    // immediately since there is no durable player state yet.
    m_state.store(REALM_STATE_RUNNING, std::memory_order_release);
    (void)mode;
    return REALM_E_OK;
}

realm_err Realm::checkpoint()
{
    realm_state s = current_state();
    if (s != REALM_STATE_RUNNING && s != REALM_STATE_SAVING)
        return REALM_E_WRONG_STATE;
    // O05: PRAGMA wal_checkpoint(RESTART) on all four DBs. No-op safe in O04.
    return REALM_E_OK;
}

realm_err Realm::request_stop(realm_stop_reason reason)
{
    (void)reason;
    realm_state s = current_state();
    if (s == REALM_STATE_STOPPED || s == REALM_STATE_FAILED ||
        s == REALM_STATE_STOPPING || s == REALM_STATE_CREATED)
        return REALM_E_WRONG_STATE;
    m_stop_requested.store(true, std::memory_order_release);
    return REALM_E_OK;
}

realm_err Realm::join(uint64_t timeout_ms)
{
    realm_state s = current_state();
    // Nothing to wait for if we never started or already terminal.
    if (s == REALM_STATE_CREATED || s == REALM_STATE_STOPPED ||
        s == REALM_STATE_FAILED)
        return REALM_E_OK;

    if (timeout_ms == 0)
        return (current_state() == REALM_STATE_STOPPED ||
                current_state() == REALM_STATE_FAILED)
                   ? REALM_E_OK : REALM_E_TIMEOUT;

    std::unique_lock<std::mutex> lk(m_terminal_mutex);
    auto deadline = (timeout_ms == UINT64_MAX)
                        ? std::chrono::steady_clock::time_point::max()
                        : std::chrono::steady_clock::now() +
                              std::chrono::milliseconds(timeout_ms);
    while (!m_terminal)
    {
        if (m_terminal_cv.wait_until(lk, deadline) == std::cv_status::timeout)
            break;
    }
    return m_terminal ? REALM_E_OK : REALM_E_TIMEOUT;
}

realm_err Realm::get_state(realm_state* out) const
{
    if (!out) return REALM_E_INVALID_ARG;
    *out = current_state();
    return REALM_E_OK;
}

} // namespace pocket_realm
