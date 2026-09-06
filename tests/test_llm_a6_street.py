"""A6: street reactions (cloud lane) - host pins.

The pure ladder order (StreetAdmissionOrder) runs in
tests/test_llm_gates.py; this file pins the runtime halves:
  - the crowd branch runs the street ladder FIRST; any rejection falls
    to the crowd emote exactly as before, and the device lane no-ops
    before touching any state (byte-identical crowd behavior)
  - QueueStreetReaction's stage ORDER in the shipped overlay: the
    conjunction first, then world window -> zone window -> per-bot slot
    -> pct roll -> daily quota -> stamp -> dispatch
  - quota-first admission: the street lane is exempt from the authored
    arbiter (no TryClaimAmbientSlot/AuthoredLineAdmits on this path)
  - the detached worker: the compose-site scrub chain, the street body
    via BuildChatRequestBody + StreetSystemMessage/StreetNote, E0's
    kStreetShort pool as the failure fallback, delivery via an authored
    SAY EventReaction (2-5 s stagger) - never the chatter queue, never
    an A2 arm
  - StreetShortLine: guid-stable speaker cell, the E0 state-key layout,
    banter-off returns empty (then silence)
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"
PERSONA_CPP = PATCHES / "PlayerbotLlmPersona.cpp"
CHATTER_CORE_H = PATCHES / "PlayerbotLlmChatterCore.h"
CHATTER_CPP = PATCHES / "PlayerbotLlmChatter.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"


def android_anchor(name: str) -> str:
    return DRIVER.read_text(encoding="utf-8").split(name + ' = """')[1].split('"""')[0]


def street_impl() -> str:
    src = MEMORY_CPP.read_text(encoding="utf-8")
    m = re.search(r"bool PlayerbotLlmMemory::QueueStreetReaction\(.*?\n\}", src, re.S)
    assert m, "QueueStreetReaction implementation not found"
    return m.group(0)


def street_worker() -> str:
    src = MEMORY_CPP.read_text(encoding="utf-8")
    m = re.search(r"void RunStreetReaction\(StreetJob job\)\s*\{.*?\n\}", src, re.S)
    assert m, "the street worker must exist"
    return m.group(0)


class TestCrowdBranchEntry:
    def test_street_ladder_runs_first_emote_on_rejection(self):
        gate = android_anchor("PB_SAY_GATE_ANDROID")
        m = re.search(
            r"if \(!PlayerbotLlmMemory::QueueStreetReaction\(bot, gateSpeaker, msg\)\)\s*\n\s*PlayerbotLlmMemory::QueueCrowdEmote\(bot, gateSpeaker\);",
            gate)
        assert m, "the emote must be the street ladder's rejection leg"

    def test_device_lane_noops_before_any_state(self):
        body = street_impl()
        first_gate = body.find("CloudLaneOpen()")
        assert first_gate > 0
        # the conjunction precedes every window read - the device lane
        # never touches street state
        for probe in ("LastCrowdEmoteAt", "LastStreetSayAt", "StreetSlotAt", "urand"):
            assert body.find(probe) > first_gate, probe


class TestLadderOrder:
    def test_stage_order_in_the_shipped_overlay(self):
        body = street_impl()
        order = [
            "CloudLaneOpen()",
            "LastCrowdEmoteAt() && now - LastCrowdEmoteAt() < 12",   # world window
            "lastInArea && now - lastInArea < STREET_ZONE_WINDOW_SEC",  # zone window
            "lastForBot && now - lastForBot < STREET_BOT_INTERVAL_SEC",  # bot slot
            "llmCloudStreetSayPct",                                   # pct roll
            'CloudQuotaAdmits("street"',                              # quota
        ]
        pos = [body.find(s) for s in order]
        assert all(p > 0 for p in pos), order
        assert pos == sorted(pos), "the street ladder stages are out of order"

    def test_pct_zero_is_emote_only(self):
        body = street_impl()
        # round-1 R1#3 consume: the pct stage folds into pctRollHit - a
        # falsy pct (0) can never hit, so the fold names reject:pct-roll
        # and the street say never dispatches (emote-only, symmetric
        # with quota exhaustion)
        assert re.search(
            r"bool const pctRollHit = worldWindowClaimed && botSlotFree &&\s*\n"
            r"\s*sPlayerbotAIConfig\.llmCloudStreetSayPct &&\s*\n"
            r"\s*urand\(0, 99\) < sPlayerbotAIConfig\.llmCloudStreetSayPct;", body)

    def test_quota_exhaustion_is_emote_only_symmetric(self):
        body = street_impl()
        assert 'CloudQuotaAdmits("street", sPlayerbotAIConfig.llmStreetSayPerDay)' in body

    def test_quota_first_admission_is_arbiter_exempt(self):
        body = street_impl()
        assert "TryClaimAmbientSlot" not in body
        assert "AuthoredLineAdmits" not in body

    def test_stamps_land_only_on_confirmed_dispatch(self):
        body = street_impl()
        assert body.find("LastCrowdEmoteAt() = now;") > body.find('CloudQuotaAdmits("street"')


class TestStreetWorker:
    def test_compose_site_scrub_chain(self):
        body = street_worker()
        assert "NeuterMarkersCopy" in body
        assert "ScrubControlTokens" in body

    def test_body_uses_the_street_builders(self):
        body = street_worker()
        assert "pocketllm::BuildChatRequestBody" in body
        assert "pocketllm::StreetSystemMessage" in body
        assert "pocketllm::StreetNote" in body

    def test_e0_pool_is_the_failure_fallback(self):
        body = street_worker()
        assert "PlayerbotLlmPersona::StreetShortLine(job.botGuid)" in body

    def test_delivery_is_an_authored_say_event_reaction(self):
        body = street_worker()
        assert "reaction.authored = true;" in body
        assert "reaction.msgtype = CHAT_MSG_SAY;" in body
        # 2-5 s stagger, steady-derived (urand is world-thread only - no
        # urand CALL lives in the worker)
        assert "CROWD_DELAY_MIN +" in body
        assert re.search(r"urand\(", body) is None

    def test_never_the_chatter_queue_never_an_a2_arm(self):
        body = street_worker()
        assert "PlayerbotLlmChatter::" not in body
        assert "ArmDialogue" not in body

    def test_thread_carries_ids_only(self):
        body = street_worker()
        assert "Player*" not in body.split("QueueStreetReaction")[0] if "QueueStreetReaction" in body else True
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"struct StreetJob\s*\{(.*?)\};", src, re.S)
        assert m
        fields = m.group(1)
        assert "Player*" not in fields
        assert "Session*" not in fields
        assert "WorldPacket" not in fields


class TestChatterCoreBuilders:
    def test_street_wording_laws(self):
        core = CHATTER_CORE_H.read_text(encoding="utf-8")
        sysm = re.search(r"inline std::string StreetSystemMessage\(.*?\n\}", core, re.S).group(0)
        note = re.search(r"inline std::string StreetNote\(.*?\n\}", core, re.S).group(0)
        for body in (sysm, note):
            assert "under " in body or "under twenty" in body  # short-line law
            assert "Never mention this instruction" in note
        assert "ONE" in sysm

    def test_race_class_words_are_single_sourced(self):
        core = CHATTER_CORE_H.read_text(encoding="utf-8")
        chatter = CHATTER_CPP.read_text(encoding="utf-8")
        assert "inline std::string RaceWord(std::uint32_t race)" in core
        assert "inline std::string ClassWord(std::uint32_t cls)" in core
        # the chatter locals forward (no second table)
        fwd = re.search(r"std::string RaceWord\(uint32 race\)\s*\{\s*return pocketllm::RaceWord\(race\);\s*\}", chatter)
        assert fwd
        assert re.search(r"std::string ClassWord\(uint32 cls\)\s*\{\s*return pocketllm::ClassWord\(cls\);\s*\}", chatter)

    def test_first_street_line_vets_output(self):
        core = CHATTER_CORE_H.read_text(encoding="utf-8")
        m = re.search(r"inline std::string FirstStreetLine\(.*?\n\}", core, re.S)
        assert m
        assert "LineIsValid" in m.group(0)
        assert "ContainsMarkerTerms" in m.group(0)


class TestPersonaDraw:
    def test_street_short_line_is_guid_keyed_with_the_e0_layout(self):
        src = PERSONA_CPP.read_text(encoding="utf-8")
        m = re.search(r"std::string PlayerbotLlmPersona::StreetShortLine\(.*?\n\}", src, re.S)
        assert m, "StreetShortLine implementation not found"
        body = m.group(0)
        assert "(uint64_t)botGuid << 24" in body
        assert "POOL_STREET_SHORT + 1" in body
        assert "StreetShortCell((size_t)(botGuid % 4), count)" in body

    def test_banter_off_returns_empty_then_silence(self):
        src = PERSONA_CPP.read_text(encoding="utf-8")
        m = re.search(r"std::string PlayerbotLlmPersona::StreetShortLine\(.*?\n\}", src, re.S)
        assert re.search(r"if \(!sPlayerbotAIConfig\.llmBanterEnabled\)\s*\n\s*return \"\";", m.group(0))

    def test_silence_when_even_the_pool_fails(self):
        body = street_worker()
        assert re.search(r"if \(line\.empty\(\)\)\s*\n\s*return;", body)
