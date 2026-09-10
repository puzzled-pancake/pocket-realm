"""The MSVC pure-core lane gate: runs scripts/smoke_win_msvc.py on Windows.

The g++-backed host batteries (test_llm_*.py) remain the historical lane
(and what ubuntu CI runs); this is the Windows-native compiler lane so a
pure-MSVC box still executes the shipped pure cores instead of silently
skipping — same dual-lane arrangement as the database providers. The
behavioral pin (banter golden FNV-1a64) is shared by both lanes.
"""

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SMOKE = ROOT / "scripts" / "smoke_win_msvc.py"
PINNED_GOLDEN = "de4bd8227a3ab0d1"


def test_smoke_script_pins_the_golden_hash():
    text = SMOKE.read_text(encoding="utf-8")
    assert f'"{PINNED_GOLDEN}"' in text, (
        "the MSVC lane must keep checking the pinned banter golden hash"
    )


def test_msvc_pure_core_lane_passes():
    if sys.platform != "win32":
        pytest.skip("windows-only compiler lane")
    result = subprocess.run(
        [sys.executable, str(SMOKE)],
        # 8 batteries, each a cl compile + run bounded at 300 s inside the
        # smoke; a slow box must get battery results, not a TimeoutExpired
        # false negative.
        capture_output=True, text=True, timeout=2700, cwd=str(ROOT))
    assert result.returncode == 0, result.stdout + result.stderr[-2000:]
    assert "msvc pure-core smoke: all batteries passed" in result.stdout
    assert "PASS banter_core" in result.stdout
