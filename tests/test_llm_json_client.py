"""The A9 real-JSON response-client battery (S2 gate).

Compiles the SHIPPED header (native/patches/playerbots/PlayerbotLlmJson.h)
on the host with -std=c++11 - like the banter battery, the host always
tests the shipped code, never a copy - and runs three legs:

* invariants: envelope edge cases (null/missing/non-string content,
  reasoning_content, finish_reason variants, the legacy completions text
  shape, error envelopes, pretty printing, \\uXXXX + surrogate decoding,
  strict raw-control-char refusal), the empty-content retry splice, and
  the truncation tail trim.
* battery: the A9 acceptance gate - 100 generated replies carrying escaped
  quotes, newlines, tabs, raw multi-byte UTF-8, backslashes and keyed tool
  markers must parse with ZERO failures and byte-exact content. The same
  replies run through a replica of the old regex extraction so the output
  documents the failure classes the JSON path removes.
* fuzz: 50k adversarial mutations of valid envelopes and request bodies -
  the parser never crashes, a refused splice never touches the body, and a
  successful splice is always a pure insertion that re-parses.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "PlayerbotLlmJson.h"
HARNESS = ROOT / "tools" / "test_llm_json_client.cpp"


@pytest.fixture(scope="session")
def json_client_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    if not CORE.is_file() or not HARNESS.is_file():
        pytest.skip("json client core not staged")
    tmp = tmp_path_factory.mktemp("jsonclient")
    exe = tmp / "json_client_test.exe"
    build = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall", "-I", str(PATCHES),
         "-o", str(exe), str(HARNESS)],
        capture_output=True, text=True, timeout=300)
    if build.returncode != 0:
        pytest.fail(f"host compile failed:\n{build.stderr[:4000]}")
    return exe


def _run(json_client_binary, *args):
    return subprocess.run([str(json_client_binary), *args],
                          capture_output=True, text=True, timeout=300)


# ------------------------------------------------------------ invariants ----
def test_invariants_leg(json_client_binary):
    result = _run(json_client_binary, "invariants")
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "json client invariants passed" in result.stdout


def test_core_content_contract():
    text = CORE.read_text(encoding="utf-8")
    # The shipped contract: real JSON semantics, the regex fallback stays
    # for non-OpenAI text shapes (gated by a voicing plausibility check),
    # and the header stays dependency-free so the host battery compiles
    # the shipped bytes.
    for anchor in ("choices[0].message.content", "reasoning_content",
                   "AppendInstructionToLastUserMessage", "TrimTruncatedTail",
                   "finish_reason", "LooksLikeVoicableText"):
        assert anchor in text, f"core contract anchor missing: {anchor}"


# -------------------------------------------------------------- battery ----
def test_battery_100_generations_zero_parse_failures(json_client_binary):
    # THE A9 GATE: 100 generations with escaped quotes/newlines/unicode -
    # zero parse failures on the JSON path. The regex-failure count is
    # reported (baseline evidence) but only the JSON path gates the stage.
    result = _run(json_client_binary, "battery", "100")
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    lines = [l for l in result.stdout.splitlines() if l.startswith("{")]
    assert lines, "battery produced no JSON"
    data = json.loads(lines[-1])
    assert data["generations"] == 100
    assert data["json_failures"] == 0, (
        f"A9 gate failed: {data['json_failures']} parse failures")
    # the adversarial classes must actually be exercised, or the gate is
    # vacuous: truncated replies, tool-marker replies, and \uXXXX-escaped
    # envelopes (surrogate pairs included) present
    assert data["truncated"] > 0
    assert data["tool_replies"] > 0
    assert data["escaped"] > 0
    # the OLD path demonstrably fails these (the documented motivation)
    assert data["regex_failures"] > 0
    assert "json client battery passed" in result.stdout


def test_battery_is_deterministic(json_client_binary):
    first = _run(json_client_binary, "battery", "100")
    second = _run(json_client_binary, "battery", "100")
    fa = [l for l in first.stdout.splitlines() if l.startswith("{")]
    fb = [l for l in second.stdout.splitlines() if l.startswith("{")]
    assert fa and fa == fb, "the battery is not reproducible"


# ------------------------------------------------------------------ fuzz ----
def test_fuzz_leg(json_client_binary):
    result = _run(json_client_binary, "fuzz", "50000")
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    lines = [l for l in result.stdout.splitlines() if l.startswith("{")]
    assert lines, "fuzz produced no JSON"
    data = json.loads(lines[-1])
    assert data["iterations"] == 50000
    assert data["mutations"] > 0
    # both outcomes are healthy: spliced (pure insertion) and refused
    # (untouched) - what is gated is the CHECK invariants inside the harness
    assert data["splice_ok"] + data["splice_refused"] == 50000
    assert "json client fuzz passed" in result.stdout
