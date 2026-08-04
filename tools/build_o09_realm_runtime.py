#!/usr/bin/env python3
"""Reproducibly build and stage the current Android x86_64 realm libraries.

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
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old in text:
        path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")
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


def restore_cmangos_source() -> None:
    """Restore the pinned submodule byte-for-byte after the overlay build."""
    replace_anchor(
        NATIVE / "cmangos" / "src" / "game" / "Maps" / "GridMap.cpp",
        MMAP_GUARD_ANDROID,
        MMAP_GUARD_UPSTREAM,
    )
    replace_anchor(
        NATIVE / "cmangos" / "src" / "mangosd" / "Master.cpp",
        WORLD_THREAD_ANDROID,
        WORLD_THREAD_UPSTREAM,
    )
    replace_anchor(
        NATIVE / "cmangos" / "src" / "shared" / "Database" / "SqlOperations.cpp",
        RESULT_QUEUE_ANDROID,
        RESULT_QUEUE_UPSTREAM,
    )


def configure_and_build(force: bool) -> tuple[Path, Path]:
    ndk, cmake, ninja, llvm = tools()
    deps = NATIVE / ".deps" / "prefix-x86_64"
    required = [deps / "include" / "openssl" / "ssl.h", deps / "lib" / "libssl.a",
                deps / "lib" / "libcrypto.a", deps / "lib" / "cmake" / "Boost-1.86.0"]
    if not all(path.exists() for path in required):
        raise RuntimeError("O03 x86_64 OpenSSL/Boost dependencies are missing; run scripts/build_native.py first")
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
    common = ["-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
              f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", "-DANDROID_ABI=x86_64",
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
         f"-DMYSQL_EXTRA_LIBRARIES={ndk / 'toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/x86_64-linux-android/26/libz.so'}",
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
        "schema": 1, "built_at_utc": datetime.now(timezone.utc).isoformat(), "abi": "x86_64",
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
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    prepare_connector_source()
    prepare_cmangos_source()
    try:
        llvm, _ = configure_and_build(args.force)
        record = stage(llvm)
    finally:
        restore_cmangos_source()
    print(json.dumps(record, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
