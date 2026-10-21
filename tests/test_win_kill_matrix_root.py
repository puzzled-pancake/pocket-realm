"""W7 code-red W7-R2-3: the kill-matrix diagnostic's product-root reads
(journal_state / live_wal_sidecars) must resolve through
tools.common.product_data_root() — POCKET_REALM_DATA_ROOT override first —
never a hard-coded OS-user-profile construction. Before the fix both legs
spelled Path.home()/AppData/Local/PocketRealm and silently read a
nonexistent tree after relocation (journal None, WAL list empty), so kill
decisions were made against the wrong root.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from tools import win_kill_matrix  # noqa: E402


def test_product_root_reads_honor_the_override(tmp_path, monkeypatch):
    """With POCKET_REALM_DATA_ROOT pointed at a planted tmp root,
    journal_state() reports the planted journal and live_wal_sidecars()
    lists the planted sidecars — proving both legs read the override root,
    not the OS user profile."""
    root = tmp_path / "relocated-root"
    journal_dir = root / "realm" / "runtime-supervisor"
    journal_dir.mkdir(parents=True)
    (journal_dir / "journal.json").write_text(
        json.dumps({
            "clean": False, "phase": "RUNNING",
            "lastDurableAction": "boot-committed",
        }),
        encoding="utf-8",
    )
    datadir = root / "database" / "sqlite-datadir"
    datadir.mkdir(parents=True)
    (datadir / "worlddb-wal").write_bytes(b"\x00" * 16)
    (datadir / "chardb-wal").write_bytes(b"\x00" * 16)

    monkeypatch.setenv("POCKET_REALM_DATA_ROOT", str(root))

    state = win_kill_matrix.journal_state()
    assert state == {
        "clean": False,
        "phase": "RUNNING",
        "lastDurableAction": "boot-committed",
    }, "journal_state() did not read the override root's journal.json"

    sidecars = win_kill_matrix.live_wal_sidecars()
    assert sorted(sidecars) == [
        "chardb-wal", "worlddb-wal",
    ], "live_wal_sidecars() did not read the override root's datadir"


def test_missing_override_root_is_absent_not_wrong(tmp_path, monkeypatch):
    """An override root without the trees reports absence (None / []) —
    the honest 'not present here' answer, never a fallback to another
    root."""
    monkeypatch.setenv(
        "POCKET_REALM_DATA_ROOT", str(tmp_path / "empty-root"))
    assert win_kill_matrix.journal_state() is None
    assert win_kill_matrix.live_wal_sidecars() == []
