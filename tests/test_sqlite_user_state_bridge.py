"""User-state bridge harness.

Proves the host-testable core of the dual-provider translation:

  - the TSV codec round-trips every escape class byte-exactly
    (mysqldump --tab convention: \\t \\n \\r \\\\ \\0, NULL as \\N -
    unambiguous because a literal backslash-N text value arrives
    escaped as \\\\N);
  - export queries wrap EXACTLY the blob columns in HEX() (the
    two longblob `data` columns corrupt without it) and the export
    prelude pins the session to UTC (timestamp normalization, by
    construction);
  - the full bridge direction: batch-TSV text -> transactional import
    into a seeded-shape SQLite datadir preserves values byte-for-byte
    (blob checksums over binary incl. 0x00/0xFF/quotes; text with
    tabs/newlines/backslashes; NULL vs empty string), with user state
    winning over seed rows (INSERT OR REPLACE);
  - the reverse direction: emitting TSV from the SQLite side and
    re-importing reproduces the database identically (the round-trip
    leg);
  - interrupted-translation recovery: ragged/truncated TSV fails loud
    with no partial commit; a leftover .partial from a killed run is
    discarded and the next attempt succeeds.

The on-device leg (a REAL datadir through the shipped client) is
device-gated and exercised by the device test lane.
"""
from __future__ import annotations

import hashlib
import sqlite3
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import sqlite_user_state_bridge as bridge  # noqa: E402


# ---------------------------------------------------------------------------
# Codec
# ---------------------------------------------------------------------------

def test_tsv_codec_round_trips_every_escape_class() -> None:
    values: list[bytes | None] = [
        b"plain",
        b"tab\there",
        b"new\nline",
        b"carriage\rreturn",
        b"back\\slash",
        b"nul\x00byte",
        b"mix\t\\and\n\r\x00end",
        "multi-byte \u00e3\u00c3\u2014 utf-8 \u4e2d\u6587".encode("utf-8"),
        b"",                    # empty string
        None,                   # NULL
        b"\\N",                 # the literal two-char string
        b"NULL",                # the literal word (never a NULL marker)
        bytes(range(256)),      # every byte value incl. 0x00 and 0xFF
    ]
    for v in values:
        field = bridge.encode_field(v)
        assert "\t" not in field or v is None or b"\t" in (v or b"")
        assert bridge.decode_field(field) == v, (v, field)
    # record/field structure
    rows = [values, [b"1", None, b"x\ty"]]
    text = bridge.encode_tsv(rows)
    assert bridge.decode_tsv(text) == rows


def test_decode_is_the_mysqldump_convention() -> None:
    # hand-written wire bytes, not our own encoder: NULL marker,
    # escaped controls, plain UTF-8.
    assert bridge.decode_field("\\N") is None
    assert bridge.decode_field("a\\tb") == b"a\tb"
    assert bridge.decode_field("a\\nb") == b"a\nb"
    assert bridge.decode_field("a\\\\b") == b"a\\b"
    # an UNescaped backslash before a non-escape char stays literal
    assert bridge.decode_field("a\\qb") == b"a\\qb"
    # raw UTF-8 wire text (not escape-built chars)
    assert bridge.decode_field("cafã") == "cafã".encode("utf-8")


# ---------------------------------------------------------------------------
# Export queries
# ---------------------------------------------------------------------------

def test_utc_pin_is_the_session_normalization() -> None:
    # the withdrawn client-stdout export_query surface was deleted; the
    # OUTFILE wire is the only export surface and is pinned by the
    # fixture test below
    assert bridge.UTC_PIN == "SET time_zone = '+00:00'"


# ---------------------------------------------------------------------------
# Full-bridge fixtures (a seeded-shape datadir)
# ---------------------------------------------------------------------------

def _make_target(path: Path) -> None:
    conn = sqlite3.connect(str(path))
    try:
        conn.executescript(
            """
            CREATE TABLE `characters` (
              `guid` INTEGER PRIMARY KEY AUTOINCREMENT,
              `account` INTEGER NOT NULL DEFAULT '0',
              `name` TEXT COLLATE NOCASE NOT NULL DEFAULT '',
              `race` INTEGER NOT NULL DEFAULT '0',
              `logout_time` INTEGER NOT NULL DEFAULT '0'
            );
            CREATE TABLE `account_data` (
              `account` INTEGER NOT NULL DEFAULT '0',
              `type` INTEGER NOT NULL DEFAULT '0',
              `time` INTEGER NOT NULL DEFAULT '0',
              `data` BLOB NOT NULL,
              PRIMARY KEY (`account`,`type`)
            );
            CREATE TABLE `character_account_data` (
              `guid` INTEGER NOT NULL DEFAULT '0',
              `type` INTEGER NOT NULL DEFAULT '0',
              `time` INTEGER NOT NULL DEFAULT '0',
              `data` BLOB NOT NULL,
              PRIMARY KEY (`guid`,`type`)
            );
            CREATE TABLE `mail` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT,
              `messageType` INTEGER NOT NULL DEFAULT '0',
              `subject` TEXT COLLATE NOCASE,
              `body` TEXT COLLATE NOCASE
            );
            CREATE TABLE `account` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT,
              `username` TEXT COLLATE NOCASE NOT NULL DEFAULT '',
              `joindate` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            -- a seed row the user state must overwrite (guid 1)
            INSERT INTO `characters` (`guid`, `account`, `name`)
              VALUES (1, 0, 'seed-placeholder');
            """)
        conn.commit()
    finally:
        conn.close()


_BLOB_A = bytes(range(256)) + b"'quotes' and\x00nul\xff"
_BLOB_B = hashlib.sha256(b"corpus").digest() * 4


def _user_rows() -> dict[str, list[list[bytes | None]]]:
    return {
        "characters": [
            [b"1", b"7", "Tab\tNamé".encode("utf-8"), b"1", b"1700000000"],
            [b"2", b"7", "line\nbreak \\\\ back".encode("utf-8"),
             b"2", b"1700000050"],
        ],
        "account_data": [
            [b"7", b"0", b"1700000100", _BLOB_A.hex().encode("ascii")],
        ],
        "character_account_data": [
            [b"1", b"1", b"1700000200", _BLOB_B.hex().encode("ascii")],
            [b"2", b"1", b"1700000250", b""],  # EMPTY blob (not NULL)
        ],
        "mail": [
            [b"10", b"0", None, "body with\ttab and \\ backslash".encode()],
        ],
        "account": [
            [b"7", b"pocket_user", b"2026-08-24 12:34:56"],
        ],
    }


def _bridge_into(tmp_path: Path) -> tuple[Path, dict]:
    live = tmp_path / "classiccharacters.sqlite"
    _make_target(live)
    rows = _user_rows()
    tsv = {table: bridge.encode_tsv(table_rows)
           for table, table_rows in rows.items()}
    counts = bridge.import_database(live, tsv)
    return live, counts


def test_bridge_preserves_user_state_byte_for_byte(tmp_path) -> None:
    live, counts = _bridge_into(tmp_path)
    assert counts["characters"] == 2 and counts["account_data"] == 1
    conn = sqlite3.connect(str(live))
    try:
        conn.text_factory = bytes
        # INTEGER columns come back as ints; TEXT as bytes
        rows = list(conn.execute(
            "SELECT `guid`, `name` FROM `characters` ORDER BY `guid`"))
        assert rows == [(1, "Tab\tNamé".encode("utf-8")),
                        (2, "line\nbreak \\\\ back".encode("utf-8"))]
        blob = conn.execute(
            "SELECT `data` FROM `account_data` "
            "WHERE `account`=7 AND `type`=0").fetchone()[0]
        assert bytes(blob) == _BLOB_A
        assert hashlib.sha256(bytes(blob)).hexdigest() == \
            hashlib.sha256(_BLOB_A).hexdigest()
        blob_b = conn.execute(
            "SELECT `data` FROM `character_account_data` "
            "WHERE `guid`=1 AND `type`=1").fetchone()[0]
        assert bytes(blob_b) == _BLOB_B
        # empty-vs-NULL blob distinction survives (time is NOT NULL; the
        # empty blob is real data, not NULL)
        nonempty = conn.execute(
            "SELECT `time`, `data` FROM `character_account_data` "
            "WHERE `guid`=2").fetchone()
        assert nonempty[0] == 1700000250 and bytes(nonempty[1]) == b""
        # NULL text vs empty string
        subject, body = conn.execute(
            "SELECT `subject`, `body` FROM `mail` WHERE `id`=10").fetchone()
        assert subject is None
        assert bytes(body) == \
            "body with\ttab and \\ backslash".encode("utf-8")
        # user state replaced the seed row (guid 1), and the row count
        # did not double (INSERT OR REPLACE, not plain INSERT)
        n = conn.execute("SELECT COUNT(*) FROM `characters`").fetchone()[0]
        assert n == 2
    finally:
        conn.close()


def test_reverse_direction_round_trips_the_database(tmp_path) -> None:
    """Emit TSV from the imported SQLite side (the client-convention
    emitter), decode+import into a fresh copy: identical database."""
    live, _ = _bridge_into(tmp_path)
    conn = sqlite3.connect(str(live))
    conn.text_factory = bytes
    try:
        tables = [r[0].decode() for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%'")]
        tsv = {}
        for table in tables:
            cols = bridge.table_columns(conn, table)
            sel = ", ".join(f'"{c}"' for c, _, _ in cols)
            rows = []
            for row in conn.execute(f'SELECT {sel} FROM "{table}"'):
                out = []
                for value, (name, decl, _notnull) in zip(row, cols):
                    if value is None:
                        out.append(None)
                    elif (decl.split()[0] if decl else "") == "BLOB":
                        out.append(bytes(value).hex().encode("ascii"))
                    elif isinstance(value, bytes):
                        out.append(value)
                    else:  # INTEGER/REAL/str from the SQLite side
                        out.append(str(value).encode("utf-8"))
                rows.append(out)
            tsv[table] = bridge.encode_tsv(rows)
    finally:
        conn.close()
    second = tmp_path / "second.sqlite"
    _make_target(second)
    counts = bridge.import_database(second, tsv)
    assert sum(counts.values()) > 0
    # compare every table's every row
    a = sqlite3.connect(str(live))
    b = sqlite3.connect(str(second))
    a.text_factory = b.text_factory = bytes
    try:
        for table in tsv:
            qa = list(a.execute(f'SELECT * FROM "{table}"'))
            qb = list(b.execute(f'SELECT * FROM "{table}"'))
            assert qa == qb, table
    finally:
        a.close()
        b.close()


def test_ragged_tsv_fails_loud_with_no_partial_commit(tmp_path) -> None:
    live = tmp_path / "classiccharacters.sqlite"
    _make_target(live)
    before = live.read_bytes()
    good = bridge.encode_tsv([[b"3", b"9", b"Ragged", b"1", b"1"]])
    # truncated MID-ROW: three fields, five required, AND no trailing
    # newline - the strict decoder diagnoses the unterminated
    # tail first; a terminated ragged row still matches 'ragged'
    truncated = "3\t9\tRagged"
    with pytest.raises(bridge.BridgeError, match="unterminated|ragged"):
        bridge.import_database(live, {"characters": good + truncated})
    assert live.read_bytes() == before        # untouched
    assert not (tmp_path / "classiccharacters.sqlite.partial").exists()


def test_leftover_partial_from_killed_run_is_recovered(tmp_path) -> None:
    live = tmp_path / "classiccharacters.sqlite"
    _make_target(live)
    partial = tmp_path / "classiccharacters.sqlite.partial"
    partial.write_bytes(b"orphaned kill -9 debris")
    tsv = {"characters": bridge.encode_tsv(
        [[b"5", b"7", b"Survivor", b"1", b"1700000000"]])}
    counts = bridge.import_database(live, tsv)
    assert counts == {"characters": 1}
    assert not partial.exists()
    conn = sqlite3.connect(str(live))
    try:
        name = conn.execute(
            "SELECT `name` FROM `characters` WHERE `guid`=5").fetchone()
        assert name == ("Survivor",)
    finally:
        conn.close()


def test_human_slice_excludes_regenerable_bot_tables() -> None:
    live = None
    conn = sqlite3.connect(":memory:")
    try:
        conn.executescript(
            "CREATE TABLE `characters` (`guid` INTEGER);"
            "CREATE TABLE `ai_playerbot_movers` (`id` INTEGER);")
        tables = bridge.human_slice_tables(conn)
    finally:
        conn.close()
    assert tables == ["characters"]


def test_broken_utf8_in_a_text_column_fails_loud(tmp_path) -> None:
    live = tmp_path / "classiccharacters.sqlite"
    _make_target(live)
    bad = bridge.encode_field(b"not-\xff-utf8")  # lone 0xFF byte
    tsv = f"4\t7\t{bad}\t1\t1\n"
    with pytest.raises(bridge.BridgeError, match="UTF-8"):
        bridge.import_database(live, {"characters": tsv})


def test_null_for_not_null_target_fails_loud(tmp_path) -> None:
    """Regression pin for the masked-DEFAULT discovery gate (SQLite's
    INSERT OR REPLACE silently stores the column DEFAULT when an
    explicit NULL violates NOT NULL) - only the Kotlin twin tested
    it before. NULL for characters.account (NOT NULL) must refuse loud with
    the live file untouched and no .partial left behind."""
    live = tmp_path / "classiccharacters.sqlite"
    _make_target(live)
    before = live.read_bytes()
    tsv = bridge.encode_tsv([[b"9", None, b"nullaccount", b"2", b"3"]])
    with pytest.raises(bridge.BridgeError, match="NOT NULL"):
        bridge.import_database(live, {"characters": tsv})
    assert live.read_bytes() == before
    assert not live.with_name(live.name + ".partial").exists()


def test_nonempty_wal_sidecar_is_refused_loud(tmp_path) -> None:
    """A committed-but-uncheckpointed -wal would be silently
    dropped by the byte copy. A live database holding a non-empty
    sidecar (connection still open) must be refused before any copy."""
    live = tmp_path / "classiccharacters.sqlite"
    _make_target(live)
    conn = sqlite3.connect(str(live))
    try:
        assert conn.execute("PRAGMA journal_mode=WAL").fetchone()[0] == "wal"
        conn.execute("INSERT INTO `characters` (`account`,`name`) VALUES (5,'walrow')")
        conn.commit()
        sidecar = live.with_name(live.name + "-wal")
        assert sidecar.exists() and sidecar.stat().st_size > 0
        tsv = {"characters": bridge.encode_tsv([[b"2", b"1", b"x", b"1", b"1"]])}
        with pytest.raises(bridge.BridgeError, match="sidecar"):
            bridge.import_database(live, tsv)
    finally:
        conn.close()


def test_wal_mode_is_restored_after_import(tmp_path) -> None:
    """The import transaction runs in DELETE journal mode;
    a WAL-mode live database (cleanly closed, sidecar gone) must come
    out of the import still WAL, not silently DELETE-persisted."""
    live = tmp_path / "classiccharacters.sqlite"
    _make_target(live)
    conn = sqlite3.connect(str(live))
    try:
        assert conn.execute("PRAGMA journal_mode=WAL").fetchone()[0] == "wal"
        conn.execute("INSERT INTO `characters` (`account`,`name`) VALUES (5,'walrow')")
        conn.commit()
    finally:
        conn.close()  # clean close: checkpoints and removes the sidecar
    assert not live.with_name(live.name + "-wal").exists()
    tsv = {"characters": bridge.encode_tsv([[b"3", b"1", b"x", b"1", b"1"]])}
    bridge.import_database(live, tsv)
    check = sqlite3.connect(str(live))
    try:
        assert check.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
        names = [r[0] for r in check.execute("SELECT `name` FROM `characters`")]
        # user row + prior WAL-committed state + seed row all present
        # (guid 3 does not collide with walrow's auto id 2)
        assert "x" in names and "walrow" in names and "seed-placeholder" in names
    finally:
        check.close()


def test_decode_tsv_strict_is_the_staged_file_entry() -> None:
    """The host twin of DatabaseUserStateBridge.
    decodeTsvBytes - byte-level entry, interior empty lines preserved
    (single-column empty-string rows), unterminated trailing rows and
    invalid UTF-8 refuse loud."""
    rows = [[b"1", None], [b"tab\there", "\U0001F600".encode()], [b""]]
    text = bridge.encode_tsv(rows)
    decoded = bridge.decode_tsv_strict(text.encode("utf-8"))
    assert decoded == rows
    # invalid UTF-8 in a field refuses loud (never U+FFFD)
    with pytest.raises(bridge.BridgeError, match="UTF-8"):
        bridge.decode_tsv_strict(b"1\t\xff\n")
    # an unterminated trailing row is interrupted staging, not data
    with pytest.raises(bridge.BridgeError, match="unterminated"):
        bridge.decode_tsv_strict(b"1\n3")


def test_outfile_export_query_is_the_shipped_wire(tmp_path) -> None:
    """The canonical statement bytes live in ONE shared
    fixture (tests/p5_outfile_wire_fixture.txt) that BOTH this test and
    the Kotlin twin's outfileExportQueryIsExactlyTheMysqldumpTabMechanism
    compare against - the runtimes cannot diverge on the export wire.
    The literals in the statement are SQL escape sequences
    (backslash+t, two backslashes in ESCAPED BY, backslash+n); the
    sql_mode pin makes the parse independent of the server global."""
    cols = [("guid", "int"), ("name", "varchar"), ("data", "longblob"),
            ("flag", "bit")]
    q = bridge.outfile_export_query(
        "classiccharacters", "account_data", cols,
        "/data/root/import/page.tsv",
        primary_key=["guid", "name"], offset=1000, limit=500)
    fixture = (ROOT / "tests" / "p5_outfile_wire_fixture.txt").read_text()
    assert q == fixture
    # the fixture itself carries the load-bearing shapes
    assert "SET SESSION sql_mode='';" in fixture
    assert "FROM `classiccharacters`.`account_data`" in fixture
    assert fixture.endswith(
        "INTO OUTFILE '/data/root/import/page.tsv' "
        + "FIELDS TERMINATED BY '" + chr(92) + "t' ENCLOSED BY '' "
        + "ESCAPED BY '" + chr(92) + chr(92) + "' "
        + "LINES TERMINATED BY '" + chr(92) + "n';\n")
    # no primary key -> ORDER BY ALL columns (four slice tables have no
    # PK; undefined LIMIT/OFFSET order silently duplicates/drops rows)
    q2 = bridge.outfile_export_query("classiccharacters", "account_data",
                                     cols, "/p.tsv")
    # the no-PK form is second-fixture-pinned (both suites compare the
    # same bytes)
    fixture2 = (ROOT / "tests" / "p5_outfile_wire_fixture_nopk.txt").read_text()
    assert q2 == fixture2
    assert "ORDER BY `guid`, `name`, `data`, `flag`" in fixture2
    # byte-level newline hygiene: the fixtures must
    # never carry a CR
    assert b"\r" not in (ROOT / "tests" / "p5_outfile_wire_fixture.txt").read_bytes()
    assert b"\r" not in (ROOT / "tests" / "p5_outfile_wire_fixture_nopk.txt").read_bytes()
    # the blob family HEX-wraps by TYPE (append-path rule), not only by
    # the two pinned names
    assert bridge.is_export_blob_column("t", "payload", "varbinary")
    assert not bridge.is_export_blob_column("t", "payload", "varchar")
    # and the source-truth cross-check query is pinned
    assert bridge.count_query("characters") == "SELECT COUNT(*) FROM `characters`;"
