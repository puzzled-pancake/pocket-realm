#ifndef _PlayerbotLlmPersona_h
#define _PlayerbotLlmPersona_h

#include <string>

class Player;

/*
 * Persona fallback scaffolding for the improv-under-pressure categories that
 * testing flagged as hard for every model regardless of scale: vouching for
 * the player, refusing in-character, de-escalating a threat. Detection is a
 * cheap keyword/intent classification on the incoming message (never a model
 * call); a match retrieves an authored line for the bot's archetype instead
 * of generating freeform. Grow the libraries from logged awkward/failed
 * real-play moments rather than prompt-engineering the base model.
 */
class PlayerbotLlmPersona
{
public:
    enum HardCategory
    {
        CATEGORY_NONE,
        CATEGORY_VOUCH,       // "vouch for me", "tell them I'm with you"
        CATEGORY_REFUSE,      // requests the bot should decline in-character
        CATEGORY_DEESCALATE,  // "calm him down", "talk us out of this"
    };

    enum Archetype
    {
        ARCHETYPE_GRUFF,
        ARCHETYPE_SHY,
        ARCHETYPE_NOBLE,
        ARCHETYPE_ROGUEISH,
    };

    // keyword/intent classifier over the incoming message; plain string work
    static HardCategory Classify(std::string const& message);

    // archetype inferred from stable bot data (class + race seeds)
    static Archetype ArchetypeFor(Player* bot);

    // authored beat for the category in the bot's archetype voice; empty when
    // the category has no authored line (caller falls through to the LLM).
    // Two variants per cell rotate per trip, and whisper turns get beats
    // that answer the asker - the public beats address the room, which
    // reads wrong delivered privately
    static std::string FallbackLine(Player* bot, HardCategory category, bool whisper);

    // convenience: classify + fallback in one call
    static bool TryFallback(Player* bot, std::string const& message, bool whisper, std::string& line);

    // ---- the authored layer speaks for itself (all banter-core driven)

    // governor-busy placeholder from the POOL_BUSY recency ring (per-bot,
    // tic-seasoned); falls back to the configured LLMBusyReply conf line
    static std::string BusyReply(uint32 botGuid);

    // authored kill quip for the bot, naming the killer; rendered {P}/{B}
    // and tic-seasoned. Empty only if the pool draw failed (never expected).
    static std::string KillBanterLine(Player* bot, Player* killer);

    // plan v5 W1: authored event-reaction cells (12 lines x 4 archetypes,
    // the FallbackLine corpus law), rendered {P} and tic-seasoned.
    // REACTION_CONDOLENCE voices a bot standing over the fallen player;
    // REACTION_SHAKEN voices a revived bot meeting the player again after
    // a full wipe. Empty only on a draw failure.
    enum ReactionKind
    {
        REACTION_CONDOLENCE,
        REACTION_SHAKEN,
    };
    static std::string ReactionLine(Player* bot, ReactionKind kind, Player* forPlayer);

    // plan v5 W4: the authored grudge act-refusal (POOL_GRUDGE_REFUSE
    // recency-ring draw), rendered {P} and tic-seasoned
    static std::string GrudgeRefusalLine(Player* bot, Player* player);

    // plan v5 W8: one authored in-character nudge for the /notice scene
    // read (POOL_SCENE_NUDGE recency-ring draw), rendered {P}/{B}
    static std::string SceneNudgeLine(Player* bot, Player* player);

    // authored relationship-tier greeting for a returning player; the
    // relationship state decides the warmth, the corpus phrases it
    static std::string GreetingLine(Player* bot, Player* player);

    // rare ambient idle/mood line while adventuring with the master: runs
    // its own cadence/roll/cooldown/combat gating internally and DELIVERS
    // the line (party channel when grouped, say otherwise). True when a
    // line was spoken.
    static bool MaybeAmbientLine(Player* bot);

    // pure classifier: is this message a bare conversational greeting
    // ("hi", "well met", "hello <botname>", ...)? Used to route long-absence
    // hellos to the authored tier greeting instead of a generation.
    static bool IsSimpleGreeting(std::string const& message);
};

#endif
