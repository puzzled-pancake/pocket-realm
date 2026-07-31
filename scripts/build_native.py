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

Stages are cached under native/.deps/prefix-<triple> so a partial run resumes.
Pass a stage name to run only that stage.

    python3 scripts/build_native.py list
    python3 scripts/build_native.py openssl
    python3 scripts/build_native.py boost
    python3 scripts/build_native.py sqlite
    python3 scripts/build_native.py cmangos
    python3 scripts/build_native.py all

ABI selection (default arm64-v8a, the product target):

    python3 scripts/build_native.py --abi arm64-v8a list
    python3 scripts/build_native.py --abi x86_64 all     # emulator test target

The arm64-v8a target is the product ABI (.claude/rules/native.md). x86_64 is an
emulator-only target so the same native realm can be *executed* (not just
objdump'd) during development; it is never shipped.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native"
DEPS_SRC = NATIVE / ".deps" / "src"
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
# The embeddable realm lifecycle facade (O04). Built as libpocketrealm.so from
# native/pocket-runtime, linked against the same game/shared/playerbots static
# libraries as mangosd but with POCKET_EMBEDDED defined (exit->throw).
POCKET_RUNTIME = NATIVE / "pocket-runtime"


@dataclass(frozen=True)
class Arch:
    """Per-ABI build parameters. arm64-v8a is the product target; x86_64 is an
    emulator-only test target so the native realm can be executed, not just
    objdump'd, during development."""
    abi: str           # ANDROID_ABI value (e.g. arm64-v8a)
    triple: str        # machine triple used in path/binary names (e.g. arm64)
    openssl_target: str  # perl Configure target (e.g. android-arm64)
    clang_prefix: str  # NDK clang++ wrapper prefix (e.g. aarch64-linux-android)
    boost_arch: str    # b2 architecture=.../abi=.../binary-format=... tokens

ARCHES = {
    "arm64-v8a": Arch(
        abi="arm64-v8a", triple="arm64",
        openssl_target="android-arm64", clang_prefix="aarch64-linux-android",
        boost_arch="architecture=arm abi=aapcs binary-format=elf",
    ),
    "x86_64": Arch(
        abi="x86_64", triple="x86_64",
        openssl_target="android-x86_64", clang_prefix="x86_64-linux-android",
        boost_arch="architecture=x86 binary-format=elf",
    ),
}
# Selected in main() from --abi; default kept as the product ABI so the default
# behavior is byte-for-byte identical to the pre-multi-arch behavior.
ARCH: Arch = ARCHES["arm64-v8a"]


def prefix_dir(arch: Arch) -> Path:
    return NATIVE / ".deps" / f"prefix-{arch.triple}"


def build_dir(arch: Arch) -> Path:
    return NATIVE / f".build-{arch.triple}"

CMAKE_DIR = SDK / "cmake"
_CMAKE_VERSIONS = sorted([p.name for p in CMAKE_DIR.iterdir() if p.is_dir()], reverse=True)
CMAKE_BIN = next((CMAKE_DIR / v / "bin" / "cmake.exe" for v in _CMAKE_VERSIONS
                  if (CMAKE_DIR / v / "bin" / "cmake.exe").exists()), shutil.which("cmake"))
NINJA = next((CMAKE_DIR / v / "bin" / "ninja.exe" for v in _CMAKE_VERSIONS
              if (CMAKE_DIR / v / "bin" / "ninja.exe").exists()), shutil.which("ninja"))
TOOLCHAIN_FILE = NDK_LINK / "build" / "cmake" / "android.toolchain.cmake"

STAGES = ["openssl", "boost", "sqlite", "cmangos"]
# O04 flags (off by default so O03's exact build is the default behavior).
BUILD_RUNTIME = False       # set by --runtime; builds libpocketrealm.so
BUILD_RUNTIME_TESTS = False # set by --runtime-tests; builds pocket_lifecycle_test


def run(cmd: list[str], cwd: Path | None = None, env: dict | None = None) -> int:
    print(f"\n$ {' '.join(str(c) for c in cmd)}")
    return subprocess.call(cmd, cwd=str(cwd) if cwd else None, env=env)


def _stale_target(src: Path, target: str) -> bool:
    """True if `src` was last built for a target other than `target`.

    Several dependency source trees are shared across ABIs (a fresh tarball
    extract is expensive); this detects a target switch so the caller can clean
    before re-configuring, avoiding mixed-arch artifacts.
    """
    marker = src / ".pocket-realm-target"
    if not marker.is_file():
        return False
    return marker.read_text().strip() != target


def _mark_target(src: Path, target: str) -> None:
    (src / ".pocket-realm-target").write_text(target)


def to_msys_path(p: Path) -> str:
    r"""Convert a Windows drive-letter path to its MSYS2 mount form so it matches
    what MSYS `which` returns. e.g. C:\Users\X -> /c/Users/X. OpenSSL's android
    config compares `which("clang")` against $ANDROID_NDK_ROOT with a regex
    anchored at the start, so the two must use the same path form or the clang
    detection fails and it falls back to a nonexistent NDK gcc.

    Only the drive-letter case is supported; a UNC or long (\\?\) path is
    refused loudly rather than emitted half-converted (a silent mixed form is
    exactly the failure mode the docstring above warns against).
    """
    s = str(p).replace("\\", "/")
    drive, rest = os.path.splitdrive(s)
    if len(drive) == 2 and drive[1] == ":":
        return "/" + drive[0].lower() + rest
    if drive:
        raise ValueError(f"to_msys_path: unsupported path form for {p} (drive={drive!r}); "
                         f"only drive-letter paths are supported")
    return s


def run_msys(script: str) -> int:
    """Run a bash script inside MSYS2 with the NDK toolchain on PATH.

    Uses a NON-login shell (-c): a login shell (-lc) sources /etc/profile which
    resets PATH and drops the NDK toolchain, so OpenSSL's `which("clang")` then
    fails and it falls back to looking for a (nonexistent) NDK gcc and dies. The
    toolchain is prepended in both the process env and an explicit `export PATH`
    so it survives regardless of what the script sources.

    ANDROID_NDK_ROOT is passed in MSYS-mount form (/c/...) so it matches the form
    `which clang` returns; otherwise OpenSSL's clang-detection regex misses and
    it dies looking for a NDK gcc.
    """
    tc_bin = str(TOOLCHAIN / "bin").replace("\\", "/")
    env = {
        "ANDROID_NDK_ROOT": to_msys_path(NDK_LINK),
        "PATH": f"{tc_bin}:/usr/bin",
        "HOME": os.environ.get("USERPROFILE", str(Path.home())).replace("\\", "/"),
        "MSYSTEM": "MSYS",
    }
    full = f'export PATH="{tc_bin}:/usr/bin:$PATH"\n{script}'
    return subprocess.call([str(MSYS_BASH), "-c", full], env=env)


def ensure_ndk_link():
    """Create the simple-name NDK junction, or recreate it if it points at a
    stale NDK (e.g. after an NDK upgrade changed which version sorts last, or
    removed the old version leaving a broken junction).

    A junction that exists but targets the wrong NDK would silently build against
    an older toolchain than `NDK` while the build log claims the new one, so we
    resolve the current target rather than trusting existence alone. Note
    Path.exists() follows the junction target, so a BROKEN junction (target NDK
    uninstalled) reports exists()==False; we must also check is_junction() to
    detect and recreate it, otherwise mklink fails on the existing name. (Use
    is_junction, NOT is_symlink: Windows directory junctions made by `mklink /J`
    carry the mount-point reparse tag, so is_symlink() returns False for them.)
    """
    expected = os.path.realpath(NDK)
    # exists() follows the junction target; is_junction() catches a broken one
    # (a junction with its target uninstalled).
    if NDK_LINK.exists() or NDK_LINK.is_junction():
        actual = os.path.realpath(NDK_LINK)
        if NDK_LINK.exists() and os.path.normcase(actual) == os.path.normcase(expected):
            return
        print(f"NDK junction points at {actual} (expected {expected}); recreating")
        # Directory junctions are not symlinks from Python's view; rmdir removes
        # the junction without descending into the target tree.
        _remove_junction(NDK_LINK)
    print(f"Creating NDK junction {NDK_LINK} -> {NDK}")
    subprocess.run(["cmd", "/c", "mklink", "/J", str(NDK_LINK), str(NDK)], check=True)


def _remove_junction(path: Path):
    """Remove a directory junction without recursing into its target."""
    subprocess.run(["cmd", "/c", "rmdir", str(path)], check=True)


def _force_remove(func, path, exc_info):
    """shutil.rmtree onerror/onexc handler that clears the read-only bit before
    removal. Git pack files (.idx/.pack) are marked read-only on Windows and
    would otherwise raise PermissionError during a tree refresh."""
    import stat
    try:
        os.chmod(path, stat.S_IWRITE)
        func(path)
    except OSError:
        func(path)


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
    # 365 MB tree. If the marker names the current submodule, assume the mirror
    # is current and skip the refresh.
    marker = PLAYERBOTS_IN_TREE / ".pocket-realm-source"
    if marker.exists() and marker.read_text().strip() == str(PLAYERBOTS_SUBMODULE):
        return
    # If the tree exists without a marker (e.g. from an earlier manual copy or
    # FetchContent), the read-only .git/objects pack files need the force handler
    # to be removed before re-mirroring.
    if PLAYERBOTS_IN_TREE.exists():
        shutil.rmtree(PLAYERBOTS_IN_TREE, onerror=_force_remove)
    PLAYERBOTS_IN_TREE.parent.mkdir(parents=True, exist_ok=True)
    print(f"Populating {PLAYERBOTS_IN_TREE} from {PLAYERBOTS_SUBMODULE}")
    # copytree copies the submodule's gitlink as a real dir; exclude .git to keep
    # the mirror a plain source tree (CMake only needs the source files).
    shutil.copytree(PLAYERBOTS_SUBMODULE, PLAYERBOTS_IN_TREE,
                    ignore=shutil.ignore_patterns(".git"))
    marker.write_text(str(PLAYERBOTS_SUBMODULE))


def openssl() -> int:
    src = DEPS_SRC / "openssl-3.4.3"
    if not src.is_dir():
        print(f"ERROR: {src} not present.", file=sys.stderr)
        return 1
    prefix = prefix_dir(ARCH)
    # MSYS-mount forms: with a non-login shell (-c) the cygwin path-mangling that
    # a login profile sets up is absent, so b2/openssl would treat a drive-letter
    # path like G:/x as a relative component and install into a malformed
    # ".../src/<G:>/" tree. Use the /g/... mount form which both handle natively.
    msrc = to_msys_path(src)
    mprefix = to_msys_path(prefix)
    # OpenSSL's source tree is shared across ABIs. If a previous Configure for a
    # different target left objects behind, `make` reuses them and produces
    # mixed-arch archives (e.g. AArch64 objects in an x86_64 libcrypto.a) that
    # fail to link. Clean when the target changed.
    if _stale_target(src, ARCH.openssl_target):
        print(f"OpenSSL tree was configured for a different target; cleaning")
        run_msys(f"cd {msrc} && [ -f Makefile ] && make clean || true")
    _mark_target(src, ARCH.openssl_target)
    script = f"""
set -e
cd {msrc}
perl Configure {ARCH.openssl_target} -D__ANDROID_API__={API} \
  --prefix={mprefix} --openssldir={mprefix}/ssl \
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
    # b2 caches built artifacts in bin.v2 keyed loosely; an ABI switch can leave
    # stale objects. Clean the boost build cache when the triple changed OR when
    # the PIC flag changed (PIC is required once libpocketrealm.so is built; a
    # non-PIC Boost archive cannot link into a shared library).
    boost_target = f"{ARCH.clang_prefix}+pic"
    if _stale_target(src, boost_target):
        print(f"Boost tree was built for a different triple/PIC config; cleaning bin.v2")
        shutil.rmtree(src / "bin.v2", ignore_errors=True)
    _mark_target(src, boost_target)
    tc = TOOLCHAIN.as_posix()
    prefix = prefix_dir(ARCH)
    msrc = to_msys_path(src)
    mprefix = to_msys_path(prefix)
    cx = f"{tc}/bin/{ARCH.clang_prefix}{API}-clang++"
    tgt = f"{ARCH.clang_prefix}{API}"
    user_config = f"""using clang : android
: {cx}
: <compileflags>"--target={tgt} --sysroot={tc}/sysroot"
  <linkflags>"--target={tgt} --sysroot={tc}/sysroot"
  <archiver>{tc}/bin/llvm-ar
  <ranlib>{tc}/bin/llvm-ranlib
;
"""
    (src / "user-config.jam").write_text(user_config)
    script = f"""
set -e
cd {msrc}
[ -f b2 ] || ./bootstrap.sh --with-toolset=gcc
./b2 --user-config=user-config.jam toolset=clang-android target-os=android \\
  address-model=64 {ARCH.boost_arch} \\
  link=static runtime-link=static threading=multi \\
  cxxflags=-fPIC cflags=-fPIC \\
  --with-program_options --with-thread --with-regex --with-serialization --with-filesystem --with-system \\
  --prefix={mprefix} -j$(nproc) install
"""
    return run_msys(script)


def sqlite() -> int:
    src = DEPS_SRC / "sqlite"
    build = src / f"build-{ARCH.triple}"
    prefix = prefix_dir(ARCH)
    cmd = [str(CMAKE_BIN), "-S", str(src), "-B", str(build),
           f"-DCMAKE_TOOLCHAIN_FILE={TOOLCHAIN_FILE}",
           f"-DANDROID_ABI={ARCH.abi}", f"-DANDROID_PLATFORM=android-{API}",
           "-DANDROID_STL=c++_shared", "-G", "Ninja",
           f"-DCMAKE_MAKE_PROGRAM={NINJA}",
           f"-DCMAKE_INSTALL_PREFIX={prefix}"]
    if run(cmd) != 0:
        return 1
    return run([str(CMAKE_BIN), "--build", str(build), "--target", "install"])


def cmangos() -> int:
    # The playerbots source must be in-tree before configure so CMaNGOS's
    # FetchContent (SOURCE_DIR form) finds it. This makes the playerbots build
    # reproducible from a clean checkout rather than relying on a manual copy.
    ensure_playerbots()
    build = build_dir(ARCH)
    src = NATIVE / "cmangos"
    prefix = prefix_dir(ARCH)
    cmd = [str(CMAKE_BIN), "-S", str(src), "-B", str(build),
           f"-DCMAKE_TOOLCHAIN_FILE={TOOLCHAIN_FILE}",
           f"-DANDROID_ABI={ARCH.abi}", f"-DANDROID_PLATFORM=android-{API}",
           "-DANDROID_STL=c++_shared", "-G", "Ninja",
           f"-DCMAKE_MAKE_PROGRAM={NINJA}",
           # Point find_package at the cross-compiled prefix and constrain the
           # search there (so it never picks up a host Boost/OpenSSL). These are
           # the exact vars the working arm64 configure used.
           f"-DCMAKE_PREFIX_PATH={prefix}",
           f"-DCMAKE_FIND_ROOT_PATH={prefix}",
           f"-DOPENSSL_ROOT_DIR={prefix}",
           f"-DBOOST_ROOT={prefix}",
           f"-DBoost_DIR={prefix}/lib/cmake/Boost-1.86.0",
           "-DBoost_USE_STATIC_LIBS=ON",
           "-DBoost_USE_STATIC_RUNTIME=ON",
           "-DBoost_USE_MULTITHREADED=ON",
           "-DSQLITE=ON", "-DBUILD_EXTRACTORS=OFF",
           "-DBUILD_GAME_SERVER=ON", "-DBUILD_LOGIN_SERVER=ON",
           "-DBUILD_PLAYERBOTS=ON",
           "-DCMAKE_BUILD_TYPE=Release"]
    # O04: build the embeddable lifecycle facade (libpocketrealm.so). Gated by
    # --runtime so a plain `cmangos` stage stays bit-for-bit identical to O03
    # (the standalone mangosd/realmd are unaffected either way; POCKET_EMBEDDED
    # is only defined inside the pocketrealm target).
    if BUILD_RUNTIME:
        cmd += [f"-DBUILD_POCKET_RUNTIME=ON",
                f"-DPOCKET_RUNTIME_DIR={POCKET_RUNTIME}",
                f"-DPOCKET_RUNTIME_DIR:PATH={POCKET_RUNTIME}"]
    if BUILD_RUNTIME_TESTS:
        cmd += ["-DBUILD_POCKET_RUNTIME_TESTS=ON"]
    if run(cmd) != 0:
        return 1
    return run([str(CMAKE_BIN), "--build", str(build), "-j", str(os.cpu_count() or 4)])


def main() -> int:
    global ARCH, BUILD_RUNTIME, BUILD_RUNTIME_TESTS
    ap = argparse.ArgumentParser()
    ap.add_argument("--abi", default="arm64-v8a", choices=list(ARCHES),
                    help="target ABI (default arm64-v8a, the product target; "
                         "x86_64 is an emulator-only test target)")
    ap.add_argument("--runtime", action="store_true",
                    help="also build libpocketrealm.so (O04 embeddable facade)")
    ap.add_argument("--runtime-tests", action="store_true",
                    help="also build the pocket_lifecycle_test native test binary")
    ap.add_argument("stages", nargs="*", default=["all"],
                    help="stage(s) to run (default all); pass e.g. 'boost sqlite cmangos'")
    args = ap.parse_args()
    ARCH = ARCHES[args.abi]
    BUILD_RUNTIME = args.runtime or args.runtime_tests
    BUILD_RUNTIME_TESTS = args.runtime_tests
    prefix = prefix_dir(ARCH)

    # Normalize the requested stages: resolve 'all'/'list', reject unknowns.
    if "list" in args.stages:
        print("Stages:", ", ".join(STAGES))
        print(f"ABI: {ARCH.abi} (triple {ARCH.triple})")
        print(f"NDK: {NDK}")
        print(f"NDK link: {NDK_LINK}")
        print(f"MSYS2: {MSYS2} ({'found' if MSYS_BASH.is_file() else 'NOT FOUND'})")
        print(f"Prefix: {prefix}")
        print(f"Pocket runtime: {'build libpocketrealm.so' if BUILD_RUNTIME else 'OFF (pass --runtime)'}")
        print(f"Pocket runtime tests: {'build pocket_lifecycle_test' if BUILD_RUNTIME_TESTS else 'OFF'}")
        return 0
    bad = [s for s in args.stages if s not in ("all", *STAGES)]
    if bad:
        ap.error(f"unrecognized stage(s): {' '.join(bad)}")
    stages = STAGES if "all" in args.stages else args.stages

    ensure_ndk_link()
    prefix.mkdir(parents=True, exist_ok=True)
    # Stages openssl/boost run inside MSYS2; fail early with a clear message
    # rather than a cryptic missing-bash error deep in the build.
    needs_msys = any(s in ("openssl", "boost") for s in stages)
    if needs_msys and not MSYS_BASH.is_file():
        print(f"ERROR: MSYS2 bash not found at {MSYS_BASH}. Install MSYS2 and "
              f"set MSYS2_ROOT (or MSYS_HOME), then `pacman -S --noconfirm "
              f"make gcc`.", file=sys.stderr)
        return 2
    print(f"ABI: {ARCH.abi}\nNDK: {NDK}\nNDK link: {NDK_LINK}\n"
          f"MSYS2: {MSYS2}\nPrefix: {prefix}")

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
