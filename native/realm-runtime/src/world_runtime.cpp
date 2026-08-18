#include "server_common.h"
#include "bot_target_fence.h"

#include "Common.h"
#include "Config/Config.h"
#include "Accounts/AccountMgr.h"
#include "Database/DatabaseEnv.h"
#include "Globals/ObjectAccessor.h"
#include "Log/Log.h"
#include "Master.h"
#include "World/World.h"
#ifdef ENABLE_PLAYERBOTS
#include "playerbot/PlayerbotAIConfig.h"
#include "playerbot/RandomPlayerbotMgr.h"
#endif

#include <jni.h>
#include <openssl/provider.h>
#include <openssl/sha.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cctype>
#include <condition_variable>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

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
        m_hard_stall_total.store(0, std::memory_order_release);
        m_consecutive_hard_stalls.store(0, std::memory_order_release);
        m_last_hard_stall_elapsed_ms.store(0, std::memory_order_release);
        {
            std::lock_guard<std::mutex> tick_guard(m_tick_window_mutex);
            m_tick_window_count = 0;
            m_tick_window_cursor = 0;
        }
        m_bot_enabled.store(false, std::memory_order_release);
        m_bots_available.store(0, std::memory_order_release);
        m_bots_online.store(0, std::memory_order_release);
        m_bot_accounts.store(0, std::memory_order_release);
        m_effective_bot_target.store(0, std::memory_order_release);
        m_bot_target_fence.reset();
        for (auto& value : m_low_cpu_telemetry)
            value.store(0, std::memory_order_release);
        m_last_low_cpu_sampled_at = 0;
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

    int begin_bot_target_generation(int64_t generation)
    {
        if (generation <= 0) return POCKET_SERVER_INVALID_ARGUMENT;
        const auto state = m_state.state();
        if (state != POCKET_SERVER_STARTING && state != POCKET_SERVER_READY)
            return POCKET_SERVER_WRONG_STATE;
        return m_bot_target_fence.begin(generation) ?
            POCKET_SERVER_OK : POCKET_SERVER_WRONG_STATE;
    }

    int retire_bot_target_generation(int64_t generation)
    {
        if (generation <= 0) return POCKET_SERVER_INVALID_ARGUMENT;
        return m_bot_target_fence.retire(generation) ?
            POCKET_SERVER_OK : POCKET_SERVER_WRONG_STATE;
    }

    int set_bot_target(int target, int64_t generation, uint64_t timeout_ms)
    {
#ifndef ENABLE_PLAYERBOTS
        (void)target; (void)generation; (void)timeout_ms;
        return POCKET_SERVER_WRONG_STATE;
#else
        if (m_state.state() != POCKET_SERVER_READY || !m_bot_enabled.load(std::memory_order_acquire))
            return POCKET_SERVER_WRONG_STATE;
        if (target < m_bot_min.load(std::memory_order_acquire) ||
            target > m_bot_max.load(std::memory_order_acquire))
            return POCKET_SERVER_INVALID_ARGUMENT;
        const auto queued = m_bot_target_fence.queue(
            target, generation, m_effective_bot_target.load(std::memory_order_acquire));
        switch (queued.state)
        {
            case pocket_server::BotTargetFence::QueueState::REJECTED:
                return POCKET_SERVER_WRONG_STATE;
            case pocket_server::BotTargetFence::QueueState::ALREADY_EFFECTIVE:
                return POCKET_SERVER_OK;
            case pocket_server::BotTargetFence::QueueState::QUEUED:
                break;
        }
        return m_bot_target_fence.wait_applied(
            queued.sequence, generation, std::chrono::milliseconds(timeout_ms)) ?
            POCKET_SERVER_OK : POCKET_SERVER_TIMEOUT;
#endif
    }

    void bot_status(jlong* values)
    {
        values[0] = POCKET_SERVER_ABI_VERSION;
#ifdef ENABLE_PLAYERBOTS
        values[1] = 1;
#else
        values[1] = 0;
#endif
        values[2] = m_bot_enabled.load(std::memory_order_acquire) ? 1 : 0;
        values[3] = m_bots_available.load(std::memory_order_acquire);
        values[4] = m_bots_online.load(std::memory_order_acquire);
        values[5] = m_effective_bot_target.load(std::memory_order_acquire);
        values[6] = m_bot_accounts.load(std::memory_order_acquire);
        for (size_t i = 0; i < m_low_cpu_telemetry.size(); ++i)
            values[7 + i] = m_low_cpu_telemetry[i].load(std::memory_order_acquire);
    }

    void performance_status(jlong* values)
    {
        values[0] = POCKET_SERVER_ABI_VERSION;
        std::vector<uint32_t> samples;
        {
            std::lock_guard<std::mutex> guard(m_tick_window_mutex);
            samples.assign(m_tick_window.begin(), m_tick_window.begin() + m_tick_window_count);
        }
        values[1] = static_cast<jlong>(samples.size());
        if (samples.empty()) return;
        std::sort(samples.begin(), samples.end());
        const auto percentile = [&samples](size_t numerator, size_t denominator) {
            const size_t rank = (samples.size() * numerator + denominator - 1) / denominator;
            return samples[std::min(samples.size() - 1, std::max<size_t>(1, rank) - 1)];
        };
        values[2] = percentile(50, 100);
        values[3] = percentile(95, 100);
        values[4] = percentile(99, 100);
        values[5] = samples.back();
        values[6] = static_cast<jlong>(std::count_if(samples.begin(), samples.end(),
            [](uint32_t duration) { return duration > 1000; }));
        values[7] = static_cast<jlong>(m_hard_stall_total.load(std::memory_order_acquire));
        values[8] = static_cast<jlong>(m_last_hard_stall_elapsed_ms.load(std::memory_order_acquire));
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

    bool verify_account_password(const std::string& username, const std::string& password)
    {
        if (m_state.state() != POCKET_SERVER_READY || username.empty() || password.empty() ||
            username.size() > 16 || password.size() > 16)
            return false;
        const auto is_account_token = [](const std::string& value) {
            return std::all_of(value.begin(), value.end(),
                [](unsigned char c) { return c < 0x80 && std::isalnum(c); });
        };
        if (!is_account_token(username) || !is_account_token(password)) return false;
        const auto account = account_info(username);
        return account.first != 0 && sAccountMgr.CheckPassword(account.first, password);
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

    std::string character_persistence(const std::string& username, const std::string& character_name)
    {
        if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)
            return "{\"found\":false,\"reason\":\"world-not-ready\"}";
        const auto ascii_alnum = [](const std::string& value) {
            return std::all_of(value.begin(), value.end(),
                [](unsigned char c) { return c < 0x80 && std::isalnum(c); });
        };
        const auto ascii_letters = [](const std::string& value) {
            return std::all_of(value.begin(), value.end(),
                [](unsigned char c) { return c < 0x80 && std::isalpha(c); });
        };
        if (username.empty() || username.size() > 16 || !ascii_alnum(username) ||
            character_name.size() < 2 || character_name.size() > 12 || !ascii_letters(character_name))
            return "{\"found\":false,\"reason\":\"invalid-identity\"}";

        const auto account = account_info(username);
        if (account.first == 0) return "{\"found\":false,\"reason\":\"account-missing\"}";
        std::string escaped_name = character_name;
        CharacterDatabase.escape_string(escaped_name);
        auto character = CharacterDatabase.PQuery(
            "SELECT guid,account,name,race,class,gender,level,xp,money,"
            "position_x,position_y,position_z,map,orientation,cinematic "
            "FROM characters WHERE account='%u' AND name='%s' AND deleteDate IS NULL LIMIT 1",
            account.first, escaped_name.c_str());
        if (!character) return "{\"found\":false,\"reason\":\"character-missing\"}";
        Field* fields = character->Fetch();
        const uint32_t guid = fields[0].GetUInt32();

        std::ostringstream inventory_rows;
        uint32_t inventory_count = 0;
        uint32_t sentinel_entry = 0;
        uint32_t sentinel_count = 0;
        if (auto inventory = CharacterDatabase.PQuery(
                "SELECT ci.bag,ci.slot,ci.item,ci.item_template,COALESCE(ii.count,0) "
                "FROM character_inventory ci LEFT JOIN item_instance ii ON ii.guid=ci.item "
                "WHERE ci.guid='%u' ORDER BY ci.bag,ci.slot,ci.item,ci.item_template", guid))
        {
            do
            {
                Field* row = inventory->Fetch();
                inventory_rows << row[0].GetUInt32() << ':' << row[1].GetUInt32() << ':'
                               << row[2].GetUInt32() << ':' << row[3].GetUInt32() << ':'
                               << row[4].GetUInt32() << '\n';
                if (inventory_count == 0)
                {
                    sentinel_entry = row[3].GetUInt32();
                    sentinel_count = row[4].GetUInt32();
                }
                ++inventory_count;
            } while (inventory->NextRow());
        }

        std::ostringstream quest_rows;
        uint32_t quest_count = 0;
        if (auto quests = CharacterDatabase.PQuery(
                "SELECT quest,status,rewarded,explored,timer,mobcount1,mobcount2,mobcount3,mobcount4,"
                "itemcount1,itemcount2,itemcount3,itemcount4 FROM character_queststatus "
                "WHERE guid='%u' ORDER BY quest", guid))
        {
            do
            {
                Field* row = quests->Fetch();
                for (int i = 0; i < 13; ++i)
                {
                    if (i) quest_rows << ':';
                    quest_rows << row[i].GetUInt64();
                }
                quest_rows << '\n';
                ++quest_count;
            } while (quests->NextRow());
        }

        const std::string inventory_digest = sha256_hex(inventory_rows.str());
        const std::string quest_digest = sha256_hex(quest_rows.str());
        std::ostringstream durable;
        durable << guid << ':' << fields[1].GetUInt32() << ':' << fields[2].GetCppString() << ':'
                << fields[3].GetUInt32() << ':' << fields[4].GetUInt32() << ':'
                << fields[5].GetUInt32() << ':' << fields[6].GetUInt32() << ':'
                << fields[7].GetUInt32() << ':' << fields[8].GetUInt32() << ':'
                << std::setprecision(9) << fields[9].GetFloat() << ':' << fields[10].GetFloat() << ':'
                << fields[11].GetFloat() << ':' << fields[12].GetUInt32() << ':'
                << fields[13].GetFloat() << ':' << inventory_digest << ':' << quest_digest;

        std::ostringstream json;
        json << "{\"found\":true,\"guid\":" << guid
             << ",\"accountId\":" << fields[1].GetUInt32()
             << ",\"name\":\"" << json_escape(fields[2].GetCppString()) << "\""
             << ",\"race\":" << fields[3].GetUInt32()
             << ",\"class\":" << fields[4].GetUInt32()
             << ",\"gender\":" << fields[5].GetUInt32()
             << ",\"level\":" << fields[6].GetUInt32()
             << ",\"xp\":" << fields[7].GetUInt32()
             << ",\"money\":" << fields[8].GetUInt32()
             << ",\"position\":{\"x\":" << std::setprecision(9) << fields[9].GetFloat()
             << ",\"y\":" << fields[10].GetFloat() << ",\"z\":" << fields[11].GetFloat()
             << ",\"map\":" << fields[12].GetUInt32() << ",\"orientation\":" << fields[13].GetFloat() << "}"
             << ",\"cinematic\":" << fields[14].GetUInt32()
             << ",\"inventoryCount\":" << inventory_count
             << ",\"inventorySha256\":\"" << inventory_digest << "\""
             << ",\"inventorySentinel\":{\"itemEntry\":" << sentinel_entry
             << ",\"count\":" << sentinel_count << "}"
             << ",\"questCount\":" << quest_count
             << ",\"questSha256\":\"" << quest_digest << "\""
             << ",\"durableSha256\":\"" << sha256_hex(durable.str()) << "\"}";
        return json.str();
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
        m_bot_target_fence.reset();
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
        if (m_state.state() == POCKET_SERVER_FAILED)
        {
            // A FAILED worker completed on its own (this used to be
            // conflated with a wedge TIMEOUT): join it and finish the stop.
            std::lock_guard<std::mutex> guard(m_lifecycle);
            if (m_worker.joinable()) m_worker.join();
            m_state.transition(POCKET_SERVER_STOPPED);
            return POCKET_SERVER_OK;
        }
        if (m_state.state() != POCKET_SERVER_STOPPED)
        {
            // Wedged teardown: the old code leaked the joinable
            // worker forever AND reported TIMEOUT identically to a clean
            // failure. Detach with a loud record so the caller sees the wedge;
            // the worker thread dies with the :world process at service exit.
            std::lock_guard<std::mutex> guard(m_lifecycle);
            if (m_worker.joinable())
            {
                m_worker.detach();
                sLog.outError("PocketRealm: world stop timed out after %llums; worker detached",
                              static_cast<unsigned long long>(timeout_ms));
            }
            return POCKET_SERVER_TIMEOUT;
        }
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
        if (duration > 1000)
        {
            m_hard_stall_total.fetch_add(1, std::memory_order_relaxed);
            // Store the elapsed duration (this previously stored a
            // monotonic TIMESTAMP into a field named *_elapsed_ms).
            m_last_hard_stall_elapsed_ms.store(duration, std::memory_order_release);
            if (m_consecutive_hard_stalls.fetch_add(1, std::memory_order_relaxed) + 1 >=
                    HARD_STALL_FAIL_STREAK)
            {
                // Watchdog escalation Kotlin already backs bots
                // off on repeated stalls; a world loop wedged for this many
                // CONSECUTIVE >1s ticks (~minutes) is failed loudly instead of
                // running dead forever.
                World::StopNow(SHUTDOWN_EXIT_CODE);
                fail(POCKET_SERVER_INTERNAL, "world loop wedged: repeated consecutive hard stalls");
            }
        }
        else
        {
            m_consecutive_hard_stalls.store(0, std::memory_order_relaxed);
        }
        {
            std::lock_guard<std::mutex> guard(m_tick_window_mutex);
            m_tick_window[m_tick_window_cursor] = duration;
            m_tick_window_cursor = (m_tick_window_cursor + 1) % m_tick_window.size();
            m_tick_window_count = std::min(m_tick_window_count + 1, m_tick_window.size());
        }
#ifdef ENABLE_PLAYERBOTS
        if (m_bot_enabled.load(std::memory_order_acquire))
        {
            m_bot_target_fence.consume([&](int pending) {
                sRandomPlayerbotMgr.SetValue(uint32(0), "bot_count", static_cast<uint32>(pending));
                m_effective_bot_target.store(pending, std::memory_order_release);
            });
            const LowCpuBotTelemetry telemetry = sRandomPlayerbotMgr.GetLowCpuTelemetry();
            if (telemetry.sampledAt != m_last_low_cpu_sampled_at)
            {
                m_last_low_cpu_sampled_at = telemetry.sampledAt;
                m_bots_online.store(telemetry.onlineBots, std::memory_order_release);
                const uint32_t values[] = {
                    telemetry.sampledAt,
                    telemetry.activeBots,
                    telemetry.realPlayers,
                    telemetry.sameActiveZone,
                    telemetry.within150,
                    telemetry.within500,
                    telemetry.within1500,
                    telemetry.levelDelta2,
                    telemetry.levelDelta4,
                    telemetry.loginsLast60s,
                    telemetry.teleportsLast60s,
                    telemetry.rerandomizesLast60s,
                };
                for (size_t i = 0; i < m_low_cpu_telemetry.size(); ++i)
                    m_low_cpu_telemetry[i].store(values[i], std::memory_order_release);
            }
        }
#endif
        m_state.beat();
    }

private:
    static std::string sha256_hex(const std::string& value)
    {
        unsigned char digest[SHA256_DIGEST_LENGTH];
        SHA256(reinterpret_cast<const unsigned char*>(value.data()), value.size(), digest);
        std::ostringstream hex;
        hex << std::hex << std::setfill('0');
        for (unsigned char byte : digest) hex << std::setw(2) << static_cast<unsigned int>(byte);
        return hex.str();
    }

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
            {
                fail(POCKET_SERVER_CONFIG, "world configuration rejected");
                cleanup();  // early fails skipped teardown
                return;
            }
#ifdef ENABLE_PLAYERBOTS
            sPlayerbotAIConfig.SetConfigSource(
                sConfig.GetStringDefault("PocketRealm.PlayerbotConfig", ""));
            const int configured_bot_target = sConfig.GetIntDefault("PocketRealm.BotTarget", 0);
#endif
            sLog.Initialize();
            if (!sMaster.StartDatabasesEmbedded())
            {
                fail(POCKET_SERVER_DB_REVISION, "world database connect or revision check failed");
                cleanup();  // early fails skipped teardown
                return;
            }
            bool client_data_gate = false;
            if (!sMaster.InitWorldEmbedded(&client_data_gate))
            {
                fail(client_data_gate ? POCKET_SERVER_DATA_MISSING : POCKET_SERVER_DATA_BUILD,
                     client_data_gate ? "verified client-derived data is missing" : "world data load failed");
                cleanup();  // early fails skipped teardown
                return;
            }
#ifdef ENABLE_PLAYERBOTS
            if (sPlayerbotAIConfig.enabled)
            {
                if (configured_bot_target < static_cast<int>(sPlayerbotAIConfig.minRandomBots) ||
                    configured_bot_target > static_cast<int>(sPlayerbotAIConfig.maxRandomBots))
                {
                    fail(POCKET_SERVER_CONFIG, "bot target is outside the measured profile bounds");
                    cleanup();  // early fails skipped teardown
                    return;
                }
                m_bot_min.store(sPlayerbotAIConfig.minRandomBots, std::memory_order_release);
                m_bot_max.store(sPlayerbotAIConfig.maxRandomBots, std::memory_order_release);
                m_bot_accounts.store(sPlayerbotAIConfig.randomBotAccounts.size(), std::memory_order_release);
                sRandomPlayerbotMgr.SetValue(uint32(0), "bot_count", configured_bot_target);
                m_effective_bot_target.store(configured_bot_target, std::memory_order_release);
                m_bot_enabled.store(true, std::memory_order_release);

                uint32_t available = 0;
                if (!sPlayerbotAIConfig.randomBotAccounts.empty())
                {
                    std::ostringstream ids;
                    bool first = true;
                    for (const uint32_t account : sPlayerbotAIConfig.randomBotAccounts)
                    {
                        if (!first) ids << ',';
                        ids << account;
                        first = false;
                    }
                    if (auto count = CharacterDatabase.PQuery(
                            "SELECT COUNT(*) FROM characters WHERE account IN (%s)", ids.str().c_str()))
                        available = count->Fetch()[0].GetUInt32();
                }
                m_bots_available.store(available, std::memory_order_release);
            }
#endif
            if (!sMaster.StartNetworkEmbedded(1))
            {
                fail(POCKET_SERVER_PORT_IN_USE, "world listener failed");
                cleanup();  // early fails skipped teardown
                return;
            }
            m_started = true;
            m_state.transition(POCKET_SERVER_READY);
            // Also exit on FAILED : the hard-stall watchdog fails the
            // state from the world thread; the loop must not outlive it or
            // stop()'s join hangs forever.
            while (!m_stop.load(std::memory_order_acquire) &&
                   m_state.state() != POCKET_SERVER_FAILED)
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
        m_bot_target_fence.reset();
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
    std::atomic<uint64_t> m_hard_stall_total{0};
    std::atomic<uint64_t> m_last_hard_stall_elapsed_ms{0};
    std::atomic<uint32_t> m_consecutive_hard_stalls{0};
    // >=60 consecutive ticks over 1s each (world loop wedged for a minute+).
    static constexpr uint32_t HARD_STALL_FAIL_STREAK = 60;
    std::atomic<bool> m_bot_enabled{false};
    std::atomic<uint32_t> m_bots_available{0};
    std::atomic<uint32_t> m_bots_online{0};
    std::atomic<uint32_t> m_bot_accounts{0};
    std::atomic<int> m_bot_min{0};
    std::atomic<int> m_bot_max{0};
    std::atomic<int> m_effective_bot_target{0};
    pocket_server::BotTargetFence m_bot_target_fence;
    std::array<std::atomic<uint32_t>, 12> m_low_cpu_telemetry{};
    uint32_t m_last_low_cpu_sampled_at{0};
    std::array<uint32_t, 2048> m_tick_window{};
    size_t m_tick_window_count{0};
    size_t m_tick_window_cursor{0};
    std::mutex m_tick_window_mutex;
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
Java_com_pocketrealm_server_WorldNative_setBotTargetNative(JNIEnv*, jclass, jint target)
{ return g_runtime.set_bot_target(static_cast<int>(target), 0, 5'000); }

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_beginAdmissionBotTargetGenerationNative(
    JNIEnv*, jclass, jlong generation)
{ return g_runtime.begin_bot_target_generation(static_cast<int64_t>(generation)); }

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_setAdmissionBotTargetNative(
    JNIEnv*, jclass, jint target, jlong generation)
{ return g_runtime.set_bot_target(
    static_cast<int>(target), static_cast<int64_t>(generation), 5'000); }

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_retireAdmissionBotTargetGenerationNative(
    JNIEnv*, jclass, jlong generation)
{ return g_runtime.retire_bot_target_generation(static_cast<int64_t>(generation)); }

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pocketrealm_server_WorldNative_botStatusNative(JNIEnv* env, jclass)
{
    jlong values[19]{};
    g_runtime.bot_status(values);
    jlongArray result = env->NewLongArray(19);
    env->SetLongArrayRegion(result, 0, 19, values);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_pocketrealm_server_WorldNative_performanceStatusNative(JNIEnv* env, jclass)
{
    jlong values[9]{};
    g_runtime.performance_status(values);
    jlongArray result = env->NewLongArray(9);
    env->SetLongArrayRegion(result, 0, 9, values);
    return result;
}

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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketrealm_server_WorldNative_verifyAccountPasswordNative(
    JNIEnv* env, jclass, jstring username, jstring password)
{
    return g_runtime.verify_account_password(
        from_jstring(env, username), from_jstring(env, password)) ? JNI_TRUE : JNI_FALSE;
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
Java_com_pocketrealm_server_WorldNative_characterPersistenceNative(
    JNIEnv* env, jclass, jstring username, jstring character_name)
{
    return to_jstring(env, g_runtime.character_persistence(
        from_jstring(env, username), from_jstring(env, character_name)));
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
