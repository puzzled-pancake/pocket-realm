"""E3: security-refusal wiring - host pins.

The authored refusal POOL (POOL_SECURITY_REFUSE, the archetype cells and
the ring) is owned by the persona overlay and pinned by its own battery;
this file pins the SUBMODULE wiring in
native/playerbots/playerbot/PlayerbotSecurity.cpp (lane 3, pristine
tree):
  - the live refusal site (the PLAYERBOT_SECURITY_INVITE family delivery
    tail in CheckLevelFor) draws PlayerbotLlmPersona::SecurityRefusalLine
    when llmBanterEnabled is on, for the INVITE-FAMILY reasons only
    (invite / is-leader / not-leader / full-group) - LOW_LEVEL /
    GEARSCORE / queue denials keep their actionable messages
  - llmBanterEnabled = 0 leaves the UI-speak byte-identical (the guard
    wraps the whole wiring; the legacy string survives as the
    draw-failure fallback)
  - the repeat guard is re-keyed to (guid, DenyReason) - the old
    exact-text compare was defeated by ring rotation
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SECURITY_CPP = ROOT / "native" / "playerbots" / "playerbot" / "PlayerbotSecurity.cpp"
PERSONA_H = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmPersona.h"
PERSONA_CPP = ROOT / "native" / "patches" / "playerbots" / "PlayerbotLlmPersona.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"

INVITE_FAMILY = (
    "DenyReason::PLAYERBOT_DENY_INVITE",
    "DenyReason::PLAYERBOT_DENY_IS_LEADER",
    "DenyReason::PLAYERBOT_DENY_NOT_LEADER",
    "DenyReason::PLAYERBOT_DENY_FULL_GROUP",
)


def refusal_wiring() -> str:
    """The E3 wiring slice at the live refusal-delivery tail."""
    src = SECURITY_CPP.read_text(encoding="utf-8")
    return src.split("std::string text = out.str();")[1].split("return false;")[0]


def test_persona_header_included_with_module_convention():
    src = SECURITY_CPP.read_text(encoding="utf-8")
    # the overlay lands in bot_root; submodule files reach it through the
    # module include convention (the PlayerbotLlmMemory.h precedent)
    assert '#include "playerbot/PlayerbotLlmPersona.h"' in src


def test_persona_export_exists():
    header = PERSONA_H.read_text(encoding="utf-8")
    assert "static std::string SecurityRefusalLine(Player* bot);" in header
    persona = PERSONA_CPP.read_text(encoding="utf-8")
    # the draw falls back internally (the BusyReply precedent) - the
    # wiring site only consumes the return value
    impl = persona.split("std::string PlayerbotLlmPersona::SecurityRefusalLine")[1]
    assert "llmBusyReply" in impl.split("}")[0]


def test_wiring_at_the_live_site_with_the_banter_guard():
    wiring = refusal_wiring()
    # the kill-switch guard wraps the whole wiring (0 = the legacy
    # UI-speak string, byte-identical)
    assert "sPlayerbotAIConfig.llmBanterEnabled && (" in wiring
    assert wiring.index("llmBanterEnabled") < wiring.index("SecurityRefusalLine")
    # the line draws through the persona export and keeps the legacy
    # text as the draw-failure fallback
    assert "PlayerbotLlmPersona::SecurityRefusalLine(bot)" in wiring
    assert "if (!refusal.empty())" in wiring
    assert "text = refusal;" in wiring


def test_only_the_invite_family_reasons_are_wired():
    wiring = refusal_wiring()
    for reason in INVITE_FAMILY:
        assert reason in wiring, f"invite-family reason wired: {reason}"
    # the actionable denials keep their existing messages: they tell the
    # player something they can FIX, not a gate to talk past
    for not_wired in ("PLAYERBOT_DENY_LOW_LEVEL", "PLAYERBOT_DENY_GEARSCORE",
                      "PLAYERBOT_DENY_BG", "PLAYERBOT_DENY_LFG"):
        assert not_wired not in wiring, f"{not_wired} must keep its actionable text"
    src = SECURITY_CPP.read_text(encoding="utf-8")
    # exactly ONE persona call in the file - the switch's message cases
    # (including the LOW_LEVEL/GEARSCORE builders and the dead :203
    # TALK-switch INVITE case) are untouched
    assert src.count("PlayerbotLlmPersona::SecurityRefusalLine(bot)") == 1
    low_level = src.split("case DenyReason::PLAYERBOT_DENY_LOW_LEVEL:")[1].split(
        "case DenyReason::PLAYERBOT_DENY_GEARSCORE:")[0]
    assert "You are too low level" in low_level
    gearscore = src.split("case DenyReason::PLAYERBOT_DENY_GEARSCORE:")[1].split(
        "case DenyReason::PLAYERBOT_DENY_NOT_YOURS:")[0]
    assert "Your gearscore is too low" in gearscore
    assert "SecurityRefusalLine" not in low_level and "SecurityRefusalLine" not in gearscore


def test_dead_invite_case_untouched():
    src = SECURITY_CPP.read_text(encoding="utf-8")
    # the TALK-switch INVITE case (~:203, dead code - an INVITE reason
    # never reaches PLAYERBOT_SECURITY_TALK) keeps its literal and gains
    # no persona call
    talk_switch = src.split("case PlayerbotSecurityLevel::PLAYERBOT_SECURITY_TALK:")[1]
    dead_case = talk_switch.split("case DenyReason::PLAYERBOT_DENY_INVITE:")[1].split(
        "case DenyReason::PLAYERBOT_DENY_FAR:")[0]
    assert 'out << "Invite me to your group first";' in dead_case
    assert "SecurityRefusalLine" not in dead_case
    # the LIVE site (the PLAYERBOT_SECURITY_INVITE level arm) keeps the
    # same legacy literal as the fallback seed
    live_arm = src.split("case PlayerbotSecurityLevel::PLAYERBOT_SECURITY_INVITE:")[1]
    assert 'out << "Invite me to your group first";' in live_arm


def test_dedupe_rekeyed_to_guid_and_reason():
    wiring = refusal_wiring()
    # the remembered key is the REASON (ring rotation changes the text,
    # never the reason), not the delivered text
    assert "dedupeKey << (uint32)reason;" in wiring
    assert "whispers[guid][dedupeKey.str()]" in wiring
    src = SECURITY_CPP.read_text(encoding="utf-8")
    # the old exact-text compare is gone
    assert "whispers[guid][text]" not in src
    # the cooldown never drops below the operator's repeatDelay (the
    # kill-switch path speaks strictly less often, never more)
    assert "std::max<time_t>(8," in wiring
    assert "sPlayerbotAIConfig.repeatDelay / 1000" in wiring
    # the delivery still rides the same whisper channel
    assert "bot->Whisper(text, LANG_UNIVERSAL, ObjectGuid(guid));" in wiring


def test_banter_kill_switch_defaults_on():
    driver = DRIVER.read_text(encoding="utf-8")
    assert 'GetIntDefault("AiPlayerbot.LLMBanterEnabled", 1)' in driver
