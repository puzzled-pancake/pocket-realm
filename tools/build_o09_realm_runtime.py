#!/usr/bin/env python3
"""Reproducibly build and stage the current Android realm libraries.

The O13 product runtime compiles the pinned Playerbots module but keeps it
disabled unless an app-generated measured profile is supplied. AHBot remains
excluded. Historical O09/O12 zero-bot behavior is therefore still selectable.
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
CMANGOS_COMMIT = "c096bada9e4ed23ad4ca706c67160a26d7121337"
PLAYERBOTS_COMMIT = "1abeac646f4be02bfb47abcc779f3f9089d67f3e"
MAX_PAGE = 0x4000


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
        "id": "mmap-disabled-load-guard",
        "path": "src/game/Maps/GridMap.cpp",
        "reason": "Do not enter MMapManager::loadMap when mmap.enabled=0; the disabled manager intentionally has no map instance.",
    },
    {
        "id": "embedded-world-thread-rearm",
        "path": "src/mangosd/Master.cpp",
        "reason": "Re-arm CMaNGOS process-global stop state immediately before each embedded world-thread launch.",
    },
    {
        "id": "result-callback-outside-queue-lock",
        "path": "src/shared/Database/SqlOperations.cpp",
        "reason": "Execute async result callbacks outside the result-queue mutex so callbacks may safely issue direct statements while the database worker publishes another result.",
    },
]
PLAYERBOTS_OVERLAYS = [
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
        "id": "low-cpu-locality-telemetry",
        "paths": [
            "playerbot/RandomPlayerbotMgr.h",
            "playerbot/RandomPlayerbotMgr.cpp",
        ],
        "reason": "Sample bot locality and operation rates once per ten seconds instead of scanning the full population on every manager pass.",
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
PB_CONFIG_HEADER_UPSTREAM = """    bool randomBotAutoCreate;
    uint32 minRandomBots, maxRandomBots;
"""
PB_CONFIG_HEADER_ANDROID = """    bool randomBotAutoCreate;
    uint32 pocketGenerationBatchSize, pocketGenerationYieldMs;
    uint32 minRandomBots, maxRandomBots;
"""
PB_CONFIG_CPP_UPSTREAM = """    randomBotAutoCreate = config.GetBoolDefault("AiPlayerbot.RandomBotAutoCreate", true);
    minRandomBots = config.GetIntDefault("AiPlayerbot.MinRandomBots", 50);
"""
PB_CONFIG_CPP_ANDROID = """    randomBotAutoCreate = config.GetBoolDefault("AiPlayerbot.RandomBotAutoCreate", true);
    pocketGenerationBatchSize = config.GetIntDefault("PocketRealm.GenerationBatchSize", 5);
    pocketGenerationYieldMs = config.GetIntDefault("PocketRealm.GenerationYieldMs", 250);
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
PB_MGR_INCLUDE_UPSTREAM = """#include "WorldPosition.h"
#include <map>
#include <list>
"""
PB_MGR_INCLUDE_ANDROID = """#include "WorldPosition.h"
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
PB_MGR_LOGIN_UPSTREAM = """void RandomPlayerbotMgr::OnBotLoginInternal(Player * const bot)
{
    sLog.outDetail("%u/%d Bot %s logged in", GetPlayerbotsAmount(), sRandomPlayerbotMgr.GetMaxAllowedBotCount(), bot->GetName());
"""
PB_MGR_LOGIN_ANDROID = """void RandomPlayerbotMgr::OnBotLoginInternal(Player * const bot)
{
    lowCpuLoginEvents.push_back(time(nullptr));
    sLog.outDetail("%u/%d Bot %s logged in", GetPlayerbotsAmount(), sRandomPlayerbotMgr.GetMaxAllowedBotCount(), bot->GetName());
"""


def run(args: list[str | Path], cwd: Path | None = None) -> None:
    print("+", " ".join(map(str, args)))
    subprocess.run([str(value) for value in args], cwd=cwd, check=True)


def output(args: list[str | Path], cwd: Path | None = None) -> str:
    return subprocess.check_output([str(value) for value in args], cwd=cwd, text=True).strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


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
    bin_dir = ndk / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin"
    return ndk, cmake_root / "bin" / "cmake.exe", cmake_root / "bin" / "ninja.exe", bin_dir


def replace_anchor(path: Path, old: str, new: str) -> None:
    data = path.read_bytes()
    newline = b"\r\n" if b"\r\n" in data else b"\n"
    line_endings = lambda value: value.replace("\n", newline.decode("ascii")).encode("utf-8")
    old_bytes = line_endings(old)
    new_bytes = line_endings(new)
    # Check the upstream anchor first.  `char fp[...]` is a substring of
    # `const char fp[...]`; testing the replacement first would falsely report
    # the old declaration as already patched on Clang/ARM.
    if old_bytes in data:
        path.write_bytes(data.replace(old_bytes, new_bytes, 1))
        return
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
    if subprocess.run(["git", "status", "--porcelain"], cwd=cmangos,
                      capture_output=True, text=True, check=True).stdout.strip():
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
        cmangos / "src" / "mangosd" / "Master.cpp",
        WORLD_THREAD_UPSTREAM,
        WORLD_THREAD_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "shared" / "Database" / "SqlOperations.cpp",
        RESULT_QUEUE_UPSTREAM,
        RESULT_QUEUE_ANDROID,
    )
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
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_INCLUDE_UPSTREAM, PB_MGR_INCLUDE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_TELEMETRY_TYPE_UPSTREAM, PB_MGR_TELEMETRY_TYPE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_GETTER_UPSTREAM, PB_MGR_GETTER_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_FIELDS_UPSTREAM, PB_MGR_FIELDS_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_SCAN_UPSTREAM, PB_MGR_SCAN_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_SCAN_CALL_UPSTREAM, PB_MGR_SCAN_CALL_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_TELEPORT_UPSTREAM, PB_MGR_TELEPORT_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_RANDOMIZE_UPSTREAM, PB_MGR_RANDOMIZE_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_LOGIN_UPSTREAM, PB_MGR_LOGIN_ANDROID)


def restore_cmangos_source() -> None:
    """Restore the pinned submodule byte-for-byte after the overlay build."""
    restore_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Maps" / "GridMap.cpp",
        MMAP_GUARD_ANDROID,
        MMAP_GUARD_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "mangosd" / "Master.cpp",
        WORLD_THREAD_ANDROID,
        WORLD_THREAD_UPSTREAM,
    )
    restore_anchor(
        NATIVE / "cmangos" / "src" / "shared" / "Database" / "SqlOperations.cpp",
        RESULT_QUEUE_ANDROID,
        RESULT_QUEUE_UPSTREAM,
    )


def restore_anchor(path: Path, applied: str, original: str) -> None:
    """Undo an overlay if present, while making cleanup idempotent."""
    data = path.read_bytes()
    newline = b"\r\n" if b"\r\n" in data else b"\n"
    line_endings = lambda value: value.replace("\n", newline.decode("ascii")).encode("utf-8")
    applied_bytes = line_endings(applied)
    original_bytes = line_endings(original)
    if applied_bytes in data:
        path.write_bytes(data.replace(applied_bytes, original_bytes, 1))
    elif original_bytes not in data:
        raise RuntimeError(f"source overlay cleanup anchor drift: {path}")


def configure_and_build(force: bool) -> tuple[Path, Path]:
    ndk, cmake, ninja, llvm = tools()
    deps = NATIVE / ".deps" / ("prefix-x86_64" if TARGET_ABI == "x86_64" else "prefix-arm64")
    required = [deps / "include" / "openssl" / "ssl.h", deps / "lib" / "libssl.a",
                deps / "lib" / "libcrypto.a", deps / "lib" / "cmake" / "Boost-1.86.0"]
    if not all(path.exists() for path in required):
        raise RuntimeError(
            f"O03 {TARGET_ABI} OpenSSL/Boost dependencies are missing; "
            "run scripts/build_native.py first"
        )
    connector_cache = CONNECTOR_BUILD / "CMakeCache.txt"
    cached_source_matches = (not connector_cache.is_file() or
        f"CMAKE_HOME_DIRECTORY:INTERNAL={SOURCE.as_posix()}" in
        connector_cache.read_text(encoding="utf-8", errors="replace").replace("\\", "/"))
    if force or not cached_source_matches:
        shutil.rmtree(CONNECTOR_BUILD, ignore_errors=True)
    if force:
        shutil.rmtree(CMANGOS_BUILD, ignore_errors=True)
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
    connector = CONNECTOR_BUILD / "libmariadb" / "libmariadbclient.a"
    if not connector.is_file():
        raise RuntimeError("Connector/C static library missing after build")

    cmangos = NATIVE / "cmangos"
    CMANGOS_BUILD.mkdir(parents=True, exist_ok=True)
    run([cmake, "-S", cmangos, "-B", CMANGOS_BUILD, *common,
         "-DBUILD_GAME_SERVER=ON", "-DBUILD_LOGIN_SERVER=ON", "-DBUILD_SCRIPTDEV=ON",
         "-DBUILD_EXTRACTORS=OFF", "-DBUILD_PLAYERBOTS=ON", "-DBUILD_AHBOT=OFF",
         "-DBUILD_DEPRECATED_PLAYERBOT=OFF", "-DBUILD_POCKET_RUNTIME=ON",
         f"-DPOCKET_RUNTIME_DIR={NATIVE / 'realm-runtime'}", "-DDO_MYSQL=ON", "-DDO_SQLITE=OFF",
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
         f"-DMYSQL_INCLUDE_DIR={SOURCE / 'include'}", f"-DMYSQL_LIBRARY={connector}",
         f"-DMYSQL_EXTRA_LIBRARIES={zlib}",
         f"-DCMAKE_CXX_FLAGS=-I{CONNECTOR_BUILD / 'include'}", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"])
    run([cmake, "--build", CMANGOS_BUILD, "--target", "pocket_realmd_runtime",
         "pocket_world_runtime", "-j", str(os.cpu_count() or 4)])
    return llvm, cmake


def stage(llvm: Path) -> dict:
    STAGE.mkdir(parents=True, exist_ok=True)
    records = []
    readelf = llvm / "llvm-readelf.exe"
    strip = llvm / "llvm-strip.exe"
    allowed = {"libz.so", "libdl.so", "libm.so", "libc++_shared.so", "libc.so"}
    for name in ("libpocket_realmd_runtime.so", "libpocket_world_runtime.so"):
        source = CMANGOS_BUILD / "pocket-runtime-build" / name
        target = STAGE / name
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
    record = {
        "schema": 1, "built_at_utc": datetime.now(timezone.utc).isoformat(), "abi": TARGET_ABI,
        "min_api": 26, "elf_max_page_size": "0x4000", "playerbots": True,
        "auction_house_bot": False, "cmangos_commit": CMANGOS_COMMIT,
        "playerbots_commit": PLAYERBOTS_COMMIT,
        "cmangos_source_overlays": CMANGOS_OVERLAYS,
        "playerbots_source_overlays": PLAYERBOTS_OVERLAYS,
        "mariadb_connector_c": {"url": CONNECTOR_URL, "commit": CONNECTOR_COMMIT,
                                "license": "LGPL-2.1-or-later"},
        "artifacts": records,
    }
    PROVENANCE.parent.mkdir(parents=True, exist_ok=True)
    PROVENANCE.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    lock_record = {key: value for key, value in record.items() if key != "built_at_utc"}
    LOCKFILE.write_text(json.dumps(lock_record, indent=2) + "\n", encoding="utf-8")
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=("x86_64", "arm64-v8a"), default="x86_64")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    select_abi(args.abi)
    prepare_connector_source()
    # Refuse to consume arbitrary working-tree edits.  Only after this clean
    # check succeeds is cleanup armed; a rejected dirty tree is never touched.
    cmangos = NATIVE / "cmangos"
    if subprocess.run(["git", "status", "--porcelain"], cwd=cmangos,
                      capture_output=True, text=True, check=True).stdout.strip():
        raise RuntimeError("CMaNGOS submodule has unrecorded changes; clean it before building")
    cleanup_armed = True
    try:
        prepare_cmangos_source()
        llvm, _ = configure_and_build(args.force)
        record = stage(llvm)
    finally:
        if cleanup_armed:
            restore_cmangos_source()
    print(json.dumps(record, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
