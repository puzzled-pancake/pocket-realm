"""Davos reset/delete tools must report side-table DELETE failures.

The per-table loop already guards absent tables with `tbl in tables`,
so a blanket `except` around the DELETE could only ever hide real
failures (locked DB, trigger abort) and leave a half-reset Davos.
These tests pin the report: a failing side table is named on stdout,
healthy side tables are still cleaned, absent tables stay handled by
the membership guard alone, and the script still finishes its job.
"""
import contextlib
import io
import runpy
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CHARACTERS_DDL = """
CREATE TABLE characters (
    guid INTEGER PRIMARY KEY, name TEXT, account INTEGER, level INTEGER,
    race INTEGER, position_x REAL, position_y REAL, position_z REAL,
    orientation REAL, map INTEGER, zone INTEGER, online INTEGER,
    totaltime INTEGER, leveltime INTEGER
)
"""


def seed_datadir(root: Path) -> sqlite3.Connection:
    """Build a minimal %LOCALAPPDATA% datadir the tools can run against.

    character_aura deletes cleanly, character_inventory aborts every
    DELETE via a BEFORE DELETE trigger (a sqlite3.Error), and the other
    side tables from the tools' loop list are left absent.
    """
    datadir = root / "PocketRealm" / "database" / "sqlite-datadir"
    datadir.mkdir(parents=True)
    conn = sqlite3.connect(str(datadir / "classiccharacters.sqlite3"))
    conn.execute(CHARACTERS_DDL)
    conn.execute("INSERT INTO characters (guid, name, account, level, race)"
                 " VALUES (1, 'Template', 1, 1, 1)")
    conn.execute("INSERT INTO characters (guid, name, account, level, race)"
                 " VALUES (7, 'Davos', 1, 1, 1)")
    conn.execute("CREATE TABLE character_aura (guid INTEGER, spell INTEGER)")
    conn.execute("INSERT INTO character_aura VALUES (7, 123)")
    conn.execute("CREATE TABLE character_inventory (guid INTEGER, item INTEGER)")
    conn.execute("INSERT INTO character_inventory VALUES (7, 42)")
    conn.execute("CREATE TRIGGER deny_inventory_delete BEFORE DELETE ON "
                 "character_inventory BEGIN "
                 "SELECT RAISE(ABORT, 'side table locked'); END")
    conn.commit()
    conn.close()
    auth = sqlite3.connect(str(datadir / "classicrealmd.sqlite3"))
    auth.execute("CREATE TABLE account (id INTEGER PRIMARY KEY, username TEXT)")
    auth.execute("INSERT INTO account VALUES (1, 'RPTEST')")
    auth.commit()
    auth.close()
    return sqlite3.connect(str(datadir / "classiccharacters.sqlite3"))


def run_tool(name: str, tmp_path: Path, monkeypatch) -> str:
    seed_datadir(tmp_path)
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        runpy.run_path(str(ROOT / "tools" / name), run_name="__main__")
    return out.getvalue()


def test_reset_davos_reports_failing_side_table(tmp_path, monkeypatch):
    out = run_tool("reset_davos.py", tmp_path, monkeypatch)
    # the DELETE failure is reported, not silently swallowed
    assert "character_inventory skip:" in out
    assert "side table locked" in out
    # the reset itself still completes: Davos recreated, healthy side
    # table cleaned, absent side tables skipped by the membership guard
    assert "old Davos removed" in out
    assert "Davos reset:" in out
    conn = sqlite3.connect(str(tmp_path / "PocketRealm" / "database" /
                               "sqlite-datadir" / "classiccharacters.sqlite3"))
    try:
        assert conn.execute(
            "SELECT COUNT(*) FROM character_aura WHERE guid = 7"
        ).fetchone()[0] == 0
        assert conn.execute(
            "SELECT COUNT(*) FROM characters WHERE name = 'Davos'"
        ).fetchone()[0] == 1
    finally:
        conn.close()


def test_delete_davos_reports_failing_side_table(tmp_path, monkeypatch):
    out = run_tool("delete_davos.py", tmp_path, monkeypatch)
    assert "character_inventory skip:" in out
    assert "side table locked" in out
    assert "cleaned character_aura" in out
    assert "Davos deleted" in out
    conn = sqlite3.connect(str(tmp_path / "PocketRealm" / "database" /
                               "sqlite-datadir" / "classiccharacters.sqlite3"))
    try:
        assert conn.execute(
            "SELECT COUNT(*) FROM character_aura WHERE guid = 7"
        ).fetchone()[0] == 0
        assert conn.execute(
            "SELECT COUNT(*) FROM characters WHERE name = 'Davos'"
        ).fetchone()[0] == 0
    finally:
        conn.close()
