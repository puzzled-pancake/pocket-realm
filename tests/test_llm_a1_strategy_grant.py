"""A1 source-contract pins: the AiFactory strategy grant + the gate
refusal stamp.

The grant lives in the PRISTINE playerbots submodule (lane 3 per plan
0.b: 'add the NEW host source-contract pin reading the pristine tree'),
and no behavior battery exercises AiFactory directly - a regression to
the bare key (llmEnabled > 0 alone, the 0.13 stray-mention hazard A1
widens safely) or the loss of the == 2 arm (today's external baseline)
would compile clean and pass the whole suite. The pin reads the
pristine tree the test_llm_security_refusal.py PlayerbotSecurity /
test_db_async_null_guard.py pristine-read way.

The refusal stamp (A1's 'refusal logs once per bot per session'
deliverable) lives in the memory overlay + the SayAction gate payload:
the helper is mutex-guarded once-per-bot semantics, the call site fires
only for a hard trigger the reply gate refused (never for claim losers
or ambient non-triggers).
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AI_FACTORY = ROOT / "native" / "playerbots" / "playerbot" / "AiFactory.cpp"
MEMORY_H = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmMemory.h"
MEMORY_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmMemory.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"

GRANT = ("if (sPlayerbotAIConfig.llmEnabled == 2 ||\n"
         "        (sPlayerbotAIConfig.llmEnabled > 0 && CloudLaneOpen()))")
STRATEGY = 'nonCombatEngine->addStrategy("ai chat");'


def _norm(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace").replace(
        "\r\n", "\n").replace("\r", "\n")


def _gate_payload() -> str:
    return DRIVER.read_text(encoding="utf-8").split(
        'PB_SAY_GATE_ANDROID = """')[1].split('"""')[0]


def test_grant_include_follows_the_module_convention():
    # the module's usual include form (same as SayAction.cpp)
    assert '#include "playerbot/PlayerbotLlmMemory.h"' in _norm(AI_FACTORY)


def test_the_grant_is_exactly_the_conjunction_widening():
    text = _norm(AI_FACTORY)
    assert GRANT in text, (
        "the == 2 arm (today's external baseline) AND the "
        "CloudLaneOpen() widening, verbatim - the bare-key form is the "
        "0.13 hazard")
    idx = text.index(GRANT)
    assert STRATEGY in text[idx: idx + 200]
    # the grant appears exactly once (no second site could drift)
    assert text.count(GRANT) == 1


# ---- the refusal stamp ------------------------------------------------------

def test_refusal_stamp_is_once_per_bot_under_the_mutex():
    src = _norm(MEMORY_CPP)
    helper = src.split("bool PlayerbotLlmMemory::NoteGateRefusalOnce")[1]
    helper = helper.split("\n}")[0]
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in helper
    # once-per-bot: find-then-insert, first refusal true, later false
    assert "noted.find(botGuid) != noted.end()" in helper
    assert "noted.insert(botGuid);" in helper
    # the process-local stamp set (dies with the world process) exists
    # as the accessor the helper consumes
    assert "std::set<uint32>& GateRefusalNoted()" in src
    assert "GateRefusalNoted()" in helper
    decl = _norm(MEMORY_H)
    assert "static bool NoteGateRefusalOnce(uint32 botGuid);" in decl


def test_refusal_log_fires_only_for_gate_refused_hard_triggers():
    gate = _gate_payload()
    # the call site: a hard trigger that survived the claim but the
    # reply gate refused - the dead-gate signature. The stamp call is
    # LAST (lazy: it never runs unless everything before it holds).
    assert ("if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed &&\n"
            "        partyResponderClaimed && !replyGateAllowed &&\n"
            "        PlayerbotLlmMemory::NoteGateRefusalOnce(bot->GetGUIDLow()))"
            ) in gate
    # once per bot per session is stated in the log line itself
    assert ('"BotLLM: reply gate refused bot=%u src=%d '
            '(once per bot per session)"') in gate
