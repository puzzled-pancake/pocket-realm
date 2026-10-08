#!/usr/bin/env python3
"""Stage the pinned llama.cpp source tree for the Windows llama-server build.

The llama.cpp source is a real pinned component — sources[] entry
`llama-cpp-6d05498` (the SAME revision the Android lane vendors,
native/llm/README.md) — fetched and sha-verified by tools/fetch_provider.py,
staged here under native/.deps/src/llama/, and compiled for Windows by
tools/build_win_llama_server.py (never re-vendored, never patched, never an
untracked build-host artifact). The staging directory is the CMake source
the desktop llama-server.exe is built from.

Usage:
    python tools/stage_llama_cpp.py            # stage (fetch if needed)
    python tools/stage_llama_cpp.py --list     # show the pin

Deterministic: same tarball hash, same extraction, no mutations — the
CMake build downstream is reproducible from the pinned bytes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
SOURCES = ROOT / "schemas" / "sources.json"
CACHE = NATIVE / ".providers"
DEST = NATIVE / ".deps" / "src" / "llama"
ENTRY_ID = "llama-cpp-6d05498"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def entry() -> dict:
    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    for source in data["sources"]:
        if source["id"] == ENTRY_ID:
            return source
    raise SystemExit(f"sources.json is missing the {ENTRY_ID} pin")


def cached_artifact(entry_data: dict) -> Path:
    artifact = entry_data["artifact"]
    cached = CACHE / ENTRY_ID / artifact
    if cached.is_file() and sha256_file(cached) == entry_data["artifact_sha256"]:
        return cached
    raise SystemExit(
        f"the pinned {ENTRY_ID} artifact is not cached (or hash-mismatched); "
        f"run: python tools/fetch_provider.py --no-extract {ENTRY_ID}"
    )


def extract(artifact: Path, entry_data: dict) -> Path:
    """Extract the tarball into DEST/<commit> with the top-level dir stripped.

    Idempotent: a staged tree whose marker pin file carries the artifact
    hash is left untouched; any other state is re-extracted from scratch.
    """
    commit = entry_data["commit"]
    target = DEST / commit
    marker = target / ".staged-artifact-sha256"
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == entry_data["artifact_sha256"]:
        print(f"OK   {target.relative_to(ROOT)} already staged from the pinned bytes")
        return target
    if target.exists():
        shutil.rmtree(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    # Extract to a temp dir first: a killed run must never leave a partial
    # tree that the marker file would then bless.
    with tempfile.TemporaryDirectory(dir=str(DEST)) as tmp:
        tmp_root = Path(tmp)
        with tarfile.open(artifact, "r:gz") as tar:
            top_level = {m.name.split("/", 1)[0] for m in tar.getmembers()}
            if len(top_level) != 1:
                raise SystemExit(f"unexpected tarball layout: {sorted(top_level)}")
            tar.extractall(tmp_root)  # noqa: S202 - pinned, hash-verified archive
        extracted = tmp_root / top_level.pop()
        shutil.move(str(extracted), str(target))
    marker.write_text(entry_data["artifact_sha256"], encoding="utf-8")
    print(f"OK   staged {target.relative_to(ROOT)} (from {artifact.name}, sha256-verified)")
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="show the pin and exit")
    args = parser.parse_args()
    entry_data = entry()
    if args.list:
        print(f"{ENTRY_ID}: {entry_data['artifact_url']}")
        print(f"  sha256 {entry_data['artifact_sha256']}")
        print(f"  staged at {DEST / entry_data['commit']}")
        return
    artifact = cached_artifact(entry_data)
    extract(artifact, entry_data)


if __name__ == "__main__":
    sys.exit(main())
