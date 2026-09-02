#!/usr/bin/env python3
"""Stage the pinned SQLite amalgamation and build libsqlite3.a per ABI.

The amalgamation is a real pinned component - sources[] entry
`sqlite-amalgamation-3460100` - fetched and sha-verified by
tools/fetch_provider.py, staged here, and compiled by the single recipe at
native/.deps/src/sqlite/CMakeLists.txt (never re-vendored, never patched,
never an untracked build-host artifact).

Usage:
    python tools/stage_sqlite_amalgamation.py --abi x86_64
    python tools/stage_sqlite_amalgamation.py --abi arm64-v8a
    python tools/stage_sqlite_amalgamation.py --list

Deterministic: same zip, same recipe, same NDK -> byte-identical
libsqlite3.a (verified by rebuild-compare). The script refuses to proceed
if the staged amalgamation does not match the pinned content hashes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
SOURCES = ROOT / "schemas" / "sources.json"
SRC = NATIVE / ".deps" / "src" / "sqlite"
ENTRY_ID = "sqlite-amalgamation-3460100"
API = 26


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sdk_tools(abi: str) -> tuple[Path, Path, Path, Path]:
    """Env-first SDK discovery, identical precedence to the o09 driver."""
    import os

    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not configured:
        properties = ROOT / "android" / "local.properties"
        if not properties.is_file():
            raise SystemExit("Android SDK not found (set ANDROID_SDK_ROOT)")
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                configured = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
    if not configured:
        raise SystemExit("Android SDK not found (set ANDROID_SDK_ROOT)")
    sdk = Path(configured)
    ndks = sorted(path for path in (sdk / "ndk").glob("*") if path.is_dir())
    cmakes = sorted(path for path in (sdk / "cmake").glob("*") if path.is_dir())
    if not ndks or not cmakes:
        raise SystemExit("NDK/CMake missing from the Android SDK")
    ndk = ndks[-1]
    cmake = cmakes[-1] / "bin" / "cmake.exe"
    ninja = cmakes[-1] / "bin" / "ninja.exe"
    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    return cmake, ninja, toolchain, ndk


def entry() -> dict:
    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    for source in data["sources"]:
        if source["id"] == ENTRY_ID:
            return source
    raise SystemExit(f"sources.json is missing the {ENTRY_ID} pin")


def ensure_cached(entry_data: dict) -> Path:
    """Fetch-and-verify the zip through the shared provider machinery."""
    cached = NATIVE / ".providers" / ENTRY_ID / entry_data["artifact"]
    if cached.is_file() and sha256_file(cached) == entry_data["artifact_sha256"]:
        return cached
    result = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "fetch_provider.py"), ENTRY_ID, "--no-extract"],
        cwd=ROOT)
    if result.returncode != 0 or not cached.is_file():
        raise SystemExit("provider fetch failed for the pinned amalgamation")
    actual = sha256_file(cached)
    if actual != entry_data["artifact_sha256"]:
        raise SystemExit(f"amalgamation zip sha256 mismatch after fetch: {actual}")
    return cached


def stage_sources(entry_data: dict, archive: Path) -> Path:
    """Extract the amalgamation next to the recipe and verify content hashes."""
    expected_dir = SRC / entry_data["artifact"].removesuffix(".zip")
    marker = SRC / ".pocket-realm-amalgamation-sha"
    if marker.is_file() and marker.read_text(encoding="utf-8").strip() == entry_data["artifact_sha256"] \
            and expected_dir.is_dir():
        return expected_dir
    for name, expected in entry_data["content_sha256"].items():
        target = expected_dir / name
        if not target.is_file():
            break
        if sha256_file(target) == expected:
            continue
        break
    else:
        marker.write_text(entry_data["artifact_sha256"] + "\n", encoding="utf-8")
        return expected_dir
    if expected_dir.exists():
        shutil.rmtree(expected_dir)
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(SRC)
    for name, expected in entry_data["content_sha256"].items():
        actual = sha256_file(expected_dir / name)
        if actual != expected:
            raise SystemExit(f"staged {name} sha256 {actual} != pin {expected}")
    marker.write_text(entry_data["artifact_sha256"] + "\n", encoding="utf-8")
    return expected_dir


def build(abi: str) -> Path:
    entry_data = entry()
    archive = ensure_cached(entry_data)
    stage_sources(entry_data, archive)
    triple = "x86_64-linux-android" if abi == "x86_64" else "aarch64-linux-android"
    build_dir = SRC / f"build-{triple}"
    prefix = NATIVE / ".deps" / ("prefix-x86_64" if abi == "x86_64" else "prefix-arm64")
    cmake, ninja, toolchain, _ = sdk_tools(abi)
    build_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(cmake), "-S", str(SRC), "-B", str(build_dir),
                    f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
                    f"-DANDROID_ABI={abi}", f"-DANDROID_PLATFORM=android-{API}",
                    "-DANDROID_STL=c++_shared", "-G", "Ninja",
                    f"-DCMAKE_MAKE_PROGRAM={ninja}",
                    f"-DCMAKE_INSTALL_PREFIX={prefix}"], check=True)
    subprocess.run([str(cmake), "--build", str(build_dir), "--target", "install"], check=True)
    library = prefix / "lib" / "libsqlite3.a"
    if not library.is_file():
        raise SystemExit(f"libsqlite3.a missing after build: {library}")
    print(f"OK  {abi}: {library} sha256={sha256_file(library)}")
    return library


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=("x86_64", "arm64-v8a"))
    parser.add_argument("--list", action="store_true", help="print the pin and exit")
    args = parser.parse_args()
    if args.list or not args.abi:
        data = entry()
        print(json.dumps({key: data[key] for key in
                          ("id", "artifact_url", "artifact_sha256", "content_sha256")},
                         indent=2))
        return 0
    build(args.abi)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
