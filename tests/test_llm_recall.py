"""The memory-USE battery (A13/A16/A17/A18/A19 host gate).

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
    # split markers must EXIST (a reworded marker fails open: the slice
    # silently widens and the pins below lose their span)
    assert "// ---- question path, computed BEFORE the ladder" in inner
    event_block = inner.split("if (state.eventTurn)")[1] \
        .split("// ---- question path, computed BEFORE the ladder")[0]
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
    # the crossing folds into the mood weather DIRECTIONALLY: up
    # brightens (smitten), down quiets (grief) - a mutant that pins one
    # arm inverts the weather for the other direction
    assert ("NudgeMood(bot->GetGUIDLow(),\n"
            "                crossed > 0 ? pocketllm::MOOD_SMITTEN : pocketllm::MOOD_GRIEF);") in inner
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
    """Every cargo builder call in the bridge threads the
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
    """The frozen long-form cue rides exactly three bridge
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
    # mutant like (sPlayerbotAIConfig.llmMaxNewTokens * 2)); Phase-3 adds
    # the longForm dial as the second argument at every site
    gates = re.findall(
        r"LongFormLicensed\(sPlayerbotAIConfig\.llmMaxNewTokens, sPlayerbotAIConfig\.llmRpLongForm\)",
        inner)
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
    un-cued note (any ordinary beat) never widens (the call-site pin
    alone would let 'any stamped note widens' live)."""
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
    assert "// ---- authored kill banter (rare by design)" in memory
    duel = memory.split("OnDuelComplete(Player* participant")[1] \
        .split("// ---- authored kill banter (rare by design)")[0]
    assert 'LogFact(bot->GetGUIDLow(), duelPlayer->GetGUIDLow(), fact.str(), "shared-event")' in duel, \
        "A13: the duel outcome is the news-recall beat's cargo"
    assert 'ShareGossip(bot->GetGUIDLow(), town.str(), "duel")' in duel, \
        "A19: a player-subject world_gossip row (the world knows what I did)"
    assert "reaction.eventKind = kind;" in duel
    assert "EVENT_DUEL_PLAYER_FLED" in duel and "EVENT_DUEL_BOT_FLED" in duel
    # the wronging folds into the mood weather: a loss or a cheap
    # player-flee nudges GRUDGE (a mutant that flips the mood argument
    # inverts the weather - the exact contract under the nudge)
    assert "NudgeMood(bot->GetGUIDLow(), pocketllm::MOOD_GRUDGE);" in duel, \
        "duel loss / player-flee folds a grudge into the mood weather"
    assert "MOOD_SMITTEN" not in duel, \
        "no duel outcome brightens the weather"


def test_trained_builder_gains_tier_note_and_say_cap():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    trained = memory.split("BuildTrainedChatRequest(Player* bot")[1] \
        .split("std::string PlayerbotLlmMemory::GetRelationshipTier")[0]
    assert "NicknameTierNote" in trained, \
        "the Bonded address shift rides the banklib tierNote leg"
    assert "int const tier = preStompTier;" in trained, \
        "the sysm tier is the pre-stomp read (the S5 law)"
    assert "(playerOrChannel == (0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY)))" in trained
    # A18: the ambient say cross-injection window caps at 5 lines
    # on-device (16 on the 128k API tier), 8 conversational (32 on API)
    assert "? (apiTier ? 16 : 5) : (apiTier ? 32 : 8);" in trained, \
        "A18: the ambient say cross-injection window caps at 5 lines"
    # the load-bearing wiring: the shipped sysm actually receives the
    # staged pack seasoning AND the mood weather line (deleting either
    # argument would otherwise pass every pure-function test)
    assert "LoadPackSeasoning(), MoodLineFor(botGuid));" in trained, \
        "the trained builder passes the pack seasoning and mood line into SysmForCard"


def test_history_storage_cap_clears_the_read_window():
    """The rolling-history writer must clear the reader's largest window:
    a deque capped at 20 silently starves the API tier's 32-turn read cap
    (storage binds before the reader ever sees the tail)."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    writer = memory.split("void AppendHistoryTurn(")[1] \
        .split("// Prompt-framing control tokens")[0]
    assert "ExternalApiTierActive() ? 32 : 20" in writer, \
        "the write-side cap is tier-aware and >= the reader's 32-turn window"
    assert "while (turns.size() > storeCap)" in writer, \
        "the deque eviction keys on the tier-aware cap"


def test_driver_reads_the_rp_conf_keys():
    """The build driver's config overlays must parse every emitted RP key
    with the app's spelling, the 50 default, and the 9-block delta id
    set (a key emitted but never parsed is inert)."""
    driver = (ROOT / "tools" / "build_o09_realm_runtime.py").read_text(encoding="utf-8")
    assert 'GetStringDefault("AiPlayerbot.LLMPromptPackFile", "")' in driver, \
        "the staged pack path key is parsed natively"
    for key in ("LLMRpInitiative", "LLMRpVolatility", "LLMRpReactivity", "LLMRpLongForm"):
        assert f'GetIntDefault("AiPlayerbot.{key}", 50)' in driver, \
            f"{key} is parsed with the documented 50 default"
    # the delta keys are composed from the frozen id list; pin the LIST
    # (the whole 9-id universe in one array) plus the composition line
    import re as _re
    overlay = driver.split("static char const* const kBlocks[] = {")[1].split("};")[0]
    ids = _re.findall(r'"([^"]+)"', overlay)
    assert ids == [
        "voice-lock", "rule-autonomy", "rule-anti-omniscient",
        "rule-boldness", "rule-salience", "ban-list", "scene-close",
        "initiative-opener", "mood-weather", "player-persona",
    ], f"the native delta id list must equal the app universe (got {ids})"
    assert 'std::string("AiPlayerbot.LLMPromptBlock.") + kBlocks[i]' in driver, \
        "the per-preset delta keys compose from the id list"


def test_discount_procedure_rides_tier_four_gives():
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    give = bridge.split("BEAT_GIVE_ITEM:")[1].split("default:")[0]
    assert "state.tier >= 4" in give, \
        "A16 Trusted unlock: a friend's price rides the give_item fill"
    assert "friend's price" in give.lower() or "A friend's price" in give


def test_plan_v5_slice1_core_hooks_are_anchored():
    """W1/F1: the player-death and trade-completion hooks must be anchored
    in the driver (the submodules stay pristine; a hook that only exists in
    the patches overlay can never fire)."""
    driver = DRIVER.read_text(encoding="utf-8")
    death = driver.split('CORE_UNIT_DEATH_ANDROID = """')[1].split('"""')[0]
    assert "PlayerbotLlmMemory::OnPlayerDied(deadPlayer)" in death, \
        "the SetDeathState anchor calls the death hook"
    assert "s == JUST_DIED && GetTypeId() == TYPEID_PLAYER" in death, \
        "the death hook fires on real player deaths only"
    assert 'replace_anchor(cmangos / "src" / "game" / "Entities" / "Unit.cpp", CORE_UNIT_DEATH_UPSTREAM, CORE_UNIT_DEATH_ANDROID)' in driver, \
        "the death anchor is registered in the apply list"
    trade = driver.split('CORE_TRADE_ANDROID = """')[1].split('"""')[0]
    assert "PlayerbotLlmMemory::OnTradeCompleted(_player, trader)" in trade, \
        "the trade anchor calls the completion hook pre-moveItems"
    assert 'replace_anchor(cmangos / "src" / "game" / "Trade" / "TradeHandler.cpp", CORE_TRADE_UPSTREAM, CORE_TRADE_ANDROID)' in driver, \
        "the trade anchor is registered in the apply list"
    assert 'restore_anchor(\n        NATIVE / "cmangos" / "src" / "game" / "Trade" / "TradeHandler.cpp",\n        CORE_TRADE_ANDROID,' in driver, \
        "the trade anchor restores for --configure-only"


def test_plan_v5_slice1_conf_keys():
    driver = DRIVER.read_text(encoding="utf-8")
    for key, default in (("LLMEventReactionsEnabled", 1),
                         ("LLMGrudgeRefusalEnabled", 1),
                         ("LLMAuthoredLinesPerHour", 8)):
        assert f'GetIntDefault("AiPlayerbot.{key}", {default})' in driver, \
            f"{key} is parsed with the documented default"
    header = driver.split('PB_LLM_CONFIG_HEADER_ANDROID = """')[1].split('"""')[0]
    assert "llmEventReactionsEnabled, llmGrudgeRefusalEnabled, llmAuthoredLinesPerHour;" in header, \
        "the slice-1 fields are declared in the config header overlay"


def test_plan_v5_w1_death_reaction_machinery():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    assert "void PlayerbotLlmMemory::OnPlayerDied(Player* victim)" in memory
    # wipe classification reads the whole group's state
    death = memory.split("OnPlayerDied(Player* victim)")[1].split("OnTradeCompleted")[0]
    assert "member->IsInWorld() && member->IsAlive()" in death, \
        "wipe classification: a lone survivor anywhere means no wipe"
    assert "REACTION_CONDOLENCE" in death, "the authored condolence cell draw"
    assert "PendingAftermath()" in death, "a wipe arms the pending shaken lines"
    assert "ShareGossip(" in death, "a wipe mints a town gossip row"
    # the pending aftermath is consumed by the initiative scan, once
    tick = memory.split("TickInitiative(Player* bot)")[1].split("void PlayerbotLlmMemory::OnPlayerGroupKill")[0]
    assert "REACTION_SHAKEN" in tick, "the shaken line voices when the party reforms"
    # the first condolence is the guaranteed beat: it must NOT claim the
    # ambient slot (exempt from every pacing budget)
    assert "AuthoredLineAdmitsLocked(arbCategory, /*exempt=*/false, /*roomOnly=*/false)" in memory, \
        "the ambient slot consults the F7 budget"


def test_plan_v5_w4_grudge_refusal():
    tools = TOOLS_CPP.read_text(encoding="utf-8")
    region = tools.split('call.name == "follow"')[1].split('call.name == "loot_roll"')[0]
    assert region.count("GetUnresolvedGrudge") >= 2, \
        "both follow and party_invite check the standing grudge"
    assert "GrudgeRefusalLine(bot, player)" in region, \
        "the refusal is the authored one-liner"
    assert "llmGrudgeRefusalEnabled" in region and "llmRpVolatility > 25" in region, \
        "the refusal is key-gated and steady bots (dial <= 25) swallow it"


def test_plan_v5_debt_settlement_fires_kind_one_beat():
    bridge_h = (PATCHES / "PlayerbotLlmBridge.h").read_text(encoding="utf-8")
    assert "EVENT_DEBT_SETTLED = 7" in bridge_h
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    case = bridge.split("case PlayerbotLlmBridge::EVENT_DEBT_SETTLED:")[1].split("default:")[0]
    assert "TierBeatCargo(player ? player->GetName() : \"\"," in case, \
        "the settled-debt event turn licenses the kind-1 (debt-forgiven) cargo"
    assert "1, bot ? bot->GetGUIDLow() : 0" in case, "kind 1 exactly"
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    trade = memory.split("OnTradeCompleted(Player* accepter, Player* initiator)")[1]
    assert "DELETE FROM `bot_player_facts`" in trade, \
        "settlement RETIRES the debt row (the reminder engine reads by class)"
    assert "paid the debt square" in trade, "the settlement mints its fact"
    assert "EVENT_DEBT_SETTLED" in trade, "the reaction queues with the event kind"


def test_initiative_and_crowd_anchors():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    initiative = memory.split("TickInitiative(Player* bot)")[1] \
        .split("void PlayerbotLlmMemory::OnPlayerGroupKill")[0]
    assert "InitiativeFloorSecs" in memory, \
        "the zero-spam floor scales from the initiative dial (1200s..300s)"
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


# ---- regression pins -------------------------------------------------------


def test_recall_masks_use_mask_constants():
    """Regression pin: the bridge once passed raw FactClass VALUES as bit
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
    """Regression pin: bare substring matching misattributed gossip to
    players whose names are prefixes of other words ('Ash' vs 'Ashmar'
    vs lowercase 'ash')."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    gossip = memory.split("GossipAbout(std::string const& playerName)")[1] \
        .split("std::vector<std::string> PlayerbotLlmMemory::GetJournal")[0]
    assert "ContainsWordExact(text, playerName)" in gossip


def test_secret_marker_is_category_constrained():
    """Regression pin: a model-filled fact copying a player-whispered
    'secret told:' prefix must not lock the Trusted unlock out - the
    probe is category-constrained to the ceremony's writer."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    # end marker must FOLLOW the start anchor and exist (a dead or
    # misordered marker fails open: the slice widens and the pin loses
    # its span)
    assert "std::string PlayerbotLlmMemory::GossipAbout" in memory
    probe = memory.split("HasFactPrefix(uint32 bot")[1] \
        .split("std::string PlayerbotLlmMemory::GossipAbout")[0]
    assert "`category` = 'player-identity'" in probe


def test_relationship_insert_stamps_timestamp():
    """Regression pin: the initial row's NULL last_interaction_at made
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
    # the DRAIN side of the say-tagged reply is pinned too
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


def test_plan_v5_slice2_explore_hook_and_place_memory():
    """W3: the explore-bit hook is anchored in the driver (the game's own
    'first time here' verification), the elite branch precedes the quip
    roll, and the anniversary mint rides the arrival path."""
    driver = DRIVER.read_text(encoding="utf-8")
    explore = driver.split('CORE_EXPLORE_ANDROID = """')[1].split('"""')[0]
    assert "PlayerbotLlmMemory::OnPlayerExploredArea(this, p->zone ? p->zone : p->ID)" in explore, \
        "the explore hook fires at the zone level"
    assert 'replace_anchor(cmangos / "src" / "game" / "Entities" / "Player.cpp", CORE_EXPLORE_UPSTREAM, CORE_EXPLORE_ANDROID)' in driver, \
        "the explore anchor is registered"
    assert 'CORE_EXPLORE_ANDROID,\n        CORE_EXPLORE_UPSTREAM,' in driver, \
        "the explore anchor restores for --configure-only"

    memory = MEMORY_CPP.read_text(encoding="utf-8")
    explore_fn = memory.split("OnPlayerExploredArea(Player* player, uint32 zoneOrAreaId)")[1].split("ConsumePendingAnswer")[0]
    assert "HasSharedFactPrefix" in explore_fn, \
        "first-visit facts are prefix-checked against the ledger (no duplicate firsts after restart)"
    assert "for the first time" in explore_fn

    kill = memory.split("OnPlayerGroupKill(Player* tapper, Unit* victim)")[1]
    elite = kill.split("Phase-3 reactivity: the 1-in-24 kill roll")[0]
    assert "CREATURE_ELITE_ELITE" in elite, "elite detection rides the creature rank"
    assert elite.index("CREATURE_ELITE_ELITE") < kill.index("urand(1, killSides)"), \
        "the elite mint happens BEFORE the quip roll (elites skip it)"
    assert '"kill"' in elite, "an elite fell mints a player-subject town row"

    tick = memory.split("TickInitiative(Player* bot)")[1]
    assert "AnniversaryBucket(days)" in tick, "the anniversary mint rides the arrival path"
    assert "have known " in tick


def test_plan_v5_slice2_curiosity_and_weather():
    """W5 + W7a: the question class is the third initiative class with the
    deterministic answer capture at the bridge; the weather/hour bias is
    default-ON with its conf kill-switch."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    tick = memory.split("TickInitiative(Player* bot)")[1]
    assert "CuriosityQuestionLine(questionIdx, player->GetName())" in tick, \
        "the curiosity class asks from the bank"
    assert "now - lastAsk->second < 1800" in tick, "the 30-minute question floor"
    assert "GetTrainedTier(bot, player) < 2" in tick, "questions need tier >= 2"
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    assert "ConsumePendingAnswer(bot->GetGUIDLow()," in bridge, \
        "the bridge consumes the armed answer on conversational turns"
    consume = memory.split("ConsumePendingAnswer(uint32 bot, uint32 player, std::string const& reply)")[1]
    assert "asked, and the answer was:" in consume, "the captured answer mints as a fact"

    persona = (PATCHES / "PlayerbotLlmPersona.cpp").read_text(encoding="utf-8")
    ambient = persona.split("MaybeAmbientLine(Player* bot)")[1]
    assert "WEATHER_TYPE_RAIN" in ambient and "WEATHER_TYPE_STORM" in ambient, \
        "rain/storm biases the ambient draw (read via the W7a accessors)"
    assert "GetWeatherGrade() > 0.0f" in ambient, "a zero-grade weather type is no weather"
    assert "POOL_SUPERSTITION" in ambient and "POOL_MOOD_HOMESICK" in ambient
    assert "lt->tm_hour < 6 || lt->tm_hour >= 21" in ambient, "night doubles nightweary"
    assert "llmWorldTruthAmbient" in ambient, "the bias is key-gated"

    driver = DRIVER.read_text(encoding="utf-8")
    assert 'GetIntDefault("AiPlayerbot.LLMWorldTruthAmbient", 1)' in driver, \
        "W7a default-ON with a documented kill-switch"


def test_plan_v5_slice3_tier_dedup_quota_and_recap():
    """F4a: ONE tier condition; C1.3: the quota ledger; C2: the recap is
    digest-first with quota-capped fail-closed prose."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    assert memory.count("llmApiProviderSafe &&") == 0, \
        "the inline tier condition is deduplicated into the helper"
    assert "bool PlayerbotLlmMemory::ExternalApiTierActive()" in memory
    assert "PlayerbotLlmMemory::ExternalApiTierActive() ? 32 : 20" in memory, \
        "the history writer gates on the helper"
    assert "bool const apiTier = ExternalApiTierActive();" in memory, \
        "the history reader gates on the helper"
    assert "bool PlayerbotLlmMemory::CloudQuotaAdmits(char const* surface, uint32 perDay)" in memory

    login = memory.split("OnPlayerLogin(Player* player)")[1].split("MaybeSessionStandingLine")[0]
    assert "DeliverSessionRecap(player)" in login, \
        "a paired player's first login of the process fires the recap"
    recap = memory.split("DeliverSessionRecap(Player* player)")[1].split("MaybeSessionStandingLine")[0]
    assert 'CloudQuotaAdmits("recap-prose"' in recap, "prose is quota-capped"
    assert "RenderRecapDigest(player)" in recap, "digest-first"
    assert "Previously, in your realm:" in recap, "the deterministic header line"
    assert "ParseCompletionEnvelope" in recap and "SplitNarratorBlock" in recap, \
        "prose parses and splits through the safety law"
    digest = memory.split("RenderRecapDigest(Player* player)")[1].split("DeliverSessionRecap(Player* player)")[0]
    assert "now - offlineFloor < 3600" in digest, "a quick relog is not a session return"
    assert "lines.size() < 3" in digest, "silence below three rows (the doctrine)"

    driver = DRIVER.read_text(encoding="utf-8")
    for key, default in (("LLMRecapEnabled", 1), ("LLMRecapProse", 1),
                         ("LLMRecapProsePerDay", 6)):
        assert f'GetIntDefault("AiPlayerbot.{key}", {default})' in driver, \
            f"{key} is parsed with the documented default"


def test_plan_v5_slice4_notice_and_furniture():
    """W8: the /notice keyword branch in the SayAction overlay renders the
    scene read (player-initiated, zero generation). W7b: scene/homeland
    furniture rides the bridge extra leg, default OFF, never [State]."""
    driver = DRIVER.read_text(encoding="utf-8")
    async_block = driver.split("PB_SAY_ASYNC_ANDROID = ")[1]
    notice = async_block.split('lowerMsg == "notice"')[1].split("}")[0] if 'lowerMsg == "notice"' in async_block else ""
    assert 'PlayerbotLlmMemory::SceneReadLines(bot, player)' in async_block, \
        "the notice keyword renders the scene read"
    assert async_block.index('lowerMsg == "notice"') > async_block.index('lowerMsg == "gossip"'), \
        "notice joins the keyword dispatch after gossip (the F3-minimal table)"
    for key, default in (("LLMSceneReadEnabled", 1), ("LLMWorldTruthFurniture", 0)):
        assert f'GetIntDefault("AiPlayerbot.{key}", {default})' in driver, \
            f"{key} parsed with the documented default"

    memory = MEMORY_CPP.read_text(encoding="utf-8")
    scene = memory.split("SceneReadLines(Player* bot, Player* player)")[1].split("// ---- the player surface")[0]
    assert "You are in " in scene and "deep inside" in scene, "place truth"
    assert "HasStealthAura()" in scene, "stealth truth"
    assert "is badly hurt" in scene, "wounded members"
    assert "GossipAbout(player->GetName())" in scene, "the live rumor"
    assert "SceneNudgeLine" in scene, "the authored nudge"

    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    furn = bridge.split("plan v5 W7b: the scene/homeland furniture")[1].split("return note;")[0]
    assert "HasStealthAura()" in furn, "stealth precedence"
    assert "HomeZoneOfRace" in furn and "IsEnemyCapitalZone" in furn, "homeland helpers"
    assert "llmWorldTruthFurniture" in furn, "key-gated (default OFF)"
    assert "note.extra" in furn, "rides the extra leg"
    # the furniture must never touch the trained [State] fill
    assert "CompanionState" not in furn and "NpcSpotState" not in furn

    recall = (PATCHES / "PlayerbotLlmRecallCore.h").read_text(encoding="utf-8")
    assert 'HomeZoneOfRace(1)' in recall or "case 1: return \"elwynn\"" in recall


def test_plan_v5_slice7_persona_block_and_keyword_lore():
    """S.2: the persona card is a seasoning block (empty renders nothing -
    no schema change needed for v1). H3: keyword lore injects once per
    session per pairing, after the question path's first claim."""
    pack = (ROOT / "android" / "app" / "src" / "main" / "java" /
            "com" / "pocketrealm" / "llm" / "LlmPromptPack.kt").read_text(encoding="utf-8")
    assert '"player-persona"' in pack, "the persona card is a pack block"
    assert 'body = "",' in pack, "empty default body (renders nothing)"

    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    h3 = bridge.split("plan v5 H3: keyword-triggered lore")[1].split("conversational ACT beats")[0]
    assert "BestCard(normalizedMsg)" in h3, "the card resolves by title"
    assert "ClaimLoreCardOnce" in h3, "once per session per pairing"

    driver = DRIVER.read_text(encoding="utf-8")
    assert '"mood-weather", "player-persona",' in driver, \
        "the native delta list carries the persona id"


def test_plan_v5_wave1_fixes_and_pin_gaps():
    """Wave-1 review fixes + the pin gaps the test audit found: the
    condolence exemption (negative pin), the answer expiry, the saga
    two-fact gate, the drama cooldown, the ARB cap mapping, the exact
    kind-1 call, the exact roundtable staleness line, the dialect-aware
    recap query, and the W4 always-decline hoist."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    condolence = memory.split("REACTION_CONDOLENCE, victim)")[1].split("void PlayerbotLlmMemory::OnTradeCompleted")[0]
    assert "TryClaimAmbientSlot" not in condolence and "AuthoredLineAdmits" not in condolence, \
        "the guaranteed condolence beat stays outside every pacing budget"
    assert "if (line.empty())\n        return; // the mint and the nudge wait" in condolence, \
        "the mint and mood nudge wait for a confirmed voice"

    consume = memory.split("ConsumePendingAnswer(uint32 bot, uint32 player, std::string const& reply)")[1].split("// ---- the player surface")[0]
    assert "bool const expired = time(nullptr) > itr->second.second;" in consume, \
        "a stale arm never mints"
    assert "time(nullptr) + 600" in memory, "the arm carries its 10-minute expiry"

    digest = memory.split("RenderRecapDigest(Player* player)")[1].split("DeliverSessionRecap(Player* player)")[0]
    assert "strftime('%%s', `created_at`) > '%u'" in digest, \
        "the ledger-grew query is dual-dialect (SQLite has no FROM_UNIXTIME)"
    assert "offlineFloor" in digest, "the floor local no longer shadows std::floor"

    zone = memory.split("std::string ZoneNameOf(Player* bot)")[1].split("struct HistoryLine")[0]
    assert "GetAreaEntryByAreaID(bot->GetZoneId())" in zone, \
        "ZoneNameOf resolves real players (the victim/tapper have no PlayerbotAI)"

    chatter = (PATCHES / "PlayerbotLlmChatter.cpp").read_text(encoding="utf-8")
    saga = chatter.split("bool PlayerbotLlmChatter::TryBeginCampfireSaga(Player* master)")[1]
    assert "job.facts.size() < 2" in saga, "a saga needs at least two truths to weave"
    drama = chatter.split("the rare authored drama set piece")[1].split("time_t duelNote = 0;")[0]
    assert "now - s.lastDramaAt >= 2700" in drama, "the drama cooldown is pinned"
    assert "s.lastDramaAt = now; // claim confirmed" in drama, \
        "the drama window burns only on a confirmed claim"
    assert "2 * (int)((pairSeed / 6) % 2)" in drama, \
        "bonded pairs get reunion/debt (0/2); rivalry is for sour pairs"
    assert "PeekNewestDyadEvent" in chatter.split("bool PickPartyTopic")[1].split("std::string const telling")[0], \
        "dyad topics vet fatigue BEFORE claiming (no consumed-in-silence events)"

    drain = chatter.split("for (PendingLine& entry : due)")[1].split("if (!deferred.empty())")[0]
    assert "AuthoredBudgetHasRoom(arbCat)" in drain, "the drain peeks before delivering"
    assert "else if (delivered && !entry.longForm)" in drain, \
        "the F7 stamp lands only on a confirmed delivery"
    refill = chatter.split("if (quiet && murmurQueued < policy.murmurQueueLowWater")[1].split("for (Player* player : players)")[0]
    assert "AuthoredBudgetHasRoom(PlayerbotLlmMemory::ARB_AMBIENT)" in refill, \
        "a spent ambient budget skips the murmur batch entirely"

    caps = memory.split("uint32 const catCap = category == PlayerbotLlmMemory::ARB_AMBIENT")[1].split("int64_t const now")[0]
    assert "std::min<uint32>(3, globalCap)" in caps, "ambient sub-cap 3/hr"
    assert "std::min<uint32>(2, globalCap)" in caps, "reaction sub-cap 2/hr"

    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    debt = bridge.split("case PlayerbotLlmBridge::EVENT_DEBT_SETTLED:")[1].split("default:")[0]
    assert "TierBeatCargo(player ? player->GetName() : \"\"," in debt, \
        "the settled-debt event licenses the kind-1 cargo exactly"
    assert "1, bot ? bot->GetGUIDLow() : 0" in debt, "kind 1 exactly"

    tools = TOOLS_CPP.read_text(encoding="utf-8")
    follow = tools.split('call.name == "follow"')[1].split('call.name == "loot_roll"')[0]
    assert "if (sPlayerbotAIConfig.llmGrudgeRefusalEnabled)" in follow and \
        follow.index("llmGrudgeRefusalEnabled") < follow.index("llmRpVolatility > 25"), \
        "the grudge ALWAYS declines execution; volatility only gates the VOICE"

    driver = DRIVER.read_text(encoding="utf-8")
    async_block = driver.split("PB_SAY_ASYNC_ANDROID = ")[1]
    notice = async_block.split('lowerMsg == "notice"')[1].split("}")[0]
    assert "PlayerbotLlmMemory::SceneReadLines(bot, player)" in notice, \
        "the scene read lives inside the notice branch"
    assert "ConsumePendingAnswer(bot->GetGUIDLow()," in driver, \
        "an armed ask consumes spoken answers on the say path"
    # W5 fix (wave 3): the party-line consume sits OUTSIDE hardTriggerAllowed
    # (hardTrigger == addressedToBot on SRC_PARTY, so an unaddressed-answer
    # leg behind that gate is dead code)
    party_block = driver.split("plan v5 C3: the roundtable row")[1].split(
        "if (bot->GetPlayerbotAI()")[0]
    assert "if (gateSpeaker && gateSpeaker->isRealPlayer() &&" in party_block, \
        "the party block gates on speaker + channel"
    assert "else\n            PlayerbotLlmMemory::ConsumePendingAnswer" in party_block, \
        "the unaddressed leg consumes the armed ask"
    for key, default in (("LLMDramaEnabled", 1), ("LLMCuriosityEnabled", 1)):
        assert f'GetIntDefault("AiPlayerbot.{key}", {default})' in driver, \
            f"{key} exists (plan 5.4 kill-switch)"
    assert "FindWeather(uint32 zoneId) const" in driver, \
        "the read-only weather lookup is anchored (no create-on-miss)"
