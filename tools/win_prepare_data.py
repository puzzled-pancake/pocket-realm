#!/usr/bin/env python3
"""Windows data-preparation bring-up: client -> prepared world data.

Runs the four Windows extractors (built by tools/build_win_realm_runtime.py
--flavor <id> --extractors) against a genuine client of the requested
flavor (vanilla 1.12.1 / tbc 2.4.3 / wotlk 3.3.5a), then assembles the
PreparedDataStore contract the world server boots from:

  <datadir>/generations/<uuid>/{dbc,maps,vmaps,mmaps} + data-manifest.json
  <datadir>/active-<flavorId>.json   (canonical per-flavor pointer, DESK-128)
  <datadir>/active.json              (legacy un-namespaced mirror — kept for
                                      one transition release so a shared
                                      PreparedDataStore without the flavor
                                      namespace keeps resolving)

Resumability is VALIDATION-based, not existence-based: a step is skipped
only when its output passes this tool's completeness validation. An
interrupted ad, vmap assembly, or MoveMapGen run leaves partial output
behind that fails validation and is wiped and regenerated. MoveMapGen exits 0
even when individual per-map builds fail, so its stdout is scanned for
per-map failures and every extracted map must be covered by at least one
mmtile before the step counts as complete — with one measured exception:
wotlk's generator additionally emits the alt tile form
%03u%02i%02i_NN.mmtile (its runtime loads both forms) and silently yields
zero tiles for a few of the shipped client's maps (605/606 in the 3.3.5a
enUS set); on the wotlk lane those maps are accepted only when the same
run's stdout scanned clean of per-map failures.

The client directory is left byte-clean: ad.exe runs with -e 3 (maps+dbc
only — never the Cameras lane), extractor outputs are moved out to the
workspace as soon as they land, the client's entry set is snapshotted
before/after every extractor run, and a failure path removes whatever
the extractor created before exiting non-zero.

Usage: python tools/win_prepare_data.py [--client <dir>] [--flavor vanilla|tbc|wotlk]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
# Per-flavor win build roots (the naming schema of record is
# tools/flavor_layout.py; these mirror select_win_flavor in
# tools/build_win_realm_runtime.py): vanilla keeps the historical
# unsuffixed root, tbc/wotlk carry the -{flavor} suffix. select_flavor()
# rebinds EXTRACTORS/WORKSPACE/MIN_COUNTS to the active flavor's lane; the
# module-level literals below are the vanilla defaults so direct importers
# (tests, publish-only callers) keep the historical behavior.
WIN_BUILD_ROOTS = {
    "vanilla": ROOT / "native/.build-win-x86_64",
    "tbc": ROOT / "native/.build-win-x86_64-tbc",
    "wotlk": ROOT / "native/.build-win-x86_64-wotlk",
}
# The ACTIVE flavor's identity (id + lane paths); "vanilla" until
# select_flavor() rebinds it — never a second mutable default.
FLAVOR = "vanilla"
EXTRACTORS = WIN_BUILD_ROOTS["vanilla"] / "bin/x64_Release/Extractors"
DEFAULT_CLIENT = Path("E:/Wow clients/WoW_Classic_ENG_1.12.1")
WORKSPACE = WIN_BUILD_ROOTS["vanilla"] / "data-prep"
# Resolved in main() AFTER the os.name check: reading LOCALAPPDATA at
# import time raised KeyError on any host without it, making main()'s
# windows-only guard unreachable. None until main() assigns it (callers
# driving publish() directly assign it themselves).
DATADIR: Path | None = None

STEP_TIMEOUT_S = 4 * 60 * 60

# ad.exe -e mask: MAP(1) | DBC(2). Camera(4) is deliberately excluded so
# the extractor never creates <client>/Cameras at all. The mask semantics
# are identical across the three flavors' contrib/extractor ad.exe.
AD_EXTRACT_MASK = "3"

# PreparedDataStore schema-1 publication minimums PER FLAVOR; validated
# BEFORE the active pointer files are touched so a partial generation can
# never become the boot source. MIN_COUNTS is the ACTIVE flavor's row
# (rebound by select_flavor) — validate_ad/validate_counts/publish all
# read it, so a tbc run is gated by tbc's floors, never vanilla's.
# vanilla's floors are the historical round numbers below its measured
# first generation (dbc=158, maps=2429, vmapTrees=43, mmapMaps=43,
# mmapTiles=1966 — 2026-10). tbc and wotlk were re-derived the same way
# from their own first generations. Floors are never copied across flavors
# as if measured — a floor above a flavor's real yield would brick its
# lane, and a vanilla floor on a tbc run would let a truncated TBC
# extraction publish.
# tbc measured (generation 84002584, staged 2.4.3.8606 enUS client):
# dbc=185, maps=3586, vmapTrees=72, mmapMaps=72, mmapTiles=2748.
# wotlk measured (generation 09613a74-1b08-4993-9cb0-4dc1e716eb16,
# desktop-assisted prep, staged 3.3.5a.12340 enUS client, 2026-10-11):
# dbc=247, maps=5744, vmapTrees=101, mmapMaps=103, mmapTiles=4105.
FLAVOR_MIN_COUNTS = {
    "vanilla": {"dbc": 100, "maps": 100, "vmapTrees": 1, "mmapMaps": 1, "mmapTiles": 1},
    "tbc": {"dbc": 150, "maps": 3500, "vmapTrees": 60, "mmapMaps": 60, "mmapTiles": 2500},
    "wotlk": {"dbc": 200, "maps": 5000, "vmapTrees": 80, "mmapMaps": 80, "mmapTiles": 3500},
}
MIN_COUNTS = dict(FLAVOR_MIN_COUNTS["vanilla"])

# Per-flavor pointer namespace + manifest identity (DESK-128): the
# canonical pointer is active-<flavorId>.json and the manifest carries the
# flavor alias key that the shared PreparedDataStore's
# validateDataManifestEnvelope resolves through cmangosFamily/clientBuild
# (ClientFlavor: flavorId slug, build, serverFamily — families
# classic/tbc/wotlk, android/app/.../client/ClientFlavor.kt). A flavor's
# entry here is the contract that its whole lane exists: per-flavor
# extractors (build_win_realm_runtime.py --flavor <id> --extractors at the
# same WIN_BUILD_ROOTS root), a client shape (CLIENT_SHAPE below), and its
# own publication floors (FLAVOR_MIN_COUNTS). An identity without its
# extractor lane would publish an unbootable generation — refusing unknown
# flavors in main() stays the loud gate.
FLAVOR_ID = re.compile(r"^[a-z][a-z0-9-]{1,31}$")
FLAVOR_IDENTITIES = {
    "vanilla": {"clientBuild": 5875, "cmangosFamily": "classic"},
    "tbc": {"clientBuild": 8606, "cmangosFamily": "tbc"},
    "wotlk": {"clientBuild": 12340, "cmangosFamily": "wotlk"},
}

# The client-shape contract per flavor (the check extract() refuses on):
# the root executable plus the Data/ entries that identify a genuine
# client of that flavor. Vanilla 1.12.1 ships its base content as
# Data/base.MPQ; TBC/WotLK split the base content across
# common*/expansion*/patch* archives and carry a locale subfolder
# (Data/<locale>/locale-*.MPQ). LOCALE_ENTRY matches any known locale
# directory — the staged genuine clients are enUS, but a deDE client is
# equally genuine (the extractors read the locale archives by scanning
# Data/, not by hard-coding enUS). The shapes must be MUTUALLY EXCLUSIVE:
# wotlk's archive set is a strict superset of tbc's (it only adds
# lichking.MPQ), so required-entries alone would wave a wotlk client
# through the tbc lane and extract wrath-era bytes into a tbc generation.
# The forbidden markers pin the boundary: vanilla refuses common.MPQ,
# tbc refuses lichking.MPQ, wotlk requires it.
LOCALE_ENTRY = "<locale>"
CLIENT_LOCALES = ("enUS", "enGB", "koKR", "frFR", "deDE",
                  "zhCN", "zhTW", "esES", "esMX", "ruRU")
CLIENT_SHAPE = {
    "vanilla": {"exe": "WoW.exe", "data_entries": ("base.MPQ",),
                "forbidden": ("common.MPQ", "lichking.MPQ")},
    "tbc": {"exe": "WoW.exe", "data_entries": ("common.MPQ", "expansion.MPQ", LOCALE_ENTRY),
            "forbidden": ("base.MPQ", "lichking.MPQ")},
    "wotlk": {"exe": "WoW.exe",
              "data_entries": ("common.MPQ", "expansion.MPQ", "lichking.MPQ", LOCALE_ENTRY),
              "forbidden": ("base.MPQ",)},
}


def select_flavor(flavor_id: str) -> None:
    """Bind the module's per-flavor lane paths to a flavor: extractor home,
    staging workspace, and publication floors all live under the flavor's
    WIN_BUILD_ROOTS root. The workspace MUST be flavor-scoped, not shared:
    the resume skip is validation-based, so a tbc run against vanilla's
    complete workspace would skip every extractor and publish vanilla
    bytes under a tbc manifest — the same cross-flavor contamination
    DESK-128's per-flavor pointers exist to prevent."""
    global FLAVOR, EXTRACTORS, WORKSPACE, MIN_COUNTS, MMTILE_NAME
    if flavor_id not in FLAVOR_IDENTITIES:
        raise StepFailure(
            f"flavor {flavor_id!r} has no qualified manifest identity "
            f"(qualified: {', '.join(sorted(FLAVOR_IDENTITIES))})")
    FLAVOR = flavor_id
    EXTRACTORS = WIN_BUILD_ROOTS[flavor_id] / "bin/x64_Release/Extractors"
    WORKSPACE = WIN_BUILD_ROOTS[flavor_id] / "data-prep"
    MIN_COUNTS = dict(FLAVOR_MIN_COUNTS[flavor_id])
    MMTILE_NAME = FLAVOR_MMTILE_NAME[flavor_id]

# The tile-less maps the wotlk clean-stdout waiver may accept — the STABLY
# measured set: the archived full-stdout probe (
# out/completion/w2/w2_1_movemapgen_probe_stdout.log, 161,404 bytes =
# 2,407 LF + 2,945 CR — progress prints are CR-terminated, so a decoded
# text read's "line count" is newline-translation-inflated; count bytes),
# zero "Failed"/"FIALED" lines, shows maps 605/606 completing with ZERO
# tile writes — their per-tile BUILD ATTEMPTS are printed (9 for 605,
# 2 for 606, MapBuilder.cpp:673's "Building tile [x,y] (i / n)") and all
# silently skip on the no-vertices branch (cmangos-wotlk
# MapBuilder.cpp:1042-1050).
# 616 is deliberately NOT in this set: its emptiness is RUN-NONDETERMINISTIC
# (the wave's first archived run refused 605/606/616 — zero 616 tiles after
# a completed clean-stdout MoveMapGen; the official rerun gave 616 exactly
# one alt tile) — a 616-empty run on a future re-run FAILS CLOSED here and a
# re-run recovers it. The waiver is PINNED to this measurement
# any OTHER tile-less map — or a wider set than these two —
# refuses even on a failure-clean stdout, mirroring the floors'
# measured-provenance discipline (a future generator defect that silently
# skips maps can never ride the exception wider than its evidence).
ALLOWED_TILELESS_WOTLK = frozenset({"605", "606"})

# The per-lane refusal copy naming the ACTIVE mmtile contract: the
# wotlk lane accepts the alt form, so its refusal must say so — the flat
# "expected 7 digits" misstates the accepted shape precisely where the
# flavor-awareness lives.
MMTILE_NAME_COPY = {
    "vanilla": "expected 7 digits",
    "tbc": "expected 7 digits",
    "wotlk": "expected 7 digits, or 7 digits + _NN",
}

# mmtile names are packed digits: %03d map + %02d x + %02d y. A name that
# does not match (e.g. a future go%04u gameobject tile) must fail loudly
# instead of polluting the mmapMaps count. wotlk's extractor ALSO emits the
# alt form %03u%02i%02i_NN.mmtile — the per-building-permutation navmesh
# tiles its own generator writes (cmangos-wotlk contrib/mmap/src/
# MapBuilder.cpp:1090) and its own runtime loads (TILE_ALT_FILE_NAME_FORMAT,
# src/game/MotionGenerators/MoveMap.cpp:28) — so the wotlk lane accepts
# exactly one _NN suffix. The vanilla/tbc generators carry no alt branch
# (their MapBuilder sources emit the plain form only), so their lanes keep
# the strict 7-digit contract: an alt tile in a vanilla/tbc workspace is
# still cross-flavor contamination and still refuses.
FLAVOR_MMTILE_NAME = {
    "vanilla": re.compile(r"^\d{7}$"),
    "tbc": re.compile(r"^\d{7}$"),
    "wotlk": re.compile(r"^\d{7}(_\d{2})?$"),
}
MMTILE_NAME = FLAVOR_MMTILE_NAME["vanilla"]

# MoveMapGen prints "[Map NNN] Failed creating navmesh!" on per-map build
# failures while still exiting 0 — and the wotlk generator's allocation
# failures print the core's own misspelling "FIALED" (native/cmangos-wotlk
# contrib/mmap/src/MapBuilder.cpp:950 "alloc iv.polyMesh FIALED!" and :961
# "alloc m_dmesh FIALED!") the oracle must catch both spellings or an
# alloc-failed map scans as "failure-clean" and the tile-less waiver below
# would accept it as a legitimate empty map.
MOVEMAP_FAILURE = re.compile(r"\[Map\s+\d+\].*(?:Failed|FIALED)", re.IGNORECASE)

# Directories the extractors may create INSIDE the client root. Anything
# else appearing (or any of these pre-existing) is an error: the tool
# must never touch files it did not create.
EXTRACTOR_CLIENT_DIRS = ("dbc", "maps", "buildings", "Cameras")


class StepFailure(RuntimeError):
    """An extractor/publish step failed; partial client-side outputs
    have already been removed by the handler."""


def run(step: str, cwd: Path, cmd: list[str]) -> str:
    print(f"[{step}] {' '.join(str(c) for c in cmd)} (cwd={cwd})", flush=True)
    started = time.time()
    try:
        result = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
                                timeout=STEP_TIMEOUT_S)
    except subprocess.TimeoutExpired as error:
        raise StepFailure(f"{step} timed out after {STEP_TIMEOUT_S}s") from error
    combined = (result.stdout or "") + (result.stderr or "")
    if result.returncode != 0 or "exit with errors" in combined:
        raise StepFailure(f"{step} failed (rc={result.returncode}):\n{combined[-4000:]}")
    if result.stdout:
        print(f"[{step}] {result.stdout[-600:]}", flush=True)
    print(f"[{step}] done in {time.time() - started:.0f}s", flush=True)
    # R2-r2-2 (W2 code-red round 2): the failure oracle must see BOTH streams
    # — the generator's per-map write failures go through perror() → stderr
    # (cmangos-wotlk MapBuilder.cpp:853-854/:1095-1096) while rc stays 0, so
    # a stdout-only return scanned 'failure-clean' past them.
    return combined


def move_tree_contents(source: Path, target: Path, step: str) -> None:
    if not source.is_dir():
        raise StepFailure(f"{step}: expected output directory missing: {source}")
    target.mkdir(parents=True, exist_ok=True)
    for entry in source.iterdir():
        shutil.move(str(entry), str(target / entry.name))
    source.rmdir()


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


# ---------------- client-dir cleanliness ----------------

def client_entries(client: Path) -> set[str]:
    return {entry.name.lower() for entry in client.iterdir()}


def assert_extractor_dirs_absent(client: Path) -> None:
    """A pre-existing extractor-named dir in the client is never ours to
    move out — refuse rather than touch user files."""
    present = [name for name in EXTRACTOR_CLIENT_DIRS if (client / name).exists()]
    if present:
        raise StepFailure(
            f"{client} already contains {present}; refusing to touch client-owned "
            "directories — remove them manually if they are extractor leftovers")


def clean_client_side_outputs(client: Path, before: set[str]) -> None:
    """Remove dirs an extractor created inside the client (failure path)."""
    for name in EXTRACTOR_CLIENT_DIRS:
        created = client / name
        if name.lower() in before or not created.exists():
            continue
        shutil.rmtree(created, ignore_errors=True)
        print(f"[cleanup] removed extractor-created {created}", flush=True)


# ---------------- step validation ----------------

def maps_ids(workspace: Path) -> set[str]:
    """The %03d map ids present in maps/ (from NNNNNNN.map names)."""
    return {path.name[:3] for path in (workspace / "maps").glob("*.map")}


def validate_ad(workspace: Path) -> list[str]:
    """The ad step is skippable only when its staged outputs already meet
    the publication minimums publish enforces — an interrupted ad.exe run
    leaves partial dirs behind that must be wiped and re-extracted, never
    skipped on mere existence."""
    problems = []
    for kind, pattern in (("dbc", "*.dbc"), ("maps", "*.map")):
        count = len(list((workspace / kind).glob(pattern)))
        if count < MIN_COUNTS[kind]:
            problems.append(f"{kind}={count} < {MIN_COUNTS[kind]}")
    return problems


def validate_vmaps(workspace: Path) -> list[str]:
    problems = []
    vmtrees = list((workspace / "vmaps").glob("*.vmtree"))
    if not vmtrees:
        problems.append("no .vmtree files under vmaps/")
    return problems


def validate_mmaps(workspace: Path, movemap_stdout_clean: bool | None = None) -> list[str]:
    """movemap_stdout_clean: None (the resume-skip context — no run stdout
    exists to trust) keeps the strict every-map-covered contract; True means
    the MoveMapGen stdout whose step produced this workspace scanned clean of
    per-map failure lines. Only the wotlk lane may waive a tile-less map on
    that basis: its generator silently drops tiles whose mesh data has no
    vertices (the skip branch at contrib/mmap/src/MapBuilder.cpp:1042-1050 —
    its printf is deliberately commented out) and whole maps of the shipped
    client yield zero tiles that way (measured 2026-10-11 on the staged
    3.3.5a enUS client: the probe shows maps 605/606 completing with zero
    tile writes — their per-tile build attempts are printed (9 for 605,
    2 for 606) and all silently skip; the full-stdout probe is archived at
    out/completion/w2/).
    The waiver is BOUNDED to that measured set (ALLOWED_TILELESS_WOTLK — any
    other or wider tile-less set refuses even on a clean stdout; 616's
    emptiness is run-nondeterministic and refuses BY DESIGN, R3-r2-1), and a
    hard per-map failure never reaches it: the MOVEMAP_FAILURE scan aborts
    the step before validation, and the oracle matches BOTH of the generator's
    failure spellings — "Failed" and the core's own misspelled alloc
    "FIALED" (MapBuilder.cpp:950/:961, R5-1) — so the waiver accepts exactly
    the extractor's own documented empty output, never a failed build."""
    problems = []
    tiles = list((workspace / "mmaps").glob("*.mmtile"))
    if not tiles:
        problems.append("no .mmtile files under mmaps/")
        return problems
    tile_maps: set[str] = set()
    for path in tiles:
        if not MMTILE_NAME.match(path.stem):
            problems.append(
                f"unexpected mmtile name ({MMTILE_NAME_COPY[FLAVOR]}): {path.name}")
        else:
            tile_maps.add(path.name[:3])
    # Coverage: every extracted map must have at least one navmesh tile.
    # MoveMapGen exits 0 even when per-map builds fail, so a missing map
    # here is the only signal that the step is incomplete — except where the
    # clean-stdout waiver applies, and ONLY for the measured tile-less set
    # (ALLOWED_TILELESS_WOTLK): a wider missing set refuses even on a
    # failure-clean stdout, so a future generator defect that silently skips
    # maps can never ride the exception wider than its measurement.
    missing = sorted(maps_ids(workspace) - tile_maps)
    if missing:
        if FLAVOR == "wotlk" and movemap_stdout_clean \
                and set(missing) <= ALLOWED_TILELESS_WOTLK:
            print(f"[mmaps] {len(missing)} maps yield no navmesh tiles with a "
                  f"failure-clean MoveMapGen stdout (the measured legitimate "
                  f"empty maps): {', '.join(missing)}",
                  flush=True)
        else:
            problems.append(
                f"{len(missing)} maps have no mmtiles (MoveMapGen per-map failures?): "
                f"{', '.join(missing[:20])}{'…' if len(missing) > 20 else ''}")
    # R2-r2-2 (W2 code-red round 2): every TILE-BEARING map also requires its
    # %03u.mmap navmesh header — the runtime loads tiles through it (the
    # desktop twin's requireCount(mmaps, ".mmap", ...) has always enforced
    # this side; the official lane did not). A header write lost to a
    # stderr-printed failure must refuse here, never publish an unloadable
    # map.
    missing_headers = sorted(
        bucket for bucket in tile_maps
        if not (workspace / "mmaps" / f"{bucket}.mmap").is_file())
    if missing_headers:
        problems.append(
            f"{len(missing_headers)} tiled maps have no .mmap navmesh header "
            f"(the runtime cannot load their tiles): "
            f"{', '.join(missing_headers[:20])}")
    return problems


def wipe(workspace: Path, kind: str) -> None:
    target = workspace / kind
    if target.exists():
        shutil.rmtree(target)
        print(f"[resume] wiping incomplete {target}", flush=True)


def registered_exe_identity(flavor: str) -> tuple[int | None, str | None]:
    """The flavor's S5.1-registered exe (size, sha256) from
    schemas/flavor.json — the module-level indirection tests override to
    match fixture bytes (the gate's hashing path still runs)."""
    manifest = json.loads((ROOT / "schemas" / "flavor.json").read_text(encoding="utf-8"))
    block = next(f for f in manifest["flavors"] if f["id"] == flavor)
    return (
        (block.get("client_binary") or {}).get("known_exe_size_bytes"),
        (block.get("wine") or {}).get("known_exe_sha256"),
    )


def assert_client_shape(client: Path, flavor: str) -> None:
    """Refuse a client root that is not a genuine <flavor> client BEFORE any
    extractor runs (a wrong-flavor client would extract garbage or fail
    deep inside libmpq). Per-flavor contract: CLIENT_SHAPE carries the
    root exe plus the Data/ entries that identify the flavor; LOCALE_ENTRY
    matches any known locale directory; the forbidden markers keep the
    flavors mutually exclusive (wotlk's archive set otherwise satisfies
    tbc's required entries)."""
    shape = CLIENT_SHAPE[flavor]
    problems: list[str] = []
    if not (client / shape["exe"]).is_file():
        problems.append(f"{shape['exe']} missing")
    for entry in shape["data_entries"]:
        if entry == LOCALE_ENTRY:
            locales = [d for d in CLIENT_LOCALES if (client / "Data" / d).is_dir()]
            if not locales:
                problems.append(
                    "no locale folder under Data/ (expected one of: "
                    + ", ".join(CLIENT_LOCALES) + ")")
        elif not (client / "Data" / entry).exists():
            problems.append(f"Data/{entry} missing")
    for entry in shape["forbidden"]:
        if (client / "Data" / entry).exists():
            problems.append(
                f"Data/{entry} present (belongs to a LATER expansion's client)")
    if problems:
        raise StepFailure(
            f"{client} does not look like a {flavor} client root: " + "; ".join(problems))
    # the shape check is NAME-presence only — a doctored
    # set (wrong-era archives renamed into this flavor's layout) sails
    # through and publish stamps an identity never verified against the
    # bytes. The exe IS the identity: sha256 + size against the flavor's
    # S5.1-registered pins in schemas/flavor.json (the same pins the
    # Kotlin import lane's VAL-03 gate enforces).
    exe = client / shape["exe"]
    expected_size, expected_sha = registered_exe_identity(flavor)
    # R2-6-2 (W2 code-red round 6): the identity gate used to SKIP both legs
    # for a flavor registered without pins — the fail-closed posture was
    # keyed to data no pin enforced. A lane without registered identity pins
    # cannot verify anything and refuses before any extractor runs; the new
    # registry-coverage pin (tests/test_win_prepare_data_flavors.py) holds
    # every FLAVOR_IDENTITIES flavor to both schemas/flavor.json pins so the
    # data cannot silently regress either.
    if expected_size is None or expected_sha is None:
        raise StepFailure(
            f"the {flavor} lane has no registered client identity pins "
            "(schemas/flavor.json client_binary.known_exe_size_bytes / "
            "wine.known_exe_sha256) — the identity gate cannot run, so the "
            "client set cannot be verified (VAL-IDENTITY-PINS-MISSING)")
    actual_size = exe.stat().st_size
    if actual_size != expected_size:
        raise StepFailure(
            f"{client} exe identity mismatch: {shape['exe']} is "
            f"{actual_size} bytes, the registered {flavor} client is "
            f"{expected_size} — the client set does not match the "
            f"{flavor} lane's registered identity")
    import hashlib
    digest = hashlib.sha256(exe.read_bytes()).hexdigest()
    if digest != expected_sha:
        raise StepFailure(
            f"{client} exe identity mismatch: {shape['exe']} sha256 "
            f"{digest[:12]} != the registered {flavor} client "
            f"{expected_sha[:12]} — the client set does not match the "
            f"{flavor} lane's registered identity (a doctored or "
            "wrong-flavor set renamed into this layout)")
    return digest


def extract(client: Path) -> None:
    for name in ("ad.exe", "vmap_extractor.exe", "vmap_assembler.exe", "MoveMapGen.exe"):
        if not (EXTRACTORS / name).is_file():
            raise StepFailure(f"extractor missing: {EXTRACTORS / name} "
                              "(run tools/build_win_realm_runtime.py "
                              f"--flavor {FLAVOR} --extractors)")
    verified_sha = assert_client_shape(client, FLAVOR)
    # R2-r2-1 (W2 code-red round 2): the workspace records the lane identity
    # its data was extracted under — publish()/--publish-only refuse any
    # workspace whose marker is absent or names another lane, so a
    # wrong-flavor-shaped workspace can never publish under this lane's
    # manifest identity.
    marker = {"schema": 1, "flavor": FLAVOR, "exeSha256": verified_sha}
    (WORKSPACE / "lane-identity.json").write_text(
        json.dumps(marker, indent=1) + "\n", encoding="utf-8")
    assert_extractor_dirs_absent(client)

    # ---- ad: dbc + maps (never Cameras; -e 3) ----
    if validate_ad(WORKSPACE):
        wipe(WORKSPACE, "dbc")
        wipe(WORKSPACE, "maps")
        before = client_entries(client)
        try:
            run("ad", client, [str(EXTRACTORS / "ad.exe"), "-e", AD_EXTRACT_MASK])
            move_tree_contents(client / "dbc", WORKSPACE / "dbc", "ad")
            move_tree_contents(client / "maps", WORKSPACE / "maps", "ad")
        except StepFailure:
            clean_client_side_outputs(client, before)
            raise
        leftover = client_entries(client) - before
        if leftover:
            clean_client_side_outputs(client, before)
            raise StepFailure(f"ad created unexpected client entries: {sorted(leftover)}")
        problems = validate_ad(WORKSPACE)
        if problems:
            raise StepFailure("ad extraction incomplete: " + "; ".join(problems))
    else:
        print("[ad] outputs already staged and complete; skipping")

    # ---- vmaps: extractor -> buildings, assembler -> vmaps ----
    if validate_vmaps(WORKSPACE):
        wipe(WORKSPACE, "vmaps")
        if not (WORKSPACE / "buildings").is_dir() or not any((WORKSPACE / "buildings").glob("*")):
            before = client_entries(client)
            try:
                run("vmap_extractor", client, [str(EXTRACTORS / "vmap_extractor.exe")])
                move_tree_contents(client / "buildings", WORKSPACE / "buildings", "vmap_extractor")
            except StepFailure:
                clean_client_side_outputs(client, before)
                raise
            leftover = client_entries(client) - before
            if leftover:
                clean_client_side_outputs(client, before)
                raise StepFailure(
                    f"vmap_extractor created unexpected client entries: {sorted(leftover)}")
        # The assembler neither creates its destination directory nor
        # reports failure through its exit code — stage the dir and
        # verify the vmtrees ourselves.
        (WORKSPACE / "vmaps").mkdir(parents=True, exist_ok=True)
        run("vmap_assembler", WORKSPACE, [str(EXTRACTORS / "vmap_assembler.exe"), "buildings", "vmaps"])
        problems = validate_vmaps(WORKSPACE)
        if problems:
            raise StepFailure("vmap assembly incomplete: " + "; ".join(problems))
    else:
        print("[vmaps] already assembled and complete; skipping")

    # ---- mmaps: MoveMapGen (exits 0 even on per-map failures) ----
    if validate_mmaps(WORKSPACE):
        wipe(WORKSPACE, "mmaps")
        for support in ("offmesh.txt", "config.json"):
            source = EXTRACTORS / support
            if source.is_file():
                shutil.copy2(source, WORKSPACE / support)
        # MoveMapGen, like the assembler, requires its output dir.
        (WORKSPACE / "mmaps").mkdir(parents=True, exist_ok=True)
        threads = os.cpu_count() or 4
        stdout = run("mmaps", WORKSPACE,
                     [str(EXTRACTORS / "MoveMapGen.exe"), "--silent", "--threads", str(threads)])
        failures = sorted(set(match.group(0) for match in MOVEMAP_FAILURE.finditer(stdout)))
        if failures:
            raise StepFailure(
                f"MoveMapGen reported per-map failures despite rc=0: {failures[:20]}")
        problems = validate_mmaps(WORKSPACE, movemap_stdout_clean=not failures)
        if problems:
            raise StepFailure("mmap generation incomplete: " + "; ".join(problems))
    else:
        print("[mmaps] already generated and complete; skipping")


# ---------------- publication ----------------

def validate_counts(counts: dict) -> None:
    problems = [f"{key}={counts[key]} < {minimum}"
                for key, minimum in MIN_COUNTS.items() if counts[key] < minimum]
    if problems:
        raise StepFailure("prepared data below publication minimums: " + "; ".join(problems))


def write_pointer(pointer: Path, text: str) -> None:
    """Atomic pointer flip: a crash mid-write must never destroy the last
    good pointer. os.replace over the tmp is atomic on NTFS."""
    pointer_tmp = pointer.with_name(pointer.name + ".tmp")
    pointer_tmp.write_text(text, encoding="utf-8")
    os.replace(pointer_tmp, pointer)


def verify_publication(flavor: str, generation: str, manifest_sha: str) -> None:
    """The pre-flip self-check (DESK-128): the staged manifest is read back
    from disk and must carry the exact published contract — flavor alias,
    manifest sha — BEFORE any pointer moves, so a failed self-check can
    never poison a pointer that previously named a good generation."""
    manifest_path = DATADIR / "generations" / generation / "data-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("flavor") != flavor:
        raise StepFailure(f"manifest flavor alias is {manifest.get('flavor')!r}, expected {flavor!r}")
    if manifest["clientBuild"] != FLAVOR_IDENTITIES[flavor]["clientBuild"] or \
            manifest["cmangosFamily"] != FLAVOR_IDENTITIES[flavor]["cmangosFamily"]:
        raise StepFailure(f"manifest identity does not match the {flavor} flavor identity")
    if sha256_of(manifest_path) != manifest_sha:
        raise StepFailure("staged manifest sha256 does not match the digest to publish")


def verify_pointers(flavor: str, generation: str, manifest_sha: str) -> None:
    """Post-flip round-trip check: both written pointers must read back
    exactly the published contract (a disk that cannot reproduce an
    os.replace must fail the publication, never become the boot source)."""
    expected = {"schema": 1, "mode": "NORMAL", "generation": generation,
                "manifestSha256": manifest_sha}
    canonical = DATADIR / f"active-{flavor}.json"
    legacy = DATADIR / "active.json"
    for pointer in (canonical, legacy):
        observed = json.loads(pointer.read_text(encoding="utf-8"))
        if observed != expected:
            raise StepFailure(f"pointer self-check failed for {pointer.name}: "
                              f"{json.dumps(observed)} != {json.dumps(expected)}")


def publish(flavor: str) -> None:
    identity = FLAVOR_IDENTITIES[flavor]
    generation = str(uuid.uuid4())
    gen_dir = DATADIR / "generations" / generation

    files = []
    counts: dict = {"dbc": 0, "maps": 0, "vmapTrees": 0, "mmapMaps": set(), "mmapTiles": 0}
    for kind in ("dbc", "maps", "vmaps", "mmaps"):
        source = WORKSPACE / kind
        if not source.is_dir():
            raise StepFailure(f"missing prepared data kind: {source}")

    # R2-r2-1 (W2 code-red round 2): the workspace's lane-identity marker —
    # written by extract() after the client shape + S5.1 exe verification —
    # gates EVERY publication. Absence (a workspace this tool's extract never
    # produced, e.g. hand-staged or produced by an older lane) and flavor
    # mismatch both refuse: a wrong-flavor-shaped workspace can never publish
    # under this lane's manifest identity. The recorded exe digest must equal
    # the flavor's registered pin — a digest-less marker attests nothing and
    # refuses too (/-w2r3-1: fail-closed on the sha leg).
    marker_path = WORKSPACE / "lane-identity.json"
    if not marker_path.is_file():
        raise StepFailure(
            "workspace lane identity missing: no lane-identity.json in "
            f"{WORKSPACE} — the workspace was not produced by this tool's "
            "verified extract; re-run the extract (VAL-WORKSPACE-IDENTITY)")
    try:
        marker = json.loads(marker_path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise StepFailure(f"workspace lane identity unreadable: {error} "
                          "(VAL-WORKSPACE-IDENTITY)") from error
    # R2-r4-1 (W2 code-red round 4): valid-but-non-object JSON (a bare string,
    # array, or number) parsed fine and then crashed marker.get() with a raw
    # AttributeError below — an unactionable traceback instead of the gate's
    # VAL refusal. Same fail-closed posture, actionable copy.
    if not isinstance(marker, dict):
        raise StepFailure(
            "workspace lane identity unreadable: lane-identity.json must be a "
            f"JSON object, got {type(marker).__name__} — rewrite it or re-run "
            "the extract (VAL-WORKSPACE-IDENTITY)")
    if marker.get("schema") != 1 or marker.get("flavor") != flavor:
        raise StepFailure(
            "workspace lane identity mismatch: lane-identity.json records "
            f"{marker.get('flavor')!r} but this lane publishes {flavor!r} — "
            "the workspace belongs to another flavor's lane "
            "(VAL-WORKSPACE-IDENTITY)")
    marker_sha = marker.get("exeSha256")
    registered_sha = registered_exe_identity(flavor)[1]
    # w2r3-1 +: FAIL CLOSED on
    # the sha leg BY CODE, not by data — all three legs refuse:
    #   1. a lane with no registered exe pin cannot verify anything;
    #   2. a marker carrying no digest (null/absent/falsy) attests nothing
    #      about the client it was extracted from;
    #   3. a digest that differs from the registered pin is an unverified
    #      client set.
    # The old `registered_sha and marker_sha != registered_sha` form waved
    # BOTH (1) and (2) through; extract() always records the verified digest
    # (assert_client_shape refuses unpinned lanes outright), so none of these
    # is ever a legitimate product.
    if not registered_sha:
        raise StepFailure(
            f"workspace lane identity unverifiable: the {flavor} lane has no "
            "registered exe identity pin, so the workspace's recorded digest "
            "cannot be checked — register the pin in schemas/flavor.json and "
            "re-run the extract (VAL-WORKSPACE-IDENTITY)")
    if not marker_sha:
        raise StepFailure(
            "workspace lane identity mismatch: lane-identity.json records no "
            "exe digest — a marker that attests nothing about its client is "
            "refused; re-run the extract (VAL-WORKSPACE-IDENTITY)")
    if marker_sha != registered_sha:
        raise StepFailure(
            "workspace lane identity mismatch: lane-identity.json records exe "
            f"sha256 {str(marker_sha)[:12]} but the registered {flavor} client "
            f"is {registered_sha[:12]} — the workspace was extracted from an "
            "unverified client set (VAL-WORKSPACE-IDENTITY)")

    # The copy runs into a fresh generation dir and validation gates the
    # pointer flip; a failed publication leaves no generation behind.

    gen_dir.mkdir(parents=True, exist_ok=True)
    try:
        for kind in ("dbc", "maps", "vmaps", "mmaps"):
            source = WORKSPACE / kind
            shutil.copytree(source, gen_dir / kind)
            for path in sorted((gen_dir / kind).rglob("*")):
                if not path.is_file() or path.name == ".gitkeep":
                    continue
                relative = path.relative_to(gen_dir).as_posix()
                files.append({"path": relative, "size": path.stat().st_size,
                              "sha256": sha256_of(path)})
                if kind == "dbc":
                    counts["dbc"] += 1
                elif kind == "maps":
                    counts["maps"] += 1
                elif kind == "vmaps" and path.suffix == ".vmtree":
                    counts["vmapTrees"] += 1
                elif kind == "mmaps" and path.suffix == ".mmtile":
                    # the ACTIVE flavor's mmtile contract (FLAVOR_MMTILE_NAME;
                    # packed digits %03d map + %02d x + %02d y, plus wotlk's
                    # measured _NN alt form).
                    if not MMTILE_NAME.match(path.stem):
                        raise StepFailure(f"unexpected mmtile name: {path.name}")
                    counts["mmapTiles"] += 1
                    counts["mmapMaps"].add(path.name[:3])

        mmap_maps = len(counts["mmapMaps"])
        for key in ("dbc", "maps", "vmapTrees", "mmapTiles"):
            counts[key] = int(counts[key])
        counts["mmapMaps"] = mmap_maps
        validate_counts(counts)

        manifest = {
            "schema": 1,
            "complete": True,
            "mode": "NORMAL",
            "flavor": flavor,
            "clientBuild": identity["clientBuild"],
            "cmangosFamily": identity["cmangosFamily"],
            "counts": counts,
            "files": files,
        }
        manifest_path = gen_dir / "data-manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=1), encoding="utf-8")
        manifest_sha = sha256_of(manifest_path)

        # Per-flavor pointer namespace (DESK-128): the canonical
        # active-<flavorId>.json plus the un-namespaced active.json mirror
        # (kept for one transition release so existing consumers and shared
        # PreparedDataStore builds without the flavor namespace keep
        # resolving). Both name the same generation; two flavors' lanes
        # coexist because each flavor owns its own canonical pointer.
        active = {"schema": 1, "mode": "NORMAL", "generation": generation,
                  "manifestSha256": manifest_sha}
        active_text = json.dumps(active)
        # Self-check BEFORE the flips (see verify_publication): a failure
        # here leaves both pointers naming the previous good generation.
        verify_publication(flavor, generation, manifest_sha)
        DATADIR.mkdir(parents=True, exist_ok=True)
        write_pointer(DATADIR / f"active-{flavor}.json", active_text)
        write_pointer(DATADIR / "active.json", active_text)
        verify_pointers(flavor, generation, manifest_sha)
    except BaseException:
        # A failed publication leaves no new generation behind. A pointer
        # that names the condemned generation is removed (fail-closed —
        # "not ready" beats a dead pointer); failures before the flips
        # leave both pointers naming the previous good generation.
        shutil.rmtree(gen_dir, ignore_errors=True)
        for pointer in (DATADIR / f"active-{flavor}.json", DATADIR / "active.json"):
            try:
                if json.loads(pointer.read_text(encoding="utf-8")).get("generation") == generation:
                    pointer.unlink()
            except (OSError, ValueError):
                pass
        raise

    prune_stale_generations(generation)
    print(f"published generation {generation} (flavor {flavor}): "
          f"{len(files)} files, counts={counts}")
    print(f"canonical pointer -> {DATADIR / ('active-' + flavor + '.json')}")
    print(f"legacy mirror -> {DATADIR / 'active.json'}")


def prune_stale_generations(active: str, keep: int = 2) -> None:
    """Keep every flavor's pointer-named generation plus the newest `keep`
    others; a failed run otherwise leaks a full generation per attempt.
    Every active-*.json pointer protects its generation: pruning another
    flavor's boot source would silently destroy its lane (the exact
    one-shared-pointer failure DESK-128 removes). R2-r4-2 (W2 code-red
    round 4): an UNREADABLE pointer used to be skipped — leaving its
    generation unprotected for the rmtree below while the corrupt pointer
    survived dangling. The prune now REFUSES (before deleting anything) when
    any pointer is unreadable or malformed: pruning is hygiene, a lane's
    generation is its only copy, and the operator can repair the pointer and
    re-run the publish."""
    generations = DATADIR / "generations"
    if not generations.is_dir():
        return
    protected = {active}
    for pointer in sorted(generations.parent.glob("active*.json")):
        try:
            pointer_data = json.loads(pointer.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise StepFailure(
                "cannot prune: pointer "
                f"{pointer.name} is unreadable ({error}) — pruning with an "
                "unreadable per-flavor pointer could delete that lane's only "
                "generation (data loss). Read/repair or remove the pointer "
                "manually, then re-run the publish; the newly published "
                "generation and pointers are intact "
                "(VAL-POINTER-UNREADABLE)") from error
        if not isinstance(pointer_data, dict) or \
                not isinstance(pointer_data.get("generation"), str):
            raise StepFailure(
                "cannot prune: pointer "
                f"{pointer.name} is malformed (no string 'generation' field) — "
                "pruning with a malformed per-flavor pointer could delete that "
                "lane's only generation (data loss). Read/repair or remove the "
                "pointer manually, then re-run the publish; the newly "
                "published generation and pointers are intact "
                "(VAL-POINTER-UNREADABLE)")
        protected.add(pointer_data["generation"])
    def modified(path: Path) -> float:
        return path.stat().st_mtime
    stale = sorted((path for path in generations.iterdir() if path.is_dir()
                    and path.name not in protected),
                   key=modified, reverse=True)[keep:]
    for path in stale:
        shutil.rmtree(path, ignore_errors=True)
        print(f"[prune] removed stale generation {path.name}", flush=True)


def main() -> int:
    global DATADIR
    if os.name != "nt":
        print("windows-only lane", file=sys.stderr)
        return 2
    # resolve through the shared override-aware
    # resolver - the app reads the SAME root DesktopStorageRoots resolves
    # (POCKET_REALM_DATA_ROOT wins; product_data_root raises when neither
    # is set, which main() reports as the honest PREPARE FAILED).
    try:
        DATADIR = common.product_data_root() / "content" / "o11-server"
    except RuntimeError as failure:
        print(f"PREPARE FAILED: {failure}", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", type=Path, default=DEFAULT_CLIENT)
    parser.add_argument("--flavor", default="vanilla",
                        help="the flavor lane to publish (canonical pointer "
                             "active-<flavor>.json + manifest flavor alias); "
                             "default vanilla")
    parser.add_argument("--publish-only", action="store_true",
                        help="assemble the PreparedDataStore layout from an "
                             "already-complete workspace, skipping extraction")
    args = parser.parse_args()

    flavor = args.flavor
    if not FLAVOR_ID.match(flavor):
        print(f"PREPARE FAILED: --flavor must match {FLAVOR_ID.pattern}, got {flavor!r}",
              file=sys.stderr)
        return 2
    if flavor not in FLAVOR_IDENTITIES:
        print(f"PREPARE FAILED: flavor {flavor!r} has no qualified manifest identity "
              f"(qualified: {', '.join(sorted(FLAVOR_IDENTITIES))})", file=sys.stderr)
        return 2
    # Bind the flavor's lane paths (extractor home, workspace, floors)
    # BEFORE any extraction: the workspace is flavor-scoped so one flavor's
    # complete outputs can never satisfy another flavor's resume skip.
    select_flavor(flavor)

    WORKSPACE.mkdir(parents=True, exist_ok=True)
    try:
        if not args.publish_only:
            extract(args.client)
        else:
            # R2-1 (W2 code-red round 1): --publish-only is not a trust
            # shortcut. The workspace must pass the SAME completeness gates
            # the extraction path enforces before anything publishes — a
            # MoveMapGen-truncated workspace used to flip both active
            # pointers with a complete:true manifest. Strict validation
            # context: no run stdout exists to trust, so the wotlk
            # tile-less waiver is unavailable here (exactly like the
            # resume-skip call).
            problems = (validate_ad(WORKSPACE) + validate_vmaps(WORKSPACE)
                        + validate_mmaps(WORKSPACE))
            if problems:
                raise StepFailure("workspace fails completeness validation: "
                                  + "; ".join(problems))
        publish(flavor)
    except StepFailure as failure:
        print(f"PREPARE FAILED: {failure}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
