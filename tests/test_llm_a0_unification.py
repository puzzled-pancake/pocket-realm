"""A0 backend-unification contract guards (S3).

The A0 unification lives in driver-anchor text (tools/
build_o09_realm_runtime.py) and overlay files - there is no host-runnable
world to exercise speaker attribution or the governor hoist directly, so
these assertions pin the load-bearing invariants textually: if a later
stage re-gates memory on the backend, drops the HTTP tool extraction, or
re-couples tool attribution to the bot's owner, a test fails here before
any native build silently ships the regression. Compile-level truth stays
with the lane build; behavioral truth stays with the (user-gated) device
run.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
TOOLS_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmTools.cpp"
MEMORY_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmMemory.cpp"


def _driver():
    spec = importlib.util.spec_from_file_location("o09_driver", DRIVER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_governor_sits_above_the_backend_branch():
    generate = _driver().PB_LLM_IFACE_CPP_ANDROID
    governor = generate.index("governorMutex")
    backend_branch = generate.index("llmBackend == PlayerbotAIConfig::LLM_BACKEND_LLAMA")
    assert governor < backend_branch, (
        "the duty-cycle governor must run before the backend branch "
        "(A0 hoist) - the HTTP path would lose admission control")


def test_every_http_return_path_extracts_and_queues_tools():
    generate = _driver().PB_LLM_IFACE_CPP_ANDROID
    # the three HTTP return paths that voice text all extract tools; the
    # fail-quiet paths return empty (nothing to extract)
    returns = generate.count("PlayerbotLlmTools::ExtractAndQueue(")
    # S7 round-1: the leak/marker/dupe rejections moved BEFORE
    # extraction, so the HTTP envelope/retry/dedupe paths flow through
    # ONE finally-chosen extraction site (llama raw + that + voicable
    # raw fallback = 3)
    assert returns == 3, (
        f"expected 3 ExtractAndQueue sites (llama raw + the single "
        f"finally-chosen HTTP extraction + voicable raw fallback), found "
        f"{returns} - G-1 re-opened on an HTTP path or the single-"
        "extraction contract broke")


def test_http_returns_carry_the_speaker_guid():
    generate = _driver().PB_LLM_IFACE_CPP_ANDROID
    # S7: the envelope/retry extractions moved into PocketLlmVoiceFilter
    # (the A12 leak check runs BEFORE extraction so a rejected reply
    # leaves no queued calls behind); the raw-fallback site stayed inline
    for site in ("ExtractAndQueue(httpBody",
                 "PocketLlmVoiceFilter(envelope.content",
                 "PocketLlmVoiceFilter(retry.content",
                 "ExtractAndQueue(content, botGuid, speakerGuid"):
        assert site in generate, f"missing speaker-threaded extraction site: {site}"


def test_hard_trigger_gate_is_backend_independent():
    gate = _driver().PB_SAY_GATE_ANDROID
    assert "!useLlamaBackend ||" not in gate, (
        "the hard-trigger gate must not bypass for the HTTP backend (G-6)")
    assert "SRC_WHISPER" in gate and "addressedToBot" in gate


def test_tool_instructions_ride_both_backends():
    prompt = _driver().PB_SAY_PROMPT_V2_ANDROID
    tools_at = prompt.index("ToolInstructions(")
    # S4: the backend branch is guarded by json.empty() (the trained-format
    # body short-circuits both legacy paths); the tools note must still be
    # appended before that split so llama AND the legacy HTTP template
    # carry it (A0/G-1). Under the trained format the TOOLS_NOTE rides the
    # system message instead (PlayerbotLlmPrompt.h). S5/A7: the call is
    # GUID-keyed (per-bot trained variant).
    llama_branch = prompt.index("useLlamaBackend)")
    assert tools_at < llama_branch, (
        "ToolInstructions must be appended before the backend split so the "
        "HTTP template path carries it too (A0/G-1)")
    # the llama branch itself must be json.empty()-guarded: without it the
    # raw-completion concatenation would clobber the trained-format body
    assert "if (json.empty() && useLlamaBackend)" in prompt, (
        "the llama raw-prompt branch must not overwrite a trained-format body")
    dup = _driver().PB_SAY_JSON_DUP_ANDROID
    assert "else if (json.empty())" in dup, (
        "the legacy template fill must be gated so it cannot clobber the "
        "trained-format request body")


def test_speaker_resolution_is_not_the_bot_owner():
    cpp = TOOLS_CPP.read_text(encoding="utf-8")
    # strip line comments so the check targets code, not prose mentions
    code_only = "\n".join(line.split("//", 1)[0] for line in cpp.splitlines())
    # S6 narrowing: GetMaster may appear ONLY in a comparison against the
    # already-resolved speaker (the follow beat's master check) - never as
    # the resolution source for attribution (the A0 interlocutor fix)
    for line in code_only.splitlines():
        if "->GetMaster(" in line:
            assert "!= player" in line or "== player" in line, (
                "tool attribution must resolve the interlocutor via the "
                "threaded speakerGuid, never the bot's owner; GetMaster is "
                "allowed only to COMPARE against the resolved speaker "
                "(A0 interlocutor fix + S6 follow guard)")
    assert "FindPlayer" in code_only and "speakerGuid" in code_only


def test_autonomous_turns_cannot_persist_player_state():
    cpp = TOOLS_CPP.read_text(encoding="utf-8")
    assert "playerTurn" in cpp and "LLM_SRC_CHAT_REPLY" in cpp, (
        "the source guard refusing persistence tools on autonomous turns "
        "must stay")
    rpg = _driver().PB_RPG_ASYNC_ANDROID
    assert "uint32(0)" in rpg, "the RPG path must pass speakerGuid 0"


def test_persisted_memory_text_is_marker_neutered():
    cpp = MEMORY_CPP.read_text(encoding="utf-8")
    log_fact = cpp.index("PlayerbotLlmMemory::LogFact")
    share_gossip = cpp.index("PlayerbotLlmMemory::ShareGossip")
    sentiment = cpp.index("PlayerbotLlmMemory::AddBoundedSentimentInput")
    for name, start, end in (
        ("LogFact", log_fact, share_gossip),
        ("AddBoundedSentimentInput", sentiment, log_fact),
        ("ShareGossip", share_gossip, len(cpp)),
    ):
        body = cpp[start:end]
        assert "NeuterMarkersCopy" in body, (
            f"{name} must neuter tool markers at the write - a persisted "
            "live marker replays into future prompts as a forged-call "
            "exemplar (S3 R6 P1)")
        # ORDER is load-bearing: StripAstral must run BEFORE the neutering.
        # It deletes 4-byte sequences and would fuse an astral-padded `<X<`
        # back into a live `<<` after the neuter pass (S3 round-2 P1).
        # S5 wraps the neuter in ScrubControlTokens (control-token scrub)
        # and may line-break between the calls - normalize whitespace
        # before matching.
        import re
        normalized = re.sub(r"\s+", "", body)
        # S5 round-2: the chain is strip -> SCRUB -> NEUTER. The neuter runs
        # LAST by law: the scrub's deletions can fuse `<[EVENT]<` into a
        # live `<<`, so neutering first would leave the fused marker.
        assert "NeuterMarkersCopy(ScrubControlTokens(StripAstral(" in normalized, (
            f"{name} must run strip -> scrub -> neuter (the neuter LAST so "
            "scrub deletions cannot fuse live markers)")
