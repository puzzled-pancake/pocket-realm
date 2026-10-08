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

# The pinned realmd.sql base dump (native/cmangos/sql/base/realmd.sql:259)
# closes several antispam_replacement values with an escaped backslash right
# before the closing quote — ('/V\\','M') in raw dump bytes. The old blind
# replace("\\'", "''") matched the SECOND backslash of the \\ pair together
# with the closing quote, corrupting the literal into '/V\'' and leaving the
# next value's "M" bare -> 'near "M": syntax error' at cur.execute, which
# killed the whole realmd seed (evidence LV-server-db-44:
# out/live-verification/evidence/LV-server-db-44/realm-test.txt). Exact byte
# shape of that INSERT, raw strings so every backslash is literal.
ANTISPAM_REPLACEMENT_DUMP = (
    "CREATE TABLE `antispam_replacement` (\n"
    "  `from` varchar(45) NOT NULL DEFAULT '',\n"
    "  `to` varchar(45) NOT NULL DEFAULT '',\n"
    "  PRIMARY KEY (`from`)\n"
    ");\n"
    r"insert  into `antispam_replacement`(`from`,`to`) values ('\\\\/\\\\/','W'),"
    r"('/\\/\\','M'),('0','O'),('...hic!',''),('()','O'),('\\/\\/','W'),"
    r"('/\\\\','A'),('VV','W'),('@','O'),('/V\\','M'),('/\\\\/\\\\','M'),"
    r"('㎜','MM'),('!<','K');"
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


def test_translate_mysql_escapes_consumes_escaped_backslash_first() -> None:
    # The scanner must treat \\ as an escaped backslash (a unit) before it
    # considers \' / \", so a value ending in a backslash right before its
    # closing quote is not corrupted into an unbalanced literal.
    esc = seed_realm_db._translate_mysql_escapes
    assert esc(r"('/V\\','M')") == r"('/V\\','M')"          # \\ before closing '
    assert esc(r"('Kel\'Thuzad')") == "('Kel''Thuzad')"     # \' -> ''
    assert esc(r'("a\"b")') == '("a"b")'                    # \" -> "
    assert esc("no escapes here") == "no escapes here"


def test_antispam_insert_with_trailing_escaped_backslash_applies() -> None:
    # The exact antispam_replacement INSERT shape from realmd.sql:259 must
    # apply in default fail-loud mode: pre-fix it died with
    # sqlite3.OperationalError: near "M": syntax error (LV-server-db-44).
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(ANTISPAM_REPLACEMENT_DUMP), "realmd", verbose=False
    )
    assert errors == 0
    assert applied == 2  # CREATE + the whole 13-value INSERT
    rows = dict(conn.execute(
        "SELECT `from`, `to` FROM antispam_replacement"
    ).fetchall())
    assert len(rows) == 13
    # Pairings straddling the previously corrupted region must not shift:
    # SQLite stores backslashes literally, so '/V\\' seeds a 4-char value.
    assert rows["0"] == "O"
    assert rows["...hic!"] == ""
    assert rows["/V\\\\"] == "M"
    assert rows["㎜"] == "MM"
    conn.close()


# LVF-server-db-017: the mangos base dump's entry-0 placeholder row
# (native/cmangos/sql/base/mangos.sql:936) inserts only (`Entry`) while
# `Name` VARCHAR(200) NOT NULL (mangos.sql:926) carries no DEFAULT. MySQL
# non-strict replay backfills Name with '' and lands the row; bare SQLite
# refuses it (NOT NULL constraint failed) and killed the whole mangos seed.
# Byte shape of mangos.sql:914-928 (column subset) and :936.
SPAWN_DATA_TEMPLATE_DUMP = (
    "CREATE TABLE `creature_spawn_data_template` (\n"
    "  `Entry` int unsigned NOT NULL COMMENT 'ID of template',\n"
    "  `NpcFlags` int NOT NULL DEFAULT '-1',\n"
    "  `Name` VARCHAR(200) NOT NULL,\n"
    "  PRIMARY KEY (`Entry`,`NpcFlags`)\n"
    ") ENGINE=MyISAM DEFAULT CHARSET=utf8 ROW_FORMAT=DYNAMIC"
    " COMMENT='Creature System (Spawn Data Template)';\n"
    "INSERT INTO `creature_spawn_data_template` (`Entry`) VALUES (0);\n"
)


def test_entry0_placeholder_insert_backfills_missing_not_null_name() -> None:
    # The row must LAND (never dropped) with exactly MySQL's non-strict
    # values: Entry=0 as inserted, NpcFlags from its declared DEFAULT -1,
    # Name backfilled to ''.
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(SPAWN_DATA_TEMPLATE_DUMP), "mangos", verbose=False
    )
    assert errors == 0
    assert applied == 2
    assert conn.execute(
        "SELECT Entry, NpcFlags, Name FROM creature_spawn_data_template"
    ).fetchall() == [(0, -1, "")]
    conn.close()


# Same defect class, numeric: mangos.sql:2176 lists 13 of 14 columns;
# box_broadcast_text INT(11) NOT NULL (mangos.sql:2165) has no DEFAULT and
# MySQL backfills it with 0. Multi-row: every row gets the backfill.
GOSSIP_MENU_OPTION_DUMP = (
    "CREATE TABLE `gossip_menu_option` (\n"
    "  `menu_id` smallint(6) unsigned NOT NULL DEFAULT '0',\n"
    "  `id` smallint(6) unsigned NOT NULL DEFAULT '0',\n"
    "  `box_text` text,\n"
    "  `box_broadcast_text` INT(11) NOT NULL,\n"
    "  `condition_id` mediumint(8) unsigned NOT NULL DEFAULT '0',\n"
    "  PRIMARY KEY (`menu_id`,`id`)\n"
    ") ENGINE=MyISAM DEFAULT CHARSET=utf8;\n"
    "INSERT INTO `gossip_menu_option`(menu_id, id, box_text, condition_id)"
    " VALUES\n"
    "(0,0,NULL,0),\n"
    "(0,1,'hi',3);\n"
)


def test_gossip_box_broadcast_text_backfilled_zero_every_row() -> None:
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(GOSSIP_MENU_OPTION_DUMP), "mangos", verbose=False
    )
    assert errors == 0
    assert applied == 2
    assert conn.execute(
        "SELECT menu_id, id, box_text, box_broadcast_text, condition_id"
        " FROM gossip_menu_option ORDER BY id"
    ).fetchall() == [
        (0, 0, None, 0, 0),
        (0, 1, "hi", 0, 3),
    ]
    conn.close()


# Non-regeneration guard: omitting a single-column INTEGER PK (the rowid
# alias, pet_name_generation's `id`, mangos.sql:4606/4619) is valid —
# SQLite assigns it like MySQL assigns AUTO_INCREMENT. No backfill must
# fire and the ids must come out 1, 2, ...
PET_NAME_GENERATION_DUMP = (
    "CREATE TABLE `pet_name_generation` (\n"
    "  `id` mediumint(8) unsigned NOT NULL AUTO_INCREMENT,\n"
    "  `word` tinytext NOT NULL,\n"
    "  `entry` mediumint(8) unsigned NOT NULL DEFAULT '0',\n"
    "  `half` tinyint(4) NOT NULL DEFAULT '0',\n"
    "  PRIMARY KEY (`id`)\n"
    ") ENGINE=MyISAM DEFAULT CHARSET=utf8;\n"
    "INSERT INTO `pet_name_generation` (`word`,`entry`,`half`) VALUES\n"
    "('Aba',416,0),\n"
    "('Az',416,0);\n"
)


def test_rowid_alias_omission_applies_without_backfill() -> None:
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(PET_NAME_GENERATION_DUMP), "mangos", verbose=False
    )
    assert errors == 0
    assert applied == 2
    assert conn.execute(
        "SELECT id, word, entry, half FROM pet_name_generation ORDER BY id"
    ).fetchall() == [(1, "Aba", 416, 0), (2, "Az", 416, 0)]
    conn.close()


# mangos.sql:13863-13867: trailing '--' comments sit AFTER the final ';'
# of the INSERT, so the legacy ';\n' split missed the terminator and glued
# the next dump section onto the statement ("You can only execute one
# statement at a time"). Comment stripping is now literal-aware and runs
# before splitting; the next statement must still apply.
SPELL_PROC_ITEM_ENCHANT_DUMP = (
    "CREATE TABLE `spell_proc_item_enchant` (\n"
    "  `entry` mediumint(8) unsigned NOT NULL DEFAULT '0',\n"
    "  `chance` float NOT NULL DEFAULT '0',\n"
    "  PRIMARY KEY (`entry`)\n"
    ") ENGINE=MyISAM DEFAULT CHARSET=utf8;\n"
    "INSERT INTO `spell_proc_item_enchant` VALUES\n"
    "(8034, 9),        -- Frostbrand Weapon\n"
    "(13897, 6.0),     -- Enchant Weapon - Fiery Weapon\n"
    "(20004, 6.0),     -- Enchant Weapon - Lifestealing\n"
    "(20005, 1.6);     -- Enchant Weapon - Icy Chill\n"
    "INSERT INTO `spell_proc_item_enchant` VALUES (9999, 0.5);\n"
)


def test_trailing_comment_after_final_semicolon_splits_statement() -> None:
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(SPELL_PROC_ITEM_ENCHANT_DUMP), "mangos", verbose=False
    )
    assert errors == 0
    assert applied == 3  # CREATE + commented INSERT + the next INSERT
    assert conn.execute(
        "SELECT entry, chance FROM spell_proc_item_enchant ORDER BY entry"
    ).fetchall() == [
        (8034, 9.0),
        (9999, 0.5),
        (13897, 6.0),
        (20004, 6.0),
        (20005, 1.6),
    ]
    conn.close()


# mangos.sql:10317: the only corpus table tail the ')ENGINE=' /
# ')DEFAULT CHARSET=' strips miss — COMMENT= welded straight onto ')'.
REFERENCE_LOOT_NAMES_DUMP = (
    "CREATE TABLE `reference_loot_template_names` (\n"
    "  `entry` int unsigned NOT NULL DEFAULT '0',\n"
    "  `name` varchar(255) NOT NULL DEFAULT '',\n"
    "  PRIMARY KEY (`entry`)\n"
    ")COMMENT='Reference Loot Template Names';\n"
    "INSERT INTO `reference_loot_template_names` VALUES (1, 'x');\n"
)


def test_bare_table_comment_tail_stripped() -> None:
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(REFERENCE_LOOT_NAMES_DUMP), "mangos", verbose=False
    )
    assert errors == 0
    assert applied == 2
    assert conn.execute(
        "SELECT entry, name FROM reference_loot_template_names"
    ).fetchall() == [(1, "x")]
    conn.close()


# mangos.sql:10545/12576: `varchar(200) CHARACTER SET utf8mb3 COLLATE
# utf8mb3_general_ci NOT NULL` — the charset clause made CREATE TABLE die
# with 'near "SET": syntax error'. Stripped outside literals only.
CHARSET_COLUMN_DUMP = (
    "CREATE TABLE `spawn_group`  (\n"
    "  `Id` int(11) NOT NULL COMMENT 'Spawn Group ID',\n"
    "  `Name` varchar(200) CHARACTER SET utf8mb3"
    " COLLATE utf8mb3_general_ci NOT NULL COMMENT 'Description of usage',\n"
    "  PRIMARY KEY(`Id`)\n"
    ") ENGINE=MyISAM DEFAULT CHARSET=utf8;\n"
    "INSERT INTO `spawn_group` (`Id`, `Name`) VALUES (1, 'keep');\n"
    "INSERT INTO `spawn_group` (`Id`, `Name`) VALUES"
    " (2, 'CHARACTER SET utf8mb3 is data -- not a comment');\n"
)


def test_character_set_column_clause_stripped_literals_kept() -> None:
    # The clause must vanish from the column DEFINITION while the exact
    # same phrase and a '--' marker inside a string VALUE survive byte-exact
    # (the legacy-corruption class this fix must never open).
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(CHARSET_COLUMN_DUMP), "mangos", verbose=False
    )
    assert errors == 0
    assert applied == 3
    assert conn.execute(
        "SELECT Id, Name FROM spawn_group ORDER BY Id"
    ).fetchall() == [
        (1, "keep"),
        (2, "CHARACTER SET utf8mb3 is data -- not a comment"),
    ]
    conn.close()


# The tbc/wotlk Full_DB archives each carry EXACTLY ONE `FULLTEXT KEY`
# line (CREATE TABLE `areatrigger_teleport`, verified by grep over both
# archives; the vanilla corpus has zero). Byte shape below is the tbc
# archive's, column subset. SQLite has no fulltext index syntax inside
# CREATE TABLE ('near "FULLTEXT": syntax error'), so the line must drop
# like any other inline index — with the same prior-line comma strip as
# the KEY drop, else `PRIMARY KEY (id),` dangles before the ')'.
AREATRIGGER_TELEPORT_DUMP = (
    "CREATE TABLE `areatrigger_teleport` (\n"
    "  `id` mediumint unsigned NOT NULL DEFAULT '0' COMMENT 'Identifier',\n"
    "  `name` text,\n"
    "  `required_level` tinyint unsigned NOT NULL DEFAULT '0',\n"
    "  PRIMARY KEY (`id`),\n"
    "  FULLTEXT KEY `name` (`name`)\n"
    ") ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 ROW_FORMAT=FIXED"
    " COMMENT='Trigger System';\n"
    "INSERT INTO `areatrigger_teleport` (`id`, `name`, `required_level`)"
    " VALUES (25, 'Orgrimmar', 0);\n"
)


def test_fulltext_key_line_drops_with_prior_comma_strip() -> None:
    # The FULLTEXT index line is a MySQL-ism SQLite cannot parse: it must
    # vanish (comma on the kept PRIMARY KEY line stripped with it) and the
    # table + its rows must land in default fail-loud mode.
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, FakeDump(AREATRIGGER_TELEPORT_DUMP), "mangos", verbose=False
    )
    assert errors == 0
    assert applied == 2
    assert conn.execute(
        "SELECT id, name, required_level FROM areatrigger_teleport"
    ).fetchall() == [(25, "Orgrimmar", 0)]
    conn.close()


def test_fulltext_key_line_leaves_no_residue_in_translation() -> None:
    # Direct translator pin: the emitted CREATE TABLE statement carries no
    # FULLTEXT/KEY residue and no dangling comma before the closing ')'.
    stmts = list(seed_realm_db.translate_statements(AREATRIGGER_TELEPORT_DUMP))
    create = next(s for s in stmts if s.upper().startswith("CREATE TABLE"))
    assert "FULLTEXT" not in create.upper()  # the index line itself is gone
    assert "PRIMARY KEY (`id`)" in create   # the kept PK survives the strip
    assert ", )" not in create and ",\n)" not in create and ",)" not in create


def test_real_mangos_base_dump_applies_clean_in_default_mode() -> None:
    # The gate input itself: native/cmangos/sql/base/mangos.sql must apply
    # end-to-end in default fail-loud mode (zero tolerated statements), and
    # the entry-0 placeholder row must land with MySQL's non-strict values.
    src = ROOT / "native" / "cmangos" / "sql" / "base" / "mangos.sql"
    if not src.exists():
        pytest.skip("pinned mangos.sql base dump not present")
    conn = connection()
    applied, errors = seed_realm_db.apply_dump(
        conn, src, "mangos", verbose=False
    )
    assert errors == 0
    assert applied > 300
    assert conn.execute(
        "SELECT Entry, NpcFlags, Name FROM creature_spawn_data_template"
    ).fetchall() == [(0, -1, "")]
    # The previously swallowed statement's rows must be present too.
    assert conn.execute(
        "SELECT COUNT(*) FROM spell_proc_item_enchant"
    ).fetchone()[0] == 4
    conn.close()


def test_main_missing_world_dump_fails_loud(monkeypatch, capsys, tmp_path) -> None:
    # A missing ClassicDB dump (submodule not checked out, typo'd
    # --world-dump) must not degrade to a schema-only mangos seed: that
    # hollow world DB applies cleanly, passes verify, and used to print
    # "SEEDED 4 SQLite databases" with rc=0 (no fake success, docstring).
    monkeypatch.setattr(
        sys, "argv",
        ["seed_realm_db.py", "--out", str(tmp_path),
         "--world-dump", str(tmp_path / "nope" / "ClassicDB.sql.gz")])
    rc = seed_realm_db.main()
    assert rc == 2  # loud refusal, same shape as the non-vanilla flavor one
    captured = capsys.readouterr()
    assert "content dump not found" in captured.err
    assert "--no-world-dump" in captured.err  # the remedy is named
    assert "SEEDED 4 SQLite databases" not in captured.out  # no fake success
    assert not (tmp_path / "mangos.sqlite").exists()  # nothing was seeded


def test_main_no_world_dump_opt_in_seeds_schema_only(monkeypatch, tmp_path) -> None:
    # The explicit --no-world-dump opt-in is the only sanctioned schema-only
    # seed: it must succeed against a missing dump and land a real (schema +
    # base mangos.sql) mangos.sqlite.
    src = ROOT / "native" / "cmangos" / "sql" / "base" / "mangos.sql"
    if not src.exists():
        pytest.skip("pinned mangos.sql base dump not present")
    monkeypatch.setattr(
        sys, "argv",
        ["seed_realm_db.py", "--out", str(tmp_path),
         "--world-dump", str(tmp_path / "nope" / "ClassicDB.sql.gz"),
         "--no-world-dump"])
    rc = seed_realm_db.main()
    assert rc == 0
    assert (tmp_path / "mangos.sqlite").is_file()
    assert (tmp_path / "realmd.sqlite").is_file()
