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
  - backend: --database-backend selects the lane (DESK-78). sqlite (the
    default, the Windows v1 posture) builds the in-tree DO_SQLITE backend
    fail-loud like o09; mariadb builds the same DLLs against the MariaDB
    Connector/C client for the staged mariadbd engine lane and verifies the
    provider distribution's staging (fail-loud when absent)
  - staging gates: PE (DLL exists + JNI export tables) instead of ELF
    DT_NEEDED/page-size checks; jniLibs staging and the Android lockfiles
    are untouched
  - the submodule is left byte-pristine after every run (same invariant)

Outputs land in native/.build-win-x86_64[-{flavor}][-mariadb]/: the
mariadb backend gets its own build root (select_win_flavor) so the two
backends' same-named DLLs never clobber each other; only the sqlite
vanilla lane keeps the historical unsuffixed root. The extractors are
the one exception: their home (EXTRACTORS_BUILD) never carries the
backend suffix — backend-agnostic build tools resolve from one shared
root (same law as the seam DLL and the provider staging).
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
try:
    from tools import flavor_layout
except ImportError:  # direct execution: python tools/<script>.py
    import flavor_layout
from common import pe_info, sha256_file  # noqa: E402

BUILD = ROOT / "native" / ".build-win-x86_64"
VCPKG_INSTALLED = ROOT / "native" / "win-deps" / "vcpkg_installed" / "x64-windows-static-md"
WIN_PREFIX = ROOT / "native" / ".deps" / "prefix-win-x86_64"
# The seam DLL is the flavor-agnostic SQLite engine + JNI surface (decision
# D37): one physical artifact shared by every flavor's lane, so SEAM_BUILD
# does NOT carry the flavor suffix even when BUILD does (select_win_flavor).
SEAM_BUILD = ROOT / "native" / ".build-win-x86_64" / "sqlite-seam-build"
# The extractors' home: backend-UNsuffixed like SEAM_BUILD and the provider
# staging (backend-agnostic artifacts keep the shared root), because that is
# the fixed path BOTH documented consumers resolve — tools/win_prepare_data.py
# (EXTRACTORS) and the desktop DataPreparationStore dev walk — while the
# backend-suffixed BUILD root is a lockfile artifact home no extractor
# consumer ever scans. select_win_flavor re-derives it with the flavor
# suffix (extractor sources are per-flavor client trees).
EXTRACTORS_BUILD = ROOT / "native" / ".build-win-x86_64"

WIN_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-sqlite-win.json"
SIBLING_SQLITE_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-sqlite.json"


def select_win_flavor(flavor_id: str, backend: str = "sqlite") -> None:
    """Route THIS lane's pins, source tree, build root, and lockfile per
    flavor AND backend (the naming schema of record is flavor_layout; the
    fail-loud pin verification is o09.select_flavor). Vanilla resolves to the
    historical constants byte-for-byte; tbc/wotlk get -{flavor}-suffixed
    build roots and lockfiles. The mariadb backend appends its own
    -mariadb build-root suffix (mirroring the flavor suffix): the two
    backends' DLLs are DIFFERENT bytes under the SAME output names
    (pocket-runtime-build/pocket_world_runtime.dll), so a shared root let
    whichever lane rebuilt last overwrite the other's artifacts — exactly
    one backend's win lockfile could agree with the directory at a time
    (the R3 row-17 bytes leg: the sqlite pin said a7dc8763 while the dir
    held the mariadb rebuild's b4537bd9). Must run before any other lane
    work: every pin mismatch has to
    fail before configure, not after an hours-long DLL build. All paths are
    derived from the module ROOT at call time (never captured at import), so
    the hermetic lane harness can point ROOT at its tmp tree."""
    global BUILD, SEAM_BUILD, EXTRACTORS_BUILD, LOCKFILE_ARTIFACTS, \
        WIN_LOCKFILE, WIN_MARIADB_LOCKFILE
    # Pins + core/bots submodule routing (fail-loud on unregistered/mismatched
    # pins BEFORE any file is touched).
    o09.select_flavor(flavor_id)
    layout = flavor_layout.resolve(flavor_id)
    suffix = "" if flavor_id == "vanilla" else f"-{flavor_id}"
    backend_suffix = "" if backend == "sqlite" else f"-{backend}"
    BUILD = ROOT / "native" / f".build-win-x86_64{suffix}{backend_suffix}"
    # The extractors' home carries the flavor suffix but NEVER the backend
    # suffix: the tools are backend-agnostic, and both consumers
    # (tools/win_prepare_data.py EXTRACTORS; the desktop DataPreparationStore
    # dev walk) resolve the backend-unsuffixed root — routing them into the
    # suffixed lane root stranded them where no consumer scans (the R4
    # cross-tool gap: --extractors --database-backend mariadb used to land
    # them under -mariadb, and win_prepare_data's extractor remedy, repeated
    # with the mariadb flag, reproduced the gap).
    EXTRACTORS_BUILD = ROOT / "native" / f".build-win-x86_64{suffix}"
    # The seam DLL is the flavor-agnostic SQLite engine + JNI surface
    # (decision D37): one physical artifact shared by every flavor's lane —
    # the build root carries the suffix, the seam home never does.
    SEAM_BUILD = ROOT / "native" / ".build-win-x86_64" / "sqlite-seam-build"
    LOCKFILE_ARTIFACTS = [
        BUILD / "pocket-runtime-build" / "pocket_realmd_runtime.dll",
        BUILD / "pocket-runtime-build" / "pocket_world_runtime.dll",
        SEAM_BUILD / "pocket_sqlite.dll",
    ]
    WIN_LOCKFILE = ROOT / layout.sqlite_win_lockfile()
    WIN_MARIADB_LOCKFILE = ROOT / (
        "schemas/realm-runtime-lockfile-mariadb-win.json" if not suffix
        else f"schemas/realm-runtime-lockfile-mariadb-win{suffix}.json")

# DESK-78: the MariaDB backend variant's documents. The mariadb lane never
# touches the sqlite lockfile; it writes its own runtime lockfile plus the
# provider lockfile the desktop engine (DesktopMariaDbProvider) verifies the
# staged distribution against.
WIN_MARIADB_LOCKFILE = ROOT / "schemas" / "realm-runtime-lockfile-mariadb-win.json"
PROVIDER_LOCKFILE = ROOT / "schemas" / "database-provider-lockfile-win.json"
# The staged engine distribution stays under the UNSUFFIXED .build-win-x86_64
# root: it is an INPUT the mariadb lane verifies (never a lane build output),
# and the desktop resolves it from that constant path — the sqlite lane's
# --force wipe spares it for the same reason it spares the seam build.
PROVIDER_STAGING = ROOT / "native" / ".build-win-x86_64" / "mariadb-provider"

# The MariaDB Connector/C (client) inputs the MySQL backend's link needs in
# the Windows prefix. mariadbd/mariadb.exe themselves ride the provider
# staging above, never the compile.
MARIADB_CONNECTOR_INCLUDE = WIN_PREFIX / "include" / "mysql"
MARIADB_CONNECTOR_LIB = WIN_PREFIX / "lib" / "libmariadb.lib"

# Every runtime-loaded PE native the app image carries: the two realm DLLs
# plus the SQLite seam here, and — appended per backend by
# lockfile_artifacts() below — the mariadb lane's connector libmysql.dll
# that packageApp ships beside them (four shipped DLLs, four artifact
# rows). Build tools (extractors) stay unpinned here: they never ride into
# the image.
LOCKFILE_ARTIFACTS = [
    BUILD / "pocket-runtime-build" / "pocket_realmd_runtime.dll",
    BUILD / "pocket-runtime-build" / "pocket_world_runtime.dll",
    SEAM_BUILD / "pocket_sqlite.dll",
]


def lockfile_artifacts(backend: str) -> list[Path]:
    """The lockfile writer's artifact set for a backend: the shared
    three-DLL base plus, on the mariadb lane, the staged MariaDB
    Connector/C client DLL (pocket-runtime-build/libmysql.dll) the realm
    runtimes import and packageApp ships BESIDE them. A fourth shipped DLL
    must carry its own size/sha256/pe_imports row — the runtimes'
    pe_imports pin only that they import its NAME, and the desktop fence
    (NativeRuntimeFence) rehashes artifact ROWS, so an unpinned libmysql.dll
    would be the DESK-115 stale-DLL-ships-silently hole again. The sqlite
    lane never builds or ships it."""
    if backend == "mariadb":
        return LOCKFILE_ARTIFACTS + [
            BUILD / "pocket-runtime-build" / "libmysql.dll"]
    return list(LOCKFILE_ARTIFACTS)

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


def dependency_preflight(backend: str = "sqlite") -> None:
    required = [
        VCPKG_INSTALLED / "include" / "openssl" / "ssl.h",
        VCPKG_INSTALLED / "lib" / "libssl.lib",
        VCPKG_INSTALLED / "lib" / "libcrypto.lib",
        # This vcpkg generation ships zlib as zs.lib.
        VCPKG_INSTALLED / "lib" / "zs.lib",
    ]
    if backend == "sqlite":
        required += [
            WIN_PREFIX / "lib" / "sqlite3.lib",
            WIN_PREFIX / "include" / "sqlite3.h",
        ]
    else:
        # The MySQL backend links the MariaDB Connector/C client library;
        # the fail-loud remedy names the missing inputs exactly.
        required += [
            MARIADB_CONNECTOR_INCLUDE / "mysql_version.h",
            MARIADB_CONNECTOR_LIB,
        ]
    missing = [str(p) for p in required if not p.is_file()]
    if missing:
        if backend == "sqlite":
            raise RuntimeError(
                "Windows dependencies missing; run scripts/build_win_deps.py first:\n  "
                + "\n  ".join(missing)
            )
        raise RuntimeError(
            "Windows MariaDB backend dependencies missing (stage the "
            "MariaDB Connector/C into the win prefix):\n  " + "\n  ".join(missing)
        )


def configure_command(extractors: bool = False, backend: str = "sqlite") -> list[str]:
    # The active flavor's core tree (o09.select_flavor routed it; vanilla
    # resolves to native/cmangos exactly as before).
    cmangos = ROOT / o09.ACTIVE_CORE_SUBMODULE
    deps = VCPKG_INSTALLED
    # The root this configure targets: the extractors graph routes to the
    # backend-unsuffixed EXTRACTORS_BUILD home (its consumers' fixed path);
    # every runtime configure keeps the lane's backend-suffixed BUILD root.
    build_root = EXTRACTORS_BUILD if extractors else BUILD
    if backend == "mariadb":
        return [
            CMAKE, "-S", cmangos, "-B", build_root,
            "-G", "Ninja",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
            "-DBUILD_GAME_SERVER=ON", "-DBUILD_LOGIN_SERVER=ON", "-DBUILD_SCRIPTDEV=ON",
            f"-DBUILD_EXTRACTORS={'ON' if extractors else 'OFF'}",
            "-DBUILD_PLAYERBOTS=ON", "-DBUILD_AHBOT=OFF",
            "-DBUILD_DEPRECATED_PLAYERBOT=OFF", "-DBUILD_POCKET_RUNTIME=ON",
            f"-DPOCKET_RUNTIME_DIR={ROOT / 'native' / 'realm-runtime'}",
            # DESK-78's MariaDB backend variant: SQLITE stays OFF (MySQL is
            # the fail-loud overlay's default), so the DLLs link the MariaDB
            # Connector/C client and speak the wire protocol to the staged
            # mariadbd instead of opening the DO_SQLITE datadir in-process.
            # Explicit connector paths like every other dep — FindMySQL must
            # never guess. SQLITE=OFF is passed EXPLICITLY (not left to the
            # fresh-cache default): the SQLITE cache variable is the overlay's
            # real switch, and the extractors leg configures the
            # backend-unsuffixed EXTRACTORS_BUILD root whose cache a prior
            # sqlite lane run left at SQLITE=ON — an implicit default would
            # inherit that stale selection.
            "-DSQLITE=OFF",
            f"-DMYSQL_INCLUDE_DIR={MARIADB_CONNECTOR_INCLUDE}",
            f"-DMYSQL_LIBRARY={MARIADB_CONNECTOR_LIB}",
            # realm-runtime's WIN32 block runs find_package(SQLite3 REQUIRED)
            # unconditionally (the fail-loud backend overlay wants the find
            # to succeed even when the MYSQL lane links none of it), so the
            # mariadb lane passes the SAME pinned-amalgamation paths as the
            # sqlite lane — without them the mariadb configure dies in
            # FindSQLite3 (proven live 2026-10-07: the connector-staged
            # prefix alone is not a SQLite3 discovery source).
            f"-DSQLite3_INCLUDE_DIR={WIN_PREFIX / 'include'}",
            f"-DSQLite3_LIBRARY={WIN_PREFIX / 'lib' / 'sqlite3.lib'}",
            f"-DPOCKET_REALM_WIN_DEPS={deps}",
            f"-DOPENSSL_ROOT_DIR={deps}",
            f"-DOPENSSL_INCLUDE_DIR={deps / 'include'}",
            f"-DOPENSSL_SSL_LIBRARY={deps / 'lib' / 'libssl.lib'}",
            f"-DOPENSSL_CRYPTO_LIBRARY={deps / 'lib' / 'libcrypto.lib'}",
            "-DBoost_USE_STATIC_LIBS=ON", "-DBoost_USE_STATIC_RUNTIME=ON",
            f"-DCMAKE_PREFIX_PATH={deps}",
            f"-DZLIB_LIBRARY={deps / 'lib' / 'zs.lib'}",
            f"-DZLIB_INCLUDE_DIR={deps / 'include'}",
            "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
        ]
    sqlite_prefix = WIN_PREFIX
    return [
        CMAKE, "-S", cmangos, "-B", build_root,
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


def in_msvc_env(vcvars: Path, body: str, log: Path, cwd: Path | None = None) -> None:
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
            f'cd /d "{cwd or BUILD}"\r\n'
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


def write_lockfile(backend: str = "sqlite") -> None:
    """Record the lane's reviewed state into the backend's lockfile
    (schemas/realm-runtime-lockfile-{sqlite,mariadb}-win.json).

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
    lockfile = WIN_LOCKFILE if backend == "sqlite" else WIN_MARIADB_LOCKFILE
    # Review round 2 (R2-2 medium): the sibling is flavor-resolved with a
    # fallback — once a flavor's own android sqlite lane has staged, its
    # lockfile carries the flavor's own seed_transcripts pins; until then
    # the vanilla sibling is the only source, and the placeholder note
    # below says exactly that (and names the producing command).
    flavor_id = o09.ACTIVE_FLAVOR
    flavor_sibling = (ROOT / "schemas"
                      / f"realm-runtime-lockfile-sqlite-{flavor_id}.json")
    sibling_path = (flavor_sibling if flavor_id != "vanilla"
                    and flavor_sibling.is_file() else SIBLING_SQLITE_LOCKFILE)
    sibling = json.loads(sibling_path.read_text(encoding="utf-8"))
    seed_pins = sibling.get("seed_transcripts")
    # Round-2 R2-1a: a NON-vanilla flavor's lockfile must never present
    # vanilla transcripts as its own pinned seed state silently. Bind to
    # the flavor's OWN baseline when it carries transcript digests; while
    # it does not (W2/W5 measure them), record an explicit placeholder
    # note naming the retiring mechanism — the provenance record says what
    # the bytes are, not what the lane wishes they were.
    flavor_baseline_path = (
        ROOT / "schemas" / "sqlite-seed-baseline.json" if flavor_id == "vanilla"
        else ROOT / "schemas" / f"sqlite-seed-baseline-{flavor_id}.json"
    )
    flavor_baseline = (
        json.loads(flavor_baseline_path.read_text(encoding="utf-8"))
        if flavor_baseline_path.is_file() else {}
    )
    flavor_digests = flavor_baseline.get("transcript_digests") or {}
    carrying_vanilla_transcripts = flavor_id != "vanilla" and not flavor_digests
    if carrying_vanilla_transcripts:
        baseline = json.loads((ROOT / "schemas" / "sqlite-seed-baseline.json")
                              .read_text(encoding="utf-8"))
        digests = baseline.get("transcript_digests") or {}
    else:
        digests = flavor_digests
    for name, pin in (seed_pins or {}).items():
        expected = digests.get(name)
        if not expected or pin.get("sha256") != expected:
            raise RuntimeError(
                f"seed pin mismatch for {name}: sqlite sibling lockfile "
                f"{pin.get('sha256')} != baseline {expected}; refusing to "
                "record incoherent seed pins in the win lockfile")
    # The flavor-resolved overlay set — the same record AND gate as the
    # android lane's writer (o09.overlays_for): a flavor whose per-flavor
    # overlay set is not registered (wotlk before its S2 re-derivation) is
    # refused here, before any artifact bytes are read, so this lane can
    # never record an unreviewed overlay table as reviewed lockfile state.
    overlays = [dict(entry) for entry in o09.overlays_for(o09.ACTIVE_FLAVOR, backend)]
    artifacts = []
    for artifact in lockfile_artifacts(backend):
        if not artifact.is_file():
            remedy = "run the lane build (and the seam build) first"
            if artifact.name == CONNECTOR_CLIENT_DLL:
                # The connector DLL is never built by the lane: it is staged
                # from the verified provider distribution. Name the producer,
                # not a build that cannot produce the file.
                remedy = (
                    "the mariadb lane stages this DLL from the verified "
                    "provider distribution's lib/libmariadb.dll "
                    "(write_lane_lockfiles does this; a missing copy means "
                    "the provider staging or its runtime-manifest "
                    "client_library pin is broken - restage the qualified "
                    "MariaDB Windows build)")
            raise RuntimeError(
                f"cannot write the win lockfile: {artifact} is missing - "
                f"{remedy}")
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
        "database_backend": backend,
        "flavor": o09.ACTIVE_FLAVOR,
        "cmangos_commit": o09.ACTIVE_CMANGOS_COMMIT,
        "playerbots_commit": o09.ACTIVE_PLAYERBOTS_COMMIT,
        "cmangos_source_overlays": overlays,
        "playerbots_source_overlays": o09.PLAYERBOTS_OVERLAYS,
        "patches_content": o09.patches_content_digests(),
        "artifacts": artifacts,
        "seed_transcripts": seed_pins,
        **({
            "seed_transcripts_provenance": {
                "note": (
                    "vanilla-transcripts placeholder: this non-vanilla "
                    "lane seeds the VANILLA classic* transcripts until its "
                    "own per-flavor transcripts are measured and pinned in "
                    "sqlite-seed-baseline-<flavor>.json. The supported "
                    "retire path (decision D59): run `python tools/"
                    "seed_sqlite_from_manifest.py --flavor <flavor> --out "
                    "<dir> --transcripts <dir> --write-baseline` (the "
                    "manifest seeder's merge write — the only tool that "
                    "can produce those keys), restage the lane "
                    "(tools/build_o09_realm_runtime.py --flavor <flavor>), "
                    "then re-key this lockfile; it then binds the "
                    "flavor's own digests and this placeholder retires."),
                "retires": "W2/W5: first per-flavor transcript staging "
                           "re-keys this block (decision D59)",
            },
        } if carrying_vanilla_transcripts else {}),
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
    if backend == "sqlite":
        record["sqlite_amalgamation"] = {
            "version": _sqlite_version(),
            # The reviewed sources.json pin is authoritative; the optional
            # local build stamp is the cross-check when present.
            "sqlite3_c_sha256": _amalgamation_pin(),
            "staged_lib_sha256": stamp.read_text(encoding="ascii").strip()
            if stamp.is_file() else None,
        }
    else:
        # The mariadb lane's runtime lockfile cross-references the provider
        # lockfile (the staged distribution's reviewed bytes) — the two
        # documents are written together by --write-lockfile.
        record["provider_lockfile"] = PROVIDER_LOCKFILE.relative_to(ROOT).as_posix()
        record["provider"] = json.loads(PROVIDER_LOCKFILE.read_text(encoding="utf-8"))
    lockfile.write_bytes((json.dumps(record, indent=2) + "\n").encode("utf-8"))
    world = next(a for a in artifacts if a["path"].endswith("pocket_world_runtime.dll"))
    print(f"wrote {lockfile.relative_to(ROOT)}")
    print(
        "desktop BuildConfig telltale (keep BuildConfig.kt in sync):\n"
        f"  NATIVE_RUNTIME_BUILD_ID = \"win-x86_64-{o09.ACTIVE_FLAVOR}-{backend}-cmangos-"
        f"{o09.ACTIVE_CMANGOS_COMMIT[:8]}-playerbots-{o09.ACTIVE_PLAYERBOTS_COMMIT[:8]}-"
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


def verify_backend_selection(configure_stdout: str, backend: str = "sqlite",
                             build_root: Path | None = None) -> None:
    """Same two-leg evidence contract as o09.verify_backend_selection, read
    from THIS lane's build graph (the o09 helper is hardwired to the Android
    build dir): the fail-loud status line, then the DO_* define in the
    generated Ninja graph — per selected backend. build_root names the tree
    the configure just wrote (the extractors leg configures the
    backend-unsuffixed EXTRACTORS_BUILD home, not the lane's BUILD root)."""
    status = None
    for line in configure_stdout.splitlines():
        if "Pocket Realm database backend:" in line:
            status = line.split("Pocket Realm database backend:", 1)[1].strip()
    if backend == "sqlite":
        expected_status, expected_define, forbidden_define = "SQLITE", "-DDO_SQLITE", "-DDO_MYSQL"
    else:
        expected_status, expected_define, forbidden_define = "MYSQL", "-DDO_MYSQL", "-DDO_SQLITE"
    if status is None or not status.startswith(expected_status):
        raise RuntimeError(
            f"backend selection not verified: configure status={status!r}, "
            f"expected {expected_status} (the fail-loud CMake overlay is required "
            "evidence; never trust a backend claim without it)")
    build_ninja = (build_root or BUILD) / "build.ninja"
    if not build_ninja.is_file():
        raise RuntimeError(f"backend selection not verified: {build_ninja} missing")
    ninja = build_ninja.read_text(encoding="utf-8", errors="replace")
    if expected_define not in ninja:
        raise RuntimeError(f"{expected_define} absent from {build_ninja}")
    if forbidden_define in ninja:
        raise RuntimeError(f"{forbidden_define} unexpectedly present in {build_ninja}")


# ---------------------------------------------------------------------------
# DESK-78: the MariaDB provider distribution's staging gate. The provider
# (mariadbd.exe / mariadb.exe / bootstrap / pinned migration manifest) is a
# PHYSICAL artifact the lane cannot synthesize: when the staging directory is
# absent the variant fails loud with the remedy instead of silently building
# a server lane whose engine could never start.
# ---------------------------------------------------------------------------

PROVIDER_RUNTIME_MANIFEST = "runtime-manifest.json"
PROVIDER_BOOTSTRAP = "bootstrap.sql"
PROVIDER_MIGRATION_MANIFEST = "migration-manifest.json"
PROVIDER_EXECUTABLES = {
    "mariadbd": PROVIDER_STAGING / "bin" / "mariadbd.exe",
    "mariadb": PROVIDER_STAGING / "bin" / "mariadb.exe",
}
# The Connector/C client DLL the qualified provider distribution ships as
# lib/libmariadb.dll. The mariadb lane's realm runtimes import it BY NAME as
# libmysql.dll (the connector's MySQL-compat import name — the runtimes'
# pe_imports pin only the NAME) and the app image ships it beside them, so
# the lane stages its own copy from THIS hash-verified distribution member
# (stage_connector_client_dll): before that producer existed the pinned
# fourth artifact was an untracked manual copy no documented remedy could
# reproduce (a fresh clone or --force finished the hours-long DLL build and
# then died at the lockfile write).
PROVIDER_CLIENT_DLL = "lib/libmariadb.dll"
CONNECTOR_CLIENT_DLL = "libmysql.dll"
WIN_PROVIDER_ID = "mariadb-11.5.2-windows-x86_64"
WIN_PROVIDER_VERSION = "11.5.2"


def provider_staged_files() -> list[Path]:
    files = [
        PROVIDER_STAGING / PROVIDER_RUNTIME_MANIFEST,
        PROVIDER_STAGING / PROVIDER_BOOTSTRAP,
        PROVIDER_STAGING / PROVIDER_MIGRATION_MANIFEST,
        PROVIDER_STAGING / PROVIDER_CLIENT_DLL,
    ]
    files += list(PROVIDER_EXECUTABLES.values())
    return files


def verify_mariadb_provider_staging() -> dict:
    """Verify the staged distribution's identity end-to-end (the desktop
    DesktopMariaDbProvider.verify contract, mirrored build-side): the
    runtime manifest names THIS provider id + abi, every executable record's
    sha256/size match the staged bytes, and the bootstrap digest matches.
    Returns the provider-lockfile record."""
    missing = [str(p) for p in provider_staged_files() if not p.is_file()]
    if missing:
        raise RuntimeError(
            "the Windows MariaDB provider distribution is not staged "
            f"({PROVIDER_STAGING.relative_to(ROOT)}); stage mariadbd.exe, the "
            "mariadb.exe client, bootstrap.sql, the pinned migration "
            "manifest and the Connector/C client DLL (lib/libmariadb.dll) "
            "from the qualified MariaDB Windows build before "
            "building the mariadb lane:\n  " + "\n  ".join(missing)
        )
    manifest = json.loads((PROVIDER_STAGING / PROVIDER_RUNTIME_MANIFEST).read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or manifest.get("provider") != WIN_PROVIDER_ID:
        raise RuntimeError(
            f"staged provider manifest does not name {WIN_PROVIDER_ID}: "
            f"provider={manifest.get('provider')!r}")
    if manifest.get("abi") != "windows-x86_64":
        raise RuntimeError(
            f"staged provider manifest abi {manifest.get('abi')!r} is not windows-x86_64")
    if manifest.get("bootstrap_sha256") != sha256_file(
            PROVIDER_STAGING / PROVIDER_BOOTSTRAP):
        raise RuntimeError("staged provider bootstrap digest mismatch")
    executables = manifest.get("executables") or {}
    if set(executables) != set(PROVIDER_EXECUTABLES):
        raise RuntimeError(
            f"staged provider manifest executables {sorted(executables)} != "
            f"{sorted(PROVIDER_EXECUTABLES)}")
    artifacts = []
    for which, path in PROVIDER_EXECUTABLES.items():
        record = executables[which]
        staged_sha = sha256_file(path)
        if record.get("sha256") != staged_sha or record.get("size") != path.stat().st_size:
            raise RuntimeError(f"staged {which} bytes disagree with the runtime manifest")
        # `name` (staging-relative) is the form the staged runtime manifest's
        # executables records carry and the desktop DesktopMariaDbProvider.
        # verify pin lookup matches; `path` (repo-relative) is provenance.
        # Both are recorded so the desktop verify() can never fail to find
        # the pin for a REAL staged distribution (the manifest's short name
        # can never equal a repo-relative path).
        artifacts.append({
            "name": path.relative_to(PROVIDER_STAGING).as_posix(),
            "path": path.relative_to(ROOT).as_posix(),
            "size": path.stat().st_size,
            "sha256": staged_sha,
        })
        if record.get("name") != path.relative_to(PROVIDER_STAGING).as_posix():
            raise RuntimeError(
                f"staged manifest executables[{which}].name "
                f"{record.get('name')!r} != staging-relative path "
                f"{path.relative_to(PROVIDER_STAGING).as_posix()!r}")
    # The Connector/C client DLL: the same distribution record shape as the
    # executables, because the lane SHIPS it (as libmysql.dll) beside the
    # realm runtimes — its bytes must be pinned by the manifest the lockfile
    # re-records, or the fourth shipped DLL has no reviewed provenance.
    client_dll = PROVIDER_STAGING / PROVIDER_CLIENT_DLL
    client = manifest.get("client_library") or {}
    client_sha = sha256_file(client_dll)
    if (client.get("name") != PROVIDER_CLIENT_DLL
            or client.get("size") != client_dll.stat().st_size
            or client.get("sha256") != client_sha):
        raise RuntimeError(
            f"the staged connector client DLL ({PROVIDER_CLIENT_DLL} - the "
            "libmysql.dll the mariadb lane's realm runtimes import and the "
            "app image ships) disagrees with the runtime manifest's "
            "client_library pin; restage the qualified MariaDB Windows build")
    migration_manifest = json.loads(
        (PROVIDER_STAGING / PROVIDER_MIGRATION_MANIFEST).read_text(encoding="utf-8"))
    if "entries" not in migration_manifest:
        raise RuntimeError("staged migration manifest carries no entries array")
    return {
        "provider": {"id": WIN_PROVIDER_ID, "version": WIN_PROVIDER_VERSION},
        "manifest_sha256": sha256_file(PROVIDER_STAGING / PROVIDER_RUNTIME_MANIFEST),
        "bootstrap_sha256": manifest["bootstrap_sha256"],
        "migration_manifest_sha256": sha256_file(
            PROVIDER_STAGING / PROVIDER_MIGRATION_MANIFEST),
        "migration_count": len(migration_manifest["entries"]),
        "staging_root": PROVIDER_STAGING.relative_to(ROOT).as_posix(),
        "artifacts": artifacts,
        "client_library": {
            "name": PROVIDER_CLIENT_DLL,
            "path": client_dll.relative_to(ROOT).as_posix(),
            "size": client_dll.stat().st_size,
            "sha256": client_sha,
        },
    }


def write_provider_lockfile() -> dict:
    """Record the staged provider's reviewed bytes into
    schemas/database-provider-lockfile-win.json — the document the desktop
    engine verifies the staging against at runtime (staged like the realm
    DLLs with a lockfile).

    Review round 2 (R2-3 medium, decision D60): this document and the
    shared PROVIDER_STAGING root are scoped VANILLA-lane inputs. The
    staged migration manifest inside the shared root is the vanilla ledger
    golden (the G3-3 bytes), so a non-vanilla lane writing here either
    gets refused by the desktop verifier against the vanilla pin or
    silently re-keys the committed vanilla provenance — both wrong. The
    record carries `lane_scope` so the scope is machine-readable, and a
    non-vanilla write refuses loud naming the decision until W1.3 defines
    per-flavor provider staging."""
    flavor_id = o09.ACTIVE_FLAVOR
    if flavor_id != "vanilla":
        raise RuntimeError(
            f"write_provider_lockfile refuses the {flavor_id!r} lane: "
            f"schemas/database-provider-lockfile-win.json and "
            f"native/.build-win-x86_64/mariadb-provider are scoped "
            "VANILLA-lane inputs (decision D60) — the staged migration "
            "manifest inside the shared root is the vanilla ledger golden, "
            "and re-keying this shared pin to a tbc/wotlk migration set "
            "would clobber the committed vanilla provenance. W1.3 owns "
            "per-flavor provider staging; until it lands, a non-vanilla "
            "mariadb lane verifies against its own "
            "realm-runtime-lockfile-mariadb-win-<flavor>.json and never "
            "rewrites the shared provider document")
    record = verify_mariadb_provider_staging()
    record = {
        "schema": 1,
        "abi": "windows-x86_64",
        "database_backend": "mariadb",
        "lane_scope": {
            "flavors": ["vanilla"],
            "decision": "D60",
            "note": (
                "this shared document + the mariadb-provider staging root "
                "are VANILLA-lane inputs: the staged migration manifest is "
                "the vanilla ledger golden (migration_manifest_sha256); "
                "non-vanilla lanes must not write or re-key it until W1.3 "
                "defines per-flavor provider staging"),
        },
        **record,
    }
    PROVIDER_LOCKFILE.write_bytes((json.dumps(record, indent=2) + "\n").encode("utf-8"))
    print(f"wrote {PROVIDER_LOCKFILE.relative_to(ROOT)} "
          f"({record['provider']['id']}, {record['migration_count']} pinned migrations)")
    return record


def stage_connector_client_dll() -> Path:
    """Stage the MariaDB Connector/C client DLL the mariadb lane ships
    beside the realm runtimes: BUILD/pocket-runtime-build/libmysql.dll (the
    MySQL-compat import name the DLLs' PE import tables pin) copied from the
    qualified provider distribution's lib/libmariadb.dll, hash-verified
    against the staged runtime manifest's client_library record — the bytes
    the provider lockfile pins. Self-contained on purpose: the copy never
    trusts the caller's verification order. Before this producer existed
    the shipped DLL was an untracked manual copy, so a fresh clone (or a
    --force wipe) completed the DLL build and then failed the lockfile
    write with a remedy that named no source able to produce the file."""
    source = PROVIDER_STAGING / PROVIDER_CLIENT_DLL
    if not source.is_file():
        raise RuntimeError(
            "the staged MariaDB provider distribution carries no connector "
            f"client DLL ({PROVIDER_STAGING.relative_to(ROOT) / PROVIDER_CLIENT_DLL}); "
            "restage the qualified MariaDB Windows build - the mariadb lane "
            "ships its libmysql.dll from there")
    manifest = json.loads(
        (PROVIDER_STAGING / PROVIDER_RUNTIME_MANIFEST).read_text(encoding="utf-8"))
    pin = manifest.get("client_library") or {}
    source_sha = sha256_file(source)
    if (pin.get("name") != PROVIDER_CLIENT_DLL
            or pin.get("size") != source.stat().st_size
            or pin.get("sha256") != source_sha):
        raise RuntimeError(
            f"the staged connector client DLL ({PROVIDER_CLIENT_DLL}) "
            "disagrees with the runtime manifest's client_library pin; "
            "restage the qualified MariaDB Windows build")
    target = BUILD / "pocket-runtime-build" / CONNECTOR_CLIENT_DLL
    target.parent.mkdir(parents=True, exist_ok=True)
    if not (target.is_file() and sha256_file(target) == source_sha):
        shutil.copyfile(source, target)
        print(f"staged connector client DLL: {target} "
              f"(sha256 {source_sha[:16]}..., from the verified provider "
              "distribution)")
    return target


def write_lane_lockfiles(backend: str = "sqlite") -> None:
    """The lane's lockfile set: the mariadb lane re-verifies + re-records
    the provider distribution alongside the runtime lockfile (write_lockfile
    reads the provider lockfile the line above just wrote) and re-stages the
    connector client DLL the runtime lockfile's fourth artifact row pins —
    every path that writes the mariadb lockfile therefore also produces the
    DLL it pins (full build, --write-lockfile refresh, and the --extractors
    composition alike)."""
    if backend == "mariadb":
        write_provider_lockfile()
        stage_connector_client_dll()
    write_lockfile(backend)


def main() -> int:
    if os.name != "nt":
        print("windows-only lane", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--flavor", default="vanilla", choices=tuple(flavor_layout.FLAVOR_IDS),
        help="flavor lane to build (default vanilla). Routes the pinned core "
             "tree (native/cmangos-{flavor}), the pins from schemas/flavor.json "
             "(fail-loud before configure), the build root "
             "(native/.build-win-x86_64[-{flavor}][-mariadb]) and the runtime "
             "lockfile (realm-runtime-lockfile-{backend}-win[-{flavor}].json). "
             "The SQLite seam DLL is the shared flavor-agnostic artifact "
             "(decision D37).")
    parser.add_argument("--configure-only", action="store_true")
    parser.add_argument(
        "--database-backend", choices=("sqlite", "mariadb"), default="sqlite",
        help="the desktop lane's database backend (DESK-78). sqlite (the "
             "default) keeps the declared v1 posture: the DO_SQLITE in-tree "
             "backend opens the datadir in-process. mariadb builds the SAME "
             "DLLs against the MariaDB Connector/C client for the staged "
             "mariadbd engine lane and verifies the provider distribution's "
             "staging — fail-loud when the distribution is absent. The "
             "mariadb lane builds in its OWN root "
             "(native/.build-win-x86_64-mariadb): the two backends' DLLs are "
             "different bytes under the same names, so a shared root let the "
             "last rebuild win and only one backend's lockfile could match "
             "the directory.")
    parser.add_argument("--extractors", action="store_true",
                        help="build the four data extractors (ad, vmap_extractor, "
                             "vmap_assembler, MoveMapGen) instead of the runtimes. "
                             "The tools are backend-agnostic: whatever "
                             "--database-backend selects, they always stage "
                             "under the backend-UNsuffixed root "
                             "(native/.build-win-x86_64[-{flavor}]/bin/"
                             "x64_Release/Extractors) — the fixed path "
                             "tools/win_prepare_data.py and the desktop "
                             "data-prep lane resolve — while the backend's "
                             "DLLs and lockfiles keep the lane's own root")
    parser.add_argument("--write-lockfile", action="store_true",
                        help="record the freshly built PE artifacts (with import "
                             "tables) and the current source pins into the "
                             "backend's lockfile (sqlite: "
                             "realm-runtime-lockfile-sqlite-win.json; mariadb: "
                             "realm-runtime-lockfile-mariadb-win.json + "
                             "database-provider-lockfile-win.json). Alone this "
                             "only refreshes the lockfiles from the artifacts "
                             "already on disk; with --extractors the flags "
                             "compose — the extractors build runs first and the "
                             "lockfiles are written after it completes")
    parser.add_argument("--force", action="store_true",
                        help="wipe the build directory before configuring")
    args = parser.parse_args()
    backend = args.database_backend

    # Route pins + per-flavor/per-backend paths FIRST: a pin mismatch fails
    # before the provider staging check, the dependency preflight, or any
    # tree touch.
    select_win_flavor(args.flavor, backend)
    # Per-flavor overlay registration gate (the seam lane's
    # resolve_flavor_or_die contract): a flavor whose overlay set is not yet
    # registered (wotlk before its S2 re-derivation) fails loud HERE, before
    # the --write-lockfile short-circuit can record an unreviewed lane
    # state as a committed lockfile.
    o09.overlays_for(args.flavor, backend)

    if args.write_lockfile and not args.extractors:
        # The documented lockfile-only use: refresh the lane's lockfiles from
        # the artifacts already on disk, with no build (the remedy
        # test_win_lockfile.py pins for a drifted BuildConfig telltale).
        # With --extractors the flags compose instead — this leg is skipped
        # and the lockfiles are written AFTER the extractors build completes
        # (LVF-flavor-import-014: the unconditional short-circuit used to
        # drop the --extractors leg silently).
        write_lane_lockfiles(backend)
        return 0

    if backend == "mariadb":
        # The staging gate FIRST: the engine lane's whole purpose is the
        # staged distribution, so an absent distribution fails before the
        # hours-long DLL build, not after it.
        verify_mariadb_provider_staging()

    dependency_preflight(backend)

    cmangos = ROOT / o09.ACTIVE_CORE_SUBMODULE
    tracked_dirty = subprocess.run(["git", "diff", "--quiet"], cwd=cmangos).returncode != 0 or \
        subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=cmangos).returncode != 0
    untracked = output(["git", "ls-files", "--others", "--exclude-standard"], cmangos)
    if tracked_dirty or untracked:
        raise RuntimeError(
            "CMaNGOS submodule has unrecorded changes; clean it before building "
            f"(git -C {o09.ACTIVE_CORE_SUBMODULE} checkout -- . && "
            f"git -C {o09.ACTIVE_CORE_SUBMODULE} clean -fd)"
        )

    # The o09 staging machinery keyed to the selected backend: the mysql
    # lane keeps the submodule's connection layer pristine (the sqlite
    # hardening overlays are sqlite-only; the db null guards + fail-loud
    # backend selection apply to both).
    o09.BACKEND = backend
    try:
        # Inside the try: an anchor edit that throws mid-overlay must
        # still hit the finally-restore below, not strand a dirty submodule.
        o09.prepare_cmangos_source()
        # The root this run configures and builds: the extractors leg routes
        # to the backend-unsuffixed EXTRACTORS_BUILD home (both consumers —
        # tools/win_prepare_data.py EXTRACTORS and the desktop
        # DataPreparationStore dev walk — resolve that fixed path, so a
        # --database-backend mariadb extractors build must land the tools
        # where they look, never inside the -mariadb lane root); every
        # runtime lane keeps BUILD. For the sqlite vanilla lane the two are
        # the same directory, byte-for-byte the historical behavior.
        build_root = EXTRACTORS_BUILD if args.extractors else BUILD
        if args.force:
            # Wipe the root THIS run configures (build_root) but keep
            # sqlite-seam-build/ (built by tools/build_win_sqlite_seam.py
            # into the same parent): a forced rebuild must not strand
            # write_lockfile() without pocket_sqlite.dll after an
            # hours-long DLL build. Keep mariadb-provider/ too
            # (PROVIDER_STAGING): it is the OTHER backend's staged engine
            # distribution, not this lane's output — the same
            # foreign-lane-artifact law. Wiping build_root — never plain
            # BUILD — closes the split-roots gap: a --force --extractors
            # --database-backend mariadb run that wiped the -mariadb lane
            # root would destroy pocket-runtime-build/ (the lane DLLs plus
            # the staged libmysql.dll this invocation never rebuilds) and
            # strand realm-runtime-lockfile-mariadb-win.json pinning
            # artifacts that no longer exist, while the shared root it
            # actually configures kept its stale CMakeCache through a
            # "forced" run.
            spared = {build_root / "sqlite-seam-build", PROVIDER_STAGING}
            if build_root.is_dir():
                for entry in build_root.iterdir():
                    if entry not in spared:
                        if entry.is_dir():
                            shutil.rmtree(entry, ignore_errors=True)
                        else:
                            entry.unlink()
        build_root.mkdir(parents=True, exist_ok=True)
        vcvars = find_vcvars()
        # Quote the cmake path inside the generated batch (Program Files).
        cmd = configure_command(extractors=args.extractors, backend=backend)
        configure_body = f'"{cmd[0]}" ' + " ".join(str(c) for c in cmd[1:])
        configure_body += f' > "{build_root / "configure.log"}" 2>&1'
        in_msvc_env(vcvars, configure_body, build_root / "configure.log",
                    cwd=build_root)
        capture = (build_root / "configure.log").read_text(
            encoding="utf-8", errors="replace")
        verify_backend_selection(capture, backend, build_root)
        if args.configure_only:
            print(f"configure-only: backend={backend} verified in {build_root}")
            return 0
        if args.extractors:
            build_body = (
                f'"{CMAKE}" --build "{build_root}" ' +
                " ".join(f"--target {target}" for target in EXTRACTOR_TARGETS) +
                f' -j {os.cpu_count() or 4} ' +
                f'> "{build_root / "extractors.log"}" 2>&1'
            )
            in_msvc_env(vcvars, build_body, build_root / "extractors.log",
                        cwd=build_root)
            exes = [build_root / "bin/x64_Release/Extractors" / f"{target}.exe"
                    for target in EXTRACTOR_TARGETS]
            for exe in exes:
                assert exe.is_file(), f"expected extractor missing: {exe}"
            print("windows extractors built:\n" + "\n".join(f"  {exe}" for exe in exes))
            if backend != "sqlite":
                # The coupling, said out loud at the only moment it can
                # surprise: the tools above are backend-agnostic, so they
                # staged in the shared root while THIS lane's DLLs (and the
                # lockfile a composed --write-lockfile writes) stay in the
                # backend-suffixed BUILD root.
                print(f"extractors are backend-agnostic: staged under the shared "
                      f"root {EXTRACTORS_BUILD} (where tools/win_prepare_data.py "
                      f"resolves them); the {backend} lane's DLLs stay in {BUILD}")
            if args.write_lockfile:
                # Composed invocation (--write-lockfile --extractors): the
                # lockfiles are recorded AFTER the requested work completes,
                # same order as the runtime lane below. The extractors
                # themselves stay unpinned (LOCKFILE_ARTIFACTS: build tools
                # never ride into the image) — the refresh records the
                # current source pins plus the runtime DLLs' on-disk bytes
                # and fails loud with the lane-build remedy when those are
                # missing, so composition can never record a hollow state.
                write_lane_lockfiles(backend)
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
        # disagree on the dev box. The mariadb lane re-verifies + re-records
        # the provider distribution alongside (write_lockfile reads the
        # provider lockfile the line above just wrote).
        write_lane_lockfiles(backend)
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
