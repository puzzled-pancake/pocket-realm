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
    # R2UI-3 (android-ui-tests review round 2): the per-flavor expansion MPQ
    # names join the banned set (§4.6: expansion.mpq / lichking.mpq outside
    # their owning client-shape and addon-asset tables).
    "expansion.mpq",
    "lichking.mpq",
)

# Banned build numbers, matched as standalone integer literals after
# normalization: not glued to other digits, letters, or dots (so "wow5875"
# and "1.12.1.5875" pass the numeric rule; the dotted forms are banned
# strings in their own right), never inside a banned-string occurrence (a
# client id reports once, under its own label), and matched value-wise —
# digit separators and the hex/binary/octal forms decode first (`5_875`,
# `0x16F3`, `0b1011011110011`, and `013363` all report as 5875).
BANNED_NUMBERS = (5875, 8606, 12340, 6005, 6141, 11200, 20400, 30300)
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

# v2 scope (D18 redesign; round-1 R1-1/R3-1 extension): inside a git
# checkout the sweep covers EVERY COMMITTED-OR-COMMITTABLE text file
# (git ls-files --cached --others --exclude-standard), so no production
# tree - tracked or awaiting the finalize commit - can silently sit
# outside the tripwire (the enumerated-roots design missed
# desktop/build.gradle.kts and needed a patch per omission; the
# tracked-only form missed the wave's own untracked production code).
# Excluded: vendored submodule sources (own upstreams) and vendored
# runtime blobs; binaries are skipped by a NUL-byte sniff. Outside a git
# repo (synthetic test trees) the sweep falls back to the legacy
# enumerated SCAN_ROOTS walk so scope tests keep working - and a scan
# that resolves ZERO files is a loud degraded-scan failure (R2-2).
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
        "literals": VANILLA_DB_NAMES + ("wow-1.12.1-5875", "5875") + ("6005", "6141",),
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
    {
        "path": "tests/test_s1_flavor_parameterization.py",
        "literals": None,
        "reason": (
            "the S1 corrections/parameterization tests pin the C1/C4 accepted-build "
            "sets and prove the C1 rejection copy by constructing flavor identities"
        ),
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
        "literals": ("wow-1.12.1-5875", "5875", "1.12.1.5875", "6005", "6141"),
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
        "literals": ("5875", "8606", "12340", "2.4.3.8606", "expansion.mpq", "lichking.mpq"),
        "reason": (
            "host lane: Windows data preparation carries the per-flavor "
            "FLAVOR_IDENTITIES table (DESK-128) — the host mirror of the "
            "S5.1-registered clientBuild/cmangosFamily pairs the manifest "
            "and ClientFlavors pin; identity data, not flavor-agnostic code. "
            "The dotted 2.4.3.8606 form appears once, in the measured-floors "
            "prose citing the staged client the tbc counts were measured on"
        ),
        "retires": "S5 retires: client identity qualification via the flavor registry",
    },
    # Pruned (S4 wave, 2026-10-08): the install_client_windows.ps1 entry
    # ("1.12.1.5875") went stale when the helper was flavor-routed through
    # the manifest's flavorBlock (version/size literals now come from
    # schemas/flavor.json) — the sweep's own staleness gate demanded its
    # retirement.
    # S4/S8 (desktop lane): the Windows desktop test lane mirrors the vanilla
    # server stack with hard-coded classic* DB names and build-5875 auth
    # fixtures. Found when the desktop module entered SCAN_ROOTS (round 5).
    # Pruned (wave verification round 2): the AuthGate, DesktopRuntimeBackend,
    # RpHost and server/ServerRuntimeFiles entries went stale when their
    # literals were routed through ClientFlavors (DESK-93/94 flavor routing) —
    # the sweep's own staleness gate demanded their retirement.
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/RpWowClient.kt",
        "literals": ("5875",),
        "reason": "desktop test client pins the 5875 auth/world frame layout",
        "retires": "S8 retires: per-flavor desktop auth fixtures",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/Win32AutoLogin.kt",
        "literals": ("8606", "12340"),
        "reason": (
            "desktop login-window signatures are keyed off the shared "
            "ClientFlavors registry (P11 lanes item 19); the KDoc documents "
            "that the alternate window classes were captured from the staged "
            "8606/12340 exes' own PE strings (out/flavor-capture manifests "
            "own the data) — prose describing per-flavor capture provenance, "
            "not a code literal routing a lane"
        ),
        "retires": (
            "S7/S8 (W3) retires: login-topology verification promotes the "
            "signatures and the build mentions move to the capture-manifest "
            "owning table"
        ),
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/importer/DataPreparationSourceQuarantineTest.kt",
        "literals": ("wow-1.12.1-5875",),
        "reason": "R2r3-1 data-prep source quarantine: the refuse leg asserts the refusal names the vanilla source generation's verified clientId while the active lane is tbc",
        "retires": "W4 retires: the multi-qualified flip owns the lanes natively",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/server/DesktopBootLaneBindingTest.kt",
        "literals": ("classicmangos", "tbcmangos"),
        "reason": "R1-r2-1 desktop boot-lane binding: the behavioral mid-start-switch truth table asserts the BOOT lane's conf database names vs the live machine's (the vanilla/TBC owning name sets, ClientFlavor.kt)",
        "retires": "W4 retires: the multi-qualified flip owns the lanes natively",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopSqliteSeederTest.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "desktop sqlite seeder fixture pins the vanilla DB names",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackendTest.kt",
        "literals": ("5875", "classicrealmd", "6005", "6141") + VANILLA_DB_NAMES,
        "reason": (
            "desktop backend fixture pins the vanilla realmd DB name and the "
            "DESK-108 bootstrap fixture replays one synthetic transcript per "
            "vanilla database (its synthetic data-manifest stamps the vanilla "
            "clientBuild 5875)"
        ),
        "retires": "S4 retires: per-flavor DB routing; 5875 retires at S8",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/server/DesktopServerRuntimeFilesTest.kt",
        "literals": ("5875", "12340") + VANILLA_DB_NAMES,
        "reason": (
            "desktop runtime-files fixture pins vanilla DB names and the 5875 "
            "identity; the S5-flip fixtures construct a synthetic "
            "two-qualified flavor set (wotlk build 12340) to pin ActiveFlavor "
            "resolution"
        ),
        "retires": "S4 retires (DB names); 5875/12340 retire at S8",
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
        "literals": ("5875", "8606", "12340"),
        "reason": (
            "login-window mapped topology captured from the qualified build-5875 "
            "client plus its build-keyed 8606/12340 twins (P11 lanes item 19): "
            "the KDoc documents each twin's provenance contract — the 5875 lane "
            "is device-verified, the 8606/12340 matchers ride the same verified "
            "Wine transport seam until their device captures land"
        ),
        "retires": (
            "S7 (W3) retires: per-flavor client datasets and UX copy — the "
            "login-topology verification promotes the twins and the identity "
            "strings move to the capture-manifest owning table"
        ),
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
        "literals": ("5875", "6005"),
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
        "literals": ("5875", "6005"),
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
        "literals": ("5875", "8606", "12340", "1.12.1.5875", "2.4.3.8606", "3.3.5.12340", "11200", "20400", "30300", "6005", "6141", "expansion.mpq", "lichking.mpq") + VANILLA_DB_NAMES,
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
        "literals": ("1.12.1.5875", "6005"),
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
        "path": "android/app/src/test/java/com/pocketrealm/server/NativeRuntimeFreshnessTest.kt",
        "literals": ("classiccharacters", "classicrealmd", "classiclogs"),
        "reason": (
            "freshness-gate fixture: the lockfile seed_transcript rows it decodes "
            "are keyed by the vanilla database names"
        ),
        "retires": "S4 retires: per-flavor DB routing",
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
        "literals": VANILLA_DB_NAMES + ("6005",),
        "reason": "the vanilla migration ledger names the classic* databases it seeds",
        "retires": "S9 retires: the deprecation-window alias file is deleted once no consumer reads it (G3-3 item 2 / D35); this entry retires with it",
    },
    {
        "path": "schemas/database-migrations-vanilla.json",
        "literals": VANILLA_DB_NAMES + ("6005",),
        "reason": "the canonical per-flavor vanilla ledger (byte copy of the alias) names the classic* databases it seeds",
        "retires": "never while the vanilla lane seeds classic* (the alias entry above retires first)",
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
        "path": "schemas/realm-runtime-lockfile-mariadb-win.json",
        "literals": VANILLA_DB_NAMES,
        "reason": (
            "lockfile provenance names the classic* databases the lane seeds "
            "(mariadb win lane twin of the sqlite win lockfile; seed corpus "
            "identical, backend pins differ)"
        ),
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
        "literals": ("5875", "11200", "6005"),
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
        "path": "android/app/src/androidTest/java/com/pocketrealm/importer/ClientGenerationStoreQuarantineInstrumentedTest.kt",
        "literals": ("wow-1.12.1-5875", "wow-2.4.3-8606"),
        "reason": "G3-5 quarantine androidTest seeds a vanilla and a foreign (TBC) generation to prove the scoped prune keeps the foreign one",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/importer/ManifestFlavorShimTest.kt",
        "literals": ("1.12.1.5875", "5875", "wow-1.12.1-5875"),
        "reason": "G3-2 golden-fixture test composes the vanilla client identity the manifest writer stamps",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/importer/GenerationPointerQuarantineTest.kt",
        "literals": ("5875", "wow-1.12.1-5875", "wow-2.4.3-8606"),
        "reason": "G3-5 keep-set test resolves vanilla and foreign (TBC) generations' flavors",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/server/VanillaConfGoldenTest.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": "G3-7 golden conf fixtures pin the vanilla database-name set the templates seed",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/VanillaCatalogPinsTest.kt",
        "literals": ("5875",),
        "reason": "G3-10 pins the binding/settings catalogs to the 5875 capture",
        "retires": "S7 retires: vanilla client datasets",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/ingame/InGameSettingsFilesLayoutPinTest.kt",
        "literals": ("5875", "wow-1.12.1-5875"),
        "reason": "G3-10 WtfLayout path-equality pins use a canonical vanilla client root",
        "retires": "S7 retires: vanilla client datasets",
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
    {
        "path": "tests/test_utf16_gate_coverage.py",
        "literals": None,
        "reason": "the round-3 UTF-16 gate-coverage tests construct banned flavor literals by design to prove both gates sweep encoded text",
        "retires": "never (owning-table test)",
    },
    # ---- code-red round 1 (R1-1/R3-1): the sweep now covers UNTRACKED
    # commit-candidate files (--cached --others --exclude-standard), which
    # surfaced the wave's not-yet-committed surfaces. Every entry below is
    # triaged to the same standard as the tracked set: owning table,
    # generated data, golden fixture, or synthetic test fixture, each with
    # its retiring wave.
    # Per-flavor client catalogs: each catalog IS the owning table for its
    # own flavor's build identity (the WowVanillaBindingCatalog/
    # WowVanillaSettingsCatalog twins above hold the vanilla entries).
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/WowTbcBindingCatalog.kt",
        "literals": ("8606",),
        "reason": "the tbc binding catalog is the owning table for the 8606 build's captured bindings",
        "retires": "S7 retires: per-flavor client datasets (identity routes through the registry)",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/WowWotlkBindingCatalog.kt",
        "literals": ("12340",),
        "reason": "the wotlk binding catalog is the owning table for the 12340 build's captured bindings",
        "retires": "S7 retires: per-flavor client datasets",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ingame/WowTbcSettingsCatalog.kt",
        "literals": ("8606",),
        "reason": "the tbc settings catalog is the owning table for the 8606 build's captured settings",
        "retires": "S7 retires: per-flavor client datasets",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/ingame/WowWotlkSettingsCatalog.kt",
        "literals": ("12340",),
        "reason": "the wotlk settings catalog is the owning table for the 12340 build's captured settings",
        "retires": "S7 retires: per-flavor client datasets",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/client/GeneratedTimeframeOverlayCatalog.kt",
        "literals": None,
        "reason": (
            "GENERATED owning data: tools/generate_timeframe_era.py emits this "
            "catalog from the vanilla timeframe overlays (which target the "
            "vanilla world DB by design) - generated tables own their inputs' "
            "literals the way flavor.json owns the registry's"
        ),
        "retires": "W3 retires: per-flavor era curation regenerates per-flavor catalogs",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/database/TimeframeOverlayPlan.kt",
        "literals": ("classicmangos",),
        "reason": "timeframe overlay plan names the vanilla world DB the overlay SQL applies to",
        "retires": "W3 retires: per-flavor overlay plans route DB names through the registry",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/database/TimeframeOverlayApplier.kt",
        "literals": ("classicmangos",),
        "reason": "timeframe overlay applier writes to the vanilla world DB the overlays target",
        "retires": "W3 retires: per-flavor overlay application",
    },
    {
        "path_prefix": "schemas/timeframes/vanilla/overlays/",
        "literals": None,
        "reason": (
            "the VANILLA timeframe's own overlay SQL targets the vanilla world "
            "DB by design - owning data for the vanilla timeframe lane"
        ),
        "retires": "never while vanilla overlays target classicmangos (W3 adds the sibling flavors' own trees)",
    },
    {
        "path": "tools/generate_timeframe_era.py",
        "literals": ("classicmangos",),
        "reason": "the timeframe-era generator names the vanilla world DB its vanilla overlays write to",
        "retires": "W3 retires: per-flavor era curation parameterizes the target",
    },
    {
        "path": "tools/capture_flavor_framexml.py",
        "literals": ("5875",),
        "reason": "capture harness names the vanilla 109/211 capture precedent build in its provenance prose",
        "retires": "S7 retires: per-flavor capture provenance",
    },
    {
        "path": "tools/generate_flavor_catalogs.py",
        "literals": ("8606", "12340"),
        "reason": (
            "the per-flavor catalog generator emits each flavor's build "
            "identity into its catalog - the tool is the owning-table writer"
        ),
        "retires": "S7 retires: per-flavor catalogs (the generator keeps owning them)",
    },
    # Desktop lane production surfaces (the desktop twins of tracked
    # android entries).
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/database/DesktopMariaDbEngine.kt",
        "literals": VANILLA_DB_NAMES + ("5875",) + ("6005", "6141",),
        "reason": (
            "KNOWN DEFECT (port plan section 3(b), W1 item 2): the desktop "
            "MariaDB bootstrap CREATE DATABASE literals still name the "
            "classic* set and the 5875 build stamp - flavor routing through "
            "the resolver is W1's first work item, both engine twins"
        ),
        "retires": "W1 retires: flavor-routed bootstrap CREATE DATABASE",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/NativeRuntimeFence.kt",
        "literals": VANILLA_DB_NAMES,
        "reason": (
            "desktop freshness fence names the classic* databases of the "
            "mariadb-win lockfile lane it pins"
        ),
        "retires": "S4/W1 retires: per-flavor lockfile lanes carry their own DB name sets",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/client/ClientTweaksRuntime.kt",
        "literals": ("5875", "6005", "6141"),
        "reason": "desktop runtime applies byte-addressed vanilla tweaks pinned to the build-5875 image",
        "retires": "S7 retires: per-flavor client datasets",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/client/DesktopVanillaTweaksPatcher.kt",
        "literals": ("5875",),
        "reason": "desktop patcher applies byte-addressed vanilla tweaks pinned to the build-5875 image",
        "retires": "S7 retires: per-flavor client datasets",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/FirstRunTutorial.kt",
        "literals": ("5875",),
        "reason": "desktop UX copy names the client the user must supply (the vanilla twin's S7 entry class)",
        "retires": "S7 retires: per-flavor UX copy",
    },
    # Per-flavor win lockfiles (tool-written by the W0 rebuilds).
    {
        "path": "schemas/realm-runtime-lockfile-sqlite-win-tbc.json",
        "literals": VANILLA_DB_NAMES,
        "reason": (
            "tool-written per-flavor win lockfile: its seed-transcript "
            "provenance still records the classic* names the lane seeded "
            "(the win lanes have not yet re-keyed per flavor)"
        ),
        "retires": "S4 retires: per-flavor DB routing re-keys the transcripts",
    },
    {
        "path": "schemas/realm-runtime-lockfile-sqlite-win-wotlk.json",
        "literals": VANILLA_DB_NAMES,
        "reason": (
            "tool-written per-flavor win lockfile: its seed-transcript "
            "provenance still records the classic* names the lane seeded"
        ),
        "retires": "S4 retires: per-flavor DB routing re-keys the transcripts",
    },
    # G3 golden fixtures (D27: byte-stable pins, never edited).
    {
        "path": "android/app/src/test/resources/fixtures/g3/client-manifest-current-release.json",
        "literals": None,
        "reason": "G3-2 golden fixture bytes pin the vanilla identity the manifest writer stamps (D27: never edited)",
        "retires": "never while the golden seal stands (D27)",
    },
    {
        "path": "android/app/src/test/resources/fixtures/g3/data-manifest-current-release.json",
        "literals": None,
        "reason": "G3-2 golden fixture bytes pin the vanilla identity the data manifest stamps (D27: never edited)",
        "retires": "never while the golden seal stands (D27)",
    },
    {
        "path_prefix": "android/app/src/test/resources/fixtures/g3/conf/",
        "literals": None,
        "reason": "G3-7 golden conf fixtures pin the vanilla database-name set the templates seed (D27: never edited)",
        "retires": "never while the golden seal stands (D27)",
    },
    # Synthetic per-flavor test fixtures (android + desktop).
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ActiveFlavorTest.kt",
        "literals": ("5875", "11200"),
        "reason": "synthetic vanilla-lane fixture proving ActiveFlavor resolution",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/LoginTopologiesTest.kt",
        "literals": ("5875", "8606", "12340"),
        "reason": "synthetic per-build login-topology fixtures for all three captured builds",
        "retires": "S5/S7 retires: per-flavor fixtures + topology promotion",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/MultiQualifiedLaneResolutionTest.kt",
        "literals": ("5875", "8606", "6005", "6141"),
        "reason": "synthetic two-qualified-flavor fixture proving lane-scoped resolution",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/importer/DataPreparationStoreTest.kt",
        "literals": ("wow-1.12.1-5875",),
        "reason": "R2-4 quarantine fixtures pin the verified vanilla clientId the generation stamp must resolve",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/TwoQualifiedFlavors.kt",
        "literals": ("8606", "wow-2.4.3-8606"),
        "reason": "shared synthetic two-qualified-flavor registry fixture (tbc identity by design)",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/database/RealmBuildsProjectionContractTest.kt",
        "literals": ("classicrealmd",),
        "reason": "realmd fixture pins the vanilla realmlist projection",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/ui/TunnelInviteTest.kt",
        "literals": ("5875",),
        "reason": "tunnel-invite fixture pins the registry-derived client line's vanilla rendering",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/client/DesktopVanillaTweaksPatcherTest.kt",
        "literals": ("5875",),
        "reason": "synthetic vanilla patcher fixture",
        "retires": "S7 retires: per-flavor client datasets",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopDatabaseServiceTest.kt",
        "literals": ("5875", "6005", "6141"),
        "reason": "synthetic vanilla identity fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopMariaDbEngineTest.kt",
        "literals": ("5875", "12340", "classicmangos", "classicrealmd", "6005", "6141"),
        "reason": (
            "desktop engine fixtures: vanilla bootstrap rows (the W1-routing "
            "defect's own test surface) plus a 12340-flavored row proving "
            "per-flavor DB name routing where it already exists"
        ),
        "retires": "W1 retires with the bootstrap routing; the 12340 row retires at S8",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopSqliteSealsTest.kt",
        "literals": ("classiclogs",),
        "reason": "sqlite seal fixture names the vanilla logs DB it seals",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopTimeframeOverlayTest.kt",
        "literals": ("classicmangos",),
        "reason": "timeframe overlay fixture targets the vanilla world DB",
        "retires": "W3 retires: per-flavor overlays",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/AuthGateTest.kt",
        "literals": ("1.12.1.5875", "5875"),
        "reason": "synthetic vanilla auth fixture (the desktop auth-gate lane)",
        "retires": "S8 retires: per-flavor desktop auth fixtures",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopAppModelTest.kt",
        "literals": ("classicrealmd",),
        "reason": "synthetic vanilla realmd fixture",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopRealmArchiveTest.kt",
        "literals": ("classicrealmd",),
        "reason": "synthetic vanilla realmd archive fixture",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopSnapshotsRestoreTest.kt",
        "literals": ("classicrealmd",),
        "reason": "synthetic vanilla snapshot fixture",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopStorageRootsTest.kt",
        "literals": ("classicrealmd",),
        "reason": "synthetic vanilla datadir fixture",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopTunnelBackendTest.kt",
        "literals": ("5875", "6005", "6141"),
        "reason": "synthetic vanilla tunnel-lane identity fixture",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/FirstRunTutorialTest.kt",
        "literals": ("5875",),
        "reason": "tutorial copy fixture (vanilla rendering)",
        "retires": "S7 retires: per-flavor UX copy",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/NativeRuntimeFenceTest.kt",
        "literals": ("classiccharacters", "classicmangos", "classicrealmd"),
        "reason": "fence fixture pins the mariadb-win lane's vanilla DB set the fence verifies",
        "retires": "S4/W1 retires: per-flavor lockfile lanes",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/Win32AutoLoginSignatureTest.kt",
        "literals": ("5875",),
        "reason": "signature-test prose names the pinned build-5875 login frame (DESK-138)",
        "retires": "S8 retires: per-flavor desktop auth fixtures",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/importer/DesktopImportStoragePlannerTest.kt",
        "literals": ("classicrealmd",),
        "reason": "synthetic vanilla import fixture",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/server/DesktopTunnelServerFilesTest.kt",
        "literals": ("5875",),
        "reason": "tunnel conf fixture stamps the vanilla identity",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "tests/test_davos_tools_error_reporting.py",
        "literals": ("classiccharacters", "classicrealmd"),
        "reason": "repo test constructs the vanilla DB names davos tools report on",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "tests/test_install_client_windows_ps1.py",
        "literals": ("1.12.1.5875", "5875", "8606"),
        "reason": (
            "installer-lane tests stage genuine vanilla 1.12.1.5875 archives "
            "and a registered tbc 8606 client for the -Flavor ranking rows"
        ),
        "retires": "S5 retires: client identity qualification via the registry",
    },
    {
        "path": "tests/test_verify_seed_sqlite_floor.py",
        "literals": ("classiccharacters",),
        "reason": "seed-floor test names the vanilla characters DB it floors",
        "retires": "S4 retires: per-flavor DB routing",
    },
    {
        "path": "tests/test_win_prepare_data_flavors.py",
        "literals": ("5875", "8606", "12340", "expansion.mpq", "lichking.mpq"),
        "reason": "per-flavor prep tests pin the FLAVOR_IDENTITIES build numbers (owning-table pins)",
        "retires": "S5 retires: client identity qualification via the registry",
    },

    # ------------------------------------------------------------------
    # R2UI-3 (android-ui-tests review round 2): the §4.6 lane tokens
    # (realmbuilds-adjacent builds 6005/6141, the per-flavor addon
    # interface numbers 11200/20400/30300, the expansion MPQ names) joined
    # the banned set; these owning surfaces legitimately carry them.
    # ------------------------------------------------------------------
    {
        "path": "android/app/detekt-baseline.xml",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/addons/AddonRuntimeProjectorTest.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/androidTest/java/com/pocketrealm/client/SafClientScannerTest.kt",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/debug/java/com/pocketrealm/importer/ImportFixtureProvider.java",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/android-port/AndroidPort/ActionBars.lua",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/android-port/AndroidPort/AndroidPort.toc",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/android-port/AndroidPort/Bags.lua",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/android-port/AndroidPort/Core.lua",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/android-port/AndroidPort/FrameMover.lua",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/android-port/AndroidPort/Hud.lua",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/android-port/AndroidPort/Talk.lua",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/assets/addons/catalog-v1.json",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/addons/AddonArchiveValidator.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/addons/AddonRepository.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/main/java/com/pocketrealm/addons/AddonRuntimeProjector.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/addons/AddonArchiveValidatorTest.kt",
        "literals": ("11200", "20400", "30300",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/addons/AddonFlavorCatalogTest.kt",
        "literals": ("wow-3.3.5a-12340",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/addons/AddonOperationSafetyTest.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/addons/AndroidPortAssetTest.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/addons/VanillaAddonPinTest.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ActiveFlavorTest.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/ClientFlavorTest.kt",
        "literals": ("11200", "20400", "30300", "6005", "6141", "expansion.mpq", "lichking.mpq",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/MpqSetKeyFormTripwireTest.kt",
        "literals": ("5875",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/MultiQualifiedLaneResolutionTest.kt",
        "literals": ("6005", "6141",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/client/R2DoctoredMpqScannerTest.kt",
        "literals": ("1.12.1.5875", "2.4.3.8606", "3.3.5.12340", "wow-1.12.1-5875",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/database/TimeframeOverlayPlanTest.kt",
        "literals": ("classicmangos",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/importer/ArchiveDetectionTest.kt",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/server/PreparedDataVerifyWalkTest.kt",
        "literals": ("5875",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/server/R2WrongFlavorManifestMatrixTest.kt",
        "literals": ("12340", "5875", "8606", "wow-1.12.1-5875", "wow-2.4.3-8606",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "tests/test_addons_wiki_pin.py",
        "literals": ("11200", "20400", "30300"),
        "reason": "the Add-ons wiki doc-pin asserts the wiki's interface-number line covers all three lanes' accepted interfaces (owning fixture)",
        "retires": "S5 retires: per-flavor fixtures",
    },
    {
        "path": "android/app/src/test/java/com/pocketrealm/server/R3BreakerNegativeTest.kt",
        "literals": ("1.12.1.5875", "5875", "6005", "6141", "expansion.mpq", "lichking.mpq", "wow-1.12.1-5875",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/addons/DesktopAddonRepository.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/addons/DesktopAddonRuntimeProjector.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/main/kotlin/com/pocketrealm/database/DesktopMariaDbEngine.kt",
        "literals": ("6005", "6141",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/addons/DesktopAddonRuntimeProjectorTest.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/addons/DesktopAddonValidatorFlavorSeamTest.kt",
        "literals": ("11200",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/client/DesktopClientScannerTest.kt",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopDatabaseServiceTest.kt",
        "literals": ("6005", "6141",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/database/DesktopMariaDbEngineTest.kt",
        "literals": ("6005", "6141",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopClientIdentityGateTest.kt",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopClientSelectionStateTest.kt",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackendTest.kt",
        "literals": ("6005", "6141",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopTunnelBackendTest.kt",
        "literals": ("6005", "6141",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/importer/ManagedClientImporterArchiveTest.kt",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "desktop/src/test/kotlin/com/pocketrealm/importer/ManagedClientImporterTest.kt",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "schemas/database-migrations-vanilla.json",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "schemas/database-migrations.json",
        "literals": ("6005",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "tests/test_win_prepare_data_flavors.py",
        "literals": ("expansion.mpq", "lichking.mpq",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "tools/check_flavor.py",
        "literals": ("6005", "6141",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
    },
    {
        "path": "tools/win_prepare_data.py",
        "literals": ("expansion.mpq", "lichking.mpq",),
        "reason": "owning surface (see ALLOWLIST_CORE family note)",
        "retires": "W3.4/W4.4 retires: per-flavor lane surfaces",
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
    """Every COMMITTED-or-COMMITTABLE text file, or None when the git
    enumeration cannot run (not a checkout / git failure).

    R1-1/R3-1: the enumeration is `--cached --others --exclude-standard`
    — the index PLUS the untracked files a finalize commit would sweep in.
    The old tracked-only scan left untracked production code (e.g. an
    uncompiled-yet DesktopMariaDbEngine.kt full of classic* literals)
    structurally invisible to the tripwire until the commit flipped it red."""
    import subprocess

    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files",
             "--cached", "--others", "--exclude-standard", "-z"],
            capture_output=True, timeout=120,
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
            head_bytes = path.open("rb").read(8192)
        except OSError:
            pass  # unreadable files surface as <unreadable> findings
        else:
            # Round-3 R3-2: UTF-16/32 text carries NUL bytes but is text —
            # a whole file class (PowerShell 5.1 Out-File default) used to
            # vanish from the sweep silently. Decode via BOM; skip only
            # genuinely binary files.
            if bytes([0]) in head_bytes and _decode_bom_text(path) is None:
                continue
        paths.append(path)
    return paths


def _decode_bom_text(path: Path) -> str | None:
    """Decode a UTF-16/32 file via its BOM; None when no BOM (binary)."""
    try:
        raw = path.read_bytes()
    except OSError:
        return None
    for bom, encoding in (
        (bytes([0xFF, 0xFE, 0x00, 0x00]), "utf-32"),
        (bytes([0x00, 0x00, 0xFE, 0xFF]), "utf-32"),
        (bytes([0xFF, 0xFE]), "utf-16"),
        (bytes([0xFE, 0xFF]), "utf-16"),
    ):
        if raw.startswith(bom):
            try:
                return raw.decode(encoding)
            except (UnicodeDecodeError, ValueError):
                return None
    return None


def _git_binary_skipped(root: Path) -> int:
    """Count tracked files the NUL-byte sniff classifies as binary (json
    reporting only: the sweep silently skips these, so the report says so)."""
    import subprocess

    try:
        result = subprocess.run(
            ["git", "-C", str(root), "ls-files",
             "--cached", "--others", "--exclude-standard", "-z"],
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


def _ledger_pair_findings(root: Path) -> list[dict[str, object]]:
    """G3-3 item 3: the vanilla ledger path set — canonical
    `schemas/database-migrations-vanilla.json` plus the deprecation-window
    alias `schemas/database-migrations.json` — must be byte-identical to each
    other, not just literal-scanned. The S1 ledger rename is additive; a
    divergence between the two names means a consumer would apply different
    migration sets depending on which name it resolved."""
    canonical = root / "schemas" / "database-migrations-vanilla.json"
    alias = root / "schemas" / "database-migrations.json"
    if not (canonical.is_file() and alias.is_file()):
        return []
    try:
        canonical_bytes = canonical.read_bytes()
        alias_bytes = alias.read_bytes()
    except OSError:
        return []  # unreadable files already surface as <unreadable> findings
    if canonical_bytes == alias_bytes:
        return []
    return [
        {
            "path": "schemas/database-migrations-vanilla.json",
            "line": 0,
            "literals": ["<ledger-pair-divergence>"],
            "text": (
                "per-flavor ledger gate: vanilla ledger path set "
                "{schemas/database-migrations-vanilla.json, "
                "schemas/database-migrations.json} must stay byte-identical "
                "to each other (G3-3)"
            ),
        }
    ]


def _journal_schema_findings(root: Path) -> list[dict[str, object]]:
    """G3-4: the supervisor journal schema stays 3 — the session flavor
    entered as an optional field, never a schema bump (a strict decode at a
    bumped schema would strand any device killed mid-session just before an
    app update). Any `JOURNAL_SCHEMA = <n>` other than 3 under the supervisor
    package is a finding."""
    supervisor = root / "android" / "app" / "src" / "main" / "java" / "com" / "pocketrealm" / "supervisor"
    if not supervisor.is_dir():
        return []
    findings: list[dict[str, object]] = []
    for path in sorted(supervisor.glob("*.kt")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            continue  # unreadable files already surface as <unreadable> findings
        for lineno, line in enumerate(text.splitlines(), 1):
            if re.search(r"JOURNAL_SCHEMA\s*=\s*(?!3\b)\d+", line):
                findings.append(
                    {
                        "path": path.relative_to(root).as_posix(),
                        "line": lineno,
                        "literals": ["<journal-schema-bump>"],
                        "text": line.strip(),
                    }
                )
    return findings


def sweep(
    root: Path, allowlist: tuple[dict[str, object], ...] = ALLOWLIST
) -> tuple[list[dict[str, object]], int, int]:
    """Return (findings, allowlisted count, files scanned). Stale scoped
    allowlist entries are reported as findings (config errors).

    R2-2 fail-closed: a scan that resolves ZERO files is reported as a loud
    degraded-scan finding — a tripwire that saw nothing must never answer
    OK (the legacy SCAN_ROOTS fallback exists for synthetic test trees; a
    root with neither git nor any SCAN_ROOTS dir is a coverage failure)."""
    findings: list[dict[str, object]] = []
    allowlisted = 0
    paths = scan_paths(root)
    if not paths:
        findings.append(
            {
                "path": str(root),
                "line": 0,
                "literals": ["<degraded-scan>"],
                "text": (
                    "leak_sweep: the scan resolved ZERO files (git "
                    "unavailable and no legacy SCAN_ROOTS directory exists) "
                    "— a sweep that saw nothing is a coverage failure, never "
                    "a pass"
                ),
            }
        )
        # W0 close-out: the structural surfaces do not depend on the
        # content-scan path yield. A synthetic tree carrying only a
        # structural surface (e.g. a schemas/ ledger-pair fixture with no
        # .kt/.py file anywhere) must still get the pair/journal/stale
        # checks — the round-2 degraded early-return was skipping them.
        # The degraded tripwire itself stays (zero content files is still
        # a coverage failure, never a pass).
        findings.extend(_stale_allowlist_findings(root, allowlist))
        findings.extend(_ledger_pair_findings(root))
        findings.extend(_journal_schema_findings(root))
        return findings, 0, 0
    for path in paths:
        rel = path.relative_to(root).as_posix()
        entry = _allowlist_entry(rel, allowlist)
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            # Round-3 R3-2: a UTF-16/32 BOM file fails the utf-8 read by
            # design — decode via its BOM before calling it unreadable.
            decoded = _decode_bom_text(path)
            if decoded is not None:
                text = decoded
            else:
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
    findings.extend(_ledger_pair_findings(root))
    findings.extend(_journal_schema_findings(root))
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
