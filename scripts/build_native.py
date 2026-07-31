#!/usr/bin/env python3
"""Cross-compile the native realm (CMaNGOS + Playerbots) for Android arm64-v8a.

This is the O03 reproducible-build entry point. It builds the external
dependencies (OpenSSL, Boost, SQLite) and then CMaNGOS + Playerbots against the
pinned NDK.

Environment requirements (Windows host):
  - Android NDK (found via ANDROID_SDK_ROOT/ANDROID_HOME under SDK/ndk)
  - MSYS2 at G:\\msys64 (provides a complete Unix-style perl for OpenSSL and a
    host gcc for the Boost b2 bootstrap). Install with:
      pacman -S --noconfirm make gcc
  - The SDK-bundled CMake + Ninja

Stages are cached under native/.deps/prefix-arm64 so a partial run resumes.
Pass a stage name to run only that stage.

    python3 scripts/build_native.py list
    python3 scripts/build_native.py openssl
    python3 scripts/build_native.py boost
    python3 scripts/build_native.py sqlite
    python3 scripts/build_native.py cmangos
    python3 scripts/build_native.py all
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
DEPS_SRC = NATIVE / ".deps" / "src"
PREFIX = NATIVE / ".deps" / "prefix-arm64"
MSYS2 = Path("G:/msys64")
MSYS_BASH = MSYS2 / "usr" / "bin" / "bash.exe"

SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
if not SDK.is_dir():
    print("ERROR: ANDROID_SDK_ROOT/ANDROID_HOME not set or missing", file=sys.stderr)
    sys.exit(2)

_NDK_DIR = SDK / "ndk"
NDK_VERSIONS = sorted([p.name for p in _NDK_DIR.iterdir()]) if _NDK_DIR.is_dir() else []
NDK = _NDK_DIR / NDK_VERSIONS[-1] if NDK_VERSIONS else None
# An NDK junction with a simple name avoids Windows 8.3 short-name path issues
# in OpenSSL's toolchain detection. Created if missing.
NDK_LINK = SDK / "ndk-link"
TOOLCHAIN = NDK_LINK / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64"
API = 26
ABI = "arm64-v8a"

CMAKE_DIR = SDK / "cmake"
_CMAKE_VERSIONS = sorted([p.name for p in CMAKE_DIR.iterdir() if p.is_dir()], reverse=True)
CMAKE_BIN = next((CMAKE_DIR / v / "bin" / "cmake.exe" for v in _CMAKE_VERSIONS
                  if (CMAKE_DIR / v / "bin" / "cmake.exe").exists()), shutil.which("cmake"))
NINJA = next((CMAKE_DIR / v / "bin" / "ninja.exe" for v in _CMAKE_VERSIONS
              if (CMAKE_DIR / v / "bin" / "ninja.exe").exists()), shutil.which("ninja"))
TOOLCHAIN_FILE = NDK_LINK / "build" / "cmake" / "android.toolchain.cmake"

STAGES = ["openssl", "boost", "sqlite", "cmangos"]


def run(cmd: list[str], cwd: Path | None = None, env: dict | None = None) -> int:
    print(f"\n$ {' '.join(str(c) for c in cmd)}")
    return subprocess.call(cmd, cwd=str(cwd) if cwd else None, env=env)


def run_msys(script: str) -> int:
    """Run a bash script inside MSYS2 with the NDK toolchain on PATH."""
    env = {
        "ANDROID_NDK_ROOT": str(NDK_LINK).replace("\\", "/"),
        "PATH": f"{TOOLCHAIN/'bin'}:/usr/bin",
        "HOME": os.environ.get("USERPROFILE", str(Path.home())).replace("\\", "/"),
    }
    return subprocess.call([str(MSYS_BASH), "-lc", script], env=env)


def ensure_ndk_link():
    """Create the simple-name NDK junction if it doesn't exist."""
    if NDK_LINK.exists():
        return
    print(f"Creating NDK junction {NDK_LINK} -> {NDK}")
    subprocess.run(["cmd", "/c", "mklink", "/J", str(NDK_LINK), str(NDK)], check=True)


def openssl() -> int:
    src = DEPS_SRC / "openssl-3.4.3"
    if not src.is_dir():
        print(f"ERROR: {src} not present.", file=sys.stderr)
        return 1
    script = f"""
set -e
cd {src.as_posix()}
perl Configure android-arm64 -D__ANDROID_API__={API} \
  --prefix={PREFIX.as_posix()} --openssldir={PREFIX.as_posix()}/ssl \
  no-shared no-tests no-asm
make -j$(nproc)
make install_sw
"""
    return run_msys(script)


def boost() -> int:
    src = DEPS_SRC / "boost-1.86.0"
    if not src.is_dir():
        print(f"ERROR: {src} not present.", file=sys.stderr)
        return 1
    tc = TOOLCHAIN.as_posix()
    user_config = f"""using clang : android
: {tc}/bin/aarch64-linux-android{API}-clang++
: <compileflags>"--target=aarch64-linux-android{API} --sysroot={tc}/sysroot"
  <linkflags>"--target=aarch64-linux-android{API} --sysroot={tc}/sysroot"
  <archiver>{tc}/bin/llvm-ar
  <ranlib>{tc}/bin/llvm-ranlib
;
"""
    (src / "user-config.jam").write_text(user_config)
    script = f"""
set -e
cd {src.as_posix()}
[ -f b2 ] || ./bootstrap.sh --with-toolset=gcc
./b2 --user-config=user-config.jam toolset=clang-android target-os=android \\
  address-model=64 architecture=arm abi=aapcs binary-format=elf \\
  link=static runtime-link=static threading=multi \\
  --with-program_options --with-thread --with-regex --with-serialization --with-filesystem --with-system \\
  --prefix={PREFIX.as_posix()} -j$(nproc) install
"""
    return run_msys(script)


def sqlite() -> int:
    src = DEPS_SRC / "sqlite"
    build = src / "build-arm64"
    cmd = [str(CMAKE_BIN), "-S", str(src), "-B", str(build),
           f"-DCMAKE_TOOLCHAIN_FILE={TOOLCHAIN_FILE}",
           f"-DANDROID_ABI={ABI}", f"-DANDROID_PLATFORM=android-{API}",
           "-DANDROID_STL=c++_shared", "-G", "Ninja",
           f"-DCMAKE_MAKE_PROGRAM={NINJA}",
           f"-DCMAKE_INSTALL_PREFIX={PREFIX}"]
    if run(cmd) != 0:
        return 1
    return run([str(CMAKE_BIN), "--build", str(build), "--target", "install"])


def cmangos() -> int:
    build = NATIVE / ".build-arm64"
    src = NATIVE / "cmangos"
    cmd = [str(CMAKE_BIN), "-S", str(src), "-B", str(build),
           f"-DCMAKE_TOOLCHAIN_FILE={TOOLCHAIN_FILE}",
           f"-DANDROID_ABI={ABI}", f"-DANDROID_PLATFORM=android-{API}",
           "-DANDROID_STL=c++_shared", "-G", "Ninja",
           f"-DCMAKE_MAKE_PROGRAM={NINJA}",
           f"-DCMAKE_PREFIX_PATH={PREFIX}",
           f"-DOPENSSL_ROOT_DIR={PREFIX}",
           f"-DBOOST_ROOT={PREFIX}",
           "-DSQLITE=ON", "-DBUILD_EXTRACTORS=OFF",
           "-DBUILD_GAME_SERVER=ON", "-DBUILD_LOGIN_SERVER=ON",
           "-DCMAKE_BUILD_TYPE=Release"]
    if run(cmd) != 0:
        return 1
    return run([str(CMAKE_BIN), "--build", str(build), "-j", str(os.cpu_count() or 4)])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", nargs="?", default="all", choices=["all", "list", *STAGES])
    args = ap.parse_args()
    if args.stage == "list":
        print("Stages:", ", ".join(STAGES))
        print(f"NDK: {NDK}")
        print(f"NDK link: {NDK_LINK}")
        print(f"MSYS2: {MSYS2}")
        print(f"Prefix: {PREFIX}")
        return 0

    ensure_ndk_link()
    PREFIX.mkdir(parents=True, exist_ok=True)
    print(f"NDK: {NDK}\nNDK link: {NDK_LINK}\nMSYS2: {MSYS2}\nPrefix: {PREFIX}")

    stages = STAGES if args.stage == "all" else [args.stage]
    for s in stages:
        fn = {"openssl": openssl, "boost": boost, "sqlite": sqlite, "cmangos": cmangos}[s]
        print(f"\n===== STAGE {s} =====")
        if fn() != 0:
            print(f"\nFAILED at stage {s}", file=sys.stderr)
            return 1
    print("\nALL STAGES OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
