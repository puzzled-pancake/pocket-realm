#ifndef _PlayerbotLlmGates_h
#define _PlayerbotLlmGates_h

/*
 * WS-A conversation gates (plan v2.3, T1): the PURE decision predicates
 * behind every cloud-lane widening. Scope fence: no I/O, no clock, no
 * config reads, no state - every input arrives as a value so the whole
 * surface compiles and runs in the host harness
 * (tools/test_llm_gates.cpp). The world-side call sites are REPLACED by
 * these helpers in the same anchor payloads the pins read (consume, not
 * copy); the src values mirror ChatChannelSource and SayAction.cpp's
 * anchor bridges the two with static_asserts so an upstream renumber
 * fails the build, not a runtime misclassification.
 *
 * The one live wrapper - CloudLaneOpen() - reads the config and is
 * compiled only inside the server (ENABLE_PLAYERBOTS); the harness
 * exercises the pure three-value form. The conjunction law (0.13):
 * no cloud-lane behavior may key on LLMCloudChatter alone - the tier
 * must be active too, so a device-lane emission can never widen.
 */
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <string>
#include <vector>

namespace PlayerbotLlmGates
{
    // ChatChannelSource values (bridged by static_asserts at the call
    // site - never edited here when upstream renumbers)
    enum GateSrc
    {
        GATE_SRC_PARTY = 13,
        GATE_SRC_RAID = 14,
        GATE_SRC_UNDEFINED = 15,
        GATE_SRC_SAY = 8,
        GATE_SRC_WHISPER = 9,
        GATE_SRC_TRADE = 3,
        GATE_SRC_GENERAL = 2,
        GATE_SRC_YELL = 12
    };

    // The tier-active threshold: the cloud conversation lane exists only
    // on external endpoints with a real context window (conf-static per
    // process - ExternalApiTierActive is the live form).
    std::uint32_t const CLOUD_TIER_MIN_CTX = 65536;

    // Pure form of the conjunction: the key AND the external tier. The
    // live CloudLaneOpen() (below, server build only) folds the config
    // values into exactly this.
    inline bool CloudLaneOpen(bool cloudChatterKey, std::uint32_t providerSafe, std::uint32_t ctxLength)
    {
        return cloudChatterKey && providerSafe != 0 && ctxLength >= CLOUD_TIER_MIN_CTX;
    }

    // The hard trigger (A3): which incoming lines may start a generation
    // at all.
    //   whisper: always (the intimate channel, both lanes)
    //   party/raid: addressed lines when a REAL player spoke (A3's
    //     bot-authored hole: a bot naming a bot never spends a turn);
    //     UNADDRESSED party/raid lines only on the cloud lane with the
    //     party reply arm enabled (default 0 - addressed-only until the
    //     T3 party step is green)
    //   say: name-addressed + real player, both lanes
    //   trade/general/yell (and anything else): never a trigger
    inline bool HardTriggerAllowed(std::uint32_t src, bool addressedToBot, bool realPlayer,
        bool cloudChat, bool partyReplyEnabled = false)
    {
        switch (src)
        {
            case GATE_SRC_WHISPER:
                return true;
            case GATE_SRC_PARTY:
            case GATE_SRC_RAID:
                if (!realPlayer)
                    return false;               // bot-authored lines never trigger
                if (addressedToBot)
                    return true;                // both lanes
                return cloudChat && partyReplyEnabled; // cloud widening, opt-in
            case GATE_SRC_SAY:
                return addressedToBot && realPlayer;   // both lanes
            default:
                return false;                   // trade/general/yell/... never
        }
    }

    // The SayAction reply gate: the strategy/or ==3 leg today, widened by
    // the cloud lane. blockedMask is the conf's blocked-reply-channel set
    // membership (already resolved by the caller - the set itself is
    // state).
    inline bool ReplyGateAllowed(std::uint32_t src, int llmEnabled, bool hasAiChatStrategy,
        bool cloudChat, bool blocked)
    {
        if (src == GATE_SRC_UNDEFINED || blocked)
            return false;
        if (llmEnabled == 3)
            return true;                        // hand-conf lane, unchanged
        if (hasAiChatStrategy)
            return true;                        // == 2 + strategy, today's external behavior
        return cloudChat;                       // the A1 widening, conjunction-keyed
    }

    // Name-addressing for /say and party (replaces boost::icontains at
    // the call sites so the lowercase-name law is pinnable): a
    // word-bound match of the bot's name, case-insensitive, no substring
    // hits ("Varl" must not match "Varleigh") - the name must be the
    // WHOLE word (or a possessive "'s" tail is still the name being
    // addressed).
    inline bool ContainsNameIgnoreCase(std::string const& msg, std::string const& name)
    {
        if (name.empty() || msg.size() < name.size())
            return false;
        auto eqLower = [](char a, char b)
        {
            return std::tolower(static_cast<unsigned char>(a)) ==
                   std::tolower(static_cast<unsigned char>(b));
        };
        for (size_t i = 0; i + name.size() <= msg.size(); ++i)
        {
            if (!eqLower(msg[i], name[0]))
                continue;
            if (!std::equal(name.begin(), name.end(), msg.begin() + i,
                    [&](char a, char b) { return eqLower(a, b); }))
                continue;
            size_t const end = i + name.size();
            bool const leftBound = i == 0 ||
                !(std::isalnum(static_cast<unsigned char>(msg[i - 1])) || msg[i - 1] == '\'');
            bool rightBound = end == msg.size();
            if (!rightBound)
            {
                char const c = msg[end];
                // "'s" keeps the boundary: "Varleigh's" still addresses
                // Varleigh; any other alnum continuation is a different word
                rightBound = !(std::isalnum(static_cast<unsigned char>(c))) ||
                    (c == '\'' && end + 1 < msg.size() && msg[end + 1] == 's');
            }
            if (leftBound && rightBound)
                return true;
        }
        return false;
    }

    // The A8 end-of-turn class, as a pure fold over the observable
    // outcomes (busy = governor denial; rawError = transport error /
    // http_%d / timeout class string already resolved by the caller;
    // rawEmpty = the backend returned nothing; linesEmpty = content
    // arrived but nothing voicable survived).
    inline std::string ClassifyGeneration(bool busy, std::string const& rawError,
        bool rawEmpty, bool linesEmpty)
    {
        if (busy)
            return "busy";
        if (!rawError.empty())
            return rawError;
        if (rawEmpty)
            return "empty";
        if (linesEmpty)
            return "empty";
        return "ok";
    }

    struct ResponderCandidate
    {
        std::uint32_t guid;
        std::int32_t tier;      // relationship tier, highest speaks first
        std::uint64_t lastWonMs; // rotation anti-monopolization (bigger = spoke more recently)
    };

    // A3's exactly-one responder, pure form: the claim (first writer
    // wins) is runtime state in PlayerbotLlmMemory; WHICH claimant wins
    // is this deterministic pick - highest tier, longest-since-last-win
    // breaks ties, guid as the final stable tiebreak (0 = nobody; the
    // 0-responder case claims nothing and is pinned host-side).
    inline std::uint32_t SelectResponder(std::vector<ResponderCandidate> const& candidates)
    {
        ResponderCandidate const* best = nullptr;
        for (ResponderCandidate const& c : candidates)
        {
            if (!best)
            {
                best = &c;
                continue;
            }
            if (c.tier != best->tier)
            {
                if (c.tier > best->tier)
                    best = &c;
                continue;
            }
            // equal tier: whoever has NOT spoken for longer wins (smaller
            // lastWonMs = longer ago); a 0 lastWonMs is "never won"
            if (c.lastWonMs != best->lastWonMs)
            {
                if (c.lastWonMs < best->lastWonMs)
                    best = &c;
                continue;
            }
            if (c.guid < best->guid)
                best = &c;
        }
        return best ? best->guid : 0;
    }

    struct DialogueOccupant
    {
        std::uint32_t guid;
        std::uint64_t expiresAtMs;
    };

    // A2's per-map zone cap, admission half: prune expired markers (the
    // self-healing TTL - a logout mid-dialogue leaks at most one ghost
    // for <= TTL, no decrement path needed), then a non-interlocutor
    // admits only below the cap. The interlocutor's own admission is
    // unconditional (soft cap) - and when the interlocutor's entry has
    // expired it is re-armed (its expiry extends), never evicted.
    inline bool EvictDialogueVictim(std::vector<DialogueOccupant>& occupants, std::uint64_t nowMs,
        std::uint32_t interlocutorGuid, std::uint32_t cap, std::uint32_t admittingGuid)
    {
        // prune expired markers first (self-healing)
        size_t write = 0;
        for (size_t read = 0; read < occupants.size(); ++read)
        {
            if (occupants[read].expiresAtMs > nowMs)
                occupants[write++] = occupants[read];
        }
        occupants.resize(write);

        if (admittingGuid == interlocutorGuid)
            return true; // the interlocutor always admits (soft cap)

        if (cap == 0)
            return false; // cap disabled by conf: nothing but the interlocutor

        return occupants.size() < cap;
    }

    // A6's street admission ladder - the ORDER is the contract (pin it):
    // world/zone window claim -> per-bot slot -> pct roll -> quota ->
    // dispatch; the emote fires on any rejection. Each stage's verdict
    // arrives resolved; this fold names the stage that rejected so the
    // pin asserts the order, not just the outcome.
    inline std::string StreetAdmissionOrder(bool worldWindowClaimed, bool botSlotFree,
        bool pctRollHit, bool quotaAdmits)
    {
        if (!worldWindowClaimed) return "reject:world-window";
        if (!botSlotFree)        return "reject:bot-slot";
        if (!pctRollHit)         return "reject:pct-roll";
        if (!quotaAdmits)        return "reject:quota";
        return "dispatch";
    }

    // A4's failure decision as a pure fold: busy keeps the persona
    // placeholder (both lanes, unchanged - duty-cycle denial is pacing,
    // not a dead endpoint); every OTHER hard failure (cap, timeout,
    // http_%d, error) and post-parse emptiness wants the authored
    // fallback instead of silence. The autonomous RPG source folds this
    // away by carrying no plan (it stays silent, as today).
    inline bool FailureWantsFallback(bool busy, bool linesEmpty)
    {
        return !busy && linesEmpty;
    }

    // A4's interceptor-demotion plan. World-thread sites fill it with
    // IDS ONLY (kind, channel, category, flags) - never pre-drawn text:
    // pre-drawing would advance shared recency rings and mint belief
    // facts for lines that may never deliver. The async worker draws
    // the actual line at FAILURE time (the BusyReply precedent).
    // Default-constructed = inactive: the autonomous RPG dispatch site
    // keeps compiling with the defaulted trailing parameter and stays
    // silent, and an inactive plan on the conversational path is
    // exactly the device lane (byte-identical silence on failure).
    enum FallbackKind
    {
        FBK_NONE = 0,   // no interceptor demoted (plain cloud turn)
        FBK_GREET = 1,  // the arrival-greeting interceptor demoted
        FBK_PERSONA = 2 // the hard-category persona interceptor demoted
    };

    struct FallbackPlan
    {
        bool active;            // a cloud-lane conversational turn (the
                                // closure owns deliver/record/award)
        std::uint32_t kind;     // FallbackKind
        std::uint32_t channel;  // ChatMsg type the reply lands on
        std::uint32_t personaCategory; // PlayerbotLlmPersona::HardCategory
        bool whisper;           // the persona draw's channel flag
        std::string absence;    // the greet leg's absence bucket (one of
                                // the fixed literals - an id, not text)
        std::uint32_t mapId;    // A2 re-arm map at the fallback delivery
        FallbackPlan()
            : active(false), kind(FBK_NONE), channel(0),
              personaCategory(0), whisper(false), mapId(0) {}
    };
}

#endif
