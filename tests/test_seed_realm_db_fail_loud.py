"""Fail-loud semantics for the realm seeder.

A silently dropped SQL statement was silent data loss in the seeded realm.
Default behavior must abort (rollback + raise); --tolerate-sql-errors is the
explicit opt-in and must still count and report the dropped statements.
"""
from __future__ import annotations

import re
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


# ---------------------------------------------------------------------------
# Wrong-flavor --world-dump refusal (slice round 1, R5)
# ---------------------------------------------------------------------------

def test_world_dump_identity_error_names_the_flavors_own_repo(
        monkeypatch, tmp_path) -> None:
    """The floors are MINIMUMS — a higher-volume wrong-flavor Full_DB passes
    every tbc floor table (wotlk: 29919 creature_template > tbc floor 18799;
    9086 quest_template > 6599; 44613 item_template > 30396; 262
    areatrigger_teleport > 186) — and the version-table leg is an honest
    WARN, so the tool itself must refuse an override outside the flavor's
    own db repo. Measured floor values: schemas/sqlite-seed-baseline{,-tbc,
    -wotlk}.json."""
    from tools import flavor_layout
    try:
        flavor_layout.require_flavor_pins("tbc",
                                          groups=("server_core", "db_content"))
    except flavor_layout.FlavorPinError:
        pytest.skip("tbc pins not registered/checked out")
    plan = seed_realm_db._flavor_seed_plan("tbc")
    # The plan's own pinned archive: no error.
    assert seed_realm_db._world_dump_identity_error(plan, plan.world_dump) is None
    # An existing foreign dump (the vanilla corpus, or any path outside the
    # tbc db repo): refused, naming the flavor's own repo and the world DB.
    foreign = tmp_path / "ClassicDB_1_12_1_z2815.sql.gz"
    foreign.write_bytes(b"placeholder")
    error = seed_realm_db._world_dump_identity_error(plan, foreign)
    assert error is not None
    assert "tbcmangos" in error
    assert str(plan.db_submodule) in error


def test_main_foreign_world_dump_refused_before_any_seed(
        monkeypatch, capsys, tmp_path) -> None:
    # Vanilla lane, an EXISTING foreign dump: refused rc=2 before a single
    # database is written — the old behavior seeded the foreign world
    # content with rc=0 (floors not consulted on the vanilla lane at all).
    foreign = tmp_path / "ForeignWorldDump.sql.gz"
    foreign.write_bytes(b"placeholder")
    monkeypatch.setattr(
        sys, "argv",
        ["seed_realm_db.py", "--out", str(tmp_path / "out"),
         "--world-dump", str(foreign)])
    rc = seed_realm_db.main()
    assert rc == 2
    captured = capsys.readouterr()
    assert "outside the vanilla db repo" in captured.err
    assert "native/classic-db" in captured.err
    assert "SEEDED 4 SQLite databases" not in captured.out
    assert not (tmp_path / "out" / "mangos.sqlite").exists()


def test_main_no_world_dump_skips_the_identity_check(
        monkeypatch, tmp_path) -> None:
    # --no-world-dump never reads the dump, so a foreign --world-dump path
    # stays the sanctioned schema-only opt-in (rc=0), not a new refusal.
    src = ROOT / "native" / "cmangos" / "sql" / "base" / "mangos.sql"
    if not src.exists():
        pytest.skip("pinned mangos.sql base dump not present")
    foreign = tmp_path / "ForeignWorldDump.sql.gz"
    foreign.write_bytes(b"placeholder")
    monkeypatch.setattr(
        sys, "argv",
        ["seed_realm_db.py", "--out", str(tmp_path),
         "--world-dump", str(foreign), "--no-world-dump"])
    rc = seed_realm_db.main()
    assert rc == 0
    assert (tmp_path / "mangos.sqlite").is_file()


# ---------------------------------------------------------------------------
# R2-1 (review round 1, serious): a doctored db_content.seed must not
# escape the flavor's db repo.
# ---------------------------------------------------------------------------

def test_manifest_seed_pins_stay_inside_their_db_repo() -> None:
    """Repo static pin (R2-1a): every registered db_content.seed in
    schemas/flavor.json is a relative path with no '..' parts whose join
    with the flavor's db repo root stays INSIDE that repo. Green on the
    honest schema; the rule is enforced in CI so a doctored manifest fails
    here before any tool consumes it."""
    from tools import flavor_layout
    manifest = flavor_layout.load_flavor_manifest()
    checked = 0
    for block in manifest.get("flavors", []):
        seed = (block.get("db_content") or {}).get("seed")
        if not (isinstance(seed, str) and seed):
            continue
        error = flavor_layout.db_content_seed_error(block["id"], seed)
        assert error is None, error
        checked += 1
    assert checked >= 3, (
        f"expected the three flavor seed pins to be registered, saw {checked}")


def test_doctored_seed_pin_refused_at_plan_resolution(monkeypatch) -> None:
    """Unit (R2-1b, manifest seam): a manifest whose tbc db_content.seed
    points at the wotlk archive via '..' must fail plan resolution LOUDLY —
    never resolve to a foreign dump the minimum-floors cannot catch."""
    from tools import flavor_layout
    real_load = flavor_layout.load_flavor_manifest
    real_manifest = real_load()

    def fake_load(path=None):
        import copy
        doctored = copy.deepcopy(real_manifest)
        for block in doctored["flavors"]:
            if block.get("id") == "tbc":
                block["db_content"]["seed"] = (
                    "../classic-db-wotlk/Full_DB/WoTLKDB_1_9_14092.sql.gz")
        return doctored

    monkeypatch.setattr(flavor_layout, "load_flavor_manifest", fake_load)
    if not (ROOT / "native/cmangos-tbc/src/shared/revision_sql.h").is_file():
        pytest.skip("tbc core checkout not present")
    with pytest.raises(RuntimeError) as excinfo:
        seed_realm_db._flavor_seed_plan("tbc")
    message = str(excinfo.value)
    assert "db_content.seed" in message
    assert "native/classic-db-tbc" in message
    # The absolute/drive class refuses the same way.
    monkeypatch.setattr(
        flavor_layout, "load_flavor_manifest",
        lambda path=None: {**real_manifest,
                           "flavors": [{**block,
                                        "db_content": {**block["db_content"],
                                                       "seed": "Z:/elsewhere/WoTLKDB.sql.gz"}}
                                       if block.get("id") == "tbc" else block
                                       for block in real_manifest["flavors"]]})
    with pytest.raises(RuntimeError) as excinfo:
        seed_realm_db._flavor_seed_plan("tbc")
    assert "absolute/drive path" in str(excinfo.value)


def test_stage_seed_resolution_refuses_the_same_escape(monkeypatch) -> None:
    """The stager's seam carries the same rule: _full_db_seed on a doctored
    block refuses the '..' escape before the join."""
    import stage_database_migrations
    from tools import flavor_layout
    real_load = flavor_layout.load_flavor_manifest
    real_manifest = real_load()
    tbc_block = next(b for b in real_manifest["flavors"]
                     if b.get("id") == "tbc")
    doctored = {"db_content": dict(tbc_block["db_content"],
                                   seed="../classic-db/Full_DB/"
                                        "ClassicDB_1_12_1_z2815.sql.gz")}
    with pytest.raises(RuntimeError) as excinfo:
        stage_database_migrations._full_db_seed("tbc", doctored)
    assert "native/classic-db-tbc" in str(excinfo.value)


def test_main_doctored_seed_pin_refused_before_any_seed(
        monkeypatch, tmp_path) -> None:
    """End-to-end (R2-1b): main() with a doctored tbc seed pin exits 2 and
    writes NO database — the refusal happens at plan resolution, before
    build_one ever runs."""
    from tools import flavor_layout
    real_load = flavor_layout.load_flavor_manifest
    real_manifest = real_load()

    def fake_load(path=None):
        import copy
        doctored = copy.deepcopy(real_manifest)
        for block in doctored["flavors"]:
            if block.get("id") == "tbc":
                block["db_content"]["seed"] = (
                    "../classic-db-wotlk/Full_DB/WoTLKDB_1_9_14092.sql.gz")
        return doctored

    monkeypatch.setattr(flavor_layout, "load_flavor_manifest", fake_load)
    # Pin verification is this test's isolation boundary, not its subject
    # (the doctored manifest still carries the real pins, but scanning the
    # submodule tree is R2-2's lane): pass it through.
    monkeypatch.setattr(
        flavor_layout, "require_flavor_pins",
        lambda flavor_id, **_: {"server_core": "isolation",
                                "db_content": "isolation"})
    out = tmp_path / "out"
    monkeypatch.setattr(
        sys, "argv",
        ["seed_realm_db.py", "--out", str(out), "--flavor", "tbc"])
    rc = seed_realm_db.main()
    assert rc == 2
    assert not (out / "tbcmangos.sqlite").exists()
    assert not list(out.glob("*.sqlite")) if out.exists() else True


# ---------------------------------------------------------------------------
# R2-2 (review round 1): the vanilla lane verifies the pins it seeds from.
# ---------------------------------------------------------------------------

def test_vanilla_lane_verifies_its_pins(monkeypatch, tmp_path) -> None:
    """A failing pin check must refuse the VANILLA seed too (rc=2, nothing
    seeded) — the guard used to be `if args.flavor != "vanilla"`, so a
    stale or dirty native/cmangos seeded all four DBs with rc=0 and no
    attestation."""
    from tools import flavor_layout

    def refuse(flavor_id, **_):
        raise flavor_layout.FlavorPinError(
            f"flavor {flavor_id!r} server_core pin mismatch (test)")

    monkeypatch.setattr(flavor_layout, "require_flavor_pins", refuse)
    out = tmp_path / "out"
    monkeypatch.setattr(
        sys, "argv", ["seed_realm_db.py", "--out", str(out)])
    rc = seed_realm_db.main()
    assert rc == 2
    assert not (out / "mangos.sqlite").exists()
    # The removal of the guard stays pinned: no flavor is exempt.
    source = (ROOT / "tools" / "seed_realm_db.py").read_text(encoding="utf-8")
    assert 'if args.flavor != "vanilla"' not in source


# ---------------------------------------------------------------------------
# R1-3 (review round 1): the world dump must carry the flavor's OWN
# db_version revision family — the in-repo copy class the path leg cannot
# see (a tbc Full_DB under native/classic-db still passes the path check).
# ---------------------------------------------------------------------------

def _gz_with(lines: list[str]) -> bytes:
    import gzip as _gzip
    import io
    buffer = io.BytesIO()
    with _gzip.GzipFile(fileobj=buffer, mode="wb") as handle:
        handle.write(("\n".join(lines) + "\n").encode("utf-8"))
    return buffer.getvalue()


FOREIGN_MARKERS = {
    "vanilla": "INSERT INTO `db_version` VALUES ('required_s2491_01_mangos_spillover_rename');",
    "tbc": "INSERT INTO `db_version` VALUES ('required_14092_01_mangos_proc_cooldown');",
    "wotlk": "INSERT INTO `db_version` VALUES ('required_z2815_01_mangos_pursuit');",
}
OWN_MARKER = {
    "vanilla": "INSERT INTO `db_version` VALUES ('required_z2815_01_mangos_pursuit');",
    "tbc": "INSERT INTO `db_version` VALUES ('required_s2491_01_mangos_spillover_rename');",
    "wotlk": "INSERT INTO `db_version` VALUES ('required_14092_01_mangos_proc_cooldown');",
}


@pytest.mark.parametrize("flavor", ("vanilla", "tbc", "wotlk"))
def test_world_dump_content_error_refuses_foreign_family(
        flavor, tmp_path) -> None:
    """Unit: a dump whose db_version marker belongs to ANOTHER branch is
    named and refused; a dump with no recognizable mangos marker at all is
    refused too (fail-closed); the flavor's own marker passes."""
    plan = seed_realm_db._flavor_seed_plan(flavor)
    foreign = tmp_path / "foreign.sql.gz"
    foreign.write_bytes(_gz_with([FOREIGN_MARKERS[flavor]]))
    error = seed_realm_db._world_dump_content_error(plan, foreign)
    assert error is not None
    assert "foreign branch" in error
    assert "required_" in error  # the offending marker is named
    unmarked = tmp_path / "unmarked.sql.gz"
    unmarked.write_bytes(_gz_with(["-- just noise, no db_version rows"]))
    error = seed_realm_db._world_dump_content_error(plan, unmarked)
    assert error is not None and "no " in error and "revision marker" in error
    own = tmp_path / "own.sql.gz"
    own.write_bytes(_gz_with([OWN_MARKER[flavor]]))
    assert seed_realm_db._world_dump_content_error(plan, own) is None


@pytest.mark.parametrize("flavor", ("vanilla", "tbc", "wotlk"))
def test_pinned_dumps_carry_only_their_own_family(flavor) -> None:
    """The sniff's measured invariant (module comment): each pinned
    Full_DB's FULL stream carries its own branch's markers and zero foreign
    ones — the honest archives pass the sniff unchanged (run here against
    the real default archive)."""
    plan = seed_realm_db._flavor_seed_plan(flavor)
    if not plan.world_dump.is_file():
        pytest.skip(f"{flavor} db repo not checked out")
    assert seed_realm_db._world_dump_content_error(plan, plan.world_dump) is None


# Review round 2 (R2-1 serious): the round-1 sniff classified only the
# FIRST any-family hit per 1 MiB window, so a composite dump whose first
# marker is own-family and whose foreign marker sits later in the SAME
# window (>64 bytes from the end, so the overlap tail cannot save it) was
# ACCEPTED — seeding foreign world content under the flavor's DB names
# with rc=0, the exact class D55's "no foreign-family marker may appear
# anywhere in the stream" contract refuses. Every hit is now classified.

@pytest.mark.parametrize("flavor", ("vanilla", "tbc", "wotlk"))
def test_mixed_dump_same_window_own_then_foreign_is_refused(
        flavor, tmp_path) -> None:
    """The composite-dump geometry: own-family marker first, foreign-family
    marker later in the same window (>64 chars from the end so the
    overlap tail alone cannot bridge it). Must return the foreign-branch
    refusal for every flavor."""
    plan = seed_realm_db._flavor_seed_plan(flavor)
    filler = "INSERT INTO `plausible_table` VALUES ('{}');\n"
    mixed = tmp_path / "mixed.sql.gz"
    mixed.write_bytes(_gz_with([
        OWN_MARKER[flavor],
        *(filler.format(f"pad-{i}") for i in range(200)),
        FOREIGN_MARKERS[flavor],
        *(filler.format(f"tail-{i}") for i in range(50)),
    ]))
    error = seed_realm_db._world_dump_content_error(plan, mixed)
    assert error is not None, (
        "a composite dump (own marker first, foreign marker later in the "
        "same window) must be refused — D55's contract covers the whole "
        "stream, not the first hit")
    assert "foreign branch" in error
    # the refusal names the offending marker (the sniff names the dialect
    # prefix any_family matches: required_<rev>_<n>_mangos_)
    foreign_prefix = re.search(
        r"required_[a-z0-9]+_\d+_mangos_", FOREIGN_MARKERS[flavor]).group(0)
    assert foreign_prefix in error


@pytest.mark.parametrize("flavor", ("vanilla", "tbc", "wotlk"))
def test_mixed_dump_foreign_in_later_window_is_refused(
        flavor, tmp_path) -> None:
    """Second doctored geometry: the foreign marker lands in a LATER window
    than the own cluster (the round-1 first-hit rule also passed this)."""
    plan = seed_realm_db._flavor_seed_plan(flavor)
    filler = "INSERT INTO `plausible_table` VALUES ('{}');\n"
    mixed = tmp_path / "mixed-late.sql.gz"
    # >1 MiB of filler pushes the foreign marker into a later window.
    mixed.write_bytes(_gz_with(
        [OWN_MARKER[flavor]]
        + [filler.format(f"pad-{i:07d}" + "x" * 64) for i in range(20000)]
        + [FOREIGN_MARKERS[flavor]]))
    error = seed_realm_db._world_dump_content_error(plan, mixed)
    assert error is not None and "foreign branch" in error, error


def test_inrepo_override_with_gitignored_extension_is_refused(
        monkeypatch, tmp_path) -> None:
    """R2-1 serious, arm 2: the db submodules gitignore scratch extensions
    (*.log *.orig *~ ...), so a doctored dump under one of those names is
    INVISIBLE to the pin dirty-check while sitting inside the flavor's own
    repo. The path leg must refuse any world dump without a git-visible
    .sql/.sql.gz name, so a doctored in-repo dump either carries a foreign
    marker (sniff) or takes a tracked name (dirty-check)."""
    plan = seed_realm_db._flavor_seed_plan("vanilla")
    evil = tmp_path / "evil.log"
    evil.write_bytes(_gz_with([OWN_MARKER["vanilla"],
                               FOREIGN_MARKERS["vanilla"]]))
    error = seed_realm_db._world_dump_identity_error(plan, evil)
    assert error is not None
    assert ".sql/.sql.gz" in error and "gitignore" in error
    # the plain-.sql shape stays a legal world-dump name: a path INSIDE the
    # flavor's db repo with a git-visible .sql name passes the path leg
    # (path arithmetic only — the file is never created, so the submodule
    # stays clean for the other tests)
    plain = ROOT / "native" / "classic-db" / "Full_DB" / ".tmp-r2-policy.sql"
    assert not plain.exists()
    assert seed_realm_db._world_dump_identity_error(plan, plain) is None


def test_main_foreign_family_dump_under_own_repo_refused_before_seed(
        monkeypatch, tmp_path) -> None:
    """End-to-end (R1-3): a foreign-flavor Full_DB copied INSIDE the
    flavor's own db repo passes the PATH leg but must be refused rc=2 by
    the CONTENT leg before any database is written. The pin check is
    stubbed because the fixture dirties the submodule by construction (it
    is deleted in finally); the sniff itself is the unit under test."""
    from tools import flavor_layout
    monkeypatch.setattr(
        flavor_layout, "require_flavor_pins",
        lambda flavor_id, **_: {"server_core": "isolation",
                                "db_content": "isolation"})
    planted = (ROOT / "native" / "classic-db" / "Full_DB"
               / ".tmp-r1-3-foreign-family.sql.gz")
    planted.write_bytes(_gz_with([FOREIGN_MARKERS["vanilla"]]))
    try:
        out = tmp_path / "out"
        monkeypatch.setattr(
            sys, "argv",
            ["seed_realm_db.py", "--out", str(out),
             "--world-dump", str(planted)])
        rc = seed_realm_db.main()
        assert rc == 2
        assert not (out / "mangos.sqlite").exists()
    finally:
        planted.unlink(missing_ok=True)
