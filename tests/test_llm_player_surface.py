"""The player-surface source-contract pins.

The host batteries pin the pure cores (the reply budgets in
test_llm_act_tools, the tier-shift line in test_llm_recall); this file pins
the world-side glue the host cannot drive, following test_llm_recall.py's
pattern: the driver's ANDROID anchor blocks, the overlay files, and the
Kotlin/Lua surfaces are asserted by content contract. Every load-bearing
pin here was mutation-tested (each mutant killed by exactly one pin).
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"
PATCHES = ROOT / "native" / "patches" / "playerbots"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"
BRIDGE_CPP = PATCHES / "PlayerbotLlmBridge.cpp"
ADDON = ROOT / "android" / "app" / "src" / "main" / "assets" / "addons" / "android-port" / "AndroidPort"


def android_anchor(name: str) -> str:
    """The ANDROID replacement text of a driver anchor constant."""
    return DRIVER.read_text(encoding="utf-8").split(name + " = ")[1].split('"""')[1]


# ---- E1 pacing law --------------------------------------------------------


def test_timediff_is_a_running_budget_across_all_lines():
    head = android_anchor("PB_SAY_TIMEDIFF_HEAD_ANDROID")
    tail = android_anchor("PB_SAY_TIMEDIFF_TAIL_ANDROID")
    for block in (head, tail):
        assert "timeDiff -= delay;" in block, \
            "the generation credit must carry to later lines, not zero out"
        assert "delay = 0;" in block
    # the old single-shot form (credit consumed by the first line) must be
    # gone from the >= branch: the running decrement replaces it
    assert "timeDiff -= delay;\n                        delay = 0;" in head


def test_pace_call_drops_to_35ms_and_busy_lands_instantly():
    anchor = android_anchor("PB_SAY_PACE_CALL_ANDROID")
    assert anchor.count("busyReply ? 0 : 35") == 1, \
        "MsPerChar drops 200 -> 35 on the reply path"
    # E1b: the credit is capped and the reaction/floor knobs ride the
    # conversational call only - every pacing argument is busy-gated, so
    # the busy placeholder still lands instantly (no pacing, no credit)
    assert "busyReply ? 0 : std::min<uint32>(timeDiff, 2500)" in anchor, \
        "the generation credit is capped (a long generation never cancels the typing pace)"
    assert "busyReply ? 0 : urand(400, 1200), busyReply ? 0 : 400" in anchor, \
        "the reaction beat and per-line floor are conversational-only"
    assert anchor.count("busyReply ? 0 :") == 4, \
        "the busy placeholder lands instantly (no pacing, no credit)"
    assert "false, 200, emoteTemplate" not in anchor


def test_journal_keeps_its_diary_pace():
    context = android_anchor("PB_SAY_CONTEXT_ANDROID")
    assert "journalTemplate, false, 4, WorldPacket(), 0" in context, \
        "the journal surface keeps its 4 ms/char diary pace (E1: unchanged)"


def test_reply_class_budgets_thread_through_both_callers():
    decl = android_anchor("PB_SAY_GEN_DECL_ANDROID")
    assert "uint32 replyClass = 0" in decl, "defaulted conversational class"
    assert "bool longFormCued = false" in decl, \
        "the long-form cue flag defaults false (the RPG path opts out)"
    body = android_anchor("PB_SAY_GEN_DEF_ANDROID")
    # A4 widened the definition's tail (the FallbackPlan rides after
    # reqId); the pin follows the budget threading, not the ending
    assert "uint32 replyClass, bool longFormCued, uint64_t reqId" in body
    recorder = android_anchor("PB_SAY_RECORDER_ANDROID")
    assert "pocketllm::ApplyReplyBudget(lines, replyClass, sPlayerbotAIConfig.llmMaxNewTokens, longFormCued, sPlayerbotAIConfig.llmRpLongForm);" \
        in recorder, \
        "the voice budget applies before the history recorder, scaled by the " \
        "tier's max new tokens, the turn's own cue state, AND the preset's " \
        "longForm dial (S11: the widening is earned per turn, never " \
        "tier-wide, and the budget license must match the cue's license)"
    # pin the ORDER, not just presence - history and the player
    # must see the same words
    assert recorder.index("ApplyReplyBudget(lines, replyClass") < \
        recorder.index("AppendTurn(botGuid, playerOrChannel, true, botName"), \
        "the clamp runs before the history recorder writes the lines"
    # the chat async resolves the cue flag stamp-checked
    # against the generation's own license (the RPG path defaults false)
    chat = android_anchor("PB_SAY_ASYNC_ANDROID")
    assert "PlayerbotLlmBridge::NoteLongFormCued(bot->GetGUIDLow(), llmLicenseStamp)" in chat, \
        "the long-form widening resolves against the generation's own note"
    # conversational = whisper class; the ambient RPG path is the 1-line class
    assert "splitPattern, debug, 0u, PlayerbotLlmBridge::NoteLongFormCued" in chat
    rpg = android_anchor("PB_RPG_ASYNC_ANDROID")
    assert "splitPattern, debug, 1u, false, llmReqId, PlayerbotLlmGates::FallbackPlan());" in rpg, \
        "ambient passes the cue flag explicitly false and the " \
        "default-constructed (inactive) fallback plan (defaults do not " \
        "bind through the std::async function pointer)"
    assert "a bark, not a speech" in rpg


def test_conversation_counter_rides_the_recorder_gate():
    recorder = android_anchor("PB_SAY_RECORDER_ANDROID")
    assert "PlayerbotLlmMemory::NoteConversation();" in recorder
    assert "ConversationCount()" in recorder, \
        "the debug surface carries the diagnostics counter"
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    assert "std::atomic<uint64_t>& ConversationCounter()" in memory
    assert "ConversationCounter().fetch_add(1" in memory


def test_ack_is_instant_whisper_gated_and_pre_generation():
    context = android_anchor("PB_SAY_CONTEXT_ANDROID")
    gate = "if (chatChannelSource == ChatChannelSource::SRC_WHISPER && !llmEventTurn)"
    ack_call = "PlayerbotLlmMemory::AcknowledgeWhisper(bot, player);"
    assert gate in context and ack_call in context
    assert context.index(gate) < context.index(ack_call), "the ack is gated"
    # E1's ordering law: acknowledgment BEFORE the memory reads and the
    # generation queue
    assert context.index(ack_call) < context.index("GetPreStompState(bot, player)")


def test_memory_ack_faces_and_rate_caps():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    ack = memory.split("void PlayerbotLlmMemory::AcknowledgeWhisper")[1].split("\n}\n")[0]
    assert "bot->SetFacingToObject(player);" in ack, "turn-to-face before the emote"
    # order pinned, not just presence
    assert ack.index("SetFacingToObject") < ack.index("PlayTextEmote"), \
        "the turn-to-face lands before the emote"
    assert "PlayTextEmote" in ack, "A3's own delivery path, zero LLM cost"
    assert "< 4" in ack, "per-pairing rate cap (no emote spam in fast exchanges)"
    assert "player->isRealPlayer()" in ack


# ---- T4 connect timeout ---------------------------------------------------


def test_connect_timeout_bounds_the_tcp_connect():
    anchor = android_anchor("PB_IFACE_CONNECT_ANDROID")
    assert "sPlayerbotAIConfig.llmConnectTimeout;" in anchor, \
        "the connect budget is the conf key (10 s default)"
    assert "O_NONBLOCK" in anchor and "select(" in anchor
    assert "SO_ERROR" in anchor, "the writable-socket verdict is verified"
    assert "ETIMEDOUT" in anchor, "a select timeout reports as ETIMEDOUT"
    assert "fcntl(sock, F_SETFL, sockFlags);" in anchor, \
        "the socket returns to blocking mode for the send/recv legs"
    # the generation budget must bound EVERY socket leg - a
    # stalled TLS handshake or write would otherwise hang the generation
    # thread and permanently hold its generation slot
    assert "SO_RCVTIMEO" in anchor and "SO_SNDTIMEO" in anchor
    assert anchor.index("SO_RCVTIMEO") > anchor.index("if (connected")
    sock = android_anchor("PB_IFACE_SOCKINCLUDE_ANDROID")
    assert "sys/select.h" in sock


def test_connect_timeout_conf_key_is_clamped_and_doc_line_exists():
    header = android_anchor("PB_LLM_CONFIG_HEADER_ANDROID")
    assert "uint32 llmConnectTimeout;" in header
    timeout = android_anchor("PB_LLM_TIMEOUT_ANDROID")
    assert "AiPlayerbot.LLMConnectTimeout\", 10" in timeout
    # a hand value of 0 or negative must not void the budget
    assert "std::max(1u, std::min(60u" in timeout
    conf = android_anchor("PB_LLM_CONF_ANDROID")
    assert "# AiPlayerbot.LLMConnectTimeout = 10" in conf


# ---- E2 first contact -----------------------------------------------------


def test_login_onboarding_hook_is_anchored_and_restored():
    driver = DRIVER.read_text(encoding="utf-8")
    anchor = driver.split("CORE_LOGIN_ONBOARDING_ANDROID = ")[1].split('"""')[1]
    assert "PlayerbotLlmMemory::OnPlayerLogin(this);" in anchor
    assert "SendItemDurations();" in anchor, \
        "the anchor is the tail of SendInitialPacketsAfterAddToMap (login)"
    # CORE-tree anchors need BOTH the apply and restore entries
    apply_count = driver.count(
        "CORE_LOGIN_ONBOARDING_UPSTREAM, CORE_LOGIN_ONBOARDING_ANDROID)")
    restore_count = driver.count(
        "        CORE_LOGIN_ONBOARDING_ANDROID,\n"
        "        CORE_LOGIN_ONBOARDING_UPSTREAM,\n    )")
    assert apply_count == 1 and restore_count == 1


def test_onboarding_line_is_once_per_character_and_ascii():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    login = memory.split("void PlayerbotLlmMemory::OnPlayerLogin")[1].split("\n}\n")[0]
    assert "PlayerHasAnyPairing(player->GetGUIDLow())" in login, \
        "the first pairing silences the line forever"
    assert "GetPlayerbotAI()" in login, "bots never see the onboarding line"
    # the anchor site (SendInitialPacketsAfterAddToMap) also
    # fires on every cross-map teleport - the once-per-process dedupe makes
    # the line a true one-time voice and skips the repeat DB query
    assert "if (!OnboardedPlayers().insert(player->GetGUIDLow()).second)" in login, \
        "the dedupe's early return must key on the insert itself"
    assert "The people of this realm will talk back - walk up and greet them by name." in login
    assert "—" not in login, "ASCII hyphen only (the sys-line channel)"


def test_standing_one_liner_fires_on_first_whisper_of_session():
    # the AUTOMATIC surface ("standing one-liner on first
    # whisper of a session"), not just the keyword
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    standing = memory.split("void PlayerbotLlmMemory::MaybeSessionStandingLine")[1].split("\n}\n")[0]
    assert 'preStompAbsence == "a first meeting"' in standing, \
        "a first meeting has no standing worth voicing (the E2 welcome owns it)"
    assert "if (!StandingVoicedPairs().insert(pairKey).second)" in standing, \
        "once per pairing per process - the early return keys on the insert itself"
    assert "SendSysMessage" in standing, "system-colored, zero generation"
    context = android_anchor("PB_SAY_CONTEXT_ANDROID")
    call = "PlayerbotLlmMemory::MaybeSessionStandingLine(bot, player, llmAbsencePre);"
    assert call in context
    # wired AFTER the pre-stomp read (it consumes the absence), BEFORE any
    # early-return path can swallow the first whisper
    assert context.index("GetPreStompState(bot, player)") < context.index(call)
    assert context.index(call) < context.index('lowerMsg == "journal"')


def test_first_contact_welcome_is_scripted_only_for_the_first_pairing():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    welcome = memory.split("AuthoredFirstContactWelcome")[1].split("\n}\n")[0]
    assert "PlayerHasAnyPairing" in welcome, "every later pairing generates normally"
    assert "LogFact(" in welcome, \
        "the first-meeting fact still forms (the memory law beats the voice law)"
    assert "shared-event" in welcome
    header = MEMORY_H.read_text(encoding="utf-8")
    assert "static std::string AuthoredFirstContactWelcome(Player* bot, Player* player);" in header


def test_say_anchor_intercepts_the_welcome_and_keywords():
    context = android_anchor("PB_SAY_CONTEXT_ANDROID")
    welcome = context.split("First-contact onboarding")[1].split("// Journal interception")[0]
    assert 'llmAbsencePre == "a first meeting"' in welcome, \
        "only a genuine first meeting can take the scripted path"
    assert "AuthoredFirstContactWelcome(bot, player)" in welcome
    assert "AddRelationshipPoints(bot, player, 1);" in welcome, \
        "the scripted welcome keeps the relationship touch (a real conversation)"
    keywords = context.split("Keyword surfaces")[1].split("// Persona fallback")[0]
    assert 'lowerMsg == "standing"' in keywords
    assert 'lowerMsg == "gossip"' in keywords
    assert "chatChannelSource == ChatChannelSource::SRC_WHISPER && !llmEventTurn" in keywords
    assert "StandingLine(bot, player)" in keywords
    assert "GossipLines(bot, player)" in keywords
    # keyword reads award NO relationship points - an uncapped
    # +1 per bare keyword whisper was a zero-cost tier-5 farm
    assert "AddRelationshipPoints" not in keywords, \
        "the keyword surfaces are reads, not conversations (journal precedent)"


# ---- E4 visible progression ------------------------------------------------


def test_tier_shift_sys_line_rides_the_observed_crossing():
    bridge = BRIDGE_CPP.read_text(encoding="utf-8")
    ceremony = bridge.split("ConsumeTierTransition(bot->GetGUIDLow()")[1].split("\n    }")[0]
    assert "TierShiftSysLine(bot->GetName(), crossed > 0)" in ceremony
    assert "GetSession()" in ceremony, "sent to the player, not the world"


def test_standing_line_reads_the_live_tier():
    memory = MEMORY_CPP.read_text(encoding="utf-8")
    standing = memory.split("std::string PlayerbotLlmMemory::StandingLine")[1].split("\n}\n")[0]
    assert "GetTrainedTier(bot, player)" in standing
    assert "TierProse" in standing, "prose label, never the storage enum"


# ---- E3 the Talk flow ------------------------------------------------------


def test_talk_module_ships_and_resolves_without_name_typing():
    talk = (ADDON / "Talk.lua").read_text(encoding="utf-8")
    assert "UnitIsPlayer(\"target\")" in talk, "an explicit player target wins"
    assert "lastWhisperFrom" in talk and "lastSayFrom" in talk, \
        "target -> last whisperer -> last speaker (the 1.12 client has no unit radar)"
    assert '"/w " .. name .. " "' in talk, "the composer opens pre-filled"
    assert 'type(ChatFrame_OpenChat) == "function"' in talk, \
        "the stock open/focus lifecycle, type-guarded for FrameXML reshuffles"
    assert "ChatFrame_OpenChat(prefixed, DEFAULT_CHAT_FRAME)" in talk, \
        "the guarded primary path actually calls it"
    assert 'CHAT_MSG_WHISPER_INFORM' in talk, "outgoing whispers refresh the target"
    assert 'IsModuleEnabled("talk")' in talk, "bisection switch honored"
    # an opened-but-unsend composer must not write the resolved
    # name back into the whisper tracker (resolution stays event-driven)
    assert "self.lastWhisperFrom = name" not in talk
    toc = (ADDON / "AndroidPort.toc").read_text(encoding="utf-8")
    assert "Talk.lua" in toc


def test_radial_talk_takes_socials_slot_and_move_ui_stays():
    radial = (ADDON / "Radial.lua").read_text(encoding="utf-8")
    assert '{ name = "Talk"' in radial
    assert "AndroidPort.Talk:Open()" in radial
    # Move UI keeps its slot (pinned by AndroidPortAssetTest), with its
    # F8 binding; Talk takes Social's slot instead (the
    # offline realm's friends list is empty; touch reaches the minimap
    # button)
    assert '{ name = "Move UI"' in radial
    assert '{ name = "Social"' not in radial
    core = (ADDON / "Core.lua").read_text(encoding="utf-8")
    assert 'SetBinding("F8", "AP_MOVE_UI")' in core


def test_hud_grows_while_talking_and_reveals_scroll_on_burst():
    hud = (ADDON / "Hud.lua").read_text(encoding="utf-8")
    assert "CHAT_TALK_HEIGHT = 220" in hud
    assert "ConversationActive() and CHAT_TALK_HEIGHT or CHAT_HEIGHT" in hud
    assert "CHAT_MSG_WHISPER_INFORM" in hud, "both directions of the exchange"
    assert "BurstActive()" in hud, "the journal dump reveals the scroll chrome"
    # the journaled guard is pinned on the real block, not on
    # the word appearing anywhere in the file - a player-journaled rect is
    # never resized out from under them
    apply_block = hud.split("function Hud:ApplyChatFrame()")[1].split("\nend")[0]
    assert "if not journaled then" in apply_block
    assert apply_block.index("if not journaled then") < \
        apply_block.index("ConversationActive() and CHAT_TALK_HEIGHT"), \
        "the tall rect only applies inside the non-journaled branch"


def test_whisper_is_direct_address_and_commands_keep_their_wall():
    """QA run-4 (the silent whisper lane): a whisper to a bot is a sentence
    aimed at the bot, but two upstream artifacts starved the whole
    conversation lane. (1) The SMSG_MESSAGECHAT reply armament computed
    isMentioned by name-substring and dropped every un-mentioned event 4
    times out of 5 - and a whisper's text does not contain the bot's name -
    so arming was a 1-in-5 lottery. (2) HandleCommand's group-state gates
    refused whispers to grouped bots (FULL_GROUP/NOT_LEADER rate the bot
    below INVITE) and spoke an unrelated command-refusal line, so the
    player's one reply was canned refusal noise. The payloads: a whisper is
    direct address (isMentioned), and a multi-word whisper with no command
    shape keeps the gates' refuse-to-execute (identical security) but loses
    the spoken refusal - the AI reply is the one answer. Single-word
    whispers and link/money-marked text keep today's spoken wall."""
    mention = android_anchor("PB_AI_WHISPER_MENTION_ANDROID")
    assert "if (msgtype == CHAT_MSG_WHISPER)" in mention
    assert "isMentioned = true;" in mention, \
        "a whisper must arm the reply deterministically"
    upstream = DRIVER.read_text(encoding="utf-8")
    assert ('PB_AI_WHISPER_MENTION_UPSTREAM = """                bool '
            'isMentioned = message.find(bot->GetName()) != '
            'std::string::npos;' in upstream), \
        "the mention anchor rides the pristine line byte-exactly"
    gate1 = android_anchor("PB_AI_CMD_GATE1_ANDROID")
    gate2 = android_anchor("PB_AI_CMD_GATE2_ANDROID")
    for block in (gate1, gate2):
        assert ("(type != CHAT_MSG_WHISPER) || llmConversationalWhisper"
                in block), "both gates carry the conversational silent flag"
        assert "return;" in block, (
            "refused text still never executes - the security posture is "
            "unchanged")
    # the classifier's five conditions, pinned load-bearing
    assert "type == CHAT_MSG_WHISPER" in gate1
    assert "filtered.find(' ') != std::string::npos" in gate1, \
        "single-word whispers keep the spoken wall"
    assert "!GetChatHelper()->parseable(filtered)" in gate1, \
        "link/money/trade-marked text keeps the spoken wall"
    assert "commandSeparator" in gate1 and "commandPrefix" in gate1
    # and the registration is real (the drift-raise mechanics hold)
    driver = DRIVER.read_text(encoding="utf-8")
    for name in ("PB_AI_WHISPER_MENTION", "PB_AI_CMD_GATE1", "PB_AI_CMD_GATE2"):
        assert (f'replace_anchor(bot_root / "PlayerbotAI.cpp", '
                f'{name}_UPSTREAM, {name}_ANDROID)' in driver), name
