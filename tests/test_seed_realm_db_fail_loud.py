"""Fail-loud semantics for the realm seeder.

A silently dropped SQL statement was silent data loss in the seeded realm.
Default behavior must abort (rollback + raise); --tolerate-sql-errors is the
explicit opt-in and must still count and report the dropped statements.
"""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import seed_realm_db  # noqa: E402


class FakeDump:
    """Duck-typed dump source: apply_dump only touches .suffix/.read_text."""

    def __init__(self, text: str, suffix: str = ".sql") -> None:
        self._text = text
        self.suffix = suffix

    def read_text(self, encoding: str = "utf-8", errors: str = "strict") -> str:
        return self._text

    def exists(self) -> bool:
        return True


GOOD_DUMP = "CREATE TABLE t (id INTEGER);\nINSERT INTO t VALUES (1);\n"
BAD_DUMP = (
    "CREATE TABLE t (id INTEGER);\n"
    "INSERT INTO nonexistent_table VALUES (1);\n"
    "INSERT INTO t VALUES (2);\n"
)


def connection() -> sqlite3.Connection:
    return sqlite3.connect(":memory:")


def test_default_mode_aborts_on_bad_statement() -> None:
    conn = connection()
    with pytest.raises(sqlite3.Error):
        seed_realm_db.apply_dump(conn, FakeDump(BAD_DUMP), "mangos", verbose=False)
    conn.close()


def test_tolerant_mode_counts_and_continues() -> None:
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(BAD_DUMP), "mangos", verbose=False, tolerate_errors=True
    )
    assert applied == 2  # CREATE + the valid INSERT survive
    assert errors == 1  # the bogus INSERT is counted, not silent
    surviving = conn.execute("SELECT COUNT(*) FROM t").fetchone()[0]
    assert surviving == 1
    conn.close()


def test_good_dump_applies_cleanly_in_default_mode() -> None:
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(conn, FakeDump(GOOD_DUMP), "mangos", verbose=False)
    assert applied == 2
    assert errors == 0
    conn.close()


def test_build_one_success_requires_zero_errors() -> None:
    # tolerance flag off + errors present -> the failure propagates (even
    # louder than a nonzero return; the old "any statement applied" rule
    # returned 0 and silently seeded a partial database).
    db = ROOT / ".tmp" / "scratch" / "seed-test" / "bad.sqlite"
    db.parent.mkdir(parents=True, exist_ok=True)
    with pytest.raises(sqlite3.Error):
        seed_realm_db.build_one(db, [FakeDump(BAD_DUMP)], "mangos", verbose=False)


def test_build_one_succeeds_with_tolerated_errors() -> None:
    db = ROOT / ".tmp" / "scratch" / "seed-test" / "tol.sqlite"
    db.parent.mkdir(parents=True, exist_ok=True)
    rc = seed_realm_db.build_one(
        db, [FakeDump(BAD_DUMP)], "mangos", verbose=False, tolerate_errors=True
    )
    assert rc == 0
