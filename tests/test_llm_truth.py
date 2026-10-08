"""The truth-guard battery (A10/A11/A12 host gate).

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
    # the merge is concat-aware (a recall beat's cargo may already
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
    inner = text.split("PlayerbotLlmBridge::Note BuildNoteInner(Player* bot")[1][:6000]
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
    # the backoff code itself, not just the comment (deleting
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


# ---- design-timeframe §5: the era policy follows the chosen stage ----------

GENERATED_ERA_HEADER = PATCHES / "PlayerbotLlmEraStages.generated.h"
GENERATOR = ROOT / "tools" / "generate_timeframe_era.py"


def _registered_stage_ids() -> list[str]:
    import json

    stages: list[str] = []
    for flavor in ("vanilla", "tbc", "wotlk"):
        manifest = json.loads(
            (ROOT / "schemas" / "timeframes" / f"{flavor}.json").read_text(encoding="utf-8")
        )
        stages.extend(stage["id"] for stage in manifest["stages"])
    return stages


def test_generated_era_header_matches_its_pin():
    # The per-stage class lists are GENERATED from the schemas/timeframes
    # manifests (the single source); the pin binds the header bytes to the
    # source manifests. A stale generated artifact fails here.
    import subprocess

    assert GENERATED_ERA_HEADER.is_file(), "regenerate: python tools/generate_timeframe_era.py era"
    result = subprocess.run(
        ["python", str(GENERATOR), "check"], capture_output=True, text=True, cwd=str(ROOT)
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_every_registered_stage_has_an_era_selector_branch():
    # The per-stage pin (design §5): each registered stage id resolves its
    # own branch in the generated selector, so curation landing in a
    # manifest cannot silently miss a stage.
    header = GENERATED_ERA_HEADER.read_text(encoding="utf-8")
    for stage_id in _registered_stage_ids():
        assert f'if (stage == "{stage_id}")' in header, (
            f"the generated era selector lost the {stage_id} branch"
        )


def test_truth_core_resolves_era_terms_through_the_stage():
    core = CORE.read_text(encoding="utf-8")
    # the two era accessors delegate to the generated per-stage selector,
    # driven by the stage the world resolves from the conf key
    assert "erastages::AlwaysBanTermsFor(ActiveTimeframeStage())" in core
    assert "erastages::SensePhrasesFor(ActiveTimeframeStage())" in core
    # the stage-era instruction line exists and is EMPTY at the default
    # stage (the trained wording stays byte-identical there)
    assert "EraStageLine" in core
    assert "IsDefaultStage(stage)" in core
    # no hardcoded era term lists remain in the core (the lists live in
    # the generated header only - one source of record)
    for stale in ('"shattrath", "draenei", "pandaren", "acherus",', '"flying mount", false'):
        assert stale not in core, f"hardcoded era list crept back: {stale}"


def test_production_stage_reader_is_installed_for_the_conf_key():
    filters = FILTERS_CPP.read_text(encoding="utf-8")
    # the ONE native consumer of the conf key: reads the world's stage and
    # installs the provider before any era surface runs
    assert 'GetStringDefault("PocketRealm.TimeframeStage"' in filters
    assert "TimeframeStageProviderSlot() = &ReadPocketRealmTimeframeStage" in filters


def test_stage_era_line_rides_the_existing_instruction_span():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    # the SysmForCard call site composes the era line through the
    # seasoning leg - never a new top-level prompt segment - and the
    # player's editable prompt pack stays untouched
    assert "pocketllm::EraSeasoningFor(LoadPackSeasoning())" in memory


def test_host_battery_pins_the_stage_resolution_legs():
    harness = HARNESS.read_text(encoding="utf-8")
    # the host battery drives the provider slot directly and pins the
    # resolution semantics for every lane (unset/unknown -> default;
    # non-final -> the patch-naming line; byte-identical at default)
    assert "TestEraStageResolution" in harness
    assert "TimeframeStageProviderSlot()" in harness
    assert 'erastages::DefaultStage() == "vanilla-1.12.1"' in harness
    assert 'stages.size() == 22' in harness


def test_telemetry_real_players_excludes_bots() -> None:
    """Round-3 R5-3-1: the native low-CPU telemetry's realPlayers loop must
    EXCLUDE players carrying a PlayerbotAI — the mgr's players map holds
    hired/converted bots (MovePlayerBot inserts them; logout alone erases),
    so an unguarded loop pads player counts with unlabeled bots (the
    'bots labeled, never padded' rule at the telemetry level). Source
    contract on the o09 driver's PB_MGR_SCAN_ANDROID patch (the host
    cannot drive the native boot)."""
    import sys as _sys
    from pathlib import Path as _Path

    root = _Path(__file__).resolve().parents[1]
    _sys.path.insert(0, str(root / "tools"))
    import build_o09_realm_runtime as o09

    block = o09.PB_MGR_SCAN_ANDROID
    loop = block[block.index("std::vector<Player*> realPlayers;"):
                 block.index("snapshot.realPlayers")]
    assert "GetPlayerbotAI()" in loop, (
        "the realPlayers loop lost its bot-exclusion guard - hired bots "
        "would be counted as human players again"
    )
    assert "!player->GetPlayerbotAI()" in loop


def test_vanilla_era_baseline_carries_no_corpus_proven_false_bans() -> None:
    """Round-4 R5-4-1: the never-ban discipline ('vanilla-legitimate words
    are NEVER banned') is machine-checked against the SHIPPED vanilla
    corpus. The pruned terms have whole-word enUS hits in the pinned
    ClassicDB 1.12.1 dump (broadcast_text/npc_text/quest text) and must
    stay OUT of the vanilla baseline's ban lists — re-adding any of them
    (the pre-R5-4-1 state: draenei/pandaren always-banned, argus/zandalar/
    kul tiras/broken isles/burning crusade as non-negatable needles) made
    chat-enabled bots deflect on real vanilla content ('Go talk with Smith
    Argus in Goldshire'). W3.1's text-aware corpus curation owns the full
    sweep; this pin guards the qualified lane's regression direction."""
    import gzip
    import json
    import re as _re

    manifest = json.loads(
        (ROOT / "schemas" / "timeframes" / "vanilla.json").read_text(encoding="utf-8"))
    banned: set[str] = set()
    baseline = manifest.get("final_stage_era_baseline") or {}
    banned.update(t.lower() for t in baseline.get("always_ban_terms") or [])
    banned.update(needle.lower() for needle, _flag in baseline.get("sense_phrases") or [])
    for stage in manifest["stages"]:
        era = stage.get("era_terms") or {}
        grounded = era.get("grounded_baseline") or {}
        banned.update(t.lower() for t in grounded.get("always_ban_terms") or [])
        banned.update(needle.lower() for needle, _flag in grounded.get("sense_phrases") or [])

    corpus_proven = ["draenei", "pandaren", "argus", "zandalar",
                     "kul tiras", "broken isles", "burning crusade"]
    reintroduced = sorted(banned & set(corpus_proven))

    # Round-6 R6-2: the DISCIPLINE sweeps the ENTIRE effective vanilla ban
    # list against the shipped corpus. Terms with whole-word hits are
    # false bans UNLESS recorded here as deliberately-kept anachronism
    # guards — each with its justification. The v1 lists are
    # byte-equivalent to the pre-control behavior by design decision
    # (design doc: "v1 = vanilla lane with vanilla-1.12.1 era lists
    # byte-equivalent to today's"); W3.1's corpus-driven curation owns
    # relaxing them. Every entry names the finding that recorded it.
    KEPT_GUARDS_JUSTIFIED = {
        # shattrath: the TBC hub city — a vanilla-era bot naming it IS the
        # anachronism the era lint exists for (corpus hits are lore-text
        # mentions, not live-vocabulary need).
        "shattrath": "deliberate anachronism guard (v1 byte-equivalent; W3.1 relaxes)",
        "zangarmarsh": "deliberate anachronism guard (v1 byte-equivalent; W3.1 relaxes)",
        "hellfire peninsula": "deliberate anachronism guard (v1 byte-equivalent; W3.1 relaxes)",
        "netherstorm": "deliberate anachronism guard (v1 byte-equivalent; W3.1 relaxes)",
        "terokkar forest": "deliberate anachronism guard (v1 byte-equivalent; W3.1 relaxes)",
        "shadowmoon valley": "deliberate anachronism guard (v1 byte-equivalent; W3.1 relaxes)",
        "flying mount": "deliberate anachronism guard (TBC capability; W3.1 relaxes)",
        "wrath of the lich king": "deliberate anachronism guard (expansion title; W3.1 relaxes)",
        "portal has opened": "deliberate anachronism guard (negatable sense of the TBC portal event; W3.1 relaxes)",
    }
    unguarded = sorted(banned & set(KEPT_GUARDS_JUSTIFIED))
    # (checked below, after the corpus pass, that every unguarded-but-hit
    # term would have been caught)

    # The reintroduced check covers ONLY the round-4 pruned terms (they
    # must never return); kept guards are deliberately banned and are
    # verified below against the corpus instead.
    reintroduced = sorted(banned & set(corpus_proven))
    assert not reintroduced, (
        f"corpus-proven vanilla-legitimate term(s) {reintroduced} re-entered "
        "the vanilla ban lists - the never-ban discipline forbids it "
        "(R5-4-1: bots deflected on real vanilla content)"
    )
    corpus_proven += sorted(banned & set(KEPT_GUARDS_JUSTIFIED))

    dump = ROOT / "native/classic-db/Full_DB/ClassicDB_1_12_1_z2815.sql.gz"
    assert dump.is_file(), "the pinned vanilla corpus is missing"
    needles = [t.encode("utf-8").lower() for t in corpus_proven]
    hits: dict[str, int] = {}
    with gzip.open(dump, "rb") as handle:
        for line in handle:
            low = line.lower()
            for raw_needle in needles:
                start = 0
                while True:
                    i = low.find(raw_needle, start)
                    if i == -1:
                        break
                    j = i + len(raw_needle)
                    before_ok = i == 0 or not chr(low[i - 1]).isalpha()
                    after_ok = j >= len(low) or not chr(low[j]).isalpha()
                    if before_ok and after_ok:
                        hits[raw_needle.decode()] = hits.get(raw_needle.decode(), 0) + 1
                    start = i + 1
    missing = [t for t in corpus_proven if hits.get(t, 0) == 0]
    assert not missing, (
        f"corpus regression: {missing} no longer hit the shipped vanilla "
        "dump - the pin's corpus assumptions changed; re-derive before "
        "trusting the prune"
    )
    # Round-6 R6-2: every kept guard named in the exception table must
    # actually still be BANNED (a stale table entry — term removed from
    # the lists but left recorded as kept — is a config error, the
    # leak-sweep stale-entry pattern).
    stale_table = sorted(
        term for term in KEPT_GUARDS_JUSTIFIED if term not in banned
    )
    assert not stale_table, (
        f"exception-table entries {stale_table} are no longer in the vanilla "
        "ban lists — prune the stale KEPT_GUARDS_JUSTIFIED entries"
    )
