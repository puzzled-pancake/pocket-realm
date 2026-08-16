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
        std::lock_guard<std::mutex> guard(m_lifecycle);
        if (m_state.state() != POCKET_SERVER_STOPPED && m_state.state() != POCKET_SERVER_FAILED)
            return POCKET_SERVER_WRONG_STATE;
        if (config.empty()) return POCKET_SERVER_INVALID_ARGUMENT;
        if (m_worker.joinable()) m_worker.join();
        m_stop.store(false, std::memory_order_release);
        m_state.transition(POCKET_SERVER_STARTING);
        m_worker = std::thread([this, config] { run(config); });
        return POCKET_SERVER_OK;
    }

    int stop(uint64_t timeout_ms)
    {
        {
            std::lock_guard<std::mutex> guard(m_lifecycle);
            auto state = m_state.state();
            if (state == POCKET_SERVER_STOPPED) return POCKET_SERVER_OK;
            if (state == POCKET_SERVER_FAILED)
            {
                if (m_worker.joinable()) m_worker.join();
                cleanup();
                m_state.transition(POCKET_SERVER_STOPPED);
                return POCKET_SERVER_OK;
            }
            m_state.transition(POCKET_SERVER_STOPPING);
            m_stop.store(true, std::memory_order_release);
            if (m_io) m_io->stop();
        }
        const uint64_t deadline = pocket_server::monotonic_ms() + timeout_ms;
        while (m_state.state() != POCKET_SERVER_STOPPED &&
               m_state.state() != POCKET_SERVER_FAILED &&
               pocket_server::monotonic_ms() < deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        if (m_state.state() != POCKET_SERVER_STOPPED) return POCKET_SERVER_TIMEOUT;
        std::lock_guard<std::mutex> guard(m_lifecycle);
        if (m_worker.joinable()) m_worker.join();
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
                // N5 (de-vibe): stop() reads m_io under m_lifecycle from Binder
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
            const uint32 threads = std::max(1, sConfig.GetIntDefault("ListenerThreads", 1));
            for (uint32 i = 0; i < threads; ++i)
                m_threads.emplace_back([this] {
                    try {
                        m_io->run();
                    } catch (...) {
                        // N6 (de-vibe): an exception escaping io_context::run()
                        // used to std::terminate the whole :realm process;
                        // surface it as a FAILED server instead.
                        fail(POCKET_SERVER_INTERNAL, "realmd io thread exception");
                    }
                });
            LoginDatabase.AllowAsyncTransactions();
            m_state.transition(POCKET_SERVER_READY);
            while (!m_stop.load(std::memory_order_acquire))
            {
                m_heartbeat_count.fetch_add(1, std::memory_order_relaxed);
                m_state.beat();
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
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

    void cleanup()
    {
        {
            std::lock_guard<std::mutex> io_guard(m_lifecycle);
            if (m_io) m_io->stop();
        }
        for (auto& thread : m_threads) if (thread.joinable()) thread.join();
        m_threads.clear();
        std::lock_guard<std::mutex> io_guard(m_lifecycle);
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
    std::mutex m_lifecycle;
    std::thread m_worker;
    std::unique_ptr<boost::asio::io_context> m_io;
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
