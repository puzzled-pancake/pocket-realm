#!/usr/bin/env python3
"""Build the native/packaging experiment artifacts.

Standalone CMake build of native/packaging against the pinned NDK (same toolchain
as build_native.py). Outputs land in native/.build-<triple>/packaging-build/:

  libpocketpkgtest.so        JNI shim + dlopen-by-soname + crash helper
  libpocket_pkg_launcher.so  PIE executable, renamed to .so for AGP

Both are 16 KB-aligned and link only libc/libdl/liblog (platform-supplied).

Usage:
  python3 tools/build_packaging.py --abi x86_64
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
PKG_SRC = NATIVE / "packaging"
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
    build = NATIVE / f".build-{triple}" / "packaging-build"
    build.mkdir(parents=True, exist_ok=True)

    # Configure + build. API 26 matches the realm build; the packaging libs
    # have no min-sdk-sensitive code beyond dlopen/dladdr/sysconf.
    configure = [
        str(CMAKE), "-S", str(PKG_SRC), "-B", str(build),
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

    # Rename the PIE executable to a .so so AGP packages it. CMake emits it
    # without an extension on Linux/Android toolchains; rename explicitly.
    exe = build / "pocket_pkg_launcher"
    so_name = build / "libpocket_pkg_launcher.so"
    if exe.is_file():
        so_name.write_bytes(exe.read_bytes())
        print(f"  staged {so_name.name} ({so_name.stat().st_size} bytes)")
    elif not so_name.is_file():
        # Some CMake versions already emit the .so name on Windows-hosted builds.
        print(f"ERROR: launcher output not found at {exe} or {so_name}", file=sys.stderr)
        return 2

    print("== artifacts ==")
    for f in ["libpocketpkgtest.so", "libpocket_pkg_launcher.so"]:
        p = build / f
        print(f"  {p} {'OK' if p.is_file() else 'MISSING'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
