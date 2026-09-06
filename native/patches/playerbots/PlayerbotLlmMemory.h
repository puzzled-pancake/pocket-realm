#ifndef _PlayerbotLlmMemory_h
#define _PlayerbotLlmMemory_h

#include "PlayerbotLlmGates.h"
#include <cstdint>
#include <string>
#include <vector>

#include "playerbot/PlayerbotAI.h"
#include "playerbot/PlayerbotAIConfig.h"
#include "playerbot/PlayerbotLlmRecallCore.h"

class Player;
class Unit;

/*
 * Persistent memory for the LLM companion feature: backstory, append-only
 * per-player facts, deterministic relationship tiers, the shared gossip pool
 * and the byte-stable prompt-segment builder that anchors warm-slot prefix
 * caching.
 *
 * Segment order is fixed (backstory -> bot trait seasoning -> relationship
 * tier/traits -> absence bucket -> injected facts -> gossip slice -> rolling
 * turns) and existing segments are never reordered or rewritten
 * mid-conversation: only the rolling-turn tail is ever trimmed. Any change
 * to this order must bump POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION.
 */
class PlayerbotLlmMemory
{
public:
    // characters.bot_backstory: written once (deterministic seed from bot
    // data), injected byte-identical every turn
    static std::string GetOrCreateBackstory(Player* bot);

    // player-carried prompt furniture dies here too: the fixpoint
    // control-token scrub shared by the history writer, the trained
    // turn composer and the legacy placeholder fill (a forged
    // "[RESULT]"/"[BRIDGE AI]" line must never render as
    // bridge-authored text on ANY path)
    static std::string ScrubControlTokens(std::string const& text);

    // rolling short-term conversational continuity (in-memory, tail-capped);
    // whisper history is per (bot, player), party/raid history is shared per
    // (bot, channel) including the bot's own lines
    static void AppendTurn(uint32 bot, uint32 playerOrChannel, bool sharedChannel, std::string const& speaker, std::string const& line);

    // ordered byte-stable context for the in-process backend
    static std::string BuildPromptContext(Player* bot, Player* player, int chatChannelSource, std::string const& chanName);

    // ---- trained prompt format (HTTP path, AiPlayerbot.LLMPromptFormat = 1):
    // assembles the COMPLETE chat request the trained contract defines -
    // system message from live bot/player data + DB memory (banklib
    // sysm_for_card shape), the trained user turn ([Memories]/[State]
    // tail), and the prior turns as role-separated history. playerLine
    // arrives marker-neutered (the injection choke points upstream);
    // playerOrChannel is the same history key ChatReplyDo computes. The
    // just-recorded current turn is excluded from history (it IS the
    // request's user message). preStompAbsence/preStompTier are the
    // PRE-STOMP reads ChatReplyDo captured (REQUIRED: a live read here
    // races the async relationship write; an empty bucket is a caller
    // bug and degrades to the first-meeting reading). isEventTurn is
    // the drain flag threaded from the event site (never derived from
    // text - the former "(event) " prefix was player-forgeable);
    // eventKind selects the event note's licensed extras (cheer on
    // level-up, sentiment on duel outcomes).
    // Returns the ready-to-POST JSON body.
    static std::string BuildTrainedChatRequest(Player* bot, Player* player,
        std::string const& playerLine, uint32 playerOrChannel,
        std::string const& preStompAbsence, int preStompTier,
        bool isEventTurn, uint32 eventKind,
        uint64_t* licenseStamp = nullptr);

    // ---- relationship (deterministic tiers; the model phrases, never decides)
    static std::string GetRelationshipTier(Player* bot, Player* player);
    // the trained 1-5 scale: the DB's four storage tiers plus Bonded (5),
    // derived from points >= 120 on read (the stored enum/CHECK stays
    // four-valued - no schema migration, no constraint-failing writes)
    static int GetTrainedTier(Player* bot, Player* player);
    static void AddRelationshipPoints(Player* bot, Player* player, int32 points);
    // bounded input for conversational tone (tool adjust_sentiment)
    static bool AddBoundedSentimentInput(uint32 bot, uint32 player, int32 clampedDelta, std::string const& reason);

    // wall-clock-aware greeting bucket: "short"/"medium"/"long" absence
    static std::string GetAbsenceBucket(Player* bot, Player* player);

    // ---- Phase-3 mood weather (world thread, in-memory): the bot's
    // current mood index (0-7, see MOOD_* in llm_banter_core.h), a nudge
    // counter for grudge/smitten/grief events, and the seasoning line
    // for the system prompt. MoodNow derives from (botGuid, hourly
    // bucket, nudges) — GUID-stable, no DB. NudgeMood records an event
    // nudge (grudge/smitten/grief direction); MoodLineFor renders the
    // seasoning line, empty when mood seasoning is off.
    static int MoodNow(uint32 botGuid);
    static void NudgeMood(uint32 botGuid, int mood);
    static std::string MoodLineFor(uint32 botGuid);

    // ---- the pre-stomp pairing read (absence, now also
    // carrying the tier the ceremony observes). ONE query for
    // last_interaction_at + tier + points, taken before the
    // relationship stomp queues; tier derives exactly like
    // GetTrainedTier (points >= 120 -> Bonded).
    struct PreStompState
    {
        std::string absence;
        int tier;
        PreStompState() : tier(1) {}
    };
    static PreStompState GetPreStompState(Player* bot, Player* player);

    // ---- facts (append-only)
    static void LogFact(uint32 bot, uint32 player, std::string const& text, std::string const& category);
    static std::vector<std::string> GetJournal(Player* bot, Player* player);

    // ---- recall surfaces (newest-20 window, classified with the
    // pure core): the newest fact whose class is in the mask (bit per
    // pocketllm::FactClass; tone rows never match - they are opinions,
    // not memories); the newest unresolved negative-tone row (no newer
    // positive tone row); a durable prefix probe (the secret-release
    // marker); and the player-subject gossip row (newest-8 fetch,
    // matched in code - no wildcard LIKE hazards).
    static std::string GetNewestRecallFact(uint32 bot, uint32 player, int classMask);
    static std::string GetUnresolvedGrudge(uint32 bot, uint32 player);
    static bool HasFactPrefix(uint32 bot, uint32 player, std::string const& prefix);
    static std::string GossipAbout(std::string const& playerName);

    // ---- shared gossip pool (world DB)
    static void ShareGossip(uint32 bot, std::string const& text, std::string const& category);
    static std::string GetGossipSlice(uint32 bot);

    // share_gossip gate: only alongside a real, verified trigger event
    static void NoteVerifiedEvent(uint32 bot, uint32 eventId);
    static bool HasRecentVerifiedEvent(uint32 bot);

    // journal surface (facts + milestones) for a gossip-window page: one
    // entry per whisper line so the delivery path can pace it like a diary
    static std::vector<std::string> GetJournalLines(Player* bot, Player* player);

    // ---- event allowlist hooks (called from core under ENABLE_PLAYERBOTS,
    // world thread only): record a verified event for every bot in the
    // player's own active party and queue a bounded conversational reaction
    static void OnPlayerLevelUp(Player* player, uint32 newLevel);
    static void OnPlayerRareLoot(Player* player, uint32 itemId);
    // Duel-outcome hook (Player::DuelComplete - the ONE site covering
    // all nine outcome call sites; Unit.cpp's damage win calls it on the
    // loser with DUEL_WON). Deliberately minimal: verified event + one
    // [EVENT] reaction for the dueled bot; the sentiment/gossip beat
    // machinery hangs off the same hook. participantType is a
    // DuelCompleteType value; only player-vs-bot duels react.
    static void OnDuelComplete(Player* participant, Player* opponent, uint32 participantType);
    // Kill-banter hook (Unit::Kill credit block, world thread): rarely
    // queues an AUTHORED party quip (no generation behind it) for one bot in
    // the tapper's group; heavily rate-limited internally
    static void OnPlayerGroupKill(Player* tapper, Unit* victim);
    // quest turn-in reuses the existing "rpg start/end quest" trigger values;
    // level-up and rare-loot are these two new interception points

    // plan v5 W1/F1: the player's own death (Unit::SetDeathState JUST_DIED,
    // world thread). Wipe classification + reactors live inside: grouped
    // bots (or, for the partyless player, KNOWN bots within say range) get
    // an authored condolence over the body (the one guaranteed beat outside
    // every pacing budget), a grief mood nudge and a mint-once fact; a full
    // wipe instead mints a town gossip row and arms a shaken one-liner per
    // bot that TickInitiative voices when the party reforms (dead bots
    // cannot speak)
    static void OnPlayerDied(Player* victim);

    // plan v5 F1: the ONE player<->bot trade completion hook
    // (HandleAcceptTradeOpcode, pre-moveItems so both TradeData still
    // carry the offers). A real->bot trade is a bounded kindness (+1 with
    // its tone row, which also resolves a standing grudge); money to the
    // bot retires the newest unresolved debt row, mints the settlement
    // fact and queues the EVENT_DEBT_SETTLED reaction that finally fires
    // the shipped-but-unwired TierBeat kind-1 beat
    static void OnTradeCompleted(Player* accepter, Player* initiator);

    // plan v5 W3: first-visit facts. The core's explore-bit setter (the
    // game's own verification of "first time here") calls this with the
    // ZONE-level area id; grouped and known-nearby bots each mint their own
    // "traveled with {player} to {zone} for the first time" memory,
    // prefix-checked against the DB so restarts never mint a duplicate
    // first time
    static void OnPlayerExploredArea(Player* player, uint32 zoneOrAreaId);

    // plan v5 W5: the bot-curiosity answer capture. TickInitiative arms a
    // pending answer when it asks; the bridge consumes it on the player's
    // next conversational turn, minting the fact deterministically (the
    // 0.8B law - the model's licensed log_fact may or may not fire, the
    // answer must persist either way). Returns true when a fact minted
    static bool ConsumePendingAnswer(uint32 bot, uint32 player, std::string const& reply);

    // plan v5 F4a: the ONE external-API-tier test (was duplicated inline
    // at the history writer and reader - every cloud surface must gate on
    // this identical condition)
    static bool ExternalApiTierActive();

    // plan v5 C1.3: the cloud quota meter - one per-day counter per
    // surface, in-process day bucket. Returns false when the surface's
    // daily cap is spent (and counts the admission when it returns true)
    static bool CloudQuotaAdmits(char const* surface, uint32 perDay);

    // A7 tier I: the interactive budget - per real player per hour,
    // exempt from the ambient arbiter (the addressed interlocutor is
    // always admitted; the budget bounds the sustained rate). Device
    // lane: unbounded here (the governor is the only limiter).
    static bool InteractiveBudgetAdmits(uint32 playerGuid);

    // A7.3 bot2bot containment (round-1 R1#2 wiring): the tier-II daily
    // quota plus the autonomous-exchange depth cap (at most 3
    // consecutive autonomous lines per bot, reset by a real player's
    // conversational trigger reaching that bot). Device lane: admits
    // unconditionally (the mirror-case byte-identity law).
    static bool BotToBotAdmits(uint32 botGuid);
    static void NoteBotPlayerInteraction(uint32 botGuid);

    // ---- A3: the exactly-one party responder (cloud lane only). N bots
    // hear one unaddressed party line; the deterministic pick
    // (PlayerbotLlmGates::SelectResponder over CollectPartyCandidates)
    // names the responder and THIS first-writer-wins claim enforces it
    // when candidate lists diverge across the fan-out. Recording
    // (NotePartyLine/ConsumePendingAnswer) happens for ALL bots; only
    // the generation is exactly-one.

    // Stable FNV-1a over the line text - every bot hashing the same
    // party line must land on the identical claim key, so the hash has
    // exactly one implementation (here), never at the call sites.
    static uint64_t PartyMsgHash(std::string const& msg);

    // First-writer-wins per (speaker, msgHash) under StateMutex: true
    // when THIS bot holds the claim (a fresh claim stamps the rotation
    // map). Expired entries are pruned on every call; the claim window
    // (PARTY_CLAIM_WINDOW_SECONDS, round-6 R1: 30 s) exceeds every
    // reachable chat-drain stagger - the fan-out does NOT resolve
    // within one tick (UpdateAIInternal delays run 3-7 s on
    // teleport/cast chains). Round-8 R1: the key is GROUP-FREE (a
    // speaker stands in at most one group; the drain can only key by
    // the CURRENT group, and a group-switching listener used that to
    // claim under a fresh key beside the original winner). Round-10
    // R1: a grant additionally requires the LINE fresh at the claim's
    // own clock - within window-margin of the first-heard registry
    // (NotePartyLineHeard) - so a claim can never post-date every
    // prior token's expiry, whatever the fan-out straddle or the
    // drain's mid-work clock divergence.
    static bool TryClaimPartyResponder(uint32 botGuid, uint32 speakerGuid,
        uint64_t msgHash);

    // Round-10 R1 (the first-heard registry): every group member's
    // receive handler min-stamps the LINE's earliest receive instant
    // (same group-free key as the claim map, same StateMutex, lazy
    // prune at the window). This is the freshness authority the claim
    // grant consults: every marker/claim token is stamped at >= its
    // writer's receive and so expires at >= firstHeard+window, while
    // a grant needs now <= firstHeard+window-margin - a granted claim
    // can never meet an expired prior token, at ANY drain clock
    // divergence. Round-11 R1: the stand-down marker carries the SAME
    // gate (an ungated drain-side stamper could stamp below
    // firstHeard and expire inside the grant range), so every
    // ACCEPTED token stamp >= firstHeard - both legs read one law.
    // Round-12 R1 (generation scoping): the key is line-INSTANCE-
    // blind (speaker+hash), so a verbatim repeat past the window
    // re-registers fresh while the prior line's token may still be
    // live - the token writers' ownership check is therefore scoped
    // to the CURRENT registry generation (TokenOwnsCurrentLine): a
    // token expiring strictly before firstHeard+window was stamped
    // before this generation began and is erased (erase-and-replace),
    // so a repeat line owns its own exactly-one; a current-generation
    // token owns the line to at least firstHeard+window.
    // Premises, stated honestly: (1) the law assumes non-decreasing
    // wall-clock reads (round-11 R1 MINOR: a >=2 s backward clock
    // STEP landing between one receive handler's registry write and
    // its marker stamp could re-open a microscopic shape -
    // environmental, shared by every wall-clock window in the
    // engine); (2) a fan-out straddle beyond the window re-registers
    // the line as fresh (round-11 R8 MINOR - a >30 s world-thread
    // stall inside one broadcast, far outside every adjudicated
    // tier); inside the window, with generation scoping, the envelope
    // is total. Absent at stamp/grant time = unprovable freshness =
    // refuse (a missed reply, never a second generation).
    static void NotePartyLineHeard(uint32 speakerGuid, uint64_t msgHash);

    // Round-6 R1 (addressed-line sibling): an ADDRESSED line stands
    // down with a MARKER in the same claim map (winner 0 = the
    // addressee's own arm owns the line; same window, same lazy prune)
    // so a staggered late drain cannot re-open the line after the
    // addressee leaves the group mid-fan-out. First writer wins; false
    // when a claim or marker already owns the line. Round-8 R1: the
    // group-free key keeps the marker findable across group switches.
    static bool TryStandDownPartyLine(uint32 speakerGuid, uint64_t msgHash);

    // Round-7 R1 (claim-window class closure): the drain-side staleness
    // oracle for a queued party/raid line. On the armed surface the
    // queue path is noDelay (the queued m_time IS the line's fan-out
    // instant) and entries are unprocessable before m_time, so a line
    // aged >= PARTY_CLAIM_WINDOW_SECONDS minus the round-9 R1 fan-out
    // straddle margin (PARTY_CLAIM_FANOUT_STRADDLE_SECONDS: a later
    // member's queue entry can carry m_time past the fan-out's
    // earliest push, and the drop fires early so entries that could
    // outlive the line's tokens go first) is dropped: processing it
    // could sit beside a claim/marker expiring that very second. The
    // margin bounds the straddle it covers - wider fan-out spans are
    // possible (round-10 R1 MINOR: the push blocks on a mid-drain
    // member's chatRepliesMutex), and at those widths the drop misses
    // a drainer as a MISSED REPLY at worst: the claim grant itself is
    // anchored to the first-heard registry (NotePartyLineHeard), so a
    // second generation stays unreachable at any straddle. True =
    // drop the line: a missed reply, never a second generation. Pure
    // time compare (no state).
    static bool PartyClaimWindowElapsed(time_t lineTime);

    // The deterministic pick's candidate set: every BOT in the group
    // with its relationship tier toward the SPEAKER (the existing
    // GetTrainedTier lookup - reused, never duplicated) and its lastWon
    // rotation stamp (smaller = spoke longer ago; stamped when a claim
    // succeeds). True when at least one candidate was collected.
    static bool CollectPartyCandidates(uint32 groupId, uint32 speakerGuid,
        std::vector<PlayerbotLlmGates::ResponderCandidate>& out);

    // The per-speaker flood gate (UNADDRESSED party lane only): N lines
    // within 2 s coalesce into one generation - max one admitted
    // generation per speaker per 2 s sliding window (check-and-stamp
    // under StateMutex).
    static bool PartyFloodAdmits(uint32 speakerGuid);

    // Round-7 R1 MINOR: a REFUSED claim must not consume the speaker's
    // 2 s flood slot - the coalescing law counts GENERATIONS ("N lines
    // within 2 s = one generation") and a claim loss produced none.
    // CAS-shaped: the erase lands only when the slot still carries THIS
    // attempt's stamp, so a concurrent winner's stamp (set between this
    // attempt's admit and its claim loss) always survives.
    static void PartyFloodRefund(uint32 speakerGuid, time_t stampedAt);

    // A1: the once-per-bot-per-session stamp behind the SayAction
    // payload's reply-gate refusal log (the dead-gate signature). True
    // the FIRST time a bot is refused this session, false after - the
    // set is process-local and dies with the world process (the
    // quota-restart semantics).
    static bool NoteGateRefusalOnce(uint32 botGuid);

    // ---- A2: the conversation fast-lane window. Arming is world-thread
    // at the ChatReplyDo dispatch site (and the A4 fallback delivery
    // leg); occupancy is guid-keyed per map under StateMutex with a TTL
    // (logout mid-dialogue leaks at most one ghost for <= the TTL - no
    // decrement path exists). The interlocutor admits unconditionally
    // (soft cap); non-interlocutor admits require map occupancy < 16
    // after pruning (PlayerbotLlmGates::EvictDialogueVictim). The
    // LLMDialogueFastLane key gates the ARMING sites; DialogueActive is
    // the priority query (cheap: guarded map scan behind the 5 s
    // AllowActivity cache).
    static void ArmDialogue(uint32 botGuid, uint32 mapId, bool interlocutor);
    static bool DialogueActive(uint32 botGuid);

    // ---- A4: the authored failure-fallback (cloud lane, conversational
    // turns only). DrawFailureFallback resolves the plan's ids into a
    // line at FAILURE time on the worker thread (pointers re-resolve by
    // guid - the AddBoundedSentimentInput precedent; a vanished bot or
    // player means nobody is left to speak to: silence). Delivery queues
    // on the world-thread EventReaction drain - the async region never
    // touches a Session*, and the guid award rides the same closure so
    // {deliver, bot-line record, award} lands exactly once per turn
    // outcome. AddRelationshipPointsByGuid re-resolves both sides the
    // same way.
    static std::string DrawFailureFallback(
        PlayerbotLlmGates::FallbackPlan const& plan,
        uint32 botGuid, uint32 playerGuid);
    static void QueueConversationalFallback(uint32 botGuid, uint32 playerGuid,
        uint32 msgtype, std::string const& text, uint32 mapId);
    static void AddRelationshipPointsByGuid(uint32 botGuid, uint32 playerGuid,
        int32 points);

    // ---- A6: the street admission ladder (cloud lane). The crowd
    // branch's entry: world/zone window claim -> per-bot slot ->
    // LLMCloudStreetSayPct roll -> CloudQuotaAdmits("street") ->
    // dispatch, IN THAT ORDER (the pin asserts it); any rejection
    // returns false so the caller falls back to the crowd emote, and a
    // dispatched street say drops the emote for that event (not
    // defers). Quota-first admission: the street lane is EXEMPT from the
    // authored arbiter (its cap is the daily quota + the per-bot
    // interval). The generation runs on a detached worker (the composer
    // precedent); the delivered line - generated, or E0's kStreetShort
    // pool as the failure fallback - rides an authored SAY
    // EventReaction (2-5 s stagger), never the chatter queue, and never
    // arms A2.
    static bool QueueStreetReaction(Player* bot, Player* speaker,
        std::string const& heard);

    // ---- C7: greet-repeat persistence (the 0413 relationship columns).
    // LastGreetLine reads the pairing's persisted last greeting (empty
    // when never voiced or the kill-switch LLMGreetMemory = 0);
    // NoteGreetingVoiced stamps both columns at the delivery composer.
    // GreetingLine redraws once past the persisted line so a restart
    // never replays the same greeting verbatim (the boot nonce covers
    // every OTHER pool's cross-restart replay).
    static std::string LastGreetLine(Player* bot, Player* player);
    static void NoteGreetingVoiced(Player* bot, Player* player,
        std::string const& line);

    // ---- C5: tier-ceremony persistence (the 0413 last_voiced_tier
    // column). The bridge's in-process LastVoicedTier seeds lazily from
    // the persisted value, honored only while tier_since is recent (<=
    // 48 h: the bridge's never-fire-on-stale-state doctrine, amended
    // deliberately); NoteTierVoiced writes the crossing back.
    static bool PersistedLastVoicedTier(uint32 bot, uint32 player,
        int& tierOut);
    static void NoteTierVoiced(uint32 bot, uint32 player, int tier);

    // ---- C6: relationship economics. AwardChatTurn is the capped
    // per-pairing daily TURN award (the +1 conversational sites route
    // here; the per-pairing daily cap LLMTurnAwardDailyCap bounds the
    // farm surface, 0 = uncapped); deed values apply at their hooks
    // (LLMDeedPoints*, 0 disables the AWARD, never the fact/reaction at
    // the same hook). The quest deed rides the new CORE_REWARDQUEST
    // anchor (Player::RewardQuest) and is gated !IsRepeatable.
    static bool AwardChatTurn(Player* bot, Player* player);
    static bool AwardChatTurnByGuid(uint32 botGuid, uint32 playerGuid);
    // the shared per-pairing daily cap consumed by turns AND
    // shared-kills (C6: trade/quest/first-visit stay exempt)
    static bool AwardCappedPoints(Player* bot, Player* player, int32 points);
    static void OnQuestRewarded(Player* player, uint32 questId,
        std::string const& questTitle, bool repeatable);

    // ---- C4: deterministic party digests. A bounded per-master rolling
    // window of party lines (10-12 entries, <= 160 B each) written at
    // the A3-restructured party block; every window close, ONE writer
    // bot (the tier >= 3 storyteller pick over the group candidates)
    // mints a register-native digest row (5-24 words, category
    // shared-event, MintOnceFact keyed (bot, windowIndex), voiced_at
    // unset) under the LLMPartyDigestPerDay quota.
    static void NotePartyDigestLine(uint32 masterGuid, std::string const& line);
    static void MaybeMintPartyDigest(uint32 masterGuid, uint32 groupId);

    // plan v5 W8: the /notice scene read - the player's own half of the
    // immersion. Renders the live scene (place, stealth/combat, wounded
    // party members, hour/weather, the current rumor) as second-person
    // lines plus one authored in-character nudge. Zero generations
    static std::vector<std::string> SceneReadLines(Player* bot, Player* player);

    // plan v5 S.3: the "story" codex read - the saga rows the town holds
    // about the player (re-readable; the delivered saga is the
    // notification, this is the destination). Zero generations
    static std::vector<std::string> StoryLines(Player* bot, Player* player);

    // plan v5 C2: the session recap. The deterministic digest renders
    // what actually accumulated while the player was away (new ledger
    // rows since their last active moment, town talk naming them, tenure
    // milestones); empty when fewer than three lines warrant it (the
    // silence doctrine - a recap below three rows is noise)
    static std::vector<std::string> RenderRecapDigest(Player* player);

    // the recap delivery: deterministic digest lines always; the cloud
    // prose variant replaces them when the external tier + toggle +
    // daily quota admit (one capped call, fail-closed to silence)
    static void DeliverSessionRecap(Player* player);

    // plan v5 C5: the weekly dossier - one row about the player the
    // whole town holds (world_gossip category 'dossier'; rides every
    // bot's prompt through the gossip slice). Deterministic locally
    // (the newest remembered truth); cloud wording on the external tier,
    // once per 7 days, fail-closed
    static void MintWeeklyDossier(Player* player);

    // plan v5 F2: the bot<->bot dyad ledger (in-process - the accepted
    // statics class, process-lifetime like moods and chatter fatigue; a
    // DB table would buy cross-restart persistence the engagement value
    // does not need). Affinity -3..+5 from shared party history; the
    // newest dyad event feeds the party topic (W6) and biases the drama
    // beat kind (C4)
    static void NoteDyadEvent(uint32 botA, uint32 botB, int points,
        std::string const& eventText);
    static int DyadAffinity(uint32 botA, uint32 botB);
    // the newest unvoiced dyad event, claimed once (true when claimed)
    static bool ClaimNewestDyadEvent(uint32 botA, uint32 botB, std::string& eventOut);
    // non-consuming peek at the newest unvoiced dyad event (the caller
    // vets fatigue BEFORE claiming - a claimed-but-vetoed event is lost)
    static bool PeekNewestDyadEvent(uint32 botA, uint32 botB, std::string& eventOut);

    // plan v5 F7: the authored-line hourly ceiling. One global deque plus
    // one deque per category; exempt beats (the first post-death/wipe
    // line) neither check nor consume. 0 on the conf key disables authored
    // ambient entirely (exempt beats still land)
    // non-consuming budget check (the dispatch gates use it so a spent
    // ceiling skips the work entirely; the stamp still lands only via
    // AuthoredLineAdmits on a confirmed delivery)
    static bool AuthoredBudgetHasRoom(uint32 category);

    enum ArbCategory
    {
        ARB_AMBIENT = 0,   // idle/mood/crowd ambience + murmur (cap 3/hr)
        ARB_REACTION,      // kill quips, initiative asks, aftermath (cap 2/hr)
        ARB_SCENE,         // party banter + global set pieces (global budget
                           // only - the lanes' own cadences govern them)
        ARB_COUNT
    };
    static bool AuthoredLineAdmits(uint32 category, bool exempt);

    // claims the bot's shared ambient-chatter slot (kill quips + idle/mood
    // lines draw from the same budget so total bot-initiated speech stays
    // rare): true once per minIntervalSeconds per bot AND while the F7
    // authored-line hourly budget (global + category) still admits - a
    // budget rejection does NOT burn the per-bot interval (the kill-banter
    // law: a dropped line must not open the window in silence)
    static bool TryClaimAmbientSlot(uint32 botGuid, uint32 minIntervalSeconds,
        uint32 arbCategory = ARB_AMBIENT);

    // ---- initiative scheduler (world thread, UpdateAI-cadence):
    // the authored speak-first layer - arrival greet-first packets for
    // remembered players, debt
    // reminders and tier-gated goal ask-afters ("new facts want out
    // once; stale facts stay quiet" - each fact initiates at most once),
    // and the rare authored bot2bot exchange when a player walks up on
    // two bots. All classes share the ambient slot with a 10-minute
    // floor (the zero-spam gate cap); everything is deterministic and
    // costs no generation.
    static void TickInitiative(Player* bot);

    // The authored arrival packet - tier greeting + absence
    // magnitude + what the town says about the player. Empty when
    // nothing applies.
    static std::string AuthoredArrivalGreeting(Player* bot, Player* player,
        std::string const& absenceBucket);

    // The deterministic crowd tier on a non-trigger ambient /say
    // from a real player - at most a bounded couple of staggered text
    // emotes per message window, world-thread queued with the 2-5s
    // persona-paced delay. Returns true when a crowd reaction queued.
    static bool QueueCrowdEmote(Player* bot, Player* speaker);

    // ---- the player surface (pacing acknowledgment, first contact,
    // visible progression). All world-thread; all cheap.

    // Instant whisper receipt acknowledgment BEFORE the generation is
    // queued - face the speaker and one deterministic text emote (its
    // own delivery path, zero LLM cost). Rate-capped per pairing so rapid
    // whisper exchanges do not emote-spam.
    static void AcknowledgeWhisper(Player* bot, Player* player);

    // Player-facing conversations counter (diagnostics) - one count
    // per genuine player-facing generation, exactly the recorder gate's
    // own definition (never busy placeholders, never autonomous chatter).
    static void NoteConversation();
    static uint64_t ConversationCount();

    // The one-time in-game onboarding line at login (gating inside:
    // LLM enabled, real player, no bot pairing yet - a pure DB read, so
    // it fires once per character, before the first bot contact). The
    // anchor site also runs on cross-map teleports; the once-per-process
    // dedupe makes the line a true one-time voice.
    static void OnPlayerLogin(Player* player);

    // The "standing" one-liner on the FIRST whisper of a session (one
    // voice per pairing per world process; system-colored, zero
    // generation). preStompAbsence is the pre-stomp read the caller
    // captured - a first meeting skips (the welcome owns that moment).
    static void MaybeSessionStandingLine(Player* bot, Player* player,
        std::string const& preStompAbsence);

    // Helper: does this player have ANY bot pairing row yet? (the
    // once-per-character gate for the onboarding line and the scripted
    // first-contact welcome)
    static bool PlayerHasAnyPairing(uint32 playerGuid);

    // Phase-4: pairing tenure in days, from the OLDEST fact row's
    // created_at (both SQL dialects read unix seconds). Returns -1 when
    // the pairing has no facts yet (caller renders nothing).
    static int PairingAgeDays(uint32 bot, uint32 player);

    // The player's FIRST-EVER bot contact gets the scripted welcome
    // (authored, and hinting that bots remember - which is true: the
    // pairing's first-meeting fact forms right here through the same
    // native write the licensed log_fact line persists through; a
    // scripted voice cannot emit tool markers). Empty when this is not
    // the player's first-ever pairing, so the normal first-meeting beat
    // runs instead.
    static std::string AuthoredFirstContactWelcome(Player* bot, Player* player);

    // The whisper keyword surfaces - "standing" renders the tier
    // one-liner, "gossip" the town-talk rows the greeting surfaces
    // carry. Pure DB reads, zero generation.
    static std::string StandingLine(Player* bot, Player* player);
    static std::vector<std::string> GossipLines(Player* bot, Player* player);

    struct EventReaction
    {
        uint32 playerGuid;
        std::string text;
        // true when text is final authored banter to deliver as-is (no
        // ChatReplyDo generation behind it)
        bool authored;
        // delivery channel for the generated reaction: 0 = party (the
        // level-up/loot class speaks to the group); a duel outcome is a
        // private exchange and whispers the duel partner instead
        // (stranger duels have no shared group to talk to). The ChatMsg
        // value itself is set at the queue site, keeping this header
        // clear of core enum includes.
        uint32 msgtype;
        // The event kind (PlayerbotLlmBridge::EventKind) so the
        // event note licenses what actually happened (cheer on level-up,
        // sentiment on duel outcomes); 0 keeps the plain nudge
        uint32 eventKind;
        // Pacing: the reaction is not delivered before this wall
        // time (staggered crowd/b2b delivery; 0 = immediate)
        time_t notBefore;
        // Authored text is an EMOTE NAME, not a spoken line -
        // delivered through the deterministic text-emote path
        bool emote;
        EventReaction()
            : playerGuid(0), authored(false), msgtype(0),
              eventKind(0), notBefore(0), emote(false) {}
    };
    // drained by PlayerbotAI::UpdateAI on the world thread; a reaction
    // whose notBefore is still ahead is re-queued (front) and skipped
    static bool DrainEventReaction(uint32 botGuid, EventReaction& reaction);

    // plan v5 C4: queue one authored reaction under the cap (the shared
    // two-slot discipline; the drama set piece and every future authored
    // beat queue through here)
    static void QueueAuthoredReaction(Player* bot, EventReaction& reaction);

    // 30s pre-warm cadence per bot (mutex-guarded; map threads call this)
    static bool PrewarmDue(uint32 botGuid);
};

// 0.13 conjunction law: every cloud-lane widening consumes THIS - never
// the bare LLMCloudChatter key. The tier half is conf-static per process
// (providerSafe + ctx >= 65536), so a device-lane emission can never
// widen even with the key on; the pure three-value form lives in
// PlayerbotLlmGates.h for the host harness.
inline bool CloudLaneOpen()
{
    return sPlayerbotAIConfig.llmCloudChatter != 0 &&
        PlayerbotLlmMemory::ExternalApiTierActive();
}

#endif
