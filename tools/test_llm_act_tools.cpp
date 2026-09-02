// Host battery for the S6 ACT-tool pure core (A2/A3 gate, host leg):
// compiles the SHIPPED header (PlayerbotLlmToolsCore.h) - the host always
// tests the shipped code, never a copy - and runs four legs:
//
//   scanner     the <<tool ...>> extraction semantics (multi-block,
//               quoted multi-word values, nested markers, quoted >>,
//               stray >> prose cut, unterminated markers, whitespace
//               trim) and the cleaned-prose invariants.
//   whitelist   the queue-admission vocabulary: exactly banklib's ten
//               VALID_TOOLS, nothing else.
//   emotes      all 19 trained text-emote ids pinned to their 1.12
//               SharedDefines values, the measured fuzzy map (frown->no
//               ...), light morphology, and the no/nod non-collapse.
//   beats       the bridge's state-free trigger layer: ACT beats fire on
//               request phrasings and stay silent on the S2 restraint
//               turns; the insult second-person gate; gift extraction.
#include "PlayerbotLlmToolsCore.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int g_failures = 0;

#define CHECK(cond, msg) do { \
    if (!(cond)) { std::printf("FAIL: %s\n", msg); ++g_failures; } \
} while (0)

#define CHECK_EQ(actual, expected, msg) do { \
    if (!((actual) == (expected))) { \
        std::printf("FAIL: %s (actual '%s' vs expected '%s')\n", msg, \
            std::to_string(actual).c_str(), std::to_string(expected).c_str()); \
        ++g_failures; \
    } \
} while (0)

static void CheckStr(std::string const& actual, std::string const& expected, char const* msg)
{
    if (actual != expected)
    {
        std::printf("FAIL: %s\n  actual:   [%s]\n  expected: [%s]\n",
            msg, actual.c_str(), expected.c_str());
        ++g_failures;
    }
}

static pocketllm::ToolCall const* FindCall(
    std::vector<pocketllm::ToolCall> const& calls, std::string const& name)
{
    for (auto const& c : calls)
        if (c.name == name)
            return &c;
    return nullptr;
}

static std::string Field(pocketllm::ToolCall const& call, std::string const& key)
{
    for (auto const& kv : call.fields)
        if (kv.first == key)
            return kv.second;
    return "";
}

static void ScannerLeg()
{
    // plain prose + one block
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls =
            pocketllm::ExtractToolCalls("Hello there. <<log_fact text=\"likes ale\">> Bye.", &cleaned);
        CheckStr(cleaned, "Hello there.  Bye.", "prose around one block");
        CHECK_EQ(calls.size(), 1u, "one call parsed");
        CHECK(calls.size() == 1 && calls[0].name == "log_fact", "tool name");
        CHECK(calls.size() == 1 && Field(calls[0], "text") == "likes ale", "quoted value");
    }
    // multiple blocks, multi-word quoted values, unquoted values
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<give_item player=\"Brannoc\" item=\"rough bronze hammer\">>"
            "words between"
            "<<duel_challenge name=Kromgrit>>", &cleaned);
        CheckStr(cleaned, "words between", "prose between blocks");
        CHECK_EQ(calls.size(), 2u, "two calls");
        pocketllm::ToolCall const* give = FindCall(calls, "give_item");
        CHECK(give && Field(*give, "player") == "Brannoc", "give_item player field");
        CHECK(give && Field(*give, "item") == "rough bronze hammer", "multi-word quoted value");
        pocketllm::ToolCall const* duel = FindCall(calls, "duel_challenge");
        CHECK(duel && Field(*duel, "name") == "Kromgrit", "unquoted value");
    }
    // quoted >> must not terminate the block
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<share_gossip text=\"he said >> loudly\">>", &cleaned);
        CheckStr(cleaned, "", "quoted >> leaves no prose");
        pocketllm::ToolCall const* gossip = FindCall(calls, "share_gossip");
        CHECK(gossip && Field(*gossip, "text") == "he said >> loudly",
            "quoted >> stays inside the value");
    }
    // nested unquoted << ... >> closes on the inner pair
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<a <<b>> tail >>", &cleaned);
        CHECK_EQ(calls.size(), 1u, "nested marker counts once");
        CHECK(calls.size() == 1 && calls[0].name == "a", "outer name kept");
    }
    // stray >> in prose cuts the prose there
    {
        std::string cleaned;
        pocketllm::ExtractToolCalls("Sure. >>log_fact text=\"x\"<< kept", &cleaned);
        CheckStr(cleaned, "Sure.", "stray >> cuts prose (trailing space trimmed)");
    }
    // unterminated marker: prose before it survives, the partial drops
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls =
            pocketllm::ExtractToolCalls("Keep me <<log_fact text=\"never", &cleaned);
        CheckStr(cleaned, "Keep me", "unterminated marker dropped");
        CHECK_EQ(calls.size(), 0u, "no call from an unterminated marker");
    }
    // trailing whitespace/newlines trimmed from the cleaned prose
    {
        std::string cleaned;
        pocketllm::ExtractToolCalls("line\n<<perform_emote emote=\"grin\">>\n  ", &cleaned);
        CheckStr(cleaned, "line", "trailing whitespace trimmed");
    }
    // keys tolerate spaces around '='; a stray '=' between pairs consumes
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<loot_roll choice = \"need\">>", &cleaned);
        pocketllm::ToolCall const* roll = FindCall(calls, "loot_roll");
        CHECK(roll && Field(*roll, "choice") == "need", "spaces around =");
    }
    // the base-model fieldless emote form folds its bare token into the
    // emote field (S6-ledger (j), landed at S11); ONLY the pure form does
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "Ha! <<perform_emote laugh>>", &cleaned);
        CheckStr(cleaned, "Ha!", "fieldless block leaves prose");
        pocketllm::ToolCall const* emote = FindCall(calls, "perform_emote");
        CHECK(emote && Field(*emote, "emote") == "laugh",
            "fieldless emote token folds into the emote field");
    }
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<perform_emote>>", &cleaned);
        pocketllm::ToolCall const* emote = FindCall(calls, "perform_emote");
        CHECK(emote && Field(*emote, "emote").empty(),
            "no bare token: nothing folds");
    }
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<log_fact laugh>>", &cleaned);
        pocketllm::ToolCall const* fact = FindCall(calls, "log_fact");
        CHECK(fact && fact->fields.empty(),
            "the fold is perform_emote-only (a bare log_fact token stays dropped)");
    }
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<perform_emote l4ugh>>", &cleaned);
        pocketllm::ToolCall const* emote = FindCall(calls, "perform_emote");
        CHECK(emote && Field(*emote, "emote").empty(),
            "non-alphabetic bare token does not fold");
    }
    {
        std::string cleaned;
        std::vector<pocketllm::ToolCall> calls = pocketllm::ExtractToolCalls(
            "<<perform_emote emote=\"laugh\" grin>>", &cleaned);
        pocketllm::ToolCall const* emote = FindCall(calls, "perform_emote");
        CHECK(emote && Field(*emote, "emote") == "laugh",
            "a keyed value wins; a trailing bare token never overrides it");
    }
    // a marker must never survive into the cleaned prose (injection law:
    // what reaches chat cannot re-open a tool block)
    {
        char const* const probes[] = {
            "<<perform_emote emote=\"laugh\">><<perform_emote emote=\"cry\">>",
            "<<a=\"<<\">>text",
            "<<<log_fact text=\"x\">>",
            "<< >>",
            "<<",
            ">>",
            "<<log_fact>>",
        };
        for (char const* probe : probes)
        {
            std::string cleaned;
            std::vector<pocketllm::ToolCall> calls =
                pocketllm::ExtractToolCalls(probe, &cleaned);
            CHECK(cleaned.find("<<") == std::string::npos, "no marker survives into prose");
            CHECK(cleaned.find(">>") == std::string::npos, "no terminator survives into prose");
            for (auto const& c : calls)
                CHECK(!c.name.empty(), "parsed calls always carry a name");
        }
    }
    // fuzz: random marker soup never leaves a live marker in the prose
    {
        unsigned state = 12345;
        auto rnd = [&state]() { state = state * 1103515245u + 12345u; return (state >> 16) & 0x7fff; };
        char const* const pieces[] = {
            "<<", ">>", "log_fact", "text", "=", "\"", " ", "x", "<", ">",
            "perform_emote", "emote", "laugh", "\n", "name",
        };
        for (int iter = 0; iter < 20000; ++iter)
        {
            std::string soup;
            int const n = 1 + int(rnd() % 24);
            for (int i = 0; i < n; ++i)
                soup += pieces[rnd() % (sizeof(pieces) / sizeof(pieces[0]))];
            std::string cleaned;
            pocketllm::ExtractToolCalls(soup, &cleaned);
            CHECK(cleaned.find("<<") == std::string::npos, "fuzz: no marker in prose");
            CHECK(cleaned.find(">>") == std::string::npos, "fuzz: no terminator in prose");
            if (g_failures)
                break;
        }
    }
    std::printf("scanner leg done\n");
}

static void WhitelistLeg()
{
    char const* const known[] = {
        "log_fact", "adjust_sentiment", "share_gossip", "perform_emote",
        "duel_challenge", "give_item", "follow", "party_invite",
        "move_to", "loot_roll",
    };
    for (char const* k : known)
        CHECK(pocketllm::IsKnownTool(k), "known tool admitted");
    char const* const junk[] = {
        "", "Log_Fact", "log-fact", "logfact", "say", "emote", "cast",
        "delete_item", "loot", "roll", "give", "duel", "move", "party",
        "set_relationship", "log_fact2", "perform",
    };
    for (char const* j : junk)
        CHECK(!pocketllm::IsKnownTool(j), "unknown tool refused");
    std::printf("whitelist leg done\n");
}

static void EmotesLeg()
{
    struct { char const* name; uint32_t id; } const table[] = {
        {"wave", 101}, {"bow", 17}, {"salute", 78}, {"laugh", 60},
        {"cry", 31}, {"nod", 67}, {"no", 66}, {"point", 72}, {"cheer", 21},
        {"dance", 34}, {"flex", 41}, {"kiss", 58}, {"rude", 77},
        {"grin", 49}, {"shrug", 83}, {"chicken", 22}, {"whistle", 104},
        {"glare", 46}, {"hug", 56},
    };
    for (auto const& row : table)
    {
        uint32_t const got = pocketllm::ResolveTextEmote(row.name);
        if (got != row.id)
        {
            std::printf("FAIL: emote %s resolved %u, expected %u\n",
                row.name, got, row.id);
            ++g_failures;
        }
    }
    // case-insensitive on input
    CHECK_EQ(pocketllm::ResolveTextEmote("GRIN"), 49u, "uppercase input");
    CHECK_EQ(pocketllm::ResolveTextEmote("Laugh "), 60u, "trailing space");
    // the measured fuzzy map (off-whitelist values)
    CHECK_EQ(pocketllm::ResolveTextEmote("frown"), 66u, "frown -> no");
    CHECK_EQ(pocketllm::ResolveTextEmote("yes"), 67u, "yes -> nod");
    CHECK_EQ(pocketllm::ResolveTextEmote("sob"), 31u, "sob -> cry");
    CHECK_EQ(pocketllm::ResolveTextEmote("smile"), 49u, "smile -> grin");
    CHECK_EQ(pocketllm::ResolveTextEmote("chuckle"), 60u, "chuckle -> laugh");
    CHECK_EQ(pocketllm::ResolveTextEmote("bye"), 101u, "bye -> wave");
    CHECK_EQ(pocketllm::ResolveTextEmote("clap"), 21u, "clap -> cheer");
    CHECK_EQ(pocketllm::ResolveTextEmote("angry"), 46u, "angry -> glare");
    // the remaining measured aliases
    CHECK_EQ(pocketllm::ResolveTextEmote("weep"), 31u, "weep -> cry");
    CHECK_EQ(pocketllm::ResolveTextEmote("giggle"), 60u, "giggle -> laugh");
    CHECK_EQ(pocketllm::ResolveTextEmote("snicker"), 60u, "snicker -> laugh");
    CHECK_EQ(pocketllm::ResolveTextEmote("hello"), 101u, "hello -> wave");
    CHECK_EQ(pocketllm::ResolveTextEmote("goodbye"), 101u, "goodbye -> wave");
    CHECK_EQ(pocketllm::ResolveTextEmote("applaud"), 21u, "applaud -> cheer");
    CHECK_EQ(pocketllm::ResolveTextEmote("agree"), 67u, "agree -> nod");
    CHECK_EQ(pocketllm::ResolveTextEmote("mad"), 46u, "mad -> glare");
    // morphology: plurals and inflections of the longer names
    CHECK_EQ(pocketllm::ResolveTextEmote("laughs"), 60u, "laughs -> laugh");
    CHECK_EQ(pocketllm::ResolveTextEmote("bows"), 17u, "bows -> bow");
    CHECK_EQ(pocketllm::ResolveTextEmote("nods"), 67u, "nods -> nod");
    CHECK_EQ(pocketllm::ResolveTextEmote("waves"), 101u, "waves -> wave");
    CHECK_EQ(pocketllm::ResolveTextEmote("laughing"), 60u, "laughing -> laugh");
    CHECK_EQ(pocketllm::ResolveTextEmote("cheered"), 21u, "cheered -> cheer");
    CHECK_EQ(pocketllm::ResolveTextEmote("kisses"), 58u, "kisses -> kiss");
    // vowel-dropping gerunds of the short names stay unresolvable (no
    // stemmer: guessing an animation is worse than playing none)
    CHECK_EQ(pocketllm::ResolveTextEmote("nodding"), 0u, "nodding unresolved");
    CHECK_EQ(pocketllm::ResolveTextEmote("waving"), 0u, "waving unresolved");
    // no/nod must never collapse into each other or into junk
    CHECK_EQ(pocketllm::ResolveTextEmote("no"), 66u, "no stays no");
    CHECK_EQ(pocketllm::ResolveTextEmote("nod"), 67u, "nod stays nod");
    CHECK_EQ(pocketllm::ResolveTextEmote("nope"), 0u, "nope unresolved");
    CHECK_EQ(pocketllm::ResolveTextEmote(""), 0u, "empty unresolved");
    CHECK_EQ(pocketllm::ResolveTextEmote("banana"), 0u, "banana unresolved");
    CHECK_EQ(pocketllm::ResolveTextEmote("xyz"), 0u, "xyz unresolved");
    CHECK_EQ(pocketllm::ResolveTextEmote("n"), 0u, "single letter unresolved");
    std::printf("emotes leg done\n");
}

static void BeatsLeg()
{
    // ACT request phrasings
    CHECK(pocketllm::WantsDuel("fine, you win the argument. duel me, right now, goldshire"), "duel me");
    CHECK(pocketllm::WantsDuel("care for a duel, smith?"), "care for a duel");
    CHECK(pocketllm::WantsDuel("Let's duel!"), "let's duel");
    CHECK(!pocketllm::WantsDuel("did you hear about the duel in Goldshire"), "duel gossip is not a challenge");
    CHECK(!pocketllm::WantsDuel("lovely evening out here"), "no duel on small talk");
    // third-person "fight me" narration must not license a challenge
    CHECK(!pocketllm::WantsDuel("the bards fight me for the story rights"), "third-person fight me");

    CHECK(pocketllm::WantsFollow("follow me to the quarry"), "follow me");
    CHECK(pocketllm::WantsFollow("come with me, we have wolves to thin"), "come with me");
    CHECK(pocketllm::WantsFollow("walk with me and keep pace"), "walk with me");
    CHECK(!pocketllm::WantsFollow("stay close to the fire"), "idiomatic stay close");
    CHECK(pocketllm::WantsPartyInvite("invite me along, will you"), "invite me");
    CHECK(pocketllm::WantsPartyInvite("can i join your group?"), "join your group");
    CHECK(pocketllm::WantsPartyInvite("add me to the party, we are short a shield"),
        "add me positive");
    // join forms need group context: "join you for a drink" is an idiom
    CHECK(!pocketllm::WantsPartyInvite("can i join you for a drink at the inn"), "idiomatic join you");

    // gift extraction
    std::string item;
    CHECK(pocketllm::ExtractGiftItem("give me a hammer for the job", &item), "gift ask detected");
    CheckStr(item, "hammer", "simple gift noun");
    CHECK(pocketllm::ExtractGiftItem("hand me that rough bronze hammer", &item), "multi-word gift ask");
    CheckStr(item, "rough bronze hammer", "multi-word gift noun");
    CHECK(pocketllm::ExtractGiftItem("can i have one of your torches", &item), "plural gift ask");
    CheckStr(item, "torches", "plural noun kept (executor retries singular)");
    CHECK(!pocketllm::WantsGiveItem("could you spare me a moment"),
        "the 'spare me' idiom never licenses give_item");
    // abstract-noun idioms never license a hand-over
    CHECK(!pocketllm::WantsGiveItem("give me a moment to think"),
        "give me a moment idiom");
    CHECK(!pocketllm::WantsGiveItem("can i have a word with you"),
        "have a word idiom");
    CHECK(!pocketllm::WantsGiveItem("i could use a hand with this"),
        "use a hand idiom");
    CHECK(!pocketllm::WantsGiveItem("lend me your ears, smith"),
        "lend me your ears idiom");
    CHECK(!pocketllm::WantsGiveItem("give me a break"),
        "give me a break idiom");
    // multi-word and plural-stemmed idiom phrases
    CHECK(!pocketllm::WantsGiveItem("give me a few minutes to think"),
        "few minutes idiom");
    CHECK(!pocketllm::WantsGiveItem("give me a second chance, that is all"),
        "second chance idiom");
    CHECK(pocketllm::WantsGiveItem("i'll take that whetstone off your hands"), "i'll take ask");
    {
        std::string whetstone;
        CHECK(pocketllm::ExtractGiftItem("i'll take that whetstone off your hands", &whetstone),
            "whetstone ask detected");
        CheckStr(whetstone, "whetstone", "preposition tail stops the noun phrase");
    }

    // the beat selector: S2 restraint turns never select an ACT beat
    char const* const restraint[] = {
        "lovely evening out here",
        "what do you charge for a shield repair",
        "the rain is going to rust my armor",
        "any news from the militia",
        "you dwarf folk really can drink",
        "i think i will head to westfall tomorrow",
    };
    for (char const* turn : restraint)
        CHECK_EQ((int)pocketllm::SelectConversationalBeat(turn), (int)pocketllm::BEAT_NONE,
            "restraint turn selects no beat");
    CHECK_EQ((int)pocketllm::SelectConversationalBeat("duel me, right now"), (int)pocketllm::BEAT_DUEL, "duel beat");
    CHECK_EQ((int)pocketllm::SelectConversationalBeat("give me one of your hammers"), (int)pocketllm::BEAT_GIVE_ITEM, "give beat");
    CHECK_EQ((int)pocketllm::SelectConversationalBeat("follow me close"), (int)pocketllm::BEAT_FOLLOW, "follow beat");
    CHECK_EQ((int)pocketllm::SelectConversationalBeat("can i join the group?"), (int)pocketllm::BEAT_PARTY_INVITE, "invite beat");
    // duel outranks the other ACT beats when several match
    CHECK_EQ((int)pocketllm::SelectConversationalBeat("duel me then follow me home"), (int)pocketllm::BEAT_DUEL, "duel priority");

    // ---- S7 negation guards (the S6-logged idiom class): a negated
    // trigger must not license the beat
    CHECK(!pocketllm::WantsDuel("don't duel me, i yield"), "negated duel");
    CHECK(!pocketllm::WantsDuel("do not duel me again"), "do-not duel");
    CHECK(!pocketllm::WantsDuel("never duel me again, you brute"), "never duel");
    CHECK(pocketllm::WantsDuel("don't just stand there, duel me"), "comma gap breaks the negation window");
    CHECK(!pocketllm::WantsFollow("don't follow me, i travel alone"), "negated follow");
    CHECK(!pocketllm::WantsPartyInvite("don't invite me anywhere, i am busy"), "negated invite");
    CHECK(!pocketllm::WantsGiveItem("don't give me that look"), "negated give idiom");
    CHECK(pocketllm::WantsGiveItem("don't give me that look, give me the hammer"),
        "later un-negated give still extracts");

    // ---- S7 move_to beat: lead-me-there phrasings
    std::string place;
    CHECK(pocketllm::ExtractMovePlace("take me to the deadmines", &place), "take me to");
    CheckStr(place, "the deadmines", "article kept for POI resolution");
    CHECK(pocketllm::ExtractMovePlace("lead me to Goldshire please", &place), "lead me to");
    CheckStr(place, "goldshire", "cased place lowercased");
    CHECK(!pocketllm::WantsMoveTo("don't take me to the deadmines"), "negated move");
    CHECK_EQ((int)pocketllm::SelectConversationalBeat("take me to the inn"), (int)pocketllm::BEAT_MOVE_TO,
        "move beat");
    // follow outranks move when both match ("follow me to the inn")
    CHECK_EQ((int)pocketllm::SelectConversationalBeat("follow me to the inn"), (int)pocketllm::BEAT_FOLLOW,
        "follow beats move");


    // insult second-person gate: object insults must not fire the beat
    CHECK(pocketllm::IsSecondPerson("your work is pig iron trash", "Grumph"), "your-insult gated in");
    CHECK(pocketllm::IsSecondPerson("you are useless", "Grumph"), "you-insult gated in");
    CHECK(!pocketllm::IsSecondPerson("this sword is trash", "Grumph"), "object insult gated out");
    CHECK(!pocketllm::IsSecondPerson("that hammer is garbage", "Grumph"), "object garbage gated out");
    CHECK(pocketllm::IsSecondPerson("grumph is useless", "Grumph"), "direct-name insult gated in");
    CHECK(!pocketllm::IsSecondPerson("grumph is useless", "Kromgrit"), "other-name insult gated out");

    std::printf("beats leg done\n");
}

// The per-class voice budgets - whisper-class notes (2 lines
// x 160 bytes, on EVERY tier when uncued), ambient barks (1 line x 80
// everywhere), and the long-form widening (a CUED turn on a licensed
// tier runs to the splitter's own 4 x 255 budget - the widening is earned
// per turn; a plain turn on a licensed tier keeps the short budget).
// UTF-8-safe truncation throughout.
static void BudgetLeg()
{
    {
        std::vector<std::string> lines = {
            "a plain conversational line",
            "a second plain line",
            "a third line that must drop",
        };
        pocketllm::ApplyReplyBudget(lines, 0, 200, false);
        CHECK_EQ((int)lines.size(), 2, "conversational keeps two lines");
        CheckStr(lines[0], "a plain conversational line", "line 0 untouched");
        CheckStr(lines[1], "a second plain line", "line 1 untouched");
    }
    {
        // an UNCUED turn on a licensed tier keeps the short budget: the
        // widening is earned per turn, never tier-wide
        std::vector<std::string> lines = {
            std::string(255, 'a'), std::string(255, 'b'),
            std::string(255, 'c'), std::string(200, 'd'),
        };
        pocketllm::ApplyReplyBudget(lines, 0, 230, false);
        CHECK_EQ((int)lines.size(), 2, "uncued licensed-tier turn keeps two lines");
        CHECK_EQ((int)lines[0].size(), 160, "uncued licensed-tier byte bound is 160");
        std::vector<std::string> cuemiss = {std::string(200, 'a')};
        pocketllm::ApplyReplyBudget(cuemiss, 0, 230, false);
        CHECK_EQ((int)cuemiss[0].size(), 160, "uncued one-line cuts at 160 even at 230");
    }
    {
        std::vector<std::string> lines = {"one bark only", "and never a second"};
        pocketllm::ApplyReplyBudget(lines, 1, 230, true);
        CHECK_EQ((int)lines.size(), 1, "ambient keeps one line even when cued");
        CheckStr(lines[0], "one bark only", "ambient line untouched");
    }
    {
        std::string long_ = "word ";
        for (int i = 0; i < 40; ++i)
            long_ += "word ";
        std::vector<std::string> lines = {long_};
        pocketllm::ApplyReplyBudget(lines, 0, 200, false);
        CHECK(lines[0].size() <= 160, "conversational truncates at 160 unlicensed");
        CHECK(lines[0].size() == 0 ||
                  (((unsigned char)lines[0][lines[0].size() - 1] & 0xC0) != 0x80),
            "never ends inside a multibyte sequence");
        std::vector<std::string> ambient = {long_};
        pocketllm::ApplyReplyBudget(ambient, 1, 230, false);
        CHECK(ambient[0].size() <= 80, "ambient truncates at 80");
    }
    {
        // the S11 long-form budget: a CUED turn on a licensed tier carries
        // up to 4 lines at the 255 channel cap (150 words ~ 900 bytes)
        std::vector<std::string> lines = {
            std::string(255, 'a'), std::string(255, 'b'),
            std::string(255, 'c'), std::string(255, 'd'),
            std::string(200, 'e'),
        };
        pocketllm::ApplyReplyBudget(lines, 0, 230, true);
        CHECK_EQ((int)lines.size(), 4, "cued licensed chat keeps four lines");
        CHECK_EQ((int)lines[3].size(), 255, "cued line bound is the channel cap");
        std::vector<std::string> over = {std::string(256, 'a')};
        pocketllm::ApplyReplyBudget(over, 0, 230, true);
        CHECK_EQ((int)over[0].size(), 255, "cued one-over line cuts to 255");
        // a cued turn on an UNLICENSED tier earns nothing
        std::vector<std::string> uncued = {std::string(200, 'a'), "x", "y", "z"};
        pocketllm::ApplyReplyBudget(uncued, 0, 210, true);
        CHECK_EQ((int)uncued.size(), 2, "cued but unlicensed tier keeps the short budget");
        // the token boundary itself: >= 225 licenses when cued, 224 never
        std::vector<std::string> edge = {std::string(200, 'a'), "x", "y", "z"};
        pocketllm::ApplyReplyBudget(edge, 0, 225, true);
        CHECK_EQ((int)edge.size(), 4, "225 tokens licenses the long budget when cued");
        std::vector<std::string> under = {std::string(200, 'a'), "x", "y", "z"};
        pocketllm::ApplyReplyBudget(under, 0, 224, true);
        CHECK_EQ((int)under.size(), 2, "224 tokens stays the short budget");
    }
    {
        // a multibyte run straddling the 80-byte cut must back off whole
        // characters, not split one (the splitter's own law)
        std::string euros;
        for (int i = 0; i < 60; ++i)
            euros += "\xe2\x82\xac"; // U+20AC, 3 bytes each
        std::vector<std::string> lines = {euros};
        pocketllm::ApplyReplyBudget(lines, 1, 230, true);
        CHECK_EQ((int)lines[0].size(), 78, "multibyte cut backs off to a whole character");
    }
    {
        // within-budget lines pass through byte-identically
        std::vector<std::string> lines = {"short", std::string(160, 'a')};
        pocketllm::ApplyReplyBudget(lines, 0, 200, false);
        CHECK_EQ((int)lines[1].size(), 160, "exactly-at-budget line untouched");
        std::vector<std::string> over = {std::string(161, 'a')};
        pocketllm::ApplyReplyBudget(over, 0, 200, false);
        CHECK_EQ((int)over[0].size(), 160, "one-over line cuts to 160");
    }
    std::printf("budget leg done\n");
}

int main(int argc, char** argv)
{
    char const* leg = argc > 1 ? argv[1] : "all";
    if (std::strcmp(leg, "scanner") == 0 || std::strcmp(leg, "all") == 0)
        ScannerLeg();
    if (std::strcmp(leg, "whitelist") == 0 || std::strcmp(leg, "all") == 0)
        WhitelistLeg();
    if (std::strcmp(leg, "emotes") == 0 || std::strcmp(leg, "all") == 0)
        EmotesLeg();
    if (std::strcmp(leg, "beats") == 0 || std::strcmp(leg, "all") == 0)
        BeatsLeg();
    if (std::strcmp(leg, "budget") == 0 || std::strcmp(leg, "all") == 0)
        BudgetLeg();
    if (g_failures)
    {
        std::printf("act tools battery FAILED (%d)\n", g_failures);
        return 1;
    }
    std::printf("act tools battery passed\n");
    return 0;
}
