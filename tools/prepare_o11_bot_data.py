#!/usr/bin/env python3
"""Prepare a REAL o11 normal-play server-data generation (dbc + maps +
vmaps + mmaps) for the bot benchmark legs, entirely host-side.

Inputs (all already on this host):
  - the user's own WoW 1.12.1.5875 client (identity-verified against
    native/.build-o09-server-data/BUILD_PROVENANCE.json before anything
    runs; the client is mounted into the build container READ-ONLY)
  - the pinned, patched CMaNGOS source's extractor tools, built for
    Linux x86-64 by build/host-extractors-linux/build.sh (vmap_extractor,
    vmap_assembler, MoveMapGen)
  - the baseline pack's dbc/ + maps/ (already client-derived)

Pipeline (mirrors DataPreparationStore.prepare exactly):
  vmap_extractor -s -d <client>/Data -o stage     -> stage/Buildings
  vmap_assembler stage/Buildings stage/vmaps      -> *.vmtree/*.vmtile
  MoveMapGen <mapId> --silent --threads N --workdir stage  -> mmaps/
  (mmaps for maps 0 and 1 only - every shipped bot profile pins
   AiPlayerbot.RandomBotMaps = 0,1; other maps load lazily at runtime)

Then publishes the PreparedDataStore layout the app verifies:
  <out>/active.json {schema, generation, manifestSha256, mode:NORMAL}
  <out>/generations/<uuid>/{dbc,maps,vmaps,mmaps} + data-manifest.json
with per-file size+sha256 records (requireActive() re-verifies every
byte on-device before the world will boot).

Usage: python3 tools/prepare_o11_bot_data.py [--maps 0,1] [--threads 4]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# Env-first (the ANDROID_SDK_ROOT precedent): the default names this
# host's client install; another host overrides it without editing.
CLIENT = Path(os.environ.get("POCKET_WOW_CLIENT") or r"C:\Vanilla wow 1.12.1")
BASELINE = ROOT / "native" / ".build-o09-server-data"
TOOLS = ROOT / "build" / "host-extractors-linux" / "out"
WORK = ROOT / "build" / "o11-bot-data"
IMAGE = "ghcr.io/termux/package-builder-cgct:latest"


def run(command: list[object], **kwargs) -> str:
    printable = " ".join(str(c) for c in command)
    print("+", printable, flush=True)
    completed = subprocess.run([str(c) for c in command], text=True,
                               capture_output=True, **kwargs)
    if completed.stdout:
        print(completed.stdout[-2000:], flush=True)
    if completed.returncode != 0:
        print(completed.stderr[-4000:], flush=True)
        raise SystemExit(f"command failed ({completed.returncode}): {printable}")
    return completed.stdout


def verify_client_identity() -> None:
    provenance = json.loads(
        (BASELINE / "BUILD_PROVENANCE.json").read_text(encoding="utf-8"))
    identity = provenance["client_identity"]
    exe = CLIENT / "WoW.exe"
    if not exe.is_file():
        raise SystemExit(f"client missing: {exe}")
    digest = hashlib.sha256(exe.read_bytes()).hexdigest()
    if digest != identity["sha256"]:
        raise SystemExit(
            f"client identity mismatch: {digest} != pinned {identity['sha256']}")
    print(f"client identity verified: {identity['version']} "
          f"build {identity['build']}")


EXTRACT_SH = r"""
set -euxo pipefail
export PATH=/work/out:$PATH
# Container-local: the Docker Desktop mount is 9p - thousands of small
# writes through it are an order of magnitude slower AND flaky under
# rapid create bursts (cp hit spurious "File exists" races). Everything
# heavy stays on container fs; final outputs stream out through tar (a
# single sequential write per tree). The extractor demands an empty
# output dir, so extraction always starts clean.
stage=/stage
wd=/workdir
mkdir -p "$stage" "$wd"
cp -a /work/stage/dbc /work/stage/maps "$wd"/ 2>/dev/null || true
if [ -d /work/stage/vmaps ]; then
  # cached from a prior run (the copy-out below) - skip extraction
  cp -a /work/stage/vmaps "$wd"/
else
  vmap_extractor -d /client/Data -o "$stage" 2>&1 | tail -30
  # upstream's fopen("wb") does not create the destination directory
  # (DataPreparationStore mkdirs it before the same call)
  mkdir -p "$wd/vmaps"
  vmap_assembler "$stage/Buildings" "$wd/vmaps" 2>&1 | tail -60
  tar -C "$wd" -cf - vmaps | tar -C /work/stage -xf -
fi
mkdir -p "$wd/mmaps"
for map in ${MMAP_MAPS//,/ }; do
  MoveMapGen "$map" --silent --threads "$THREADS" --workdir "$wd" 2>&1 | tail -20
done
echo "vmtrees: $(find "$wd/vmaps" -name '*.vmtree' | wc -l)"
echo "mmap headers: $(find "$wd/mmaps" -name '*.mmap' | wc -l)"
echo "mmtiles: $(find "$wd/mmaps" -name '*.mmtile' | wc -l)"
for tree in mmaps; do
  rm -rf "/work/stage/$tree"
  tar -C "$wd" -cf - "$tree" | tar -C /work/stage -xf -
done
"""


def extract(mmap_maps: list[int], threads: int) -> None:
    stage = WORK / "stage"
    if (stage / "vmaps").is_dir() and (stage / "mmaps").is_dir():
        print("stage already carries vmaps/ and mmaps/ - skipping extraction")
        return
    for name in ("vmap_extractor", "vmap_assembler", "MoveMapGen"):
        if not (TOOLS / name).is_file():
            raise SystemExit(f"missing host tool {TOOLS / name} "
                             "(run build/host-extractors-linux/build.sh first)")
    # the tools read maps/ + dbc/ from the workdir (MoveMapGen layout)
    (stage / "maps").mkdir(parents=True, exist_ok=True)
    (stage / "dbc").mkdir(parents=True, exist_ok=True)
    for source, target in ((BASELINE / "maps", stage / "maps"),
                           (BASELINE / "dbc", stage / "dbc")):
        for item in source.iterdir():
            destination = target / item.name
            if not destination.exists():
                shutil.copy2(item, destination)
    (WORK / "extract.sh").write_text(EXTRACT_SH, encoding="utf-8", newline="\n")
    run(["docker", "run", "--rm", "--user", "root",
         "-v", f"{WORK}:/work", "-v", f"{TOOLS}:/work/out:ro",
         "-v", f"{CLIENT}:/client:ro",
         "-e", f"MMAP_MAPS={','.join(str(m) for m in mmap_maps)}",
         "-e", f"THREADS={threads}",
         IMAGE, "bash", "/work/extract.sh"])


def sha256(file: Path) -> str:
    digest = hashlib.sha256()
    with file.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def publish(mmap_maps: list[int]) -> Path:
    store = WORK / "o11-server"
    stage = WORK / "stage"
    generation = str(uuid.uuid4())
    target = store / "generations" / generation
    if target.exists():
        raise SystemExit("generation collision - rerun")
    counts = {"dbc": 0, "maps": 0, "vmapTrees": 0, "vmapTiles": 0,
              "mmapMaps": 0, "mmapTiles": 0}
    files = []
    for name in ("dbc", "maps", "vmaps", "mmaps"):
        source = stage / name
        if not source.is_dir():
            raise SystemExit(f"stage directory missing: {source}")
        shutil.copytree(source, target / name)
        for file in sorted((target / name).rglob("*")):
            if not file.is_file():
                continue
            relative = file.relative_to(target).as_posix()
            files.append({"path": relative, "size": file.stat().st_size,
                          "sha256": sha256(file)})
            suffix_kind = {
                ".dbc": ("dbc",), ".map": ("maps",),
                ".vmtree": ("vmapTrees",), ".vmtile": ("vmapTiles",),
                ".mmap": ("mmapMaps",), ".mmtile": ("mmapTiles",),
            }.get(file.suffix)
            if suffix_kind:
                for kind in suffix_kind:
                    counts[kind] += 1
    manifest = {
        "schema": 1, "complete": True, "mode": "NORMAL",
        "clientBuild": 5875, "cmangosFamily": "classic",
        "sourceClientGeneration": generation,
        "mmapMapScope": mmap_maps,
        "counts": counts, "files": files,
    }
    manifest_file = target / "data-manifest.json"
    manifest_file.write_text(json.dumps(manifest, indent=2) + "\n",
                             encoding="utf-8", newline="\n")
    manifest_sha = sha256(manifest_file)
    (store / "active.json").write_text(
        json.dumps({"schema": 1, "generation": generation,
                    "manifestSha256": manifest_sha, "mode": "NORMAL"},
                   indent=2) + "\n",
        encoding="utf-8", newline="\n")
    # PreparedDataStore.requireActiveEnvelope invariants (fail here, not
    # on-device 10 minutes later):
    assert counts["dbc"] >= 100 and counts["maps"] >= 100, counts
    assert counts["vmapTrees"] >= 1, counts
    assert counts["mmapMaps"] >= 1 and counts["mmapTiles"] >= 1, counts
    assert generation.replace("-", "").isalnum() and len(generation) == 36
    print(json.dumps({"generation": generation, "manifestSha256": manifest_sha,
                      "counts": counts, "files": len(files)}, indent=2))
    return store


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maps", default="0,1",
                        help="comma-separated mmap map ids (default 0,1 - "
                             "the shipped profiles' RandomBotMaps)")
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    mmap_maps = [int(m) for m in args.maps.split(",")]
    verify_client_identity()
    extract(mmap_maps, args.threads)
    store = publish(mmap_maps)
    print(f"READY: {store}  (stage this into "
          f"files/content/o11-server/ - the benchmark driver does it)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
