"""Hardened DO_SQLITE connection layer: unit tests per fix.

The replacement files under native/patches/cmangos/ are compiled into the
DO_SQLITE lane by tools/build_o09_realm_runtime.py (mysql builds never touch
them). These tests pin every hardening the replacement ships, and the
two-handle contention test exercises the engine-level policy (WAL,
synchronous=FULL, busy_timeout=500, BEGIN IMMEDIATE, commit-failure
rollback) against the PINNED amalgamation compiled for the host.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "cmangos"
AMALGAMATION = ROOT / "native" / ".deps" / "src" / "sqlite" / "sqlite-amalgamation-3460100"
DRIVER = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")


def _patch(name: str) -> str:
    return (PATCHES / name).read_text(encoding="utf-8")


def test_connection_policy_is_the_decided_one() -> None:
    cpp = _patch("DatabaseSqlite.cpp")
    assert "PRAGMA journal_mode=WAL;" in cpp
    # synchronous=NORMAL under WAL - crash-consistent with bounded
    # progress loss on power cut, never corruption. The per-engine
    # power-cut contracts are deliberate: MariaDB keeps trx_commit=1,
    # and the 64 MiB page cache serves the same low-RAM devices.
    assert "PRAGMA synchronous=NORMAL;" in cpp
    assert "synchronous=FULL" not in cpp
    assert "PRAGMA cache_size=-65536;" in cpp
    # The busy policy has one source: the constants below feed the
    # sqlite3_busy_timeout call (no duplicated pragma literal to drift).
    assert "constexpr int POCKET_SQLITE_BUSY_TIMEOUT_MS = 500;" in cpp
    assert "constexpr int POCKET_SQLITE_BUSY_RETRIES = 3;" in cpp
    assert "sqlite3_busy_timeout(mSqlite, POCKET_SQLITE_BUSY_TIMEOUT_MS);" in cpp
    # The upstream hazards must be gone from the replacement.
    assert "synchronous=1" not in cpp
    assert "sqlite3_busy_timeout(mSqlite, 2)" not in cpp
    # A silent WAL failure (first-open race) degrades concurrency loudly.
    assert '"PRAGMA journal_mode;"' in cpp
    assert "journal_mode is not WAL after init" in cpp


def test_execute_retries_on_busy_instead_of_silent_false() -> None:
    cpp = _patch("DatabaseSqlite.cpp")
    assert "SQLITE_BUSY || result == SQLITE_LOCKED" in cpp
    assert "_StepNoRows" in cpp


def test_write_transactions_begin_immediate() -> None:
    assert '"BEGIN IMMEDIATE"' in _patch("DatabaseSqlite.cpp")


def test_commit_failure_rolls_back_and_begin_failure_refuses() -> None:
    # The commit-wedge fix rides the SqlOperations overlay, applied by
    # the driver for the sqlite lane and inert under DO_MYSQL. A failed
    # BEGIN must refuse to run the batch at all (upstream fell through to
    # autocommit - non-atomic partial application); a failed COMMIT must
    # roll the open transaction back.
    assert "#ifdef DO_SQLITE" in DRIVER
    assert "if (!conn->BeginTransaction())" in DRIVER
    assert "const bool committed = conn->CommitTransaction();" in DRIVER
    assert "conn->RollbackTransaction();" in DRIVER


def test_query_results_materialize_under_the_connection_lock() -> None:
    header = _patch("QueryResultSqlite.h")
    cpp = _patch("QueryResultSqlite.cpp")
    # No live statement survives the constructor; no lazy stepping.
    assert "sqlite3_stmt* stmt" in header
    assert "m_rows" in header
    assert "sqlite3_finalize(stmt);" in cpp
    # The old double-scan + post-reset reads shapes must be gone.
    assert "while (sqlite3_step(*mStmt) == SQLITE_ROW)" not in cpp
    assert "sqlite3_reset(*mStmt);" not in cpp


def test_mid_scan_failure_fails_the_query_like_mysql() -> None:
    # MySQL surfaces a mid-read failure as a FAILED query; a mid-scan step
    # error must not hand back a silently truncated result set.
    header = _patch("QueryResultSqlite.h")
    cpp = _patch("DatabaseSqlite.cpp")
    assert "bool ScanComplete() const" in header
    assert "m_scanComplete" in _patch("QueryResultSqlite.cpp")
    # Both call sites pinned individually (Query: exact shape; QueryNamed:
    # the compound condition) - neither gate is removable alone.
    assert "if (!queryResult->ScanComplete())" in cpp  # Query
    assert "if (!queryResult->ScanComplete() || !queryResult->NextRow())" in cpp  # QueryNamed
    assert cpp.count("queryResult->ScanComplete()") == 2


def test_busy_policy_agrees_with_the_kotlin_config_policy() -> None:
    # DatabaseSqliteConfigPolicy is the Kotlin-side source of the
    # same contract; both sides are pinned so neither can drift alone.
    kotlin = (ROOT / "android" / "app" / "src" / "main" / "java" / "com"
              / "pocketrealm" / "database" / "DatabaseSqliteConfigPolicy.kt"
              ).read_text(encoding="utf-8")
    assert "const val BUSY_TIMEOUT_MS = 500" in kotlin
    assert "PRAGMA busy_timeout=$BUSY_TIMEOUT_MS;" in kotlin  # derived, not duplicated
    assert "constexpr int POCKET_SQLITE_BUSY_TIMEOUT_MS = 500;" in _patch("DatabaseSqlite.cpp")


def test_no_statement_wrapper_leak() -> None:
    # The leak was the heap stmt-wrapper (`new(sqlite3_stmt*)`) allocated
    # per query/statement and never freed. A plain out-parameter
    # (sqlite3_stmt**) in a private helper is not a wrapper.
    for name in ("DatabaseSqlite.cpp", "DatabaseSqlite.h",
                 "QueryResultSqlite.cpp", "QueryResultSqlite.h"):
        assert "new(sqlite3_stmt*)" not in _patch(name), name
        assert "delete mStmt" not in _patch(name), name


def test_querynamed_zero_rows_returns_null_like_mysql() -> None:
    cpp = _patch("DatabaseSqlite.cpp")
    assert "zero rows returns nullptr, not a live empty result" in cpp
    assert "delete queryResult;" in cpp


def test_text_binds_carry_explicit_length_and_transient() -> None:
    cpp = _patch("DatabaseSqlite.cpp")
    assert "sqlite3_bind_text(m_stmt, nIndex + 1, data.toStr()," in cpp
    assert "static_cast<int>(data.size()), SQLITE_TRANSIENT)" in cpp
    assert "-1, SQLITE_STATIC" not in cpp  # the upstream truncating bind


def test_inverted_isquery_flag_is_fixed() -> None:
    cpp = _patch("DatabaseSqlite.cpp")
    # readonly == 0 means the statement WRITES: it is not a query.
    assert "sqlite3_stmt_readonly(m_stmt) == 0" in cpp
    assert "m_bIsQuery = false;" in cpp


def test_hardening_only_applies_to_the_sqlite_lane() -> None:
    # DO_MYSQL builds must be behaviorally unchanged: the driver applies the
    # replacements only under --backend sqlite, and the commit-rollback
    # overlay is #ifdef DO_SQLITE.
    assert 'if BACKEND == "sqlite":\n        apply_sqlite_hardening(cmangos)' in DRIVER
    assert 'if BACKEND == "sqlite":\n        restore_sqlite_hardening' in DRIVER


def test_two_handle_contention_against_the_pinned_amalgamation() -> None:
    """Compile the pinned amalgamation + the contention test for the host
    (core define set; the full production recipe adds FTS5/RTREE/
    metadata/json1/dbstat) and run it: no dropped writes, no wedge,
    embedded NULs survive, and concurrent same-connection Query iteration
    under the emulated SqlConnection::Lock is snapshot-consistent (T6)."""
    gcc = shutil.which("gcc") or shutil.which("clang") or shutil.which("cc")
    if gcc is None:
        pytest.skip("no host C compiler available")
    if not (AMALGAMATION / "sqlite3.c").is_file():
        pytest.skip("amalgamation not staged (run tools/stage_sqlite_amalgamation.py)")
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        binary = tmp_path / "contention_test.exe"
        # Production define set: the shipped lane compiles the amalgamation
        # multi-threaded (SQLITE_THREADSAFE=2) with the FULL-parity WAL
        # default - the host test must run the same engine semantics, not a
        # stricter serialized build that would mask same-connection races.
        build = subprocess.run(
            [gcc, "-O1",
             "-DSQLITE_THREADSAFE=2",
             "-DSQLITE_DEFAULT_WAL_SYNCHRONOUS=2",
             "-I", str(AMALGAMATION), "-o", str(binary),
             str(AMALGAMATION / "sqlite3.c"),
             str(ROOT / "tools" / "test_sqlite_contention.c")],
            capture_output=True, text=True, timeout=300)
        if build.returncode != 0:
            pytest.fail(f"host compile failed:\n{build.stderr[:2000]}")
        run = subprocess.run([str(binary), str(tmp_path / "contention.db")],
                             capture_output=True, text=True, timeout=120)
        assert run.returncode == 0, (
            f"contention test failed:\n{run.stdout}\n{run.stderr[:2000]}")
        assert "contention tests passed" in run.stdout
