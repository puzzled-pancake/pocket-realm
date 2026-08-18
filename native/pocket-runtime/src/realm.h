// realm.h — the C++ object behind the opaque realm_handle.
//
// Realm owns the embeddable realm lifecycle for one realm generation: it spawns
// a worker thread that drives CMaNGOS startup (Master::_StartDB, realmd loop)
// without signals/console/blocking, tracks the six library-lane health conditions
// honestly, and tears down cooperatively via World::StopNow / realmd's stop flag.
//
// Threading model: realm_* API calls happen on caller threads; Realm guards its
// mutable state with m_mutex. The single worker thread (m_worker) is the only
// thread that touches CMaNGOS singletons — every CMaNGOS call is marshalled
// onto it. This is the key constraint that makes cooperative stop tractable:
// there is exactly one thread to drain.
//
// Re-entrancy (O04 acceptance: create/start/.../destroy twice in one process):
// the singletons are reset for reinit at stop time (see lifecycle_mangosd.cpp
// ResetForReinit). If that proves infeasible, the second realm_start returns
// REALM_E_BUSY per the recorded Strategy B decision — but the C ABI is identical.
#pragma once

#include "pocket_realm.h"

#include <atomic>
#include <condition_variable>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace pocket_realm {

class Realm
{
public:
    Realm();
    ~Realm();

    Realm(const Realm&) = delete;
    Realm& operator=(const Realm&) = delete;

    // ---- ABI entry points (see schemas/abi/pocket_realm.h) ----
    realm_err start();
    realm_err get_health(realm_health* out);
    realm_err command(const char* cmd, int len);
    realm_err save(realm_save_mode mode);
    realm_err checkpoint();
    realm_err request_stop(realm_stop_reason reason);
    realm_err join(uint64_t timeout_ms);
    realm_err get_state(realm_state* out) const;

    // Called once by realm_create to absorb the config. Returns REALM_E_OK on
    // success. After this, the Realm is in CREATED and ready for start().
    realm_err configure(const realm_config& cfg);

    realm_state current_state() const { return m_state.load(std::memory_order_acquire); }

    // Log helper used by lifecycle code that runs on the worker thread.
    void log(realm_log_level level, const char* msg) const;

private:
    // Worker entry: runs the full start→run→stop sequence on m_worker. Sets
    // m_state and m_health along the way. Never throws out (caught internally).
    void worker_main();

    // True if m_state is a terminal-ish state where a new start() is legal.
    bool can_restart_from_current() const;

    // ---- config (set once in configure(), immutable after) ----
    std::string m_data_dir;
    std::string m_db_dir;
    std::string m_world_conf;
    std::string m_realmd_conf;
    std::string m_playerbot_conf;   // empty if none
    uint32_t m_world_threads{1};
    realm_log_fn m_log_fn{nullptr};
    void* m_log_user{nullptr};

    // ---- runtime state ----
    std::atomic<realm_state> m_state{REALM_STATE_CREATED};
    mutable std::mutex m_mutex;             // guards m_health + m_blocker
    realm_cond_state m_conditions[REALM_COND_COUNT]{};
    std::string m_blocker_text;             // under m_mutex
    std::string m_last_fatal_msg;           // under m_mutex (for FAILED)

    std::thread m_worker;
    std::condition_variable m_terminal_cv;   // signaled when state reaches terminal
    std::mutex m_terminal_mutex;
    std::atomic<bool> m_terminal{false};     // guarded by m_terminal_mutex + cv

    // Set by request_stop() so the worker's run loop knows to drain and tear
    // down rather than continuing to block on World::IsStopped().
    std::atomic<bool> m_stop_requested{false};

    // Tracks whether this Realm has already run one cycle (for the second-cycle
    // Strategy A evidence gate and Strategy B fallback signaling).
    std::atomic<bool> m_cycle_started{false};
};

} // namespace pocket_realm
