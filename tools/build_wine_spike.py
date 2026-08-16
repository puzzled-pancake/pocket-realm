#!/usr/bin/env python3
"""Build the native/wine-spike O06 Phase-1 artifacts.

Standalone CMake build of native/wine-spike against the pinned NDK (same
toolchain as build_packaging.py). Output lands in
native/.build-x86_64/wine-spike-build/:

  libwine_spike.so  JNI shim + symlink-tree builder + Wine launcher +
                    /proc maps probe + PE cache materializer/verifier.

16 KB-aligned, links only Android/Bionic libs (log, dl). The glibc/Wine ELFs
are NOT linked here — they are execve'd at runtime via the APK-managed loader.

Usage:
  python3 tools/build_wine_spike.py --abi x86_64
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

try:
    from tools import common
except ImportError:  # direct execution: python tools/<script>.py
    import common
NATIVE = ROOT / "native"
SRC = NATIVE / "wine-spike"
SDK = common.resolve_android_sdk()
NDK = common.resolve_android_ndk(sdk=SDK)
NDK_LINK = SDK / "ndk-link"
TOOLCHAIN_FILE = NDK_LINK / "build" / "cmake" / "android.toolchain.cmake"
CMAKE_DIR = SDK / "cmake"
_V = sorted([p.name for p in CMAKE_DIR.iterdir() if p.is_dir()], reverse=True) if CMAKE_DIR.is_dir() else []
CMAKE = next((CMAKE_DIR / v / "bin" / "cmake.exe" for v in _V
              if (CMAKE_DIR / v / "bin" / "cmake.exe").exists()), None)
NINJA = next((CMAKE_DIR / v / "bin" / "ninja.exe" for v in _V
              if (CMAKE_DIR / v / "bin" / "ninja.exe").exists()), None)

ABIS = {"arm64-v8a": "arm64", "x86_64": "x86_64"}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--abi", required=True, choices=list(ABIS))
    args = ap.parse_args()

    if CMAKE is None or NINJA is None:
        print("ERROR: CMake/Ninja not found under SDK", file=sys.stderr)
        return 2
    if not TOOLCHAIN_FILE.is_file():
        print(f"ERROR: NDK toolchain not found at {TOOLCHAIN_FILE}; "
              f"run scripts/build_native.py first to create the ndk-link junction.",
              file=sys.stderr)
        return 2

    triple = ABIS[args.abi]
    build = NATIVE / f".build-{triple}" / "wine-spike-build"
    build.mkdir(parents=True, exist_ok=True)

    configure = [
        str(CMAKE), "-S", str(SRC), "-B", str(build),
        f"-DCMAKE_TOOLCHAIN_FILE={TOOLCHAIN_FILE}",
        f"-DANDROID_ABI={args.abi}",
        "-DANDROID_PLATFORM=android-26",
        "-DANDROID_STL=c++_shared",
        "-G", "Ninja", f"-DCMAKE_MAKE_PROGRAM={NINJA}",
        "-DCMAKE_BUILD_TYPE=Release",
    ]
    print("== configure ==")
    rc = subprocess.run(configure).returncode
    if rc != 0:
        return rc
    print("== build ==")
    rc = subprocess.run([str(CMAKE), "--build", str(build), "--parallel"]).returncode
    if rc != 0:
        return rc

    so = build / "libwine_spike.so"
    print(f"\n== artifact ==")
    print(f"  {so} {'OK' if so.is_file() else 'MISSING'} ({so.stat().st_size if so.is_file() else 0} bytes)")
    return 0 if so.is_file() else 1


if __name__ == "__main__":
    sys.exit(main())
