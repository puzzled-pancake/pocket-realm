#ifndef _PlayerbotLlmChatterCore_h
#define _PlayerbotLlmChatterCore_h

// Pure, host-compilable core of the world-chatter layer:
// the power-ladder policy table, the world-level repetition ring, the
// fatigue + legend ledger, the event-grounded authored floor, and the
// frozen prompt/note wording for the murmur + party-banter device paths
// and the cloud composer protocol.
//
// DOCTRINE: SILENCE IS THE DEFAULT STATE.
// Every line consumes a real event from the server's own history - the
// caller (PlayerbotLlmChatter.cpp) may not even roll without a fact-bank
// row in hand; this core provides no idle-timer-only line shape. The
// authored pools demote to the lowest-specificity emergency floor, and
// even floor lines carry an event (RenderFloorTemplate's {E}).
//
// The wording law: the murmur/party note
// strings and the composer prompts below are FROZEN -
// the bark bank must train the same byte strings the bridge
// injects at runtime (the wording-lock rule applied to the new register).
// Host tests pin them; they move only with a banklib-side change.
//
// PURE: no Player*, no DB, no globals, no wall clock - time, rng and
// state are passed in (the ToolsCore/TruthCore/RecallCore pattern;
// tools/test_llm_chatter.cpp compiles this header standalone with
// -std=c++11 and pins every contract below).
#include <cctype>
#include <cstdint>
#include <cstring>
#include <deque>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "PlayerbotLlmRecallCore.h"   // DistortGossipHop, the legend law
#include "PlayerbotLlmTruthCore.h"    // JaccardWords (the dedupe metric
                                     // the world ring shares, so thresholds
                                     // stay comparable across the two rings)
#include "llm_banter_core.h"          // SplitMix32 + the persona trait dims

namespace pocketllm {

// ---- the power ladder rungs. The app stages the power file at world
// start and re-stages it on battery events (enabled + rung, plus a
// diagnostic stamp the scheduler ignores); the native side re-reads
// it at every scheduler tick. OFF is also what a missing/disabled
// power file means - fail toward silence. The ladder is collapsed by
// design: the user asked for a loud world or they did not. CONSTRAINED is
// the low-battery courtesy dim (stretched cadence, same layers); thermal
// throttling is the OS's job underneath, not a second throttle here.
// EMERGENCY/CRITICAL remain as enum values for wire compat but are never
// produced by the app and map to the dim behavior below.
enum ChatterRung
{
    RUNG_OFF = 0,        // master toggle off (or power file says disabled)
    RUNG_EMERGENCY = 1,  // legacy: treated as the dim courtesy (see below)
    RUNG_CRITICAL = 2,   // legacy: treated as the dim courtesy (see below)
    RUNG_CONSTRAINED = 3, // low-battery courtesy dim: same layers, stretched cadence
    RUNG_NORMAL = 4,     // chatter on, full cadence
};

enum ChatterLayer
{
    LAYER_MURMUR = 0,    // bots near the player, /say, event-grounded
    LAYER_PARTY = 1,     // companion banter while the player quests
    LAYER_GLOBAL = 2,    // rare general-channel set pieces
    LAYER_FLOOR = 3,     // the authored emergency texture (a murmur-class
                         // delivery shape, not an LLM generation)
};

// ---- the per-rung cadence/rate policy. Cadences are the ceiling of the
// design: the silence doctrine still gates every single line on an event.
struct ChatterPolicy
{
    bool generated;       // any LLM generation may run (device or composer)
    bool murmur;          // the murmur layer may deliver
    bool party;           // the party-banter layer may deliver
    bool global;          // the global layer may deliver
    bool composer;        // cloud composer batches may run (NORMAL only,
                          // and only when a composer endpoint is known)
    uint32_t murmurBatchWindowSec;    // refill cadence for the murmur queue
    uint32_t murmurDisplayMinSec;     // drained display cadence (staggered)
    uint32_t murmurDisplayMaxSec;
    uint32_t murmurQueueLowWater;     // refill decision fires below this
    uint32_t partyIdleWindowSec;      // the Phase-5 6-min party cadence
    uint32_t partyIdleRollPct;        // probabilistic roll per window
    uint32_t globalMinSpacingSec;     // hard floor between global lines
    uint32_t globalWindowSec;         // roll window for the rare set piece
    uint32_t globalRollPct;
    uint32_t floorMinSpacingSec;      // authored floor cadence (dormant lane)
};

inline ChatterPolicy ChatterPolicyFor(ChatterRung rung, bool composerConfigured)
{
    ChatterPolicy p;
    switch (rung)
    {
        case RUNG_NORMAL:
            // composer batches: one request of 2-4 exchanges every 4-6 min,
            // drained from the queue at the 20-40 s display cadence. With
            // no composer endpoint configured, NORMAL behaves as the
            // device-batch path at the same display cadence. Phase-5:
            // murmur 30-60 → 20-40 s, party 12 min@50% → 6 min@50%,
            // global 90 → 45 min — AFTER the corpus grew (voice rules
            // first: persona cells 2→6+ lines, wildcard 10→16).
            p.generated = true; p.murmur = true; p.party = true; p.global = true;
            p.composer = composerConfigured;
            p.murmurBatchWindowSec = 270;
            p.murmurDisplayMinSec = 20; p.murmurDisplayMaxSec = 40;
            p.murmurQueueLowWater = 2;
            p.partyIdleWindowSec = 360; p.partyIdleRollPct = 50;
            p.globalMinSpacingSec = 2700;
            p.globalWindowSec = 3600; p.globalRollPct = 50;
            p.floorMinSpacingSec = 0;
            return p;
        case RUNG_CONSTRAINED:
        case RUNG_EMERGENCY:
        case RUNG_CRITICAL:
            // The low-battery courtesy dim: every layer stays on (the user
            // asked for a loud world), single device lines, cadence
            // stretched toward ~1 line/90 s. Legacy EMERGENCY/CRITICAL rung
            // values map here: the app never emits them, but an old staged
            // file must dim, never silence or stop generation outright.
            p.generated = true; p.murmur = true; p.party = true; p.global = true;
            p.composer = false;
            p.murmurBatchWindowSec = 540;
            p.murmurDisplayMinSec = 90; p.murmurDisplayMaxSec = 120;
            p.murmurQueueLowWater = 1;
            p.partyIdleWindowSec = 900; p.partyIdleRollPct = 25;
            p.globalMinSpacingSec = 7200;
            p.globalWindowSec = 10800; p.globalRollPct = 50;
            p.floorMinSpacingSec = 0;
            return p;
        case RUNG_OFF:
        default:
            p.generated = false; p.murmur = false; p.party = false;
            p.global = false; p.composer = false;
            p.murmurBatchWindowSec = 0;
            p.murmurDisplayMinSec = 0; p.murmurDisplayMaxSec = 0;
            p.murmurQueueLowWater = 0;
            p.partyIdleWindowSec = 0; p.partyIdleRollPct = 0;
            p.globalMinSpacingSec = 0; p.globalWindowSec = 0;
            p.globalRollPct = 0;
            p.floorMinSpacingSec = 0;
            return p;
    }
}

// ---- the WORLD-LEVEL repetition ring (the counter the per-bot
// rings lack: cross-bot echo). Every delivered chatter line is vetted
// against the last kCap delivered lines with the same Jaccard word-set
// metric the per-bot dedupe uses (TruthCore::JaccardWords, threshold
// 0.5 - the per-bot precedent applied world-wide).
struct WorldRing
{
    std::deque<std::string> lines;
    static size_t const kCap = 24;
};

inline double WorldRingMaxJaccard() { return 0.5; }

inline bool RingAdmits(WorldRing const& ring, std::string const& line)
{
    for (std::string const& recent : ring.lines)
        if (JaccardWords(recent, line) > WorldRingMaxJaccard())
            return false;
    return true;
}

inline void RingRemember(WorldRing& ring, std::string const& line)
{
    ring.lines.push_back(line);
    while (ring.lines.size() > WorldRing::kCap)
        ring.lines.pop_front();
}

// ---- the fatigue + legend ledger. Fatigue is tracked THREE ways
// (the counter Skyrim lacked):
//   per world-fact: a story retires after kMaxFactTellings deliveries -
//     no one comments on the same duel twice, and a topic saturates.
//   per (template x speaker x listener): the authored floor's spacing -
//     the same speaker does not hand the same listener the same shape.
//   per-listener credence: a bot who HEARD a legend never retells it;
//     only originators/witnesses spread it, one distortion hop per
//     retelling, content-bearing hops capped at kMaxContentHops (the
//     small-model telephone-game collapse guard). The immutable event
//     row is the truth anchor - drift lives only in delivered prose.
struct FactFatigue
{
    uint32_t tellings = 0;   // delivered chatter lines about this fact
    uint32_t hops = 0;       // content-bearing distortion hops so far
    std::string lastText;    // the last delivered telling (drift basis)
};

struct ChatterFatigue
{
    // Insert-only ledgers, accepted by design (the GUID-statics class):
    // retired facts keep their tombstones so the retirement gate holds,
    // and a loud 10-hour session accrues on the order of tens-to-hundreds
    // of KB (perFact entries ~100-400 B, heard pairs ~40-60 B) - bounded
    // by world fact production, process-lifetime, never unbounded per
    // tick.
    std::map<std::string, FactFatigue> perFact;
    std::set<std::string> heard;                 // "<listener>|<factKey>"
    std::map<std::string, int64_t> lastTemplate; // "<tpl>|<spk>|<lst>"

    static uint32_t const kMaxFactTellings = 5;
    static uint32_t const kMaxContentHops = 3;
};

inline std::string FatigueHerdKey(uint32_t listenerGuid, std::string const& factKey)
{
    return std::to_string(listenerGuid) + "|" + factKey;
}

inline bool FatigueAdmits(ChatterFatigue const& fatigue, std::string const& factKey)
{
    auto itr = fatigue.perFact.find(factKey);
    return itr == fatigue.perFact.end() ||
        itr->second.tellings < ChatterFatigue::kMaxFactTellings;
}

inline bool CredenceAdmits(ChatterFatigue const& fatigue, uint32_t speakerGuid,
    std::string const& factKey)
{
    return fatigue.heard.find(FatigueHerdKey(speakerGuid, factKey)) ==
        fatigue.heard.end();
}

inline void MarkHeard(ChatterFatigue& fatigue, uint32_t listenerGuid,
    std::string const& factKey)
{
    fatigue.heard.insert(FatigueHerdKey(listenerGuid, factKey));
}

// Records one DELIVERED telling. originatorTelling=true keeps the row
// text verbatim (the bot lived it); every later teller drifts exactly one
// deterministic DistortGossipHop from the previous telling while hops
// remain - at the cap the color freezes (retellings stay truthful to the
// last drifted form; the row itself is never rewritten). The verbatim
// branch is keyed on lastText (set at PICK), not the tellings count: a
// picked-but-undelivered originator telling must not re-freeze the next
// teller's drift.
inline std::string LegendTellingText(ChatterFatigue& fatigue, std::string const& factKey,
    std::string const& rowText, bool originatorTelling)
{
    FactFatigue& fact = fatigue.perFact[factKey];
    if (originatorTelling || fact.lastText.empty())
    {
        fact.lastText = rowText;
        return rowText;
    }
    if (fact.hops < ChatterFatigue::kMaxContentHops)
    {
        fact.lastText = DistortGossipHop(fact.lastText, 0);
        ++fact.hops;
    }
    // at the hop cap the last drifted text passes through unchanged
    return fact.lastText;
}

inline void FatigueRecordTelling(ChatterFatigue& fatigue, std::string const& factKey)
{
    ++fatigue.perFact[factKey].tellings;
}

inline std::string TemplateKey(uint32_t templateIdx, uint32_t speakerGuid,
    uint32_t listenerGuid)
{
    return std::to_string(templateIdx) + "|" +
        std::to_string(speakerGuid) + "|" + std::to_string(listenerGuid);
}

inline bool TemplateSpacingAdmits(ChatterFatigue const& fatigue, uint32_t templateIdx,
    uint32_t speakerGuid, uint32_t listenerGuid, int64_t nowSec, int64_t minSpacingSec)
{
    auto itr = fatigue.lastTemplate.find(
        TemplateKey(templateIdx, speakerGuid, listenerGuid));
    return itr == fatigue.lastTemplate.end() ||
        nowSec - itr->second >= minSpacingSec;
}

inline void TemplateSpacingRecord(ChatterFatigue& fatigue, uint32_t templateIdx,
    uint32_t speakerGuid, uint32_t listenerGuid, int64_t nowSec)
{
    fatigue.lastTemplate[TemplateKey(templateIdx, speakerGuid, listenerGuid)] = nowSec;
}

// ---- the murmur register (the trained target, runtime-tolerated): 8-20
// words is the trained register; at runtime a murmur line below
// kMinMurmurWords is junk (deflections, "Hm."), above kMaxMurmurWords it
// is a speech, not a murmur. Delivery clamps bytes separately.
inline size_t CountWords(std::string const& text)
{
    size_t words = 0;
    bool inWord = false;
    for (char c : text)
    {
        if (isalpha(static_cast<unsigned char>(c)) || isdigit(static_cast<unsigned char>(c)))
        {
            if (!inWord) ++words;
            inWord = true;
        }
        else
            inWord = false;
    }
    return words;
}

enum { kMinMurmurWords = 5, kMaxMurmurWords = 24, kMurmurMaxBytes = 120 };

inline bool IsMurmurRegister(std::string const& line)
{
    size_t const words = CountWords(line);
    return words >= kMinMurmurWords && words <= kMaxMurmurWords;
}

// ---- the authored event-grounded floor (dormant under the collapsed
// ladder: every live rung generates, so the floor only runs if a future
// policy row sets generated=false). Templates
// carry the event as cargo - an authored line never fabricates ledger
// state (the bot2bot law), so even the floor honors the silence
// doctrine. {S} speaker, {L} listener, {E} event.
namespace detail {
inline char const* const* MurmurFloorTable(size_t& count)
{
    static char const* const templates[] = {
        "Heard the latest, {L}? {E}. Straight truth.",
        "{L}. {E}. I would not have believed it either.",
        "They are saying {E}, {L}. Were you there for that?",
        "{E}, and no one talks of anything else. You caught it too, {L}?",
        "Keep this close, {L}: {E}.",
        "{E}. Small world, small news, but it is ours, {L}.",
        "A traveler told me {E}, {L}. A traveler has no reason to lie.",
        "{L}, was it you who saw it? {E}. The whole road knows.",
        "First the birds, then the traders, {L}: {E}.",
        "{E}. Mark the day, {L}. Days like this are rare.",
    };
    count = sizeof(templates) / sizeof(templates[0]);
    return templates;
}
} // namespace detail

inline char const* MurmurFloorTemplate(size_t idx)
{
    size_t count = 0;
    char const* const* templates = detail::MurmurFloorTable(count);
    return templates[idx % count];
}

inline size_t MurmurFloorTemplateCount()
{
    size_t count = 0;
    detail::MurmurFloorTable(count);
    return count;
}

inline std::string RenderFloorTemplate(size_t idx, std::string const& speaker,
    std::string const& listener, std::string const& eventText)
{
    std::string out;
    std::string const tmpl = MurmurFloorTemplate(idx);
    for (size_t i = 0; i < tmpl.size(); ++i)
    {
        if (tmpl.compare(i, 3, "{S}") == 0) { out += speaker; i += 2; }
        else if (tmpl.compare(i, 3, "{L}") == 0) { out += listener; i += 2; }
        else if (tmpl.compare(i, 3, "{E}") == 0) { out += eventText; i += 2; }
        else out += tmpl[i];
    }
    return out;
}

// ---- the third persona dimension (anti-flanderization): the CURRENT
// GRIPE, GUID-derived but slot-rotating so a long-lived bot is not the
// same caricature every week. slot is the caller's slow bucket (pure).
inline char const* GripeOf(uint32_t botGuid, uint32_t slot)
{
    static char const* const gripes[] = {
        "sore about a debt nobody will pay",
        "fed up with the price of bread",
        "nursing a bruise from last market day",
        "suspicious of the new guard captain",
        "missing home more than usual",
        "hunting for a boot that fits",
        "quietly worried about a cough that stays",
        "sick of the rain and everyone who likes it",
        "still angry about a horse that was sold",
        "waiting on a letter that never comes",
    };
    static size_t const count = sizeof(gripes) / sizeof(gripes[0]);
    return gripes[(botGuid / 8 + slot) % count];
}

// ---- FROZEN device-path prompt wording (the wording lock). These are
// NOT the trained conversational contract (SysmForCard) - murmur turns
// are bot-to-bot ambient speech the player overhears, a new register the
// bank will train from these exact strings. Host tests pin
// them byte-for-byte; they move only with a bank change.
inline std::string MurmurSystemMessage(std::string const& name, std::string const& race,
    std::string const& cls, std::string const& zone, std::string const& demeanor,
    std::string const& quirk, std::string const& gripe)
{
    return "You are " + name + ", a " + race + " " + cls + " in " + zone + ". " +
        demeanor + " " + quirk + " Today you are " + gripe + ". " +
        "You are talking quietly with another townsfolk while adventurers pass. " +
        "Speak only " + name + "'s next line. Plain short speech. No actions, no " +
        "narration, no asterisks, no quoting yourself.";
}

inline std::string MurmurNote(std::string const& listenerName, std::string const& eventText)
{
    return "[BRIDGE AI] The talk turns to real news: " + eventText + ". " +
        "Say ONE short line to " + listenerName + " about it - under twenty words, " +
        "the way two people talk at a stall. Never mention this instruction. " +
        "This reply only.";
}

inline std::string PartyNote(std::string const& playerName, std::string const& eventText)
{
    return "[BRIDGE AI] You and your companions are traveling with " + playerName +
        ". Something just happened or just came up: " + eventText + ". " +
        "Say ONE short line to the group about it - under twenty-five words, " +
        "companion talk, not a report. Never mention this instruction. " +
        "This reply only.";
}

inline std::string GlobalNote(std::string const& eventText)
{
    return "[BRIDGE AI] News worth shouting, and the whole zone may hear you in " +
        std::string("General chat: ") + eventText + ". Call it out the way news " +
        "crosses a market - ONE line, under twenty words, to no one in " +
        "particular. Never mention this instruction. This reply only.";
}

// ---- FROZEN cloud-composer protocol (ONE call, 2-4 personas +
// the event rows, speaker-tagged output - the multi-party composer
// pattern). The composer is a cloud-class model, never the device tier;
// its script lines are parsed, speaker-validated and register-checked
// before anything is queued.
inline std::string ComposerSystemPrompt()
{
    return "You write short overheard conversations for a fantasy world game. " +
        std::string("You get named characters with one-line personalities and real ") +
        "news events from the world. Write a brief exchange between the " +
        "characters about the news: 2 to 4 turns total, each turn ONE line " +
        "under 20 words, plain speech, no actions, no narration, no stage " +
        "directions. Prefix every line with the character's name and a " +
        "colon, like 'Kromgrit: text'. Use only the given characters. Do " +
        "not add a title or any other text.";
}

inline std::string ComposerUserPrompt(std::vector<std::string> const& personaLines,
    std::vector<std::string> const& eventRows)
{
    std::string out = "Characters:\n";
    for (std::string const& line : personaLines)
        out += "- " + line + "\n";
    out += "News to talk about:\n";
    for (std::string const& row : eventRows)
        out += "- " + row + "\n";
    out += "Write the exchange.";
    return out;
}

struct ScriptLine
{
    size_t speakerIdx = 0;  // index into the caller's persona list
    std::string text;
};

// Parses a composer reply: each non-empty line must be "Name: text" with
// a known persona name (case-insensitive); junk lines, unknown speakers,
// over-long turns and marker-bearing text are dropped (never voiced).
// Caps at 6 accepted turns.
inline std::vector<ScriptLine> ParseComposerScript(std::string const& raw,
    std::vector<std::string> const& names)
{
    std::vector<ScriptLine> out;
    std::string lowerNames[8];
    size_t const nameCount = names.size() < 8 ? names.size() : 8;
    for (size_t i = 0; i < nameCount; ++i)
        for (char c : names[i])
            lowerNames[i].push_back((char)std::tolower((unsigned char)c));

    size_t at = 0;
    while (out.size() < 6 && at < raw.size())
    {
        size_t const eol = raw.find('\n', at);
        std::string const line = raw.substr(at,
            eol == std::string::npos ? std::string::npos : eol - at);
        at = eol == std::string::npos ? raw.size() : eol + 1;

        // trim
        size_t b = 0, e = line.size();
        while (b < e && isspace(static_cast<unsigned char>(line[b]))) ++b;
        while (e > b && isspace(static_cast<unsigned char>(line[e - 1]))) --e;
        if (b >= e) continue;
        size_t const colon = line.find(':', b);
        if (colon == std::string::npos || colon >= e) continue;
        std::string speakerRaw = line.substr(b, colon - b);
        while (!speakerRaw.empty() &&
            isspace(static_cast<unsigned char>(speakerRaw.back())))
            speakerRaw.pop_back();
        std::string speakerLower;
        for (char c : speakerRaw)
            speakerLower.push_back((char)std::tolower((unsigned char)c));

        bool matched = false;
        for (size_t i = 0; i < nameCount; ++i)
        {
            if (speakerLower != lowerNames[i]) continue;
            std::string text = line.substr(colon + 1, e - colon - 1);
            size_t tb = 0, te = text.size();
            while (tb < te && isspace(static_cast<unsigned char>(text[tb]))) ++tb;
            while (te > tb && isspace(static_cast<unsigned char>(text[te - 1]))) --te;
            text = text.substr(tb, te - tb);
            if (text.size() < 4 || text.size() > kMurmurMaxBytes) { matched = true; break; }
            // the ChatterLineSafe law, inline: printable ASCII, no
            // protocol/pipe characters, no emote-initial leads (a cloud
            // composer must never mint '|c..|h' client sequences)
            bool safe = text[0] != '*' && text[0] != '[' && text[0] != ' ';
            for (size_t k = 0; safe && k < text.size(); ++k)
            {
                unsigned char const b = static_cast<unsigned char>(text[k]);
                if (b < 0x20 || b > 0x7E || text[k] == '<' || text[k] == '>' ||
                    text[k] == '{' || text[k] == '}' || text[k] == '|')
                    safe = false;
            }
            if (!safe) { matched = true; break; }
            ScriptLine accepted;
            accepted.speakerIdx = i;
            accepted.text = text;
            out.push_back(accepted);
            matched = true;
            break;
        }
        (void)matched; // an unmatched speaker line is dropped by construction
    }
    return out;
}

// ---- FROZEN narrator wording for the plan-v5 session recap (C2). Like
// the composer protocol above, this is a cloud-class surface: the prose
// variant renders the digest lines as a "previously, in your realm"
// block. Host tests pin the strings; they move only with a deliberate
// wording change.
inline std::string RecapSystemPrompt()
{
    return "You are the chronicler of a small fantasy realm. You get true "
        + std::string("lines from the realm's memory ledger about one returning ") +
        "adventurer. Write 'Previously, in your realm:' and then 3 to 5 short " +
        "lines that retell those truths as a warm narrator - plain speech, no " +
        "names of mechanics, no questions, no stage directions. Each line under " +
        "twenty words. Use only the given truths; invent nothing.";
}

// Splits a narrator block into deliverable sys lines: newline-split, trim,
// the chatter line-safety law (printable ASCII, no protocol/pipe bytes,
// no emote leads) at the 200-byte sys-line budget, capped at 8 lines.
inline std::vector<std::string> SplitNarratorBlock(std::string const& raw)
{
    std::vector<std::string> out;
    size_t at = 0;
    while (out.size() < 8 && at < raw.size())
    {
        size_t const eol = raw.find('\n', at);
        std::string line = raw.substr(at,
            eol == std::string::npos ? std::string::npos : eol - at);
        at = eol == std::string::npos ? raw.size() : eol + 1;
        size_t b = 0, e = line.size();
        while (b < e && isspace(static_cast<unsigned char>(line[b]))) ++b;
        while (e > b && isspace(static_cast<unsigned char>(line[e - 1]))) --e;
        if (b >= e) continue;
        line = line.substr(b, e - b);
        if (line.size() < 4 || line.size() > 200) continue;
        bool safe = line[0] != '*' && line[0] != '[' && line[0] != ' ' && line[0] != '|';
        for (size_t k = 0; safe && k < line.size(); ++k)
        {
            unsigned char const c = static_cast<unsigned char>(line[k]);
            if (c < 0x20 || c > 0x7E || line[k] == '<' || line[k] == '>' ||
                line[k] == '{' || line[k] == '}' || line[k] == '|')
                safe = false;
        }
        if (safe)
            out.push_back(line);
    }
    return out;
}

// ---- the murmur clamp: a delivered murmur line is trimmed to the byte
// budget on a UTF-8 boundary (the TruthCore TruncUtf8 twin, local so the
// core stays standalone) with a trailing ellipsis so a cut reads cut.
inline std::string ClampMurmurBytes(std::string const& text, size_t maxBytes)
{
    if (text.size() <= maxBytes) return text;
    size_t end = maxBytes;
    while (end > 0 && (static_cast<unsigned char>(text[end]) & 0xC0) == 0x80)
        --end; // back off lead-byte fragments
    while (end > 0 && end + 3 > maxBytes) --end; // room for "..."
    std::string out = text.substr(0, end);
    out += "...";
    return out;
}

// ---- the chatter line-safety law (the banter-core LineIsValid contract,
// tightened for an autonomous player-absent producer): printable ASCII
// only (kills newlines, control bytes AND any UTF-8 residue in one law),
// no protocol/pipe characters (a cloud composer must never emit '|c..|h'
// client color/hyperlink sequences or tool braces), no emote-initial or
// space-initial leads, sane length. Applied to every queued generated
// line on top of HygienePass (which clamps ASCII but preserves newlines
// and admits braces/pipes).
inline bool ChatterLineSafe(std::string const& text)
{
    if (text.size() < 4 || text.size() > kMurmurMaxBytes) return false;
    char const lead = text[0];
    if (lead == '*' || lead == '[' || lead == ' ' || lead == '|') return false;
    for (char c : text)
    {
        unsigned char const b = static_cast<unsigned char>(c);
        if (b < 0x20 || b > 0x7E) return false;
        if (c == '<' || c == '>' || c == '{' || c == '}' || c == '|') return false;
    }
    return true;
}

// plan v5 F4b: the long-form lane's line-safety law - the same byte/lead/
// protocol rules at the 200-byte staged-line budget (a saga line is a
// deliberate performance, not a murmur; the murmur register does not
// apply, the injection laws do)
enum { kLongFormMaxBytes = 200 };

inline bool ChatterLongLineSafe(std::string const& text)
{
    if (text.size() < 4 || text.size() > kLongFormMaxBytes) return false;
    char const lead = text[0];
    if (lead == '*' || lead == '[' || lead == ' ' || lead == '|') return false;
    for (char c : text)
    {
        unsigned char const b = static_cast<unsigned char>(c);
        if (b < 0x20 || b > 0x7E) return false;
        if (c == '<' || c == '>' || c == '{' || c == '}' || c == '|') return false;
    }
    return true;
}

// ---- FROZEN saga wording (plan v5 C1). The campfire saga is the cloud
// flagship: one call turns the pairing's real fact rows into a 300-600
// token telling; the first safe line becomes the headline gossip row the
// town retells for weeks. Host tests pin the string.
inline std::string SagaSystemPrompt()
{
    return "You are a campfire storyteller in a fantasy world. You get true "
        + std::string("memory lines about one adventurer and their companions. ") +
        "Write the story the storyteller tells aloud at the fire about those real " +
        "events: 5 to 10 short lines, each under two hundred bytes, plain speech, " +
        "warm and a little larger than life but true to the given facts. No names " +
        "of mechanics, no questions, no stage directions. Every line stands alone.";
}

inline std::string SagaUserPrompt(std::string const& storyteller,
    std::string const& playerName, std::vector<std::string> const& factLines)
{
    std::string out = storyteller + " is telling " + playerName +
        "'s own story back to them at the campfire.\n";
    out += "True memory lines:\n";
    for (std::string const& fact : factLines)
        out += "- " + fact + "\n";
    out += "Write the telling.";
    return out;
}

// ---- the interruption rule: player chat owns the channel. The caller
// stamps every real-player conversational trigger; murmur delivery
// pauses while the stamp is inside this window (party banter likewise -
// a player talking to a companion outranks the idle exchange).
enum { kPlayerChannelHoldSec = 8 };

inline bool PlayerHoldsChannel(int64_t lastPlayerChatSec, int64_t nowSec)
{
    return lastPlayerChatSec > 0 && nowSec - lastPlayerChatSec < kPlayerChannelHoldSec;
}

// The ambient ADMISSION window (wider than the delivery hold): a device
// batch is only DISPATCHED when the player has been quiet for this long
// - generation while a conversation is live wastes battery and races the
// interactive lane even on a multi-slot server (the queueing
// window). The murmur queue refills in quiet moments and drains at
// display cadence; the player never loses the channel to a batch.
enum { kAmbientAdmissionHoldSec = 30 };

inline bool AmbientAdmissionQuiet(int64_t lastPlayerChatSec, int64_t nowSec)
{
    return lastPlayerChatSec <= 0 || nowSec - lastPlayerChatSec >= kAmbientAdmissionHoldSec;
}

// ---- deterministic batch arithmetic (pure, so the soak harness drives
// the REAL decision math over simulated time).
inline bool WindowRoll(uint32_t& rng, uint32_t rollPct)
{
    if (rollPct == 0) return false;
    return (SplitMix32(rng) % 100u) < rollPct;
}

} // namespace pocketllm

#endif
