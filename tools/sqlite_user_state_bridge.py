#!/usr/bin/env python3
"""P5/G7 user-state bridge: MariaDB datadir -> SQLite provider (core).

Direction of use (dual-provider window first boot): the OLD (MariaDB)
provider must still boot to export (F13/F44 - fail-closed seals make
the window mandatory; the next release drops MariaDB only after the
translation is proven). This module is the host-testable core of the
translation:

  - EXPORT QUERIES: per-table ``SELECT ..., HEX(<blob>), ...`` in the
    spec's batch-TSV design, with the session pinned to UTC FIRST (the
    P3 tz footnote's normalization - by construction, not per-value
    conversion: MySQL TIMESTAMP columns convert on read using the
    session tz, so the pin reads them at UTC; DATETIME columns are
    tz-agnostic literals and cross as stored (device-local wall clock)
    - recorded residual, P5 R1 I-84). The shipped export runs as
    ``SELECT ... INTO OUTFILE`` (outfile_export_query) - the mysqldump
    --tab mechanism itself, so the server performs exactly this
    module's TSV escaping.
  - TSV CODEC: the mysqldump --tab convention the P5 spec mandates -
    fields tab-separated, records newline-terminated, ``\t \n \0
    \\`` escaped inside fields (the server's default INTO OUTFILE
    escape set; a raw CR crosses unescaped and the codec decodes both
    forms), NULL as the two-char ``\\N`` marker.
    Unambiguous: a literal backslash-N TEXT value arrives escaped as
    two backslashes + N and decodes back to itself.
  - TRANSACTIONAL IMPORT: user rows land in the seeded SQLite datadir
    with INSERT OR REPLACE (user state wins over any seed row - the
    seed owns schema + static content, the datadir owns the user), BLOB
    columns imported from the exporter's HEX text as X'...'-equivalent
    bytes. Imports write to ``<name>.partial`` and are atomically
    os.replace()d onto the live path only when clean (the pre-registered
    P5 sentinel); a leftover ``.partial`` from a killed run is detected
    and removed before the next attempt.

The wire contract is OURS (the shipped client must emit the
mysqldump-TSV convention); the on-device integration leg validates the
client against this contract when the window lane runs.
"""
from __future__ import annotations

import os
import sqlite3
from pathlib import Path

# The exporter prelude: pin the session timezone so every TIMESTAMP
# read converts to UTC (P3 footnote normalization, by construction).
# DATETIME columns are tz-agnostic literals and cross as stored (the
# recorded residual, P5 R1 I-84).
UTC_PIN = "SET time_zone = '+00:00'"

# The two longblob columns the P5 spec calls out (characters.sql:61 and
# :156 - account_data.data / character_account_data.data): corrupt
# without HEX encoding on export (F44).
BLOB_COLUMNS: dict[str, set[str]] = {
    "account_data": {"data"},
    "character_account_data": {"data"},
}

# The user-state slice (recorded in the plan ledger, P5):
#   - classiccharacters: every table EXCEPT the ai_playerbot_* tables
#     (bots are regenerable; their config/learning tables carry the
#     bot_ prefix and ride along - they are small and user-adjacent);
#     the world-ish runtime tables (corpse, respawns, instance state,
#     world_state, saved_variables) are per-datadir state and translate.
#   - classicrealmd: account + bans + realmcharacters + the anticheat
#     fingerprint history; operational logs (account_logons, uptime) and
#     seed-owned tables (realmlist, antispam_*, db_version) do not.
USER_STATE_TABLES: dict[str, list[str]] = {
    "classiccharacters": [],  # populated from the seeded schema below
    "classicrealmd": [
        "account", "account_banned", "ip_banned", "realmcharacters",
        "system_fingerprint_usage",
    ],
}

_EXPORT_PAGE = 500


def _unquote(name: str) -> str:
    return name.strip("`")


def table_columns(target: sqlite3.Connection,
                  table: str) -> list[tuple[str, str, int]]:
    """(column, declared-type, notnull) list from the TARGET schema.
    The notnull flag matters: SQLite's INSERT OR REPLACE silently
    substitutes the column DEFAULT when an explicit NULL violates a
    NOT NULL constraint (empirically verified) - the bridge must fail
    loud on such rows instead of storing masked defaults. Column names
    are decoded when the caller set text_factory=bytes (schema reads
    are ASCII by construction)."""
    def _s(v) -> str:
        return v.decode("utf-8") if isinstance(v, bytes) else v

    return [(_s(r[1]), _s(r[2] or "").upper(), r[3]) for r in
            target.execute(f'PRAGMA table_info("{table}")')]


def human_slice_tables(target: sqlite3.Connection) -> list[str]:
    """The classiccharacters export list: every table except the
    regenerable ai_playerbot_* tables (decided P5; see module docstring)."""
    tables = [r[0] for r in target.execute(
        "SELECT name FROM sqlite_master WHERE type='table' "
        "AND name NOT LIKE 'sqlite_%' ORDER BY name")]
    return [t for t in tables if not t.startswith("ai_playerbot")]


# Source DATA_TYPE values that export as HEX text (the blob family; the
# BLOB_COLUMNS name map pins the two known slice longblobs as defense in
# depth - P5 R1: the family rule guards the append path).
_EXPORT_BLOB_TYPES = {"BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB",
                      "VARBINARY", "BINARY"}


def is_export_blob_column(table: str, name: str, source_data_type: str) -> bool:
    base = source_data_type.split()[0] if source_data_type else ""
    return name in BLOB_COLUMNS.get(table, set()) or base.upper() in _EXPORT_BLOB_TYPES


def outfile_export_query(database: str, table: str,
                         columns: list[tuple[str, str]],
                         out_file: str, primary_key: list[str] | None = None,
                         offset: int = 0, limit: int = _EXPORT_PAGE) -> str:
    """The SHIPPED export wire (Kotlin twin of
    DatabaseUserStateBridge.outfileExportQuery): one paged statement
    whose escaping the SERVER performs via INTO OUTFILE - the mysqldump
    --tab mechanism itself, no client-stdout boundary (P5 R1 I-77).
    Bit columns are normalized with CAST(.. AS UNSIGNED) (I-85).
    The field/line literals are SQL ESCAPE SEQUENCES - backslash+t
    etc. in the emitted text, and TWO backslashes inside ESCAPED BY
    so the server reads one (P5 R3 I-107: an earlier edit emitted
    raw control bytes and a lone backslash, which under the default
    sql_mode breaks the ESCAPED BY literal's quoting entirely). The
    qualified db.table makes the statement invocation-context-free
    (I-E1R3: the shipped page call passes no --database); the
    sql_mode pin (mysqldump precedent; read-only session) makes the
    backslash-escape literals parse identically regardless of the
    server's global mode.)
    """
    cols = []
    for name, decl, *_ in columns:
        base = decl.split()[0] if decl else ""
        if is_export_blob_column(table, name, decl):
            cols.append(f"HEX(`{name}`)")
        elif base.upper() == "BIT":
            cols.append(f"CAST(`{name}` AS UNSIGNED)")
        else:
            cols.append(f"`{name}`")
    order_columns = list(primary_key) if primary_key else [c[0] for c in columns]
    order = f" ORDER BY {', '.join(f'`{c}`' for c in order_columns)}"
    tail = "FIELDS TERMINATED BY '\\t' ENCLOSED BY '' ESCAPED BY '\\\\' LINES TERMINATED BY '\\n';"
    head = f"{UTC_PIN};\nSET NAMES utf8mb4;\nSET SESSION sql_mode='';\n"
    return (head +
            f"SELECT {', '.join(cols)} FROM `{database}`.`{table}`{order} "
            f"LIMIT {int(limit)} OFFSET {int(offset)} "
            f"INTO OUTFILE '{out_file}' "
            f"{tail}\n")


def count_query(table: str) -> str:
    """The source-truth row count cross-check (I-78). INVOCATION
    CONTRACT: the FROM is unqualified - the caller must pass the
    database to the client invocation (the engine's count leg does)."""
    return f"SELECT COUNT(*) FROM `{table}`;"


# ---------------------------------------------------------------------------
# TSV codec (mysqldump --tab convention)
# ---------------------------------------------------------------------------

_ESCAPES = {"t": "\t", "n": "\n", "r": "\r", "0": "\0", "\\": "\\"}


def decode_field(field: str) -> bytes | None:
    """One TSV field -> value bytes (None for NULL)."""
    if field == "\\N":
        return None
    out = bytearray()
    i = 0
    n = len(field)
    while i < n:
        c = field[i]
        if c == "\\" and i + 1 < n and field[i + 1] in _ESCAPES:
            out += _ESCAPES[field[i + 1]].encode("latin-1")
            i += 2
            continue
        out += c.encode("utf-8", "surrogateescape")
        i += 1
    return bytes(out)


def encode_field(value: bytes | None) -> str:
    """Value bytes -> one TSV field (the inverse of decode_field).
    surrogateescape on both sides keeps non-UTF-8 byte sequences
    round-trip-exact."""
    if value is None:
        return "\\N"
    text = value.decode("utf-8", errors="surrogateescape")
    return (text.replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\0", "\\0"))


def decode_tsv(text: str) -> list[list[bytes | None]]:
    """Full batch-TSV text -> rows of value bytes."""
    if not text:
        return []
    rows: list[list[bytes | None]] = []
    for line in text.split("\n"):
        if line == "":
            continue
        rows.append([decode_field(f) for f in line.split("\t")])
    return rows


def decode_tsv_strict(data: bytes) -> list[list[bytes | None]]:
    """Staged-file entry (Kotlin twin of DatabaseUserStateBridge.
    decodeTsvBytes, P5 R4 B-3): decode batch-TSV BYTES directly - no
    String boundary in front of the fail-loud UTF-8 gate. Escaped
    fields never contain raw 0x09/0x0A, so byte-level splitting is
    exact; an interior empty line is a legal single-column
    empty-string row (preserved); an unterminated trailing row
    refuses LOUD (interrupted staging)."""
    if not data:
        return []
    rows: list[list[bytes | None]] = []
    row: list[bytes | None] = []
    start = 0

    def _field(piece: bytes) -> bytes | None:
        try:
            text = piece.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise BridgeError(
                f"TSV field is not valid UTF-8: {piece[:40]!r}") from exc
        return decode_field(text)

    for i, b in enumerate(data):
        if b == 0x09:  # tab
            row.append(_field(data[start:i]))
            start = i + 1
        elif b == 0x0A:  # newline
            row.append(_field(data[start:i]))
            rows.append(row)
            row = []
            start = i + 1
    if start != len(data) or row:
        raise BridgeError("unterminated trailing TSV row - interrupted staging?")
    return rows


def encode_tsv(rows: list[list[bytes | None]]) -> str:
    """Rows of value bytes -> batch-TSV text (client-convention emitter,
    used by the round-trip harness and the reverse direction)."""
    return "".join(
        "\t".join(encode_field(v) for v in row) + "\n" for row in rows)


# ---------------------------------------------------------------------------
# Transactional import
# ---------------------------------------------------------------------------

class BridgeError(RuntimeError):
    pass


def _coerce(value: bytes | None, decl: str) -> object:
    """TSV value bytes -> a sqlite3 parameter for a column of the
    declared target type."""
    if value is None:
        return None
    base = decl.split()[0] if decl else ""
    if base == "BLOB":
        return value
    # INTEGER/REAL/TEXT affinity: SQLite accepts bytes for any column,
    # but decoding text columns here makes type errors loud and early.
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BridgeError(
            f"user-state value is not valid UTF-8 for a "
            f"{base or 'TEXT'} column: {value[:40]!r}") from exc


def import_table(target: sqlite3.Connection, table: str,
                 tsv_text: bytes | str) -> int:
    """Decode one table's batch-TSV text and INSERT OR REPLACE the rows
    into the target (user state wins over seed rows). Fails loud on
    ragged rows (interrupted export) - the caller's transaction rolls
    back, nothing partial lands."""
    columns = table_columns(target, table)
    if not columns:
        raise BridgeError(f"target schema has no table {table!r}")
    # R5 (C): the import consumes the STRICT staged-file semantics -
    # bytes route through decode_tsv_strict (unterminated trailing rows
    # and dropped interior empty lines refuse/preserve exactly like the
    # shipped Kotlin decodeTsvBytes); str input stays supported for the
    # codec-level tests (encoded to UTF-8 first).
    rows = (decode_tsv_strict(tsv_text) if isinstance(tsv_text, bytes)
            else decode_tsv_strict(
                tsv_text.encode("utf-8", "surrogateescape")))
    sql = (f'INSERT OR REPLACE INTO "{table}" '
           f'({", ".join(chr(34) + c + chr(34) for c, _, _ in columns)}) '
           f'VALUES ({", ".join("?" * len(columns))})')
    imported = 0
    for row in rows:
        if len(row) != len(columns):
            raise BridgeError(
                f"{table}: ragged TSV row ({len(row)} fields vs "
                f"{len(columns)} columns) - interrupted export?")
        params = []
        for value, (name, decl, notnull) in zip(row, columns):
            if value is None:
                if notnull:
                    raise BridgeError(
                        f"{table}.{name}: NULL for a NOT NULL target "
                        f"column (INSERT OR REPLACE would silently "
                        f"store the DEFAULT - source/export anomaly)")
                params.append(None)
                continue
            if name in BLOB_COLUMNS.get(table, set()) or \
                    (decl.split()[0] if decl else "") == "BLOB":
                try:
                    params.append(bytes.fromhex(value.decode("ascii")))
                    continue
                except ValueError as exc:
                    raise BridgeError(
                        f"{table}.{name}: blob column did not arrive "
                        f"as HEX text: {value[:40]!r}") from exc
            params.append(_coerce(value, decl))
        target.execute(sql, params)
        imported += 1
    return imported


def import_database(live_path: Path, tables_tsv: dict[str, str]) -> dict:
    """Import {table: tsv} into the SQLite database at live_path,
    atomically: the live file is copied to <name>.partial, user rows are
    merged inside one transaction, and the partial is os.replace()d onto
    the live path only when clean. A leftover .partial from a killed run
    is removed first (interrupted-translation recovery). Refuses LOUD
    when a non-empty -wal/-shm sidecar exists (committed-but-
    uncheckpointed frames would be silently dropped by the byte copy -
    P5 R1 I-81: the recorded flow imports freshly seeded, cleanly
    closed databases; anything else is an anomaly worth stopping for)
    and restores the live file's pre-import journal mode after the
    merge (the import transaction runs in DELETE mode; a WAL-mode live
    database must not silently ship DELETE-persisted)."""
    for suffix in ("-wal", "-shm", "-journal"):
        sidecar = live_path.with_name(live_path.name + suffix)
        if sidecar.exists() and sidecar.stat().st_size > 0:
            raise BridgeError(
                f"live database has a non-empty {suffix} sidecar; "
                f"checkpoint/close it before import: {sidecar}")
    if not live_path.exists():
        raise BridgeError(f"live database missing: {live_path}")
    pre_mode = None
    # read-only URI probe: a plain connect can trigger hot-journal
    # recovery on the LIVE file before the copy (P5 R3, B I-B)
    probe = sqlite3.connect(
        f"file:{live_path.as_posix()}?mode=ro", uri=True)
    try:
        row = probe.execute("PRAGMA journal_mode").fetchone()
        pre_mode = row[0] if row else None
    finally:
        probe.close()
    partial = live_path.with_name(live_path.name + ".partial")
    if partial.exists():
        # killed mid-run: the live file was never touched (the replace
        # is the commit point); discard the orphan and start over.
        partial.unlink()
    # byte-exact copy (sidecars were refused above)
    data = live_path.read_bytes()
    partial.write_bytes(data)
    counts: dict[str, int] = {}
    try:
        conn = sqlite3.connect(str(partial))
        conn.isolation_level = None  # explicit BEGIN/COMMIT control
        try:
            conn.execute("PRAGMA journal_mode=DELETE")
            conn.execute("PRAGMA foreign_keys=OFF")
            conn.execute("BEGIN")
            try:
                for table, tsv in tables_tsv.items():
                    counts[table] = import_table(
                        conn, table,
                        tsv if isinstance(tsv, bytes)
                        else tsv.encode("utf-8", "surrogateescape"))
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            if pre_mode and pre_mode.lower() != "delete":
                conn.execute(f"PRAGMA journal_mode={pre_mode}")
        finally:
            # close BEFORE any unlink/replace: Windows holds an
            # exclusive lock while the connection is open
            conn.close()
    except Exception:
        partial.unlink(missing_ok=True)
        raise
    os.replace(partial, live_path)
    return counts
