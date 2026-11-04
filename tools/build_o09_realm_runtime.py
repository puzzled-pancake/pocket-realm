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
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:
    import common
try:
    from tools import flavor_layout
except ImportError:
    import flavor_layout
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
CMANGOS_COMMIT = "ce83805d48f9c98b2af617096be5347acb8a1f17"
PLAYERBOTS_COMMIT = "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
MAX_PAGE = 0x4000
BACKEND = "mysql"
# Active flavor (--flavor; default vanilla). The vanilla build pins are the
# constants above and must agree with schemas/flavor.json (select_flavor
# asserts it); tbc/wotlk resolve their pins from the flavor manifest and fail
# loud there until their forks/pins are registered (negative-test class 1).
ACTIVE_FLAVOR = "vanilla"
ACTIVE_CMANGOS_COMMIT = CMANGOS_COMMIT
ACTIVE_PLAYERBOTS_COMMIT = PLAYERBOTS_COMMIT
ACTIVE_CORE_SUBMODULE = "native/cmangos"
ACTIVE_BOTS_SUBMODULE = "native/playerbots"


def select_flavor(flavor_id: str) -> None:
    """Resolve the active flavor's pins from schemas/flavor.json and verify
    them against the checkouts BEFORE any build work. Vanilla additionally
    asserts its manifest pins equal this driver's constants — the driver and
    the manifest are two views of one pin, and a divergence is exactly the
    provenance split this file must never carry again (see the o11 split
    repair, decision D36).

    The verified groups are the ones a realm-runtime BUILD consumes:
    server_core + bots. db_content is the DATA lanes' gate (its tbc/wotlk
    pins resolve at S4, after these S2/S3 builds — the same scoping rule the
    o11 extractor lane applies with groups=("server_core",))."""
    global ACTIVE_FLAVOR, ACTIVE_CMANGOS_COMMIT, ACTIVE_PLAYERBOTS_COMMIT
    global ACTIVE_CORE_SUBMODULE, ACTIVE_BOTS_SUBMODULE
    layout = flavor_layout.resolve(flavor_id)
    pins = flavor_layout.require_flavor_pins(flavor_id, groups=("server_core", "bots"))
    if flavor_id == "vanilla":
        if pins.get("server_core") != CMANGOS_COMMIT:
            raise RuntimeError(
                f"vanilla server_core pin drifted: flavor.json says "
                f"{pins.get('server_core')}, driver pins {CMANGOS_COMMIT} — "
                "reconcile before building (provenance split, decision D36)"
            )
        if pins.get("bots") != PLAYERBOTS_COMMIT:
            raise RuntimeError(
                f"vanilla bots pin drifted: flavor.json says {pins.get('bots')}, "
                f"driver pins {PLAYERBOTS_COMMIT} — reconcile before building"
            )
        cmangos_commit, bots_commit = CMANGOS_COMMIT, PLAYERBOTS_COMMIT
    else:
        cmangos_commit = pins["server_core"]
        bots_commit = pins["bots"]
    ACTIVE_FLAVOR = flavor_id
    ACTIVE_CMANGOS_COMMIT = cmangos_commit
    ACTIVE_PLAYERBOTS_COMMIT = bots_commit
    ACTIVE_CORE_SUBMODULE = layout.core_submodule
    ACTIVE_BOTS_SUBMODULE = layout.bots_submodule


def select_abi(abi: str) -> None:
    """Select isolated paths and Android target flags before any build work.
    Runs after select_flavor: the build root and lockfile name resolve through
    flavor_layout (the naming schema of record) so a non-vanilla lane builds
    into and records its OWN flavor-suffixed names, never the shared vanilla
    ones. Vanilla resolves to the historical literals byte-for-byte."""
    global TARGET_ABI, BUILD, SOURCE, CONNECTOR_BUILD, CMANGOS_BUILD, STAGE, PROVENANCE, LOCKFILE
    if abi not in {"x86_64", "arm64-v8a"}:
        raise ValueError(f"unsupported realm ABI: {abi}")
    layout = flavor_layout.resolve(ACTIVE_FLAVOR)
    TARGET_ABI = abi
    BUILD = ROOT / layout.o09_build_root(abi)
    SOURCE = BUILD / "sources" / "mariadb-connector-c"
    CONNECTOR_BUILD = BUILD / "mariadb-connector"
    CMANGOS_BUILD = BUILD / "cmangos"
    STAGE = BUILD / "realm-staging" / "jniLibs" / abi
    PROVENANCE = BUILD / "realm-staging" / "BUILD_PROVENANCE.json"
    LOCKFILE = ROOT / layout.realm_runtime_lockfile(abi)
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
        "id": "embedded-io-context-restart",
        "backends": ["mysql", "sqlite"],
        "path": "src/mangosd/Master.cpp",
        "reason": "Restart the asio io_context at the top of every embedded network start so network threads service the listener after an in-process stop/start cycle (run() returns immediately after stop() until restart()).",
    },
    {
        "id": "embedded-queue-thread-join",
        "backends": ["mysql", "sqlite"],
        "paths": ["src/mangosd/Master.cpp", "src/game/World/World.h", "src/game/World/World.cpp"],
        "reason": "Join the LFG/BattleGround queue threads on embedded stop via World::StopQueueThreads(); a second embedded start would otherwise assign over joinable std::thread slots and std::terminate the host process.",
    },
    {
        "id": "result-callback-outside-queue-lock",
        "backends": ["mysql", "sqlite"],
        "path": "src/shared/Database/SqlOperations.cpp",
        "reason": "Execute async result callbacks outside the result-queue mutex so callbacks may safely issue direct statements while the database worker publishes another result.",
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
        "id": "win-named-pipe-socket-field",
        "backends": ["mysql"],
        "path": "src/shared/Database/DatabaseMysql.cpp",
        "reason": "Windows MSVC lane only: host=='.' selects the named-pipe protocol but the native nulls the unix_socket field, so libmariadb dials its DEFAULT pipe name ('MySQL') and the app-private mariadbd pipe (the conf's socket field) never answers. Pass the connection string's second field as the pipe name - the exact mirror of the POSIX '.' branch one #else down (proven live 2026-10-07: realmd failed DB_CONNECT with \"pipe: MySQL\").",
    },
    {
        "id": "fail-loud-backend-selection",
        "backends": ["mysql", "sqlite"],
        "path": "CMakeLists.txt",
        "reason": "Refuse the no-op -DDO_MYSQL/-DDO_SQLITE cache defines, refuse PostgreSQL, and print the selected backend at configure time; the real switch is the SQLITE cache variable with MySQL as the else-default.",
    },
    {
        "id": "win-static-openssl-only",
        "backends": ["mysql", "sqlite"],
        "path": "CMakeLists.txt",
        "reason": "Windows MSVC lane only (POCKET_REALM_WIN_DEPS): route OPENSSL_LIBRARIES/OPENSSL_DEBUG_LIBRARIES and the include dir at the pinned vcpkg STATIC OpenSSL instead of the shipped dep/lib prebuilt IMPORT libs, which silently add a dynamic libssl-3-x64.dll dependency the app image does not carry (MSVC link order lets late playerbots references resolve from whichever OpenSSL library appears later on the link line). No effect on non-WIN32 lanes.",
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
    {
        "id": "tbc-icu-apple-guard",
        "backends": ["mysql", "sqlite"],
        "path": "CMakeLists.txt",
        "reason": "TBC-only branch fact (W5): the pinned cmangos-tbc tree is the only branch gating its ICU find on `if (UNIX OR APPLE)` - classic and wotlk gate `if (APPLE)` - and the NDK toolchain sets UNIX=1 while the android dep prefix stages no ICU, so the tbc Android configure died at FindICU before any target existed. Mirrors the o11 lane's o11-cmangos-tbc-icu-apple-guard.patch. Configure-inert on WIN32 (both guards false) and never linked by the server (staged DT_NEEDED allowlist: libc/libdl/libm/libc++_shared/z), so narrowing the guard cannot change the produced binaries.",
    },
    {
        "id": "wotlk-bgmap-unlink",
        "backends": ["mysql", "sqlite"],
        "paths": [
            "src/game/Maps/Map.h",
            "src/game/Maps/Map.cpp",
            "src/game/BattleGround/BattleGround.cpp",
        ],
        "reason": "WotLK-only: a battleground and its map hold raw pointers to each other and neither cleared the other's on destruction. At shutdown World::CleanupsBeforeStop deletes every battleground and then MapManager::UnloadAll deletes their maps, whose destructor read m_bg->GetTypeId() from the freed battleground (access violation with any battleground open). Each side now clears the other's pointer when it goes first, and the map only queues the battleground's removal while it still has one. Classic and TBC have empty map destructors.",
    },
]

# --- S1 overlay split (S1 item 4; consumed by CP-2/S2) -------------
# The overlay table above is split into two definitions BY SCOPE:
#   * "expansion-neutral" — the SQLite-backend-injection/engine-layer family:
#     semantics are flavor-independent (the database dialect layer is the same
# code in every cmangos branch). the applying code still re-verifies each anchor
#     text against the branch before applying it.
#   * "flavor" — overlays whose anchor text/semantics are branch facts
#     (Master.cpp lifecycle family, PocketRealmInteraction, GridMap/MoveMap
#     guards); these live in a per-flavor registration and MUST be
# re-derived per branch before that flavor builds.
# This mapping records the scope; the applied anchor code above is unchanged
# (vanilla's applied sequence stays byte-identical).
OVERLAY_SCOPES = {
    "authenticated-nearby-use-open": "flavor",
    "mmap-disabled-load-guard": "flavor",
    "mmap-loadmap-graceful-miss": "flavor",
    "mmap-loadallmaptiles-graceful-miss": "flavor",
    "embedded-world-thread-rearm": "flavor",
    "embedded-io-context-restart": "flavor",
    "embedded-queue-thread-join": "flavor",
    "result-callback-outside-queue-lock": "expansion-neutral",
    "db-async-null-guard": "expansion-neutral",
    "win-named-pipe-socket-field": "expansion-neutral",
    "fail-loud-backend-selection": "expansion-neutral",
    "win-static-openssl-only": "expansion-neutral",
    "runtime-dialect-truncate": "expansion-neutral",
    "runtime-dialect-anticheat-prune": "expansion-neutral",
    "db-sqlite-connection-hardening": "expansion-neutral",
    "tbc-icu-apple-guard": "flavor",
    "wotlk-bgmap-unlink": "flavor",
}
# TBC re-derived anchor variants (S2 item 3 / CP-2). The tbc branch
# (puzzled-pancake/mangos-tbc pocket-realm @ ffbf97675) drifts from classic at
# exactly two core overlay sites (verified anchor-by-anchor against both
# pinned trees); the overlay INTENT is unchanged, only the surrounding
# upstream text differs. Each variant was derived against the pinned tbc tree:
#   POCKET_SESSION_FIELD  - tbc WorldSession.h has no m_clientTimeDelay; the
#     interact-time field slots next to m_Tutorials as in classic.
#   POCKET_CHAT           - tbc reads the whisper body conditionally
#     (LANG_ADDON peek); the Pocket Realm trigger is an ordinary-language
#     self-whisper, so the gate lands after the same read, before msg.empty().
POCKET_SESSION_FIELD_TBC_UPSTREAM = """        uint32 m_Tutorials[8];
        TutorialDataState m_tutorialState;
"""
POCKET_SESSION_FIELD_TBC_ANDROID = """        uint32 m_lastPocketRealmInteractTime = 0;
        uint32 m_Tutorials[8];
        TutorialDataState m_tutorialState;
"""
POCKET_CHAT_TBC_UPSTREAM = """        case CHAT_MSG_WHISPER:
        {
            std::string to, msg;
            recv_data >> to;

            // WHISPER channel is available for addons since 2.1.0
            if (lang == LANG_ADDON)
                recv_data.read(msg , false);
            else
                recv_data >> msg;

            if (msg.empty())
                break;
"""
POCKET_CHAT_TBC_ANDROID = """        case CHAT_MSG_WHISPER:
        {
            std::string to, msg;
            recv_data >> to;

            // WHISPER channel is available for addons since 2.1.0
            if (lang == LANG_ADDON)
                recv_data.read(msg , false);
            else
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

# wotlk S2 re-derivation (CP-2, 2026-10-06): at all five core_anchor_pair
# sites the wotlk pinned tree (cmangos-wotlk pocket-realm c3a8d86f) carries
# the SAME drifted upstream text as tbc - verified byte-identical (unique
# CRLF match) against native/cmangos-wotlk during this build's
# re-derivation. Each wotlk variant aliases the tbc text explicitly, so a
# later tbc-only drift fails the wotlk build loud instead of silently
# reusing, and vice versa.
POCKET_SESSION_FIELD_WOTLK_UPSTREAM = POCKET_SESSION_FIELD_TBC_UPSTREAM
POCKET_SESSION_FIELD_WOTLK_ANDROID = POCKET_SESSION_FIELD_TBC_ANDROID
POCKET_CHAT_WOTLK_UPSTREAM = POCKET_CHAT_TBC_UPSTREAM
POCKET_CHAT_WOTLK_ANDROID = POCKET_CHAT_TBC_ANDROID

# The mmap overlays at wotlk (same re-derivation): upstream moved the
# grid-load navmesh guard out of GridMap.cpp into Map::LoadMapAndVMap and
# ALREADY implements the MMAP_GUARD overlay's semantics there (IsEnabled
# gate + per-tile IsMMapTileLoaded check before loadMap), so the wotlk leg
# pins that existing text (old == new: a present-or-fail tripwire whose
# restore is a no-op) instead of transforming it. loadMap /
# loadAllMapTiles kept their classic shape but became instance-aware
# (m_loadedMMaps + packInstanceId(mapId, instanceId)), so the
# assert -> graceful-return overlays re-derive on the new text with the
# classic overlays' exact log wording and returns.
MMAP_GUARD_WOTLK_ANCHOR = """    if (MMAP::MMapFactory::createOrGetMMapManager()->IsEnabled())
        if (!MMAP::MMapFactory::createOrGetMMapManager()->IsMMapTileLoaded(GetId(), GetInstanceId(), gx, gy))
            MMAP::MMapFactory::createOrGetMMapManager()->loadMap(sWorld.GetDataPath(), GetId(), GetInstanceId(), gx, gy, 0);
"""
MMAP_LOADMAP_WOTLK_UPSTREAM = """        auto itr = m_loadedMMaps.find(packInstanceId(mapId, instanceId));
        MANGOS_ASSERT(itr != m_loadedMMaps.end()); // must not occur here as it would not be thread safe - only in loadMapData through loadMapInstance
"""
MMAP_LOADMAP_WOTLK_ANDROID = """        auto itr = m_loadedMMaps.find(packInstanceId(mapId, instanceId));
        if (itr == m_loadedMMaps.end())
        {
            sLog.outError("MMAP:loadMap: navmesh data for map %u was never registered (missing mmaps/%03u.mmap); pathfinding disabled for this map", mapId, mapId);
            return false;
        }
"""
MMAP_LOADALL_WOTLK_UPSTREAM = """    void MMapManager::loadAllMapTiles(std::string const& basePath, uint32 mapId, uint32 instanceId)
    {
        auto itr = m_loadedMMaps.find(packInstanceId(mapId, instanceId));
        MANGOS_ASSERT(itr != m_loadedMMaps.end());
"""
MMAP_LOADALL_WOTLK_ANDROID = """    void MMapManager::loadAllMapTiles(std::string const& basePath, uint32 mapId, uint32 instanceId)
    {
        auto itr = m_loadedMMaps.find(packInstanceId(mapId, instanceId));
        if (itr == m_loadedMMaps.end())
        {
            sLog.outError("MMAP:loadAllMapTiles: navmesh data for map %u was never registered (missing mmaps/%03u.mmap); tile preload skipped", mapId, mapId);
            return;
        }
"""


def core_anchor_pair(classic_upstream: str, classic_android: str,
                     tbc_upstream: str, tbc_android: str,
                     wotlk_upstream: str, wotlk_android: str) -> tuple[str, str]:
    """The (upstream, applied) anchor text pair for the ACTIVE flavor's branch.

    Classic text everywhere except the flavors whose pinned trees drift:
    tbc drifts at exactly the five sites documented above (S2 item 3 /
    CP-2), and wotlk re-derived its own variants at its S2 build (2026-10-06)
    - byte-identical to tbc's text at all five sites, aliased explicitly
    above so the two flavors can never silently drift together. A missing
    variant fails loud in replace_anchor, never silently skips."""
    if ACTIVE_FLAVOR == "tbc":
        return tbc_upstream, tbc_android
    if ACTIVE_FLAVOR == "wotlk":
        return wotlk_upstream, wotlk_android
    return classic_upstream, classic_android


def core_anchor_restore_pair(classic_upstream: str, classic_android: str,
                             tbc_upstream: str, tbc_android: str,
                             wotlk_upstream: str, wotlk_android: str) -> tuple[str, str]:
    """(applied, original) for restore_anchor, per the ACTIVE flavor — the
    argument order restore_anchor expects, derived from the same per-flavor
    pairs the apply step used (a plain core_anchor_pair here would hand
    restore (upstream, applied) and silently no-op via its present-original
    check — exactly the drift class the post-build tripwire exists for)."""
    if ACTIVE_FLAVOR == "tbc":
        return tbc_android, tbc_upstream
    if ACTIVE_FLAVOR == "wotlk":
        return wotlk_android, wotlk_upstream
    return classic_android, classic_upstream


# Per-flavor overlay registration: the flavor overlay ids each branch's build
# may apply. Vanilla registered first; tbc registered at its build after
# the re-derivation documented above (all eight flavor-scoped overlays
# re-anchored, none dropped); wotlk registered at its own build
# (2026-10-08) on the re-derivation already present above — the five
# core_anchor_pair sites alias the tbc text (verified byte-identical against
# native/cmangos-wotlk during the 2026-10-06 DLL build) and the three mmap
# overlays carry wotlk's own re-derived anchors (Map::LoadMapAndVMap carries
# the guard upstream; the instance-aware m_loadedMMaps lookups re-derive the
# graceful-miss semantics). Same sorted flavor-scoped list tbc got: all
# eight flavor overlays apply, none dropped.
# tbc-only overlay ids, registered for tbc IN ADDITION to the shared
# flavor-scoped set; vanilla/wotlk keep the shared set EXACTLY (their trees
# already gate ICU on APPLE-only, so registering tbc-icu-apple-guard there
# would put an unapplied row into their lockfiles and move the vanilla
# lane's applied-overlay set — invariant 1's byte identity). The delta is
# pinned by tests/test_s1_flavor_parameterization.py::
# test_o09_overlay_split_resolves_registered_flavor_sets.
_TBC_ONLY_OVERLAY_IDS = {"tbc-icu-apple-guard"}
_WOTLK_ONLY_OVERLAY_IDS = {"wotlk-bgmap-unlink"}
_FLAVOR_SCOPED_OVERLAY_IDS = sorted(
    overlay_id for overlay_id, scope in OVERLAY_SCOPES.items() if scope == "flavor"
)
PER_FLAVOR_OVERLAY_REGISTRATION = {
    "vanilla": sorted(set(_FLAVOR_SCOPED_OVERLAY_IDS) - _TBC_ONLY_OVERLAY_IDS - _WOTLK_ONLY_OVERLAY_IDS),
    "tbc": sorted(set(_FLAVOR_SCOPED_OVERLAY_IDS) - _WOTLK_ONLY_OVERLAY_IDS),
    "wotlk": sorted(set(_FLAVOR_SCOPED_OVERLAY_IDS) - _TBC_ONLY_OVERLAY_IDS),
}


def overlays_for(flavor_id: str, backend: str) -> list[dict]:
    """The overlay table a flavor's build applies, in application order:
    expansion-neutral entries in shared-table order, plus the flavor's
    registered overlay set. A flavor without a registered per-flavor overlay
    set is refused loud (expected before its S2 re-derivation)."""
    # The overlay table speaks FAMILIES (mysql/sqlite); the desktop win lane
    # speaks backends (sqlite/mariadb). "mariadb" is the mysql family —
    # prepare/restore_cmangos_source already treat BACKEND in
    # ("mysql", "mariadb") as one lane — so map it before selection, or the
    # backends filter drops every entry and the missing-guard below refuses
    # a lane that actually applies the mysql overlay set.
    if backend == "mariadb":
        backend = "mysql"
    registered = PER_FLAVOR_OVERLAY_REGISTRATION.get(flavor_id)
    if registered is None:
        raise RuntimeError(
            f"flavor {flavor_id!r} has no registered per-flavor overlay set — "
            "re-derive the overlay definitions per branch first (port plan S2 "
            "item 3 / CP-2), then register them in PER_FLAVOR_OVERLAY_REGISTRATION"
        )
    unknown = sorted(set(registered) - set(OVERLAY_SCOPES))
    if unknown:
        raise RuntimeError(
            f"flavor {flavor_id!r} registers unknown overlay ids {unknown}; "
            "known ids: " + ", ".join(sorted(OVERLAY_SCOPES))
        )
    selected = []
    for entry in CMANGOS_OVERLAYS:
        if backend not in entry.get("backends", ("mysql", "sqlite")):
            continue
        if OVERLAY_SCOPES[entry["id"]] == "expansion-neutral" or entry["id"] in registered:
            selected.append(entry)
    missing = sorted(
        overlay_id for overlay_id in registered
        if OVERLAY_SCOPES[overlay_id] == "flavor"
        and overlay_id not in {entry["id"] for entry in selected}
    )
    if missing:
        raise RuntimeError(
            f"flavor {flavor_id!r} registers overlay ids missing from the overlay "
            f"table (or filtered out for backend {backend!r}): {missing}"
        )
    return selected
POCKET_INTERACT_SOURCE = NATIVE / "patches" / "cmangos" / "PocketRealmInteraction.cpp"


def pocket_interact_source(flavor_id: str) -> Path:
    """R1-1 (native-server round 2): the nearby-interact overlay source is
    PER-FLAVOR — the chest path's opening-spell derivation is client-
    specific (vanilla emulates the client's own fixed Opening cast;
    tbc/wotlk consult the player's own qualifying spell through the core's
    lock store, PocketRealmInteraction-<flavor>.cpp). Port plan S2.3's
    per-client re-derivation: an expansion flavor WITHOUT its variant is a
    fail-loud, never a silent vanilla byte ride."""
    scoped = NATIVE / "patches" / "cmangos" / f"PocketRealmInteraction-{flavor_id}.cpp"
    if scoped.is_file():
        return scoped
    if flavor_id == "vanilla":
        return POCKET_INTERACT_SOURCE
    raise RuntimeError(
        f"missing per-flavor nearby-interact source {scoped.name} — port "
        "plan S2.3 requires the chest path re-derived per client (the "
        "shared vanilla source must not ride an expansion build)")
AHBOT_UPDATE_GATE_UPSTREAM = """void AhBot::Update()
{
    if (sWorld.IsShutdowning())
        return;
"""
AHBOT_UPDATE_GATE_ANDROID = """void AhBot::Update()
{
    // POCKET REALM: never spawn the checker thread without a loaded
    // config - ForceUpdate dereferences config state (updateInterval,
    // item sets) that is garbage when Initialize() never ran to success.
    if (!sAhBotConfig.enabled)
        return;

    if (sWorld.IsShutdowning())
        return;
"""
AHBOT_CONFIG_DISABLE_UPSTREAM = """bool AhBotConfig::Initialize()
{
    if (!config.SetSource(SYSCONFDIR"ahbot.conf", "AHBot_"))
"""
AHBOT_CONFIG_DISABLE_ANDROID = """bool AhBotConfig::Initialize()
{
    // POCKET REALM: hard-disable until a config actually loads. The
    // early return below used to leave `enabled` uninitialized; a fresh
    // process zeroes it (Android never observed a problem), but the
    // embedded in-process lane recycles the heap across world boots and
    // the second boot read garbage that enabled the disabled lane.
    enabled = false;
    if (!config.SetSource(SYSCONFDIR"ahbot.conf", "AHBot_"))
"""


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
        "id": "random-bot-pool-provisioning-repair",
        "paths": [
            "playerbot/PlayerbotAIConfig.cpp",
            "playerbot/RandomPlayerbotMgr.cpp",
        ],
        "reason": "Keep the random-bot login set yieldable on an accumulated multi-pool datadir (LVF-bot-actors-003+008): scope the GetBots pool view to the allowlist so foreign profiles' 'add' rows can no longer hold the world 'full' and burn the login budget on refused accounts; admit cap-saturated accounts to the allowlist and rotate headroom before minting in GetOrCreateAccount; make the reset op clear the refusal state end-to-end (event table, pick set, allowlist refill, fresh provisioning); re-derive allowlist coverage from the login DB at boot so accounts minted beyond the configured count stay visible.",
    },
    {
        "id": "ahbot-config-absent-hard-disable",
        "paths": [
            "ahbot/AhBot.cpp",
            "ahbot/AhBotConfig.cpp",
        ],
        "reason": "When ahbot.conf is absent, AhBotConfig::Initialize() returns before writing `enabled`, leaving it UNINITIALIZED, and AhBot::Update() never checks enabled before spawning the detached checker thread - whose ForceUpdate then dereferences garbage config state (updateInterval, item sets). A fresh process zeroes the heap so Android never observed it, but the embedded in-process lane recycles the heap across world boots and the second boot crashed the host JVM. Hard-disable on config-absence and gate Update on enabled: identical no-activity outcome on both platforms, minus the uninitialized read.",
    },
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
        "id": "chat-path-hardening",
        "paths": [
            "playerbot/PlayerbotAIConfig.cpp",
            "playerbot/strategy/actions/SayAction.cpp",
            "playerbot/strategy/actions/DebugAction.cpp",
            "playerbot/PlayerbotAI.cpp",
        ],
        "reason": "Hardening on upstream chat paths that run with the bot LLM disabled: the reply-type switch answers raid mentions in raid chat instead of a private tell, the debug command keeps moderator powers for real moderator sessions only, a whisper always counts as direct address, and an unparseable LLMApiEndpoint port fails closed at config load instead of aborting world boot.",
    },
    {
        "id": "retired-llm-fork-call-sites",
        "paths": [
            "playerbot/AiFactory.cpp",
            "playerbot/strategy/triggers/RpgTriggers.cpp",
            "playerbot/PlayerbotSecurity.cpp",
        ],
        "reason": "The pinned fork's bot-LLM call sites (cloud-lane 'ai chat' grant, cloud RPG chance cap, authored invite refusal) call into the retired PlayerbotLlmMemory/PlayerbotLlmPersona overlays; restore the upstream grant, chance and refusal text. The per-reason refusal dedupe stays.",
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
EMBEDDED_IO_CONTEXT_RESTART_UPSTREAM = """bool Master::StartNetworkEmbedded(uint32_t network_threads)
{
    if (network_threads == 0) network_threads = 1;
"""
EMBEDDED_IO_CONTEXT_RESTART_ANDROID = """bool Master::StartNetworkEmbedded(uint32_t network_threads)
{
    if (network_threads == 0) network_threads = 1;

    // Embedded re-init: asio's run() returns immediately after stop() until
    // restart() is called. Without this, a second embedded start in the same
    // process spawns network threads whose run() calls exit instantly and
    // the listener stays unserviced.
    m_context.restart();
"""
# HONEST LISTENER (boot-race root cause): the world thread runs but its
# first World::Update has not completed when the launch line returns - a
# session processed inside the first ticks raced the boot's deferred
# world-thread legs and fail-fasted the process (0xC0000409, three harness
# reproductions). READY-obeying consumers already wait (world_runtime.cpp
# gates READY on the same first-tick flag); this closes the residual
# window for anything that does NOT obey READY: no realmlist ONLINE row,
# no LFG/BG queue threads, and no listening socket until the loop has
# ticked once and survived.
MASTER_LISTENER_GATE_UPSTREAM = """    m_worldThread->setPriority(MaNGOS::Priority_Highest);

    // Mark the realm online (realmlist row).
    {
"""
MASTER_LISTENER_GATE_ANDROID = """    m_worldThread->setPriority(MaNGOS::Priority_Highest);

    {
        static constexpr unsigned int FIRST_TICK_WAIT_MS = 300'000u;
        if (!pocket_world_first_tick_wait(FIRST_TICK_WAIT_MS))
        {
            sLog.outError("PocketRealm: world loop produced no first tick in %ums; listener not opened",
                          FIRST_TICK_WAIT_MS);
            return false;
        }
    }

    // Mark the realm online (realmlist row).
    {
"""
MASTER_GATE_DECL_UPSTREAM = """#define sMaster MaNGOS::Singleton<Master>::Instance()
#endif
"""
MASTER_GATE_DECL_ANDROID = """#define sMaster MaNGOS::Singleton<Master>::Instance()

// The honest-listener first-tick gate (see StartNetworkEmbedded in
// Master.cpp). Implemented in the pocket world runtime; returns nonzero
// once the world loop's first World::Update has completed and survived.
// Namespace scope - outside the class, where extern "C" is legal.
extern "C" int pocket_world_first_tick_wait(unsigned int timeout_ms);
#endif
"""
EMBEDDED_QUEUE_THREAD_JOIN_UPSTREAM = """    // Mirror the tail of Run() but without signals/CLI. The facade has already
    // set World::StopNow before calling this.
    if (m_worldThread)
    {
        m_worldThread->wait();
    }

    m_context.stop();
"""
EMBEDDED_QUEUE_THREAD_JOIN_ANDROID = """    // Mirror the tail of Run() but without signals/CLI. The facade has already
    // set World::StopNow before calling this.
    if (m_worldThread)
    {
        m_worldThread->wait();
    }

    // The LFG/BG queue threads exit their update loops once World::IsStopped()
    // fires but stay joinable; join them here so the next embedded start can
    // re-assign their std::thread slots (assigning over a joinable thread
    // terminates the host process).
    sWorld.StopQueueThreads();

    m_context.stop();
"""
WORLD_QUEUE_STOP_DECL_UPSTREAM = """        void StartLFGQueueThread();
        void StartBGQueueThread();
"""
WORLD_QUEUE_STOP_DECL_ANDROID = """        void StartLFGQueueThread();
        void StartBGQueueThread();
        void StopQueueThreads();
"""
WORLD_QUEUE_STOP_DEF_UPSTREAM = """void World::StartBGQueueThread()
{
    m_bgQueueThread = std::thread([&]()
    {
        m_bgQueue.Update();
    });
}
"""
WORLD_QUEUE_STOP_DEF_ANDROID = """void World::StartBGQueueThread()
{
    m_bgQueueThread = std::thread([&]()
    {
        m_bgQueue.Update();
    });
}

void World::StopQueueThreads()
{
    // Embedded re-init counterpart to the Start*QueueThread launches: the
    // only other join site is ~World, which never runs for the static sWorld
    // instance. Callers must have set World::StopNow first (the queue update
    // loops exit on World::IsStopped()).
    if (m_lfgQueueThread.joinable())
        m_lfgQueueThread.join();
    if (m_bgQueueThread.joinable())
        m_bgQueueThread.join();
}
"""
WIN_STATIC_OPENSSL_UPSTREAM = """  set(OPENSSL_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/dep/lib/include")
  set(OPENSSL_LIBRARIES
     "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_release/libcrypto.lib"
     "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_release/libssl.lib"
     "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_release/legacy.lib"
  )
  set(OPENSSL_DEBUG_LIBRARIES
     "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_debug/libcrypto.lib"
     "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_debug/libssl.lib"
     "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_debug/legacy.lib"
  )
"""
WIN_STATIC_OPENSSL_ANDROID = """  if(POCKET_REALM_WIN_DEPS)
    # Pocket Realm Windows lane (tools/build_win_realm_runtime.py passes
    # -DPOCKET_REALM_WIN_DEPS=<vcpkg installed tree>): the pinned STATIC
    # OpenSSL supplies every symbol. The shipped prebuilt IMPORT libs would
    # otherwise silently add a dynamic libssl-3-x64.dll dependency the app
    # image does not carry - MSVC link order lets late playerbots
    # references resolve from whichever OpenSSL library appears later on
    # the link line.
    set(OPENSSL_INCLUDE_DIR "${POCKET_REALM_WIN_DEPS}/include")
    set(OPENSSL_LIBRARIES
       "${POCKET_REALM_WIN_DEPS}/lib/libcrypto.lib"
       "${POCKET_REALM_WIN_DEPS}/lib/libssl.lib"
    )
    set(OPENSSL_DEBUG_LIBRARIES "${OPENSSL_LIBRARIES}")
  else()
    set(OPENSSL_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/dep/lib/include")
    set(OPENSSL_LIBRARIES
       "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_release/libcrypto.lib"
       "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_release/libssl.lib"
       "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_release/legacy.lib"
    )
    set(OPENSSL_DEBUG_LIBRARIES
       "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_debug/libcrypto.lib"
       "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_debug/libssl.lib"
       "${CMAKE_SOURCE_DIR}/dep/lib/${DEP_ARCH}_debug/legacy.lib"
    )
  endif()
"""
# tbc lane: the pinned cmangos-tbc tree gates its ICU find on
# `if (UNIX OR APPLE)` — classic and wotlk gate `if (APPLE)` only — and the
# NDK toolchain sets UNIX=1 while the android dep prefix stages no ICU, so
# the tbc Android configure died at FindICU before any target existed
# (archived failure evidence: out/completion/w5/o09-tbc-first-attempt-findicu.log —
# the green rerun lives under o09-tbc-arm64-build.log). Mirror of the o11 lane's
# native/o11-patches/o11-cmangos-tbc-icu-apple-guard.patch. Configure-inert
# on WIN32 (both guards false there) and the server never links ICU (the
# staged DT_NEEDED allowlist is libc/libdl/libm/libc++_shared/z).
ICU_GUARD_TBC_UPSTREAM = "if (UNIX OR APPLE)"
ICU_GUARD_TBC_ANDROID = (
    "if (APPLE) # pocket-realm o09: only Apple needs this ICU find - the NDK "
    "sets UNIX=1 while the android dep prefix stages no ICU and the server "
    "never links it (classic/wotlk gate APPLE-only already)"
)
# wotlk-bgmap-unlink (see the overlay table entry for the crash it fixes).
BGMAP_CLEAR_WOTLK_UPSTREAM = """        BattleGround* GetBG() const override { return m_bg; }
"""
BGMAP_CLEAR_WOTLK_ANDROID = """        BattleGround* GetBG() const override { return m_bg; }
        void ClearBG() { m_bg = nullptr; }
"""
BGMAP_DTOR_WOTLK_UPSTREAM = """BattleGroundMap::~BattleGroundMap()
{
    sBattleGroundMgr.GetMessager().AddMessage([instanceId = GetInstanceId(), typeId = m_bg->GetTypeId()](BattleGroundMgr* mgr)
    {
        mgr->RemoveBattleGround(instanceId, typeId);
    });
    m_bg = nullptr;
}
"""
BGMAP_DTOR_WOTLK_ANDROID = """BattleGroundMap::~BattleGroundMap()
{
    // pocket-realm: the battleground clears m_bg when it is deleted first
    // (shutdown deletes every battleground before unloading maps).
    if (m_bg)
    {
        m_bg->SetBgMap(nullptr);
        sBattleGroundMgr.GetMessager().AddMessage([instanceId = GetInstanceId(), typeId = m_bg->GetTypeId()](BattleGroundMgr* mgr)
        {
            mgr->RemoveBattleGround(instanceId, typeId);
        });
    }
    m_bg = nullptr;
}
"""
BG_DTOR_WOTLK_UPSTREAM = """    if (m_bgMap)
        m_bgMap->SetUnload();
"""
BG_DTOR_WOTLK_ANDROID = """    if (m_bgMap)
    {
        m_bgMap->SetUnload();
        m_bgMap->ClearBG(); // pocket-realm: the map must not reach back into a deleted battleground
    }
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
# wotlk-only bots overlay (see prepare_cmangos_source): the wotlk core HEAD's
# chrono rewrite of GO respawn state vs the pinned multi-expansion bots
# tree's WotLK fishing-bobber timer. Upstream anchor is present in the
# pinned tree for every flavor (the statement is not expansion-guarded);
# only the wotlk branch applies the conversion.
PB_FISH_RESPAWN_TIME_UPSTREAM = """            time_t bobberActiveTime = obj->GetRespawnTime() - FISHING_BOBBER_READY_TIME;
"""
PB_FISH_RESPAWN_TIME_WOTLK_ANDROID = """            time_t bobberActiveTime = Clock::to_time_t(obj->GetRespawnTime()) - FISHING_BOBBER_READY_TIME;
"""
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
#include <deque>
#include <map>
#include <unordered_map>
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
        // Login-spread pending set (bot guid -> mode:
        // 1 = near-player/plain spread, 2 = village-ring placement) and the
        // per-spawn-cell settler bookkeeping (designated race + count; the
        // settler marker itself persists via the "settler" event value).
        std::unordered_map<uint32, uint8> pocketLoginSpreadPending;
        std::map<uint64, std::pair<uint32, uint32>> pocketVillageCells;
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

    // Round-3 R5-3-1: the players map HOLDS bots (MovePlayerBot inserts
    // hired/converted bots at RandomPlayerbotMgr.cpp MovePlayerBot; only
    // logout erases them) — without the AI guard the "real players" count
    // pads player presence with unlabeled bots (the listing substrate's
    // "bots labeled, never padded" rule, at the telemetry level).
    std::vector<Player*> realPlayers;
    realPlayers.reserve(players.size());
    for (auto const& entry : players)
    {
        Player* player = entry.second;
        if (player && player->IsInWorld() && !player->IsGameMaster() &&
            !player->GetPlayerbotAI())
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
# Standard-SQL NOT: upstream builds the character-selection query with
# MySQL's unary '!' (" OR !" + wasRand); SQLite rejects the token outright
# (`unrecognized token: "!"`), so the need-to-increase
# selection branch returns no rows on SQLite. "NOT" is
# standard and semantically identical in MySQL.
PB_MGR_QUERY_NOT_UPSTREAM = '                            query += " OR !" + wasRand;'
PB_MGR_QUERY_NOT_ANDROID = '                            query += " OR NOT " + wasRand;'
PB_MGR_LOGIN_UPSTREAM = """void RandomPlayerbotMgr::OnBotLoginInternal(Player * const bot)
{
    sLog.outDetail("%u/%d Bot %s logged in", GetPlayerbotsAmount(), sRandomPlayerbotMgr.GetMaxAllowedBotCount(), bot->GetName());
"""

# --- Login spawn-stack relief + village ring -----------------------------
# RandomPlayerbotMgr / PlayerbotAIConfig / aiplayerbot.conf.dist.in are
# anchor-managed: the login-only forced relocation and the settler ring
# extend the payload pairs below. The forced path bypasses the level<5
# guard ONLY through the pending set armed at the login site (the
# PocketArmLoginSpread call appended to PB_MGR_LOGIN_ANDROID); every other
# RandomTeleport caller keeps today's semantics byte-for-byte. The village
# ring ships DARK: the native VillageRingCount default is 0 and no preset
# emits the keys until a profile sets villageRingCount > 0.
PB_MGR_RTEL_DECL_UPSTREAM = """        void RandomTeleport(Player* bot);
        void RandomTeleport(Player* bot, std::vector<WorldLocation> &locs, bool hearth = false, bool activeOnly = false);
"""
PB_MGR_RTEL_DECL_ANDROID = """        void RandomTeleport(Player* bot);
        void RandomTeleport(Player* bot, std::vector<WorldLocation> &locs, bool hearth = false, bool activeOnly = false, bool force = false);
        // Login-site spawn-stack relief + village ring
        void PocketArmLoginSpread(Player* bot);
        void PocketLoginSpreadTeleport(Player* bot);
        void PocketPlaceVillageRing(Player* bot);
        Player* PocketPickSpreadAnchor(Player* bot) const;
        uint64 PocketVillageCellKey(Player const* bot) const;
"""
# The declaration pair above widens the header's locs overload with the
# defaulted `force` parameter; the out-of-line DEFINITION in the pristine
# .cpp must grow the same parameter or the definition matches no
# declaration (and the level-guard payload's `!force` has no referent).
PB_MGR_RTEL_DEF_UPSTREAM = """void RandomPlayerbotMgr::RandomTeleport(Player* bot, std::vector<WorldLocation> &locs, bool hearth, bool activeOnly)
{
"""
PB_MGR_RTEL_DEF_ANDROID = """void RandomPlayerbotMgr::RandomTeleport(Player* bot, std::vector<WorldLocation> &locs, bool hearth, bool activeOnly, bool force)
{
"""
PB_MGR_LEVEL_GUARD_UPSTREAM = """	if (bot->GetLevel() < 5)
		return;
"""
PB_MGR_LEVEL_GUARD_ANDROID = """	// The forced (login-spread) path bypasses the guard
	// below level 5 - exactly the stacked sync band; every other caller
	// keeps today's guard.
	if (bot->GetLevel() < 5 && !force)
		return;
"""
PB_MGR_RANDOMIZE_SETTLER_UPSTREAM = """            if (randomiser)
            {
                Randomize(player);
                return true;
            }
"""
PB_MGR_RANDOMIZE_SETTLER_ANDROID = """            if (GetEventValue(bot, "settler"))
            {
                // Designated villagers are stable - the
                // randomize event (level/gear reroll) would churn the
                // village, so settlers skip it and just re-arm the cadence
                // (pristine ScheduleRandomize takes the cadence explicitly;
                // same urand bounds as the upstream schedule sites).
                ScheduleRandomize(bot, urand(sPlayerbotAIConfig.minRandomBotRandomizeTime, sPlayerbotAIConfig.maxRandomBotRandomizeTime));
                return true;
            }
            if (randomiser)
            {
                Randomize(player);
                return true;
            }
"""
PB_MGR_TELEPORT_EVENT_UPSTREAM = """        uint32 teleport = GetEventValue(bot, "teleport");
        if (!teleport && players.size())
        {
            if (sPlayerbotAIConfig.enableRandomTeleports)
            {
                sLog.outDetail("Bot #%d %s:%d <%s>: sent to grind", bot, player->GetTeam() == ALLIANCE ? "A" : "H", player->GetLevel(), player->GetName());
                RandomTeleportForLevel(player, true);
                ScheduleTeleport(bot);
            }
            else
            {
                sLog.outDetail("Bot #%d %s:%d <%s>: supposed to be sent to grind, but enableRandomTeleports = false", bot, player->GetTeam() == ALLIANCE ? "A" : "H", player->GetLevel(), player->GetName());
            }
            return true;
        }
"""
PB_MGR_TELEPORT_EVENT_ANDROID = """        uint32 teleport = GetEventValue(bot, "teleport");
        // The login-spread pending set fires on the same
        // event, with or without players online (a stacked world with zero
        // real players still spreads; the no-player leg places
        // level-appropriately).
        uint8 spreadMode = 0;
        if (!teleport)
        {
            auto const pendingSpread = pocketLoginSpreadPending.find(bot);
            if (pendingSpread != pocketLoginSpreadPending.end())
                spreadMode = pendingSpread->second;
        }
        if ((!teleport && players.size()) || spreadMode)
        {
            if (sPlayerbotAIConfig.enableRandomTeleports)
            {
                if (spreadMode)
                {
                    sLog.outDetail("Bot #%d %s:%d <%s>: login spread", bot, player->GetTeam() == ALLIANCE ? "A" : "H", player->GetLevel(), player->GetName());
                    PocketLoginSpreadTeleport(player);
                    ScheduleTeleport(bot);
                }
                else
                {
                    sLog.outDetail("Bot #%d %s:%d <%s>: sent to grind", bot, player->GetTeam() == ALLIANCE ? "A" : "H", player->GetLevel(), player->GetName());
                    RandomTeleportForLevel(player, true);
                    ScheduleTeleport(bot);
                }
            }
            else
            {
                // random teleports disabled: drop the pending entry too, the
                // spread must not outlive its own event
                pocketLoginSpreadPending.erase(bot);
                sLog.outDetail("Bot #%d %s:%d <%s>: supposed to be sent to grind, but enableRandomTeleports = false", bot, player->GetTeam() == ALLIANCE ? "A" : "H", player->GetLevel(), player->GetName());
            }
            return true;
        }
"""
PB_MGR_SPREAD_HELPERS_UPSTREAM = """void RandomPlayerbotMgr::ScheduleTeleport(uint32 bot, uint32 time)
{
    if (!time)
        time = 60 + urand(sPlayerbotAIConfig.randomBotTeleportMinInterval, sPlayerbotAIConfig.randomBotTeleportMaxInterval);
    SetEventValue(bot, "teleport", 1, time);
}
"""
PB_MGR_SPREAD_HELPERS_ANDROID = """void RandomPlayerbotMgr::ScheduleTeleport(uint32 bot, uint32 time)
{
    if (!time)
        time = 60 + urand(sPlayerbotAIConfig.randomBotTeleportMinInterval, sPlayerbotAIConfig.randomBotTeleportMaxInterval);
    SetEventValue(bot, "teleport", 1, time);
}

namespace
{
    // Spread ring geometry: the near-player placement ring stays inside say
    // range (ListenRange.Say = 25 yd) so relocated bots hear the anchor
    // player.
    float const POCKET_SPREAD_RING_MIN_YD = 10.0f;
    float const POCKET_SPREAD_RING_MAX_YD = 25.0f;
    // The stagger window keeps the spread's grid loads off the login wave's.
    uint32 const POCKET_SPREAD_STAGGER_MIN_SEC = 5;
    uint32 const POCKET_SPREAD_STAGGER_SPAN_SEC = 25;
    uint8 const POCKET_SPREAD_NEAR_PLAYER = 1;
    uint8 const POCKET_SPREAD_VILLAGE = 2;
    // Keep-best narrowing cap: the forced fallback never walks more than a
    // handful of candidates near the anchor.
    size_t const POCKET_SPREAD_KEEP_BEST = 8;
    // Village-ring placement attempts before keeping the stacked spot.
    uint32 const POCKET_VILLAGE_ATTEMPTS = 8;
}

uint64 RandomPlayerbotMgr::PocketVillageCellKey(Player const* bot) const
{
    // 10-yd grid cell of the spawn point, offset-encoded to stay unsigned:
    // mapId (20 bits) | cellX (22) | cellY (22).
    float const POCKET_VILLAGE_CELL_YD = 10.0f;
    int64 const cellX = (int64)(bot->GetPositionX() / POCKET_VILLAGE_CELL_YD) + (1LL << 21);
    int64 const cellY = (int64)(bot->GetPositionY() / POCKET_VILLAGE_CELL_YD) + (1LL << 21);
    return ((uint64)bot->GetMapId() << 44) | ((uint64)cellX << 22) | (uint64)cellY;
}

void RandomPlayerbotMgr::PocketArmLoginSpread(Player* bot)
{
    // Fresh level 1-4 bots log in stacked at the exact
    // racial-start coordinates (saved-position login; the periodic teleport
    // hard-returns below level 5 and only fires with players online;
    // RandomizeFirst early-returns at the starting level). Arm a ONE-SHOT
    // forced relocation that rides the normal "teleport" event with a short
    // stagger, so teleport grid loads do not stack on the login wave.
    if (!sPlayerbotAIConfig.randomBotLoginSpread)
        return;
    if (!bot || !bot->IsInWorld() || !bot->GetPlayerbotAI())
        return;
    if (bot->IsBeingTeleported() || (bot->GetSession() && bot->GetSession()->isLogingOut()))
        return;
    if (bot->InBattleGround() || bot->InBattleGroundQueue())
        return;
    if (bot->GetLevel() < 1 || bot->GetLevel() >= 5)
        return;

    uint32 const botId = bot->GetGUIDLow();
    bool settler = GetEventValue(botId, "settler") != 0;

    // Persisted villagers re-arm their ring placement (and re-register
    // the cell for this session); a spawn point that still has ring slots
    // designates new SAME-RACE settlers. Villagers stay exempt from the
    // forced relocation itself - the two mechanisms pull opposite
    // directions on the same bots, resolved here.
    if (sPlayerbotAIConfig.villageRingCount > 0)
    {
        uint64 const cell = PocketVillageCellKey(bot);
        auto cellEntry = pocketVillageCells.find(cell);
        if (cellEntry != pocketVillageCells.end())
        {
            if (!settler && cellEntry->second.first == bot->getRace() &&
                cellEntry->second.second < sPlayerbotAIConfig.villageRingCount)
            {
                ++cellEntry->second.second;
                SetEventValue(botId, "settler", 1, -1);
                settler = true;
            }
        }
        else
        {
            pocketVillageCells[cell] = std::make_pair(bot->getRace(), 1);
            if (!settler)
            {
                SetEventValue(botId, "settler", 1, -1);
                settler = true;
            }
        }
    }

    uint8 const mode = settler ? POCKET_SPREAD_VILLAGE : POCKET_SPREAD_NEAR_PLAYER;
    if (pocketLoginSpreadPending.emplace(botId, mode).second)
        ScheduleTeleport(botId, POCKET_SPREAD_STAGGER_MIN_SEC + urand(0, POCKET_SPREAD_STAGGER_SPAN_SEC));
}

Player* RandomPlayerbotMgr::PocketPickSpreadAnchor(Player* bot) const
{
    // Nearest real player on the bot's own map (the relocation stays
    // local; cross-map pulls are the periodic path's job, not the login
    // spread's).
    Player* best = nullptr;
    float bestDist = 0.0f;
    for (auto const& entry : players)
    {
        Player* player = entry.second;
        if (!player || !player->IsInWorld() || player->IsGameMaster())
            continue;
        if (player->GetMapId() != bot->GetMapId())
            continue;
        float const dist = sServerFacade.GetDistance2d(bot, player);
        if (!best || dist < bestDist)
        {
            best = player;
            bestDist = dist;
        }
    }
    return best;
}

void RandomPlayerbotMgr::PocketLoginSpreadTeleport(Player* bot)
{
    auto const pendingSpread = pocketLoginSpreadPending.find(bot->GetGUIDLow());
    if (pendingSpread == pocketLoginSpreadPending.end())
        return;
    uint8 const mode = pendingSpread->second;
    pocketLoginSpreadPending.erase(pendingSpread);

    if (mode == POCKET_SPREAD_VILLAGE)
    {
        PocketPlaceVillageRing(bot);
        return;
    }

    // Near-player placement: a mob-avoiding ring offset around the nearest
    // real player (the FleeManager pattern - the candidate ring at the
    // given radius around the startPosition, hostile orientations excluded,
    // nearest-mob distance maximized), z snapped like the placement loop.
    Player* anchor = PocketPickSpreadAnchor(bot);
    if (anchor && anchor->GetMap())
    {
        Map* map = anchor->GetMap();
        FleeManager manager(bot, frand(POCKET_SPREAD_RING_MIN_YD, POCKET_SPREAD_RING_MAX_YD), 0.0f, false, WorldPosition(anchor));
        float rx, ry, rz;
        if (manager.CalculateDestination(&rx, &ry, &rz))
        {
            float ground;
#ifdef MANGOSBOT_TWO
            ground = map->GetHeight(bot->GetPhaseMask(), rx, ry, rz + 0.5f);
#else
            ground = map->GetHeight(rx, ry, rz + 0.5f);
#endif
            if (ground > INVALID_HEIGHT)
            {
                if (bot->IsTaxiFlying())
                    bot->GetMotionMaster()->MovementExpired();
                bot->GetMotionMaster()->Clear();
                bot->TeleportTo(anchor->GetMapId(), rx, ry, 0.05f + ground, 0);
                lowCpuTeleportEvents.push_back(time(nullptr));
                bot->SendHeartBeat();
                bot->GetPlayerbotAI()->Reset(true);
                sLog.outDetail("Login spread: bot %s ringed near player %s", bot->GetName(), anchor->GetName());
                return;
            }
        }
    }

    // No real player online, or the ring found no valid point: plain
    // level-appropriate teleport with the level guard bypassed - never a
    // stay-stacked no-op. The near-player keep-best narrowing resolves
    // BEFORE RandomTeleport is entered (and independent of activeOnly: the
    // forced leg enters with activeOnly = false), so the empty-candidate
    // recursion inside - which re-enters WITHOUT the force flag and
    // dead-ends on the level guard for exactly this population - is never
    // reached from the forced path and can never bypass this filter.
    std::vector<WorldLocation> locs = locsPerLevelCache[bot->GetLevel()];
    if (anchor)
    {
        // `near` is a reserved token under MSVC (16-bit segment modifier
        // legacy), so the local is nearLocs; identical semantics.
        std::vector<WorldLocation> nearLocs;
        WorldLocation const* bestAny = nullptr;
        float bestAnyDist = 0.0f;
        for (auto const& loc : locs)
        {
            if (loc.mapid != anchor->GetMapId())
            {
                float const d = WorldPosition(loc).fDist(WorldPosition(anchor));
                if (!bestAny || d < bestAnyDist)
                {
                    bestAny = &loc;
                    bestAnyDist = d;
                }
                continue;
            }
            nearLocs.push_back(loc);
        }
        if (!nearLocs.empty())
        {
            std::sort(nearLocs.begin(), nearLocs.end(), [anchor](WorldLocation const& a, WorldLocation const& b)
            {
                return WorldPosition(a).fDist(WorldPosition(anchor)) < WorldPosition(b).fDist(WorldPosition(anchor));
            });
            if (nearLocs.size() > POCKET_SPREAD_KEEP_BEST)
                nearLocs.resize(POCKET_SPREAD_KEEP_BEST);
            locs.swap(nearLocs);
        }
        else if (bestAny)
        {
            locs.clear();
            locs.push_back(*bestAny);
        }
    }
    RandomTeleport(bot, locs, false, false, true);
}

void RandomPlayerbotMgr::PocketPlaceVillageRing(Player* bot)
{
    // Place the settler on a ring around its spawn point (its current
    // stacked position), between the conf yd bounds, z snapped via GetHeight
    // exactly like the RandomTeleport placement loop. Persistence is
    // emergent: the 150-yd proximity freeze holds members, wander leaks
    // slowly, and there is deliberately no re-forming machinery.
    Map* map = bot->GetMap();
    if (!map)
        return;

    float ringMin = (float)sPlayerbotAIConfig.villageRingMinYd;
    float ringMax = (float)sPlayerbotAIConfig.villageRingMaxYd;
    if (ringMax < ringMin)
        ringMax = ringMin;
    if (ringMin < 1.0f)
        ringMin = 1.0f;

    for (uint32 attempt = 0; attempt < POCKET_VILLAGE_ATTEMPTS; ++attempt)
    {
        float const angle = frand(0.0f, 2.0f * M_PI_F);
        float const radius = frand(ringMin, ringMax);
        float const x = bot->GetPositionX() + cos(angle) * radius;
        float const y = bot->GetPositionY() + sin(angle) * radius;
        float ground;
#ifdef MANGOSBOT_TWO
        ground = map->GetHeight(bot->GetPhaseMask(), x, y, bot->GetPositionZ() + 0.5f);
#else
        ground = map->GetHeight(x, y, bot->GetPositionZ() + 0.5f);
#endif
        if (ground <= INVALID_HEIGHT)
            continue;

        bot->GetMotionMaster()->Clear();
        bot->TeleportTo(bot->GetMapId(), x, y, 0.05f + ground, 0);
        lowCpuTeleportEvents.push_back(time(nullptr));
        bot->SendHeartBeat();
        bot->GetPlayerbotAI()->Reset(true);
        sLog.outDetail("Village ring: bot %s placed %f yd off the spawn point", bot->GetName(), radius);
        return;
    }

    sLog.outError("Village ring: no valid ring offset for bot %s - kept the stacked position", bot->GetName());
}
"""
PB_D1_CONFIG_HEADER_UPSTREAM = """    uint32 randomBotTeleportNearPlayerMaxAmount;
    float randomBotTeleportNearPlayerMaxAmountRadius;
    uint32 randomBotTeleportMinInterval, randomBotTeleportMaxInterval;
"""
PB_D1_CONFIG_HEADER_ANDROID = """    uint32 randomBotTeleportNearPlayerMaxAmount;
    float randomBotTeleportNearPlayerMaxAmountRadius;
    uint32 randomBotTeleportMinInterval, randomBotTeleportMaxInterval;
    // Login-site spawn-stack relief + village ring
    bool randomBotLoginSpread;
    uint32 villageRingCount;
    uint32 villageRingMinYd;
    uint32 villageRingMaxYd;
"""
PB_D1_CONFIG_CPP_UPSTREAM = """    randomBotTeleportNearPlayerMaxAmount = config.GetIntDefault("AiPlayerbot.RandomBotTeleportNearPlayerMaxAmount", 0);
    randomBotTeleportNearPlayerMaxAmountRadius = config.GetFloatDefault("AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius", 0.0f);
"""
PB_D1_CONFIG_CPP_ANDROID = """    randomBotTeleportNearPlayerMaxAmount = config.GetIntDefault("AiPlayerbot.RandomBotTeleportNearPlayerMaxAmount", 0);
    randomBotTeleportNearPlayerMaxAmountRadius = config.GetFloatDefault("AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius", 0.0f);
    // Login-site spawn-stack relief + village ring.
    // RandomBotLoginSpread = 0 disables the forced relocation entirely;
    // VillageRingCount = 0 (the shipped default on every preset) keeps the
    // settler machinery dark.
    randomBotLoginSpread = config.GetBoolDefault("AiPlayerbot.RandomBotLoginSpread", true);
    villageRingCount = config.GetIntDefault("AiPlayerbot.VillageRingCount", 0);
    villageRingMinYd = config.GetIntDefault("AiPlayerbot.VillageRingMinYd", 10);
    villageRingMaxYd = config.GetIntDefault("AiPlayerbot.VillageRingMaxYd", 25);
"""
PB_D1_CONF_DIST_UPSTREAM = """# Limit whithin what distance between other bots they teleport to points in zones around real player
# AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius = 0
"""
PB_D1_CONF_DIST_ANDROID = """# Limit whithin what distance between other bots they teleport to points in zones around real player
# AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius = 0

# Move freshly logged-in level 1-4 bots off the racial-start stack. With a real player online they land on a mob-avoiding ring 10-25 yd around the nearest one; without any player online they get a plain level-appropriate teleport. 0 disables.
# AiPlayerbot.RandomBotLoginSpread = 1

# Per-spawn-point village ring. While a spawn point has fewer than VillageRingCount same-race level 1-4 settlers designated, new level 1-4 logins there become villagers: exempt from the login relocation and from the randomize event, placed on a ring between the yd bounds (inside say range, 25 yd). 0 = off (the shipped default).
# AiPlayerbot.VillageRingCount = 0
# AiPlayerbot.VillageRingMinYd = 10
# AiPlayerbot.VillageRingMaxYd = 25
"""


# crash-class fix: the conf loader only LOGGED an invalid response regex,
# leaving it armed for ParseResponse - where it throws inside the async
# generation worker (previously a process-abort class via the detached
# packet thread's future.get). Fail open: the broken pattern is cleared
# (an empty pattern is pass-through in every ParseResponse leg).
PB_CONFIG_REGEX_UPSTREAM = """    try {
        std::regex pattern(llmResponseStartPattern);
    }
    catch (const std::regex_error& e) {        
        sLog.outError("Regex error in %s: %s", llmResponseStartPattern.c_str(), e.what());
    }

    try {
        std::regex pattern(llmResponseEndPattern);
    }
    catch (const std::regex_error& e) {
        sLog.outError("Regex error in %s: %s", llmResponseEndPattern.c_str(), e.what());
    }

    try {
        std::regex pattern(llmResponseDeletePattern);
    }
    catch (const std::regex_error& e) {
        sLog.outError("Regex error in %s: %s", llmResponseDeletePattern.c_str(), e.what());
    }

    try {
        std::regex pattern(llmResponseSplitPattern);
    }
    catch (const std::regex_error& e) {
        sLog.outError("Regex error in %s: %s", llmResponseSplitPattern.c_str(), e.what());
    }"""
PB_CONFIG_REGEX_ANDROID = """    try {
        std::regex pattern(llmResponseStartPattern);
    }
    catch (const std::regex_error& e) {
        sLog.outError("Regex error in %s: %s - pattern DISABLED (pass-through)", llmResponseStartPattern.c_str(), e.what());
        // fail open: an empty start pattern is pass-through, never a crash
        llmResponseStartPattern.clear();
    }

    try {
        std::regex pattern(llmResponseEndPattern);
    }
    catch (const std::regex_error& e) {
        sLog.outError("Regex error in %s: %s - pattern DISABLED (pass-through)", llmResponseEndPattern.c_str(), e.what());
        llmResponseEndPattern.clear();
    }

    try {
        std::regex pattern(llmResponseDeletePattern);
    }
    catch (const std::regex_error& e) {
        sLog.outError("Regex error in %s: %s - pattern DISABLED (pass-through)", llmResponseDeletePattern.c_str(), e.what());
        llmResponseDeletePattern.clear();
    }

    try {
        std::regex pattern(llmResponseSplitPattern);
    }
    catch (const std::regex_error& e) {
        sLog.outError("Regex error in %s: %s - pattern DISABLED (pass-through)", llmResponseSplitPattern.c_str(), e.what());
        llmResponseSplitPattern.clear();
    }"""

# The upstream reply-type switch has no SRC_RAID case, so a raid mention
# (a mention) fell through to the CHAT_MSG_WHISPER default and
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

# In the SMSG_MESSAGECHAT reply armament a whisper IS direct address: the
# name-substring isMentioned heuristic and the crowd-drop it feeds
# ("!isMentioned && urand(0,4)") are say/party semantics and dropped four of
# five un-mentioned whispers before the reply armament ran.
PB_AI_WHISPER_MENTION_UPSTREAM = """                bool isMentioned = message.find(bot->GetName()) != std::string::npos;
"""
PB_AI_WHISPER_MENTION_ANDROID = """                bool isMentioned = message.find(bot->GetName()) != std::string::npos;
                // A whisper is direct address by definition -
                // the name-substring heuristic is a say/party semantic and
                // starved the whisper lane (every un-mentioned whisper was
                // dropped 4 times out of 5 before the reply armament ran)
                if (msgtype == CHAT_MSG_WHISPER)
                    isMentioned = true;
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

# The pinned playerbots fork (7e2cd2fb) carries three committed bot-LLM call
# sites whose callees (PlayerbotLlmMemory / PlayerbotLlmPersona) were patch
# overlays and are retired. These put the call sites back to upstream: the
# 'ai chat' strategy grant and the RPG chat chance read the upstream config
# only, and the invite-family refusal keeps its plain text. The per-reason
# refusal dedupe from the same fork commit stays.
PB_FORK_AIFACTORY_INCLUDE_UPSTREAM = """#include "playerbot/AiFactory.h"
#include "playerbot/PlayerbotLlmMemory.h"
"""
PB_FORK_AIFACTORY_INCLUDE_ANDROID = """#include "playerbot/AiFactory.h"
"""
PB_FORK_AIFACTORY_GRANT_UPSTREAM = """    // WS-A A1: the == 2 arm is TODAY's external behavior and stays (the
    // app emits 2 on both device-external and debug lanes); the widened
    // arm grants the strategy on the cloud lane only - CloudLaneOpen()
    // is the key AND external-tier conjunction (never the bare key), so
    // a device-lane emission can never widen.
    if (sPlayerbotAIConfig.llmEnabled == 2 ||
        (sPlayerbotAIConfig.llmEnabled > 0 && CloudLaneOpen()))
"""
PB_FORK_AIFACTORY_GRANT_ANDROID = """    if(sPlayerbotAIConfig.llmEnabled == 2)
"""
PB_FORK_RPG_INCLUDE_UPSTREAM = """#include "playerbot/PlayerbotAIConfig.h"
#include "playerbot/PlayerbotLlmMemory.h"
#include <algorithm>
"""
PB_FORK_RPG_INCLUDE_ANDROID = """#include "playerbot/PlayerbotAIConfig.h"
"""
PB_FORK_RPG_CHANCE_UPSTREAM = """    // A1b cloud pacing: the RPG lane ran at chance 100 before the cloud
    // widenings - on the cloud lane the effective chance is capped at 10
    // (pacing from cadence, not starvation; one trigger is already 5-11
    // turns and the generation quota does the real containment).
    {
        uint32 const effectiveChance = CloudLaneOpen()
            ? std::min<uint32>(sPlayerbotAIConfig.llmRpgAIChatChance, 10)
            : sPlayerbotAIConfig.llmRpgAIChatChance;
        if (!effectiveChance || !(urand(0, 99) < effectiveChance))
            return false;
    }

"""
PB_FORK_RPG_CHANCE_ANDROID = """    if (!sPlayerbotAIConfig.llmRpgAIChatChance || !(urand(0, 99) < sPlayerbotAIConfig.llmRpgAIChatChance))
        return false;

"""
PB_FORK_SECURITY_INCLUDE_UPSTREAM = """// plan E3: the authored gate-refusal bank (SecurityRefusalLine draws the
// POOL_SECURITY_REFUSE cell in the bot's archetype; overlay header, the
// module include convention)
#include "playerbot/PlayerbotLlmPersona.h"

#include <algorithm>
"""
PB_FORK_SECURITY_INCLUDE_ANDROID = """#include <algorithm>
"""
PB_FORK_SECURITY_REFUSAL_UPSTREAM = """    // plan E3: the invite-family denials (invite/leader/full-group) are
    // the one wall every group-seeking player hits, and the UI-speak
    // string reads like a system message. On the banter lane the refusal
    // draws an authored in-character line instead - delivered through
    // the SAME whisper channel below. The pool draw falls back to the
    // legacy string internally (the BusyReply precedent), so the
    // draw-failure path keeps today's text; llmBanterEnabled = 0 keeps
    // the UI-speak byte-identical (kill-switch law). LOW_LEVEL /
    // GEARSCORE / queue denials keep their actionable numbers - they
    // tell the player something they can fix, not a gate to talk past.
    if (sPlayerbotAIConfig.llmBanterEnabled && (
        reason == DenyReason::PLAYERBOT_DENY_INVITE ||
        reason == DenyReason::PLAYERBOT_DENY_IS_LEADER ||
        reason == DenyReason::PLAYERBOT_DENY_NOT_LEADER ||
        reason == DenyReason::PLAYERBOT_DENY_FULL_GROUP))
    {
        std::string const refusal = PlayerbotLlmPersona::SecurityRefusalLine(bot);
        if (!refusal.empty())
            text = refusal;
    }

    uint64 guid = from->GetObjectGuid().GetRawValue();
"""
PB_FORK_SECURITY_REFUSAL_ANDROID = """    uint64 guid = from->GetObjectGuid().GetRawValue();
"""

PB_MGR_LOGIN_ANDROID = """void RandomPlayerbotMgr::OnBotLoginInternal(Player * const bot)
{
    lowCpuLoginEvents.push_back(time(nullptr));
    sLog.outDetail("%u/%d Bot %s logged in", GetPlayerbotsAmount(), sRandomPlayerbotMgr.GetMaxAllowedBotCount(), bot->GetName());
    // The login site - arm the one-shot spawn-stack
    // spread (no-op above level 4, when disabled, or for designated
    // villagers re-arming their ring placement).
    PocketArmLoginSpread(bot);
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

# win-named-pipe-socket-field (Windows MSVC mysql lane): the "." host branch
# selects MYSQL_PROTOCOL_PIPE but drops the pipe NAME; the conf's second
# field must reach mysql_real_connect as the socket argument (libmariadb
# reads it as the pipe name under the PIPE protocol), mirroring the POSIX
# branch below it.
WIN_PIPE_SOCKET_UPSTREAM = """#ifdef _WIN32
    if (host == ".")                                        // named pipe use option (Windows)
    {
        unsigned int opt = MYSQL_PROTOCOL_PIPE;
        mysql_options(mysqlInit, MYSQL_OPT_PROTOCOL, (char const*)&opt);
        port = 0;
        unix_socket = nullptr;
    }
"""
WIN_PIPE_SOCKET_ANDROID = """#ifdef _WIN32
    if (host == ".")                                        // named pipe use option (Windows)
    {
        unsigned int opt = MYSQL_PROTOCOL_PIPE;
        mysql_options(mysqlInit, MYSQL_OPT_PROTOCOL, (char const*)&opt);
        port = 0;
        // Pocket Realm: the pipe NAME is the connection string's second
        // field; without it libmariadb dials its default pipe ("MySQL")
        // and the app-private mariadbd socket never answers (proven live
        // 2026-10-07: the connect argument carries the name through
        // Connector/C 3.4.1 - arg-only verified against the staged pipe).
        unix_socket = port_or_socket.c_str();
    }
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
    # the Android toolchain
    # MUST be the mandated E:-resident install. CMake pins whatever compiler
    # path the FIRST configure saw in its warm-dir cache
    # (CMakeFiles/*/CMakeC[XX]Compiler.cmake), so a C:-resident SDK silently
    # becomes the provenance of every artifact a warm-dir rebuild produces
    # while the invoking command lines show only E: tools. Fail at resolution,
    # before any configure can reuse such a cache.
    mandated_sdk_root = Path(r"E:\pocket_realm_toolchain\android-sdk")
    if sdk.resolve() != mandated_sdk_root.resolve():
        raise RuntimeError(
            f"ANDROID_SDK_ROOT resolves to {sdk} — the android lane toolchain "
            f"must be the mandated E:-resident install {mandated_sdk_root} "
            "(hard rule 1 / §7). A C:-resident NDK in a warm CMake compiler "
            "cache becomes the provenance of every rebuilt artifact (the "
            "R3-r5-1 class). Set ANDROID_SDK_ROOT="
            f"{mandated_sdk_root} and wipe the affected native/.build-o09-* "
            "warm dir so CMake reconfigures with the E: toolchain."
        )
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
    cmangos = NATIVE / ACTIVE_CORE_SUBMODULE.removeprefix("native/")
    actual = output(["git", "rev-parse", "HEAD"], cmangos)
    if actual != ACTIVE_CMANGOS_COMMIT:
        raise RuntimeError(f"CMaNGOS pin mismatch: {actual}")
    playerbots = NATIVE / ACTIVE_BOTS_SUBMODULE.removeprefix("native/")
    playerbots_actual = output(["git", "rev-parse", "HEAD"], playerbots)
    if playerbots_actual != ACTIVE_PLAYERBOTS_COMMIT:
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
    (mirror / ".pocket-realm-commit").write_text(ACTIVE_PLAYERBOTS_COMMIT + "\n", encoding="utf-8")
    if ACTIVE_FLAVOR == "wotlk":
        # wotlk core HEAD rewrote GO respawn state to std::chrono (2fc8161e:
        # "GameObject: Rewrite GO respawn time to std chrono"), so
        # GameObject::GetRespawnTime() now returns TimePoint. The pinned
        # multi-expansion bots tree's WotLK path (MANGOSBOT_TWO) still does
        # TimePoint - int arithmetic in the fishing-bobber timer. Convert
        # with the core's own idiom (GameObject.cpp: Clock::to_time_t) so
        # the epoch-seconds code below compiles unchanged.
        replace_anchor(
            mirror / "playerbot" / "strategy" / "actions" / "FishAction.cpp",
            PB_FISH_RESPAWN_TIME_UPSTREAM,
            PB_FISH_RESPAWN_TIME_WOTLK_ANDROID,
        )
    if ACTIVE_FLAVOR == "wotlk":
        # wotlk re-derivation: the guard lives in Map::LoadMapAndVMap and
        # its semantics are already upstream - pin the text (see
        # MMAP_GUARD_WOTLK_ANCHOR); the two MoveMap overlays re-derive on the
        # instance-aware m_loadedMMaps lookup.
        replace_anchor(
            cmangos / "src" / "game" / "Maps" / "Map.cpp",
            MMAP_GUARD_WOTLK_ANCHOR,
            MMAP_GUARD_WOTLK_ANCHOR,
        )
        replace_anchor(
            cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
            MMAP_LOADMAP_WOTLK_UPSTREAM,
            MMAP_LOADMAP_WOTLK_ANDROID,
        )
        replace_anchor(
            cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
            MMAP_LOADALL_WOTLK_UPSTREAM,
            MMAP_LOADALL_WOTLK_ANDROID,
        )
    else:
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
    # honest-listener first-tick gate: Master.h declaration + the
    # wait-between-launch-and-listen span in Master.cpp (must run AFTER the
    # WORLD_THREAD anchor, whose ANDROID text shares the launch lines)
    replace_anchor(
        cmangos / "src" / "mangosd" / "Master.h",
        MASTER_GATE_DECL_UPSTREAM,
        MASTER_GATE_DECL_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        MASTER_LISTENER_GATE_UPSTREAM,
        MASTER_LISTENER_GATE_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        EMBEDDED_IO_CONTEXT_RESTART_UPSTREAM,
        EMBEDDED_IO_CONTEXT_RESTART_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        EMBEDDED_QUEUE_THREAD_JOIN_UPSTREAM,
        EMBEDDED_QUEUE_THREAD_JOIN_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "game" / "World" / "World.h",
        WORLD_QUEUE_STOP_DECL_UPSTREAM,
        WORLD_QUEUE_STOP_DECL_ANDROID,
    )
    replace_anchor(
        cmangos / "src" / "game" / "World" / "World.cpp",
        WORLD_QUEUE_STOP_DEF_UPSTREAM,
        WORLD_QUEUE_STOP_DEF_ANDROID,
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
    replace_anchor(cmangos / "src" / "game" / "Server" / "WorldSession.h", *core_anchor_pair(
        POCKET_SESSION_FIELD_UPSTREAM, POCKET_SESSION_FIELD_ANDROID,
        POCKET_SESSION_FIELD_TBC_UPSTREAM, POCKET_SESSION_FIELD_TBC_ANDROID,
        POCKET_SESSION_FIELD_WOTLK_UPSTREAM, POCKET_SESSION_FIELD_WOTLK_ANDROID))
    replace_anchor(cmangos / "src" / "game" / "Chat" / "ChatHandler.cpp", *core_anchor_pair(
        POCKET_CHAT_UPSTREAM, POCKET_CHAT_ANDROID,
        POCKET_CHAT_TBC_UPSTREAM, POCKET_CHAT_TBC_ANDROID,
        POCKET_CHAT_WOTLK_UPSTREAM, POCKET_CHAT_WOTLK_ANDROID))
    interaction = cmangos / "src" / "game" / "Chat" / "PocketRealmInteraction.cpp"
    if interaction.exists():
        raise RuntimeError(f"source overlay target unexpectedly exists: {interaction}")
    interaction.write_bytes(pocket_interact_source(ACTIVE_FLAVOR).read_bytes())
    bot_root = mirror / "playerbot"
    # the random-bot provisioning/allowlist repair
    # ships as tracked full-file overlays under native/patches/playerbots/
    # (the submodule itself stays byte-pristine). Copied BEFORE every anchor
    # overlay below: the overlay files keep the upstream bytes at all anchor
    # sites, so the replace_anchor sequence applies on top unchanged.
    (bot_root / "RandomPlayerbotMgr.cpp").write_bytes(
        (NATIVE / "patches" / "playerbots" / "RandomPlayerbotMgr.cpp").read_bytes())
    (bot_root / "PlayerbotAIConfig.cpp").write_bytes(
        (NATIVE / "patches" / "playerbots" / "PlayerbotAIConfig.cpp").read_bytes())
    replace_anchor(bot_root / "PlayerbotAIConfig.h", PB_CONFIG_HEADER_UPSTREAM, PB_CONFIG_HEADER_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_CONFIG_REGEX_UPSTREAM, PB_CONFIG_REGEX_ANDROID)
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
    ahbot_root = mirror / "ahbot"
    replace_anchor(ahbot_root / "AhBot.cpp", AHBOT_UPDATE_GATE_UPSTREAM, AHBOT_UPDATE_GATE_ANDROID)
    replace_anchor(ahbot_root / "AhBotConfig.cpp", AHBOT_CONFIG_DISABLE_UPSTREAM, AHBOT_CONFIG_DISABLE_ANDROID)
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
    # Login spawn-stack relief + village ring
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_D1_CONFIG_CPP_UPSTREAM, PB_D1_CONFIG_CPP_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.h", PB_D1_CONFIG_HEADER_UPSTREAM, PB_D1_CONFIG_HEADER_ANDROID)
    replace_anchor(bot_root / "aiplayerbot.conf.dist.in", PB_D1_CONF_DIST_UPSTREAM, PB_D1_CONF_DIST_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.h", PB_MGR_RTEL_DECL_UPSTREAM, PB_MGR_RTEL_DECL_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_RTEL_DEF_UPSTREAM, PB_MGR_RTEL_DEF_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_LEVEL_GUARD_UPSTREAM, PB_MGR_LEVEL_GUARD_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_RANDOMIZE_SETTLER_UPSTREAM, PB_MGR_RANDOMIZE_SETTLER_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_TELEPORT_EVENT_UPSTREAM, PB_MGR_TELEPORT_EVENT_ANDROID)
    replace_anchor(bot_root / "RandomPlayerbotMgr.cpp", PB_MGR_SPREAD_HELPERS_UPSTREAM, PB_MGR_SPREAD_HELPERS_ANDROID)
    # Chat-path hardening on upstream playerbots code that runs with the
    # bot LLM disabled (raid reply channel, debug moderator gate, whisper
    # direct-address, endpoint-parse fail-closed at config load)
    replace_anchor(bot_root / "strategy" / "actions" / "SayAction.cpp", PB_SAY_RAID_CASE_UPSTREAM, PB_SAY_RAID_CASE_ANDROID)
    replace_anchor(bot_root / "strategy" / "actions" / "DebugAction.cpp", PB_DEBUG_MODGATE_UPSTREAM, PB_DEBUG_MODGATE_ANDROID)
    replace_anchor(bot_root / "PlayerbotAIConfig.cpp", PB_LLM_EP_CATCH_UPSTREAM, PB_LLM_EP_CATCH_ANDROID)
    # Retired fork-side bot-LLM call sites back to upstream
    replace_anchor(bot_root / "AiFactory.cpp", PB_FORK_AIFACTORY_INCLUDE_UPSTREAM, PB_FORK_AIFACTORY_INCLUDE_ANDROID)
    replace_anchor(bot_root / "AiFactory.cpp", PB_FORK_AIFACTORY_GRANT_UPSTREAM, PB_FORK_AIFACTORY_GRANT_ANDROID)
    replace_anchor(bot_root / "strategy" / "triggers" / "RpgTriggers.cpp", PB_FORK_RPG_INCLUDE_UPSTREAM, PB_FORK_RPG_INCLUDE_ANDROID)
    replace_anchor(bot_root / "strategy" / "triggers" / "RpgTriggers.cpp", PB_FORK_RPG_CHANCE_UPSTREAM, PB_FORK_RPG_CHANCE_ANDROID)
    replace_anchor(bot_root / "PlayerbotSecurity.cpp", PB_FORK_SECURITY_INCLUDE_UPSTREAM, PB_FORK_SECURITY_INCLUDE_ANDROID)
    replace_anchor(bot_root / "PlayerbotSecurity.cpp", PB_FORK_SECURITY_REFUSAL_UPSTREAM, PB_FORK_SECURITY_REFUSAL_ANDROID)
    replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_WHISPER_MENTION_UPSTREAM, PB_AI_WHISPER_MENTION_ANDROID)
    # DB async null-guard + fail-loud backend selection overlays
    apply_db_null_guard_overlays(cmangos / "src" / "shared" / "Database")
    replace_anchor(cmangos / "CMakeLists.txt", BACKEND_SELECT_UPSTREAM, BACKEND_SELECT_ANDROID)
    # Windows MSVC lane: keep the pinned static OpenSSL the only linked copy
    replace_anchor(cmangos / "CMakeLists.txt", WIN_STATIC_OPENSSL_UPSTREAM, WIN_STATIC_OPENSSL_ANDROID)
    # tbc lane: narrow the ICU find guard to APPLE (tbc-icu-apple-guard).
    # The classic/wotlk trees gate APPLE-only already, so this anchor pair is
    # tbc-scoped: running it for another flavor cannot match (and the missing
    # anchor would refuse loud) — exactly why the call is branch-gated here
    # and the overlay id is registered for tbc only.
    if ACTIVE_FLAVOR == "tbc":
        replace_anchor(cmangos / "CMakeLists.txt", ICU_GUARD_TBC_UPSTREAM, ICU_GUARD_TBC_ANDROID)
    # wotlk lane: unlink battleground <-> map on destruction (wotlk-bgmap-unlink).
    if ACTIVE_FLAVOR == "wotlk":
        game = cmangos / "src" / "game"
        replace_anchor(game / "Maps" / "Map.h", BGMAP_CLEAR_WOTLK_UPSTREAM, BGMAP_CLEAR_WOTLK_ANDROID)
        replace_anchor(game / "Maps" / "Map.cpp", BGMAP_DTOR_WOTLK_UPSTREAM, BGMAP_DTOR_WOTLK_ANDROID)
        replace_anchor(game / "BattleGround" / "BattleGround.cpp", BG_DTOR_WOTLK_UPSTREAM, BG_DTOR_WOTLK_ANDROID)
    # Windows mysql lane: the named-pipe connection must carry the conf's
    # pipe NAME (host == "." selects the protocol; the socket field names
    # the pipe). MySQL-lane-only: sqlite builds never compile DatabaseMysql.
    if BACKEND in ("mysql", "mariadb"):
        replace_anchor(
            cmangos / "src" / "shared" / "Database" / "DatabaseMysql.cpp",
            WIN_PIPE_SOCKET_UPSTREAM, WIN_PIPE_SOCKET_ANDROID)
    # The sqlite lane swaps in the hardened connection-layer
    # replacement files; mysql builds never touch them.
    if BACKEND == "sqlite":
        apply_sqlite_hardening(cmangos)
    verify_db_async_null_guards(cmangos)


def restore_cmangos_source() -> None:
    """Restore the pinned submodule byte-for-byte after the overlay build."""
    cmangos = NATIVE / ACTIVE_CORE_SUBMODULE.removeprefix("native/")
    interaction = cmangos / "src" / "game" / "Chat" / "PocketRealmInteraction.cpp"
    if interaction.exists():
        if interaction.read_bytes() != pocket_interact_source(ACTIVE_FLAVOR).read_bytes():
            raise RuntimeError(f"source overlay cleanup content drift: {interaction}")
        interaction.unlink()
    restore_anchor(cmangos / "src" / "game" / "Chat" / "ChatHandler.cpp", *core_anchor_restore_pair(
        POCKET_CHAT_UPSTREAM, POCKET_CHAT_ANDROID,
        POCKET_CHAT_TBC_UPSTREAM, POCKET_CHAT_TBC_ANDROID,
        POCKET_CHAT_WOTLK_UPSTREAM, POCKET_CHAT_WOTLK_ANDROID))
    restore_anchor(cmangos / "src" / "game" / "Server" / "WorldSession.h", *core_anchor_restore_pair(
        POCKET_SESSION_FIELD_UPSTREAM, POCKET_SESSION_FIELD_ANDROID,
        POCKET_SESSION_FIELD_TBC_UPSTREAM, POCKET_SESSION_FIELD_TBC_ANDROID,
        POCKET_SESSION_FIELD_WOTLK_UPSTREAM, POCKET_SESSION_FIELD_WOTLK_ANDROID))
    restore_anchor(cmangos / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_API_ANDROID, POCKET_SESSION_API_UPSTREAM)
    restore_anchor(cmangos / "src" / "game" / "Server" / "WorldSession.h", POCKET_SESSION_ENUM_ANDROID, POCKET_SESSION_ENUM_UPSTREAM)
    restore_anchor(cmangos / "src" / "game" / "World" / "World.cpp", POCKET_WORLD_CPP_ANDROID, POCKET_WORLD_CPP_UPSTREAM)
    restore_anchor(cmangos / "src" / "game" / "World" / "World.h", POCKET_WORLD_UINT_ANDROID, POCKET_WORLD_UINT_UPSTREAM)
    restore_anchor(cmangos / "src" / "game" / "World" / "World.h", POCKET_WORLD_H_ANDROID, POCKET_WORLD_H_UPSTREAM)
    if ACTIVE_FLAVOR == "wotlk":
        restore_anchor(
            cmangos / "src" / "game" / "Maps" / "Map.cpp",
            MMAP_GUARD_WOTLK_ANCHOR,
            MMAP_GUARD_WOTLK_ANCHOR,
        )
        restore_anchor(
            cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
            MMAP_LOADALL_WOTLK_ANDROID,
            MMAP_LOADALL_WOTLK_UPSTREAM,
        )
        restore_anchor(
            cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
            MMAP_LOADMAP_WOTLK_ANDROID,
            MMAP_LOADMAP_WOTLK_UPSTREAM,
        )
    else:
        restore_anchor(
            cmangos / "src" / "game" / "Maps" / "GridMap.cpp",
            MMAP_GUARD_ANDROID,
            MMAP_GUARD_UPSTREAM,
        )
        restore_anchor(
            cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
            MMAP_LOADALL_ANDROID,
            MMAP_LOADALL_UPSTREAM,
        )
        restore_anchor(
            cmangos / "src" / "game" / "MotionGenerators" / "MoveMap.cpp",
            MMAP_LOADMAP_ANDROID,
            MMAP_LOADMAP_UPSTREAM,
        )
    restore_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        WORLD_THREAD_ANDROID,
        WORLD_THREAD_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        EMBEDDED_QUEUE_THREAD_JOIN_ANDROID,
        EMBEDDED_QUEUE_THREAD_JOIN_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        EMBEDDED_IO_CONTEXT_RESTART_ANDROID,
        EMBEDDED_IO_CONTEXT_RESTART_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "mangosd" / "Master.cpp",
        MASTER_LISTENER_GATE_ANDROID,
        MASTER_LISTENER_GATE_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "mangosd" / "Master.h",
        MASTER_GATE_DECL_ANDROID,
        MASTER_GATE_DECL_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "game" / "World" / "World.cpp",
        WORLD_QUEUE_STOP_DEF_ANDROID,
        WORLD_QUEUE_STOP_DEF_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "game" / "World" / "World.h",
        WORLD_QUEUE_STOP_DECL_ANDROID,
        WORLD_QUEUE_STOP_DECL_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "shared" / "Database" / "SqlOperations.cpp",
        RESULT_QUEUE_ANDROID,
        RESULT_QUEUE_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "game" / "Globals" / "ObjectMgr.cpp",
        OBJECTMGR_TRUNCATE_CREATURE_ANDROID,
        OBJECTMGR_TRUNCATE_CREATURE_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "game" / "Globals" / "ObjectMgr.cpp",
        OBJECTMGR_TRUNCATE_GAMEOBJECT_ANDROID,
        OBJECTMGR_TRUNCATE_GAMEOBJECT_UPSTREAM,
    )
    restore_anchor(
        cmangos / "src" / "game" / "Anticheat" / "module" / "libanticheat.cpp",
        ANTICHEAT_PRUNE_ANDROID,
        ANTICHEAT_PRUNE_UPSTREAM,
    )
    restore_anchor(
        cmangos / "CMakeLists.txt",
        BACKEND_SELECT_ANDROID,
        BACKEND_SELECT_UPSTREAM,
    )
    restore_anchor(
        cmangos / "CMakeLists.txt",
        WIN_STATIC_OPENSSL_ANDROID,
        WIN_STATIC_OPENSSL_UPSTREAM,
    )
    if ACTIVE_FLAVOR == "tbc":
        restore_anchor(
            cmangos / "CMakeLists.txt",
            ICU_GUARD_TBC_ANDROID,
            ICU_GUARD_TBC_UPSTREAM,
        )
    if ACTIVE_FLAVOR == "wotlk":
        game = cmangos / "src" / "game"
        restore_anchor(game / "Maps" / "Map.h", BGMAP_CLEAR_WOTLK_ANDROID, BGMAP_CLEAR_WOTLK_UPSTREAM)
        restore_anchor(game / "Maps" / "Map.cpp", BGMAP_DTOR_WOTLK_ANDROID, BGMAP_DTOR_WOTLK_UPSTREAM)
        restore_anchor(game / "BattleGround" / "BattleGround.cpp", BG_DTOR_WOTLK_ANDROID, BG_DTOR_WOTLK_UPSTREAM)
    if BACKEND in ("mysql", "mariadb"):
        restore_anchor(
            cmangos / "src" / "shared" / "Database" / "DatabaseMysql.cpp",
            WIN_PIPE_SOCKET_ANDROID,
            WIN_PIPE_SOCKET_UPSTREAM,
        )
    if BACKEND == "sqlite":
        restore_sqlite_hardening(cmangos)
    restore_db_null_guard_overlays(cmangos / "src" / "shared" / "Database")


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
    # Shared configure locals (connector-c and cmangos configure identically);
    # computed once here — zlib is a mysql-lane link flag, never a connector
    # configure flag.
    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    ndk_triple = "x86_64-linux-android" if TARGET_ABI == "x86_64" else "aarch64-linux-android"
    zlib = ndk / "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib" / ndk_triple / "26/libz.so"
    common = ["-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={ninja}",
              f"-DCMAKE_TOOLCHAIN_FILE={toolchain}", f"-DANDROID_ABI={TARGET_ABI}",
              "-DANDROID_PLATFORM=android-26", "-DCMAKE_BUILD_TYPE=Release",
              "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"]
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

    cmangos = NATIVE / ACTIVE_CORE_SUBMODULE.removeprefix("native/")
    CMANGOS_BUILD.mkdir(parents=True, exist_ok=True)
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
        "cmangos_commit": ACTIVE_CMANGOS_COMMIT,
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
         "pocket_world_runtime", "-j",
         str(os.environ.get("POCKET_BUILD_JOBS") or (os.cpu_count() or 4))])
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
    # The transcript lane is flavor-resolved end-to-end (
    # medium) the seeder runs THIS flavor's canonical ledger
    # (flavor_layout.migration_ledger()[0]) — the old call passed no
    # manifest_path, so every flavor staged the VANILLA classic*
    # transcripts and then compared them against a per-flavor baseline that
    # could never carry transcript_digests (no tool wrote it) — the dead
    # retire path replaces with this supported one. The
    # baseline is flavor-resolved the same way: vanilla reads the
    # historical unqualified baseline byte-for-byte, tbc/wotlk their own.
    # A staging run against a missing baseline or missing transcript keys
    # fails loud naming the command that produces them — never silently
    # verifying against the vanilla baseline.
    layout = flavor_layout.resolve(ACTIVE_FLAVOR)
    manifest_path = ROOT / layout.migration_ledger()[0]
    baseline_path = ROOT / layout.sqlite_seed_baseline()
    # Review the augment dir is flavor-resolved too —
    # passing only the ledger still paired a tbc/wotlk
    # restage with the VANILLA schemas/seed-augment dir whose PROVENANCE
    # pins the vanilla golden manifest hash, so every per-flavor run died
    # on the augment provenance guard and the retire path was
    # dead-on-arrival. A flavor's own augment dir (schemas/seed-augment-
    # <flavor>) is absent until that lane deliberately captures — a clean
    # zero-append, never a vanilla capture fold.
    augment_dir = seeder.lane_paths(ACTIVE_FLAVOR)[2]
    if not baseline_path.is_file():
        raise RuntimeError(
            f"flavor {ACTIVE_FLAVOR!r} has no seed baseline at "
            f"{baseline_path.relative_to(ROOT).as_posix()} - the per-flavor "
            "baseline is written by the seed lane's deliberate --write-baseline "
            "run (port plan S3/S4); refusing to stage transcripts against a "
            "baseline that does not exist")
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    seeder_cmd = (
        "python tools/seed_sqlite_from_manifest.py --flavor "
        f"{ACTIVE_FLAVOR} --out <dir> --transcripts <dir> --write-baseline")
    if "transcript_digests" not in baseline:
        raise RuntimeError(
            f"flavor {ACTIVE_FLAVOR!r} baseline "
            f"({baseline_path.relative_to(ROOT).as_posix()}) carries no "
            "transcript_digests - the per-flavor transcript keys are "
            f"produced by the manifest seeder's merge write: {seeder_cmd} "
            "(then restage this lane); the old seed-lane --write-baseline "
            "remedy cannot produce them (decision D59)")
    with tempfile.TemporaryDirectory(dir=str(common.toolchain_tmpdir())) as tmp:
        rc, summary = seeder.seed(Path(tmp) / "db", Path(tmp) / "tr",
                                  False, 0, manifest_path=manifest_path,
                                  augment_dir=augment_dir)
        if rc != 0:
            raise RuntimeError("seed failed; refusing to stage transcripts")
        digests = summary.get("transcript_digests")
        if digests != baseline.get("transcript_digests"):
            raise RuntimeError(
                "seed transcript digests diverge from the append-only "
                "baseline - re-measure them deliberately with the manifest "
                f"seeder's merge write ({seeder_cmd}) before staging")
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
    runtime_libs = ("libpocket_realmd_runtime.so", "libpocket_world_runtime.so")
    # A warm staging dir can still hold libraries an earlier driver staged
    # (the retired vendored llama.cpp set); Gradle syncs the whole dir into
    # the APK, so anything this build does not produce is removed.
    for stale in stage.glob("*.so"):
        if stale.name not in runtime_libs:
            stale.unlink()
    for name in runtime_libs:
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
    record = {
        "schema": 1, "built_at_utc": datetime.now(timezone.utc).isoformat(), "abi": TARGET_ABI,
        "min_api": 26, "elf_max_page_size": "0x4000", "playerbots": True,
        "auction_house_bot": False, "cmangos_commit": ACTIVE_CMANGOS_COMMIT,
        "playerbots_commit": ACTIVE_PLAYERBOTS_COMMIT,
        "database_backend": BACKEND,
        # Record only the overlays actually applied to THIS build: the
        # flavor-resolved set (expansion-neutral + the flavor's registered
        # overlay set), backend-filtered. For vanilla this is exactly the
        # pre-split list in the same order (byte-identical lockfile output).
        "cmangos_source_overlays": [
            dict(entry) for entry in overlays_for(ACTIVE_FLAVOR, BACKEND)],
        "playerbots_source_overlays": PLAYERBOTS_OVERLAYS,
        # Content pin of every native/patches/ file compiled into this
        # build: an edited patch file makes
        # the committed lockfile stale, loudly.
        "patches_content": patches_content_digests(),
        "mariadb_connector_c": {"url": CONNECTOR_URL, "commit": CONNECTOR_COMMIT,
                                "license": "LGPL-2.1-or-later"} if BACKEND == "mysql" else None,
        "artifacts": records,
    }
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


def _lockfile_source_pins(flavor_id: str) -> tuple[str, str]:
    """The (cmangos, playerbots) pins of record for a lockfile's flavor: the
    driver constants for vanilla (select_flavor asserts they equal the
    manifest) and the flavor manifest's registered pins for tbc/wotlk. The
    warm-dir refresh reproduces the pins of record exactly as a build of
    that lane would record them — a tbc/wotlk lockfile must never be
    re-pinned with the vanilla constants (nor the reverse)."""
    if flavor_id == "vanilla":
        return CMANGOS_COMMIT, PLAYERBOTS_COMMIT
    manifest = flavor_layout.load_flavor_manifest()
    block = next((flavor for flavor in manifest.get("flavors", [])
                  if isinstance(flavor, dict) and flavor.get("id") == flavor_id),
                 None)

    def _registered(group: dict | None) -> str | None:
        if not isinstance(group, dict):
            return None
        commit = group.get("commit")
        if not isinstance(commit, str) or not commit or group.get("status") == "unresolved":
            return None
        return commit

    cmangos_commit = _registered(block.get("server_core") if block else None)
    playerbots_commit = _registered(block.get("bots") if block else None)
    if not cmangos_commit or not playerbots_commit:
        raise RuntimeError(
            f"cannot refresh a {flavor_id} realm-runtime lockfile: "
            "schemas/flavor.json has no registered server_core/bots pins for "
            "the flavor — register the fork pins before regenerating")
    return cmangos_commit, playerbots_commit


def write_lockfiles() -> list[str]:
    """Warm-dir lockfile regeneration (T0.3): refresh every committed
    realm-runtime lockfile's SOURCE-side pins (patches_content, the
    overlay registries, the submodule commits) without running a build.

    Every committed schemas/realm-runtime-lockfile*.json is refreshed —
    the android mysql/sqlite lanes, the win sqlite/mariadb lanes, and
    their per-flavor variants — with each record's own flavor/backend
    deciding the pins and the overlay set, so a tbc/wotlk lockfile keeps
    its flavor's pins (every lane records overlays_for(flavor, backend) —
    the same registration-checked derivation its writer records, with the
    win mariadb lane's backend resolved onto the table's mysql family
    key).

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
    patches = patches_content_digests()
    for path in sorted((ROOT / "schemas").glob("realm-runtime-lockfile*.json")):
        name = path.relative_to(ROOT).as_posix()
        record = json.loads(path.read_text(encoding="utf-8"))
        backend = record.get("database_backend", "mysql")
        # Flavor from the record's own field; the name fallback covers the
        # android lanes' records (no flavor key — their writer never wrote
        # one) via the naming schema's -{flavor} suffix.
        flavor = record.get("flavor") or next(
            (candidate for candidate in ("tbc", "wotlk")
             if f"-{candidate}" in name), "vanilla")
        cmangos_commit, playerbots_commit = _lockfile_source_pins(flavor)
        record["cmangos_commit"] = cmangos_commit
        record["playerbots_commit"] = playerbots_commit
        # Every lane's writer records the flavor-resolved, registration-
        # checked set (android stage() and build_win_realm_runtime.
        # write_lockfile both derive overlays_for(flavor, backend), which
        # resolves the win mariadb lane's backend onto the overlay table's
        # mysql family key); an unregistered flavor's lockfile refreshes
        # loud, never silently.
        record["cmangos_source_overlays"] = [
            dict(entry) for entry in overlays_for(flavor, backend)]
        record["playerbots_source_overlays"] = PLAYERBOTS_OVERLAYS
        record["patches_content"] = patches
        # the honest staleness marker. A warm-dir refresh moves
        # the SOURCE pins without rebuilding, so the recorded artifact
        # bytes correspond to the PREVIOUS patch set until a real build
        # lands. Derived per lane: any recorded artifact file whose mtime
        # predates the newest patch input marks the lane
        # binaries-stale-of-sources; a full build re-hashes the fresh
        # artifacts and rewrites the field.
        # The patch-sensitive runtime artifact (the world DLL/.so) is the
        # freshness witness: copied third-party dependencies (libcrypto,
        # libmysql, ...) keep their original mtimes and would false-positive.
        artifact_paths = [a.get("path", "") for a in record.get("artifacts", [])]
        world = next((ROOT / rel for rel in artifact_paths
                      if rel.endswith("pocket_world_runtime.dll")
                      or rel.endswith("pocket_world_runtime.so")
                      and (ROOT / rel).is_file()), None)
        witness = world if world is not None and world.is_file() else next(
            (ROOT / rel for rel in artifact_paths if (ROOT / rel).is_file()), None)
        newest_patch = max(
            (f.stat().st_mtime for f in (ROOT / "native/patches").rglob("*") if f.is_file()),
            default=0.0,
        )
        record["binaries_stale_for_patches"] = (
            witness is not None and witness.stat().st_mtime < newest_patch
        ) if witness is not None else True
        lock_bytes = (json.dumps(record, indent=2) + "\n").encode("utf-8")
        if path.read_bytes() != lock_bytes:
            path.write_bytes(lock_bytes)
            updated.append(name)
        if backend == "sqlite" and record.get("abi") in ("x86_64", "arm64-v8a"):
            # The SQLite lane's APK identity asset is BYTE-IDENTICAL to
            # the sibling lockfile by construction (Gradle asserts the
            # equality at packaging; loadAndVerifySqliteIdentity consumes
            # it on device). A real build rewrites both; the warm-dir
            # regen must keep that invariant or the first
            # -PsqliteProvider assembly fails on a stale asset. Artifact
            # pins inside remain the last full build's - the same
            # documented tradeoff as the lockfile itself.
            asset = (ROOT / flavor_layout.resolve(flavor).o09_build_root(record["abi"]) /
                     "realm-staging-sqlite" / "assets" / "database" /
                     "provider-sqlite" / "BUILD_PROVENANCE.json")
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
    parser.add_argument("--flavor", default="vanilla",
                        choices=tuple(flavor_layout.FLAVOR_IDS),
                        help="game flavor to build (default vanilla; tbc/wotlk "
                             "require their forks/pins registered — fail loud "
                             "otherwise, port plan S1)")
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
    # the disk-headroom preflight — hour-scale native builds on a
    # volume below the documented floor fail here with a reclaim remedy,
    # never as a wasted multi-GB ENOSPC build.
    common.require_disk_headroom(ROOT / "native")
    try:
        select_flavor(args.flavor)
    except flavor_layout.FlavorPinError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    select_abi(args.abi)
    global BACKEND
    BACKEND = args.backend
    if not args.configure_only and args.backend == "mysql":
        # Connector/C is a MySQL-lane dependency only; a sqlite full build
        # must not touch the MariaDB mirror at all.
        prepare_connector_source()
    # Refuse to consume arbitrary working-tree edits.  Only after this clean
    # check succeeds is cleanup armed; a rejected dirty tree is never touched.
    # The ACTIVE flavor's submodule is the tree this build overlays, restores,
    # and drift-checks — never the vanilla one a --flavor run never touched.
    cmangos = NATIVE / ACTIVE_CORE_SUBMODULE.removeprefix("native/")
    tracked_dirty = subprocess.run(["git", "diff", "--quiet"], cwd=cmangos).returncode != 0 or \
        subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=cmangos).returncode != 0
    untracked = output(["git", "ls-files", "--others", "--exclude-standard"], cmangos)
    if tracked_dirty or untracked:
        raise RuntimeError(
            f"CMaNGOS submodule ({ACTIVE_CORE_SUBMODULE}) has unrecorded changes; "
            "clean it before building. If a previous build was killed mid-run "
            "(its restore was skipped), the overlaid files are still applied - "
            f"restore the tracked ones with: git -C {ACTIVE_CORE_SUBMODULE} checkout -- . "
            "(the backend-selection overlay touches the submodule ROOT "
            "CMakeLists.txt, not just src/) and remove the untracked "
            f"overlay file with: git -C {ACTIVE_CORE_SUBMODULE} clean -fd "
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
