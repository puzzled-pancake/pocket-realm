#ifndef _PlayerbotLlmTruthCore_h
#define _PlayerbotLlmTruthCore_h

// Pure, host-compilable core of the S7 truth guards (plan §3):
//   A10  stakes-scoped entity guard - candidate proper-noun extraction,
//        question/stakes shapes and the FROZEN directive wording
//   A11  lore card index (jsonl load, keyword scoring, POI resolution) +
//        the corrected era lists (always-ban terms vs context-allow
//        sense-phrases) + the era lint over shipped cards
//   A12  post-generation hygiene - 5-gram prompt-leak detector, marker
//        terms, markdown stripper, ASCII clamp, /say cap splitter,
//        Jaccard dedupe, self-initiated-invention sentence scan, and the
//        strict-UTF-8 request-side sanitizer
// No core/module includes - PlayerbotLlmBridge.cpp, PlayerbotLlmTools.cpp,
// PlayerbotLlmMemory.cpp, PlayerbotLlmFilters.cpp and the host battery
// (tools/test_llm_truth.cpp) all compile it standalone, so the guard
// grammar, the retrieval scoring and every filter are pinned by tests
// instead of by inspection.
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "PlayerbotLlmJson.h"

namespace pocketllm {

inline std::string LowerAscii(std::string const& s)
{
    std::string out;
    out.reserve(s.size());
    for (char c : s)
        out.push_back((char)std::tolower((unsigned char)c));
    return out;
}

// Light plural folding so "night elves" retrieves the "night elf" card
// and "cities" matches "city" (no full stemmer - retrieval only).
inline std::string FoldWord(std::string w)
{
    if (w.size() > 4 && w.compare(w.size() - 3, 3, "ies") == 0)
        return w.substr(0, w.size() - 3) + "y";
    if (w.size() > 3 && w.compare(w.size() - 3, 3, "ves") == 0)
        return w.substr(0, w.size() - 3) + "f";
    if (w.size() > 3 && w.back() == 's' &&
        w.compare(w.size() - 2, 2, "ss") != 0)
        return w.substr(0, w.size() - 1);
    return w;
}

inline std::string FoldPhrase(std::string const& phrase)
{
    std::vector<std::string> out;
    std::string cur;
    for (size_t i = 0; i <= phrase.size(); ++i)
    {
        char const c = i < phrase.size() ? phrase[i] : ' ';
        if (std::isalpha((unsigned char)c))
            cur.push_back((char)std::tolower((unsigned char)c));
        else if (!cur.empty())
        {
            out.push_back(FoldWord(cur));
            cur.clear();
        }
    }
    std::string joined;
    for (size_t i = 0; i < out.size(); ++i)
    {
        if (i)
            joined += " ";
        joined += out[i];
    }
    return joined;
}

// Tokenizes into lowercase alpha runs for retrieval and Jaccard work.
inline std::vector<std::string> WordTokens(std::string const& text)
{
    std::vector<std::string> out;
    std::string cur;
    for (size_t i = 0; i <= text.size(); ++i)
    {
        char const c = i < text.size() ? text[i] : ' ';
        if (std::isalpha((unsigned char)c))
            cur.push_back((char)std::tolower((unsigned char)c));
        else if (!cur.empty())
        {
            out.push_back(cur);
            cur.clear();
        }
    }
    return out;
}

// ------------------------------------------------------------------ A10 ----

// The A10 directive, FROZEN before any v2.3 P45 authoring (plan §5 wording
// lock): the bank must train exactly the distribution the bridge injects,
// so this template may not drift. The [BRIDGE AI] prefix and the
// "(For this reply only...)" footer are compose() furniture - this is the
// note body only. cls is exactly one of "person" / "place" / "thing".
inline std::string GuardDirective(std::string const& entity, std::string const& cls)
{
    return "You have never heard of " + entity + " - no such " + cls +
           " trades or lives here. Tell him plainly you do not know the "
           "name, and ask what he means.";
}

// One candidate entity extracted from the player's turn.
struct GuardCandidate
{
    std::string name;   // "Marshal Redwyn" / "Emerald Chalice of Lakeshire"
    std::string cls;    // "person" / "place" / "thing"
    bool eraTerm = false; // an always-ban era word/phrase, not a proper noun

    GuardCandidate() {}
    GuardCandidate(std::string const& n, std::string const& c, bool era)
        : name(n), cls(c), eraTerm(era) {}
};

// Sentence-initial tokens that are capitals only by grammar, never names.
// Mid-sentence capitalized tokens are the extraction signal; the first
// word of a turn is admitted only when a second capitalized token follows
// (so "What did Guildmaster Torbin say" still yields "Guildmaster Torbin"
// via its mid-sentence run, while "What do you charge" yields nothing).
inline bool IsGuardStopword(std::string const& word)
{
    static char const* const stops[] = {
        "I", "You", "He", "She", "It", "We", "They", "The", "A", "An",
        "My", "Your", "His", "Her", "Our", "Their", "This", "That",
        "What", "Where", "Who", "Why", "When", "How", "Which", "Whose",
        "Is", "Are", "Was", "Were", "Do", "Does", "Did", "Can", "Could",
        "Would", "Will", "Should", "Have", "Has", "Had", "Any", "And",
        "But", "So", "If", "No", "Not", "Or", "As", "At", "In", "On",
        "To", "For", "Of", "With", "There", "Here", "Then", "Now",
        "Tell", "Say", "One", "Two", "Three", "Fine", "Well", "Aye",
        "Yes", "Okay", "Ok", "Hey", "Hi", "Hello", "Thanks", "Thank",
        "Please", "Maybe", "Never", "Always", "Just", "Let", "Lets",
        "Also", "Another", "Some", "Each", "Every", "All", "Both",
        // interjection heads: mid-sentence capitals that are speech, not
        // names ("I see, Listen, where is Dughan?" guards Dughan only)
        "Listen", "Look", "Sure", "Wait", "Hush", "Bah", "Mate",
        "Friend", "Stranger", "Lass", "Sir", "Madam", "Come", "Think",
        "Remember", "Forget", "Imagine", "Beg", "Pardon",
        // greeting heads: two-capital openers are grammar, not names
        // ("Good Morning, where is the inn?")
        "Good", "Many", "Merry", "Happy", "Fair", "Long", "Well",
    };
    for (char const* stop : stops)
        if (word == stop)
            return true;
    return false;
}

inline bool IsCapitalizedWord(std::string const& w)
{
    if (w.size() < 2 || !std::isalpha((unsigned char)w[0]) ||
        !std::isupper((unsigned char)w[0]))
        return false;
    for (size_t i = 1; i < w.size(); ++i)
        // one apostrophe class mid-word is a name shape ("Kel'Thuzad")
        if (!std::isalpha((unsigned char)w[i]) && w[i] != '\'')
            return false;
    return true;
}

// Folds ALL-CAPS shouting to Capital case ("GOLDSHIRE" -> "Goldshire"):
// resolution downstream is case-insensitive, but the directive text and
// the licensed name fields should read like names, not shouts.
inline std::string NormalizeCaps(std::string const& w)
{
    bool allCaps = w.size() >= 3;
    for (char c : w)
        allCaps = allCaps && std::isupper((unsigned char)c) != 0;
    if (!allCaps)
        return w;
    std::string out = w;
    for (size_t i = 1; i < out.size(); ++i)
        out[i] = (char)std::tolower((unsigned char)out[i]);
    return out;
}

// "redwyn" -> "Redwyn": first letter up, rest down (display casing for
// title-anchored lowercase names).
inline std::string Capitalize(std::string const& w)
{
    if (w.empty())
        return w;
    std::string out;
    out.push_back((char)std::toupper((unsigned char)w[0]));
    for (size_t i = 1; i < w.size(); ++i)
        out.push_back((char)std::tolower((unsigned char)w[i]));
    return out;
}

// lowercase connectors that keep a name run going: "Chalice of Lakeshire"
inline bool IsNameConnector(std::string const& w)
{
    return w == "of" || w == "the" || w == "de" || w == "von" || w == "al";
}

// Words that never continue a name run when absorbing the lowercase tail
// of a compound thing ("Stormwind falconer badge" absorbs "falconer" and
// "badge", but "Westfall tomorrow" stops at the adverb and "Northvale
// Tower doubled" stops at the participle).
inline bool AbsorbsIntoName(std::string const& w)
{
    static char const* const stops[] = {
        "in", "on", "at", "to", "for", "from", "with", "and", "or", "but",
        "is", "are", "was", "were", "do", "does", "did", "sell", "sells",
        "charge", "cost", "me", "you", "it", "that", "this", "again",
        "yet", "about", "around", "here", "there", "when", "where", "who",
        "what", "how", "tomorrow", "today", "tonight", "please", "maybe",
        "right", "now", "still", "just", "anywhere", "everywhere", "if",
        "while", "before", "after", "over", "under", "near", "past",
        "say", "says", "said", "tell", "tells", "told", "know", "knows",
        "knew", "think", "thinks", "come", "comes", "came", "going",
        "gone", "getting", "got", "give", "gives", "gave", "take",
        "takes", "took", "doubled", "called", "named", "seen", "saw",
        "watch", "watched", "open", "opened", "close", "closed", "keep",
        "kept", "held", "hold", "sold", "buy", "bought", "trade",
        "traded", "meet", "met", "live", "lives", "lived", "stay",
        "stays", "his", "her", "their", "our", "your", "my",
    };
    for (char const* s : stops)
        if (w == s)
            return false;
    return w.size() >= 3;
}

// Class word for the directive: title words imply people, place suffixes
// imply places, everything else is a thing.
inline std::string GuardClassForName(std::string const& name)
{
    static char const* const titles[] = {
        "marshal", "guildmaster", "captain", "master", "smith",
        "innkeeper", "guard", "lord", "lady", "sir", "sergeant",
        "brother", "sister", "father", "mother", "general", "king",
        "queen", "champion", "trainer", "magistrate", "clergyman",
        "archmage", "magus", "knight", "commander", "legate",
    };
    static char const* const places[] = {
        "tower", "abbey", "castle", "keep", "inn", "tavern", "village",
        "town", "city", "bridge", "mill", "mine", "cavern", "cave",
        "gate", "house", "hall", "port", "dock", "spire", "fort",
        "isle", "island", "valley", "forest", "swamp", "field", "hold",
        "basin", "bay", "shrine", "monastery", "citadel", "cathedral",
        "arena", "camp", "outpost", "watchtower", "lighthouse",
    };
    std::string lower;
    lower.reserve(name.size());
    for (char c : name)
        lower.push_back((char)std::tolower((unsigned char)c));
    for (char const* t : titles)
        if (lower.find(t) != std::string::npos)
            return "person";
    for (char const* p : places)
    {
        // word-boundary suffix match ("Inn" but not "Finn"-style middles)
        size_t at = lower.rfind(p);
        if (at != std::string::npos &&
            (at + strlen(p) == lower.size() || lower[at + strlen(p)] == ' '))
            return "place";
    }
    return "thing";
}

// Extracts candidate entities from the player's message: maximal runs of
// capitalized words (plus lowercase connectors) that are not pure grammar
// capitals, and quoted names anywhere. ALL-CAPS shouting is folded to
// capital case (players shout zone names); resolution stays
// case-insensitive downstream either way.
inline std::vector<GuardCandidate> ExtractGuardEntities(std::string const& msg)
{
    std::vector<GuardCandidate> out;

    // tokenize: letters plus INNER apostrophes ("Kel'Thuzad" one token,
    // "'Fionna'" strips its quotes); quote-wrapped tokens are flagged so
    // a name survives even in sentence-initial position
    struct Tok
    {
        std::string word;   // raw
        bool quoted;        // wrapped in ' or "
    };
    std::vector<Tok> toks;
    {
        std::string cur;
        bool quoted = false;
        for (size_t i = 0; i <= msg.size(); ++i)
        {
            char const c = i < msg.size() ? msg[i] : ' ';
            bool wordChar = std::isalpha((unsigned char)c) != 0;
            if (c == '\'' && !cur.empty() && i + 1 < msg.size() &&
                std::isalpha((unsigned char)msg[i + 1]))
                wordChar = true; // inner apostrophe
            if (wordChar)
            {
                if (cur.empty() && i > 0 && (msg[i - 1] == '\'' || msg[i - 1] == '"'))
                    quoted = true;
                cur.push_back(c);
            }
            else if (!cur.empty())
            {
                if (!(quoted && (c == '\'' || c == '"')))
                    quoted = false;
                toks.push_back(Tok{ cur, quoted });
                cur.clear();
            }
        }
    }

    // sentence-initial positions: token 0 and any token after . ! ?
    std::vector<bool> sentenceStart(toks.size(), false);
    {
        bool atStart = true;
        size_t consumed = 0; // chars consumed by tokens so far
        for (size_t i = 0; i < toks.size(); ++i)
        {
            size_t next = msg.find(toks[i].word, consumed);
            if (next == std::string::npos)
                next = consumed;
            for (size_t j = consumed; j < next; ++j)
                if (msg[j] == '.' || msg[j] == '!' || msg[j] == '?')
                    atStart = true;
            sentenceStart[i] = atStart;
            atStart = false;
            consumed = next + toks[i].word.size();
        }
    }

    for (size_t i = 0; i < toks.size();)
    {
        Tok const& t = toks[i];
        if (t.word.size() < 3)
        {
            ++i;
            continue;
        }
        bool const cap = IsCapitalizedWord(NormalizeCaps(t.word));
        if (!cap && !t.quoted)
        {
            ++i;
            continue;
        }
        // coalesce the run: capitalized words + lowercase connectors,
        // then (thing compounds) a short lowercase noun tail
        std::string name = NormalizeCaps(t.word);
        if (t.quoted)
            name = Capitalize(t.word);
        size_t j = i + 1;
        int capWords = 1;
        while (j < toks.size())
        {
            bool const conn = IsNameConnector(toks[j].word);
            bool const nextCap = j + 1 < toks.size() &&
                IsCapitalizedWord(NormalizeCaps(toks[j + 1].word));
            if (IsCapitalizedWord(NormalizeCaps(toks[j].word)))
            {
                name += " " + NormalizeCaps(toks[j].word);
                ++capWords;
                ++j;
            }
            else if (conn && nextCap)
            {
                name += " " + toks[j].word;
                ++j;
            }
            else
                break;
        }
        int absorbed = 0;
        while (j < toks.size() && absorbed < 2 &&
               !IsCapitalizedWord(NormalizeCaps(toks[j].word)) &&
               !IsNameConnector(toks[j].word) &&
               AbsorbsIntoName(toks[j].word))
        {
            name += " " + toks[j].word;
            ++j;
            ++absorbed;
        }
        // sentence-initial acceptance: grammar capitalizes ANY first
        // word ("Listen, where is Dughan?"), so a turn-opening run is a
        // candidate only when it is quoted or carries a SECOND
        // capitalized word ("Marshal Redwyn, I said")
        if ((sentenceStart[i] && !t.quoted && capWords < 2) ||
            (IsGuardStopword(NormalizeCaps(t.word)) && !t.quoted))
        {
            i = j;
            continue;
        }
        GuardCandidate c;
        c.name = name;
        c.cls = GuardClassForName(name);
        out.push_back(c);
        i = j;
    }

    // title-word anchoring: lowercase input still implies a person when a
    // rank word precedes a name ("where do i find marshal redwyn"). The
    // title list mirrors GuardClassForName's person titles.
    {
        std::vector<std::string> words;
        {
            std::string cur;
            for (size_t i = 0; i <= msg.size(); ++i)
            {
                char const c = i < msg.size() ? msg[i] : ' ';
                if (std::isalpha((unsigned char)c))
                    cur.push_back((char)std::tolower((unsigned char)c));
                else if (!cur.empty())
                {
                    words.push_back(cur);
                    cur.clear();
                }
            }
        }
        static char const* const anchorTitles[] = {
            "marshal", "guildmaster", "captain", "general", "sergeant",
            "innkeeper", "archmage", "commander", "chancellor",
        };
        for (size_t i = 0; i + 1 < words.size(); ++i)
        {
            bool isTitle = false;
            for (char const* t : anchorTitles)
                if (words[i] == t)
                    isTitle = true;
            if (!isTitle)
                continue;
            size_t n = i + 1;
            if (words[n] == "the" && n + 1 < words.size())
                ++n;
            if (words[n].size() < 3 || IsGuardStopword(Capitalize(words[n])))
                continue;
            std::string name = Capitalize(words[i]);
            while (n < words.size() && words[n].size() >= 3 &&
                   !IsGuardStopword(Capitalize(words[n])) &&
                   name.size() < 40)
            {
                name += " " + Capitalize(words[n]);
                ++n;
                // one extra word at most beyond the name itself
                break;
            }
            bool dup = false;
            for (GuardCandidate const& c : out)
                if (LowerAscii(c.name) == LowerAscii(name))
                    dup = true;
            if (!dup)
                out.push_back(GuardCandidate(name, "person", false));
        }
    }

    // always-ban era words behave as pseudo-entities (lowercase): a 1.12
    // local has never heard of a draenei any more than of Marshal Redwyn
    static char const* const eraPeople[] = { "draenei", "pandaren" };
    static char const* const eraThings[] = { "shattrath", "acherus" };
    std::string lower;
    lower.reserve(msg.size());
    for (char ch : msg)
        lower.push_back((char)std::tolower((unsigned char)ch));
    for (char const* t : eraPeople)
        if (lower.find(t) != std::string::npos)
            out.push_back(GuardCandidate{ t, "person", true });
    for (char const* t : eraThings)
        if (lower.find(t) != std::string::npos)
            out.push_back(GuardCandidate{ t, "thing", true });
    if (lower.find("flying mount") != std::string::npos)
        out.push_back(GuardCandidate{ "flying mounts", "thing", true });
    if (lower.find("ebon blade") != std::string::npos)
        out.push_back(GuardCandidate{ "the Ebon Blade", "person", true });
    return out;
}

// Strict question shape - the lore loop's trigger ("what/where/who/why +
// known entity" per plan A11). Imperative service shapes ("take me to X")
// are NOT questions: they route to the ACT beats, not the card head.
inline bool IsQuestionShape(std::string const& msg)
{
    static char const* const anywhere[] = {
        "what ", "where ", "who ", "why ", "when ", "how ", "which ",
        "isn't", "aren't", "doesn't", "don't", "tell me", "ever ",
        "heard ",
    };
    std::string lower;
    lower.reserve(msg.size() + 1);
    for (char c : msg)
        lower.push_back((char)std::tolower((unsigned char)c));
    lower = " " + lower;
    static char const* const heads[] = { " ", "\n" };
    for (char const* a : anywhere)
        for (char const* h : heads)
            if (lower.find(std::string(h) + a) != std::string::npos)
                return true;
    static char const* const startAux[] = {
        "is ", "are ", "do ", "does ", "did ", "can ", "could ",
        "would ", "will ", "should ", "have ", "has ", "any ",
    };
    size_t const first = lower.find_first_not_of(" \t\n");
    if (first != std::string::npos)
        for (char const* a : startAux)
            if (lower.compare(first, strlen(a), a) == 0)
                return true;
    return lower.find("?") != std::string::npos;
}

// The guard's stakes gate: only player-acted-on entities (directions,
// vendors, quest figures, officials) get the hard denial - the message
// must ask about or act on the entity, not merely mention it in passing.
inline bool IsQuestionOrStakesShape(std::string const& msg)
{
    static char const* const service[] = {
        "take me to", "lead me to", "walk me to", "go to", "send me to",
        "show me to", "point me to", "where can i", "looking for",
        "need to find", "want to buy", "sell me", "buy from", "trade with",
        "ask for", "sends his", "sells ", "vendor", "directions",
    };
    std::string lower;
    lower.reserve(msg.size() + 1);
    for (char c : msg)
        lower.push_back((char)std::tolower((unsigned char)c));
    lower = " " + lower;
    if (lower.find("?") != std::string::npos)
        return true;
    // wh-words and question idioms anywhere in the turn
    static char const* const anywhere[] = {
        "what ", "where ", "who ", "why ", "when ", "how ", "which ",
        "isn't", "aren't", "doesn't", "don't", "tell me", "ever ",
        "heard ",
    };
    static char const* const heads[] = { " ", "\n" };
    for (char const* a : anywhere)
        for (char const* h : heads)
            if (lower.find(std::string(h) + a) != std::string::npos)
                return true;
    // bare auxiliaries ("is", "does", "have"...) only OPEN a question:
    // mid-sentence ("i think i will head to Westfall") they are ordinary
    // verbs, not stakes
    {
        static char const* const startAux[] = {
            "is ", "are ", "do ", "does ", "did ", "can ", "could ",
            "would ", "will ", "should ", "have ", "has ", "any ",
        };
        size_t const first = lower.find_first_not_of(" \t\n");
        if (first != std::string::npos)
            for (char const* a : startAux)
                if (lower.compare(first, strlen(a), a) == 0)
                    return true;
    }
    for (char const* s : service)
        if (lower.find(s) != std::string::npos)
            return true;
    return false;
}

// ------------------------------------------------------------------ A11 ----

// One lore card as shipped in the jsonl asset (built by
// tools/llm_lab/build_lore_cards.py from the era-scrubbed corpus).
struct LoreCard
{
    std::string title;  // canonical page name ("Deadmines")
    std::string text;   // era-scrubbed lead at full ~140-word density
    std::vector<std::string> keys; // retrieval keywords (lowercase)
    bool poi = false;   // zones/cities/dungeons: resolvable move_to places

    LoreCard() {}
    LoreCard(std::string const& t, std::string const& x,
             std::vector<std::string> const& k, bool p)
        : title(t), text(x), keys(k), poi(p) {}
};

// The retrieval index: cards plus an inverted keyword map. Loading is a
// plain file read so the host battery exercises the real parser over
// fixture files; an empty/failed load degrades to "no cards" (the guard
// still works - the lore loop is the only thing that goes quiet).
class LoreIndex
{
public:
    bool Load(std::string const& path)
    {
        std::ifstream in(path.c_str());
        if (!in.is_open())
            return false;
        cards_.clear();
        keyToCards_.clear();
        std::string line;
        while (std::getline(in, line))
        {
            while (!line.empty() && (line.back() == '\r' || line.back() == '\n'))
                line.pop_back();
            if (line.empty())
                continue;
            detail::JsonValue root;
            if (!detail::ParseJson(line, root) || root.type != detail::JSON_OBJECT)
                continue;
            LoreCard card;
            if (detail::JsonValue const* v = root.Find("title"))
                if (v->type == detail::JSON_STRING)
                    card.title = v->str;
            if (detail::JsonValue const* v = root.Find("text"))
                if (v->type == detail::JSON_STRING)
                    card.text = v->str;
            if (detail::JsonValue const* v = root.Find("poi"))
                if (v->type == detail::JSON_BOOL)
                    card.poi = v->boolean;
            if (detail::JsonValue const* v = root.Find("keys"))
                if (v->type == detail::JSON_ARRAY)
                    for (detail::JsonValue const& k : v->items)
                        if (k.type == detail::JSON_STRING && !k.str.empty())
                            card.keys.push_back(LowerAscii(k.str));
            if (card.title.empty() || card.text.empty())
                continue;
            std::set<std::string> unique(card.keys.begin(), card.keys.end());
            card.keys.assign(unique.begin(), unique.end());
            for (std::string const& k : card.keys)
            {
                keyToCards_[k].push_back(cards_.size());
                std::string const folded = FoldPhrase(k);
                if (folded != k)
                    keyToCards_[folded].push_back(cards_.size());
            }
            cards_.push_back(card);
        }
        return !cards_.empty();
    }

    size_t Size() const { return cards_.size(); }
    std::vector<LoreCard> const& Cards() const { return cards_; }

    // True when the term is one of the index's keywords (title/alias
    // granularity, not full-text): the guard's known-name test.
    bool KnowsKey(std::string const& term) const
    {
        return keyToCards_.count(LowerAscii(term)) != 0;
    }

    // Retrieval scoring: idf-weighted keyword overlap over title/alias
    // keys only (a full-text index would echo confabulated keys). Single-
    // word keys score 3/(1+df) when the term appears in the query;
    // multi-word keys ("defias brotherhood") score when the whole phrase
    // appears consecutively in the query, weighted by length. The
    // threshold keeps stopword noise out; two specific terms, one rare
    // title term, or one phrase key clear it.
    double Score(std::vector<std::string> const& queryTerms, size_t cardIdx,
        std::string const& queryNorm) const
    {
        double score = 0.0;
        std::string titleFolded = FoldPhrase(cards_[cardIdx].title);
        if (titleFolded.compare(0, 4, "the ") == 0)
            titleFolded = titleFolded.substr(4);
        for (std::string const& term : queryTerms)
        {
            auto itr = keyToCards_.find(term);
            if (itr == keyToCards_.end())
                continue;
            bool const listed = std::find(cards_[cardIdx].keys.begin(),
                cards_[cardIdx].keys.end(), term) != cards_[cardIdx].keys.end();
            if (listed)
                score += 3.0 / (1.0 + (double)itr->second.size());
            // a query term that IS the card's title is the strongest
            // signal ("who rules stormwind" -> the Stormwind cards)
            if (term == titleFolded)
                score += 2.0;
        }
        for (std::string const& key : cards_[cardIdx].keys)
        {
            if (key.find(' ') == std::string::npos)
                continue;
            if (queryNorm.find(" " + key + " ") == std::string::npos &&
                queryNorm.find(" " + FoldPhrase(key) + " ") == std::string::npos)
                continue;
            size_t words = 1;
            for (char c : key)
                if (c == ' ')
                    ++words;
            score += (3.0 + 0.5 * (double)words) /
                (1.0 + (double)keyToCards_.find(key)->second.size());
        }
        return score;
    }

    LoreCard const* BestCard(std::string const& query, double* scoreOut = nullptr,
        double threshold = 1.6) const
    {
        std::vector<std::string> terms = RetrievalTerms(query);
        std::string queryNorm;
        {
            std::string lower = LowerAscii(query);
            for (char c : lower)
                queryNorm.push_back(std::isalpha((unsigned char)c) ? c : ' ');
            queryNorm = " " + FoldPhrase(queryNorm) + " ";
        }
        LoreCard const* best = nullptr;
        double bestScore = 0.0;
        // candidate set: any card listing at least one query term
        std::set<size_t> candidates;
        for (std::string const& term : terms)
        {
            auto itr = keyToCards_.find(term);
            if (itr != keyToCards_.end())
                candidates.insert(itr->second.begin(), itr->second.end());
        }
        for (size_t idx : candidates)
        {
            double const s = Score(terms, idx, queryNorm);
            if (s > bestScore)
            {
                bestScore = s;
                best = &cards_[idx];
            }
        }
        if (bestScore < threshold)
            best = nullptr;
        if (scoreOut)
            *scoreOut = bestScore;
        return best;
    }

    // POI resolution for move_to: the place must be a zone/city/dungeon
    // card keyword (the canonical title rides back for the go action).
    // Leading articles strip so "the deadmines" resolves like "deadmines",
    // and a card whose TITLE is the term wins over one that merely lists
    // it as a zone alias ("westfall" -> the Westfall zone card, not the
    // Deadmines dungeon card that mentions it).
    LoreCard const* ResolvePoi(std::string const& place) const
    {
        std::string term = LowerAscii(place);
        if (term.compare(0, 4, "the ") == 0)
            term = term.substr(4);
        while (!term.empty() && (term.back() == ' ' || term.back() == '.'))
            term.pop_back();
        for (LoreCard const& card : cards_)
        {
            if (!card.poi)
                continue;
            std::string title = LowerAscii(card.title);
            if (title.compare(0, 4, "the ") == 0)
                title = title.substr(4);
            if (title == term)
                return &card;
        }
        auto itr = keyToCards_.find(term);
        if (itr == keyToCards_.end())
            return nullptr;
        for (size_t idx : itr->second)
            if (cards_[idx].poi)
                return &cards_[idx];
        return nullptr;
    }

    // Query terms for retrieval: word tokens minus question furniture.
    static std::vector<std::string> RetrievalTerms(std::string const& query)
    {
        static std::set<std::string> const stop = {
            "what", "where", "who", "why", "when", "how", "which", "whose",
            "is", "are", "was", "were", "do", "does", "did", "can", "could",
            "would", "will", "should", "have", "has", "had", "the", "a",
            "an", "of", "in", "on", "at", "to", "for", "about", "tell",
            "me", "you", "i", "it", "he", "she", "they", "we", "and", "or",
            "any", "some", "there", "here", "from", "by", "with", "that",
            "this", "much", "many", "ever", "know", "heard", "say", "said",
        };
        std::vector<std::string> out;
        for (std::string const& t : WordTokens(query))
            if (!t.empty() && t.size() > 2 && !stop.count(t))
                out.push_back(FoldWord(t));
        return out;
    }

private:
    std::vector<LoreCard> cards_;
    std::map<std::string, std::vector<size_t>> keyToCards_;
};

// ---- the corrected era policy (plan §3 A11 verbatim classes) ----
// Always-ban: unambiguous later-era words. Context-allow terms NEVER
// appear here (Dalaran, death knight, Northrend, Outland, blood elf,
// worgen, Lich King, Naxxramas, Kel'Thuzad are all 1.12-legitimate; and
// the word "wrath" is ordinary speech, never banned).
inline std::vector<std::string> EraAlwaysBanTerms()
{
    static std::vector<std::string> const terms = {
        "shattrath", "draenei", "pandaren", "acherus",
    };
    return terms;
}

// Later-expansion SENSES of context-allow words: banned as phrases only.
// Each entry is (needle-lowercase, negation-escapes) - when the reply
// negates the sense ("the portal is not open"), it is clean.
inline std::vector<std::pair<std::string, bool>> EraSensePhrases()
{
    static std::vector<std::pair<std::string, bool>> const phrases = {
        { "flying mount", false }, { "flying mounts", false },
        { "portal is open", true }, { "portals are open", true },
        { "portal has opened", true }, { "portals have opened", true },
        { "gilneas opened", true }, { "gilneas is open", true },
        { "gilneas has opened", true }, { "playable worgen", false },
        { "dalaran floats", false }, { "sewers of dalaran", false },
        { "dalaran's sewers", false }, { "ebon blade", false },
        { "burning crusade", false }, { "wrath of the lich king", false },
        { "mists of pandaria", false },
        { "warlords of draenor", false },
        { "argus", false }, { "zereth mortis", false }, { "korthia", false },
        { "revendreth", false }, { "maldraxxus", false },
        { "ardenweald", false }, { "nazjatar", false },
        { "kul tiras", false }, { "zandalar", false },
        { "broken isles", false }, { "hellfire peninsula", false },
        { "zangarmarsh", false }, { "terokkar forest", false },
        { "blade's edge", false }, { "netherstorm", false },
        { "shadowmoon valley", false },
    };
    // NEVER in any ban list: wrath, legion, dragonflight(s), northrend,
    // outland, dalaran, worgen, death knight, naxxramas, lich king,
    // kel'thuzad, blood elf - all vanilla-legitimate words (the
    // "Dragonflight"/"Wrath"/"Legion" expansion TITLES collide with
    // them, so only full multi-word titles are banned).
    return phrases;
}

// Scans reply text for era violations. Returns the matched needle, or
// empty when clean. Negation-aware: a phrase marked negatable is clean
// when "not"/"never"/"n't"/"closed"/"no" appears within the 14 bytes
// after the phrase (the deny sense is exactly what we want voiced).
// One word-boundary-bounded occurrence check (plural-s tolerated on
// the right: "pandarens" still trips "pandaren").
inline bool EraHit(std::string const& lower, std::string const& needle,
    size_t at, size_t* endOut = nullptr)
{
    bool const leftOk = at == 0 ||
        !std::isalpha((unsigned char)lower[at - 1]);
    size_t end = at + needle.size();
    if (end < lower.size() && lower[end] == 's')
        ++end;
    bool const rightOk = end >= lower.size() ||
        !std::isalpha((unsigned char)lower[end]);
    if (endOut && leftOk && rightOk)
        *endOut = end;
    return leftOk && rightOk;
}

// A deny-shaped negator at word boundaries near a hit (up to 14 bytes
// after, 24 before): "no draenei trades here" and "never heard of any
// draenei" are exactly the denials the A10 guard elicits, so they must
// not regenerate. ("cannot" does not count - "cannot miss it" is not a
// denial of the premise; the boundary check rejects it as "not"-inside-
// a-word.)
inline bool NegatedNear(std::string const& lower, size_t hitBegin, size_t hitEnd)
{
    static char const* const negators[] = { "not ", "n't ", "never ",
                                            "no more", "closed" };
    // BEFORE the term only denial VERBS count: "never heard of any
    // draenei" denies, "never trust a draenei" affirms the thing exists
    static char const* const denialVerbs[] = { "never heard", "never met",
                                               "never seen", "not heard",
                                               "n't heard", "know no" };
    size_t const tailBegin = std::min(hitEnd, lower.size());
    std::string tail = lower.substr(tailBegin,
        std::min<size_t>(20, lower.size() - tailBegin));
    // clip at the first sentence terminator: a negator in the NEXT
    // sentence must not clear an affirmation in this one
    for (size_t i = 0; i < tail.size(); ++i)
        if (tail[i] == '.' || tail[i] == '!' || tail[i] == '?')
        {
            tail.resize(i);
            break;
        }
    size_t const windowBegin = hitBegin > 24 ? hitBegin - 24 : 0;
    std::string const window = lower.substr(windowBegin, hitBegin - windowBegin);
    for (char const* neg : negators)
    {
        for (size_t at = tail.find(neg); at != std::string::npos;
             at = tail.find(neg, at + 1))
            if (at == 0 || !std::isalpha((unsigned char)tail[at - 1]))
                return true;
    }
    for (char const* verb : denialVerbs)
    {
        for (size_t at = window.find(verb); at != std::string::npos;
             at = window.find(verb, at + 1))
        {
            size_t const absAt = windowBegin + at;
            if (absAt == 0 || !std::isalpha((unsigned char)lower[absAt - 1]))
                return true;
        }
    }
    // bare "no " directly before the term ("no draenei trades here")
    if (hitBegin >= 3 && lower.compare(hitBegin - 3, 3, "no ") == 0 &&
        (hitBegin == 3 || !std::isalpha((unsigned char)lower[hitBegin - 4])))
        return true;
    return false;
}

// Scans reply text for era violations. Returns the matched needle, or
// empty when clean. Every occurrence of every phrase is checked (a
// negated first mention must not mask an affirmed second), always-ban
// terms escape on nearby deny-shaped negation (the guard's denials
// voice the name), and the sense phrases marked negatable escape the
// same way.
inline std::string EraScan(std::string const& text)
{
    std::string const lower = LowerAscii(text);
    for (std::string const& term : EraAlwaysBanTerms())
    {
        size_t at = 0;
        while ((at = lower.find(term, at)) != std::string::npos)
        {
            size_t end = 0;
            if (EraHit(lower, term, at, &end) && !NegatedNear(lower, at, end))
                return term;
            ++at;
        }
    }
    for (auto const& phrase : EraSensePhrases())
    {
        size_t at = 0;
        while ((at = lower.find(phrase.first, at)) != std::string::npos)
        {
            size_t end = 0;
            if (EraHit(lower, phrase.first, at, &end) &&
                (!phrase.second || !NegatedNear(lower, at, end)))
                return phrase.first;
            ++at;
        }
    }
    return std::string();
}

// Era lint over shipped cards: a card carrying an era term or sense is a
// contamination (cards are injected as [RESULT] truth and bypass every
// other filter by construction). Returns every violating card title.
inline std::vector<std::string> EraLintCards(std::vector<LoreCard> const& cards)
{
    std::vector<std::string> bad;
    for (LoreCard const& card : cards)
    {
        // keys join the lint surface: IsKnownName treats every key as
        // ground truth, so a contaminated key would neuter the guard
        std::string surface = card.title + "\n" + card.text;
        for (std::string const& key : card.keys)
            surface += "\n" + key;
        if (!EraScan(surface).empty())
            bad.push_back(card.title);
    }
    return bad;
}

// ------------------------------------------------------------------ A12 ----

// Markdown stripper: headings/bold/italic/bullets/code fences never reach
// the 1.12 chat frame as markup. Emphasis runs are dropped PER LINE and
// only in pairs: one run alone is ordinary punctuation ("5 * 3"), and an
// underscore inside a word (snake_case) always survives.
inline std::string StripMarkdown(std::string const& text)
{
    std::string out;
    out.reserve(text.size());
    size_t pos = 0;
    while (pos <= text.size())
    {
        size_t const eol = text.find('\n', pos);
        size_t const lineEnd = eol == std::string::npos ? text.size() : eol;
        std::string const line = text.substr(pos, lineEnd - pos);

        // heading/bullet/quote lead markers
        size_t begin = 0;
        while (begin < line.size() && line[begin] == ' ')
            ++begin;
        if (begin < line.size())
        {
            if (line[begin] == '#')
            {
                while (begin < line.size() && line[begin] == '#')
                    ++begin;
                while (begin < line.size() && line[begin] == ' ')
                    ++begin;
            }
            else if ((line[begin] == '-' || line[begin] == '+' ||
                      line[begin] == '>') &&
                     begin + 1 < line.size() && line[begin + 1] == ' ')
            {
                begin += 2;
                while (begin < line.size() && line[begin] == ' ')
                    ++begin;
            }
        }
        // count emphasis runs of * and _ in the line
        int starRuns = 0, underRuns = 0;
        for (size_t i = begin; i < line.size();)
        {
            char const c = line[i];
            if (c == '*' || c == '_')
            {
                size_t run = 1;
                while (i + run < line.size() && line[i + run] == c)
                    ++run;
                bool const wordInner = i > begin &&
                    std::isalpha((unsigned char)line[i - 1]) &&
                    i + run < line.size() &&
                    std::isalpha((unsigned char)line[i + run]);
                if (c == '*')
                    ++starRuns;
                else if (!wordInner)
                    ++underRuns;
                i += run;
            }
            else
                ++i;
        }

        for (size_t i = begin; i < line.size();)
        {
            char const c = line[i];
            if (c == '`')
            {
                size_t run = 1;
                while (i + run < line.size() && line[i + run] == '`')
                    ++run;
                i += run; // inline code AND fences: markup dies, words stay
                continue;
            }
            if (c == '*' || c == '_')
            {
                size_t run = 1;
                while (i + run < line.size() && line[i + run] == c)
                    ++run;
                bool const wordInner = i > begin &&
                    std::isalpha((unsigned char)line[i - 1]) &&
                    i + run < line.size() &&
                    std::isalpha((unsigned char)line[i + run]);
                bool const paired = (c == '*') ? starRuns >= 2 : underRuns >= 2;
                if (paired && !(c == '_' && wordInner))
                {
                    i += run;
                    continue;
                }
                if (c == '*' && run == 1 && i == begin)
                {
                    i += run; // solitary bullet star at the line lead
                    continue;
                }
                out.append(line, i, run);
                i += run;
                continue;
            }
            out.push_back(c);
            ++i;
        }
        if (eol != std::string::npos)
            out.push_back('\n');
        if (eol == std::string::npos)
            break;
        pos = eol + 1;
    }
    // collapse the doubled spaces the removed markers leave behind
    {
        std::string collapsed;
        collapsed.reserve(out.size());
        for (size_t i = 0; i < out.size(); ++i)
        {
            if (out[i] == ' ' && i + 1 < out.size() && out[i + 1] == ' ')
                continue;
            collapsed.push_back(out[i]);
        }
        out.swap(collapsed);
    }
    return out;
}

// ASCII clamp for the 1.12 client: whole multibyte sequences drop (a
// partial strip would emit raw UTF-8 lead/continuation bytes as
// mojibake). Newlines and tabs survive.
inline std::string ClampAscii(std::string const& text)
{
    std::string out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size();)
    {
        unsigned char const c = (unsigned char)text[i];
        if (c < 0x80)
        {
            out.push_back((char)c);
            ++i;
            continue;
        }
        size_t len = 1;
        if ((c & 0xE0) == 0xC0)
            len = 2;
        else if ((c & 0xF0) == 0xE0)
            len = 3;
        else if ((c & 0xF8) == 0xF0)
            len = 4;
        i += len; // drop the whole sequence
    }
    return out;
}

// The /say line cap: the client cuts at 255 bytes, so the splitter cuts
// FIRST at word boundaries (never mid-word, never mid-UTF-8 - though the
// ASCII clamp runs before this in the stack, defensive trimming keeps
// the invariant local). One documented number: 255.
inline std::vector<std::string> SplitSayCap(std::string const& text, size_t cap = 255)
{
    std::vector<std::string> out;
    size_t begin = 0;
    while (begin < text.size())
    {
        size_t len = std::min(cap, text.size() - begin);
        if (begin + len < text.size())
        {
            // prefer the newline, then a space, inside the window
            size_t cut = text.rfind('\n', begin + len - 1);
            if (cut == std::string::npos || cut < begin)
                cut = text.rfind(' ', begin + len - 1);
            if (cut != std::string::npos && cut >= begin)
                len = cut - begin + 1; // keep the newline/space at the cut
        }
        // never split a multibyte sequence: back off trailing
        // continuation bytes, then a lead byte whose sequence was cut
        while (len > 1 &&
               ((unsigned char)text[begin + len - 1] & 0xC0) == 0x80)
            --len;
        if (len > 1 && ((unsigned char)text[begin + len - 1] & 0xC0) == 0xC0)
            --len;
        std::string const piece = text.substr(begin, len);
        bool const allSpace = piece.find_first_not_of(" \t\r\n") ==
            std::string::npos;
        if (!allSpace)
            out.push_back(piece);
        begin += len;
    }
    if (out.empty())
        out.push_back(std::string());
    return out;
}

// Prompt-leak marker terms: protocol furniture that must never be voiced.
inline bool ContainsMarkerTerms(std::string const& text)
{
    static char const* const markers[] = {
        "[BRIDGE", "Write in place of", "Speak your reply",
        "copy the ready ones", "Your reply:", "Example 1",
        "[Memories]", "[State]", "[RESULT]", "[say]", "[EVENT]",
        "<<", ">>",
    };
    for (char const* m : markers)
        if (text.find(m) != std::string::npos)
            return true;
    return false;
}

// Lowercased word 5-grams of a text (punctuation-split tokens).
inline std::set<std::vector<std::string>> FiveGrams(std::string const& text)
{
    std::set<std::vector<std::string>> out;
    std::vector<std::string> tokens = WordTokens(text);
    if (tokens.size() < 5)
        return out;
    for (size_t i = 0; i + 5 <= tokens.size(); ++i)
        out.insert(std::vector<std::string>(tokens.begin() + i,
                                            tokens.begin() + i + 5));
    return out;
}

// The h4 leak test: ANY shared word 5-gram between the reply and the
// instruction furniture (identity + TOOLS_NOTE + bible + NO_NARRATE +
// LEDGER_AVOID). Facts/tier/absence/backstory lines are excluded by the
// caller before comparison - re-voicing a remembered fact is recall, not
// leakage.
inline bool SharesFiveGram(std::string const& reply, std::string const& reference)
{
    std::set<std::vector<std::string>> const ref = FiveGrams(reference);
    std::vector<std::string> tokens = WordTokens(reply);
    for (size_t i = 0; i + 5 <= tokens.size(); ++i)
        if (ref.count(std::vector<std::string>(tokens.begin() + i,
                                               tokens.begin() + i + 5)))
            return true;
    return false;
}

// Word-set Jaccard similarity for the dedupe reroll.
inline double JaccardWords(std::string const& a, std::string const& b)
{
    std::vector<std::string> const ta = WordTokens(a), tb = WordTokens(b);
    if (ta.empty() && tb.empty())
        return 1.0;
    std::set<std::string> const sa(ta.begin(), ta.end()), sb(tb.begin(), tb.end());
    size_t inter = 0;
    for (std::string const& w : sa)
        if (sb.count(w))
            ++inter;
    size_t const uni = sa.size() + sb.size() - inter;
    return uni ? (double)inter / (double)uni : 0.0;
}

// Splits a reply into sentence spans on . ! ? followed by space/end.
inline std::vector<std::pair<size_t, size_t>> SentenceSpans(std::string const& text)
{
    std::vector<std::pair<size_t, size_t>> out;
    size_t begin = 0;
    for (size_t i = 0; i < text.size(); ++i)
    {
        if ((text[i] == '.' || text[i] == '!' || text[i] == '?') &&
            (i + 1 >= text.size() || text[i + 1] == ' ' || text[i + 1] == '\n'))
        {
            if (i + 1 > begin)
                out.push_back({ begin, i + 1 });
            begin = i + 1;
        }
    }
    if (begin < text.size())
        out.push_back({ begin, text.size() });
    return out;
}

// Self-initiated-invention scan (A10's post-filter leg): a REPLY sentence
// that introduces a capitalized entity the world does not know, attached
// to a service/direction claim ("I know a gnomish instructor in
// Stormwind"), is dropped. The isKnown predicate comes from the caller
// (bridge resolver: DB + areas + players + lore keys). Returns the byte
// spans to remove.
inline std::vector<std::pair<size_t, size_t>> InventionClaimSpans(
    std::string const& reply,
    bool (*isKnown)(std::string const&, void*), void* ctx)
{
    static char const* const claimCues[] = {
        "know a", "knows a", "know an", "knows an", "a man who",
        "a woman who", "one who can", "someone who", "who sells",
        "who teaches", "who trains", "can get you", "can find you",
        "will take you", "leads to", "go see", "seek out", "ask for",
    };
    std::vector<std::pair<size_t, size_t>> out;
    for (auto const& span : SentenceSpans(reply))
    {
        std::string const sentence = reply.substr(span.first, span.second - span.first);
        std::string const lower = LowerAscii(sentence);
        bool cue = false;
        for (char const* c : claimCues)
            if (lower.find(c) != std::string::npos)
            {
                cue = true;
                break;
            }
        if (!cue)
            continue;
        for (GuardCandidate const& cand : ExtractGuardEntities(sentence))
        {
            if (cand.eraTerm)
                continue;
            if (isKnown(cand.name, ctx))
                continue;
            // a compound whose HEAD word is known is a known thing with
            // descriptors ("the Goldshire inn"), not an invention
            size_t const space = cand.name.find(' ');
            if (space != std::string::npos)
            {
                std::string head = cand.name.substr(0, space);
                if (head.size() > 2 && head.compare(head.size() - 2, 2, "'s") == 0)
                    head.resize(head.size() - 2);
                if (!head.empty() && isKnown(head, ctx))
                    continue;
            }
            out.push_back(span);
            break;
        }
    }
    return out;
}

// Canned in-character deflections (h4 law): the leak/era filter's last
// resort after two regenerations - deterministic, persona-neutral.
inline std::string CannedDeflection(uint64_t salt)
{
    static char const* const lines[] = {
        "Aye, well. Ask me that one another day.",
        "Hm. Never you mind that. Something else?",
        "That is not a thing I will answer straight. Ask me plainer.",
        "Bah. My tongue ran ahead of me there. Forget it.",
    };
    return lines[salt % (sizeof(lines) / sizeof(lines[0]))];
}

} // namespace pocketllm

#endif
