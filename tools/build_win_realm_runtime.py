#!/usr/bin/env python3
"""Build the Pocket Realm realm runtimes as Windows DLLs with MSVC.

The Windows-port native lane (Phase 2d). Reuses the o09 driver's source
staging verbatim — pinned submodule commits, the playerbots CMake mirror,
anchor-verified overlays, sqlite hardening, db null guards — by importing
tools/build_o09_realm_runtime.py, then configures the SAME tree for
MSVC/Ninja with the Phase-2c dependency prefix (vcpkg x64-windows-static
OpenSSL/Boost/zlib + the repo-pinned SQLite amalgamation) and builds
pocket_realmd_runtime.dll + pocket_world_runtime.dll.

Differences from the o09 lane that this driver owns:
  - toolchain: vcvars64 + cl.exe instead of the NDK
  - dependency prefix: native/.deps/prefix-win-x86_64 (+ the vcpkg install
    tree) instead of the Android prefixes
  - backend: SQLITE only (the Windows v1 lane; fail-loud like o09)
  - staging gates: PE (DLL exists + JNI export tables) instead of ELF
    DT_NEEDED/page-size checks; jniLibs staging and the Android lockfiles
    are untouched
  - the submodule is left byte-pristine after every run (same invariant)

Outputs land in native/.build-win-x86_64/.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import build_o09_realm_runtime as o09  # noqa: E402

BUILD = ROOT / "native" / ".build-win-x86_64"
VCPKG_INSTALLED = ROOT / "native" / "win-deps" / "vcpkg_installed" / "x64-windows-static-md"
WIN_PREFIX = ROOT / "native" / ".deps" / "prefix-win-x86_64"

EXTRACTOR_TARGETS = ["ad", "vmap_extractor", "vmap_assembler", "MoveMapGen"]


def which_cmake() -> str:
    found = subprocess.run(["where", "cmake"], capture_output=True, text=True)
    path = (found.stdout or "").strip().splitlines()
    assert path, "cmake not on PATH"
    return path[0]


CMAKE = which_cmake()

VSWHERE = Path(
    os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")
) / "Microsoft Visual Studio" / "Installer" / "vswhere.exe"


def find_vcvars() -> Path:
    found = subprocess.run(
        [str(VSWHERE), "-latest", "-products", "*", "-property", "installationPath"],
        capture_output=True, text=True, check=True, timeout=30)
    install = (found.stdout or "").strip().splitlines()
    assert install, "vswhere found no Visual Studio installation"
    vcvars = Path(install[0]) / "VC" / "Auxiliary" / "Build" / "vcvars64.bat"
    assert vcvars.is_file(), f"vcvars64.bat missing under {install[0]}"
    return vcvars


def run(cmd, **kwargs):
    print(f"+ {' '.join(str(c) for c in cmd)}", flush=True)
    return subprocess.run([str(c) for c in cmd], check=True, **kwargs)


def output(cmd, cwd: Path) -> str:
    return subprocess.run([str(c) for c in cmd], cwd=str(cwd), capture_output=True,
                          text=True, check=True).stdout.strip()


def dependency_preflight() -> None:
    required = [
        VCPKG_INSTALLED / "include" / "openssl" / "ssl.h",
        VCPKG_INSTALLED / "lib" / "libssl.lib",
        VCPKG_INSTALLED / "lib" / "libcrypto.lib",
        # This vcpkg generation ships zlib as zs.lib.
        VCPKG_INSTALLED / "lib" / "zs.lib",
        WIN_PREFIX / "lib" / "sqlite3.lib",
        WIN_PREFIX / "include" / "sqlite3.h",
    ]
    missing = [str(p) for p in required if not p.is_file()]
    if missing:
        raise RuntimeError(
            "Windows dependencies missing; run scripts/build_win_deps.py first:\n  "
            + "\n  ".join(missing)
        )


def configure_command(extractors: bool = False) -> list[str]:
    cmangos = ROOT / "native" / "cmangos"
    deps = VCPKG_INSTALLED
    sqlite_prefix = WIN_PREFIX
    return [
        CMAKE, "-S", cmangos, "-B", BUILD,
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DBUILD_GAME_SERVER=ON", "-DBUILD_LOGIN_SERVER=ON", "-DBUILD_SCRIPTDEV=ON",
        f"-DBUILD_EXTRACTORS={'ON' if extractors else 'OFF'}",
        "-DBUILD_PLAYERBOTS=ON", "-DBUILD_AHBOT=OFF",
        "-DBUILD_DEPRECATED_PLAYERBOT=OFF", "-DBUILD_POCKET_RUNTIME=ON",
        f"-DPOCKET_RUNTIME_DIR={ROOT / 'native' / 'realm-runtime'}",
        # Fail-loud backend selection: SQLITE is the real switch (same
        # contract as the o09 sqlite lane).
        "-DSQLITE=ON",
        f"-DOPENSSL_ROOT_DIR={deps}",
        f"-DOPENSSL_INCLUDE_DIR={deps / 'include'}",
        f"-DOPENSSL_SSL_LIBRARY={deps / 'lib' / 'libssl.lib'}",
        f"-DOPENSSL_CRYPTO_LIBRARY={deps / 'lib' / 'libcrypto.lib'}",
        "-DBoost_USE_STATIC_LIBS=ON", "-DBoost_USE_STATIC_RUNTIME=ON",
        f"-DCMAKE_PREFIX_PATH={deps}",
        # Explicit sqlite paths like every other dep: the pinned amalgamation
        # is the only acceptable source.
        f"-DSQLite3_INCLUDE_DIR={sqlite_prefix / 'include'}",
        f"-DSQLite3_LIBRARY={sqlite_prefix / 'lib' / 'sqlite3.lib'}",
        # This vcpkg generation ships zlib as zs.lib; FindZLIB would never
        # discover it by name, so the paths are explicit like every other dep.
        f"-DZLIB_LIBRARY={deps / 'lib' / 'zs.lib'}",
        f"-DZLIB_INCLUDE_DIR={deps / 'include'}",
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
        # The DO_SQLITE overlay headers reach every target through
        # QueryResultSqlite.h; the UNIX lane attaches SQLite3_INCLUDE_DIRS
        # in the cmangos root, the Windows lane carries it globally (same
        # pattern as the o09 connector include).
        f"-DCMAKE_CXX_FLAGS=/I{WIN_PREFIX / 'include'}",
    ]


def in_msvc_env(vcvars: Path, body: str, log: Path) -> None:
    log.parent.mkdir(parents=True, exist_ok=True)
    script = log.with_suffix(".bat")
    # write_bytes: text mode would translate each \r\n into \r\n\r\n on
    # Windows, which cmd.exe tolerates for simple lines but breaks on
    # goto/label blocks.
    script.write_bytes(
        (
            "@echo off\r\n"
            # A MinGW ccache on PATH intercepts cl.exe; keep compiles uncached
            # so iteration evidence is always the real compiler's.
            "set CCACHE_DISABLE=1\r\n"
            f'call "{vcvars}" >nul 2>&1\r\n'
            f"cd /d {BUILD}\r\n"
            f"{body}\r\n"
            "exit /b %ERRORLEVEL%\r\n"
        ).encode("ascii")
    )
    result = subprocess.run(["cmd", "/c", str(script)])
    if result.returncode != 0:
        tail = ""
        if log.is_file():
            tail = log.read_text(encoding="utf-8", errors="replace")[-4000:]
        raise RuntimeError(f"MSVC cmake failed (exit {result.returncode}):\n{tail}")


def jni_symbols_from_shim(shim: Path) -> list[str]:
    """Every JNI export the Kotlin shim declares, derived from its own
    `external fun` declarations (package + file stem + function name, per
    JNI name mangling) — the gate can never drift behind the shim."""
    text = shim.read_text(encoding="utf-8")
    package = re.search(r"^\s*package\s+([\w.]+)", text, re.MULTILINE)
    assert package, f"no package declaration in {shim}"
    prefix = "Java_" + package.group(1).replace(".", "_") + "_" + shim.stem + "_"
    names = re.findall(r"\bexternal\s+fun\s+(\w+)", text)
    assert names, f"no external fun declarations in {shim}"
    return [prefix + name for name in names]


def verify_jni_exports(vcvars: Path, dll: Path, symbols: list[str]) -> None:
    """PE staging gate: every JNI export the Kotlin shim declares must be
    present in the DLL's export table (dumpbin /exports, which only exists
    inside the MSVC environment). A missing export must fail HERE, not as a
    runtime UnsatisfiedLinkError three phases later."""
    work = BUILD / "verify"
    work.mkdir(parents=True, exist_ok=True)
    script = work / f"{dll.stem}-dumpbin.bat"
    script.write_text(
        "@echo off\r\n"
        f'call "{vcvars}" >nul 2>&1\r\n'
        f'dumpbin /exports "{dll}"\r\n',
        encoding="ascii",
    )
    result = subprocess.run(["cmd", "/c", str(script)], capture_output=True, text=True)
    # Export table rows list the symbol as the last whitespace-separated
    # token (ordinal hint RVA name); other lines never end in a Java_ token.
    exports = {line.split()[-1] for line in result.stdout.splitlines()
               if line.split() and line.split()[-1].startswith("Java_")}
    assert exports, f"{dll.name}: dumpbin reported no JNI exports at all"
    missing = []
    for symbol in symbols:
        if symbol in exports:
            continue
        # Overloaded externals mangle as <name>__<signature>; a prefix hit
        # is still a bound method.
        if not any(export.startswith(symbol + "__") for export in exports):
            missing.append(symbol)
    assert not missing, f"{dll.name} missing JNI exports: {missing}"


def verify_backend_selection(configure_stdout: str) -> None:
    """Same two-leg evidence contract as o09.verify_backend_selection, read
    from THIS lane's build graph (the o09 helper is hardwired to the Android
    build dir): the fail-loud status line, then the DO_SQLITE define in the
    generated Ninja graph."""
    status = None
    for line in configure_stdout.splitlines():
        if "Pocket Realm database backend:" in line:
            status = line.split("Pocket Realm database backend:", 1)[1].strip()
    if status is None or not status.startswith("SQLITE"):
        raise RuntimeError(
            f"backend selection not verified: configure status={status!r}, "
            "expected SQLITE (the fail-loud CMake overlay is required "
            "evidence; never trust a SQLITE claim without it)")
    build_ninja = BUILD / "build.ninja"
    if not build_ninja.is_file():
        raise RuntimeError(f"backend selection not verified: {build_ninja} missing")
    ninja = build_ninja.read_text(encoding="utf-8", errors="replace")
    if "-DDO_SQLITE" not in ninja:
        raise RuntimeError(f"-DDO_SQLITE absent from {build_ninja}")
    if "-DDO_MYSQL" in ninja:
        raise RuntimeError(f"-DDO_MYSQL unexpectedly present in {build_ninja}")


def main() -> int:
    if os.name != "nt":
        print("windows-only lane", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configure-only", action="store_true")
    parser.add_argument("--extractors", action="store_true",
                        help="build the four data extractors (ad, vmap_extractor, "
                             "vmap_assembler, MoveMapGen) instead of the runtimes")
    parser.add_argument("--force", action="store_true",
                        help="wipe the build directory before configuring")
    args = parser.parse_args()

    dependency_preflight()

    cmangos = ROOT / "native" / "cmangos"
    tracked_dirty = subprocess.run(["git", "diff", "--quiet"], cwd=cmangos).returncode != 0 or \
        subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=cmangos).returncode != 0
    untracked = output(["git", "ls-files", "--others", "--exclude-standard"], cmangos)
    if tracked_dirty or untracked:
        raise RuntimeError(
            "CMaNGOS submodule has unrecorded changes; clean it before building "
            "(git -C native/cmangos checkout -- . && git -C native/cmangos clean -fd)"
        )

    # The o09 staging machinery keyed to the sqlite backend.
    o09.BACKEND = "sqlite"
    try:
        # Inside the try: an anchor edit that throws mid-overlay must
        # still hit the finally-restore below, not strand a dirty submodule.
        o09.prepare_cmangos_source()
        if args.force:
            shutil.rmtree(BUILD, ignore_errors=True)
        BUILD.mkdir(parents=True, exist_ok=True)
        vcvars = find_vcvars()
        # Quote the cmake path inside the generated batch (Program Files).
        cmd = configure_command(extractors=args.extractors)
        configure_body = f'"{cmd[0]}" ' + " ".join(str(c) for c in cmd[1:])
        configure_body += f' > "{BUILD / "configure.log"}" 2>&1'
        in_msvc_env(vcvars, configure_body, BUILD / "configure.log")
        capture = (BUILD / "configure.log").read_text(encoding="utf-8", errors="replace")
        verify_backend_selection(capture)
        if args.configure_only:
            print(f"configure-only: backend=sqlite verified in {BUILD}")
            return 0
        if args.extractors:
            build_body = (
                f'"{CMAKE}" --build "{BUILD}" ' +
                " ".join(f"--target {target}" for target in EXTRACTOR_TARGETS) +
                f' -j {os.cpu_count() or 4} ' +
                f'> "{BUILD / "extractors.log"}" 2>&1'
            )
            in_msvc_env(vcvars, build_body, BUILD / "extractors.log")
            exes = [BUILD / "bin/x64_Release/Extractors" / f"{target}.exe"
                    for target in EXTRACTOR_TARGETS]
            for exe in exes:
                assert exe.is_file(), f"expected extractor missing: {exe}"
            print("windows extractors built:\n" + "\n".join(f"  {exe}" for exe in exes))
            return 0
        build_body = (
            f'"{CMAKE}" --build "{BUILD}" --target pocket_realmd_runtime '
            f'pocket_world_runtime -j {os.cpu_count() or 4} '
            f'> "{BUILD / "build.log"}" 2>&1'
        )
        in_msvc_env(vcvars, build_body, BUILD / "build.log")

        realmd_dll = BUILD / "pocket-runtime-build" / "pocket_realmd_runtime.dll"
        world_dll = BUILD / "pocket-runtime-build" / "pocket_world_runtime.dll"
        for dll in (realmd_dll, world_dll):
            assert dll.is_file(), f"expected DLL missing: {dll}"
        verify_jni_exports(vcvars, realmd_dll, jni_symbols_from_shim(
            ROOT / "android/app/src/main/java/com/pocketrealm/server/RealmNative.kt"))
        verify_jni_exports(vcvars, world_dll, jni_symbols_from_shim(
            ROOT / "android/app/src/main/java/com/pocketrealm/server/WorldNative.kt"))
        print(f"windows realm runtimes built:\n  {realmd_dll}\n  {world_dll}")
        return 0
    finally:
        o09.restore_cmangos_source()
        leftover = output(["git", "status", "--porcelain"], cmangos)
        if leftover and sys.exc_info()[0] is None:
            # Only raise when no exception is already in flight; masking
            # the original failure with the drift report hides its cause.
            raise RuntimeError(f"post-build submodule drift (restore missed a file):\n{leftover}")
        if leftover:
            print(f"WARNING: post-build submodule drift after failure:\n{leftover}",
                  file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
