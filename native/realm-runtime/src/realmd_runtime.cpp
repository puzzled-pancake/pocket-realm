#include "server_common.h"

#include "Common.h"
#include "Config/Config.h"
#include "Database/DatabaseEnv.h"
#include "Log/Log.h"
#include "Network/AsyncListener.hpp"
#include "AuthSocket.h"
#include "RealmList.h"
#include "SystemConfig.h"
#include "revision_sql.h"

#include <boost/asio.hpp>
#include <jni.h>
#include <openssl/provider.h>

#include <atomic>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

DatabaseType LoginDatabase;

namespace {

class RealmdRuntime {
public:
    int start(const std::string& config)
    {
        {
            std::lock_guard<std::mutex> guard(m_lifecycle);
            if (m_state.state() != POCKET_SERVER_STOPPED && m_state.state() != POCKET_SERVER_FAILED)
                return POCKET_SERVER_WRONG_STATE;
            if (config.empty()) return POCKET_SERVER_INVALID_ARGUMENT;
            // Signal a leftover worker (an io-thread fail()
            // can leave FAILED with the worker still looping). The join must
            // run OUTSIDE the guard: the worker's exit path calls cleanup(),
            // which takes m_lifecycle.
            m_stop.store(true, std::memory_order_release);
            if (m_io) m_io->stop();
        }
        if (m_worker.joinable()) m_worker.join();
        {
            std::lock_guard<std::mutex> guard(m_lifecycle);
            m_stop.store(false, std::memory_order_release);
            m_state.transition(POCKET_SERVER_STARTING);
            m_worker = std::thread([this, config] { run(config); });
        }
        return POCKET_SERVER_OK;
    }

    int stop(uint64_t timeout_ms)
    {
        bool failed_at_entry = false;
        {
            std::lock_guard<std::mutex> guard(m_lifecycle);
            auto state = m_state.state();
            if (state == POCKET_SERVER_STOPPED) return POCKET_SERVER_OK;
            // Signal BOTH exits under the guard; the join
            // and cleanup run OUTSIDE it — the worker's exit path calls
            // cleanup(), which takes m_lifecycle, so joining while holding it
            // would deadlock.
            if (state == POCKET_SERVER_FAILED)
            {
                failed_at_entry = true;
            }
            else
            {
                m_state.transition(POCKET_SERVER_STOPPING);
            }
            m_stop.store(true, std::memory_order_release);
            if (m_io) m_io->stop();
        }
        if (failed_at_entry)
        {
            if (m_worker.joinable()) m_worker.join();
            cleanup();
            std::lock_guard<std::mutex> guard(m_lifecycle);
            m_state.transition(POCKET_SERVER_STOPPED);
            return POCKET_SERVER_OK;
        }
        const uint64_t deadline = pocket_server::monotonic_ms() + timeout_ms;
        while (m_state.state() != POCKET_SERVER_STOPPED &&
               m_state.state() != POCKET_SERVER_FAILED &&
               pocket_server::monotonic_ms() < deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        if (m_state.state() == POCKET_SERVER_FAILED)
        {
            // The worker failed during shutdown (previously conflated
            // with a wedge timeout). Join outside the lock, then finish.
            if (m_worker.joinable()) m_worker.join();
            cleanup();
            std::lock_guard<std::mutex> guard(m_lifecycle);
            m_state.transition(POCKET_SERVER_STOPPED);
            return POCKET_SERVER_OK;
        }
        if (m_state.state() != POCKET_SERVER_STOPPED) return POCKET_SERVER_TIMEOUT;
        {
            // The worker is done (STOPPED is its final statement); joining
            // under the guard is safe and keeps the reset atomic.
            std::lock_guard<std::mutex> guard(m_lifecycle);
            if (m_worker.joinable()) m_worker.join();
        }
        return POCKET_SERVER_OK;
    }

    void status(pocket_server_status* out)
    {
        if (!out) return;
        std::memset(out, 0, sizeof(*out));
        out->abi_version = POCKET_SERVER_ABI_VERSION;
        out->state = m_state.state();
        out->error = m_state.error();
        out->heartbeat_ms = m_state.heartbeat();
        out->tick_count = m_heartbeat_count.load(std::memory_order_acquire);
        pocket_server::copy_detail(out->detail, sizeof(out->detail), m_state.detail());
    }

private:
    void run(const std::string& config)
    {
        try
        {
            OSSL_PROVIDER_load(nullptr, "legacy");
            OSSL_PROVIDER_load(nullptr, "default");
            if (!sConfig.SetSource(config, "Realmd_"))
                return fail(POCKET_SERVER_CONFIG, "realmd configuration rejected");
            sLog.Initialize();
            const std::string db = sConfig.GetStringDefault("LoginDatabaseInfo");
            if (db.empty() || !LoginDatabase.Initialize(db.c_str()))
                return fail(POCKET_SERVER_DB_CONNECT, "realmd could not connect through the app-private database socket");
            if (!LoginDatabase.CheckRequiredField("realmd_db_version", REVISION_DB_REALMD))
                return fail(POCKET_SERVER_DB_REVISION, "realmd schema revision mismatch");

            sRealmList.Initialize(sConfig.GetIntDefault("RealmsStateUpdateDelay", 20));
            if (sRealmList.size() == 0)
                return fail(POCKET_SERVER_DB_REVISION, "realmd found no valid pinned realm row");

            {
                // stop() reads m_io under m_lifecycle from Binder
                // threads; the assignment must hold the same mutex.
                std::lock_guard<std::mutex> io_guard(m_lifecycle);
                m_io = std::make_unique<boost::asio::io_context>();
            }
            try
            {
                m_listener = std::make_unique<MaNGOS::AsyncListener<AuthSocket>>(
                    *m_io, sConfig.GetStringDefault("BindIP", "127.0.0.1"),
                    sConfig.GetIntDefault("RealmServerPort", DEFAULT_REALMSERVER_PORT));
            }
            catch (const boost::system::system_error& error)
            {
                return fail(POCKET_SERVER_PORT_IN_USE,
                            std::string("realmd listener failed: ") + error.code().message());
            }
            // G1 keep-alive (rp-depth-fix v2.3 §8): kill-switch
            // AiPlayerbot.RealmdTimerMs, default 250 ms, 0 = timer off (the
            // documented workaround; removal condition in §0.a). The key is
            // read through this file's existing sConfig path; the app's
            // staged realmd.conf (ServerRuntimeFiles.realmdConfig) carries
            // no AiPlayerbot.* keys today, so the effective value is the
            // default until one is staged there - no new conf plumbing.
            const int32 timer_ms = sConfig.GetIntDefault("AiPlayerbot.RealmdTimerMs", 250);
            if (timer_ms > 0)
            {
                // stop() destroys the timer under m_lifecycle; the
                // assignment must hold the same mutex.
                std::lock_guard<std::mutex> io_guard(m_lifecycle);
                m_keepalive_timer = std::make_unique<boost::asio::steady_timer>(*m_io);
                arm_keepalive(std::chrono::milliseconds(timer_ms));
            }
            const uint32 threads = std::max(1, sConfig.GetIntDefault("ListenerThreads", 1));
            for (uint32 i = 0; i < threads; ++i)
                m_threads.emplace_back([this] {
                    try {
                        m_io->run();
                    } catch (...) {
                        // An exception escaping io_context::run()
                        // used to std::terminate the whole :realm process;
                        // surface it as a FAILED server instead.
                        fail(POCKET_SERVER_INTERNAL, "realmd io thread exception");
                    }
                });
            LoginDatabase.AllowAsyncTransactions();
            m_state.transition(POCKET_SERVER_READY);
            // G1 liveness: the keep-alive handler is the io-thread dispatch
            // evidence. An UNCHANGED count while !m_stop for a sustained
            // window (>= 3 consecutive timer intervals) means the reactor
            // missed its wakeup - a dead-but-READY listener - so fail()
            // converts the silent mode into a visible FAILED. With the timer
            // off (RealmdTimerMs = 0) there is no dispatch evidence and the
            // check is skipped (documented workaround mode).
            const uint32 heartbeat_ms = 100;
            uint64_t last_liveness = m_io_liveness.load(std::memory_order_acquire);
            uint32 stalled_heartbeats = 0;
            // Also exit on FAILED: io-thread exceptions
            // fail the state; the loop must not outlive it or stop()'s join
            // can never complete.
            while (!m_stop.load(std::memory_order_acquire) &&
                   m_state.state() != POCKET_SERVER_FAILED)
            {
                m_heartbeat_count.fetch_add(1, std::memory_order_relaxed);
                m_state.beat();
                if (timer_ms > 0)
                {
                    const uint64_t liveness = m_io_liveness.load(std::memory_order_acquire);
                    if (liveness == last_liveness)
                    {
                        ++stalled_heartbeats;
                        if (stalled_heartbeats * heartbeat_ms >=
                            static_cast<uint32>(timer_ms) * 3)
                        {
                            // Same exit shape as an io-thread exception:
                            // fail(), leave the loop, cleanup() joins the
                            // io threads instead of stranding them.
                            fail(POCKET_SERVER_INTERNAL,
                                 "realmd io reactor stalled: keep-alive dispatch count frozen");
                            break;
                        }
                    }
                    else
                    {
                        last_liveness = liveness;
                        stalled_heartbeats = 0;
                    }
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(heartbeat_ms));
            }
            cleanup();
            m_state.transition(POCKET_SERVER_STOPPED);
        }
        catch (const std::exception& error)
        {
            cleanup();
            fail(POCKET_SERVER_INTERNAL, std::string("realmd exception: ") + error.what());
        }
        catch (...)
        {
            cleanup();
            fail(POCKET_SERVER_INTERNAL, "realmd unknown native exception");
        }
    }

    // G1 keep-alive pump: one outstanding steady_timer wait at a time,
    // re-armed from its own completion handler. The re-arm is
    // UNCONDITIONAL while the runtime is running - any error code
    // (including the operation_aborted of a spurious cancel) still
    // re-arms, so only m_stop or io_context destruction ends the chain.
    // Each handler dispatch bumps m_io_liveness; the run() heartbeat
    // reads that count and fails on a sustained freeze.
    void arm_keepalive(std::chrono::milliseconds interval)
    {
        m_keepalive_timer->expires_after(interval);
        m_keepalive_timer->async_wait(
            [this, interval](const boost::system::error_code&)
            {
                m_io_liveness.fetch_add(1, std::memory_order_release);
                if (!m_stop.load(std::memory_order_acquire))
                    arm_keepalive(interval);
            });
    }

    void cleanup()
    {
        {
            std::lock_guard<std::mutex> io_guard(m_lifecycle);
            if (m_io) m_io->stop();
        }
        for (auto& thread : m_threads) if (thread.joinable()) thread.join();
        m_threads.clear();
        std::lock_guard<std::mutex> io_guard(m_lifecycle);
        // The keep-alive timer is destroyed with the listener: only after
        // the io threads joined, so no handler can be re-arming it.
        m_keepalive_timer.reset();
        m_listener.reset();
        m_io.reset();
        LoginDatabase.StopServerEmbedded();
    }

    void fail(pocket_server_error error, const std::string& detail)
    {
        m_state.transition(POCKET_SERVER_FAILED, error, detail);
    }

    pocket_server::StateRecord m_state;
    std::atomic<bool> m_stop{false};
    std::atomic<uint64_t> m_heartbeat_count{0};
    // G1: io-thread liveness evidence - bumped by every keep-alive
    // handler dispatch on the io_context.
    std::atomic<uint64_t> m_io_liveness{0};
    std::mutex m_lifecycle;
    std::thread m_worker;
    std::unique_ptr<boost::asio::io_context> m_io;
    std::unique_ptr<boost::asio::steady_timer> m_keepalive_timer;
    std::unique_ptr<MaNGOS::AsyncListener<AuthSocket>> m_listener;
    std::vector<std::thread> m_threads;
};

RealmdRuntime g_runtime;

jstring jstring_from(JNIEnv* env, const std::string& value)
{
    return env->NewStringUTF(value.c_str());
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_RealmNative_startNative(JNIEnv* env, jclass, jstring config)
{
    if (!config) return POCKET_SERVER_INVALID_ARGUMENT;
    const char* text = env->GetStringUTFChars(config, nullptr);
    std::string value(text ? text : "");
    if (text) env->ReleaseStringUTFChars(config, text);
    return g_runtime.start(value);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_RealmNative_stopNative(JNIEnv*, jclass, jlong timeout_ms)
{
    return g_runtime.stop(static_cast<uint64_t>(std::max<jlong>(0, timeout_ms)));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pocketrealm_server_RealmNative_statusNative(JNIEnv* env, jclass)
{
    pocket_server_status status{};
    g_runtime.status(&status);
    const jlong values[] = {status.abi_version, status.state, status.error,
        static_cast<jlong>(status.heartbeat_ms), static_cast<jlong>(status.tick_count)};
    jlongArray result = env->NewLongArray(5);
    env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketrealm_server_RealmNative_detailNative(JNIEnv* env, jclass)
{
    pocket_server_status status{};
    g_runtime.status(&status);
    return jstring_from(env, status.detail);
}
