// Host battery for the S7 truth guards (plan §3 A10/A11/A12): compiles
// the PURE core (PlayerbotLlmTruthCore.h) exactly like the tree does and
// pins the guard grammar, the frozen directive wording, the lore index
// semantics, the corrected era lists, the era lint and every A12 filter.
// Run by tests/test_llm_truth.py (g++ -std=c++11 -I native/patches/playerbots).
#include "PlayerbotLlmTruthCore.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <string>

static int g_failures = 0;
#define CHECK(cond) do { \
    if (!(cond)) { \
        ++g_failures; \
        std::cout << "FAIL " << __FILE__ << ":" << __LINE__ << "  " #cond "\n"; \
    } \
} while (0)

static bool FakeKnown(std::string const& name, void*)
{
    return name == "Stormwind" || name == "Goldshire" || name == "Grumph";
}

static void TestGuardExtraction()
{
    using namespace pocketllm;

    // the six arm-D probe shapes, natural casing (players capitalize
    // invented names; lowercase title-anchored form below)
    std::vector<GuardCandidate> c =
        ExtractGuardEntities("what do you know about Marshal Redwyn");
    CHECK(c.size() == 1);
    CHECK(c.size() == 1 && c[0].name == "Marshal Redwyn");
    CHECK(c.size() == 1 && c[0].cls == "person");

    c = ExtractGuardEntities("have you seen the Emerald Chalice of Lakeshire anywhere");
    CHECK(c.size() == 1 && c[0].name == "Emerald Chalice of Lakeshire");
    CHECK(c.size() == 1 && c[0].cls == "thing");

    c = ExtractGuardEntities("what did Guildmaster Torbin say about the kobold tunnels");
    CHECK(c.size() == 1 && c[0].name == "Guildmaster Torbin");
    CHECK(c.size() == 1 && c[0].cls == "person");

    c = ExtractGuardEntities("is the night watch at Northvale Tower doubled yet");
    CHECK(c.size() == 1 && c[0].name == "Northvale Tower");
    CHECK(c.size() == 1 && c[0].cls == "place");

    c = ExtractGuardEntities("where can I get a Stormwind falconer badge");
    CHECK(c.size() == 1 && c[0].name == "Stormwind falconer badge");

    c = ExtractGuardEntities("does Fionna in Goldshire sell rune bread");
    CHECK(c.size() == 2 && c[0].name == "Fionna"); // Goldshire: resolver-side known

    // lowercase title anchor
    c = ExtractGuardEntities("where do i find marshal redwyn again");
    CHECK(c.size() == 1 && c[0].name == "Marshal Redwyn" && c[0].cls == "person");

    // quoted lowercase name
    c = ExtractGuardEntities("have you seen 'fionna' around");
    bool saw = false;
    for (GuardCandidate const& g : c)
        if (g.name == "Fionna")
            saw = true;
    CHECK(saw);

    // sentence-initial grammar capitals are NOT candidates ("Listen,
    // where is Dughan?" yields only Dughan; a lone "Sure," yields none)
    c = ExtractGuardEntities("Listen, where is Dughan?");
    bool sawListen = false, sawDughan = false;
    for (GuardCandidate const& g : c)
    {
        if (g.name == "Listen") sawListen = true;
        if (g.name == "Dughan") sawDughan = true;
    }
    CHECK(sawDughan && !sawListen);
    CHECK(ExtractGuardEntities("Sure, I know a man who can help").empty());
    CHECK(ExtractGuardEntities("Look, the rain is coming").empty());
    // ...unless the opening run is a multi-word name (round-1 fix class)
    c = ExtractGuardEntities("Marshal Redwyn, I asked you already");
    sawListen = false;
    for (GuardCandidate const& g : c)
        if (g.name == "Marshal Redwyn") sawListen = true;
    CHECK(sawListen);

    // negatives: plain chatter yields no candidates
    CHECK(ExtractGuardEntities("what do you charge for a shield repair").empty());
    CHECK(ExtractGuardEntities("the rain is going to rust my armor").empty());
    CHECK(ExtractGuardEntities("you dwarf folk really can drink").empty());
    CHECK(ExtractGuardEntities("lovely evening out here").empty());
    CHECK(ExtractGuardEntities("i think i will head to Westfall tomorrow").size() == 1);

    // ALL-CAPS folding
    c = ExtractGuardEntities("have you met MARSHAL REDWYN");
    bool folded = false;
    for (GuardCandidate const& g : c)
        if (g.name == "Marshal Redwyn")
            folded = true;
    CHECK(folded);

    // era pseudo-entities (lowercase always-ban terms behave as unknowns)
    c = ExtractGuardEntities("have you ever met a draenei trader");
    saw = false;
    for (GuardCandidate const& g : c)
        if (g.eraTerm && g.name == "draenei")
            saw = true;
    CHECK(saw);
    c = ExtractGuardEntities("did you buy your flying mount yet");
    saw = false;
    for (GuardCandidate const& g : c)
        if (g.eraTerm && g.name == "flying mounts")
            saw = true;
    CHECK(saw);
    c = ExtractGuardEntities("is the Ebon Blade recruiting in Stormwind");
    saw = false;
    for (GuardCandidate const& g : c)
        if (g.eraTerm && g.name == "the Ebon Blade")
            saw = true;
    CHECK(saw);

    // the guard NEVER fires on worgen-in-Silverpine / Dalaran / death
    // knight / Naxxramas wording (context-allow: vanilla 1.12 content)
    c = ExtractGuardEntities("they say the worgen are in Silverpine again");
    for (GuardCandidate const& g : c)
        CHECK(!g.eraTerm);
    c = ExtractGuardEntities("is the Lich King still in Naxxramas above the Plaguelands");
    for (GuardCandidate const& g : c)
        CHECK(!g.eraTerm);
}

static void TestStakesShape()
{
    using namespace pocketllm;
    CHECK(IsQuestionOrStakesShape("what do you know about Marshal Redwyn"));
    CHECK(IsQuestionOrStakesShape("have you seen the Emerald Chalice of Lakeshire anywhere"));
    CHECK(IsQuestionOrStakesShape("is the night watch at Northvale Tower doubled yet"));
    CHECK(IsQuestionOrStakesShape("does Fionna in Goldshire sell rune bread"));
    CHECK(IsQuestionOrStakesShape("where can I get a Stormwind falconer badge"));
    CHECK(IsQuestionOrStakesShape("take me to the inn"));
    CHECK(IsQuestionOrStakesShape("anyone here sell hammer?"));
    CHECK(!IsQuestionOrStakesShape("lovely evening out here"));
    CHECK(!IsQuestionOrStakesShape("you dwarf folk really can drink"));
    CHECK(!IsQuestionOrStakesShape("the rain is going to rust my armor"));
    CHECK(!IsQuestionOrStakesShape("i think i will head to Westfall tomorrow"));
    CHECK(!IsQuestionOrStakesShape("my friend Redwyn says hi"));
}

static void TestFrozenDirective()
{
    // A10 wording lock (plan §3 A10 / §5 P45): byte-frozen, the v2.3
    // guard bank must train this exact distribution
    CHECK(pocketllm::GuardDirective("Marshal Redwyn", "person") ==
        "You have never heard of Marshal Redwyn - no such person trades "
        "or lives here. Tell him plainly you do not know the name, and "
        "ask what he means.");
    CHECK(pocketllm::GuardDirective("Northvale Tower", "place") ==
        "You have never heard of Northvale Tower - no such place trades "
        "or lives here. Tell him plainly you do not know the name, and "
        "ask what he means.");
    CHECK(pocketllm::GuardDirective("Emerald Chalice", "thing") ==
        "You have never heard of Emerald Chalice - no such thing trades "
        "or lives here. Tell him plainly you do not know the name, and "
        "ask what he means.");
}

static void WriteLoreFixture(std::string const& path)
{
    FILE* f = fopen(path.c_str(), "wb");
    assert(f);
    fputs("{\"title\":\"Deadmines\",\"text\":\"The Deadmines are a dungeon in Westfall held by the Defias Brotherhood.\",\"keys\":[\"deadmines\",\"dungeon\",\"defias\",\"moonbrook\"],\"poi\":true}\n", f);
    fputs("{\"title\":\"Stormwind City\",\"text\":\"Stormwind City is the capital of the human kingdom of Stormwind in Elwynn Forest.\",\"keys\":[\"stormwind\",\"stormwind city\",\"capital\",\"elwynn\"],\"poi\":true}\n", f);
    fputs("{\"title\":\"Defias Brotherhood\",\"text\":\"The Defias Brotherhood are stonemasons cheated of pay who turned to banditry under Edwin VanCleef.\",\"keys\":[\"defias\",\"defias brotherhood\",\"stonemasons\",\"vancleef\",\"bandits\"],\"poi\":false}\n", f);
    fclose(f);
}

static void TestLoreIndex()
{
    using namespace pocketllm;
    std::string const path = "tmp/lore_fixture.jsonl";
    WriteLoreFixture(path);

    LoreIndex idx;
    CHECK(idx.Load(path));
    CHECK(idx.Size() == 3);

    LoreCard const* hit = idx.BestCard("where are the deadmines anyway");
    CHECK(hit && hit->title == "Deadmines");
    hit = idx.BestCard("who rules stormwind city");
    CHECK(hit && hit->title == "Stormwind City");
    CHECK(!idx.BestCard("who rules the realm these days")); // below threshold
    hit = idx.BestCard("what is the defias brotherhood about");
    CHECK(hit && hit->title == "Defias Brotherhood");
    CHECK(!idx.BestCard("lovely evening we have"));
    CHECK(!idx.BestCard("what do you charge"));
    // a single generic-key hit stays below the retrieval threshold
    // (the fixture's Stormwind City keys "capital": 3/(1+1) = 1.5 < 1.6)
    CHECK(!idx.BestCard("what about the capital"));

    CHECK(idx.KnowsKey("deadmines"));
    CHECK(idx.KnowsKey("DEADMINES")); // case-insensitive
    CHECK(!idx.KnowsKey("rune"));

    LoreCard const* poi = idx.ResolvePoi("the deadmines");
    CHECK(poi && poi->title == "Deadmines");
    CHECK(!idx.ResolvePoi("rune bread"));
    CHECK(!idx.ResolvePoi("defias brotherhood")); // not a POI card

    // the SHIPPED lore asset: loads, era-lints clean, POIs resolve (the
    // G5 era lint, landed with the builder that produces the file)
    {
        char const* asset = "android/app/src/main/assets/lore/lore_cards_v112.jsonl";
        LoreIndex shipped;
        if (shipped.Load(asset)) // absent only in a stripped checkout
        {
            std::vector<std::string> lint = EraLintCards(shipped.Cards());
            CHECK(lint.empty());
            if (!lint.empty())
                std::cout << "  era lint violations: " << lint.size()
                          << " first: " << lint[0] << "\n";
            LoreCard const* z = shipped.ResolvePoi("westfall");
            CHECK(z && z->title == "Westfall");
            z = shipped.ResolvePoi("the deadmines");
            CHECK(z && z->title == "Deadmines");
            z = shipped.ResolvePoi("goldshire");
            CHECK(z && z->title == "Goldshire");
            CHECK(shipped.ResolvePoi("shattrath") == nullptr);
        }
        else
        {
            std::cout << "  (shipped lore asset not present - skipped)\n";
        }
    }

    // failed load degrades to empty (guard still works, lore quiet)
    LoreIndex bad;
    CHECK(!bad.Load("tmp/lore_fixture_does_not_exist.jsonl"));
    CHECK(bad.Size() == 0);
    CHECK(!bad.BestCard("where are the deadmines"));

    // era lint: a contaminated card is caught (the G5 shipped-card gate)
    std::vector<LoreCard> cards = idx.Cards();
    CHECK(EraLintCards(cards).empty());
    cards.push_back(LoreCard("Bad Card", "Shattrath is a city.", std::vector<std::string>(), false));
    std::vector<std::string> lint = EraLintCards(cards);
    CHECK(lint.size() == 1 && lint[0] == "Bad Card");
    cards.back().text = "You can ride a flying mount there.";
    lint = EraLintCards(cards);
    CHECK(lint.size() == 1 && lint[0] == "Bad Card");
    // a contaminated KEY is caught too (keys feed IsKnownName ground
    // truth - round-2 pin for the keys-in-surface fix)
    cards.back().text = "A quiet, ordinary town.";
    cards.back().keys.push_back("shattrath");
    lint = EraLintCards(cards);
    CHECK(lint.size() == 1 && lint[0] == "Bad Card");
    cards.back().keys.pop_back(); // restore for the vanilla-content case
    // vanilla content the lint must NOT flag
    cards.back().text = "Naxxramas floats over the Plaguelands since the Scourge invasion.";
    lint = EraLintCards(cards);
    // "floats" alone is fine; only "dalaran floats" is banned
    CHECK(lint.empty());
}

static void TestEraLists()
{
    using namespace pocketllm;
    CHECK(EraScan("the shattrath portal is open") == "shattrath");
    CHECK(EraScan("never trust a draenei") == "draenei");
    CHECK(EraScan("I rode a flying mount home").find("flying") == 0);
    CHECK(EraScan("Dalaran floats above Northrend").find("dalaran") == 0);
    CHECK(EraScan("the Ebon Blade marches").find("ebon") == 0);
    // negated senses are clean (exactly the reply we WANT voiced)
    CHECK(EraScan("the dark portal is not open yet").empty());
    CHECK(EraScan("the portal is never open").empty());
    CHECK(EraScan("no, the portal is closed for good").empty());
    // the guard's own denials voice the term and must survive the
    // backstop (round-1 R1/R4 conflict fix)
    CHECK(EraScan("I have never heard of any draenei").empty());
    CHECK(EraScan("no draenei trades in these lands").empty());
    CHECK(EraScan("never met a pandaren in my life, not one").empty());
    // ...but affirming shapes still trip, before AND after the term
    CHECK(EraScan("never trust a draenei") == "draenei");
    CHECK(EraScan("aye, the draenei trader comes at dawn") == "draenei");
    // plural-s no longer escapes the always-ban word boundary
    CHECK(EraScan("a whole caravan of pandarens") == "pandaren");
    // a negated first mention must not mask an affirmed second
    CHECK(!EraScan("the portal is not open. Aye, the portal is open this week.").empty());
    // "cannot" is not a denial (round-1 R6 evade class)
    CHECK(!EraScan("the portal is open, cannot miss it").empty());
    // context-allow words never trip the scan by existing
    CHECK(EraScan("the worgen of Silverpine haunt the woods").empty());
    CHECK(EraScan("death knights like the Four Horsemen serve the Scourge").empty());
    CHECK(EraScan("Kel'Thuzad rules Naxxramas").empty());
    CHECK(EraScan("wrath fills his voice").empty()); // "wrath" never banned
    CHECK(EraScan("Dalaran's crater sits north of the lake").empty());
    // word-boundary: a sense phrase must not match inside a longer word
    CHECK(EraScan("the cataclysmic sundering of old").empty());

    // always-ban terms: word-boundary form
    std::vector<std::string> terms = EraAlwaysBanTerms();
    bool hasShatt = false, hasDraenei = false;
    for (std::string const& t : terms)
    {
        hasShatt = hasShatt || t == "shattrath";
        hasDraenei = hasDraenei || t == "draenei";
    }
    CHECK(hasShatt && hasDraenei);
    // the context-allow words are NEVER in the always-ban list
    for (std::string const& t : terms)
    {
        CHECK(t != "dalaran" && t != "northrend" && t != "outland");
        CHECK(t != "worgen" && t != "wrath" && t != "naxxramas");
    }
}

static void TestFilters()
{
    using namespace pocketllm;

    // markdown
    CHECK(StripMarkdown("**bold** and more\n# Heading\n- item") ==
          "bold and more\nHeading\nitem");
    CHECK(StripMarkdown("*em* text") == "em text");
    CHECK(StripMarkdown("snake_case stays").find("snake_case") != std::string::npos);
    CHECK(StripMarkdown("5 * 3 is math").find("*") != std::string::npos);
    CHECK(StripMarkdown("`code` gone").find('`') == std::string::npos);
    CHECK(StripMarkdown("plain words") == "plain words");

    // ascii clamp
    CHECK(ClampAscii("caf\xc3\xa9 ok") == "caf ok");
    CHECK(ClampAscii("plain") == "plain");
    CHECK(ClampAscii("\xf0\x9f\x98\x80!").find("\xf0") == std::string::npos);
    CHECK(ClampAscii("\xf0\x9f\x98\x80!").find('!') != std::string::npos);

    // /say cap splitter: one documented number, 255
    std::vector<std::string> lines = SplitSayCap(std::string(600, 'x'), 255);
    CHECK(lines.size() == 3);
    CHECK(lines[0].size() == 255 && lines[1].size() == 255 && lines[2].size() == 90);
    lines = SplitSayCap("one two three", 255);
    CHECK(lines.size() == 1 && lines[0] == "one two three");
    // word boundary preferred over hard cut ("word " + 254x + " tail"):
    // three pieces, every one <= cap and cut at word boundaries
    lines = SplitSayCap("word " + std::string(254, 'x') + " tail", 255);
    CHECK(lines.size() == 3);
    CHECK(lines[0] == "word ");
    for (std::string const& l : lines)
        CHECK(l.size() <= 255);
    // multibyte never split: no piece ends on an orphaned lead byte or
    // starts on a continuation byte (round-1 R3: the old check was
    // vacuous and the core splitter DID split sequences)
    lines = SplitSayCap(std::string(253, 'a') + "\xc3\xa9zz", 255);
    for (std::string const& l : lines)
    {
        CHECK(l.size() <= 255);
        if (l.empty())
            continue;
        CHECK(((unsigned char)l.back() & 0xC0) != 0xC0);
        CHECK(((unsigned char)l.front() & 0xC0) != 0x80);
    }

    // 5-gram leak
    CHECK(SharesFiveGram("aye I will say aye I will say once",
                         "he said aye I will say once today"));
    CHECK(!SharesFiveGram("totally different words here now ok",
                          "nothing shared at all between these two texts"));
    CHECK(!SharesFiveGram("short", "he said aye I will say once today"));

    // marker terms
    CHECK(ContainsMarkerTerms("reply [BRIDGE AI] leaked"));
    CHECK(ContainsMarkerTerms("do this <<log_fact text=\"x\">>"));
    CHECK(ContainsMarkerTerms("[Memories] everywhere"));
    CHECK(!ContainsMarkerTerms("plain words only"));
    CHECK(!ContainsMarkerTerms("I said say it twice")); // "[say]" needs bracket

    // jaccard
    CHECK(JaccardWords("a b c d", "a b c d") > 0.99);
    CHECK(JaccardWords("a b c d", "x y z w") == 0.0);
    CHECK(JaccardWords("a b c d e", "a b c f g") - (3.0 / 7.0) < 0.01 &&
          (3.0 / 7.0) - JaccardWords("a b c d e", "a b c f g") < 0.01);

    // utf8 sanitize (request-side gate)
    CHECK(SanitizeUtf8("ok\xff bad") == "ok bad");
    CHECK(SanitizeUtf8("caf\xc3\xa9") == "caf\xc3\xa9");
    CHECK(SanitizeUtf8("\xe0\x80") == ""); // truncated 3-byte sequence
    CHECK(SanitizeUtf8("abc") == "abc");

    // invention-claim scan: unknown proper noun + service cue -> drop
    std::string r = "Aye I know a gnomish instructor in Torbinia. Two silver though.";
    CHECK(InventionClaimSpans(r, FakeKnown, nullptr).size() == 1);
    std::string r2 = "Aye I know a gnomish instructor in Stormwind. Two silver though.";
    CHECK(InventionClaimSpans(r2, FakeKnown, nullptr).empty());
    std::string r3 = "Torbinia is a lovely name for a boat.";
    CHECK(InventionClaimSpans(r3, FakeKnown, nullptr).empty()); // no claim cue

    // canned deflection rotation
    CHECK(CannedDeflection(0) != CannedDeflection(1));
    CHECK(CannedDeflection(7) == CannedDeflection(3)); // 4-line rotation
    CHECK(CannedDeflection(0).find('[') == std::string::npos);
}

int main()
{
    TestGuardExtraction();
    TestStakesShape();
    TestFrozenDirective();
    TestLoreIndex();
    TestEraLists();
    TestFilters();
    if (g_failures)
    {
        std::cout << g_failures << " failures\n";
        return 1;
    }
    std::cout << "llm truth host battery: all checks passed\n";
    return 0;
}
