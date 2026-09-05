#ifndef _PlayerbotLlmBridge_h
#define _PlayerbotLlmBridge_h

/*
 * The bridge: deterministic beat selection + [BRIDGE AI] note
 * construction, called from ChatReplyDo before generation.
 *
 * The BRIDGE OWNS DECISIONS: the model owns prose only. Every tool
 * emission on the shipped path is licensed by a note this bridge built
 * from game state - no note means no tools (the trained contract's
 * restraint distribution). The license is not just elicit-shaped, it is
 * ENFORCED: every note's tool set is recorded (RecordLicense) and the
 * executor drops queued calls their note never licensed (zero
 * executions from unlicensed turns).
 *
 * Note construction law: skeletons arrive ready-made in the note's
 * lines, fill hints ride BELOW the block, ONE note per turn (ONE-NOTE
 * law - two notes collapse tool fire to 35%).
 * The rendering itself is PlayerbotLlmPrompt.h's ComposeUserTurn (the
 * banklib compose twin, byte-gated) - this class never formats the note
 * itself.
 */
#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "PlayerbotLlmTruthCore.h"

class Player;

class PlayerbotLlmBridge
{
public:
    // Turn state, captured PRE-STOMP by the caller and threaded to
    // every note build: the absence
    // bucket + trained tier read before the relationship write queues,
    // the drain flag + event kind for event turns. firstMeeting derives
    // from the bucket ("a first meeting") exactly as before.
    struct TurnState
    {
        std::string absence;      // pre-stomp bucket ("a first meeting", ...)
        int tier;                 // pre-stomp trained tier 1-5
        bool eventTurn;           // the drain flag, never derived from text
        uint32 eventKind;         // EventReaction kind (0 = conversational)
        bool firstMeeting() const { return absence == "a first meeting"; }
        TurnState() : tier(1), eventTurn(false), eventKind(0) {}
    };

    // Event kinds the reaction queue carries (the drain threads them into
    // the note so event turns license what the event actually was).
    enum EventKind
    {
        EVENT_NONE = 0,
        EVENT_LEVEL_UP = 1,
        EVENT_RARE_LOOT = 2,
        EVENT_DUEL_LOST = 3,        // the bot lost the duel, fair
        EVENT_DUEL_WON = 4,         // the bot won
        EVENT_DUEL_PLAYER_FLED = 5, // the player fled the duel
        EVENT_DUEL_BOT_FLED = 6,    // the bot fled
        // plan v5 F1: a paid debt retires the fact row and fires the
        // shipped-but-unwired TierBeat kind-1 (debt-forgiven) cargo as
        // the event turn's licensed extra
        EVENT_DEBT_SETTLED = 7,
    };

    struct Note
    {
        std::vector<std::string> lines;   // ready-made <<tool ...>> skeletons
        std::string fills;                // fill hints BELOW the block
        std::string extra;                // directive prose (tone/speak-first/known-entity guard)
        // lore loop: the [RESULT] card text a question-shaped turn
        // retrieved (renders in the turn's head, never as a note; an
        // empty string means no card). Cards and notes compose - the
        // card grounds the answer, the note still licenses tools.
        std::string result;
        // This note MANDATES content (recall cargo, ceremony,
        // secret) - the reply is SUPPOSED to carry these words, so the
        // dedupe reroll must exempt the generation (a debt beat
        // wants "you still owe me five silver" to resemble its last
        // mention).
        bool mandatesContent = false;
        // This note carried the frozen long-form cue (a licensed
        // telling) - the reply budget widens ONLY for such turns, so a
        // plain conversational turn on a licensed tier keeps the short
        // budget (the flag rides the license stamp like mandatesContent)
        bool longFormCued = false;
        // the license stamp RecordLicense issued for THIS note; threaded
        // out by the prompt builders so the generation's emissions are
        // vetted against exactly this note (0 when no license recorded)
        uint64_t stamp = 0;
        bool empty() const { return lines.empty() && extra.empty() && result.empty(); }
    };

    // The per-generation tool license: the stamp identifies one note
    // (globally monotonic, so a stale queue entry can never collide with
    // a newer note's set) and tools/lineByTool record exactly what that
    // note carried. The generation's OWN stamp (threaded out of the note
    // build into ExtractAndQueue - never the bot's live stamp at
    // completion time) tags its queued calls; the executor admits a call
    // only when that stamp still matches the live license and its tool
    // was licensed - a superseding note invalidates older queued calls
    // (fail-closed: the generation is re-elicitable, a wrongly-executed
    // ACT tool is not). lineByTool carries the LICENSED line itself:
    // every field the bridge decided (names, items, direction, emote,
    // choice, category) executes from this line, never from the model's copy -
    // only fill-hint fields (text/reason) are the model's to write.
    // mandatesContent mirrors the note's flag so the dedupe reroll
    // can exempt exactly the generation whose own note mandated content.
    struct ToolLicense
    {
        uint64_t stamp = 0;
        std::set<std::string> tools;
        std::map<std::string, std::string> lineByTool;
        bool mandatesContent = false;
        bool longFormCued = false;
    };

    static uint64_t RecordLicense(uint32 botGuid, Note const& note);
    static ToolLicense CurrentLicense(uint32 botGuid);
    // The licensed note line for a covered (botGuid, stamp, tool); empty
    // when not covered (wrong stamp, unlicensed tool, no license). The
    // executor both GATES and fetches on this one read.
    static std::string LicensedLineFor(uint32 botGuid, uint64_t stamp, std::string const& tool);
    // True when the generation's OWN note (identified by stamp)
    // mandated content - the dedupe reroll exemption. Stamp-checked
    // like LicensedLineFor: a superseding note never leaks the exemption
    // to an older in-flight generation.
    static bool NoteMandatesContent(uint32 botGuid, uint64_t stamp);
    // True when the generation's OWN note (identified by stamp)
    // carried the long-form cue - the reply budget's per-turn earning.
    // Stamp-checked like NoteMandatesContent.
    static bool NoteLongFormCued(uint32 botGuid, uint64_t stamp);

    // Normalizes one conversational turn: strips the event-drain's "say
    // something" nudge so neither the beat engine nor the prompt builder
    // replays a stale imperative. (The former "(event) " string prefix is
    // GONE as a signal - event turns are identified by the drain flag
    // threaded from the event site, because a chat prefix was player-
    // forgeable.)
    static std::string NormalizeTurn(std::string const& botName, std::string const& msg);

    // The trigger engine: inspects the (normalized) turn plus the
    // game/ledger state and returns AT MOST ONE note (ONE-BEAT law:
    // content-mandating recall/ceremony cargo rides the ONE note's
    // directive leg; the rare one-time ceremonies co-fire only with a
    // non-ACT beat). Priority: an actionable ACT request (duel >
    // give_item > follow > party_invite) over insult > gratitude >
    // recall beats (debt question / memory question / news) >
    // greeting-gap weave > first-meeting > gossip-after-verified-event >
    // emote beat; event turns take the event-kind note (housekeeping
    // log_fact + the kind's licensed extra: a cheer on level-up, a
    // sentiment move on duels). An empty note licenses nothing.
    // The TurnState carries the PRE-STOMP absence+tier reads (a fresh
    // read races the async relationship write), the drain
    // flag and the event kind - never derived from text.
    static Note BuildNote(Player* bot, Player* player, std::string const& normalizedMsg,
        TurnState const& state);

    // banklib.SPEAK_FIRST verbatim: the event-turn directive.
    static char const* SpeakFirst();

    // ---- lore loop state (loaded once from
    // AiPlayerbot.LLMLoreFile; null when the key is empty or the file
    // fails to load - the loop goes quiet, the entity guard still works).
    // ResolvePoiPlace maps a player's place phrase to the CANONICAL POI
    // card title (false when unresolvable - the bridge then refuses to
    // license a move_to for it; the executor re-resolves at execution
    // time the same way). IsKnownName is the known-entity test
    // shared with the invention post-filter: templates (creatures,
    // quests, items, areas), lore card keys, gameobject names and
    // online players/bots all count as known.
    static pocketllm::LoreIndex const* Lore();
    static bool ResolvePoiPlace(std::string const& place, std::string* canonicalOut);
    static bool IsKnownName(std::string const& name);

    // Renders a full turn for the LEGACY prompt paths (llama raw +
    // hand-configured template servers): the compose-shaped block
    // ([EVENT] head when the turn is an event, then the note) that the
    // trained builder assembles natively. Empty string when the turn
    // licenses nothing.
    static std::string RenderLegacyTurn(Player* bot, Player* player,
        std::string const& msg, TurnState const& state,
        uint64_t* licenseStamp = nullptr);
};

#endif
