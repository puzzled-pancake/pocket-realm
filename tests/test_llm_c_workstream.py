"""WS-C wiring (plan v2.3 C1/C3/C4/C5/C6/C7/C8) - host pins.

The pure halves (RewordFirstMeetingRow, TownTalkClause) run on the host
in the banter battery; this file pins the runtime halves that live in
the overlays and driver payloads:
  C1  render-time rewording at BOTH fetch surfaces (the trained facts
      segment + the journal), tier-gated at acquaintance+
  C3  the de-framed dossier mint (no "The word on" double frame), the
      4-category pick, the party-talk exclusion, the dropped 7-day cloud
      gate, and the clamp+hygiene+name-match acceptance for the reword
  C4  the per-master digest window at the A3 block, the storyteller pick
      (tier >= 3, SelectResponder), MintOnceFact keying, the quota
  C5  tier_since stamps only on crossings (ODKU order), the persisted
      last_voiced_tier seed with the 48 h freshness gate, the per-player
      ceremony-rider coalescing with the per-crossing sys line
  C6  the capped turn award (both lanes' sites route through it), the
      deed values at their hooks, the quest anchor's !IsRepeatable gate
  C7  the boot nonce (kill-switch keeps the zero nonce), the greet-line
      exclusion + persistence columns
  C8  the history INSERT/trim inside the AppendTurn choke point, the
      lazy hydration, the LLMHistoryPersist gate
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCHES = ROOT / "native" / "patches" / "playerbots"
MEMORY_CPP = PATCHES / "PlayerbotLlmMemory.cpp"
MEMORY_H = PATCHES / "PlayerbotLlmMemory.h"
BRIDGE_CPP = PATCHES / "PlayerbotLlmBridge.cpp"
PERSONA_CPP = PATCHES / "PlayerbotLlmPersona.cpp"
BANTER_CORE = PATCHES / "llm_banter_core.h"
DRIVER = ROOT / "tools" / "build_o09_realm_runtime.py"


def android_anchor(name: str) -> str:
    return DRIVER.read_text(encoding="utf-8").split(name + ' = """')[1].split('"""')[0]


def impl(src: Path, signature: str) -> str:
    text = src.read_text(encoding="utf-8")
    m = re.search(re.escape(signature) + r".*?\n\}", text, re.S)
    assert m, f"implementation not found: {signature}"
    return m.group(0)


class TestC1RenderReword:
    def test_prompt_facts_segment_rewords(self):
        body = impl(MEMORY_CPP, "std::string PlayerbotLlmMemory::BuildTrainedChatRequest(")
        seg = body.split("---- facts")[1].split("std::vector<std::string> mems")[0]
        assert "RewordFirstMeetingRow" in seg
        # tier-gated: stranger rows render the first meeting verbatim
        assert "pastFirstMeeting = tier >= 2" in seg

    def test_journal_rewords(self):
        body = impl(MEMORY_CPP, "std::vector<std::string> PlayerbotLlmMemory::GetJournal(")
        assert "RewordFirstMeetingRow" in body
        # the tier read is hoisted OUT of the row loop (one query for the
        # whole render, not one relationship query per journal row)
        assert "int const journalTier = GetTrainedTier(bot, player);" in body
        assert "journalTier >= 2" in body

    def test_pure_reworder_is_prefix_stable(self):
        core = BANTER_CORE.read_text(encoding="utf-8")
        assert "inline bool IsFirstMeetingRow(" in core
        assert "inline std::string RewordFirstMeetingRow(" in core
        # the pure corpus law is host-run by the banter battery; here the
        # prefix-stability comment contract is pinned
        assert "PREFIX-STABLE" in core or "prefix-stable" in core.lower()


class TestC3TownTalk:
    def test_deterministic_mint_is_de_framed(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::MintWeeklyDossier(")
        assert "TownTalkClause" in body
        assert '"The word on "' not in body, "the double-frame mint is gone"

    def test_pick_query_widens_and_excludes_digests(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::MintWeeklyDossier(")
        assert "IN ('shared-event', 'preference', 'opinion', 'player-identity')" in body
        assert "NOT LIKE 'party talk:%%'" in body

    def test_cloud_gate_dropped_the_seven_day_age(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::MintWeeklyDossier(")
        assert "PairingAgeDays" not in body.split("ExternalApiTierActive()")[1].split("try")[0]

    def test_reword_acceptance_clamps_and_names(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::MintWeeklyDossier(")
        assert "TownTalkLineUsable(lines[0])" in body
        assert "ContainsWordExact(lines[0], playerName)" in body

    def test_the_usability_gate_lives_in_the_pure_core(self):
        core = (PATCHES / "PlayerbotLlmChatterCore.h").read_text(encoding="utf-8")
        m = re.search(r"inline bool TownTalkLineUsable\(.*?\n\}", core, re.S)
        assert m
        assert "LineIsValid" in m.group(0)
        assert "ContainsMarkerTerms" in m.group(0)
        assert "words <= 24" in m.group(0)


class TestC4PartyDigests:
    def test_window_written_at_the_a3_block(self):
        gate = android_anchor("PB_SAY_GATE_ANDROID")
        assert "NotePartyDigestLine(gateSpeaker->GetGUIDLow(), msg)" in gate
        assert "MaybeMintPartyDigest(gateSpeaker->GetGUIDLow()," in gate
        # the legacy roundtable row is untouched
        assert "NotePartyLine(gateSpeaker->GetGUIDLow(), msg)" in gate

    def test_window_is_bounded(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::NotePartyDigestLine(")
        assert "while (window.size() > 12)" in body
        assert "TruncUtf8(line, 160)" in body

    def test_storyteller_pick_and_quota(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::MaybeMintPartyDigest(")
        assert "c.tier >= 3" in body
        assert "SelectResponder(storytellers)" in body
        assert 'CloudQuotaAdmits("party-digest", sPlayerbotAIConfig.llmPartyDigestPerDay)' in body
        assert "MintOnceFact(writer, masterGuid," in body
        # the digest category is shared-event with the recognizable prefix
        assert '"shared-event"' in body
        assert '"party talk: "' in body


class TestC5CeremonyPersistence:
    def test_odku_tier_since_precedes_tier_mysql(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::AddRelationshipPoints(")
        mysql = body.split("#else")[1]
        assert mysql.index("`tier_since` = IF(") < mysql.index("`tier` = IF(")
        sqlite = body.split("#ifdef DO_SQLITE")[1].split("#else")[0]
        assert "`tier_since` = CASE WHEN `tier` <>" in sqlite

    def test_persisted_seed_has_the_freshness_gate(self):
        body = impl(MEMORY_CPP, "bool PlayerbotLlmMemory::PersistedLastVoicedTier(")
        assert "48 * 3600" in body
        assert "last_voiced_tier" in body

    def test_bridge_seeds_and_persists(self):
        bridge = BRIDGE_CPP.read_text(encoding="utf-8")
        assert "PersistedLastVoicedTier" in bridge
        assert "NoteTierVoiced" in bridge

    def test_rider_coalesces_per_player_sys_line_per_crossing(self):
        bridge = BRIDGE_CPP.read_text(encoding="utf-8")
        assert "CeremonyRiderAt" in bridge
        # the sys line sits OUTSIDE the riderAdmits arm: the rider's
        # cargo block closes (mandatesContent) BEFORE the per-crossing
        # sys line sends, marked by the law comment at the site
        i = bridge.index("TierShiftSysLine")
        rider = bridge.index("riderAdmits = false")
        close = bridge.index("mandatesContent = true;", rider)
        assert rider < close < i, "the rider arm closes before the sys line"
        assert "PER-CROSSING by law" in bridge[i - 400:i], \
            "the per-crossing sys-line law is stated at the site"


class TestC6Economics:
    def test_turn_awards_route_through_the_cap_both_lanes(self):
        ctx = android_anchor("PB_SAY_CONTEXT_ANDROID")
        assert "AwardChatTurn(bot, player)" in ctx
        rec = android_anchor("PB_SAY_RECORDER_ANDROID")
        assert "AwardChatTurnByGuid(botGuid, speakerGuid)" in rec
        # the raw award is gone from both turn sites
        assert "AddRelationshipPointsByGuid(botGuid, speakerGuid, 1)" not in rec

    def test_cap_consumed_by_turns_and_shared_kills_only(self):
        body = impl(MEMORY_CPP, "bool PlayerbotLlmMemory::AwardCappedPoints(")
        assert "llmTurnAwardDailyCap" in body
        kill = impl(MEMORY_CPP, "void PlayerbotLlmMemory::OnPlayerGroupKill(")
        assert "AwardCappedPoints(member, tapper," in kill
        trade = impl(MEMORY_CPP, "void PlayerbotLlmMemory::OnTradeCompleted(")
        assert "AwardCappedPoints" not in trade
        assert "llmDeedPointsTrade" in trade

    def test_trade_deed_rides_the_sentiment_admission(self):
        # round-1 R7#3: the farm law - N completed trades in 60 s award
        # exactly ONE deed. The deed is gated on the same SentimentRate
        # admission as the tone row (AddBoundedSentimentInput now returns
        # its verdict); an unconditional award would farm deeds.
        trade = impl(MEMORY_CPP, "void PlayerbotLlmMemory::OnTradeCompleted(")
        assert "bool const sentimentAdmitted = AddBoundedSentimentInput(" in trade
        deed = trade.index("llmDeedPointsTrade")
        gate = trade.index("if (sentimentAdmitted)")
        assert gate < deed, "the deed award must sit inside the admission gate"
        # the admission itself: one bounded input per (bot, player) per
        # minute, verdict surfaced to callers
        sent = impl(MEMORY_CPP, "bool PlayerbotLlmMemory::AddBoundedSentimentInput(")
        assert "return false; // rate-limited" in sent
        assert sent.rstrip().endswith("return true;\n}")

    def test_deed_zero_disables_award_not_fact(self):
        explore = impl(MEMORY_CPP, "void PlayerbotLlmMemory::OnPlayerExploredArea(")
        assert "llmDeedPointsFirstVisit" in explore
        assert explore.index("if (uint32 const deed") < explore.index("LogFact(bot->GetGUIDLow()")

    def test_quest_anchor_gates_repeatable(self):
        body = impl(MEMORY_CPP, "void PlayerbotLlmMemory::OnQuestRewarded(")
        assert "if (repeatable)" in body
        assert "return;" in body
        assert "llmDeedPointsQuest" in body
        driver = DRIVER.read_text(encoding="utf-8")
        assert 'replace_anchor(cmangos / "src" / "game" / "Entities" / "Player.cpp", CORE_REWARDQUEST_UPSTREAM, CORE_REWARDQUEST_ANDROID)' in driver
        assert "RemoveTimedQuest(quest_id);" in driver.split("CORE_REWARDQUEST_UPSTREAM = ")[1].split('"""')[1]
        # the anchor restores for --configure-only
        assert "CORE_REWARDQUEST_ANDROID,\n        CORE_REWARDQUEST_UPSTREAM," in driver


class TestC7GreetMemory:
    def test_boot_nonce_mixes_into_the_seed(self):
        core = BANTER_CORE.read_text(encoding="utf-8")
        assert "BanterBootNonce() * 0xC2B2AE35u" in core
        assert "inline void SetBanterBootNonce(" in core

    def test_kill_switch_keeps_the_zero_nonce(self):
        persona = PERSONA_CPP.read_text(encoding="utf-8")
        m = re.search(r"StateRef StateFor\(.*?\n\}", persona, re.S)
        assert "llmGreetMemory ? (uint32_t)time(nullptr) : 0u" in m.group(0)

    def test_greeting_line_redraws_past_the_persisted_line(self):
        body = impl(PERSONA_CPP, "std::string PlayerbotLlmPersona::GreetingLine(")
        assert "LastGreetLine(bot, player)" in body
        assert "sPlayerbotAIConfig.llmGreetMemory" in body

    def test_greeting_persist_rides_the_composer(self):
        # the composer leg moved behind the fail-soft containment
        # (AuthoredArrivalGreetingInner) - the persist must ride THERE
        body = impl(MEMORY_CPP, "std::string PlayerbotLlmMemory::AuthoredArrivalGreetingInner(")
        assert "NoteGreetingVoiced(bot, player, line)" in body
        stamp = impl(MEMORY_CPP, "void PlayerbotLlmMemory::NoteGreetingVoiced(")
        assert "last_greeted_at" in stamp and "last_greet_line" in stamp


class TestC8HistoryPersistence:
    def test_insert_rides_the_choke_point(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void AppendHistoryTurn\(.*?\n\}", src, re.S)
        assert "INSERT INTO `bot_player_history`" in m.group(0)
        assert "llmHistoryPersist" in m.group(0)
        assert "DELETE FROM `bot_player_history`" in m.group(0)

    def test_lazy_hydration_on_first_touch(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void HydrateHistoryIfNeeded\(.*?\n\}", src, re.S)
        assert m
        assert "ORDER BY `seq` DESC LIMIT 32" in m.group(0)
        assert "llmHistoryPersist" in m.group(0)

    def test_sqlite_timestamp_is_escaped_for_pexecute(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        assert "strftime('%%s','now')" in src


class TestC2VoicedFactPersistence:
    """Round-1 R4#1: voiced_at has exactly one stamp site (the
    TickInitiative delivery block, where the fact id is in hand
    synchronously - never at enqueue on delayed paths) and the
    InitiatedFactIds seed is lazy (the newest-6 PQuery also selects
    voiced_at; NULL = never voiced; no backfill)."""

    def test_the_stamp_site_is_the_delivery_block(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void PlayerbotLlmMemory::TickInitiative\(.*?\n\}", src, re.S)
        assert m, "TickInitiative missing"
        body = m.group(0)
        stamp = "UPDATE `bot_player_facts` SET `voiced_at` = '%u' WHERE `id` = '%u'"
        assert stamp in body
        # the stamp sits AFTER the in-process set and BEFORE the Say of
        # the SAME leg (the delivery point, not the enqueue): the
        # insert(usedFactId) is unique to the debt/goal leg, so the
        # window between it and the next Say is that leg's delivery
        delivered = body.index("InitiatedFactIds()[key].insert(usedFactId);")
        said = body.index("bot->Say(line, LANG_UNIVERSAL);", delivered)
        assert delivered < body.index(stamp) < said
        # exactly one stamp site in the whole overlay
        assert src.count("SET `voiced_at`") == 1

    def test_the_lazy_seed_selects_voiced_at_in_the_newest6_query(self):
        src = MEMORY_CPP.read_text(encoding="utf-8")
        m = re.search(r"void PlayerbotLlmMemory::TickInitiative\(.*?\n\}", src, re.S)
        body = m.group(0)
        assert "SELECT `id`, `fact_text`, `category`, `voiced_at` FROM `bot_player_facts`" in body
        # a voiced row seeds the in-process set (one initiation EVER, not
        # one per process); NULL keeps today's behavior
        assert "if (row.voiced)" in body
        assert "InitiatedFactIds()[key].insert(row.id);" in body
        # no boot-time scan: the seed rides the existing per-pair query
        assert "FROM `bot_player_facts` WHERE `voiced_at` IS NOT NULL" not in src

    def test_no_backfill_anywhere(self):
        # NULL = never voiced; backfilling would permanently silence
        # historical debt/goal initiations (the plan's stated law)
        mig = (ROOT / "sql" / "migrations" / "ai_playerbot_llm_memory_v2.sql").read_text(encoding="utf-8")
        low = mig.lower()
        assert "voiced_at" in low
        assert "update" not in low and "insert" not in low
