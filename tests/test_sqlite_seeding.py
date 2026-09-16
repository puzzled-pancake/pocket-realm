"""Seeding fidelity harness.

Proves the manifest-driven rebuilt seeder end-to-end against the pinned
414-entry migration manifest:

  - SEED OK with ZERO statement errors in every database and a sanitized
    summary that byte-matches the append-only baseline
    schemas/sqlite-seed-baseline.json (determinism);
  - the addendum legs, live against the seeded artifacts:
    (a) bot_player_relationship PRIMARY KEY (bot, player) and
        bot_backstory PRIMARY KEY (bot) survive translation,
    (b) UNIQUE KEY -> CREATE UNIQUE INDEX is distinguished from
        KEY -> CREATE INDEX (concrete probe: account_idx_username vs
        account_idx_gmlevel),
    (c) the anticheat system_fingerprint_usage_fingerprint index exists
        (the rowid-prune subquery's per-login cost),
    (d) AUTO_INCREMENT columns translate to EXACTLY
        `INTEGER PRIMARY KEY AUTOINCREMENT` (the rowid-alias rule), with
        the registered negative control: a naive `int(10) unsigned
        PRIMARY KEY` rendering FAILS the id-omitting INSERT;
  - escape fidelity: every MySQL escape class round-trips
    byte-identically through the translator + engine;
  - the engine-parity leg: the per-database transcripts
    execute cleanly under the PINNED amalgamation via
    tools/sqlite_exec_file.c - the exact engine build the APK ships,
    not Python's stdlib sqlite3 - with the engine's own literal-aware
    statement splitter as a cross-check of the translator's;
  - full-corpus translation determinism: a second translation pass
    over the manifest is byte-identical to the first.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import seed_sqlite_from_manifest as seeder  # noqa: E402


@pytest.fixture(autouse=True)
def _isolated_seed_augments(tmp_path, monkeypatch):
    """Synthetic-manifest tests must not inherit the repo's real seed
    augmentations (they target real database names). Tests that compare
    against the committed baseline opt back in via
    _real_seed_augments below."""
    monkeypatch.setattr(seeder, "AUGMENT_DIR", tmp_path / "empty-augment")


@pytest.fixture
def _real_seed_augments(monkeypatch):
    """Opt back into the repo's committed augmentation set (baseline-bound
    digest tests only)."""
    monkeypatch.setattr(seeder, "AUGMENT_DIR",
                        seeder.ROOT / "schemas" / "seed-augment")
import sqlite_seed_translator as translator  # noqa: E402

BASELINE = ROOT / "schemas" / "sqlite-seed-baseline.json"
AMALGAMATION = (ROOT / "native" / ".deps" / "src" / "sqlite" /
                "sqlite-amalgamation-3460100")
EXECUTOR_SRC = ROOT / "tools" / "sqlite_exec_file.c"

DB_NAMES = ("classicrealmd", "classiccharacters", "classiclogs",
            "classicmangos")

# The full production define set from native/.deps/src/sqlite/
# CMakeLists.txt target_compile_definitions - the host fixture legs
# must compile the SAME engine configuration the APK ships.
PRODUCTION_DEFINES = [
    "-DSQLITE_ENABLE_FTS5",
    "-DSQLITE_ENABLE_RTREE",
    "-DSQLITE_ENABLE_COLUMN_METADATA",
    "-DSQLITE_ENABLE_JSON1",
    "-DSQLITE_DEFAULT_MEMSTATUS=0",
    "-DSQLITE_LIKE_DOESNT_MATCH_BLOBS",
    "-DSQLITE_OMIT_DEPRECATED",
    "-DSQLITE_USE_URI=1",
    "-DSQLITE_ENABLE_DBSTAT_VTAB",
    "-DSQLITE_DEFAULT_WAL_SYNCHRONOUS=2",
    "-DSQLITE_THREADSAFE=2",
]


@pytest.fixture(scope="module")
def seed_run(tmp_path_factory):
    """Run the real seeder once; everything else asserts its artifacts.
    The autouse AUGMENT_DIR isolation is function-scoped and this
    fixture is module-scoped (instantiated first), so this MUST run
    against the real repo augmentation set - the baseline pins the
    augmented digests - assert that ordering explicitly rather than
    resting on pytest fixture instantiation order."""
    assert seeder.AUGMENT_DIR == seeder.ROOT / "schemas" / "seed-augment"
    tmp = tmp_path_factory.mktemp("seed")
    out = tmp / "db"
    transcripts = tmp / "transcripts"
    rc, summary = seeder.seed(out, transcripts, False, 0)
    return {
        "rc": rc,
        "summary": summary,
        "out": out,
        "transcripts": transcripts,
    }


def test_seed_reports_ok_with_zero_errors(seed_run) -> None:
    assert seed_run["rc"] == 0
    summary = seed_run["summary"]
    for db in DB_NAMES:
        result = summary["databases"][db]
        assert result["errors"] == 0, (db, result["error_samples"])
        assert result["applied"] == result["statements"] > 0, db
    assert summary["variables_unfolded"] == []
    assert summary["translation"]["statements"] > 27000


def test_sanitized_summary_matches_the_append_only_baseline(seed_run) -> None:
    """The baseline pins statement/row/rewrite counts (timings stripped).
    A drift here means the corpus or translator changed - update the
    baseline deliberately."""
    assert BASELINE.is_file(), "run the seeder with --write-baseline first"
    stored = json.loads(BASELINE.read_text(encoding="utf-8"))
    assert seeder.sanitize_summary(seed_run["summary"]) == stored


def _table_pk(path: Path, table: str) -> list[tuple[str, int]]:
    conn = sqlite3.connect(str(path))
    try:
        rows = conn.execute(f'PRAGMA table_info("{table}")').fetchall()
    finally:
        conn.close()
    return [(name, pk) for _cid, name, _type, _nn, _dflt, pk in rows
            if pk > 0]


def test_addendum_a_composite_pks_survive_translation(seed_run) -> None:
    """The ON CONFLICT(bot, player) upsert requires the composite
    PK; bot_backstory keeps its single-column PK."""
    chars = seed_run["out"] / "classiccharacters.sqlite"
    rel = _table_pk(chars, "bot_player_relationship")
    assert rel == [("bot", 1), ("player", 2)], rel
    story = _table_pk(chars, "bot_backstory")
    assert story == [("bot", 1)], story


def test_addendum_b_unique_vs_plain_index_distinction(seed_run) -> None:
    """(b) UNIQUE KEY -> CREATE UNIQUE INDEX, KEY -> CREATE INDEX. A
    non-unique rendering of a UNIQUE KEY silently drops integrity."""
    report = seed_run["summary"]["translation"]
    assert report["unique_indexes_emitted"] > 0
    assert report["indexes_emitted"] > 0
    realmd = seed_run["out"] / "classicrealmd.sqlite"
    conn = sqlite3.connect(str(realmd))
    try:
        idx = {name: unique for name, unique in conn.execute(
            "SELECT name, `unique` FROM pragma_index_list('account')")}
    finally:
        conn.close()
    assert idx.get("account_idx_username") == 1, idx
    assert idx.get("account_idx_gmlevel") == 0, idx


def test_addendum_c_fingerprint_index_exists(seed_run) -> None:
    """(c) the anticheat rowid-prune subquery needs its fingerprint
    index (per-login cost on a growing table)."""
    realmd = seed_run["out"] / "classicrealmd.sqlite"
    conn = sqlite3.connect(str(realmd))
    try:
        names = [row[0] for row in conn.execute(
            "SELECT name FROM pragma_index_list("
            "'system_fingerprint_usage')")]
    finally:
        conn.close()
    assert "system_fingerprint_usage_fingerprint" in names, names


def test_addendum_d_rowid_alias_typing_and_negative_control(
        seed_run) -> None:
    """(d) AUTO_INCREMENT -> EXACTLY INTEGER PRIMARY KEY AUTOINCREMENT
    (a faithful `int(10) unsigned PRIMARY KEY` rendering loses the
    rowid alias and auto-assign - proven by the negative control)."""
    realmd = seed_run["out"] / "classicrealmd.sqlite"
    conn = sqlite3.connect(str(realmd))
    try:
        sql = conn.execute(
            "SELECT sql FROM sqlite_master WHERE "
            "name='system_fingerprint_usage'").fetchone()[0]
    finally:
        conn.close()
    assert "`id` INTEGER PRIMARY KEY AUTOINCREMENT" in sql

    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / "neg.sqlite"
        conn = sqlite3.connect(str(db))
        try:
            conn.execute("PRAGMA journal_mode=DELETE")
            # The naive (non-)translation a "faithful" renderer emits:
            # a valid SQLite type with INTEGER affinity that is NOT
            # exactly `INTEGER`, hence not a rowid alias. The failure is
            # SILENT - SQLite permits NULL in a non-alias PRIMARY KEY
            # column, so the id-omitting INSERT "succeeds" with id NULL
            # (data corruption, not a loud error).
            conn.execute("CREATE TABLE t (`id` int unsigned "
                         "PRIMARY KEY, `v` int)")
            conn.execute("INSERT INTO t (`v`) VALUES (1)")
            got = conn.execute("SELECT `id` FROM t").fetchone()[0]
        finally:
            conn.close()
        assert got is None, (
            f"negative control stale: naive rendering auto-assigned "
            f"id={got!r}")


def test_addendum_e_runtime_statements_against_translated_schema(
        seed_run, tmp_path) -> None:
    """Addendum (e) - BINDING: execute the rewritten runtime statement
    shapes against the TRANSLATED schema (the seeded artifacts), not
    only the fixture's hand-declared tables. A name/type/CHECK drift on
    any LLM column fails HERE at prepare/execute - exactly the class
    that schema-presence probes cannot catch. Runs on COPIES: the
    module fixture's artifacts stay pristine for the other tests."""
    ODKU = ("INSERT INTO `bot_player_relationship` (`bot`, `player`, "
            "`tier`, `points`, `last_interaction_at`) "
            "VALUES ('%d', '%d', 'stranger', %d, NULL) "
            "ON CONFLICT(`bot`, `player`) DO UPDATE SET "
            "`points` = `points` + excluded.`points`, "
            "`tier` = CASE WHEN `points` + excluded.`points` >= 60 "
            "THEN 'trusted' "
            "WHEN `points` + excluded.`points` >= 30 THEN 'ally' "
            "WHEN `points` + excluded.`points` >= 10 THEN "
            "'acquaintance' ELSE 'stranger' END, "
            "`last_interaction_at` = CURRENT_TIMESTAMP")
    chars = tmp_path / "chars.sqlite"
    shutil.copy(seed_run["out"] / "classiccharacters.sqlite", chars)
    conn = sqlite3.connect(str(chars))
    try:
        conn.execute("PRAGMA foreign_keys=OFF")
        # fresh row
        conn.execute(ODKU % (4242, 7, 0))
        # 0 -> 10 crosses stranger -> acquaintance (>= 10)
        conn.execute(ODKU % (4242, 7, 10))
        tier, points = conn.execute(
            "SELECT `tier`, `points` FROM bot_player_relationship "
            "WHERE `bot`=4242 AND `player`=7").fetchone()
        assert (tier, points) == ("acquaintance", 10)
        # 10 -> 30 crosses to ally (>= 30)
        conn.execute(ODKU % (4242, 7, 20))
        tier, points = conn.execute(
            "SELECT `tier`, `points` FROM bot_player_relationship "
            "WHERE `bot`=4242 AND `player`=7").fetchone()
        assert (tier, points) == ("ally", 30)
        # 30 -> 60 crosses to trusted (>= 60)
        conn.execute(ODKU % (4242, 7, 30))
        tier, points = conn.execute(
            "SELECT `tier`, `points` FROM bot_player_relationship "
            "WHERE `bot`=4242 AND `player`=7").fetchone()
        assert (tier, points) == ("trusted", 60)
        # INSERT OR IGNORE backstory shape
        conn.execute("INSERT OR IGNORE INTO `bot_backstory` (`bot`, "
                     "`text`) VALUES (5150, 'once')")
        conn.execute("INSERT OR IGNORE INTO `bot_backstory` (`bot`, "
                     "`text`) VALUES (5150, 'twice')")
        texts = [r[0] for r in conn.execute(
            "SELECT `text` FROM bot_backstory WHERE `bot`=5150")]
        assert texts == ["once"]
        # id-omitting INSERT + ORDER BY id DESC read (the runtime shape)
        conn.execute("INSERT INTO `bot_player_facts` (`bot`, `player`, "
                     "`category`, `fact_text`) VALUES "
                     "(5150, 7, 'opinion', 'dislikes rain')")
        row = conn.execute(
            "SELECT `id`, `fact_text` FROM `bot_player_facts` "
            "WHERE `bot`=5150 ORDER BY `id` DESC LIMIT 1").fetchone()
        assert row[1] == "dislikes rain" and isinstance(row[0], int) \
            and row[0] > 0
        # REPLACE INTO rides the same translated schema
        conn.execute("REPLACE INTO `bot_player_facts` (`id`, `bot`, "
                     "`player`, `category`, `fact_text`) VALUES "
                     "(?, 5150, 7, 'opinion', 'dislikes snow')",
                     (row[0],))
        val = conn.execute(
            "SELECT `fact_text` FROM `bot_player_facts` WHERE `id`=?",
            (row[0],)).fetchone()[0]
        assert val == "dislikes snow"
    finally:
        conn.close()

    # anticheat rowid-prune DELETE with exactly two binds (realmd)
    realmd = tmp_path / "realmd.sqlite"
    shutil.copy(seed_run["out"] / "classicrealmd.sqlite", realmd)
    conn = sqlite3.connect(str(realmd))
    try:
        conn.execute("DELETE FROM system_fingerprint_usage "
                     "WHERE rowid IN (SELECT rowid FROM "
                     "system_fingerprint_usage WHERE fingerprint = ? "
                     "ORDER BY `time` ASC LIMIT ?)", (12345, 10))
    finally:
        conn.close()

    # world_gossip datetime prune filter (mangos)
    mangos = tmp_path / "mangos.sqlite"
    shutil.copy(seed_run["out"] / "classicmangos.sqlite", mangos)
    conn = sqlite3.connect(str(mangos))
    try:
        conn.execute("DELETE FROM world_gossip WHERE "
                     "datetime(`expires_at`) <= datetime('now')")
    finally:
        conn.close()


def test_revision_probe_z2830_and_spell_coefficients(seed_run) -> None:
    """The world seed must reach the required z2830
    revision - the z2815 snapshot alone lacks the spell_template
    coefficient columns (they arrive via the manifest's Spell.sql), and
    the db_version CHANGE-chain must end at the z2830 column name."""
    mangos = seed_run["out"] / "classicmangos.sqlite"
    conn = sqlite3.connect(str(mangos))
    try:
        cols = {r[1] for r in conn.execute(
            "PRAGMA table_info(spell_template)")}
        for col in ("EffectBonusCoefficient1", "EffectBonusCoefficient2",
                    "EffectBonusCoefficient3",
                    "EffectBonusCoefficientFromAP1",
                    "EffectBonusCoefficientFromAP2",
                    "EffectBonusCoefficientFromAP3"):
            assert col in cols, f"missing spell_template column {col}"
        dbv = {r[1] for r in conn.execute(
            "PRAGMA table_info(db_version)")}
        assert "required_z2830_01_mangos_icon_name" in dbv, sorted(dbv)
    finally:
        conn.close()


def test_addendum_c_d_named_extensions(seed_run) -> None:
    """An extension of (c): world_gossip's KEY expires_at index must exist
    (the datetime prune's cost guard). (d) the addendum's NAMED tables -
    bot_player_facts.id and world_gossip.id - carry the exact rowid-
    alias typing, not just the same-shape proxy in realmd."""
    mangos = seed_run["out"] / "classicmangos.sqlite"
    conn = sqlite3.connect(str(mangos))
    try:
        names = [r[0] for r in conn.execute(
            "SELECT name FROM pragma_index_list('world_gossip')")]
        assert "world_gossip_expires_at" in names, names
        sql = conn.execute("SELECT sql FROM sqlite_master WHERE "
                           "name='world_gossip'").fetchone()[0]
        assert "`id` INTEGER PRIMARY KEY AUTOINCREMENT" in sql
    finally:
        conn.close()
    chars = seed_run["out"] / "classiccharacters.sqlite"
    conn = sqlite3.connect(str(chars))
    try:
        sql = conn.execute("SELECT sql FROM sqlite_master WHERE "
                           "name='bot_player_facts'").fetchone()[0]
        assert "`id` INTEGER PRIMARY KEY AUTOINCREMENT" in sql
    finally:
        conn.close()


def test_created_index_parity(seed_run) -> None:
    """Every CREATE INDEX the seed executed (translator-emitted AND
    verbatim corpus statements - both land in the transcripts) must
    exist in the seeded database with an IDENTICAL definition (table,
    column list, uniqueness). CREATE INDEX IF NOT EXISTS may silently
    skip only when an exact duplicate already exists; a name collision
    with a DIFFERENT definition is silent integrity loss and fails
    here. Nothing may exist beyond what was executed."""
    index_stmt = re.compile(
        r"CREATE\s+(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?"
        r"`?(\w+)`?\s+ON\s+`?(\w+)`?\s*\(([^)]*)\)", re.I)
    for db in DB_NAMES:
        transcript = (seed_run["transcripts"] / f"{db}.sql").read_text(
            encoding="utf-8")
        executed: dict[str, tuple] = {}
        for m in index_stmt.finditer(transcript):
            unique, name, table, cols = m.groups()
            executed[name] = (table, re.sub(r"[`\s]", "", cols),
                              bool(unique))
        assert executed, db
        conn = sqlite3.connect(str(seed_run["out"] / f"{db}.sqlite"))
        try:
            created = {n: s for n, s in conn.execute(
                "SELECT name, sql FROM sqlite_master "
                "WHERE type='index' AND sql IS NOT NULL")}
            live_tables = {n.lower() for (n,) in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'")}
            for name, (table, cols, unique) in executed.items():
                if table.lower() not in live_tables:
                    # temp-table pattern (corpus migrations create,
                    # index, use, then DROP TABLE): both the table and
                    # its indexes must actually be GONE, not lingering
                    assert name not in created, (
                        f"{db}: index {name} on dropped table {table} "
                        "still exists")
                    continue
                # the table lives: the index must exist with an
                # identical definition (the last executed CREATE for
                # this name is what the final schema holds)
                assert name in created, f"{db}: executed index {name} missing"
                sql = created[name]
                cm = re.search(r"INDEX\s+`?\w+`?\s+ON\s+`?(\w+)`?\s*"
                               r"\(([^)]*)\)", sql, re.I)
                got_table, got_cols = cm.group(1), cm.group(2)
                got_unique = bool(re.match(r"\s*CREATE\s+UNIQUE", sql, re.I))
                assert (got_table, re.sub(r"[`\s]", "", got_cols),
                        got_unique) == (table, cols, unique), (
                    f"{db}: index {name} definition drift: executed "
                    f"{table}({cols}) unique={unique} vs created "
                    f"{got_table}({got_cols}) unique={got_unique}")
            for name in created:
                assert name in executed, (
                    f"{db}: index {name} exists but nothing executed it")
        finally:
            conn.close()


def test_literal_canary_value_paren_survives(seed_run) -> None:
    """Regression pin: the z2815 command help text carries
    '#value (0..100)' inside a string literal - the statement-wide
    VALUE->VALUES rewrite corrupted it to '#VALUES (0..100)' before
    the literal-aware substitution. The seeded data must carry the
    ORIGINAL bytes."""
    mangos = seed_run["out"] / "classicmangos.sqlite"
    conn = sqlite3.connect(str(mangos))
    try:
        rows = conn.execute(
            "SELECT help FROM command WHERE help LIKE '%#value (%'").fetchall()
        assert rows, "literal canary '#value (' missing from command"
        assert not conn.execute(
            "SELECT 1 FROM command WHERE help LIKE '%#VALUES (%'"
        ).fetchall(), "corrupted '#VALUES (' present"
        assert any("#value (0..100)" in r[0] for r in rows)
    finally:
        conn.close()


def test_f52_escape_anchor_three_files_exact(seed_run) -> None:
    """External anchor: the legacy-corruption
    escape classes (\\n \\r \\\\) land in EXACTLY the 3 predicted files
    with an exact per-file count - an expectation derived from a
    one-time corpus research pass, not from the translator's own
    totals. The measured corpus truth is 49,242 backslash escapes."""
    f52 = seed_run["summary"]["translation"]["f52_nr_backslash"]
    expected = {
        "native/playerbots/sql/world/ai_playerbot_texts.sql": 43552,
        "native/classic-db/Full_DB/ClassicDB_1_12_1_z2815.sql.gz": 5672,
        "native/cmangos/sql/base/realmd.sql": 18,
    }
    assert f52 == expected, f52
    assert sum(f52.values()) == 49242


def test_escape_fidelity_all_classes_round_trip() -> None:
    """Every MySQL escape class the translator rewrites
    (plus '' doubling, backslash-quote, and multi-byte UTF-8) must land
    byte-identically in the seeded engine. NUL is asserted at the
    translation level only: Python's sqlite3 wrapper refuses NUL inside
    SQL text, and the C replay path (sqlite3_exec, C strings) cannot
    carry it either - the corpus contains no \\0 escapes (proven by the
    clean pinned-engine replay below)."""
    expected = "a\nb\rc\td\be\fx\x1ag\\h'i\"j campeã; — k"
    mysql_literal = ("'a\\nb\\rc\\td\\be\\fx\\Zg\\\\h\\'i\\\"j "
                     "campeã; — k'")
    report = translator.TranslationReport()
    stmts = translator.translate_file_text(
        "CREATE TABLE esc (`v` TEXT);\n"
        f"INSERT INTO esc VALUES ({mysql_literal});\n",
        "escape-fidelity.sql", report, {})
    # Translation-level NUL leg: the \\0 rewrite emits a literal NUL byte.
    nul_stmts = translator.translate_file_text(
        "INSERT INTO esc VALUES ('x\\0y');\n",
        "escape-fidelity.sql", report, {})
    assert "'x\x00y'" in " ".join(nul_stmts)
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / "esc.sqlite"
        conn = sqlite3.connect(str(db))
        try:
            for stmt in stmts:
                conn.execute(stmt)
            got = conn.execute("SELECT `v` FROM esc").fetchone()[0]
        finally:
            conn.close()
    assert got == expected, (got, expected)


def test_positional_add_column_rebuilds_in_mysql_order() -> None:
    """AFTER/FIRST positioning is MySQL semantics SQLite ALTER
    cannot express; stripping it shifted every later column, and the
    SQLStorage loads read these tables with SELECT * positionally - the
    SQLite world lane read data1 as moTransport.taxiPathId and crashed
    world boot in TransportMgr::GenerateWaypoints. The rebuild must
    place the column exactly where MySQL would, keep every row
    (backfilled with the declared DEFAULT), and re-create the table's
    tracked indexes (they die with the renamed-away table)."""
    report = translator.TranslationReport()
    state = translator.DBSchemaState()
    stmts = translator.translate_file_text(
        "CREATE TABLE `t` (\n"
        "  `entry` INTEGER NOT NULL DEFAULT '0',\n"
        "  `name` varchar(100) NOT NULL DEFAULT '',\n"
        "  `faction` smallint NOT NULL DEFAULT '0',\n"
        "  `data0` int NOT NULL DEFAULT '0',\n"
        "  KEY `idx_name` (`name`),\n"
        "  PRIMARY KEY (`entry`)\n"
        ") ENGINE=MyISAM DEFAULT CHARSET=utf8;\n"
        "INSERT INTO `t` VALUES (1,'Door',35,241),(2,'Chest',35,0);\n"
        "ALTER TABLE `t` ADD COLUMN `IconName` varchar(100) NOT NULL "
        "DEFAULT '' AFTER `name`;\n"
        "UPDATE `t` SET `IconName`='goo' WHERE `entry`=1;\n",
        "positional.sql", report, {}, db_state=state)
    conn = sqlite3.connect(":memory:")
    try:
        for stmt in stmts:
            conn.execute(stmt)
        cols = [r[1] for r in conn.execute("PRAGMA table_info(t)")]
        assert cols == ["entry", "name", "IconName", "faction", "data0"], cols
        rows = conn.execute(
            "SELECT `entry`,`name`,`IconName`,`faction`,`data0` FROM `t` "
            "ORDER BY `entry`").fetchall()
        assert rows == [(1, "Door", "goo", 35, 241),
                        (2, "Chest", "", 35, 0)], rows
        idx = [r[1] for r in conn.execute("PRAGMA index_list(t)")]
        assert any("idx_name" in name for name in idx), idx
        tables = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")]
        assert "t__pos_rebuild" not in tables, tables
    finally:
        conn.close()
    assert report.positional_add_rebuilds == 1
    assert report.positional_add_columns == 1


def test_positional_add_chain_first_and_renamed_target() -> None:
    """The corpus chains positional adds (multiplier chain) and targets
    a column a CHANGE just renamed (ModelId1 -> DisplayId1, then
    DisplayIdProbability1 AFTER DisplayId1); FIRST must insert at the
    head. The tracker follows in replay order, so later rebuilds see
    every earlier rename and insert."""
    report = translator.TranslationReport()
    state = translator.DBSchemaState()
    stmts = translator.translate_file_text(
        "CREATE TABLE `ct` (\n"
        "  `Entry` int NOT NULL DEFAULT '0',\n"
        "  `ModelId1` int NOT NULL DEFAULT '0',\n"
        "  `ExperienceMultiplier` float NOT NULL DEFAULT 1,\n"
        "  PRIMARY KEY (`Entry`)\n"
        ") ENGINE=MyISAM DEFAULT CHARSET=utf8;\n"
        "INSERT INTO `ct` VALUES (30, 382, 1.0);\n"
        "ALTER TABLE `ct` CHANGE `ModelId1` `DisplayId1` int NOT NULL "
        "DEFAULT 0;\n"
        "ALTER TABLE `ct` ADD COLUMN `DisplayIdProbability1` SMALLINT "
        "UNSIGNED NOT NULL DEFAULT 0 AFTER `DisplayId1`;\n"
        "ALTER TABLE `ct` ADD `StrengthMultiplier` FLOAT NOT NULL "
        "DEFAULT 1 AFTER `ExperienceMultiplier`;\n"
        "ALTER TABLE `ct` ADD COLUMN `Pinned` int NOT NULL DEFAULT '7' "
        "FIRST;\n",
        "chain.sql", report, {}, db_state=state)
    conn = sqlite3.connect(":memory:")
    try:
        for stmt in stmts:
            conn.execute(stmt)
        cols = [r[1] for r in conn.execute("PRAGMA table_info(ct)")]
        assert cols == ["Pinned", "Entry", "DisplayId1",
                        "DisplayIdProbability1", "ExperienceMultiplier",
                        "StrengthMultiplier"], cols
        row = conn.execute(
            "SELECT `Pinned`,`Entry`,`DisplayId1`,"
            "`DisplayIdProbability1`,`ExperienceMultiplier`,"
            "`StrengthMultiplier` FROM `ct`").fetchone()
        assert row == (7, 30, 382, 0, 1.0, 1.0), row
    finally:
        conn.close()
    assert report.positional_add_rebuilds == 3


def test_positional_add_fail_loud_without_fidelity() -> None:
    """A positional add the tracker cannot place must abort the seed,
    never silently strip the position - that silence is the column-shift
    regression."""
    report = translator.TranslationReport()
    with pytest.raises(RuntimeError, match="DBSchemaState"):
        translator.translate_file_text(
            "CREATE TABLE `t` (`a` int NOT NULL);\n"
            "ALTER TABLE `t` ADD COLUMN `b` int NOT NULL DEFAULT 0 "
            "AFTER `a`;\n",
            "untracked.sql", report, {})
    state = translator.DBSchemaState()
    with pytest.raises(RuntimeError, match="untracked table"):
        translator.translate_file_text(
            "ALTER TABLE `nope` ADD COLUMN `b` int NOT NULL DEFAULT 0 "
            "AFTER `a`;\n",
            "untracked.sql", report, {}, db_state=state)
    with pytest.raises(RuntimeError, match="names no column"):
        translator.translate_file_text(
            "CREATE TABLE `t` (`a` int NOT NULL);\n"
            "ALTER TABLE `t` ADD COLUMN `b` int NOT NULL DEFAULT 0 "
            "AFTER `absent`;\n",
            "untracked.sql", report, {}, db_state=state)
    with pytest.raises(RuntimeError, match="opaque"):
        translator.translate_file_text(
            "CREATE TABLE `t2` (SELECT `a`, `a` + 1 AS `b` FROM `t`);\n"
            "ALTER TABLE `t2` ADD COLUMN `c` int NOT NULL DEFAULT 0 "
            "FIRST;\n",
            "opaque.sql", report, {}, db_state=state)


def test_plain_add_column_appends_without_rebuild() -> None:
    """Without positioning, SQLite's ALTER append IS MySQL's semantics:
    one statement, no rebuild, tracker appends."""
    report = translator.TranslationReport()
    state = translator.DBSchemaState()
    stmts = translator.translate_file_text(
        "CREATE TABLE `t` (`a` int NOT NULL);\n"
        "ALTER TABLE `t` ADD `b` int(11) NOT NULL DEFAULT '0';\n",
        "plain.sql", report, {}, db_state=state)
    alters = [s for s in stmts if s.upper().startswith("ALTER TABLE")]
    assert len(alters) == 1, stmts
    assert report.positional_add_rebuilds == 0
    assert state.tables["t"].column_names() == ["a", "b"]


def test_i189_seeded_crash_table_order_is_pinned(seed_run) -> None:
    """Direct order pin on the REAL seeded database:
    gameobject_template must carry IconName at column 4 (the loader's
    srcfmt 's' position) and data0 at column 9 (the first data-union
    field, moTransport.taxiPathId) - synthetic tests alone would carry
    a silent ordering regression."""
    conn = sqlite3.connect(str(seed_run["out"] / "classicmangos.sqlite"))
    try:
        go = [r[1] for r in conn.execute(
            "PRAGMA table_info(gameobject_template)")]
        assert go[4] == "IconName", go[:6]
        assert go[9] == "data0", go[7:11]
        assert go[:5] == ["entry", "type", "displayId", "name",
                          "IconName"], go[:5]
        ct = [r[1] for r in conn.execute(
            "PRAGMA table_info(creature_template)")]
        assert ct[5:13] == ["DisplayId1", "DisplayId2", "DisplayId3",
                            "DisplayId4", "DisplayIdProbability1",
                            "DisplayIdProbability2",
                            "DisplayIdProbability3",
                            "DisplayIdProbability4"], ct[5:13]
    finally:
        conn.close()


def test_positioning_strip_is_literal_aware() -> None:
    """The plain-path AFTER/FIRST strip must never rewrite inside
    literals (the exact silent-corruption family the positional rebuild
    exists for)."""
    report = translator.TranslationReport()
    state = translator.DBSchemaState()
    stmts = translator.translate_file_text(
        "CREATE TABLE `t` (`a` TEXT NOT NULL DEFAULT '');\n"
        "INSERT INTO `t` (`a`) VALUES ('x');\n"
        "ALTER TABLE `t` ADD COLUMN `b` TEXT NOT NULL "
        "DEFAULT 'one FIRST two AFTER x';\n"
        "ALTER TABLE `t` ADD COLUMN `c` int NOT NULL DEFAULT 0 AFTER `b`;\n",
        "literal-positioning.sql", report, {}, db_state=state)
    conn = sqlite3.connect(":memory:")
    try:
        for stmt in stmts:
            conn.execute(stmt)
        val = conn.execute(
            "SELECT `b` FROM `t`").fetchone()[0]
        assert val == "one FIRST two AFTER x", val
        cols = [r[1] for r in conn.execute("PRAGMA table_info(t)")]
        assert cols == ["a", "b", "c"], cols
    finally:
        conn.close()


def _transcript_digests(transcripts: Path) -> dict[str, str]:
    out = {}
    for db in DB_NAMES:
        data = (transcripts / f"{db}.sql").read_bytes()
        out[db] = hashlib.sha256(data).hexdigest()
    return out


def test_full_corpus_translation_is_deterministic(seed_run, _real_seed_augments) -> None:
    """A second full translation pass over the manifest must be
    byte-identical to the first (replay-order @var folding, chunking,
    and rewrite counters all deterministic). Transcript files are read
    as BYTES: statements contain literal CR/LF control chars that
    universal-newline text reads would silently rewrite. The second
    pass also proves the corpus-wide AUTO_INCREMENT -> AUTOINCREMENT
    column parity (source column tokens minus ENGINE-tail counter
    mentions == output AUTOINCREMENT occurrences; 42 == 42 today)."""
    manifest = json.loads(seeder.MANIFEST.read_text(encoding="utf-8"))
    report = translator.TranslationReport()
    variables: dict[str, str] = {}
    per_db: dict[str, list[str]] = {}
    # per-database schema tracking, mirroring the driver (positional
    # ADD COLUMN rebuilds need it and fail loud without it)
    db_states: dict[str, translator.DBSchemaState] = {}
    src_autoinc = 0
    for entry in manifest["entries"]:
        # strict decode mirrors the driver; the module fixture's
        # own strict pass gates the corpus first, so this leg can no
        # longer silently re-introduce the replaced-error class.
        text = seeder.entry_bytes(entry).decode("utf-8")
        stripped = translator._strip_comments_and_count(
            text, report, entry["source_path"])
        # case-insensitive: at least one corpus file (ai_playerbot_
        # cache.sql) spells the keyword lowercase - the translator's
        # rewrite is re.I, so the counter must be too.
        src_autoinc += len(re.findall(r"AUTO_INCREMENT", stripped, re.I))
        src_autoinc -= len(re.findall(r"AUTO_INCREMENT\s*=\s*\d+",
                                      stripped, re.I))
        stmts = translator.translate_file_text(
            text, entry["source_path"], report, variables,
            db_state=db_states.setdefault(
                entry["database"], translator.DBSchemaState()))
        per_db.setdefault(entry["database"], []).extend(stmts)
    # The pipeline's seed-augmentation leg (precomputed playerbot caches)
    # is a shared helper so this inline copy cannot drift from seed().
    seeder.apply_seed_augments(per_db, report)
    out_autoinc = sum("".join(per_db[db]).count("AUTOINCREMENT")
                      for db in per_db)
    assert out_autoinc == src_autoinc, (
        f"AUTO_INCREMENT columns lost/gained in translation: "
        f"source {src_autoinc} vs output {out_autoinc}")
    # False-FAIL classes (all loud, by design - investigate, never
    # narrow): a future append whose ALTER..CHANGE/MODIFY redefines an
    # AUTO_INCREMENT column (counted source-side, vanished output-side
    # as a no-op), literal-interior AUTO_INCREMENT mentions (this
    # counter is not literal-aware), or /*!>80000*/ conditional drops.
    for db in DB_NAMES:
        data = (";\n".join(per_db[db]) + ";\n").encode("utf-8")
        fresh = hashlib.sha256(data).hexdigest()
        stored = _transcript_digests(seed_run["transcripts"])[db]
        assert fresh == stored, f"non-deterministic translation: {db}"
    # The baseline hash-binds the transcript BYTES (not just counts):
    # count-preserving content drift fails here.
    pinned = seed_run["summary"].get("transcript_digests")
    assert pinned, "driver did not record transcript digests"
    assert pinned == _transcript_digests(seed_run["transcripts"])


def test_transcripts_execute_under_the_pinned_amalgamation(
        seed_run, tmp_path) -> None:
    """Engine-parity leg: compile tools/sqlite_exec_file.c against the
    pinned amalgamation (production define set) and replay ALL FOUR
    transcripts. sqlite3_exec's literal-aware splitter is a deliberate
    cross-check of the translator's statement boundaries."""
    gcc = shutil.which("gcc") or shutil.which("clang") or shutil.which("cc")
    if gcc is None:
        pytest.skip("no host C compiler available")
    if not (AMALGAMATION / "sqlite3.c").is_file():
        pytest.skip("amalgamation not staged (run "
                    "tools/stage_sqlite_amalgamation.py)")
    binary = tmp_path / "sqlite_exec_file.exe"
    build = subprocess.run(
        [gcc, "-O2", *PRODUCTION_DEFINES,
         "-I", str(AMALGAMATION), "-o", str(binary),
         str(AMALGAMATION / "sqlite3.c"),
         str(EXECUTOR_SRC)],
        capture_output=True, text=True, timeout=900)
    if build.returncode != 0:
        pytest.fail(f"host compile failed:\n{build.stderr[:2000]}")
    db = tmp_path / "engine.sqlite"
    transcripts = [str(seed_run["transcripts"] / f"{db_}.sql")
                   for db_ in DB_NAMES]
    run = subprocess.run([str(binary), str(db), *transcripts],
                         capture_output=True, text=True, timeout=600)
    assert run.returncode == 0, (
        f"pinned-engine replay failed:\n{run.stdout}\n{run.stderr[:2000]}")
    assert "applied cleanly" in run.stdout


# ---------------------------------------------------------------------------
# Translator regression pins
# ---------------------------------------------------------------------------


def test_var_substitution_is_literal_aware() -> None:
    """@var substitution must never fire inside string literals -
    MySQL never substitutes session variables there, so a '@name' inside
    data text is DATA (the surviving literal-corruption class in the
    @var path). All FOUR substitution sites are pinned - the
    INSERT-values expansion path, the INSERT fallback (INSERT..SELECT),
    and the generic non-INSERT path."""
    report = translator.TranslationReport()
    variables: dict[str, str] = {}
    stmts = translator.translate_file_text(
        "SET @a := 'x';\nSET @n := 5;\n"
        "CREATE TABLE probe (`v` TEXT);\n"
        "INSERT INTO probe VALUES ('mail @a now');\n"
        "INSERT INTO probe VALUES ('ticket @n closed');\n"
        "INSERT INTO probe VALUES (@n);\n"
        "INSERT INTO probe VALUES ('cnt @n := @n + 1 end');\n"
        "INSERT INTO probe SELECT @n, 'select literal @a here';\n"
        "UPDATE probe SET `v` = @n WHERE `v` = 'where literal @n';\n",
        "var-literal.sql", report, variables)
    joined = ";\n".join(stmts)
    assert "'mail @a now'" in joined, joined[:400]
    assert "'ticket @n closed'" in joined, joined[:400]
    assert "'select literal @a here'" in joined, joined[:600]
    assert "'where literal @n'" in joined, joined[:600]
    # Literal-survival pin for the FOURTH site - the
    # running-counter substitution inside _expand_insert_variables.
    assert "'cnt @n := @n + 1 end'" in joined, joined[:600]
    # the real (outside-literal) uses still substitute
    assert "VALUES\n(5)" in joined, joined[:400]
    assert "SELECT 5, " in joined, joined[:600]
    assert "`v` = 5 WHERE" in joined, joined[:600]


def test_nocase_collation_preserves_mysql_ci_semantics(seed_run) -> None:
    """MySQL text columns compare case-insensitively (server-
    default utf8*_general_ci); the translation emits COLLATE NOCASE so
    live lookups (ObjectMgr::GetPlayerGuidByName "WHERE name = '%s'",
    the add-ignore lookup, the character-creation duplicate-name check)
    keep their semantics. Explicit _bin/_cs columns stay BINARY."""
    report = seed_run["summary"]["translation"]
    assert report["nocase_columns"] > 500, report["nocase_columns"]
    chars = seed_run["out"] / "classiccharacters.sqlite"
    conn = sqlite3.connect(str(chars))
    try:
        sql = conn.execute("SELECT sql FROM sqlite_master WHERE "
                           "name='characters'").fetchone()[0]
    finally:
        conn.close()
    assert "`name` TEXT COLLATE NOCASE" in sql, sql[:400]
    with tempfile.TemporaryDirectory() as tmp:
        copy = Path(tmp) / "chars.sqlite"
        shutil.copy(chars, copy)
        conn = sqlite3.connect(str(copy))
        try:
            conn.execute("INSERT INTO characters (`name`) "
                         "VALUES ('NoCaseProbe')")
            n = conn.execute("SELECT COUNT(*) FROM characters WHERE "
                             "name = 'nocaseprobe'").fetchone()[0]
        finally:
            conn.close()
    assert n >= 1, "case-mismatched name lookup failed (BINARY drift)"


def test_decimal_columns_render_as_counted_real(seed_run) -> None:
    """decimal/numeric map explicitly to REAL (counted in the
    baseline), not an uncounted NUMERIC-affinity fall-through."""
    report = seed_run["summary"]["translation"]
    assert report["decimal_rewrites"] >= 6, report["decimal_rewrites"]
    chars = seed_run["out"] / "classiccharacters.sqlite"
    conn = sqlite3.connect(str(chars))
    try:
        names = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE name LIKE "
            "'%ahbot%' OR name LIKE '%named_location%'")]
        sqls = {n: s for (n, s) in conn.execute(
            "SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL "
            "AND (name LIKE '%ahbot%' OR name LIKE '%named_location%')")}
    finally:
        conn.close()
    assert names, "ahbot/named_location tables missing from the seed"
    rendered = [s for s in sqls.values() if "REAL" in s]
    assert rendered, sqls


def test_multibyte_inserts_chunk_on_bytes_not_chars() -> None:
    """The chunker's entry gate measures UTF-8 BYTES. A statement
    under the CHAR limit but over the BYTE limit (multibyte-dense rows)
    must still chunk - the pinned corpus's broadcast_text_locale INSERT
    (1,041,717 bytes) previously skipped chunking on its char count."""
    big = "字" * 300_000  # 300k chars = 900k UTF-8 bytes per row
    report = translator.TranslationReport()
    out = translator.translate_file_text(
        "CREATE TABLE `t` (`v` TEXT);\n"
        f"INSERT INTO `t` VALUES ('{big}'),('{big}');\n",
        "chunk-bytes.sql", report, {})
    inserts = [s for s in out if s.startswith("INSERT")]
    assert len(inserts) == 2, len(inserts)
    assert report.insert_chunks_split == 1


def test_values_regex_reach_whitespace_and_multiline_columns() -> None:
    """The VALUES regex family accepts `insert  into` (double
    space; the realmd antispam corpus shape) and newline-carrying column
    lists (entry 0164) - previously both bypassed @-expansion and
    chunking (row-parity held only vacuously for them). The multiline
    leg carries an @var so the regex MUST fire (a byte-identical
    passthrough can no longer satisfy it)."""
    report = translator.TranslationReport()
    variables: dict[str, str] = {}
    out = translator.translate_file_text(
        "SET @n := 5;\n"
        "CREATE TABLE `t` (`a` int, `b` int);\n"
        "insert  into `t` values (@n := @n + 1),( @n := @n + 1);\n"
        "INSERT INTO `t`\n(`a`, `b`)\nVALUES (@n, 9);\n",
        "reach.sql", report, variables)
    joined = ";\n".join(out)
    # the row splitter preserves interior whitespace: the second row
    # keeps its leading space from the corpus-style "( @n ...". The
    # plain @n in the third statement reads 7 - the PERSISTED final
    # counter value after the two running increments (5 -> 6 -> 7),
    # pinning the replay-order semantics alongside the reach fix.
    assert "VALUES\n(6),( 7)" in joined, joined[:400]
    # anchored to the expansion-path rendering (newline before the row):
    # the generic fallback would emit "VALUES (7, 9)" with a space, so
    # a regex-family regression cannot be satisfied by the fallback.
    assert "VALUES\n(7, 9)" in joined, joined[:400]


def _synthetic_manifest(tmp_path: Path, body: str) -> Path:
    sql = tmp_path / "synthetic.sql"
    sql.write_bytes(body.encode("utf-8"))
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"entries": [{
        "migration_id": "synthetic-0001",
        "source_path": str(sql),
        "database": "classicmangos",
        "sql_sha256": hashlib.sha256(sql.read_bytes()).hexdigest(),
        "sql_size": sql.stat().st_size,
    }]}), encoding="utf-8")
    return manifest


def test_driver_first_error_aborts_and_dumps_binary_exact(tmp_path) -> None:
    """The fail-loud family is regression-pinned. First
    statement error (max_errors=0) aborts the seed loudly and the
    forensic dump carries the TRUE bytes (newline="": no Windows
    text-mode mangling of literal CR/LF)."""
    manifest = _synthetic_manifest(tmp_path, (
        "CREATE TABLE `t` (`v` TEXT);\n"
        "INSERT INTO `t` VALUES ('ok');\n"
        "INSERT INTO `t` VALUES ('line1\\nline2', 'x');\n"
        "INSERT INTO `t` VALUES ('never reached');\n"))
    with pytest.raises(RuntimeError, match="seed failed"):
        seeder.seed(tmp_path / "out", None, False, 0,
                    manifest_path=manifest)
    dump = tmp_path / "out" / "failed-statements.sql"
    assert dump.is_file()
    data = dump.read_bytes()
    assert b"\r\n" not in data, "text-mode translation corrupted the dump"
    assert b"'line1\nline2'" in data


def test_write_baseline_refused_on_unclean_seed(tmp_path,
                                                 monkeypatch) -> None:
    """--write-baseline refuses an unclean seed; the
    append-only baseline is never clobbered by a failed run. The
    manifest-path guard is monkeypatched past (its own refusal is
    pinned separately below) so this exercises the not-clean branch."""
    manifest = _synthetic_manifest(tmp_path, (
        "CREATE TABLE `t` (`v` TEXT);\n"
        "INSERT INTO `t` VALUES ('a', 'b');\n"))  # one tolerated error
    monkeypatch.setattr(seeder, "MANIFEST", manifest)
    before = BASELINE.read_bytes()
    with pytest.raises(RuntimeError, match="seed not clean"):
        seeder.seed(tmp_path / "out", None, True, 1,
                    manifest_path=manifest)
    assert BASELINE.read_bytes() == before


def test_apply_seed_augments_guards(tmp_path) -> None:
    """The augmentation loader's fail-loud guards (the ;; corruption
    class once cost a pinned-engine replay session to diagnose; every
    guard gets a regression pin)."""
    import gzip as gzip_mod
    report = translator.TranslationReport()
    per_db = {"classiccharacters": []}

    def run_with(files: dict) -> None:
        for name, content in files.items():
            (tmp_path / name).write_bytes(
                gzip_mod.compress(content.encode("utf-8"))
                if name.endswith(".gz") else content.encode("utf-8"))
        monkey = pytest.MonkeyPatch()
        monkey.setattr(seeder, "AUGMENT_DIR", tmp_path)
        try:
            per_db.clear()
            per_db["classiccharacters"] = []
            seeder.apply_seed_augments(per_db, report)
        finally:
            monkey.undo()
            # cleanup must also run on the RAISING paths or earlier
            # cases' fixtures leak into later globs
            for stale in tmp_path.iterdir():
                stale.unlink()

    with pytest.raises(RuntimeError, match="unknown database"):
        run_with({"otherdb.sql": "INSERT INTO `t` VALUES (1);"})
    with pytest.raises(RuntimeError, match="non-INSERT"):
        run_with({"classiccharacters.sql": "REPLACE INTO `t` VALUES (1);"})
    with pytest.raises(RuntimeError, match="embedded semicolon"):
        run_with({"classiccharacters.sql":
                  "INSERT INTO `t` VALUES (1); INSERT INTO `t` VALUES (2);"})
    # happy path incl. the trailing-semicolon strip + gz container
    run_with({"classiccharacters.sql.gz":
              "INSERT INTO `t` VALUES (1);\nINSERT INTO `t` VALUES (2);\n"})
    assert per_db["classiccharacters"] == [
        "INSERT INTO `t` VALUES (1)", "INSERT INTO `t` VALUES (2)"]


def test_apply_seed_augments_provenance_guard(tmp_path) -> None:
    """A manifest advance must fail the seed until the capture is
    re-validated - the world's cache-load branches have no version
    check of their own."""
    (tmp_path / "classiccharacters.sql").write_text(
        "INSERT INTO `t` VALUES (1);", encoding="utf-8")
    (tmp_path / "PROVENANCE.json").write_text(
        json.dumps({"manifest_sha256": "0" * 64}), encoding="utf-8")
    monkey = pytest.MonkeyPatch()
    monkey.setattr(seeder, "AUGMENT_DIR", tmp_path)
    try:
        with pytest.raises(RuntimeError, match="PROVENANCE"):
            seeder.apply_seed_augments({"classiccharacters": []},
                                       translator.TranslationReport(),
                                       manifest_sha256="1" * 64)
        # a matching manifest passes
        assert seeder.apply_seed_augments(
            {"classiccharacters": []}, translator.TranslationReport(),
            manifest_sha256="0" * 64) == 1
    finally:
        monkey.undo()


def test_write_baseline_refused_for_foreign_manifest(tmp_path) -> None:
    """A synthetic/foreign manifest can never
    overwrite the committed append-only baseline, even on a clean seed."""
    manifest = _synthetic_manifest(
        tmp_path, "CREATE TABLE `t` (`v` TEXT);\n")
    before = BASELINE.read_bytes()
    with pytest.raises(RuntimeError,
                       match="not the real migration manifest"):
        seeder.seed(tmp_path / "out", None, True, 0,
                    manifest_path=manifest)
    assert BASELINE.read_bytes() == before


def test_stale_sidecars_and_dump_reset_across_runs(tmp_path) -> None:
    """Stale -wal/-shm/-journal sidecars and a stale
    failed-statements.sql from an earlier run are cleared before the
    fresh seed (no cross-run contamination of artifacts or evidence)."""
    manifest = _synthetic_manifest(
        tmp_path, "CREATE TABLE `t` (`v` TEXT);\n")
    out = tmp_path / "out"
    out.mkdir(parents=True)
    (out / "classicmangos.sqlite").write_bytes(b"stale")
    for suffix in ("-wal", "-shm", "-journal"):
        (out / ("classicmangos.sqlite" + suffix)).write_bytes(b"stale")
    (out / "failed-statements.sql").write_text("-- stale\n",
                                               encoding="utf-8")
    rc, _summary = seeder.seed(out, None, False, 0,
                               manifest_path=manifest)
    assert rc == 0
    assert not (out / "classicmangos.sqlite-wal").exists()
    assert not (out / "classicmangos.sqlite-shm").exists()
    assert not (out / "classicmangos.sqlite-journal").exists()
    assert not (out / "failed-statements.sql").exists()


def test_collation_edge_shapes_are_pinned() -> None:
    """The bare ALTER..ADD form (no
    COLUMN keyword - the dominant in-corpus ADD idiom, corpus-numeric
    today), the _bin/_cs and bare-`binary` explicit collations, the
    ALTER-branch collation rename, uppercase ENUM, the N-variant text
    types, the index-named-'text' boundary guard, and the DEFAULT
    literal canary all translate correctly. Each shape is corpus-absent
    today; this is the append-path pin."""
    report = translator.TranslationReport()
    out = translator.translate_file_text(
        "CREATE TABLE `c` (\n"
        "  `a` varchar(10) COLLATE utf8_bin,\n"
        "  `b` varchar(10) COLLATE binary,\n"
        "  `e` ENUM('x', 'Y'),\n"
        "  `lit` TEXT DEFAULT 'a, b TEXT c',\n"
        "  `nv` NVARCHAR(100),\n"
        "  `nat` NATIONAL VARCHAR(64)\n"
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8;\n"
        "ALTER TABLE `c` ADD `d` varchar(20) NOT NULL DEFAULT '';\n"
        "ALTER TABLE `c` ADD COLUMN `f` varchar(20);\n"
        "ALTER TABLE `c` ADD COLUMN `g` varchar(10) COLLATE utf8_bin;\n"
        "ALTER TABLE `c` ADD INDEX `text` (`a`);\n",
        "collate-edge.sql", report, {})
    joined = ";\n".join(out)
    assert "`a` TEXT COLLATE BINARY" in joined, joined
    assert "`b` TEXT COLLATE BINARY" in joined, joined
    assert "`d` TEXT COLLATE NOCASE" in joined, joined
    assert "`f` TEXT COLLATE NOCASE" in joined, joined
    assert "`g` TEXT COLLATE BINARY" in joined, joined
    assert "`nv` TEXT COLLATE NOCASE" in joined, joined
    assert "`nat` TEXT COLLATE NOCASE" in joined, joined
    assert report.enum_columns == 1, report.enum_columns
    assert "CHECK(`e` IN ('x', 'Y'))" in joined, joined
    # Literal canary: the literal-aware NOCASE routing must leave literal
    # interiors verbatim (a ', x TEXT'-shaped DEFAULT is DATA).
    assert "'a, b TEXT c'" in joined, joined
    # An index NAMED 'text' must not eat a COLLATE (the boundary
    # guard); the statement stays a CREATE INDEX.
    assert "COLLATE NOCASE (`a`)" not in joined, joined
    assert "ADD INDEX" in joined or "CREATE" in joined, joined


def test_every_translated_text_column_carries_a_collation(
        seed_run) -> None:
    """Schema-level NOCASE invariant - every
    TEXT-family column in the seeded schemas must carry an explicit
    COLLATE (NOCASE for the ci default, BINARY for explicit _bin/_cs).
    A future manifest append whose text column escapes the blanket
    fails HERE, loudly, instead of seeding BINARY
    silently behind a green harness."""
    missing = []
    raw_collate = []
    for db in DB_NAMES:
        conn = sqlite3.connect(str(seed_run["out"] / f"{db}.sqlite"))
        try:
            for (name, sql) in conn.execute(
                    "SELECT name, sql FROM sqlite_master WHERE type = "
                    "'table' AND sql IS NOT NULL"):
                for m in re.finditer(
                        r"(?m)^(\s*`?[A-Za-z_]\w*`?\s+TEXT\b)"
                        r"(?!\s*COLLATE)", sql):
                    missing.append(f"{db}.{name}: {m.group(1).strip()}")
                # A raw collation NAME that is
                # neither NOCASE nor BINARY means an explicit-collation
                # rename escaped on some path - SQLite would reject it
                # at first use, but catch it here, path-independently.
                for m in re.finditer(
                        r"\bCOLLATE\s+(?!NOCASE\b|BINARY\b)\w+", sql):
                    raw_collate.append(f"{db}.{name}: {m.group(0)}")
        finally:
            conn.close()
    assert not missing, missing[:10]
    assert not raw_collate, raw_collate[:10]


def test_baseline_pins_manifest_hash_and_transcript_sizes(seed_run) -> None:
    """The baseline names the exact
    manifest state it was seeded from (O(1) mismatch diagnosis) and
    pins the per-database transcript byte sizes - the seed
    footprint for the dual-provider window (~118 MiB raw)."""
    stored = json.loads(BASELINE.read_text(encoding="utf-8"))
    # LF-normalized input: the repo's canonical form is LF
    # (.gitattributes), so a fresh LF checkout hashes identically to
    # this CRLF working tree.
    assert stored["manifest_sha256"] == hashlib.sha256(
        seeder.MANIFEST.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
    sizes = {db: (seed_run["transcripts"] / f"{db}.sql").stat().st_size
             for db in DB_NAMES}
    assert stored["transcript_sizes"] == sizes
    assert sum(sizes.values()) > 100_000_000


# ---------------------------------------------------------------------------
# Append-only tail parity
# ---------------------------------------------------------------------------


def _bot_table_infos(db_path: Path) -> dict[str, list[tuple]]:
    """PRAGMA table_info rows for every bot_* table, keyed by table name
    (cid, name, type, notnull, dflt_value, pk - the full shape)."""
    conn = sqlite3.connect(str(db_path))
    try:
        names = [r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name LIKE 'bot\\_%' ESCAPE '\\'")]
        return {name: conn.execute(
            f'PRAGMA table_info("{name}")').fetchall() for name in names}
    finally:
        conn.close()


def test_c2_tail_parity_fresh_replay_matches_truncated_plus_0413(
        seed_run, tmp_path) -> None:
    """The append-only law's mechanical backstop: a FRESH full-manifest
    replay and an UPGRADE-shaped replay (the previous manifest minus its
    last entry, then the 0413 v2 file applied as the upgrade step) must
    converge on IDENTICAL PRAGMA table_info for every bot_* table.

    This is the pin for the C2 bricking risk: editing the SHIPPED seed
    DDL (ai_playerbot_llm_memory.sql) to carry the new columns inline
    would diverge fresh provisions from the 0412-shaped databases the
    field already holds - the truncated leg then fails loudly here
    (duplicate column, or a shifted table_info) instead of at first boot
    on a device. The truncated leg executes only schema-shaping
    statements (CREATE/ALTER/DROP): PRAGMA table_info is DDL-determined,
    and re-executing the 153 MiB characters INSERT corpus would buy no
    fidelity over the fresh leg's full execution."""
    manifest = json.loads(seeder.MANIFEST.read_text(encoding="utf-8"))
    # E2 widened the tail: 0414 (texts register UPDATEs, classicmangos)
    # rides after 0413. The parity upgrade shape is manifest minus its
    # last TWO entries, then both tail files applied in order - the 0414
    # leg is DML-only, so PRAGMA parity is structural for 0413 and
    # trivially held for 0414 (it shapes nothing).
    tail_e2 = manifest["entries"][-1]
    assert tail_e2["migration_id"] == (
        "0414-playerbot-world-playerbot-texts-e2-register"), tail_e2
    assert tail_e2["database"] == "classicmangos", tail_e2
    tail = manifest["entries"][-2]
    assert tail["migration_id"] == (
        "0413-playerbot-characters-ai_playerbot_llm_memory_v2"), tail
    assert tail["database"] == "classiccharacters", tail
    # entry_bytes re-verifies the file's bytes against the manifest's
    # sql_sha256 pin - the upgrade leg replays exactly the shipped SQL.
    v2_sql = seeder.entry_bytes(tail).decode("utf-8")

    report = translator.TranslationReport()
    variables: dict[str, str] = {}
    db_states: dict[str, translator.DBSchemaState] = {}
    schema_stmts: list[str] = []
    for entry in manifest["entries"][:-2]:
        text = seeder.entry_bytes(entry).decode("utf-8")
        stmts = translator.translate_file_text(
            text, entry["source_path"], report, variables,
            db_state=db_states.setdefault(entry["database"],
                                          translator.DBSchemaState()))
        if entry["database"] == "classiccharacters":
            schema_stmts.extend(
                s for s in stmts if re.match(
                    r"\s*(CREATE|ALTER|DROP)\b", s, re.I))
    # the 0414 upgrade leg: DML-only, translated against the replayed
    # classicmangos state so the upgrade replay stays faithful to
    # what a device applies (its UPDATEs shape no schema)
    e2_sql = seeder.entry_bytes(tail_e2).decode("utf-8")
    translator.translate_file_text(
        e2_sql, tail_e2["source_path"], report, variables,
        db_state=db_states.setdefault(tail_e2["database"],
                                      translator.DBSchemaState()))
    # The upgrade step: the v2 file translated as its own tail against the
    # SAME replay-continued schema state an upgraded database would have.
    schema_stmts.extend(translator.translate_file_text(
        v2_sql, tail["source_path"], report, variables,
        db_state=db_states["classiccharacters"]))

    upgraded = tmp_path / "upgraded-characters.sqlite"
    conn = sqlite3.connect(str(upgraded))
    try:
        conn.execute("PRAGMA foreign_keys=OFF")
        for stmt in schema_stmts:
            conn.execute(stmt)
        conn.commit()
    finally:
        conn.close()

    fresh_infos = _bot_table_infos(seed_run["out"] / "classiccharacters.sqlite")
    upgraded_infos = _bot_table_infos(upgraded)
    # The bot_* table SET itself must converge (a table only one leg has
    # is a diverged provision, not just a changed column).
    assert set(fresh_infos) == set(upgraded_infos), (
        sorted(fresh_infos), sorted(upgraded_infos))
    assert set(fresh_infos) == {
        "bot_backstory", "bot_player_facts", "bot_player_relationship",
        "bot_player_history"}, sorted(fresh_infos)
    for name in sorted(fresh_infos):
        assert fresh_infos[name] == upgraded_infos[name], (
            f"bot_* schema divergence on {name}: fresh "
            f"{fresh_infos[name]} vs upgraded {upgraded_infos[name]}")

    # Shape pins for the 0413 columns themselves (order included - the
    # appended-at-tail positions ARE the upgrade contract).
    facts_cols = [r[1] for r in fresh_infos["bot_player_facts"]]
    assert facts_cols[-1] == "voiced_at", facts_cols
    rel_cols = [r[1] for r in fresh_infos["bot_player_relationship"]]
    assert rel_cols[-3:] == ["last_voiced_tier", "last_greeted_at",
                             "last_greet_line"], rel_cols
    assert [(r[1], r[5]) for r in fresh_infos["bot_player_history"]
            if r[5] > 0] == [("bot", 1), ("player_or_channel", 2),
                             ("seq", 3)], fresh_infos["bot_player_history"]
    # NULL default = "never voiced" - the no-backfill law, at the schema
    # (explicit DEFAULT NULL renders as the 'NULL' literal in table_info).
    for table, col in (("bot_player_facts", "voiced_at"),
                       ("bot_player_relationship", "last_voiced_tier"),
                       ("bot_player_relationship", "last_greeted_at"),
                       ("bot_player_relationship", "last_greet_line")):
        row = next(r for r in fresh_infos[table] if r[1] == col)
        assert row[3] == 0 and row[4] == "NULL", (table, col, row)
