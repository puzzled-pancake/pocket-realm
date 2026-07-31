#!/usr/bin/env python3
"""Cross-compile the native realm (CMaNGOS + Playerbots) for Android arm64-v8a.

This is the O03 reproducible-build entry point. It builds the external
dependencies (OpenSSL, Boost, SQLite) and then CMaNGOS + Playerbots against the
pinned NDK.

Environment requirements (Windows host):
  - Android NDK (found via ANDROID_SDK_ROOT/ANDROID_HOME under SDK/ndk)
  - MSYS2 (found via MSYS2_ROOT/MSYS_HOME env, default G:\\msys64). Provides a
    complete Unix-style perl for OpenSSL and a host gcc for the Boost b2
    bootstrap. Install with:
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
# MSYS2 location is environment-overridable so the build is not pinned to one
# developer's machine. Default kept as the documented install path.
MSYS2 = Path(os.environ.get("MSYS2_ROOT") or os.environ.get("MSYS_HOME") or "G:/msys64")
MSYS_BASH = MSYS2 / "usr" / "bin" / "bash.exe"

SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
if not SDK.is_dir():
    print("ERROR: ANDROID_SDK_ROOT/ANDROID_HOME not set or missing", file=sys.stderr)
    sys.exit(2)

_NDK_DIR = SDK / "ndk"
NDK_VERSIONS = sorted([p.name for p in _NDK_DIR.iterdir()]) if _NDK_DIR.is_dir() else []
NDK = _NDK_DIR / NDK_VERSIONS[-1] if NDK_VERSIONS else None
if NDK is None or not NDK.is_dir():
    print(f"ERROR: no NDK found under {_NDK_DIR}. Install an NDK via the SDK "
          f"manager (e.g. ndk;30.0.15729638).", file=sys.stderr)
    sys.exit(2)
# The playerbots source lives in its own submodule; CMaNGOS's FetchContent
# expects it pre-populated at src/modules/PlayerBots (SOURCE_DIR form).
PLAYERBOTS_SUBMODULE = NATIVE / "playerbots"
PLAYERBOTS_IN_TREE = NATIVE / "cmangos" / "src" / "modules" / "PlayerBots"
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
    """Create the simple-name NDK junction, or recreate it if it points at a
    stale NDK (e.g. after an NDK upgrade changed which version sorts last).

    A junction that exists but targets the wrong NDK would silently build against
    an older toolchain than `NDK` while the build log claims the new one, so we
    resolve the current target rather than trusting existence alone.
    """
    expected = os.path.realpath(NDK)
    if NDK_LINK.exists():
        actual = os.path.realpath(NDK_LINK)
        if os.path.normcase(actual) == os.path.normcase(expected):
            return
        print(f"NDK junction points at {actual}, expected {expected}; recreating")
        # Remove the stale junction. rmdir works for junctions and does not
        # descend into the target tree.
        NDK_LINK.unlink() if NDK_LINK.is_symlink() else _remove_junction(NDK_LINK)
    print(f"Creating NDK junction {NDK_LINK} -> {NDK}")
    subprocess.run(["cmd", "/c", "mklink", "/J", str(NDK_LINK), str(NDK)], check=True)


def _remove_junction(path: Path):
    """Remove a directory junction without recursing into its target."""
    subprocess.run(["cmd", "/c", "rmdir", str(path)], check=True)


def ensure_playerbots():
    """Populate CMaNGOS's in-tree modules/PlayerBots from the playerbots
    submodule so its FetchContent (SOURCE_DIR form) finds the pinned source.

    CMaNGOS's src/CMakeLists.txt declares PlayerBots with
    `SOURCE_DIR=.../modules/PlayerBots`; FetchContent copies from there rather
    than cloning. On a clean checkout the directory is absent (gitignored by the
    cmangos submodule under src/modules/), so we mirror the pinned submodule
    here to make the playerbots build reproducible.
    """
    if not PLAYERBOTS_SUBMODULE.is_dir():
        print(f"ERROR: playerbots submodule missing at {PLAYERBOTS_SUBMODULE}. "
              f"Run `git submodule update --init`.", file=sys.stderr)
        sys.exit(2)
    # A marker file makes re-runs idempotent without a full content compare of a
    # 365 MB tree. If the marker is absent or names a different source, refresh.
    marker = PLAYERBOTS_IN_TREE / ".pocket-realm-source"
    if marker.exists() and marker.read_text().strip() == str(PLAYERBOTS_SUBMODULE):
        return
    if PLAYERBOTS_IN_TREE.exists():
        shutil.rmtree(PLAYERBOTS_IN_TREE)
    PLAYERBOTS_IN_TREE.parent.mkdir(parents=True, exist_ok=True)
    print(f"Populating {PLAYERBOTS_IN_TREE} from {PLAYERBOTS_SUBMODULE}")
    shutil.copytree(PLAYERBOTS_SUBMODULE, PLAYERBOTS_IN_TREE)
    marker.write_text(str(PLAYERBOTS_SUBMODULE))


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
    # The playerbots source must be in-tree before configure so CMaNGOS's
    # FetchContent (SOURCE_DIR form) finds it. This makes the playerbots build
    # reproducible from a clean checkout rather than relying on a manual copy.
    ensure_playerbots()
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
           "-DBUILD_PLAYERBOTS=ON",
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
        print(f"MSYS2: {MSYS2} ({'found' if MSYS_BASH.is_file() else 'NOT FOUND'})")
        print(f"Prefix: {PREFIX}")
        return 0

    ensure_ndk_link()
    PREFIX.mkdir(parents=True, exist_ok=True)
    # Stages openssl/boost run inside MSYS2; fail early with a clear message
    # rather than a cryptic missing-bash error deep in the build.
    needs_msys = args.stage in ("all", "openssl", "boost")
    if needs_msys and not MSYS_BASH.is_file():
        print(f"ERROR: MSYS2 bash not found at {MSYS_BASH}. Install MSYS2 and "
              f"set MSYS2_ROOT (or MSYS_HOME), then `pacman -S --noconfirm "
              f"make gcc`.", file=sys.stderr)
        return 2
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
