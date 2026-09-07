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
    assert ("static bool TryClaimPartyResponder(uint32 botGuid, uint32 speakerGuid,\n"
            "        uint64_t msgHash);") in header
    assert "static bool TryStandDownPartyLine(uint32 speakerGuid, uint64_t msgHash);" in header
    assert "static bool CollectPartyCandidates(uint32 groupId, uint32 speakerGuid," in header
    assert "static bool PartyFloodAdmits(uint32 speakerGuid);" in header
    assert "static bool PartyClaimWindowElapsed(time_t lineTime);" in header
    assert "static void PartyFloodRefund(uint32 speakerGuid, time_t stampedAt);" in header
    assert "static uint64_t PartyMsgHash(std::string const& msg);" in header

    src = MEMORY_CPP.read_text(encoding="utf-8")
    claim = src.split("bool PlayerbotLlmMemory::TryClaimPartyResponder")[1].split(
        "bool PlayerbotLlmMemory::TryStandDownPartyLine")[0]
    # the claim rides the existing StateMutex pattern
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in claim
    # expired claims are PRUNED (bounded state; a stale claim can never
    # block a later line)
    assert "claims.erase(itr)" in claim
    # first-writer-wins: an existing live CURRENT-GENERATION claim
    # refuses the second writer (round-12: prior-line residue is
    # erased by the generation-scoped check - see the round-12 test)
    assert "if (TokenOwnsCurrentLine(key, heardItr->second))" in claim
    # round-6 R1: the window EXCEEDS every reachable chat-drain stagger
    # (UpdateAIInternal delays run 3-7 s on teleport/cast chains - the
    # 5 s window expired before late bystanders evaluated the line and
    # the rotation stamp armed the next tie-order bot to re-claim it)
    assert "int64_t const PARTY_CLAIM_WINDOW_SECONDS = 30;" in src
    assert "claim.expiresAt = now + PARTY_CLAIM_WINDOW_SECONDS;" in claim
    # the winner stamps the rotation map (the anti-monopolization field
    # the pure SelectResponder consumes)
    assert "PartyLastWonMs()[botGuid] = " in claim
    # round-8 R1: the key is GROUP-FREE end to end (decl, key function,
    # both call shapes) - a listener that switches groups between
    # receive and drain must still compute the key that owns the line
    assert "uint64 PartyClaimKey(uint32 speakerGuid, uint64_t msgHash)" in src
    key_body = src.split("uint64 PartyClaimKey(uint32 speakerGuid, uint64_t msgHash)")[1]
    key_body = key_body.split("\n}")[0]
    assert "groupId" not in key_body, "the claim key must not mix the group id"


def test_stand_down_marker_owns_the_addressed_line_round6():
    """Round-6 R1 (addressed-line sibling): an ADDRESSED line stands
    down with a MARKER in the same claim map - a staggered late drain
    (after the addressee left the group mid-fan-out) sees a live marker
    and refuses instead of taking a fresh ordering pick beside the
    addressee's still-queued own turn."""
    src = MEMORY_CPP.read_text(encoding="utf-8")
    marker = src.split("bool PlayerbotLlmMemory::TryStandDownPartyLine")[1].split(
        "bool PlayerbotLlmMemory::CollectPartyCandidates")[0]
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in marker
    # first writer wins - a CURRENT-generation claim or marker owns
    # the line (round-12: prior-line residue is erased, not obeyed)
    assert ("a current-generation claim or marker owns the line"
            in marker)
    # the marker is a winner-0 claim with the SAME window
    assert "marker.botGuid = 0;" in marker
    assert "marker.expiresAt = now + PARTY_CLAIM_WINDOW_SECONDS;" in marker
    # and the payload stamps it on the addressed leg (first bystander)
    gate = android_anchor("PB_SAY_GATE_ANDROID")
    party = gate.split("plan v5 C3: the roundtable row")[1].split(
        "if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed"
        " && partyResponderClaimed && replyGateAllowed")[0]
    claim_leg = party.split("if (!addressedToBot && CloudLaneOpen()")[1]
    assert "else if (addressedBotGuid != 0)" in claim_leg
    assert "PlayerbotLlmMemory::TryStandDownPartyLine(" in claim_leg
    # the lock-order contract is now STATED where the mutex lives
    assert "LOCK-ORDER CONTRACT" in src.split("std::mutex& StateMutex")[0].split(
        "// verified-event window")[1]


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
    # read in the round-3 A1 refusal log, ONE read in the round-7 flood
    # refund, ONE read in the strategy gate: the claim is consulted
    # nowhere else (whisper/say legs have no party claim)
    assert gate.count("partyResponderClaimed") == 6
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
    # + the round-7 refund read inside the block; the 6th (and only
    # other) use is the strategy-gate read itself
    assert party.count("partyResponderClaimed") == 5
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


# ---- round-7 R1: the timing layer closed at both ends -----------------------

def test_fanout_stamp_covers_the_leave_before_first_drain_hole_round7():
    """Round-7 R1 MAJOR#2: the drain-time bystander marker only existed
    once a bystander drained while the addressee was still a member -
    an addressee that left (kick/leave) before ANY bystander drained
    left late bystanders a fresh ordering pick beside the addressee's
    still-queued own turn. The ADDRESSEE's own receive now stamps the
    marker at fan-out time (world thread, inside the receive handler -
    no drain can interleave), so the marker exists for every later
    interleaving. Round-8 R1: the stamp sits AFTER the queue push so its
    clock read is >= the entry's m_time (a pre-push stamp could land one
    second earlier when the clock ticks between them, and the drain TTL
    measures from m_time - an earlier-expiring marker re-opened the
    boundary second)."""
    queue_call = android_anchor("PB_AI_QUEUE_CALL_ANDROID")
    # the stamp consults the CANONICAL matcher (agreeing with the drain
    # gate's addressedToBot exactly - the raw substring isMentioned
    # would miss case-variant mentions and re-open the hole)
    assert ("PlayerbotLlmGates::ContainsNameIgnoreCase(message, "
            "bot->GetName())") in queue_call
    # gated on the full claim-surface armament (device lane and the
    # staged default-0 key never stamp: byte-identical)
    assert queue_call.index("if (isAiChat && CloudLaneOpen() &&") < \
        queue_call.index("PlayerbotLlmGates::ContainsNameIgnoreCase(message,")
    # round-10 R1 restructure: the lane gate ends at the partyReply key
    # (the name matcher nests inside) - the key still gates BOTH the
    # registry write and the marker stamp
    assert queue_call.index("sPlayerbotAIConfig.llmPartyReplyEnabled != 0") < \
        queue_call.index("PlayerbotLlmMemory::NotePartyLineHeard(")
    # party/raid only, real-player speaker only
    assert ("(stampChannel == ChatChannelSource::SRC_PARTY ||" in queue_call and
            "stampChannel == ChatChannelSource::SRC_RAID)" in queue_call)
    assert "stampSpeaker->isRealPlayer()" in queue_call
    # the SAME group-free claim key the drain path computes (speaker
    # low + the one hash implementation) - a different key would be a
    # different line
    assert "PlayerbotLlmMemory::TryStandDownPartyLine(" in queue_call
    assert "PlayerbotLlmMemory::PartyMsgHash(message)" in queue_call
    # and the stamp rides the fan-out but AFTER the push (round-8 R1:
    # program order makes the marker's stamp >= the entry's m_time, so
    # the marker outlives every non-dropped drain of the line)
    stamp_at = queue_call.index("PlayerbotLlmMemory::TryStandDownPartyLine(")
    push_at = queue_call.index("QueueChatResponse(msgtype, guid1,")
    assert push_at < stamp_at, \
        "the marker must stamp at >= the push's clock read (the straddle law)"
    # Round-10 R1 (the first-heard registry): EVERY member's receive
    # min-stamps the line's earliest heard instant - inside the same
    # lane/channel/speaker gate, AFTER the push, BEFORE the marker
    # stamp (the registry must lower-bound every later stamp); the
    # claim grant consults it (TryClaimPartyResponder) so a claim can
    # never post-date every prior token's expiry at any fan-out
    # straddle or drain clock divergence.
    heard_at = queue_call.index("PlayerbotLlmMemory::NotePartyLineHeard(")
    assert push_at < heard_at < stamp_at, \
        "the registry stamps between the push and the marker (min-bound law)"
    # round-11 R7 MINOR: the channel/speaker gate must CONTAIN both
    # writes (presence-only pins survived a de-nested matcher in R7's
    # compiled mutant) - the verbatim nested shape is pinned
    nested = (
        "stampSpeaker && stampSpeaker->isRealPlayer())\n"
        "                    {\n"
        "                        PlayerbotLlmMemory::NotePartyLineHeard(\n"
        "                            stampSpeaker->GetGUIDLow(),\n"
        "                            PlayerbotLlmMemory::PartyMsgHash(message));\n"
        "                        if (PlayerbotLlmGates::ContainsNameIgnoreCase(message, bot->GetName()))\n"
        "                        {\n"
        "                            PlayerbotLlmMemory::TryStandDownPartyLine(\n"
        "                                stampSpeaker->GetGUIDLow(),\n"
        "                                PlayerbotLlmMemory::PartyMsgHash(message));\n"
        "                        }\n"
        "                    }"
    )
    assert nested in queue_call, \
        "both the registry write and the addressee marker sit inside the channel/speaker gate"
    # the channel/speaker gate resolves ONCE and the canonical matcher
    # now nests INSIDE it (the marker stays addressee-only; the
    # registry write is the every-member part)
    assert queue_call.count("ChatChannelSource stampChannel =") == 1
    assert queue_call.count("sObjectAccessor.FindPlayer(guid1)") == 1
    assert queue_call.count(
        "PlayerbotLlmGates::ContainsNameIgnoreCase(message,") == 1


def test_first_heard_registry_gates_the_claim_grant_round10():
    """Round-10 R1 MAJOR (the mid-drain clock divergence): the drain
    reads the clock at its TTL gate and AGAIN inside the claim/stand-
    down helpers, with real work between (ChatReplyDo's scans,
    CollectPartyCandidates' synchronous tier queries) - an entry
    admitted at the gate's second could hit a claim prune one second
    later where the line's marker/claim just died, and the straddled
    bystander claimed beside the original winner (R1's compiled probe:
    21/21 doubles exactly on the mid-drain tick cross). The exactly-one
    law is now anchored to the LINE, not any entry's m_time: every
    member's receive min-stamps the line's earliest heard instant
    (NotePartyLineHeard), and TryClaimPartyResponder grants only inside
    window-margin of it. Every marker/claim token is stamped at >= its
    writer's receive and so expires at >= firstHeard+window, while the
    grant bound is firstHeard+window-margin - a granted claim can
    never meet an expired prior token, whatever the fan-out straddle
    or the drain's mid-work clock divergence."""
    src = MEMORY_CPP.read_text(encoding="utf-8")
    # the registry: min-stamp under StateMutex, lazy prune at the window
    note = src.split("void PlayerbotLlmMemory::NotePartyLineHeard")[1]
    note = note.split("\n}")[0]
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in note
    assert "PartyLineHeardMap()" in note
    assert "heard[key] = now;" in note, \
        "first writer records its receive instant"
    assert ("else if (now < itr->second)" in note and
            "itr->second = now;" in note), \
        "a later member can only LOWER the stamp (the min law)"
    assert ("now - pruneItr->second > PARTY_CLAIM_WINDOW_SECONDS" in note), \
        "the registry prunes past the window (nothing admits after it)"
    # round-12 R1 (prune-before-insert): the PRUNE precedes the insert
    # - with the insert first, a past-window receive found the old
    # entry (min-law: no update), then the prune erased it and the
    # receive's instant was LOST (a lone-member repeat line left with
    # no registry, fail-closed where a fresh generation was owed)
    assert note.index("pruneItr") < note.index("heard[key] = now;"), \
        "the prune runs BEFORE the insert/min (deterministic re-registration)"
    # the claim-side gate: absent-or-stale firstHeard refuses the grant
    # (round-12: the gate runs BEFORE the ownership check - the residue
    # discriminator needs a live registry entry)
    claim = src.split("bool PlayerbotLlmMemory::TryClaimPartyResponder")[1]
    claim = claim.split("\n}")[0]
    freshness_at = claim.index(
        "std::map<uint64, int64_t> const& heard = PartyLineHeardMap();")
    owner_at = claim.index("if (TokenOwnsCurrentLine(key, heardItr->second))")
    grant_at = claim.index("PartyResponderClaim& claim = claims[key];")
    assert freshness_at < owner_at < grant_at, \
        "freshness gate, then generation-scoped ownership, then grant"
    gate_block = claim[freshness_at:owner_at]
    assert "return false;" in gate_block, \
        "stale-or-absent firstHeard refuses the grant (fail closed)"
    assert "heardItr == heard.end() ||" in gate_block, \
        "absent firstHeard is unprovable freshness - refuse"
    assert ("now - heardItr->second >=" in gate_block and
            "PARTY_CLAIM_WINDOW_SECONDS - "
            "PARTY_CLAIM_FANOUT_STRADDLE_SECONDS" in gate_block), \
        "the grant bound is the SAME window-minus-margin law as the drain TTL"
    # the header declares the registry beside the claim
    hdr = MEMORY_H.read_text(encoding="utf-8")
    assert ("static void NotePartyLineHeard(uint32 speakerGuid, "
            "uint64_t msgHash);" in hdr)


def test_stand_down_marker_carries_the_same_freshness_gate_round11():
    """Round-11 R1 MAJOR: TryStandDownPartyLine's second caller - the
    drain-side stand-down branch - runs under no isAiChat/strategy
    armament, so a strategy-less member (its receive never wrote the
    first-heard registry, its push carries the legacy +10-20 s
    stagger) could stamp the line's FIRST marker token at a clock
    read BELOW firstHeard while the fan-out was stalled behind
    mid-drain members: the addressee's receive-marker was then
    refused (first writer), and once the addressee left, a bystander
    claim in [marker_expiry, firstHeard+window-margin] - a window the
    ungated stamp itself prices at >= 2 s - granted beside the
    original winner (R1's probe: 893 doubles over 5043 combos,
    minimal straddle 12 s). The marker now carries the claim's own
    gate: absent-or-stale firstHeard refuses - and with it every
    ACCEPTED token stamp >= firstHeard, restoring the grant proof's
    premise for both legs (the receive-path caller always passes:
    its own registry write precedes it in the same handler)."""
    src = MEMORY_CPP.read_text(encoding="utf-8")
    marker = src.split("bool PlayerbotLlmMemory::TryStandDownPartyLine")[1]
    marker = marker.split("\n}")[0]
    freshness_at = marker.index(
        "std::map<uint64, int64_t> const& heard = PartyLineHeardMap();")
    owner_at = marker.index(
        "if (TokenOwnsCurrentLine(key, heardItr->second))")
    grant_at = marker.index("PartyResponderClaim& marker = claims[key];")
    assert freshness_at < owner_at < grant_at, \
        "freshness gate, then generation-scoped ownership, then stamp"
    gate_block = marker[freshness_at:owner_at]
    assert "return false;" in gate_block, \
        "stale-or-absent firstHeard refuses the marker (fail closed)"
    assert "heardItr == heard.end() ||" in gate_block, \
        "an absent registry can never anchor a marker stamp"
    assert ("now - heardItr->second >=" in gate_block and
            "PARTY_CLAIM_WINDOW_SECONDS - "
            "PARTY_CLAIM_FANOUT_STRADDLE_SECONDS" in gate_block), \
        "the marker bound is the SAME window-minus-margin law (one law)"
    # round-12 R7 MINOR: pin the gate block VERBATIM so a whole-
    # condition sense inversion (De Morgan wrap preserving every
    # substring) cannot survive
    assert ("    if (heardItr == heard.end() ||\n"
            "        now - heardItr->second >=\n"
            "            PARTY_CLAIM_WINDOW_SECONDS - "
            "PARTY_CLAIM_FANOUT_STRADDLE_SECONDS)\n"
            "        return false;" in marker), \
        "the marker gate's exact condition is pinned verbatim"


def test_residue_token_from_a_prior_line_is_erased_round12():
    """Round-12 R1 MAJOR (the cross-line residue): the claim key is
    line-INSTANCE-blind (speaker+hash), so a verbatim repeat past the
    window re-registers fresh in the first-heard registry while the
    PRIOR line's claim token can still be live. The repeat's
    addressee-marker was first-writer-REFUSED by the dead line's
    residue token; the addressee dispatched its own addressed-arm turn
    anyway (that arm never consults the claim), and once the residue
    died inside the REPEAT's grant window - with the addressee gone -
    a bystander claim granted beside it (R1's probe: 195,678 of
    226,800 combos double, minimum repeat offset +31 s, every
    ingredient an ordinary player action). The ownership check is now
    generation-scoped (TokenOwnsCurrentLine): every accepted stamp
    sits in [firstHeard, firstHeard+window-margin] of its OWN
    generation, so a current token expires at >= firstHeard+window;
    the registry prunes past the window and re-inserts fresh, so the
    new firstHeard strictly exceeds the prior generation's last
    possible stamp - a token expiring strictly before
    firstHeard+window is prior-line residue and is ERASED, letting
    the repeat line own its exactly-one."""
    src = MEMORY_CPP.read_text(encoding="utf-8")
    law = src.split("bool TokenOwnsCurrentLine")[1]
    law = law.split("\n}")[0]
    assert "claims.find(key)" in law
    assert ("itr->second.expiresAt <\n"
            "        firstHeard + PARTY_CLAIM_WINDOW_SECONDS" in law), \
        "the exact generation discriminator is pinned"
    assert "claims.erase(itr);" in law, \
        "residue is erased (erase-and-replace)"
    assert "return false;" in law and "return true;" in law
    # BOTH token writers consult the generation-scoped ownership check
    claim = src.split("bool PlayerbotLlmMemory::TryClaimPartyResponder")[1]
    claim = claim.split("\n}")[0]
    marker = src.split("bool PlayerbotLlmMemory::TryStandDownPartyLine")[1]
    marker = marker.split("\n}")[0]
    assert "if (TokenOwnsCurrentLine(key, heardItr->second))" in claim
    assert "if (TokenOwnsCurrentLine(key, heardItr->second))" in marker
    # round-13 R7 MINOR: the consult is pinned VERBATIM with its own
    # return - a mutant voiding the consult's BODY (call kept,
    # `return false;` gone) survived the substring-only pins above
    consult = ("    if (TokenOwnsCurrentLine(key, heardItr->second))\n"
               "        return false;")
    assert consult in claim, \
        "the claim's ownership consult is pinned verbatim with its return"
    assert consult in marker, \
        "the marker's ownership consult is pinned verbatim with its return"
    # and the claim's gate is verbatim-pinned too (R7's inversion class)
    assert ("    if (heardItr == heard.end() ||\n"
            "        now - heardItr->second >=\n"
            "            PARTY_CLAIM_WINDOW_SECONDS - "
            "PARTY_CLAIM_FANOUT_STRADDLE_SECONDS)\n"
            "        return false;" in claim), \
        "the claim gate's exact condition is pinned verbatim"


def test_generation_moved_past_drops_old_line_stragglers_round13():
    """Round-13 R1 MAJOR (the old-line straggler re-open): the round-12
    generation scoping made both freshness gates and the residue
    discriminator read the CURRENT registry generation, so a TTL-live
    straggled entry of a PRIOR identical-text line (its registry key
    re-registered fresh by a verbatim repeat past the window) passed
    them all - the gates measured its age against the NEW firstHeard,
    and TokenOwnsCurrentLine erased the old line's still-live winner
    token as residue - the straggler claimed and dispatched a second
    generation for the OLD line while the repeat's own responder was
    refused beside its token (R1's probe: 84,825/84,825 combos, both
    legs, three-line timelines). The drain's TTL gate now drops such
    entries FIRST: PartyClaimGenerationMovedPast is true exactly when
    the key's CURRENT firstHeard > lineTime - the entry predates the
    current generation and cannot belong to it."""
    src = MEMORY_CPP.read_text(encoding="utf-8")
    helper = src.split("bool PlayerbotLlmMemory::PartyClaimGenerationMovedPast")[1]
    helper = helper.split("\n}")[0]
    # the helper rides StateMutex like every registry consult (the
    # drain holds chatRepliesMutex and already nests StateMutex through
    # ChatReplyDo's claim leg - StateMutex stays the leaf)
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in helper
    # absent key -> false: the registry holds only armed-lane party/raid
    # real-speaker lines, so every other lane misses the lookup and
    # stays byte-identical
    assert "if (itr == heard.end())" in helper
    assert "return false;" in helper
    # unstamped entries and null speakers never drop (fail open only in
    # the no-proof direction, matching PartyClaimWindowElapsed)
    assert "if (!speakerGuid || lineTime == 0)" in helper
    # THE discriminator, verbatim: STRICTLY greater. firstHeard is the
    # MIN receive of the current generation and every member's registry
    # write follows its own queue push, so a same-generation entry
    # carries m_time >= firstHeard (== is CURRENT, never dropped; >=
    # here would drop every first-second same-generation entry), while
    # under the within-window straddle premise every prior-generation
    # entry carries m_time <= fh_old+30 < fh_new (always dropped; <=
    # would re-open the +31 s straggler)
    assert "return itr->second > (int64_t)lineTime;" in helper
    # the header declares it beside the registry it reads
    hdr = MEMORY_H.read_text(encoding="utf-8")
    assert ("static bool PartyClaimGenerationMovedPast(uint32 speakerGuid,\n"
            "        uint64_t msgHash, time_t lineTime);" in hdr)
    # the header law states the entry-side drop and its one exception
    # (the first-writer push/write second-boundary straddle: one
    # conservative miss, never a second generation)
    assert "drain's TTL gate drops such entries instead" in hdr
    assert "(m_time = firstHeard-1: one entry dropped, a missed reply," in hdr

    drain = android_anchor("PB_AI_DRAIN_STALE_ANDROID")
    # the drop sits AFTER the TTL block (the TTL owns the no-repeat
    # stragglers; this one owns the repeat-flip stragglers) and BEFORE
    # the dispatch handoff - nothing that reaches ChatReplyDo can
    # predate the current generation
    ttl_at = drain.index("PlayerbotLlmMemory::PartyClaimWindowElapsed(checkTime)")
    gen_at = drain.index("PlayerbotLlmMemory::PartyClaimGenerationMovedPast(")
    assert ttl_at < gen_at < drain.index("ChatReplyAction::ChatReplyDo("), \
        "generation drop after the TTL drop, before the dispatch"
    # the whole gate + args pinned verbatim: same cheap-gate armament
    # as the TTL drop, the entry's own speaker+hash+stamp (no channel/
    # speaker reclassification - absence keeps other lanes
    # byte-identical)
    arm = ("if (checkTime && sPlayerbotAIConfig.llmEnabled > 0 && "
           "CloudLaneOpen() &&\n"
           "                sPlayerbotAIConfig.llmPartyReplyEnabled != 0 &&\n"
           "                PlayerbotLlmMemory::PartyClaimGenerationMovedPast("
           "holder.m_guid1,\n"
           "                    PlayerbotLlmMemory::PartyMsgHash(holder.m_msg), "
           "checkTime))")
    assert arm in drain, \
        "the generation drop's gate + args are pinned verbatim"
    # and it DROPS (pop + continue inside its own branch)
    tail = drain[gen_at:]
    assert "chatReplies.pop();" in tail and "continue;" in tail


def test_drain_ttl_drops_window_expired_party_lines_round7():
    """Round-7 R1 MAJOR#1: the drain stagger is ADDITIVE (a master's
    repeated 'wait' adds up to 20 s per invocation; teleport/cast
    chains stack), so no fixed claim window exceeds every reachable
    first drain - a deferred bot re-claimed beside the original winner
    after the window pruned the claim. The line itself now expires with
    the window: a queued party/raid line aged past
    PARTY_CLAIM_WINDOW_SECONDS (minus the round-9 R1 fan-out straddle
    margin) at its drain is DROPPED (a missed reply, never a second
    generation). On the armed surface the queue path is noDelay (the
    queued m_time IS the fan-out instant) and entries are unprocessable
    before m_time, so the age compare is exact."""
    src = MEMORY_CPP.read_text(encoding="utf-8")
    oracle = src.split("bool PlayerbotLlmMemory::PartyClaimWindowElapsed")[1]
    oracle = oracle.split("\n}")[0]
    # the ONE window constant decides both the claim map and the line
    # TTL (a second constant could drift and re-open the gap between
    # them); round-8 R1: >= (not >) is LOAD-BEARING - the prune kills a
    # claim AT stamp+30 (expiresAt <= now), so a strict > oracle left
    # one live-line/dead-claim boundary second that re-opened both legs;
    # round-9 R1: the invariant is per-LINE, not per-entry - a fan-out
    # crossing a second boundary gives a later member's queue entry its
    # own later m_time (T+1 beside a marker stamped at T), so the drop
    # must fire one second EARLY: every processed drainer then sits
    # strictly inside every claim/marker life (now <= m_time+28 <=
    # T+29 < T+30 <= each expiry, T the fan-out's earliest push)
    assert ("time(nullptr) - lineTime >=\n"
            "        PARTY_CLAIM_WINDOW_SECONDS - "
            "PARTY_CLAIM_FANOUT_STRADDLE_SECONDS") in oracle
    assert ("int64_t const PARTY_CLAIM_FANOUT_STRADDLE_SECONDS = 1;"
            in src), "the straddle margin is a NAMED constant pinned at 1 s"
    assert "lineTime != 0 &&" in oracle, "unstamped entries never drop"
    # pure time compare - no state, no mutex (the drain holds
    # chatRepliesMutex; the lock-order contract keeps StateMutex a leaf
    # and this helper never asks for it)
    assert "StateMutex" not in oracle

    drain = android_anchor("PB_AI_DRAIN_STALE_ANDROID")
    # the drop sits between the notBefore hold and the dispatch handoff
    assert drain.index("PlayerbotLlmMemory::PartyClaimWindowElapsed(") < \
        drain.index("ChatReplyAction::ChatReplyDo(")
    # gated on the FULL claim-surface armament: llmEnabled is the queue
    # path's noDelay condition (without it lines carry the legacy
    # 10-30 s stagger and m_time stops being the fan-out instant), the
    # conjunction law and the default-0 key match the claim leg
    assert ("if (checkTime && sPlayerbotAIConfig.llmEnabled > 0 && "
            "CloudLaneOpen() &&") in drain
    assert "sPlayerbotAIConfig.llmPartyReplyEnabled != 0 &&" in drain
    assert "PlayerbotLlmMemory::PartyClaimWindowElapsed(checkTime)" in drain
    # party/raid only (the claim surface's channels) ...
    assert ("(staleChannel == ChatChannelSource::SRC_PARTY ||" in drain and
            "staleChannel == ChatChannelSource::SRC_RAID)" in drain)
    # ... real-player lines only (bot chatter is not the surface and
    # must stay byte-identical), then the drop
    assert "staleSpeaker->isRealPlayer()" in drain
    # round-13: the census counts the TTL drop's own pop+continue; the
    # round-13 generation drop (below) adds the third - the notBefore
    # hold above pops through delayedResponses, not here
    assert drain.count("chatReplies.pop();") == 3 and \
        drain.count("continue;") == 3


def test_group_switcher_claims_under_the_line_key_round8():
    """Round-8 R1 MAJOR#2: the claim key was (speaker, hash, groupId)
    with the group resolved at DRAIN time - a listener kicked and
    re-invited to another group inside the window computed a FRESH key,
    claimed beside the original winner, and delivered a second
    generation to a group that never heard the line. The key is now
    GROUP-FREE (a speaker stands in at most one group, so (speaker,
    hash) cannot collide across two live groups; the one cross-group
    shape - the speaker moves groups and repeats the identical text
    inside the window - now refuses the repeat, conservative) and both
    drain-time call sites pass no group."""
    gate = android_anchor("PB_SAY_GATE_ANDROID")
    party = gate.split("plan v5 C3: the roundtable row")[1].split(
        "if (sPlayerbotAIConfig.llmEnabled > 0 && hardTriggerAllowed"
        " && partyResponderClaimed && replyGateAllowed")[0]
    claim_leg = party.split("if (!addressedToBot && CloudLaneOpen()")[1]
    # the claim call shape: speaker low + the one hash - NO group id
    assert ("PlayerbotLlmMemory::TryClaimPartyResponder(\n"
            "                        bot->GetGUIDLow(), gateSpeaker->GetGUIDLow(),\n"
            "                        PlayerbotLlmMemory::PartyMsgHash(msg));") in claim_leg
    # the bystander marker call shape: same group-free key
    assert ("PlayerbotLlmMemory::TryStandDownPartyLine(\n"
            "                        gateSpeaker->GetGUIDLow(),\n"
            "                        PlayerbotLlmMemory::PartyMsgHash(msg));") in claim_leg
    # no call site anywhere in the payload still threads a group id in
    assert "responderGroup->GetId());" not in claim_leg
    # the candidates collector DOES keep the group (the pick is among
    # the current group's bots - the groupless key only governs line
    # ownership, not responder selection)
    assert "CollectPartyCandidates(" in claim_leg


def test_refused_claim_refunds_the_flood_stamp_round7():
    """Round-7 R1 MINOR: a claim the picked bot LOST still consumed the
    speaker's 2 s flood slot (identical-text re-send inside the window,
    or the adjudicated party/raid shared key) - the speaker's next
    DISTINCT line was denied beside a generation that never happened.
    The refund is CAS-shaped: only the attempt that stamped the slot
    lifts it, so a concurrent winner's stamp survives."""
    src = MEMORY_CPP.read_text(encoding="utf-8")
    refund = src.split("void PlayerbotLlmMemory::PartyFloodRefund")[1]
    refund = refund.split("\n}")[0]
    assert "std::lock_guard<std::mutex> lock(StateMutex());" in refund
    # CAS: erase only when the slot still carries THIS attempt's stamp
    assert "itr->second == stampedAt" in refund
    assert "stamps.erase(itr);" in refund

    gate = android_anchor("PB_SAY_GATE_ANDROID")
    # the stamp value is captured BEFORE the admit (second-boundary
    # skew means at worst a missed refund - today's behavior, never a
    # wrong erase)
    assert "time_t const floodStampedAt = time(nullptr);" in gate
    assert gate.index("time_t const floodStampedAt = time(nullptr);") < \
        gate.index("PlayerbotLlmMemory::PartyFloodAdmits(")
    # and the refund fires ONLY on a lost claim
    assert ("if (!partyResponderClaimed)\n"
            "                        PlayerbotLlmMemory::PartyFloodRefund(") in gate
