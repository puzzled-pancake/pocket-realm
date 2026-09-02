#ifndef _PlayerbotLlmToolsCore_h
#define _PlayerbotLlmToolsCore_h

// Pure, host-compilable core of the LLM tool pipeline (A2/A3): the
// `<<tool ...>>` scanner, the queue whitelist, the trained text-emote
// table and the bridge's state-free beat predicates. No core/module
// includes - PlayerbotLlmTools.cpp and PlayerbotLlmBridge.cpp consume
// it in-tree, and the host battery (tools/test_llm_act_tools.cpp)
// compiles it standalone exactly like PlayerbotLlmJson.h, so the
// queue-admission grammar and the emote mapping are pinned by tests
// instead of by inspection (the S3-logged ExtractAndQueue host-battery
// debt, discharged).
#include <cstdint>
#include <cctype>
#include <cstring>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace pocketllm {

// ---- parsed form of one `<<name key="value" ...>>` block
struct ToolCall
{
    std::string name;
    std::vector<std::pair<std::string, std::string>> fields;
};

// The trained tool vocabulary, mirroring banklib.VALID_TOOLS exactly:
// the four persistence tools plus the six ACT tools (A2). Anything else
// a generation emits is protocol leakage, never a queued call.
inline bool IsKnownTool(std::string const& name)
{
    static char const* const known[] = {
        "log_fact", "adjust_sentiment", "share_gossip", "perform_emote",
        "duel_challenge", "give_item", "follow", "party_invite",
        "move_to", "loot_roll",
    };
    for (char const* k : known)
        if (name == k)
            return true;
    return false;
}

// Extracts every `<<tool ...>>` block from raw model output and returns
// the parsed calls; *cleaned receives the dialogue text with the blocks
// (and any stray `>>` residue) removed. Semantics identical to the
// pre-A2 inline scanner (quote-aware terminators, depth tracking for
// nested markers, unterminated-marker drop, stray-`>>` prose cut).
inline std::vector<ToolCall> ExtractToolCalls(std::string const& raw, std::string* cleanedOut)
{
    std::vector<ToolCall> calls;
    std::string cleaned;
    cleaned.reserve(raw.size());

    // prose between and after tool blocks never legitimately contains `>>`:
    // a stray or reversed marker (`Sure. >>log_fact text="x"<<` - the scanner
    // only opens blocks at `<<`) is protocol leakage, so each prose segment
    // ends at its first `>>`; everything past it belongs to the degenerate
    // marker, not to the reply. A segment seam can FUSE a trailing '>' with
    // a leading '>' into a live '>>' (single '>'s are legal prose) - the
    // seam check drops the duplicate so the invariant holds on concatenation
    auto appendProse = [&cleaned](std::string const& s, size_t from, size_t count)
    {
        std::string const seg = s.substr(from, count);
        size_t const stray = seg.find(">>");
        std::string const kept = (stray == std::string::npos) ? seg : seg.substr(0, stray);
        size_t start = 0;
        if (!cleaned.empty() && !kept.empty() &&
            ((cleaned.back() == '>' && kept.front() == '>') ||
             (cleaned.back() == '<' && kept.front() == '<')))
            start = 1;
        cleaned += kept.substr(start);
    };

    size_t pos = 0;
    while (pos < raw.size())
    {
        size_t open = raw.find("<<", pos);
        if (open == std::string::npos)
        {
            appendProse(raw, pos, std::string::npos);
            break;
        }
        // the terminator is the first `>>` outside a quoted value - a quoted
        // `>>` must not truncate the block and leak residue into chat. A
        // nested unquoted `<<` opens a depth counter so its `>>` closes the
        // nest instead of the block. If the depth-aware scan finds nothing
        // (the nest's terminator sat inside a quoted value), a pure
        // quote-aware scan runs so quoted-`>>` delimiting still works.
        size_t close = std::string::npos;
        {
            bool inQuote = false;
            int depth = 0;
            bool nested = false;
            for (size_t scan = open + 2; scan + 1 < raw.size(); ++scan)
            {
                if (raw[scan] == '"')
                    inQuote = !inQuote;
                else if (!inQuote && raw[scan] == '<' && raw[scan + 1] == '<')
                {
                    ++depth;
                    nested = true;
                    ++scan; // consume the second '<' so the pair counts once
                }
                else if (!inQuote && raw[scan] == '>' && raw[scan + 1] == '>')
                {
                    if (depth > 0)
                    {
                        --depth;
                        ++scan; // closes the nested opener, not the block
                        continue;
                    }
                    close = scan;
                    break;
                }
            }
            if (close == std::string::npos && nested)
            {
                bool quote = false;
                for (size_t scan = open + 2; scan + 1 < raw.size(); ++scan)
                {
                    if (raw[scan] == '"')
                        quote = !quote;
                    else if (!quote && raw[scan] == '>' && raw[scan + 1] == '>')
                    {
                        close = scan;
                        break;
                    }
                }
            }
        }
        if (close == std::string::npos)
        {
            // unbalanced quotes confused the scan: fall back to the first
            // plain `>>` so prose following a malformed marker survives
            close = raw.find(">>", open + 2);
        }
        if (close == std::string::npos)
        {
            // unterminated marker (generation cut mid-tool-line): keep the
            // prose before it, drop the partial protocol line entirely
            appendProse(raw, pos, open - pos);
            break;
        }

        appendProse(raw, pos, open - pos);
        std::string block = raw.substr(open + 2, close - open - 2);

        // parse `name key="value with spaces" key2=v2` with a scanner that
        // keeps quoted multi-word values intact
        ToolCall call;
        {
            size_t pos = 0;
            while (pos < block.size() && isspace(static_cast<unsigned char>(block[pos])))
                ++pos; // tolerate a leading space before the tool name
            while (pos < block.size() && !isspace(static_cast<unsigned char>(block[pos])))
            {
                call.name.push_back(block[pos]);
                ++pos;
            }
            auto skipSpace = [&pos, &block]() {
                while (pos < block.size() && isspace(static_cast<unsigned char>(block[pos])))
                    ++pos;
            };
            std::string dangling; // a trailing bare token the key scan drops
            while (true)
            {
                std::string key;
                while (pos < block.size() && block[pos] != '=' && !isspace(static_cast<unsigned char>(block[pos])))
                {
                    key.push_back(block[pos]);
                    ++pos;
                }
                skipSpace();
                if (pos >= block.size())
                {
                    dangling = key;
                    break;
                }
                if (block[pos] != '=' || key.empty())
                {
                    // a stray '=' where a key was expected must be consumed:
                    // continuing without advancing would spin forever
                    if (block[pos] == '=')
                        ++pos;
                    continue;
                }
                ++pos; // '='
                skipSpace();
                std::string value;
                if (pos < block.size() && block[pos] == '"')
                {
                    ++pos;
                    while (pos < block.size())
                    {
                        // a quote only closes the value when a delimiter (or
                        // the end of the block) follows: unescaped inner
                        // quotes stay inside the value instead of truncating
                        // it and smearing the rest as junk keys
                        if (block[pos] == '"' &&
                            (pos + 1 >= block.size() || block[pos + 1] == '=' ||
                                isspace(static_cast<unsigned char>(block[pos + 1]))))
                            break;
                        value.push_back(block[pos]);
                        ++pos;
                    }
                    if (pos < block.size())
                        ++pos; // closing quote
                }
                else
                {
                    while (pos < block.size() && !isspace(static_cast<unsigned char>(block[pos])))
                    {
                        value.push_back(block[pos]);
                        ++pos;
                    }
                }
                call.fields.emplace_back(key, value);
            }
            // the base-model fieldless form `<<perform_emote laugh>>`
            // (prtools rec #1, S6-ledger (j)) parsed name-only: the key
            // scanner dropped the bare token. Fold it into the emote field
            // so the queued call is self-describing. ONLY the pure form
            // normalizes - a single alphabetic token, no other fields - and
            // the executor's field authority is unchanged: the licensed
            // line still decides which emote plays.
            if (call.name == "perform_emote" && call.fields.empty() &&
                !dangling.empty() &&
                dangling.find_first_not_of(
                    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
                    == std::string::npos)
                call.fields.emplace_back("emote", dangling);
        }

        if (!call.name.empty())
            calls.push_back(call);
        pos = close + 2;
    }

    while (!cleaned.empty() && (cleaned.back() == '\n' || cleaned.back() == ' '))
        cleaned.pop_back();
    if (cleanedOut)
        *cleanedOut = cleaned;
    return calls;
}

// ---- A3: the 19 trained emotes as 1.12 TEXT-emote ids (SharedDefines.h
// TEXTEMOTE_*). The values are duplicated here because this header must
// compile on the host, where SharedDefines.h does not exist; a mismatch
// would misroute the emote, so the mapping is pinned by the host battery
// against the same table banklib carries.
enum TextEmoteId : uint32_t
{
    TEXTEMOTE_BOW_CORE      = 17,
    TEXTEMOTE_CHEER_CORE    = 21,
    TEXTEMOTE_CHICKEN_CORE  = 22,
    TEXTEMOTE_CRY_CORE      = 31,
    TEXTEMOTE_DANCE_CORE    = 34,
    TEXTEMOTE_FLEX_CORE     = 41,
    TEXTEMOTE_GLARE_CORE    = 46,
    TEXTEMOTE_GRIN_CORE     = 49,
    TEXTEMOTE_HUG_CORE      = 56,
    TEXTEMOTE_KISS_CORE     = 58,
    TEXTEMOTE_LAUGH_CORE    = 60,
    TEXTEMOTE_NO_CORE       = 66,
    TEXTEMOTE_NOD_CORE      = 67,
    TEXTEMOTE_POINT_CORE    = 72,
    TEXTEMOTE_RUDE_CORE     = 77,
    TEXTEMOTE_SALUTE_CORE   = 78,
    TEXTEMOTE_SHRUG_CORE    = 83,
    TEXTEMOTE_WAVE_CORE     = 101,
    TEXTEMOTE_WHISTLE_CORE  = 104,
};

// Resolves an emote= value to a 1.12 TEXTEMOTE id: the 19 trained names
// first, then the measured off-whitelist fuzzy map (prtools: frown->no
// and close kin; grin/shrug themselves are whitelisted now), then light
// morphology (plural/gerund prefix containment). 0 = unresolvable, which
// the executor treats as "play nothing" (never guess an animation).
inline uint32_t ResolveTextEmote(std::string emote)
{
    for (char& c : emote)
        c = (char)std::tolower(static_cast<unsigned char>(c));
    while (!emote.empty() && isspace(static_cast<unsigned char>(emote.back())))
        emote.pop_back();

    static std::map<std::string, uint32_t> const allowed = {
        {"wave", TEXTEMOTE_WAVE_CORE}, {"bow", TEXTEMOTE_BOW_CORE},
        {"salute", TEXTEMOTE_SALUTE_CORE}, {"laugh", TEXTEMOTE_LAUGH_CORE},
        {"cry", TEXTEMOTE_CRY_CORE}, {"nod", TEXTEMOTE_NOD_CORE},
        {"no", TEXTEMOTE_NO_CORE}, {"point", TEXTEMOTE_POINT_CORE},
        {"cheer", TEXTEMOTE_CHEER_CORE}, {"dance", TEXTEMOTE_DANCE_CORE},
        {"flex", TEXTEMOTE_FLEX_CORE}, {"kiss", TEXTEMOTE_KISS_CORE},
        {"rude", TEXTEMOTE_RUDE_CORE}, {"grin", TEXTEMOTE_GRIN_CORE},
        {"shrug", TEXTEMOTE_SHRUG_CORE}, {"chicken", TEXTEMOTE_CHICKEN_CORE},
        {"whistle", TEXTEMOTE_WHISTLE_CORE}, {"glare", TEXTEMOTE_GLARE_CORE},
        {"hug", TEXTEMOTE_HUG_CORE},
    };
    auto itr = allowed.find(emote);
    if (itr != allowed.end())
        return itr->second;

    // measured fuzzy map for values the models invent off-whitelist
    static std::map<std::string, std::string> const aliases = {
        {"frown", "no"}, {"yes", "nod"}, {"sob", "cry"}, {"weep", "cry"},
        {"smile", "grin"}, {"chuckle", "laugh"}, {"giggle", "laugh"},
        {"snicker", "laugh"}, {"hello", "wave"}, {"goodbye", "wave"},
        {"bye", "wave"}, {"applaud", "cheer"}, {"clap", "cheer"},
        {"agree", "nod"}, {"mad", "glare"}, {"angry", "glare"},
    };
    auto alias = aliases.find(emote);
    if (alias != aliases.end())
    {
        auto mapped = allowed.find(alias->second);
        if (mapped != allowed.end())
            return mapped->second;
    }

    // light morphology, no stemmer: strip a plural s and retry exact
    // ("bows" -> "bow", "waves" -> "wave"); for the longer names also
    // accept a plain inflection suffix ("laughs"/"laughed"/"laughing").
    // Gerunds of the short names (nodding/waving drop a vowel) stay
    // unresolvable rather than guessed - 0 means "play nothing"
    std::string const stem = (!emote.empty() && emote.back() == 's')
        ? emote.substr(0, emote.size() - 1) : emote;
    auto stemmed = allowed.find(stem);
    if (stemmed != allowed.end())
        return stemmed->second;
    if (emote.size() >= 5)
    {
        static char const* const suffixes[] = { "es", "ed", "ing" };
        for (auto const& entry : allowed)
        {
            std::string const& name = entry.first;
            if (name.size() < 4)
                continue;
            if (emote.compare(0, name.size(), name) != 0)
                continue;
            std::string const rest = emote.substr(name.size());
            for (char const* suf : suffixes)
                if (rest == suf)
                    return entry.second;
        }
    }
    return 0;
}

// ---- bridge beat predicates (A2/A6): the state-free trigger layer of
// PlayerbotLlmBridge::BuildNote. State gates (verified-event window,
// first-meeting, relationship tier) stay in the bridge - these answer
// only "what is the player asking for / doing in this text".
inline bool ContainsAnyLower(std::string const& text, char const* const* list, size_t count)
{
    std::string lower;
    lower.reserve(text.size());
    for (char c : text)
        lower.push_back((char)std::tolower((unsigned char)c));
    for (size_t i = 0; i < count; ++i)
        if (lower.find(list[i]) != std::string::npos)
            return true;
    return false;
}

// ---- S7 negation guard (the S6-logged idiom class): "don't duel me"
// fired the duel beat - fail-closed at the executor but voiced wrong.
// A trigger hit is NEGATED when a negator ends within the 24 bytes
// before it with only whitespace between ("don't just stand there, duel
// me" is not negated - the comma breaks the window).
inline bool HitNegated(std::string const& lower, size_t hit)
{
    static char const* const negators[] = {
        "don't ", "dont ", "do not ", "won't ", "wont ", "never ",
        "not gonna ", "not going to ", "stop ", "no more ", "no need to ",
        "no need ", "rather not ",
    };
    size_t const windowBegin = hit > 24 ? hit - 24 : 0;
    for (char const* neg : negators)
    {
        size_t const len = strlen(neg);
        for (size_t at = lower.rfind(neg, hit); at != std::string::npos && at + len <= hit;
             at = (at ? lower.rfind(neg, at - 1) : std::string::npos))
        {
            if (at < windowBegin)
                break;
            bool gapClean = true;
            for (size_t i = at + len; i < hit; ++i)
                if (!isspace(static_cast<unsigned char>(lower[i])))
                    gapClean = false;
            if (gapClean)
                return true;
            if (at == 0)
                break;
        }
    }
    return false;
}

// Earliest un-negated hit of any trigger (npos when none): the idiom the
// turn actually carries.
inline size_t EarliestHit(std::string const& lower, char const* const* list, size_t count)
{
    size_t best = std::string::npos;
    for (size_t i = 0; i < count; ++i)
    {
        size_t at = lower.find(list[i]);
        while (at != std::string::npos)
        {
            if (!HitNegated(lower, at) && (best == std::string::npos || at < best))
                best = at;
            at = lower.find(list[i], at + 1);
        }
    }
    return best;
}

inline bool ContainsTrigger(std::string const& text, char const* const* list, size_t count)
{
    std::string lower;
    lower.reserve(text.size());
    for (char c : text)
        lower.push_back((char)std::tolower((unsigned char)c));
    return EarliestHit(lower, list, count) != std::string::npos;
}

enum ConversationalBeat
{
    BEAT_NONE = 0,
    BEAT_DUEL,          // player challenges the bot        -> duel_challenge
    BEAT_GIVE_ITEM,     // player asks the bot for an item  -> give_item
    BEAT_FOLLOW,        // player asks the bot along        -> follow
    BEAT_PARTY_INVITE,  // player asks into the group       -> party_invite
    BEAT_MOVE_TO,       // player asks to be led somewhere  -> move_to (S7/A11)
};

inline bool WantsDuel(std::string const& msg)
{
    // "fight me" is deliberately absent: ordinary narration uses it in the
    // third person ("the bards fight me for the story rights") and a wrong
    // license voices a challenge the player never made
    static char const* const triggers[] = {
        "duel me", "let's duel", "lets duel", "care for a duel",
        "i challenge you", "want to duel",
    };
    return ContainsTrigger(msg, triggers, sizeof(triggers) / sizeof(triggers[0]));
}

// The insult beat's second-person gate (S5-logged): "this sword is
// trash" insults an OBJECT, not the bot, and fired -1 with the wrong
// attribution. An insult beat needs the bot addressed - a second-person
// word or the bot's own name somewhere in the turn.
inline bool IsSecondPerson(std::string const& msg, std::string const& botName)
{
    std::string lower;
    lower.reserve(msg.size());
    for (char c : msg)
        lower.push_back((char)std::tolower(static_cast<unsigned char>(c)));
    if (lower.find("you") != std::string::npos)
        return true;
    if (!botName.empty())
    {
        std::string name;
        name.reserve(botName.size());
        for (char c : botName)
            name.push_back((char)std::tolower(static_cast<unsigned char>(c)));
        return lower.find(name) != std::string::npos;
    }
    return false;
}

// Extracts the item noun the player is asking the bot to hand over
// ("give me a hammer for the job" -> "hammer"; "hand me that rough
// bronze hammer" -> "rough bronze hammer", capped at three words);
// false when no ask is present or nothing noun-like follows it.
// Bridge-decided: a wrong extraction can only fail the executor's
// inventory lookup, never trade something unasked.
inline bool ExtractGiftItem(std::string const& msg, std::string* itemOut)
{
    static char const* const triggers[] = {
        "give me", "hand me", "pass me", "lend me",
        "can i have", "could i have", "may i have", "let me have",
        "i could use", "i'll take", "i will take",
    };
    std::string lower;
    lower.reserve(msg.size());
    for (char c : msg)
        lower.push_back((char)std::tolower(static_cast<unsigned char>(c)));

    // earliest UN-NEGATED trigger wins ("don't give me that look, give me
    // the hammer" extracts the hammer); the item phrase starts right
    // after it
    size_t at = std::string::npos;
    size_t len = 0;
    for (char const* trigger : triggers)
    {
        size_t hit = lower.find(trigger);
        while (hit != std::string::npos)
        {
            if (!HitNegated(lower, hit) &&
                (at == std::string::npos || hit < at))
            {
                at = hit;
                len = std::char_traits<char>::length(trigger);
            }
            hit = lower.find(trigger, hit + 1);
        }
    }
    if (at == std::string::npos)
        return false;

    static char const* const fillers[] = {
        "a", "an", "the", "my", "your", "one", "some", "that", "this",
        "of",
    };
    static char const* const stops[] = {
        "for", "to", "from", "and", "or", "so", "please", "then", "if",
        "before", "after", "when", "while", "with", "on", "off", "about",
        "at", "in", "into", "over",
    };
    // abstract nouns of speech/time: an ask phrasing + one of these is an
    // idiom ("give me a moment", "i could use a hand", "lend me your
    // ears"), never an item hand-over - the note would voice a hand-over
    // the executor then refuses
    static char const* const abstracts[] = {
        "moment", "minute", "second", "word", "words", "hand", "hands",
        "break", "opinion", "advice", "favor", "favour", "chance",
        "rest", "ears", "ear", "guess", "hint", "clue", "look",
    };
    auto isWord = [](std::string const& w, char const* const* list, size_t count) {
        for (size_t i = 0; i < count; ++i)
            if (w == list[i])
                return true;
        return false;
    };

    std::string item;
    size_t pos = at + len;
    int taken = 0;
    while (pos < msg.size() && taken < 3)
    {
        while (pos < msg.size() && isspace(static_cast<unsigned char>(msg[pos])))
            ++pos;
        size_t end = pos;
        while (end < msg.size() && isalpha(static_cast<unsigned char>(msg[end])))
            ++end;
        if (end == pos)
            break; // punctuation or end of turn ends the phrase
        std::string word = msg.substr(pos, end - pos);
        for (char& c : word)
            c = (char)std::tolower(static_cast<unsigned char>(c));
        if (isWord(word, stops, sizeof(stops) / sizeof(stops[0])))
            break;
        if (isWord(word, fillers, sizeof(fillers) / sizeof(fillers[0])))
        {
            pos = end;
            continue;
        }
        // ANY word of the phrase, plural-stemmed: "a few minutes",
        // "a second chance" are idioms too (round-2 P2), not item asks
        bool abstract = isWord(word, abstracts, sizeof(abstracts) / sizeof(abstracts[0]));
        if (!abstract && word.size() > 1 && word.back() == 's')
            abstract = isWord(word.substr(0, word.size() - 1), abstracts,
                sizeof(abstracts) / sizeof(abstracts[0]));
        if (abstract)
            return false;
        if (!item.empty())
            item += " ";
        item += word;
        ++taken;
        pos = end;
    }
    if (itemOut)
        *itemOut = item;
    return !item.empty();
}

inline bool WantsGiveItem(std::string const& msg)
{
    std::string item;
    return ExtractGiftItem(msg, &item);
}

inline bool WantsFollow(std::string const& msg)
{
    // bare "stay close" is dropped (idiomatic: "stay close to the fire");
    // execution is additionally master-gated so the worst case is the bot
    // following its own master - which it does by default anyway
    static char const* const triggers[] = {
        "follow me", "come with me", "walk with me",
    };
    return ContainsTrigger(msg, triggers, sizeof(triggers) / sizeof(triggers[0]));
}

inline bool WantsPartyInvite(std::string const& msg)
{
    // the join forms require GROUP context: "can i join you for a drink" is
    // an idiom, "can i join your group" is a request. The substring forms
    // cover the can/may/let-me/mind-if variants of each join phrase.
    static char const* const triggers[] = {
        "invite me", "add me",
        "join your group", "join the group",
        "join your party", "join the party",
        "join the raid",
    };
    return ContainsTrigger(msg, triggers, sizeof(triggers) / sizeof(triggers[0]));
}

// Extracts the destination of a lead-me-there ask ("take me to the
// deadmines" -> "the deadmines"): up to four words after the trigger,
// stopped by prepositions/verbs, trailing punctuation trimmed. POI
// RESOLUTION is bridge-side (lore state); this is the raw phrase.
inline bool ExtractMovePlace(std::string const& msg, std::string* placeOut)
{
    static char const* const triggers[] = {
        "take me to", "lead me to", "walk me to", "go to", "show me to",
        "point me to", "send me to",
    };
    std::string lower;
    lower.reserve(msg.size());
    for (char c : msg)
        lower.push_back((char)std::tolower((unsigned char)c));
    size_t const at = EarliestHit(lower, triggers, sizeof(triggers) / sizeof(triggers[0]));
    if (at == std::string::npos)
        return false;
    // find the actual trigger length that matched at `at`
    size_t trigLen = 0;
    for (char const* t : triggers)
    {
        size_t const l = strlen(t);
        if (lower.compare(at, l, t) == 0)
        {
            trigLen = l;
            break;
        }
    }
    if (!trigLen)
        return false;

    static char const* const stops[] = {
        "and", "or", "so", "then", "please", "if", "before", "after",
        "when", "while", "with",
    };
    std::string place;
    size_t pos = at + trigLen;
    int taken = 0;
    while (pos < msg.size() && taken < 4)
    {
        while (pos < msg.size() && isspace(static_cast<unsigned char>(msg[pos])))
            ++pos;
        size_t end = pos;
        while (end < msg.size() && isalpha(static_cast<unsigned char>(msg[end])))
            ++end;
        if (end == pos)
            break; // punctuation or end of turn ends the place phrase
        std::string word = msg.substr(pos, end - pos);
        for (char& c : word)
            c = (char)std::tolower((unsigned char)c);
        bool stop = false;
        for (char const* s : stops)
            if (word == s)
                stop = true;
        if (stop)
            break;
        if (!place.empty())
            place += " ";
        place += word;
        ++taken;
        pos = end;
    }
    if (placeOut)
        *placeOut = place;
    return !place.empty();
}

inline bool WantsMoveTo(std::string const& msg)
{
    std::string place;
    return ExtractMovePlace(msg, &place);
}

// The conversational ACT beats in priority order: an explicit actionable
// request outranks the sentiment beats that may share the turn ("duel
// me, you pig-iron fool" - the challenge is the ask; the sentiment can
// ride a later turn). move_to sits LAST: "follow me to the inn" is a
// follow, and a place-naming ask that resolves to no POI falls through
// to the rest of the ladder. BEAT_NONE leaves the turn to the S5 beats.
inline ConversationalBeat SelectConversationalBeat(std::string const& msg)
{
    if (WantsDuel(msg))
        return BEAT_DUEL;
    if (WantsGiveItem(msg))
        return BEAT_GIVE_ITEM;
    if (WantsFollow(msg))
        return BEAT_FOLLOW;
    if (WantsPartyInvite(msg))
        return BEAT_PARTY_INVITE;
    if (WantsMoveTo(msg))
        return BEAT_MOVE_TO;
    return BEAT_NONE;
}

// ---- S9/E1 per-class voice budgets. Reply classes: 0 = conversational
// (whisper-class: whispers plus the party/raid/say hard-trigger replies
// that share the pipeline - at most TWO lines of 160 bytes, a note, not
// an essay), 1 = ambient (autonomous RPG chatter: ONE line of 80). The
// 255 splitter cap stays the hard channel bound; this is the voice
// budget layered on top, after every hygiene filter (mandated recall
// beats land inside one note by construction - the A13 cargo shapes are
// single-reply). S11 long-form: a CUE-BEARING conversational turn
// (longFormCued - the generation's own note carried the frozen
// long-form cue) on a tier whose max new tokens clear the long bank may
// run to the splitter's own line budget - 150 words is ~900 bytes, 3-4
// say lines at the 255 channel cap (plan §5.1: mechanically fine,
// pacing-budgeted). The widening is EARNED PER TURN, never tier-wide:
// a plain conversational turn keeps 2 x 160 on every tier, and ambient
// stays 1 x 80 everywhere. Over-budget lines truncate with the same
// UTF-8 backoff the splitter uses so a line never ends mid-character;
// over-budget lines DROP (bounded loss; the licensed tool lines were
// already extracted upstream of the line pipeline, so only voiced prose
// can be cut).

// S11 P50/P51 long-form licensing (the threshold law; the cue string
// itself lives in RecallCore's emitted block). A tier whose configured
// max new tokens clears the long bank (150 words ~= 225 tokens at the
// measured ~1.5 tok/word) may carry cue-bearing tellings; short tiers
// never see the cue and never earn the wider reply budget below.
inline bool LongFormLicensed(unsigned int maxNewTokens)
{
    return maxNewTokens >= 225;
}

inline size_t ReplyBudgetBytes(uint32_t replyClass, unsigned int maxNewTokens,
    bool longFormCued)
{
    if (replyClass == 1)
        return 80;
    return (longFormCued && LongFormLicensed(maxNewTokens)) ? 255 : 160;
}

inline size_t ReplyBudgetLines(uint32_t replyClass, unsigned int maxNewTokens,
    bool longFormCued)
{
    if (replyClass == 1)
        return 1;
    return (longFormCued && LongFormLicensed(maxNewTokens)) ? 4 : 2;
}

inline std::string ClampLineBytes(std::string const& line, size_t maxBytes)
{
    if (line.size() <= maxBytes)
        return line;
    size_t cut = maxBytes;
    // never cut inside a multibyte sequence, nor right after an orphaned
    // lead byte (the splitter's backoff, one implementation here so the
    // pure core stays free of core-tree includes)
    while (cut > 0 && ((unsigned char)line[cut - 1] & 0xC0) == 0x80)
        --cut;
    if (cut > 0 && ((unsigned char)line[cut - 1] & 0xC0) == 0xC0)
        --cut;
    return line.substr(0, cut);
}

inline void ApplyReplyBudget(std::vector<std::string>& lines, uint32_t replyClass,
    unsigned int maxNewTokens, bool longFormCued)
{
    size_t const maxLines = ReplyBudgetLines(replyClass, maxNewTokens, longFormCued);
    size_t const maxBytes = ReplyBudgetBytes(replyClass, maxNewTokens, longFormCued);
    for (size_t i = 0; i < lines.size(); ++i)
    {
        if (i < maxLines)
            lines[i] = ClampLineBytes(lines[i], maxBytes);
        else
        {
            lines.resize(maxLines);
            return;
        }
    }
}

} // namespace pocketllm

#endif
