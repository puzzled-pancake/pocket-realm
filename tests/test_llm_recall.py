"""The S8 memory-USE battery (A13/A16/A17/A18/A19 host gate).

Compiles the SHIPPED pure core (native/patches/playerbots/
PlayerbotLlmRecallCore.h) on the host with -std=c++11 and pins the fact
classifier, the recall question shapes, the beat-cargo wording (the
recallfix measured table), the ceremony/nickname/secret picks and the
gossip distortion. The world-side glue (the bridge ladder's recall
beats, the tier ceremony, the driver anchors, the memory recall
surfaces) is pinned by source-contract assertions below, following
test_llm_truth.py's pattern: the host cannot drive PlayerbotAI or the
DB layer, so the contract the world thread implements is pinned instead.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "PlayerbotLlmRecallCore.h"
HARNESS = ROOT / "tools" / "test_llm_recall.cpp"
BRIDGE_CPP = PATCHES / "PlayerbotLlmBridge.cpp"
BRIDGE_H = PATCHES / "PlayerbotLlmBridge.h"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"
TOOLS_CPP = PATCHES / "PlayerbotLlmTools.cpp"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"


@pytest.fixture(scope="session")
def recall_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    if not CORE.is_file() or not HARNESS.is_file():
        pytest.skip("recall core not staged")
    tmp = tmp_path_factory.mktemp("recall")
    exe = shutil.copy(HARNESS, tmp / "harness.cpp")
    out = tmp / "recall_host.exe"
    compile = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall",
         "-I", str(PATCHES), "-o", str(out), str(exe)],
        capture_output=True, text=True, cwd=str(ROOT))
    assert compile.returncode == 0, compile.stderr[:2000]
    return out


def test_host_battery_legs(recall_binary):
    result = subprocess.run([str(recall_binary)], capture_output=True,
                            text=True, cwd=str(ROOT))
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "all checks passed" in result.stdout


# ---- source-contract pins (the in-tree glue the host cannot drive) --------


def test_recall_beats_sit_between_sentiment_and_first_meeting():
    text = BRIDGE_CPP.read_text(encoding="utf-8")
    inner = text.split("BuildNoteInner(Player* bot")[1].split("} // namespace")[0]
    # ladder order: ACT > insult > gratitude > debt > memory > news >
    # greeting-gap > first-meeting > gossip-window > laughter
    order = [
        "IsDebtQuestion(normalizedMsg)",
        "IsMemoryQuestion(normalizedMsg)",
        "ConsumeGreetingGap(",
        "state.firstMeeting()",
        'note.lines.push_back("<<share_gossip',
    ]
    positions = [inner.index(marker) for marker in order]
    assert positions == sorted(positions), \
        "the A13 recall beats must precede first-meeting/gossip in the ladder"
    # every cargo builder is used, and each of the five recall branches
    # marks its note content-mandating (the A12 dedupe exemption keys on
    # exactly this)
    for marker in ("DebtCargo", "MemoryCargo", "NewsCargo", "GossipCargo",
                   "GrudgeCargo"):
        assert marker + "(" in inner, f"{marker} must fire from the ladder"
    assert inner.count("mandatesContent = true") >= 5


def test_event_notes_carry_their_kinds():
    text = BRIDGE_CPP.read_text(encoding="utf-8").replace('\\"', '"')
    inner = text.split("BuildNoteInner(Player* bot")[1].split("} // namespace")[0]
    event_block = inner.split("if (state.eventTurn)")[1].split("// ---- S7 A10/A11 question path")[0]
    assert '<<perform_emote emote="cheer">>' in event_block, \
        "a level-up reaction licenses the cheer emote (A17 event hook)"
    assert '<<adjust_sentiment direction="+1" reason="...">>' in event_block
    assert '<<adjust_sentiment direction="-1" reason="...">>' in event_block
    assert "EVENT_LEVEL_UP" in event_block and "EVENT_DUEL_LOST" in event_block
    assert "mandatesContent = true" in event_block, \
        "event news is bridge-decided cargo (A13 exemption applies)"


def test_tier_ceremony_gating_and_secret_marker():
    text = BRIDGE_CPP.read_text(encoding="utf-8")
    inner = text.split("BuildNoteInner(Player* bot")[1].split("} // namespace")[0]
    # an ACT request outranks ceremony; the transition still advances
    assert "if (crossed && !actBeat)" in inner
    # the ceremony fires on OBSERVED transitions (one turn after the
    # async crossing write lands - the pre-stomp read cadence)
    assert "ConsumeTierTransition(bot->GetGUIDLow()" in inner
    # the Trusted secret release: durable one-time marker via the
    # licensed log_fact line, retried when a generation is dropped
    assert '"secret told:"' in inner
    assert "HasFactPrefix" in inner
    assert "SecretCargo" in inner
    # ceremony wording never names the mechanic (pin the emitted frame
    # arrays - the phrase-selection code may of course read tier)
    import re
    core = CORE.read_text(encoding="utf-8")
    literals = []
    for arr in ("kCeremonyUpFrames", "kCeremonyDownFrames", "kCeremonyUpPhrases"):
        seg = core.split(f"static char const* const {arr}[] = {{", 1)[1]
        literals += re.findall(r'"[^"]*"', seg.split("};", 1)[0])
    assert literals, "the emitted ceremony frames carry wording literals"
    for lit in literals:
        for word in ("tier", "points", "relationship"):
            assert word not in lit.lower(), \
                f"the ceremony wording must not name the mechanic: {lit}"


def test_beat_cargo_variants_are_guid_threaded():
    """rev-3b (S11): every cargo builder call in the bridge threads the
    bot's GUID so one bot hears ONE flavor for life - a stable of bots
    spreads across the variant set. The span check is paren-balanced per
    CALL: a regex to the next ';' would swallow both arms of a ternary in
    one match and let a dropped GUID survive (the mutation suite's
    surviving-mutant class)."""
    import re

    def call_spans(text, name):
        spans = []
        for m in re.finditer(re.escape(name) + r"\(", text):
            depth, i = 1, m.end()
            while i < len(text) and depth:
                if text[i] == "(":
                    depth += 1
                elif text[i] == ")":
                    depth -= 1
                i += 1
            spans.append(text[m.start():i])
        return spans

    cpp = BRIDGE_CPP.read_text(encoding="utf-8")
    for marker in ("DebtCargo", "MemoryCargo", "NewsCargo", "GossipCargo",
                   "GrudgeCargo", "CeremonyUpCargo", "CeremonyDownCargo"):
        calls = call_spans(cpp, marker)
        assert calls, f"{marker} no longer fires from the bridge"
        for span in calls:
            assert "bot->GetGUIDLow()" in span, \
                f"a {marker} call does not thread the bot GUID: {span[:90]}"


def test_recall_core_carries_the_emitted_frame_block():
    """The variant frames are machine-emitted from banklib (the wording
    lock: banks train the exact strings the bridge injects) - the core
    must carry the splice markers and derive the flavor count from the
    emitted set, never from a hand-typed literal."""
    core = CORE.read_text(encoding="utf-8")
    assert core.count("// ---- beat-cargo frames (generated by") == 1
    assert "// ---- end beat-cargo frames ----" in core
    assert "CargoFlavor(uint32_t botGuid)" in core
    # flavor derives from the emitted array size, not a magic number
    flavor = core.split("CargoFlavor(uint32_t botGuid)")[1].split("}")[0]
    assert "sizeof(kDebtCargoFrames)" in flavor


def test_longform_cue_injection_is_tier_and_content_gated():
    """S11 P50/P51: the frozen long-form cue rides exactly three bridge
    beats (storytelling ask, tier-5 bonded open-confidence, event-class
    news deep-dive), each gated on the configured max new tokens AND
    anchored to real memory content - the game stays the truth, and
    short tiers never see the cue at all."""
    import re
    cpp = BRIDGE_CPP.read_text(encoding="utf-8")
    inner = cpp.split("BuildNoteInner(Player* bot")[1].split("} // namespace")[0]
    assert inner.count("kLongFormCue") == 3, \
        f"three injection sites exactly (found {inner.count('kLongFormCue')})"
    # exact-call regex (a substring count would survive an argument-scaling
    # mutant like (sPlayerbotAIConfig.llmMaxNewTokens * 2))
    gates = re.findall(
        r"LongFormLicensed\(sPlayerbotAIConfig\.llmMaxNewTokens\)", inner)
    assert len(gates) == 3, \
        f"every cue site reads the tier's configured max new tokens verbatim " \
        f"(found {len(gates)} exact calls)"
    # every cue site also MARKS the note (the reply budget's per-turn
    # earning keys on this flag via the license stamp)
    assert inner.count("note.longFormCued = true;") == 3, \
        "each cue injection marks its note for the budget widening"
    rungs = inner.split("else if")
    story = next(r for r in rungs if "WantsStorytelling(normalizedMsg)" in r)
    assert "FACT_MASK_EVENT" in story, \
        "a storytelling beat anchors to a real shared event or does not fire"
    assert "mandatesContent = true" in story
    bonded = next(r for r in rungs if "WantsOpenConfidence(normalizedMsg)" in r)
    assert "state.tier >= 5" in bonded, "open-confidence is a Bonded-only shape"
    assert "IsSecondPerson" in bonded, \
        "open-confidence is second-person-gated (round-1 R1 P2: 'what do " \
        "you make of' had a third-person use - the trigger was dropped, " \
        "the gate added)"
    assert "FACT_MASK_GOAL" in bonded, "the bonded beat anchors to the goal cargo"
    news = next(r for r in rungs if "kNewsTriggers" in r
                and "HasRecentVerifiedEvent" in r)
    assert "FACT_MASK_EVENT" in news, \
        "the news recall path (and its deep-dive cue) selects event-class " \
        "facts by construction - never re-classified with a lost category"
    assert news.index("FACT_MASK_EVENT") < news.index("kLongFormCue"), \
        "the cue rides a fact drawn from the event-class mask"
    # the cue constant is emitted from banklib (wording lock), never
    # hand-written in the core
    core = CORE.read_text(encoding="utf-8")
    assert core.count("static char const* const kLongFormCue") == 1


def test_note_long_form_cued_is_flag_checked():
    """The budget widening's per-turn earning keys on NoteLongFormCued:
    it must read the license's OWN flag, stamp-checked - a stamped but
    un-cued note (any ordinary beat) never widens (the round-2 mutation
    survivor class: the call-site pin alone let 'any stamped note
    widens' live)."""
    cpp = BRIDGE_CPP.read_text(encoding="utf-8")
    body = cpp.split("bool PlayerbotLlmBridge::NoteLongFormCued")[1].split("}")[0]
    assert "license.stamp == stamp" in body, "stamp-checked like the mandate reader"
    assert "license.longFormCued" in body, \
        "the flag itself decides - not merely a live-stamp match"
    rec = cpp.split("uint64_t PlayerbotLlmBridge::RecordLicense")[1].split("}")[0]
    assert "license.longFormCued = note.longFormCued;" in rec, \
        "the license records the note's own cued flag"


def test_license_carries_the_mandate_flag():
    header = BRIDGE_H.read_text(encoding="utf-8")
    assert "bool mandatesContent = false;" in header, \
        "the note and the license carry the A13 beat-content flag"
    cpp = BRIDGE_CPP.read_text(encoding="utf-8")
    assert "license.mandatesContent = note.mandatesContent;" in cpp
    claim = cpp.split("NoteMandatesContent(uint32 botGuid")[1]
    assert "license.stamp != stamp || !license.mandatesContent" in claim, \
        "NoteMandatesContent is stamp-checked (a superseding note never " \
        "leaks the exemption to an older in-flight generation)"


def test_dedupe_reroll_exemption_is_wired():
    driver = DRIVER.read_text(encoding="utf-8")
    assert "!PlayerbotLlmBridge::NoteMandatesContent(botGuid, licenseStamp) &&" in driver, \
        "the A12 dedupe reroll exempts content-mandating notes (A13)"


def test_pre_stomp_state_and_threading():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    state = memory.split("PlayerbotLlmMemory::PreStompState PlayerbotLlmMemory::GetPreStompState")[1] \
        .split("void PlayerbotLlmMemory::LogFact")[0]
    assert "FROM `bot_player_relationship`" in state, \
        "ONE query serves absence + the ceremony's tier observation"
    assert state.count("PQuery") == 1
    assert "out.tier = points >= 120 ? 5" in state, \
        "the pre-stomp tier derives exactly like GetTrainedTier"
    driver = DRIVER.read_text(encoding="utf-8")
    assert "PlayerbotLlmMemory::GetPreStompState(bot, player);" in driver
    assert "llmAbsencePre = llmPre.absence;" in driver
    assert "llmTierPre = llmPre.tier;" in driver


def test_tone_prefixes_carry_sign():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    write = memory.split("AddBoundedSentimentInput(uint32 bot")[1] \
        .split("std::string PlayerbotLlmMemory::GetAbsenceBucket")[0]
    assert '"(tone -) "' in write and '"(tone +) "' in write, \
        "the grudge surface reads the bridge-decided direction"
    grudge = memory.split("GetUnresolvedGrudge(uint32 bot")[1] \
        .split("bool PlayerbotLlmMemory::HasFactPrefix")[0]
    assert "ORDER BY `id` DESC LIMIT 1" in grudge, \
        "the newest opinion row negative == unresolved (a later kindness resolves)"
    journal = memory.split("GetJournal(Player* bot")[1].split("GetJournalLines")[0]
    assert 'text.rfind("(tone", 0) == 0' in journal, \
        "the journal strips every tone shape (player-read surface)"


def test_recall_surfaces_are_newest_window_and_code_matched():
    import re
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    recall = memory.split("GetNewestRecallFact(uint32 bot")[1] \
        .split("GetUnresolvedGrudge(uint32 bot")[0]
    assert "LIMIT 20" in recall
    assert '(tone' in recall  # tone rows are never weave cargo
    gossip = memory.split("GossipAbout(std::string const& playerName)")[1] \
        .split("std::vector<std::string> PlayerbotLlmMemory::GetJournal")[0]
    # strip comments before the LIKE check (the doc comment says "no LIKE")
    gossip_code = re.sub(r"//[^\n]*", "", gossip)
    assert "LIKE" not in gossip_code, \
        "player names are matched in code (wildcards in names are not patterns)"
    assert "LIMIT 8" in gossip


def test_duel_outcome_becomes_memory_and_legend():
    memory = MEMORY_CPP.read_text(encoding="utf-8").replace('\\"', '"')
    duel = memory.split("OnDuelComplete(Player* participant")[1] \
        .split("// ---- M6 authored kill banter")[0]
    assert 'LogFact(bot->GetGUIDLow(), duelPlayer->GetGUIDLow(), fact.str(), "shared-event")' in duel, \
        "A13: the duel outcome is the news-recall beat's cargo"
    assert 'ShareGossip(bot->GetGUIDLow(), town.str(), "duel")' in duel, \
        "A19: a player-subject world_gossip row (the world knows what I did)"
    assert "reaction.eventKind = kind;" in duel
    assert "EVENT_DUEL_PLAYER_FLED" in duel and "EVENT_DUEL_BOT_FLED" in duel


def test_trained_builder_gains_tier_note_and_say_cap():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    trained = memory.split("BuildTrainedChatRequest(Player* bot")[1] \
        .split("std::string PlayerbotLlmMemory::GetRelationshipTier")[0]
    assert "NicknameTierNote" in trained, \
        "the Bonded address shift rides the banklib tierNote leg"
    assert "int const tier = preStompTier;" in trained, \
        "the sysm tier is the pre-stomp read (the S5 law)"
    assert "(playerOrChannel == (0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY)))" in trained
    assert "? 5 : 8;" in trained, \
        "A18: the ambient say cross-injection window caps at 5 lines"


def test_discount_procedure_rides_tier_four_gives():
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    give = bridge.split("BEAT_GIVE_ITEM:")[1].split("default:")[0]
    assert "state.tier >= 4" in give, \
        "A16 Trusted unlock: a friend's price rides the give_item fill"
    assert "friend's price" in give.lower() or "A friend's price" in give


def test_initiative_and_crowd_anchors():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    initiative = memory.split("TickInitiative(Player* bot)")[1] \
        .split("void PlayerbotLlmMemory::OnPlayerGroupKill")[0]
    assert "INITIATIVE_MIN_INTERVAL = 600" in memory, \
        "the zero-spam gate: one bot-initiated line per bot per 10 min"
    assert "AuthoredArrivalGreeting" in initiative
    assert "InitiatedFactIds()" in memory, \
        "new facts want out once (the freshness-weighted cadence)"
    assert "AnyPlayerInObjectRangeCheck" in initiative, \
        "the bot2bot exchange finds its partner (A18 walk-up beat)"
    crowd = memory.split("QueueCrowdEmote(Player* bot")[1] \
        .split("void PlayerbotLlmMemory::TickInitiative")[0]
    assert "LastCrowdEmoteAt()" in crowd, \
        "the crowd cap belongs to the EVENT (1-2 emotes per window)"
    assert "notBefore = time(nullptr) + urand(" in crowd, \
        "crowd emotes stagger the A18 2-5s persona pace"
    driver = DRIVER.read_text(encoding="utf-8")
    assert "PlayerbotLlmMemory::TickInitiative(bot);" in driver, \
        "the scheduler runs from the world-thread UpdateAI anchor"
    assert "PlayerbotLlmMemory::QueueCrowdEmote(bot, gateSpeaker);" in driver, \
        "the crowd tier hooks the non-trigger say path at the gate anchor"
    assert "llmSayStagger = int32(urand(2, 5));" in driver, \
        "generated say replies stagger 2-5s (A18 pacing)"


def test_arrival_greeting_carries_magnitude_and_town_talk():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    packet = memory.split("AuthoredArrivalGreeting(Player* bot, Player* player,")[1] \
        .split("bool PlayerbotLlmMemory::QueueCrowdEmote")[0]
    assert "AbsenceMagnitudeLine" in packet, \
        "the absence beat carries magnitude, never a passive line"
    assert "GossipAbout" in packet and "DistortGossipHop" in packet and "LogFact" in packet, \
        "A19: town talk rides the greeting and logs a belief row one hop deep"
    driver = DRIVER.read_text(encoding="utf-8")
    assert "AuthoredArrivalGreeting(bot, player, llmAbsencePre)" in driver


def test_first_meeting_never_routes_to_the_persona_fallback():
    driver = DRIVER.read_text(encoding="utf-8")
    assert 'llmAbsencePre != "a first meeting" &&' in driver, \
        "the S5-logged fold: the first-meeting log_fact beat must fire " \
        "(the persona fallback's early exit skipped BuildNote entirely)"


def test_gossip_belief_rows_on_the_generated_path_too():
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    gap = bridge.split("ConsumeGreetingGap(bot->GetGUIDLow(), player->GetGUIDLow())")[1].split("if (cargo.empty())")[0]
    assert "heard the town talk:" in gap and "DistortGossipHop" in gap, \
        "the generated greeting beat logs its belief row with one hop of drift"


def test_play_text_emote_is_the_shared_delivery():
    tools = TOOLS_CPP.read_text(encoding="utf-8")
    assert "void PlayerbotLlmTools::PlayTextEmote(Player* bot, Player* target" in tools
    emote_branch = tools.split('call.name == "perform_emote"')[1].split("else if")[0]
    assert "PlayTextEmote(bot, player, LicensedField" in emote_branch
    driver = DRIVER.read_text(encoding="utf-8")
    assert "PlayerbotLlmTools::PlayTextEmote(bot, emoteTarget, reaction.text);" in driver, \
        "the authored crowd emote reaction delivers through the same path"


# ---- round-1 fix pins -------------------------------------------------------


def test_recall_masks_use_mask_constants():
    """Round-1 R1's P0: the bridge passed raw FactClass VALUES as bit
    masks (FACT_DEBT == 1 selected PLAIN). Every call site must compose
    from the FACT_MASK_* constants."""
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    inner = bridge.split("BuildNoteInner(Player* bot")[1].split("} // namespace")[0]
    assert "pocketllm::FACT_MASK_DEBT);" in inner
    assert "pocketllm::FACT_MASK_EVENT);" in inner
    assert inner.count("pocketllm::FACT_MASK_GOAL | pocketllm::FACT_MASK_EVENT") == 2
    assert "pocketllm::FACT_GOAL |" not in inner and "pocketllm::FACT_DEBT)" not in inner
    core = CORE.read_text(encoding="utf-8")
    assert "FACT_MASK_DEBT = 1 << FACT_DEBT" in core


def test_new_core_build_wiring_is_pinned():
    driver = DRIVER.read_text(encoding="utf-8")
    assert '"playerbot/PlayerbotLlmRecallCore.h",' in driver, \
        "the overlay manifest documents the new core"
    assert '(bot_root / "PlayerbotLlmRecallCore.h").write_bytes' in driver, \
        "prepare_cmangos_source copies the new core into the mirror"


def test_gossip_matching_is_word_exact():
    """Round-1 R6's P1: bare substring matching misattributed gossip to
    players whose names are prefixes of other words ('Ash' vs 'Ashmar'
    vs lowercase 'ash')."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    gossip = memory.split("GossipAbout(std::string const& playerName)")[1] \
        .split("std::vector<std::string> PlayerbotLlmMemory::GetJournal")[0]
    assert "ContainsWordExact(text, playerName)" in gossip


def test_secret_marker_is_category_constrained():
    """Round-1 R6's P1: a model-filled fact copying a player-whispered
    'secret told:' prefix must not lock the Trusted unlock out - the
    probe is category-constrained to the ceremony's writer."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    probe = memory.split("HasFactPrefix(uint32 bot")[1].split("GetUnresolvedGrudge")[0]
    assert "`category` = 'player-identity'" in probe


def test_relationship_insert_stamps_timestamp():
    """Round-1 R6's P1: the initial row's NULL last_interaction_at made
    turn 2 read as a second first meeting (double log_fact beat)."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    add = memory.split("AddRelationshipPoints(Player* bot")[1].split("AddBoundedSentimentInput")[0]
    assert "NULL) " not in add, "the INSERT stamps CURRENT_TIMESTAMP"
    assert add.count("CURRENT_TIMESTAMP)") == 2,         "both dialect INSERT branches stamp the initial interaction"


def test_arrival_eligibility_precedes_slot_claim():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    loop = memory.split("for (Player* player : arrivals)")[1].split("return; // one initiative per scan")[0]
    absence_at = loop.index("GetAbsenceBucket")
    claim_at = loop.index("TryClaimAmbientSlot")
    assert absence_at < claim_at, \
        "a stranger's arrival must not burn the 10-minute initiative slot"


def test_bot2bot_reply_matches_its_opener():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    ex = memory.split("A18: a player walking up on two bots")[1].split("return; // one initiative per scan")[0]
    assert "replies[pick]" in ex, "the reply matches its own opener"
    assert "reply.msgtype = CHAT_MSG_SAY" in ex, "the reply answers on /say"
    # round-2 R3: the DRAIN side of the say-tagged reply is pinned too
    driver = DRIVER.read_text(encoding="utf-8")
    assert "if (reaction.msgtype == CHAT_MSG_SAY)" in driver, \
        "a SAY-tagged authored reaction speaks on /say even when grouped"


def test_drain_waits_the_stagger_window():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    drain = memory.split("DrainEventReaction(uint32 botGuid")[1]
    assert "front().notBefore > time(nullptr)" in drain, \
        "the drain holds a staggered head back (later reactions never " \
        "jump ahead of it)"


def test_tone_rows_never_become_weave_cargo():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    recall = memory.split("GetNewestRecallFact(uint32 bot")[1] \
        .split("GetUnresolvedGrudge(uint32 bot")[0]
    assert 'text.rfind("(tone", 0) == 0)\n            continue;' in recall, \
        "tone rows are SKIPPED by the recall fetch (presence of the " \
        "prefix check alone is not the contract)"


def test_ceremony_defers_on_act_turns():
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    assert "PeekTierTransition" in bridge, \
        "an ACT turn observes the crossing WITHOUT consuming it"
    inner = bridge.split("BuildNoteInner(Player* bot")[1].split("} // namespace")[0]
    assert "actBeat\n            ? PeekTierTransition" in inner


def test_mandate_exemption_is_rate_capped():
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    claim = bridge.split("NoteMandatesContent(uint32 botGuid")[1].split("}\n\n")[0]
    assert "MandateExemptAt()" in claim and "< 60" in claim, \
        "the dedupe exemption is a per-bot budget (one per minute)"


def test_say_stagger_applies_to_mentions_only():
    driver = DRIVER.read_text(encoding="utf-8")
    anchor = driver.split("PB_AI_QUEUE_CALL_ANDROID")[1].split('"""')[1]
    assert "isAiChat && !isMentioned" in anchor, \
        "non-mention says pass undelayed (the crowd tier owns that pacing)"
    assert "isAiChat && isMentioned" in anchor, \
        "only mention answers stagger 2-5s"
    assert "llmSayStagger = int32(urand(2, 5));" in anchor
