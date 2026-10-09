#!/usr/bin/env python3
"""Seed the four SQLite realm databases from the MySQL-format CMaNGOS dumps.

This is the "real bring-up with stub DB" enabler: it translates the
MySQL 8.0 dumps (classic-db Full_DB + core sql/base) into the four SQLite
files the DO_SQLITE backend expects, so DATABASE_OPEN / SCHEMA_COMPATIBLE /
AUTH_READY can be genuinely green without proprietary client data (supervisor journal).

The dumps are MySQL syntax (ENGINE=, AUTO_INCREMENT, /*!...*/ comments,
LOCK/UNLOCK, bit(1), COMMENT '...', backtick quoting). SQLite accepts most
of this leniently except for the parts that aren't SQL at all; we strip or
rewrite those. No proprietary data is involved — pure schema/content
translation of open CMaNGOS SQL (GPL). Per schemas/sources.json the row
content is fair-use-claim and never shipped; this runs into a throwaway
build-time .sqlite.

Usage:
    python3 tools/seed_realm_db.py --out <db_dir> [--flavor vanilla|tbc|wotlk]

Produces four SQLite files per flavor. Vanilla keeps the historical
{mangos,characters,realmd,logs}.sqlite names byte-for-byte; tbc/wotlk name
their outputs after the flavor's databases block in schemas/flavor.json
(tbcrealmd.sqlite, tbcmangos.sqlite, ...). Idempotent: re-running drops &
recreates.

Flavor parameter (port plan S1 item 6; per-flavor seed execution is S4
item 3, implemented): every flavor seeds its OWN inputs, resolved through
tools/flavor_layout.py — the flavor core's sql/base, the flavor DB repo's
Full_DB content archive, the flavor's DB names, and the revision floors
parsed from the flavor core's src/shared/revision_sql.h. Every flavor
(vanilla included since review round 1 R2-2) refuses loud (rc=2) unless
its server_core and db_content pins are registered, checked out at the
pin, and clean — never a silent reuse of the classic inputs. The world
dump is refused on two legs before anything is written: PATH (it must
live under the flavor's own db repo) and CONTENT (its db_version
revision markers must belong to the flavor's own branch; R1-3), and a
db_content.seed that leaves the flavor's db repo refuses at plan
resolution (R2-1).

Inputs (resolved relative to the repo root, per flavor):
    <core>/sql/base/{realmd,characters,logs,mangos}.sql     (schema+base)
    <db repo>/Full_DB/*.sql.gz                               (world content)
  vanilla: native/cmangos + native/classic-db/Full_DB/ClassicDB_1_12_1_z2815.sql.gz

The mangos (world) DB requires the flavor's Full_DB content dump: a missing
dump (submodule not checked out, typo'd --world-dump) aborts the run rather
than seed a schema-only world DB that would still pass verify. --no-world-dump
is the explicit schema-only opt-in.

Per-flavor baselines (S4 item 3): a clean tbc/wotlk seed can pin its
measured table/row floors into schemas/sqlite-seed-baseline-<flavor>.json
(--write-baseline); every later seed is verified against those floors so a
hollow or partially-applied dump fails the lane (the vanilla baseline,
schemas/sqlite-seed-baseline.json, belongs to the manifest transcript lane —
tools/seed_sqlite_from_manifest.py --write-baseline — and is never touched
here).

Exits non-zero on any real failure (no fake success).
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sqlite3
import sys
from dataclasses import dataclass
from pathlib import Path

try:
    from tools import flavor_layout
except ImportError:  # direct execution: python tools/<script>.py
    import flavor_layout

try:
    from tools.sqlite_seed_translator import (
        _split_value_rows, _strip_comments_and_count,
        _sub_outside_literals, TranslationReport)
except ImportError:  # direct execution: python tools/<script>.py
    from sqlite_seed_translator import (
        _split_value_rows, _strip_comments_and_count,
        _sub_outside_literals, TranslationReport)

ROOT = Path(__file__).resolve().parents[1]
# Vanilla literals (the historical lane; _flavor_seed_plan() resolves the
# same paths for every flavor — for vanilla through these exact constants,
# byte-identical to the pre-parameterization behavior).
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
    # Bare table-COMMENT tail welded to the closing paren with no space and
    # no ENGINE= clause to anchor the strips above — the only table tail in
    # the corpus those two miss (mangos.sql:10317
    # `)COMMENT='Reference Loot Template Names';` -> 'near "=": syntax
    # error'). Anchored on `)` so a column-level COMMENT '...' never fires.
    (re.compile(r"\)\s*COMMENT\s*=.*?(?=;|$)", re.I), ") "),
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
                       # FULLTEXT KEY `name` (`name`) — MySQL fulltext index,
                       # exactly one site per tbc/wotlk Full_DB (CREATE TABLE
                       # areatrigger_teleport; zero sites in the vanilla
                       # corpus). SQLite has no fulltext index syntax inside
                       # CREATE TABLE ('near "FULLTEXT": syntax error'), so the
                       # line drops like any other inline index, with the same
                       # prior-line comma strip as the KEY drop.
                       re.match(r"^FULLTEXT\s+KEY\b", stripped, re.I) or
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

    # Column-def CHARACTER SET <name> clauses (mangos.sql:10545 and 12576:
    # `varchar(200) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci NOT
    # NULL`) are MySQL-isms SQLite cannot parse ('near "SET": syntax
    # error'). The trailing COLLATE <name> is already stripped below; only
    # the charset clause was missed. Stripped OUTSIDE string literals only -
    # help text may legitimately contain the phrase.
    text = _sub_outside_literals(
        text, re.compile(r"\bCHARACTER\s+SET\s+\w+\b", re.I), "")

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


# Matched pairs: escaped backslash (kept as-is), \' -> '', \" -> ".
_MYSQL_ESCAPE_RE = re.compile(r"\\\\|\\'|\\\"")
_MYSQL_ESCAPE_MAP = {"\\\\": "\\\\", "\\'": "''", '\\"': '"'}


def _translate_mysql_escapes(text: str) -> str:
    """Rewrite MySQL backslash escapes into SQLite-dialect literals.

    One left-to-right pass that consumes ``\\\\`` (escaped backslash) FIRST,
    as a unit, before considering ``\\'``/``\\"`` — otherwise a dump value
    ending in a backslash right before its closing quote (the
    ``antispam_replacement`` rows in realmd.sql: ``('/V\\\\','M')``) is
    misread as ``\\'`` and the blind rewrite corrupts the literal into
    ``'/V\\''`` + a bare ``M`` -> 'near "M": syntax error' at cur.execute
    (evidence LV-server-db-44). Then ``\\'`` -> ``''`` and ``\\"`` -> ``"``,
    which SQLite's parser accepts in default mode. Any other backslash
    sequence (\\n, \\r, \\0, ...) passes through unchanged, as before.
    """
    if "\\" not in text:
        return text
    # Regex alternation scans left-to-right and tries \\\\ first, so an
    # escaped backslash is consumed as one unit and can never pair with the
    # quote that follows it.
    return _MYSQL_ESCAPE_RE.sub(lambda m: _MYSQL_ESCAPE_MAP[m.group()], text)


# ---------------------------------------------------------------------------
# MySQL non-strict implicit-default backfill for listed-column INSERTs
# ---------------------------------------------------------------------------

# INSERT with an explicit column list — the only shape that can omit a
# column at all (positional INSERT INTO t VALUES (...) supplies every
# column or SQLite rejects the count). Body capture excludes the outermost
# parens; the trailing ';' is what translate_statements appends.
_INSERT_LISTED_RE = re.compile(
    r"^INSERT\s+INTO\s+`?(\w+)`?\s*\(([^()]*)\)\s*VALUES\s*\((.*)\)\s*;?\s*$",
    re.I | re.S)

_INT_DEFAULT_WORDS = ("INT", "REAL", "FLOA", "DOUB", "DEC", "NUM", "BOOL")


def _mysql_implicit_default(decl_type: str) -> str:
    """MySQL non-strict replay backfill for an omitted NOT NULL column with
    no DEFAULT: 0 for numeric types, '' for the string/blob family (dates
    render as TEXT in the translated schema and get '' here — no corpus
    INSERT hits that edge; the parity probes own any residual divergence)."""
    t = (decl_type or "").upper()
    if any(w in t for w in _INT_DEFAULT_WORDS):
        return "0"
    return "''"


def _backfill_mysql_implicit_defaults(cur: sqlite3.Cursor, stmt: str,
                                      label: str,
                                      schema_cache: dict) -> str:
    """Rewrite a listed-column INSERT that omits NOT NULL columns carrying
    no DEFAULT so it carries exactly the values the MySQL server the dumps
    were produced for would have replayed (0 / '').

    SQLite (the seam) refuses such rows outright — NOT NULL constraint
    failed — which killed the whole mangos seed on the base dump's
    entry-0 placeholder row (LVF-server-db-017: mangos.sql:936 inserts
    `creature_spawn_data_template` (`Entry`) VALUES (0) while `Name`
    VARCHAR(200) NOT NULL at mangos.sql:926 has no DEFAULT; same class:
    mangos.sql:2176 gossip_menu_option omitting box_broadcast_text,
    INT(11) NOT NULL no default at mangos.sql:2165). The row is never
    dropped or invented: it lands with MySQL's own implicit-default
    values, and every rewrite prints a record."""
    m = _INSERT_LISTED_RE.match(stmt)
    if not m:
        return stmt
    table, collist, body = m.group(1), m.group(2), m.group(3)
    info = schema_cache.get(table)
    if info is None:
        info = cur.execute(f'PRAGMA table_info("{table}")').fetchall()
        schema_cache[table] = info
    if not info:
        return stmt  # unknown table: execute() fails loud, as before
    provided = {c.strip().strip("`").lower() for c in collist.split(",")}
    pk_members = [r for r in info if r[5]]
    # A single-column INTEGER PK is the rowid alias: SQLite assigns it
    # exactly like MySQL assigns AUTO_INCREMENT, so omitting it is valid
    # (pet_name_generation's `id`). A multi-column PK member is not an
    # alias (creature_spawn_data_template's composite PK) and needs the
    # backfill like any other NOT NULL-no-default column.
    rowid_alias = (len(pk_members) == 1
                   and (pk_members[0][2] or "").strip().upper() == "INTEGER")
    missing = []
    for _cid, name, ctype, notnull, dflt, _pk in info:
        if name.lower() in provided or not notnull or dflt is not None:
            continue
        if rowid_alias and name == pk_members[0][1]:
            continue
        missing.append((name, _mysql_implicit_default(ctype)))
    if not missing:
        return stmt
    rows = _split_value_rows(body)
    if rows is None:
        return stmt  # not row-splittable: let execute() decide, fail loud
    defaults = ",".join(lit for _, lit in missing)
    rows = [row + "," + defaults for row in rows]
    names = ", ".join(f"`{name}`" for name, _ in missing)
    joined = "),\n(".join(rows)
    new_stmt = (f"INSERT INTO `{table}` ({collist.strip()}, {names}) "
                f"VALUES (\n{joined});")
    cols_desc = ", ".join(f"{name}={lit}" for name, lit in missing)
    print(f"  [{label}] mysql-implicit-default backfill: `{table}` INSERT "
          f"omitted NOT NULL column(s) with no DEFAULT; supplied "
          f"{cols_desc} per MySQL non-strict replay semantics "
          f"({len(rows)} row(s) rewritten, 0 dropped)")
    return new_stmt


def apply_dump(conn: sqlite3.Connection, source: Path, label: str, verbose: bool, tolerate_errors: bool = False):
    """Read a (possibly gzipped) MySQL dump and execute every translated stmt."""
    if source.suffix == ".gz":
        with gzip.open(source, "rt", encoding="utf-8", errors="replace") as f:
            text = f.read()
    else:
        text = source.read_text(encoding="utf-8", errors="replace")

    # Comment stripping runs FIRST and literal-aware: '--', '#' and '/* */'
    # never fire inside string literals, while trailing row comments like
    # mangos.sql:13867 `(20005, 1.6);     -- Enchant Weapon - Icy Chill`
    # previously glued the following dump section onto this statement (the
    # line-based splitter only splits on ';' + newline; here the ';' is
    # followed by the comment, not end-of-line). /*!...*/ conditional blocks
    # are preserved by the stripper and dropped downstream, unchanged.
    text = _strip_comments_and_count(text, TranslationReport(), label)

    # MySQL dumps escape apostrophes inside string literals as \' (backslash-
    # quote). SQLite's SQL parser in default mode does not treat backslash as
    # an escape — it expects '' (doubled quote). Rewrite \' -> '' so INSERT
    # data with names like "Kel'Thuzad" or "Player's" survives. This runs before
    # statement splitting so it sees whole literals. The rewrite is one
    # left-to-right pass (_translate_mysql_escapes) so an escaped backslash
    # before the closing quote ('...\\') is consumed as a unit instead of
    # being misread as \' and corrupting the literal (LV-server-db-44).
    text = _translate_mysql_escapes(text)

    count = 0
    errors = 0
    cur = conn.cursor()
    schema_cache: dict = {}
    for stmt in translate_statements(text):
        # MySQL non-strict replay backfills omitted NOT NULL columns with
        # implicit defaults; bare SQLite refuses them (LVF-server-db-017).
        stmt = _backfill_mysql_implicit_defaults(cur, stmt, label, schema_cache)
        # Truncate absurdly long statements for logging.
        preview = stmt if len(stmt) <= 80 else stmt[:77] + "..."
        try:
            cur.execute(stmt)
            count += 1
        except sqlite3.Error as e:
            errors += 1
            if not tolerate_errors:
                # Fail-loud default: a silently dropped statement is silent
                # data loss in the seeded realm.
                print(f"  [{label}] sqlite error: {e}\n    on: {preview}", file=sys.stderr)
                conn.rollback()
                raise
            if verbose or errors <= 5:
                print(f"  [{label}] (tolerated) sqlite error: {e}\n    on: {preview}", file=sys.stderr)
    conn.commit()
    return count, errors


def build_one(db_path: Path, sources: list[Path], label: str, verbose: bool,
              tolerate_errors: bool = False,
              required_versions: dict | None = None,
              stats: dict | None = None) -> int:
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
            n, err = apply_dump(conn, src, label, verbose, tolerate_errors)
            total += n
            total_err += err
        # Sanity: the version column must exist for CheckRequiredField.
        ver_ok = verify_version_table(conn, label, required_versions)
        status = "PASS" if ver_ok else "BEHIND (migration needed)"
        print(f"OK  {label}: {total} statements applied ({total_err} errors); "
              f"version: {status}")
        if stats is not None:
            stats.update(statements=total, applied=total, errors=total_err)
        # Success requires a fully-applied dump (zero SQL errors); tolerated
        # errors are an explicit --tolerate-sql-errors opt-in and still get
        # counted in the summary. The old "any statement applied" rule
        # silently seeded partially-built databases.
        return 0 if (total > 0 and (total_err == 0 or tolerate_errors)) else 1
    finally:
        conn.close()


# Required version column per DB (revision_sql.h). CheckRequiredField does
# SELECT <col> FROM <table> LIMIT 1, so the column must exist.
try:
    from tools import stage_database_migrations as _migrations
except ImportError:  # direct execution
    import stage_database_migrations as _migrations

# Single source of truth for required revisions: the migration generator's
# EXPECTED_REVISIONS (also emitted into schemas/database-migrations.json).
# This file only adds the version-table name per database label.
_REV = _migrations.EXPECTED_REVISIONS
REQUIRED_VERSIONS = {
    "realmd": ("realmd_db_version", _REV["realm"]),
    "characters": ("character_db_version", _REV["characters"]),
    "logs": ("logs_db_version", _REV["logs"]),
    "mangos": ("db_version", _REV["world"]),
}


# ---------------------------------------------------------------------------
# Per-flavor seed plan (port plan S4 item 3)
# ---------------------------------------------------------------------------

# The version-table name is the same in every cmangos branch (verified by
# grep over all three cores' sql/base: realmd_db_version /
# character_db_version / logs_db_version / db_version).
_VERSION_TABLES = {
    "realmd": "realmd_db_version",
    "characters": "character_db_version",
    "logs": "logs_db_version",
    "mangos": "db_version",
}
# Internal DB label -> the flavor.json `databases` key naming it (the world
# DB is the mangos core).
_MANIFEST_DB_KEY = {
    "realmd": "realmd",
    "characters": "characters",
    "logs": "logs",
    "mangos": "world",
}
# The flavor core's own revision floors: the same header the core compiles
# its CheckRequiredField list from, so the seed verifies against exactly the
# revisions the checked-out core will demand at startup.
_REVISION_HEADER = "src/shared/revision_sql.h"
_REVISION_DEFINE_RE = re.compile(
    r'^\s*#\s*define\s+REVISION_DB_(REALMD|LOGS|CHARACTERS|MANGOS)\s+"([^"]+)"',
    re.MULTILINE)
# World-content floor tables recorded in every per-flavor baseline: the
# census proof set (creature/quest/item volume) plus areatrigger_teleport —
# the one table whose MySQL definition carries a FULLTEXT KEY the
# translator must drop (a regression there fails the floor).
WORLD_FLOOR_TABLES = ("creature_template", "quest_template",
                      "item_template", "areatrigger_teleport")


@dataclass(frozen=True)
class SeedPlan:
    """Every flavor-specific input one seed run consumes, resolved through
    flavor_layout (the naming schema of record)."""
    flavor_id: str
    core_submodule: str
    db_submodule: str
    sql_base: Path              # <core>/sql/base
    world_dump: Path            # the flavor DB repo's Full_DB archive
    db_names: dict              # internal label -> output file stem
    required_versions: dict     # internal label -> (version table, floor col)
    baseline: Path              # the flavor's sqlite-seed-baseline json


def _required_versions_from_core(flavor_id: str, layout) -> dict:
    """Parse REVISION_DB_* floors from the flavor core's revision_sql.h.

    Each seeded version table carries a column literally NAMED after the
    required revision (CheckRequiredField does SELECT <rev> FROM <table>
    LIMIT 1), so the header defines both the table's expected floor column
    and the honest BEHIND verdict when the Full_DB content snapshot is older
    (tbc: 1 revision behind; wotlk: 11; vanilla z2815 vs z2830 — the
    migration chain itself belongs to the ledger/MariaDB lane)."""
    header = ROOT / layout.core_submodule / _REVISION_HEADER
    try:
        text = header.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        raise RuntimeError(
            f"cannot read the {flavor_id} core's revision floors "
            f"({header}): {exc} — the seed cannot verify version tables "
            "without them") from exc
    defines = dict(_REVISION_DEFINE_RE.findall(text))
    define_for = {"realmd": "REALMD", "logs": "LOGS",
                  "characters": "CHARACTERS", "mangos": "MANGOS"}
    missing = [f"REVISION_DB_{key}" for key in define_for.values()
               if key not in defines]
    if missing:
        raise RuntimeError(
            f"{header} is missing {', '.join(missing)} — the seed cannot "
            "verify version tables without the core's revision floors")
    return {label: (_VERSION_TABLES[label], defines[key])
            for label, key in define_for.items()}


def _flavor_seed_plan(flavor_id: str) -> SeedPlan:
    """Resolve one flavor's complete seed plan. Vanilla is byte-identical to
    the pre-parameterization lane (same literals, same output names, same
    REQUIRED_VERSIONS); tbc/wotlk resolve their own core base dir, Full_DB
    archive, DB names, and revision floors."""
    layout = flavor_layout.resolve(flavor_id)
    if flavor_id == "vanilla":
        return SeedPlan(
            flavor_id=flavor_id,
            core_submodule=layout.core_submodule,
            db_submodule=layout.db_submodule,
            sql_base=SQL_BASE,
            world_dump=WORLD_DUMP,
            db_names={label: label for label in DB_SOURCES},
            required_versions=REQUIRED_VERSIONS,
            baseline=ROOT / layout.sqlite_seed_baseline(),
        )
    manifest = flavor_layout.load_flavor_manifest()
    block = next((f for f in manifest.get("flavors", [])
                  if isinstance(f, dict) and f.get("id") == flavor_id), None)
    if block is None:
        raise RuntimeError(
            f"flavor {flavor_id!r} not found in schemas/flavor.json — the "
            "per-flavor seed inputs live in its flavor block")

    # Output DB names: the flavor's own databases block (tbcrealmd… /
    # wotlkrealmd…). Vanilla keeps the historical {realmd,characters,logs,
    # mangos}.sqlite stems because downstream consumers pin them.
    databases = block.get("databases")
    if not isinstance(databases, dict):
        raise RuntimeError(
            f"flavor {flavor_id!r} has no databases block in "
            "schemas/flavor.json — cannot name its SQLite outputs")
    db_names = {}
    for label, key in _MANIFEST_DB_KEY.items():
        name = databases.get(key)
        if not isinstance(name, str) or not name:
            raise RuntimeError(
                f"flavor {flavor_id!r} databases.{key} is missing/empty — "
                f"cannot name the {label} SQLite output")
        db_names[label] = name

    # World content archive: the flavor's OWN DB repo, never the classic
    # dump. Preferred source is the flavor block's db_content.seed pin; a
    # unique Full_DB/*.sql.gz in the flavor's db submodule resolves it when
    # the seed field has not been registered yet.
    db_root = ROOT / layout.db_submodule
    seed = (block.get("db_content") or {}).get("seed")
    if isinstance(seed, str) and seed:
        # R2-1 (review round 1, serious): a doctored db_content.seed
        # ("../classic-db-wotlk/...", an absolute path) joins onto db_root
        # uncontained and — because the floors are MINIMUMS and the
        # version-table leg is a WARN — seeds foreign world content under
        # this flavor's DB names with rc=0. The containment rule lives in
        # flavor_layout (shared with stage_database_migrations) and is
        # enforced BEFORE the join.
        seed_error = flavor_layout.db_content_seed_error(flavor_id, seed)
        if seed_error:
            raise RuntimeError(seed_error)
        world_dump = db_root / seed
    else:
        archives = sorted((db_root / "Full_DB").glob("*.sql.gz"))
        if len(archives) != 1:
            raise RuntimeError(
                f"cannot resolve the {flavor_id} world content archive: "
                f"{len(archives)} Full_DB/*.sql.gz under {db_root} (need "
                "exactly one; register db_content.seed in schemas/flavor.json "
                "or check out the pinned submodule)")
        world_dump = archives[0]

    return SeedPlan(
        flavor_id=flavor_id,
        core_submodule=layout.core_submodule,
        db_submodule=layout.db_submodule,
        sql_base=ROOT / layout.core_submodule / "sql" / "base",
        world_dump=world_dump,
        db_names=db_names,
        required_versions=_required_versions_from_core(flavor_id, layout),
        baseline=ROOT / layout.sqlite_seed_baseline(),
    )


def _world_dump_identity_error(plan: SeedPlan, world_dump: Path) -> str | None:
    """Refuse a --world-dump override that leaves the flavor's OWN db repo
    (the PATH leg of the wrong-flavor refusal; the CONTENT leg is
    `_world_dump_content_error` below).

    The per-flavor floors are MINIMUMS (a higher-volume wrong-flavor dump —
    e.g. the wotlk Full_DB measured in schemas/sqlite-seed-baseline-wotlk.json
    — passes every tbc floor table) and the version-table leg is an honest
    WARN (BEHIND), so nothing else inside this tool rejects a cross-flavor
    Full_DB. The path leg alone cannot catch a foreign dump copied INSIDE
    the flavor's own db repo (the vanilla lane's in-repo copy class) — the
    content sniff below closes that; the two legs together enforce the
    docstring's "never classic inputs under another flavor's name"
    promise. Overrides inside the flavor's own db submodule stay allowed
    (same repo, same provenance chain as the pinned db_content.seed
    archive) but must carry the flavor's own revision family."""
    # Review round 2 (R2-1 serious, arm 2): the db submodules gitignore
    # scratch names (*.log *.orig *.rej *.swo *.swp *.vi *~ —
    # native/classic-db/.gitignore), so a doctored dump under one of those
    # extensions is INVISIBLE to the pin dirty-check while this path leg
    # would accept anything inside the repo. A world content archive is a
    # .sql.gz Full_DB (or plain .sql) — a git-VISIBLE name, so a doctored
    # in-repo dump either carries a foreign marker (refused by the content
    # sniff) or takes a tracked name the dirty-check sees.
    name_ok = world_dump.suffix == ".sql" or (
        world_dump.suffix == ".gz"
        and world_dump.name.lower().endswith(".sql.gz"))
    if not name_ok:
        return (
            f"--world-dump {world_dump} does not carry a .sql/.sql.gz name "
            "— the db repos gitignore scratch extensions (*.log, *.orig, "
            "*~), so a dump under an ignored name would be invisible to "
            "the pin dirty-check; a world content archive is a Full_DB "
            ".sql.gz (or plain .sql), never a scratch-named file"
        )
    if world_dump.resolve() == plan.world_dump.resolve():
        return None
    db_root = (ROOT / plan.db_submodule).resolve()
    try:
        world_dump.resolve().relative_to(db_root)
    except ValueError:
        return (
            f"--world-dump {world_dump} is outside the {plan.flavor_id} db "
            f"repo ({plan.db_submodule}) — a wrong-flavor Full_DB would seed "
            f"{plan.db_names['mangos']}.sqlite with foreign world content the "
            "floor check cannot catch (floors are minimums, a higher-volume "
            "wrong-flavor dump passes them). Pass the flavor's own "
            "db_content.seed archive or a path under "
            f"{plan.db_submodule}."
        )
    return None


# Content identity sniff (review round 1, R1-3): the world-db revision
# marker dialects are the SAME regexes stage_database_migrations' per-flavor
# rules use for the cutoff computation — a cmangos Full_DB's db_version
# INSERT carries required_<rev>_<n>_mangos_<name> rows in exactly its own
# branch's dialect (measured 2026-10-09 over all three pinned dumps, full
# streams: vanilla z2815 at 33.5 MB decompressed and ONLY z-markers; tbc
# s2491 at 50 MB, ONLY s-markers; wotlk 14092 at 67 MB, ONLY 5-digit
# markers — so "own family present, foreign family absent" is a measured
# invariant of the honest archives, not a guess).
def _world_dump_content_error(plan: SeedPlan, world_dump: Path) -> str | None:
    """Refuse a world dump whose db_version revision markers belong to a
    DIFFERENT branch than the flavor's own (or that carries no recognizable
    mangos marker at all). The path leg above cannot see a wrong-flavor
    Full_DB copied INSIDE the flavor's own db repo; the floors (minimums)
    and the version-table WARN cannot either. Runs on the pinned default
    dump too — the honest dumps pass by measurement (module comment)."""
    # Family classifiers: the same branch dialects
    # stage_database_migrations._FLAVOR_RULES match for the cutoff
    # computation, minus the trailing marker-NAME requirement (ownership
    # classification needs only the dialect prefix; the name-suffix form
    # cannot re-match the bare any-family hit).
    family_of = {
        "vanilla": re.compile(r"required_z\d{4}_\d+_mangos_"),
        "tbc": re.compile(r"required_s\d{4}_\d+_mangos_"),
        "wotlk": re.compile(r"required_\d{5}_\d+_mangos_"),
    }
    own_family = family_of[plan.flavor_id]
    any_family = re.compile(r"required_(?:z\d{4}|s\d{4}|\d{5})_\d+_mangos_")
    opener = (gzip.open(world_dump, "rt", encoding="utf-8", errors="replace")
              if world_dump.suffix == ".gz"
              else world_dump.open("rt", encoding="utf-8", errors="replace"))
    own_seen = False
    foreign = ""
    tail = ""
    try:
        with opener as handle:
            # Whole-stream scan (no cap): the wotlk dump's first marker sits
            # at 67 MB decompressed, so a capped probe would falsely refuse
            # the honest archive; a full pass costs <1 s (measured) next to
            # the seed's own full read. EVERY any-family hit in the window
            # is classified (review round 2, R2-1 serious: the old
            # first-hit-only `search` accepted a composite dump whose FIRST
            # marker was own-family and whose foreign marker sat later in
            # the same window — the D55 contract is that NO foreign marker
            # appears anywhere in the stream).
            while True:
                chunk = handle.read(1024 * 1024)
                if not chunk:
                    break
                window = tail + chunk
                if not own_seen and own_family.search(window):
                    own_seen = True
                if not foreign:
                    for hit in any_family.finditer(window):
                        if not own_family.match(hit.group(0)):
                            foreign = hit.group(0)
                            break
                tail = window[-64:]
    except (OSError, EOFError, gzip.BadGzipFile) as exc:
        return (
            f"--world-dump {world_dump} could not be read for the content "
            f"identity sniff: {exc} — refusing to seed world content that "
            "cannot be attested as this flavor's own"
        )
    if foreign:
        return (
            f"--world-dump {world_dump} carries a foreign branch's world-db "
            f"revision marker ({foreign}) — it is not {plan.flavor_id} world "
            f"content and would seed {plan.db_names['mangos']}.sqlite with "
            "wrong-flavor data the floor check cannot catch (floors are "
            "minimums). Pass the flavor's own db_content.seed archive."
        )
    if not own_seen:
        return (
            f"--world-dump {world_dump} carries no {plan.flavor_id} world-db "
            "revision marker (db_version required_*_mangos_* rows) — it is "
            "not recognizable as this flavor's world content; refusing a "
            "dump that cannot be attested. Pass the flavor's own "
            "db_content.seed archive."
        )
    return None


def _measure_db(db_path: Path) -> tuple[int, int, dict]:
    """(table count, total row count, {floor table: rows}) for a seeded DB."""
    conn = sqlite3.connect(str(db_path))
    try:
        tables = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%'")]
        rows = sum(conn.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
                   for t in tables)
        floor_rows = {}
        for t in WORLD_FLOOR_TABLES:
            if t in tables:
                floor_rows[t] = conn.execute(
                    f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
        return len(tables), rows, floor_rows
    finally:
        conn.close()


def verify_seed_floors(out: Path, plan: SeedPlan) -> bool:
    """Verify a finished seed against the committed per-flavor baseline
    floors (tables/rows per DB, rows per world floor table). Floors are
    minimums: content growth upstream passes, a hollow or partially-applied
    dump fails. A missing baseline passes (the first --write-baseline run is
    what measures it — never guessed numbers). Vanilla is out of scope: its
    schemas/sqlite-seed-baseline.json is the manifest transcript lane's
    digest chain, a different schema verified by its own tools
    (tools/verify_seed_sqlite_floor.py)."""
    if plan.flavor_id == "vanilla":
        return True
    if not plan.baseline.is_file():
        return True
    baseline = json.loads(plan.baseline.read_text(encoding="utf-8"))
    floors = baseline.get("databases", {})
    floor_tables = baseline.get("floor_tables", {})
    seeded = set(plan.db_names.values())
    if seeded != set(floors):
        print(f"ERROR: baseline {plan.baseline} databases {sorted(floors)} "
              f"!= the seeded set {sorted(seeded)} — regenerate the baseline "
              "deliberately (--write-baseline on a clean seed)", file=sys.stderr)
        return False
    ok = True
    for db_name in sorted(seeded):
        floor = floors[db_name]
        db = out / f"{db_name}.sqlite"
        if not db.is_file():
            print(f"ERROR: floors: {db} was not seeded", file=sys.stderr)
            ok = False
            continue
        tables, rows, measured_floor_tables = _measure_db(db)
        if tables < floor.get("tables", 0):
            print(f"ERROR: floors: {db_name} has {tables} tables, baseline "
                  f"floor is {floor.get('tables')} (hollow seed)",
                  file=sys.stderr)
            ok = False
        if rows < floor.get("rows", 0):
            print(f"ERROR: floors: {db_name} has {rows} rows, baseline floor "
                  f"is {floor.get('rows')} (partially-applied dump)",
                  file=sys.stderr)
            ok = False
        for table, floor_rows in sorted(
                (floor_tables.get(db_name) or {}).items()):
            actual = measured_floor_tables.get(table)
            if actual is None:
                print(f"ERROR: floors: {db_name}.{table} is missing from the "
                      f"seeded DB (baseline floor {floor_rows} rows)",
                      file=sys.stderr)
                ok = False
            elif actual < floor_rows:
                print(f"ERROR: floors: {db_name}.{table} has {actual} rows, "
                      f"baseline floor is {floor_rows}", file=sys.stderr)
                ok = False
        if ok:
            print(f"OK  floors: {db_name}: {tables} tables / {rows} rows "
                  ">= baseline")
    return ok


def write_seed_baseline(out: Path, plan: SeedPlan, stats_by_db: dict) -> Path:
    """Write the flavor's baseline from a CLEAN, fully-applied seed — every
    number measured from the seeded databases, never guessed. Vanilla is
    refused: schemas/sqlite-seed-baseline.json is the manifest transcript
    lane's append-only file (tools/seed_sqlite_from_manifest.py owns it)."""
    if plan.flavor_id == "vanilla":
        print("ERROR: --write-baseline refuses vanilla: "
              "schemas/sqlite-seed-baseline.json belongs to the manifest "
              "transcript lane (tools/seed_sqlite_from_manifest.py "
              "--write-baseline)", file=sys.stderr)
        raise RuntimeError("vanilla baseline is not this lane's to write")
    databases = {}
    floor_tables = {}
    for db_name in sorted(plan.db_names.values()):
        tables, rows, measured = _measure_db(out / f"{db_name}.sqlite")
        st = stats_by_db.get(db_name, {})
        databases[db_name] = {
            "statements": st.get("statements"),
            "applied": st.get("applied"),
            "errors": st.get("errors"),
            "tables": tables,
            "rows": rows,
        }
        if db_name == plan.db_names.get("mangos"):
            missing = [t for t in WORLD_FLOOR_TABLES if t not in measured]
            if missing:
                raise RuntimeError(
                    "world floor tables missing from the seeded world DB: "
                    f"{missing} — refusing to write a baseline that cannot "
                    "prove world content")
            floor_tables[db_name] = {
                t: measured[t] for t in WORLD_FLOOR_TABLES}
    payload = {
        "flavor": plan.flavor_id,
        "generated_by": (f"tools/seed_realm_db.py --flavor {plan.flavor_id} "
                         "--write-baseline (first successful seed; floors "
                         "measured, not guessed)"),
        "inputs": {
            "core_submodule": plan.core_submodule,
            "db_submodule": plan.db_submodule,
            "world_dump": str(plan.world_dump.relative_to(ROOT)).replace("\\", "/"),
        },
        "databases": databases,
        "floor_tables": floor_tables,
    }
    plan.baseline.write_text(
        json.dumps(payload, indent=2, sort_keys=False) + "\n",
        encoding="utf-8")
    print(f"baseline written: {plan.baseline}")
    return plan.baseline


def verify_version_table(conn: sqlite3.Connection, label: str,
                         required_versions: dict | None = None) -> bool:
    """Confirm the version column the core's CheckRequiredField queries exists.

    Returns False (honestly) when the seeded snapshot is older than the core
    expects — vanilla's world (mangos) base snapshot is at z2815 while the
    core wants z2830; tbc's Full_DB installs s2491 against the core's s2492
    floor and wotlk's 14092 against 14104. We do NOT fake this by renaming:
    SCHEMA_COMPATIBLE must reflect that the migrations between the snapshot
    and the core revision have not been applied. That migration chain belongs
    to the runtime tooling (the ledger/MariaDB lane). The other three DBs
    (realmd/characters/logs) seed at the correct revision and pass cleanly
    on every flavor (measured: tbc s2474/s2473/s2433, wotlk
    14083/14097/14039).
    """
    table, col = (required_versions or REQUIRED_VERSIONS).get(label, ("", ""))
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
              f"library-lane/runtime boundary; SCHEMA_COMPATIBLE will honestly report FALSE)",
              file=sys.stderr)
        return False
    except sqlite3.Error as e:
        print(f"WARN  {label}: cannot introspect version table: {e}", file=sys.stderr)
        return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="output directory for the 4 .sqlite files")
    ap.add_argument("--flavor", default="vanilla", choices=tuple(flavor_layout.FLAVOR_IDS),
                    help="game flavor to seed (default vanilla; every flavor seeds "
                         "its OWN core sql/base, Full_DB archive, DB names and "
                         "revision floors, resolved through flavor_layout — "
                         "tbc/wotlk refuse loud until their server_core and "
                         "db_content pins are registered)")
    ap.add_argument("--world-dump", default=None,
                    help="path to the flavor's Full_DB .sql.gz (default: the "
                         "flavor's pinned archive; vanilla: "
                         f"{WORLD_DUMP}). Must live under the flavor's own db "
                         "repo AND carry the flavor's own world-db revision "
                         "markers — a foreign Full_DB is refused on both the "
                         "path and the content legs (the floors are minimums "
                         "and cannot catch a higher-volume wrong-flavor dump)")
    ap.add_argument("--no-world-dump", action="store_true",
                    help="explicitly opt into a schema-only mangos (world) seed: skip "
                         "the Full_DB content dump. NOT the default because a "
                         "schema-only world DB passes verify while carrying no "
                         "world content (hollow seed)")
    ap.add_argument("--write-baseline", action="store_true",
                    help="after a clean, fully-applied tbc/wotlk seed, write the "
                         "flavor's schemas/sqlite-seed-baseline-<flavor>.json with "
                         "the MEASURED table/row floors (refused for vanilla and "
                         "for schema-only or unclean seeds)")
    ap.add_argument("--tolerate-sql-errors", action="store_true",
                    help="opt in to tolerating per-statement SQL errors (counted and "
                         "reported; NOT the default because dropped statements are "
                         "silent data loss in the seeded realm)")
    ap.add_argument("--verbose", action="store_true",
                    help="log every tolerated translation error")
    args = ap.parse_args()

    # R2-2 (review round 1): EVERY flavor verifies its pins before seeding —
    # vanilla included, like stage_database_migrations.select_inputs ("S1
    # ran this check only for tbc/wotlk as the pre-S4 stopgap; ... vanilla
    # is verified like the rest"). The old `!= "vanilla"` guard let a stale
    # (HEAD != pin) or dirty native/cmangos / native/classic-db checkout
    # seed all four vanilla DBs with rc=0 and no pin attestation — the
    # stale-generation class the tbc/wotlk lanes refuse loud. The vanilla
    # pins resolve on the registered lane, so honest runs are byte-identical;
    # only the stale class gains its refusal. S4 item 3: the per-flavor seed
    # consumes the flavor's OWN core sql/base and DB-repo Full_DB archive,
    # so both pins must be registered AND checked out before anything is
    # seeded (bots is not a seed input and is not required here). Fail loud
    # rather than silently seed classic inputs under another flavor's name.
    try:
        flavor_layout.require_flavor_pins(
            args.flavor, groups=("server_core", "db_content"))
    except flavor_layout.FlavorPinError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        print(
            f"ERROR: refusing to seed the {args.flavor} lane until its "
            "server_core and db_content pins are registered and checked "
            "out at the pin, with a clean tree — the seed applies the "
            "flavor core's sql/base and the flavor DB repo's Full_DB "
            "archive, never classic inputs under another flavor's name",
            file=sys.stderr,
        )
        return 2

    try:
        plan = _flavor_seed_plan(args.flavor)
    except (RuntimeError, ValueError) as exc:
        # ValueError: unknown flavor id from flavor_layout.resolve().
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    if args.write_baseline:
        if plan.flavor_id == "vanilla":
            print("ERROR: --write-baseline is a tbc/wotlk operation; the "
                  "vanilla baseline (schemas/sqlite-seed-baseline.json) is "
                  "written by the manifest transcript lane "
                  "(tools/seed_sqlite_from_manifest.py --write-baseline)",
                  file=sys.stderr)
            return 2
        if args.no_world_dump:
            print("ERROR: --write-baseline refuses a schema-only seed: its "
                  "floors could not prove world content (the hollow-seed "
                  "class this tool refuses everywhere)", file=sys.stderr)
            return 2

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    world_dump = Path(args.world_dump) if args.world_dump else plan.world_dump

    # A schema-only mangos (world) DB is a hollow seed: base mangos.sql alone
    # applies cleanly, verify passes (mangos.sql carries db_version), and the
    # core's DATABASE_OPEN would pass too — while every world table is empty.
    # A missing dump (db submodule not checked out, typo'd --world-dump) must
    # fail loud, not degrade silently.
    if not args.no_world_dump and not world_dump.exists():
        print(
            f"ERROR: mangos (world) content dump not found: {world_dump}\n"
            "  Refusing to seed a schema-only world DB (it would pass verify "
            "while\n  carrying no Full_DB content). Remedy: check out the "
            "pinned submodule\n  "
            f"(git submodule update --init {plan.db_submodule}), "
            "fix --world-dump, or pass\n  --no-world-dump for an explicit "
            "schema-only seed.",
            file=sys.stderr)
        return 2

    # An EXISTING but foreign dump is the wrong-flavor class the floors and
    # the version-table WARN cannot catch (both checked after the seed):
    # refuse it here, before any database is written — first by PATH (the
    # dump must live in the flavor's own db repo), then by CONTENT (its
    # db_version revision markers must belong to the flavor's own branch;
    # R1-3: the in-repo copy class the path leg cannot see). --no-world-dump
    # never reads the dump, so its path is unvalidated by design.
    identity_error = (
        None if args.no_world_dump
        else _world_dump_identity_error(plan, world_dump))
    if identity_error:
        print(f"ERROR: {identity_error}", file=sys.stderr)
        return 2
    content_error = (
        None if args.no_world_dump
        else _world_dump_content_error(plan, world_dump))
    if content_error:
        print(f"ERROR: {content_error}", file=sys.stderr)
        return 2

    rc = 0
    stats_by_db: dict[str, dict] = {}
    for label, src_names in DB_SOURCES.items():
        sources = [plan.sql_base / n for n in src_names]
        # The mangos (world) DB also gets the big content dump after the base
        # (skipped only by the explicit --no-world-dump schema-only opt-in).
        if label == "mangos" and world_dump.exists() and not args.no_world_dump:
            sources.append(world_dump)
        db_name = plan.db_names[label]
        stats: dict = {}
        r = build_one(out / f"{db_name}.sqlite", sources, label, args.verbose,
                      getattr(args, "tolerate_sql_errors", False),
                      plan.required_versions, stats)
        stats_by_db[db_name] = stats
        if r != 0:
            rc = r
    if rc == 0 and not verify_seed_floors(out, plan):
        # A below-floor seed is a hollow or partially-applied seed: the same
        # fake-success class build_one refuses, caught after the fact.
        rc = 1
    if rc == 0 and args.write_baseline:
        try:
            write_seed_baseline(out, plan, stats_by_db)
        except RuntimeError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            rc = 1
    if rc == 0:
        print(f"\nSEEDED 4 SQLite databases in {out}")
    else:
        print(f"\nSEED COMPLETED WITH WARNINGS (rc={rc}) — see above", file=sys.stderr)
    return rc


if __name__ == "__main__":
    sys.exit(main())
