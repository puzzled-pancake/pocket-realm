#!/usr/bin/env python3
"""Build the Pocket Realm realm runtimes as Windows DLLs with MSVC.

The Windows-port native lane. Reuses the o09 driver's source
staging verbatim — pinned submodule commits, the playerbots CMake mirror,
anchor-verified overlays, sqlite hardening, db null guards — by importing
tools/build_o09_realm_runtime.py, then configures the SAME tree for
MSVC/Ninja with the vcpkg dependency prefix (x64-windows-static
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
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import build_o09_realm_runtime as o09  # noqa: E402
from common import pe_info, sha256_file  # noqa: E402

BUILD = ROOT / "native" / ".build-win-x86_64"
VCPKG_INSTALLED = ROOT / "native" / "win-deps" / "vcpkg_installed" / "x64-windows-static-md"
WIN_PREFIX = ROOT / "native" / ".deps" / "prefix-win-x86_64"
SEAM_BUILD = ROOT / "native" / ".build-win-x86_64" / "sqlite-seam-build"

WIN_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-sqlite-win.json"
SIBLING_SQLITE_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-sqlite.json"

# Every runtime-loaded PE native the app image carries (the two realm DLLs
# plus the SQLite seam). Build tools (extractors) stay unpinned here: they
# never ride into the image.
LOCKFILE_ARTIFACTS = [
    BUILD / "pocket-runtime-build" / "pocket_realmd_runtime.dll",
    BUILD / "pocket-runtime-build" / "pocket_world_runtime.dll",
    SEAM_BUILD / "pocket_sqlite.dll",
]

# Expected machine type (AMD64) for every lane artifact — a wrong-arch DLL
# must fail the lockfile write, not a far-off loadLibrary.
PE_MACHINE_X64 = 0x8664

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
        # win-static-openssl-only overlay: route cmangos's OpenSSL linkage at
        # the pinned vcpkg STATIC libs. Without this the submodule's shipped
        # dep/lib prebuilt IMPORT libs ride the link line and late playerbots
        # references silently import dynamic libssl-3-x64.dll — a DLL the app
        # image does not carry.
        f"-DPOCKET_REALM_WIN_DEPS={deps}",
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


def write_lockfile() -> None:
    """Record the Windows lane's reviewed state into
    schemas/realm-runtime-lockfile-sqlite-win.json.

    The Windows twin of the android lanes' lockfile: source pins (commits,
    overlay registries, patches_content) mirror the o09 sqlite lane exactly
    (same engine-fix set on both platforms), the artifact pins carry the
    freshly built PE bytes with their IMPORT TABLES where the android
    lockfiles pin ELF DT_NEEDED — the table that exposed the shipped
    dynamic-OpenSSL import libs. Seed transcript pins are copied from the
    android x86_64 sqlite lockfile and cross-checked against the
    append-only seed baseline; the transcripts are ABI-independent bytes.

    Byte pins describe THIS machine's build outputs (untracked, like every
    lane's staging). CI runs the pins-only pytest coherence gate
    (tests/test_win_lockfile.py) — the android lanes' documented
    pins-only-CI posture — while a lane rebuild on the dev box rewrites
    this file and refreshes desktop BuildConfig's runtime telltale.
    """
    sibling = json.loads(SIBLING_SQLITE_LOCKFILE.read_text(encoding="utf-8"))
    seed_pins = sibling.get("seed_transcripts")
    baseline = json.loads((ROOT / "schemas" / "sqlite-seed-baseline.json")
                          .read_text(encoding="utf-8"))
    digests = baseline.get("transcript_digests") or {}
    for name, pin in (seed_pins or {}).items():
        expected = digests.get(name)
        if not expected or pin.get("sha256") != expected:
            raise RuntimeError(
                f"seed pin mismatch for {name}: sqlite sibling lockfile "
                f"{pin.get('sha256')} != baseline {expected}; refusing to "
                "record incoherent seed pins in the win lockfile")
    artifacts = []
    for artifact in LOCKFILE_ARTIFACTS:
        if not artifact.is_file():
            raise RuntimeError(
                f"cannot write the win lockfile: {artifact} is missing - "
                "run the lane build (and the seam build) first")
        info = pe_info(artifact)
        if info["machine"] != PE_MACHINE_X64:
            raise RuntimeError(
                f"{artifact.name}: expected x64 PE (machine "
                f"{PE_MACHINE_X64:#x}), found {info['machine']:#x}")
        artifacts.append({
            "path": artifact.relative_to(ROOT).as_posix(),
            "size": artifact.stat().st_size,
            "sha256": sha256_file(artifact),
            "pe_machine": f"{info['machine']:#x}",
            "pe_imports": info["dependents"],
        })
    stamp = WIN_PREFIX / "lib" / "sqlite3.lib.amalgamation-sha256"
    record = {
        "schema": 1,
        "abi": "windows-x86_64",
        "database_backend": "sqlite",
        "cmangos_commit": o09.CMANGOS_COMMIT,
        "playerbots_commit": o09.PLAYERBOTS_COMMIT,
        "cmangos_source_overlays": [
            dict(entry) for entry in o09.CMANGOS_OVERLAYS
            if "sqlite" in entry.get("backends", ("mysql", "sqlite"))],
        "playerbots_source_overlays": o09.PLAYERBOTS_OVERLAYS,
        "patches_content": o09.patches_content_digests(),
        "sqlite_amalgamation": {
            "version": _sqlite_version(),
            # The reviewed sources.json pin is authoritative; the optional
            # local build stamp is the cross-check when present.
            "sqlite3_c_sha256": _amalgamation_pin(),
            "staged_lib_sha256": stamp.read_text(encoding="ascii").strip()
            if stamp.is_file() else None,
        },
        "artifacts": artifacts,
        "seed_transcripts": seed_pins,
        "toolchain": {
            "generator": "Ninja",
            "openssl": "vcpkg x64-windows-static-md (static; "
                       "win-static-openssl-only overlay keeps the shipped "
                       "dep/lib import libs off the link line)",
            "notable_imports": {
                # Windows-bundled (load-safe everywhere); pinned because a
                # change here still deserves to be loud.
                "dbgeng.dll": "WheatyExceptionReport crash reporter in the "
                              "cmangos Windows platform layer",
            },
        },
    }
    WIN_LOCKFILE.write_bytes((json.dumps(record, indent=2) + "\n").encode("utf-8"))
    world = next(a for a in artifacts if a["path"].endswith("pocket_world_runtime.dll"))
    print(f"wrote {WIN_LOCKFILE.relative_to(ROOT)}")
    print(
        "desktop BuildConfig telltale (keep BuildConfig.kt in sync):\n"
        f"  NATIVE_RUNTIME_BUILD_ID = \"win-x86_64-sqlite-cmangos-"
        f"{o09.CMANGOS_COMMIT[:8]}-playerbots-{o09.PLAYERBOTS_COMMIT[:8]}-"
        f"{world['sha256'][:12]}\"")


def _sqlite_version() -> str:
    script = ROOT / "scripts" / "build_win_deps.py"
    match = re.search(r'SQLITE_VERSION\s*=\s*"([^"]+)"', script.read_text(encoding="utf-8"))
    if not match:
        raise RuntimeError("cannot read SQLITE_VERSION from scripts/build_win_deps.py")
    return match.group(1)


def _amalgamation_pin() -> str:
    """The reviewed sqlite3.c sha256 from schemas/sources.json (the pin the
    amalgamation tripwire test already enforces)."""
    sources = json.loads((ROOT / "schemas" / "sources.json").read_text(encoding="utf-8"))
    for source in sources.get("sources", []):
        if "sqlite-amalgamation" in str(source.get("id", "")):
            content = source.get("content_sha256")
            if isinstance(content, dict) and content.get("sqlite3.c"):
                return content["sqlite3.c"]
            if isinstance(content, str) and content:
                return content
    raise RuntimeError("schemas/sources.json has no sqlite-amalgamation content pin")


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
    parser.add_argument("--write-lockfile", action="store_true",
                        help="record the freshly built PE artifacts (with import "
                             "tables) and the current source pins into "
                             "schemas/realm-runtime-lockfile-sqlite-win.json")
    parser.add_argument("--force", action="store_true",
                        help="wipe the build directory before configuring")
    args = parser.parse_args()

    if args.write_lockfile:
        write_lockfile()
        return 0

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
            # Wipe THIS lane's outputs but keep sqlite-seam-build/ (built by
            # tools/build_win_sqlite_seam.py into the same parent): a forced
            # runtime rebuild must not strand write_lockfile() without
            # pocket_sqlite.dll after an hours-long DLL build.
            seam = BUILD / "sqlite-seam-build"
            if BUILD.is_dir():
                for entry in BUILD.iterdir():
                    if entry != seam:
                        shutil.rmtree(entry, ignore_errors=True)                             if entry.is_dir() else entry.unlink()
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
        # A successful lane build is exactly when the lockfile should be
        # refreshed — the recorded bytes and the staged bytes can then never
        # disagree on the dev box.
        write_lockfile()
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
