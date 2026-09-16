// Host battery for the S8 memory-USE layer (plan A13/A16/A17/A18/A19
// pure machinery): compiles the PURE core (PlayerbotLlmRecallCore.h,
// which includes PlayerbotLlmToolsCore.h) exactly like the tree does
// and pins the fact classifier, the recall question shapes, the
// beat-cargo wording (the recallfix measured table - cargo, never bare
// instructions), the FactDirect address transform, the ceremony/
// nickname/secret picks, the money phrase, the authored initiative line
// shapes and the gossip distortion transforms. Run by
// tests/test_llm_recall.py (g++ -std=c++11 -I native/patches/playerbots);
// the world-side glue (the bridge ladder, the driver anchors, the
// memory SQL) is pinned by source-contract assertions there.
#include "PlayerbotLlmRecallCore.h"

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

static void TestFactClasses()
{
    using namespace pocketllm;
    CHECK(FactClassOf("owes me five silver from the ale", "shared-event") == FACT_DEBT);
    CHECK(FactClassOf("he owes you two gold", "opinion") == FACT_DEBT);
    CHECK(FactClassOf("still owes for the boots", "shared-event") == FACT_DEBT);
    CHECK(FactClassOf("borrowed a hammer and kept it", "shared-event") == FACT_DEBT);
    // word-boundary: "respond" must not carry "owes" (there is no such
    // overlap, but the guard is ContainsWord-style cue matching on
    // substrings of the cue list - pin the negative class cases)
    CHECK(FactClassOf("loves the rain in Elwynn", "preference") == FACT_PLAIN);
    CHECK(FactClassOf("beat you in a duel, fair and square", "shared-event") == FACT_EVENT);
    CHECK(FactClassOf("is saving for a ram", "preference") == FACT_GOAL);
    CHECK(FactClassOf("wants to learn smithing", "shared-event") == FACT_GOAL);
    CHECK(FactClassOf("training to ride", "player-identity") == FACT_GOAL);
    // debt outranks event/goal (the debt cue list runs first)
    CHECK(FactClassOf("owes me silver after the duel", "shared-event") == FACT_DEBT);
}

static void TestQuestionShapes()
{
    using namespace pocketllm;
    CHECK(IsDebtQuestion("wait, do i owe you anything?"));
    CHECK(IsDebtQuestion("so are we square or not"));
    CHECK(IsDebtQuestion("did i pay you back yet"));
    CHECK(!IsDebtQuestion("i don't owe you anything, stop asking"));
    CHECK(!IsDebtQuestion("where do i get a hammer"));
    CHECK(IsMemoryQuestion("what do you remember about me"));
    CHECK(IsMemoryQuestion("do you remember me"));
    CHECK(!IsMemoryQuestion("do you remember the way to Goldshire"));
    CHECK(!IsMemoryQuestion("remember to bring rope"));
}

static void TestFactDirect()
{
    using namespace pocketllm;
    CHECK(FactDirect("owes me five silver from the ale") ==
          "owes you five silver from the ale");
    CHECK(FactDirect("he owes me five silver") == "owes you five silver");
    CHECK(FactDirect("she borrowed my whetstone") == "borrowed your whetstone");
    CHECK(FactDirect("hates spiders since the basement job") ==
          "hates spiders since the basement job");
    // the swap is word-boundary: "metal"/"army" survive untouched
    CHECK(FactDirect("left my metal file in the army crate") ==
          "left your metal file in the army crate");
}

static void TestCargoWording()
{
    using namespace pocketllm;
    // the measured table's shapes, byte-pinned per FLAVOR (rev-3b: six
    // persona-flavored variants per kind, GUID-stable per bot - flavor 0 is
    // the original S8 wording; 30/31/32 are three consecutive guids so each
    // pins one flavor)
    std::string const debt = DebtCargo("Brannoc", "he owes me 5 silver from the ale", 30);
    CHECK(debt == "Brannoc owes you 5 silver from the ale. "
                  "It is UNPAID. You are NOT square. Name it.");
    CHECK(DebtCargo("Brannoc", "he owes me 5 silver from the ale", 31) ==
          "Brannoc owes you 5 silver from the ale. That debt stands - "
          "nothing square about it. Bring it up yourself this time.");
    CHECK(DebtCargo("Brannoc", "he owes me 5 silver from the ale", 32) ==
          "Brannoc owes you 5 silver from the ale. Still unpaid, and you "
          "keep the count. Say it plainly when it fits.");
    std::string const news = NewsCargo("Brannoc", "he beat you in a duel, fair and square", 30);
    CHECK(news == "Something DID happen: beat you in a duel, fair and square. "
                  "That is your news, with Brannoc. Tell it.");
    CHECK(NewsCargo("Brannoc", "he beat you in a duel, fair and square", 31) ==
          "Something DID happen: beat you in a duel, fair and square. "
          "That is your news, with Brannoc. Tell it plainly.");
    CHECK(NewsCargo("Brannoc", "he beat you in a duel, fair and square", 32) ==
          "Something DID happen: beat you in a duel, fair and square. "
          "Brannoc has not heard this yet. Tell it.");
    std::string const memory = MemoryCargo("Brannoc", "hates spiders since the basement job", false, 30);
    CHECK(memory == "You DO remember Brannoc. One true thing: hates spiders "
                    "since the basement job. Work it in - once, naturally.");
    CHECK(MemoryCargo("Brannoc", "hates spiders since the basement job", false, 31) ==
          "You DO remember Brannoc. Here is the thing you know: hates "
          "spiders since the basement job. Let it slip in - once, as your own.");
    CHECK(MemoryCargo("Brannoc", "hates spiders since the basement job", false, 32) ==
          "You DO remember Brannoc. This much is true: hates spiders "
          "since the basement job. Weave it in once, easy - never like reciting.");
    std::string const askAfter = MemoryCargo("Brannoc", "is saving for a ram", true, 30);
    CHECK(askAfter == "You remember what Brannoc was after: is saving for a ram. "
                      "Ask after it, like it matters - because it does.");
    CHECK(MemoryCargo("Brannoc", "is saving for a ram", true, 31) ==
          "You remember what Brannoc was after: is saving for a ram. "
          "Ask - and sound like you cared enough to keep it.");
    CHECK(MemoryCargo("Brannoc", "is saving for a ram", true, 32) ==
          "You remember what Brannoc was after: is saving for a ram. "
          "Ask after it warmly; you have been waiting to hear.");
    std::string const grudge = GrudgeCargo("called your cooking pig swill", 30);
    CHECK(grudge == "You are still sore about this: called your cooking pig swill. "
                    "Let it show at the edges only - never name the grudge outright.");
    CHECK(GrudgeCargo("called your cooking pig swill", 31) ==
          "It still stings, this: called your cooking pig swill. Keep it "
          "under the words - never lay the grudge on the table.");
    CHECK(GrudgeCargo("called your cooking pig swill", 32) ==
          "An old sore, not healed: called your cooking pig swill. Let it "
          "color the edges - the grudge itself stays unsaid.");
    std::string const gossip = GossipCargo("Brannoc", "won a duel against Grumph", 30);
    CHECK(gossip == "Word is going round about Brannoc: 'won a duel against Grumph'. "
                    "Ask about it, lightly - one question, no more.");
    CHECK(GossipCargo("Brannoc", "won a duel against Grumph", 31) ==
          "There is talk about Brannoc: 'won a duel against Grumph'. "
          "One light question - then let it drop.");
    CHECK(GossipCargo("Brannoc", "won a duel against Grumph", 32) ==
          "The town buzzes a little about Brannoc: 'won a duel against Grumph'. "
          "Ask, gently - once only.");
    // the ceremony wording (the frozen verbatim shape; never names the mechanic)
    std::string const up4 = CeremonyUpCargo("Brannoc", 4, 30);
    CHECK(up4 == "You have quietly decided Brannoc is a true friend. "
                 "Show it your own way, briefly - and do not explain yourself.");
    CHECK(CeremonyUpCargo("Brannoc", 4, 31) ==
          "Something has settled in you: Brannoc is a true friend. Let it "
          "show in what you do - brief, and unexplained.");
    CHECK(CeremonyUpCargo("Brannoc", 4, 32) ==
          "You know it now, quietly: Brannoc is a true friend. Show it "
          "once, your own way - no speeches.");
    CHECK(CeremonyUpCargo("Brannoc", 3, 30).find("a friend worth keeping") != std::string::npos);
    CHECK(CeremonyUpCargo("Brannoc", 5, 30).find("the one you would follow anywhere") != std::string::npos);
    // the tier phrase survives in every flavor
    CHECK(CeremonyUpCargo("Brannoc", 5, 31).find("the one you would follow anywhere") != std::string::npos);
    CHECK(CeremonyUpCargo("Brannoc", 5, 32).find("the one you would follow anywhere") != std::string::npos);
    CHECK(CeremonyDownCargo("Brannoc", 30).find("colder") != std::string::npos);
    CHECK(CeremonyDownCargo("Brannoc", 30).find("tier") == std::string::npos);
    CHECK(CeremonyDownCargo("Brannoc", 31) ==
          "The warmth has thinned toward Brannoc. Say nothing of it. "
          "Let your actions carry the chill.");
    CHECK(CeremonyDownCargo("Brannoc", 32) ==
          "You trust Brannoc less than you did. Keep that to yourself - "
          "it shows in deeds, never in words.");
// flavors 3-5 (guids 33/34/35): byte-pinned like 0-2 above
    CHECK(DebtCargo("Brannoc", "he owes me 5 silver from the ale", 33) ==
          "Brannoc owes you 5 silver from the ale. The ledger does not forget, and neither do you. Collect with a grin, not a snarl.");
    CHECK(DebtCargo("Brannoc", "he owes me 5 silver from the ale", 34) ==
          "Brannoc owes you 5 silver from the ale. Owed is owed. Remind them the way old friends do - sharp, fond, and impossible to dodge.");
    CHECK(DebtCargo("Brannoc", "he owes me 5 silver from the ale", 35) ==
          "Brannoc owes you 5 silver from the ale. Debts age like ale with you: stronger, louder, and harder to ignore. Say so.");
    CHECK(NewsCargo("Brannoc", "he beat you in a duel, fair and square", 33) ==
          "News, and it is yours to carry: beat you in a duel, fair and square. Brannoc gets it from you first - make it land.");
    CHECK(NewsCargo("Brannoc", "he beat you in a duel, fair and square", 34) ==
          "You were there - or heard it from one who was: beat you in a duel, fair and square. Tell Brannoc straight.");
    CHECK(NewsCargo("Brannoc", "he beat you in a duel, fair and square", 35) ==
          "This just happened, and Brannoc should hear it from a friend: beat you in a duel, fair and square. Tell it whole.");
    CHECK(MemoryCargo("Brannoc", "hates spiders since the basement job", false, 33) ==
          "That thing about Brannoc - hates spiders since the basement job - has been sitting with you. Spend it now, once, like it just surfaced.");
    CHECK(MemoryCargo("Brannoc", "hates spiders since the basement job", false, 34) ==
          "You carry this about Brannoc: hates spiders since the basement job. Drop it into the talk sideways, the way real remembering works.");
    CHECK(MemoryCargo("Brannoc", "hates spiders since the basement job", false, 35) ==
          "Of all you know of Brannoc, this rises now: hates spiders since the basement job. Voice it once, then let the moment pass.");
    CHECK(MemoryCargo("Brannoc", "is saving for a ram", true, 33) ==
          "That goal of Brannoc - is saving for a ram - still hangs open. Pull the thread: how fares it?");
    CHECK(MemoryCargo("Brannoc", "is saving for a ram", true, 34) ==
          "Brannoc wanted this: is saving for a ram. You kept it in mind all this time. Ask, and mean it.");
    CHECK(MemoryCargo("Brannoc", "is saving for a ram", true, 35) ==
          "So - Brannoc and is saving for a ram. Time has passed; curiosity has not. Ask after it properly.");
    CHECK(GrudgeCargo("called your cooking pig swill", 33) ==
          "That old business - called your cooking pig swill - still smarts when pressed. Let the chill show in what you do, not what you say.");
    CHECK(GrudgeCargo("called your cooking pig swill", 34) ==
          "called your cooking pig swill. You have not forgotten, and forgiveness is not on the menu tonight. Edge, not accusation.");
    CHECK(GrudgeCargo("called your cooking pig swill", 35) ==
          "The scar of it - called your cooking pig swill - itches in this company. Short answers, long memory.");
    CHECK(GossipCargo("Brannoc", "won a duel against Grumph", 33) ==
          "Something is being said about Brannoc: 'won a duel against Grumph'. Poke at it once, softly - then leave it be.");
    CHECK(GossipCargo("Brannoc", "won a duel against Grumph", 34) ==
          "You caught wind of this about Brannoc: 'won a duel against Grumph'. One curious question, no more.");
    CHECK(GossipCargo("Brannoc", "won a duel against Grumph", 35) ==
          "Rumor brushes Brannoc: 'won a duel against Grumph'. Brush back - lightly, once, and watch the reaction.");
    CHECK(CeremonyUpCargo("Brannoc", 4, 33) ==
          "It has crept up on you: Brannoc is a true friend to you now. A warmer word, unannounced.");
    CHECK(CeremonyUpCargo("Brannoc", 4, 34) ==
          "No ceremony, no speech - but Brannoc is a true friend, and it shows in how you stand nearer.");
    CHECK(CeremonyUpCargo("Brannoc", 4, 35) ==
          "You catch yourself smiling when Brannoc arrives. That is new. That is a true friend. Let it be seen, briefly.");
    CHECK(CeremonyDownCargo("Brannoc", 33) ==
          "A small frost where Brannoc is concerned. No words about it - fewer favors, slower nods.");
    CHECK(CeremonyDownCargo("Brannoc", 34) ==
          "You hold Brannoc a little more at arm's length now. Polite. Distant. Final.");
    CHECK(CeremonyDownCargo("Brannoc", 35) ==
          "The easy warmth with Brannoc is gone. Courtesy remains; closeness does not.");

    // the flavor law: GUID-stable, rotating across consecutive guids, and
    // ONE flavor per bot across every cargo class (six flavors per kind)
    CHECK(CargoFlavor(30) == 0 && CargoFlavor(31) == 1 && CargoFlavor(35) == 5);
    CHECK(CargoFlavor(36) == 0);
    CHECK(DebtCargo("P", "owes me 1 copper", 77) ==
          DebtCargo("P", "owes me 1 copper", 77));
    {
        // every kind rotates six DISTINCT frames across six consecutive
        // guids (a collapsed or duplicated emitted array fails here)
        for (int g0 : {90, 96, 102})
            for (int i = 0; i < 6; ++i)
                for (int j = i + 1; j < 6; ++j)
                {
                    unsigned const gi = (unsigned)(g0 + i), gj = (unsigned)(g0 + j);
                    CHECK(DebtCargo("P", "owes me 1 copper", gi) != DebtCargo("P", "owes me 1 copper", gj));
                    CHECK(NewsCargo("P", "won the dice game", gi) != NewsCargo("P", "won the dice game", gj));
                    CHECK(MemoryCargo("P", "hates spiders", false, gi) != MemoryCargo("P", "hates spiders", false, gj));
                    CHECK(MemoryCargo("P", "is saving for a ram", true, gi) != MemoryCargo("P", "is saving for a ram", true, gj));
                    CHECK(GrudgeCargo("called the stew thin", gi) != GrudgeCargo("called the stew thin", gj));
                    CHECK(GossipCargo("P", "won a duel", gi) != GossipCargo("P", "won a duel", gj));
                    CHECK(CeremonyUpCargo("P", 4, gi) != CeremonyUpCargo("P", 4, gj));
                    CHECK(CeremonyDownCargo("P", gi) != CeremonyDownCargo("P", gj));
                }
    }
    // ---- the long-form cue (frozen bytes) + the licensing
    // layer and its trigger shapes
    CHECK(std::string(kLongFormCue) ==
          "This one is worth telling properly - take a full breath "
          "and tell it whole, start to end.");
    CHECK(LongFormLicensed(225));
    CHECK(LongFormLicensed(300));
    CHECK(!LongFormLicensed(224));
    CHECK(!LongFormLicensed(200));
    // longForm dial: 0 raises the bar to 300, 100 lowers to 150
    CHECK(LongFormLicensed(225, 50));
    CHECK(!LongFormLicensed(225, 0));
    CHECK(LongFormLicensed(300, 0));
    CHECK(LongFormLicensed(150, 100));
    // mid-band probes: the stepped dial holds BETWEEN endpoints too
    // (dial 70 sits in the 26-74 rung: bar 225; dial 80 in the >=75 rung)
    CHECK(!LongFormLicensed(224, 70));
    CHECK(LongFormLicensed(150, 80));
    CHECK(!LongFormLicensed(149, 80));
    CHECK(!LongFormLicensed(149, 100));
    CHECK(LongFormLicensed(225, 999));
    CHECK(WantsStorytelling("come on, tell me a story from the road"));
    CHECK(WantsStorytelling("what happened at the tower last night?"));
    CHECK(WantsStorytelling("walk me through the fight"));
    CHECK(!WantsStorytelling("any news?"));
    CHECK(!WantsStorytelling("i sold my axe today"));
    CHECK(WantsOpenConfidence("so, how have you been really?"));
    CHECK(WantsOpenConfidence("tell me about yourself sometime"));
    CHECK(!WantsOpenConfidence("how much for the hammer"));
    CHECK(!WantsOpenConfidence("follow me to the quarry"));
    // the system-colored progression line riding the observed
    // crossing - never names the mechanic, ASCII only, both directions
    CHECK(TierShiftSysLine("Kromgrit", true) ==
          "Kromgrit seems warmer toward you.");
    CHECK(TierShiftSysLine("Kromgrit", false) ==
          "Kromgrit seems colder toward you.");
    CHECK(TierShiftSysLine("Kromgrit", true).find("tier") == std::string::npos);
    CHECK(TierShiftSysLine("Kromgrit", true).find("relationship") == std::string::npos);
    // the secret cargo names the secret and the one-time shape
    std::string const secret = SecretCargo("Brannoc", 3);
    CHECK(secret.find("You trust Brannoc enough for the one thing you keep") == 0);
    CHECK(secret.find("Tell it once, briefly, as your own choice") != std::string::npos);
    CHECK(SecretCargo("Brannoc", 3) == SecretCargo("Brannoc", 3));
    CHECK(SecretCargo("Brannoc", 3) != SecretCargo("Brannoc", 4));
}

static void TestDeterminism()
{
    using namespace pocketllm;
    // nickname: GUID-stable, bounded shape
    CHECK(NicknameOf("Brannoc", 7) == "Bran");
    CHECK(NicknameOf("Brannoc", 7) == NicknameOf("Brannoc", 99));
    CHECK(NicknameOf("Ash", 1) == "Ash"); // short names unchanged
    CHECK(NicknameOf("Daevin", 2) == "Daev");
    std::string const note = NicknameTierNote("Brannoc", 7);
    CHECK(note == "You have a private name for {player} - \"Bran\" - and it "
                  "slips out more often than their real name.");
    // the tier-5 ceremony's PROCEDURE form (the Westfall law: the
    // standing tierNote alone measured 0/3 - the adoption cargo is the
    // load-bearing surface)
    std::string const adopt = NicknameAdoptionCargo("Brannoc", 7);
    CHECK(adopt == "You have taken to calling Brannoc by a private name of "
                   "your own: Bran. Use it in this reply - and when it "
                   "suits you after.");
    // case-sensitive word-boundary matching (the GossipAbout fix)
    CHECK(ContainsWordExact("Ash lost a duel to Bygdok", "Ash"));
    CHECK(!ContainsWordExact("the ash of the fire", "Ash"));
    CHECK(!ContainsWordExact("Ashmar lost a duel", "Ash"));
    // mask constants: a raw enum value is not its own mask
    CHECK(FACT_MASK_DEBT == (1 << FACT_DEBT));
    CHECK((int)FACT_MASK_DEBT != (int)FACT_DEBT);
    CHECK(BackstorySecretOf(11) == BackstorySecretOf(11));
    // the secret bank is verb-phrased (the cargo prefix is "you ...")
    for (uint32_t guid = 0; guid < 24; ++guid)
    {
        std::string const s = BackstorySecretOf(guid);
        CHECK(!s.empty() && s[0] != ' ');
    }
}

static void TestMoneyAndDistortion()
{
    using namespace pocketllm;
    CHECK(MoneyPhrase("owes me five silver from the ale") == "five silver");
    CHECK(MoneyPhrase("borrowed two gold and a smile") == "two gold");
    CHECK(MoneyPhrase("took ten copper for the torch") == "ten copper");
    CHECK(MoneyPhrase("loves the rain").empty());

    // distortion: money inflates exactly one rung per hop (deterministic)
    CHECK(DistortGossipHop("Brannoc owes five silver over dice", 1) ==
          "Brannoc owes ten silver over dice");
    CHECK(DistortGossipHop("Brannoc owes five silver over dice", 0) ==
          "Brannoc owes ten silver over dice");
    CHECK(DistortGossipHop("won a hundred gold in the mines", 1) ==
          "won a hundred gold in the mines"); // top rung: no change
    // certainty sharpens, copying the matched hedge's case
    CHECK(DistortGossipHop("they say he beat the marshal", 3) ==
          "everyone says he beat the marshal");
    CHECK(DistortGossipHop("It is said the vault was empty", 3) ==
          "It is known the vault was empty");
    // untouched when neither transform applies
    CHECK(DistortGossipHop("a bard played at the inn", 5) ==
          "a bard played at the inn");
}

static void TestCuriosityBank()
{
    using namespace pocketllm;
    // The 16-wide question bank - ASCII, bounded length,
    // {P} rendered, and the out-of-range guard returns empty
    CHECK(CuriosityQuestionCount() == 16);
    for (size_t i = 0; i < CuriosityQuestionCount(); ++i)
    {
        std::string q = CuriosityQuestionLine(i, "Brannoc");
        CHECK(!q.empty());
        CHECK(q.find("Brannoc") != std::string::npos);
        CHECK(q.find("{P}") == std::string::npos);
        bool ascii = true;
        for (char c : q)
            if (static_cast<unsigned char>(c) < 0x20 || static_cast<unsigned char>(c) > 0x7E)
                ascii = false;
        CHECK(ascii);
        CHECK(q.size() >= 20 && q.size() <= 200);
    }
    CHECK(CuriosityQuestionLine(99, "Brannoc").empty());
    CHECK(CuriosityQuestionLine(3, "Ash") == CuriosityQuestionLine(3, "Ash"));
    // distinctness: the bank must not carry a duplicate question (the
    // cargo distinctness law - a repeated ask breaks the once-per-question
    // promise in spirit)
    for (size_t i = 0; i < CuriosityQuestionCount(); ++i)
        for (size_t j = i + 1; j < CuriosityQuestionCount(); ++j)
            CHECK(CuriosityQuestionLine(i, "Ash") != CuriosityQuestionLine(j, "Ash"));
}

static void TestAuthoredShapes()
{
    using namespace pocketllm;
    CHECK(!AbsenceMagnitudeLine("a few moments").empty() == false);
    CHECK(AbsenceMagnitudeLine("a few hours") ==
          "It has been a few hours since you passed this way.");
    CHECK(AbsenceMagnitudeLine("most of a day") ==
          "Near a full day since you last passed.");
    CHECK(AbsenceMagnitudeLine("many days") ==
          "It has been days since you last passed this way.");
    CHECK(DebtReminderLine("Brannoc", "five silver") ==
          "The coin, Brannoc: five silver. I keep an honest count.");
    CHECK(DebtReminderLine("Brannoc", "") ==
          "That debt of yours still stands, Brannoc. I have not forgotten.");
    CHECK(GoalAskAfterLine("Brannoc", "is saving for a ram") ==
          "I remember what you were after, Brannoc: 'is saving for a ram'. How goes it?");
}

static void TestLegends()
{
    using namespace pocketllm;
    // anniversary buckets: 30/100/365 with EXACT boundaries (the >= law),
    // below-30 silent
    CHECK(AnniversaryBucket(0) == 0);
    CHECK(AnniversaryBucket(29) == 0);
    CHECK(AnniversaryBucket(30) == 30);
    CHECK(AnniversaryBucket(99) == 30);
    CHECK(AnniversaryBucket(100) == 100);
    CHECK(AnniversaryBucket(364) == 100);
    CHECK(AnniversaryBucket(365) == 365);
    CHECK(AnniversaryBucket(400) == 365);
    CHECK(std::string(AnniversaryLine(0)).empty());
    CHECK(std::string(AnniversaryLine(100)) ==
          "A hundred days of crossings. The road keeps bringing you back.");
    CHECK(std::string(AnniversaryLine(30)).find("month") != std::string::npos);
    CHECK(std::string(AnniversaryLine(365)).find("year") != std::string::npos);
    // counter escalation: silent at 0, escalates 1-4 (byte-pinned - a
    // substring pin once let a "Practically a legend" mutant through),
    // then the 5-cap RETIRES (telling 5 renders the plain fact)
    CHECK(std::string(CounterEscalation(0)).empty());
    CHECK(std::string(CounterEscalation(1)) == "Once more, then.");
    CHECK(std::string(CounterEscalation(2)) == "Again - the telling grows.");
    CHECK(std::string(CounterEscalation(3)) == "Still the talk. The legend thickens.");
    CHECK(std::string(CounterEscalation(4)) == "Practically legend by now.");
    CHECK(LegendCounterLine("Beat Brannoc fair.", 0) == "Beat Brannoc fair.");
    CHECK(LegendCounterLine("Beat Brannoc fair.", 1) == "Beat Brannoc fair. Once more, then.");
    CHECK(LegendCounterLine("Beat Brannoc fair.", 4) == "Beat Brannoc fair. Practically legend by now.");
    CHECK(LegendCounterLine("Beat Brannoc fair.", 5) == "Beat Brannoc fair.");
    CHECK(LegendCounterLine("Beat Brannoc fair.", 9) == "Beat Brannoc fair.");
    // tier beats: three kinds, GUID-flavored, player-named, marker-free
    for (int k = 0; k < 3; ++k)
    {
        std::string beat = TierBeatCargo("Brannoc", k, 7u);
        CHECK(beat.find("Brannoc") != std::string::npos);
        CHECK(beat.find("<<") == std::string::npos && beat.find(">>") == std::string::npos);
    }
    CHECK(TierBeatCargo("Brannoc", 99, 7u).find("Brannoc") != std::string::npos);
    // journal-facing beats: third-person prose (never a bot instruction)
    CHECK(std::string(TierBeatJournalLine(0)) ==
          "Would vouch for you anywhere; that word was earned.");
    CHECK(std::string(TierBeatJournalLine(1)) ==
          "The old debt is settled square; the air is lighter for it.");
    CHECK(std::string(TierBeatJournalLine(2)) ==
          "Sharp, fond bickering - the kind only old friends can afford.");
    // the authored legend surface is ASCII-only: these lines ride the
    // whisper/journal paths that bypass the LLM output clamp, and the
    // 1.12 client renders anything wider as mojibake
    {
        auto ascii = [](std::string const& s)
        {
            for (size_t i = 0; i < s.size(); ++i)
                if ((unsigned char)s[i] > 127)
                {
                    CHECK(false);
                    return;
                }
        };
        for (int k = 0; k < 3; ++k)
            for (unsigned g = 0; g < 6; ++g)
                ascii(TierBeatCargo("Brannoc", k, g));
        for (int t = 0; t <= 5; ++t)
            ascii(LegendCounterLine("Beat Brannoc fair.", t));
        ascii(AnniversaryLine(30));
        ascii(AnniversaryLine(100));
        ascii(AnniversaryLine(365));
        ascii(TierBeatJournalLine(0));
        ascii(TierShiftSysLine("Kromgrit", true));
        ascii(TierShiftSysLine("Kromgrit", false));
    }
    // POI-biased sampling: place-named rows travel farther
    static char const* const pois[] = { "Goldshire", "Deadmines" };
    CHECK(RumorNamesPlace("Brannoc won at Goldshire.", pois, 2));
    CHECK(!RumorNamesPlace("Brannoc won a duel.", pois, 2));
    CHECK(!RumorNamesPlace("Brannoc won a duel.", 0, 0));
}

int main()
{
    TestFactClasses();
    TestQuestionShapes();
    TestFactDirect();
    TestCargoWording();
    TestDeterminism();
    TestMoneyAndDistortion();
    TestAuthoredShapes();
    TestCuriosityBank();
    TestLegends();
    if (g_failures)
    {
        std::cout << g_failures << " failures\n";
        return 1;
    }
    std::cout << "llm recall host battery: all checks passed\n";
    return 0;
}
