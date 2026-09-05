#!/usr/bin/env python3
"""Reproducibly build and stage the current Android realm libraries.

The product runtime compiles the pinned Playerbots module but keeps it
disabled unless an app-generated measured profile is supplied. AHBot remains
excluded. The historical zero-bot behavior is therefore still selectable.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
NATIVE = ROOT / "native"
TARGET_ABI = "x86_64"
BUILD = NATIVE / ".build-o09-x86_64"
SOURCE = BUILD / "sources" / "mariadb-connector-c"
CONNECTOR_BUILD = BUILD / "mariadb-connector"
CMANGOS_BUILD = BUILD / "cmangos"
STAGE = BUILD / "realm-staging" / "jniLibs" / "x86_64"
PROVENANCE = BUILD / "realm-staging" / "BUILD_PROVENANCE.json"
LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile.json"
CONNECTOR_URL = "https://github.com/MariaDB/mariadb-connector-c.git"
CONNECTOR_COMMIT = "de6305915f86bb33c83b1fe782a2b8a76920aec1"
CMANGOS_COMMIT = "082afd606f8e37ea939df6fdfcd4af81f8085e6e"
PLAYERBOTS_COMMIT = "6c681ef8dd63cb96f111dc9239d569d6663347e5"
MAX_PAGE = 0x4000
BACKEND = "mysql"


def select_abi(abi: str) -> None:
    """Select isolated paths and Android target flags before any build work."""
    global TARGET_ABI, BUILD, SOURCE, CONNECTOR_BUILD, CMANGOS_BUILD, STAGE, PROVENANCE, LOCKFILE
    if abi not in {"x86_64", "arm64-v8a"}:
        raise ValueError(f"unsupported realm ABI: {abi}")
    TARGET_ABI = abi
    BUILD = NATIVE / f".build-o09-{abi}"
    SOURCE = BUILD / "sources" / "mariadb-connector-c"
    CONNECTOR_BUILD = BUILD / "mariadb-connector"
    CMANGOS_BUILD = BUILD / "cmangos"
    STAGE = BUILD / "realm-staging" / "jniLibs" / abi
    PROVENANCE = BUILD / "realm-staging" / "BUILD_PROVENANCE.json"
    LOCKFILE = ROOT / ("schemas/realm-runtime-lockfile.json" if abi == "x86_64"
                       else f"schemas/realm-runtime-lockfile-{abi}.json")
CMANGOS_OVERLAYS = [
    {
        "id": "authenticated-nearby-use-open",
        "backends": ["mysql", "sqlite"],
        "paths": [
            "src/game/World/World.h",
            "src/game/World/World.cpp",
            "src/game/Server/WorldSession.h",
            "src/game/Chat/ChatHandler.cpp",
            "src/game/Chat/PocketRealmInteraction.cpp",
        ],
        "reason": "Let the managed Vanilla addon request one rate-limited nearby corpse/chest/ordinary-object interaction through the authenticated session and existing loot/use handlers.",
    },
    {
        "id": "mmap-disabled-load-guard",
        "backends": ["mysql", "sqlite"],
        "path": "src/game/Maps/GridMap.cpp",
        "reason": "Do not enter MMapManager::loadMap when mmap.enabled=0; the disabled manager intentionally has no map instance.",
    },
    {
        "id": "mmap-loadmap-graceful-miss",
        "backends": ["mysql", "sqlite"],
        "path": "src/game/MotionGenerators/MoveMap.cpp",
        "reason": "Entering a map whose navmesh was never registered (missing mmaps/NNN.mmap) must disable pathfinding for that map, not abort the world process.",
    },
    {
        "id": "mmap-loadallmaptiles-graceful-miss",
        "backends": ["mysql", "sqlite"],
        "path": "src/game/MotionGenerators/MoveMap.cpp",
        "reason": "The Playerbots tile preload path must degrade to a logged skip on an unregistered map instead of aborting the world process.",
    },
    {
        "id": "embedded-world-thread-rearm",
        "backends": ["mysql", "sqlite"],
        "path": "src/mangosd/Master.cpp",
        "reason": "Re-arm CMaNGOS process-global stop state immediately before each embedded world-thread launch.",
    },
    {
        "id": "result-callback-outside-queue-lock",
        "backends": ["mysql", "sqlite"],
        "path": "src/shared/Database/SqlOperations.cpp",
        "reason": "Execute async result callbacks outside the result-queue mutex so callbacks may safely issue direct statements while the database worker publishes another result.",
    },
    {
        "id": "llm-companion-event-hooks",
        "backends": ["mysql", "sqlite"],
        "paths": [
            "src/game/Entities/Player.cpp",
            "src/game/Loot/LootHandler.cpp",
        ],
        "reason": "Level-up and rare-loot interception points that feed the playerbot LLM companion's event allowlist (bounded party reactions, deterministic relationship milestones, verified-event window for share_gossip).",
    },
    {
        "id": "db-async-null-guard",
        "backends": ["mysql", "sqlite"],
        "paths": [
            "src/shared/Database/Database.h",
            "src/shared/Database/Database.cpp",
            "src/shared/Database/DatabaseImpl.h",
        ],
        "reason": "Route all twelve async enqueue sites through SafeDelayOperation/SafeDelayQueryHolder: HaltDelayThread() nulls m_threadBody while the sticky async flag stays on across embedded restart cycles, so an unguarded enqueue after a halt is a null dereference.",
    },
    {
        "id": "fail-loud-backend-selection",
        "backends": ["mysql", "sqlite"],
        "path": "CMakeLists.txt",
        "reason": "Refuse the no-op -DDO_MYSQL/-DDO_SQLITE cache defines, refuse PostgreSQL, and print the selected backend at configure time; the real switch is the SQLITE cache variable with MySQL as the else-default.",
    },
    {
        "id": "runtime-dialect-truncate",
        "backends": ["mysql", "sqlite"],
        "path": "src/game/Globals/ObjectMgr.cpp",
        "reason": "Route the two raw TRUNCATE sites through the backend _TRUNCATE_ macro (DELETE FROM under DO_SQLITE; TRUNCATE TABLE - semantically identical - under MySQL).",
    },
    {
        "id": "runtime-dialect-anticheat-prune",
        "backends": ["mysql", "sqlite"],
        "path": "src/game/Anticheat/module/libanticheat.cpp",
        "reason": "DELETE..ORDER BY..LIMIT is MySQL-only; under DO_SQLITE use the registered rowid-IN-subquery rewrite with the fingerprint predicate inside the subquery (exactly two positional binds unchanged).",
    },
    {
        "id": "db-sqlite-connection-hardening",
        "backends": ["sqlite"],
        "paths": [
            "src/shared/Database/DatabaseSqlite.h",
            "src/shared/Database/DatabaseSqlite.cpp",
            "src/shared/Database/QueryResultSqlite.h",
            "src/shared/Database/QueryResultSqlite.cpp",
            "src/shared/Database/SqlOperations.cpp",
        ],
        "reason": "DO_SQLITE-only hardened connection layer: WAL with synchronous=NORMAL rendered per connection, busy_timeout 500ms with BUSY retry instead of silent false, BEGIN IMMEDIATE write transactions, begin-failure refusal + commit-failure rollback, fully materialized query results under the connection lock, no leaked statement wrappers, explicit-length TRANSIENT text binds. DO_MYSQL builds are behaviorally unchanged.",
    },
]
POCKET_INTERACT_SOURCE = NATIVE / "patches" / "cmangos" / "PocketRealmInteraction.cpp"


def patches_content_digests() -> dict[str, str]:
    """sha256 of EVERY patch file under native/patches/ that rides a
    build (whole-file replacement sources; the anchor-replacement
    constants live in this driver).
    Recorded in the lockfile so any patches edit makes the committed
    lockfile mechanically stale - an edited patch file must force a
    deliberate lockfile regeneration, never a silent ride-along."""
    patches = NATIVE / "patches"
    out: dict[str, str] = {}
    for path in sorted(patches.rglob("*")):
        if path.is_file():
            out[path.relative_to(ROOT).as_posix()] = sha256(path)
    return out
POCKET_WORLD_H_UPSTREAM = """    CONFIG_BOOL_ADDON_CHANNEL,
    CONFIG_BOOL_CORPSE_EMPTY_LOOT_SHOW,
"""
POCKET_WORLD_H_ANDROID = """    CONFIG_BOOL_ADDON_CHANNEL,
    CONFIG_BOOL_POCKET_REALM_NEARBY_INTERACT,
    CONFIG_BOOL_CORPSE_EMPTY_LOOT_SHOW,
"""
POCKET_WORLD_UINT_UPSTREAM = """    CONFIG_UINT32_COMPRESSION = 0,
    CONFIG_UINT32_INTERVAL_SAVE,
"""
POCKET_WORLD_UINT_ANDROID = """    CONFIG_UINT32_COMPRESSION = 0,
    CONFIG_UINT32_POCKET_REALM_NEARBY_INTERACT_COOLDOWN_MS,
    CONFIG_UINT32_INTERVAL_SAVE,
"""
POCKET_WORLD_CPP_UPSTREAM = """    setConfig(CONFIG_BOOL_ADDON_CHANNEL, "AddonChannel", true);
    setConfig(CONFIG_BOOL_CLEAN_CHARACTER_DB, "CleanCharacterDB", true);
"""
POCKET_WORLD_CPP_ANDROID = """    setConfig(CONFIG_BOOL_ADDON_CHANNEL, "AddonChannel", true);
    setConfig(CONFIG_BOOL_POCKET_REALM_NEARBY_INTERACT, "PocketRealm.NearbyInteract", false);
    setConfigMinMax(CONFIG_UINT32_POCKET_REALM_NEARBY_INTERACT_COOLDOWN_MS,
                    "PocketRealm.NearbyInteractCooldownMs", 250, 100, 2000);
    setConfig(CONFIG_BOOL_CLEAN_CHARACTER_DB, "CleanCharacterDB", true);
"""
POCKET_SESSION_ENUM_UPSTREAM = """class SessionAnticheatInterface;

struct OpcodeHandler;
"""
POCKET_SESSION_ENUM_ANDROID = """class SessionAnticheatInterface;

enum PocketRealmInteractResult
{
    POCKET_REALM_INTERACT_OK_LOOT,
    POCKET_REALM_INTERACT_OK_USE,
    POCKET_REALM_INTERACT_NO_TARGET,
    POCKET_REALM_INTERACT_BLOCKED,
};

struct OpcodeHandler;
"""
POCKET_SESSION_API_UPSTREAM = """        void HandleMessagechatOpcode(WorldPacket& recvPacket);
        void HandleTextEmoteOpcode(WorldPacket& recvPacket);
"""
POCKET_SESSION_API_ANDROID = """        void HandleMessagechatOpcode(WorldPacket& recvPacket);
        bool HandlePocketRealmChatTrigger(std::string const& to, std::string const& message);
        PocketRealmInteractResult HandlePocketRealmNearbyInteract();
        void HandleTextEmoteOpcode(WorldPacket& recvPacket);
"""
POCKET_SESSION_FIELD_UPSTREAM = """        uint32 m_clientTimeDelay;
        uint32 m_Tutorials[8];
"""
POCKET_SESSION_FIELD_ANDROID = """        uint32 m_clientTimeDelay;
        uint32 m_lastPocketRealmInteractTime = 0;
        uint32 m_Tutorials[8];
"""
POCKET_CHAT_UPSTREAM = """        case CHAT_MSG_WHISPER:
        {
            std::string to, msg;
            recv_data >> to;
            recv_data >> msg;

            if (msg.empty())
                break;
"""
POCKET_CHAT_ANDROID = """        case CHAT_MSG_WHISPER:
        {
            std::string to, msg;
            recv_data >> to;
            recv_data >> msg;

            // The Vanilla add-on trigger arrives as an ordinary self-whisper
            // with a real language, so this must not be gated on LANG_ADDON.
            // Consuming here suppresses both the recipient CHAT_MSG_WHISPER
            // and the sender CHAT_MSG_WHISPER_INFORM echo.
            if (HandlePocketRealmChatTrigger(to, msg))
                break;

            if (msg.empty())
                break;
"""
PLAYERBOTS_OVERLAYS = [
    {
        "id": "portable-unary-not-character-query",
        "paths": [
            "playerbot/RandomPlayerbotMgr.cpp",
        ],
        "reason": "Upstream emits MySQL unary-'!' NOT in the character-selection query (' OR !' + wasRand); SQLite fails the whole query with `unrecognized token: \"!\"`, starving the need-to-increase bot-selection branch on SQLite. Replace with standard NOT (identical semantics in MySQL).",
    },
    {
        "id": "bounded-resumable-mobile-generation",
        "paths": [
            "playerbot/PlayerbotAIConfig.h",
            "playerbot/PlayerbotAIConfig.cpp",
            "playerbot/RandomPlayerbotFactory.cpp",
        ],
        "reason": "Persist each character normally, then yield after a profile-bounded batch so interrupted generation resumes from existing account/character rows.",
    },
    {
        "id": "bounded-first-player-activation",
        "paths": [
            "playerbot/PlayerbotAIConfig.h",
            "playerbot/PlayerbotAIConfig.cpp",
            "playerbot/RandomPlayerbotMgr.cpp",
        ],
        "reason": "Limit the synchronous deficit scan to a profile-bounded activation batch so the first real-player login cannot mark hundreds of bots active in one transaction.",
    },
    {
        "id": "fresh-coalesced-character-db-probe",
        "paths": [
            "playerbot/RandomPlayerbotMgr.h",
            "playerbot/RandomPlayerbotMgr.cpp",
            "playerbot/PlayerbotLoginMgr.cpp",
        ],
        "reason": "Keep at most one deadline-bounded character-database probe in flight, reject late generations, and permit new bot logins only from a fresh successful result.",
    },
    {
        "id": "low-cpu-locality-telemetry",
        "paths": [
            "playerbot/RandomPlayerbotMgr.h",
            "playerbot/RandomPlayerbotMgr.cpp",
        ],
        "reason": "Sample bot locality and operation rates once per ten seconds instead of scanning the full population on every manager pass.",
    },
    {
        "id": "in-process-llama-backend",
        "paths": [
            "playerbot/PlayerbotAIConfig.h",
            "playerbot/PlayerbotAIConfig.cpp",
            "playerbot/PlayerbotLLMInterface.h",
            "playerbot/PlayerbotLLMInterface.cpp",
            "playerbot/llm_banter_core.h",
            "playerbot/PlayerbotLlamaRuntime.h",
            "playerbot/PlayerbotLlamaRuntime.cpp",
            "playerbot/PlayerbotLlmMemory.h",
            "playerbot/PlayerbotLlmMemory.cpp",
            "playerbot/PlayerbotLlmTools.h",
            "playerbot/PlayerbotLlmTools.cpp",
            "playerbot/PlayerbotLlmToolsCore.h",
            "playerbot/PlayerbotLlmPersona.h",
            "playerbot/PlayerbotLlmPersona.cpp",
            "playerbot/PlayerbotLlmJson.h",
            "playerbot/PlayerbotLlmPrompt.h",
            "playerbot/PlayerbotLlmBridge.h",
            "playerbot/PlayerbotLlmBridge.cpp",
            "playerbot/PlayerbotLlmTruthCore.h",
            "playerbot/PlayerbotLlmRecallCore.h",
            "playerbot/PlayerbotLlmFilters.h",
            "playerbot/PlayerbotLlmFilters.cpp",
            "playerbot/PlayerbotLlmChatter.h",
            "playerbot/PlayerbotLlmChatterCore.h",
            "playerbot/PlayerbotLlmChatter.cpp",
            "playerbot/PlayerbotLlmGates.h",
            "playerbot/strategy/actions/SayAction.h",
            "playerbot/strategy/actions/SayAction.cpp",
            "playerbot/strategy/actions/RpgSubActions.cpp",
            "playerbot/strategy/actions/DebugAction.cpp",
            "playerbot/PlayerbotAI.cpp",
            "playerbot/PlayerbotAI.h",
            "aiplayerbot.conf.dist.in",
        ],
        "reason": "Link the vendored llama.cpp kai build into the world runtime behind PlayerbotLLMInterface: pinned mid-core worker, watchdog abort, per-bot warm slots, hard-trigger gate with duty-cycle governor, byte-stable memory segments (backstory/facts/relationship/gossip), world-thread tool executor, persona fallbacks, journal surface, and delayed-packet session-lifetime guards.",
    },
]
MMAP_GUARD_UPSTREAM = """    if (!MMAP::MMapFactory::createOrGetMMapManager()->IsMMapIsLoaded(m_mapId, x, y))
    {
        // load navmesh
        MMAP::MMapFactory::createOrGetMMapManager()->loadMap(sWorld.GetDataPath(), m_mapId, x, y);
    }
"""
MMAP_GUARD_ANDROID = """    auto* mmap = MMAP::MMapFactory::createOrGetMMapManager();
    if (mmap->IsEnabled() && !mmap->IsMMapIsLoaded(m_mapId, x, y))
    {
        // load navmesh only when mmap pathfinding is enabled and initialized
        mmap->loadMap(sWorld.GetDataPath(), m_mapId, x, y);
}
"""
# The trailing comment makes this anchor unique: loadAllMapTiles carries a
# byte-identical assert pair earlier in the file and replace_anchor patches the
# first match.
MMAP_LOADMAP_UPSTREAM = """        auto itr = loadedMMaps.find(mapId);
        MANGOS_ASSERT(itr != loadedMMaps.end()); // must not occur here as it would not be thread safe - only in loadMapData through loadMapInstance
"""
MMAP_LOADMAP_ANDROID = """        auto itr = loadedMMaps.find(mapId);
        if (itr == loadedMMaps.end())
        {
            sLog.outError("MMAP:loadMap: navmesh data for map %u was never registered (missing mmaps/%03u.mmap); pathfinding disabled for this map", mapId, mapId);
            return false;
        }
"""
MMAP_LOADALL_UPSTREAM = """    void MMapManager::loadAllMapTiles(std::string const& basePath, uint32 mapId)
    {
        auto itr = loadedMMaps.find(mapId);
        MANGOS_ASSERT(itr != loadedMMaps.end());
"""
MMAP_LOADALL_ANDROID = """    void MMapManager::loadAllMapTiles(std::string const& basePath, uint32 mapId)
    {
        auto itr = loadedMMaps.find(mapId);
        if (itr == loadedMMaps.end())
        {
            sLog.outError("MMAP:loadAllMapTiles: navmesh data for map %u was never registered (missing mmaps/%03u.mmap); tile preload skipped", mapId, mapId);
            return;
        }
"""
WORLD_THREAD_UPSTREAM = """    // Launch the world update thread.
    m_worldThread.reset(new MaNGOS::Thread(new WorldRunnable));
"""
WORLD_THREAD_ANDROID = """    // Re-arm process-global world-loop state before every embedded launch. A
    // prior clean stop, failed start, or service restart may leave it set.
    World::ResetForReinit();

    // Launch the world update thread.
    m_worldThread.reset(new MaNGOS::Thread(new WorldRunnable));
"""
RESULT_QUEUE_UPSTREAM = """void SqlResultQueue::Update()
{
    std::lock_guard<std::mutex> guard(m_mutex);

    /// execute the callbacks waiting in the synchronization queue
    while (!m_queue.empty())
    {
        auto const callback = std::move(m_queue.front());
        m_queue.pop();
        callback->Execute();
    }
}
"""
RESULT_QUEUE_ANDROID = """void SqlResultQueue::Update()
{
    /// Pop under the queue lock, but execute outside it. Playerbot login
    /// callbacks can issue direct statements on the async connection while the
    /// database worker is publishing another callback. Holding both locks in
    /// opposite orders deadlocks the world thread on its first update.
    while (true)
    {
        std::unique_ptr<MaNGOS::IQueryCallback> callback;
        {
            std::lock_guard<std::mutex> guard(m_mutex);
            if (m_queue.empty())
                break;
            callback = std::move(m_queue.front());
            m_queue.pop();
        }
        callback->Execute();
    }
}
"""
# Runtime-dialect rewrites. The raw TRUNCATE pair routes through the
# backend _TRUNCATE_ macro (DELETE FROM under DO_SQLITE, TRUNCATE TABLE
# under MySQL - semantically identical MySQL behavior); the anticheat
# prune uses the registered rowid-IN-subquery rewrite under DO_SQLITE
# (DELETE..ORDER BY..LIMIT would need the never-vendored UPDATE/DELETE
# LIMIT build flag), keeping the fingerprint predicate INSIDE
# the subquery so the existing two positional binds are unchanged.
OBJECTMGR_TRUNCATE_CREATURE_UPSTREAM = '''    WorldDatabase.DirectExecute("TRUNCATE creature_zone");'''
OBJECTMGR_TRUNCATE_CREATURE_ANDROID = '''    WorldDatabase.DirectExecute(_TRUNCATE_ " creature_zone");'''
OBJECTMGR_TRUNCATE_GAMEOBJECT_UPSTREAM = '''    WorldDatabase.DirectExecute("TRUNCATE gameobject_zone");'''
OBJECTMGR_TRUNCATE_GAMEOBJECT_ANDROID = '''    WorldDatabase.DirectExecute(_TRUNCATE_ " gameobject_zone");'''
ANTICHEAT_PRUNE_UPSTREAM = '''        auto prune = LoginDatabase.CreateStatement(pruneLog, "DELETE FROM system_fingerprint_usage WHERE fingerprint = ? ORDER BY `time` ASC LIMIT ?");'''
ANTICHEAT_PRUNE_ANDROID = '''#ifdef DO_SQLITE
        auto prune = LoginDatabase.CreateStatement(pruneLog,
            "DELETE FROM system_fingerprint_usage WHERE rowid IN "
            "(SELECT rowid FROM system_fingerprint_usage WHERE fingerprint = ? ORDER BY `time` ASC LIMIT ?)");
#else
        auto prune = LoginDatabase.CreateStatement(pruneLog, "DELETE FROM system_fingerprint_usage WHERE fingerprint = ? ORDER BY `time` ASC LIMIT ?");
#endif'''
PB_CONFIG_HEADER_UPSTREAM = """    bool randomBotAutoCreate;
    uint32 minRandomBots, maxRandomBots;
"""
PB_CONFIG_HEADER_ANDROID = """    bool randomBotAutoCreate;
    uint32 pocketGenerationBatchSize, pocketGenerationYieldMs, pocketActivationBatchSize;
    uint32 minRandomBots, maxRandomBots;
"""
PB_CONFIG_CPP_UPSTREAM = """    randomBotAutoCreate = config.GetBoolDefault("AiPlayerbot.RandomBotAutoCreate", true);
    minRandomBots = config.GetIntDefault("AiPlayerbot.MinRandomBots", 50);
"""
PB_CONFIG_CPP_ANDROID = """    randomBotAutoCreate = config.GetBoolDefault("AiPlayerbot.RandomBotAutoCreate", true);
    pocketGenerationBatchSize = config.GetIntDefault("PocketRealm.GenerationBatchSize", 5);
    pocketGenerationYieldMs = config.GetIntDefault("PocketRealm.GenerationYieldMs", 250);
    pocketActivationBatchSize = config.GetIntDefault("PocketRealm.ActivationBatchSize", 5);
    if (!pocketActivationBatchSize)
        pocketActivationBatchSize = 1;
    minRandomBots = config.GetIntDefault("AiPlayerbot.MinRandomBots", 50);
"""
PB_CONFIG_SOURCE_DECL_UPSTREAM = """    bool Initialize();
"""
PB_CONFIG_SOURCE_DECL_ANDROID = """    bool Initialize();
    void SetConfigSource(const std::string& source) { configSource = source; }
"""
PB_CONFIG_SOURCE_FIELD_UPSTREAM = """    Config config;
"""
PB_CONFIG_SOURCE_FIELD_ANDROID = """    Config config;
    std::string configSource = _D_AIPLAYERBOT_CONFIG;
"""
PB_CONFIG_SOURCE_USE_UPSTREAM = """    if (!config.SetSource(_D_AIPLAYERBOT_CONFIG, "PlayerBots_"))
"""
PB_CONFIG_SOURCE_USE_ANDROID = """    if (!config.SetSource(configSource, "PlayerBots_"))
"""
PB_FACTORY_INCLUDE_UPSTREAM = """#include <random>
"""
PB_FACTORY_INCLUDE_ANDROID = """#include <chrono>
#include <random>
#include <thread>
"""
PB_FACTORY_BATCH_UPSTREAM = """    uint32 botsCreated = 0;
    BarGoLink bar1(sPlayerbotAIConfig.randomBotAccountCount*
"""
PB_FACTORY_BATCH_ANDROID = """    uint32 botsCreated = 0;
    const auto checkpointYield = [&botsCreated]() {
        const uint32 batch = sPlayerbotAIConfig.pocketGenerationBatchSize;
        if (batch && botsCreated && botsCreated % batch == 0 &&
            sPlayerbotAIConfig.pocketGenerationYieldMs)
        {
            sLog.outString("POCKET_BOT_GENERATION_CHECKPOINT created=%u", botsCreated);
            std::this_thread::sleep_for(
                std::chrono::milliseconds(sPlayerbotAIConfig.pocketGenerationYieldMs));
        }
    };
    BarGoLink bar1(sPlayerbotAIConfig.randomBotAccountCount*
"""
PB_FACTORY_FIXED_UPSTREAM = """\t                created++;
\t                botsCreated++;
\t                bar1.step();
"""
PB_FACTORY_FIXED_ANDROID = """\t                created++;
\t                botsCreated++;
\t                bar1.step();
\t                checkpointYield();
"""
PB_FACTORY_RANDOM_UPSTREAM = """                    uint8 rclss = factory.GetRandomClass();
                    botsCreated++;
                    factory.CreateRandomBot(rclss);
                    bar1.step();
"""
PB_FACTORY_RANDOM_ANDROID = """                    uint8 rclss = factory.GetRandomClass();
                    if (factory.CreateRandomBot(rclss))
                    {
                        botsCreated++;
                        bar1.step();
                        checkpointYield();
                    }
"""
PB_MGR_ACTIVATION_BUDGET_UPSTREAM = """    if(sPlayerbotAIConfig.asyncBotLogin)
        return 0;"""
PB_MGR_ACTIVATION_BUDGET_ANDROID = """    if(sPlayerbotAIConfig.asyncBotLogin)
        return 0;

    // Mark only a bounded number of characters active per manager update.  The
    // upstream deficit scan can otherwise write hundreds of add/logout events
    // synchronously when the first real player enters a large mobile realm.
    uint32 pocketActivationRemaining = sPlayerbotAIConfig.pocketActivationBatchSize;"""
PB_MGR_ACTIVATION_COUNT_UPSTREAM = """                    currentAllowedBotCount--;
                    neededAddBots--;

                    if (!currentAllowedBotCount)
"""
PB_MGR_ACTIVATION_COUNT_ANDROID = """                    currentAllowedBotCount--;
                    neededAddBots--;
                    if (pocketActivationRemaining)
                        pocketActivationRemaining--;
                    if (!pocketActivationRemaining)
                        currentAllowedBotCount = 0;

                    if (!currentAllowedBotCount)
"""
PB_MGR_DB_API_UPSTREAM = """        static void DatabasePing(QueryResult* result, uint32 pingStart, std::string db);
        void SetDatabaseDelay(std::string db, uint32 delay) {databaseDelay[db] = delay;}
        uint32 GetDatabaseDelay(std::string db) {if(databaseDelay.find(db) == databaseDelay.end()) return 0; return databaseDelay[db];}
"""
PB_MGR_DB_API_ANDROID = """        static void DatabasePing(QueryResult* result, uint32 pingStart, std::string db);
        void SetDatabaseDelay(std::string db, uint32 delay) {databaseDelay[db] = delay;}
        uint32 GetDatabaseDelay(std::string db) {if(databaseDelay.find(db) == databaseDelay.end()) return 0; return databaseDelay[db];}
        bool PocketDatabaseReadyForLogin(uint32 now) const;
        bool PocketScheduleDatabaseProbe(uint32 now);
        bool PocketBeginDatabaseProbe(uint32 now, uint32& token);
        void PocketCompleteDatabaseProbe(std::string const& db, uint32 token, uint32 delay, uint32 now, bool successful);
"""
PB_MGR_DB_FIELDS_UPSTREAM = """        std::map<std::string, uint32> databaseDelay;
"""
PB_MGR_DB_FIELDS_ANDROID = """        std::map<std::string, uint32> databaseDelay;
        bool pocketDatabaseProbeInFlight = false;
        bool pocketDatabaseProbeHasResult = false;
        bool pocketDatabaseProbeHasStarted = false;
        uint32 pocketDatabaseProbeSentAt = 0;
        uint32 pocketDatabaseProbeCompletedAt = 0;
        uint32 pocketDatabaseProbeActiveToken = 0;
"""
PB_MGR_DB_LOGIN_GATE_UPSTREAM = """    if (sRandomPlayerbotMgr.GetDatabaseDelay("CharacterDatabase") < 10 * IN_MILLISECONDS && !sPlayerbotAIConfig.asyncBotLogin && onlineBotCount < maxAllowedBotCount && maxLogins > 0)
"""
PB_MGR_DB_LOGIN_GATE_ANDROID = """    const uint32 pocketDatabaseNow = sWorld.GetCurrentMSTime();
    if (sRandomPlayerbotMgr.PocketDatabaseReadyForLogin(pocketDatabaseNow) && !sPlayerbotAIConfig.asyncBotLogin && onlineBotCount < maxAllowedBotCount && maxLogins > 0)
"""
PB_MGR_DB_SCHEDULE_UPSTREAM = """    //Ping character database.
    CharacterDatabase.AsyncPQuery(&RandomPlayerbotMgr::DatabasePing, sWorld.GetCurrentMSTime(), std::string("CharacterDatabase"), "SELECT 1");
"""
PB_MGR_DB_SCHEDULE_ANDROID = """    // Keep only one probe outstanding and sample at a bounded cadence. A stale or
    // failed probe withholds new logins but never logs out an existing bot.
    sRandomPlayerbotMgr.PocketScheduleDatabaseProbe(sWorld.GetCurrentMSTime());
"""
PB_LOGIN_DB_SCHEDULE_UPSTREAM = """    CharacterDatabase.AsyncPQuery(&RandomPlayerbotMgr::DatabasePing, sWorld.GetCurrentMSTime(), std::string("CharacterDatabase"), "select 1");
"""
PB_LOGIN_DB_SCHEDULE_ANDROID = """    sRandomPlayerbotMgr.PocketScheduleDatabaseProbe(sWorld.GetCurrentMSTime());
"""
PB_MGR_DB_CALLBACK_UPSTREAM = """void RandomPlayerbotMgr::DatabasePing(QueryResult* result, uint32 pingStart, std::string db)
{
    sRandomPlayerbotMgr.SetDatabaseDelay(db, sWorld.GetCurrentMSTime() - pingStart);
    delete result;
}
"""
PB_MGR_DB_CALLBACK_ANDROID = """bool RandomPlayerbotMgr::PocketDatabaseReadyForLogin(uint32 now) const
{
    if (!pocketDatabaseProbeHasResult)
        return false;
    const auto delay = databaseDelay.find("CharacterDatabase");
    return delay != databaseDelay.end() &&
        delay->second < 10 * IN_MILLISECONDS &&
        now - pocketDatabaseProbeCompletedAt <= 15 * IN_MILLISECONDS;
}

bool RandomPlayerbotMgr::PocketBeginDatabaseProbe(uint32 now, uint32& token)
{
    if (pocketDatabaseProbeInFlight)
    {
        if (now - pocketDatabaseProbeSentAt < 15 * IN_MILLISECONDS)
            return false;
        // A result-queue or DB-worker callback may be lost across shutdown or
        // reconnect. Expire only that generation and fail the login gate shut.
        pocketDatabaseProbeInFlight = false;
        pocketDatabaseProbeActiveToken = 0;
        databaseDelay["CharacterDatabase"] = UINT32_MAX;
        pocketDatabaseProbeHasResult = true;
        pocketDatabaseProbeCompletedAt = now;
    }
    if (pocketDatabaseProbeHasStarted &&
        now - pocketDatabaseProbeSentAt < 10 * IN_MILLISECONDS)
        return false;

    // The 32-bit monotonic start time is the callback token. Unsigned
    // subtraction keeps deadline/cadence checks correct across timer wrap.
    pocketDatabaseProbeHasStarted = true;
    pocketDatabaseProbeActiveToken = now;
    pocketDatabaseProbeInFlight = true;
    pocketDatabaseProbeSentAt = now;
    token = now;
    return true;
}

bool RandomPlayerbotMgr::PocketScheduleDatabaseProbe(uint32 now)
{
    uint32 token = 0;
    if (!PocketBeginDatabaseProbe(now, token))
        return false;
    const bool queued = CharacterDatabase.AsyncPQuery(&RandomPlayerbotMgr::DatabasePing,
        token, std::string("CharacterDatabase"), "SELECT 1");
    if (!queued)
        PocketCompleteDatabaseProbe("CharacterDatabase", token, UINT32_MAX,
            sWorld.GetCurrentMSTime(), false);
    return queued;
}

void RandomPlayerbotMgr::PocketCompleteDatabaseProbe(std::string const& db, uint32 token, uint32 delay, uint32 now, bool successful)
{
    if (!pocketDatabaseProbeInFlight ||
        token != pocketDatabaseProbeActiveToken)
        return;
    databaseDelay[db] = successful ? delay : UINT32_MAX;
    pocketDatabaseProbeHasResult = true;
    pocketDatabaseProbeCompletedAt = now;
    pocketDatabaseProbeInFlight = false;
    pocketDatabaseProbeActiveToken = 0;
}

void RandomPlayerbotMgr::DatabasePing(QueryResult* result, uint32 pingStart, std::string db)
{
    const uint32 now = sWorld.GetCurrentMSTime();
    sRandomPlayerbotMgr.PocketCompleteDatabaseProbe(db, pingStart,
        now - pingStart, now, result != nullptr);
    delete result;
}
"""
PB_MGR_INCLUDE_UPSTREAM = """#include "WorldPosition.h"
#include <map>
#include <list>
"""
PB_MGR_INCLUDE_ANDROID = """#include "WorldPosition.h"
#include "PlayerbotLlmChatter.h"
#include <deque>
#include <map>
#include <list>
"""
PB_MGR_TELEMETRY_TYPE_UPSTREAM = """class PerformanceMonitorOperation;
"""
PB_MGR_TELEMETRY_TYPE_ANDROID = """class PerformanceMonitorOperation;

/** A ten-second world-thread snapshot for low-overhead Android telemetry. */
struct LowCpuBotTelemetry
{
    uint32 sampledAt = 0;
    uint32 onlineBots = 0;
    uint32 activeBots = 0;
    uint32 realPlayers = 0;
    uint32 sameActiveZone = 0;
    uint32 within150 = 0;
    uint32 within500 = 0;
    uint32 within1500 = 0;
    uint32 levelDelta2 = 0;
    uint32 levelDelta4 = 0;
    uint32 loginsLast60s = 0;
    uint32 teleportsLast60s = 0;
    uint32 rerandomizesLast60s = 0;
};
"""
PB_MGR_GETTER_UPSTREAM = """        uint32 GetPlayersLevel() { return playersLevel; }
"""
PB_MGR_GETTER_ANDROID = """        uint32 GetPlayersLevel() { return playersLevel; }
        LowCpuBotTelemetry GetLowCpuTelemetry() const { return lowCpuTelemetry; }
"""
PB_MGR_FIELDS_UPSTREAM = (
    "        uint32 botCount = 0;\n"
    "        uint32 activeBots = 0;" + "        \n"
)
PB_MGR_FIELDS_ANDROID = """        uint32 botCount = 0;
        uint32 activeBots = 0;
        time_t lowCpuTelemetryTimer = 0;
        LowCpuBotTelemetry lowCpuTelemetry;
        std::deque<time_t> lowCpuLoginEvents;
        std::deque<time_t> lowCpuTeleportEvents;
        std::deque<time_t> lowCpuRerandomizeEvents;
"""
PB_MGR_SCAN_UPSTREAM = """void RandomPlayerbotMgr::LogPlayerLocation()
{
    botCount = 0;
    activeBots = 0;
    if (sPlayerbotAIConfig.randomBotAutologin)
    {
        ForEachPlayerbot([&](Player* bot) {
            if (bot->GetPlayerbotAI())
            {

                botCount++;
                if (bot->GetPlayerbotAI()->AllowActivity(ALL_ACTIVITY))
                {
                    activeBots++;
                }
            }
        });
    }

    for (auto i : GetPlayers())
    {
        Player* bot = i.second;
        if (!bot)
            continue;
        if (bot->GetPlayerbotAI())
        {
            botCount++;
            if (bot->GetPlayerbotAI()->AllowActivity(ALL_ACTIVITY))
                activeBots++;
        }
    }
"""
PB_MGR_SCAN_ANDROID = """void RandomPlayerbotMgr::LogPlayerLocation()
{
    LowCpuBotTelemetry snapshot;
    snapshot.sampledAt = static_cast<uint32>(time(nullptr));

    std::vector<Player*> realPlayers;
    realPlayers.reserve(players.size());
    for (auto const& entry : players)
    {
        Player* player = entry.second;
        if (player && player->IsInWorld() && !player->IsGameMaster())
            realPlayers.push_back(player);
    }
    snapshot.realPlayers = static_cast<uint32>(realPlayers.size());

    const auto includeBot = [&snapshot, &realPlayers](Player* bot)
    {
        if (!bot || !bot->IsInWorld() || !bot->GetPlayerbotAI())
            return;

        ++snapshot.onlineBots;
        if (bot->GetPlayerbotAI()->AllowActivity(ALL_ACTIVITY))
            ++snapshot.activeBots;

        bool sameZone = false;
        bool near150 = false;
        bool near500 = false;
        bool near1500 = false;
        bool delta2 = false;
        bool delta4 = false;
        for (Player* player : realPlayers)
        {
            const uint32 botLevel = bot->GetLevel();
            const uint32 playerLevel = player->GetLevel();
            const uint32 levelDelta = botLevel > playerLevel ? botLevel - playerLevel : playerLevel - botLevel;
            delta2 = delta2 || levelDelta <= 2;
            delta4 = delta4 || levelDelta <= 4;
            if (bot->GetMapId() != player->GetMapId())
                continue;
            sameZone = sameZone || bot->GetZoneId() == player->GetZoneId();
            const float distance = sServerFacade.GetDistance2d(bot, player);
            near150 = near150 || distance <= 150.0f;
            near500 = near500 || distance <= 500.0f;
            near1500 = near1500 || distance <= 1500.0f;
        }
        if (sameZone) ++snapshot.sameActiveZone;
        if (near150) ++snapshot.within150;
        if (near500) ++snapshot.within500;
        if (near1500) ++snapshot.within1500;
        if (delta2) ++snapshot.levelDelta2;
        if (delta4) ++snapshot.levelDelta4;
    };

    if (sPlayerbotAIConfig.randomBotAutologin)
        ForEachPlayerbot(includeBot);

    for (auto const& entry : GetPlayers())
    {
        Player* bot = entry.second;
        if (bot && bot->GetPlayerbotAI())
            includeBot(bot);
    }

    const time_t cutoff = static_cast<time_t>(snapshot.sampledAt) - 60;
    const auto prune = [cutoff](std::deque<time_t>& events)
    {
        while (!events.empty() && events.front() < cutoff)
            events.pop_front();
    };
    prune(lowCpuLoginEvents);
    prune(lowCpuTeleportEvents);
    prune(lowCpuRerandomizeEvents);
    snapshot.loginsLast60s = static_cast<uint32>(lowCpuLoginEvents.size());
    snapshot.teleportsLast60s = static_cast<uint32>(lowCpuTeleportEvents.size());
    snapshot.rerandomizesLast60s = static_cast<uint32>(lowCpuRerandomizeEvents.size());
    lowCpuTelemetry = snapshot;
    botCount = snapshot.onlineBots;
    activeBots = snapshot.activeBots;
"""
PB_MGR_SCAN_CALL_UPSTREAM = """    LogPlayerLocation();
"""
PB_MGR_SCAN_CALL_ANDROID = """    // Match the core active-zone cadence; do not scan all bots every pass.
    const time_t now = time(nullptr);
    if (!lowCpuTelemetryTimer || now >= lowCpuTelemetryTimer + 10)
    {
        lowCpuTelemetryTimer = now;
        LogPlayerLocation();
        // The world-chatter scheduler rides the same 10 s world-
        // thread cadence (power reconcile + queue drain + batch rolls;
        // every gate lives inside - a no-op when chatter is off)
        PlayerbotLlmChatter::Tick();
    }
"""
PB_MGR_TELEPORT_UPSTREAM = """            bot->TeleportTo(loc.mapid, x, y, z, 0);
            bot->SendHeartBeat();
"""
PB_MGR_TELEPORT_ANDROID = """            bot->TeleportTo(loc.mapid, x, y, z, 0);
            lowCpuTeleportEvents.push_back(time(nullptr));
            bot->SendHeartBeat();
"""
PB_MGR_RANDOMIZE_UPSTREAM = """    PlayerbotFactory factory(bot, level);
    factory.Randomize(false, false);
"""
PB_MGR_RANDOMIZE_ANDROID = """    PlayerbotFactory factory(bot, level);
    factory.Randomize(false, false);
    lowCpuRerandomizeEvents.push_back(time(nullptr));
"""
# Standard-SQL NOT: upstream builds the character-selection query with
# MySQL's unary '!' (" OR !" + wasRand); SQLite rejects the token outright
# (device evidence 2026-08-27: `unrecognized token: "!"`), so the
# need-to-increase selection branch returns no rows on SQLite. "NOT" is
# standard and semantically identical in MySQL.
PB_MGR_QUERY_NOT_UPSTREAM = '                            query += " OR !" + wasRand;'
PB_MGR_QUERY_NOT_ANDROID = '                            query += " OR NOT " + wasRand;'
PB_MGR_LOGIN_UPSTREAM = """void RandomPlayerbotMgr::OnBotLoginInternal(Player * const bot)
{
    sLog.outDetail("%u/%d Bot %s logged in", GetPlayerbotsAmount(), sRandomPlayerbotMgr.GetMaxAllowedBotCount(), bot->GetName());
"""

# --- LLM in-process backend overlays ---------------------------------------
PB_LLM_CONFIG_HEADER_UPSTREAM = """    ParsedUrl llmEndPointUrl;
    std::set<uint32> llmBlockedReplyChannels;
"""
PB_LLM_CONFIG_HEADER_ANDROID = """    ParsedUrl llmEndPointUrl;
    std::set<uint32> llmBlockedReplyChannels;

    // in-process llama.cpp backend (arm64-v8a only; compiled out elsewhere)
    enum { LLM_BACKEND_HTTP = 0, LLM_BACKEND_LLAMA = 1 };
    uint32 llmBackend, llmThreads, llmCpuFirstCore, llmCtxSize, llmSlots, llmTopK, llmMaxNewTokens;
    uint32 llmGovernorWindow, llmGovernorBotMax, llmGovernorGlobalMax, llmToolsEnabled;
    uint32 llmBanterEnabled;
    // A4/A5 trained prompt format: 1 = the native messages builder speaks
    // the trained contract (PlayerbotLlmPrompt.h); 0 = legacy conf template
    uint32 llmPromptFormat, llmThinkingKwargs, llmFactsCap, llmMemoriesTail, llmApiProviderSafe;
    float llmMinP, llmPresencePenalty;
    std::string llmModelPath, llmBusyReply;
    std::string llmApiModel, llmPromptDumpFile;
    // S7/A11: lore card index file (empty = the retrieval loop is off)
    // and the era logit-bias switch (resolved via /tokenize, fail-open)
    std::string llmLoreFile;
    uint32 llmEraBias;
    float llmTemp, llmTopP, llmRepeatPenalty;
    // S9/T4: bounded TCP connect for the HTTP client (the blocking default
    // hangs for minutes on a dead external endpoint; the cap is 10 s)
    uint32 llmConnectTimeout;
    // World chatter: the master ambience switch (default 0
    // - silence is the default state), the app-refreshed power file, and
    // the cloud composer endpoint (parsed once like the main endpoint)
    uint32 llmChatterEnabled;
    std::string llmChatterPowerFile;
    std::string llmChatterComposerUrl, llmChatterComposerModel, llmChatterComposerKey;
    ParsedUrl llmChatterComposerUrlParsed;
    // Phase 1 prompt pack: staged JSON of ordered blocks (empty = trained
    // default, byte-identical). The renderer appends only enabled seasoning
    // blocks inside the existing instruction span - never a new top-level
    // segment, so trained weights see familiar shape.
    std::string llmPromptPackFile;
    // Phase 2 per-preset RP layer: explicit block switches (preset >
    // global pack > trained default) + RP dial weights (Phase 3 consumes
    // them; parsed + stored here so the conf never fails on unknown keys).
    std::map<std::string, int> llmPromptBlockOverride;
    uint32 llmRpInitiative, llmRpVolatility, llmRpReactivity, llmRpLongForm;
    // plan v5 W1/W4/F7: event reactions + the grudge act-refusal toggles,
    // and the global authored-line hourly ceiling (0 = authored ambient
    // off entirely; the guaranteed first beats stay exempt). W7a: the
    // weather/hour ambient-bias toggle (default ON; 0 restores the
    // unbiased table - pure sampling weights, no prompt bytes either way)
    uint32 llmEventReactionsEnabled, llmGrudgeRefusalEnabled, llmAuthoredLinesPerHour;
    uint32 llmWorldTruthAmbient;
    // plan v5 C2: the session recap (deterministic digest always; the
    // prose variant is one quota-capped cloud call per world start)
    uint32 llmRecapEnabled, llmRecapProse, llmRecapProsePerDay;
    // plan v5 W8/W7b: the /notice scene read (default ON - it is a
    // player-initiated zero-cost read) and the scene/homeland prompt
    // furniture (default OFF pending the bake-off; rides the bridge
    // extra leg, never the trained [State] fill)
    uint32 llmSceneReadEnabled, llmWorldTruthFurniture;
    // plan v5 C1: the campfire saga (cloud tier only, quota-capped per
    // roster per day; the first safe line becomes a town gossip row);
    // C3 roundtable rows + C5 weekly dossier share the quota meter
    uint32 llmSagaEnabled, llmSagaPerDay;
    uint32 llmRoundtablePerDay, llmDossierEnabled, llmDossierPerDay;
    // C4 drama set-piece switch + the W5 curiosity switch (plan 5.4 keys)
    uint32 llmDramaEnabled, llmCuriosityEnabled;
    // G3 TLS lane: peer-verification switch (default ON; 0 restores the
    // pre-G3 unverified handshake for self-signed LAN endpoints) and the
    // app-staged CA bundle path (empty = the Android system store
    // fallback; bare SSL_VERIFY_PEER without any store fails every
    // handshake on Android - no /etc/ssl/certs exists for native code)
    uint32 llmTlsVerify;
    std::string llmTlsCaFile;
    // WS-A cloud lane (plan v2.3 A0.a/A1/A7): the master cloud-chatter
    // toggle consumed as the conjunction CloudLaneOpen() =
    // llmCloudChatter && ExternalApiTierActive() (never the bare key -
    // a device-lane leak of the widenings is a hard stop), the party
    // unaddressed-reply arm (default 0 until the T3 party step is
    // green), the street reaction share + the three per-UTC-day
    // process-local generation quotas, the two-tier budgets (ambient
    // realm-global per-hour + interactive per-player per-hour), and the
    // A2 dialogue fast-lane arming switch
    uint32 llmCloudChatter, llmPartyReplyEnabled;
    uint32 llmCloudStreetSayPct, llmStreetSayPerDay, llmRpgChatPerDay, llmBotToBotPerDay;
    uint32 llmCloudLineBudgetPerHour, llmCloudInteractivePerPlayerHour;
    uint32 llmDialogueFastLane;
"""
PB_LLM_CONFIG_CPP_UPSTREAM = """    //LLM START
    llmEnabled = config.GetIntDefault("AiPlayerbot.LLMEnabled", 1);
    llmApiEndpoint = config.GetStringDefault("AiPlayerbot.LLMApiEndpoint", "http://127.0.0.1:5001/api/v1/generate");
"""
PB_LLM_CONFIG_CPP_ANDROID = """    //LLM START
    llmEnabled = config.GetIntDefault("AiPlayerbot.LLMEnabled", 1);
    llmBackend = config.GetIntDefault("AiPlayerbot.LLMBackend", 0); // 0 = http, 1 = in-process llama
    llmModelPath = config.GetStringDefault("AiPlayerbot.LLMModelPath", "");
    llmThreads = config.GetIntDefault("AiPlayerbot.LLMThreads", 3);
    llmCpuFirstCore = config.GetIntDefault("AiPlayerbot.LLMCpuFirstCore", 3);
    llmCtxSize = config.GetIntDefault("AiPlayerbot.LLMCtxSize", 4096);
    llmSlots = config.GetIntDefault("AiPlayerbot.LLMSlots", 4);
    llmTopK = config.GetIntDefault("AiPlayerbot.LLMTopK", 64);
    llmMaxNewTokens = config.GetIntDefault("AiPlayerbot.LLMMaxNewTokens", 200);
    llmTemp = config.GetFloatDefault("AiPlayerbot.LLMTemp", 0.8f);
    llmTopP = config.GetFloatDefault("AiPlayerbot.LLMTopP", 0.95f);
    llmRepeatPenalty = config.GetFloatDefault("AiPlayerbot.LLMRepeatPenalty", 1.1f);
    llmGovernorWindow = config.GetIntDefault("AiPlayerbot.LLMGovernorWindow", 60);
    llmGovernorBotMax = config.GetIntDefault("AiPlayerbot.LLMGovernorBotMax", 8);
    llmGovernorGlobalMax = config.GetIntDefault("AiPlayerbot.LLMGovernorGlobalMax", 24);
    llmToolsEnabled = config.GetIntDefault("AiPlayerbot.LLMToolsEnabled", 1);
    // A4/A5: the trained prompt format on the HTTP path - 1 = the native
    // messages builder (PlayerbotLlmPrompt.h, byte-diffed against banklib
    // by the host battery); 0 = the legacy conf-template fill. The request
    // model name and the sampling fields below feed the native builder.
    llmPromptFormat = config.GetIntDefault("AiPlayerbot.LLMPromptFormat", 0);
    llmApiModel = config.GetStringDefault("AiPlayerbot.LLMApiModel", "local");
    llmMinP = config.GetFloatDefault("AiPlayerbot.LLMMinP", 0.0f);
    llmPresencePenalty = config.GetFloatDefault("AiPlayerbot.LLMPresencePenalty", 0.0f);
    // SS4.4: emit chat_template_kwargs {"enable_thinking": false} for model
    // families whose export template defaults to thinking (the qwen family;
    // the app sets this from the registry descriptor flag)
    llmThinkingKwargs = config.GetIntDefault("AiPlayerbot.LLMThinkingKwargs", 0);
    // SS2.1 memory depth: system-segment facts cap + [Memories] tail size
    llmFactsCap = config.GetIntDefault("AiPlayerbot.LLMFactsCap", 12);
    llmMemoriesTail = config.GetIntDefault("AiPlayerbot.LLMMemoriesTail", 6);
    // 1 for external OpenAI-compatible endpoints that reject unknown body
    // keys (strips top_k/repeat_penalty/min_p/presence_penalty from the
    // native request body)
    llmApiProviderSafe = config.GetIntDefault("AiPlayerbot.LLMProviderSafe", 0);
    // M1a prompt-dump hook: one JSON line per trained-format generation
    // (device-side verification of the byte-diff contract)
    llmPromptDumpFile = config.GetStringDefault("AiPlayerbot.LLMPromptDumpFile", "");
    // S7/A11: the lore retrieval loop's card file (empty disables the
    // loop; the app stages the asset and emits the absolute path) and
    // the era always-ban logit bias (1 = resolve token ids via the
    // embedded server's /tokenize and bias them; fails open)
    llmLoreFile = config.GetStringDefault("AiPlayerbot.LLMLoreFile", "");
    llmEraBias = config.GetIntDefault("AiPlayerbot.LLMEraBias", 1);
    llmBusyReply = config.GetStringDefault("AiPlayerbot.LLMBusyReply", "Hm. I will be thinking on that a while.");
    // authored banter layer (kill quips, tier greetings, idle/mood lines);
    // free - no generation behind any of it
    llmBanterEnabled = config.GetIntDefault("AiPlayerbot.LLMBanterEnabled", 1);
    llmApiEndpoint = config.GetStringDefault("AiPlayerbot.LLMApiEndpoint", "http://127.0.0.1:5001/api/v1/generate");
    // BuildPromptContext budgets its segments against this CHARACTER window,
    // but the real llama constraint is per-slot TOKENS (LLMCtxSize, ~3.5
    // chars each). The legacy 4096-char default would routinely squeeze the
    // rolling history to a fraction of its 3000-char cap while the slot sits
    // mostly empty; 12288 keeps the worst-case composition under the window
    // with full richness (Phase 1 plan-v4 bump from 8192: prompt-pack
    // seasoning headroom). The upstream tree re-reads the key later with the
    // 4096 default (which would overwrite this); PB_LLM_CTX_REREAD removes
    // that legacy re-read so this is the single authoritative read.
    llmContextLength = config.GetIntDefault("AiPlayerbot.LLMContextLength", 12288);
    // The world-chatter layer. Enabled defaults 0 - the
    // silence doctrine; the app emits 1 whenever it stages the power
    // file, and the FILE's enabled flag is the master switch (re-read
    // every scheduler tick; the app stages it at world start and on
    // battery events, so the ambience toggle applies at the next realm
    // start while battery dims land mid-session; missing/disabled =
    // chatter stops - see PlayerbotLlmChatter.cpp). The composer
    // endpoint is a CLOUD-class script generator; empty = the NORMAL
    // rung degrades to device single-line batches. parseUrl throws on
    // non-URL text, so the (default-empty) composer URL is parsed under
    // the same guard the main endpoint uses.
    llmChatterEnabled = config.GetIntDefault("AiPlayerbot.LLMChatterEnabled", 0);
    llmChatterPowerFile = config.GetStringDefault("AiPlayerbot.LLMChatterPowerFile", "");
    llmChatterComposerUrl = config.GetStringDefault("AiPlayerbot.LLMChatterComposerUrl", "");
    // parseUrl throws on anything non-URL-shaped - and the DEFAULT is the
    // empty string (composer unconfigured), so the parse must be guarded
    // exactly like the main endpoint below or every world boot without a
    // composer row aborts init (round-1 R6 P0). std::exception (not just
    // invalid_argument): parseUrl's stoi throws out_of_range on a huge
    // port too (round-2 R1).
    if (!llmChatterComposerUrl.empty())
        try {
            llmChatterComposerUrlParsed = parseUrl(llmChatterComposerUrl);
        }
        catch (const std::exception& e) {
            sLog.outError("Unable to parse LLMChatterComposerUrl: %s", e.what());
        }
    llmChatterComposerModel = config.GetStringDefault("AiPlayerbot.LLMChatterComposerModel", "local");
    llmChatterComposerKey = config.GetStringDefault("AiPlayerbot.LLMChatterComposerKey", "");
    // Phase 1 prompt pack path (empty = trained default; fails open - the
    // renderer treats a missing/unreadable file as "pack off").
    llmPromptPackFile = config.GetStringDefault("AiPlayerbot.LLMPromptPackFile", "");
    // Phase 2 per-preset RP layer (sentinel 50 = follow the global pack;
    // block keys default absent = follow the file). GetIntDefault keeps
    // hand-edited confs fail-open; unknown block ids are stored verbatim
    // and ignored by the renderer (same discipline as the pack parser).
    llmRpInitiative = (uint32)config.GetIntDefault("AiPlayerbot.LLMRpInitiative", 50);
    llmRpVolatility = (uint32)config.GetIntDefault("AiPlayerbot.LLMRpVolatility", 50);
    llmRpReactivity = (uint32)config.GetIntDefault("AiPlayerbot.LLMRpReactivity", 50);
    llmRpLongForm = (uint32)config.GetIntDefault("AiPlayerbot.LLMRpLongForm", 50);
    // plan v5 W1/W4/F7: event reactions (death condolence/wipe aftermath/
    // debt settlement) and the grudge act-refusal both default ON (they
    // are zero-generation authored beats); the authored-line hourly
    // ceiling defaults to the engagement-reviewed 8 (0 disables authored
    // ambient entirely - exempt beats like the first post-wipe line
    // still land)
    llmEventReactionsEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMEventReactionsEnabled", 1);
    llmGrudgeRefusalEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMGrudgeRefusalEnabled", 1);
    llmAuthoredLinesPerHour = (uint32)config.GetIntDefault("AiPlayerbot.LLMAuthoredLinesPerHour", 8);
    // W7a default-ON per the engagement review: weather/hour bias the
    // ambient table's sampling weights only
    llmWorldTruthAmbient = (uint32)config.GetIntDefault("AiPlayerbot.LLMWorldTruthAmbient", 1);
    // C2 recap: digest default ON (zero calls); prose replaces it on the
    // external tier, quota-capped per day
    llmRecapEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMRecapEnabled", 1);
    llmRecapProse = (uint32)config.GetIntDefault("AiPlayerbot.LLMRecapProse", 1);
    llmRecapProsePerDay = (uint32)config.GetIntDefault("AiPlayerbot.LLMRecapProsePerDay", 6);
    // W8/W7b: scene read default ON (player-initiated, zero cost);
    // scene furniture default OFF until the bake-off promotes it
    llmSceneReadEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMSceneReadEnabled", 1);
    llmWorldTruthFurniture = (uint32)config.GetIntDefault("AiPlayerbot.LLMWorldTruthFurniture", 0);
    // C1 saga: enabled by default but fires only on the external tier
    llmSagaEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMSagaEnabled", 1);
    llmSagaPerDay = (uint32)config.GetIntDefault("AiPlayerbot.LLMSagaPerDay", 3);
    // C3/C5: the roundtable row quota + the weekly dossier toggles
    llmRoundtablePerDay = (uint32)config.GetIntDefault("AiPlayerbot.LLMRoundtablePerDay", 30);
    llmDossierEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMDossierEnabled", 1);
    llmDossierPerDay = (uint32)config.GetIntDefault("AiPlayerbot.LLMDossierPerDay", 1);
    llmDramaEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMDramaEnabled", 1);
    llmCuriosityEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMCuriosityEnabled", 1);
    // G3: TLS verification for the external HTTPS endpoint. Default 1
    // (verify + TLS 1.2 floor + hostname pin); 0 is the documented
    // kill-switch restoring the unverified handshake for self-signed
    // LAN endpoints. The CA file is the app-staged Mozilla bundle; an
    // empty value falls back to the system hashed-dir store.
    llmTlsVerify = (uint32)config.GetIntDefault("AiPlayerbot.LLMTLSVerify", 1);
    llmTlsCaFile = config.GetStringDefault("AiPlayerbot.LLMTLSCaFile", "");
    // A0.a: the WS-A cloud-lane keys. Defaults are the plan's 0.a rows;
    // 0 disables the behavior named by the row (the repo's opt-out
    // convention). Quotas are per-UTC-day, realm-global, process-local
    // (they reset on realm restart - documented in the toggle copy).
    llmCloudChatter = (uint32)config.GetIntDefault("AiPlayerbot.LLMCloudChatter", 1);
    llmPartyReplyEnabled = (uint32)config.GetIntDefault("AiPlayerbot.LLMPartyReplyEnabled", 0);
    llmCloudStreetSayPct = (uint32)config.GetIntDefault("AiPlayerbot.LLMCloudStreetSayPct", 25);
    llmStreetSayPerDay = (uint32)config.GetIntDefault("AiPlayerbot.LLMStreetSayPerDay", 200);
    llmRpgChatPerDay = (uint32)config.GetIntDefault("AiPlayerbot.LLMRpgChatPerDay", 300);
    llmBotToBotPerDay = (uint32)config.GetIntDefault("AiPlayerbot.LLMBotToBotPerDay", 300);
    llmCloudLineBudgetPerHour = (uint32)config.GetIntDefault("AiPlayerbot.LLMCloudLineBudgetPerHour", 90);
    llmCloudInteractivePerPlayerHour = (uint32)config.GetIntDefault("AiPlayerbot.LLMCloudInteractivePerPlayerHour", 240);
    llmDialogueFastLane = (uint32)config.GetIntDefault("AiPlayerbot.LLMDialogueFastLane", 1);
    {
        static char const* const kBlocks[] = {
            "voice-lock", "rule-autonomy", "rule-anti-omniscient",
            "rule-boldness", "rule-salience", "ban-list", "scene-close",
            "initiative-opener", "mood-weather", "player-persona",
        };
        for (size_t i = 0; i < sizeof(kBlocks) / sizeof(kBlocks[0]); ++i)
        {
            std::string key = std::string("AiPlayerbot.LLMPromptBlock.") + kBlocks[i];
            int v = config.GetIntDefault(key.c_str(), -1);
            if (v == 0 || v == 1)
                llmPromptBlockOverride[kBlocks[i]] = v;
        }
    }
"""
# The pristine tree reads AiPlayerbot.LLMContextLength a second time ~10 lines
# after the block above, with the legacy 4096 default — last assignment wins,
# so without this overlay the 12288 default above would be silently overwritten
# on every driver rebuild.
PB_LLM_CTX_REREAD_UPSTREAM = """    llmApiJson = config.GetStringDefault("AiPlayerbot.LLMApiJson", "{ \\"max_length\\": 100, \\"prompt\\": \\"[<pre prompt>]<context> <prompt> <post prompt>\\"}");
    llmContextLength = config.GetIntDefault("AiPlayerbot.LLMContextLength", 4096);
"""
PB_LLM_CTX_REREAD_ANDROID = """    llmApiJson = config.GetStringDefault("AiPlayerbot.LLMApiJson", "{ \\"max_length\\": 100, \\"prompt\\": \\"[<pre prompt>]<context> <prompt> <post prompt>\\"}");
    // (the earlier LLMContextLength read above already applied the key with
    // the intended 12288 default; this legacy re-read used to overwrite it)
"""
PB_LLM_IFACE_HEADER_UPSTREAM = """#include <atomic>
#include <string>
#include <vector>

class PlayerbotLLMInterface
{
public:
    PlayerbotLLMInterface() {}
    static std::string SanitizeForJson(const std::string& input);

    static std::string Generate(const std::string& prompt, int timeOutSeconds, int maxGenerations, std::vector<std::string>& debugLines);
"""
PB_LLM_IFACE_HEADER_ANDROID = """#include <atomic>
#include <string>
#include <vector>

#include "PlayerbotLlamaRuntime.h"

class PlayerbotLLMInterface
{
public:
    PlayerbotLLMInterface() {}
    static std::string SanitizeForJson(const std::string& input);

    // routes to the in-process llama runtime when AiPlayerbot.LLMBackend = 1,
    // otherwise to the HTTP endpoint. botGuid/source identify the caller for
    // per-bot warm slots, the duty-cycle governor (hoisted above the backend
    // branch, A0) and tool-queue tagging; speakerGuid is the real player
    // whose turn triggered the generation (0 on autonomous turns) so queued
    // persistence tools attribute to the interlocutor, never the owner.
    static std::string Generate(const std::string& prompt, uint32 botGuid, uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source, uint64_t licenseStamp, int timeOutSeconds, int maxGenerations, std::vector<std::string>& debugLines, uint64_t reqId = 0);

    // Ambient admission surfaces, shared with PlayerbotLlmChatter:
    // GovernorAdmit is the SAME duty-cycle check+consume Generate runs
    // (hoisted into a shared function so the ambient path cannot bypass
    // the governor); PostChatHttp is the raw chat-completions POST for
    // paths that own their validation (the murmur/composer workers -
    // never the canned-deflection voice-filter chain); the in-flight
    // count lets ambient work yield the interactive lane entirely.
    static bool GovernorAdmit(uint32 botGuid);
    static std::string PostChatHttp(const std::string& body, int timeOutSeconds, ParsedUrl const* endpointOverride, std::string const* apiKeyOverride);
    static bool InteractiveGenerationInFlight();
"""
PB_LLM_IFACE_PRIVATE_UPSTREAM = """    static void LimitContext(std::string& context, int currentLength);
private:
"""
PB_LLM_IFACE_PRIVATE_ANDROID = """    static void LimitContext(std::string& context, int currentLength);
private:
    // S10: endpoint/key overrides let the cloud-composer path POST to its
    // own endpoint through the same hardened client (null = the conf
    // endpoint, exactly the pre-S10 behavior)
    static std::string GenerateHttp(const std::string& prompt, int timeOutSeconds, int maxGenerations, std::vector<std::string>& debugLines, ParsedUrl const* endpointOverride = nullptr, std::string const* apiKeyOverride = nullptr, uint64_t reqId = 0);
    // S7/A12: the voice-filter chain (leak/era regeneration, hygiene,
    // dedupe reroll) - private static so it can drive GenerateHttp
    static std::string PocketLlmVoiceFilter(const std::string& raw, uint32 botGuid, uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source, uint64_t licenseStamp, const std::string& body, int timeOutSeconds, int maxGenerations, std::vector<std::string>& debugLines, int& generationState, bool firstTruncated);
"""
PB_LLM_IFACE_CPP_UPSTREAM = """std::string PlayerbotLLMInterface::Generate(const std::string& prompt, int timeOutSeconds, int maxGenerations, std::vector<std::string> & debugLines) {
    bool debug = !debugLines.empty();
"""
PB_LLM_IFACE_CPP_ANDROID = """namespace
{
    // A9: per-generation state set by the HTTP JSON-client leg and consumed
    // (read-and-clear) by ParseResponse on the same async worker thread:
    // TRUNCATED - the envelope's finish_reason was "length", so the
    // dangling partial sentence a truncated generation ends with is
    // trimmed before line splitting; JSON_DECODED - the reply arrived as
    // envelope content decoded with full JSON string semantics, so the
    // JSON-era residue transforms in ParseResponse (the escaped-quote
    // rewrite and the backslash-eating delete pattern) must be skipped.
    // Reset at the top of every Generate so a later llama-path parse never
    // sees a stale flag.
    enum PocketLlmGenerationFlags
    {
        POCKET_LLM_GEN_TRUNCATED = 1,
        POCKET_LLM_GEN_JSON_DECODED = 2
    };
    thread_local int pocketLlmGenerationState = 0;

}

// A12 voice-filter chain for one HTTP generation: the leak/era class
// is checked on the RAW content BEFORE any tool extraction (a
// rejected reply must not leave queued calls behind), regenerates at
// most twice with the same body (fresh sampling), then falls back to
// a canned in-character deflection. The accepted reply alone is
// extracted + hygiene-passed; marker terms are checked on the
// CLEANED text (the << >> markers are legal in raw tool replies).
// The dedupe reroll resamples ONCE with a hidden do-not-repeat tail
// note (content-mandating notes are exempt from it, rate-capped - the
// bridge's NoteMandatesContent claim). The
// generation-state bits reflect the envelope that was ACCEPTED.
// (Private static member: it drives GenerateHttp.)
std::string PlayerbotLLMInterface::PocketLlmVoiceFilter(std::string const& raw, uint32 botGuid,
    uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source,
    uint64_t licenseStamp, std::string const& body, int timeOutSeconds,
    int maxGenerations, std::vector<std::string>& debugLines,
    int& generationState, bool firstTruncated)
{
    // EXACTLY ONE reply is ever extracted+queued: the finally-chosen
    // content (round-1 R1/R5/R6 - the old form queued the original
    // before the marker check and BOTH draws on the dedupe reroll).
    // Pre-extraction checks use the PURE scanner (ExtractToolCalls
    // returns cleaned text without queueing).
    std::string content = raw;
    bool truncated = firstTruncated;
    for (int attempt = 0; attempt < 2 &&
         PlayerbotLlmFilters::LeakFailureRaw(content, body); ++attempt)
    {
        if (!debugLines.empty())
            debugLines.push_back("leak/era filter hit - regenerating");
        pocketllm::CompletionEnvelope retry = pocketllm::ParseCompletionEnvelope(
            PlayerbotLLMInterface::GenerateHttp(body, timeOutSeconds, maxGenerations, debugLines));
        if (!retry.parsed || !pocketllm::ContentUsable(retry))
            break;
        content = retry.content;
        truncated = retry.finishLength != 0;
    }
    if (PlayerbotLlmFilters::LeakFailureRaw(content, body))
    {
        if (!debugLines.empty())
            debugLines.push_back("leak/era filter exhausted - canned deflection");
        std::string const deflection = PlayerbotLlmFilters::Deflection(botGuid);
        PlayerbotLlmFilters::RememberReply(botGuid, deflection);
        generationState = POCKET_LLM_GEN_JSON_DECODED;
        return deflection;
    }
    // marker preview on the CLEANED text (the << >> markers are legal in
    // the raw tool-bearing reply) - checked BEFORE anything queues; the
    // same preview feeds the dedupe check (tool-line words would dilute
    // the Jaccard against the ring's voiced entries)
    std::string cleanedPreview;
    pocketllm::ExtractToolCalls(content, &cleanedPreview);
    if (pocketllm::ContainsMarkerTerms(cleanedPreview))
    {
        if (!debugLines.empty())
            debugLines.push_back("marker terms survived extraction - canned deflection");
        std::string const deflection = PlayerbotLlmFilters::Deflection(botGuid);
        PlayerbotLlmFilters::RememberReply(botGuid, deflection);
        generationState = POCKET_LLM_GEN_JSON_DECODED;
        return deflection;
    }
    // dedupe reroll happens PRE-EXTRACTION: only the winner is
    // extracted below (one queue per turn, by construction).
    // S8/A13 beat-content exemption: a note that MANDATED content
    // (recall cargo, ceremony, event news) is SUPPOSED to produce a
    // reply resembling its mandated words - "you still owe me five
    // silver" wants to sound like the last mention. Stamp-checked AND
    // rate-capped (one exempted generation per bot per minute - a
    // same-cargo spam turn falls back to the reroll, which rephrases
    // while keeping the cargo).
    if (!PlayerbotLlmBridge::NoteMandatesContent(botGuid, licenseStamp) &&
        PlayerbotLlmFilters::DuplicateOfRecent(botGuid, cleanedPreview))
    {
        std::string retryBody = body;
        if (pocketllm::AppendInstructionToLastUserMessage(retryBody,
                " Do not repeat your last reply; say something new."))
        {
            if (!debugLines.empty())
                debugLines.push_back("duplicate reply - one do-not-repeat resample");
            pocketllm::CompletionEnvelope retry = pocketllm::ParseCompletionEnvelope(
                PlayerbotLLMInterface::GenerateHttp(retryBody, timeOutSeconds, maxGenerations, debugLines));
            std::string cleanedRetry;
            bool usable = retry.parsed && pocketllm::ContentUsable(retry) &&
                !PlayerbotLlmFilters::LeakFailureRaw(retry.content, retryBody);
            if (usable)
                pocketllm::ExtractToolCalls(retry.content, &cleanedRetry);
            if (usable && !pocketllm::ContainsMarkerTerms(cleanedRetry))
            {
                content = retry.content; // still a dupe: accepted (one resample, by law)
                truncated = retry.finishLength != 0;
            }
        }
    }
    generationState = POCKET_LLM_GEN_JSON_DECODED |
        (truncated ? POCKET_LLM_GEN_TRUNCATED : 0);
    // THE single extraction: the finally-chosen reply alone queues
    std::string voiced = PlayerbotLlmFilters::HygienePass(
        PlayerbotLlmTools::ExtractAndQueue(content, botGuid, speakerGuid, source, licenseStamp),
        botGuid);
    PlayerbotLlmFilters::RememberReply(botGuid, voiced);
    return voiced;
}

// The duty-cycle governor (S3's A0 hoist, extracted for S10): check AND
// consume one admission for botGuid inside the shared window state, so
// the ambient murmur path pays the exact budget a conversational turn
// pays - never a bypass. Same windows, same order, same mutex as the
// inline block that lived in Generate before the extraction (the
// structural contract is pinned by tests/test_llm_chatter.py; the
// pre-extraction text exists in no artifact, so the pin is the
// evidence).
bool PlayerbotLLMInterface::GovernorAdmit(uint32 botGuid)
{
    time_t const now = time(nullptr);
    static std::mutex governorMutex;
    static std::map<uint32, std::deque<time_t>> perBot;
    static std::deque<time_t> globalWindow;
    std::lock_guard<std::mutex> lock(governorMutex);

    time_t const cutoff = now - std::max<uint32>(1, sPlayerbotAIConfig.llmGovernorWindow);
    std::deque<time_t>& botWindow = perBot[botGuid];
    while (!botWindow.empty() && botWindow.front() < cutoff)
        botWindow.pop_front();
    while (!globalWindow.empty() && globalWindow.front() < cutoff)
        globalWindow.pop_front();

    bool const allowed =
        botWindow.size() < std::max<uint32>(1, sPlayerbotAIConfig.llmGovernorBotMax) &&
        globalWindow.size() < std::max<uint32>(1, sPlayerbotAIConfig.llmGovernorGlobalMax);
    if (allowed)
    {
        botWindow.push_back(now);
        globalWindow.push_back(now);
    }
    return allowed;
}

// S10: the raw chat-completions POST for callers that own their
// validation chain (the murmur/composer workers fail SILENT - the
// canned-deflection voice filter is for conversational turns, and a
// deflection addressed to nobody would be worse than no line).
std::string PlayerbotLLMInterface::PostChatHttp(std::string const& body,
    int timeOutSeconds, ParsedUrl const* endpointOverride,
    std::string const* apiKeyOverride)
{
    std::vector<std::string> noDebug;
    return GenerateHttp(body, timeOutSeconds, 1, noDebug, endpointOverride, apiKeyOverride);
}

// S10: ambient work yields the interactive lane entirely - the murmur
// batch only fires when no conversational generation is in flight (the
// plan's "lane 0 empty" admission).
bool PlayerbotLLMInterface::InteractiveGenerationInFlight()
{
    return sPlayerbotLLMInterface.generationCount.load() > 0;
}

std::string PlayerbotLLMInterface::Generate(const std::string& prompt, uint32 botGuid, uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source, uint64_t licenseStamp, int timeOutSeconds, int maxGenerations, std::vector<std::string> & debugLines, uint64_t reqId) {
    // A8 observability: one id per turn (the dispatch site pre-mints on
    // the world thread; direct callers mint here), one begin line after
    // the governor admits, exactly one end line classed by outcome.
    // Duration from steady_clock - the log timestamps are second-
    // resolution. No prompt or reply bytes ever ride these lines.
    if (!reqId)
        reqId = pocketllm::NextReqId();
    pocketllm::NoteGenClass("");
    std::chrono::steady_clock::time_point const t0 = std::chrono::steady_clock::now();
    char const* const lane = sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA ? "device" : "cloud";
    auto logEnd = [&](char const* cls)
    {
        unsigned long const durMs = (unsigned long)std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        sLog.outBasic("BotLLM: gen end req=%llu bot=%u class=%s durMs=%lu",
            (unsigned long long)reqId, botGuid, cls, durMs);
    };
    pocketLlmGenerationState = 0;

    // A0 governor hoist: the duty-cycle governor sits ABOVE the backend
    // branch - the HTTP path's only limiter used to be a concurrency counter
    // (default 100) against a serial single-slot server with a
    // queue-inclusive timeout, so a flood of whispers legally queued for
    // minutes. Over the density threshold the autonomous RPG path goes
    // silent and player-facing paths show the configured busy line instead
    // of silently queuing behind degraded decode - on both backends.
    // (S10: the check+consume itself is the shared GovernorAdmit.)
    bool allowed = GovernorAdmit(botGuid);

    if (!allowed)
    {
        if (!debugLines.empty())
            debugLines.push_back("duty-cycle governor busy");
        logEnd("busy");
        return source == PlayerbotLlamaRuntime::LLM_SRC_RPG_CHAT ? std::string() : std::string(POCKETREALM_LLM_BUSY);
    }

    // A8: the begin line sits after the governor (a denied turn logs
    // dispatch + end only - no generation ever started)
    sLog.outBasic("BotLLM: gen begin req=%llu bot=%u lane=%s",
        (unsigned long long)reqId, botGuid, lane);

    // A11 era logit bias: the always-ban terms, resolved once through the
    // embedded server's /tokenize endpoint (fail-open - no endpoint, no
    // bias; the guard and the era backstop still hold), ride every HTTP
    // request body. Spliced once here so every HTTP leg (first try, the
    // filter regenerations, the A9 retry) carries it; a body that already
    // declares a logit_bias (a hand-configured LLMApiJson) is untouched,
    // and providerSafe external endpoints never see llama-only keys.
    std::string promptBody = prompt;
    if (sPlayerbotAIConfig.llmBackend != PlayerbotAIConfig::LLM_BACKEND_LLAMA &&
        !sPlayerbotAIConfig.llmApiProviderSafe)
    {
        std::string const& eraBias = PlayerbotLlmFilters::EraBiasJson();
        size_t const close = promptBody.rfind('}');
        // tail-clean guard (round-1 R6): splice only when the last '}'
        // is really the body's close - a hand-configured template with
        // junk after it (or a '}' inside a trailing string value) must
        // not be corrupted into a body the server silently rejects
        bool tailClean = close != std::string::npos;
        for (size_t i = close + 1; tailClean && i < promptBody.size(); ++i)
            if (!isspace(static_cast<unsigned char>(promptBody[i])))
                tailClean = false;
        if (!eraBias.empty() && tailClean &&
            promptBody.find("\\"logit_bias\\"") == std::string::npos)
            promptBody.insert(close, ",\\"logit_bias\\":" + eraBias);
    }

    if (sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA)
    {
        std::string raw = PlayerbotLlamaRuntime::Generate(prompt, botGuid, source, timeOutSeconds, debugLines);
        // A8: symmetric device-lane classification (the assertion scope
        // decision - both lanes log begin/end, pinned host-side)
        if (raw.empty() || raw == "error")
            logEnd(raw == "error" ? "error" : "empty");
        else
            logEnd("ok");
        if (raw.empty() || raw == "error")
            return raw;

        // M4: tool blocks are stripped from the raw output here, before
        // ParseResponse's regexes can corrupt them, and queued for the
        // world-thread executor (tagged with this generation's source and
        // speaker so the executor can refuse persistence tools from
        // autonomous turns and attribute the rest to the interlocutor).
        // A12 deterministic hygiene applies (the debug backend regenerates
        // nothing - the leak/era class is HTTP-path machinery).
        return PlayerbotLlmFilters::HygienePass(
            PlayerbotLlmTools::ExtractAndQueue(raw, botGuid, speakerGuid, source, licenseStamp),
            botGuid);
    }

    // A9 real JSON client: the shipped endpoints (embedded llama-server and
    // external OpenAI-compatible services) answer /v1/chat/completions with
    // an envelope, so the assistant text is decoded here with full JSON
    // string semantics. Bodies that do not parse as a known envelope keep
    // the legacy regex path (their raw body flows to ParseResponse and the
    // conf-level patterns apply - the reviewed fallback for endpoints that
    // return non-OpenAI prose shapes).
    std::string const httpBody = GenerateHttp(promptBody, timeOutSeconds, maxGenerations, debugLines, nullptr, nullptr, reqId);
    // A8 classification precedence: a transport-noted class (http_%d /
    // timeout / cap) outranks the shape classes; "error" is the bare
    // transport failure, "empty" a clean reply with nothing voicable.
    std::string const genClass = httpBody == "error"
        ? (pocketllm::GenClassNote().empty() ? std::string("error") : pocketllm::GenClassNote())
        : std::string("ok");
    pocketllm::CompletionEnvelope envelope = pocketllm::ParseCompletionEnvelope(httpBody);
    if (!envelope.parsed)
    {
        // A body that parses as JSON but carries no completion (an error
        // envelope, an unexpected shape) must never be voiced raw - fail
        // quiet. A body that is not JSON at all keeps the legacy regex
        // fallback ONLY when it plausibly is prose: error pages, BOM
        // remnants and binary garbage would otherwise reach chat unfiltered
        // (the app empties the conf patterns, so nothing else stops them).
        if (envelope.jsonParsable || !pocketllm::LooksLikeVoicableText(httpBody))
        {
            if (!debugLines.empty())
                debugLines.push_back("response carries no voicable text - staying quiet");
            logEnd(httpBody == "error" ? genClass.c_str() : "empty");
            return std::string();
        }
        // A0: the HTTP branch runs the SAME tool extraction the in-process
        // branch always had - G-1 closed: <<tool>> calls from an envelope
        // reply are queued for the world-thread executor (speaker-tagged),
        // and the markers never reach the chat lines.
        logEnd("ok");
        return PlayerbotLlmFilters::HygienePass(
            PlayerbotLlmTools::ExtractAndQueue(httpBody, botGuid, speakerGuid, source, licenseStamp),
            botGuid);
    }

    if (pocketllm::ContentUsable(envelope))
    {
        if (!debugLines.empty())
            debugLines.push_back("LLM content: " + envelope.content);
        logEnd("ok");
        return PocketLlmVoiceFilter(envelope.content, botGuid, speakerGuid,
            source, licenseStamp, promptBody, timeOutSeconds, maxGenerations,
            debugLines, pocketLlmGenerationState, envelope.finishLength != 0);
    }

    // Empty-content guard: the pinned base model burns the whole token
    // budget on a thinking preamble routed into reasoning_content. ONE retry with a direct-answer instruction spliced onto the
    // same user turn, then give up quiet - the player never sees a literal
    // envelope fragment or an empty promise (fail-quiet law). The retry is
    // skipped when the budget was already exhausted mid-thinking
    // (finish_reason "length"): on the pinned base model every empty
    // draw at the production budget was length-class (every measured
    // empty draw at <=200 tokens under the trained prompt shapes was
    // length-class), and the thinking toll is budget-elastic
    // and always precedes content - an identical-budget resend cannot pay
    // it, so failing quiet immediately beats doubling the player's wait.
    // The retry stays armed for the recoverable class: the model finished
    // thinking (stop) but still produced no content.
    if (envelope.reasoningPresent && !envelope.finishLength)
    {
        std::string retryBody = promptBody;
        if (pocketllm::AppendInstructionToLastUserMessage(retryBody, " Answer directly."))
        {
            if (!debugLines.empty())
                debugLines.push_back("empty content with reasoning present - one direct-answer retry");
            pocketllm::CompletionEnvelope retry = pocketllm::ParseCompletionEnvelope(
                GenerateHttp(retryBody, timeOutSeconds, maxGenerations, debugLines, nullptr, nullptr, reqId));
            if (retry.parsed && pocketllm::ContentUsable(retry))
            {
                logEnd("ok");
                return PocketLlmVoiceFilter(retry.content, botGuid, speakerGuid,
                    source, licenseStamp, retryBody, timeOutSeconds, maxGenerations,
                    debugLines, pocketLlmGenerationState, retry.finishLength != 0);
            }
        }
    }

    if (!debugLines.empty())
        debugLines.push_back("no usable content in the response envelope - staying quiet");
    logEnd("empty");
    return std::string();
}

std::string PlayerbotLLMInterface::GenerateHttp(const std::string& prompt, int timeOutSeconds, int maxGenerations, std::vector<std::string> & debugLines, ParsedUrl const* endpointOverride, std::string const* apiKeyOverride, uint64_t reqId) {
    bool debug = !debugLines.empty();
    // A8: per-request class reset (the http_%d / timeout notes below are
    // thread-local; GenerateHttp is the only writer within a turn and
    // its callers run on one worker thread per generation)
    pocketllm::NoteGenClass("");
    (void)reqId;
"""
PB_SAY_HEADER_UPSTREAM = """#pragma once

#include "playerbot/strategy/Action.h"
#include "QuestAction.h"
"""
PB_SAY_HEADER_ANDROID = """#pragma once

#include "playerbot/strategy/Action.h"
#include "QuestAction.h"
#include "playerbot/PlayerbotLlamaRuntime.h"
#include "playerbot/PlayerbotLlmGates.h"
"""
PB_SAY_GEN_DECL_UPSTREAM = """        static delayedPackets GenerateResponsePackets(const std::string json
            , const WorldPacket chatTemplate, const WorldPacket emoteTemplate, const WorldPacket systemTemplate, const std::string startPattern, const std::string endPattern, const std::string deletePattern, const std::string splitPattern, bool debug = false);
"""
PB_SAY_GEN_DECL_ANDROID = """        static delayedPackets GenerateResponsePackets(const std::string json
            , uint32 botGuid, uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source, uint64_t licenseStamp
            , uint32 playerOrChannel, std::string botName
            , const WorldPacket chatTemplate, const WorldPacket emoteTemplate, const WorldPacket systemTemplate, const std::string startPattern, const std::string endPattern, const std::string deletePattern, const std::string splitPattern, bool debug = false
            , uint32 replyClass = 0, bool longFormCued = false, uint64_t reqId = 0
            , PlayerbotLlmGates::FallbackPlan const& fallback = PlayerbotLlmGates::FallbackPlan());
"""
PB_SAY_GEN_DEF_UPSTREAM = """delayedPackets ChatReplyAction::GenerateResponsePackets(const std::string json
    , const WorldPacket chatTemplate, const WorldPacket emoteTemplate, const WorldPacket systemTemplate, const std::string startPattern, const std::string endPattern, const std::string deletePattern, const std::string splitPattern, bool debug)
{
    std::vector<std::string> debugLines;

    if (debug)
        debugLines = { json };

    auto startTime = time(nullptr);

    std::string response = PlayerbotLLMInterface::Generate(json, sPlayerbotAIConfig.llmGenerationTimeout, sPlayerbotAIConfig.llmMaxSimultaniousGenerations, debugLines);
"""
PB_SAY_GEN_DEF_ANDROID = """delayedPackets ChatReplyAction::GenerateResponsePackets(const std::string json
    , uint32 botGuid, uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source, uint64_t licenseStamp
    , uint32 playerOrChannel, std::string botName
    , const WorldPacket chatTemplate, const WorldPacket emoteTemplate, const WorldPacket systemTemplate, const std::string startPattern, const std::string endPattern, const std::string deletePattern, const std::string splitPattern, bool debug
    , uint32 replyClass, bool longFormCued, uint64_t reqId
    , PlayerbotLlmGates::FallbackPlan const& fallback)
{
    std::vector<std::string> debugLines;

    if (debug)
        debugLines = { json };

    // the governor lives above the backend branch inside Generate (A0);
    // autonomous RPG chatter goes silently over budget, player-facing
    // paths show the busy line

    auto startTime = time(nullptr);

    std::string response = PlayerbotLLMInterface::Generate(json, botGuid, speakerGuid, source, licenseStamp, sPlayerbotAIConfig.llmGenerationTimeout, sPlayerbotAIConfig.llmMaxSimultaniousGenerations, debugLines, reqId);

    // governor busy placeholder: player-visible feedback instead of a silent
    // queue; the autonomous RPG path never gets here (it returns empty).
    // Captured BEFORE the substitution so the recorder below can tell the
    // placeholder apart from a genuine generation.
    bool const busyReply = response == POCKETREALM_LLM_BUSY;
    if (busyReply)
    {
        // M6: the busy placeholder is drawn from the banter core's POOL_BUSY
        // recency ring (per-bot, novelty-weighted, tic-seasoned) instead of
        // the old global counter rotation; BusyReply falls back to the
        // configured LLMBusyReply conf line if a draw ever fails.
        response = PlayerbotLlmPersona::BusyReply(botGuid);
    }
    else if (response == "error" || response.empty())
    {
        // a hard backend failure never reaches the player as a literal
        // "error" - the bot stays silent rather than break character
        response = "";
    }
"""
PB_SAY_GATE_UPSTREAM = """    if (bot->GetPlayerbotAI() && sPlayerbotAIConfig.llmEnabled > 0 && (bot->GetPlayerbotAI()->HasStrategy("ai chat", BotState::BOT_STATE_NON_COMBAT) || sPlayerbotAIConfig.llmEnabled == 3) && chatChannelSource != ChatChannelSource::SRC_UNDEFINED && sPlayerbotAIConfig.llmBlockedReplyChannels.find(chatChannelSource) == sPlayerbotAIConfig.llmBlockedReplyChannels.end()
        )
"""
PB_SAY_GATE_ANDROID = """    bool useLlamaBackend = sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA;

    // A0: the hard-trigger gate is backend-independent (G-6 closed: the
    // llama-only gate meant the HTTP path answered trade/general/bystander
    // chatter). Direct conversation only - whispers to the bot, or
    // party/raid/say chat that addresses the bot by name (say additionally
    // requires a real player, so ambient bot chatter never pays a
    // generation). Everything else (trade, general, bystander chatter) is a
    // non-trigger, not a low-priority one. Case-insensitive: players
    // routinely type bot names in lowercase, and a dropped trigger is
    // silent (canned fallback).
    bool addressedToBot = !msg.empty() && boost::algorithm::icontains(msg, bot->GetName());
    // resolved here (not inside the gated block) so the say trigger can
    // require a real player without touching the later `player` declaration
    Player* gateSpeaker = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, guid1));
    // A1/A3: the hard trigger is the pure gate helper (mirror-enum values
    // bridged by the static_asserts above). The widened arms: a REAL
    // player's addressed party/raid line (bot-authored holes stay shut)
    // and - cloud lane only, behind the default-0 party reply arm - one
    // unaddressed party line. The bot-authored addressed hole (a bot
    // naming a bot) is closed: hardTriggerAllowed requires a real player
    // on every party/raid/say leg.
    bool hardTriggerAllowed = PlayerbotLlmGates::HardTriggerAllowed(
        static_cast<uint32>(chatChannelSource), addressedToBot,
        gateSpeaker && gateSpeaker->isRealPlayer(), CloudLaneOpen(),
        sPlayerbotAIConfig.llmPartyReplyEnabled != 0);

    // S8/A18 crowd arbiter: a real player's ambient /say that names no
    // bot is a NON-trigger for generations, but the street may still
    // react - one or two nearby bots answer with a deterministic,
    // staggered text emote (never a generation: the authored layer was
    // measured better on calm beats and costs nothing). The world thread
    // runs here; the emote queues with its own 2-5s pacing.
    // A6: on the cloud lane the street admission ladder runs FIRST
    // (world/zone window -> per-bot slot -> pct roll -> daily quota ->
    // dispatch); ANY rejection falls to the crowd emote exactly as
    // before, and a dispatched street say DROPS the emote for this
    // event (not defers it). Off the cloud lane QueueStreetReaction
    // no-ops before touching any state - the device lane is
    // byte-identical.
    if (!hardTriggerAllowed && chatChannelSource == ChatChannelSource::SRC_SAY &&
        gateSpeaker && gateSpeaker->isRealPlayer() &&
        sPlayerbotAIConfig.llmEnabled > 0)
    {
        if (!PlayerbotLlmMemory::QueueStreetReaction(bot, gateSpeaker, msg))
            PlayerbotLlmMemory::QueueCrowdEmote(bot, gateSpeaker);
        // plan v5 W5: an ARMED curiosity ask consumes the player's spoken
        // answer here - a say that names no bot never reaches a
        // generation turn, and a vanished answer is a broken promise
        // (no-op when this bot holds no arm for the speaker)
        PlayerbotLlmMemory::ConsumePendingAnswer(bot->GetGUIDLow(),
            gateSpeaker->GetGUIDLow(), msg);
    }

    // Interruption rule: player chat owns the channel - stamp
    // every real-player conversational trigger so ambient murmur/party
    // delivery pauses around the player's own words (the global set
    // piece is exempt - general chat is not the player's channel).
    if (hardTriggerAllowed && gateSpeaker && gateSpeaker->isRealPlayer())
        PlayerbotLlmChatter::NotePlayerInteraction(gateSpeaker->GetGUIDLow());

    // plan v5 C3: the roundtable row + A3's exactly-one responder - a
    // real master's PARTY line is
    // remembered so the next party-lane composer exchange can argue
    // about what the PLAYER just said (quota-capped at consumption).
    // W5: an UNADDRESSED party line (no bot named) consumes an armed
    // curiosity ask here; players answer on the channel the group talks
    // on. A3 widens the unaddressed leg into exactly-one RESPONDER
    // (cloud lane only, behind the default-0 party reply arm):
    // recording is preserved for ALL bots - the unaddressed leg now
    // NotePartyLine beside the consume - while the GENERATION belongs to
    // one bot. The claim is checked at the TOP of the party block so
    // loser bots skip the context-building fan-out entirely, not just
    // dispatch: every bot deterministically picks the same responder
    // (SelectResponder over the group candidates), the picked bot then
    // passes the per-speaker flood gate (N lines in 2 s = one
    // generation) and takes the first-writer-wins claim. The addressed
    // bot bypasses the claim; whisper/say paths never consult it.
    bool partyResponderClaimed = true;
    if (gateSpeaker && gateSpeaker->isRealPlayer() &&
        chatChannelSource == ChatChannelSource::SRC_PARTY)
    {
        if (addressedToBot)
            PlayerbotLlmChatter::NotePartyLine(gateSpeaker->GetGUIDLow(), msg);
        else
            PlayerbotLlmMemory::ConsumePendingAnswer(bot->GetGUIDLow(),
                gateSpeaker->GetGUIDLow(), msg);
        // A3: recording for ALL bots - the unaddressed line joins the
        // roundtable row too (the old shape recorded it only when a bot
        // was named)
        if (!addressedToBot)
            PlayerbotLlmChatter::NotePartyLine(gateSpeaker->GetGUIDLow(), msg);

        if (!addressedToBot && CloudLaneOpen() &&
            sPlayerbotAIConfig.llmPartyReplyEnabled != 0)
        {
            partyResponderClaimed = false;
            if (Group* responderGroup = bot->GetGroup())
            {
                std::vector<PlayerbotLlmGates::ResponderCandidate> partyCandidates;
                PlayerbotLlmMemory::CollectPartyCandidates(
                    responderGroup->GetId(), gateSpeaker->GetGUIDLow(),
                    partyCandidates);
                uint32 const pickedResponder =
                    PlayerbotLlmGates::SelectResponder(partyCandidates);
                if (pickedResponder == bot->GetGUIDLow() &&
                    PlayerbotLlmMemory::PartyFloodAdmits(gateSpeaker->GetGUIDLow()))
                {
                    partyResponderClaimed = PlayerbotLlmMemory::TryClaimPartyResponder(
                        bot->GetGUIDLow(), gateSpeaker->GetGUIDLow(),
                        PlayerbotLlmMemory::PartyMsgHash(msg), responderGroup->GetId());
                }
            }
        }
    }

    // A1: the cloud lane widens the strategy gate (CloudLaneOpen() is the
    // key AND tier conjunction - llmEnabled == 2 + strategy stays today's
    // external behavior byte-for-byte; == 3 stays the hand-conf lane).
    // A3: the responder claim ANDs in here - it starts true and only the
    // unaddressed cloud party leg can clear it, so the addressed,
    // whisper and say paths never consult the claim
    if (bot->GetPlayerbotAI() && sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed && partyResponderClaimed && (bot->GetPlayerbotAI()->HasStrategy("ai chat", BotState::BOT_STATE_NON_COMBAT) || sPlayerbotAIConfig.llmEnabled == 3 || CloudLaneOpen()) && chatChannelSource != ChatChannelSource::SRC_UNDEFINED && sPlayerbotAIConfig.llmBlockedReplyChannels.find(chatChannelSource) == sPlayerbotAIConfig.llmBlockedReplyChannels.end()
        )
"""
PB_SAY_PROMPT_UPSTREAM = """                for (auto& prompt : jsonFill)
                {
                    prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
                }

                for (auto& prompt : placeholders) //Sanitize now instead of earlier to prevent double Sanitation
                {
                    prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
                }
"""
PB_SAY_PROMPT_ANDROID = """                std::string json;

                if (useLlamaBackend)
                {
                    // raw completion prompt - the in-process backend takes
                    // plain text, no JSON envelope and no escaping
                    json = jsonFill["<pre prompt>"] + " " + jsonFill["<context>"] + " " + jsonFill["<prompt>"] + " " + jsonFill["<post prompt>"];
                }
                else
                {
                    for (auto& prompt : jsonFill)
                    {
                        prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
                    }

                    for (auto& prompt : placeholders) //Sanitize now instead of earlier to prevent double Sanitation
                    {
                        prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
                    }
                }
"""
PB_SAY_RECORDER_UPSTREAM = """    std::vector<std::string> lines = PlayerbotLLMInterface::ParseResponse(response, startPattern, endPattern, deletePattern, splitPattern, debugLines);
"""
PB_SAY_RECORDER_ANDROID = """    std::vector<std::string> lines = PlayerbotLLMInterface::ParseResponse(response, startPattern, endPattern, deletePattern, splitPattern, debugLines);

    // E1 per-class voice budgets (pure core, applied before the recorder so
    // history and the player see the same words): whisper-class replies are
    // a note, not an essay - at most two 160-byte lines; the ambient RPG
    // class speaks one 80-byte line. The 255 splitter cap stays the hard
    // channel bound underneath. S11: a CUE-BEARING turn (longFormCued -
    // the generation's own note carried the frozen long-form cue, resolved
    // stamp-checked at the call site) on a long-form-licensed tier may run
    // to the splitter's own 3-4 line budget; a plain turn keeps the short
    // budget on every tier (the widening is earned per turn, never tier-wide).
    // The preset's longForm dial rides the SAME license the cue gate used,
    // so a storytelling preset (dial 100, bar 150) is not handed the
    // "tell it whole" cue only to be clamped back to the short budget.
    pocketllm::ApplyReplyBudget(lines, replyClass, sPlayerbotAIConfig.llmMaxNewTokens, longFormCued, sPlayerbotAIConfig.llmRpLongForm);

    // A4 (cloud lane, conversational turns only): the authored
    // failure-fallback - the dead-endpoint law. Busy keeps the persona
    // placeholder (duty-cycle denial is pacing, not a dead endpoint);
    // every other hard failure (cap, timeout, http_%d, error) and
    // post-parse emptiness draws the plan's authored line AT DELIVERY
    // TIME (pre-drawing would advance shared recency rings and mint
    // belief facts for lines never delivered). The closure owns
    // {deliver, bot-line record, guid award} exactly once per turn
    // outcome: delivery queues on the world-thread EventReaction drain
    // (guid identity - no Player*/Session* crosses the async wait), so
    // the line is pulled OUT of the packets pipeline (single delivery -
    // the packets path then returns nothing). The autonomous RPG source
    // carries no plan and stays silent, exactly as before; an inactive
    // plan is exactly the device lane (byte-identical silence).
    bool fallbackDelivered = false;
    if (PlayerbotLlmGates::FailureWantsFallback(busyReply, lines.empty()) &&
        fallback.active &&
        source == PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY)
    {
        std::string const fallbackLine =
            PlayerbotLlmMemory::DrawFailureFallback(fallback, botGuid, speakerGuid);
        if (!fallbackLine.empty())
        {
            PlayerbotLlmMemory::AppendTurn(botGuid, playerOrChannel,
                (playerOrChannel & 0x80000000u) != 0, botName, fallbackLine);
            PlayerbotLlmMemory::QueueConversationalFallback(botGuid, speakerGuid,
                fallback.channel, fallbackLine, fallback.mapId);
            lines.clear();
            fallbackDelivered = true;
        }
    }

    // the bot's own reply joins the shared rolling history so the next prompt
    // is never a one-sided transcript (mutex-guarded; async thread safe).
    // Only genuine generations are recorded - never the busy placeholder
    // or a raw error string, which would pollute every later prompt.
    // Backend-agnostic: the embedded llama-server path (LLMBackend = 0)
    // speaks through the same lines pipeline and needs the same history.
    if (playerOrChannel && !botName.empty()
        && !busyReply && response != "error" && !lines.empty())
    {
        std::string joined;
        for (std::string const& line : lines)
        {
            if (!joined.empty())
                joined += " ";
            joined += line;
        }
        if (!joined.empty())
        {
            PlayerbotLlmMemory::AppendTurn(botGuid, playerOrChannel, true, botName, joined);
            // E4 diagnostics: one player-facing conversation per genuine
            // voiced reply - exactly this gate's own definition
            PlayerbotLlmMemory::NoteConversation();
        }
    }

    // A4: the single-delivery closure's guid award - exactly once per
    // turn outcome. A busy placeholder, a genuine generation and a
    // fallback line all delivered something and all award the turn;
    // a silent failure (nothing drawn, nobody left to speak to)
    // delivers nothing and awards nothing. Cloud conversational turns
    // only: the device lane's +1 stays at the synchronous pre-dispatch
    // site (byte-identical device behavior).
    if (fallback.active && speakerGuid &&
        source == PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY &&
        (busyReply || fallbackDelivered || !lines.empty()))
        PlayerbotLlmMemory::AddRelationshipPointsByGuid(botGuid, speakerGuid, 1);

    // E4 diagnostics: the counter's only in-tree read (the app-side
    // transport is the declared Workstream-A dependency; the debug path
    // is the sanctioned dev surface)
    if (debug)
        debugLines.push_back("LLM conversations this session: " +
            std::to_string(PlayerbotLlmMemory::ConversationCount()));
"""

# The pacing call. Upstream is the single 200 ms/char call whose
# timeDiff credit zeroed after the first line.
PB_SAY_PACE_CALL_UPSTREAM = """    delayedPackets packets, debugPackets;

    packets = LinesToPackets(lines, chatTemplate, false, 200, emoteTemplate, timeDiff);
"""
PB_SAY_PACE_CALL_ANDROID = """    delayedPackets packets, debugPackets;

    // E1 pacing law: the generation wait is credited across ALL lines (the
    // old credit zeroed after the first line, which then still dribbled -
    // a 100-char line waited 20 s AFTER an 8 s generation at 200 ms/char),
    // and the typing pace drops to 35 ms/char (the journal keeps its
    // diary pace of 4, the debug path 1; the A18 say stagger lives ahead
    // of this pipeline and is untouched). The busy placeholder is an
    // admission-control acknowledgment, not prose: it lands instantly -
    // the E1 busy-within-1s law.
    packets = LinesToPackets(lines, chatTemplate, false,
        busyReply ? 0 : 35, emoteTemplate, busyReply ? 0 : timeDiff);
"""

# The timeDiff credit becomes a running budget consumed across all
# lines (both delay blocks share the shape; the sLog lines make the tail
# block unique).
PB_SAY_TIMEDIFF_HEAD_UPSTREAM = """                auto sentenceSplit = sentence.substr(0, splitPos);
                auto delay = sentenceSplit.size() * MsPerChar;
                if (timeDiff)
                {
                    if (timeDiff >= delay)
                    {
                        delay = 0;
                    }
                    else
                    {
                        delay -= timeDiff;
                    }
                    timeDiff = 0;
                }
"""
PB_SAY_TIMEDIFF_HEAD_ANDROID = """                auto sentenceSplit = sentence.substr(0, splitPos);
                auto delay = sentenceSplit.size() * MsPerChar;
                // E1: the generation credit is a RUNNING budget - consumed
                // across every line (the old form zeroed it after the first
                // line, which then still paid its full per-char delay)
                if (timeDiff)
                {
                    if (timeDiff >= delay)
                    {
                        timeDiff -= delay;
                        delay = 0;
                    }
                    else
                    {
                        delay -= timeDiff;
                        timeDiff = 0;
                    }
                }
"""
PB_SAY_TIMEDIFF_TAIL_UPSTREAM = """            auto delay = sentence.size() * MsPerChar;
            if (timeDiff)
            {
                if (timeDiff >= delay)
                {
                    delay = 0;
                    sLog.outError("delay packet removed: %lu", delay);
                }
                else
                {
                    delay -= timeDiff;
                    sLog.outError("delay packet reduced to %lu", delay);
                }
                timeDiff = 0;
            }
"""
PB_SAY_TIMEDIFF_TAIL_ANDROID = """            auto delay = sentence.size() * MsPerChar;
            // E1: same running-budget credit as the head block. The
            // upstream outError diagnostics drop to debug level: with the
            // credit now surviving past the head, the covered-line branch
            // fires on ordinary fast replies (every generation >= the
            // last line's pace) and error-level spam filled the device log
            if (timeDiff)
            {
                if (timeDiff >= delay)
                {
                    timeDiff -= delay;
                    delay = 0;
                    sLog.outDebug("delay packet removed: %lu", delay);
                }
                else
                {
                    delay -= timeDiff;
                    timeDiff = 0;
                    sLog.outDebug("delay packet reduced to %lu", delay);
                }
            }
"""
PB_SAY_SPLITTER_UPSTREAM = """        std::string sentence = line;
        while (sentence.length() > 200) {
            size_t splitPos = sentence.rfind(' ', 200);
            if (splitPos == std::string::npos) {
                splitPos = 200;
            }
"""
PB_SAY_SPLITTER_ANDROID = """        std::string sentence = line;
        // A12: the /say client cap is 255 bytes - ONE documented number
        // (the old constant split at 200 while the client cut at 255).
        // The hard fallback cut backs off UTF-8 sequence bytes so a line
        // never ends mid-character (defensive: the ASCII clamp runs
        // upstream on the LLM paths).
        while (sentence.length() > 255) {
            size_t splitPos = sentence.rfind(' ', 255);
            if (splitPos == std::string::npos) {
                splitPos = 255;
                while (splitPos > 0 &&
                       ((unsigned char)sentence[splitPos - 1] & 0xC0) == 0x80)
                    --splitPos; // never cut inside a multibyte sequence
                if (splitPos > 0 &&
                    ((unsigned char)sentence[splitPos - 1] & 0xC0) == 0xC0)
                    --splitPos; // nor right after an orphaned lead byte
            }
"""
PB_SAY_JSON_DUP_UPSTREAM = """                splitPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseSplitPattern, placeholders);

                std::string json = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmApiJson, jsonFill);

                json = PlayerbotTextMgr::GetReplacePlaceholders(json, placeholders);
"""
PB_SAY_JSON_DUP_ANDROID = """                splitPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseSplitPattern, placeholders);

                if (useLlamaBackend)
                {
                    // raw completion output: no JSON start marker to cut
                    // around. The end pattern is replaced with a pure
                    // speaker-cut - the config default also cuts at a bare
                    // quote (right for JSON, wrong for prose) - so the bot
                    // can never voice the other party's continued turn
                    startPattern.clear();
                    endPattern = std::string("\\\\b(?!") + bot->GetName() + "\\\\b)(\\\\w+):";
                }
                else if (json.empty())
                {
                    // legacy conf-template fill only: under the trained
                    // prompt format (A4) the native builder already built
                    // the request body - the template must not clobber it
                    json = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmApiJson, jsonFill);

                    json = PlayerbotTextMgr::GetReplacePlaceholders(json, placeholders);
                }
"""
PB_SAY_ASYNC_UPSTREAM = """                futurePackets futPackets = std::async(std::launch::async, ChatReplyAction::GenerateResponsePackets, json, chatTemplate, emoteTemplate, systemTemplate, startPattern, endPattern, deletePattern, splitPattern, debug);
"""
PB_SAY_ASYNC_ANDROID = """                uint32 llmHistoryKey = (chatChannelSource == ChatChannelSource::SRC_WHISPER && player)
                    ? player->GetGUIDLow()
                    : (0x80000000u | static_cast<uint32>(chatChannelSource));
                // A0 interlocutor fix: persistence tools attribute to the
                // player who actually spoke (whisperer or party/raid/say
                // speaker), carried through to the queued tool calls - not
                // to the bot's owner. Bot-to-bot talk passes 0 (autonomous).
                uint32 llmSpeakerGuid = (player && player->isRealPlayer() && player != bot)
                    ? player->GetGUIDLow() : 0;
                // E1 reply class 0: conversational (whisper-class budgets);
                // S11: the long-form widening is earned per TURN - the
                // flag resolves against the generation's own license stamp,
                // so a plain turn keeps the short budget on every tier
                // A8: the turn's request id is minted here (world thread,
                // before the async launch) so the dispatch line and the
                // worker's begin/end lines correlate; unthrottled - the
                // per-turn assertion needs every line at 320-bot bursts
                uint64_t const llmReqId = pocketllm::NextReqId();
                sLog.outBasic("BotLLM: dispatch bot=%u src=%d lane=%s req=%llu",
                    bot->GetGUIDLow(), (int)PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY,
                    useLlamaBackend ? "device" : "cloud", (unsigned long long)llmReqId);
                // A2: arm the fast-lane window on real-player turns only
                // (event turns and bot2bot turns never arm - llmEventTurn
                // and llmSpeakerGuid are the gates; listener bots never
                // reach ChatReplyDo). The key gates arming inside
                // ArmDialogue; the cache stamp makes the uptake immediate
                // instead of lagging the 5 s AllowActivity window.
                llmFallback.mapId = bot->GetMap() ? bot->GetMap()->GetId() : 0;
                if (!llmEventTurn && llmSpeakerGuid && llmFallback.mapId)
                {
                    PlayerbotLlmMemory::ArmDialogue(bot->GetGUIDLow(),
                        llmFallback.mapId, /*interlocutor=*/true);
                    ai->ForceActivityRecheck();
                }
                futurePackets futPackets = std::async(std::launch::async, ChatReplyAction::GenerateResponsePackets, json, bot->GetGUIDLow(), llmSpeakerGuid, PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY, llmLicenseStamp, llmHistoryKey, bot->GetName(), chatTemplate, emoteTemplate, systemTemplate, startPattern, endPattern, deletePattern, splitPattern, debug, 0u, PlayerbotLlmBridge::NoteLongFormCued(bot->GetGUIDLow(), llmLicenseStamp), llmReqId, llmFallback);
"""
# A1b containment: RequestNewLines on the cloud lane is generation-
# quota'd (llmRpgChatPerDay, counting GENERATIONS - one trigger is 5-11
# turns) with a silent stop + one Basic line per UTC day on exhaustion.
PB_RPG_QUOTA_UPSTREAM = """bool RpgAIChatAction::RequestNewLines()
{
    if (packets.size())
        return false;
"""
PB_RPG_QUOTA_ANDROID = """bool RpgAIChatAction::RequestNewLines()
{
    // A1b: the RPG lane is open on BOTH lanes today; on the cloud lane
    // it stays open but generation-quota'd (conversations vs lines differ
    // 10x - the quota counts triggers). Silent stop on exhaustion; one
    // Basic line per UTC day records that the lane went quiet.
    if (CloudLaneOpen())
    {
        static int64_t lastQuotaNoteDay = 0;
        if (!PlayerbotLlmMemory::CloudQuotaAdmits("rpgchat", sPlayerbotAIConfig.llmRpgChatPerDay))
        {
            int64_t const day = (int64_t)(time(nullptr) / 86400);
            if (lastQuotaNoteDay != day)
            {
                lastQuotaNoteDay = day;
                sLog.outBasic("BotLLM: rpgchat daily quota exhausted (%u generations)", sPlayerbotAIConfig.llmRpgChatPerDay);
            }
            return false;
        }
    }

    if (packets.size())
        return false;
"""
PB_RPG_ASYNC_UPSTREAM = """    futPackets = std::async(std::launch::async, ChatReplyAction::GenerateResponsePackets, json, chatTemplate, emoteTemplate, systemTemplate, startPattern, endPattern, deletePattern, splitPattern, debug);
"""
# A8: the RPG dispatch site logs BotLLM: dispatch like the conversational
# lane, which needs pocketllm::NextReqId (PlayerbotLlmFilters.h).
PB_RPG_INCLUDE_UPSTREAM = """#include "playerbot/PlayerbotLLMInterface.h"
"""
PB_RPG_INCLUDE_ANDROID = """#include "playerbot/PlayerbotLLMInterface.h"
#include "playerbot/PlayerbotLlmFilters.h"
"""
PB_RPG_ASYNC_ANDROID = """    // E1 reply class 1: ambient (one 80-byte line - a bark, not a speech);
    // longFormCued explicit false - ambient never earns the widening
    // (defaults do not bind through the std::async function pointer)
    // A8: same dispatch line as the conversational lane
    {
        uint64_t const llmReqId = pocketllm::NextReqId();
        sLog.outBasic("BotLLM: dispatch bot=%u src=%d lane=%s req=%llu",
            bot->GetGUIDLow(), (int)PlayerbotLlamaRuntime::LLM_SRC_RPG_CHAT,
            sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA ? "device" : "cloud",
            (unsigned long long)llmReqId);
        futPackets = std::async(std::launch::async, ChatReplyAction::GenerateResponsePackets, json, bot->GetGUIDLow(), uint32(0), PlayerbotLlamaRuntime::LLM_SRC_RPG_CHAT, uint64_t(0), 0, bot->GetName(), chatTemplate, emoteTemplate, systemTemplate, startPattern, endPattern, deletePattern, splitPattern, debug, 1u, false, llmReqId);
    }
"""
# The RPG ambient-chatter path (bot <-> NPC barks) still built the HTTP JSON
# envelope and kept the JSON start pattern, which drops every plain-prose
# line the in-process backend returns (silent world) while still running the
# extracted tool side effects. Under llama it must send the raw completion
# prompt exactly like the SayAction path.
PB_RPG_PROMPT_UPSTREAM = """    for (auto& prompt : jsonFill)
    {
        prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
    }

    for (auto& prompt : placeholders) //Sanitize now instead of earlier to prevent double Sanitation
    {
        prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
    }

    std::string startPattern, endPattern, deletePattern, splitPattern;
    startPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseStartPattern, placeholders);
    endPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseEndPattern, placeholders);
    deletePattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseDeletePattern, placeholders);
    splitPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseSplitPattern, placeholders);

    std::string json = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmApiJson, jsonFill);

    json = PlayerbotTextMgr::GetReplacePlaceholders(json, placeholders);
"""
PB_RPG_PROMPT_ANDROID = """    std::string startPattern, endPattern, deletePattern, splitPattern;
    startPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseStartPattern, placeholders);
    endPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseEndPattern, placeholders);
    deletePattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseDeletePattern, placeholders);
    splitPattern = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmResponseSplitPattern, placeholders);

    std::string json;
    if (sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA)
    {
        // raw completion prompt: the in-process backend takes plain text, no
        // JSON envelope and no JSON escaping, and the JSON start pattern would
        // drop every generated line. The end pattern is replaced with a pure
        // speaker-cut (the config default also cuts at a bare quote, right
        // for JSON, wrong for prose - quoted RPG lines would vanish).
        json = jsonFill["<pre prompt>"] + " " + jsonFill["<context>"] + " " + jsonFill["<prompt>"] + " " + jsonFill["<post prompt>"];
        startPattern.clear();
        endPattern = std::string("\\\\b(?!") + bot->GetName() + "\\\\b)(\\\\w+):";
    }
    else
    {
        for (auto& prompt : jsonFill)
        {
            prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
        }

        for (auto& prompt : placeholders) //Sanitize now instead of earlier to prevent double Sanitation
        {
            prompt.second = PlayerbotLLMInterface::SanitizeForJson(prompt.second);
        }

        json = PlayerbotTextMgr::GetReplacePlaceholders(sPlayerbotAIConfig.llmApiJson, jsonFill);

        json = PlayerbotTextMgr::GetReplacePlaceholders(json, placeholders);
    }
"""
# The upstream reply-type switch has no SRC_RAID case, so a raid mention
# (a supported hard trigger) fell through to the CHAT_MSG_WHISPER default and
# the bot answered raid banter in private tells.
PB_SAY_RAID_CASE_UPSTREAM = """                case ChatChannelSource::SRC_PARTY:
                {
                    type = CHAT_MSG_PARTY;
                    break;
                }
                case ChatChannelSource::SRC_GUILD:
"""
PB_SAY_RAID_CASE_ANDROID = """                case ChatChannelSource::SRC_PARTY:
                {
                    type = CHAT_MSG_PARTY;
                    break;
                }
                case ChatChannelSource::SRC_RAID:
                {
                    type = CHAT_MSG_RAID;
                    break;
                }
                case ChatChannelSource::SRC_GUILD:
"""
PB_DEBUG_GEN_UPSTREAM = """    std::string response = PlayerbotLLMInterface::Generate(json, sPlayerbotAIConfig.llmGenerationTimeout, sPlayerbotAIConfig.llmMaxSimultaniousGenerations, debugLines);
"""
PB_DEBUG_GEN_ANDROID = """    // A8: the debug lane logs the same dispatch/begin/end triple
    uint64_t const llmReqId = pocketllm::NextReqId();
    sLog.outBasic("BotLLM: dispatch bot=%u src=%d lane=%s req=%llu",
        bot->GetGUIDLow(), (int)PlayerbotLlamaRuntime::LLM_SRC_DEBUG,
        sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA ? "device" : "cloud",
        (unsigned long long)llmReqId);
    std::string response = PlayerbotLLMInterface::Generate(json, bot->GetGUIDLow(), uint32(0), PlayerbotLlamaRuntime::LLM_SRC_DEBUG, uint64_t(0), sPlayerbotAIConfig.llmGenerationTimeout, sPlayerbotAIConfig.llmMaxSimultaniousGenerations, debugLines, llmReqId);
    if (response == POCKETREALM_LLM_BUSY)
        response = "(governor busy)";
"""
# 0.c.1 debug gate: `.bot` chat events forced isMod for ANY requesting
# player (the .bot command family registers at SEC_PLAYER), so
# `debug llm` - which echoes the transport trace including the request
# headers - was reachable by every player. The mod disjunct is removed:
# a player event must carry a real moderator session; ownerless events
# (console/mgr) keep mod powers.
PB_DEBUG_MODGATE_UPSTREAM = """    bool isMod = event.getSource() == ".bot" || (event.getOwner() && event.getOwner()->GetSession() && event.getOwner()->GetSession()->GetSecurity() >= SEC_MODERATOR);
"""
PB_DEBUG_MODGATE_ANDROID = """    bool isMod = !event.getOwner() ||
        (event.getOwner()->GetSession() && event.getOwner()->GetSession()->GetSecurity() >= SEC_MODERATOR);
"""
# A8: the debug dispatch line needs pocketllm::NextReqId
PB_DEBUG_INCLUDE_UPSTREAM = """#include "playerbot/PlayerbotLLMInterface.h"
"""
PB_DEBUG_INCLUDE_ANDROID = """#include "playerbot/PlayerbotLLMInterface.h"
#include "playerbot/PlayerbotLlmFilters.h"
"""
PB_SESSION_LIFETIME_UPSTREAM = """void PlayerbotAI::SendDelayedPacket(WorldSession* session, futurePackets futPackets)
{
    std::thread t([session, futPacket = std::move(futPackets)]() mutable {
        for (auto& delayedPacket : futPacket.get())
        {
            if (delayedPacket.second)
                std::this_thread::sleep_for(std::chrono::milliseconds(delayedPacket.second));

            std::unique_ptr<WorldPacket> packetPtr(new WorldPacket(delayedPacket.first));
            session->QueuePacket(std::move(packetPtr));
        }
    });

    t.detach();
}

void PlayerbotAI::ReceiveDelayedPacket(futurePackets futPackets)
{
    PacketHandlingHelper* handler = &botOutgoingPacketHandlers;
    std::thread t([handler, futPackets = std::move(futPackets)]() mutable {
        for (auto& delayedPacket : futPackets.get())
        {            
            handler->AddPacket(delayedPacket.first);
            if(delayedPacket.second)
                std::this_thread::sleep_for(std::chrono::milliseconds(delayedPacket.second));
        }
        });

    t.detach();
}"""
PB_SESSION_LIFETIME_ANDROID = """void PlayerbotAI::SendDelayedPacket(WorldSession* session, futurePackets futPackets)
{
    uint32 accountId = session->GetAccountId();
    std::thread t([session, accountId, futPacket = std::move(futPackets)]() mutable {
        for (auto& delayedPacket : futPacket.get())
        {
            // a multi-second LLM generation can outlive the session: re-validate
            // against the session registry before every packet we queue
            if (sWorld.FindSession(accountId) != session)
                return;

            if (delayedPacket.second)
                std::this_thread::sleep_for(std::chrono::milliseconds(delayedPacket.second));

            std::unique_ptr<WorldPacket> packetPtr(new WorldPacket(delayedPacket.first));
            session->QueuePacket(std::move(packetPtr));
        }
    });

    t.detach();
}

void PlayerbotAI::ReceiveDelayedPacket(futurePackets futPackets)
{
    PacketHandlingHelper* handler = &botOutgoingPacketHandlers;
    ObjectGuid botGuid = bot->GetObjectGuid();
    std::thread t([handler, botGuid, futPackets = std::move(futPackets)]() mutable {
        for (auto& delayedPacket : futPackets.get())
        {
            // the bot (and with it this PlayerbotAI and its packet handlers)
            // may be gone by the time the generation finishes
            Player* player = sObjectAccessor.FindPlayer(botGuid);
            if (!player || !player->GetPlayerbotAI() || &player->GetPlayerbotAI()->botOutgoingPacketHandlers != handler)
                return;

            handler->AddPacket(delayedPacket.first);
            if(delayedPacket.second)
                std::this_thread::sleep_for(std::chrono::milliseconds(delayedPacket.second));
        }
        });

    t.detach();
}"""

# A6: the 600s queue-inclusive generation timeout let a whisper legally sit
# behind minutes of queued chatter; 60s is the T1/T3 tier value (the app
# emits the selected tier's value: T2 45, T4 30). Anchor is the unique
# timeout read in the pristine config block.
PB_LLM_TIMEOUT_UPSTREAM = """    llmGenerationTimeout = config.GetIntDefault("AiPlayerbot.LLMGenerationTimeout", 600);
"""
PB_LLM_TIMEOUT_ANDROID = """    // A6: queue-inclusive per-tier timeout; the app emits the tier's value
    llmGenerationTimeout = config.GetIntDefault("AiPlayerbot.LLMGenerationTimeout", 60);
    // S9/T4: the connect leg of the tier budget (10 s; the blocking default
    // hangs minutes on a dead external endpoint). Clamped 1-60: a zero or
    // negative hand value must not void the budget (select with tv_sec<=0
    // or a wrapped uint32 would hang or instantly-fail every connect).
    llmConnectTimeout = std::max(1u, std::min(60u, uint32(config.GetIntDefault("AiPlayerbot.LLMConnectTimeout", 10))));
"""
# S9/T4: bound the TCP connect. Non-blocking connect + select(), so a dead
# external endpoint fails inside the tier budget instead of hanging for the
# OS default; the socket returns to blocking mode for the send/recv legs.
PB_IFACE_CONNECT_UPSTREAM = """    if (connect(sock, res->ai_addr, res->ai_addrlen) < 0) {
        if (debug)
            debugLines.push_back("Connection to server failed");

#ifdef _WIN32
        sLog.outError("BotLLM: Connection to server failed. Error: %d", WSAGetLastError());
        closesocket(sock);
        WSACleanup();
#else
        sLog.outError("BotLLM: Connection to server failed. Error: %s", strerror(errno));
        close(sock);
#endif
        freeaddrinfo(res);
        sPlayerbotLLMInterface.generationCount--;
        return "error";
    }
"""
PB_IFACE_CONNECT_ANDROID = """    bool connected = false;
    int connectErr = 0;
    {
#ifdef _WIN32
        u_long nonBlocking = 1;
        ioctlsocket(sock, FIONBIO, &nonBlocking);
#else
        int const sockFlags = fcntl(sock, F_GETFL, 0);
        fcntl(sock, F_SETFL, sockFlags | O_NONBLOCK);
#endif
        int const connectRc = connect(sock, res->ai_addr, res->ai_addrlen);
        if (connectRc == 0)
            connected = true;
        else
        {
#ifdef _WIN32
            connectErr = WSAGetLastError();
            bool const connectPending = (connectErr == WSAEWOULDBLOCK);
#else
            connectErr = errno;
            bool const connectPending = (connectErr == EINPROGRESS);
#endif
            if (connectPending)
            {
                fd_set writeSet, errSet;
                FD_ZERO(&writeSet);
                FD_ZERO(&errSet);
                FD_SET(sock, &writeSet);
                FD_SET(sock, &errSet);
                struct timeval connectTv;
                connectTv.tv_sec = sPlayerbotAIConfig.llmConnectTimeout;
                connectTv.tv_usec = 0;
                int const selected = select(static_cast<int>(sock) + 1, nullptr, &writeSet, &errSet, &connectTv);
                if (selected > 0)
                {
                    int soError = 0;
                    // socklen_t (POSIX everywhere; the WIN32 debug lane is
                    // MinGW, which defines it through the ws2tcpip.h the
                    // file already includes - an int here fails bionic's
                    // socklen_t* prototype)
                    socklen_t soLen = sizeof(soError);
                    if (getsockopt(sock, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&soError), &soLen) == 0)
                        connected = (soError == 0);
                    if (!connected && soError != 0)
                        connectErr = soError;
                }
                else if (selected == 0)
                    connectErr = ETIMEDOUT;
                // selected < 0 (EINTR etc.) falls through as a connect
                // failure: fail-safe direction, recorded - a retry here is
                // not worth the interleaving surface
            }
        }
#ifdef _WIN32
        u_long blockingAgain = 0;
        ioctlsocket(sock, FIONBIO, &blockingAgain);
#else
        fcntl(sock, F_SETFL, sockFlags);
#endif
        // S9/T4 completeness: the generation budget must bound EVERY socket
        // leg, not just connect - the blocking TLS handshake (SSL_connect)
        // and the request write against a stalled peer would otherwise hang
        // the async generation thread past the budget and hold its
        // generation slot forever. Socket-level send/recv deadlines cover
        // both (the recv legs keep their own elapsed-deadline loops on top;
        // where the socket runs non-blocking these socket timeouts are
        // simply inert).
        if (connected && timeOutSeconds > 0)
        {
            struct timeval ioTv;
            ioTv.tv_sec = timeOutSeconds;
            ioTv.tv_usec = 0;
#ifdef _WIN32
            DWORD ioMs = static_cast<DWORD>(timeOutSeconds) * 1000;
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char const*>(&ioMs), sizeof(ioMs));
            setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<char const*>(&ioMs), sizeof(ioMs));
#else
            setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &ioTv, sizeof(ioTv));
            setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &ioTv, sizeof(ioTv));
#endif
        }
    }
    if (!connected) {
        if (debug)
            debugLines.push_back("Connection to server failed or timed out");

        // A8: a connect-phase ETIMEDOUT is the timeout class, everything
        // else keeps the bare error class (empty note)
        pocketllm::NoteGenClass(connectErr == ETIMEDOUT ? "timeout" : "");

#ifdef _WIN32
        sLog.outError("BotLLM: Connection to server failed. Error: %d", connectErr);
        closesocket(sock);
        WSACleanup();
#else
        sLog.outError("BotLLM: Connection to server failed. Error: %s", strerror(connectErr));
        close(sock);
#endif
        freeaddrinfo(res);
        sPlayerbotLLMInterface.generationCount--;
        return "error";
    }
"""
# Companion overlays applied after the stage-1 LLM overlays above.
# select() needs its own header on bionic (sys/socket.h does not
# pull it in).
PB_IFACE_SOCKINCLUDE_UPSTREAM = """#include <fcntl.h>
#include <errno.h>
"""
PB_IFACE_SOCKINCLUDE_ANDROID = """#include <fcntl.h>
#include <errno.h>
#include <sys/select.h>
"""
PB_IFACE_INCLUDE_UPSTREAM = """#include "PlayerbotTextMgr.h"
"""
PB_IFACE_INCLUDE_ANDROID = """#include "PlayerbotTextMgr.h"
#include "PlayerbotLlmTools.h"
#include "PlayerbotLlmJson.h"
#include "PlayerbotLlmTruthCore.h"
#include "PlayerbotLlmToolsCore.h"
#include "PlayerbotLlmFilters.h"
#include "PlayerbotLlmBridge.h"
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdlib>
#include <deque>
#include <map>
#include <mutex>
"""
PB_SAY_INCLUDE_UPSTREAM = """#include "playerbot/PlayerbotTextMgr.h"
#include "Chat/ChannelMgr.h"
"""
PB_SAY_INCLUDE_ANDROID = """#include "playerbot/PlayerbotTextMgr.h"
#include "playerbot/PlayerbotLlmFilters.h"
#include "playerbot/PlayerbotLlmMemory.h"
#include "playerbot/PlayerbotLlmGates.h"
// bridge: the pure gates take mirror values so the header stays
// host-compilable; an upstream ChatChannelSource renumber must fail the
// BUILD here, never misclassify a channel at runtime
static_assert((int)PlayerbotLlmGates::GATE_SRC_WHISPER == (int)SRC_WHISPER, "GateSrc bridge drifted");
static_assert((int)PlayerbotLlmGates::GATE_SRC_PARTY == (int)SRC_PARTY, "GateSrc bridge drifted");
static_assert((int)PlayerbotLlmGates::GATE_SRC_RAID == (int)SRC_RAID, "GateSrc bridge drifted");
static_assert((int)PlayerbotLlmGates::GATE_SRC_SAY == (int)SRC_SAY, "GateSrc bridge drifted");
static_assert((int)PlayerbotLlmGates::GATE_SRC_YELL == (int)SRC_YELL, "GateSrc bridge drifted");
static_assert((int)PlayerbotLlmGates::GATE_SRC_TRADE == (int)SRC_TRADE, "GateSrc bridge drifted");
static_assert((int)PlayerbotLlmGates::GATE_SRC_GENERAL == (int)SRC_GENERAL, "GateSrc bridge drifted");
static_assert((int)PlayerbotLlmGates::GATE_SRC_UNDEFINED == (int)SRC_UNDEFINED, "GateSrc bridge drifted")
#include "playerbot/PlayerbotLlmPersona.h"
#include "playerbot/PlayerbotLlmTools.h"
#include "playerbot/PlayerbotLlmToolsCore.h"
#include "playerbot/PlayerbotLlmBridge.h"
#include "playerbot/PlayerbotLlmChatter.h"
#include "playerbot/llm_banter_core.h"
#include "Chat/ChannelMgr.h"
#include <atomic>
"""
PB_SAY_CONTEXT_UPSTREAM = """        std::string llmContext = AI_VALUE(std::string, "manual string::llmcontext" + llmChannel);

        if (player)
        {
            std::string playerName = player->GetName();
"""
PB_SAY_CONTEXT_ANDROID = """        std::string llmContext = AI_VALUE(std::string, "manual string::llmcontext" + llmChannel);

        // S5/A1, S8: the PRE-STOMP pairing read - absence bucket AND the
        // trained tier - captured BEFORE the relationship touch queues
        // (the write is async, so a read after the queue push races it and
        // the first-meeting beat would double-fire or never fire). One
        // read, threaded to the sysm absence line, the bridge's beats and
        // the A16 tier ceremony.
        std::string llmAbsencePre;
        int llmTierPre = 1;
        // the event-turn drain flag + kind, hoisted beside the capture so
        // the request-builder block below can carry them into the trained
        // builder and the bridge
        bool const llmEventTurn = isEventTurn;
        uint32 const llmEventKind = eventKind;
        // A2 stamp threading: the license stamp of the note THIS turn's
        // builders record (BuildNote runs synchronously below), captured
        // at note time and carried into the generation so its emissions
        // are vetted against exactly that note - never the bot's live
        // license at completion time (round-1 P1: an interleaved newer
        // note or a note-less autonomous generation could otherwise be
        // adopted). Zero seals the turn: nothing queues.
        uint64_t llmLicenseStamp = 0;
        // A4: the interceptor-demotion plan. Cloud conversational turns
        // activate it (the closure owns deliver/record/award on the
        // worker); the device lane leaves it inactive, so every
        // interceptor stays preemptive and every award stays synchronous
        // - byte-identical device behavior.
        PlayerbotLlmGates::FallbackPlan llmFallback;
        bool const llmCloudTurn = CloudLaneOpen();

        if (player && player->isRealPlayer())
        {
            // E1 pacing law: acknowledge the whisper BEFORE the memory reads
            // and the generation queue - face the speaker, one deterministic
            // text emote, zero LLM cost, rate-capped per pairing (the world
            // thread runs here; the ack lands inside the same world update
            // that delivered the whisper, comfortably under the 1 s law).
            // Whispers only: say/party answers already carry the A18
            // persona-paced stagger.
            if (chatChannelSource == ChatChannelSource::SRC_WHISPER && !llmEventTurn)
                PlayerbotLlmMemory::AcknowledgeWhisper(bot, player);

            // M6: the memory layer serves BOTH backends now - the journal,
            // persona beats and the byte-stable context builder are pure
            // string/DB work, so an external HTTP endpoint gets the same
            // persistent memory the in-process backend always had.
            // bot-to-bot chatter still never pays the memory cost.
            PlayerbotLlmMemory::PreStompState const llmPre =
                PlayerbotLlmMemory::GetPreStompState(bot, player);
            llmAbsencePre = llmPre.absence;
            llmTierPre = llmPre.tier;

            // E4 visible progression: the standing one-liner rides the
            // FIRST whisper of a session (once per pairing per process,
            // system-colored, zero generation) - it frames the reply
            // before any path voices it. A first meeting skips (the E2
            // welcome owns that moment).
            if (chatChannelSource == ChatChannelSource::SRC_WHISPER && !llmEventTurn)
                PlayerbotLlmMemory::MaybeSessionStandingLine(bot, player, llmAbsencePre);

            // M6 greeting + S8/A13-A19: a bare "hi" from someone the bot
            // has not spoken with in hours or days gets the AUTHORED
            // relationship-tier greeting (the tier state IS the warmth,
            // and the welcome costs nothing) - now carrying the absence
            // MAGNITUDE (never a passive line) and what the town says
            // about the player. Falls through to the normal path for
            // short absences (generation handles those).
            if (sPlayerbotAIConfig.llmBanterEnabled &&
                chatChannelSource == ChatChannelSource::SRC_WHISPER &&
                !llmEventTurn &&
                PlayerbotLlmPersona::IsSimpleGreeting(msg))
            {
                if (llmAbsencePre == "most of a day" || llmAbsencePre == "many days")
                {
                    // A4: on the cloud lane the arrival greeting DEMOTES
                    // to the generation's failure-fallback - the model
                    // writes the arrival beat (tier/absence/town-talk all
                    // ride its context), and the authored greeting
                    // answers only a dead endpoint. The device lane keeps
                    // the preemptive authored greeting byte-identically.
                    if (llmCloudTurn)
                    {
                        llmFallback.active = true;
                        llmFallback.kind = PlayerbotLlmGates::FBK_GREET;
                        llmFallback.absence = llmAbsencePre;
                    }
                    else
                    {
                    std::string const greetLine =
                        PlayerbotLlmMemory::AuthoredArrivalGreeting(bot, player, llmAbsencePre);
                    if (!greetLine.empty())
                    {
                        bot->Whisper(greetLine, LANG_UNIVERSAL, player->GetObjectGuid());
                        PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(), player->GetGUIDLow(),
                            false, player->GetName(), msg);
                        PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(), player->GetGUIDLow(),
                            false, bot->GetName(), greetLine);
                        PlayerbotLlmMemory::AddRelationshipPoints(bot, player, 1);
                        return;
                    }
                    }
                }
            }

            // E2 first-contact onboarding: the player's FIRST-EVER bot
            // contact is scripted (authored welcome that hints bots
            // remember; the pairing's first-meeting fact forms inside
            // through the native write - a scripted voice cannot emit tool
            // markers). Whisper-class, never an event turn; empty falls
            // through to the normal first-meeting beat for every pairing
            // after the first.
            if (chatChannelSource == ChatChannelSource::SRC_WHISPER && !llmEventTurn &&
                llmAbsencePre == "a first meeting")
            {
                std::string const welcomeLine =
                    PlayerbotLlmMemory::AuthoredFirstContactWelcome(bot, player);
                if (!welcomeLine.empty())
                {
                    bot->Whisper(welcomeLine, LANG_UNIVERSAL, player->GetObjectGuid());
                    PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(), player->GetGUIDLow(),
                        false, player->GetName(), msg);
                    PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(), player->GetGUIDLow(),
                        false, bot->GetName(), welcomeLine);
                    PlayerbotLlmMemory::AddRelationshipPoints(bot, player, 1);
                    return;
                }
            }

            // M5 journal interception: the bot's fact rows as a readable
            // surface ("Bygdok's Journal"), zero generation cost
            std::string lowerMsg = boost::algorithm::to_lower_copy(msg);
            if (lowerMsg == "journal")
            {
                // the journal can run to several KB: it answers the
                // whisperer in the whisper channel only, paced line by line
                // like a diary being read out (LinesToPackets also enforces
                // the 255-char per-packet line limit) - one multi-KB SMSG
                // renders as a single unreadable wall of text in the chat
                // frame. Light MsPerChar: diary pace, not chat-typing pace.
                WorldPacket journalTemplate = GetPacketTemplate(CMSG_MESSAGECHAT, CHAT_MSG_WHISPER, bot, player);
                futurePackets futJournal = std::async(std::launch::async,
                    ChatReplyAction::LinesToPackets, PlayerbotLlmMemory::GetJournalLines(bot, player),
                    journalTemplate, false, 4, WorldPacket(), 0);
                ai->SendDelayedPacket(bot->GetSession(), std::move(futJournal));
                return;
            }

            // E4 keyword surfaces (whisper only; zero generation):
            // "standing" - the tier one-liner the journal header carries;
            // "gossip" - the town-talk rows the greeting surfaces render.
            // Deliberately NO relationship points: these are player-
            // initiated reads, not conversations (the journal awards none
            // either - round-1 R6: an uncapped +1 per keyword whisper was
            // a zero-cost tier-5 farm).
            if (chatChannelSource == ChatChannelSource::SRC_WHISPER && !llmEventTurn)
            {
                if (lowerMsg == "standing")
                {
                    std::string const standing = PlayerbotLlmMemory::StandingLine(bot, player);
                    if (!standing.empty())
                    {
                        bot->Whisper(standing, LANG_UNIVERSAL, player->GetObjectGuid());
                        return;
                    }
                }
                else if (lowerMsg == "gossip")
                {
                    std::vector<std::string> const gossipLines =
                        PlayerbotLlmMemory::GossipLines(bot, player);
                    if (!gossipLines.empty())
                    {
                        WorldPacket gossipTemplate = GetPacketTemplate(CMSG_MESSAGECHAT, CHAT_MSG_WHISPER, bot, player);
                        futurePackets futGossip = std::async(std::launch::async,
                            ChatReplyAction::LinesToPackets, gossipLines,
                            gossipTemplate, false, 4, WorldPacket(), 0);
                        ai->SendDelayedPacket(bot->GetSession(), std::move(futGossip));
                        return;
                    }
                    // nothing on the street: fall through and let the reply
                    // say so in voice (an empty authored surface is not a
                    // silent dead end)
                }
                // plan v5 W8: "/notice" - the player's own half of the
                // immersion. The same live-scene truth the bots see,
                // rendered second-person plus one authored nudge. Whisper
                // class, zero generation, no relationship points (the
                // keyword-read law above)
                else if (lowerMsg == "notice")
                {
                    std::vector<std::string> const sceneLines =
                        PlayerbotLlmMemory::SceneReadLines(bot, player);
                    if (!sceneLines.empty())
                    {
                        WorldPacket sceneTemplate = GetPacketTemplate(CMSG_MESSAGECHAT, CHAT_MSG_WHISPER, bot, player);
                        futurePackets futScene = std::async(std::launch::async,
                            ChatReplyAction::LinesToPackets, sceneLines,
                            sceneTemplate, false, 4, WorldPacket(), 0);
                        ai->SendDelayedPacket(bot->GetSession(), std::move(futScene));
                        return;
                    }
                }
                // plan v5 S.3: "story" - the codex read. The delivered
                // saga is the notification; this whisper is the
                // destination (re-readable, zero generation)
                else if (lowerMsg == "story")
                {
                    std::vector<std::string> const storyLines =
                        PlayerbotLlmMemory::StoryLines(bot, player);
                    if (!storyLines.empty())
                    {
                        WorldPacket storyTemplate = GetPacketTemplate(CMSG_MESSAGECHAT, CHAT_MSG_WHISPER, bot, player);
                        futurePackets futStory = std::async(std::launch::async,
                            ChatReplyAction::LinesToPackets, storyLines,
                            storyTemplate, false, 4, WorldPacket(), 0);
                        ai->SendDelayedPacket(bot->GetSession(), std::move(futStory));
                        return;
                    }
                }
            }

            // M5 persona fallback: known-hard improv categories get an
            // authored beat instead of freeform generation. Both the
            // triggering line and the beat are recorded under the same
            // key convention as the normal path, and the relationship
            // touch is kept. S8 fold (S5-logged): a FIRST MEETING never
            // routes here - the first-meeting log_fact beat must fire, so
            // the pairing's opening moment becomes memory.
            std::string personaLine;
            // A4: on the cloud lane the hard-category persona beat
            // DEMOTES to the generation's failure-fallback. Classify
            // ONLY - never TryFallback, which draws (advancing the
            // shared recency ring for a line that may never deliver;
            // the plan redraws at failure time). The device lane keeps
            // the preemptive draw byte-identically.
            PlayerbotLlmPersona::HardCategory const llmPersonaCategory =
                llmCloudTurn ? PlayerbotLlmPersona::Classify(msg)
                             : PlayerbotLlmPersona::CATEGORY_NONE;
            if (llmAbsencePre != "a first meeting" &&
                (llmCloudTurn
                    ? llmPersonaCategory != PlayerbotLlmPersona::CATEGORY_NONE
                    : PlayerbotLlmPersona::TryFallback(bot, msg,
                          chatChannelSource == ChatChannelSource::SRC_WHISPER, personaLine)))
            {
                if (llmCloudTurn)
                {
                    llmFallback.active = true;
                    llmFallback.kind = PlayerbotLlmGates::FBK_PERSONA;
                    llmFallback.personaCategory = (uint32)llmPersonaCategory;
                    llmFallback.whisper =
                        chatChannelSource == ChatChannelSource::SRC_WHISPER;
                }
                else
                {
                uint32 llmPersonaKey = chatChannelSource == ChatChannelSource::SRC_WHISPER
                    ? player->GetGUIDLow()
                    : (0x80000000u | static_cast<uint32>(chatChannelSource));
                PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(), llmPersonaKey,
                    chatChannelSource != ChatChannelSource::SRC_WHISPER, player->GetName(), msg);
                if (chatChannelSource == ChatChannelSource::SRC_WHISPER)
                    bot->Whisper(personaLine, LANG_UNIVERSAL, player->GetObjectGuid());
                else if (chatChannelSource == ChatChannelSource::SRC_RAID)
                    bot->GetPlayerbotAI()->SayToRaid(personaLine);
                else if (chatChannelSource == ChatChannelSource::SRC_SAY)
                    bot->Say(personaLine, LANG_UNIVERSAL);
                else
                    bot->GetPlayerbotAI()->SayToParty(personaLine);
                PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(), llmPersonaKey,
                    chatChannelSource != ChatChannelSource::SRC_WHISPER, bot->GetName(), personaLine);
                PlayerbotLlmMemory::AddRelationshipPoints(bot, player, 1);
                return;
                }
            }

            // A4: every cloud conversational turn activates the closure -
            // the +1 award moves off the synchronous pre-dispatch site
            // into the worker's exactly-once fold. Turns whose
            // interceptor demoted carry its kind; plain turns keep
            // FBK_NONE (a hard failure there stays silent: no authored
            // line exists for an arbitrary turn - the closure award
            // still applies).
            if (llmCloudTurn)
            {
                llmFallback.active = true;
                llmFallback.channel =
                    chatChannelSource == ChatChannelSource::SRC_WHISPER ? uint32(CHAT_MSG_WHISPER)
                    : chatChannelSource == ChatChannelSource::SRC_SAY ? uint32(CHAT_MSG_SAY)
                    : chatChannelSource == ChatChannelSource::SRC_RAID ? uint32(CHAT_MSG_RAID)
                    : uint32(CHAT_MSG_PARTY);
            }

            // M2: the byte-stable ordered segment builder replaces the ad-hoc
            // rolling string for the in-process backend (backstory -> tier ->
            // absence -> facts -> gossip -> rolling turns)
            // the absence bucket reads last_interaction_at, so the
            // relationship touch that stomps it happens only AFTER the
            // context is built. Whisper history is per (bot, player);
            // party/raid history is shared per (bot, channel) - including
            // the bot's own reply lines (recorded post-generation).
            uint32 llmTurnKey = chatChannelSource == ChatChannelSource::SRC_WHISPER
                ? player->GetGUIDLow()
                : (0x80000000u | static_cast<uint32>(chatChannelSource));
            // synthetic event prompts are recorded under an "(event)" pseudo
            // speaker so later prompts read as narration, never as the
            // player saying stage directions. The flag is the drain flag
            // threaded from the event site - never derived from msg text
            // (the former "(event) " prefix was player-forgeable).
            bool const eventTurn = llmEventTurn;
            std::string turnText = msg;
            if (eventTurn)
            {
                // the event drain appends a one-shot "say something!" cue to
                // make the bot answer; persisting it would replay a stale
                // imperative in every later prompt. It still reaches the
                // CURRENT generation through the <prompt> template, whose
                // <initial message> placeholder is filled from the untrimmed
                // msg.
                std::string const nudge = std::string(" ") + bot->GetName() + ", say something!";
                if (turnText.size() >= nudge.size() &&
                    turnText.compare(turnText.size() - nudge.size(), nudge.size(), nudge) == 0)
                    turnText.resize(turnText.size() - nudge.size());
            }
            PlayerbotLlmMemory::AppendTurn(bot->GetGUIDLow(), llmTurnKey,
                chatChannelSource != ChatChannelSource::SRC_WHISPER,
                eventTurn ? "(event)" : player->GetName(), turnText);
            llmContext = PlayerbotLlmMemory::BuildPromptContext(bot, player, (int)chatChannelSource, chanName);
            // A4: the cloud turn's +1 moved into the worker's
            // single-delivery closure (exactly once per delivered
            // outcome); the device lane keeps the synchronous pre-award
            // byte-identically
            if (!llmCloudTurn)
                PlayerbotLlmMemory::AddRelationshipPoints(bot, player, 1);
        }

        if (player)
        {
            std::string playerName = player->GetName();
"""
PB_SAY_PROMPT_V2_UPSTREAM = """                std::string json;

                if (useLlamaBackend)
                {
                    // raw completion prompt - the in-process backend takes
                    // plain text, no JSON envelope and no escaping
                    json = jsonFill["<pre prompt>"] + " " + jsonFill["<context>"] + " " + jsonFill["<prompt>"] + " " + jsonFill["<post prompt>"];
                }
"""
PB_SAY_PROMPT_V2_ANDROID = """                std::string json;

                // A4/A5: the trained prompt format builds the request
                // NATIVELY (messages array in the trained contract,
                // byte-diffed against the training renderer by the host
                // battery) - no conf-template surgery. Falls back to the
                // template paths below when LLMPromptFormat = 0 (hand-
                // configured servers) or the llama debug backend.
                if (sPlayerbotAIConfig.llmPromptFormat && !useLlamaBackend && player && player->isRealPlayer())
                {
                    uint32 const llmTrainedKey = (chatChannelSource == ChatChannelSource::SRC_WHISPER && player)
                        ? player->GetGUIDLow()
                        : (0x80000000u | static_cast<uint32>(chatChannelSource));
                    json = PlayerbotLlmMemory::BuildTrainedChatRequest(bot, player, msg, llmTrainedKey, llmAbsencePre, llmTierPre, llmEventTurn, llmEventKind, &llmLicenseStamp);
                }

                // A0: tool instructions ride BOTH backends (G-1: they were
                // llama-only, so the shipped HTTP config could never elicit
                // a tool call). The instruction text lands in the <pre
                // prompt> fill: raw concatenation for the in-process
                // backend, JSON-escaped into the system message by the
                // template path below. (Under the trained format the tools
                // note rides the system message already.)
                if (json.empty() && sPlayerbotAIConfig.llmToolsEnabled)
                    jsonFill["<pre prompt>"] += " " + PlayerbotLlmTools::ToolInstructions(bot->GetGUIDLow());

                // A1: the bridge's note rides the legacy paths too - ONE
                // bridge, every backend (the trained path bridges inside
                // BuildTrainedChatRequest). Event turns render through the
                // trained [EVENT] head + speak-first directive here as well.
                if (json.empty() && player && player->isRealPlayer())
                {
                    PlayerbotLlmBridge::TurnState llmTurn;
                    llmTurn.absence = llmAbsencePre;
                    llmTurn.tier = llmTierPre;
                    llmTurn.eventTurn = llmEventTurn;
                    llmTurn.eventKind = llmEventKind;
                    std::string const llmBridgeTurn =
                        PlayerbotLlmBridge::RenderLegacyTurn(bot, player, msg, llmTurn, &llmLicenseStamp);
                    if (!llmBridgeTurn.empty())
                        jsonFill["<post prompt>"] += llmBridgeTurn;
                }

                if (json.empty() && useLlamaBackend)
                {
                    // raw completion prompt - the in-process backend takes
                    // plain text, no JSON envelope and no escaping
                    json = jsonFill["<pre prompt>"] + " " + jsonFill["<context>"] + " " + jsonFill["<prompt>"] + " " + jsonFill["<post prompt>"];
                }
"""
# --- S8/A18: persona-paced say-reply staggering -------------------------------
# The LLM path queued with noDelay (instant) on every channel - instant
# uniform replies are uncanny in a crowd. Whispers stay instant (E1's
# <1s ack is S9 law); a name-mention /say answer staggers 2-5s; the
# random-bot path keeps its own legacy 10-20s roll (delaySecs -1).
PB_AI_QUEUE_DECL_UPSTREAM = """    void QueueChatResponse(uint32 msgType, ObjectGuid guid1, ObjectGuid guid2, std::string message, std::string chanName, std::string name, bool noDelay = false);
"""
PB_AI_QUEUE_DECL_ANDROID = """    void QueueChatResponse(uint32 msgType, ObjectGuid guid1, ObjectGuid guid2, std::string message, std::string chanName, std::string name, bool noDelay = false, int32 delaySecs = -1);
"""
# A2 (rp-depth v2.3): the fast-lane dialogue activity class. IN_DIALOGUE
# sits BEFORE NO_PATH/IN_*_MAP so a cross-map whisper or an inactive zone
# never throttles the interlocutor; the bracket entry makes the class
# always-active ({0,0}); ForceActivityRecheck lets the arming site stamp
# the 5 s AllowActivity cache hot so fast-lane uptake is immediate.
PB_AI_DIALOGUE_ENUM_UPSTREAM = """    PLAYER_FRIEND,
    PLAYER_GUILD,
    NO_PATH,
"""
PB_AI_DIALOGUE_ENUM_ANDROID = """    PLAYER_FRIEND,
    PLAYER_GUILD,
    IN_DIALOGUE,
    NO_PATH,
"""
PB_AI_DIALOGUE_RECHECK_UPSTREAM = """    bool AllowActivity(ActivityType activityType = ALL_ACTIVITY, bool checkNow = false);
"""
PB_AI_DIALOGUE_RECHECK_ANDROID = """    bool AllowActivity(ActivityType activityType = ALL_ACTIVITY, bool checkNow = false);
    // A2: drop the 5 s AllowActivity cache so the next check re-derives
    // the priority (called by the fast-lane arming site - uptake must
    // not lag the cache window)
    void ForceActivityRecheck()
    {
        for (uint8 i = 0; i < MAX_ACTIVITY_TYPE; ++i)
            allowActiveCheckTimer[i] = 0;
    }
"""
# A2: the priority early-return - placed after the real-player/master
# checks (HAS_REAL_PLAYER_MASTER/IS_REAL_PLAYER/IN_GROUP_WITH_REAL_PLAYER
# all still classify first) and BEFORE the bg/test/instance/zone ladder,
# so a dialogue window outranks NO_PATH/IN_INACTIVE_MAP/IN_ACTIVE_MAP.
PB_AI_PRIORITY_DIALOGUE_UPSTREAM = """            if (!member->GetPlayerbotAI() || (member->GetPlayerbotAI() && member->GetPlayerbotAI()->HasRealPlayerMaster()))
                return ActivePiorityType::IN_GROUP_WITH_REAL_PLAYER;
        }
    }

    if (bot->IsBeingTeleported()) //We might end up in a bg so stay active.
"""
PB_AI_PRIORITY_DIALOGUE_ANDROID = """            if (!member->GetPlayerbotAI() || (member->GetPlayerbotAI() && member->GetPlayerbotAI()->HasRealPlayerMaster()))
                return ActivePiorityType::IN_GROUP_WITH_REAL_PLAYER;
        }
    }

    // A2 fast-lane: a bot inside an armed dialogue window is always
    // active for its duration (300 s, re-armed on every real-player
    // turn) - the conversation's cadence must not ride the activity
    // lottery. Before NO_PATH/IN_*_MAP by construction (cross-map
    // whispers, inactive zones).
    if (PlayerbotLlmMemory::DialogueActive(bot->GetGUIDLow()))
        return ActivePiorityType::IN_DIALOGUE;

    if (bot->IsBeingTeleported()) //We might end up in a bg so stay active.
"""
# A2: the bracket entry - IN_DIALOGUE joins the always-active {0,0}
# group alongside the real-player/master classes.
PB_AI_BRACKET_DIALOGUE_UPSTREAM = """    case ActivePiorityType::VISIBLE_FOR_PLAYER:
    case ActivePiorityType::IN_BATTLEGROUND:
    case ActivePiorityType::IS_RUNNING_TEST:
        return { 0,0 };
"""
PB_AI_BRACKET_DIALOGUE_ANDROID = """    case ActivePiorityType::VISIBLE_FOR_PLAYER:
    case ActivePiorityType::IN_BATTLEGROUND:
    case ActivePiorityType::IS_RUNNING_TEST:
    case ActivePiorityType::IN_DIALOGUE:
        return { 0,0 };
"""
PB_AI_QUEUE_DEF_UPSTREAM = """void PlayerbotAI::QueueChatResponse(uint32 msgType, ObjectGuid guid1, ObjectGuid guid2, std::string message, std::string chanName, std::string name, bool noDelay)
{
    std::scoped_lock lock(chatRepliesMutex);
    chatReplies.push(ChatQueuedReply(msgType, guid1.GetCounter(), guid2.GetCounter(), message, chanName, name, time(0) + (noDelay ? 0 : urand(inCombat ? 15 : 10, inCombat ? 30 : 20))));
}
"""
PB_AI_QUEUE_DEF_ANDROID = """void PlayerbotAI::QueueChatResponse(uint32 msgType, ObjectGuid guid1, ObjectGuid guid2, std::string message, std::string chanName, std::string name, bool noDelay, int32 delaySecs)
{
    std::scoped_lock lock(chatRepliesMutex);
    chatReplies.push(ChatQueuedReply(msgType, guid1.GetCounter(), guid2.GetCounter(), message, chanName, name, time(0) + (noDelay ? 0 : (delaySecs >= 0 ? delaySecs : urand(inCombat ? 15 : 10, inCombat ? 30 : 20)))));
}
"""
PB_AI_QUEUE_CALL_UPSTREAM = """                MANGOS_ASSERT(!message.empty());     
                QueueChatResponse(msgtype, guid1, ObjectGuid(), message, chanName, name, isAiChat);
"""
PB_AI_QUEUE_CALL_ANDROID = """                MANGOS_ASSERT(!message.empty());
                // S8/A18 pacing: the LLM path answers whispers/part/raid
                // the moment generation finishes (noDelay), but a /say
                // ANSWER (a name mention - the only say that generates)
                // staggers 2-5s so a street scene reads as people, not a
                // chorus. A NON-mention say passes undelayed: it never
                // reaches a generation (the hard-trigger gate refuses
                // it), and the crowd tier's own 2-5s notBefore is the
                // whole pacing that class needs (round-1 R5: stacking
                // both delays made crowd emotes land 4-10s late). The
                // random-bot path keeps the legacy roll.
                int32 llmSayStagger = -1;
                bool llmSayNoDelay = isAiChat;
                if (isAiChat && !isMentioned &&
                    GetChatChannelSource(bot, msgtype, chanName) == ChatChannelSource::SRC_SAY)
                    llmSayNoDelay = true;
                else if (isAiChat && isMentioned &&
                    GetChatChannelSource(bot, msgtype, chanName) == ChatChannelSource::SRC_SAY)
                {
                    llmSayStagger = int32(urand(2, 5));
                    llmSayNoDelay = false;
                }
                QueueChatResponse(msgtype, guid1, ObjectGuid(), message, chanName, name, llmSayNoDelay, llmSayStagger);
"""
PB_UPDATEAI_UPSTREAM = """void PlayerbotAI::UpdateAI(uint32 elapsed, bool minimal)
{
    AiObjectContext* context = aiObjectContext;
"""
PB_UPDATEAI_ANDROID = """void PlayerbotAI::UpdateAI(uint32 elapsed, bool minimal)
{
    // world-thread executor for LLM tool side-effects (validated against
    // live state at execution time) and the M3/M6 event reactions
    if (bot->IsInWorld() && !minimal)
    {
        PlayerbotLlmTools::ExecutePending(bot);

        if (sPlayerbotAIConfig.llmEnabled)
        {
            PlayerbotLlmMemory::EventReaction reaction;
            if (PlayerbotLlmMemory::DrainEventReaction(bot->GetGUIDLow(), reaction))
            {
                if (reaction.authored && reaction.emote)
                {
                    // S8/A18 crowd tier: the reaction's text is an emote
                    // name - the deterministic text-emote path (the
                    // licensed perform_emote executor's own delivery).
                    Player* emoteTarget = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, reaction.playerGuid));
                    PlayerbotLlmTools::PlayTextEmote(bot, emoteTarget, reaction.text);
                }
                else if (reaction.authored)
                {
                    // M6 authored banter (kill quips): the text is final -
                    // rendered and tic-seasoned at queue time. No generation
                    // is behind it, so it just speaks on the party channel.
                    // S8: a SAY-tagged authored reaction (the bot2bot reply,
                    // which answers on the channel the bystander heard the
                    // opener on) speaks on /say even when grouped.
                    // A4: a WHISPER-tagged authored reaction (the
                    // conversational failure-fallback) whispers - a private
                    // answer must never land on /say.
                    if (reaction.msgtype == CHAT_MSG_WHISPER)
                    {
                        Player* whisperTarget = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, reaction.playerGuid));
                        if (whisperTarget)
                            bot->Whisper(reaction.text, LANG_UNIVERSAL, whisperTarget->GetObjectGuid());
                    }
                    else if (reaction.msgtype == CHAT_MSG_SAY)
                        bot->Say(reaction.text, LANG_UNIVERSAL);
                    else if (bot->GetGroup())
                        bot->GetPlayerbotAI()->SayToParty(reaction.text);
                    else
                        bot->Say(reaction.text, LANG_UNIVERSAL);
                }
                else
                {
                    Player* eventPlayer = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, reaction.playerGuid));
                    if (eventPlayer)
                        ChatReplyAction::ChatReplyDo(bot,
                            reaction.msgtype ? reaction.msgtype : uint32(CHAT_MSG_PARTY),
                            reaction.playerGuid, 0,
                            reaction.text + " " + bot->GetName() + ", say something!", "",
                            eventPlayer->GetName(), /*isEventTurn=*/true, reaction.eventKind);
                }
            }

            // M6 rare ambient banter: authored idle/mood lines while
            // adventuring with the master. All gating (banter flag, combat,
            // proximity, 90s cadence, 1-in-16 roll, shared 15-min ambient
            // slot) lives inside the call; it is a no-op when any gate fails.
            PlayerbotLlmPersona::MaybeAmbientLine(bot);

            // S8/A17 initiative scheduler: the authored speak-first layer
            // (arrival greet-first, debt reminders, tier-gated ask-afters,
            // the rare bot2bot exchange). Every gate lives inside the call
            // (banter flag, combat, the 10-min zero-spam cap); no-op when
            // any fails.
            PlayerbotLlmMemory::TickInitiative(bot);

            // keep the active-party slots warm: KV cache is cheap relative
            // to weights on E2B, so nobody in the party is ever cold. Prefill
            // only (no sampled tokens, no governor budget spent), in-process
            // backend only, in the exact prompt shape a real party turn uses
            // so the warmed prefix is actually reusable.
            if (sPlayerbotAIConfig.llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA &&
                PlayerbotLlmMemory::PrewarmDue(bot->GetGUIDLow()))
            {
                if (Player* master = bot->GetPlayerbotAI()->GetMaster())
                {
                    // byte-for-byte the pre-prompt of a real party turn:
                    // full placeholder set, the bot's saved custom prompt,
                    // then the tool instructions - so the warmed KV prefix
                    // actually matches the next real generation
                    std::map<std::string, std::string> prewarmPlaceholders;
                    ChatReplyAction::GetAIChatPlaceholders(prewarmPlaceholders, bot, master);
                    ChatReplyAction::GetAIChatPlaceholders(prewarmPlaceholders, bot, "bot");
                    ChatReplyAction::GetAIChatPlaceholders(prewarmPlaceholders, master, "other");
                    prewarmPlaceholders["<channel name>"] = "in party chat";
                    std::string llmPromptCustom = aiObjectContext->GetValue<std::string>("manual saved string::llmdefaultprompt")
                        ? aiObjectContext->GetValue<std::string>("manual saved string::llmdefaultprompt")->Get()
                        : std::string();
                    std::string prePrompt = BOT_TEXT2(sPlayerbotAIConfig.llmPrePrompt + " " + llmPromptCustom, prewarmPlaceholders);
                    if (sPlayerbotAIConfig.llmToolsEnabled)
                        prePrompt += " " + PlayerbotLlmTools::ToolInstructions(bot->GetGUIDLow());
                    PlayerbotLlamaRuntime::PreWarmSlot(bot->GetGUIDLow(),
                        prePrompt + " " + PlayerbotLlmMemory::BuildPromptContext(bot, master, (int)ChatChannelSource::SRC_PARTY, ""));
                }
            }
        }
    }

    AiObjectContext* context = aiObjectContext;
"""
PB_RPG_MANUAL_GET_UPSTREAM = """GAI_VALUE2(std::string, "global string", "llmcontext manual" + std::to_string(target.GetCounter()))"""
PB_RPG_MANUAL_GET_ANDROID = """AI_VALUE(std::string, "manual string::llmcontext manual" + std::to_string(target.GetCounter()))"""
PB_RPG_MANUAL_SET_UPSTREAM = """SET_GAI_VALUE2(std::string, "global string", "llmcontext manual" + std::to_string(target.GetCounter()), llmContext)"""
PB_RPG_MANUAL_SET_ANDROID = """SET_AI_VALUE(std::string, "manual string::llmcontext manual" + std::to_string(target.GetCounter()), llmContext)"""
PB_LLM_CONF_UPSTREAM = """# Time in seconds the server will wait for the generation to finish. This includes waiting in queue.
# AiPlayerbot.LLMGenerationTimeout = 600
"""
PB_LLM_CONF_ANDROID = """# Time in seconds the server will wait for the generation to finish. This includes waiting in queue.
# A6: 60 is the T1/T3 tier default (the app emits the selected tier's
# value: T2 45, T4 60 since the plan-v4 API-tier retune).
# AiPlayerbot.LLMGenerationTimeout = 60
# S9/T4: bounded TCP connect for the endpoint (seconds). A dead external
# endpoint fails inside this budget instead of hanging for the OS default;
# loopback embedded connects are instant either way.
# AiPlayerbot.LLMConnectTimeout = 10
# In-process llama.cpp backend (arm64 devices only): 0 = http endpoint, 1 = embedded runtime.
# AiPlayerbot.LLMBackend = 0
# Trained prompt format (A4/A5): 1 = the native messages builder speaks the
# training contract (system = identity/TOOLS_NOTE/bible/backstory/tier/
# absence/facts; user = [Memories]/[State] tail; role-separated history);
# 0 = the legacy LLMApiJson template fill below. Requires a
# /v1/chat/completions endpoint.
# AiPlayerbot.LLMPromptFormat = 0
# Request model name for the native builder (the app emits its tier's).
# AiPlayerbot.LLMApiModel = local
# 1 = emit chat_template_kwargs {"enable_thinking": false} for model
# families whose export template defaults to thinking (qwen; SS4.4).
# AiPlayerbot.LLMThinkingKwargs = 0
# 1 = strip llama.cpp-only body fields (top_k/repeat_penalty/min_p/
# presence_penalty) for external endpoints that reject unknown keys.
# AiPlayerbot.LLMProviderSafe = 0
# Memory depth (SS2.1): system-segment facts cap + [Memories] tail size.
# AiPlayerbot.LLMFactsCap = 12
# AiPlayerbot.LLMMemoriesTail = 6
# Prompt-dump hook (M1a): one JSON line per trained-format generation is
# appended to this file (device-side byte-diff verification; dev-only -
# grows unbounded).
# AiPlayerbot.LLMPromptDumpFile =
# S7/A11: the lore card index (jsonl, one card per line: title/text/
# keys/poi) built from the era-scrubbed vanilla corpus by
# tools/llm_lab/build_lore_cards.py. Empty = the lore retrieval loop is
# off (question turns get no [RESULT] cards and move_to stays refused);
# the entity guard still works without it.
# AiPlayerbot.LLMLoreFile =
# S7/A11: 1 = bias the always-ban era terms (shattrath, draenei,
# pandaren, acherus) by resolving their token ids once through the
# embedded server's /tokenize endpoint (-50 both token cases). Fails
# open when /tokenize is unavailable; external (providerSafe) endpoints
# never receive the key.
# AiPlayerbot.LLMEraBias = 1
# Extra sampling knobs for the trained builder AND the in-process backend
# (the legacy template carries its own copy of all sampling values):
# AiPlayerbot.LLMMinP = 0.0
# AiPlayerbot.LLMPresencePenalty = 0.0
# Absolute path of the GGUF model for the embedded runtime.
# AiPlayerbot.LLMModelPath =
# Threads pinned to the mid cores (first core + N consecutive cores).
# AiPlayerbot.LLMThreads = 3
# AiPlayerbot.LLMCpuFirstCore = 3
# Context size and warm slots per party.
# AiPlayerbot.LLMCtxSize = 4096
# AiPlayerbot.LLMSlots = 4
# Sampling: temp / top-k / top-p / repeat penalty.
# AiPlayerbot.LLMTemp = 0.8
# AiPlayerbot.LLMTopK = 64
# AiPlayerbot.LLMTopP = 0.95
# AiPlayerbot.LLMRepeatPenalty = 1.1
# New tokens per reply (A6 hand-configured fallback 200; the app emits
# the tier's value: 230 T1, 210 T2/T3, 600 T4 - the plan-v4 API-tier
# headroom). At >= 225
# the long-form cue arms (the P50 bank's licensed tellings); a hand
# config that raises this while leaving a hand-written LLMApiJson at a
# lower max_tokens would arm the cue and truncate the telling - keep the
# two in lockstep.
# AiPlayerbot.LLMMaxNewTokens = 200
# Duty-cycle governor: max generations per bot / globally inside the window.
# AiPlayerbot.LLMGovernorWindow = 60
# AiPlayerbot.LLMGovernorBotMax = 8
# AiPlayerbot.LLMGovernorGlobalMax = 24
# Busy placeholder shown when the governor trips on a player-facing path. A
# closing statement, not a promise - nothing is ever scheduled afterwards.
# AiPlayerbot.LLMBusyReply = Hm. I will be thinking on that a while.
# Tool-calling (log_fact / adjust_sentiment / share_gossip / perform_emote).
# AiPlayerbot.LLMToolsEnabled = 1
# Authored banter layer (rare kill quips, tier greetings, idle/mood lines) -
# costs nothing, no model calls. 0 = bots only ever speak in reply.
# AiPlayerbot.LLMBanterEnabled = 1
# Phase-1/2 prompt pack + RP dials (the app stages these when configured):
# LLMPromptPackFile points at the staged pack JSON; LLMPromptBlock.<id>
# forces one of the 10 seasoning blocks on/off (0/1, per-preset deltas);
# the LLMRp* dials are 0-100 with 50 = default (Initiative scales the
# 10-min opener floor 1200s..300s, Volatility the mood-weather weight,
# Reactivity event-shortcut eagerness, LongForm the long-telling
# license bar 300/225/150 tokens).
# AiPlayerbot.LLMPromptPackFile =
# AiPlayerbot.LLMRpInitiative = 50
# AiPlayerbot.LLMRpVolatility = 50
# AiPlayerbot.LLMRpReactivity = 50
# AiPlayerbot.LLMRpLongForm = 50
# Plan-v5 authored engagement layer: event reactions (death condolence,
# wipe aftermath + the town row, debt settlement) and the grudge
# act-refusal are zero-generation authored beats; the authored-line
# hourly ceiling bounds their SUM with the murmur lane (murmur shares
# the ambient 3/hr sub-cap, features get 2/hr, party/global set pieces
# count toward the global ceiling only). 0 on the ceiling disables
# authored ambient entirely - the guaranteed first beats (the condolence
# over a body, the first shaken line after a wipe) stay exempt.
# AiPlayerbot.LLMEventReactionsEnabled = 1
# AiPlayerbot.LLMGrudgeRefusalEnabled = 1
# AiPlayerbot.LLMAuthoredLinesPerHour = 8
# The rest of the plan-v5 engagement layer (all default-on, fail-open):
# LLMWorldTruthAmbient=1 (weather/hour ambient bias), LLMWorldTruthFurniture=0
# (scene/homeland bridge furniture, OFF pending the bake-off),
# LLMSceneReadEnabled=1 (/notice), LLMCuriosityEnabled=1 (bot questions),
# LLMDramaEnabled=1 (authored two-bot set pieces),
# LLMRecapEnabled=1/LLMRecapProse=1/LLMRecapProsePerDay=6 (session recap),
# LLMSagaEnabled=1/LLMSagaPerDay=3 (campfire saga, cloud tier),
# LLMRoundtablePerDay=30 (party-line composer rows),
# LLMDossierEnabled=1/LLMDossierPerDay=1 (weekly dossier).
# S8: the authored INITIATIVE layer rides the same switch - greet-first on
# a remembered player's return, debt reminders, tier-gated ask-afters, the
# rare bot2bot exchange when a player walks up, and the crowd tier's
# deterministic emotes on ambient /say (never a generation; 1 line per bot
# per 10 min - the zero-spam cap). 0 keeps bots reply-only.
# World chatter (the LLM-voiced ambient layers): party banter,
# proximity murmur, rare general-chat set pieces, all event-gated (silence
# is the default - no fact-bank row, no line). The app emits these
# whenever it stages the power file; the FILE's enabled flag is the
# master switch (re-read every tick, so the ambience toggle works
# mid-session in both directions) and carries the live power-ladder rung
# the app refreshes (missing/stale = chatter stops or degrades to the
# authored floor). The composer endpoint is the optional cloud script
# generator (empty = device single-line batches).
# AiPlayerbot.LLMChatterEnabled = 0
# AiPlayerbot.LLMChatterPowerFile =
# AiPlayerbot.LLMChatterComposerUrl =
# AiPlayerbot.LLMChatterComposerModel = local
# AiPlayerbot.LLMChatterComposerKey =
# G3: TLS verification for the external HTTPS endpoint. 1 (default) =
# verify the server certificate against the staged CA bundle (fallback:
# the Android system store) and pin the hostname; TLS 1.2 floor. 0
# restores the unverified handshake for self-signed LAN endpoints -
# http:// endpoints are unaffected either way (the Bearer key already
# rides those in cleartext; the app's normalizer warns about them).
# AiPlayerbot.LLMTLSVerify = 1
# The CA bundle the app stages next to the conf (absolute path; empty =
# system store fallback).
# AiPlayerbot.LLMTLSCaFile =
# WS-A cloud conversation lane. LLMCloudChatter masters every cloud
# widening as CloudLaneOpen() = LLMCloudChatter && external tier active;
# the device lane never sees the widenings regardless of this key.
# LLMCloudStreetSayPct: % of admitted crowd reactions that may speak
# (rest emote only). The *PerDay quotas count GENERATIONS per UTC day,
# realm-global, process-local (reset on realm restart). The two budgets:
# ambient lines per hour (realm-global) and interactive replies per
# player per hour. LLMDialogueFastLane arms the in-dialogue activity
# fast-lane (A2). 0 disables the named behavior.
# AiPlayerbot.LLMCloudChatter = 1
# AiPlayerbot.LLMPartyReplyEnabled = 0
# AiPlayerbot.LLMCloudStreetSayPct = 25
# AiPlayerbot.LLMStreetSayPerDay = 200
# AiPlayerbot.LLMRpgChatPerDay = 300
# AiPlayerbot.LLMBotToBotPerDay = 300
# AiPlayerbot.LLMCloudLineBudgetPerHour = 90
# AiPlayerbot.LLMCloudInteractivePerPlayerHour = 240
# AiPlayerbot.LLMDialogueFastLane = 1
"""
# G3 part 1: the SSL_CTX setup. Upstream only disabled SSLv2/v3; the
# cloud lane gets a TLS 1.2 floor, real peer verification (a staged CA
# bundle, falling back to the Android system hashed-dir store - there is
# no /etc/ssl/certs for native code on Android, so bare SSL_VERIFY_PEER
# would fail every handshake), all behind the LLMTLSVerify kill-switch.
PB_IFACE_TLSCTX_UPSTREAM = """        SSL_CTX_set_options(ctx, SSL_OP_NO_SSLv2 | SSL_OP_NO_SSLv3);
        SSL_CTX_set_mode(ctx, SSL_MODE_AUTO_RETRY);
"""
PB_IFACE_TLSCTX_ANDROID = """        SSL_CTX_set_options(ctx, SSL_OP_NO_SSLv2 | SSL_OP_NO_SSLv3);
        SSL_CTX_set_mode(ctx, SSL_MODE_AUTO_RETRY);
        // G3: TLS 1.2 floor + verified chain. The CA material comes from
        // the app-staged Mozilla bundle (LLMTLSCaFile absolute path);
        // an empty/unloadable bundle falls back to the system store.
        // LLMTLSVerify = 0 keeps today's handshake byte-for-byte for
        // self-signed LAN endpoints.
        SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION);
        if (sPlayerbotAIConfig.llmTlsVerify)
        {
            SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, nullptr);
            bool caLoaded = false;
            if (!sPlayerbotAIConfig.llmTlsCaFile.empty())
                caLoaded = SSL_CTX_load_verify_locations(
                    ctx, sPlayerbotAIConfig.llmTlsCaFile.c_str(), nullptr) == 1;
            if (!caLoaded)
                SSL_CTX_load_verify_locations(ctx, nullptr, "/system/etc/security/cacerts");
        }
"""
# G3 part 2: hostname pin. Upstream sets only SNI; chain-only
# verification still accepts ANY valid certificate for any name, so the
# hostname is pinned into the handshake whenever verification is on.
# NOTE: the middle line of the upstream span carries trailing spaces
# ("        \n"), so this constant is byte-exact rather than a tidy
# triple-quoted block - a tidied copy would drift the anchor.
PB_IFACE_TLSHOST_UPSTREAM = "        SSL_set_tlsext_host_name(ssl, parsedUrl.hostname.c_str());\n        \n        SSL_set_fd(ssl, sock);\n"
PB_IFACE_TLSHOST_ANDROID = """        SSL_set_tlsext_host_name(ssl, parsedUrl.hostname.c_str());
        // G3: match the hostname inside the verified handshake (SNI alone
        // is not validation; without this a valid cert for any name passes)
        if (sPlayerbotAIConfig.llmTlsVerify)
            SSL_set1_host(ssl, parsedUrl.hostname.c_str());

        SSL_set_fd(ssl, sock);
"""
# 0.c.1 redaction: the debug echo carried the full request INCLUDING the
# Authorization: Bearer line and body to whichever player ran `debug llm`
# (reachable at SEC_PLAYER through .bot debug). The wire request keeps
# its key; only the echoed copy is redacted.
# NOTE: like PB_IFACE_TLSHOST_UPSTREAM, the blank line inside this span
# carries trailing spaces in the pristine file - byte-exact literal.
PB_IFACE_REQECHO_UPSTREAM = "    std::string requestStr = request.str();\n    \n    if (debug)\n        debugLines.push_back(\"Send the request: \" + requestStr);\n"
PB_IFACE_REQECHO_ANDROID = """    std::string requestStr = request.str();

    if (debug)
    {
        // 0.c.1: debugLines are echoed to the requesting player - the
        // Authorization value never rides them, under any debug path
        std::string echo = requestStr;
        size_t const auth = echo.find("Authorization: Bearer ");
        if (auth != std::string::npos)
        {
            size_t const eol = echo.find("\\r\\n", auth);
            echo.replace(auth,
                (eol == std::string::npos ? echo.size() : eol) - auth,
                "Authorization: Bearer [redacted]");
        }
        debugLines.push_back("Send the request: " + echo);
    }
"""
# 0.c.3 port-parse crash: parseUrl's std::stoi throws out_of_range for
# huge port literals and only invalid_argument was caught - the world
# failed to boot at config load. Widen to std::exception and fail closed
# (empty endpoint = an immediately-dead client), never a silent default.
PB_LLM_EP_CATCH_UPSTREAM = """    try {
        llmEndPointUrl = parseUrl(llmApiEndpoint);
    }
    catch (const std::invalid_argument& e) {
        sLog.outError("Unable to parse LLMApiEndpoint url: %s", e.what());
    }
"""
PB_LLM_EP_CATCH_ANDROID = """    try {
        llmEndPointUrl = parseUrl(llmApiEndpoint);
    }
    catch (const std::exception& e) {
        // 0.c.3: std::stoi inside parseUrl also throws out_of_range (a
        // port literal beyond int range); the old single-class catch let
        // that abort world boot. Fail closed: log, leave the endpoint
        // empty so the HTTP client refuses it instantly (dead endpoint),
        // never silently fall back to a default port.
        sLog.outError("Unable to parse LLMApiEndpoint url: %s", e.what());
        llmEndPointUrl = ParsedUrl();
    }
"""
# A7.4 concurrency off-by-one + the A8 cap class: the old check admitted
# maxGenerations + 1 in-flight generations, and a cap rejection was
# indistinguishable from any other empty return.
PB_IFACE_CONCUR_UPSTREAM = """    if (sPlayerbotLLMInterface.generationCount > maxGenerations)
    {
        if (debug)
            debugLines.push_back("Maximum generations reached " + std::to_string(sPlayerbotLLMInterface.generationCount) + "/" + std::to_string(maxGenerations));
        return {};
    }
"""
PB_IFACE_CONCUR_ANDROID = """    if (sPlayerbotLLMInterface.generationCount >= maxGenerations)
    {
        // A7.4: >= - the old > admitted max+1 concurrent generations.
        // A8: the cap class is its own failure class (never "busy" -
        // the busy line is governor duty-cycle; a cap hit must fall to
        // the A4 authored fallback, not the busy persona line).
        pocketllm::NoteGenClass("cap");
        if (debug)
            debugLines.push_back("Maximum generations reached " + std::to_string(sPlayerbotLLMInterface.generationCount) + "/" + std::to_string(maxGenerations));
        return {};
    }
"""
# S10: the endpoint/key override legs inside GenerateHttp - the composer
# path POSTs to its own endpoint through the same hardened client. Both
# anchors patch pristine lines the driver never touched before.
PB_IFACE_EP_URL_UPSTREAM = """    ParsedUrl parsedUrl = sPlayerbotAIConfig.llmEndPointUrl;
"""
PB_IFACE_EP_URL_ANDROID = """    ParsedUrl parsedUrl = endpointOverride ? *endpointOverride : sPlayerbotAIConfig.llmEndPointUrl;
"""
PB_IFACE_EP_KEY_UPSTREAM = """    if (!sPlayerbotAIConfig.llmApiKey.empty())
        request << "Authorization: Bearer " << sPlayerbotAIConfig.llmApiKey << "\\r\\n";
"""
PB_IFACE_EP_KEY_ANDROID = """    std::string const& chatApiKey = apiKeyOverride ? *apiKeyOverride : sPlayerbotAIConfig.llmApiKey;
    if (!chatApiKey.empty())
        request << "Authorization: Bearer " << chatApiKey << "\\r\\n";
"""
# core hooks for the LLM companion event allowlist
# the SendPacket line makes the anchor unique: PLAYER_NEXT_LEVEL_XP is also
# set in InitStatsForLevel (login-time stat init) and only GiveLevel precedes
# it with the packet send - first-match-by-file-order alone hooked the right
# function only by accident of layout
CORE_GIVELEVEL_UPSTREAM = """    GetSession()->SendPacket(data);

    SetUInt32Value(PLAYER_NEXT_LEVEL_XP, sObjectMgr.GetXPForLevel(level));
"""
CORE_GIVELEVEL_ANDROID = """    GetSession()->SendPacket(data);

    SetUInt32Value(PLAYER_NEXT_LEVEL_XP, sObjectMgr.GetXPForLevel(level));

#ifdef ENABLE_PLAYERBOTS
    // LLM companion event allowlist: level-up of any group member
    PlayerbotLlmMemory::OnPlayerLevelUp(this, level);
#endif
"""
# The duel-outcome hook. The anchor sits before the duel state is
# deleted (duel->opponent still live); this one function covers all nine
# outcome call sites - Unit.cpp's damage win calls DuelComplete on the
# LOSER with DUEL_WON, the flee/interrupt sites on the fleeing/leaving
# player - so a single hook sees every outcome.
CORE_DUELCOMPLETE_UPSTREAM = """    // restore health/mana view for friendly player
    ForceHealthAndPowerUpdate();
    duel->opponent->ForceHealthAndPowerUpdate();
"""
CORE_DUELCOMPLETE_ANDROID = """    // restore health/mana view for friendly player
    ForceHealthAndPowerUpdate();
    duel->opponent->ForceHealthAndPowerUpdate();

#ifdef ENABLE_PLAYERBOTS
    PlayerbotLlmMemory::OnDuelComplete(this, duel->opponent, type);
#endif
"""
# The one-time first-contact onboarding line rides login (gating +
# the once-per-character no-pairing-yet check live in the memory layer;
# the world conf is only LLM-armed when the app started the runtime
# before the world - the supervisor's measured ordering). The two
# "must be after add to map" comment lines make the tail of
# SendInitialPacketsAfterAddToMap unique.
CORE_LOGIN_ONBOARDING_UPSTREAM = """    SendEnchantmentDurations();                             // must be after add to map
    SendItemDurations();                                    // must be after add to map
}
"""
CORE_LOGIN_ONBOARDING_ANDROID = """    SendEnchantmentDurations();                             // must be after add to map
    SendItemDurations();                                    // must be after add to map

#ifdef ENABLE_PLAYERBOTS
    PlayerbotLlmMemory::OnPlayerLogin(this);
#endif
}
"""
# S6: ChatReplyDo gains the event-turn flag threaded from the event
# drain (the former "(event) " string prefix was player-forgeable: any
# whisper could have claimed the event path). Defaulted so the upstream
# call sites (the chat dispatch holder, the debug gen path) compile
# unchanged and are simply never event turns.
PB_SAY_CHATREPLY_DECL_UPSTREAM = """        static void ChatReplyDo(Player* bot, uint32 type, uint32 guid1, uint32 guid2, std::string msg, std::string chanName, std::string name);
"""
PB_SAY_CHATREPLY_DECL_ANDROID = """        static void ChatReplyDo(Player* bot, uint32 type, uint32 guid1, uint32 guid2, std::string msg, std::string chanName, std::string name, bool isEventTurn = false, uint32 eventKind = 0);
"""
PB_SAY_CHATREPLY_DEF_UPSTREAM = """void ChatReplyAction::ChatReplyDo(Player* bot, uint32 type, uint32 guid1, uint32 guid2, std::string msg, std::string chanName, std::string name)
{"""
PB_SAY_CHATREPLY_DEF_ANDROID = """void ChatReplyAction::ChatReplyDo(Player* bot, uint32 type, uint32 guid1, uint32 guid2, std::string msg, std::string chanName, std::string name, bool isEventTurn, uint32 eventKind)
{"""
CORE_PLAYER_INCLUDE_UPSTREAM = """#ifdef ENABLE_PLAYERBOTS
#include "playerbot/playerbot.h"
#include "playerbot/PlayerbotAIConfig.h"
#endif
"""
CORE_PLAYER_INCLUDE_ANDROID = """#ifdef ENABLE_PLAYERBOTS
#include "playerbot/playerbot.h"
#include "playerbot/PlayerbotAIConfig.h"
#include "playerbot/PlayerbotLlmMemory.h"
#endif
"""
CORE_LOOT_UPSTREAM = """    InventoryResult result = loot->SendItem(_player, lootItem);
"""
CORE_LOOT_ANDROID = """    InventoryResult result = loot->SendItem(_player, lootItem);

#ifdef ENABLE_PLAYERBOTS
    // LLM companion event allowlist: rare-or-better loot by a group member
    if (result == EQUIP_ERR_OK)
        PlayerbotLlmMemory::OnPlayerRareLoot(_player, lootItem->itemId);
#endif
"""
CORE_LOOT_INCLUDE_UPSTREAM = """#include "Entities/Player.h"
#include "Globals/ObjectAccessor.h"
"""
CORE_LOOT_INCLUDE_ANDROID = """#include "Entities/Player.h"
#include "Globals/ObjectAccessor.h"
#ifdef ENABLE_PLAYERBOTS
#include "playerbot/PlayerbotLlmMemory.h"
#endif
"""
# --- Unit::Kill hook for authored kill banter ---------------------------
# The once-per-kill credit block (tapper is the loot-recipient Player*, the
# reward lines make the anchor unique within Unit.cpp). The memory layer does
# its own gating: real players only, 4% roll, party stagger, ambient cooldown.
CORE_UNIT_INCLUDE_UPSTREAM = """#include "Globals/ObjectMgr.h"
"""
CORE_UNIT_INCLUDE_ANDROID = """#include "Globals/ObjectMgr.h"
#ifdef ENABLE_PLAYERBOTS
#include "playerbot/PlayerbotLlmMemory.h"
#endif
"""
CORE_UNIT_KILL_UPSTREAM = """    // Reward player, his pets, and group/raid members
    if (tapper != victim)
    {
        if (tapperGroup)
            tapperGroup->RewardGroupAtKill(victim, tapper);
        else if (tapper)
            tapper->RewardSinglePlayerAtKill(victim);
    }
"""
CORE_UNIT_KILL_ANDROID = """    // Reward player, his pets, and group/raid members
    if (tapper != victim)
    {
        if (tapperGroup)
            tapperGroup->RewardGroupAtKill(victim, tapper);
        else if (tapper)
            tapper->RewardSinglePlayerAtKill(victim);
    }

#ifdef ENABLE_PLAYERBOTS
    // LLM companion authored kill banter: one hook per kill (world thread);
    // OnPlayerGroupKill rolls, staggers and rate-limits internally.
    if (tapper)
        PlayerbotLlmMemory::OnPlayerGroupKill(tapper, victim);
#endif
"""
# --- plan v5 W1: the player-death hook ----------------------------------
# SetDeathState's JUST_DIED tail fires exactly once per death, whatever
# killed the player (combat, falls, scripts) - the one funnel for "the
# player's own death", which no bot acknowledged until now. The anchor is
# the function's closing assignment (unique in Unit.cpp).
CORE_UNIT_DEATH_UPSTREAM = """    m_deathState = s;
}
"""
CORE_UNIT_DEATH_ANDROID = """#ifdef ENABLE_PLAYERBOTS
    // plan v5 W1: wipe classification + authored condolence live inside
    // (world thread - SetDeathState runs on the damage/death paths only;
    // login loads use the DEAD state, never JUST_DIED)
    if (s == JUST_DIED && GetTypeId() == TYPEID_PLAYER)
    {
        Player* const deadPlayer = (Player*)this;
        if (deadPlayer->isRealPlayer() && deadPlayer->GetSession())
            PlayerbotLlmMemory::OnPlayerDied(deadPlayer);
    }
#endif
    m_deathState = s;
}
"""
# --- plan v5 F1: the player<->bot trade completion hook -----------------
# Pre-moveItems so both TradeData still carry the offered money/items
# (the handler moves and deletes them synchronously right after). The
# memory layer decides which side is the bot and what the trade meant.
CORE_TRADE_INCLUDE_UPSTREAM = """#include "Entities/Player.h"
#include "Entities/Item.h"
"""
CORE_TRADE_INCLUDE_ANDROID = """#include "Entities/Player.h"
#include "Entities/Item.h"
#ifdef ENABLE_PLAYERBOTS
#include "playerbot/PlayerbotLlmMemory.h"
#endif
"""
CORE_TRADE_UPSTREAM = """        // execute trade: 1. remove
"""
CORE_TRADE_ANDROID = """#ifdef ENABLE_PLAYERBOTS
        // plan v5 F1: the ONE player-bot trade completion hook - debt
        // settlement, errand completion and the kindness tone row all
        // hang off it (world thread, pre-moveItems)
        PlayerbotLlmMemory::OnTradeCompleted(_player, trader);
#endif
        // execute trade: 1. remove
"""
# --- plan v5 W3: the first-visit fact hook --------------------------------
# The explore-bit setter is the game's OWN verification of "first time
# here" - the anchor sits inside the newly-discovered branch, right before
# the area id is read for exploration XP. The zone-level id is preferred
# (sub-zone granularity would mint a fact per street corner).
CORE_EXPLORE_UPSTREAM = """            uint32 area = p->ID;
"""
CORE_EXPLORE_ANDROID = """#ifdef ENABLE_PLAYERBOTS
            // plan v5 W3: first-visit facts - grouped/known bots mint
            // "traveled with you to <zone> for the first time" (world
            // thread; prefix-checked against the ledger, so a restart
            // never mints a duplicate first time)
            PlayerbotLlmMemory::OnPlayerExploredArea(this, p->zone ? p->zone : p->ID);
#endif
            uint32 area = p->ID;
"""
# the FindWeather DEFINITION lands after the WeatherSystem map member
# (inline in the header keeps the driver out of Weather.cpp)
CORE_WEATHERSYS_UPSTREAM = """        Weather* FindOrCreateWeather(uint32 zoneId);
        void UpdateWeathers(uint32 diff);
"""
CORE_WEATHERSYS_ANDROID = """        Weather* FindOrCreateWeather(uint32 zoneId);
        void UpdateWeathers(uint32 diff);

        // plan v5 F5: fail-on-miss zone lookup (read-only bias reads)
        Weather* FindWeather(uint32 zoneId) const
        {
            WeatherMap::const_iterator itr = m_weathers.find(zoneId);
            return itr != m_weathers.end() ? itr->second : nullptr;
        }
"""
# --- plan v5 W7a: read-only weather access --------------------------------
# Weather's state getter is private and WeatherSystem has no lookup that
# fails instead of creating; the ambient layer only needs the raw
# type/grade. Two inline accessors in the public section - the smallest
# surface that keeps the bias read-only.
CORE_WEATHER_UPSTREAM = """        Weather(uint32 zone, WeatherZoneChances const* weatherChances);
        ~Weather() {};
"""
CORE_WEATHER_ANDROID = """        Weather(uint32 zone, WeatherZoneChances const* weatherChances);
        ~Weather() {};

        // plan v5 W7a: read-only access for the ambient layer's
        // weather bias (no state computation duplicated outside)
        WeatherType GetWeatherType() const { return m_type; }
        float GetWeatherGrade() const { return m_grade; }
        // plan v5 F5: a FAIL-ON-MISS zone lookup - the ambient readers
        // must never create weather objects as a side effect
        Weather* FindWeather(uint32 zoneId) const;
"""
# --- neuter tool markers in the player's raw words ----------------------
# The <initial message> placeholder is the only path where player text enters
# the prompt un-choked (history writes all funnel through the neutered
# AppendTurn). The statement line is unique within SayAction.cpp.
PB_SAY_NEUTER_UPSTREAM = """                placeholders["<initial message>"] = msg;
"""
PB_SAY_NEUTER_ANDROID = """                // injection hygiene: tool markers AND prompt furniture in
                // the player's words die here - the model must never see
                // live protocol it could echo back as a forged call, nor
                // a forged "[RESULT]"/"[BRIDGE AI]" line on the legacy
                // path (AppendTurn scrubs history writes; the trained
                // builder scrubs its own turn; this covers the direct
                // placeholder echo on the conf-template/llama paths)
                msg = pocketllm::NeuterMarkersCopy(PlayerbotLlmMemory::ScrubControlTokens(msg).c_str());
                placeholders["<initial message>"] = msg;
"""
# --- S2/A9: truncation-aware, decode-aware line splitting --------------------
# Generate's HTTP JSON-client leg sets thread_local state bits; ParseResponse
# (same async worker thread) reads and clears them: TRUNCATED drops the
# dangling partial sentence a finish_reason=="length" reply ends with, and
# JSON_DECODED skips the two JSON-era residue transforms below (they exist
# for raw-body extraction and mangle decoded prose). Both anchor lines are
# unique: "start pattern:" appears once, the ReplaceAll call once.
PB_LLM_IFACE_PARSE_UPSTREAM = """    std::string actualResponse = response;

    if (debug)
        debugLines.push_back("start pattern:" + startPattern);
"""
PB_LLM_IFACE_PARSE_ANDROID = """    std::string actualResponse = response;

    // A9 per-generation state (set by Generate's HTTP JSON-client leg on
    // this same worker thread). Read-and-clear: the llama path sets neither
    // bit and a later parse must never see a stale one.
    int const llmGenerationState = pocketLlmGenerationState;
    pocketLlmGenerationState = 0;
    if (llmGenerationState & POCKET_LLM_GEN_TRUNCATED)
    {
        actualResponse = pocketllm::TrimTruncatedTail(actualResponse);
        if (debug)
            debugLines.push_back("truncated generation: dangling tail trimmed");
    }

    if (debug)
        debugLines.push_back("start pattern:" + startPattern);
"""
PB_LLM_IFACE_UNESCAPE_UPSTREAM = """    PlayerbotTextMgr::ReplaceAll(actualResponse, R"(\\")", "'");
"""
PB_LLM_IFACE_UNESCAPE_ANDROID = """    // A9: this rewrite strips the escaped-quote residue of RAW-body regex
    // extraction; on the JSON-decoded path the quotes are already decoded,
    // and rewriting them would mangle ordinary quoted speech.
    if (!(llmGenerationState & POCKET_LLM_GEN_JSON_DECODED))
        PlayerbotTextMgr::ReplaceAll(actualResponse, R"(\\")", "'");
"""
PB_LLM_IFACE_DELETE_UPSTREAM = """    if (!deletePattern.empty()) {
"""
PB_LLM_IFACE_DELETE_ANDROID = """    // The delete pattern targets JSON-era escape residue
    // (`\\n`, `\\uXXXX`) that never exists in decoded prose - but its
    // `\\[^ ]+` alternative eats a literal backslash and the word after
    // it. Decoded content skips it; the raw fallback path keeps it.
    if (!(llmGenerationState & POCKET_LLM_GEN_JSON_DECODED) && !deletePattern.empty()) {
"""
# --- GenerateHttp external-endpoint hardening -------------------------------
# The raw body under the header block was returned as-is: a non-200 error
# page became the "completion" (the start pattern then yields "" - silent
# bot) and a chunked body kept its hex chunk framing. Both break any real
# external endpoint (OpenAI, OpenRouter, ...); llama-server is unaffected.
PB_LLM_IFACE_HTTP_UPSTREAM = """    size_t pos = response.find("\\r\\n\\r\\n");
    if (pos != std::string::npos) {
        response = response.substr(pos + 4);
        if (debug)
            debugLines.push_back("HTTP Body: " + response);
    }

    return response;
"""
PB_LLM_IFACE_HTTP_ANDROID = """    // status + framing hardening for real-world endpoints
    {
        size_t const headerEnd = response.find("\\r\\n\\r\\n");
        std::string const headers = headerEnd == std::string::npos ? response : response.substr(0, headerEnd);
        std::string body = headerEnd == std::string::npos ? std::string() : response.substr(headerEnd + 4);

        // status line: "HTTP/1.x NNN ...". A 0 (unparseable) keeps the
        // legacy lenient behavior; any non-2xx is a hard error.
        {
            size_t const lineEnd = headers.find("\\r\\n");
            std::string const statusLine = headers.substr(0, lineEnd == std::string::npos ? headers.size() : lineEnd);
            size_t const sp1 = statusLine.find(' ');
            int statusCode = 0;
            if (sp1 != std::string::npos)
                statusCode = atoi(statusLine.c_str() + sp1 + 1);
            if (statusCode && (statusCode < 200 || statusCode >= 300))
            {
                if (debug)
                    debugLines.push_back("HTTP status " + std::to_string(statusCode) + " - treating as error");
                sLog.outError("BotLLM: HTTP status %d from the LLM endpoint", statusCode);
                // A8: the end-of-turn line names the status class
                pocketllm::NoteGenClass("http_" + std::to_string(statusCode));
                return "error";
            }
        }

        // de-chunk a Transfer-Encoding: chunked body (hex sizes, final 0
        // chunk whose trailer is dropped with the loop break)
        {
            std::string lowerHeaders = headers;
            for (char& c : lowerHeaders)
                c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            if (lowerHeaders.find("transfer-encoding: chunked") != std::string::npos)
            {
                std::string decoded;
                size_t read = 0;
                while (read < body.size())
                {
                    size_t const eol = body.find("\\r\\n", read);
                    if (eol == std::string::npos)
                        break;
                    unsigned long const chunkLen = strtoul(body.c_str() + read, nullptr, 16);
                    read = eol + 2;
                    if (chunkLen == 0)
                        break;
                    if (read + chunkLen > body.size())
                        break;
                    decoded.append(body, read, chunkLen);
                    read += chunkLen + 2;
                }
                body.swap(decoded);
            }
        }

        if (debug)
            debugLines.push_back("HTTP Body: " + body);
        return body;
    }

    return response;
"""
PB_AI_INCLUDE_UPSTREAM = """#include "PlayerbotDbStore.h"
"""
PB_AI_INCLUDE_ANDROID = """#include "PlayerbotDbStore.h"
#include "PlayerbotLlmMemory.h"
#include "PlayerbotLlmPersona.h"
#include "PlayerbotLlmTools.h"
"""
PB_MGR_LOGIN_ANDROID = """void RandomPlayerbotMgr::OnBotLoginInternal(Player * const bot)
{
    lowCpuLoginEvents.push_back(time(nullptr));
    sLog.outDetail("%u/%d Bot %s logged in", GetPlayerbotsAmount(), sRandomPlayerbotMgr.GetMaxAllowedBotCount(), bot->GetName());
"""

# --- DB async null-guard overlays -------------------------------------------
# HaltDelayThread() nulls m_threadBody while m_allowAsyncTransactions stays
# sticky-true across POCKET_EMBEDDED restart cycles (the
# production restart path re-enters session 2 with CharacterDatabase async-on
# against freshly-halted thread state). Every async enqueue site must tolerate
# a halted worker: fall back to direct execution on the async connection
# rather than dereference a dead queue or silently drop the operation.
DB_GUARD_HEADER_UPSTREAM = """        void StopServer();

        // factory method to create SqlConnection objects
"""
DB_GUARD_HEADER_ANDROID = """        void StopServer();

        // Pocket Realm: null-guarded async enqueue. HaltDelayThread() nulls
        // m_threadBody while m_allowAsyncTransactions stays sticky-true across
        // POCKET_EMBEDDED restart cycles, so async enqueue sites must tolerate
        // a halted worker: fall back to direct execution on the async
        // connection rather than dereference a dead queue or drop the op.
        bool SafeDelayOperation(SqlOperation* op);
        bool SafeDelayQueryHolder(SqlQueryHolder* holder, MaNGOS::IQueryCallback* callback);

        // factory method to create SqlConnection objects
"""
DB_GUARD_HELPERS_UPSTREAM = """    m_delayThread = nullptr;
    m_threadBody = nullptr;
}

void Database::ThreadStart()
"""
DB_GUARD_HELPERS_ANDROID = """    m_delayThread = nullptr;
    m_threadBody = nullptr;
}

bool Database::SafeDelayOperation(SqlOperation* op)
{
    if (!op)
        return false;

    if (m_threadBody)
    {
        m_threadBody->Delay(op);
        return true;
    }

    // Pocket Realm restart window: the async worker is halted while the
    // connection pool lives on. Execute inline on the async connection --
    // exactly what the async-off path does, including ownership: the worker
    // queue deletes ops after Execute, so the inline path must too
    // (mirrors CommitTransactionDirect).
    if (!m_pAsyncConn)
    {
        delete op;
        return false;
    }
    const bool ok = op->Execute(m_pAsyncConn);
    delete op;
    return ok;
}

bool Database::SafeDelayQueryHolder(SqlQueryHolder* holder, MaNGOS::IQueryCallback* callback)
{
    if (!holder || !callback)
        return false;

    if (m_threadBody)
        return holder->Execute(callback, m_threadBody, m_pResultQueue);

    if (!m_pAsyncConn || !m_pResultQueue)
    {
        // The caller allocated the callback; refuse-branches must not leak it
        // (SqlQueryHolder::Execute refuses without taking ownership too).
        delete callback;
        return false;
    }

    // Pocket Realm restart window: SqlQueryHolder::Execute would silently
    // drop the whole query set on a null thread; run it inline instead. The
    // callback still syncs back through the result queue.
    SqlQueryHolderEx holderEx(holder, callback, m_pResultQueue);
    return holderEx.Execute(m_pAsyncConn);
}

void Database::ThreadStart()
"""
DB_GUARD_EXEC_UPSTREAM = """        // Simple sql statement
        m_threadBody->Delay(new SqlPlainRequest(sql));
    }

    return true;
"""
DB_GUARD_EXEC_ANDROID = """        // Simple sql statement
        return SafeDelayOperation(new SqlPlainRequest(sql));
    }

    return true;
"""
DB_GUARD_COMMIT_UPSTREAM = """    // add SqlTransaction to the async queue
    m_threadBody->Delay(m_currentTransaction.release());
    return true;
"""
DB_GUARD_COMMIT_ANDROID = """    // add SqlTransaction to the async queue
    return SafeDelayOperation(m_currentTransaction.release());
"""
DB_GUARD_STMT_UPSTREAM = """        // Simple sql statement
        m_threadBody->Delay(new SqlPreparedRequest(id.ID(), params));
    }

    return true;
"""
DB_GUARD_STMT_ANDROID = """        // Simple sql statement
        return SafeDelayOperation(new SqlPreparedRequest(id.ID(), params));
    }

    return true;
"""
DB_GUARD_ASYNC_QUERY_UPSTREAM = "m_threadBody->Delay(new SqlQuery("
DB_GUARD_ASYNC_QUERY_ANDROID = "SafeDelayOperation(new SqlQuery("
DB_GUARD_HOLDER_UPSTREAM = (
    "holder->Execute(new MaNGOS::QueryCallback(std::move(callback)), "
    "m_threadBody, m_pResultQueue);"
)
DB_GUARD_HOLDER_ANDROID = (
    "SafeDelayQueryHolder(holder, "
    "new MaNGOS::QueryCallback(std::move(callback)));"
)

# Fail-loud backend selection: the DO_* variables are derived compile
# definitions, never inputs. The real switches are the POSTGRESQL/SQLITE cache
# variables (MySQL = else-default); a -DDO_SQLITE=ON on the command line
# selects nothing and silently builds MySQL.
BACKEND_SELECT_UPSTREAM = """if(POSTGRESQL)
  set(DEFINITIONS ${DEFINITIONS} DO_POSTGRESQL)
elseif(SQLITE)
  set(DEFINITIONS ${DEFINITIONS} DO_SQLITE)
else()
  set(DEFINITIONS ${DEFINITIONS} DO_MYSQL)
endif()
"""
BACKEND_SELECT_ANDROID = """# Pocket Realm: fail-loud backend selection (G1). The DO_* variables are
# derived compile definitions, never inputs: -DDO_MYSQL/-DDO_SQLITE on the
# command line select nothing (the real switches are the POSTGRESQL/SQLITE
# cache variables, MySQL is the else-default) and a flipped DO_ flag silently
# builds the wrong engine. Refuse the no-op form and print the selected
# backend so every configure log carries the evidence.
if(DEFINED CACHE{DO_MYSQL} OR DEFINED CACHE{DO_SQLITE} OR DEFINED CACHE{DO_POSTGRESQL})
  message(FATAL_ERROR "Pocket Realm: -DDO_MYSQL/-DDO_SQLITE/-DDO_POSTGRESQL are derived no-op defines; select the backend with -DSQLITE=ON (MySQL is the default when SQLITE is off)")
endif()
if(POSTGRESQL)
  message(FATAL_ERROR "Pocket Realm: the PostgreSQL backend is not shipped; use -DSQLITE=ON or the MySQL default")
endif()
if(SQLITE)
  message(STATUS "Pocket Realm database backend: SQLITE (in-tree DO_SQLITE backend)")
  set(DEFINITIONS ${DEFINITIONS} DO_SQLITE)
else()
  message(STATUS "Pocket Realm database backend: MYSQL (default; pass -DSQLITE=ON for the in-tree SQLite backend)")
  set(DEFINITIONS ${DEFINITIONS} DO_MYSQL)
endif()
"""

# Commit-failure rollback + begin-failure refusal for the DO_SQLITE
# lane. Upstream ignored BeginTransaction()'s return (a failed BEGIN ran the
# batch in autocommit - non-atomic partial application) and returned false
# from a failed COMMIT with the transaction still open ("cannot start a
# transaction within a transaction" - session-long write wedge).
# The #ifdef keeps DO_MYSQL builds behaviorally unchanged.
SQLITE_TXN_COMMIT_UPSTREAM = """    conn->BeginTransaction();

    const int nItems = m_queue.size();
    for (int i = 0; i < nItems; ++i)
    {
        SqlOperation* pStmt = m_queue[i];

        if (!pStmt->Execute(conn))
        {
            conn->RollbackTransaction();
            return false;
        }
    }

    return conn->CommitTransaction();
}
"""
SQLITE_TXN_COMMIT_ANDROID = """#ifdef DO_SQLITE
    // Pocket Realm DO_SQLITE hardening (G3): refuse to run the batch at all
    // when BEGIN fails (upstream fell through to autocommit - non-atomic
    // partial application), and roll the open transaction back on a failed
    // COMMIT (upstream left it open, wedging the connection for the session
    // - F30 chain B).
    if (!conn->BeginTransaction())
        return false;

    const int nItems = m_queue.size();
    for (int i = 0; i < nItems; ++i)
    {
        SqlOperation* pStmt = m_queue[i];

        if (!pStmt->Execute(conn))
        {
            conn->RollbackTransaction();
            return false;
        }
    }

    const bool committed = conn->CommitTransaction();
    if (!committed)
        conn->RollbackTransaction();
    return committed;
#else
    conn->BeginTransaction();

    const int nItems = m_queue.size();
    for (int i = 0; i < nItems; ++i)
    {
        SqlOperation* pStmt = m_queue[i];

        if (!pStmt->Execute(conn))
        {
            conn->RollbackTransaction();
            return false;
        }
    }

    return conn->CommitTransaction();
#endif
}
"""


def apply_db_null_guard_overlays(shared_dir: Path) -> None:
    """Apply the async null-guard overlays to a src/shared/Database directory.

    Split out from prepare_cmangos_source so the tripwire test can exercise
    the exact overlay pair against a pristine copy of the pinned sources.
    """
    database_h = shared_dir / "Database.h"
    database_cpp = shared_dir / "Database.cpp"
    database_impl = shared_dir / "DatabaseImpl.h"
    replace_anchor(database_h, DB_GUARD_HEADER_UPSTREAM, DB_GUARD_HEADER_ANDROID)
    replace_anchor(database_cpp, DB_GUARD_HELPERS_UPSTREAM, DB_GUARD_HELPERS_ANDROID)
    replace_anchor(database_cpp, DB_GUARD_EXEC_UPSTREAM, DB_GUARD_EXEC_ANDROID)
    replace_anchor(database_cpp, DB_GUARD_COMMIT_UPSTREAM, DB_GUARD_COMMIT_ANDROID)
    replace_anchor(database_cpp, DB_GUARD_STMT_UPSTREAM, DB_GUARD_STMT_ANDROID)
    replace_all(database_impl, DB_GUARD_ASYNC_QUERY_UPSTREAM, DB_GUARD_ASYNC_QUERY_ANDROID)
    replace_all(database_impl, DB_GUARD_HOLDER_UPSTREAM, DB_GUARD_HOLDER_ANDROID)


def restore_db_null_guard_overlays(shared_dir: Path) -> None:
    """Undo the async null-guard overlays; idempotent like restore_anchor."""
    database_h = shared_dir / "Database.h"
    database_cpp = shared_dir / "Database.cpp"
    database_impl = shared_dir / "DatabaseImpl.h"
    restore_anchor(database_h, DB_GUARD_HEADER_ANDROID, DB_GUARD_HEADER_UPSTREAM)
    restore_anchor(database_cpp, DB_GUARD_HELPERS_ANDROID, DB_GUARD_HELPERS_UPSTREAM)
    restore_anchor(database_cpp, DB_GUARD_EXEC_ANDROID, DB_GUARD_EXEC_UPSTREAM)
    restore_anchor(database_cpp, DB_GUARD_COMMIT_ANDROID, DB_GUARD_COMMIT_UPSTREAM)
    restore_anchor(database_cpp, DB_GUARD_STMT_ANDROID, DB_GUARD_STMT_UPSTREAM)
    restore_all(database_impl, DB_GUARD_ASYNC_QUERY_ANDROID, DB_GUARD_ASYNC_QUERY_UPSTREAM)
    restore_all(database_impl, DB_GUARD_HOLDER_ANDROID, DB_GUARD_HOLDER_UPSTREAM)


def restore_all(path: Path, applied: str, original: str) -> None:
    """Undo a replace_all overlay if present, while staying idempotent."""
    data = path.read_bytes()
    for applied_bytes, original_bytes in (
        (applied.encode("utf-8"), original.encode("utf-8")),
        (
            applied.replace("\n", "\r\n").encode("utf-8"),
            original.replace("\n", "\r\n").encode("utf-8"),
        ),
    ):
        if applied_bytes in data:
            path.write_bytes(data.replace(applied_bytes, original_bytes))
            return
    original_variants = (
        original.encode("utf-8"),
        original.replace("\n", "\r\n").encode("utf-8"),
    )
    if not any(original_bytes in data for original_bytes in original_variants):
        raise RuntimeError(f"source overlay cleanup anchor drift: {path}")


def verify_db_async_null_guards(cmangos: Path) -> None:
    """Tripwire: every async enqueue site routes through the guarded helpers.

    HaltDelayThread() nulls m_threadBody while m_allowAsyncTransactions stays
    sticky-true across POCKET_EMBEDDED restart cycles; an unguarded
    enqueue after a halt is a null dereference, so the only tolerated
    m_threadBody->Delay is the one inside Database::SafeDelayOperation and
    the only tolerated holder->Execute is the guarded call inside
    Database::SafeDelayQueryHolder. Runs after every overlay application.
    """
    database_cpp = (cmangos / "src" / "shared" / "Database" / "Database.cpp").read_text(
        encoding="utf-8", errors="replace")
    database_impl = (cmangos / "src" / "shared" / "Database" / "DatabaseImpl.h").read_text(
        encoding="utf-8", errors="replace")
    cpp_delays = database_cpp.count("m_threadBody->Delay(")
    if cpp_delays != 1 or "SafeDelayOperation" not in database_cpp:
        raise RuntimeError(
            "DB async null-guard tripwire: Database.cpp must contain exactly one "
            f"m_threadBody->Delay (inside SafeDelayOperation); found {cpp_delays}. "
            "New async enqueue sites must route through the guarded helper.")
    if "holder->Execute(" in database_impl or "m_threadBody->Delay(" in database_impl:
        raise RuntimeError(
            "DB async null-guard tripwire: DatabaseImpl.h still contains an "
            "unguarded async enqueue site (m_threadBody->Delay/holder->Execute); "
            "route it through SafeDelayOperation/SafeDelayQueryHolder.")


def apply_sqlite_hardening(cmangos: Path) -> None:
    """Swap in the hardened DO_SQLITE connection layer.

    Full-file replacements from native/patches/cmangos/ plus the
    SqlOperations commit-failure-rollback anchor overlay. Only ever applied
    for --backend sqlite; DO_MYSQL builds are behaviorally unchanged (the
    rollback overlay is #ifdef'd to DO_SQLITE).
    """
    shared = cmangos / "src" / "shared" / "Database"
    for name in ("DatabaseSqlite.h", "DatabaseSqlite.cpp",
                 "QueryResultSqlite.h", "QueryResultSqlite.cpp"):
        (shared / name).write_bytes((NATIVE / "patches" / "cmangos" / name).read_bytes())
    replace_anchor(shared / "SqlOperations.cpp",
                   SQLITE_TXN_COMMIT_UPSTREAM, SQLITE_TXN_COMMIT_ANDROID)


def restore_sqlite_hardening(cmangos: Path) -> None:
    """Restore the pristine connection-layer files (git-tracked, verified
    clean before the overlays were applied, so checkout is byte-exact)."""
    shared = cmangos / "src" / "shared" / "Database"
    replace_anchor(shared / "SqlOperations.cpp",
                   SQLITE_TXN_COMMIT_ANDROID, SQLITE_TXN_COMMIT_UPSTREAM)
    for name in ("DatabaseSqlite.h", "DatabaseSqlite.cpp",
                 "QueryResultSqlite.h", "QueryResultSqlite.cpp"):
        run(["git", "checkout", "--", f"src/shared/Database/{name}"], cmangos)


def run(args: list[str | Path], cwd: Path | None = None) -> None:
    print("+", " ".join(map(str, args)))
    subprocess.run([str(value) for value in args], cwd=cwd, check=True)


def run_capture(args: list[str | Path], cwd: Path | None = None) -> str:
    """Run a command, print it, and return its combined stdout/stderr text."""
    printed = " ".join(map(str, args))
    print("+", printed)
    try:
        completed = subprocess.run([str(value) for value in args], cwd=cwd, check=True,
                                   capture_output=True, text=True, encoding="utf-8",
                                   errors="replace")
    except subprocess.CalledProcessError as error:
        # Surface the captured output of the failed step; swallowing it made
        # configure failures undiagnosable.
        print((error.stdout or "") + (error.stderr or ""))
        raise
    return (completed.stdout or "") + (completed.stderr or "")


def output(args: list[str | Path], cwd: Path | None = None) -> str:
    return subprocess.check_output([str(value) for value in args], cwd=cwd, text=True).strip()


sha256 = common.sha256_file
def sdk_root() -> Path:
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured)
    properties = ROOT / "android" / "local.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("sdk.dir="):
            return Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    raise RuntimeError("Android SDK not found")


def tools() -> tuple[Path, Path, Path, Path]:
    sdk = sdk_root()
    ndks = sorted(path for path in (sdk / "ndk").glob("*") if path.is_dir())
    cmakes = sorted(path for path in (sdk / "cmake").glob("*") if path.is_dir())
    if not ndks or not cmakes:
        raise RuntimeError("NDK/CMake missing from Android SDK")
    ndk, cmake_root = ndks[-1], cmakes[-1]
    # The fail-loud backend overlay uses `if(DEFINED CACHE{VAR})`, which
    # requires CMake >= 3.21; on an older cmake it degrades silently to a
    # non-firing check - the guard's own failure mode would be quiet.
    try:
        cmake_version = output([cmake_root / "bin" / "cmake.exe", "--version"]).split()[2]
        cmake_major_minor = tuple(int(part) for part in cmake_version.split(".")[:2])
    except (IndexError, ValueError):
        raise RuntimeError(f"cannot parse cmake version for {cmake_root}")
    if cmake_major_minor < (3, 21):
        raise RuntimeError(
            f"cmake {cmake_version} at {cmake_root} is older than 3.21; the "
            "fail-loud backend selection overlay would silently not fire")
    bin_dir = ndk / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin"
    return ndk, cmake_root / "bin" / "cmake.exe", cmake_root / "bin" / "ninja.exe", bin_dir


def replace_anchor(path: Path, old: str, new: str) -> None:
    data = path.read_bytes()
    variants = (
        (old.encode("utf-8"), new.encode("utf-8")),
        (
            old.replace("\n", "\r\n").encode("utf-8"),
            new.replace("\n", "\r\n").encode("utf-8"),
        ),
    )
    # Check the upstream anchor first.  `char fp[...]` is a substring of
    # `const char fp[...]`; testing the replacement first would falsely report
    # the old declaration as already patched on Clang/ARM.
    # Some pinned mirrors contain a single LF-only line in an otherwise CRLF
    # file, so the anchor itself -- rather than the file-wide majority -- must
    # choose the replacement line ending.
    for old_bytes, new_bytes in variants:
        if old_bytes in data:
            path.write_bytes(data.replace(old_bytes, new_bytes, 1))
            return
    for _old_bytes, new_bytes in variants:
        if new_bytes in data:
            return
    raise RuntimeError(f"source overlay anchor drift: {path}: {old}")


def replace_all(path: Path, old: str, new: str) -> None:
    """Replace every occurrence (pure textual migration, e.g. a key rename)."""
    data = path.read_bytes()
    for old_bytes, new_bytes in (
        (old.encode("utf-8"), new.encode("utf-8")),
        (old.replace("\n", "\r\n").encode("utf-8"), new.replace("\n", "\r\n").encode("utf-8")),
    ):
        if old_bytes in data:
            path.write_bytes(data.replace(old_bytes, new_bytes))
            return
    for _old_bytes, new_bytes in (
        (old.encode("utf-8"), new.encode("utf-8")),
        (new.replace("\n", "\r\n").encode("utf-8"), new.replace("\n", "\r\n").encode("utf-8")),
    ):
        if new_bytes in data:
            return
    raise RuntimeError(f"source overlay anchor drift: {path}: {old}")


def prepare_connector_source() -> None:
    if not SOURCE.exists():
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--filter=blob:none", CONNECTOR_URL, SOURCE])
    run(["git", "fetch", "--depth", "1", "origin", CONNECTOR_COMMIT], SOURCE)
    run(["git", "checkout", "--detach", CONNECTOR_COMMIT], SOURCE)
    actual = output(["git", "rev-parse", "HEAD"], SOURCE)
    if actual != CONNECTOR_COMMIT:
        raise RuntimeError(f"Connector/C pin mismatch: {actual}")
    # MariaDB's uint/ushort probe typo is exposed by cross-compilation; this
    # uses the actual typedef. Clang 21 also correctly rejects writing through
    # the historical const fingerprint buffer.
    replace_anchor(SOURCE / "cmake" / "check_types.cmake",
                   "CHECK_TYPE_SIZE(uint SIZEOF_USHORT)",
                   "CHECK_TYPE_SIZE(ushort SIZEOF_USHORT)")
    replace_anchor(SOURCE / "libmariadb" / "ma_tls.c",
                   "const char fp[EVP_MAX_MD_SIZE];", "char fp[EVP_MAX_MD_SIZE];")


def prepare_cmangos_source() -> None:
    cmangos = NATIVE / "cmangos"
    actual = output(["git", "rev-parse", "HEAD"], cmangos)
    if actual != CMANGOS_COMMIT:
        raise RuntimeError(f"CMaNGOS pin mismatch: {actual}")
    playerbots = NATIVE / "playerbots"
    playerbots_actual = output(["git", "rev-parse", "HEAD"], playerbots)
    if playerbots_actual != PLAYERBOTS_COMMIT:
        raise RuntimeError(f"Playerbots pin mismatch: {playerbots_actual}")
    tracked_dirty = subprocess.run(["git", "diff", "--quiet"], cwd=cmangos).returncode != 0 or \
        subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=cmangos).returncode != 0
    untracked = output(["git", "ls-files", "--others", "--exclude-standard"], cmangos)
    if tracked_dirty or untracked:
        raise RuntimeError("CMaNGOS submodule has unrecorded changes; build overlays belong in this driver")
    if subprocess.run(["git", "diff", "--quiet"], cwd=playerbots).returncode != 0:
        raise RuntimeError("Playerbots submodule has unrecorded changes; build overlays belong in this driver")
    mirror = cmangos / "src" / "modules" / "PlayerBots"
    # Recreate the CMake mirror for every build so overlays are always applied
    # to the pinned pristine source rather than to a previous build's mirror.
    if mirror.exists():
        shutil.rmtree(mirror)
    shutil.copytree(playerbots, mirror, ignore=shutil.ignore_patterns(".git"))
    (mirror / ".pocket-realm-commit").write_text(PLAYERBOTS_COMMIT + "\n", encoding="utf-8")
    replace_anchor(
        cmangos / "src" / "game" / "Maps" / "GridMap.cpp",
        MMAP_GUARD_UPSTREAM,
        MMAP_GUARD_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
        MMAP_LOADMAP_UPSTREAM,
        MMAP_LOADMAP_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
        MMAP_LOADALL_UPSTREAM,
        MMAP_LOADALL_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        WORLD_THREAD_UPSTREAM,
        WORLD_THREAD_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "shared" / "Database" / "SqlOperations.cpp",
        RESULT_QUEUE_UPSTREAM,
        RESULT_QUEUE_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "game" / "Globals" / "ObjectMgr.cpp",
        OBJECTMGR_TRUNCATE_CREATURE_UPSTREAM,
        OBJECTMGR_TRUNCATE_CREATURE_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "game" / "Globals" / "ObjectMgr.cpp",
        OBJECTMGR_TRUNCATE_GAMEOBJECT_UPSTREAM,
        OBJECTMGR_TRUNCATE_GAMEOBJECT_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "game" / "Anticheat" / "module" / "libanticheat.cpp",
        ANTICHEAT_PRUNE_UPSTREAM,
        ANTICHEAT_PRUNE_ANDROID,
    )
    replace_anchor(cmangos / "src" / "game" / "World" / "World.h", POCKET_WORLD_H_UPSTREAM, POCKET_WORLD_H_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "World" / "World.h", POCKET_WORLD_UINT_UPSTREAM, POCKET_WORLD_UINT_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "World" / "World.cpp", POCKET_WORLD_CPP_UPSTREAM, POCKET_WORLD_CPP_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_ENUM_UPSTREAM, POCKET_SESSION_ENUM_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_API_UPSTREAM, POCKET_SESSION_API_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_FIELD_UPSTREAM, POCKET_SESSION_FIELD_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Chat" / "ChatHandler.cpp", POCKET_CHAT_UPSTREAM, POCKET_CHAT_ANDROID)
    interaction = cmangos / "src" / "game" / "Chat" / "PocketRealmInteraction.cpp"
    if interaction.exists():
        raise RuntimeError(f"source overlay target unexpectedly exists: {interaction}")
    interaction.write_bytes(POCKET_INTERACT_SOURCE.read_bytes())
    bot_root = mirror / "playerbot"
    replace_anchor(bot_root / "PlayerbotAIConfig.h", PB_CONFIG_HEADER_UPSTREAM, PB_CONFIG_HEADER_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.h", PB_CONFIG_SOURCE_DECL_UPSTREAM, PB_CONFIG_SOURCE_DECL_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.h", PB_CONFIG_SOURCE_FIELD_UPSTREAM, PB_CONFIG_SOURCE_FIELD_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_CONFIG_CPP_UPSTREAM, PB_CONFIG_CPP_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_CONFIG_SOURCE_USE_UPSTREAM, PB_CONFIG_SOURCE_USE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotFactory.cpp", PB_FACTORY_INCLUDE_UPSTREAM, PB_FACTORY_INCLUDE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotFactory.cpp", PB_FACTORY_BATCH_UPSTREAM, PB_FACTORY_BATCH_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotFactory.cpp", PB_FACTORY_FIXED_UPSTREAM, PB_FACTORY_FIXED_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotFactory.cpp", PB_FACTORY_RANDOM_UPSTREAM, PB_FACTORY_RANDOM_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_ACTIVATION_BUDGET_UPSTREAM, PB_MGR_ACTIVATION_BUDGET_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_ACTIVATION_COUNT_UPSTREAM, PB_MGR_ACTIVATION_COUNT_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_DB_API_UPSTREAM, PB_MGR_DB_API_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_DB_FIELDS_UPSTREAM, PB_MGR_DB_FIELDS_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_DB_LOGIN_GATE_UPSTREAM, PB_MGR_DB_LOGIN_GATE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_DB_SCHEDULE_UPSTREAM, PB_MGR_DB_SCHEDULE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_DB_CALLBACK_UPSTREAM, PB_MGR_DB_CALLBACK_ANDROID)
    # exactly two SendHolders overloads (BotInfos const& / BotPool*) share
    # this anchor in PlayerbotLoginMgr.cpp: two consecutive calls patch the
    # first match each, covering both - a third upstream site would stay
    # unpatched (and removing one call here would silently revert one
    # overload to the raw ping)
    replace_anchor(bot_root / "PlayerbotLoginMgr.cpp", PB_LOGIN_DB_SCHEDULE_UPSTREAM, PB_LOGIN_DB_SCHEDULE_ANDROID)
    replace_anchor(bot_root / "PlayerbotLoginMgr.cpp", PB_LOGIN_DB_SCHEDULE_UPSTREAM, PB_LOGIN_DB_SCHEDULE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_INCLUDE_UPSTREAM, PB_MGR_INCLUDE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_TELEMETRY_TYPE_UPSTREAM, PB_MGR_TELEMETRY_TYPE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_GETTER_UPSTREAM, PB_MGR_GETTER_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_FIELDS_UPSTREAM, PB_MGR_FIELDS_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_SCAN_UPSTREAM, PB_MGR_SCAN_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_SCAN_CALL_UPSTREAM, PB_MGR_SCAN_CALL_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_TELEPORT_UPSTREAM, PB_MGR_TELEPORT_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_RANDOMIZE_UPSTREAM, PB_MGR_RANDOMIZE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_QUERY_NOT_UPSTREAM, PB_MGR_QUERY_NOT_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_LOGIN_UPSTREAM, PB_MGR_LOGIN_ANDROID)
    # LLM in-process backend overlays
    (bot_root / "llm_banter_core.h").write_bytes((NATIVE / "patches" / "playerbots" / "llm_banter_core.h").read_bytes())
    (bot_root / "PlayerbotLlamaRuntime.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlamaRuntime.h").read_bytes())
    (bot_root / "PlayerbotLlamaRuntime.cpp").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlamaRuntime.cpp").read_bytes())
    (bot_root / "PlayerbotLlmMemory.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmMemory.h").read_bytes())
    (bot_root / "PlayerbotLlmMemory.cpp").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmMemory.cpp").read_bytes())
    (bot_root / "PlayerbotLlmTools.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmTools.h").read_bytes())
    (bot_root / "PlayerbotLlmTools.cpp").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmTools.cpp").read_bytes())
    (bot_root / "PlayerbotLlmToolsCore.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmToolsCore.h").read_bytes())
    (bot_root / "PlayerbotLlmPersona.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmPersona.h").read_bytes())
    (bot_root / "PlayerbotLlmPersona.cpp").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmPersona.cpp").read_bytes())
    (bot_root / "PlayerbotLlmJson.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmJson.h").read_bytes())
    (bot_root / "PlayerbotLlmPrompt.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmPrompt.h").read_bytes())
    (bot_root / "PlayerbotLlmBridge.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmBridge.h").read_bytes())
    (bot_root / "PlayerbotLlmBridge.cpp").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmBridge.cpp").read_bytes())
    (bot_root / "PlayerbotLlmTruthCore.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmTruthCore.h").read_bytes())
    (bot_root / "PlayerbotLlmRecallCore.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmRecallCore.h").read_bytes())
    (bot_root / "PlayerbotLlmFilters.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmFilters.h").read_bytes())
    (bot_root / "PlayerbotLlmFilters.cpp").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmFilters.cpp").read_bytes())
    (bot_root / "PlayerbotLlmChatter.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmChatter.h").read_bytes())
    (bot_root / "PlayerbotLlmChatterCore.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmChatterCore.h").read_bytes())
    (bot_root / "PlayerbotLlmChatter.cpp").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmChatter.cpp").read_bytes())
    (bot_root / "PlayerbotLlmGates.h").write_bytes((NATIVE / "patches" / "playerbots" / "PlayerbotLlmGates.h").read_bytes())
    replace_anchor(bot_root / "PlayerbotAIConfig.h", PB_LLM_CONFIG_HEADER_UPSTREAM, PB_LLM_CONFIG_HEADER_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_LLM_CONFIG_CPP_UPSTREAM, PB_LLM_CONFIG_CPP_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_LLM_CTX_REREAD_UPSTREAM, PB_LLM_CTX_REREAD_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.h", PB_LLM_IFACE_HEADER_UPSTREAM, PB_LLM_IFACE_HEADER_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.h", PB_LLM_IFACE_PRIVATE_UPSTREAM, PB_LLM_IFACE_PRIVATE_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_LLM_IFACE_CPP_UPSTREAM, PB_LLM_IFACE_CPP_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.h", PB_SAY_HEADER_UPSTREAM, PB_SAY_HEADER_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.h", PB_SAY_GEN_DECL_UPSTREAM, PB_SAY_GEN_DECL_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_GEN_DEF_UPSTREAM, PB_SAY_GEN_DEF_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_GATE_UPSTREAM, PB_SAY_GATE_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_PROMPT_UPSTREAM, PB_SAY_PROMPT_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_JSON_DUP_UPSTREAM, PB_SAY_JSON_DUP_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_ASYNC_UPSTREAM, PB_SAY_ASYNC_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "RpgSubActions.cpp", PB_RPG_ASYNC_UPSTREAM, PB_RPG_ASYNC_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "RpgSubActions.cpp", PB_RPG_INCLUDE_UPSTREAM, PB_RPG_INCLUDE_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "RpgSubActions.cpp", PB_RPG_QUOTA_UPSTREAM, PB_RPG_QUOTA_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "RpgSubActions.cpp", PB_RPG_PROMPT_UPSTREAM, PB_RPG_PROMPT_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_RAID_CASE_UPSTREAM, PB_SAY_RAID_CASE_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "DebugAction.cpp", PB_DEBUG_GEN_UPSTREAM, PB_DEBUG_GEN_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "DebugAction.cpp", PB_DEBUG_MODGATE_UPSTREAM, PB_DEBUG_MODGATE_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "DebugAction.cpp", PB_DEBUG_INCLUDE_UPSTREAM, PB_DEBUG_INCLUDE_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_SESSION_LIFETIME_UPSTREAM, PB_SESSION_LIFETIME_ANDROID)
    # LLM companion overlays (applied on top of the stage-1 LLM edits)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_INCLUDE_UPSTREAM, PB_IFACE_INCLUDE_ANDROID)
    # bounded TCP connect (non-blocking connect + select)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_SOCKINCLUDE_UPSTREAM, PB_IFACE_SOCKINCLUDE_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_CONNECT_UPSTREAM, PB_IFACE_CONNECT_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_TLSCTX_UPSTREAM, PB_IFACE_TLSCTX_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_TLSHOST_UPSTREAM, PB_IFACE_TLSHOST_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_REQECHO_UPSTREAM, PB_IFACE_REQECHO_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_CONCUR_UPSTREAM, PB_IFACE_CONCUR_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_LLM_EP_CATCH_UPSTREAM, PB_LLM_EP_CATCH_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_INCLUDE_UPSTREAM, PB_SAY_INCLUDE_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_CONTEXT_UPSTREAM, PB_SAY_CONTEXT_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_PROMPT_V2_UPSTREAM, PB_SAY_PROMPT_V2_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_RECORDER_UPSTREAM, PB_SAY_RECORDER_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_SPLITTER_UPSTREAM, PB_SAY_SPLITTER_ANDROID)
    # Pacing law (running timeDiff credit, 35 ms/char, instant busy)
    # and the per-class voice-budget call threading
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_TIMEDIFF_HEAD_UPSTREAM, PB_SAY_TIMEDIFF_HEAD_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_TIMEDIFF_TAIL_UPSTREAM, PB_SAY_TIMEDIFF_TAIL_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_PACE_CALL_UPSTREAM, PB_SAY_PACE_CALL_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_UPDATEAI_UPSTREAM, PB_UPDATEAI_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_INCLUDE_UPSTREAM, PB_AI_INCLUDE_ANDROID)
    # Persona-paced say-reply staggering (queue delay override)
    replace_anchor(bot_root / "PlayerbotAI.h", PB_AI_QUEUE_DECL_UPSTREAM, PB_AI_QUEUE_DECL_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_QUEUE_DEF_UPSTREAM, PB_AI_QUEUE_DEF_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_QUEUE_CALL_UPSTREAM, PB_AI_QUEUE_CALL_ANDROID)
    # A2 fast-lane (rp-depth v2.3): enum + recheck helper in the header,
    # the priority early-return + bracket entry in the class
    replace_anchor(bot_root / "PlayerbotAI.h", PB_AI_DIALOGUE_ENUM_UPSTREAM, PB_AI_DIALOGUE_ENUM_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.h", PB_AI_DIALOGUE_RECHECK_UPSTREAM, PB_AI_DIALOGUE_RECHECK_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_PRIORITY_DIALOGUE_UPSTREAM, PB_AI_PRIORITY_DIALOGUE_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_BRACKET_DIALOGUE_UPSTREAM, PB_AI_BRACKET_DIALOGUE_ANDROID)
    replace_anchor(bot_root / "aiplayerbot.conf.dist.in", PB_LLM_CONF_UPSTREAM, PB_LLM_CONF_ANDROID)
    # Writer migration: the manual NPC-chat debug store moves from the
    # cross-bot global key to a per-(bot,target) key so no old-format writer
    # survives (behavior change: conversations with one NPC are no longer
    # shared between bots)
    replace_all(bot_root / "strategy" / "actions" / "RpgSubActions.cpp", PB_RPG_MANUAL_GET_UPSTREAM, PB_RPG_MANUAL_GET_ANDROID)
    replace_all(bot_root / "strategy" / "actions" / "RpgSubActions.cpp", PB_RPG_MANUAL_SET_UPSTREAM, PB_RPG_MANUAL_SET_ANDROID)
    # core hooks for the LLM companion event allowlist
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Player.cpp", CORE_PLAYER_INCLUDE_UPSTREAM, CORE_PLAYER_INCLUDE_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Player.cpp", CORE_GIVELEVEL_UPSTREAM, CORE_GIVELEVEL_ANDROID)
    # The duel-outcome hook (all nine call sites through one function)
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Player.cpp", CORE_DUELCOMPLETE_UPSTREAM, CORE_DUELCOMPLETE_ANDROID)
    # First-contact onboarding line at login (once per character)
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Player.cpp", CORE_LOGIN_ONBOARDING_UPSTREAM, CORE_LOGIN_ONBOARDING_ANDROID)
    # ChatReplyDo's event-turn flag (threaded from the drain; the
    # "(event) " text prefix is retired as a signal)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.h", PB_SAY_CHATREPLY_DECL_UPSTREAM, PB_SAY_CHATREPLY_DECL_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_CHATREPLY_DEF_UPSTREAM, PB_SAY_CHATREPLY_DEF_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Loot" / "LootHandler.cpp", CORE_LOOT_INCLUDE_UPSTREAM, CORE_LOOT_INCLUDE_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Loot" / "LootHandler.cpp", CORE_LOOT_UPSTREAM, CORE_LOOT_ANDROID)
    # Core hook: authored kill banter
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Unit.cpp", CORE_UNIT_INCLUDE_UPSTREAM, CORE_UNIT_INCLUDE_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Unit.cpp", CORE_UNIT_KILL_UPSTREAM, CORE_UNIT_KILL_ANDROID)
    # plan v5 W1/F1: player-death condolence + trade completion hooks
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Unit.cpp", CORE_UNIT_DEATH_UPSTREAM, CORE_UNIT_DEATH_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Trade" / "TradeHandler.cpp", CORE_TRADE_INCLUDE_UPSTREAM, CORE_TRADE_INCLUDE_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Trade" / "TradeHandler.cpp", CORE_TRADE_UPSTREAM, CORE_TRADE_ANDROID)
    # plan v5 W3: first-visit facts at the explore-bit setter
    replace_anchor(cmangos / "src" / "game" / "Entities" / "Player.cpp", CORE_EXPLORE_UPSTREAM, CORE_EXPLORE_ANDROID)
    # plan v5 W7a: read-only weather accessors
    replace_anchor(cmangos / "src" / "game" / "Weather" / "Weather.h", CORE_WEATHER_UPSTREAM, CORE_WEATHER_ANDROID)
    replace_anchor(cmangos / "src" / "game" / "Weather" / "Weather.h", CORE_WEATHERSYS_UPSTREAM, CORE_WEATHERSYS_ANDROID)
    # Injection hygiene + external-endpoint hardening
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_NEUTER_UPSTREAM, PB_SAY_NEUTER_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_LLM_IFACE_HTTP_UPSTREAM, PB_LLM_IFACE_HTTP_ANDROID)
    # Truncation-aware, decode-aware ParseResponse (consumes the JSON
    # client's per-generation state; gates the JSON-era residue transforms)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_LLM_IFACE_PARSE_UPSTREAM, PB_LLM_IFACE_PARSE_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_LLM_IFACE_UNESCAPE_UPSTREAM, PB_LLM_IFACE_UNESCAPE_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_LLM_IFACE_DELETE_UPSTREAM, PB_LLM_IFACE_DELETE_ANDROID)
    # Per-tier generation timeout default
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_LLM_TIMEOUT_UPSTREAM, PB_LLM_TIMEOUT_ANDROID)
    # The composer endpoint/key override legs inside GenerateHttp
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_EP_URL_UPSTREAM, PB_IFACE_EP_URL_ANDROID)
    replace_anchor(bot_root / "PlayerbotLLMInterface.cpp", PB_IFACE_EP_KEY_UPSTREAM, PB_IFACE_EP_KEY_ANDROID)
    # DB async null-guard + fail-loud backend selection overlays
    apply_db_null_guard_overlays(cmangos / "src" / "shared" / "Database")
    replace_anchor(cmangos / "CMakeLists.txt", BACKEND_SELECT_UPSTREAM, BACKEND_SELECT_ANDROID)
    # The sqlite lane swaps in the hardened connection-layer
    # replacement files; mysql builds never touch them.
    if BACKEND == "sqlite":
        apply_sqlite_hardening(cmangos)
    verify_db_async_null_guards(cmangos)


def restore_cmangos_source() -> None:
    """Restore the pinned submodule byte-for-byte after the overlay build."""
    interaction = NATIVE / "cmangos" / "src" / "game" / "Chat" / "PocketRealmInteraction.cpp"
    if interaction.exists():
        if interaction.read_bytes() != POCKET_INTERACT_SOURCE.read_bytes():
            raise RuntimeError(f"source overlay cleanup content drift: {interaction}")
        interaction.unlink()
    restore_anchor(NATIVE / "cmangos" / "src" / "game" / "Chat" / "ChatHandler.cpp", POCKET_CHAT_ANDROID, POCKET_CHAT_UPSTREAM)
    restore_anchor(NATIVE / "cmangos" / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_FIELD_ANDROID, POCKET_SESSION_FIELD_UPSTREAM)
    restore_anchor(NATIVE / "cmangos" / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_API_ANDROID, POCKET_SESSION_API_UPSTREAM)
    restore_anchor(NATIVE / "cmangos" / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_ENUM_ANDROID, POCKET_SESSION_ENUM_UPSTREAM)
    restore_anchor(NATIVE / "cmangos" / "src" / "game" / "World" / "World.cpp", POCKET_WORLD_CPP_ANDROID, POCKET_WORLD_CPP_UPSTREAM)
    restore_anchor(NATIVE / "cmangos" / "src" / "game" / "World" / "World.h", POCKET_WORLD_UINT_ANDROID, POCKET_WORLD_UINT_UPSTREAM)
    restore_anchor(NATIVE / "cmangos" / "src" / "game" / "World" / "World.h", POCKET_WORLD_H_ANDROID, POCKET_WORLD_H_UPSTREAM)
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Maps" / "GridMap.cpp",
        MMAP_GUARD_ANDROID,
        MMAP_GUARD_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
        MMAP_LOADALL_ANDROID,
        MMAP_LOADALL_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
        MMAP_LOADMAP_ANDROID,
        MMAP_LOADMAP_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "mangosd" / "Master.cpp",
        WORLD_THREAD_ANDROID,
        WORLD_THREAD_UPSTREAM,
    )
    replace_anchor(
        NATIVE / "cmangos" / "src" / "shared" / "Database" / "SqlOperations.cpp",
        RESULT_QUEUE_ANDROID,
        RESULT_QUEUE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Globals" / "ObjectMgr.cpp",
        OBJECTMGR_TRUNCATE_CREATURE_ANDROID,
        OBJECTMGR_TRUNCATE_CREATURE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Globals" / "ObjectMgr.cpp",
        OBJECTMGR_TRUNCATE_GAMEOBJECT_ANDROID,
        OBJECTMGR_TRUNCATE_GAMEOBJECT_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Anticheat" / "module" / "libanticheat.cpp",
        ANTICHEAT_PRUNE_ANDROID,
        ANTICHEAT_PRUNE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Loot" / "LootHandler.cpp",
        CORE_LOOT_ANDROID,
        CORE_LOOT_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Loot" / "LootHandler.cpp",
        CORE_LOOT_INCLUDE_ANDROID,
        CORE_LOOT_INCLUDE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Player.cpp",
        CORE_GIVELEVEL_ANDROID,
        CORE_GIVELEVEL_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Player.cpp",
        CORE_DUELCOMPLETE_ANDROID,
        CORE_DUELCOMPLETE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Player.cpp",
        CORE_LOGIN_ONBOARDING_ANDROID,
        CORE_LOGIN_ONBOARDING_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Player.cpp",
        CORE_PLAYER_INCLUDE_ANDROID,
        CORE_PLAYER_INCLUDE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Unit.cpp",
        CORE_UNIT_KILL_ANDROID,
        CORE_UNIT_KILL_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Unit.cpp",
        CORE_UNIT_DEATH_ANDROID,
        CORE_UNIT_DEATH_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Trade" / "TradeHandler.cpp",
        CORE_TRADE_INCLUDE_ANDROID,
        CORE_TRADE_INCLUDE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Trade" / "TradeHandler.cpp",
        CORE_TRADE_ANDROID,
        CORE_TRADE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Player.cpp",
        CORE_EXPLORE_ANDROID,
        CORE_EXPLORE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Weather" / "Weather.h",
        CORE_WEATHER_ANDROID,
        CORE_WEATHER_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Weather" / "Weather.h",
        CORE_WEATHERSYS_ANDROID,
        CORE_WEATHERSYS_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Entities" / "Unit.cpp",
        CORE_UNIT_INCLUDE_ANDROID,
        CORE_UNIT_INCLUDE_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "CMakeLists.txt",
        BACKEND_SELECT_ANDROID,
        BACKEND_SELECT_UPSTREAM,
    )
    if BACKEND == "sqlite":
        restore_sqlite_hardening(NATIVE / "cmangos")
    restore_db_null_guard_overlays(NATIVE / "cmangos" / "src" / "shared" / "Database")


def restore_anchor(path: Path, applied: str, original: str) -> None:
    """Undo an overlay if present, while making cleanup idempotent."""
    data = path.read_bytes()
    variants = (
        (applied.encode("utf-8"), original.encode("utf-8")),
        (
            applied.replace("\n", "\r\n").encode("utf-8"),
            original.replace("\n", "\r\n").encode("utf-8"),
        ),
    )
    # replace_anchor deliberately follows the exact line ending used by its
    # matched anchor.  Several pinned CMaNGOS files are mixed LF/CRLF, so a
    # file-wide newline guess can strand an applied overlay during cleanup.
    for applied_bytes, original_bytes in variants:
        if applied_bytes in data:
            path.write_bytes(data.replace(applied_bytes, original_bytes, 1))
            return
    original_variants = (
        original.encode("utf-8"),
        original.replace("\n", "\r\n").encode("utf-8"),
    )
    if not any(original_bytes in data for original_bytes in original_variants):
        raise RuntimeError(f"source overlay cleanup anchor drift: {path}")


def configure_and_build(force: bool, backend: str = "mysql", configure_only: bool = False) -> tuple[Path, Path]:
    ndk, cmake, ninja, llvm = tools()
    deps = NATIVE / ".deps" / ("prefix-x86_64" if TARGET_ABI == "x86_64" else "prefix-arm64")
    required = [deps / "include" / "openssl" / "ssl.h", deps / "lib" / "libssl.a",
                deps / "lib" / "libcrypto.a", deps / "lib" / "cmake" / "Boost-1.86.0"]
    if backend == "sqlite":
        # The in-tree SQLite backend needs only the pinned amalgamation
        # static library; no connector-c, no MySQL flags.
        required = required + [deps / "lib" / "libsqlite3.a"]
    if not all(path.exists() for path in required):
        raise RuntimeError(
            f"{TARGET_ABI} OpenSSL/Boost dependencies are missing; "
            "run scripts/build_native.py first"
        )
    connector = None
    if backend == "mysql":
        # The include dir / library path are needed at configure time even
        # when the connector build itself is skipped (configure-only mode);
        # find_package(MySQL) trusts the cache values without stat-ing them.
        connector = CONNECTOR_BUILD / "libmariadb" / "libmariadbclient.a"
        if not configure_only:
            connector_cache = CONNECTOR_BUILD / "CMakeCache.txt"
            cached_source_matches = (not connector_cache.is_file() or
                f"CMAKE_HOME_DIRECTORY:INTERNAL={SOURCE.as_posix()}" in
                connector_cache.read_text(encoding="utf-8", errors="replace").replace("\\", "/"))
            if force or not cached_source_matches:
                shutil.rmtree(CONNECTOR_BUILD, ignore_errors=True)
            CONNECTOR_BUILD.mkdir(parents=True, exist_ok=True)
            toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
            ndk_triple = "x86_64-linux-android" if TARGET_ABI == "x86_64" else "aarch64-linux-android"
            zlib = ndk / "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib" / ndk_triple / "26/libz.so"
            common = ["-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
                      f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={TARGET_ABI}",
                      "-DANDROID_PLATFORM=android-26", "-DCMAKE_BUILD_TYPE=Release",
                      "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"]
            run([cmake, "-S", SOURCE, "-B", CONNECTOR_BUILD, *common,
                 "-DWITH_SSL=OPENSSL", f"-DOPENSSL_ROOT_DIR={deps}",
                 f"-DOPENSSL_INCLUDE_DIR={deps / 'include'}",
                 f"-DOPENSSL_SSL_LIBRARY={deps / 'lib' / 'libssl.a'}",
                 f"-DOPENSSL_CRYPTO_LIBRARY={deps / 'lib' / 'libcrypto.a'}",
                 "-DWITH_CURL=OFF", "-DWITH_DYNCOL=OFF", "-DWITH_UNIT_TESTS=OFF",
                 "-DWITH_MYSQLCOMPAT=OFF",
                 "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -Wno-error=deprecated-non-prototype"])
            run([cmake, "--build", CONNECTOR_BUILD, "--target", "mariadbclient", "-j", str(os.cpu_count() or 4)])
            if not connector.is_file():
                raise RuntimeError("Connector/C static library missing after build")
    if force:
        # A --force rebuild of ONE backend must not destroy the OTHER
        # backend's evidence markers living in the same build dir (only
        # this backend's own marker is invalidated - it is regenerated
        # right after the configure below).
        preserved_markers: dict[str, bytes] = {}
        if CMANGOS_BUILD.is_dir():
            for marker_path in CMANGOS_BUILD.glob("POCKET_BACKEND_VERIFY.*.json"):
                if marker_path.name != f"POCKET_BACKEND_VERIFY.{backend}.json":
                    preserved_markers[marker_path.name] = marker_path.read_bytes()
        shutil.rmtree(CMANGOS_BUILD, ignore_errors=True)
        CMANGOS_BUILD.mkdir(parents=True, exist_ok=True)
        for marker_name, marker_blob in preserved_markers.items():
            (CMANGOS_BUILD / marker_name).write_bytes(marker_blob)

    cmangos = NATIVE / "cmangos"
    CMANGOS_BUILD.mkdir(parents=True, exist_ok=True)
    llama_flags = []
    if TARGET_ABI == "arm64-v8a":
        # in-process llama.cpp backend: vendored kai-build prebuilts, linked
        # into pocket_world_runtime only on arm64 (compiled out on x86_64)
        llama_flags = [
            "-DPOCKETREALM_LLAMA=ON",
            f"-DPOCKETREALM_LLAMA_PREBUILT={ROOT / 'native/llm/prebuilt/arm64-v8a'}",
            f"-DPOCKETREALM_LLAMA_INCLUDE={ROOT / 'native/llm/include'}",
        ]
    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    ndk_triple = "x86_64-linux-android" if TARGET_ABI == "x86_64" else "aarch64-linux-android"
    zlib = ndk / "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib" / ndk_triple / "26/libz.so"
    common = ["-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
              f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={TARGET_ABI}",
              "-DANDROID_PLATFORM=android-26", "-DCMAKE_BUILD_TYPE=Release",
              "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"]
    # Fail-loud backend selection: the SQLITE cache variable is the real
    # switch (MySQL = else-default). The historical -DDO_MYSQL/-DDO_SQLITE
    # pair selected nothing and is refused at configure time.
    backend_flags = ["-DSQLITE=ON"] if backend == "sqlite" else ["-DSQLITE=OFF"]
    mysql_flags = []
    if backend == "mysql":
        mysql_flags = [
            f"-DMYSQL_INCLUDE_DIR={SOURCE / 'include'}", f"-DMYSQL_LIBRARY={connector}",
            f"-DMYSQL_EXTRA_LIBRARIES={zlib}",
        ]
    sqlite_flags = []
    if backend == "sqlite":
        # Explicit paths like every other dep: the NDK toolchain's find-root
        # re-rooting makes prefix-path discovery unreliable, and the pinned
        # amalgamation library is the only acceptable source.
        sqlite_flags = [
            f"-DSQLite3_INCLUDE_DIR={deps / 'include'}",
            f"-DSQLite3_LIBRARY={deps / 'lib' / 'libsqlite3.a'}",
        ]
    cache = CMANGOS_BUILD / "CMakeCache.txt"
    legacy_cache_cleanup = []
    if cache.is_file():
        # Historical builds passed the no-op -DDO_* pair; those entries
        # linger in cached build trees and now trip the fail-loud assert.
        # Removing them inside the same invocation as the configure keeps the
        # assert catching real misuse, not our own stale cache from before
        # the fail-loud assert landed (cmake -U takes a glob; bare names
        # remove exactly those entries).
        legacy_cache_cleanup = ["-UDO_MYSQL", "-UDO_SQLITE", "-UDO_POSTGRESQL"]
        # A mysql configure leaves MYSQL_* cache entries behind; under sqlite
        # they must not bleed connector include/library paths into the build.
        if backend == "sqlite":
            legacy_cache_cleanup += ["-UMYSQL_INCLUDE_DIR", "-UMYSQL_LIBRARY",
                                     "-UMYSQL_EXTRA_LIBRARIES"]
    capture = run_capture([cmake, "-S", cmangos, "-B", CMANGOS_BUILD, *common,
         *legacy_cache_cleanup,
         "-DBUILD_GAME_SERVER=ON", "-DBUILD_LOGIN_SERVER=ON", "-DBUILD_SCRIPTDEV=ON",
         "-DBUILD_EXTRACTORS=OFF", "-DBUILD_PLAYERBOTS=ON", "-DBUILD_AHBOT=OFF",
         "-DBUILD_DEPRECATED_PLAYERBOT=OFF", "-DBUILD_POCKET_RUNTIME=ON",
         f"-DPOCKET_RUNTIME_DIR={NATIVE / 'realm-runtime'}", *backend_flags,
         *llama_flags,
         f"-DBOOST_ROOT={deps}", f"-DBoost_DIR={deps / 'lib' / 'cmake' / 'Boost-1.86.0'}",
         f"-DCMAKE_PREFIX_PATH={deps}",
         f"-Dboost_headers_DIR={deps / 'lib' / 'cmake' / 'boost_headers-1.86.0'}",
         f"-Dboost_atomic_DIR={deps / 'lib' / 'cmake' / 'boost_atomic-1.86.0'}",
         f"-Dboost_filesystem_DIR={deps / 'lib' / 'cmake' / 'boost_filesystem-1.86.0'}",
         f"-Dboost_program_options_DIR={deps / 'lib' / 'cmake' / 'boost_program_options-1.86.0'}",
         f"-Dboost_regex_DIR={deps / 'lib' / 'cmake' / 'boost_regex-1.86.0'}",
         f"-Dboost_serialization_DIR={deps / 'lib' / 'cmake' / 'boost_serialization-1.86.0'}",
         f"-Dboost_system_DIR={deps / 'lib' / 'cmake' / 'boost_system-1.86.0'}",
         f"-Dboost_thread_DIR={deps / 'lib' / 'cmake' / 'boost_thread-1.86.0'}",
         f"-Dboost_wserialization_DIR={deps / 'lib' / 'cmake' / 'boost_wserialization-1.86.0'}",
         "-DBoost_USE_STATIC_LIBS=ON", "-DBoost_USE_STATIC_RUNTIME=ON",
         f"-DOPENSSL_ROOT_DIR={deps}", f"-DOPENSSL_INCLUDE_DIR={deps / 'include'}",
         f"-DOPENSSL_SSL_LIBRARY={deps / 'lib' / 'libssl.a'}",
         f"-DOPENSSL_CRYPTO_LIBRARY={deps / 'lib' / 'libcrypto.a'}",
         *mysql_flags,
         *sqlite_flags,
         "-DCMAKE_CXX_FLAGS=" + (f"-I{CONNECTOR_BUILD / 'include'}" if connector else ""),
         "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"])
    verify_backend_selection(backend, capture)
    # Durable evidence, self-healing on every lane rebuild: the sqlite-side
    # proof is otherwise ephemeral (stdout only), so every verified configure
    # leaves a per-backend marker (one backend's verification never
    # overwrites the other's).
    marker = CMANGOS_BUILD / f"POCKET_BACKEND_VERIFY.{backend}.json"
    status_line = next((line.strip() for line in capture.splitlines()
                        if "Pocket Realm database backend:" in line), "")
    marker.write_text(json.dumps({
        "backend": backend,
        "abi": TARGET_ABI,
        "cmangos_commit": CMANGOS_COMMIT,
        "driver": "tools/build_o09_realm_runtime.py",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "configure_status_line": status_line,
        "evidence": ["configure status line", "build.ninja DO_* defines"],
    }, indent=2) + "\n", encoding="utf-8")
    stale_marker = CMANGOS_BUILD / "POCKET_BACKEND_VERIFY.json"
    if stale_marker.exists():
        stale_marker.unlink()
    if configure_only:
        print(f"configure-only: backend={backend} verified in {CMANGOS_BUILD}")
        return llvm, cmake
    run([cmake, "--build", CMANGOS_BUILD, "--target", "pocket_realmd_runtime",
         "pocket_world_runtime", "-j", str(os.cpu_count() or 4)])
    if backend == "sqlite":
        # A SQLITE=ON build must carry zero mariadbclient
        # references anywhere in the link. No staging
        # into the shared MariaDB staging dir - the
        # artifacts stay in the build tree.
        purity = verify_sqlite_link_purity(llvm)
        marker = CMANGOS_BUILD / "POCKET_BACKEND_VERIFY.sqlite.json"
        record = json.loads(marker.read_text(encoding="utf-8")) if marker.is_file() else {}
        record["link_purity"] = "verified"
        # Distinct from generated_at_utc (configure time): this stamp says
        # when the compiled evidence itself was produced.
        record["build_completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        record["runtime_sha256"] = purity
        marker.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        print(f"sqlite-lane build verified mariadb-free; artifacts built in "
              f"{CMANGOS_BUILD / 'pocket-runtime-build'} and staged into the "
              "sibling realm-staging-sqlite root")
    else:
        # Symmetric completion stamp for the mysql lane: the marker's
        # generated_at_utc alone cannot distinguish configure-verified from
        # fully-built.
        marker = CMANGOS_BUILD / "POCKET_BACKEND_VERIFY.mysql.json"
        record = json.loads(marker.read_text(encoding="utf-8")) if marker.is_file() else {}
        record["build_completed_at_utc"] = datetime.now(timezone.utc).isoformat()
        marker.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return llvm, cmake


def verify_sqlite_link_purity(llvm: Path) -> dict:
    """A SQLITE=ON build must contain zero mariadbclient references.

    Checks the generated build graph for connector paths and the built
    runtime objects for connector symbols (the full mysql_/mariadb_ API
    prefixes only exist in libmariadbclient.a; matching them means the
    connector leaked into the link). Also proves the sqlite side is
    positively present (sqlite3_* symbols), so the check cannot pass
    vacuously. Returns the runtime sha256 evidence for the verify marker.
    """
    graph = (CMANGOS_BUILD / "build.ninja").read_text(encoding="utf-8", errors="replace")
    for needle in ("mariadbclient", "libmariadb", "MYSQL_LIBRARY"):
        if needle in graph:
            raise RuntimeError(
                f"sqlite link purity: {needle!r} appears in the generated "
                "build graph; the MariaDB connector must not enter a "
                "SQLITE=ON build")
    if "libsqlite3.a" not in graph:
        raise RuntimeError(
            "sqlite link purity: the pinned amalgamation library does not "
            "appear in the build graph; the check would pass vacuously")
    nm = llvm / "llvm-nm.exe"
    evidence = {}
    for name in ("libpocket_realmd_runtime.so", "libpocket_world_runtime.so"):
        binary = CMANGOS_BUILD / "pocket-runtime-build" / name
        if not binary.is_file():
            raise RuntimeError(f"sqlite link purity: expected build output missing: {binary}")
        symbols = output([nm, binary])
        leaked = sorted({
            line.split()[-1] for line in symbols.splitlines()
            if line.split() and (
                line.split()[-1].startswith("mysql_") or
                line.split()[-1].startswith("mariadb_"))})
        if leaked:
            preview = ", ".join(leaked[:5])
            raise RuntimeError(
                f"sqlite link purity: connector symbols present in {name} "
                f"({preview}...); a SQLITE=ON build must be mariadb-free")
        if not any(line.split() and line.split()[-1].startswith("sqlite3_")
                   for line in symbols.splitlines()):
            raise RuntimeError(
                f"sqlite link purity: no sqlite3_* symbols in {name}; the "
                "backend did not actually link SQLite")
        evidence[name] = sha256(binary)
    return evidence


def verify_backend_selection(backend: str, configure_stdout: str = "") -> None:
    """Prove the selected backend from configure output, not from the flags.

    Two legs of evidence (a SQLITE=ON claim without configure or link
    evidence proves nothing): (1) the fail-loud CMake overlay's status line
    in the configure output, and (2) the DO_* compile defines actually
    written into the generated Ninja build graph. flags.make is a
    Makefiles-generator artifact and never exists under this driver's
    `-G Ninja`, so build.ninja is the ground-truth source.
    """
    status = None
    for line in configure_stdout.splitlines():
        if "Pocket Realm database backend:" in line:
            status = line.split("Pocket Realm database backend:", 1)[1].strip()
    expected_status = "SQLITE" if backend == "sqlite" else "MYSQL"
    if status is None or not status.startswith(expected_status):
        raise RuntimeError(
            f"backend selection not verified: configure status={status!r}, "
            f"expected {expected_status} (the fail-loud CMake overlay is "
            "required evidence; never trust a SQLITE claim without it)")
    build_ninja = CMANGOS_BUILD / "build.ninja"
    if not build_ninja.is_file():
        raise RuntimeError(
            f"backend selection not verified: {build_ninja} missing; the "
            "generated build graph is the ground-truth evidence")
    ninja = build_ninja.read_text(encoding="utf-8", errors="replace")
    expected_define = "DO_SQLITE" if backend == "sqlite" else "DO_MYSQL"
    other_define = "DO_MYSQL" if backend == "sqlite" else "DO_SQLITE"
    if f"-D{expected_define}" not in ninja:
        raise RuntimeError(
            f"backend selection compile define -D{expected_define} absent "
            f"from {build_ninja}")
    if f"-D{other_define}" in ninja:
        raise RuntimeError(
            f"backend selection compile define -D{other_define} unexpectedly "
            f"present in {build_ninja}")


def package_seed_transcripts(stage_root: Path) -> dict:
    """Run the manifest seeder (host) and ship its four transcripts as
    deterministic gzip assets (mtime=0) under assets/seed/, verifying
    the raw transcript digests against the append-only baseline first -
    a staging build never ships seed content the baseline does
    not pin (the integrity chain, extended to the APK assets)."""
    import gzip
    import sys
    import tempfile
    sys.path.insert(0, str(ROOT / "tools"))
    import seed_sqlite_from_manifest as seeder  # noqa: E402
    baseline = json.loads(
        (ROOT / "schemas" / "sqlite-seed-baseline.json")
        .read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as tmp:
        rc, summary = seeder.seed(Path(tmp) / "db", Path(tmp) / "tr",
                                  False, 0)
        if rc != 0:
            raise RuntimeError("seed failed; refusing to stage transcripts")
        digests = summary.get("transcript_digests")
        if digests != baseline.get("transcript_digests"):
            raise RuntimeError(
                "seed transcript digests diverge from the append-only "
                "baseline - regenerate the baseline deliberately (clean "
                "--write-baseline run) before staging")
        assets = stage_root / "assets" / "seed"
        assets.mkdir(parents=True, exist_ok=True)
        # a staging dir rewritten across the .gz -> .sqlz rename must
        # not ship BOTH names (the stale .gz would gunzip to the raw
        # transcript inside the APK alongside the compressed .sqlz)
        for stale in assets.glob("*.sql.gz"):
            stale.unlink()
        out: dict[str, dict] = {}
        for db, digest in sorted(digests.items()):
            raw = (Path(tmp) / "tr" / f"{db}.sql").read_bytes()
            if hashlib.sha256(raw).hexdigest() != digest:
                raise RuntimeError(f"{db}: transcript digest mismatch")
            packed = gzip.compress(raw, 9, mtime=0)
            # .sqlz, NOT .sql.gz: AGP's asset merge transparently
            # gunzips *.gz assets (extension-keyed - it decompresses and
            # strips the suffix at merge), which would ship the raw
            # 118.7 MiB transcripts deflated instead of the pinned
            # 22.71 MiB gzip and break the on-device GZIPInputStream
            # read. The MariaDB migrations ship as .sqlz for exactly
            # this reason; the seed adopts the same convention.
            (assets / f"{db}.sqlz").write_bytes(packed)
            out[db] = {
                "sha256": digest,
                "size": len(raw),
                "gzip_sha256": hashlib.sha256(packed).hexdigest(),
                "gzip_size": len(packed),
            }
        return out


def stage(llvm: Path) -> dict:
    # The sqlite lane stages into a SIBLING root -
    # never the shared MariaDB staging dir (that would poison Gradle's
    # committed-lockfile realm gate) - and ships
    # the seed transcripts as gzip assets, digest-verified against the
    # append-only baseline.
    stage_root = BUILD / ("realm-staging-sqlite" if BACKEND == "sqlite"
                          else "realm-staging")
    stage = stage_root / "jniLibs" / TARGET_ABI
    provenance = stage_root / "BUILD_PROVENANCE.json"
    stage.mkdir(parents=True, exist_ok=True)
    records = []
    readelf = llvm / "llvm-readelf.exe"
    strip = llvm / "llvm-strip.exe"
    allowed = {"libz.so", "libdl.so", "libm.so", "libc++_shared.so", "libc.so"}
    if TARGET_ABI == "arm64-v8a":
        allowed |= {"libllama.so", "libllama-common.so", "libggml.so", "libggml-base.so", "libggml-cpu.so"}
    for name in ("libpocket_realmd_runtime.so", "libpocket_world_runtime.so"):
        source = CMANGOS_BUILD / "pocket-runtime-build" / name
        target = stage / name
        shutil.copy2(source, target)
        run([strip, "--strip-unneeded", target])
        dynamic = output([readelf, "-dW", target])
        needed = sorted(line.split("[")[1].split("]")[0] for line in dynamic.splitlines() if "(NEEDED)" in line)
        unexpected = set(needed) - allowed
        if unexpected:
            raise RuntimeError(f"unexpected DT_NEEDED for {name}: {sorted(unexpected)}")
        program = output([readelf, "-lW", target])
        aligns = [int(line.split()[-1], 16) for line in program.splitlines() if line.lstrip().startswith("LOAD ")]
        if not aligns or max(aligns) < MAX_PAGE or any(value < MAX_PAGE for value in aligns):
            raise RuntimeError(f"{name} is not 16 KB page-compatible: {aligns}")
        records.append({"path": target.relative_to(ROOT).as_posix(), "size": target.stat().st_size,
                        "sha256": sha256(target), "needed": needed, "load_alignments": aligns})
    if TARGET_ABI == "arm64-v8a":
        # vendored llama.cpp runtime ships as staged shared libraries so the
        # Gradle jniLibs Sync picks them up with the world runtime
        for source in sorted((ROOT / "native/llm/prebuilt/arm64-v8a").glob("*.so")):
            target = stage / source.name
            shutil.copy2(source, target)
            # vendored libs face the same ELF gates as the runtime libs: a
            # re-vendored build with 4K LOAD segments or new transitive deps
            # must fail here, not at dlopen on a 16K-page device
            dynamic = output([readelf, "-dW", target])
            needed = sorted(line.split("[")[1].split("]")[0] for line in dynamic.splitlines() if "(NEEDED)" in line)
            unexpected = set(needed) - allowed
            if unexpected:
                raise RuntimeError(f"unexpected DT_NEEDED for {source.name}: {sorted(unexpected)}")
            program = output([readelf, "-lW", target])
            aligns = [int(line.split()[-1], 16) for line in program.splitlines() if line.lstrip().startswith("LOAD ")]
            if not aligns or max(aligns) < MAX_PAGE or any(value < MAX_PAGE for value in aligns):
                raise RuntimeError(f"{source.name} is not 16 KB page-compatible: {aligns}")
            records.append({"path": target.relative_to(ROOT).as_posix(), "size": target.stat().st_size,
                            "sha256": sha256(target), "needed": needed, "load_alignments": aligns})
    record = {
        "schema": 1, "built_at_utc": datetime.now(timezone.utc).isoformat(), "abi": TARGET_ABI,
        "min_api": 26, "elf_max_page_size": "0x4000", "playerbots": True,
        "auction_house_bot": False, "cmangos_commit": CMANGOS_COMMIT,
        "playerbots_commit": PLAYERBOTS_COMMIT,
        "database_backend": BACKEND,
        # Record only the overlays actually applied to THIS build: registry
        # entries may declare a "backends" filter (e.g. the sqlite-only
        # connection hardening must not appear in a mysql lockfile's
        # provenance).
        "cmangos_source_overlays": [
            dict(entry) for entry in CMANGOS_OVERLAYS
            if BACKEND in entry.get("backends", ("mysql", "sqlite"))],
        "playerbots_source_overlays": PLAYERBOTS_OVERLAYS,
        # Content pin of every native/patches/ file compiled into this
        # build: an edited patch file makes
        # the committed lockfile stale, loudly.
        "patches_content": patches_content_digests(),
        "mariadb_connector_c": {"url": CONNECTOR_URL, "commit": CONNECTOR_COMMIT,
                                "license": "LGPL-2.1-or-later"} if BACKEND == "mysql" else None,
        "artifacts": records,
    }
    if TARGET_ABI == "arm64-v8a":
        record["llama_cpp"] = {"commit": "6d05498314db1b57f81c271080018aa2d0b89be9",
                               "vendored": "native/llm/prebuilt/arm64-v8a"}
    if BACKEND == "sqlite":
        # The dual-provider window ships the seed as COMPRESSED assets
        # (~22.7 MiB gzip vs ~118.7 MiB raw), digest-bound to the
        # append-only baseline.
        record["seed_transcripts"] = package_seed_transcripts(stage_root)
    provenance.parent.mkdir(parents=True, exist_ok=True)
    provenance.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    lock_record = {key: value for key, value in record.items() if key != "built_at_utc"}
    # The committed MariaDB-lane lockfile is the shipped-provider pin verified
    # by Gradle; a SQLite-lane build records itself in a sibling file so the
    # dual-provider window never moves the MariaDB pin implicitly.
    lockfile = LOCKFILE
    if BACKEND == "sqlite":
        lockfile = LOCKFILE.with_name(
            LOCKFILE.name.replace(".json", "-sqlite.json"))
    lock_bytes = (json.dumps(lock_record, indent=2) + "\n").encode("utf-8")
    lockfile.write_bytes(lock_bytes)
    if BACKEND == "sqlite":
        # Identity source: the APK asset the Kotlin control plane's
        # loadAndVerifySqliteIdentity consumes. The asset bytes are the
        # LOCK-RECORD form (built_at_utc excluded), byte-identical to the
        # committed sibling lockfile by construction - Gradle's
        # validateRealmRuntime asserts asset == lockfile, so the engine's
        # verified identity and the lockfile pin can never diverge.
        # Byte-stability matters: built_at_utc would make every rebuild a
        # different asset; the lock-record is deterministic for the same
        # inputs.
        asset = stage_root / "assets" / "database" / "provider-sqlite" / \
            "BUILD_PROVENANCE.json"
        asset.parent.mkdir(parents=True, exist_ok=True)
        asset.write_bytes(lock_bytes)
    return record


def write_lockfiles() -> list[str]:
    """Warm-dir lockfile regeneration (T0.3): refresh every committed
    realm-runtime lockfile's SOURCE-side pins (patches_content, the
    overlay registries, the submodule commits) without running a build.

    What this mode is for: an overlay edit trips patches_content in every
    lane's lockfile at once, and the tripwire pin
    (test_lockfiles_pin_patches_content) goes red until they are refreshed.
    Rebuilding all lanes to do that costs hours; the artifacts, seed
    transcripts and connector pins stay byte-pinned to the last FULL build
    either way - this mode refreshes exactly the fields that are derivable
    from the working tree and leaves the rest untouched, so the committed
    lockfile again describes "these patch bytes, last built into these
    artifacts". A full lane rebuild remains the only way to move the
    artifact pins; treating a regenerated lockfile as a fresh build would
    be wrong (the recorded .so digests still correspond to the previous
    patch bytes until a rebuild lands)."""
    updated: list[str] = []
    lanes = [
        ("x86_64", "mysql", "schemas/realm-runtime-lockfile.json"),
        ("x86_64", "sqlite", "schemas/realm-runtime-lockfile-sqlite.json"),
        ("arm64-v8a", "mysql", "schemas/realm-runtime-lockfile-arm64-v8a.json"),
        ("arm64-v8a", "sqlite", "schemas/realm-runtime-lockfile-arm64-v8a-sqlite.json"),
    ]
    for abi, backend, name in lanes:
        path = ROOT / name
        if not path.is_file():
            continue
        record = json.loads(path.read_text(encoding="utf-8"))
        record["cmangos_commit"] = CMANGOS_COMMIT
        record["playerbots_commit"] = PLAYERBOTS_COMMIT
        record["cmangos_source_overlays"] = [
            dict(entry) for entry in CMANGOS_OVERLAYS
            if backend in entry.get("backends", ("mysql", "sqlite"))]
        record["playerbots_source_overlays"] = PLAYERBOTS_OVERLAYS
        record["patches_content"] = patches_content_digests()
        lock_bytes = (json.dumps(record, indent=2) + "\n").encode("utf-8")
        if path.read_bytes() != lock_bytes:
            path.write_bytes(lock_bytes)
            updated.append(name)
        if backend == "sqlite":
            # The SQLite lane's APK identity asset is BYTE-IDENTICAL to
            # the sibling lockfile by construction (Gradle asserts the
            # equality at packaging; loadAndVerifySqliteIdentity consumes
            # it on device). A real build rewrites both; the warm-dir
            # regen must keep that invariant or the first
            # -PsqliteProvider assembly fails on a stale asset. Artifact
            # pins inside remain the last full build's - the same
            # documented tradeoff as the lockfile itself.
            asset = (NATIVE / f".build-o09-{abi}" / "realm-staging-sqlite" /
                     "assets" / "database" / "provider-sqlite" /
                     "BUILD_PROVENANCE.json")
            if asset.is_file() and asset.read_bytes() != lock_bytes:
                asset.write_bytes(lock_bytes)
    if not updated:
        print("lockfiles already current (no source-side pins changed)")
    else:
        print("regenerated source-side pins in:")
        for name in updated:
            print(f"  {name}")
        print("NOTE: artifact pins still correspond to the last FULL lane "
              "build - run a real build before shipping rebuilt binaries.")
    return updated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=("x86_64", "arm64-v8a"), default="x86_64")
    parser.add_argument("--backend", choices=("mysql", "sqlite"), default="mysql",
                        help="database backend: the SQLITE cache variable is the real "
                             "CMake switch (MySQL is the else-default). sqlite builds "
                             "skip connector-c and stage into the sibling realm-staging-sqlite "
                             "root with the sibling -sqlite lockfile.")
    parser.add_argument("--configure-only", action="store_true",
                        help="apply overlays, run the CMake configure, verify the "
                             "fail-loud backend selection, restore, and stop. No "
                             "compile, no staging.")
    parser.add_argument("--write-lockfiles", action="store_true",
                        help="regenerate the SOURCE-side pins (patches_content, "
                             "overlay registries, submodule commits) in every "
                             "committed realm-runtime lockfile without building. "
                             "Artifact pins keep describing the last full build; "
                             "the warm-dir regen exists so an overlay edit does "
                             "not require three lane rebuilds to re-green the "
                             "patches_content tripwire.")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    if args.write_lockfiles:
        write_lockfiles()
        return 0
    select_abi(args.abi)
    global BACKEND
    BACKEND = args.backend
    if not args.configure_only and args.backend == "mysql":
        # Connector/C is a MySQL-lane dependency only; a sqlite full build
        # must not touch the MariaDB mirror at all.
        prepare_connector_source()
    # Refuse to consume arbitrary working-tree edits.  Only after this clean
    # check succeeds is cleanup armed; a rejected dirty tree is never touched.
    cmangos = NATIVE / "cmangos"
    tracked_dirty = subprocess.run(["git", "diff", "--quiet"], cwd=cmangos).returncode != 0 or \
        subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=cmangos).returncode != 0
    untracked = output(["git", "ls-files", "--others", "--exclude-standard"], cmangos)
    if tracked_dirty or untracked:
        raise RuntimeError(
            "CMaNGOS submodule has unrecorded changes; clean it before "
            "building. If a previous build was killed mid-run (its restore "
            "was skipped), the overlaid files are still applied - restore "
            "the tracked ones with: git -C native/cmangos checkout -- . "
            "(the backend-selection overlay touches the submodule ROOT "
            "CMakeLists.txt, not just src/) and remove the untracked "
            "overlay file with: git -C native/cmangos clean -fd "
            "src/game/Chat/PocketRealmInteraction.cpp")
    cleanup_armed = True
    record = None
    try:
        prepare_cmangos_source()
        llvm, _ = configure_and_build(args.force, backend=args.backend,
                                      configure_only=args.configure_only)
        if not args.configure_only:
            # BOTH backends stage. The sqlite lane
            # stages into its sibling root + sibling lockfile; the MariaDB
            # staging dir and committed lockfile are never touched by it.
            record = stage(llvm)
    finally:
        if cleanup_armed:
            restore_cmangos_source()
            # Invariant, not convention: after every completed build the
            # submodule must be byte-pristine. A restore miss here would
            # otherwise surface only as the NEXT run's refusal.
            leftover = output(["git", "status", "--porcelain"], cmangos)
            if leftover:
                raise RuntimeError(
                    "post-build submodule drift (restore missed a file):\n"
                    f"{leftover}")
    if record is not None:
        print(json.dumps(record, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
