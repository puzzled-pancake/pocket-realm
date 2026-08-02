#include "server_common.h"

#include "Common.h"
#include "Config/Config.h"
#include "Database/DatabaseEnv.h"
#include "Log/Log.h"
#include "Master.h"
#include "World/World.h"

#include <jni.h>
#include <openssl/provider.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace {

struct CommandResult {
    std::mutex mutex;
    std::condition_variable changed;
    bool done{false};
    bool success{false};
    std::string output;
};

class WorldRuntime {
public:
    int start(const std::string& config)
    {
        std::lock_guard<std::mutex> guard(m_lifecycle);
        if (m_state.state() != POCKET_SERVER_STOPPED && m_state.state() != POCKET_SERVER_FAILED)
            return POCKET_SERVER_WRONG_STATE;
        if (config.empty()) return POCKET_SERVER_INVALID_ARGUMENT;
        if (m_worker.joinable()) m_worker.join();
        m_stop.store(false, std::memory_order_release);
        m_ticks.store(0, std::memory_order_release);
        m_last_tick.store(0, std::memory_order_release);
        m_max_tick.store(0, std::memory_order_release);
        m_state.transition(POCKET_SERVER_STARTING);
        m_worker = std::thread([this, config] { run(config); });
        return POCKET_SERVER_OK;
    }

    int save(uint64_t timeout_ms)
    {
        if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;
        m_state.transition(POCKET_SERVER_SAVING);
        const int result = issue_command("saveall", timeout_ms, nullptr);
        if (m_state.state() == POCKET_SERVER_SAVING)
            m_state.transition(POCKET_SERVER_READY,
                result == POCKET_SERVER_OK ? POCKET_SERVER_OK : POCKET_SERVER_TIMEOUT,
                result == POCKET_SERVER_OK ? "" : "world save acknowledgement timed out");
        return result;
    }

    int create_account(const std::string& username, const std::string& password, uint64_t timeout_ms)
    {
        if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;
        if (username.empty() || password.empty() || username.size() > 16 || password.size() > 16)
            return POCKET_SERVER_INVALID_ARGUMENT;
        const auto is_account_token = [](const std::string& value) {
            return std::all_of(value.begin(), value.end(),
                [](unsigned char c) { return std::isalnum(c); });
        };
        if (!is_account_token(username) || !is_account_token(password))
            return POCKET_SERVER_INVALID_ARGUMENT;
        std::string output;
        const std::string command = "account create " + username + " " + password;
        const int result = issue_command(command, timeout_ms, &output);
        if (result == POCKET_SERVER_OK) return result;
        std::transform(output.begin(), output.end(), output.begin(), [](unsigned char c) { return std::tolower(c); });
        if (output.find("already") != std::string::npos || output.find("exist") != std::string::npos)
            return POCKET_SERVER_ACCOUNT_EXISTS;
        return result == POCKET_SERVER_TIMEOUT ? result : POCKET_SERVER_ACCOUNT_REJECTED;
    }

    int stop(uint64_t timeout_ms)
    {
        {
            std::lock_guard<std::mutex> guard(m_lifecycle);
            const auto state = m_state.state();
            if (state == POCKET_SERVER_STOPPED) return POCKET_SERVER_OK;
            if (state == POCKET_SERVER_FAILED)
            {
                if (m_worker.joinable()) m_worker.join();
                m_state.transition(POCKET_SERVER_STOPPED);
                return POCKET_SERVER_OK;
            }
            m_state.transition(POCKET_SERVER_STOPPING);
            m_stop.store(true, std::memory_order_release);
            World::StopNow(SHUTDOWN_EXIT_CODE);
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
        out->tick_count = m_ticks.load(std::memory_order_acquire);
        out->last_tick_ms = m_last_tick.load(std::memory_order_acquire);
        out->max_tick_ms = m_max_tick.load(std::memory_order_acquire);
        if (out->state == POCKET_SERVER_READY || out->state == POCKET_SERVER_SAVING)
            out->active_sessions = sWorld.GetActiveSessionCount();
        pocket_server::copy_detail(out->detail, sizeof(out->detail), m_state.detail());
    }

    void record_tick(uint32_t duration)
    {
        m_ticks.fetch_add(1, std::memory_order_relaxed);
        m_last_tick.store(duration, std::memory_order_release);
        uint32_t previous = m_max_tick.load(std::memory_order_relaxed);
        while (duration > previous &&
               !m_max_tick.compare_exchange_weak(previous, duration, std::memory_order_release)) {}
        m_state.beat();
    }

private:
    void run(const std::string& config)
    {
        try
        {
            OSSL_PROVIDER_load(nullptr, "legacy");
            OSSL_PROVIDER_load(nullptr, "default");
            if (!sConfig.SetSource(config, "Mangosd_"))
                return fail(POCKET_SERVER_CONFIG, "world configuration rejected");
            sLog.Initialize();
            if (!sMaster.StartDatabasesEmbedded())
                return fail(POCKET_SERVER_DB_REVISION, "world database connect or revision check failed");
            bool client_data_gate = false;
            if (!sMaster.InitWorldEmbedded(&client_data_gate))
                return fail(client_data_gate ? POCKET_SERVER_DATA_MISSING : POCKET_SERVER_DATA_BUILD,
                            client_data_gate ? "verified client-derived data is missing" : "world data load failed");
            if (!sMaster.StartNetworkEmbedded(1))
                return fail(POCKET_SERVER_PORT_IN_USE, "world listener failed");
            m_started = true;
            m_state.transition(POCKET_SERVER_READY);
            while (!m_stop.load(std::memory_order_acquire))
            {
                m_state.beat();
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            cleanup();
            m_state.transition(POCKET_SERVER_STOPPED);
        }
        catch (const boost::system::system_error& error)
        {
            cleanup();
            fail(POCKET_SERVER_PORT_IN_USE, std::string("world listener failed: ") + error.code().message());
        }
        catch (const std::exception& error)
        {
            cleanup();
            fail(POCKET_SERVER_INTERNAL, std::string("world exception: ") + error.what());
        }
        catch (...)
        {
            cleanup();
            fail(POCKET_SERVER_INTERNAL, "world unknown native exception");
        }
    }

    int issue_command(const std::string& command, uint64_t timeout_ms, std::string* output)
    {
        auto result = std::make_shared<CommandResult>();
        sWorld.QueueCliCommand(new CliCommandHolder(
            0, SEC_CONSOLE, command.c_str(),
            [result](const char* text) {
                if (!text) return;
                std::lock_guard<std::mutex> guard(result->mutex);
                if (result->output.size() < 2048)
                    result->output.append(text, std::min<size_t>(std::strlen(text), 2048 - result->output.size()));
            },
            [result](bool success) {
                std::lock_guard<std::mutex> guard(result->mutex);
                result->success = success;
                result->done = true;
                result->changed.notify_all();
            }));
        std::unique_lock<std::mutex> guard(result->mutex);
        if (!result->changed.wait_for(guard, std::chrono::milliseconds(timeout_ms), [&] { return result->done; }))
            return POCKET_SERVER_TIMEOUT;
        if (output) *output = result->output;
        return result->success ? POCKET_SERVER_OK : POCKET_SERVER_ACCOUNT_REJECTED;
    }

    void cleanup()
    {
        if (m_started)
        {
            World::StopNow(SHUTDOWN_EXIT_CODE);
            sMaster.StopEmbedded();
            m_started = false;
        }
        else
        {
            CharacterDatabase.StopServerEmbedded();
            WorldDatabase.StopServerEmbedded();
            LoginDatabase.StopServerEmbedded();
            LogsDatabase.StopServerEmbedded();
            World::ResetForReinit();
        }
    }

    void fail(pocket_server_error error, const std::string& detail)
    {
        m_state.transition(POCKET_SERVER_FAILED, error, detail);
    }

    pocket_server::StateRecord m_state;
    std::atomic<bool> m_stop{false};
    std::atomic<uint64_t> m_ticks{0};
    std::atomic<uint32_t> m_last_tick{0};
    std::atomic<uint32_t> m_max_tick{0};
    std::mutex m_lifecycle;
    std::thread m_worker;
    bool m_started{false};
};

WorldRuntime g_runtime;

jstring to_jstring(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }
std::string from_jstring(JNIEnv* env, jstring value)
{
    if (!value) return {};
    const char* text = env->GetStringUTFChars(value, nullptr);
    std::string result(text ? text : "");
    if (text) env->ReleaseStringUTFChars(value, text);
    return result;
}

} // namespace

extern "C" void pocket_world_record_tick(uint32_t duration_ms) { g_runtime.record_tick(duration_ms); }

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_startNative(JNIEnv* env, jclass, jstring config)
{ return g_runtime.start(from_jstring(env, config)); }

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_saveNative(JNIEnv*, jclass, jlong timeout_ms)
{ return g_runtime.save(static_cast<uint64_t>(std::max<jlong>(0, timeout_ms))); }

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_createAccountNative(
    JNIEnv* env, jclass, jstring username, jstring password, jlong timeout_ms)
{
    return g_runtime.create_account(from_jstring(env, username), from_jstring(env, password),
        static_cast<uint64_t>(std::max<jlong>(0, timeout_ms)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_stopNative(JNIEnv*, jclass, jlong timeout_ms)
{ return g_runtime.stop(static_cast<uint64_t>(std::max<jlong>(0, timeout_ms))); }

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pocketrealm_server_WorldNative_statusNative(JNIEnv* env, jclass)
{
    pocket_server_status status{};
    g_runtime.status(&status);
    const jlong values[] = {status.abi_version, status.state, status.error,
        static_cast<jlong>(status.heartbeat_ms), static_cast<jlong>(status.tick_count),
        status.last_tick_ms, status.max_tick_ms, status.active_sessions};
    jlongArray result = env->NewLongArray(8);
    env->SetLongArrayRegion(result, 0, 8, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketrealm_server_WorldNative_detailNative(JNIEnv* env, jclass)
{
    pocket_server_status status{};
    g_runtime.status(&status);
    return to_jstring(env, status.detail);
}
