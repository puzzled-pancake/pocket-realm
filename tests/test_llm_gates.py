"""PlayerbotLlmGates.h - the WS-A gate battery (plan v2.3 T1).

Compiles the shipped pure header on the host (-std=c++11, the repo's
core convention) and runs the full decision matrix; then pins the world-
side contracts:
  - the header IS the 22nd overlay (driver copy list + registry + the
    patches_content pin covers the bytes)
  - SayAction bridges the mirror GateSrc enum with static_asserts so an
    upstream ChatChannelSource renumber fails the build
  - the live CloudLaneOpen() conjunction (key AND tier) lives beside
    ExternalApiTierActive; no widened site may key on the bare key
"""
from __future__ import annotations

import importlib.util
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
GATES_H = PATCHES / "PlayerbotLlmGates.h"
HARNESS = ROOT / "tools" / "test_llm_gates.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"


@pytest.fixture(scope="session")
def gates_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    tmp = tmp_path_factory.mktemp("gates")
    exe = tmp / "gates_host.exe"
    result = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall", "-I", str(PATCHES),
         "-o", str(exe), str(HARNESS)],
        capture_output=True, text=True, cwd=str(ROOT))
    assert result.returncode == 0, result.stderr[:2000]
    return exe


def test_host_battery(gates_binary):
    result = subprocess.run([str(gates_binary)], capture_output=True,
                            text=True, cwd=str(ROOT))
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "llm gates battery: OK" in result.stdout


def test_header_is_pure_no_io_clock_config_or_state():
    text = GATES_H.read_text(encoding="utf-8")
    for banned in ("sLog", "sWorld", "time(", "GetTickCount", "std::cin",
                   "std::cout", "fopen", "sPlayerbotAIConfig", "static "
                   "std::map", "std::mutex"):
        assert banned not in text, f"scope fence breached: {banned}"


def test_overlay_registration_is_the_22nd_file():
    driver_text = DRIVER.read_text(encoding="utf-8")
    assert '(bot_root / "PlayerbotLlmGates.h").write_bytes' in driver_text
    assert '"playerbot/PlayerbotLlmGates.h",' in driver_text
    copies = [line for line in driver_text.splitlines()
              if line.strip().startswith("(bot_root /") and
              ".write_bytes((NATIVE / \"patches\" / \"playerbots\"" in line]
    assert len(copies) == 22, (
        f"expected 22 whole-file overlay copies, found {len(copies)}")


def test_sayaction_bridges_the_src_enum_with_static_asserts():
    driver_text = DRIVER.read_text(encoding="utf-8")
    # the bridge rides the SayAction include anchor payload
    assert "static_assert" in driver_text
    assert "PlayerbotLlmGates::GATE_SRC_WHISPER" in driver_text
    assert "SRC_WHISPER" in driver_text


def test_live_conjunction_beside_the_tier_helper():
    memory = MEMORY_H.read_text(encoding="utf-8")
    assert "inline bool CloudLaneOpen()" in memory
    # the conjunction: the key AND the live tier check, never the key alone
    assert "sPlayerbotAIConfig.llmCloudChatter != 0 &&" in memory
    assert "PlayerbotLlmMemory::ExternalApiTierActive()" in memory


def test_key_defaults_match_the_plan_rows():
    driver_text = DRIVER.read_text(encoding="utf-8")
    for key, default in (
        ("LLMCloudChatter", 1),
        ("LLMPartyReplyEnabled", 0),
        ("LLMCloudStreetSayPct", 25),
        ("LLMStreetSayPerDay", 200),
        ("LLMRpgChatPerDay", 300),
        ("LLMBotToBotPerDay", 300),
        ("LLMCloudLineBudgetPerHour", 90),
        ("LLMCloudInteractivePerPlayerHour", 240),
        ("LLMDialogueFastLane", 1),
    ):
        needle = f'"AiPlayerbot.{key}", {default}'
        assert needle in driver_text, needle


# ---- round-1 fix pins: the A7.1 tier-I interactive budget ---------------
def _driver_module():
    spec = importlib.util.spec_from_file_location("driver_gates_t1", DRIVER)
    module = importlib.util.module_from_spec(spec)
    sys.modules["driver_gates_t1"] = module
    spec.loader.exec_module(module)
    return module


def test_interactive_budget_bounds_tier_one_at_the_generate_chokepoint():
    # round-1 R1#1/R7#1: the per-player hourly tier-I budget is WIRED -
    # interactive cloud turns (a real player's whisper / addressed say /
    # party-responder reply) pass through InteractiveBudgetAdmits inside
    # Generate; autonomous CHAT_REPLY turns pass speakerGuid 0 and stay
    # arbiter-owned; exhaustion returns the busy marker (the duty-cycle
    # persona-line shape), logged class=busy.
    driver = _driver_module()
    payload = driver.PB_LLM_IFACE_CPP_ANDROID
    assert ("if (source == PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY && speakerGuid &&\n"
            "        !PlayerbotLlmMemory::InteractiveBudgetAdmits(speakerGuid))") in payload
    busy_block = payload.split("InteractiveBudgetAdmits(speakerGuid))")[1][:220]
    assert 'logEnd("busy");' in busy_block
    assert "return std::string(POCKETREALM_LLM_BUSY);" in busy_block
    # the interface payload can reach the memory header
    assert '#include "PlayerbotLlmMemory.h"' in driver.PB_IFACE_INCLUDE_ANDROID


def test_interactive_turns_are_exempt_from_the_ambient_arbiter():
    # the exemption half of A7.1: the ambient arbiter key family never
    # appears in the interface payload - the CHAT_REPLY path is bounded
    # by the per-player budget, never by the ambient cap
    driver = _driver_module()
    payload = driver.PB_LLM_IFACE_CPP_ANDROID
    for banned in ("AuthoredLineAdmits", "llmCloudLineBudgetPerHour",
                   "AuthoredBudgetHasRoom"):
        assert banned not in payload, banned


def test_interactive_budget_semantics_kill_switch_and_device_lane():
    # the helper's own law (overlay source contract): 0 disables
    # interactive cloud replies; the device lane returns true BEFORE the
    # cap check (byte-identical device behavior, no counter growth)
    src = (PATCHES / "PlayerbotLlmMemory.cpp").read_text(encoding="utf-8")
    body = src.split("bool PlayerbotLlmMemory::InteractiveBudgetAdmits")[1].split("\n}")[0]
    assert "if (!ExternalApiTierActive())" in body
    assert body.index("if (!ExternalApiTierActive())") < body.index("if (!cap)")
    assert "if (!cap)\n        return false;" in body
    assert "used.second >= cap" in body


def test_ambient_budget_zero_blocks_only_non_exempt_lines():
    # T1's budget-0 law (round-1 R7#2): llmCloudLineBudgetPerHour = 0
    # means the arbiter refuses to admit NON-exempt lines but never
    # blocks an exempt one (the !globalCap => return exempt arm)
    src = (PATCHES / "PlayerbotLlmMemory.cpp").read_text(encoding="utf-8")
    assert "if (!globalCap)\n        return exempt;" in src


def test_bot2bot_containment_is_wired_at_every_chance_site():
    # round-1 R1#2: the bot2bot daily quota + autonomous-exchange depth
    # cap ride the autonomous arm of ALL FOUR chance sites
    # (SayToGuild/Yell/Say/SayToParty) - likePlayer sends stay un-gated.
    driver = _driver_module()
    assert "PlayerbotLlmMemory::BotToBotAdmits(bot->GetGUIDLow())" in driver.PB_AI_B2B_GUILD_ANDROID
    assert "PlayerbotLlmMemory::BotToBotAdmits(bot->GetGUIDLow())" in driver.PB_AI_B2B_SITE_ANDROID
    # the chained registration: the unique guild site + three identical
    # sites walked in file order
    text = DRIVER.read_text(encoding="utf-8")
    assert text.count('replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_B2B_SITE_UPSTREAM, PB_AI_B2B_SITE_ANDROID)') == 3
    assert text.count('replace_anchor(bot_root / "PlayerbotAI.cpp", PB_AI_B2B_GUILD_UPSTREAM, PB_AI_B2B_GUILD_ANDROID)') == 1
    # the pristine file carries exactly the four sites the anchors claim
    pristine = (ROOT / "native" / "playerbots" / "playerbot" / "PlayerbotAI.cpp").read_text(encoding="utf-8", errors="replace")
    assert pristine.count("llmBotToBotChatChance))") == 4


def test_bot2bot_containment_semantics():
    # the helper's own law (overlay source contract): cloud-lane-only
    # (device byte-identity), the daily quota via CloudQuotaAdmits
    # (0 = surface off), and the per-bot consecutive depth cap reset by
    # a real-player trigger. Depth is checked BEFORE the quota (round-2
    # R1#1/R8#1): a later stage's rejection must not spend an earlier
    # stage's quota - the street-ladder law.
    src = (PATCHES / "PlayerbotLlmMemory.cpp").read_text(encoding="utf-8")
    body = src.split("bool PlayerbotLlmMemory::BotToBotAdmits")[1].split("\n}")
    fn = body[0]
    assert 'if (!CloudQuotaAdmits("bot2bot", sPlayerbotAIConfig.llmBotToBotPerDay))' in fn
    assert "if (!ExternalApiTierActive())" in fn
    assert fn.index("if (!ExternalApiTierActive())") < fn.index("CloudQuotaAdmits")
    assert "BOT2BOT_MAX_CONSECUTIVE" in fn
    assert fn.index("BOT2BOT_MAX_CONSECUTIVE") < fn.index("CloudQuotaAdmits"), \
        "the depth check must precede the quota spend"
    reset = src.split("void PlayerbotLlmMemory::NoteBotPlayerInteraction")[1].split("\n}")[0]
    assert "BotToBotConsecutive().erase(botGuid);" in reset
    # the reset is stamped beside the say-path player-interaction stamp
    driver = _driver_module()
    say = driver.PB_SAY_GATE_ANDROID
    assert "PlayerbotLlmMemory::NoteBotPlayerInteraction(bot->GetGUIDLow());" in say


def test_every_declared_gate_helper_is_consumed_not_copied():
    # round-1 R1#3: the consume-not-copy law - each pure helper has a
    # real runtime call site; no site keeps an inlined equivalent.
    driver = _driver_module()
    gate_say = driver.PB_SAY_GATE_ANDROID
    # the word-boundary name law replaces the substring icontains
    assert "PlayerbotLlmGates::ContainsNameIgnoreCase(msg, bot->GetName())" in gate_say
    assert "boost::algorithm::icontains" not in gate_say
    # the strategy gate folds through the reply-gate helper
    assert "PlayerbotLlmGates::ReplyGateAllowed(" in gate_say
    assert 'bot->GetPlayerbotAI()->HasStrategy("ai chat", BotState::BOT_STATE_NON_COMBAT) || sPlayerbotAIConfig.llmEnabled == 3 || CloudLaneOpen()' not in gate_say
    # the say-path response classification runs through the fold
    gen = driver.PB_SAY_GEN_DEF_ANDROID
    assert "PlayerbotLlmGates::ClassifyGeneration(" in gen
    # the street ladder's verdict is the fold's verdict
    src = (PATCHES / "PlayerbotLlmMemory.cpp").read_text(encoding="utf-8")
    assert 'PlayerbotLlmGates::StreetAdmissionOrder(worldWindowClaimed,' in src
    ladder = src.split("bool worldWindowClaimed = true;")[1].split('!= "dispatch")')[0]
    # laziness preserved: the quota only spends after the pct roll hits
    assert ladder.index("pctRollHit") < ladder.index("CloudQuotaAdmits")


def test_rpgchat_quota_spends_after_the_cheap_guards():
    """Round-4 R7#1: the street-ladder cheap-before-expensive law holds
    on the rpgchat arm too (the round-3 R1#3 fix, now pinned) - a
    reset-but-unrearmed chatLine or a pending packet burst burns no
    realm-global admission."""
    driver = _driver_module()
    payload = driver.PB_RPG_QUOTA_ANDROID
    quota = payload.index('CloudQuotaAdmits("rpgchat"')
    assert payload.index("if (packets.size())") < quota
    assert payload.index("if (futPackets.valid())") < quota
    assert payload.index("if (chatLine == -1)") < quota
