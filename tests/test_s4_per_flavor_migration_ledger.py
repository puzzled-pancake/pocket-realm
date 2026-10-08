"""Per-flavor migration-ledger lane (port plan S4 items 4/5, F3-1/F3-3):
tools/stage_database_migrations.py selects every flavor's inputs from its
OWN core/DB/bots trees and writes schemas/database-migrations-{flavor}.json.

Pinned here:
- expected_revisions is PARSED from each core's src/shared/revision_sql.h
  (the CheckRequiredField source), never hand-copied; the vanilla parse
  equals the pinned EXPECTED_REVISIONS constant (the G3-3 byte-identity
  anchor the module cross-checks);
- the world cutoff rule — the highest required_*_mangos_* revision the
  staged DB-repo inputs (Full_DB dump + Updates + Instances) already
  install — on a synthetic tree, including gz scanning, the mangos-scope
  immunity to realmd/characters markers, and the append-only-above rule;
- the real selections: tbc appends exactly the s2492 catch-up above the
  Full_DB's s2491; wotlk appends NOTHING because its Updates integrate the
  whole 14093-14104 chain (5864_Core-14102_14104.sql et al.) up to the core
  floor; vanilla keeps the historical z2830+ shape;
- the C5-in-fact ACID rule: every flavor selects its in-tree
  ACID/acid_<expansion>.sql (both pinned DB repos ship it; both histories
  are single-commit squashes, so no acid_commits provenance block exists);
- the committed ledgers carry the floors and the catch-up entries with the
  exact in-tree file hashes, and the vanilla golden pair is untouched.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

from tools import flavor_layout  # noqa: E402
from tools import stage_database_migrations as migrations  # noqa: E402


# ---------------------------------------------------------------------------
# expected_revisions: parsed from revision_sql.h, never hand-copied
# ---------------------------------------------------------------------------

def _header_define(flavor: str, define: str) -> str:
    """Independent line-scan of the flavor core's revision_sql.h (a second
    implementation, so the stager's regex parse cannot validate itself)."""
    header = (
        ROOT / flavor_layout.resolve(flavor).core_submodule
        / "src/shared/revision_sql.h"
    )
    prefix = f"#define REVISION_DB_{define} "
    for line in header.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith(prefix):
            return line.split('"')[1]
    raise AssertionError(f"REVISION_DB_{define} not found in {header}")


@pytest.mark.parametrize(
    "flavor,label,define",
    [
        ("vanilla", "realm", "REALMD"),
        ("vanilla", "characters", "CHARACTERS"),
        ("vanilla", "logs", "LOGS"),
        ("vanilla", "world", "MANGOS"),
        ("tbc", "realm", "REALMD"),
        ("tbc", "characters", "CHARACTERS"),
        ("tbc", "logs", "LOGS"),
        ("tbc", "world", "MANGOS"),
        ("wotlk", "realm", "REALMD"),
        ("wotlk", "characters", "CHARACTERS"),
        ("wotlk", "logs", "LOGS"),
        ("wotlk", "world", "MANGOS"),
    ],
)
def test_expected_revisions_are_parsed_from_the_core_header(flavor, label, define):
    assert migrations.expected_revisions(flavor)[label] == _header_define(flavor, define)


def test_vanilla_parse_equals_the_pinned_constant():
    """The G3-3 anchor: the parse must reproduce the module constant the
    golden-sealed vanilla ledger bytes carry (the stager also enforces this
    at run time; the test documents the invariant)."""
    assert migrations.expected_revisions("vanilla") == migrations.EXPECTED_REVISIONS


def test_expected_revisions_refuse_a_header_without_floors(tmp_path, monkeypatch):
    monkeypatch.setattr(migrations, "ROOT", tmp_path)
    with pytest.raises(RuntimeError, match="revision floors"):
        migrations.expected_revisions("tbc")


# ---------------------------------------------------------------------------
# The world cutoff rule on a synthetic tree (the generalized vanilla rule)
# ---------------------------------------------------------------------------

def _make_gz(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as zipped:
            zipped.write(text.encode("utf-8"))


def test_integrated_world_revision_scans_dump_updates_and_instances(tmp_path):
    rule = migrations._FLAVOR_RULES["tbc"]
    dump = tmp_path / "Full_DB" / "seed.sql.gz"
    _make_gz(dump, "CREATE TABLE db_version (`required_s2491_01_mangos_spillover_rename` bit);\n")
    updates = [tmp_path / "Updates" / "0001_content.sql"]
    updates[0].parent.mkdir(parents=True, exist_ok=True)
    updates[0].write_text(
        # A realmd marker and a NON-mangos world-family string must not move
        # the world cutoff; only the mangos dialect counts.
        "-- required_s2495_01_realmd_future\n"
        "-- required_s2499_01_logs_future\n",
        encoding="utf-8",
    )
    instances = [tmp_path / "Updates" / "Instances" / "030_alterac.sql"]
    instances[0].parent.mkdir(parents=True, exist_ok=True)
    instances[0].write_text(
        "-- required_s2490_01_mangos_older\n", encoding="utf-8",
    )
    integrated = migrations._integrated_world_revision(rule, dump, updates, instances)
    assert integrated == 2491  # the dump wins; realmd/logs markers ignored


def test_integrated_world_revision_takes_updates_above_the_dump(tmp_path):
    """The wotlk shape: an Updates file carries the core-sync chain past the
    dump's own revision (5864_Core-14102_14104.sql in the real repo)."""
    rule = migrations._FLAVOR_RULES["wotlk"]
    dump = tmp_path / "Full_DB" / "seed.sql.gz"
    _make_gz(dump, "CREATE TABLE db_version (`required_14092_01_mangos_proc_cooldown` bit);\n")
    updates = [tmp_path / "Updates" / "5864_Core-14102_14104.sql"]
    updates[0].parent.mkdir(parents=True, exist_ok=True)
    updates[0].write_text(
        "ALTER TABLE db_version CHANGE COLUMN required_14103_01_mangos_pet_spell_lists "
        "required_14104_01_mangos_spell_threat_mask bit;\n",
        encoding="utf-8",
    )
    assert migrations._integrated_world_revision(rule, dump, updates, []) == 14104


def test_integrated_world_revision_fails_loud_without_any_marker(tmp_path):
    rule = migrations._FLAVOR_RULES["tbc"]
    dump = tmp_path / "Full_DB" / "seed.sql.gz"
    _make_gz(dump, "SELECT 1;\n")
    with pytest.raises(RuntimeError, match="cutoff"):
        migrations._integrated_world_revision(rule, dump, [], [])


def test_core_update_append_filter_is_strictly_above_the_integrated_revision():
    """The append predicate itself: a core mangos file participates only when
    its parsed revision is strictly newer than the integrated one."""
    rule = migrations._FLAVOR_RULES["tbc"]
    keep = [
        name for name in ("s2491_01_mangos_spillover_rename.sql",
                          "s2492_01_mangos_gobject_near_link.sql")
        if rule.core_update_revision(name) > 2491
    ]
    assert keep == ["s2492_01_mangos_gobject_near_link.sql"]
    wotlk_rule = migrations._FLAVOR_RULES["wotlk"]
    assert wotlk_rule.core_update_revision("14104_01_mangos_spell_threat_mask.sql") == 14104


# ---------------------------------------------------------------------------
# The real selections (checked-out submodules, registered pins)
# ---------------------------------------------------------------------------

def test_tbc_selection_appends_exactly_the_s2492_catch_up():
    """Lanes item 5 / ui-android (b): the tbc Full_DB installs
    required_s2491_01_mangos_spillover_rename while the core floor is s2492
    (an ALTER … CHANGE — MariaDB-legal, so it rides the LEDGER lane; the
    sqlite dev lane keeps the honest BEHIND posture). The selection must
    append exactly that one core migration."""
    inputs = migrations.select_inputs("tbc")
    core = [
        item.path for item in inputs
        if "sql/updates/mangos" in item.path.as_posix()
    ]
    assert [p.name for p in core] == ["s2492_01_mangos_gobject_near_link.sql"]
    acid = [item.path.name for item in inputs if item.path.parent.name == "ACID"]
    assert acid == ["acid_tbc.sql"]
    bots_flavor_dir = [
        item.path for item in inputs if "sql/world/tbc" in item.path.as_posix()
    ]
    assert bots_flavor_dir, "the shared multi-expansion bots pin contributes sql/world/tbc"
    assert not [item for item in inputs if "sql/world/classic" in item.path.as_posix()]
    assert not [item for item in inputs if "sql/world/wotlk" in item.path.as_posix()]


def test_wotlk_selection_appends_nothing_above_the_integrated_chain():
    """Lanes item 5: the wotlk dump sits at 14092 but the DB repo's own
    Updates carry the whole 14093-14104 core-sync chain (5781, 5795, 5808,
    5816, 5825, 5839, 5862, 5864 — no 14097, that one is characters), so the
    honest cutoff appends NO core mangos file; replaying 14093+ would fail
    on db_version columns those updates already renamed."""
    inputs = migrations.select_inputs("wotlk")
    assert not [
        item for item in inputs if "sql/updates/mangos" in item.path.as_posix()
    ], "the wotlk Updates already integrate the core floor"
    names = [item.path.name for item in inputs]
    for chain in (
        "5781_14093_01_mangos_vehicle_ridespell.sql",
        "5795_core_sync.sql",
        "5808_14095_01_mangos_dbscript_breaking_change.sql",
        "5816_14096_01_mangos_closing_text.sql",
        "5825_14098_01_mangos_wmogroupgen.sql",
        "5839_Core-14099_14101.sql",
        "5862_cls_rework.sql",
        "5864_Core-14102_14104.sql",
    ):
        assert chain in names, f"the wotlk core-sync chain must be staged: {chain}"
    acid = [item.path.name for item in inputs if item.path.parent.name == "ACID"]
    assert acid == ["acid_wotlk.sql"]
    assert [item for item in inputs if "sql/world/wotlk" in item.path.as_posix()]


def test_vanilla_selection_keeps_the_historical_shape():
    """Byte-identity at the selection level: vanilla still appends only the
    z-revisions above Classic-DB's integrated z2829, keeps acid_classic.sql,
    and draws its bots SQL from sql/world/classic."""
    inputs = migrations.select_inputs("vanilla")
    core = [item.path.name for item in inputs if "sql/updates/mangos" in item.path.as_posix()]
    assert core and all(int(name[1:5]) > 2829 for name in core)
    assert "z2830_01_mangos_icon_name.sql" in core
    acid = [item.path.name for item in inputs if item.path.parent.name == "ACID"]
    assert acid == ["acid_classic.sql"]
    assert [item for item in inputs if "sql/world/classic" in item.path.as_posix()]


def test_flavor_databases_resolve_from_the_manifest():
    tbc = migrations.flavor_databases("tbc")
    assert tbc["world"] == "tbcmangos" and tbc["playerbot-world"] == "tbcmangos"
    assert tbc["realm"] == "tbcrealmd" and tbc["characters"] == "tbccharacters"
    wotlk = migrations.flavor_databases("wotlk")
    assert wotlk["world"] == "wotlkmangos" and wotlk["logs"] == "wotlklogs"
    assert migrations.flavor_databases("vanilla") == migrations.DATABASES


def test_flavor_databases_refuse_an_unknown_flavor():
    with pytest.raises(RuntimeError, match="not found in schemas/flavor.json"):
        migrations.flavor_databases("nosuchflavor")


# ---------------------------------------------------------------------------
# The committed ledgers (schemas/database-migrations-{tbc,wotlk}.json)
# ---------------------------------------------------------------------------

def _ledger(flavor: str) -> dict:
    path = ROOT / f"schemas/database-migrations-{flavor}.json"
    assert path.is_file(), (
        f"{path} is missing — run python tools/stage_database_migrations.py "
        f"--flavor {flavor}")
    return json.loads(path.read_text(encoding="utf-8"))


def _entry_for(ledger: dict, suffix: str) -> dict:
    matches = [e for e in ledger["entries"] if e["source_path"].endswith(suffix)]
    assert len(matches) == 1, suffix
    return matches[0]


@pytest.mark.parametrize("flavor", ["tbc", "wotlk"])
def test_committed_ledger_carries_the_core_floors(flavor):
    ledger = _ledger(flavor)
    assert ledger["schema"] == 1
    assert ledger["expected_revisions"] == migrations.expected_revisions(flavor)
    assert ledger["expected_revisions"]["world"] == _header_define(flavor, "MANGOS")
    # The C5-in-fact correction: no commit-history ACID provenance block.
    assert "acid" not in ledger["source_commits"], (
        "the pinned DB repos are single-commit squashed imports; ACID "
        "provenance is the classic_db HEAD pin, not an acid_commits block")
    assert set(ledger["source_commits"]) == {"cmangos", "classic_db", "playerbots"}


def test_committed_tbc_ledger_hashes_the_s2492_catch_up():
    ledger = _ledger("tbc")
    entry = _entry_for(ledger, "s2492_01_mangos_gobject_near_link.sql")
    source = ROOT / "native/cmangos-tbc/sql/updates/mangos/s2492_01_mangos_gobject_near_link.sql"
    assert entry["sql_sha256"] == hashlib.sha256(source.read_bytes()).hexdigest()
    assert entry["database"] == "tbcmangos"
    assert entry["component"] == "world"
    # The dump it repairs: the Full_DB seed from the manifest pin.
    seed = _entry_for(ledger, "TBCDB_1.11.0_Vengeance_One_A_Cmangos_Story.sql.gz")
    assert seed["sql_sha256"] == hashlib.sha256(
        migrations.sql_bytes(ROOT / "native/classic-db-tbc/Full_DB/"
                             "TBCDB_1.11.0_Vengeance_One_A_Cmangos_Story.sql.gz")
    ).hexdigest()


def test_committed_wotlk_ledger_ends_at_the_core_floor_via_its_updates():
    ledger = _ledger("wotlk")
    assert not [
        e for e in ledger["entries"] if "sql/updates/mangos" in e["source_path"]
    ]
    entry = _entry_for(ledger, "5864_Core-14102_14104.sql")
    source = ROOT / "native/classic-db-wotlk/Updates/5864_Core-14102_14104.sql"
    assert entry["sql_sha256"] == hashlib.sha256(source.read_bytes()).hexdigest()
    acid = _entry_for(ledger, "acid_wotlk.sql")
    assert acid["database"] == "wotlkmangos"


@pytest.mark.parametrize("flavor", ["tbc", "wotlk"])
def test_committed_ledger_matches_a_fresh_selection(flavor):
    """Regeneration determinism: the committed ledger's entry count and
    source paths are exactly what select_inputs produces from the pinned
    checkouts (values drift with pins; the mapping must not)."""
    ledger = _ledger(flavor)
    inputs = migrations.select_inputs(flavor)
    assert len(ledger["entries"]) == len(inputs)
    assert [e["source_path"] for e in ledger["entries"]] == [
        item.path.relative_to(ROOT).as_posix() for item in inputs
    ]


def test_vanilla_golden_pair_untouched_by_the_flavor_ledgers():
    """The ask's seal-critical invariant: the vanilla alias and its canonical
    byte copy still hash to the G3-3 golden after the per-flavor lanes
    landed (deeper pin in tests/test_check_ledger_golden.py and the S1
    byte-identity test)."""
    import check_ledger_golden  # noqa: E402
    assert check_ledger_golden.validate(ROOT) == []


def test_ledger_revision_marker_dialects_are_disjoint():
    """Each flavor's marker matches exactly its own dialect — a z-rev never
    parses as an s-rev or a numeric rev, so cutoffs cannot cross flavors."""
    samples = {
        "vanilla": "required_z2829_01_mangos_proc_cooldown",
        "tbc": "required_s2491_01_mangos_spillover_rename",
        "wotlk": "required_14092_01_mangos_proc_cooldown",
    }
    for flavor, marker in samples.items():
        rule = migrations._FLAVOR_RULES[flavor]
        assert [m.group(1) for m in rule.revision_marker.finditer(marker)]
    for flavor, marker in samples.items():
        rule = migrations._FLAVOR_RULES[flavor]
        for other_flavor, other_marker in samples.items():
            if other_flavor == flavor:
                continue
            assert not list(rule.revision_marker.finditer(other_marker)), (
                f"{flavor} marker must not match {other_flavor}'s {other_marker}")
