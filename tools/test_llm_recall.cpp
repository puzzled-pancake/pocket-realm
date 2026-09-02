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
    // the measured table's shapes, byte-pinned (the plan quotes these)
    std::string const debt = DebtCargo("Brannoc", "he owes me 5 silver from the ale");
    CHECK(debt == "Brannoc owes you 5 silver from the ale. "
                  "It is UNPAID. You are NOT square. Name it.");
    std::string const news = NewsCargo("Brannoc", "he beat you in a duel, fair and square");
    CHECK(news == "Something DID happen: beat you in a duel, fair and square. "
                  "That is your news, with Brannoc. Tell it.");
    std::string const memory = MemoryCargo("Brannoc", "hates spiders since the basement job", false);
    CHECK(memory == "You DO remember Brannoc. One true thing: hates spiders "
                    "since the basement job. Work it in - once, naturally.");
    std::string const askAfter = MemoryCargo("Brannoc", "is saving for a ram", true);
    CHECK(askAfter == "You remember what Brannoc was after: is saving for a ram. "
                      "Ask after it, like it matters - because it does.");
    std::string const grudge = GrudgeCargo("called your cooking pig swill");
    CHECK(grudge == "You are still sore about this: called your cooking pig swill. "
                    "Let it show at the edges only - never name the grudge outright.");
    std::string const gossip = GossipCargo("Brannoc", "won a duel against Grumph");
    CHECK(gossip == "Word is going round about Brannoc: 'won a duel against Grumph'. "
                    "Ask about it, lightly - one question, no more.");
    // the ceremony wording (plan verbatim shape; never names the mechanic)
    std::string const up4 = CeremonyUpCargo("Brannoc", 4);
    CHECK(up4 == "You have quietly decided Brannoc is a true friend. "
                 "Show it your own way, briefly - and do not explain yourself.");
    CHECK(CeremonyUpCargo("Brannoc", 3).find("a friend worth keeping") != std::string::npos);
    CHECK(CeremonyUpCargo("Brannoc", 5).find("the one you would follow anywhere") != std::string::npos);
    CHECK(CeremonyDownCargo("Brannoc").find("colder") != std::string::npos);
    CHECK(CeremonyDownCargo("Brannoc").find("tier") == std::string::npos);
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
    CHECK(FACT_MASK_DEBT != FACT_DEBT);
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

int main()
{
    TestFactClasses();
    TestQuestionShapes();
    TestFactDirect();
    TestCargoWording();
    TestDeterminism();
    TestMoneyAndDistortion();
    TestAuthoredShapes();
    if (g_failures)
    {
        std::cout << g_failures << " failures\n";
        return 1;
    }
    std::cout << "llm recall host battery: all checks passed\n";
    return 0;
}
