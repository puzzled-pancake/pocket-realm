// Host battery for PlayerbotLlmGates.h (plan v2.3 T1). The header is
// pure - no server includes - so this exercises every predicate over
// the full decision matrix the world sites consume, including the
// negative pins: key-on + tier-off must equal device behavior
// byte-for-byte (0.13), party stays addressed-only until the arm is
// enabled, bot-authored lines never trigger, and the street ladder's
// ORDER is the contract.
#include "PlayerbotLlmGates.h"

#include <cassert>
#include <cstdio>
#include <string>
#include <vector>

using namespace PlayerbotLlmGates;
using u32 = std::uint32_t;
using u64 = std::uint64_t;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { ++failures; \
    std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); } } while (0)

static void cloud_lane_open_matrix()
{
    // the conjunction law: key AND providerSafe AND ctx >= 65536
    CHECK(CloudLaneOpen(true, 1, 65536));
    CHECK(CloudLaneOpen(true, 1, 131072));
    CHECK(!CloudLaneOpen(false, 1, 131072)); // key off: nothing widens
    CHECK(!CloudLaneOpen(true, 0, 131072));  // device lane (no providerSafe)
    CHECK(!CloudLaneOpen(true, 1, 65535));   // tier ctx below the floor
    CHECK(!CloudLaneOpen(true, 0, 4096));    // a typical device emission
}

static void hard_trigger_matrix()
{
    u32 const P = GATE_SRC_PARTY, R = GATE_SRC_RAID, S = GATE_SRC_SAY,
                 W = GATE_SRC_WHISPER, T = GATE_SRC_TRADE,
                 G = GATE_SRC_GENERAL, Y = GATE_SRC_YELL;

    // whisper: always, both lanes, addressed or not
    CHECK(HardTriggerAllowed(W, false, false, false));
    CHECK(HardTriggerAllowed(W, true, true, true));

    // party addressed from a REAL player: both lanes
    CHECK(HardTriggerAllowed(P, true, true, false));
    CHECK(HardTriggerAllowed(P, true, true, true));
    // A3.3: a bot naming a bot never spends a turn (the addressed
    // bot-authored hole)
    CHECK(!HardTriggerAllowed(P, true, false, true));
    CHECK(!HardTriggerAllowed(R, true, false, true));
    // party UNADDRESSED: only cloud + the opt-in arm (default off)
    CHECK(!HardTriggerAllowed(P, false, true, true));           // arm default
    CHECK(!HardTriggerAllowed(P, false, true, true, false));    // explicit
    CHECK(HardTriggerAllowed(P, false, true, true, true));      // armed
    CHECK(!HardTriggerAllowed(P, false, true, false, true));    // device lane
    CHECK(!HardTriggerAllowed(P, false, false, true, true));    // bot speaker
    CHECK(HardTriggerAllowed(R, false, true, true, true));      // raid parity
    // say: name-addressed + real player, both lanes
    CHECK(HardTriggerAllowed(S, true, true, false));
    CHECK(HardTriggerAllowed(S, true, true, true));
    CHECK(!HardTriggerAllowed(S, false, true, true));  // unaddressed say never
    CHECK(!HardTriggerAllowed(S, true, false, true));  // bot say never
    // never-triggers
    CHECK(!HardTriggerAllowed(T, true, true, true));
    CHECK(!HardTriggerAllowed(G, true, true, true));
    CHECK(!HardTriggerAllowed(Y, true, true, true));
    CHECK(!HardTriggerAllowed(GATE_SRC_UNDEFINED, true, true, true));
}

static void reply_gate_matrix()
{
    u32 const P = GATE_SRC_PARTY;
    // today's == 2 + strategy leg stays (the external baseline)
    CHECK(ReplyGateAllowed(P, 2, true, false, false));
    // == 3 hand-conf lane unchanged
    CHECK(ReplyGateAllowed(P, 3, false, false, false));
    // no strategy + device lane: no reply (today's device behavior when
    // the strategy is absent)
    CHECK(!ReplyGateAllowed(P, 2, false, false, false));
    // the A1 widening: no strategy but the cloud lane is open
    CHECK(ReplyGateAllowed(P, 2, false, true, false));
    // blocked channels and undefined never pass
    CHECK(!ReplyGateAllowed(P, 3, true, true, true));
    CHECK(!ReplyGateAllowed(GATE_SRC_UNDEFINED, 3, true, true, false));
    // key-on + tier-off (cloudChat false): byte-identical to today
    CHECK(ReplyGateAllowed(P, 2, true, false, false) ==
          ReplyGateAllowed(P, 2, true, true, false));
}

static void name_addressing()
{
    CHECK(ContainsNameIgnoreCase("Varleigh, you there?", "Varleigh"));
    CHECK(ContainsNameIgnoreCase("hey VARLEIGH over here", "varleigh"));
    CHECK(ContainsNameIgnoreCase("Varleigh's sword is nice", "Varleigh"));
    CHECK(ContainsNameIgnoreCase("Varleigh", "Varleigh"));
    // substring hits are NOT addressings
    CHECK(!ContainsNameIgnoreCase("Varleigha said so", "Varleigh"));
    CHECK(!ContainsNameIgnoreCase("aVarleigh what", "Varleigh"));
    CHECK(!ContainsNameIgnoreCase("Varleig", "Varleigh"));
    // other words that merely contain the name
    CHECK(!ContainsNameIgnoreCase("the varleighish way", "Varleigh"));
    CHECK(ContainsNameIgnoreCase("well met, Varleigh.", "Varleigh"));
    // punctuation boundaries count
    CHECK(ContainsNameIgnoreCase("Varleigh!", "Varleigh"));
    // only the possessive 's tail keeps an apostrophe boundary
    // (round-2 R1#2: "Varleigh'x" is a different word)
    CHECK(!ContainsNameIgnoreCase("Varleigh'x happened", "Varleigh"));
    // a QUOTED name still addresses (round-4 R1#2): the opening and
    // closing apostrophes are boundaries; a word-INTERNAL apostrophe is
    // not ("O'Varleigh" never addresses Varleigh)
    CHECK(ContainsNameIgnoreCase("I saw 'Varleigh' nearby", "Varleigh"));
    CHECK(ContainsNameIgnoreCase("'Varleigh', you there?", "Varleigh"));
    CHECK(ContainsNameIgnoreCase("who is 'Varleigh'", "Varleigh"));
    CHECK(ContainsNameIgnoreCase("say 'Varleigh's name", "Varleigh"));
    CHECK(!ContainsNameIgnoreCase("O'Varleigh rules", "Varleigh"));
    CHECK(!ContainsNameIgnoreCase(std::string(), "Varleigh"));
    CHECK(!ContainsNameIgnoreCase("nothing here", ""));
}

static void classify_generation()
{
    CHECK(ClassifyGeneration(true, "", false, false) == "busy");
    CHECK(ClassifyGeneration(false, "http_429", false, false) == "http_429");
    CHECK(ClassifyGeneration(false, "timeout", false, false) == "timeout");
    CHECK(ClassifyGeneration(false, "cap", false, false) == "cap");
    CHECK(ClassifyGeneration(false, "error", false, false) == "error");
    CHECK(ClassifyGeneration(false, "", true, false) == "empty");
    CHECK(ClassifyGeneration(false, "", false, true) == "empty");
    CHECK(ClassifyGeneration(false, "", false, false) == "ok");
}

static void responder_selection()
{
    // empty claims nobody
    CHECK(SelectResponder({}) == 0);
    // single candidate
    CHECK(SelectResponder({{7, 1, 0}}) == 7);
    // highest tier wins
    CHECK(SelectResponder({{7, 1, 0}, {9, 3, 0}, {11, 2, 0}}) == 9);
    // tie: longest-since-last-win (smallest lastWonMs)
    CHECK(SelectResponder({{7, 3, 500}, {9, 3, 100}, {11, 3, 300}}) == 9);
    // full tie: stable guid order
    CHECK(SelectResponder({{9, 3, 100}, {7, 3, 100}}) == 7);
    // negative tier candidates still participate (stranger tier 0 < acquaintance 1)
    CHECK(SelectResponder({{7, 0, 0}, {9, 1, 900}}) == 9);
    // round-4 R1: on an ADDRESSED line the pick resolves to the named
    // bot regardless of tier/rotation - the unaddressed fan-out loses
    // everywhere and the addressed bot's bypass is the ONE generation
    CHECK(SelectResponder({{7, 1, 0}, {9, 3, 0}, {11, 2, 0}}, 7) == 7);
    CHECK(SelectResponder({{7, 1, 0}, {9, 3, 0}, {11, 2, 0}}, 11) == 11);
    // an addressed guid outside the candidate set changes nothing (a
    // dead or absent named bot: the normal ordering answers)
    CHECK(SelectResponder({{7, 1, 0}, {9, 3, 0}}, 42) == 9);
    // the default (0) is the unaddressed line - pure ordering
    CHECK(SelectResponder({{7, 1, 0}, {9, 3, 0}}, 0) == 9);
}

static void dialogue_eviction()
{
    std::vector<DialogueOccupant> occ;
    u64 const now = 10000;
    u32 const CAP = 16;

    // empty map: non-interlocutor admits
    occ.clear();
    CHECK(EvictDialogueVictim(occ, now, 1, CAP, 2));

    // interlocutor admits at cap (soft cap)
    occ.clear();
    for (u32 i = 0; i < CAP; ++i)
        occ.push_back({100 + i, now + 60000});
    CHECK(EvictDialogueVictim(occ, now, 1, CAP, 1));

    // at cap, non-interlocutor rejected
    occ.clear();
    for (u32 i = 0; i < CAP; ++i)
        occ.push_back({100 + i, now + 60000});
    CHECK(!EvictDialogueVictim(occ, now, 1, CAP, 2));

    // expired markers prune themselves - the self-healing TTL
    occ.clear();
    for (u32 i = 0; i < CAP; ++i)
        occ.push_back({100 + i, now - 1});      // all expired
    CHECK(EvictDialogueVictim(occ, now, 1, CAP, 2));
    CHECK(occ.empty());

    // mixed: expired entries vanish, live ones count against the cap
    occ.clear();
    occ.push_back({100, now - 1});              // expired
    occ.push_back({101, now + 60000});         // live
    occ.push_back({102, now - 1});              // expired
    CHECK(EvictDialogueVictim(occ, now, 1, CAP, 2));
    CHECK(occ.size() == 1 && occ[0].guid == 101);

    // cap 0: only the interlocutor ever admits
    occ.clear();
    CHECK(EvictDialogueVictim(occ, now, 1, 0, 1));
    CHECK(!EvictDialogueVictim(occ, now, 1, 0, 2));
}

static void street_ladder_order()
{
    // the ORDER is the contract: window -> slot -> pct -> quota
    CHECK(StreetAdmissionOrder(true, true, true, true) == "dispatch");
    CHECK(StreetAdmissionOrder(false, false, false, false) == "reject:world-window");
    CHECK(StreetAdmissionOrder(true, false, false, false) == "reject:bot-slot");
    CHECK(StreetAdmissionOrder(true, true, false, false) == "reject:pct-roll");
    CHECK(StreetAdmissionOrder(true, true, true, false) == "reject:quota");
    // pct=0 is emote-only (symmetric with quota exhaustion) - both land
    // before dispatch, after the windows
    CHECK(StreetAdmissionOrder(true, true, false, true) == "reject:pct-roll");
}

static void failure_fallback_fold()
{
    // A4: busy keeps the placeholder (duty-cycle denial is pacing, not a
    // dead endpoint) - it never wants the fallback; every other hard
    // failure and post-parse emptiness does
    CHECK(!FailureWantsFallback(true, true));
    CHECK(!FailureWantsFallback(true, false));
    CHECK(FailureWantsFallback(false, true));
    CHECK(!FailureWantsFallback(false, false));

    // the plan's default is INACTIVE (the autonomous RPG dispatch site
    // keeps compiling with the defaulted trailing parameter and stays
    // silent; an inactive plan is exactly the device lane)
    PlayerbotLlmGates::FallbackPlan plan;
    CHECK(!plan.active);
    CHECK(plan.kind == PlayerbotLlmGates::FBK_NONE);
    CHECK(plan.channel == 0);
    CHECK(plan.personaCategory == 0);
    CHECK(!plan.whisper);
    CHECK(plan.absence.empty());
    CHECK(plan.mapId == 0);
    plan.active = true;
    plan.kind = PlayerbotLlmGates::FBK_GREET;
    plan.absence = "most of a day";
    CHECK(plan.active && plan.kind == PlayerbotLlmGates::FBK_GREET);
}

int main()
{
    cloud_lane_open_matrix();
    hard_trigger_matrix();
    reply_gate_matrix();
    name_addressing();
    classify_generation();
    responder_selection();
    dialogue_eviction();
    street_ladder_order();
    failure_fallback_fold();
    if (failures == 0)
        std::printf("llm gates battery: OK\n");
    else
        std::printf("llm gates battery: %d FAILURES\n", failures);
    return failures == 0 ? 0 : 1;
}
