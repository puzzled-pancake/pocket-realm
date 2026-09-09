#!/usr/bin/env python3
"""Windows data-preparation bring-up (Phase 4b): client -> prepared world data.

Runs the four Windows extractors (built by tools/build_win_realm_runtime.py
--extractors) against a genuine 1.12.1 client, then assembles the
PreparedDataStore contract the world server boots from:

  <datadir>/generations/<uuid>/{dbc,maps,vmaps,mmaps} + data-manifest.json
  <datadir>/active.json   (schema 1, NORMAL, generation uuid, manifest sha)

Steps are resumable: an extractor step is skipped when its output
directory already holds files, so a MoveMapGen interruption (the long
pole) does not redo ad/vmaps. The client directory is left byte-clean:
extractor outputs are moved out to the workspace as soon as they land.

Usage: python tools/win_prepare_data.py [--client <dir>] [--workspace <dir>]
"""

from __future__ import annotations

import argparse
import hashlib
import os
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


def run(step: str, cwd: Path, cmd: list[str]) -> None:
    print(f"[{step}] {' '.join(str(c) for c in cmd)} (cwd={cwd})", flush=True)
    started = time.time()
    result = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True,
                            timeout=STEP_TIMEOUT_S)
    if result.returncode != 0 or "exit with errors" in (result.stdout or ""):
        tail = (result.stdout or "") + (result.stderr or "")
        raise RuntimeError(f"{step} failed (rc={result.returncode}):\n{tail[-4000:]}")
    if result.stdout:
        print(f"[{step}] {result.stdout[-600:]}", flush=True)
    print(f"[{step}] done in {time.time() - started:.0f}s", flush=True)


def move_tree_contents(source: Path, target: Path, step: str) -> None:
    if not source.is_dir():
        raise RuntimeError(f"{step}: expected output directory missing: {source}")
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


def extract(client: Path) -> None:
    for name in ("ad.exe", "vmap_extractor.exe", "vmap_assembler.exe", "MoveMapGen.exe"):
        if not (EXTRACTORS / name).is_file():
            raise RuntimeError(f"extractor missing: {EXTRACTORS / name} "
                               "(run tools/build_win_realm_runtime.py --extractors)")
    if not (client / "WoW.exe").is_file() or not (client / "Data" / "base.MPQ").is_file():
        raise RuntimeError(f"{client} does not look like a 1.12.1 client root")

    if any((WORKSPACE / "dbc").glob("*")) and any((WORKSPACE / "maps").glob("*")):
        print("[ad] outputs already staged; skipping")
    else:
        run("ad", client, [str(EXTRACTORS / "ad.exe")])
        move_tree_contents(client / "dbc", WORKSPACE / "dbc", "ad")
        move_tree_contents(client / "maps", WORKSPACE / "maps", "ad")

    if any((WORKSPACE / "vmaps").glob("*.vmtree")):
        print("[vmaps] already assembled; skipping")
    else:
        if not any((WORKSPACE / "buildings").glob("*")):
            run("vmap_extractor", client, [str(EXTRACTORS / "vmap_extractor.exe")])
            move_tree_contents(client / "buildings", WORKSPACE / "buildings", "vmap_extractor")
        # The assembler neither creates its destination directory nor
        # reports failure through its exit code — stage the dir and
        # verify the vmtrees ourselves.
        (WORKSPACE / "vmaps").mkdir(parents=True, exist_ok=True)
        run("vmap_assembler", WORKSPACE, [str(EXTRACTORS / "vmap_assembler.exe"), "buildings", "vmaps"])
        vmtrees = list((WORKSPACE / "vmaps").glob("*.vmtree"))
        if not vmtrees:
            raise RuntimeError("vmap_assembler produced no .vmtree files (see its stdout above)")

    if any((WORKSPACE / "mmaps").glob("*.mmtile")):
        print("[mmaps] already generated; skipping")
    else:
        for support in ("offmesh.txt", "config.json"):
            source = EXTRACTORS / support
            if source.is_file():
                shutil.copy2(source, WORKSPACE / support)
        # MoveMapGen, like the assembler, requires its output dir.
        (WORKSPACE / "mmaps").mkdir(parents=True, exist_ok=True)
        threads = os.cpu_count() or 4
        run("mmaps", WORKSPACE, [str(EXTRACTORS / "MoveMapGen.exe"), "--silent", "--threads", str(threads)])
        if not any((WORKSPACE / "mmaps").glob("*.mmtile")):
            raise RuntimeError("MoveMapGen produced no .mmtile files (see its stdout above)")


def publish() -> None:
    generation = str(uuid.uuid4())
    gen_dir = DATADIR / "generations" / generation
    gen_dir.mkdir(parents=True, exist_ok=True)

    files = []
    counts = {"dbc": 0, "maps": 0, "vmapTrees": 0, "mmapMaps": set(), "mmapTiles": 0}
    for kind in ("dbc", "maps", "vmaps", "mmaps"):
        source = WORKSPACE / kind
        if not source.is_dir():
            raise RuntimeError(f"missing prepared data kind: {source}")
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
                counts["mmapTiles"] += 1
                counts["mmapMaps"].add(path.name[:3])

    mmap_maps = len(counts["mmapMaps"])
    for key in ("dbc", "maps", "vmapTrees", "mmapTiles"):
        counts[key] = int(counts[key])
    counts["mmapMaps"] = mmap_maps

    import json
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

    active = {"schema": 1, "mode": "NORMAL", "generation": generation,
              "manifestSha256": manifest_sha}
    DATADIR.mkdir(parents=True, exist_ok=True)
    (DATADIR / "active.json").write_text(json.dumps(active), encoding="utf-8")
    print(f"published generation {generation}: {len(files)} files, counts={counts}")
    print(f"active pointer -> {DATADIR / 'active.json'}")


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
    if not args.publish_only:
        extract(args.client)
    publish()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
