#!/usr/bin/env python3
"""String-aware MySQL->SQLite SQL translation for the manifest-driven
seeder (P4 Route A of the MariaDB replacement plan, DEC-01).

The legacy tools/seed_realm_db.py translated with line-based comment
stripping and ';' splitting: any '--' or '/*' inside a string literal
corrupted the statement, any ';' inside a literal split it, \' was the
only escape rewritten, and KEY/UNIQUE KEY clauses were dropped wholesale
(zero indexes staged). This module replaces all of that with a proper
character-state scanner:

  - string-aware comment stripping (-- and # line comments, /* */ blocks
    - never inside literals or backtick identifiers)
  - string-aware, DELIMITER-aware statement splitting (';' inside
    literals/backticks/comments never splits)
  - MySQL conditional comments /*!NNNNN ... */: executed content is
    translated recursively when NNNNN <= 80000; DISABLE/ENABLE KEYS and
    SET forms are dropped
  - SET @x := value folding in replay order (literal + CONCAT-of-literal
    expressions); @x substituted into later statements as a quoted literal
  - escape rewrites inside literals with EXACT counting: \\n -> newline,
    \\r -> CR, \\t -> tab, \\' -> '' , \\" -> " , \\\\ -> backslash (the
    F52 class; the spec pins the expected total across exactly 3 files)
  - DDL translation: ENGINE/CHARSET/ROW_FORMAT/COMMENT table tails,
    column COMMENTs, type rewrites to affinity-exact names (int(N)
    unsigned AUTO_INCREMENT + PRIMARY KEY(col) -> INTEGER PRIMARY KEY
    AUTOINCREMENT rowid alias, per the P3->P4 addendum (d)), KEY/UNIQUE
    KEY lines -> emitted CREATE [UNIQUE] INDEX after the table (addendum
    (b)), enum('a','b') -> TEXT CHECK(col IN ('a','b')), ALTER..CHANGE
    no-op-with-audit under affinity, ALTER..ADD INDEX -> CREATE INDEX,
    TRUNCATE -> DELETE FROM, CHARACTER SET utf8mb3 stripped, LOCK/UNLOCK/
    USE/SET-session dropped.
  - positional ALTER..ADD COLUMN (AFTER `col` / FIRST): MySQL inserts
    the column mid-table; SQLite can only append. The position is NOT
    discardable - every SQLStorage load reads these tables with
    SELECT * and maps columns positionally, so a stripped AFTER shifts
    the whole tail (gameobject_template: moTransport.taxiPathId read
    data1 instead of data0 -> world-boot SIGSEGV - I-189). A per-
    database schema tracker follows CREATE/ALTER in replay order and
    re-emits positional ADDs as a full table rebuild (rename, re-create
    in MySQL-effective column order, copy, drop, re-emit indexes);
    untracked/opaque tables fail loud rather than silently reordering.

Every applied transformation is counted per class and per file; the
fidelity harness pins the counts (the seed is deterministic).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

# ---------------------------------------------------------------------------
# Character-state scanning
# ---------------------------------------------------------------------------


class TranslationReport:
    def __init__(self) -> None:
        self.comment_sites = 0
        self.comment_files: set[str] = set()
        self.escape_rewrites = 0
        self.escape_files: set[str] = set()
        self.escape_per_file: dict[str, int] = {}
        self.escape_per_class: dict[str, int] = {}
        # F52 external anchor: the research digest's legacy-corruption
        # classes (\n \r \\) per file - pinned exactly in the baseline
        # and asserted by the harness (49,242 sites / 3 files; the
        # digest's 49,243 was one high - recorded in the ledger).
        self.f52_nr_backslash: dict[str, int] = {}
        self.set_vars_folded = 0
        self.indexes_emitted = 0
        self.unique_indexes_emitted = 0
        self.enum_columns = 0
        self.truncates = 0
        self.alter_change_noops = 0
        self.alter_change_renames = 0
        self.update_joins = 0
        self.alter_add_index = 0
        self.conditional_executed = 0
        self.dropped_session = 0
        self.session_vars: set[str] = set()
        self.insert_chunks_split = 0
        self.statements = 0
        # MySQL text columns default to case-insensitive collations
        # (server-default utf8*_general_ci); the NOCASE emission restores
        # that direction on the SQLite lane (I-60).
        self.nocase_columns = 0
        self.decimal_rewrites = 0
        # I-189: positional ADD COLUMN translation - each becomes a
        # 4-statement table rebuild plus re-emitted indexes.
        self.positional_add_rebuilds = 0
        self.rename_rebuilds = 0
        self.positional_add_columns = 0

    def as_dict(self) -> dict:
        return {
            "comment_sites": self.comment_sites,
            "comment_files": len(self.comment_files),
            "escape_rewrites": self.escape_rewrites,
            "escape_files": sorted(self.escape_files),
            "escape_per_file": self.escape_per_file,
            "escape_per_class": self.escape_per_class,
            "f52_nr_backslash": dict(sorted(
                self.f52_nr_backslash.items())),
            "set_vars_folded": self.set_vars_folded,
            "indexes_emitted": self.indexes_emitted,
            "unique_indexes_emitted": self.unique_indexes_emitted,
            "enum_columns": self.enum_columns,
            "truncates": self.truncates,
            "alter_change_noops": self.alter_change_noops,
            "alter_change_renames": self.alter_change_renames,
            "update_joins": self.update_joins,
            "alter_add_index": self.alter_add_index,
            "conditional_executed": self.conditional_executed,
            "dropped_session": self.dropped_session,
            "session_vars": sorted(self.session_vars),
            "insert_chunks_split": self.insert_chunks_split,
            "statements": self.statements,
            "nocase_columns": self.nocase_columns,
            "decimal_rewrites": self.decimal_rewrites,
            "positional_add_rebuilds": self.positional_add_rebuilds,
            "rename_rebuilds": self.rename_rebuilds,
            "positional_add_columns": self.positional_add_columns,
        }


_ESCAPE_MAP = {
    "n": "\n",
    "r": "\r",
    "t": "\t",
    "f": "\f",
    "0": "\0",
    "b": "\b",
    "Z": "\x1a",
}


def _strip_comments_and_count(text: str, report: TranslationReport,
                              filename: str) -> str:
    """Remove -- and # line comments and /* */ blocks outside strings.

    /*!...*/ conditional comments are PRESERVED here (they carry SQL the
    MySQL server executes); the statement layer decides per version.
    """
    out: list[str] = []
    i = 0
    n = len(text)
    in_squote = in_dquote = in_backtick = False
    while i < n:
        c = text[i]
        if in_squote:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == "'":
                in_squote = False
            i += 1
            continue
        if in_dquote:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_dquote = False
            i += 1
            continue
        if in_backtick:
            out.append(c)
            if c == "`":
                in_backtick = False
            i += 1
            continue
        # not inside any literal:
        if c == "'" and i + 1 < n and text[i + 1] == "'" and False:
            pass  # (unreachable; kept for symmetry)
        if c == "'":
            in_squote = True
            out.append(c)
            i += 1
            continue
        if c == '"':
            in_dquote = True
            out.append(c)
            i += 1
            continue
        if c == "`":
            in_backtick = True
            out.append(c)
            i += 1
            continue
        if c == "-" and text.startswith("--", i):
            # MySQL requires whitespace after -- for a comment.
            j = i + 2
            if j < n and text[j] in " \t\r\n":
                report.comment_sites += 1
                report.comment_files.add(filename)
                # skip to end of line
                while i < n and text[i] != "\n":
                    i += 1
                continue
        if c == "#":
            report.comment_sites += 1
            report.comment_files.add(filename)
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                # unterminated block comment: strip to EOF
                report.comment_sites += 1
                report.comment_files.add(filename)
                break
            inner = text[i + 2:end]
            if not inner.startswith("!"):
                report.comment_sites += 1
                report.comment_files.add(filename)
                out.append(" ")
            else:
                out.append(text[i:end + 2])  # keep /*!...*/ for the stmt layer
            i = end + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _split_statements(text: str) -> list[str]:
    """Split on ';' outside literals/backticks. /*!...*/ aware. Runs on
    ESCAPE-REWRITTEN text, so backslashes are literal data and carry NO
    escape meaning (SQLite rules) - handling them would swallow real
    closing quotes after literal backslashes."""
    statements: list[str] = []
    buf: list[str] = []
    i = 0
    n = len(text)
    in_squote = in_dquote = in_backtick = False
    while i < n:
        c = text[i]
        if in_squote:
            buf.append(c)
            if c == "'":
                if i + 1 < n and text[i + 1] == "'":
                    buf.append("'")
                    i += 2
                    continue
                in_squote = False
            i += 1
            continue
        if in_dquote:
            buf.append(c)
            if c == '"':
                in_dquote = False
            i += 1
            continue
        if in_backtick:
            buf.append(c)
            if c == "`":
                in_backtick = False
            i += 1
            continue
        if c == "'":
            in_squote = True
            buf.append(c)
            i += 1
            continue
        if c == '"':
            in_dquote = True
            buf.append(c)
            i += 1
            continue
        if c == "`":
            in_backtick = True
            buf.append(c)
            i += 1
            continue
        if c == "/" and text.startswith("/*!", i):
            end = text.find("*/", i + 3)
            if end < 0:
                buf.append(text[i:])
                i = n
                continue
            buf.append(text[i:end + 2])
            i = end + 2
            continue
        if c == ";":
            statements.append("".join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        statements.append(tail)
    return statements


# ---------------------------------------------------------------------------
# Per-database schema tracking (I-189: positional ADD COLUMN fidelity)
# ---------------------------------------------------------------------------

_TABLE_CONSTRAINT_WORDS = frozenset((
    "PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "FOREIGN", "CHECK",
    "FULLTEXT", "SPATIAL", "INDEX"))


class TrackedTable:
    """Effective SQLite-side shape of one table: its CREATE body entries
    (column definitions AND table-level constraints, in order) plus the
    CREATE [UNIQUE] INDEX statements the translation emitted for it. Only
    the ordered entry list is needed to reproduce a table with a column
    inserted mid-body (SQLite itself cannot)."""

    def __init__(self) -> None:
        self.entries: list[str] = []
        self.indexes: list[str] = []
        self.opaque = False

    def column_names(self) -> list[str]:
        names = []
        for entry in self.entries:
            name = _entry_column_name(entry)
            if name is not None:
                names.append(name)
        return names


class DBSchemaState:
    """Schema tracking for ONE database (classicmangos etc.) across all
    manifest entries in replay order. Namespaced per database because the
    same table name may exist in several of them."""

    def __init__(self) -> None:
        self.tables: dict[str, TrackedTable] = {}

    def register_create(self, table: str, entries: list[str]) -> None:
        tracked = TrackedTable()
        tracked.entries = entries
        self.tables[table] = tracked

    def register_opaque(self, table: str) -> None:
        tracked = TrackedTable()
        tracked.opaque = True
        self.tables[table] = tracked

    def drop(self, table: str) -> None:
        self.tables.pop(table, None)

    def rename(self, old: str, new: str) -> None:
        tracked = self.tables.pop(old, None)
        if tracked is not None:
            # tracked index statements still name the OLD table; a later
            # rebuild would re-emit them against the wrong name.
            tracked.indexes = [
                stmt.replace(f"ON `{old}`", f"ON `{new}`")
                for stmt in tracked.indexes]
            self.tables[new] = tracked

    def add_index(self, table: str, stmt: str) -> None:
        tracked = self.tables.get(table)
        if tracked is not None:
            tracked.indexes.append(stmt)


def _split_body_entries(body: str) -> list[str]:
    """Top-level comma split of a translated CREATE body. Depth-aware on
    parentheses and literal-aware on '..'/".."/`..` (the same scanner
    discipline as _split_statements, minus statement splitting)."""
    entries: list[str] = []
    buf: list[str] = []
    depth = 0
    in_squote = in_dquote = in_backtick = False
    i = 0
    n = len(body)
    while i < n:
        c = body[i]
        if in_squote:
            buf.append(c)
            if c == "'":
                if i + 1 < n and body[i + 1] == "'":
                    buf.append("'")
                    i += 2
                    continue
                in_squote = False
            i += 1
            continue
        if in_dquote:
            buf.append(c)
            if c == '"':
                in_dquote = False
            i += 1
            continue
        if in_backtick:
            buf.append(c)
            if c == "`":
                in_backtick = False
            i += 1
            continue
        if c == "'":
            in_squote = True
        elif c == '"':
            in_dquote = True
        elif c == "`":
            in_backtick = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "," and depth == 0:
            entries.append("".join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        entries.append(tail)
    return entries


def _entry_column_name(entry: str) -> str | None:
    r"""First identifier of a body entry, or None when the entry is a
    table-level constraint (PRIMARY KEY(..), UNIQUE KEY.., ...).
    Backticked names may carry non-word characters (the cmangos corpus
    has `content_4493_VDB-20191006132107_world`); a \w+ parse would
    truncate at the dash and emit a column the rebuilt table lacks."""
    m = re.match(r"^`([^`]+)`", entry)
    if m:
        name = m.group(1)
    else:
        m = re.match(r"^(\w+)", entry)
        if not m:
            return None
        name = m.group(1)
    if name.upper() in _TABLE_CONSTRAINT_WORDS:
        return None
    return name


def _create_table_name_and_body(stmt: str) -> tuple[str, str] | None:
    m = re.match(
        r"^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(",
        stmt, re.I)
    if not m:
        return None
    # the translated CREATE ends at its final ')' (table tails stripped)
    start = m.end() - 1                    # position of the body '('
    end = stmt.rfind(")")
    return m.group(1), stmt[start + 1:end]


_ADD_COLUMN_RE = re.compile(
    r"^ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+(?:COLUMN\s+"
    r"|(?!(?:INDEX|KEY|UNIQUE|CONSTRAINT|PRIMARY|FOREIGN|FULLTEXT|"
    r"SPATIAL|CHECK)\b))`?(\w+)`?\s+(.+)$", re.I | re.S)
_AFTER_TAIL_RE = re.compile(r"\s+AFTER\s+`?(\w+)`?\s*$", re.I)
_FIRST_TAIL_RE = re.compile(r"\s+FIRST\s*$", re.I)


def _extract_position(stmt: str) -> tuple[str, str] | None:
    """Detect a trailing AFTER `col` / FIRST positioning clause outside
    string literals (backticks=False so backticked targets are visible).
    Detection only - the statement is not modified."""
    found: list[tuple[str, str]] = []

    def _after(m: re.Match) -> str:
        found.append(("after", m.group(1)))
        return m.group(0)

    def _first(m: re.Match) -> str:
        found.append(("first", ""))
        return m.group(0)

    _sub_outside_literals(stmt, _AFTER_TAIL_RE, _after, backticks=False)
    _sub_outside_literals(stmt, _FIRST_TAIL_RE, _first, backticks=False)
    return found[0] if found else None


def _translate_alter_body(stmt: str, report: TranslationReport) -> str:
    """The ALTER-statement transformations shared by the plain and the
    positional ADD paths: strip column COMMENTs and MySQL positioning,
    rename explicit collations (I-67), rewrite types, strip
    unsigned/zerofill, blanket NOCASE (I-60). The positioning strips
    are literal-aware (the detection at _extract_position already is;
    a DEFAULT 'x AFTER y' string must never be rewritten - I-189/R1
    lane-D mutation proof)."""
    stmt = _COL_COMMENT_RE.sub("", stmt)
    stmt = _sub_outside_literals(stmt, _AFTER_TAIL_RE, "",
                                 backticks=False)
    stmt = _sub_outside_literals(stmt, _FIRST_TAIL_RE, "",
                                 backticks=False)
    stmt = _rename_explicit_collations(stmt)
    report.decimal_rewrites += len(re.findall(
        r"\b(?:decimal|numeric)\b", stmt, re.I))
    for pat, repl in _TYPE_MAP:
        stmt = pat.sub(repl, stmt)
    stmt = _UNSIGNED_RE.sub("", stmt)
    stmt = _ZEROFILL_RE.sub("", stmt)
    stmt = _apply_nocase(stmt, report)
    return stmt


def _emit_positional_rebuild(stmt: str, position: tuple[str, str],
                             report: TranslationReport,
                             pending: list[str],
                             db_state: "DBSchemaState | None") -> None:
    """Translate a positional ALTER..ADD COLUMN into a SQLite table
    rebuild with the column at its MySQL-effective position (I-189):
    rename away, re-create in the tracked order, copy rows, drop the
    old table, re-emit the table's tracked indexes (they died with the
    renamed-away table). Existing rows get the column's declared DEFAULT,
    which is MySQL's ADD COLUMN backfill semantics."""
    if db_state is None:
        raise RuntimeError(
            "positional ADD COLUMN requires a schema-tracked pass "
            "(DBSchemaState); refusing to strip the position (I-189): "
            + " ".join(stmt.split())[:120])
    table = re.match(r"^ALTER\s+TABLE\s+`?(\w+)`?", stmt, re.I).group(1)
    tracked = db_state.tables.get(table)
    if tracked is None:
        raise RuntimeError(
            f"positional ADD COLUMN on untracked table `{table}`: the "
            f"rebuild needs its CREATE body; refusing to strip the "
            f"position (I-189)")
    if tracked.opaque:
        raise RuntimeError(
            f"positional ADD COLUMN on opaque table `{table}` (created "
            f"AS SELECT): effective column order is unknowable (I-189)")
    translated = _translate_alter_body(stmt, report)
    m = _ADD_COLUMN_RE.match(translated)
    if not m or m.group(1) != table:
        raise RuntimeError(
            "positional ADD COLUMN did not survive translation: "
            + " ".join(stmt.split())[:120])
    col, col_def = m.group(2), m.group(3).strip()
    if re.search(r",\s*ADD\b", col_def, re.I):
        # multi-clause ALTER (ADD a ... AFTER x, ADD b ... AFTER y): the
        # def would swallow the second clause into the rebuilt CREATE -
        # refuse rather than emit a confusing SQLite syntax error
        # (I-189 R1 lane-D finding)
        raise RuntimeError(
            "multi-clause positional ADD COLUMN is not translated "
            "(split the ALTER into single-clause statements): "
            + " ".join(stmt.split())[:120])
    if any(name.lower() == col.lower() for name in tracked.column_names()):
        raise RuntimeError(f"ADD COLUMN `{col}` already in `{table}`")
    if position[0] == "first":
        insert_at = 0
    else:
        target = position[1]
        insert_at = None
        for i, entry in enumerate(tracked.entries):
            name = _entry_column_name(entry)
            if name is not None and name.lower() == target.lower():
                insert_at = i + 1
                break
        if insert_at is None:
            raise RuntimeError(
                f"AFTER `{target}` names no column of `{table}` (I-189)")
    old_cols = tracked.column_names()
    tracked.entries.insert(insert_at, f"`{col}` {col_def}")
    staging = f"{table}__pos_rebuild"
    pending.append(f"ALTER TABLE `{table}` RENAME TO `{staging}`")
    pending.append("CREATE TABLE `%s` (\n  %s\n)"
                   % (table, ",\n  ".join(tracked.entries)))
    col_list = ", ".join(f"`{c}`" for c in old_cols)
    pending.append(f"INSERT INTO `{table}` ({col_list}) "
                   f"SELECT {col_list} FROM `{staging}`")
    pending.append(f"DROP TABLE `{staging}`")
    pending.extend(tracked.indexes)
    report.positional_add_rebuilds += 1
    report.positional_add_columns += 1
    return None


# ---------------------------------------------------------------------------
# Literal escape rewriting (exact counting)
# ---------------------------------------------------------------------------


def rewrite_escapes(text: str, report: TranslationReport, filename: str) -> str:
    """Rewrite MySQL backslash escapes inside single/double-quoted literals
    to their SQLite equivalents. Runs on whole text; the scanner carries
    literal state so escapes never fire outside strings (e.g. a Windows
    path in a comment is already gone)."""
    out: list[str] = []
    i = 0
    n = len(text)
    in_squote = in_dquote = in_backtick = False
    count = 0

    def emit(rewrite: str, cls: str) -> None:
        nonlocal count
        out.append(rewrite)
        count += 1
        report.escape_per_class[cls] = (
            report.escape_per_class.get(cls, 0) + 1)
        if cls in ("n", "r", "backslash"):
            report.f52_nr_backslash[filename] = (
                report.f52_nr_backslash.get(filename, 0) + 1)

    while i < n:
        c = text[i]
        if in_squote:
            if c == "\\" and i + 1 < n:
                nxt = text[i + 1]
                if nxt == "'":
                    emit("''", "quote")
                elif nxt == '"':
                    emit('"', "dquote")
                elif nxt == "\\":
                    emit("\\", "backslash")
                elif nxt in _ESCAPE_MAP:
                    emit(_ESCAPE_MAP[nxt],
                         {"n": "n", "r": "r", "t": "t", "f": "f",
                          "0": "zero", "b": "b", "Z": "Z"}[nxt])
                else:
                    # \% \_ and unknown escapes: keep the character, drop
                    # the backslash (MySQL semantics for \% outside LIKE).
                    emit(nxt, "other")
                i += 2
                continue
            if c == "'":
                # doubled '' stays as-is (already SQLite-valid)
                in_squote = False
            out.append(c)
            i += 1
            continue
        if in_dquote:
            if c == "\\" and i + 1 < n:
                nxt = text[i + 1]
                if nxt == '"':
                    emit('"', "dquote")
                elif nxt == "\\":
                    emit("\\", "backslash")
                elif nxt in _ESCAPE_MAP:
                    emit(_ESCAPE_MAP[nxt],
                         {"n": "n", "r": "r", "t": "t", "f": "f",
                          "0": "zero", "b": "b", "Z": "Z"}[nxt])
                else:
                    emit(nxt, "other")
                i += 2
                continue
            if c == '"':
                in_dquote = False
            out.append(c)
            i += 1
            continue
        if in_backtick:
            out.append(c)
            if c == "`":
                in_backtick = False
            i += 1
            continue
        if c == "'":
            in_squote = True
            out.append(c)
            i += 1
            continue
        if c == '"':
            in_dquote = True
            out.append(c)
            i += 1
            continue
        if c == "`":
            in_backtick = True
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    if count:
        report.escape_rewrites += count
        report.escape_files.add(filename)
        report.escape_per_file[filename] = (
            report.escape_per_file.get(filename, 0) + count)
    return "".join(out)


# ---------------------------------------------------------------------------
# Statement-level translation
# ---------------------------------------------------------------------------

_UPDATE_ALIAS_TAIL = (r"(?:\s+(?:AS\s+)?"
                      r"(?!INNER\b|JOIN\b|ON\b|SET\b|LEFT\b|RIGHT\b|CROSS\b|"
                      r"OUTER\b|STRAIGHT_JOIN\b|USE\b|FORCE\b|IGNORE\b)"
                      r"([A-Za-z_]\w*))?")
_UPDATE_JOIN = re.compile(
    r"^UPDATE\s+`?(\w+)`?" + _UPDATE_ALIAS_TAIL + r"\s+"
    r"(?:INNER\s+)?JOIN\s+`?(\w+)`?" + _UPDATE_ALIAS_TAIL + r"\s+"
    r"ON\s+(.+?)\s+SET\s+(.+)$",
    re.I | re.S)

def _rewrite_concat(stmt: str) -> str:
    """CONCAT(a, b, ..) -> (a || b || ..) with balanced-paren argument
    scanning (regex cannot handle nesting); innermost-first via iteration."""
    for _ in range(16):
        upper = stmt.upper()
        pos = upper.find("CONCAT")
        if pos < 0:
            return stmt
        # only rewrite if followed by optional space + '('
        j = pos + len("CONCAT")
        while j < len(stmt) and stmt[j] in " 	":
            j += 1
        if j >= len(stmt) or stmt[j] != "(":
            return stmt
        depth = 0
        k = j
        in_s = False
        while k < len(stmt):
            c = stmt[k]
            if in_s:
                if c == "'":
                    if k + 1 < len(stmt) and stmt[k + 1] == "'":
                        k += 2
                        continue
                    in_s = False
                k += 1
                continue
            if c == "'":
                in_s = True
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        if depth != 0:
            return stmt
        args = _split_concat_args(stmt[j + 1:k])
        if not args:
            # CONCAT() -> '' (vacuous, but keep the transcript free of a
            # function SQLite only gained in 3.44)
            stmt = stmt[:pos] + "''" + stmt[k + 1:]
            continue
        if len(args) == 1:
            # single-arg CONCAT -> the argument itself: SQLite's native
            # concat() is 3.44+ and the device seed replay must run on
            # old framework SQLite (3.32 on API 33 - found on the
            # Retroid Pocket 6 device run)
            stmt = stmt[:pos] + f"({args[0]})" + stmt[k + 1:]
            continue
        stmt = (stmt[:pos] + "(" + " || ".join(args) + ")" + stmt[k + 1:])
    return stmt


_UPDATE_COMMA_JOIN = re.compile(
    r"^UPDATE\s+`?(\w+)`?\s+([A-Za-z_]\w*)\s*,\s*(.+?)\s+SET\s+(.+)$",
    re.I | re.S)
_SESSION_DROP = re.compile(
    r"^(LOCK\s+TABLES|UNLOCK\s+TABLES|USE\s|SET\s+(NAMES|CHARACTER|@OLD_|SQL_NOTES|@SQL_|"
    r"SQL_MODE|FOREIGN_KEY|UNIQUE_CHECKS|AUTOCOMMIT|sql_mode|time_zone|"
    r" @saved|@`|@CURRENT|NOTES|GROUP_CONCAT|collation|Collation_connection|sql_safe_updates|SQL_SAFE_UPDATES))",
    re.I)

_TYPE_MAP = [
    (re.compile(r"\bbigint\s*\(\d+\)\s+unsigned", re.I), "INTEGER"),
    (re.compile(r"\bbigint\s*\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\bbigint\b", re.I), "INTEGER"),
    (re.compile(r"\bmediumint\s*\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\bsmallint\s*\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\btinyint\s*\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\btinyint\b", re.I), "INTEGER"),
    (re.compile(r"\bint\s*\(\d+\)", re.I), "INTEGER"),
    (re.compile(r"\blongtext\b", re.I), "TEXT"),
    (re.compile(r"\bmediumtext\b", re.I), "TEXT"),
    (re.compile(r"\btinytext\b", re.I), "TEXT"),
    (re.compile(r"\bvarchar\s*\(\d+\)", re.I), "TEXT"),
    (re.compile(r"\bchar\s*\(\d+\)", re.I), "TEXT"),
    # the N-variants and LONG VARCHAR are TEXT-affinity spellings MySQL
    # accepts; unmapped they would seed silent-BINARY columns that also
    # evade the NOCASE blanket and tripwire (B-I-A, R4 polish)
    (re.compile(r"\bnvarchar\s*\(\d+\)", re.I), "TEXT"),
    (re.compile(r"\bnchar\s*\(\d+\)", re.I), "TEXT"),
    (re.compile(r"\blong\s+varchar\b", re.I), "TEXT"),
    (re.compile(r"\bNATIONAL\s+", re.I), ""),
    (re.compile(r"\blongblob\b", re.I), "BLOB"),
    (re.compile(r"\bmediumblob\b", re.I), "BLOB"),
    (re.compile(r"\btinyblob\b", re.I), "BLOB"),
    (re.compile(r"\bvarbinary\s*\(\d+\)", re.I), "BLOB"),
    (re.compile(r"\bbinary\s*\(\d+\)", re.I), "BLOB"),
    (re.compile(r"\bdouble\b", re.I), "REAL"),
    (re.compile(r"\bfloat\b", re.I), "REAL"),
    # DECIMAL/NUMERIC are exact in MySQL; SQLite has no exact decimal
    # type, so REAL (C double) is the deliberate, counted rendering -
    # every runtime consumer reads these as float/double anyway
    # (I-65: previously an uncounted NUMERIC-affinity fall-through).
    (re.compile(r"\bdecimal\s*\(\d+\s*,\s*\d+\)", re.I), "REAL"),
    (re.compile(r"\bdecimal\b", re.I), "REAL"),
    (re.compile(r"\bnumeric\s*\(\d+\s*,\s*\d+\)", re.I), "REAL"),
    (re.compile(r"\bnumeric\b", re.I), "REAL"),
]

_ENUM_RE = re.compile(r"\benum\s*\(", re.I)
_TABLE_TAIL_RE = re.compile(
    r"\)\s*ENGINE\s*=.*?(?=;|$)", re.I | re.S)
_CHARSET_TAIL_RE = re.compile(r"\)\s*DEFAULT\s+CHARSET\b.*?(?=;|$)", re.I | re.S)
_COLLATE_TAIL_RE = re.compile(r"\)\s*(AUTO_INCREMENT\s*=\s*\d+\s*)?(DEFAULT\s+)?(CHARSET|CHARACTER\s+SET|COLLATE)\s*=\s*\S+.*?(?=;|$)", re.I | re.S)
_COL_COMMENT_RE = re.compile(
    r"\bCOMMENT\s+'(?:[^'\\]|\\.|'')*'", re.I)
_UNSIGNED_RE = re.compile(r"\bunsigned\b", re.I)
_ZEROFILL_RE = re.compile(r"\bzerofill\b", re.I)
_INLINE_PK_CLAUSE = re.compile(r",\s*PRIMARY\s+KEY\s*\(`?(\w+)`?\)", re.I)
_KEY_LINE = re.compile(
    r"(?m)^[ \t]*,?[ \t]*(UNIQUE\s+|FULLTEXT\s+|SPATIAL\s+)?KEY\s+`?(\w+)`?\s*\(([^)]*)\)(?:\s+USING\s+\w+)?",
    re.I)
_CONSTRAINT_FK_LINE = re.compile(
    r"(?m)^[ \t]*,?[ \t]*CONSTRAINT\s+`?\w+`?\s+FOREIGN\s+KEY\s*\([^)]*\)\s+REFERENCES\s+\S+.*$",
    re.I)
_CHAR_SET_UTF8MB3 = re.compile(r"\bCHARACTER\s+SET\s+utf8mb3\b", re.I)
_TRUNCATE_RE = re.compile(r"^\s*TRUNCATE\s+(?:TABLE\s+)?", re.I)
_ALTER_CHANGE = re.compile(
    r"^ALTER\s+TABLE\s+`?\w+`?\s+(?:CHANGE|MODIFY)\s+(?:COLUMN\s+)?`?(\w+)`?\s+(.+)$",
    re.I | re.S)
_ALTER_ADD_INDEX = re.compile(
    r"^ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+(UNIQUE\s+)?(?:INDEX|KEY)\s+(?:`?(\w+)`?\s*)?\(([^)]*)\)",
    re.I | re.S)
_NOW_RE = re.compile(r"\bNOW\s*\(\s*\)", re.I)
_ON_UPDATE_NOW = re.compile(
    r"\s+ON\s+UPDATE\s+CURRENT_TIMESTAMP(\(\s*\))?", re.I)
_SET_VAR = re.compile(r"^SET\s+@`?(\w+)`?\s*:?=\s*(.+)$", re.I | re.S)
_VAR_USE = re.compile(r"@`?`?(\w+)`?`?")
_LIKE_AS_INT_PK = re.compile(r"CREATE\s+TABLE\s+`?\w+`?\s+LIKE\s+`?\w+`?", re.I)


def _fold_set_expr(expr: str) -> str | None:
    """Evaluate a SET @x := expr literal (number, string, NULL, or
    CONCAT of literals). Returns the SQL literal or None if not static."""
    expr = expr.strip().rstrip(";").strip()
    if expr.upper() == "NULL":
        return "NULL"
    m = re.fullmatch(r"'((?:[^'\\]|\\.|'')*)'", expr, re.S)
    if m:
        return expr  # already a literal (escape-rewritten upstream)
    if re.fullmatch(r"-?\d+(\.\d+)?", expr):
        return expr
    m = re.fullmatch(r"CONCAT\((.*)\)", expr, re.I | re.S)
    if m:
        parts = _split_concat_args(m.group(1))
        if parts is None:
            return None
        folded = []
        for part in parts:
            lit = _fold_set_expr(part)
            if lit is None:
                return None
            if lit == "NULL":
                return None
            if lit.startswith("'"):
                folded.append(lit[1:-1])
            else:
                folded.append(lit)
        # SQLite concatenation: keep as || of literals (SQL-valid)
        return "||".join(f"'{p}'" for p in folded) if folded else "''"
    return None


def _split_concat_args(text: str) -> list[str] | None:
    args: list[str] = []
    buf: list[str] = []
    in_s = False
    depth = 0
    for ch in text:
        if in_s:
            buf.append(ch)
            if ch == "'":
                in_s = False
            continue
        if ch == "'":
            in_s = True
            buf.append(ch)
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            args.append("".join(buf).strip())
            buf = []
            continue
        buf.append(ch)
    tail = "".join(buf).strip()
    if tail:
        args.append(tail)
    return args if args else None


def _translate_enums(stmt: str, report: TranslationReport) -> str:
    """enum('a','b') -> TEXT with a column CHECK (quote-aware values)."""
    out = []
    i = 0
    n = len(stmt)
    in_s = False
    while i < n:
        c = stmt[i]
        if in_s:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(stmt[i + 1])
                i += 2
                continue
            if c == "'":
                in_s = False
            i += 1
            continue
        if c == "'":
            in_s = True
            out.append(c)
            i += 1
            continue
        if stmt[i:i + 4].lower() == "enum" and i + 4 < n \
                and stmt[i + 4] in " \t(" \
                and not re.match(r"\w", stmt[i - 1] if i else " "):
            # find the column name immediately before this enum
            before = "".join(out)
            m = re.search(r"(`?\w+`?)\s*$", before)
            # scan the quote-aware values list
            j = stmt.index("(", i)
            depth = 0
            k = j
            in_v = False
            while k < n:
                ch = stmt[k]
                if in_v:
                    if ch == "\\":
                        k += 2
                        continue
                    if ch == "'":
                        in_v = False
                    k += 1
                    continue
                if ch == "'":
                    in_v = True
                elif ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        break
                k += 1
            values = stmt[j + 1:k]
            col = m.group(1) if m else "col"
            report.enum_columns += 1
            out.append(f"TEXT CHECK({col} IN ({values}))")
            i = k + 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


_INSERT_VALUES_RE = re.compile(
    r"^(INSERT\s+INTO\s+(`?\w+`?).*?)\s+VALUES\s*\((.*)\)\s*$",
    re.I | re.S)

# SQLITE_MAX_SQL_LENGTH defaults to 1,000,000,000 in the pinned
# amalgamation (sqlite3.c SQLITE_MAX_SQL_LENGTH), so the engine limit is
# not the binding constraint today; the 800 KiB chunk bound is a
# deliberate safety margin for any engine/host build with a tighter
# limit and for bounded on-device replay buffers (I-62 records that the
# measurement itself must be BYTES - multibyte-dense locale text
# inflates char counts ~3x vs bytes).
_INSERT_CHUNK_LIMIT = 800_000


def _split_value_rows(body: str) -> list[str] | None:
    """Quote/paren-aware split of a multi-row VALUES body into row texts
    (each WITHOUT its delimiting parens - the caller's regex consumed
    the outermost '(' and the final ')'). Handles both the contiguous
    '),(' and the one-row-per-line '),\\n(' corpus styles. Returns None
    if the body is not well-formed.

    Depth semantics: the scanner sits INSIDE the outer paren (depth 1).
    A row's closing ')' brings depth to 0; between rows only whitespace,
    exactly one ',', and the next row's '(' (back to depth 1) may
    appear. The final row never closes inside the body - its ')' was
    consumed by the caller's regex - so EOF at depth 1 with accumulated
    text is the clean end."""
    rows: list[str] = []
    buf: list[str] = []
    depth = 1
    in_q = False
    in_dq = False  # top-level "..." literals (ANSI_QUOTES off: MySQL
    # string literals). Mirrors _split_statements' dquote toggling so
    # both scanners agree on boundaries (I-69).
    saw_comma = False
    i = 0
    n = len(body)
    while i < n:
        c = body[i]
        if in_q:
            buf.append(c)
            if c == "'" and i + 1 < n and body[i + 1] == "'":
                buf.append("'")
                i += 2
                continue
            if c == "'":
                in_q = False
            i += 1
            continue
        if in_dq:
            buf.append(c)
            if c == '"':
                in_dq = False
            i += 1
            continue
        if c == "'":
            in_q = True
            buf.append(c)
            i += 1
            continue
        if c == '"':
            in_dq = True
            buf.append(c)
            i += 1
            continue
        if depth == 0:
            # between rows: ws*, one ',', ws*, '('
            if c in " \t\r\n":
                i += 1
                continue
            if c == "," and not saw_comma:
                saw_comma = True
                i += 1
                continue
            if c == "(" and saw_comma:
                depth = 1
                saw_comma = False
                buf = []
                i += 1
                continue
            return None
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                rows.append("".join(buf))
                buf = []
                i += 1
                continue
        buf.append(c)
        i += 1
    if in_q or in_dq or depth != 1:
        return None  # unterminated: not well-formed
    if buf or not rows:
        rows.append("".join(buf))
    return rows


def _sub_outside_literals(stmt: str, pattern: re.Pattern[str],
                          repl, backticks: bool = True) -> str:
    """Apply a regex substitution ONLY outside string literals
    (single/double-quoted with '' doubling, and - unless
    backticks=False - backtick identifiers). Statement-wide re.sub
    alone rewrites literal interiors - live corruption on this corpus
    ('#value (0..100)' -> '#VALUES (0..100)' in mangos_string help
    text). `repl` may be a string or the callable re.sub accepts (used
    by the @var paths: MySQL never substitutes session variables inside
    quoted literals - I-61). backticks=False serves the NOCASE pass:
    column DEFINITIONS are backtick-dense (the identifier must join the
    same match as its TEXT type), so only quoted strings are excluded
    there (I-68)."""
    out: list[str] = []
    i = 0
    n = len(stmt)
    seg_start = 0

    def flush(end: int) -> None:
        if end > seg_start:
            out.append(pattern.sub(repl, stmt[seg_start:end]))

    while i < n:
        c = stmt[i]
        if c == "'":
            flush(i)
            j = i + 1
            while j < n:
                if stmt[j] == "'":
                    if j + 1 < n and stmt[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            out.append(stmt[i:min(j + 1, n)])
            i = j + 1
            seg_start = i
            continue
        if c == '"' or (c == "`" and backticks):
            close = c
            flush(i)
            j = i + 1
            while j < n and stmt[j] != close:
                j += 1
            out.append(stmt[i:min(j + 1, n)])
            i = j + 1
            seg_start = i
            continue
        i += 1
    flush(n)
    return "".join(out)


def _chunk_insert(stmt: str, report: TranslationReport) -> list[str]:
    """Split a multi-row INSERT into chunks under _INSERT_CHUNK_LIMIT."""
    m = _INSERT_VALUES_RE.match(stmt)
    if not m or len(stmt.encode("utf-8")) <= _INSERT_CHUNK_LIMIT:
        return [stmt]
    head, _, body = m.group(1), m.group(2), m.group(3)
    rows = _split_value_rows(body)
    if rows is None or len(rows) < 2:
        return [stmt]
    out: list[str] = []
    chunk: list[str] = []
    size = len(head.encode("utf-8"))
    for row in rows:
        # measure in UTF-8 BYTES everywhere (I-53/I-62): the entry gate
        # above, the accumulation, and the post-flush reset must agree -
        # multibyte-dense statements must not skip chunking because
        # their CHAR count sits under the limit.
        row_len = len(row.encode("utf-8")) + 3
        if chunk and size + row_len > _INSERT_CHUNK_LIMIT:
            out.append(f"{head} VALUES\n({'),('.join(chunk)})")
            chunk = []
            size = len(head.encode("utf-8"))
        chunk.append(row)
        size += row_len
    if chunk:
        out.append(f"{head} VALUES\n({'),('.join(chunk)})")
    report.insert_chunks_split += 1
    return out


_RUNNING_VAR = re.compile(r"@(\w+)\s*:=\s*@\1\s*\+\s*(\d+)")


def _expand_insert_variables(stmt: str, variables: dict[str, str]) -> str:
    """The classic-dump idiom: INSERT ... VALUES (@x := @x + 1, ...), ...
    - a per-row RUNNING counter seeded by an earlier SET @x := literal.
    Expands each row's assignments to their evaluated literals (MySQL's
    assignment expression yields the NEW value)."""
    m = _INSERT_VALUES_RE.match(stmt)
    if not m or "@" not in stmt:
        return stmt
    head, body = m.group(1), m.group(3)
    rows = _split_value_rows(body)
    if rows is None:
        return stmt

    def state() -> dict[str, int]:
        return {k: int(v) for k, v in variables.items()
                if v is not None and re.fullmatch(r"-?\d+", v)}

    counters = state()

    def sub_row(row: str) -> str:
        def running(match: re.Match) -> str:
            name, inc = match.group(1), int(match.group(2))
            if name not in counters:
                raise RuntimeError(
                    f"running counter @{name} used before any SET seeded "
                    f"it (silent 0-based numbering risk): {row[:100]!r}")
            counters[name] = counters[name] + inc
            return str(counters[name])
        # Literal-aware (I-61): a '@name' inside a string literal is
        # DATA, not a variable reference - MySQL never substitutes
        # session variables inside quoted literals.
        row = _sub_outside_literals(row, _RUNNING_VAR, running)
        def plain(match: re.Match) -> str:
            name = match.group(1)
            if name in counters:
                return str(counters[name])
            value = variables.get(name)
            return value if value is not None else match.group(0)
        return _sub_outside_literals(row, _VAR_USE, plain)

    rows = [sub_row(row) for row in rows]
    # Persist final counter values: a later statement reading @x must see
    # the post-INSERT value (MySQL replay-order semantics), not the
    # pre-INSERT SET value.
    for name, value in counters.items():
        if name in variables and variables[name] != str(value):
            variables[name] = str(value)
    return f"{head} VALUES\n({'),('.join(rows)})"


def _assert_no_unsubstituted_vars(stmt: str, filename: str) -> None:
    """Any @name left OUTSIDE a literal becomes a SQLite named bind
    parameter - silent data corruption if executed. Fail loud instead."""
    in_q = in_dq = in_bt = False
    i = 0
    n = len(stmt)
    while i < n:
        c = stmt[i]
        if in_q:
            if c == "'":
                if i + 1 < n and stmt[i + 1] == "'":
                    i += 2
                    continue
                in_q = False
            i += 1
            continue
        if in_dq:
            if c == '"':
                in_dq = False
            i += 1
            continue
        if in_bt:
            if c == "`":
                in_bt = False
            i += 1
            continue
        if c == "'":
            in_q = True
        elif c == '"':
            in_dq = True
        elif c == "`":
            in_bt = True
        elif c == "@":
            # accept the backticked @`name` spelling too (I-71) - a
            # legitimate MySQL variable form that previously escaped
            # both the substitution and this fail-loud assert
            m = re.match(r"@`?(\w+)`?", stmt[i:])
            if m:
                raise RuntimeError(
                    f"{filename}: unsubstituted MySQL variable @{m.group(1)} "
                    f"would become a SQLite bind parameter: "
                    f"{stmt[:120]!r}")
        i += 1


def _split_and_conditions(body: str) -> list[str]:
    """Top-level AND split of a WHERE body (literal-aware; OR groups and
    parenthesized expressions stay intact)."""
    if not body.strip():
        return []
    pieces: list[str] = []
    buf: list[str] = []
    depth = 0
    in_squote = in_dquote = in_backtick = False
    i, n = 0, len(body)
    while i < n:
        c = body[i]
        if in_squote:
            buf.append(c)
            if c == "'":
                if i + 1 < n and body[i + 1] == "'":
                    buf.append("'")
                    i += 2
                    continue
                in_squote = False
            i += 1
            continue
        if in_dquote:
            buf.append(c)
            if c == '"':
                in_dquote = False
            i += 1
            continue
        if in_backtick:
            buf.append(c)
            if c == "`":
                in_backtick = False
            i += 1
            continue
        if c == "'":
            in_squote = True
        elif c == '"':
            in_dquote = True
        elif c == "`":
            in_backtick = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif (depth == 0 and body[i:i + 5].upper() == " AND "
                and not _word_char_before(body, i)
                and not _word_char_after(body, i + 5)):
            pieces.append("".join(buf).strip())
            buf = []
            i += 5
            continue
        buf.append(c)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        pieces.append(tail)
    return pieces


def _word_char_before(text: str, index: int) -> bool:
    return index > 0 and (text[index - 1].isalnum() or text[index - 1] == "_")


def _word_char_after(text: str, index: int) -> bool:
    return index < len(text) and (text[index].isalnum() or text[index] == "_")


def translate_statement(stmt: str, report: TranslationReport,
                        variables: dict[str, str], pending: list[str],
                        db_state: DBSchemaState | None = None) -> str | None:
    """Translate one statement. Returns the SQLite statement or None (drop).
    Additional statements (e.g. emitted CREATE INDEX) are appended to
    `pending`. `db_state` carries per-database schema tracking so
    positional ADD COLUMN statements can rebuild tables in MySQL-effective
    column order (I-189); positional adds without it fail loud."""
    stmt = stmt.strip()
    if not stmt:
        return None
    if _SESSION_DROP.match(stmt):
        report.dropped_session += 1
        return None

    # SET @x := value  (fold in replay order)
    m = _SET_VAR.match(stmt)
    if m:
        value = _fold_set_expr(m.group(2))
        if value is not None:
            variables[m.group(1)] = value
            report.set_vars_folded += 1
        elif re.match(r"@@", m.group(2).strip()):
            # assigned from @@system state (e.g. saved_cs_client): session
            # scope, never data. Misuse in a data statement still fails
            # loudly at execution (unbound named parameter).
            report.session_vars.add(m.group(1))
            report.dropped_session += 1
        else:
            # non-static: refuse to guess (fail loud at the harness)
            variables[m.group(1)] = None  # type: ignore[assignment]
        return None

    # INSERT ... VALUE ( -> VALUES ( (MySQL allows the singular). Runs
    # BEFORE the @var expansion so row-aware counters work on the
    # singular form too (I-70); literal-aware (I-48).
    if stmt.upper().startswith("INSERT"):
        stmt = _sub_outside_literals(
            stmt, re.compile(r"\bVALUE\s*\(", re.I), "VALUES (")

    # Substitute folded @vars: INSERT ... VALUES gets row-aware
    # running-counter expansion (falling back to generic substitution for
    # INSERT ... SELECT and non-expanding shapes).
    if stmt.upper().startswith("INSERT") and "@" in stmt:
        expanded = _expand_insert_variables(stmt, variables)
        if expanded == stmt:
            def _sub(match: re.Match) -> str:
                name = match.group(1)
                if name in variables and variables[name] is not None:
                    return variables[name]
                return match.group(0)
            stmt = _sub_outside_literals(stmt, _VAR_USE, _sub)
        else:
            stmt = expanded
    else:
        def _sub(match: re.Match) -> str:
            name = match.group(1)
            if name in variables and variables[name] is not None:
                return variables[name]
            return match.group(0)
        stmt = _sub_outside_literals(stmt, _VAR_USE, _sub)

    # Conditional comments: /*!NNNNN content */ - execute <= 80000.
    def _cond(match: re.Match) -> str:
        version = int(match.group(1))
        content = match.group(2).strip()
        if version > 80000:
            return ""
        if re.match(r"ALTER\s+TABLE.*(DISABLE|ENABLE)\s+KEYS", content, re.I):
            return ""
        report.conditional_executed += 1
        return content
    stmt = re.sub(r"/\*!(\d{5})\s*(.*?)\s*\*/", _cond, stmt, flags=re.S)

    stmt = stmt.strip()
    if not stmt:
        return None
    if _SESSION_DROP.match(stmt):
        report.dropped_session += 1
        return None
    # Statements that materialized out of /*!...*/ expansions can
    # themselves be SET @var forms - re-check.
    m = _SET_VAR.match(stmt)
    if m:
        value = _fold_set_expr(m.group(2))
        if value is not None:
            variables[m.group(1)] = value
            report.set_vars_folded += 1
        elif re.match(r"@@", m.group(2).strip()):
            report.session_vars.add(m.group(1))
            report.dropped_session += 1
        else:
            variables[m.group(1)] = None  # type: ignore[assignment]
        return None
    if re.match(r"SET\s+@\w+\s*:?=\s*@@", stmt, re.I):
        # @@system-variable reads (character_set_client etc.) are session
        # state, not data: drop with a count.
        report.dropped_session += 1
        return None

    # TRUNCATE -> DELETE FROM
    if _TRUNCATE_RE.match(stmt):
        stmt = _TRUNCATE_RE.sub("DELETE FROM ", stmt)
        stmt = re.sub(r"DELETE\s+FROM\s+DELETE\s+FROM", "DELETE FROM", stmt, flags=re.I)
        report.truncates += 1

    # MySQL && / || logical operators (SQLite: AND / OR; || is concat in
    # SQLite - the corpus uses only &&, verified). Literal-aware: mangos
    # help text carries '&&'/'value ('-shaped strings (I-48).
    stmt = _sub_outside_literals(stmt, re.compile(r"\s*&&\s*"), " AND ")

    # MySQL multi-table UPDATE .. JOIN -> correlated-subquery UPDATE.
    # SQLite's UPDATE ... FROM needs >= 3.33 (2020) - the DEVICE seed
    # replay runs on Android's framework SQLite (3.32.2 on API 33:
    # UPDATE-FROM is a syntax error there - found on the first real
    # device run, the Retroid Pocket 6). The correlated form is >= 3.8
    # and semantically equivalent for the corpus's FK-shaped joins:
    # each target row takes the first matching source row; rows with
    # no match are skipped (inner-join UPDATE semantics).
    m = _UPDATE_JOIN.match(stmt)
    if m:
        t1, a1, t2, a2, on_cond, set_clause = m.groups()
        # No AS alias on the UPDATE target: SQLite only accepts target
        # aliases from the 3.19/3.24 era onward and the framework floor
        # is 3.18 (minSdk 26). Correlation by table name is ancient.
        lhs = f"`{t1}`"
        rhs = f"`{t2}` AS {a2}" if a2 else f"`{t2}`"
        # SQLite requires unqualified SET targets; the source alias must
        # keep resolving, so only the TARGET's qualifiers are stripped.
        if a1:
            set_clause = re.sub(
                rf"(^|,\s*)(?:{a1}|`{t1}`)\.(\w+)\s*=", r"\1\2 =",
                set_clause, flags=re.I)
        src_ref = re.compile(rf"\b(?:{a2}|`?{t2}`?)\.", re.I)
        assignments = []
        for piece in _split_body_entries(set_clause):
            column, sep, expression = piece.partition("=")
            if sep and src_ref.search(expression):
                assignments.append(
                    f"{column.strip()} = (SELECT {expression.strip()} "
                    f"FROM {rhs} WHERE {on_cond})")
            else:
                assignments.append(piece)
        report.update_joins += 1
        stmt = (f"UPDATE {lhs} SET {', '.join(assignments)} "
                f"WHERE EXISTS (SELECT 1 FROM {rhs} WHERE {on_cond})")
        if a1:
            stmt = _sub_outside_literals(
                stmt, re.compile(rf"\b{re.escape(a1)}\."), f"`{t1}`.")

    # MySQL comma-form multi-table UPDATE: UPDATE t a, (SELECT..) b [, ..]
    # SET .. [WHERE ..] - same 3.32-compat treatment: WHERE conditions
    # referencing a derived-table alias become the subquery's own
    # correlation (plus an EXISTS guard); target-only conditions stay
    # in the outer WHERE.
    m = _UPDATE_COMMA_JOIN.match(stmt)
    if m:
        t1, a1, rest, set_clause = m.groups()
        where_part = ""
        wm = re.search(r"\sWHERE\s", set_clause, re.I)
        if wm:
            where_part = set_clause[wm.start():]
            set_clause = set_clause[:wm.start()]
        if a1:
            set_clause = re.sub(
                rf"(^|,\s*)(?:{a1}|`{t1}`)\.(\w+)\s*=", r"\1\2 =",
                set_clause, flags=re.I)
        derived_aliases = re.findall(r"\)\s+([A-Za-z_]\w*)\s*(?:,|$)", rest)
        derived_ref = (re.compile(rf"\b(?:{'|'.join(derived_aliases)})\.", re.I)
                       if derived_aliases else None)
        links, filters = [], []
        body = where_part.strip()
        if body[:6].upper() == "WHERE ":
            body = body[6:].strip()
        for condition in _split_and_conditions(body):
            if not condition:
                continue
            if derived_ref and derived_ref.search(condition):
                links.append(condition)
            else:
                filters.append(condition)
        inner = f" WHERE {' AND '.join(links)}" if links else ""
        assignments = []
        for piece in _split_body_entries(set_clause):
            column, sep, expression = piece.partition("=")
            if sep and derived_ref and derived_ref.search(expression):
                assignments.append(
                    f"{column.strip()} = (SELECT {expression.strip()} "
                    f"FROM {rest}{inner})")
            else:
                assignments.append(piece)
        outer = filters[:]
        if links:
            outer.append(f"EXISTS (SELECT 1 FROM {rest}{inner})")
        report.update_joins += 1
        # Same 3.18-floor rule as the JOIN branch: no alias on the
        # UPDATE target; alias references re-qualify by table name.
        stmt = (f"UPDATE `{t1}` SET {', '.join(assignments)}"
                + (f" WHERE {' AND '.join(outer)}" if outer else ""))
        if a1:
            stmt = _sub_outside_literals(
                stmt, re.compile(rf"\b{re.escape(a1)}\."), f"`{t1}`.")

    # CONCAT(a, b, ..) -> a || b || .. (nested forms iterate to fixpoint).
    for _ in range(8):
        if "CONCAT" not in stmt.upper():
            break
        new = _rewrite_concat(stmt)
        if new == stmt:
            break
        stmt = new

    # ALTER TABLE ... ADD [COLUMN] x ...: plain adds keep the single
    # statement (SQLite append == MySQL semantics); POSITIONAL adds
    # (AFTER `col` / FIRST) rebuild the table - stripping the position
    # shifts every later column, and SELECT * loads map positionally
    # (I-189: taxiPathId read data1, world boot SIGSEGV).
    if re.match(r"^ALTER\s+TABLE\b", stmt, re.I):
        # ALTER TABLE t RENAME TO u: SQLite-native; the tracker must
        # follow so a later positional rebuild on the new name works
        # (I-189 R1 lane-A finding: the rename hook was dead code).
        m_rename = re.match(
            r"^ALTER\s+TABLE\s+`?(\w+)`?\s+RENAME\s+TO\s+`?(\w+)`?\s*$",
            stmt, re.I)
        if m_rename:
            if db_state is not None:
                db_state.rename(m_rename.group(1), m_rename.group(2))
            report.statements += 1
            return stmt
        positional = _extract_position(stmt)
        if positional is not None:
            return _emit_positional_rebuild(
                stmt, positional, report, pending, db_state)
        stmt = _translate_alter_body(stmt, report)
        m_add = _ADD_COLUMN_RE.match(stmt)
        if m_add and db_state is not None:
            tracked = db_state.tables.get(m_add.group(1))
            if tracked is not None and not tracked.opaque:
                tracked.entries.append(
                    f"`{m_add.group(2)}` {m_add.group(3).strip()}")

    m_drop = re.match(
        r"^DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?(\w+)`?", stmt, re.I)
    if m_drop and db_state is not None:
        db_state.drop(m_drop.group(1))

    # ALTER .. ADD INDEX/KEY -> CREATE [UNIQUE] INDEX
    m = _ALTER_ADD_INDEX.match(stmt)
    if m:
        table = m.group(1)
        unique = m.group(2)
        name = m.group(3) or f"auto_{re.sub('[^A-Za-z0-9_]+', '_', m.group(4))}"
        name = f"{table}_{name}"
        cols = m.group(4)
        index_stmt = (f"CREATE {'UNIQUE ' if unique else ''}INDEX "
                      f"IF NOT EXISTS `{name}` ON `{table}` ({cols})")
        pending.append(index_stmt)
        if db_state is not None:
            db_state.add_index(table, index_stmt)
        report.alter_add_index += 1
        if unique:
            report.unique_indexes_emitted += 1
        else:
            report.indexes_emitted += 1
        return None

    # ALTER .. CHANGE/MODIFY: when old != new name it is a rename. SQLite's
    # RENAME COLUMN is 3.25+ - older framework SQLite (the device floor is
    # 3.18-class, minSdk 26) fails to parse it - so a rename is emitted as
    # the same schema-tracked table rebuild the positional ADDs use (I-189):
    # rename away, re-create with the renamed column, copy rows back mapping
    # old->new, drop the old, re-emit indexes. Type deltas ride along in the
    # rebuilt column def (no-ops under affinity when unchanged).
    # Same-name MODIFY is a no-op (audited).
    m = _ALTER_CHANGE.match(stmt)
    if m:
        table_m = re.match(r"^ALTER\s+TABLE\s+`?(\w+)`?", stmt, re.I)
        table = table_m.group(1) if table_m else "?"
        rest = m.group(2).strip()
        new_name_m = re.match(r"`([^`]+)`|(\w+)", rest)
        old_name = m.group(1)
        new_name = (new_name_m.group(1) or new_name_m.group(2)) if new_name_m else None
        is_rename = bool(new_name_m) and new_name.lower() != old_name.lower()
        if is_rename and db_state is None:
            raise RuntimeError(
                "ALTER .. CHANGE rename requires a schema-tracked pass "
                "(DBSchemaState); SQLite RENAME COLUMN is 3.25+ and the "
                "rebuild needs the CREATE body: " + " ".join(stmt.split())[:120])
        tracked = db_state.tables.get(table) if db_state is not None else None
        if is_rename and (tracked is None or tracked.opaque):
            raise RuntimeError(
                f"ALTER .. CHANGE rename on untracked/opaque table `{table}`: "
                f"the 3.18-floor rebuild needs its CREATE body")
        if tracked is not None and not tracked.opaque and new_name_m:
            # keep the tracked def current (translated types) so a later
            # rebuild reproduces the column exactly
            new_def = rest[new_name_m.end():].strip()
            for i, entry in enumerate(tracked.entries):
                name = _entry_column_name(entry)
                if name is not None and name.lower() == old_name.lower():
                    tracked.entries[i] = (
                        f"`{new_name}` {new_def}".rstrip())
                    break
        if is_rename:
            report.alter_change_renames += 1
            new_cols = tracked.column_names()
            select_cols = [old_name if c.lower() == new_name.lower() else c
                           for c in new_cols]
            staging = f"{table}__rename_rebuild"
            pending.append(f"ALTER TABLE `{table}` RENAME TO `{staging}`")
            pending.append("CREATE TABLE `%s` (\n  %s\n)"
                           % (table, ",\n  ".join(tracked.entries)))
            col_list = ", ".join(f"`{c}`" for c in new_cols)
            sel_list = ", ".join(f"`{c}`" for c in select_cols)
            pending.append(f"INSERT INTO `{table}` ({col_list}) "
                           f"SELECT {sel_list} FROM `{staging}`")
            pending.append(f"DROP TABLE `{staging}`")
            pending.extend(tracked.indexes)
            report.rename_rebuilds += 1
            return None
        report.alter_change_noops += 1
        return None

    if _LIKE_AS_INT_PK.match(stmt):
        m2 = re.match(r"CREATE\s+TABLE\s+`?(\w+)`?\s+LIKE\s+`?(\w+)`?", stmt, re.I)
        stmt = f"CREATE TABLE `{m2.group(1)}` AS SELECT * FROM `{m2.group(2)}`"

    # MySQL CREATE TABLE t (SELECT ...) -> SQLite CREATE TABLE t AS SELECT
    m2 = re.match(r"^CREATE\s+TABLE\s+`?(\w+)`?\s*\(\s*(SELECT.+)\)\s*$",
                  stmt, re.I | re.S)
    if m2:
        stmt = f"CREATE TABLE `{m2.group(1)}` AS {m2.group(2)}"
        if db_state is not None:
            # column shape comes from the SELECT: opaque to the tracker
            db_state.register_opaque(m2.group(1))
    elif stmt.upper().startswith("CREATE TABLE"):
        pending_before = len(pending)
        stmt = _translate_create_table(stmt, report, pending)
        if db_state is not None:
            name_body = _create_table_name_and_body(stmt)
            if name_body is None:
                raise RuntimeError(
                    "CREATE TABLE lost its parseable shape: "
                    + " ".join(stmt.split())[:120])
            table, body = name_body
            if (re.search(r"\bIF\s+NOT\s+EXISTS\b", stmt, re.I)
                    and table in db_state.tables):
                # SQLite keeps the existing table, so the tracker keeps
                # its existing shape (a skipped CREATE changes nothing);
                # the emitted indexes are IF NOT EXISTS no-ops anyway.
                tracked = db_state.tables[table]
                tracked.indexes.extend(pending[pending_before:])
            else:
                db_state.register_create(table, _split_body_entries(body))
                db_state.tables[table].indexes.extend(
                    pending[pending_before:])
    else:
        # Non-CREATE statements: type rewrites are unnecessary; only strip
        # the utf8mb3 clause and rewrite NOW() for portability
        # (literal-aware: I-48).
        stmt = _sub_outside_literals(stmt, _CHAR_SET_UTF8MB3, "")
        stmt = _sub_outside_literals(stmt, _NOW_RE, "CURRENT_TIMESTAMP")

    report.statements += 1
    return stmt


_NOCASE_RE = re.compile(
    r"((?:\(|,|\bCOLUMN\s+|\bADD\s+"
    # after ADD, an index/constraint keyword means the next token is
    # NOT a column definition (an index NAMED "text" must not eat a
    # COLLATE - I-76, post-convergence polish; the corruption was loud,
    # but loud-miss is the only acceptable failure mode here)
    r"(?!(?:INDEX|KEY|UNIQUE|CONSTRAINT|PRIMARY|FOREIGN|FULLTEXT|"
    r"SPATIAL|CHECK)\b))"
    r"\s*`?[A-Za-z_]\w*`?[ \t]+TEXT\b)"
    r"(?![^\n,]*\bCOLLATE\b)", re.I)


def _apply_nocase(stmt: str, report: TranslationReport) -> str:
    """Append COLLATE NOCASE to every TEXT-family column definition.

    Column boundaries are '(' (body open), ',' (next column), the
    'COLUMN ' of an ALTER..ADD COLUMN clause, or a bare 'ADD ' (the
    ALTER..ADD form without the COLUMN keyword - the dominant in-corpus
    ADD idiom; I-67). The negative lookahead stops at the next
    comma/newline so an explicit COLLATE later in the SAME column
    definition (renamed below to NOCASE/BINARY) suppresses the blanket
    append. Literal-aware (I-68): the substitution never fires inside
    string literals. Shared by the CREATE TABLE and ALTER paths."""
    def _nocase(match: re.Match) -> str:
        report.nocase_columns += 1
        return match.group(1) + " COLLATE NOCASE"
    return _sub_outside_literals(stmt, _NOCASE_RE, _nocase,
                                 backticks=False)


_EXPLICIT_COLLATE_RE = re.compile(r"\bCOLLATE\s+=?\s*`?(\w+)`?", re.I)


def _rename_explicit_collations(stmt: str) -> str:
    """Explicit column collations: *_ci (and bare 'binary'-NO) render as
    COLLATE NOCASE; *_bin/_cs and the bare MySQL collation name
    'binary' render as an explicit COLLATE BINARY (keeps the blanket
    NOCASE pass off those columns); unknown collations are stripped.
    Literal-aware (I-68); shared by the CREATE TABLE and ALTER paths
    (I-67: the ALTER branch previously never renamed, leaving a raw
    collation name that failed loud at seed)."""
    def _collate(m: re.Match) -> str:
        name = m.group(1).lower()
        if name.endswith("_ci"):
            return "COLLATE NOCASE"
        if name.endswith("_bin") or name.endswith("_cs") or name == "binary":
            return "COLLATE BINARY"
        return ""
    return _sub_outside_literals(stmt, _EXPLICIT_COLLATE_RE, _collate)


def _translate_create_table(stmt: str, report: TranslationReport,
                            pending: list[str]) -> str:
    # Strip the table-options tail after the closing ')'.
    stmt = _TABLE_TAIL_RE.sub(")", stmt)
    stmt = _COLLATE_TAIL_RE.sub(")", stmt)
    stmt = _CHAR_SET_UTF8MB3.sub("", stmt)
    stmt = _rename_explicit_collations(stmt)
    stmt = _ON_UPDATE_NOW.sub("", stmt)
    # Column COMMENTs.
    stmt = _COL_COMMENT_RE.sub("", stmt)

    # KEY / UNIQUE KEY lines -> emitted indexes (addendum (b): UNIQUE KEY
    # becomes CREATE UNIQUE INDEX). Emit AFTER the table.
    table_match = re.match(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?", stmt, re.I)
    table_name = table_match.group(1) if table_match else "unknown"

    def _key_to_index(match: re.Match) -> str:
        unique, name, cols = match.group(1), match.group(2), match.group(3)
        if unique and unique.strip().upper().startswith("UNIQUE"):
            idx = f"{table_name}_{name}"
            pending.append(f"CREATE UNIQUE INDEX IF NOT EXISTS `{idx}` "
                           f"ON `{table_name}` ({cols})")
            report.unique_indexes_emitted += 1
        else:
            # FULLTEXT/SPATIAL degrade to ordinary indexes (no FTS in the
            # translated schema - justified fidelity diff, counted here).
            idx = f"{table_name}_{name}"
            pending.append(f"CREATE INDEX IF NOT EXISTS `{idx}` "
                           f"ON `{table_name}` ({cols})")
            report.indexes_emitted += 1
        return ""
    stmt = _KEY_LINE.sub(_key_to_index, stmt)

    # Inline FK constraints are dropped (runtime runs foreign_keys=OFF on
    # both engines; MySQL base dumps' inline FKs are advisory here).
    stmt = _CONSTRAINT_FK_LINE.sub("", stmt)

    # enum('a','b') -> TEXT with a column CHECK (quote-aware value scan).
    stmt = _translate_enums(stmt, report)

    # int(N)/INT/integer [unsigned] ... AUTO_INCREMENT column (any
    # modifier order) -> INTEGER PRIMARY KEY AUTOINCREMENT (the rowid
    # alias, addendum (d)). The matching table-level PRIMARY KEY clause
    # is dropped below.
    def _autoinc_line(match: re.Match) -> str:
        return (f"{match.group(1)} INTEGER PRIMARY KEY AUTOINCREMENT"
                f"{match.group(2)}")
    stmt = re.sub(
        r"(?m)^([ \t]*`?\w+`?)[ \t]+(?:(?:tiny|small|medium)?int(?:eger)?"
        r"(?:\s*\(\d+\))?|bigint(?:\s*\(\d+\))?)(?:\s+unsigned)?[^\n]*?"
        r"AUTO_INCREMENT[^,\n]*(,?)[ \t]*$",
        _autoinc_line, stmt, flags=re.I)
    m = _INLINE_PK_CLAUSE.search(stmt)
    if "AUTOINCREMENT" in stmt and m:
        stmt = _INLINE_PK_CLAUSE.sub("", stmt, count=1)

    # NOW() defaults -> CURRENT_TIMESTAMP (valid as a SQLite default).
    stmt = _NOW_RE.sub("CURRENT_TIMESTAMP", stmt)

    # Type rewrites + modifiers. decimal/numeric are counted before the
    # rewrite so the baseline pins the exact column count (I-65).
    report.decimal_rewrites += len(re.findall(
        r"\b(?:decimal|numeric)\b", stmt, re.I))
    for pat, repl in _TYPE_MAP:
        stmt = pat.sub(repl, stmt)
    stmt = _UNSIGNED_RE.sub("", stmt)
    stmt = _ZEROFILL_RE.sub("", stmt)

    # MySQL text columns compare case-INSENSITIVELY: the server-default
    # collations for utf8/utf8mb3/latin1 are *_ci, so every varchar/text
    # column in these dumps is ci unless explicitly _bin/_cs. SQLite
    # TEXT defaults to BINARY - without this pass, live runtime lookups
    # (ObjectMgr::GetPlayerGuidByName "WHERE name = '%s'",
    # MiscHandler add-ignore; the character-creation duplicate-name
    # check) silently change semantics on the SQLite lane (I-60).
    # Recorded residual divergences: NOCASE folds ASCII only (not full
    # utf8_general_ci), and MySQL ci space-padding equality is not
    # replicated - registered as a P7 cross-engine parity probe.
    stmt = _apply_nocase(stmt, report)

    # Clean dangling commas before the closing ')' - KEY/FK line drops
    # can leave comma chains (',\n,\n)'); collapse to fixpoint.
    for _ in range(8):
        cleaned = re.sub(r",\s*(\))", r"\1", stmt)
        cleaned = re.sub(r",\s*,", ",", cleaned)
        cleaned = re.sub(r"\(\s*,", "(", cleaned)
        if cleaned == stmt:
            break
        stmt = cleaned
    return stmt


_ROW_SEP_RE = re.compile(r"\)\s*,\s*\(")
_HEURISTIC_VALUES_RE = re.compile(
    r"^(INSERT\s+INTO\s+(`?\w+`?).*?)\s+VALUES?\s*\((.*)\)\s*$",
    re.I | re.S)


def _count_insert_rows(stmts: list[str]) -> int:
    """Splitter-independent row count across INSERT..VALUES statements:
    1 per statement + the number of ')' ws ',' ws '(' separators outside
    literals. Permissive BY DESIGN (catches paren-depth over-tightness):
    the depth-aware splitter must account for every row the heuristic
    sees, or the seed fails loud. This is the I-45 tripwire."""
    total = 0
    for stmt in stmts:
        if not stmt.upper().startswith("INSERT"):
            continue
        m = _HEURISTIC_VALUES_RE.match(stmt)
        if not m:
            continue
        body = m.group(3)
        total += 1 + _sub_outside_literals(
            body, _ROW_SEP_RE, "\x00").count("\x00")
    return total


def translate_file_text(text: str, filename: str,
                        report: TranslationReport,
                        variables: dict[str, str],
                        db_state: DBSchemaState | None = None) -> list[str]:
    """Full pipeline for one manifest entry's text: comment strip, escape
    rewrite, split, per-statement translation. Returns ordered SQLite
    statements (index emissions inline after their tables)."""
    # CRLF checkouts must behave identically to LF (line-anchored patterns
    # throughout); mysqldump escapes real CRs inside literals, so raw CR
    # in the file is line-ending noise.
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = _strip_comments_and_count(text, report, filename)
    text = rewrite_escapes(text, report, filename)
    src_stmts = list(_split_statements(text))
    src_rows = _count_insert_rows(src_stmts)
    out: list[str] = []
    for raw in src_stmts:
        pending: list[str] = []
        try:
            stmt = translate_statement(raw, report, variables, pending,
                                       db_state=db_state)
        except RuntimeError as exc:
            raise RuntimeError(f"{filename}: {exc}") from None
        if stmt:
            _assert_no_unsubstituted_vars(stmt, filename)
            out.extend(_chunk_insert(stmt, report))
        out.extend(pending)
    out_rows = _count_insert_rows(out)
    if out_rows != src_rows:
        raise RuntimeError(
            f"{filename}: row-parity violation - source INSERTs carry "
            f"{src_rows} value rows, translation emitted {out_rows} "
            f"(splitter/chunker row loss or duplication; investigate "
            f"before seeding)")
    return out
