"""A4: authored interceptors demote to cloud failure-fallbacks - host pins.

The pure half (FailureWantsFallback + the FallbackPlan defaults) runs in
tests/test_llm_gates.py; this file pins the runtime halves that live in
the driver payloads and overlays:
  - the async payload gains a DEFAULTED trailing FallbackPlan so the
    autonomous RPG/debug dispatch sites keep compiling and stay silent
  - the worker's failure leg: busy NEVER falls back (the placeholder owns
    duty-cycle denial), the fallback fires only on conversational turns,
    delivery queues on the EventReaction drain (guid identity - the async
    region contains no bot->Whisper/bot->Say deref), and the closure owns
    {deliver, bot-line record, guid award} exactly once per turn outcome
  - the world-thread interceptor sites demote ONLY on the cloud lane -
    the device lane keeps every preemptive body verbatim (byte-identity),
    and the cloud persona leg classifies WITHOUT drawing (a pre-draw
    would advance the shared recency ring for a line never delivered)
  - the +1 award: device keeps the synchronous pre-award; cloud moves it
    into the worker's exactly-once fold
  - A4/A5 exclusivity: the murmur floor (PlayerbotLlmChatter.cpp) never
    draws the conversational fallback and the A4 leg never touches the
    floor - the fallback owns interlocutor turns, the floor owns
    non-interlocutor dead air
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
CHATTER_CPP = PATCHES / "PlayerbotLlmChatter.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"


def android_anchor(name: str) -> str:
    return DRIVER.read_text(encoding="utf-8").split(name + ' = """')[1].split('"""')[0]


def memory_impl(fn: str) -> str:
    src = MEMORY_CPP.read_text(encoding="utf-8")
    m = re.search(rf"\n[^\n]*PlayerbotLlmMemory::{fn}\(.*?\n\}}", src, re.S)
    assert m, f"{fn} implementation not found in the memory overlay"
    return m.group(0)


class TestAsyncPlumbing:
    def test_decl_carries_defaulted_trailing_plan(self):
        decl = android_anchor("PB_SAY_GEN_DECL_ANDROID")
        assert "PlayerbotLlmGates::FallbackPlan const& fallback = PlayerbotLlmGates::FallbackPlan()" in decl

    def test_definition_signature_matches(self):
        body = android_anchor("PB_SAY_GEN_DEF_ANDROID")
        assert "PlayerbotLlmGates::FallbackPlan const& fallback)" in body

    def test_header_includes_the_gates_overlay(self):
        # the FallbackPlan type must be visible to SayAction.h's decl
        head = android_anchor("PB_SAY_HEADER_ANDROID")
        assert '#include "playerbot/PlayerbotLlmGates.h"' in head

    def test_rpg_dispatch_site_stays_silent(self):
        # the autonomous RPG site passes no ACTIVE plan: the default-
        # constructed (inactive) FallbackPlan, passed explicitly only
        # because defaults do not bind through the std::async function
        # pointer
        rpg = android_anchor("PB_RPG_ASYNC_ANDROID")
        assert "llmFallback" not in rpg
        assert "PlayerbotLlmGates::FallbackPlan()" in rpg
        assert "FallbackPlan const&" not in rpg


class TestWorkerFailureLeg:
    def test_fallback_leg_gates(self):
        rec = android_anchor("PB_SAY_RECORDER_ANDROID")
        assert "PlayerbotLlmGates::FailureWantsFallback(busyReply, lines.empty())" in rec
        assert "fallback.active" in rec
        # conversational turns only - the RPG source never carries a plan
        assert "source == PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY" in rec

    def test_async_region_never_dereferences_a_player(self):
        # the no-off-thread-deref scan: the worker legs deliver through
        # the guid-keyed EventReaction queue, never a raw bot->Whisper
        for anchor in ("PB_SAY_GEN_DEF_ANDROID", "PB_SAY_RECORDER_ANDROID"):
            body = android_anchor(anchor)
            assert "bot->Whisper" not in body, anchor
            assert "bot->Say" not in body, anchor

    def test_fallback_delivery_and_record(self):
        rec = android_anchor("PB_SAY_RECORDER_ANDROID")
        assert "PlayerbotLlmMemory::DrawFailureFallback" in rec
        assert "PlayerbotLlmMemory::QueueConversationalFallback(botGuid, speakerGuid," in rec
        # the line leaves the packets pipeline - single delivery
        assert re.search(r"lines\.clear\(\);\s*\n\s*fallbackDelivered = true;", rec)

    def test_closure_award_exactly_once(self):
        rec = android_anchor("PB_SAY_RECORDER_ANDROID")
        # C6: the closure award routes through the capped turn award
        # (the per-pairing daily cap bounds the farm surface; the
        # exactly-once-per-outcome fold itself is unchanged)
        m = re.search(r"if \(fallback\.active && speakerGuid &&.*?\)\s*\n\s*PlayerbotLlmMemory::AwardChatTurnByGuid\(botGuid, speakerGuid\);", rec, re.S)
        assert m, "the closure award fold is missing"
        # exactly one award site in the worker
        assert rec.count("AwardChatTurnByGuid") == 1
        assert "AddRelationshipPointsByGuid(botGuid, speakerGuid" not in rec

    def test_busy_never_falls_back(self):
        # the busy placeholder substitutes BEFORE the fold; busyReply is
        # the fold's first exclusion (pinned pure-side too)
        gen = android_anchor("PB_SAY_GEN_DEF_ANDROID")
        assert "bool const busyReply = response == POCKETREALM_LLM_BUSY;" in gen


class TestInterceptorDemotion:
    def test_greet_demotes_only_on_the_cloud_lane(self):
        ctx = android_anchor("PB_SAY_CONTEXT_ANDROID")
        # the demotion fills the FallbackPlan with IDS (kind + absence bucket)
        assert "llmFallback.kind = PlayerbotLlmGates::FBK_GREET;" in ctx
        assert "llmFallback.absence = llmAbsencePre;" in ctx
        # the device lane keeps the preemptive delivery verbatim in the
        # else arm (byte-identity)
        assert re.search(r"if \(llmCloudTurn\)\s*\{\s*llmFallback\.active = true;\s*llmFallback\.kind = PlayerbotLlmGates::FBK_GREET;", ctx)

    def test_persona_leg_classifies_without_drawing(self):
        ctx = android_anchor("PB_SAY_CONTEXT_ANDROID")
        # cloud leg: Classify only - TryFallback DRAWS (advancing the
        # shared recency ring); the fallback redraws at failure time
        assert "PlayerbotLlmPersona::Classify(msg)" in ctx
        cloud_leg = re.search(r"llmCloudTurn\s*\?\s*PlayerbotLlmPersona::Classify\(msg\)", ctx)
        assert cloud_leg
        # the device leg keeps TryFallback (the draw + preemptive body)
        assert re.search(r": PlayerbotLlmPersona::TryFallback\(bot, msg,", ctx)
        assert "llmFallback.kind = PlayerbotLlmGates::FBK_PERSONA;" in ctx

    def test_welcome_stays_authored_on_both_lanes(self):
        ctx = android_anchor("PB_SAY_CONTEXT_ANDROID")
        i = ctx.find("AuthoredFirstContactWelcome")
        assert i > 0
        # no cloud demotion inside the welcome block: the next FallbackPlan
        # reference comes only after the welcome's closing return
        window = ctx[i:i + 1400]
        assert "llmFallback" not in window.split("return;")[0]

    def test_pre_award_moves_into_the_closure_cloud_side(self):
        ctx = android_anchor("PB_SAY_CONTEXT_ANDROID")
        # C6: the device pre-award routes through the capped turn award
        # (LLMTurnAwardDailyCap bounds it; 0 = uncapped legacy behavior)
        assert re.search(
            r"if \(!llmCloudTurn\)\s*\n\s*PlayerbotLlmMemory::AwardChatTurn\(bot, player\);", ctx)
        # every cloud conversational turn activates the closure (FBK_NONE
        # plain turns included: the award closure applies without a
        # fallback line)
        assert re.search(r"if \(llmCloudTurn\)\s*\{\s*llmFallback\.active = true;", ctx)

    def test_plan_declared_before_the_interceptors(self):
        ctx = android_anchor("PB_SAY_CONTEXT_ANDROID")
        assert ctx.find("PlayerbotLlmGates::FallbackPlan llmFallback;") < ctx.find("AuthoredArrivalGreeting(bot, player")


class TestDeliveryAndAwardHelpers:
    def test_draw_rejects_inactive_plans_and_vanished_speakers(self):
        body = memory_impl("DrawFailureFallback")
        assert "if (!plan.active)" in body
        assert "sObjectAccessor.FindPlayer" in body
        # the greet leg recomposes the arrival greeting at failure time
        assert "AuthoredArrivalGreeting(bot, player, plan.absence)" in body
        # the persona leg re-draws the category through the persona
        assert "FallbackLine(bot," in body

    def test_queue_delivers_via_the_event_reaction_drain(self):
        body = memory_impl("QueueConversationalFallback")
        assert "EventReaction reaction;" in body
        assert "reaction.authored = true;" in body
        assert "reaction.msgtype = msgtype;" in body

    def test_award_re_resolves_by_guid(self):
        body = memory_impl("AddRelationshipPointsByGuid")
        assert body.count("sObjectAccessor.FindPlayer") == 2
        assert "AddRelationshipPoints(bot, player, points)" in body

    def test_drain_whispers_a_whisper_tagged_fallback(self):
        # a private answer must never land on /say
        upd = android_anchor("PB_UPDATEAI_ANDROID")
        assert re.search(
            r"if \(reaction\.msgtype == CHAT_MSG_WHISPER\)\s*\{.*?bot->Whisper\(reaction\.text, LANG_UNIVERSAL, whisperTarget->GetObjectGuid\(\)\);",
            upd, re.S)


class TestA4A5Exclusivity:
    def test_the_floor_owns_only_non_interlocutor_dead_air(self):
        # the murmur lane never draws the conversational fallback
        chatter = CHATTER_CPP.read_text(encoding="utf-8")
        assert "DrawFailureFallback" not in chatter
        assert "QueueConversationalFallback" not in chatter

    def test_the_fallback_never_fires_the_floor(self):
        # and the A4 legs never touch the chatter floor state
        for anchor in ("PB_SAY_RECORDER_ANDROID", "PB_SAY_GEN_DEF_ANDROID"):
            body = android_anchor(anchor)
            assert "Floor" not in body, anchor
        memory = MEMORY_CPP.read_text(encoding="utf-8")
        fallback_impl = memory_impl("QueueConversationalFallback")
        assert "PlayerbotLlmChatter::" not in fallback_impl
