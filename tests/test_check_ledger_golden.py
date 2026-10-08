"""Contract for tools/check_ledger_golden.py (G3-3): the vanilla migration
ledger is golden-sha pinned; the canonical vanilla ledger and the
S1 deprecation-window alias must be byte-identical to each other; a staged
manifest input (--staged) must be byte-identical to the pinned ledger; and
the vanilla ledger path set {canonical, alias} byte-identity rides the leak
sweep's findings (G3-3 item 3)."""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "check_ledger_golden.py"
SWEEP_TOOL = ROOT / "tools" / "leak_sweep.py"
sys.path.insert(0, str(ROOT / "tools"))

import check_ledger_golden  # noqa: E402
import leak_sweep  # noqa: E402

GOLDEN_BYTES = (
    b"{\n"
    b'  "schema": 1,\n'
    b'  "app_build_id": "o08-g2-mariadb-v1",\n'
    b'  "entries": []\n'
    b"}\n"
)


def run_tool(*flags: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(TOOL), *flags], capture_output=True, text=True
    )


def make_tree(tmp_path: Path, files: dict[Path, bytes]) -> Path:
    for rel, data in files.items():
        path = tmp_path / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return tmp_path


def test_real_repo_ledger_matches_the_golden():
    errors = check_ledger_golden.validate(ROOT)
    assert errors == []
    result = run_tool()
    assert result.returncode == 0
    assert "check_ledger_golden: OK" in result.stdout


def test_missing_ledger_is_an_error(tmp_path):
    errors = check_ledger_golden.validate(tmp_path)
    assert len(errors) == 1
    assert "neither" in errors[0]


def test_byte_edit_fails_naming_the_golden_sha(tmp_path):
    drifted = GOLDEN_BYTES.replace(b"schema", b"Schema")
    make_tree(tmp_path, {
        Path("schemas/database-migrations.json"): drifted,
    })
    errors = check_ledger_golden.validate(tmp_path)
    assert len(errors) == 1
    assert check_ledger_golden.GOLDEN_SHA256 in errors[0]
    assert "database-migrations.json" in errors[0]


def test_canonical_and_alias_must_stay_byte_identical(tmp_path):
    make_tree(tmp_path, {
        Path("schemas/database-migrations-vanilla.json"): GOLDEN_BYTES,
        Path("schemas/database-migrations.json"): GOLDEN_BYTES + b"\n",
    })
    errors = check_ledger_golden.validate(tmp_path)
    # Both names hash differently from the golden AND from each other: the
    # pair-identity message fires alongside the sha messages.
    assert any("byte-identical to each other" in error for error in errors)


def test_staged_manifest_must_be_byte_identical(tmp_path):
    make_tree(tmp_path, {
        Path("schemas/database-migrations.json"): GOLDEN_BYTES,
        Path("native/.build-x86_64/mariadb-staging/assets/database/migrations/manifest.json"): GOLDEN_BYTES,
    })
    staged = tmp_path / "native/.build-x86_64/mariadb-staging/assets/database/migrations/manifest.json"
    assert check_ledger_golden.validate_staged(tmp_path, staged) == []
    staged.write_bytes(GOLDEN_BYTES + b" ")
    errors = check_ledger_golden.validate_staged(tmp_path, staged)
    assert len(errors) == 1
    assert "tools/stage_database_migrations.py" in errors[0]


def test_leak_sweep_reports_a_divergent_ledger_pair(tmp_path):
    make_tree(tmp_path, {
        Path("schemas/database-migrations-vanilla.json"): GOLDEN_BYTES,
        Path("schemas/database-migrations.json"): GOLDEN_BYTES + b" ",
    })
    findings, _allowlisted, _scanned = leak_sweep.sweep(tmp_path)
    pair = [f for f in findings if f["literals"] == ["<ledger-pair-divergence>"]]
    assert len(pair) == 1
    assert "byte-identical" in pair[0]["text"]


def test_leak_sweep_stays_quiet_when_ledger_pair_is_identical_or_absent(tmp_path):
    make_tree(tmp_path, {
        Path("schemas/database-migrations-vanilla.json"): GOLDEN_BYTES,
        Path("schemas/database-migrations.json"): GOLDEN_BYTES,
    })
    findings, _allowlisted, _scanned = leak_sweep.sweep(tmp_path)
    assert not [f for f in findings if f["literals"] == ["<ledger-pair-divergence>"]]
    empty = tmp_path / "empty"
    empty.mkdir()
    findings, _allowlisted, _scanned = leak_sweep.sweep(empty)
    assert not [f for f in findings if f["literals"] == ["<ledger-pair-divergence>"]]


def test_real_repo_sweep_reports_no_ledger_pair_divergence():
    result = subprocess.run(
        [sys.executable, str(SWEEP_TOOL), "--root", str(ROOT), "--json"],
        capture_output=True, text=True,
    )
    report = json.loads(result.stdout or "{}")
    assert not [
        f for f in report.get("findings", [])
        if f.get("literals") == ["<ledger-pair-divergence>"]
    ]
