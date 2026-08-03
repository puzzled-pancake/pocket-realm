#include "server_common.h"

#include "Common.h"
#include "Config/Config.h"
#include "Database/DatabaseEnv.h"
#include "Globals/ObjectAccessor.h"
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
#include <sstream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

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

    int set_account_gmlevel(const std::string& username, int level, uint64_t timeout_ms)
    {
        if (m_state.state() != POCKET_SERVER_READY) return POCKET_SERVER_WRONG_STATE;
        if (username.empty() || username.size() > 16 || level < 0 || level > 3)
            return POCKET_SERVER_INVALID_ARGUMENT;
        if (!std::all_of(username.begin(), username.end(),
                [](unsigned char c) { return std::isalnum(c); }))
            return POCKET_SERVER_INVALID_ARGUMENT;
        const std::string command = "account set gmlevel " + username + " " + std::to_string(level);
        const int result = issue_command(command, timeout_ms, nullptr);
        return result == POCKET_SERVER_OK ? result :
            (result == POCKET_SERVER_TIMEOUT ? result : POCKET_SERVER_ACCOUNT_REJECTED);
    }

    std::pair<uint32_t, int32_t> account_info(const std::string& username)
    {
        if (username.empty() || username.size() > 16) return {0, -1};
        std::string escaped = username;
        LoginDatabase.escape_string(escaped);
        auto result = LoginDatabase.PQuery(
            "SELECT id,gmlevel FROM account WHERE username='%s' LIMIT 1", escaped.c_str());
        if (!result) return {0, -1};
        Field* fields = result->Fetch();
        return {fields[0].GetUInt32(), fields[1].GetInt32()};
    }

    std::string realm_info()
    {
        if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)
            return "{\"found\":false,\"reason\":\"world-not-ready\"}";
        auto result = LoginDatabase.Query(
            "SELECT id,name,address,port,realmflags,realmbuilds FROM realmlist WHERE id=1 LIMIT 1");
        if (!result) return "{\"found\":false,\"reason\":\"realm-row-missing\"}";
        Field* fields = result->Fetch();
        std::ostringstream json;
        json << "{\"found\":true"
             << ",\"id\":" << fields[0].GetUInt32()
             << ",\"name\":\"" << json_escape(fields[1].GetCppString()) << "\""
             << ",\"address\":\"" << json_escape(fields[2].GetCppString()) << "\""
             << ",\"port\":" << fields[3].GetUInt32()
             << ",\"flags\":" << fields[4].GetUInt32()
             << ",\"builds\":\"" << json_escape(fields[5].GetCppString()) << "\"}";
        return json.str();
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

    uint32_t online_players()
    {
        const auto state = m_state.state();
        if (state != POCKET_SERVER_READY && state != POCKET_SERVER_SAVING) return 0;
        HashMapHolder<Player>::ReadGuard guard(HashMapHolder<Player>::GetLock());
        return static_cast<uint32_t>(sObjectAccessor.GetPlayers().size());
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
    static std::string json_escape(const std::string& value)
    {
        std::string escaped;
        escaped.reserve(value.size());
        for (unsigned char c : value)
        {
            switch (c)
            {
                case '\\': escaped += "\\\\"; break;
                case '"': escaped += "\\\""; break;
                case '\b': escaped += "\\b"; break;
                case '\f': escaped += "\\f"; break;
                case '\n': escaped += "\\n"; break;
                case '\r': escaped += "\\r"; break;
                case '\t': escaped += "\\t"; break;
                default:
                    if (c >= 0x20) escaped.push_back(static_cast<char>(c));
                    break;
            }
        }
        return escaped;
    }

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
Java_com_pocketrealm_server_WorldNative_setAccountGmLevelNative(
    JNIEnv* env, jclass, jstring username, jint level, jlong timeout_ms)
{
    return g_runtime.set_account_gmlevel(from_jstring(env, username), static_cast<int>(level),
        static_cast<uint64_t>(std::max<jlong>(0, timeout_ms)));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pocketrealm_server_WorldNative_accountInfoNative(JNIEnv* env, jclass, jstring username)
{
    const auto info = g_runtime.account_info(from_jstring(env, username));
    jlong values[2] = {static_cast<jlong>(info.first), static_cast<jlong>(info.second)};
    jlongArray result = env->NewLongArray(2);
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketrealm_server_WorldNative_realmInfoNative(JNIEnv* env, jclass)
{ return to_jstring(env, g_runtime.realm_info()); }

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

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_onlinePlayersNative(JNIEnv*, jclass)
{ return static_cast<jint>(g_runtime.online_players()); }
