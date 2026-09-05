"""The ACT-tool battery (A2/A3 host gate).

Compiles the SHIPPED pure core (native/patches/playerbots/
PlayerbotLlmToolsCore.h) on the host with -std=c++11 - like the json
client battery, the host always tests the shipped code, never a copy -
and runs its four legs (scanner / whitelist / emotes / beats). The
world-side executor mappings (DoSpecificAction surfaces, the duel
pre-commit guard, the license cross-check) are pinned by source-contract
assertions below, following test_llm_a0_unification.py's pattern: the
host cannot drive PlayerbotAI, so the contract that the world thread
implements is pinned instead, and the on-device leg records the rest.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
CORE = PATCHES / "PlayerbotLlmToolsCore.h"
TOOLS_CPP = PATCHES / "PlayerbotLlmTools.cpp"
BRIDGE_CPP = PATCHES / "PlayerbotLlmBridge.cpp"
BRIDGE_H = PATCHES / "PlayerbotLlmBridge.h"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
HARNESS = ROOT / "tools" / "test_llm_act_tools.cpp"


@pytest.fixture(scope="session")
def act_tools_binary(tmp_path_factory):
    gxx = shutil.which("g++") or shutil.which("clang++")
    if gxx is None:
        pytest.skip("no host C++ compiler available")
    if not CORE.is_file() or not HARNESS.is_file():
        pytest.skip("act tools core not staged")
    tmp = tmp_path_factory.mktemp("acttools")
    exe = tmp / "act_tools_test.exe"
    build = subprocess.run(
        [gxx, "-std=c++11", "-O2", "-Wall", "-I", str(PATCHES),
         "-o", str(exe), str(HARNESS)],
        capture_output=True, text=True, timeout=300)
    if build.returncode != 0:
        pytest.fail(f"host compile failed:\n{build.stderr[:4000]}")
    return exe


def test_scanner_leg(act_tools_binary):
    result = subprocess.run([str(act_tools_binary), "scanner"],
                            capture_output=True, text=True, timeout=300)
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "scanner leg done" in result.stdout


def test_whitelist_leg(act_tools_binary):
    result = subprocess.run([str(act_tools_binary), "whitelist"],
                            capture_output=True, text=True, timeout=300)
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "whitelist leg done" in result.stdout


def test_emotes_leg(act_tools_binary):
    result = subprocess.run([str(act_tools_binary), "emotes"],
                            capture_output=True, text=True, timeout=300)
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "emotes leg done" in result.stdout


def test_beats_leg(act_tools_binary):
    result = subprocess.run([str(act_tools_binary), "beats"],
                            capture_output=True, text=True, timeout=300)
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "beats leg done" in result.stdout


def test_budget_leg(act_tools_binary):
    # The per-class voice budgets - conversational 2x160, ambient
    # 1x80, UTF-8 backoff at the cut (this leg once shipped unwired:
    # a ToolsCore mutation survived the suite with the leg absent)
    result = subprocess.run([str(act_tools_binary), "budget"],
                            capture_output=True, text=True, timeout=300)
    assert result.returncode == 0, result.stdout + result.stderr[:2000]
    assert "budget leg done" in result.stdout


# ------------------------------------------------- source-contract pins ----

def test_core_whitelist_matches_banklib_vocabulary():
    import re
    text = CORE.read_text(encoding="utf-8")
    known_block = text.split("IsKnownTool")[1].split("};")[0]
    found = set(re.findall(r'"([a-z_]+)"', known_block))
    assert found == {
        "log_fact", "adjust_sentiment", "share_gossip", "perform_emote",
        "duel_challenge", "give_item", "follow", "party_invite",
        "move_to", "loot_roll"}, \
        "the known-tool set must equal banklib VALID_TOOLS, exactly ten"


def test_emote_table_pins_all_nineteen_ids():
    import re
    text = CORE.read_text(encoding="utf-8")
    pins = {
        "WAVE": "101", "BOW": "17", "SALUTE": "78", "LAUGH": "60",
        "CRY": "31", "NOD": "67", "NO": "66", "POINT": "72", "CHEER": "21",
        "DANCE": "34", "FLEX": "41", "KISS": "58", "RUDE": "77",
        "GRIN": "49", "SHRUG": "83", "CHICKEN": "22", "WHISTLE": "104",
        "GLARE": "46", "HUG": "56",
    }
    for name, value in pins.items():
        assert re.search(rf"TEXTEMOTE_{name}_CORE\s*=\s*{value}\b", text), \
            f"emote {name.lower()} must pin TEXTEMOTE {value} (1.12 SharedDefines)"
    # and the resolver table wires every enum to its lowercase name
    resolver = text.split("ResolveTextEmote")[1]
    for name in pins:
        assert f"TEXTEMOTE_{name}_CORE" in resolver


def test_executor_license_check_precedes_dispatch():
    text = TOOLS_CPP.read_text(encoding="utf-8")
    # Slice the EXECUTOR body first (a bare find() anchors on the
    # LicensedField helper's guard - proven ineffective); ONE locked
    # read (LicensedLineFor) both gates and fetches; a non-empty line
    # IS the coverage proof
    executor = text.split("void PlayerbotLlmTools::ExecutePending(")[1]
    assert "if (licensedLine.empty())" in executor
    gate_pos = executor.find("if (licensedLine.empty())")
    dispatch_pos = executor.find("DoSpecificAction(")
    assert 0 < gate_pos < dispatch_pos, \
        "the license gate must run before any ACT dispatch"
    queue_pos = text.find("license.tools.count(call.name)")
    assert queue_pos > 0, "queue admission must filter by the licensed set"


def test_nudge_strip_is_event_turn_only():
    """A player whisper that happens to end in the
    nudge-shaped suffix keeps its actual words - the strip runs only on
    event-flagged turns, at every site."""
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    driver = DRIVER.read_text(encoding="utf-8")
    # the current turn is control-token scrubbed before
    # compose (a forged "[RESULT]"/"[BRIDGE AI]" line must not render as
    # bridge-authored furniture in its own turn)
    assert "ScrubControlTokens(PlayerbotLlmBridge::NormalizeTurn(" in memory.replace("\r\n", "\n")
    assert "eventTurn\n        ? NormalizeTurn" in bridge.replace("\r\n", "\n")
    # the SayAction memory block strips only inside the eventTurn branch
    assert "if (eventTurn)\n            {" in driver.replace("\r\n", "\n")


def test_executor_routes_text_emotes_not_animation_ids():
    text = TOOLS_CPP.read_text(encoding="utf-8")
    assert "SMSG_TEXT_EMOTE" in text
    assert "HandleTextEmoteOpcode" in text
    emote_branch = text.split('call.name == "perform_emote"')[1].split("else if")[0]
    assert "bot->HandleEmoteCommand(" not in emote_branch, \
        "perform_emote must not CALL the ONESHOT animation path (comments may name it)"
    # the resolve+deliver code moved into the shared PlayTextEmote
    # helper (the crowd tier's own path); the branch delegates to it
    assert 'PlayTextEmote(bot, player, LicensedField(licensedLine, "emote"))' in emote_branch
    helper = text.split("void PlayerbotLlmTools::PlayTextEmote")[1].split("void PlayerbotLlmTools::ExecutePending")[0]
    assert "ResolveTextEmote" in helper
    assert "GetNumberOfEmoteVariants" in helper, \
        "variant count mirrors EmoteActionBase"


def test_duel_precommit_guard_precedes_cast():
    text = TOOLS_CPP.read_text(encoding="utf-8")
    guard_pos = text.find("bool CanCommitDuel(")
    duel_branch = text.split('call.name == "duel_challenge"')[1].split("else if")[0]
    assert guard_pos > 0
    assert "CanCommitDuel(bot, player)" in duel_branch
    assert 'LicensedField(licensedLine, "name") != player->GetName()' in duel_branch, \
        "target consistency: the licensed note line names the speaker"
    assert '7266' in duel_branch, "the RpgDuel cast idiom"
    assert duel_branch.find("CanCommitDuel") < duel_branch.find("7266")


def test_move_to_resolves_poi_from_the_licensed_line():
    text = TOOLS_CPP.read_text(encoding="utf-8")
    # move_to is the TERMINAL dispatch branch (the executor is the
    # file's last function): no `else if` follows it, so the slice is
    # the branch body plus closing braces by construction. If code is
    # ever appended after the executor, bound this slice properly.
    branch = text.split('call.name == "move_to"')[1]
    # the place executes from the LICENSED line and is
    # re-resolved against the lore POI index at execution time - an
    # unresolvable place stays a refusal, never a blind path
    assert "ResolvePoiPlace" in branch, "move_to must re-resolve the place"
    assert 'LicensedField(licensedLine, "place")' in branch, \
        "the place is bridge-decided, never the model's copy"
    assert 'DoSpecificAction("go"' in branch, "the go-action idiom maps the move"
    assert "if (!playerTurn || !player)" in branch, "moves act toward the speaker"


def test_give_item_resolves_bot_inventory_never_model_claims():
    text = TOOLS_CPP.read_text(encoding="utf-8")
    branch = text.split('call.name == "give_item"')[1].split("else if")[0]
    assert "FindBagItemByName" in branch
    # the item noun comes from the LICENSED line, not the model's copy
    assert 'LicensedField(licensedLine, "item")' in branch
    assert 'LicensedField(licensedLine, "player") != player->GetName()' in branch
    assert "CMSG_INITIATE_TRADE" in branch, "the RpgTradeUsefulAction idiom"
    assert '"trade"' in branch


def test_bridge_decided_fields_come_from_the_licensed_line():
    """A2's field-authority rule: names/items/direction/emote/choice/category
    execute from the licensed note line; only fill-hint prose (text/reason)
    reads the model's copy. A model cannot flip a sentiment direction,
    redirect a trade, or retarget a duel by rewriting fields."""
    text = TOOLS_CPP.read_text(encoding="utf-8")
    assert 'LicensedField(licensedLine, "direction")' in text, \
        "sentiment direction is bridge-decided (prtools rec #3)"
    assert 'LicensedField(licensedLine, "emote")' in text
    assert 'LicensedField(licensedLine, "category")' in text
    assert 'LicensedField(licensedLine, "choice")' in text
    # the model's copy remains the source ONLY for fill-hint prose
    sentiment = text.split('call.name == "adjust_sentiment"')[1].split("else if")[0]
    assert 'GetField(call.fields, "reason")' in sentiment
    logfact = text.split('call.name == "log_fact"')[1].split("else if")[0]
    assert 'GetField(call.fields, "text")' in logfact


def test_follow_and_invite_guards():
    text = TOOLS_CPP.read_text(encoding="utf-8")
    follow = text.split('call.name == "follow"')[1].split("else if")[0]
    assert "GetMaster() != player" in follow, "follow executes for the master only"
    assert '"+follow"' in follow
    invite = text.split('call.name == "party_invite"')[1].split("else if")[0]
    assert '"invite"' in invite
    assert "IsMember(player->GetObjectGuid())" in invite, "no double invite"


def test_loot_roll_choice_parity():
    text = TOOLS_CPP.read_text(encoding="utf-8")
    branch = text.split('call.name == "loot_roll"')[1].split("else if")[0]
    for choice in ("need", "greed", "pass"):
        assert f'"{choice}"' in branch, f"banklib LOOT_CHOICES member {choice}"


def test_bridge_records_license_at_note_construction():
    text = BRIDGE_CPP.read_text(encoding="utf-8")
    build_note = text.split("PlayerbotLlmBridge::BuildNote(Player* bot")[1]
    assert "RecordLicense" in build_note, \
        "BuildNote must record the license for the executor cross-check"
    inner = text.split("BuildNoteInner(Player* bot")[1].split("} // namespace")[0]
    assert "RecordLicense" not in inner, "recorded exactly once, at the wrapper"


def test_bridge_beats_use_trained_note_shapes():
    # pins run against the de-escaped source (the C++ literals carry \")
    text = BRIDGE_CPP.read_text(encoding="utf-8").replace('\\"', '"')
    assert '<<duel_challenge name="' in text
    assert '<<give_item player="' in text and ' item="' in text
    assert '<<follow name="' in text
    assert '<<party_invite name="' in text
    # the existing beat texts are untouched (no wording changes while
    # the battery pins them)
    assert '<<adjust_sentiment direction="-1" reason="...">>' in text
    assert '<<log_fact text="..." category="shared-event">>' in text
    assert '<<share_gossip text="...">>' in text
    assert '<<perform_emote emote="laugh">>' in text


def test_bridge_insult_second_person_gate():
    text = BRIDGE_CPP.read_text(encoding="utf-8")
    assert "pocketllm::IsSecondPerson(normalizedMsg" in text, \
        "the insult beat must be second-person gated (S5-logged fix)"


def test_event_prefix_is_gone_as_a_signal():
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    assert "IsEventTurn" not in bridge and "IsEventTurn" not in memory, \
        "no string-sniffing event detection anywhere"
    assert '(event) " <<' not in memory, \
        "event texts emit no prefix - the drain flag owns the semantics"
    # the pseudo-speaker stays (it is INTERNAL history bookkeeping, keyed
    # off the flag, not forgeable text)
    assert '"(event)"' in memory


def test_event_drain_flag_threading():
    driver = DRIVER.read_text(encoding="utf-8")
    assert "bool isEventTurn = false" in driver, \
        "ChatReplyDo declaration gains the defaulted event flag"
    assert "bool const llmEventTurn = isEventTurn;" in driver
    assert "BuildTrainedChatRequest(bot, player, msg, llmTrainedKey, llmAbsencePre, llmTierPre, llmEventTurn, llmEventKind, &llmLicenseStamp)" in driver
    assert "llmTurn.absence = llmAbsencePre;" in driver, \
        "the legacy bridge turn carries the S8 TurnState"
    assert "/*isEventTurn=*/true, reaction.eventKind" in driver, \
        "the drain passes the flag AND the event kind"
    assert 'msg.rfind("(event) ", 0)' not in driver, \
        "no text-prefix event detection in the driver anchors"


def test_license_stamp_threads_from_note_to_extraction():
    """The stamp must tag the GENERATION'S OWN note, not the
    bot's live license at completion time - an interleaved newer note or a
    note-less autonomous generation can otherwise be vetted against a
    license that never drove it."""
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    assert "uint64_t PlayerbotLlmBridge::RecordLicense" in bridge
    assert "note.stamp = RecordLicense(" in bridge
    # stamp equality is the load-bearing expression of the license read
    assert "license.stamp == 0 || license.stamp != stamp || !license.tools.count(tool)" in bridge
    tools = TOOLS_CPP.read_text(encoding="utf-8")
    assert "uint64_t licenseStamp)" in tools  # ExtractAndQueue signature
    assert "if (licenseStamp == 0)" in tools
    assert "return cleaned; // this generation built no note: nothing licensed" in tools
    assert "if (license.stamp != licenseStamp)" in tools
    assert "queued.licenseStamp = licenseStamp;" in tools
    driver = DRIVER.read_text(encoding="utf-8")
    # the stamp is captured at note time and carried through the async call
    assert "uint64_t llmLicenseStamp = 0;" in driver
    assert "LLM_SRC_CHAT_REPLY, llmLicenseStamp, llmHistoryKey," in driver
    # note-less paths pass 0 and therefore queue nothing
    assert "LLM_SRC_RPG_CHAT, uint64_t(0), 0," in driver
    assert "LLM_SRC_DEBUG, uint64_t(0), sPlayerbotAIConfig" in driver
    assert "Generate(json, botGuid, speakerGuid, source, licenseStamp, sPlayerbotAIConfig" in driver


def test_follow_and_invite_name_pins():
    """Every ACT tool validates the licensed line's name against the
    speaker (target consistency) - follow and party_invite included."""
    text = TOOLS_CPP.read_text(encoding="utf-8")
    follow = text.split('call.name == "follow"')[1].split("else if")[0]
    assert 'LicensedField(licensedLine, "name") != player->GetName()' in follow
    invite = text.split('call.name == "party_invite"')[1].split("else if")[0]
    assert 'LicensedField(licensedLine, "name") != player->GetName()' in invite


def test_give_item_trades_only_with_the_licensed_speaker():
    """An already-open trade with a DIFFERENT player must
    never receive the licensed item (TradeAction adds to the live trade)."""
    text = TOOLS_CPP.read_text(encoding="utf-8")
    branch = text.split('call.name == "give_item"')[1].split("else if")[0]
    assert "Player* const trader = bot->GetTrader();" in branch
    assert "if (trader && trader != player)" in branch


def test_gratitude_beat_is_second_person_gated():
    """Symmetry pin: the gratitude beat carries the same
    second-person gate as the insult beat."""
    text = BRIDGE_CPP.read_text(encoding="utf-8")
    gratitude = text.split("kGratitudeTriggers")[4]  # after the last mention
    assert "IsSecondPerson" in gratitude


def test_duel_outcome_hook_anchor():
    driver = DRIVER.read_text(encoding="utf-8")
    assert "CORE_DUELCOMPLETE_UPSTREAM" in driver
    assert "PlayerbotLlmMemory::OnDuelComplete(this, duel->opponent, type);" in driver
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    impl = memory.split("void PlayerbotLlmMemory::OnDuelComplete(")[1]
    assert "NoteVerifiedEvent" in impl, "the verified-event window opens"
    assert "EventReaction reaction;" in impl
    assert "CHAT_MSG_WHISPER" in impl, "duel reactions whisper the partner"


def test_absence_fallback_trap_removed():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    # scope the pin to the builder itself: later functions (the
    # initiative scheduler) read the bucket legitimately, pre-stomp by
    # construction - no relationship write has queued on that path
    trained = memory.split("BuildTrainedChatRequest(Player* bot")[1] \
        .split("std::string PlayerbotLlmMemory::GetRelationshipTier")[0]
    assert "GetAbsenceBucket(bot, player)" not in trained, \
        "the racing live-read fallback is gone from the trained builder"
    assert '"a first meeting"' in trained, \
        "an empty bucket degrades to the first-meeting reading"


def test_event_reaction_channel_field():
    header = MEMORY_H.read_text(encoding="utf-8")
    assert "uint32 msgtype;" in header
    assert "msgtype(0)" in header


def test_driver_overlay_manifest_covers_new_core():
    driver = DRIVER.read_text(encoding="utf-8")
    assert '"playerbot/PlayerbotLlmToolsCore.h",' in driver, \
        "overlay manifest entry"
    assert '(bot_root / "PlayerbotLlmToolsCore.h").write_bytes' in driver, \
        "overlay copy line"
