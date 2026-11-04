"""Conf-matrix freshness/parity pins (R3-1, review round 3).

The committed schemas/conf-matrix-{tbc,wotlk}.json records carried NO
standing gate: the binding to their inputs (schemas/flavor.json
conf_matrix.excluded_keys + the pinned branch conf dists) existed only at
write time inside tools/generate_conf_matrix.py — the exact D57
"bound at write time only = provenance that can rot" class, and the
drift shape D58 closed for the era chain. These pins make the committed
artifacts regenerate-identical or fail loud.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import generate_conf_matrix as gcm  # noqa: E402
from tools import flavor_layout  # noqa: E402


def _record_or_skip(flavor: str) -> dict:
    """Build the fresh record; skip LOUDLY when the flavor's conf dists
    are not checked out (the pins arrive with S2/S3 — the same posture as
    the generator's own check and the submodule-absent skip precedents)."""
    try:
        return gcm.build_record(flavor)
    except RuntimeError as exc:
        pytest.skip(f"LOUD NOTE: {flavor} conf dists unavailable - {exc}")


# The committed per-flavor set (the vanilla golden stays the G3-7
# CI-run lane and ships no committed matrix file to gate).
COMMITTED_MATRICES = ("tbc", "wotlk")


@pytest.mark.parametrize("flavor", COMMITTED_MATRICES)
def test_conf_matrix_regenerates_identically(flavor: str) -> None:
    committed = ROOT / flavor_layout.resolve(flavor).conf_matrix()
    record = _record_or_skip(flavor)
    assert committed.is_file(), (
        f"{committed.name} is missing - regenerate: python "
        f"tools/generate_conf_matrix.py --flavor {flavor}")
    fresh = json.dumps(record, indent=2) + "\n"
    assert committed.read_text(encoding="utf-8") == fresh, (
        f"{committed.name} is STALE: it no longer regenerates from the "
        "current manifest + pinned conf dists (an exclusion-set edit, a "
        "re-pin that changed dists, or a hand edit). Regenerate with "
        f"python tools/generate_conf_matrix.py --flavor {flavor}; a "
        "deliberate change lands with this pin updated in the same change")


def test_generator_check_mode_agrees_with_the_parities() -> None:
    """The --check freshness gate wired into check_repo reports exactly the
    parities these pins assert (no drift between the two consumers)."""
    errors = gcm.check()
    assert not errors, errors


def test_check_flag_runs_green_cli() -> None:
    """The documented CLI form: python tools/generate_conf_matrix.py
    --check exits 0 on the in-sync tree."""
    import subprocess

    result = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "generate_conf_matrix.py"),
         "--check"],
        capture_output=True, text=True, timeout=300,
    )
    assert result.returncode == 0, (
        f"rc={result.returncode}\nstdout: {result.stdout[-400:]}\n"
        f"stderr: {result.stderr[-400:]}")
    assert "conf-matrix check: OK" in result.stdout


# R4-1 (native-server ): the expansion trees ship a
# FLAVOR-SUFFIXED aiplayerbot dist (aiplayerbot.conf.dist.in.tbc/.wotlk)
# and the build stages THAT variant as the shipped aiplayerbot.conf.dist
# (PlayerBots/CMakeLists.txt:182-186 — the base dist is the Classic leg
# only). The matrix recorded the base dist's values (RandomBotMaxLevel 60)
# while the runtime ships 70/80: every expansion-valued key disagreed
# with the shipped conf.


@pytest.mark.parametrize("flavor,expected_max_level", (("tbc", "70"), ("wotlk", "80")))
def test_expansion_matrix_matches_the_staged_suffixed_bot_dist(
    flavor: str, expected_max_level: str
) -> None:
    """The matrix's bot values equal the flavor-suffixed dist's (the one
    configure_file stages), never the base dist's Classic values."""
    record = _record_or_skip(flavor)
    level = record["keys"].get("AiPlayerbot.RandomBotMaxLevel")
    assert level is not None, "RandomBotMaxLevel vanished from the matrix"
    assert level["default"] == expected_max_level, (
        f"{flavor} matrix records AiPlayerbot.RandomBotMaxLevel="
        f"{level['default']!r} but the staged {flavor} dist ships "
        f"{expected_max_level!r} (the flavor-suffixed aiplayerbot dist is "
        "the surface CMake stages — R4-1)"
    )
    assert level["file"] == f"aiplayerbot.conf.dist.in.{flavor}", (
        f"the matrix must record the staged suffixed dist as provenance, "
        f"got {level['file']!r}"
    )
    # and every recorded AiPlayerbot key comes from the suffixed surface
    wrong = [
        key
        for key, entry in record["keys"].items()
        if key.startswith("AiPlayerbot.") and entry["file"] != f"aiplayerbot.conf.dist.in.{flavor}"
    ]
    assert not wrong, (
        f"{len(wrong)} AiPlayerbot keys still provenance the base dist "
        f"(e.g. {wrong[:3]}) — the staged conf is the suffixed one"
    )

