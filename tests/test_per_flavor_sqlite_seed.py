"""Per-flavor SQLite seed lane (port plan S4 item 3 / census P3).

Pins the lane's contract now that the tbc/wotlk server_core + db_content
pins are registered and checked out (P2) and `--flavor tbc|wotlk` seeds
its OWN inputs instead of refusing:

  - plan resolution: every flavor resolves its own core sql/base, its own
    DB-repo Full_DB archive, its flavor.json databases-block output names
    (tbcrealmd... / wotlkrealmd...), its revision floors parsed from its
    core's src/shared/revision_sql.h, and its per-flavor baseline path —
    never a classic input under another flavor's name (vanilla keeps the
    historical literals byte-for-byte);
  - the corpus claim the FULLTEXT translator fix rests on: exactly one
    `FULLTEXT KEY` line per tbc/wotlk Full_DB (areatrigger_teleport),
    zero in the vanilla corpus (the drop itself is pinned in
    tests/test_seed_realm_db_fail_loud.py);
  - the committed baselines (schemas/sqlite-seed-baseline-{tbc,wotlk}.json)
    exist, carry the measured floors for exactly the seeded database set,
    and are ENFORCED: a hollow or partially-applied seed fails
    verify_seed_floors (the no-fake-success posture of build_one, caught
    after the fact);
  - version honesty: the seeded Full_DB snapshot is older than the core's
    mangos revision floor, so verify_version_table must report False for
    mangos (SCHEMA_COMPATIBLE is never faked) while the other three
    databases pass on their own floors.
"""
from __future__ import annotations

import gzip
import json
import re
import sqlite3
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

import seed_realm_db  # noqa: E402
from tools import flavor_layout  # noqa: E402

FULLTEXT_LINE_RE = re.compile(r"(?mi)^\s*FULLTEXT\s+KEY\b")

PLANS = {
    "tbc": {
        "core": "native/cmangos-tbc",
        "db_repo": "native/classic-db-tbc",
        "dump_name": "TBCDB_1.11.0_Vengeance_One_A_Cmangos_Story.sql.gz",
        "names": ("tbccharacters", "tbclogs", "tbcmangos", "tbcrealmd"),
    },
    "wotlk": {
        "core": "native/cmangos-wotlk",
        "db_repo": "native/classic-db-wotlk",
        "dump_name": "WoTLKDB_1_9_14092.sql.gz",
        "names": ("wotlkcharacters", "wotlklogs", "wotlkmangos", "wotlkrealmd"),
    },
}


def _plan_or_skip(flavor: str) -> seed_realm_db.SeedPlan:
    """Resolve the flavor's plan, skipping when its pins have not landed
    (the same checkout-gated posture as the real-corpus tests)."""
    try:
        flavor_layout.require_flavor_pins(flavor,
                                          groups=("server_core", "db_content"))
    except flavor_layout.FlavorPinError:
        pytest.skip(f"{flavor} pins not registered/checked out")
    return seed_realm_db._flavor_seed_plan(flavor)


# ---------------------------------------------------------------------------
# Plan resolution: every input is the flavor's OWN
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_plan_resolves_the_flavors_own_inputs(flavor: str) -> None:
    spec = PLANS[flavor]
    plan = _plan_or_skip(flavor)
    assert plan.core_submodule == spec["core"]
    assert plan.db_submodule == spec["db_repo"]
    assert plan.sql_base == ROOT / spec["core"] / "sql" / "base"
    assert plan.world_dump == (ROOT / spec["db_repo"] / "Full_DB"
                               / spec["dump_name"])
    assert plan.world_dump.is_file(), "the pinned Full_DB archive is checked out"
    assert sorted(plan.db_names.values()) == sorted(spec["names"])
    # Never a classic input: the vanilla core/db-repo literals are absent.
    for path in (plan.sql_base, plan.world_dump):
        assert "native\\cmangos\\" not in str(path)
        assert "native/classic-db/" not in str(path).replace("\\", "/")


@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_plan_revision_floors_come_from_the_flavor_core(flavor: str) -> None:
    """The floors must be the flavor core's own revision_sql.h defines (the
    same header CheckRequiredField compiles from), not the vanilla set."""
    plan = _plan_or_skip(flavor)
    header = (ROOT / plan.core_submodule / "src/shared/revision_sql.h")
    text = header.read_text(encoding="utf-8", errors="replace")
    assert plan.required_versions.keys() == seed_realm_db.REQUIRED_VERSIONS.keys()
    for label, (table, col) in plan.required_versions.items():
        assert table == seed_realm_db._VERSION_TABLES[label]
        assert re.search(rf'REVISION_DB_\w+\s+"{re.escape(col)}"', text), (
            f"{label} floor {col} must be defined in {header}")


def test_vanilla_plan_keeps_the_historical_literals() -> None:
    """Invariant 1: vanilla resolves byte-identically to the pre-
    parameterization lane (same literals, same output names, the manifest
    transcript lane's baseline — never this lane's)."""
    plan = seed_realm_db._flavor_seed_plan("vanilla")
    assert plan.sql_base == seed_realm_db.SQL_BASE
    assert plan.world_dump == seed_realm_db.WORLD_DUMP
    assert plan.db_names == {label: label
                             for label in seed_realm_db.DB_SOURCES}
    assert plan.required_versions == seed_realm_db.REQUIRED_VERSIONS
    assert plan.baseline == ROOT / "schemas" / "sqlite-seed-baseline.json"


# ---------------------------------------------------------------------------
# The FULLTEXT corpus claim (areatrigger_teleport, one site per Full_DB)
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_full_db_carries_exactly_one_fulltext_key(flavor: str) -> None:
    plan = _plan_or_skip(flavor)
    text = gzip.open(plan.world_dump, "rt", encoding="utf-8",
                     errors="replace").read()
    hits = FULLTEXT_LINE_RE.findall(text)
    assert len(hits) == 1, (
        "the translator's FULLTEXT drop is sized for exactly one site per "
        "Full_DB (areatrigger_teleport); a second site needs a new look")


def test_vanilla_corpus_has_no_fulltext_key() -> None:
    dump = seed_realm_db.WORLD_DUMP
    if not dump.is_file():
        pytest.skip("pinned ClassicDB archive not present")
    text = gzip.open(dump, "rt", encoding="utf-8", errors="replace").read()
    assert not FULLTEXT_LINE_RE.search(text)


# ---------------------------------------------------------------------------
# Committed baselines are measured and enforced
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_committed_baseline_shape(flavor: str) -> None:
    plan = _plan_or_skip(flavor)
    if not plan.baseline.is_file():
        pytest.fail(f"{plan.baseline} is missing — run `python "
                    f"tools/seed_realm_db.py --flavor {flavor} --out <tmp> "
                    "--write-baseline` on a clean seed (floors are measured, "
                    "never guessed)")
    baseline = json.loads(plan.baseline.read_text(encoding="utf-8"))
    assert baseline["flavor"] == flavor
    assert baseline["inputs"]["core_submodule"] == plan.core_submodule
    assert baseline["inputs"]["db_submodule"] == plan.db_submodule
    assert set(baseline["databases"]) == set(plan.db_names.values())
    world = plan.db_names["mangos"]
    assert set(baseline["floor_tables"][world]) == set(
        seed_realm_db.WORLD_FLOOR_TABLES)
    for db_name, floors in baseline["databases"].items():
        assert floors["errors"] == 0, "baselines come only from clean seeds"
        assert floors["tables"] > 0 and floors["rows"] > 0


@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_hollow_seed_fails_the_committed_floors(flavor: str,
                                                tmp_path: Path) -> None:
    """A seed that lands empty databases must fail verification against the
    committed baseline — the after-the-fact twin of build_one's
    zero-tolerated-errors rule (a hollow world DB passes DATABASE_OPEN while
    carrying no content)."""
    plan = _plan_or_skip(flavor)
    if not plan.baseline.is_file():
        pytest.skip("baseline not yet written")
    for db_name in plan.db_names.values():
        sqlite3.connect(str(tmp_path / f"{db_name}.sqlite")).close()
    assert seed_realm_db.verify_seed_floors(tmp_path, plan) is False


def test_write_baseline_refuses_vanilla(tmp_path: Path) -> None:
    """schemas/sqlite-seed-baseline.json belongs to the manifest transcript
    lane (tools/seed_sqlite_from_manifest.py --write-baseline); this lane
    must refuse to touch it."""
    plan = seed_realm_db._flavor_seed_plan("vanilla")
    with pytest.raises(RuntimeError, match="not this lane's to write"):
        seed_realm_db.write_seed_baseline(tmp_path, plan, {})


# ---------------------------------------------------------------------------
# Version honesty (no faked SCHEMA_COMPATIBLE)
# ---------------------------------------------------------------------------

def _version_table_conn(table: str, cols: list[str]) -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    decl = ", ".join(f"`{c}` TEXT" for c in cols)
    conn.execute(f"CREATE TABLE {table} ({decl});")
    conn.execute(f"INSERT INTO {table} VALUES ({', '.join('NULL' for _ in cols)});")
    return conn


@pytest.mark.parametrize("flavor", ("tbc", "wotlk"))
def test_mangos_floor_reports_honest_behind(flavor: str) -> None:
    """The Full_DB snapshot installs an older world schema than the core's
    floor (tbc: s2491 archive vs s2492 core; wotlk: 14092 vs 14104), so the
    seeded db_version lacks the floor column and verify_version_table must
    say False for mangos — SCHEMA_COMPATIBLE is reported honestly, never
    faked by renaming. The other three DBs seed at their floor and pass."""
    plan = _plan_or_skip(flavor)
    table, col = plan.required_versions["mangos"]
    conn = _version_table_conn(table, ["required_old_01_mangos_x"])
    try:
        assert seed_realm_db.verify_version_table(
            conn, "mangos", plan.required_versions) is False
    finally:
        conn.close()
    # A db_version carrying the floor column passes.
    conn = _version_table_conn(table, [col])
    try:
        assert seed_realm_db.verify_version_table(
            conn, "mangos", plan.required_versions) is True
    finally:
        conn.close()
