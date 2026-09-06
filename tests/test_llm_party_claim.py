"""A3: the exactly-one party responder (claim-based) - host pins.

The deterministic pick half (PlayerbotLlmGates::SelectResponder, pure) is
already exercised by tests/test_llm_gates.py including the 0-responder
case; this file pins the runtime half that lives in the overlay tree:
  - PlayerbotLlmMemory carries the first-writer-wins claim (StateMutex,
    expired-claim pruning, the short claim window, the rotation stamp)
    and the per-speaker flood gate (2 s sliding window)
  - the candidates collector reuses the relationship-tier lookup and
    feeds the pure pick
  - the SayAction driver payload (PB_SAY_GATE_ANDROID) consumes
    SelectResponder + the claim at the TOP of the party block and ANDs
    the claim into the gated flow for the UNADDRESSED party/raid case
    only (round-3: SRC_RAID joins the CLAIM; recording/digest stay
    party-only) - the addressed bot bypasses, whisper/say never consult
    the claim
  - recording is preserved for ALL bots: the unaddressed leg keeps
    ConsumePendingAnswer AND adds NotePartyLine beside it
The FNV claim-key hash is the one piece of pure decision logic in the
new surface: its real body is extracted from the shipped .cpp and run on
the host against the published FNV-1a 64 vectors.
"""
from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
GATES_H = PATCHES / "PlayerbotLlmGates.h"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"


def android_anchor(name: str) -> str:
    """The ANDROID replacement text of a driver anchor constant."""
    return DRIVER.read_text(encoding="utf-8").split(name + ' = """')[1].split('"""')[0]


@pytest.fixture(scope="session")
def fnv_binary(tmp_path_factory):
    """Compiles the SHIPPED PartyMsgHash body (extracted verbatim) on the
    host so the claim key's pure half is executed, not just grepped."""
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    src = MEMORY_CPP.read_text(encoding="utf-8")
    m = re.search(
        r"uint64_t PlayerbotLlmMemory::PartyMsgHash\(std::string const& msg\)\s*\{(.*?)\n\}",
        src, re.S)
    assert m, "PartyMsgHash body not found in the shipped memory overlay"
    probe = tmp_path_factory.mktemp("fnv") / "fnv_probe.cpp"
    probe.write_text(
        "#include <cstdint>\n"
        "#include <cstdio>\n"
        "#include <string>\n"
        "static uint64_t PartyMsgHash(std::string const& msg)\n{\n" +
        m.group(1) +
        "\n}\n"
        "int main()\n"
        "{\n"
        "    if (PartyMsgHash(\"\") != 14695981039346656037ull) return 1;\n"
        "    if (PartyMsgHash(\"a\") != 0xaf63dc4c8601ec8cull) return 2;\n"
        "    if (PartyMsgHash(\"hello\") != 0xa430d84680aabd0bull) return 3;\n"
        "    if (PartyMsgHash(\"hi there\") != PartyMsgHash(\"hi there\")) return 4;\n"
        "    if (PartyMsgHash(\"Hi there\") == PartyMsgHash(\"hi there\")) return 5;\n"
        "    std::printf(\"fnv claim-key battery: OK\\n\");\n"
        "    return 0;\n"
        "}\n", encoding="utf-8")
    out = probe.with_suffix(".exe")
    compile = subprocess.run([gxx, "-std=c++11", "-O1", "-w", "-o", str(out), str(probe)],
                             capture_output=True, text=True, cwd=str(ROOT))
    assert compile.returncode == 0, compile.stderr[:2000]
    return out


def test_fnv_claim_key_runs_on_host(fnv_binary):
    result = subprocess.run([str(fnv_binary)], capture_output=True, text=True,
                            cwd=str(ROOT))
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "fnv claim-key battery: OK" in result.stdout


# ---- the memory surface (claim + candidates + flood gate) ------------------

def test_claim_helper_declared_and_mutex_guarded():
    header = MEMORY_H.read_text(encoding="utf-8")
    assert "static bool TryClaimPartyResponder(uint32 botGuid, uint32 speakerGuid," in header
    assert "static bool CollectPartyCandidates(uint32 groupId, uint32 speakerGuid," in header
    assert "static bool PartyFloodAdmits(uint32 speakerGuid);" in header
    assert "static uint64_t PartyMsgHash(std::string const& msg);" in header

    src = MEMORY_CPP.read_text(encoding="utf-8")
    claim = src.split("bool PlayerbotLlmMemory::TryClaimPartyResponder")[1].split(
        "bool PlayerbotLlmMemory::CollectPartyCandidates")[0]
    # the claim rides the existing StateMutex pattern
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in claim
    # expired claims are PRUNED (bounded state; a stale claim can never
    # block a later line)
    assert "claims.erase(itr)" in claim
    # first-writer-wins: an existing live claim refuses the second writer
    assert "first writer already holds this line" in claim
    # the short claim window (seconds: the N-bot fan-out resolves within
    # one tick, the window only covers cross-map stragglers)
    assert "claim.expiresAt = now + 5;" in claim
    # the winner stamps the rotation map (the anti-monopolization field
    # the pure SelectResponder consumes)
    assert "PartyLastWonMs()[botGuid] = " in claim


def test_candidates_collector_reuses_the_tier_lookup():
    src = MEMORY_CPP.read_text(encoding="utf-8")
    collect = src.split("bool PlayerbotLlmMemory::CollectPartyCandidates")[1].split(
        "bool PlayerbotLlmMemory::PartyFloodAdmits")[0]
    # tiers come from the EXISTING relationship lookup - reused, not
    # duplicated (a second tier implementation would drift from the
    # ceremony/standing tiers)
    assert "GetTrainedTier(member, speaker)" in collect
    assert "PlayerbotLlmGates::ResponderCandidate" in collect
    # the rotation snapshot is taken under the lock, but the DB-touching
    # tier lookups stay OUTSIDE it (StateMutex is never held across a
    # query)
    assert collect.index("std::lock_guard<std::mutex> lock(StateMutex());") < \
        collect.index("GetTrainedTier(member, speaker)")
    # bots only, alive only (a dead bot cannot answer)
    assert "member->GetPlayerbotAI()" in collect and "member->IsAlive()" in collect


def test_flood_gate_is_a_two_second_window_under_the_mutex():
    src = MEMORY_CPP.read_text(encoding="utf-8")
    flood = src.split("bool PlayerbotLlmMemory::PartyFloodAdmits")[1]
    flood = flood.split("\n}")[0]
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in flood
    assert "now - lastAt < 2" in flood, "N lines within 2 s = one generation"
    assert "lastAt = now;" in flood, "check-and-stamp: admission consumes the window"


# ---- the SayAction payload wiring ------------------------------------------

def test_payload_consumes_pick_and_claim_at_the_top_of_the_party_block():
    gate = android_anchor("PB_SAY_GATE_ANDROID")
    # round-3 R1#1 added the A1 refusal-log if with the same opening
    # prefix as the strategy gate, so the split key is the FULL gate
    # condition now (the refusal if reads !replyGateAllowed)
    party = gate.split("plan v5 C3: the roundtable row")[1].split(
        "if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed"
        " && partyResponderClaimed && replyGateAllowed")[0]
    # the pick + claim are computed INSIDE the party block, BEFORE the
    # strategy gate below it (losers skip context-building entirely, not
    # just dispatch)
    assert "bool partyResponderClaimed = true;" in party
    assert "PlayerbotLlmMemory::CollectPartyCandidates(" in party
    assert "PlayerbotLlmGates::SelectResponder(partyCandidates, addressedBotGuid)" in party
    assert "PlayerbotLlmMemory::TryClaimPartyResponder(" in party
    assert "PlayerbotLlmMemory::PartyFloodAdmits(gateSpeaker->GetGUIDLow())" in party
    assert "PlayerbotLlmMemory::PartyMsgHash(msg)" in party
    # only the PICKED bot may spend the flood window and the claim
    assert "pickedResponder == bot->GetGUIDLow()" in party
    # the claim leg is the unaddressed cloud-lane party reply arm only
    assert ("if (!addressedToBot && CloudLaneOpen() &&\n"
            "            sPlayerbotAIConfig.llmPartyReplyEnabled != 0)") in party
    # the claim ANDs into the gated flow (the round-1 R1#3 consume folds
    # the strategy arm through ReplyGateAllowed; the claim read rides
    # the same gate line)
    gate_line = gate.split(
        "if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed"
        " && partyResponderClaimed && replyGateAllowed")[1]
    assert gate_line.startswith("\n    )"), \
        "the responder claim ANDs into the strategy gate"


def test_recording_preserved_for_all_bots_on_the_unaddressed_leg():
    gate = android_anchor("PB_SAY_GATE_ANDROID")
    party = gate.split("plan v5 C3: the roundtable row")[1].split(
        "if (bot->GetPlayerbotAI()")[0]
    # round-3 R1#1 nested the recording/digest legs under a SRC_PARTY
    # guard (raid joins the CLAIM only; raid recording stays the
    # round-1 adjudicated MINOR residue) - the legs keep their shape at
    # the deeper indent
    assert ("if (chatChannelSource == ChatChannelSource::SRC_PARTY)\n"
            "        {") in party
    recording = party.split(
        "if (chatChannelSource == ChatChannelSource::SRC_PARTY)")[1].split(
        "if (!addressedToBot && CloudLaneOpen()")[0]
    # the armed-ask consumption survives (the W5 pin in test_llm_recall
    # pins the same shape) ...
    assert "else\n                PlayerbotLlmMemory::ConsumePendingAnswer" in recording
    # ... and the unaddressed leg now records the line for the
    # roundtable too - recording for ALL bots, generation for ONE
    assert ("if (!addressedToBot)\n"
            "                PlayerbotLlmChatter::NotePartyLine(gateSpeaker->GetGUIDLow(), msg);") in recording
    # the C4 digest legs sit inside the same party-only guard
    assert "PlayerbotLlmMemory::NotePartyDigestLine(" in recording
    assert "PlayerbotLlmMemory::MaybeMintPartyDigest(" in recording


def test_addressed_whisper_and_say_never_consult_the_claim():
    gate = android_anchor("PB_SAY_GATE_ANDROID")
    # exactly ONE declaration, ONE clearing, ONE claim assignment, ONE
    # read in the round-3 A1 refusal log, ONE read in the strategy
    # gate: the claim is consulted nowhere else (whisper/say legs have
    # no party claim)
    assert gate.count("partyResponderClaimed") == 5
    # the clearing site is inside the party block AND gated on the
    # unaddressed leg - the addressed bot bypasses the claim entirely
    # (its line names it). The slice ends at the strategy gate (the
    # round-1 R1#3 consume restructured the gate to fold through
    # ReplyGateAllowed; the claim read still lives there and nowhere
    # else; the round-3 refusal log reads it once between them)
    party = gate.split("plan v5 C3: the roundtable row")[1].split(
        "if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed"
        " && partyResponderClaimed && replyGateAllowed")[0]
    # declaration + clearing + claim assignment + the refusal-log read
    # inside the block; the 5th (and only other) use is the
    # strategy-gate read itself
    assert party.count("partyResponderClaimed") == 4
    # the claim defaults TRUE, so with the cloud arm off the gate's
    # behavior is byte-identical to the pre-A3 payload (HardTriggerAllowed
    # already returns false for unaddressed party lines on that lane)
    assert "bool partyResponderClaimed = true;" in party


def test_addressed_line_resolves_the_pick_to_the_named_bot_round4():
    """Round-4 R1 + round-5 R1: an ADDRESSED party/raid line must yield
    exactly ONE generation - the named bot's own dispatch (its claim
    bypass). The non-named bots compute the SAME addressedBotGuid from
    the same msg + group (BOT members only - a named player addresses
    no bot and the line stays unaddressed for the ordering pick), the
    pick resolves to it, and every bystander loses the claim (no second
    responder, no Tier I double-burn - the plan's own A3.2 sketch
    parameter). An addressee that is no candidate (dead) picks NOBODY:
    bystanders stand down - never a second generation beside the
    addressee's own turn."""
    gate = android_anchor("PB_SAY_GATE_ANDROID")
    party = gate.split("plan v5 C3: the roundtable row")[1].split(
        "if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed"
        " && partyResponderClaimed && replyGateAllowed")[0]
    claim_leg = party.split("if (!addressedToBot && CloudLaneOpen()")[1]
    # the addressed guid is computed INSIDE the claim leg from the
    # group's BOT members via the canonical name matcher
    assert "uint32 addressedBotGuid = 0;" in claim_leg
    assert "namedMember->GetPlayerbotAI() &&" in claim_leg
    assert ("PlayerbotLlmGates::ContainsNameIgnoreCase(msg, "
            "namedMember->GetName())") in claim_leg
    assert "addressedBotGuid = namedMember->GetGUIDLow();" in claim_leg
    # ... and the pick consumes it
    assert ("PlayerbotLlmGates::SelectResponder(partyCandidates, "
            "addressedBotGuid)") in claim_leg
    # round-5 R1: the pure pick STANDS DOWN when the addressed guid
    # resolves to no candidate (dead/absent addressee) - never the
    # ordering fallthrough
    header = GATES_H.read_text(encoding="utf-8")
    select = header.split("inline std::uint32_t SelectResponder")[1]
    select = select.split("\n    }")[0]
    assert "return 0; // the addressee cannot answer via the claim" in select


def test_raid_arm_shares_the_exactly_one_claim_round3():
    """Round-3 R1#1: SRC_RAID unaddressed lines are admitted by the
    widened HardTriggerAllowed on the cloud lane, so the claim must
    govern them - ONE generation per line, never one per raid bot (the
    fan-out A3 exists to prevent; it would also burn N interactive-
    budget admissions against the speaker's Tier I cap)."""
    gate = android_anchor("PB_SAY_GATE_ANDROID")
    party = gate.split("plan v5 C3: the roundtable row")[1].split(
        "if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed"
        " && partyResponderClaimed && replyGateAllowed")[0]
    # the block's outer condition covers BOTH group channels
    assert "(chatChannelSource == ChatChannelSource::SRC_PARTY ||" in party
    assert "chatChannelSource == ChatChannelSource::SRC_RAID)" in party
    # the claim body is SHARED - one clearing and one assignment serve
    # both channels (no raid duplicate that could drift from the party
    # sequence)
    assert party.count("partyResponderClaimed = false;") == 1
    assert party.count(
        "partyResponderClaimed = PlayerbotLlmMemory::TryClaimPartyResponder(") == 1
    # and the claim leg itself is still the unaddressed cloud-lane arm
    # with the default-0 key (device lane: byte-identical - the raid
    # leg clears nothing there because CloudLaneOpen() is false)
    claim_leg = party.split("if (!addressedToBot && CloudLaneOpen()")[1]
    assert "sPlayerbotAIConfig.llmPartyReplyEnabled != 0)" in claim_leg


def test_toggle_off_is_addressed_only_via_the_default0_arm():
    driver = DRIVER.read_text(encoding="utf-8")
    assert 'GetIntDefault("AiPlayerbot.LLMPartyReplyEnabled", 0)' in driver, \
        "the party reply arm defaults 0 (addressed-only until staged on)"
