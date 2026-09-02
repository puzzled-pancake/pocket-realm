"""The S7 truth-guard battery (A10/A11/A12 host gate).

Compiles the SHIPPED pure core (native/patches/playerbots/
PlayerbotLlmTruthCore.h) on the host with -std=c++11 - like the json
client and act-tool batteries, the host always tests the shipped code,
never a copy - and runs its legs: entity-guard extraction/stakes shapes,
the frozen directive wording, the lore index (jsonl load, scoring, POI
resolution, the era lint over the SHIPPED asset), the corrected era
lists, and every A12 filter. The world-side glue (the in-tree known-name
resolver, the question path in BuildNoteInner, the filter call sites in
Generate, the splitter anchor) is pinned by source-contract assertions
below, following test_llm_a0_unification.py's pattern: the host cannot
drive PlayerbotAI or the DB layer, so the contract the world thread
implements is pinned instead, and the desktop/device batteries record
the rest.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "PlayerbotLlmTruthCore.h"
HARNESS = ROOT / "tools" / "test_llm_truth.cpp"
BRIDGE_CPP = PATCHES / "PlayerbotLlmBridge.cpp"
BRIDGE_H = PATCHES / "PlayerbotLlmBridge.h"
TOOLS_CPP = PATCHES / "PlayerbotLlmTools.cpp"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
FILTERS_CPP = PATCHES / "PlayerbotLlmFilters.cpp"
FILTERS_H = PATCHES / "PlayerbotLlmFilters.h"
ASSET = ROOT / "android" / "app" / "src" / "main" / "assets" / "lore" / "lore_cards_v112.jsonl"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"


@pytest.fixture(scope="session")
def truth_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    if not CORE.is_file() or not HARNESS.is_file():
        pytest.skip("truth core not staged")
    (ROOT / "tmp").mkdir(exist_ok=True)  # the harness writes its fixture there
    tmp = tmp_path_factory.mktemp("truth")
    # run from the repo root so the shipped-asset lint leg resolves
    exe = shutil.copy(ROOT / "tools" / "test_llm_truth.cpp", tmp / "harness.cpp")
    out = tmp / "truth_host.exe"
    compile = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall",
         "-I", str(PATCHES), "-o", str(out), str(exe)],
        capture_output=True, text=True, cwd=str(ROOT))
    assert compile.returncode == 0, compile.stderr[:2000]
    return out


def _run(binary, *args):
    return subprocess.run([str(binary), *args], capture_output=True,
                          text=True, cwd=str(ROOT))


def test_host_battery_legs(truth_binary):
    result = _run(truth_binary)
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "all checks passed" in result.stdout


def test_shipped_lore_asset_lints_clean(truth_binary):
    # the era lint runs inside the binary only when the asset is present;
    # assert it actually ran (a stripped checkout must not pass silently)
    assert ASSET.is_file(), "the lore card asset must be committed"
    result = _run(truth_binary)
    assert "not present - skipped" not in result.stdout, (
        "the shipped-asset lint leg did not run")


# ---- source-contract pins (the in-tree glue the host cannot drive) --------


def test_guard_merges_not_defers_in_the_bridge():
    text = BRIDGE_CPP.read_text(encoding="utf-8")
    # the question path runs BEFORE the ladder and lands in note.extra
    assert "guardExtra" in text
    # S8: the merge is concat-aware (a recall beat's cargo may already
    # ride the extra leg - guard second) but still an assignment, never
    # a deferral
    assert 'note.extra = note.extra.empty() ? guardExtra : note.extra + "\\n" + guardExtra' in text, (
        "the A10 directive must MERGE into the beat note (merge-not-defer)")
    # exactly one guard per turn: the directive assignment is followed
    # by the loop break (the FIRST unresolved candidate wins)
    tail = text.split("guardExtra = pocketllm::GuardDirective")[1][:200]
    assert "break;" in tail
    # the directive is the frozen wording from the pure core, never inline
    assert "You have never heard of" not in text, (
        "the frozen directive text lives in PlayerbotLlmTruthCore.h only")
    # the resolver is shared with the A12 invention post-filter
    assert "PlayerbotLlmBridge::IsKnownName" in FILTERS_CPP.read_text(encoding="utf-8")


def test_guard_skips_event_turns_and_uses_frozen_wording():
    text = BRIDGE_CPP.read_text(encoding="utf-8")
    inner = text.split("PlayerbotLlmBridge::Note BuildNoteInner(Player* bot")[1][:4000]
    assert "if (state.eventTurn)" in inner
    # the event block returns before the question path is reached
    assert inner.index("if (state.eventTurn)") < inner.index("IsQuestionShape")
    core = CORE.read_text(encoding="utf-8")
    assert "You have never heard of " in core
    # the directive is C++ line-wrapped; pin the fragments byte-exactly
    assert '" trades or lives here. Tell him plainly you do not know the "' in core
    assert '"name, and ask what he means.";' in core, (
        "the A10 directive wording is frozen (plan section 5 P45 lock)")


def test_lore_card_rides_the_trained_user_turn():
    text = MEMORY_CPP.read_text(encoding="utf-8")
    assert "events, results," in text, (
        "the trained user turn renders the [RESULT] card through "
        "ComposeUserTurn's results leg")
    assert "note.result" in text
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    assert "results.push_back(note.result)" in bridge, (
        "the legacy path renders the card through the trained head too")


def test_era_bias_splice_and_filter_stack_wired_into_generate():
    driver = DRIVER.read_text(encoding="utf-8")
    generate = driver.split('PB_LLM_IFACE_CPP_ANDROID = """')[1].split('"""')[0]
    assert "PlayerbotLlmFilters::EraBiasJson()" in generate
    assert "PocketLlmVoiceFilter(envelope.content" in generate
    assert "LeakFailureRaw" in generate
    assert "Do not repeat your last reply; say something new." in generate
    # the leak check runs BEFORE extraction: a rejected reply leaves no
    # queued tool calls
    assert generate.index("LeakFailureRaw(content, body)") < \
        generate.index("ExtractAndQueue(content, botGuid, speakerGuid")


def test_say_splitter_anchor_pinned_at_255():
    driver = DRIVER.read_text(encoding="utf-8")
    android = driver.split('PB_SAY_SPLITTER_ANDROID = """')[1].split('"""')[0]
    assert "sentence.length() > 255" in android
    assert "sentence.rfind(' ', 255)" in android
    assert "never cut inside a multibyte sequence" in android
    upstream = driver.split('PB_SAY_SPLITTER_UPSTREAM = """')[1].split('"""')[0]
    assert "sentence.length() > 200" in upstream
    # the backoff code itself, not just the comment (round-2: deleting
    # the loops while keeping the comment used to pass)
    assert "0x80" in android and "0xC0" in android, (
        "the UTF-8 back-off loops must stay in the production splitter")


def test_request_side_utf8_gate_is_at_the_escaper():
    jsonh = (PATCHES / "PlayerbotLlmJson.h").read_text(encoding="utf-8")
    assert "strict-UTF-8 request-side gate" in jsonh
    assert "SanitizeUtf8" in jsonh


def test_lore_conf_keys_exist():
    driver = DRIVER.read_text(encoding="utf-8")
    assert 'AiPlayerbot.LLMLoreFile' in driver
    assert 'AiPlayerbot.LLMEraBias' in driver
    header = driver.split('PB_LLM_CONFIG_HEADER_ANDROID = """')[1].split('"""')[0]
    assert "llmLoreFile" in header
    assert "llmEraBias" in header
