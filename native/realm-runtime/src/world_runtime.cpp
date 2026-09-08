#include "server_common.h"
#include "bot_target_fence.h"

#include "Common.h"
#include "Config/Config.h"
#include "Accounts/AccountMgr.h"
#include "Database/DatabaseEnv.h"
#include "Entities/Player.h"
#include "Globals/ObjectAccessor.h"
#include "Globals/SharedDefines.h"
#include "Log/Log.h"
#include "Master.h"
#include "Server/Opcodes.h"
#include "Server/WorldPacket.h"
#include "Server/WorldSession.h"
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
        std::unique_lock<std::mutex> guard(m_lifecycle);
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
        reset_chat_injection();
        for (auto& value : m_low_cpu_telemetry)
            value.store(0, std::memory_order_release);
        m_last_low_cpu_sampled_at = 0;
        m_state.transition(POCKET_SERVER_STARTING);
        m_worker = std::thread([this, config] { run(config); });
        guard.unlock();
        // Honest spawn verdict (the stack-up-bot composite lesson): the
        // boot's early legs - config reject, database connect/revision,
        // the bot-lane arming below - settle or fail within seconds, and
        // the blanket OK this function used to return let the caller
        // report success over a world that failed (or came up with the
        // bot lane dark) a moment later. Wait for that verdict with the
        // lifecycle lock released (stop() and a second start() must stay
        // live), then report the real outcome: the worker's error code
        // on FAILED, OK on READY. A boot still STARTING at the deadline
        // keeps the old async contract - OK plus the caller's status
        // polling - so slow devices see exactly the response they always
        // did; only settled failures change what start() says.
        const uint64_t deadline = pocket_server::monotonic_ms() + START_VERDICT_TIMEOUT_MS;
        while (m_state.state() == POCKET_SERVER_STARTING &&
               pocket_server::monotonic_ms() < deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        if (m_state.state() == POCKET_SERVER_FAILED)
            return m_state.error();
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
        // The login gate's measured CharacterDatabase round-trip (the ~10 s
        // probe) as the DB-latency telemetry channel:
        // 0 = never sampled, UINT32_MAX = gate closed / probe expired. Read
        // from the atomic mirror only - the playerbots map itself lives on
        // the world thread.
        values[9] = static_cast<jlong>(m_db_probe_delay_ms.load(std::memory_order_acquire));
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
        // The tombstone_02 crash path: LoginDatabase's query pool only
        // exists between StartDatabasesEmbedded and teardown - escape_string
        // indexes m_pQueryConnections[0] and PQuery divides by the pool
        // size, both on an EMPTY vector while the world is FAILED or still
        // STARTING (the service's accountResult asks this even when the
        // op itself was refused, so the relay's account ops reach here on
        // a dead world). Guard HERE, not at the JNI boundary: this method
        // is the one layer every caller crosses (accountInfoNative,
        // verify_account_password, character_persistence, accountResult)
        // and the {0,-1} "no account" pair is the answer they already
        // translate. Same READY/SAVING family as the sibling relay ops.
        if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)
            return {0, -1};
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

    // ---- H2 relay-min: the per-change smoke rail's three fixed-purpose
    // ops. All three answer in JSON (the character_persistence pattern):
    // no SQL and no raw chat text crosses Binder - each op is a fixed verb
    // with native-side validation and escaping. world_chat additionally
    // marshals to the world thread: the session chat handlers (and every
    // playerbot hook inside them) run there, so the packet is drained from
    // record_tick like the bot-target fence, never touched from the
    // binder thread.

    std::string world_chat(const std::string& character_name, const std::string& channel,
        const std::string& target_name, const std::string& text, uint64_t timeout_ms)
    {
        if (m_state.state() != POCKET_SERVER_READY)
            return "{\"ok\":false,\"reason\":\"world-not-ready\"}";
        const auto ascii_letters = [](const std::string& value) {
            return !value.empty() && std::all_of(value.begin(), value.end(),
                [](unsigned char c) { return c < 0x80 && std::isalpha(c); });
        };
        const auto valid_name = [&](const std::string& value) {
            return value.size() >= 2 && value.size() <= 12 && ascii_letters(value);
        };
        // the wire limit CheckChatMessage enforces on real clients (over
        // 255 kicks the sender); printable ASCII only - no controls, no
        // multibyte, exactly what the relay could type on a client
        const auto valid_text = [](const std::string& value) {
            return !value.empty() && value.size() <= 255 &&
                std::all_of(value.begin(), value.end(),
                    [](unsigned char c) { return c >= 0x20 && c < 0x7f; });
        };
        uint32_t type = 0;
        if (channel == "say") type = CHAT_MSG_SAY;
        else if (channel == "party") type = CHAT_MSG_PARTY;
        else if (channel == "yell") type = CHAT_MSG_YELL;
        else if (channel == "whisper") type = CHAT_MSG_WHISPER;
        else return "{\"ok\":false,\"reason\":\"unknown-channel\"}";
        if (!valid_name(character_name))
            return "{\"ok\":false,\"reason\":\"invalid-character-name\"}";
        // target is the receiving bot name, whisper only
        if (type == CHAT_MSG_WHISPER ? !valid_name(target_name) : !target_name.empty())
            return "{\"ok\":false,\"reason\":\"invalid-target\"}";
        // a leading '.' would be eaten by ChatHandler::ParseCommands as a
        // GM command instead of reaching the chat path
        if (!valid_text(text) || text[0] == '.')
            return "{\"ok\":false,\"reason\":\"invalid-text\"}";
        {
            std::lock_guard<std::mutex> guard(m_chat_slot.mutex);
            if (m_chat_slot.pending) return "{\"ok\":false,\"reason\":\"busy\"}";
            m_chat_slot.pending = true;
            m_chat_slot.done = false;
            m_chat_slot.ok = false;
            m_chat_slot.reason.clear();
            m_chat_slot.request.type = type;
            m_chat_slot.request.sender = character_name;
            m_chat_slot.request.target = target_name;
            m_chat_slot.request.text = text;
            m_chat_slot.changed.notify_all();
        }
        // the drain runs on the next unpaused world tick; companion mode's
        // reduced duty cycle still ticks, so a paused world only delays the
        // injection until its 1 Hz update
        std::unique_lock<std::mutex> guard(m_chat_slot.mutex);
        const bool finished = m_chat_slot.changed.wait_for(
            guard, std::chrono::milliseconds(timeout_ms),
            [&] { return m_chat_slot.done; });
        const bool ok = finished && m_chat_slot.ok;
        const std::string reason = ok ? std::string() :
            (finished ? m_chat_slot.reason : std::string("world-tick-timeout"));
        // single-use slot: harvest the verdict, then clear for the next op
        // (a timeout does NOT cancel an in-flight drain - the packet may
        // still land; only the caller's view expired)
        m_chat_slot.pending = false;
        m_chat_slot.done = false;
        m_chat_slot.request = ChatInjectionRequest();
        std::ostringstream json;
        json << "{\"ok\":" << (ok ? "true" : "false")
             << ",\"injected\":" << (ok ? "true" : "false")
             << ",\"channel\":\"" << json_escape(channel) << "\""
             << ",\"char\":\"" << json_escape(character_name) << "\"";
        if (type == CHAT_MSG_WHISPER)
            json << ",\"target\":\"" << json_escape(target_name) << "\"";
        json << ",\"textBytes\":" << text.size();
        if (!ok) json << ",\"reason\":\"" << json_escape(reason) << "\"";
        json << "}";
        return json.str();
    }

    std::string reset_state(const std::string& player_name)
    {
        if (m_state.state() != POCKET_SERVER_READY)
            return "{\"ok\":false,\"reason\":\"world-not-ready\"}";
        // Test isolation: clears the LLM assertion state - the two memory
        // tables whose contents the rp-harness assertions read. Deleting
        // while bots are online is acceptable for tests: facts re-mint on
        // the next conversation (GetOrCreateBackstory/LogFact are
        // mint-on-demand) and relationships regrow from zero. The
        // process-local rolling history and chatter pools are NOT cleared
        // - they die with the world process, which a full reset restarts.
        uint32_t player_guid = 0;
        if (!player_name.empty())
        {
            if (player_name.size() < 2 || player_name.size() > 12 ||
                !std::all_of(player_name.begin(), player_name.end(),
                    [](unsigned char c) { return c < 0x80 && std::isalpha(c); }))
                return "{\"ok\":false,\"reason\":\"invalid-player-name\"}";
            std::string escaped = player_name;
            CharacterDatabase.escape_string(escaped);
            if (auto result = CharacterDatabase.PQuery(
                    "SELECT guid FROM characters WHERE name='%s' AND deleteDate IS NULL LIMIT 1",
                    escaped.c_str()))
                player_guid = result->Fetch()[0].GetUInt32();
            if (!player_guid) return "{\"ok\":false,\"reason\":\"player-missing\"}";
        }
        // PExecute reports no affected rows, so the counts are pre-delete
        // snapshots taken under the same call. They iterate real rows from
        // the same tables and player key the llm-memory-state listing reads
        // (a folded COUNT(*) on the pooled sync connection loses the busy
        // race with the live writer and folds to a null result - reported
        // as 0 over rows that existed), and a lost read is retried, never
        // silently zeroed.
        const auto count_rows = [&](const char* table) {
            for (int attempt = 0; attempt < 3; ++attempt)
            {
                if (auto result = player_guid
                        ? CharacterDatabase.PQuery(
                              "SELECT bot FROM %s WHERE player='%u'", table, player_guid)
                        : CharacterDatabase.PQuery("SELECT bot FROM %s", table))
                {
                    uint32_t counted = 0;
                    do { ++counted; } while (result->NextRow());
                    return counted;
                }
            }
            return uint32_t(0);
        };
        const uint32_t facts = count_rows("bot_player_facts");
        const uint32_t relationships = count_rows("bot_player_relationship");
        if (player_guid)
        {
            CharacterDatabase.PExecute(
                "DELETE FROM bot_player_facts WHERE player='%u'", player_guid);
            CharacterDatabase.PExecute(
                "DELETE FROM bot_player_relationship WHERE player='%u'", player_guid);
        }
        else
        {
            CharacterDatabase.PExecute("DELETE FROM bot_player_facts");
            CharacterDatabase.PExecute("DELETE FROM bot_player_relationship");
        }
        std::ostringstream json;
        json << "{\"ok\":true,\"scope\":\"" << (player_guid ? "player" : "all") << "\"";
        if (player_guid)
            json << ",\"player\":\"" << json_escape(player_name) << "\""
                 << ",\"playerGuid\":" << player_guid;
        json << ",\"factsCleared\":" << facts
             << ",\"relationshipsCleared\":" << relationships << "}";
        return json.str();
    }

    std::string llm_memory_state(const std::string& player_name)
    {
        if (m_state.state() != POCKET_SERVER_READY && m_state.state() != POCKET_SERVER_SAVING)
            return "{\"ok\":false,\"reason\":\"world-not-ready\"}";
        if (player_name.empty())
        {
            // world summary - the smoke rail's bot picker. Names sorted so
            // suite selection is deterministic; the players-map read is the
            // same lock-guarded pattern as online_players().
            std::vector<std::string> bots;
            {
                HashMapHolder<Player>::ReadGuard guard(HashMapHolder<Player>::GetLock());
                for (auto& entry : sObjectAccessor.GetPlayers())
                    if (Player* player = entry.second)
                        if (player->GetPlayerbotAI())
                            bots.push_back(player->GetName());
            }
            std::sort(bots.begin(), bots.end());
            uint32_t total_facts = 0;
            uint32_t total_relationships = 0;
            if (auto result = CharacterDatabase.PQuery("SELECT COUNT(*) FROM bot_player_facts"))
                total_facts = result->Fetch()[0].GetUInt32();
            if (auto result = CharacterDatabase.PQuery("SELECT COUNT(*) FROM bot_player_relationship"))
                total_relationships = result->Fetch()[0].GetUInt32();
            std::ostringstream json;
            json << "{\"ok\":true,\"scope\":\"world\",\"botsOnline\":" << bots.size()
                 << ",\"onlineBots\":[";
            for (size_t i = 0; i < bots.size(); ++i)
            {
                if (i) json << ',';
                json << "\"" << json_escape(bots[i]) << "\"";
            }
            json << "],\"totalFacts\":" << total_facts
                 << ",\"totalRelationships\":" << total_relationships << "}";
            return json.str();
        }
        if (player_name.size() < 2 || player_name.size() > 12 ||
            !std::all_of(player_name.begin(), player_name.end(),
                [](unsigned char c) { return c < 0x80 && std::isalpha(c); }))
            return "{\"ok\":false,\"reason\":\"invalid-player-name\"}";
        std::string escaped = player_name;
        CharacterDatabase.escape_string(escaped);
        uint32_t player_guid = 0;
        if (auto result = CharacterDatabase.PQuery(
                "SELECT guid FROM characters WHERE name='%s' AND deleteDate IS NULL LIMIT 1",
                escaped.c_str()))
            player_guid = result->Fetch()[0].GetUInt32();
        if (!player_guid) return "{\"ok\":false,\"reason\":\"player-missing\"}";

        // per-bot relationship rows (the tier/points the tier_up
        // assertion observes), bot names joined in for normalized matching
        std::ostringstream relationships;
        uint32_t relationship_count = 0;
        if (auto result = CharacterDatabase.PQuery(
                "SELECT r.bot, c.name, r.tier, r.points FROM bot_player_relationship r "
                "LEFT JOIN characters c ON c.guid = r.bot WHERE r.player = '%u' ORDER BY r.bot",
                player_guid))
        {
            do
            {
                Field* row = result->Fetch();
                if (relationship_count) relationships << ',';
                relationships << "{\"bot\":\"" << json_escape(row[1].GetCppString()) << "\""
                              << ",\"botGuid\":" << row[0].GetUInt32()
                              << ",\"tier\":\"" << json_escape(row[2].GetCppString()) << "\""
                              << ",\"points\":" << row[3].GetInt32() << '}';
                ++relationship_count;
            } while (result->NextRow());
        }

        // per-(bot, prefix) fact counts: the prefix bucket is the first 16
        // bytes of fact_text (SUBSTR is engine-common; the lane is sqlite),
        // category kept so opinion/preference rows stay distinguishable
        std::ostringstream facts;
        uint32_t fact_count = 0;
        if (auto result = CharacterDatabase.PQuery(
                "SELECT f.bot, c.name, SUBSTR(f.fact_text, 1, 16), f.category, COUNT(*) "
                "FROM bot_player_facts f LEFT JOIN characters c ON c.guid = f.bot "
                "WHERE f.player = '%u' "
                "GROUP BY f.bot, SUBSTR(f.fact_text, 1, 16), f.category "
                "ORDER BY f.bot, f.category",
                player_guid))
        {
            do
            {
                Field* row = result->Fetch();
                if (fact_count) facts << ',';
                facts << "{\"bot\":\"" << json_escape(row[1].GetCppString()) << "\""
                      << ",\"botGuid\":" << row[0].GetUInt32()
                      << ",\"prefix\":\"" << json_escape(row[2].GetCppString()) << "\""
                      << ",\"category\":\"" << json_escape(row[3].GetCppString()) << "\""
                      << ",\"count\":" << row[4].GetUInt32() << '}';
                ++fact_count;
            } while (result->NextRow());
        }

        std::ostringstream json;
        json << "{\"ok\":true,\"scope\":\"player\""
             << ",\"player\":\"" << json_escape(player_name) << "\""
             << ",\"playerGuid\":" << player_guid
             << ",\"relationships\":[" << relationships.str() << "]"
             << ",\"relationshipCount\":" << relationship_count
             << ",\"facts\":[" << facts.str() << "]"
             << ",\"factCount\":" << fact_count << "}";
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
        // the world-chat injection drains here (world thread) before any
        // tick accounting - see drain_chat_injection for the path
        drain_chat_injection();
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
            // Mirror the login-gate probe result into an atomic so the
            // status-poll thread never reads the playerbots map (world-thread
            // state) directly - same marshaling pattern as the telemetry
            // struct below.
            m_db_probe_delay_ms.store(
                sRandomPlayerbotMgr.GetDatabaseDelay("CharacterDatabase"),
                std::memory_order_release);
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
            else if (configured_bot_target > 0)
            {
                // Only a bot-profile start (world-start-bot / the
                // stack-up-bot composite) carries a nonzero
                // PocketRealm.BotTarget - plain and integrated app boots
                // write the disabled conf with target 0 and must stay OK
                // with the lane dark. If a bot-profile conf did not arm
                // the lane (unreadable, or Enabled=0), the world used to
                // come up READY with playerbotsEnabled=false and 0 bots
                // while the caller had already been told ok. Fail the
                // boot instead so the verdict names the leg.
                fail(POCKET_SERVER_CONFIG,
                     "bot profile start did not arm the playerbot lane");
                cleanup();  // early fails skipped teardown
                return;
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

    // ---- H2 relay-min: chat injection plumbing ----
    // The binder thread queues a synthetic chat line; the world thread
    // drains it on the next tick (session chat handlers and the playerbot
    // hooks inside them run there - the same marshaling law as the bot
    // target fence). One pending slot: the relay is serial by design.

    struct ChatInjectionRequest
    {
        uint32_t type{0};
        std::string sender;
        std::string target;
        std::string text;
    };

    struct ChatInjectionSlot
    {
        std::mutex mutex;
        std::condition_variable changed;
        bool pending{false};
        bool done{false};
        bool ok{false};
        std::string reason;
        ChatInjectionRequest request;
    };

    void drain_chat_injection()
    {
        ChatInjectionRequest request;
        {
            std::lock_guard<std::mutex> guard(m_chat_slot.mutex);
            if (!m_chat_slot.pending) return;
            request = m_chat_slot.request;
        }
        // Injection path (the one design decision this op owns): build a
        // synthetic CMSG_MESSAGECHAT packet in the exact wire layout a
        // client sends - uint32 type, uint32 lang, then the per-type
        // payload (whisper: to-name then text; say/party/yell: text) - and
        // hand it to WorldSession::HandleMessagechatOpcode, the same
        // function the opcode table dispatches for real client packets.
        // Every downstream gate therefore runs identically for an injected
        // line and a client line: CheckChatMessage, the CanSpeak flood
        // control, ChatHandler::ParseCommands, RandomPlayerbotMgr's
        // radius/team filters on say/yell, the per-bot
        // PlayerbotAI::HandleCommand hook on whisper, and inside it the
        // LLM say/party/whisper trigger gates under test. LANG_UNIVERSAL
        // reads as every faction so the receiving bots understand the line
        // regardless of team. A session-grafted synthetic login was
        // rejected: without a socket the session reads as a bot
        // (GetRemoteAddress() == "disconnected/bot"), so isRealPlayer()
        // stays false and the LLM conversational gates would never fire.
        Player* sender = sObjectAccessor.FindPlayerByName(request.sender.c_str());
        WorldSession* session = sender ? sender->GetSession() : nullptr;
        if (!session)
            return finish_chat_injection(false, "sender-not-online");
        WorldPacket packet(CMSG_MESSAGECHAT,
            16 + request.target.size() + request.text.size());
        packet << uint32(request.type);
        packet << uint32(LANG_UNIVERSAL);
        if (request.type == CHAT_MSG_WHISPER)
            packet << request.target;
        packet << request.text;
        session->HandleMessagechatOpcode(packet);
        finish_chat_injection(true, "");
    }

    void finish_chat_injection(bool ok, const std::string& reason)
    {
        std::lock_guard<std::mutex> guard(m_chat_slot.mutex);
        m_chat_slot.ok = ok;
        m_chat_slot.reason = reason;
        m_chat_slot.done = true;
        m_chat_slot.changed.notify_all();
    }

    void reset_chat_injection()
    {
        // lifecycle reset: wake any waiter with a verdict, never leave a
        // stale request queued for the next world run
        std::lock_guard<std::mutex> guard(m_chat_slot.mutex);
        m_chat_slot.pending = false;
        m_chat_slot.done = true;
        m_chat_slot.ok = false;
        m_chat_slot.reason = "world-lifecycle-reset";
        m_chat_slot.request = ChatInjectionRequest();
        m_chat_slot.changed.notify_all();
    }

    void cleanup()
    {
        m_bot_target_fence.reset();
        reset_chat_injection();
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
    // CharacterDatabase probe RTT mirror (ms); 0 = never sampled,
    // UINT32_MAX = login gate closed / probe expired. Written on the world
    // thread in record_tick, read by performance_status from poll threads.
    std::atomic<uint32_t> m_db_probe_delay_ms{0};
    // >=60 consecutive ticks over 1s each (world loop wedged for a minute+).
    static constexpr uint32_t HARD_STALL_FAIL_STREAK = 60;
    // start()'s settle wait (see start): long enough to cover the boot's
    // early fail legs (config / DB / bot-lane arming), short of the Kotlin
    // control timeout so slow boots keep the async OK + status polling.
    static constexpr uint64_t START_VERDICT_TIMEOUT_MS = 30'000;
    std::atomic<bool> m_bot_enabled{false};
    std::atomic<uint32_t> m_bots_available{0};
    std::atomic<uint32_t> m_bots_online{0};
    std::atomic<uint32_t> m_bot_accounts{0};
    std::atomic<int> m_bot_min{0};
    std::atomic<int> m_bot_max{0};
    std::atomic<int> m_effective_bot_target{0};
    pocket_server::BotTargetFence m_bot_target_fence;
    // H2 relay-min: the pending world-chat injection (world-thread drain;
    // see ChatInjectionSlot above)
    ChatInjectionSlot m_chat_slot;
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
    jlong values[10]{};
    g_runtime.performance_status(values);
    jlongArray result = env->NewLongArray(10);
    env->SetLongArrayRegion(result, 0, 10, values);
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

// ---- H2 relay-min smoke-rail ops ----

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketrealm_server_WorldNative_worldChatNative(
    JNIEnv* env, jclass, jstring character_name, jstring channel, jstring target,
    jstring text, jlong timeout_ms)
{
    return to_jstring(env, g_runtime.world_chat(from_jstring(env, character_name),
        from_jstring(env, channel), from_jstring(env, target), from_jstring(env, text),
        static_cast<uint64_t>(std::max<jlong>(0, timeout_ms))));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketrealm_server_WorldNative_resetStateNative(JNIEnv* env, jclass, jstring player)
{ return to_jstring(env, g_runtime.reset_state(from_jstring(env, player))); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketrealm_server_WorldNative_llmMemoryStateNative(JNIEnv* env, jclass, jstring player)
{ return to_jstring(env, g_runtime.llm_memory_state(from_jstring(env, player))); }

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

// ---- companion mode: world pause + LLM runtime profile switch ----

extern "C" void pocket_world_set_paused(int paused);
extern "C" int pocket_world_is_paused(void);

#ifdef ENABLE_PLAYERBOTS
#include "playerbot/PlayerbotLlamaRuntime.h"
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_pauseWorldNative(JNIEnv*, jclass, jint paused)
{
    pocket_world_set_paused(paused != 0 ? 1 : 0);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_isWorldPausedNative(JNIEnv*, jclass)
{ return pocket_world_is_paused(); }

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketrealm_server_WorldNative_setCompanionModeNative(JNIEnv*, jclass, jint enabled)
{
#ifdef ENABLE_PLAYERBOTS
    // companion mode = world paused + full-residency LLM profile for the
    // faster prefill; coexistence profile restores on exit
    PlayerbotLlamaRuntime::SetCompanionMode(enabled != 0);
    pocket_world_set_paused(enabled != 0 ? 1 : 0);
    return 0;
#else
    return -1;
#endif
}
