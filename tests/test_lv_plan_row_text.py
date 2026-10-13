"""Unit tests pinning the corrected LIVE-VERIFICATION-PLAN.md row text for
the four ledger-hygiene row-text defects (LVF-flavor-import-001,
LVF-server-db-008, LVF-desktop-app-001, LVF-server-db-007): the plan's pass
conditions must quote what the product/tool/tests actually do, so each test
asserts BOTH the corrected doc text (no regression to the stale wording) and
the ground truth it cites (the real tool output / real Kotlin sources).

Pinned rows:
- LV-flavor-import-09: the vanilla --list-surfaces count is 8 (6 core-tree +
  2 bots-tree, bots pair enumerated) and matches the tool's real output;
  tbc refuses rc=2 with the quoted prefix;
- LV-server-db-19: the G3-9 coverage citation points at the android lane's
  DatabaseSnapshotFlavorShimTest + DatabaseSnapshotStoreTest (the desktop
  DesktopSnapshotsRestoreTest holds zero flavor references);
- LV-desktop-app-08: the [JSH] store probe uses the real save API
  `st.save(d)` (InputProfile.kt) — `saveStored` exists on no InputProfileStore;
- LV-server-db-14: leg 8 asserts byte-identity on the two SEAL markers only
  (migration seal + generation marker) with the journal.json / clean-stop
  rewrite-by-design carve-out.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PLAN = ROOT / "docs" / "LIVE-VERIFICATION-PLAN.md"
DESKTOP_INPUT_PROFILE = ROOT / "desktop/src/main/kotlin/com/pocketrealm/client/InputProfile.kt"
SNAPSHOT_STORE = ROOT / "android/app/src/main/java/com/pocketrealm/database/DatabaseSnapshotStore.kt"
SNAPSHOT_SHIM_TEST = ROOT / "android/app/src/test/java/com/pocketrealm/database/DatabaseSnapshotFlavorShimTest.kt"
SNAPSHOT_STORE_TEST = ROOT / "android/app/src/test/java/com/pocketrealm/database/DatabaseSnapshotStoreTest.kt"
RESTORE_TEST = ROOT / "desktop/src/test/kotlin/com/pocketrealm/desktop/DesktopSnapshotsRestoreTest.kt"
SQLITE_SEALS = ROOT / "desktop/src/main/kotlin/com/pocketrealm/database/DesktopSqliteSeals.kt"


def plan_row(test_id: str) -> str:
    lines = [
        line
        for line in PLAN.read_text(encoding="utf-8").splitlines()
        if line.startswith(f"| {test_id} |")
    ]
    assert len(lines) == 1, f"expected exactly one {test_id} row, got {len(lines)}"
    return lines[0]


def test_flavor_import_09_row_quotes_the_real_surface_listing() -> None:
    row = plan_row("LV-flavor-import-09")
    assert "listing 8 dist surfaces" in row
    assert "listing 6 dist surfaces" not in row
    # the bots-tree pair is enumerated, not lumped into the core-tree count
    assert "2 bots-tree (playerbots/aiplayerbot, playerbots/ahbot)" in row
    # and the row's count matches the tool's real deterministic output
    proc = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "generate_conf_matrix.py"),
         "--flavor", "vanilla", "--list-surfaces"],
        cwd=ROOT, capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr
    surfaces = [line for line in proc.stdout.splitlines() if line.strip()]
    assert len(surfaces) == 8, surfaces
    core = [s for s in surfaces if s.startswith("native/cmangos/")]
    bots = [s for s in surfaces if s.startswith("native/playerbots/")]
    assert len(core) == 6 and len(bots) == 2, surfaces
    assert any(s.endswith("playerbots/playerbot/aiplayerbot.conf.dist.in") for s in bots)
    assert any(s.endswith("playerbots/ahbot/ahbot.conf.dist.in") for s in bots)
    # tbc leg: with the six flavor submodules materialized (W0 item 4,
    # 2026-10-08) the tbc lane lists ITS OWN 7 surfaces - 5 core-tree (the
    # tbc tree ships no mangosd/mods conf, unlike vanilla's 6) + the same
    # bots-tree pair - the row quotes that reality, replacing the pre-W0
    # unmaterialized-submodule refusal
    proc = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "generate_conf_matrix.py"),
         "--flavor", "tbc", "--list-surfaces"],
        cwd=ROOT, capture_output=True, text=True,
    )
    assert proc.returncode == 0, proc.stderr
    tbc_surfaces = [line for line in proc.stdout.splitlines() if line.strip()]
    assert len(tbc_surfaces) == 7, tbc_surfaces
    tbc_core = [s for s in tbc_surfaces if s.startswith("native/cmangos-tbc/")]
    tbc_bots = [s for s in tbc_surfaces if s.startswith("native/playerbots-tbc/")]
    assert len(tbc_core) == 5 and len(tbc_bots) == 2, tbc_surfaces
    assert "tbc rc=0 listing 7 dist surfaces" in row
    assert "2 bots-tree (playerbots-tbc/aiplayerbot, playerbots-tbc/ahbot)" in row


def test_server_db_19_row_cites_the_android_flavor_lane_tests() -> None:
    row = plan_row("LV-server-db-19")
    assert "com.pocketrealm.database.DatabaseSnapshotFlavorShimTest" in row
    assert "com.pocketrealm.database.DatabaseSnapshotStoreTest" in row
    assert ":app:testDebugUnitTest -PpocketAbi=x86_64" in row
    # the stale desktop citation must not come back as the coverage location
    assert "--tests com.pocketrealm.desktop.DesktopSnapshotsRestoreTest" not in row
    # ground truth: the pin classes exist and carry the G3-9 legs
    shim = SNAPSHOT_SHIM_TEST.read_text(encoding="utf-8")
    assert "checkSnapshotFlavorMatchesLane" in SNAPSHOT_STORE.read_text(encoding="utf-8")
    # W4 flip: the cross-flavor refusal leg now uses a synthetic foreign
    # flavor id (tbc is QUALIFIED, so a tbc snapshot against the tbc lane
    # would legitimately restore); the leg, its class, and its test count
    # are unchanged.
    assert "a foreign-flavor snapshot refuses the active lane naming both flavors" in shim
    assert SNAPSHOT_STORE_TEST.read_text(encoding="utf-8").count("@Test") == 3
    assert shim.count("@Test") == 4
    # the desktop restore class carries the flip's persisted-pick fixture
    # (the restore gate resolves the lane from the pinned boot lane), so
    # the former "holds no flavor pins" ground truth is retired with D72.
    assert "selectedFlavorId" in RESTORE_TEST.read_text(encoding="utf-8")


def test_desktop_app_08_probe_uses_the_real_save_api() -> None:
    row = plan_row("LV-desktop-app-08")
    assert "st.save(d)" in row
    # the probe itself (the mechanism cell) must use the real API; the old
    # name may appear only once, quoted inside the corrective annotation
    mechanism = row.split("|")[4]
    assert "st.save(d)" in mechanism
    assert "saveStored" not in mechanism
    assert row.count("st.saveStored(d)") == 1
    assert "names a method on no InputProfileStore" in row
    # ground truth: the desktop InputProfileStore's save API is save(profile)
    source = DESKTOP_INPUT_PROFILE.read_text(encoding="utf-8")
    assert "fun save(profile: InputProfile)" in source
    assert "fun saveStored(" not in source


def test_server_db_14_leg8_byte_identity_covers_the_two_seal_markers_only() -> None:
    row = plan_row("LV-server-db-14")
    # the unsatisfiable blanket claim is gone
    assert "journal + markers byte-identical to backup" not in row
    assert "journal.json and `.pocketrealm-clean-stop.json` are rewritten by design" in row
    # byte-identity is asserted on the two SEAL markers only
    assert "`.pocketrealm-migrations.json` migration seal" in row
    assert "`.pocketrealm-generation.json` generation marker" in row
    # ground truth: the marker names + the clean-stop timestamp field
    seals = SQLITE_SEALS.read_text(encoding="utf-8")
    assert 'MIGRATION_MARKER_NAME = ".pocketrealm-migrations.json"' in seals
    assert 'CLEAN_MARKER_NAME = ".pocketrealm-clean-stop.json"' in seals
    assert 'GENERATION_MARKER_NAME = ".pocketrealm-generation.json"' in seals
    assert "{schema, provider, stoppedAt}" in seals
