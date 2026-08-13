#!/usr/bin/env python3
"""Build the native X-server transport and GLX renderer.

Vendored source-matched from Winlator ca3d735 (app/src/main/cpp/winlator/).
Standalone CMake build against the pinned NDK (same toolchain as
build_wine_spike.py / build_packaging.py). Output lands in
native/.build-x86_64/xserver-winlator-build/libwinlator.so, staged into the APK
by Gradle's stageNativeLibs.

libwinlator.so provides the X-server's native transport: epoll accept loop,
buffered X11 input/output with SCM_RIGHTS fd-passing, Drawable BGRA ops, and
the GLES/EGL texture helpers used by the renderer. The JNI method names match
the vendored Java classes (com.winlator.xconnector.{XConnectorEpoll,
XInputStream,XOutputStream} + com.winlator.xserver.Drawable) exactly, so it is
a drop-in for System.loadLibrary("winlator").

libgladiorenderer.so is the source-matched Winlator GLX/OpenGL bridge used by
WineD3D for O07. Both are 16 KB-aligned and link Android/Bionic graphics libs.

Usage:
  python3 tools/build_xserver_winlator.py --abi x86_64
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
SRC = NATIVE / "xserver-winlator" / "cpp"
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
NDK_DIR = SDK / "ndk"
NDK_VERSIONS = sorted([p.name for p in NDK_DIR.iterdir()]) if NDK_DIR.is_dir() else []
NDK = NDK_DIR / NDK_VERSIONS[-1] if NDK_VERSIONS else None
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
    build = NATIVE / f".build-{triple}" / "xserver-winlator-build"
    build.mkdir(parents=True, exist_ok=True)

    configure = [
        str(CMAKE), "-S", str(SRC), "-B", str(build),
        f"-DCMAKE_TOOLCHAIN_FILE={TOOLCHAIN_FILE}",
        f"-DANDROID_ABI={args.abi}",
        "-DANDROID_PLATFORM=android-26",
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

    outputs = [build / "libwinlator.so", build / "libgladiorenderer.so"]
    if args.abi == "arm64-v8a":
        outputs.append(build / "libvortekrenderer.so")
    print(f"\n== artifact ==")
    for so in outputs:
        print(f"  {so} {'OK' if so.is_file() else 'MISSING'} ({so.stat().st_size if so.is_file() else 0} bytes)")
    return 0 if all(so.is_file() for so in outputs) else 1


if __name__ == "__main__":
    sys.exit(main())
