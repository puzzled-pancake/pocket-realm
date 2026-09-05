#ifndef _PlayerbotLlmRecallCore_h
#define _PlayerbotLlmRecallCore_h

// Pure, host-compilable core of the memory-USE layer (recall
// beats / tier ceremony / gossip distortion): the fact
// classifier, the recall question shapes, the beat-cargo builders (beats
// carry cargo, never bare
// instructions), the tier-ceremony wording, the deterministic
// nickname/secret picks and the authored initiative line shapes.
// PlayerbotLlmBridge.cpp + PlayerbotLlmMemory.cpp consume it in-tree,
// and the host test suite (tools/test_llm_recall.cpp, run by
// tests/test_llm_recall.py) compiles it standalone exactly like
// ToolsCore/TruthCore, so the beat wording and the recall grammar are
// pinned by tests instead of by inspection.
#include <cctype>
#include <cstdint>
#include <cstring>
#include <string>

#include "PlayerbotLlmToolsCore.h"

namespace pocketllm {

// ---- fact classification over the append-only fact rows. The
// class decides which recall beat a fact can supply cargo for; the
// category is the writer's own (log_fact tool), the text class is ours.
enum FactClass
{
    FACT_PLAIN = 0,   // no recall class (still a valid weave pick)
    FACT_DEBT,        // "... owes me five silver from the ale"
    FACT_GOAL,        // "... is saving for a ram" / wants/plans shapes
    FACT_EVENT,       // duel outcomes and other shared events
};

// The recall-surface MASK constants: GetNewestRecallFact takes a bit per
// class, and a raw enum value is NOT its own mask (FACT_DEBT == 1 would
// select PLAIN). Always compose masks from these - a value-as-mask
// confusion at the bridge call sites is exactly the bug this prevents.
enum FactClassMask
{
    FACT_MASK_PLAIN = 1 << FACT_PLAIN,
    FACT_MASK_DEBT = 1 << FACT_DEBT,
    FACT_MASK_GOAL = 1 << FACT_GOAL,
    FACT_MASK_EVENT = 1 << FACT_EVENT,
};

inline bool IsVowel(char c)
{
    c = (char)std::tolower((unsigned char)c);
    return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
}

// Word-boundary-aware lowercased containment (a bare find would let
// "respond" carry "owes").
inline bool ContainsWord(std::string const& lower, std::string const& word)
{
    size_t at = lower.find(word);
    while (at != std::string::npos)
    {
        bool left = at == 0 || !isalpha(static_cast<unsigned char>(lower[at - 1]));
        size_t const end = at + word.size();
        bool right = end >= lower.size() || !isalpha(static_cast<unsigned char>(lower[end]));
        if (left && right)
            return true;
        at = lower.find(word, end);
    }
    return false;
}

// Case-SENSITIVE word-boundary containment: the capitalization is the
// signal that the token is a NAME (player names are capitalized, common
// words in gossip prose are not). "Ash" matches "Ash lost a duel" but
// neither "the ash of the fire" (lowercase) nor "Ashmar" (no right
// boundary) - either match would misattribute the line in GossipAbout.
inline bool ContainsWordExact(std::string const& text, std::string const& word)
{
    size_t at = text.find(word);
    while (at != std::string::npos)
    {
        bool left = at == 0 || !isalpha(static_cast<unsigned char>(text[at - 1]));
        size_t const end = at + word.size();
        bool right = end >= text.size() || !isalpha(static_cast<unsigned char>(text[end]));
        if (left && right)
            return true;
        at = text.find(word, end);
    }
    return false;
}

inline std::string LowerCopy(std::string const& text)
{
    std::string lower;
    lower.reserve(text.size());
    for (char c : text)
        lower.push_back((char)std::tolower((unsigned char)c));
    return lower;
}

inline FactClass FactClassOf(std::string const& factText, std::string const& category)
{
    std::string const lower = LowerCopy(factText);
    // debt needs the owing direction: money or the owing verb toward "me"
    static char const* const debtCues[] = {
        "owes me", "owed me", "owes you", "owed you", "still owes",
        "unpaid", "not paid me", "debt", "borrowed",
    };
    for (char const* cue : debtCues)
        if (lower.find(cue) != std::string::npos)
            return FACT_DEBT;
    // goal cues outrank the shared-event category: "wants to learn
    // smithing" is an ask-after fact whatever bucket it was logged in
    static char const* const goalCues[] = {
        "saving for", "saving up", "wants to", "plans to", "working toward",
        "training to", "saving", "hopes to", "dreams of",
    };
    for (char const* cue : goalCues)
        if (lower.find(cue) != std::string::npos)
            return FACT_GOAL;
    if (category == "shared-event")
        return FACT_EVENT;
    return FACT_PLAIN;
}

// ---- recall question shapes (negation-aware, the ContainsTrigger law):
// the turn asks about a MEMORY CLASS, not about an entity - the entity
// guard never fires on these because no unresolved name is present.
inline bool IsDebtQuestion(std::string const& msg)
{
    static char const* const triggers[] = {
        "are we square", "we square", "do i owe you", "did i owe",
        "what do i owe", "owe you anything", "owe you money",
        "did i pay you", "paid you back", "pay you back yet",
        "any debts", "am i square",
    };
    return ContainsTrigger(msg, triggers, sizeof(triggers) / sizeof(triggers[0]));
}

inline bool IsMemoryQuestion(std::string const& msg)
{
    // deliberately no bare "do you remember": "do you remember the way
    // to Goldshire" asks about the WORLD (the lore card path owns it),
    // and a weave-pick note there would hijack a directions question.
    static char const* const triggers[] = {
        "what do you remember about me", "remember me",
        "remember anything about me", "know about me", "know me",
        "what do you know about me", "do you remember me",
        "remember who i am",
    };
    return ContainsTrigger(msg, triggers, sizeof(triggers) / sizeof(triggers[0]));
}

// ---- FactDirect: the bot's stored POV ("me"/"my") rendered as the
// note's second person ("you"/"your") - closed-class word-boundary
// swap only; anything else passes through untouched.
inline std::string FactDirect(std::string const& factText)
{
    static std::pair<char const*, char const*> const swaps[] = {
        {" my", " your"}, {" me", " you"}, {" mine", " yours"},
    };
    std::string out;
    out.reserve(factText.size() + 8);
    out.push_back(' ');
    out += factText;
    for (auto const& swap : swaps)
    {
        std::string const from = swap.first;
        std::string const to = swap.second;
        size_t at = out.find(from);
        while (at != std::string::npos)
        {
            size_t const end = at + from.size();
            bool right = end >= out.size() || !isalpha(static_cast<unsigned char>(out[end]));
            if (right)
                out.replace(at, from.size(), to);
            at = out.find(from, at + to.size());
        }
    }
    out.erase(0, 1);
    // a leading third-person subject ("he owes you...") drops for the
    // composed cargo shapes ("{player} owes you...")
    if (out.compare(0, 3, "he ") == 0)
        out.erase(0, 3);
    else if (out.compare(0, 4, "she ") == 0)
        out.erase(0, 4);
    return out;
}

// ---- beat-cargo frames (generated by
// tools/llm_lab/emit_prompt_constants.py from banklib.py
// BEAT_CARGO_VARIANTS - the S11 rev-3b wording lock; the
// banks train these exact frames, and the bridge selects
// one flavor per bot, GUID-stably (flavor = botGuid % 6).
// Do not hand-edit between the markers. ----
static char const* const kDebtCargoFrames[] = {
    "{P} {F}. It is UNPAID. You are NOT square. Name it.",
    "{P} {F}. That debt stands - nothing square about it. Bring it up yourself this time.",
    "{P} {F}. Still unpaid, and you keep the count. Say it plainly when it fits.",
    "{P} {F}. The ledger does not forget, and neither do you. Collect with a grin, not a snarl.",
    "{P} {F}. Owed is owed. Remind them the way old friends do - sharp, fond, and impossible to dodge.",
    "{P} {F}. Debts age like ale with you: stronger, louder, and harder to ignore. Say so.",
};

static char const* const kMemoryCargoFrames[] = {
    "You DO remember {P}. One true thing: {F}. Work it in - once, naturally.",
    "You DO remember {P}. Here is the thing you know: {F}. Let it slip in - once, as your own.",
    "You DO remember {P}. This much is true: {F}. Weave it in once, easy - never like reciting.",
    "That thing about {P} - {F} - has been sitting with you. Spend it now, once, like it just surfaced.",
    "You carry this about {P}: {F}. Drop it into the talk sideways, the way real remembering works.",
    "Of all you know of {P}, this rises now: {F}. Voice it once, then let the moment pass.",
};

static char const* const kMemoryAskCargoFrames[] = {
    "You remember what {P} was after: {F}. Ask after it, like it matters - because it does.",
    "You remember what {P} was after: {F}. Ask - and sound like you cared enough to keep it.",
    "You remember what {P} was after: {F}. Ask after it warmly; you have been waiting to hear.",
    "That goal of {P} - {F} - still hangs open. Pull the thread: how fares it?",
    "{P} wanted this: {F}. You kept it in mind all this time. Ask, and mean it.",
    "So - {P} and {F}. Time has passed; curiosity has not. Ask after it properly.",
};

static char const* const kGrudgeCargoFrames[] = {
    "You are still sore about this: {F}. Let it show at the edges only - never name the grudge outright.",
    "It still stings, this: {F}. Keep it under the words - never lay the grudge on the table.",
    "An old sore, not healed: {F}. Let it color the edges - the grudge itself stays unsaid.",
    "That old business - {F} - still smarts when pressed. Let the chill show in what you do, not what you say.",
    "{F}. You have not forgotten, and forgiveness is not on the menu tonight. Edge, not accusation.",
    "The scar of it - {F} - itches in this company. Short answers, long memory.",
};

static char const* const kNewsCargoFrames[] = {
    "Something DID happen: {F}. That is your news, with {P}. Tell it.",
    "Something DID happen: {F}. That is your news, with {P}. Tell it plainly.",
    "Something DID happen: {F}. {P} has not heard this yet. Tell it.",
    "News, and it is yours to carry: {F}. {P} gets it from you first - make it land.",
    "You were there - or heard it from one who was: {F}. Tell {P} straight.",
    "This just happened, and {P} should hear it from a friend: {F}. Tell it whole.",
};

static char const* const kGossipCargoFrames[] = {
    "Word is going round about {P}: '{G}'. Ask about it, lightly - one question, no more.",
    "There is talk about {P}: '{G}'. One light question - then let it drop.",
    "The town buzzes a little about {P}: '{G}'. Ask, gently - once only.",
    "Something is being said about {P}: '{G}'. Poke at it once, softly - then leave it be.",
    "You caught wind of this about {P}: '{G}'. One curious question, no more.",
    "Rumor brushes {P}: '{G}'. Brush back - lightly, once, and watch the reaction.",
};

static char const* const kCeremonyUpFrames[] = {
    "You have quietly decided {P} is {T}. Show it your own way, briefly - and do not explain yourself.",
    "Something has settled in you: {P} is {T}. Let it show in what you do - brief, and unexplained.",
    "You know it now, quietly: {P} is {T}. Show it once, your own way - no speeches.",
    "It has crept up on you: {P} is {T} to you now. A warmer word, unannounced.",
    "No ceremony, no speech - but {P} is {T}, and it shows in how you stand nearer.",
    "You catch yourself smiling when {P} arrives. That is new. That is {T}. Let it be seen, briefly.",
};

static char const* const kCeremonyDownFrames[] = {
    "Something in you has grown colder toward {P}. Do not announce it. Let it change what you do, not what you say.",
    "The warmth has thinned toward {P}. Say nothing of it. Let your actions carry the chill.",
    "You trust {P} less than you did. Keep that to yourself - it shows in deeds, never in words.",
    "A small frost where {P} is concerned. No words about it - fewer favors, slower nods.",
    "You hold {P} a little more at arm's length now. Polite. Distant. Final.",
    "The easy warmth with {P} is gone. Courtesy remains; closeness does not.",
};

// the ceremony {T} phrases, indexed by tier 3/4/5
static char const* const kCeremonyUpPhrases[] = {
    /* tier 3 */ "a friend worth keeping",
    /* tier 4 */ "a true friend",
    /* tier 5 */ "the one you would follow anywhere",
};

// the S11 P50/P51 long-form cue (banklib.LONGFORM_CUE):
// ONE frozen signal, appended to the beat cargo only when
// LongFormLicensed(maxNewTokens) clears the long bank.
static char const* const kLongFormCue =
    "This one is worth telling properly - take a full breath and tell it whole, start to end.";
// ---- end beat-cargo frames ----
// every frame set must be uniform: CargoFlavor mods by ONE set's size,
// so a divergent array would read out of bounds in the world process
// (the python law block checks banklib; this assert holds the C++ side
// at compile time).
static_assert(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]) ==
    sizeof(kMemoryCargoFrames) / sizeof(kMemoryCargoFrames[0]), "flavor sets must be uniform");
static_assert(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]) ==
    sizeof(kMemoryAskCargoFrames) / sizeof(kMemoryAskCargoFrames[0]), "flavor sets must be uniform");
static_assert(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]) ==
    sizeof(kGrudgeCargoFrames) / sizeof(kGrudgeCargoFrames[0]), "flavor sets must be uniform");
static_assert(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]) ==
    sizeof(kNewsCargoFrames) / sizeof(kNewsCargoFrames[0]), "flavor sets must be uniform");
static_assert(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]) ==
    sizeof(kGossipCargoFrames) / sizeof(kGossipCargoFrames[0]), "flavor sets must be uniform");
static_assert(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]) ==
    sizeof(kCeremonyUpFrames) / sizeof(kCeremonyUpFrames[0]), "flavor sets must be uniform");
static_assert(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]) ==
    sizeof(kCeremonyDownFrames) / sizeof(kCeremonyDownFrames[0]), "flavor sets must be uniform");
// ---- beat cargo. Wording follows the
// trained shapes: the fact arrives as cargo with its verdict named,
// the instruction rides one short closing sentence. The
// frames are the persona-flavored VARIANT SETS emitted above - one
// flavor per bot, GUID-stable, byte-identical to what the banks
// train (the wording lock; regenerate, never hand-edit).

inline std::string ReplaceAllCopy(std::string text, std::string const& mark,
    std::string const& with)
{
    size_t at = text.find(mark);
    while (at != std::string::npos)
    {
        text.replace(at, mark.size(), with);
        at = text.find(mark, at + with.size());
    }
    return text;
}

// One flavor per bot across every cargo class (persona consistency):
// stable for a bot's lifetime, distinct across a stable of bots. The
// flavor count is the emitted set size - banklib and this header move
// together through the emitter.
inline int CargoFlavor(uint32_t botGuid)
{
    return botGuid % (int)(sizeof(kDebtCargoFrames) / sizeof(kDebtCargoFrames[0]));
}

inline std::string DebtCargo(std::string const& playerName, std::string const& factText,
    uint32_t botGuid)
{
    // {P} before {F}: a fact string carrying a placeholder token stays
    // literal instead of being re-expanded
    return ReplaceAllCopy(
        ReplaceAllCopy(kDebtCargoFrames[CargoFlavor(botGuid)], "{P}", playerName),
        "{F}", FactDirect(factText));
}

inline std::string MemoryCargo(std::string const& playerName, std::string const& factText,
    bool askAfter, uint32_t botGuid)
{
    char const* const* frames = askAfter ? kMemoryAskCargoFrames : kMemoryCargoFrames;
    return ReplaceAllCopy(
        ReplaceAllCopy(frames[CargoFlavor(botGuid)], "{P}", playerName),
        "{F}", FactDirect(factText));
}

inline std::string GrudgeCargo(std::string const& factText, uint32_t botGuid)
{
    return ReplaceAllCopy(kGrudgeCargoFrames[CargoFlavor(botGuid)], "{F}",
        FactDirect(factText));
}

inline std::string NewsCargo(std::string const& playerName, std::string const& factText,
    uint32_t botGuid)
{
    return ReplaceAllCopy(
        ReplaceAllCopy(kNewsCargoFrames[CargoFlavor(botGuid)], "{P}", playerName),
        "{F}", FactDirect(factText));
}

inline std::string GossipCargo(std::string const& playerName, std::string const& gossipText,
    uint32_t botGuid)
{
    // {G} last: the gossip text is model/DB-derived prose
    return ReplaceAllCopy(
        ReplaceAllCopy(kGossipCargoFrames[CargoFlavor(botGuid)], "{P}", playerName),
        "{G}", gossipText);
}

// ---- the tier ceremony (trained wording; never names the mechanic).
inline std::string CeremonyUpCargo(std::string const& playerName, int tier,
    uint32_t botGuid)
{
    char const* const phrase = (tier >= 5)
        ? kCeremonyUpPhrases[2]
        : kCeremonyUpPhrases[(tier == 4) ? 1 : 0];
    return ReplaceAllCopy(
        ReplaceAllCopy(kCeremonyUpFrames[CargoFlavor(botGuid)], "{P}", playerName),
        "{T}", phrase);
}

inline std::string CeremonyDownCargo(std::string const& playerName, uint32_t botGuid)
{
    return ReplaceAllCopy(kCeremonyDownFrames[CargoFlavor(botGuid)], "{P}", playerName);
}

// ---- the long-form licensing layer. The cue above is the
// ONE frozen length signal (the wording lock: the banks train these
// bytes); the bridge appends it to a beat's cargo only when the tier's
// configured max new tokens clears the long bank - the threshold law
// itself (LongFormLicensed) lives in ToolsCore beside the reply budget
// that consumes it. Short tiers never see the cue, so the short default
// is untouched doctrine on them.

// the storytelling ask: an explicit request for a TELLING (the bridge
// still anchors the content to a real shared event - the game is truth;
// no event fact, no storytelling beat). Precision over recall: a false
// positive voices an off-length beat, a false negative is only a short
// reply.
inline bool WantsStorytelling(std::string const& msg)
{
    static char const* const triggers[] = {
        "tell me a story", "tell us a story", "tell me a tale",
        "tell us a tale", "tell the one about", "tell me about the war",
        "tell me about your", "how did you end up", "what happened at",
        "walk me through",
    };
    return ContainsTrigger(msg, triggers, sizeof(triggers) / sizeof(triggers[0]));
}

// the tier-5 Bonded open-confidence shape: a friend asking a friend to
// really talk. Tier-gated (>= 5) and second-person-gated at the caller;
// every trigger phrase carries its own address, so third-person uses
// ("what do you make of this ore?" to someone else) never fire.
inline bool WantsOpenConfidence(std::string const& msg)
{
    static char const* const triggers[] = {
        "how have you been", "how are you doing", "how are things with you",
        "what's on your mind", "tell me about yourself",
    };
    return ContainsTrigger(msg, triggers, sizeof(triggers) / sizeof(triggers[0]));
}

// ---- the cheap system-colored progression line that rides the
// OBSERVED tier crossing (pure DB-derived, zero generation - it survives
// even a governor-dropped ceremony turn). Player-facing surface wording:
// never names the mechanic, never names a tier, ASCII only (the sys-line
// channel predates the clamp and stays plain).
inline std::string TierShiftSysLine(std::string const& botName, bool up)
{
    return botName + (up ? " seems warmer toward you." : " seems colder toward you.");
}

// the Trusted unlock: one backstory secret, GUID-stable, released once
inline std::string BackstorySecretOf(uint32_t botGuid)
{
    static char const* const secrets[] = {
        "keep a letter that has never been sent",
        "have lost a family blade years ago and never say where",
        "send half of every coin earned to someone unnamed",
        "cannot read, and have hidden it for years",
        "once ran from a fight that cost a friend a hand",
        "keep a private tally of every debt, owed and owing",
        "know a hill shortcut shown to no one",
        "were married once, far from here, and do not say what ended it",
    };
    return secrets[botGuid % (sizeof(secrets) / sizeof(secrets[0]))];
}

inline std::string SecretCargo(std::string const& playerName, uint32_t botGuid)
{
    return "You trust " + playerName + " enough for the one thing you keep: you " +
        BackstorySecretOf(botGuid) +
        ". Tell it once, briefly, as your own choice - then let it be.";
}

// ---- the Bonded address shift. Deterministic, host-pinned.
inline std::string NicknameOf(std::string const& playerName, uint32_t botGuid)
{
    if (playerName.size() <= 4)
        return playerName;
    int vowels = 0;
    size_t secondVowel = std::string::npos;
    for (size_t i = 1; i < playerName.size(); ++i)
    {
        if (IsVowel(playerName[i]) && ++vowels == 2)
        {
            secondVowel = i;
            break;
        }
    }
    // npos (no second vowel) must take the 4-char fallback below: npos is
    // unsigned-max, so a bare >= 3 would always win and substr(0, npos - 1)
    // would hand back the whole name as the "private" nickname
    if (secondVowel != std::string::npos && secondVowel >= 3)
        return playerName.substr(0, secondVowel - 1);
    return playerName.substr(0, 4);
}

inline std::string NicknameTierNote(std::string const& playerName, uint32_t botGuid)
{
    return "You have a private name for {player} - \"" +
        NicknameOf(playerName, botGuid) +
        "\" - and it slips out more often than their real name.";
}

// the procedure form (disposition lines are
// ignored, procedures fire): the tier-5 ceremony ADOPTS the nickname in
// the reply itself - the standing tierNote keeps it warm afterwards
inline std::string NicknameAdoptionCargo(std::string const& playerName, uint32_t botGuid)
{
    return "You have taken to calling " + playerName + " by a private name of "
        "your own: " + NicknameOf(playerName, botGuid) +
        ". Use it in this reply - and when it suits you after.";
}

// ---- Phase-4 legends + rumor mill (pure, host-testable). Engine-first:
// no new tables, no new segments — counters, anniversaries, tier beats,
// and POI-biased rumor drift all ride existing fact/gossip rows +
// the in-memory fatigue ledger (tellings/hops/heard, capped 5/3).
//
// Counters → escalating jokes: the Nth telling of the same factKey
// escalates ("again", "still", "legend by now") and retires at the
// 5-telling cap (FatigueAdmits). AnniversaryOf maps days-since-first
// (derived from the fact row's created order — id distance is the
// clock here, no schema change) to milestone buckets: 30/100/365.
// TierBeat frames: the beat bank for the ceremony observations. The
// JOURNAL faces are live (vouch at tier >= 3, bonded bickering at
// tier 5, via TierBeatJournalLine); the second-person CARGO faces
// (TierBeatCargo) are the prompt-side bank - debt-forgiven (kind 1)
// now fires from the plan-v5 trade hook (EVENT_DEBT_SETTLED). Rumor drift
// stays deterministic: DistortGossipHop per hop, cap 3, originator
// verbatim.
inline int AnniversaryBucket(int daysSinceFirst)
{
    if (daysSinceFirst >= 365) return 365;
    if (daysSinceFirst >= 100) return 100;
    if (daysSinceFirst >= 30) return 30;
    return 0;
}

inline char const* AnniversaryLine(int bucket)
{
    switch (bucket)
    {
        case 365: return "A whole year of knowing each other. That is no small thing.";
        case 100: return "A hundred days of crossings. The road keeps bringing you back.";
        case 30: return "A month of knowing each other. Familiar boots by the fire now.";
        default: return "";
    }
}

inline char const* CounterEscalation(int telling)
{
    switch (telling)
    {
        case 1: return "Once more, then.";
        case 2: return "Again - the telling grows.";
        case 3: return "Still the talk. The legend thickens.";
        case 4: return "Practically legend by now.";
        default: return "";
    }
}

inline std::string LegendCounterLine(std::string const& factDirect, int telling)
{
    char const* esc = CounterEscalation(telling);
    if (!*esc) return factDirect;
    return factDirect + " " + esc;
}

inline std::string TierBeatCargo(std::string const& playerName, int kind,
    uint32_t botGuid)
{
    // kind: 0 = vouch (ally warmth), 1 = debt-forgiven, 2 = bonded bickering
    static char const* const frames[3][3] = {
        {
            "You would vouch for {P} without thinking now. Say so, briefly, if it fits.",
            "Something in you has decided: {P} is good folk. Let it show, once, plainly.",
            "{P} has earned your word. Give it, short and warm.",
        },
        {
            "The old debt with {P} is settled and square. The air is lighter for it - say so.",
            "{P} paid what was owed, every copper. Name the squareness; grudges end here.",
            "Square at last with {P}. The count closes. Say it with relief.",
        },
        {
            "You and {P} bicker like old wolves now - sharp, fond, never mean. Snap a little, lovingly.",
            "{P} gets the sharp edge of your tongue because they can take it. Show the fondness under it.",
            "Bonded bickering with {P}: give as good as you get, and grin through it.",
        },
    };
    if (kind < 0 || kind > 2) kind = 0;
    return ReplaceAllCopy(frames[kind][botGuid % 3], "{P}", playerName);
}

// The journal-facing face of the same beats: the player reads the
// journal, so the line observes the relationship instead of instructing
// the bot. ASCII only - the whisper path bypasses the LLM output clamp,
// and the 1.12 client renders anything wider as mojibake bytes.
inline char const* TierBeatJournalLine(int kind)
{
    switch (kind)
    {
        case 2: return "Sharp, fond bickering - the kind only old friends can afford.";
        case 1: return "The old debt is settled square; the air is lighter for it.";
        default: return "Would vouch for you anywhere; that word was earned.";
    }
}

// POI-biased rumor sampling: a gossip row naming a known place travels
// farther (returns true) — the caller's pickers prefer such rows when
// the listener is far from the origin. Place detection is substring
// over the row text against the caller's POI title list (the lore
// index owns the canonical titles; this stays a pure string test).
inline bool RumorNamesPlace(std::string const& rowText,
    char const* const* poiTitles, size_t poiCount)
{
    std::string lower = LowerCopy(rowText);
    for (size_t i = 0; i < poiCount; ++i)
    {
        if (!poiTitles[i] || !*poiTitles[i]) continue;
        std::string title = LowerCopy(poiTitles[i]);
        if (!title.empty() && lower.find(title) != std::string::npos)
            return true;
    }
    return false;
}

// ---- plan v5 W7b: the world-truth furniture helpers (pure). Homeland is
// race-stable; the enemy-ground test is deliberately the OPPOSING FACTION'S
// CAPITAL zones only (city names in zone text, lowercase substring) - a
// broader faction table would need AreaTable flags the 1.12 tree does not
// carry on the zone rows the way later clients do.
inline char const* HomeZoneOfRace(uint32_t race)
{
    switch (race)
    {
        case 1: return "elwynn";
        case 2: return "durotar";
        case 3: return "dun morogh";
        case 4: return "teldrassil";
        case 5: return "tirisfal";
        case 6: return "mulgore";
        case 7: return "dun morogh";
        case 8: return "durotar";
        default: return "";
    }
}

inline bool IsEnemyCapitalZone(std::string const& zoneLower, uint32_t race)
{
    // Horde races read Alliance capitals as enemy ground and vice versa
    bool const horde = race == 2 || race == 5 || race == 6 || race == 8;
    static char const* const allianceCapitals[] = {
        "stormwind", "elwynn", "ironforge", "dun morogh", "darnassus", "teldrassil",
    };
    static char const* const hordeCapitals[] = {
        "orgrimmar", "durotar", "undercity", "tirisfal", "thunder bluff", "mulgore",
    };
    char const* const* list = horde ? allianceCapitals : hordeCapitals;
    size_t const count = horde ? sizeof(allianceCapitals) / sizeof(allianceCapitals[0])
                               : sizeof(hordeCapitals) / sizeof(hordeCapitals[0]);
    for (size_t i = 0; i < count; ++i)
        if (zoneLower.find(list[i]) != std::string::npos)
            return true;
    return false;
}

// ---- authored surface shapes (zero generation cost).
inline std::string AbsenceMagnitudeLine(std::string const& bucket)
{
    if (bucket == "a few hours")
        return "It has been a few hours since you passed this way.";
    if (bucket == "most of a day")
        return "Near a full day since you last passed.";
    if (bucket == "many days")
        return "It has been days since you last passed this way.";
    return "";
}

// plan v5 W5: the bot-curiosity question bank - the third initiative
// class. GUID-stable order per pairing, one ask per question per pairing,
// 30-minute floor between asks; the player's next conversational reply is
// minted as a fact deterministically (the 0.8B answer-capture law: a
// vanished answer is a broken promise). Questions are personal, era-voiced
// and answerable - never quiz questions with right answers.
inline size_t CuriosityQuestionCount()
{
    return 16;
}

inline std::string CuriosityQuestionLine(size_t idx, std::string const& playerName)
{
    static char const* const questions[] = {
        "Where does a road like yours begin, {P}? Mine began with a bad harvest.",
        "What do you do when the rain traps you indoors for days, {P}?",
        "Who taught you your trade, {P}? Mine charged me for the lessons. And the candles.",
        "What is the farthest you have ever walked in one go, {P}?",
        "Do you name your weapons, {P}? I knew a man whose sword was called Regret.",
        "What is the best meal you have had on the road, {P}? I dream about a stew in Lakeshire.",
        "Ever been properly lost, {P}? Not turned around. LOST.",
        "What would you do with a hundred gold, {P}? Asking for a friend. The friend is me.",
        "Where is home, when you finally stop walking, {P}?",
        "What made you take up the sword instead of the plow, {P}?",
        "Do you write letters, {P}? I carry three I will never send.",
        "What scares you, {P}? Properly scares you. You can tell me - I scare easily.",
        "First thing you would do in a city with no guards, {P}?",
        "What is a rule you never break, {P}? I never light a fire in the rain. Keeps me humble.",
        "Who misses you, {P}, while you are out here? Somebody should.",
        "If the road ended tomorrow, {P}, what would you want said of you?",
    };
    if (idx >= sizeof(questions) / sizeof(questions[0]))
        return "";
    return ReplaceAllCopy(questions[idx], "{P}", playerName);
}

inline std::string DebtReminderLine(std::string const& playerName, std::string const& moneyPhrase)
{
    if (moneyPhrase.empty())
        return "That debt of yours still stands, " + playerName + ". I have not forgotten.";
    return "The coin, " + playerName + ": " + moneyPhrase + ". I keep an honest count.";
}

inline std::string GoalAskAfterLine(std::string const& playerName, std::string const& factText)
{
    return "I remember what you were after, " + playerName + ": '" +
        FactDirect(factText) + "'. How goes it?";
}

// ---- money phrase extraction ("five silver") for the debt
// surfaces; empty when the fact carries no closed-class money shape.
inline std::string MoneyPhrase(std::string const& factText)
{
    static char const* const currencies[] = {"silver", "gold", "copper"};
    std::string const lower = LowerCopy(factText);
    for (char const* currency : currencies)
    {
        size_t const cur = lower.find(currency);
        if (cur == std::string::npos)
            continue;
        // walk back over the number word directly before the currency
        size_t end = cur;
        while (end > 0 && isspace(static_cast<unsigned char>(lower[end - 1])))
            --end;
        size_t begin = end;
        while (begin > 0 && isalpha(static_cast<unsigned char>(lower[begin - 1])))
            --begin;
        if (begin < end)
            return factText.substr(begin, end - begin) + " " + currency;
    }
    return "";
}

// ---- distortion-per-hop. One deterministic drift per retelling
// hop: hedged tellings sharpen, money inflates exactly one rung (the
// legend grows the same way every retelling - deterministic beats
// sampler, tricks-C law). The world row stays pristine; a believing
// bot's stored telling drifts. hopSeed identifies the believing bot for
// future transform selection; both current transforms are seed-free.
inline std::string DistortGossipHop(std::string const& text, uint32_t hopSeed)
{
    (void)hopSeed;
    static char const* const rungs[] = {
        "one", "two", "three", "five", "ten", "twenty", "fifty", "hundred",
    };
    static size_t const rungCount = sizeof(rungs) / sizeof(rungs[0]);
    static char const* const currencies[] = {"silver", "gold", "copper"};
    static std::pair<char const*, char const*> const sharpenings[] = {
        {"it is said", "it is known"}, {"some say", "many say"},
        {"they say", "everyone says"}, {"rumor has it", "it is known"},
        {"supposedly", "surely"},
    };

    // sharpening first: it never touches money words. The replacement
    // copies the matched text's first-letter case (mid-sentence hedges
    // stay lowercase, sentence leads stay capitalized).
    for (auto const& swap : sharpenings)
    {
        std::string const from = swap.first;
        std::string const lower = LowerCopy(text);
        size_t const at = lower.find(from);
        if (at != std::string::npos)
        {
            std::string out = text;
            std::string replacement = swap.second;
            if (!replacement.empty() && at < text.size() &&
                isupper(static_cast<unsigned char>(text[at])))
                replacement[0] = (char)std::toupper(static_cast<unsigned char>(replacement[0]));
            out.replace(at, from.size(), replacement);
            return out;
        }
    }

    // money inflation: find "<numword> <currency>" and step the rung up
    for (char const* currency : currencies)
    {
        std::string const lower = LowerCopy(text);
        size_t const cur = lower.find(currency);
        if (cur == std::string::npos)
            continue;
        size_t end = cur;
        while (end > 0 && isspace(static_cast<unsigned char>(lower[end - 1])))
            --end;
        size_t begin = end;
        while (begin > 0 && isalpha(static_cast<unsigned char>(lower[begin - 1])))
            --begin;
        if (begin >= end)
            continue;
        std::string const num(lower.substr(begin, end - begin));
        size_t rung = rungCount;
        for (size_t i = 0; i < rungCount; ++i)
            if (num == rungs[i])
                rung = i;
        if (rung >= rungCount)
            continue; // not a rung word (a name, a made-up count): untouched
        size_t const next = rung + 1 < rungCount ? rung + 1 : rungCount - 1;
        std::string out = text;
        out.replace(begin, end - begin, rungs[next]);
        return out;
    }
    return text;
}

} // namespace pocketllm

#endif
