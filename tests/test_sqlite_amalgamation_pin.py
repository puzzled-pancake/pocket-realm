"""Tripwire: the SQLite amalgamation must only move deliberately.

The amalgamation used to be an unpinned build-host artifact (research digest
F32: sources.json carried only a note, no pin, no fetcher). It is now a real
pinned component - this test pins the reviewed url + zip sha + content shas
and refuses silent drift, mirroring the MariaDB lockfile tripwire. Update
these pins only alongside a reviewed amalgamation bump.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "schemas" / "sources.json"
ENTRY_ID = "sqlite-amalgamation-3460100"
RECIPE = ROOT / "native" / ".deps" / "src" / "sqlite" / "CMakeLists.txt"

REVIEWED_PIN = {
    "artifact_url": "https://sqlite.org/2024/sqlite-amalgamation-3460100.zip",
    "artifact_sha256": "77823cb110929c2bcb0f5d48e4833b5c59a8a6e40cdea3936b99e199dbbe5784",
    "sqlite3.c": "6c35bc5f7f85eac9c49928bacbb02bb694b547aabf69197e058cca245ad80e83",
    "sqlite3.h": "89b62c671c5964e137409ce034941b7b05a3af2c9875aba41f47f9483d0c2515",
}


def _entry() -> dict:
    data = json.loads(SOURCES.read_text(encoding="utf-8"))
    for source in data["sources"]:
        if source["id"] == ENTRY_ID:
            return source
    raise AssertionError(f"{ENTRY_ID} missing from sources.json (the F32 gap is back)")


def test_amalgamation_pin_is_registered_and_reviewed() -> None:
    entry = _entry()
    assert entry["kind"] == "prebuilt-archive"
    assert entry["artifact_url"] == REVIEWED_PIN["artifact_url"]
    assert entry["artifact_sha256"] == REVIEWED_PIN["artifact_sha256"]
    assert entry["license"] == "Public Domain"
    assert entry["build_script"] == "tools/stage_sqlite_amalgamation.py"
    content = entry["content_sha256"]
    assert content["sqlite3.c"] == REVIEWED_PIN["sqlite3.c"]
    assert content["sqlite3.h"] == REVIEWED_PIN["sqlite3.h"]


def test_cached_amalgamation_matches_the_pin() -> None:
    cached = ROOT / "native" / ".providers" / ENTRY_ID / "sqlite-amalgamation-3460100.zip"
    if not cached.is_file():
        import pytest

        pytest.skip("amalgamation zip not cached (run tools/fetch_provider.py)")
    digest = hashlib.sha256(cached.read_bytes()).hexdigest()
    assert digest == REVIEWED_PIN["artifact_sha256"], (
        "cached amalgamation zip no longer matches the reviewed pin")


def test_staged_amalgamation_matches_the_pin() -> None:
    staged = ROOT / "native" / ".deps" / "src" / "sqlite" / "sqlite-amalgamation-3460100"
    if not (staged / "sqlite3.c").is_file():
        import pytest

        pytest.skip("amalgamation not staged (run tools/stage_sqlite_amalgamation.py)")
    for name in ("sqlite3.c", "sqlite3.h"):
        digest = hashlib.sha256((staged / name).read_bytes()).hexdigest()
        assert digest == REVIEWED_PIN[name], f"staged {name} drifted from the pin"


def test_recipe_keeps_the_full_parity_wal_default() -> None:
    # DEC-02 enforcement chain: the amalgamation build default must stay
    # SQLITE_DEFAULT_WAL_SYNCHRONOUS=2 (FULL). =1 is the power-cut downgrade
    # the MariaDB policy test refuses; it must not creep back in.
    text = RECIPE.read_text(encoding="utf-8")
    assert "SQLITE_DEFAULT_WAL_SYNCHRONOUS=2" in text
    assert "SQLITE_DEFAULT_WAL_SYNCHRONOUS=1" not in text


def test_recipe_refuses_ambiguous_amalgamation_globs() -> None:
    # The exactly-one rule must hold at configure time too (a parallel-
    # session revert once removed the guard; this pins the guard text
    # itself, not just the staged-directory state).
    text = RECIPE.read_text(encoding="utf-8")
    assert "list(LENGTH SQLITE_AMALG SQLITE_AMALG_COUNT)" in text
    assert "if(NOT SQLITE_AMALG_COUNT EQUAL 1)" in text


def test_recipe_does_not_define_update_delete_limit() -> None:
    # DEC-03: the DELETE..LIMIT policy is the portable rowid rewrite (P3),
    # NOT a re-vendor of SQLite with SQLITE_ENABLE_UPDATE_DELETE_LIMIT. The
    # flag must never appear in the recipe, the o09 driver's sqlite flags,
    # or the staging script's cmake invocation.
    for path in (RECIPE,
                 ROOT / "tools" / "build_o09_realm_runtime.py",
                 ROOT / "tools" / "stage_sqlite_amalgamation.py"):
        assert "SQLITE_ENABLE_UPDATE_DELETE_LIMIT" not in path.read_text(
            encoding="utf-8", errors="replace"), f"{path.name} must never define it"


def test_only_the_pinned_amalgamation_dir_is_staged() -> None:
    # The recipe globs sqlite-amalgamation-*/sqlite3.c; a stray manually
    # unzipped amalgamation could silently win the glob. Only the pinned
    # directory may exist.
    src = ROOT / "native" / ".deps" / "src" / "sqlite"
    dirs = sorted(d.name for d in src.glob("sqlite-amalgamation-*"))
    assert dirs == ["sqlite-amalgamation-3460100"], (
        f"unexpected amalgamation directories staged: {dirs}")


def test_built_sqlite_library_matches_the_pin() -> None:
    # Deterministic-rebuild reference (P1 exit criterion): the built static
    # library must match the reviewed per-ABI hashes recorded in sources.json
    # for the pinned NDK/cmake toolchain, and must carry the FULL-parity WAL
    # default in its compile-time options string.
    import subprocess

    entry = _entry()
    built = entry.get("built_artifacts")
    assert built, "sources.json entry is missing the built_artifacts reference block"
    for abi, expected in built["libsqlite3.a"].items():
        library = ROOT / "native" / ".deps" / (
            "prefix-x86_64" if abi == "x86_64" else "prefix-arm64") / "lib" / "libsqlite3.a"
        if not library.is_file():
            import pytest

            pytest.skip(f"libsqlite3.a not built for {abi}")
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        assert digest == expected, (
            f"{abi} libsqlite3.a sha256 {digest} != pinned {expected}; either the "
            "amalgamation/recipe drifted or the toolchain changed - update the "
            "built_artifacts block in the same reviewed change")
        # Binary-level DEC-02 leg: the compile-time WAL default must be FULL.
        strings = subprocess.run(
            ["python", "-c",
             f"import sys;data=open(r'{library}','rb').read();"
             "sys.exit(0 if b'DEFAULT_WAL_SYNCHRONOUS=2' in data else 1)"],
            capture_output=True)
        assert strings.returncode == 0, (
            f"{abi} libsqlite3.a does not carry DEFAULT_WAL_SYNCHRONOUS=2 in its "
            "compile-time options; the built library predates the DEC-02 recipe change")
