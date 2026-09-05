"""The banter battery: proves the playerbot LLM companion's deterministic
voice layer never gets repetitive, never leaks protocol, never crashes.

Compiles the SHIPPED header (native/patches/playerbots/llm_banter_core.h)
on the host with -std=c++11 (the oldest dialect the game build uses) and
runs three legs: content invariants, a 500k-turn 1000-hour simulation of
200 bots x 50 players, and a 200k-iteration adversarial fuzz of the marker
neutering, line rendering, and selection under corrupted state.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "llm_banter_core.h"
HARNESS = ROOT / "tools" / "test_llm_banter_core.cpp"


@pytest.fixture(scope="session")
def banter_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    if not CORE.is_file() or not HARNESS.is_file():
        pytest.skip("banter core not staged")
    tmp = tmp_path_factory.mktemp("banter")
    exe = tmp / "banter_test.exe"
    build = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall", "-I", str(PATCHES),
         "-o", str(exe), str(HARNESS)],
        capture_output=True, text=True, timeout=300)
    if build.returncode != 0:
        pytest.fail(f"host compile failed:\n{build.stderr[:4000]}")
    return exe


def _run(banter_binary, *args):
    return subprocess.run([str(banter_binary), *args],
                          capture_output=True, text=True, timeout=300)


# ------------------------------------------------------------ invariants ----
def test_invariants_leg(banter_binary):
    result = _run(banter_binary, "invariants")
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "banter invariants passed" in result.stdout


def test_invariants_golden_pin_is_stable(banter_binary):
    # The RNG/selection fingerprint: printed by the invariants leg. Any
    # deliberate change to pools or selection updates this pin in the SAME
    # commit - an accidental change must fail here instead. (The hash is
    # FNV-1a64 over 4x500 deterministic selections of the shipped KILL
    # pool, so the pin is stable across machines and compilers.)
    GOLDEN_FNV1A64 = "de4bd8227a3ab0d1"
    result = _run(banter_binary, "invariants")
    golden = [l for l in result.stdout.splitlines() if l.startswith("golden_fnv1a64=")]
    assert golden, "golden fingerprint line missing"
    assert golden[0] == f"golden_fnv1a64={GOLDEN_FNV1A64}", (
        f"the selection/pool fingerprint drifted from the committed pin "
        f"({GOLDEN_FNV1A64}); if the pool or selection change was "
        "deliberate, re-pin this constant in the same commit")


def test_core_content_contract():
    text = CORE.read_text(encoding="utf-8")
    # No protocol bytes in any authored line (checked mechanically on the
    # host too, but the source itself must not smuggle newlines into pools).
    assert "<<log_fact" not in text.split("namespace detail")[1].split("} // namespace detail")[0]
    # The corpus is substantive: this is the anti-repetition substance.
    for pool in ("kBusy", "kGreetTrusted", "kKill", "kLootRare", "kIdle",
                 "kPhilo", "kSilence", "kMoodBored", "kWildcard"):
        assert pool in text
    assert text.count('",') > 150, "the authored corpus shrank below its size contract"


# ------------------------------------------------------------------- sim ----
@pytest.fixture(scope="session")
def sim_json(banter_binary):
    result = _run(banter_binary, "sim", "500000")
    assert result.returncode == 0, result.stderr[:2000]
    lines = [l for l in result.stdout.splitlines() if l.startswith("{")]
    assert lines, "sim produced no JSON"
    return json.loads(lines[-1])


def test_sim_answered_everything_without_suppression_wedge(sim_json):
    # The persona layer must answer when off-cooldown; suppression is the
    # fall-through signal, bounded well under half of all draws.
    assert sim_json["draws"] == 500000
    assert sim_json["answered"] + sim_json["suppressed"] == 500000
    assert sim_json["suppressed"] / 500000 < 0.5


def test_sim_wildcard_rate_in_design_band(sim_json):
    # Rare sanctioned chaos: 1% design, accepted band 0.2%-3%. Rare enough
    # to surprise at hour 1000, common enough to ever be seen.
    assert 0.002 < sim_json["wildcard_rate"] < 0.03


def test_sim_never_immediate_repeat(sim_json):
    # The core anti-repetition property: the worst stream anywhere in the
    # 1000-hour simulation must not repeat a line back-to-back. (Exact
    # consecutive repeats were the old 2-variant bug.) The harness reports
    # the 1-based draw position of the earliest back-to-back repeat across
    # all streams, or the 0xFFFFFFFF sentinel when no stream ever repeated.
    assert sim_json["worst_repeat_gap"] == 0xFFFFFFFF, (
        "a stream repeated a line back-to-back at draw "
        f"{sim_json['worst_repeat_gap']}")


def test_sim_sliding_window_variety(sim_json):
    # Any 16 consecutive picks from one stream must contain at least 10
    # distinct lines - no clumping onto a favorite subset.
    assert sim_json["worst_window16_distinct"] >= 10


def test_sim_no_dominant_favorite(sim_json):
    # Novelty weighting must not degenerate into one line eating the pool.
    assert sim_json["worst_index_share"] < 0.35


def test_sim_is_deterministic(banter_binary):
    first = _run(banter_binary, "sim", "20000")
    second = _run(banter_binary, "sim", "20000")
    fa = [l for l in first.stdout.splitlines() if l.startswith("{")]
    fb = [l for l in second.stdout.splitlines() if l.startswith("{")]
    assert fa and fa == fb, "the simulation is not reproducible"


# ------------------------------------------------------------------ fuzz ----
def test_fuzz_leg(banter_binary):
    result = _run(banter_binary, "fuzz", "200000")
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    lines = [l for l in result.stdout.splitlines() if l.startswith("{")]
    data = json.loads(lines[-1])
    assert data["neuter_leaks"] == 0, "protocol markers survived neutering"
    assert data["render_overflows"] == 0
    assert data["select_oob"] == 0, "selection returned an OOB/empty line"
    assert data["tic_double"] == 0
