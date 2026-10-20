#!/usr/bin/env python3
"""Build the desktop SQLite execution seam DLL (Windows port).

pocket_sqlite.dll = the JNI surface in native/desktop-sqlite over the
repo-pinned SQLite 3.46.1 amalgamation, compiled with the same
PRODUCTION define set as the fidelity harness (tools/sqlite_exec_file.c
— pinned by tests/test_sqlite_seeding.py PRODUCTION_DEFINES and
re-pinned for this lane by tests/test_win_sqlite_seam.py). The desktop
engine twin executes the pure DatabaseSqliteControlPlane legs through
it: seed replay, integrity gate, revision probes, ledger DDL.

Every dependency is explicit (amalgamation path via cache var, JDK via
find_package(JNI)) and every outcome is verified fail-loud: the pinned
version in sqlite3.h, the DLL's existence, and the JNI export table
derived from the Kotlin shim's own `external fun` declarations.

Output: native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import build_win_realm_runtime as realm  # noqa: E402
try:
    from tools import flavor_layout
except ImportError:
    import flavor_layout

BUILD = ROOT / "native" / ".build-win-x86_64" / "sqlite-seam-build"
PROJECT = ROOT / "native" / "desktop-sqlite"
AMALGAMATION = ROOT / "native" / ".deps" / "src" / "sqlite" / "sqlite-amalgamation-3460100"
SHIM = ROOT / "desktop/src/main/kotlin/com/pocketrealm/database/DesktopSqlite.kt"
PINNED_VERSION = "3.46.1"


def resolve_flavor_or_die(flavor_id: str) -> flavor_layout.FlavorLayout:
    """P2-6 item 2: the seam lane is per-flavor in its PINS, not its DLL —
    the pocket_sqlite.dll artifact is the flavor-agnostic SQLite engine + JNI
    surface (decision D37); only the overlay/seed pins recorded in the
    flavor's realm-runtime-lockfile-sqlite-win-{flavor}.json differ. A flavor
    whose per-flavor overlay set is not yet registered (expected before S2
    re-derives the o09 overlays per branch) fails loud here, BEFORE any MSVC
    environment is set up or any file is touched. Vanilla falls through and
    builds exactly as before."""
    layout = flavor_layout.resolve(flavor_id)
    if flavor_id != "vanilla":
        try:
            realm.o09.overlays_for(flavor_id, "sqlite")
        except RuntimeError as exc:
            raise SystemExit(f"ERROR: {exc}")
    return layout


def flavor_lockfile(layout: flavor_layout.FlavorLayout) -> Path:
    """The flavor's win SQLite-seam lockfile path (P2-6 item 1 naming):
    realm-runtime-lockfile-sqlite-win[-{flavor}].json — vanilla keeps the
    unqualified alias."""
    return ROOT / layout.sqlite_win_lockfile()


def in_msvc_env(vcvars: Path, workdir: Path, body: str, log: Path) -> None:
    log.parent.mkdir(parents=True, exist_ok=True)
    script = log.with_suffix(".bat")
    # write_bytes: text mode would double the CRLFs on Windows.
    script.write_bytes(
        (
            "@echo off\r\n"
            "set CCACHE_DISABLE=1\r\n"
            f'call "{vcvars}" >nul 2>&1\r\n'
            f'cd /d "{workdir}"\r\n'
            f"{body}\r\n"
            "exit /b %ERRORLEVEL%\r\n"
        ).encode("ascii")
    )
    result = subprocess.run(["cmd", "/c", str(script)])
    if result.returncode != 0:
        tail = ""
        if log.is_file():
            tail = log.read_text(encoding="utf-8", errors="replace")[-4000:]
        raise RuntimeError(f"MSVC step failed (exit {result.returncode}):\n{tail}")


def dumpbin_java_exports(vcvars: Path, dll: Path) -> set[str]:
    work = BUILD / "verify"
    work.mkdir(parents=True, exist_ok=True)
    script = work / "dumpbin.bat"
    script.write_bytes(
        (
            "@echo off\r\n"
            f'call "{vcvars}" >nul 2>&1\r\n'
            f'dumpbin /exports "{dll}"\r\n'
        ).encode("ascii")
    )
    result = subprocess.run(["cmd", "/c", str(script)], capture_output=True, text=True)
    exports = {line.split()[-1] for line in result.stdout.splitlines()
               if line.split() and line.split()[-1].startswith("Java_")}
    assert exports, f"{dll.name}: dumpbin reported no JNI exports at all"
    return exports


def main() -> int:
    if os.name != "nt":
        print("windows-only lane", file=sys.stderr)
        return 2

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--flavor", default="vanilla",
                        choices=tuple(flavor_layout.FLAVOR_IDS),
                        help="flavor whose seam pins this build records "
                             "(default vanilla; the DLL itself is "
                             "flavor-agnostic — decision D37). tbc/wotlk fail "
                             "loud until their per-flavor overlay sets are "
                             "re-derived at S2 (P2-6).")
    args = parser.parse_args()
    layout = resolve_flavor_or_die(args.flavor)
    lockfile = flavor_lockfile(layout)

    if not (AMALGAMATION / "sqlite3.c").is_file():
        raise RuntimeError(
            f"pinned amalgamation missing: {AMALGAMATION}\n"
            "stage it first: python tools/stage_sqlite_amalgamation.py"
        )
    header = (AMALGAMATION / "sqlite3.h").read_text(encoding="utf-8", errors="replace")
    assert re.search(r'#define\s+SQLITE_VERSION\s+"' + re.escape(PINNED_VERSION) + '"', header), (
        f"amalgamation is not the pinned {PINNED_VERSION} build: "
        "its SQLITE_VERSION define does not match"
    )

    vcvars = realm.find_vcvars()
    BUILD.mkdir(parents=True, exist_ok=True)
    configure = (
        f'"{realm.CMAKE}" -S "{PROJECT}" -B "{BUILD}" -G Ninja '
        f'-DCMAKE_BUILD_TYPE=Release '
        f'-DSQLITE_AMALGAMATION="{AMALGAMATION}" '
        f'> "{BUILD / "configure.log"}" 2>&1'
    )
    in_msvc_env(vcvars, BUILD, configure, BUILD / "configure.log")
    build = (
        f'"{realm.CMAKE}" --build "{BUILD}" --target pocket_sqlite '
        f'-j {os.cpu_count() or 4} '
        f'> "{BUILD / "build.log"}" 2>&1'
    )
    in_msvc_env(vcvars, BUILD, build, BUILD / "build.log")

    dll = BUILD / "pocket_sqlite.dll"
    assert dll.is_file(), f"expected DLL missing: {dll}"

    exports = dumpbin_java_exports(vcvars, dll)
    expected = realm.jni_symbols_from_shim(SHIM)
    missing = [symbol for symbol in expected if symbol not in exports]
    assert not missing, f"pocket_sqlite.dll missing JNI exports: {missing}"

    print(f"desktop sqlite seam built ({PINNED_VERSION}, flavor {layout.flavor_id}, "
          f"{len(expected)} JNI exports verified; pins lockfile: "
          f"{lockfile.name}):\n  {dll}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
