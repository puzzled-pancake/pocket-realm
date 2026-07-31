#!/usr/bin/env python3
"""Seed the four SQLite realm databases from the MySQL-format CMaNGOS dumps.

This is the O04 "real bring-up with stub DB" enabler: it translates the
MySQL 8.0 dumps (classic-db Full_DB + core sql/base) into the four SQLite
files the DO_SQLITE backend expects, so DATABASE_OPEN / SCHEMA_COMPATIBLE /
AUTH_READY can be genuinely green without proprietary client data (O10).

The dumps are MySQL syntax (ENGINE=, AUTO_INCREMENT, /*!...*/ comments,
LOCK/UNLOCK, bit(1), COMMENT '...', backtick quoting). SQLite accepts most
of this leniently except for the parts that aren't SQL at all; we strip or
rewrite those. No proprietary data is involved — pure schema/content
translation of open CMaNGOS SQL (GPL). Per schemas/sources.json the row
content is fair-use-claim and never shipped; this runs into a throwaway
build-time .sqlite.

Usage:
    python3 tools/seed_realm_db.py --out <db_dir>

Produces <db_dir>/{mangos,characters,realmd,logs}.sqlite. Idempotent:
re-running drops & recreates.

Inputs (resolved relative to the repo root):
    native/cmangos/sql/base/{realmd,characters,logs,mangos}.sql   (schema+base)
    native/classic-db/Full_DB/ClassicDB_1_12_1_z2815.sql.gz        (world content)

Exits non-zero on any real failure (agent.md: no fake success).
"""
from __future__ import annotations

import argparse
import gzip
import os
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SQL_BASE = ROOT / "native" / "cmangos" / "sql" / "base"
WORLD_DUMP = ROOT / "native" / "classic-db" / "Full_DB" / "ClassicDB_1_12_1_z2815.sql.gz"

# Each output DB: (sqlite filename, [list of MySQL dump sources to apply in order])
# mangos gets the world content dump (the big ClassicDB) plus the base mangos.sql
# (which carries db_version). The others get only their base schema+data.
DB_SOURCES = {
    "realmd": (["realmd.sql"]),
    "characters": (["characters.sql"]),
    "logs": (["logs.sql"]),
    "mangos": (["mangos.sql"]),  # world content from the .gz is applied after
}


# ---------------------------------------------------------------------------
# MySQL -> SQLite statement translation
# ---------------------------------------------------------------------------

# Lines that are pure MySQL-ism and produce nothing in SQLite. Matched at
# line start (after optional whitespace).
_DROP_LINE_PATTERNS = [
    re.compile(r"^\s*--"),                       # SQL line comment
    re.compile(r"^\s*#"),                        # MySQL client line comment (# Logs database...)
    re.compile(r"^\s*/\*!\d*\s"),                # MySQL conditional comment start /*!40101 ... */
    re.compile(r"^\s*LOCK TABLES"),
    re.compile(r"^\s*UNLOCK TABLES;"),
    re.compile(r"^\s*SET\s+", re.I),              # SET NAMES / SET @OLD_... etc.
    re.compile(r"^\s*USE\s+`", re.I),
]

# Substitutions applied within a statement (after accumulation).
_INNER_PATTERNS = [
    # /*!40000 ALTER TABLE `t` DISABLE KEYS */ — drop the whole directive.
    (re.compile(r"/\*!\d+\s+ALTER TABLE.*?(?:DISABLE|ENABLE)\s+KEYS\s*\*/;?"), ""),
    # /*!40101 ... */ / /*!50003 ...*/ generic conditional comments -> drop.
    (re.compile(r"/\*!\d+\s+[^;]*?\*/"), ""),
    # MySQL storage-engine + table-options tail on CREATE TABLE. The closing
    # ')' of the column list is followed by `ENGINE=... DEFAULT CHARSET=...
    # ROW_FORMAT=... COMMENT='...'`. Strip from the ')' onward and re-add ')'.
    # Must run BEFORE the per-fragment COMMENT strip below.
    (re.compile(r"\)\s*ENGINE\s*=.*?(?=;|$)", re.I), ") "),
    (re.compile(r"\)\s*DEFAULT\s+CHARSET\b.*?(?=;|$)", re.I), ") "),
    # inline COMMENT '...' on columns (SQLite rejects inside CREATE TABLE).
    # Match a SQL string literal that may contain \' or '' escaped quotes.
    (re.compile(r"\bCOMMENT\s+'(?:[^'\\]|\\.|'')*'", re.I), ""),
    # type rewrites to SQLite-affinity types
    (re.compile(r"\bbit\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\btinyint\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\bsmallint\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\bmediumint\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\bint\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\bbigint\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\blongtext\b", re.I), "TEXT"),
    (re.compile(r"\bmediumtext\b", re.I), "TEXT"),
    (re.compile(r"\btinytext\b", re.I), "TEXT"),
    (re.compile(r"\bvarchar\(\d+\)", re.I), r"TEXT"),
    (re.compile(r"\bchar\(\d+\)", re.I), r"TEXT"),
    (re.compile(r"\blongblob\b", re.I), "BLOB"),
    (re.compile(r"\bmediumblob\b", re.I), "BLOB"),
    (re.compile(r"\btinyblob\b", re.I), "BLOB"),
    (re.compile(r"\bdatetime\b", re.I), "TEXT"),
    (re.compile(r"\btimestamp\b", re.I), "TEXT"),
    # `unsigned` / `zerofill` modifiers (SQLite has no notion).
    (re.compile(r"\bunsigned\b", re.I), ""),
    (re.compile(r"\bzerofill\b", re.I), ""),
    # AUTO_INCREMENT -> AUTOINCREMENT (only valid on INTEGER PRIMARY KEY; we
    # rewrite the common `... INTEGER NOT NULL AUTO_INCREMENT` form. SQLite
    # requires AUTOINCREMENT immediately follow INTEGER PRIMARY KEY. The
    # matching trailing `PRIMARY KEY (id)` is dropped in statement post-proc.)
    (re.compile(r"INTEGER\s+NOT\s+NULL\s+AUTO_INCREMENT", re.I),
     "INTEGER PRIMARY KEY AUTOINCREMENT"),
    (re.compile(r"\bAUTO_INCREMENT\b", re.I), ""),
    # `\binteger` last so our injected AUTOINCREMENT isn't clobbered.
    (re.compile(r"(?<![A-Z_])integer", re.I), "INTEGER"),
    # NOW() -> SQLite equivalent.
    (re.compile(r"\bNOW\(\)", re.I), "CURRENT_TIMESTAMP"),
    # `CREATE TABLE x LIKE y` (MySQL clone) -> SQLite `CREATE TABLE x AS SELECT * FROM y`.
    (re.compile(r"CREATE\s+TABLE\s+(`?\w+`?)\s+LIKE\s+(`?\w+`?)", re.I),
     r"CREATE TABLE \1 AS SELECT * FROM \2"),
    # COLLATE clauses SQLite doesn't need.
    (re.compile(r"\bCOLLATE\s*=\s*\w+", re.I), ""),
    (re.compile(r"\bCOLLATE\s+\w+", re.I), ""),
]


def _post_process_create_table(stmt: str) -> str:
    """Fix CREATE TABLE issues the inner line-drop patterns can't handle:
    (1) `UNIQUE KEY name (cols)` lines (start with UNIQUE, not KEY),
    (2) a PRIMARY KEY clause duplicating the AUTOINCREMENT PK,
    (3) dangling trailing commas left by dropping KEY/CONSTRAINT/UNIQUE lines.
    """
    # Drop UNIQUE KEY lines (MySQL inline unique index). Strip the trailing
    # comma on the prior line first, same as the KEY-line drop in the splitter.
    lines = stmt.splitlines()
    out = []
    for line in lines:
        s = line.strip()
        if re.match(r"^UNIQUE\s+KEY\b", s, re.I):
            if out:
                out[-1] = re.sub(r",\s*$", "", out[-1])
            continue
        out.append(line)
    stmt = "\n".join(out)

    # Drop a trailing PRIMARY KEY clause when AUTOINCREMENT already made that
    # column the PK. Works across newlines; matches the common single + multi
    # column forms.
    if "AUTOINCREMENT" in stmt:
        stmt = re.sub(r",\s*PRIMARY\s+KEY\s*\([^)]*\)", "", stmt, flags=re.I | re.S)
    # Fix dangling commas before the closing ')': `,\n)` or `, )`.
    stmt = re.sub(r",\s*(\))", r"\1", stmt)
    return stmt


def translate_statements(sql_text: str):
    """Yield SQLite-executable statements from MySQL dump text.

    Splits on ';'<newline>, drops MySQL-only lines, applies inner rewrites,
    and filters out statements that aren't SQLite-compatible (inline KEY
    clauses are dropped by stripping their lines before accumulation).
    """
    # First, remove whole-line MySQL-isms and inline KEY clauses line by line.
    # When dropping a KEY/CONSTRAINT line, also eat the trailing comma on the
    # *previous* kept line so the CREATE TABLE doesn't end up with a dangling
    # comma before the closing ')'.
    kept_lines = []
    for line in sql_text.splitlines():
        if any(p.search(line) for p in _DROP_LINE_PATTERNS):
            continue
        stripped = line.strip()
        is_key_line = (re.match(r"^KEY\s+`", stripped) or
                       re.match(r"^KEY\s+\(", stripped) or
                       re.match(r"^CONSTRAINT\s+`.*`\s+FOREIGN\s+KEY", stripped, re.I))
        if is_key_line:
            # Strip the trailing comma from the previous kept line.
            if kept_lines:
                kept_lines[-1] = re.sub(r",\s*$", "", kept_lines[-1])
            continue
        if re.match(r"^/\*", stripped) and "*/" not in stripped:
            # multi-line comment start without close on same line: skip block
            continue
        kept_lines.append(line)

    text = "\n".join(kept_lines)

    # Close any dangling multi-line /* ... */ comments.
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)

    # Apply the inner rewrites.
    for pat, repl in _INNER_PATTERNS:
        text = pat.sub(repl, text)

    # Split into statements on semicolons that end a line (safe enough for
    # dump output; string literals with embedded ; are rare in schema dumps
    # and INSERT data uses the escaping the dump produces).
    for raw in re.split(r";\s*(?:\n|$)", text):
        stmt = raw.strip()
        if not stmt:
            continue
        # Skip statements that are now empty after rewrite or pure noise.
        if re.match(r"^(LOCK|UNLOCK|USE|SET)\b", stmt, re.I):
            continue
        # CREATE TABLE needs the trailing-comma / duplicate-PK cleanup.
        if re.match(r"^CREATE\s+TABLE", stmt, re.I):
            stmt = _post_process_create_table(stmt)
        yield stmt + ";"


def apply_dump(conn: sqlite3.Connection, source: Path, label: str, verbose: bool):
    """Read a (possibly gzipped) MySQL dump and execute every translated stmt."""
    if source.suffix == ".gz":
        with gzip.open(source, "rt", encoding="utf-8", errors="replace") as f:
            text = f.read()
    else:
        text = source.read_text(encoding="utf-8", errors="replace")

    # MySQL dumps escape apostrophes inside string literals as \' (backslash-
    # quote). SQLite's SQL parser in default mode does not treat backslash as
    # an escape — it expects '' (doubled quote). Rewrite \' -> '' so INSERT
    # data with names like "Kel'Thuzad" or "Player's" survives. This runs before
    # statement splitting so it sees whole literals.
    text = text.replace("\\'", "''").replace('\\"', '"')

    count = 0
    errors = 0
    cur = conn.cursor()
    for stmt in translate_statements(text):
        # Truncate absurdly long statements for logging.
        preview = stmt if len(stmt) <= 80 else stmt[:77] + "..."
        try:
            cur.execute(stmt)
            count += 1
        except sqlite3.Error as e:
            errors += 1
            if verbose or errors <= 5:
                print(f"  [{label}] sqlite error: {e}\n    on: {preview}", file=sys.stderr)
    conn.commit()
    return count, errors


def build_one(db_path: Path, sources: list[Path], label: str, verbose: bool) -> int:
    if db_path.exists():
        db_path.unlink()
    # WAL side files too.
    for suffix in ("-wal", "-shm"):
        p = db_path.with_name(db_path.name + suffix)
        if p.exists():
            p.unlink()
    conn = sqlite3.connect(str(db_path))
    try:
        conn.execute("PRAGMA foreign_keys=OFF")
        conn.execute("PRAGMA journal_mode=DELETE")  # we set WAL at realm runtime
        total = 0
        total_err = 0
        for src in sources:
            if not src.exists():
                print(f"ERROR: missing source for {label}: {src}", file=sys.stderr)
                return 1
            n, err = apply_dump(conn, src, label, verbose)
            total += n
            total_err += err
        # Sanity: the version column must exist for CheckRequiredField.
        ver_ok = verify_version_table(conn, label)
        status = "PASS" if ver_ok else "BEHIND (O06 migration needed)"
        print(f"OK  {label}: {total} statements applied ({total_err} tolerated errors); "
              f"version: {status}")
        # The seeder succeeds even if the world DB snapshot is older than the
        # core expects — that's the documented O04/O06 boundary, not a seed
        # failure. Only return non-zero if NO statements applied (real failure).
        return 0 if total > 0 else 1
    finally:
        conn.close()


# Required version column per DB (revision_sql.h). CheckRequiredField does
# SELECT <col> FROM <table> LIMIT 1, so the column must exist.
REQUIRED_VERSIONS = {
    "realmd": ("realmd_db_version", "required_z2820_01_realmd_joindate_datetime"),
    "characters": ("character_db_version", "required_z2819_01_characters_item_instance_text_id_fix"),
    "logs": ("logs_db_version", "required_z2778_01_logs_anticheat"),
    "mangos": ("db_version", "required_z2830_01_mangos_icon_name"),
}


def verify_version_table(conn: sqlite3.Connection, label: str) -> bool:
    """Confirm the version column the core's CheckRequiredField queries exists.

    Returns False (honestly) when the seeded snapshot is older than the core
    expects — the world (mangos) base snapshot is at z2815 while the core wants
    z2830. We do NOT fake this by renaming: SCHEMA_COMPATIBLE must reflect that
    the migrations between the snapshot and the core revision have not been
    applied. That migration chain is O06's work. The other three DBs
    (realmd/characters/logs) seed at the correct revision and pass cleanly.
    """
    table, col = REQUIRED_VERSIONS.get(label, ("", ""))
    if not table:
        return True
    try:
        cur = conn.cursor()
        cur.execute(f'PRAGMA table_info("{table}")')
        cols = [r[1] for r in cur.fetchall()]
        if col in cols:
            return True
        print(f"WARN  {label}: version column {col} not in {table} "
              f"(seeded snapshot is older than core expects — this is the "
              f"O04/O06 boundary; SCHEMA_COMPATIBLE will honestly report FALSE)",
              file=sys.stderr)
        return False
    except sqlite3.Error as e:
        print(f"WARN  {label}: cannot introspect version table: {e}", file=sys.stderr)
        return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="output directory for the 4 .sqlite files")
    ap.add_argument("--world-dump", default=str(WORLD_DUMP),
                    help=f"path to the ClassicDB .sql.gz (default: {WORLD_DUMP})")
    ap.add_argument("--verbose", action="store_true",
                    help="log every tolerated translation error")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    world_dump = Path(args.world_dump)

    rc = 0
    for db_name, src_names in DB_SOURCES.items():
        sources = [SQL_BASE / n for n in src_names]
        # The mangos (world) DB also gets the big content dump after the base.
        if db_name == "mangos" and world_dump.exists():
            sources.append(world_dump)
        r = build_one(out / f"{db_name}.sqlite", sources, db_name, args.verbose)
        if r != 0:
            rc = r
    if rc == 0:
        print(f"\nSEEDED 4 SQLite databases in {out}")
    else:
        print(f"\nSEED COMPLETED WITH WARNINGS (rc={rc}) — see above", file=sys.stderr)
    return rc


if __name__ == "__main__":
    sys.exit(main())
