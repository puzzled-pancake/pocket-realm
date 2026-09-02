#ifndef _PlayerbotLlmMemory_h
#define _PlayerbotLlmMemory_h

#include <cstdint>
#include <string>
#include <vector>

#include "playerbot/PlayerbotAI.h"
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

    // replaces the legacy "manual string::llmcontext" self-recording writer
    static void RecordBotLine(Player* bot, uint32 msgtype, std::string const& message, std::string const& chanName, std::string const& name);

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
    static void AddBoundedSentimentInput(uint32 bot, uint32 player, int32 clampedDelta, std::string const& reason);

    // wall-clock-aware greeting bucket: "short"/"medium"/"long" absence
    static std::string GetAbsenceBucket(Player* bot, Player* player);

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

    // claims the bot's shared ambient-chatter slot (kill quips + idle/mood
    // lines draw from the same budget so total bot-initiated speech stays
    // rare): true once per minIntervalSeconds per bot
    static bool TryClaimAmbientSlot(uint32 botGuid, uint32 minIntervalSeconds);

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

    // 30s pre-warm cadence per bot (mutex-guarded; map threads call this)
    static bool PrewarmDue(uint32 botGuid);
};

#endif
