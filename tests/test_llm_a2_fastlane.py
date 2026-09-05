"""A2: the conversation fast-lane - host pins.

The zone-cap admission half (PlayerbotLlmGates::EvictDialogueVictim -
prune, interlocutor-always, cap 16) runs in tests/test_llm_gates.py; this
file pins the runtime halves:
  - the IN_DIALOGUE activity class: enum value placed before
    NO_PATH/IN_*_MAP, an early return AFTER the real-player/master
    checks, and a {0,0} bracket entry (always-active)
  - arming: world-thread only, real-player turns (event and bot2bot
    turns never arm), the LLMDialogueFastLane key gates the arming
    itself, and the AllowActivity 5 s cache is stamped hot at arming
  - the occupancy map: guid-keyed per map under StateMutex, TTL 300 s,
    re-arm extends, no decrement path (leaks bounded by the TTL)
  - the relocated cloud-fallback delivery site re-arms (the pin follows
    the A4 relocation)
  - street says never arm the fast lane
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"


def android_anchor(name: str) -> str:
    return DRIVER.read_text(encoding="utf-8").split(name + ' = """')[1].split('"""')[0]


class TestActivityClass:
    def test_enum_value_sits_before_the_zone_ladder(self):
        enum = android_anchor("PB_AI_DIALOGUE_ENUM_ANDROID")
        assert "IN_DIALOGUE," in enum
        assert enum.index("IN_DIALOGUE,") < enum.index("NO_PATH,")

    def test_priority_early_return_after_master_checks(self):
        prio = android_anchor("PB_AI_PRIORITY_DIALOGUE_ANDROID")
        # after the group block (IN_GROUP_WITH_REAL_PLAYER classifies
        # first), before the bg/zone ladder
        assert prio.index("IN_GROUP_WITH_REAL_PLAYER") < prio.index("DialogueActive")
        assert prio.index("DialogueActive") < prio.index("IsBeingTeleported")
        assert "return ActivePiorityType::IN_DIALOGUE;" in prio

    def test_bracket_entry_joins_the_always_active_group(self):
        br = android_anchor("PB_AI_BRACKET_DIALOGUE_ANDROID")
        assert "case ActivePiorityType::IN_DIALOGUE:" in br
        assert br.index("IN_DIALOGUE:") < br.index("return { 0,0 };")

    def test_recheck_helper_zeroes_the_cache(self):
        rc = android_anchor("PB_AI_DIALOGUE_RECHECK_ANDROID")
        assert "ForceActivityRecheck" in rc
        assert "allowActiveCheckTimer[i] = 0;" in rc


class TestArming:
    def test_dispatch_site_arms_real_player_turns_only(self):
        async_site = android_anchor("PB_SAY_ASYNC_ANDROID")
        # event turns and bot2bot turns never arm; llmSpeakerGuid is the
        # real-player gate
        assert re.search(r"if \(!llmEventTurn && llmSpeakerGuid && llmFallback\.mapId\)", async_site)
        assert "PlayerbotLlmMemory::ArmDialogue(bot->GetGUIDLow()," in async_site
        # uptake is immediate: the 5 s AllowActivity cache stamps hot
        assert "ai->ForceActivityRecheck();" in async_site

    def test_the_key_gates_the_arming_itself(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void PlayerbotLlmMemory::ArmDialogue\(.*?\n\}", src, re.S)
        assert m, "ArmDialogue implementation not found"
        body = m.group(0)
        # the first STATEMENT is the conf gate (0 disables the window) -
        # it precedes every lock, read and stamp
        gate = body.find("if (!sPlayerbotAIConfig.llmDialogueFastLane)")
        assert gate > 0
        assert body.find("return;", gate) < body.find("StateMutex()", gate)

    def test_occupancy_uses_the_pure_admission_helper(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void PlayerbotLlmMemory::ArmDialogue\(.*?\n\}", src, re.S)
        assert "PlayerbotLlmGates::EvictDialogueVictim(occupants, nowMs, botGuid, 16, botGuid)" in m.group(0)
        # TTL 300 s, steady-clock ms, no decrement path
        assert "nowMs + 300ull * 1000ull" in m.group(0)
        assert "SteadyNowMs" in m.group(0)

    def test_dialogue_active_reads_under_the_state_mutex(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"bool PlayerbotLlmMemory::DialogueActive\(.*?\n\}", src, re.S)
        assert m
        assert "StateMutex()" in m.group(0)
        assert m.group(0).count("DialogueOccupancy()") >= 1

    def test_the_cloud_fallback_delivery_site_re_arms(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void PlayerbotLlmMemory::QueueConversationalFallback\(.*?\n\}", src, re.S)
        assert m, "QueueConversationalFallback implementation not found"
        # the relocated delivery site (post-A4) extends the window: the
        # conversation continues despite the failed turn
        assert "ArmDialogue(botGuid, mapId, true)" in m.group(0)


class TestNothingElseArms:
    def test_street_never_arms_the_fast_lane(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void RunStreetReaction\(StreetJob job\)\s*\{.*?\n\}", src, re.S)
        assert m, "the street worker must exist"
        assert "ArmDialogue" not in m.group(0)
