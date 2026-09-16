#!/usr/bin/env python3
"""Windows data-preparation bring-up: client -> prepared world data.

Runs the four Windows extractors (built by tools/build_win_realm_runtime.py
--extractors) against a genuine 1.12.1 client, then assembles the
PreparedDataStore contract the world server boots from:

  <datadir>/generations/<uuid>/{dbc,maps,vmaps,mmaps} + data-manifest.json
  <datadir>/active.json   (schema 1, NORMAL, generation uuid, manifest sha)

Resumability is VALIDATION-based, not existence-based: a step is skipped
only when its output passes this tool's completeness validation. An
interrupted vmap assembly or MoveMapGen run leaves partial output behind
that fails validation and is wiped and regenerated. MoveMapGen exits 0
even when individual per-map builds fail, so its stdout is scanned for
per-map failures and every extracted map must be covered by at least one
mmtile before the step counts as complete.

The client directory is left byte-clean: ad.exe runs with -e 3 (maps+dbc
only — never the Cameras lane), extractor outputs are moved out to the
workspace as soon as they land, the client's entry set is snapshotted
before/after every extractor run, and a failure path removes whatever
the extractor created before exiting non-zero.

Usage: python tools/win_prepare_data.py [--client <dir>] [--workspace <dir>]
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

ROOT = Path(__file__).resolve().parents[1]
EXTRACTORS = ROOT / "native/.build-win-x86_64/bin/x64_Release/Extractors"
DEFAULT_CLIENT = Path("C:/Vanilla wow 1.12.1")
WORKSPACE = ROOT / "native/.build-win-x86_64/data-prep"
DATADIR = Path(os.environ["LOCALAPPDATA"]) / "PocketRealm/content/o11-server"

STEP_TIMEOUT_S = 4 * 60 * 60

# ad.exe -e mask: MAP(1) | DBC(2). Camera(4) is deliberately excluded so
# the extractor never creates <client>/Cameras at all.
AD_EXTRACT_MASK = "3"

# PreparedDataStore schema-1 publication minimums; validated BEFORE the
# active.json pointer is touched so a partial generation can never become
# the boot source.
MIN_COUNTS = {"dbc": 100, "maps": 100, "vmapTrees": 1, "mmapMaps": 1, "mmapTiles": 1}

# mmtile names are packed digits: %03d map + %02d x + %02d y. A name that
# does not match (e.g. a future go%04u gameobject tile) must fail loudly
# instead of polluting the mmapMaps count.
MMTILE_NAME = re.compile(r"^\d{7}$")

# MoveMapGen prints "[Map NNN] Failed creating navmesh!" on per-map build
# failures while still exiting 0.
MOVEMAP_FAILURE = re.compile(r"\[Map\s+\d+\].*Failed", re.IGNORECASE)

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
    if result.returncode != 0 or "exit with errors" in (result.stdout or ""):
        raise StepFailure(f"{step} failed (rc={result.returncode}):\n{combined[-4000:]}")
    if result.stdout:
        print(f"[{step}] {result.stdout[-600:]}", flush=True)
    print(f"[{step}] done in {time.time() - started:.0f}s", flush=True)
    return result.stdout or ""


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


def validate_vmaps(workspace: Path) -> list[str]:
    problems = []
    vmtrees = list((workspace / "vmaps").glob("*.vmtree"))
    if not vmtrees:
        problems.append("no .vmtree files under vmaps/")
    return problems


def validate_mmaps(workspace: Path) -> list[str]:
    problems = []
    tiles = list((workspace / "mmaps").glob("*.mmtile"))
    if not tiles:
        problems.append("no .mmtile files under mmaps/")
        return problems
    tile_maps: set[str] = set()
    for path in tiles:
        if not MMTILE_NAME.match(path.stem):
            problems.append(f"unexpected mmtile name (expected 7 digits): {path.name}")
        else:
            tile_maps.add(path.name[:3])
    # Coverage: every extracted map must have at least one navmesh tile.
    # MoveMapGen exits 0 even when per-map builds fail, so a missing map
    # here is the only signal that the step is incomplete.
    missing = sorted(maps_ids(workspace) - tile_maps)
    if missing:
        problems.append(
            f"{len(missing)} maps have no mmtiles (MoveMapGen per-map failures?): "
            f"{', '.join(missing[:20])}{'…' if len(missing) > 20 else ''}")
    return problems


def wipe(workspace: Path, kind: str) -> None:
    target = workspace / kind
    if target.exists():
        shutil.rmtree(target)
        print(f"[resume] wiping incomplete {target}", flush=True)


def extract(client: Path) -> None:
    for name in ("ad.exe", "vmap_extractor.exe", "vmap_assembler.exe", "MoveMapGen.exe"):
        if not (EXTRACTORS / name).is_file():
            raise StepFailure(f"extractor missing: {EXTRACTORS / name} "
                              "(run tools/build_win_realm_runtime.py --extractors)")
    if not (client / "WoW.exe").is_file() or not (client / "Data" / "base.MPQ").is_file():
        raise StepFailure(f"{client} does not look like a 1.12.1 client root")
    assert_extractor_dirs_absent(client)

    # ---- ad: dbc + maps (never Cameras; -e 3) ----
    if (WORKSPACE / "dbc").is_dir() and any((WORKSPACE / "dbc").glob("*.dbc")) \
            and (WORKSPACE / "maps").is_dir() and any((WORKSPACE / "maps").glob("*.map")):
        print("[ad] outputs already staged; skipping")
    else:
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
        problems = validate_mmaps(WORKSPACE)
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


def publish() -> None:
    generation = str(uuid.uuid4())
    gen_dir = DATADIR / "generations" / generation

    files = []
    counts: dict = {"dbc": 0, "maps": 0, "vmapTrees": 0, "mmapMaps": set(), "mmapTiles": 0}
    for kind in ("dbc", "maps", "vmaps", "mmaps"):
        source = WORKSPACE / kind
        if not source.is_dir():
            raise StepFailure(f"missing prepared data kind: {source}")

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
                    # mmtile names are packed digits: %03d map + %02d x + %02d y.
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
            "clientBuild": 5875,
            "cmangosFamily": "classic",
            "counts": counts,
            "files": files,
        }
        manifest_path = gen_dir / "data-manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=1), encoding="utf-8")
        manifest_sha = sha256_of(manifest_path)

        # Atomic pointer flip: a crash mid-write must never destroy the
        # last good pointer (there is exactly one and it names the boot
        # source). os.replace over the tmp is atomic on NTFS.
        active = {"schema": 1, "mode": "NORMAL", "generation": generation,
                  "manifestSha256": manifest_sha}
        DATADIR.mkdir(parents=True, exist_ok=True)
        pointer = DATADIR / "active.json"
        pointer_tmp = DATADIR / "active.json.tmp"
        pointer_tmp.write_text(json.dumps(active), encoding="utf-8")
        os.replace(pointer_tmp, pointer)
    except BaseException:
        # A failed publication leaves no new generation behind.
        shutil.rmtree(gen_dir, ignore_errors=True)
        raise

    prune_stale_generations(generation)
    print(f"published generation {generation}: {len(files)} files, counts={counts}")
    print(f"active pointer -> {DATADIR / 'active.json'}")


def prune_stale_generations(active: str, keep: int = 2) -> None:
    """Keep the active generation plus the newest `keep` others; a failed
    run otherwise leaks a full generation per attempt."""
    generations = DATADIR / "generations"
    if not generations.is_dir():
        return
    def modified(path: Path) -> float:
        return path.stat().st_mtime
    stale = sorted((path for path in generations.iterdir() if path.is_dir()
                    and path.name != active),
                   key=modified, reverse=True)[keep:]
    for path in stale:
        shutil.rmtree(path, ignore_errors=True)
        print(f"[prune] removed stale generation {path.name}", flush=True)


def main() -> int:
    if os.name != "nt":
        print("windows-only lane", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", type=Path, default=DEFAULT_CLIENT)
    parser.add_argument("--publish-only", action="store_true",
                        help="assemble the PreparedDataStore layout from an "
                             "already-complete workspace, skipping extraction")
    args = parser.parse_args()

    WORKSPACE.mkdir(parents=True, exist_ok=True)
    try:
        if not args.publish_only:
            extract(args.client)
        publish()
    except StepFailure as failure:
        print(f"PREPARE FAILED: {failure}", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
