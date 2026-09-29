#!/usr/bin/env python3
"""Mechanical cross-flavor leak sweep (port plan §4.6, S0 scope).

Scans production Kotlin and Java (android/app/src/main wholesale — every
.kt and .java file plus every XML, so the AndroidManifest, the res tree
with values-night, and addon assets are all covered — and the
pocketrealm-llm module), the debug/androidTest/test source sets, the
Android gradle scripts, the Python plumbing (tools/ and scripts/,
recursively), and the PowerShell host lanes (tools/, recursively) for banned
flavor-identity literals — managed client ids, PE version resources, build
numbers, and the vanilla database names — outside their owning tables. A
literal hardcoded in a second module pins the vanilla lane into code that
must one day serve TBC/WotLK; the sweep runs every round and in CI forever
after, and grows each stage (addon interface numbers, runtime ids, and
expansion strings join in later stages).

Integer literals are normalized before matching: digit separators and the
hex/binary/octal forms decode to their value first, so `5_875`, `0x16F3`,
`0b1011011110011`, and `013363` (all == 5875) are caught by the same
banned-number table as the plain decimal. Octal is only the leading-zero
form (a zero followed by octal digits), so plain decimal lists like `0, 1`
never trip.

Known limitations (the sweep is a tripwire, not a sandbox; the production
gates are code-level): adjacent string concatenation evades the literal
match — `"wow-" + "1.12.1-5875"` split across lines or assembled by `+`
never yields one contiguous banned literal on a single line, and the sweep
does not attempt full Kotlin/Java parsing to constant-fold it. UTF-16/32-BOM
text files are NUL-sniffed to binary and silently skipped (zero instances in
this repo; javac would compile them — revisit if one ever lands).

Every exemption is explicit and review-staged in ALLOWLIST below: the owning
tables, the legacy compat constant, and the later-stage-owned surfaces (S4
per-flavor DB routing, S5 host-side client qualification helpers and
per-flavor test fixtures, S7 vanilla client datasets and UX copy). Findings
outside the allowlist are the migration TODO.

The allowlist is also self-checking: a scoped entry whose target file no
longer contains ANY of its covered literals is a config error ("stale
allowlist entry"), so entries retire with their literals instead of
silently dead weight. Entries whose target file is absent from the swept
root (synthetic test trees) are skipped.

Usage:
    python tools/leak_sweep.py [--root PATH] [--json]

Exit codes: 0 = clean (every hit is allowlisted), 1 = leaks found or stale
allowlist entry.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parent.parent

# Banned identity strings (client ids, version resources, DB names).
BANNED_STRINGS = (
    "wow-1.12.1-5875",
    "1.12.1.5875",
    "wow-2.4.3-8606",
    "2.4.3.8606",
    "wow-3.3.5a-12340",
    "3.3.5.12340",
    "classicrealmd",
    "classicmangos",
    "classiccharacters",
    "classiclogs",
)

# Banned build numbers, matched as standalone integer literals after
# normalization: not glued to other digits, letters, or dots (so "wow5875"
# and "1.12.1.5875" pass the numeric rule; the dotted forms are banned
# strings in their own right), never inside a banned-string occurrence (a
# client id reports once, under its own label), and matched value-wise —
# digit separators and the hex/binary/octal forms decode first (`5_875`,
# `0x16F3`, `0b1011011110011`, and `013363` all report as 5875).
BANNED_NUMBERS = (5875, 8606, 12340)
_BANNED_NUMBER_SET = frozenset(BANNED_NUMBERS)
_NUMBER_TOKEN_RE = re.compile(
    r"(?<![0-9A-Za-z_.])"
    r"(?:"
    r"0[xX][0-9A-Fa-f][0-9A-Fa-f_]*"  # hex literal (0x16F3)
    r"|0[bB][01][01_]*"               # binary literal (0b1011011110011)
    r"|0[oO][0-7][0-7_]*"             # explicit octal (0o13363)
    r"|0[0-7][0-7_]*"                 # C/Java leading-zero octal (013363)
    r"|[0-9][0-9_]*"                  # decimal literal
    r")"
    r"(?:[uU][Ll]?|[Ll])?"  # Java/Kotlin long and Kotlin unsigned suffixes
    r"(?![0-9A-Za-z_.])"
)
# Case-insensitive on purpose: a case-variant DB name is a real hazard on
# case-insensitive filesystems, and every legitimate mention of these
# literals lives in allowlisted prose/data anyway.
_STRING_RES = tuple(
    (literal, re.compile(re.escape(literal), re.IGNORECASE)) for literal in BANNED_STRINGS
)

# v2 scope (D18 redesign): inside a git checkout the sweep covers EVERY
# tracked text file, so no production tree can silently sit outside the
# tripwire (the enumerated-roots design missed desktop/build.gradle.kts and
# needed a patch per omission). Excluded: vendored submodule sources (own
# upstreams) and vendored runtime blobs; binaries are skipped by a NUL-byte
# sniff. Outside a git repo (synthetic test trees) the sweep falls back to
# the legacy enumerated SCAN_ROOTS walk so scope tests keep working.
SCAN_ROOTS: tuple[tuple[str, bool, tuple[str, ...]], ...] = (
    ("android/app/src/main", True, (".kt", ".java", ".xml")),
    ("android/app/src/debug", True, (".kt", ".java")),
    ("android/app/src/androidTest", True, (".kt",)),
    ("android/app/src/test", True, (".kt",)),
    ("android", True, (".gradle.kts",)),
    ("android/pocketrealm-llm", True, (".kt",)),
    ("desktop", True, (".kt", ".gradle.kts")),
    ("tools", True, (".py", ".ps1")),
    ("scripts", True, (".py",)),
)

# Trees excluded from git-tracked sweep mode: vendored upstreams (their own
# repos gate their content) and packaged runtime blobs (binaries).
GIT_EXCLUDED_PREFIXES: tuple[str, ...] = (
    "native/cmangos/",
    "native/playerbots/",
    "native/classic-db/",
    "native/vanilla-tweaks/",
    "runtime/",
)

# Vanilla DB names shared by the seed-plumbing exemptions below.
VANILLA_DB_NAMES = ("classicrealmd", "classicmangos", "classiccharacters", "classiclogs")

# literals == None exempts the whole file; a tuple exempts exactly those
# literal labels. Each entry names the stage that must retire it.
ALLOWLIST: tuple[dict[str, object], ...] = (
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt",
        "literals": None,
        "reason": (
            "the flavor registry is the owning table: it defines every client id, "
            "version resource, build number, and vanilla DB name by design"
        ),
        "retires": "never (§4.6 bans literals only outside their owning table)",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/ClientRuntime.kt",
        "literals": ("wow-1.12.1-5875", "5875"),
        "reason": "legacy compat const (WOW_5875_ID) kept compiling for pre-registry call sites",
        "retires": (
            "when the last comparator migrates to ClientFlavors "
            "(drift guarded by ClientFlavorTest)"
        ),
    },
    {
        "path": "tools/check_flavor.py",
        "literals": VANILLA_DB_NAMES + ("wow-1.12.1-5875", "5875"),
        "reason": (
            "manifest validator: pins the vanilla DB-name provenance lock and "
            "cites the canonical client id in its error messages"
        ),
        "retires": "never while the provenance lock stands",
    },
    {
        "path": "tools/leak_sweep.py",
        "literals": None,
        "reason": "this sweep owns the banned-literal table itself",
        "retires": "never (owning table)",
    },
    {
        "path": "tests/test_leak_sweep.py",
        "literals": None,
        "reason": "the sweep's own tests construct banned literals by design to prove detection",
        "retires": "never (owning-table test)",
    },
    {
        "path": "tests/test_check_flavor.py",
        "literals": None,
        "reason": "the validator's tests construct flavor identities by design to prove rejection",
        "retires": "never (owning-table test)",
    },
    # Seed plumbing legitimately names the vanilla databases it seeds,
    # checkpoints, bridges, and verifies; S4 retires each entry when artifact
    # naming routes per flavor through the registry resolver.
    {
        "path": "tools/checkpoint_dbs.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "backup plumbing: names the vanilla databases it checkpoints",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/create_davos.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "device provisioning: seeds the vanilla realm/character databases",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/delete_davos.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "device teardown: drops the vanilla character database",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/reset_davos.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "device reset: drops and re-seeds the vanilla databases",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/run_differential_parity.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "parity harness: diffs the vanilla databases between lanes",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/seed_sqlite_from_manifest.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "seed plumbing: translates the vanilla databases into SQLite fixtures",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/sqlite_seed_translator.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "seed plumbing: SQL-to-SQLite translation knows the vanilla world DB",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/sqlite_user_state_bridge.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "seed plumbing: bridges user state into the vanilla character DB",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/stage_database_migrations.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "migration staging: applies ledger entries to the vanilla databases",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    {
        "path": "tools/verify_seed_sqlite_floor.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "seed plumbing: row-count sanity floors over the vanilla databases",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    # Retire at S4 (per-flavor DB routing): the database engine layer names the
    # vanilla databases it creates, grants, migrates, and bridges.
    {
        "path": "android/app/src/main/java/com/pocketrealm/database/DatabaseEngine.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "database engine: creates/grants/migrates the vanilla databases by name",
        "retires": "S4 retires: per-flavor DB routing through ClientFlavors",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/database/DatabaseSqliteControlPlane.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "SQLite control plane: files for the four vanilla databases",
        "retires": "S4 retires: per-flavor DB routing through ClientFlavors",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/database/DatabaseUserStateBridge.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "user-state bridge: slices of the vanilla realmd/characters databases",
        "retires": "S4 retires: per-flavor DB routing through ClientFlavors",
    },
    # Retire at S5 (host-side client qualification helpers): host lanes that
    # validate/stage the genuine 1.12.1.5875 client during bring-up.
    {
        "path": "tools/stage_o07_client.py",
        "literals": ("wow-1.12.1-5875", "5875", "1.12.1.5875"),
        "reason": "host staging: validates and stages the user-owned 1.12.1 build 5875 client",
        "retires": "S5 retires: client identity qualification via the flavor registry",
    },
    {
        "path": "tools/prepare_o11_bot_data.py",
        "literals": ("1.12.1.5875", "5875"),
        "reason": "host lane: prepares bot data against the 1.12.1.5875 client",
        "retires": "S5 retires: client identity qualification via the flavor registry",
    },
    {
        "path": "tools/run_baseline_world_sanity.py",
        "literals": ("1.12.1.5875",),
        "reason": "host lane: world-sanity baseline over the 1.12.1.5875 extraction",
        "retires": "S5 retires: client identity qualification via the flavor registry",
    },
    {
        "path": "tools/win_prepare_data.py",
        "literals": ("5875",),
        "reason": "host lane: Windows data preparation pins clientBuild 5875",
        "retires": "S5 retires: client identity qualification via the flavor registry",
    },
    {
        "path": "tools/install_client_windows.ps1",
        "literals": ("1.12.1.5875",),
        "reason": "host-side client qualification helper: verifies the WoW.exe identity (size + version resource) of the 1.12.1.5875 client",
        "retires": "S5 retires: host-side client qualification helper",
    },
    # S4/S8 (desktop lane): the Windows desktop test lane mirrors the vanilla
    # server stack with hard-coded classic* DB names and build-5875 auth
    # fixtures. Found when the desktop module entered SCAN_ROOTS (round 5).
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/AuthGate.kt",
        "literals": ("5875", "classicrealmd"),
        "reason": "desktop SRP6/logon fixtures pin the 5875 auth frames; vanilla realmd DB name",
        "retires": "S8 retires (5875 auth frames); classicrealmd retires at S4",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt",
        "literals": ("classicrealmd",),
        "reason": "desktop lane hard-codes the vanilla realmd DB name",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/RpHost.kt",
        "literals": ("classicrealmd",),
        "reason": "desktop lane hard-codes the vanilla realmd DB name",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/RpWowClient.kt",
        "literals": ("5875",),
        "reason": "desktop test client pins the 5875 auth/world frame layout",
        "retires": "S8 retires: per-flavor desktop auth fixtures",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "desktop conf emission mirrors the vanilla DB name set",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopSqliteSeederTest.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "desktop sqlite seeder fixture pins the vanilla DB names",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackendTest.kt",
        "literals": ("classicrealmd",),
        "reason": "desktop backend fixture pins the vanilla realmd DB name",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/server/DesktopServerRuntimeFilesTest.kt",
        "literals": ("5875",) + VANILLA_DB_NAMES,
        "reason": "desktop runtime-files fixture pins vanilla DB names and the 5875 identity",
        "retires": "S4 retires (DB names); 5875 retires at S8",
    },
    # S6 (extractor manifest provenance) currently owns nothing: the extractor
    # lanes (build_o11_extractors.py, build_o09_realm_runtime.py) sweep clean.
    # Retire at S7 (vanilla client datasets & UX copy): capture-sourced vanilla
    # datasets and user-facing copy that describe the 5875 client by name.
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/ClientTweaks.kt",
        "literals": ("5875",),
        "reason": "byte-addressed vanilla tweaks pinned to the build-5875 image",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/SinglePlayerAutoLogin.kt",
        "literals": ("5875",),
        "reason": "login-window mapped topology captured from the qualified build-5875 client",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/WowVanillaBindingCatalog.kt",
        "literals": ("5875",),
        "reason": "stock key-binding catalog captured from the live build-5875 client",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ingame/ConfigWtfCodec.kt",
        "literals": ("5875",),
        "reason": "CVar float rendering matches how build 5875 echoes values",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ingame/InGameSettingsFiles.kt",
        "literals": ("5875",),
        "reason": "WTF file layout verified on the build-5875 client",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ingame/SavedVariablesCodec.kt",
        "literals": ("5875",),
        "reason": "scalar codec for build 5875's account SavedVariables.lua",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ingame/WowSettingModel.kt",
        "literals": ("5875",),
        "reason": "setting model facts from the live build-5875 WTF tree",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ingame/WowVanillaSettingsCatalog.kt",
        "literals": ("5875",),
        "reason": "stock settings manifest pinned to the build-5875 WTF tree",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ui/FirstRunTutorial.kt",
        "literals": ("5875",),
        "reason": "UX copy names the client the user must supply (1.12.1 build 5875)",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ui/SettingsScreen.kt",
        "literals": ("5875",),
        "reason": "UX copy describes tweaks in terms of the genuine 1.12.1 build 5875 client",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/importer/ArchiveFormatSniffer.kt",
        "literals": ("1.12.1.5875",),
        "reason": "importer UX copy: rejects installers, asks for the extracted 1.12.1.5875 client",
        "retires": "S7 retires: per-flavor client datasets and UX copy",
    },
    # Retire at S4 (per-flavor DB routing): the gradle build gate verifies the
    # staged seed assets of the four vanilla databases against the sibling
    # lockfile before they ship in the APK.
    {
        "path": "android/app/build.gradle.kts",
        "literals": VANILLA_DB_NAMES,
        "reason": "gradle build gate: verifies the staged vanilla seed databases against the lockfile",
        "retires": "S4 retires: per-flavor ledger routing",
    },
    # Retire at S5 (per-flavor fixtures): the debug/androidTest/test source
    # sets legitimately stage synthetic vanilla-lane material — synthetic PE
    # and MPQ archives for import tests, registry drift guards, device-side
    # runners — that pins the build-5875 client and the vanilla database
    # names by design. Entries are scoped to exactly the literals that trip
    # today; anything new in these files still fails the sweep.
    {
        "path": "android/app/src/debug/java/com/pocketrealm/importer/ImportFixtureProvider.java",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/debug/java/com/pocketrealm/importer/SyntheticClientArchives.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/client/ClientBuild5875LoginTest.kt",
        "literals": ("1.12.1.5875", "5875"),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/client/SafClientScannerTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/database/BaselineWorldSanityRunner.kt",
        "literals": ("classicrealmd",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/database/EngineBenchmarkRunner.kt",
        "literals": ("classicmangos",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/importer/O11LiveDataResumeTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/importer/O12ArchiveImportTest.kt",
        "literals": ("1.12.1.5875", "5875"),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/o12/O12IntegratedRuntimeTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ClientFlavorTest.kt",
        "literals": (
            "5875", "8606", "12340",
            "1.12.1.5875", "2.4.3.8606", "3.3.5.12340",
        ) + VANILLA_DB_NAMES,
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ClientTweaksConfigTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ClientTweaksNearbyLootAcceptTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ClientTweaksValidatorTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/WineRuntimeGenerationIdentityTest.kt",
        "literals": ("wow-1.12.1-5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/database/DatabaseDurableStateTest.kt",
        "literals": ("classiccharacters", "classicmangos"),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/database/DatabaseSqliteControlPlaneTest.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/database/DatabaseUserStateBridgeTest.kt",
        "literals": ("classiccharacters",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/importer/ArchiveDetectionTest.kt",
        "literals": ("1.12.1.5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/importer/SyntheticClientArchivesTest.kt",
        "literals": ("1.12.1.5875", "5875"),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/server/PreparedDataStoreTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/ui/FirstRunTutorialTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla test fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    # Retire with S4/S5 (per-flavor routing, then client qualification): the
    # device probe harnesses open the vanilla realmd datadir and forge
    # build-5875 auth packets against it.
    {
        "path": "tools/rp_harness/probe_whisper_dump.py",
        "literals": ("5875", "classicrealmd"),
        "reason": "device probe harness: probes the vanilla realmd datadir, forges build-5875 auth packets",
        "retires": "S4 retires: per-flavor ledger routing; S5 retires: client identity qualification",
    },
    {
        "path": "tools/rp_harness/probe_whisper_hunt.py",
        "literals": ("5875", "classicrealmd"),
        "reason": "device probe harness: probes the vanilla realmd datadir, forges build-5875 auth packets",
        "retires": "S4 retires: per-flavor ledger routing; S5 retires: client identity qualification",
    },
    {
        "path": "tools/rp_harness/probe_whisper_hunt2.py",
        "literals": ("5875", "classicrealmd"),
        "reason": "device probe harness: probes the vanilla realmd datadir, forges build-5875 auth packets",
        "retires": "S4 retires: per-flavor ledger routing; S5 retires: client identity qualification",
    },
    {
        "path": "tools/rp_harness/probe_world_auth.py",
        "literals": ("5875", "classicrealmd"),
        "reason": "device probe harness: probes the vanilla realmd datadir, forges build-5875 auth packets",
        "retires": "S4 retires: per-flavor ledger routing; S5 retires: client identity qualification",
    },
    # v2 git-tracked sweep (D18): trees newly covered, allowlisted by class.
    {
        "path_prefix": "docs/",
        "literals": None,
        "reason": "prose documentation names client builds when describing them; docs describe, code gates",
        "retires": "never (prose is not a code literal)",
    },
    {
        "path": "README.md",
        "literals": ("5875",),
        "reason": "project README prose names the 1.12.1 build 5875 requirement",
        "retires": "never (prose is not a code literal); reword per flavor when multi-flavor ships",
    },
    {
        "path": "schemas/flavor.json",
        "literals": None,
        "reason": "the multi-flavor manifest is the owning table for every client id, build, and DB name by design",
        "retires": "never (owning data)",
    },
    {
        "path": "schemas/database-migrations.json",
        "literals": VANILLA_DB_NAMES,
        "reason": "the vanilla migration ledger names the classic* databases it seeds",
        "retires": "S4 retires: per-flavor ledgers",
    },
    {
        "path": "schemas/sqlite-seed-baseline.json",
        "literals": VANILLA_DB_NAMES,
        "reason": "seed baseline provenance names the classic* databases",
        "retires": "S4 retires: per-flavor ledgers",
    },
    {
        "path": "schemas/realm-runtime-lockfile-sqlite.json",
        "literals": VANILLA_DB_NAMES,
        "reason": "lockfile provenance names the classic* databases the lane seeds",
        "retires": "S4 retires: per-flavor ledgers",
    },
    {
        "path": "schemas/realm-runtime-lockfile-arm64-v8a-sqlite.json",
        "literals": VANILLA_DB_NAMES,
        "reason": "lockfile provenance names the classic* databases the lane seeds",
        "retires": "S4 retires: per-flavor ledgers",
    },
    {
        "path": "schemas/realm-runtime-lockfile-sqlite-win.json",
        "literals": VANILLA_DB_NAMES,
        "reason": "lockfile provenance names the classic* databases the lane seeds",
        "retires": "S4 retires: per-flavor ledgers",
    },
    {
        "path": "desktop/build.gradle.kts",
        "literals": VANILLA_DB_NAMES,
        "reason": "desktop seed-verification gate names the classic* databases (surfaced by the v2 sweep)",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "android/app/detekt-baseline.xml",
        "literals": ("5875",),
        "reason": "static-analysis baseline signatures reference the vanilla identity",
        "retires": "S7 retires: vanilla client datasets",
    },
    {
        "path": "tests/p5_outfile_wire_fixture.txt",
        "literals": ("classiccharacters",),
        "reason": "captured wire fixture embeds the vanilla characters DB name",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "tests/p5_outfile_wire_fixture_nopk.txt",
        "literals": ("classiccharacters",),
        "reason": "captured wire fixture embeds the vanilla characters DB name",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "tests/test_differential_lane.py",
        "literals": ("classicrealmd", "classiccharacters", "classicmangos"),
        "reason": "repo test constructs the vanilla DB names to pin the differential lane",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "tests/test_sqlite_seeding.py",
        "literals": VANILLA_DB_NAMES,
        "reason": "repo test constructs the vanilla DB names to pin the sqlite seed",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "tests/test_sqlite_user_state_bridge.py",
        "literals": ("classiccharacters",),
        "reason": "repo test constructs the vanilla characters DB name",
        "retires": "S4 retires: per-flavor DB routing",
    },
)


def _allowlist_entry(
    rel_path: str, allowlist: tuple[dict[str, object], ...] = ALLOWLIST
) -> dict[str, object] | None:
    for entry in allowlist:
        prefix = entry.get("path_prefix")
        if prefix is not None:
            if rel_path.startswith(str(prefix)):
                return entry
        elif entry["path"] == rel_path:
            return entry
    return None


def _number_labels(token: str) -> str | None:
    """Normalize an integer literal token to its decimal value, if banned."""
    token = re.sub(r"(?:[uU][Ll]?|[Ll])$", "", token)  # long/unsigned suffixes
    try:
        digits = token.replace("_", "")
        if digits[:2].lower() == "0x":
            value = int(digits, 16)
        elif digits[:2].lower() == "0b":
            value = int(digits, 2)
        elif digits[:2].lower() == "0o" or re.fullmatch(r"0[0-7]+", digits):
            value = int(digits, 8)
        else:
            value = int(digits)
    except ValueError:
        return None
    return str(value) if value in _BANNED_NUMBER_SET else None


def _line_literals(line: str) -> set[str]:
    hits: set[str] = set()
    masked = list(line)
    for literal, pattern in _STRING_RES:
        for match in pattern.finditer(line):
            hits.add(literal)
            for index in range(match.start(), match.end()):
                masked[index] = " "
    masked_line = "".join(masked)
    for match in _NUMBER_TOKEN_RE.finditer(masked_line):
        label = _number_labels(match.group(0))
        if label is not None:
            hits.add(label)
    return hits


def _git_tracked_files(root: Path) -> list[Path] | None:
    """Every tracked text file, or None when root is not a git checkout."""
    import subprocess

    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z"],
            capture_output=True, timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    paths: list[Path] = []
    for raw in result.stdout.split(bytes([0])):
        if not raw:
            continue
        rel = raw.decode("utf-8", "replace")
        if any(rel.startswith(prefix) for prefix in GIT_EXCLUDED_PREFIXES):
            continue
        path = root / rel
        if not path.is_file():
            continue
        try:
            with path.open("rb") as handle:
                if bytes([0]) in handle.read(8192):
                    continue  # binary: no text literals to sweep
        except OSError:
            pass  # unreadable files surface as <unreadable> findings
        paths.append(path)
    return paths


def _git_binary_skipped(root: Path) -> int:
    """Count tracked files the NUL-byte sniff classifies as binary (json
    reporting only: the sweep silently skips these, so the report says so)."""
    import subprocess

    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z"],
            capture_output=True, timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return 0
    if result.returncode != 0:
        return 0
    skipped = 0
    for raw in result.stdout.split(bytes([0])):
        if not raw:
            continue
        rel = raw.decode("utf-8", "replace")
        if any(rel.startswith(prefix) for prefix in GIT_EXCLUDED_PREFIXES):
            continue
        path = root / rel
        if not path.is_file():
            continue
        try:
            with path.open("rb") as handle:
                if bytes([0]) in handle.read(8192):
                    skipped += 1
        except OSError:
            continue
    return skipped


def scan_paths(root: Path) -> list[Path]:
    tracked = _git_tracked_files(root)
    if tracked is not None:
        return sorted(tracked)
    paths: set[Path] = set()
    for rel, recursive, suffixes in SCAN_ROOTS:
        base = root / rel
        if not base.is_dir():
            continue
        walker = base.rglob("*") if recursive else base.glob("*")
        for path in walker:
            # Name-based match: Path.suffix would report ".kts" for
            # "*.gradle.kts" and miss the gradle scripts entirely.
            if path.is_file() and path.name.endswith(suffixes):
                paths.add(path)
    return sorted(paths)


def _stale_allowlist_findings(
    root: Path, allowlist: tuple[dict[str, object], ...] = ALLOWLIST
) -> list[dict[str, object]]:
    """A scoped allowlist entry must still cover at least one literal its
    target file actually contains: an entry whose literals were all fixed
    elsewhere is dead weight that hides future edits from review. Entries
    whose target file is absent from the swept root (synthetic test trees)
    are skipped, as are whole-file exemptions."""
    stale: list[dict[str, object]] = []
    for entry in allowlist:
        covered = entry["literals"]
        if covered is None:
            continue
        path = root / str(entry["path"])
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue  # unreadable files already surface as <unreadable> findings
        found: set[str] = set()
        for line in text.splitlines():
            found |= _line_literals(line)
        missing = [literal for literal in covered if literal not in found]
        if len(missing) == len(covered):
            stale.append(
                {
                    "path": str(entry["path"]),
                    "line": 0,
                    "literals": [missing[0]],
                    "text": (
                        f"stale allowlist entry: {entry['path']} "
                        f"(literal '{missing[0]}' not found)"
                    ),
                }
            )
    return stale


def sweep(
    root: Path, allowlist: tuple[dict[str, object], ...] = ALLOWLIST
) -> tuple[list[dict[str, object]], int, int]:
    """Return (findings, allowlisted count, files scanned). Stale scoped
    allowlist entries are reported as findings (config errors)."""
    findings: list[dict[str, object]] = []
    allowlisted = 0
    paths = scan_paths(root)
    for path in paths:
        rel = path.relative_to(root).as_posix()
        entry = _allowlist_entry(rel, allowlist)
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            findings.append(
                {"path": rel, "line": 0, "literals": ["<unreadable>"], "text": str(exc)}
            )
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            literals = _line_literals(line)
            if not literals:
                continue
            if entry is not None:
                covered = entry["literals"]
                if covered is None:  # whole-file exemption (owning table)
                    allowlisted += 1
                    continue
                literals -= set(covered)  # type: ignore[arg-type]
            if not literals:
                allowlisted += 1
                continue
            findings.append(
                {
                    "path": rel,
                    "line": lineno,
                    "literals": sorted(literals),
                    "text": line.strip(),
                }
            )
    findings.extend(_stale_allowlist_findings(root, allowlist))
    return findings, allowlisted, len(paths)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=DEFAULT_ROOT,
        help="repository root to sweep (default: this repo)",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable findings")
    args = parser.parse_args()

    findings, allowlisted, scanned = sweep(args.root.resolve())
    allowlist_view = [
        {
            **({"path_prefix": entry["path_prefix"]} if "path_prefix" in entry else {}),
            "path": entry.get("path", entry.get("path_prefix")),
            "literals": "all" if entry["literals"] is None else list(entry["literals"]),  # type: ignore[arg-type]
            "reason": entry["reason"],
            "retires": entry["retires"],
        }
        for entry in ALLOWLIST
    ]
    if args.json:
        print(
            json.dumps(
                {
                    "ok": not findings,
                    "files_scanned": scanned,
                    "binary_skipped": _git_binary_skipped(args.root.resolve()),
                    "scanned_roots": (
                        "git-tracked"
                        if _git_tracked_files(args.root.resolve()) is not None
                        else sorted(rel for rel, _rec, _sfx in SCAN_ROOTS)
                    ),
                    "allowlisted_findings": allowlisted,
                    "findings": findings,
                    "allowlist": allowlist_view,
                },
                indent=2,
            )
        )
        return 1 if findings else 0

    for finding in findings:
        literals = ", ".join(finding["literals"])  # type: ignore[arg-type]
        print(f"LEAK {finding['path']}:{finding['line']} [{literals}] {finding['text']}")
    if findings:
        files = len({finding["path"] for finding in findings})
        print(
            f"leak_sweep: FAILED ({len(findings)} leaks in {files} files; "
            f"{allowlisted} allowlisted findings suppressed)"
        )
        return 1
    print(f"leak_sweep: OK ({scanned} files scanned, {allowlisted} allowlisted findings)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
