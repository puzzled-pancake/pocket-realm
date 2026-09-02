// S10/E6 world-chatter host battery: compiles the SHIPPED pure core
// (PlayerbotLlmChatterCore.h) standalone with -std=c++11 and pins every
// contract the scheduler depends on - the power-ladder policy table, the
// world repetition ring, the fatigue + legend ledger, the authored floor,
// the frozen murmur/composer prompt wording, and the 6-hour soak invariants
// (silence default, zero-repeat, retirement, cadence ceilings) over the
// REAL decision math. Run by tests/test_llm_chatter.py.
#include "PlayerbotLlmChatterCore.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace pocketllm;

namespace {

int gChecks = 0;
#define CHECK(cond) do { ++gChecks; if (!(cond)) { \
    std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #cond); \
    std::exit(1); } } while (0)

void TestPolicyTable()
{
    // OFF: nothing at all (the master toggle / missing power file)
    ChatterPolicy off = ChatterPolicyFor(RUNG_OFF, true);
    CHECK(!off.generated && !off.murmur && !off.party && !off.global && !off.composer);

    // EMERGENCY: generation stops; floor only (murmur/global delivery
    // shapes remain for the authored event-grounded floor)
    ChatterPolicy em = ChatterPolicyFor(RUNG_EMERGENCY, true);
    CHECK(!em.generated && em.murmur && em.global && !em.party && !em.composer);
    CHECK(em.floorMinSpacingSec >= 120);
    CHECK(em.murmurDisplayMinSec >= 120);  // stretched display cadence
    // the batch window spaces the EMERGENCY PICKS too: a zero window
    // would re-pick (and re-drift the legend) every scheduler tick while
    // the floor's own spacing defers delivery (round-1 R1/R5)
    CHECK(em.murmurBatchWindowSec >= 120);

    // CRITICAL: global-channel layer only, ~1 line/3 min ceiling
    ChatterPolicy cr = ChatterPolicyFor(RUNG_CRITICAL, true);
    CHECK(cr.generated && cr.global && !cr.murmur && !cr.party && !cr.composer);
    CHECK(cr.globalMinSpacingSec == 180);

    // CONSTRAINED: device batches, stretched cadence (>= 90s display),
    // no composer, rarer party idle. The batch window (540s) is the
    // device-RATE bound - pinned absolutely (round-1 R5: the battery's
    // battery-arithmetic leg rests on it)
    ChatterPolicy co = ChatterPolicyFor(RUNG_CONSTRAINED, true);
    CHECK(co.generated && co.murmur && co.party && co.global && !co.composer);
    CHECK(co.murmurDisplayMinSec >= 90);
    CHECK(co.murmurBatchWindowSec == 540);
    CHECK(co.partyIdleRollPct < 50);

    // NORMAL: composer batches only when configured; 30-60s display
    ChatterPolicy n1 = ChatterPolicyFor(RUNG_NORMAL, true);
    CHECK(n1.generated && n1.composer);
    CHECK(n1.murmurDisplayMinSec == 30 && n1.murmurDisplayMaxSec == 60);
    CHECK(n1.murmurBatchWindowSec >= 240 && n1.murmurBatchWindowSec <= 360);
    CHECK(n1.partyIdleWindowSec >= 600 && n1.partyIdleWindowSec <= 900);
    // no composer configured: NORMAL degrades to device batches (composer
    // flag off, everything else identical)
    ChatterPolicy n0 = ChatterPolicyFor(RUNG_NORMAL, false);
    CHECK(n0.generated && !n0.composer && n0.murmur && n0.party);
    CHECK(n0.murmurDisplayMinSec == n1.murmurDisplayMinSec);
}

void TestWorldRing()
{
    WorldRing ring;
    std::string const a = "Ash lost five silver in a duel by the gates";
    CHECK(RingAdmits(ring, a));
    RingRemember(ring, a);
    // a near-identical phrasing is cross-bot echo: refused world-wide
    CHECK(!RingAdmits(ring, "Ash lost five silver in a duel by the gates"));
    CHECK(!RingAdmits(ring, "Ash lost five silver in a duel at the gates"));
    // a genuinely different line admits
    CHECK(RingAdmits(ring, "The griffin post finally opened at the keep"));
    // capacity: the oldest entry is forgotten, so an old line CAN return
    // (fillers share no words with each other - the ring vetoes
    // near-duplicates by design)
    static char const* const fillers[] = {
        "rain hammers the east road tonight",
        "a courier dropped saddlebags near the bridge",
        "three murloc fins dried on the net rack",
        "the smith quoted double for horseshoes",
        "gryphon feed costs more than bread now",
        "an old compass sold at the stall",
        "children chase lamp moths at dusk",
        "the ferryman hums off key",
        "soldiers dragged a broken cart uphill",
        "someone nailed a notice to the post",
        "frost rimed the well rope by morning",
        "the hound dug under the chapel step",
        "an apprentice burned the glue pot",
        "fishermen argue over net rights",
        "a peddler swears by his knives",
        "the baker's boy skipped chores",
        "wolves cried past the north fence",
        "lantern oil smells of tallow",
        "the millstone turned all night",
        "a tinker mended kettle seams",
        "grass grew over the old tracks",
        "smoke leaned west across rooftops",
        "goats wandered the market row",
        "the night watch swapped dice",
    };
    for (size_t i = 0; i < WorldRing::kCap; ++i)
    {
        if (RingAdmits(ring, fillers[i]))
            RingRemember(ring, fillers[i]);
    }
    CHECK(ring.lines.size() <= WorldRing::kCap);
    CHECK(RingAdmits(ring, a));  // evicted by now
}

void TestFatigueAndLegend()
{
    ChatterFatigue f;
    // the plan's absolute design constants (SS4.6b: "topic saturation
    // retires a story after K tellings"; "content-bearing hops capped
    // ~3-5") - pinned absolutely so a constants edit cannot silently
    // pass the relative checks below
    CHECK(ChatterFatigue::kMaxFactTellings == 5);
    CHECK(ChatterFatigue::kMaxContentHops == 3);
    std::string const key = "g:42";
    CHECK(FatigueAdmits(f, key));
    CHECK(CredenceAdmits(f, 7, key));
    CHECK(CredenceAdmits(f, 9, key));

    // the originator's first telling carries the row verbatim (the truth
    // anchor: the DB row text, never mutated)
    std::string const row = "Ash owes two silver from the ale";
    CHECK(LegendTellingText(f, key, row, true) == row);

    // a money-bearing row drifts exactly one rung per non-originator hop
    std::string second = LegendTellingText(f, key, row, false);
    CHECK(second != row);  // two -> three
    CHECK(second.find("three") != std::string::npos);
    std::string third = LegendTellingText(f, key, row, false);
    CHECK(third != second);
    std::string fourth = LegendTellingText(f, key, row, false);
    CHECK(fourth != third);
    // the content-hop cap: no further drift past kMaxContentHops
    CHECK(f.perFact[key].hops == ChatterFatigue::kMaxContentHops);
    std::string frozen = LegendTellingText(f, key, row, false);
    CHECK(frozen == fourth);

    // retirement: after kMaxFactTellings recorded deliveries the fact is
    // done - no one comments on the same duel twice
    for (uint32_t i = 0; i < ChatterFatigue::kMaxFactTellings; ++i)
        FatigueRecordTelling(f, key);
    CHECK(!FatigueAdmits(f, key));

    // credence: a bot who heard the legend never retells it
    MarkHeard(f, 33, key);
    CHECK(!CredenceAdmits(f, 33, key));
    CHECK(CredenceAdmits(f, 34, key));

    // driftless rows pass through unchanged (no hedge, no money rung)
    ChatterFatigue g;
    std::string const plain = "the militia drilled at dawn";
    CHECK(LegendTellingText(g, "g:1", plain, true) == plain);
    CHECK(LegendTellingText(g, "g:1", plain, false) == plain);

    // the authored floor's per-(template x speaker x listener) spacing
    CHECK(TemplateSpacingAdmits(f, 0, 1, 2, 1000, 120));
    TemplateSpacingRecord(f, 0, 1, 2, 1000);
    CHECK(!TemplateSpacingAdmits(f, 0, 1, 2, 1000 + 119, 120));
    CHECK(TemplateSpacingAdmits(f, 0, 1, 2, 1000 + 120, 120));
    CHECK(TemplateSpacingAdmits(f, 1, 1, 2, 1000, 120));  // other template
    CHECK(TemplateSpacingAdmits(f, 0, 3, 2, 1000, 120));  // other speaker
}

void TestRegisterAndFloor()
{
    CHECK(IsMurmurRegister("Ash lost five silver in a duel by the gates"));
    CHECK(!IsMurmurRegister("Hm."));                       // too short
    CHECK(!IsMurmurRegister("a b c d"));                   // still too short
    // a genuinely over-long line is a speech, not a murmur
    std::string speech;
    for (int i = 0; i < 10; ++i) speech += "word word word ";
    CHECK(!IsMurmurRegister(speech));

    // the chatter line-safety law (round-1 R6): an autonomous producer
    // may never queue newlines, non-ASCII residue, protocol/pipe
    // characters, or emote-initial leads
    CHECK(ChatterLineSafe("Ash lost five silver in a duel by the gates"));
    CHECK(!ChatterLineSafe("line one\nline two"));            // newline
    CHECK(!ChatterLineSafe("plain \xC3\xA9 accent text"));    // non-ASCII
    CHECK(!ChatterLineSafe("brace {E} leaked into chat"));    // protocol braces
    CHECK(!ChatterLineSafe("angled <tool> leak text"));       // tool brackets
    CHECK(!ChatterLineSafe("|cffffffff|Hitem:123|h[item]|h"));// pipe hyperlink
    CHECK(!ChatterLineSafe("*waves* that is not speech"));    // emote lead
    CHECK(!ChatterLineSafe("[Guild] channel echo here"));     // bracket lead
    CHECK(!ChatterLineSafe(" leading space leaks in"));       // space lead
    CHECK(!ChatterLineSafe("ok"));                            // too short

    CHECK(ClampMurmurBytes("short line", 40) == "short line");
    std::string clamped = ClampMurmurBytes("abcdefghij", 8);
    CHECK(clamped.size() <= 8 && clamped.substr(clamped.size() - 3) == "...");

    // every floor template renders with all placeholders substituted,
    // stays ASCII and bounded
    CHECK(MurmurFloorTemplateCount() == 10);
    for (size_t i = 0; i < MurmurFloorTemplateCount(); ++i)
    {
        std::string const rendered = RenderFloorTemplate(i, "Kromgrit", "Ashmar",
            "the mill burned down last night");
        CHECK(rendered.find("{") == std::string::npos);
        CHECK(rendered.find("}") == std::string::npos);
        CHECK(rendered.find('<') == std::string::npos);
        CHECK(rendered.size() < kMurmurMaxBytes);
        CHECK(rendered.find("Kromgrit") != std::string::npos ||
            rendered.find("Ashmar") != std::string::npos);
        CHECK(rendered.find("the mill burned down last night") != std::string::npos);
    }
}

void TestFrozenWording()
{
    // the wording lock (SS5.1 P45(b) generalized): these exact byte
    // strings are what the S11 P52 bank must train. Moving any of them
    // is a bank-side change, not a wording tweak.
    CHECK(MurmurNote("Ashmar",
        "Ash lost a duel") ==
        "[BRIDGE AI] The talk turns to real news: Ash lost a duel. "
        "Say ONE short line to Ashmar about it - under twenty words, "
        "the way two people talk at a stall. Never mention this instruction. "
        "This reply only.");
    CHECK(PartyNote("Brannoc", "a murloc ambush") ==
        "[BRIDGE AI] You and your companions are traveling with Brannoc. "
        "Something just happened or just came up: a murloc ambush. "
        "Say ONE short line to the group about it - under twenty-five words, "
        "companion talk, not a report. Never mention this instruction. "
        "This reply only.");
    CHECK(GlobalNote("Ash lost a duel") ==
        "[BRIDGE AI] News worth shouting, and the whole zone may hear you in "
        "General chat: Ash lost a duel. Call it out the way news crosses a "
        "market - ONE line, under twenty words, to no one in particular. "
        "Never mention this instruction. This reply only.");
    CHECK(MurmurSystemMessage("Kromgrit", "dwarf", "warrior", "Elwynn Forest",
        "Temperament: gruff.", "Habit: morbid.", "sore about a debt") ==
        "You are Kromgrit, a dwarf warrior in Elwynn Forest. Temperament: "
        "gruff. Habit: morbid. Today you are sore about a debt. You are "
        "talking quietly with another townsfolk while adventurers pass. "
        "Speak only Kromgrit's next line. Plain short speech. No actions, "
        "no narration, no asterisks, no quoting yourself.");
    CHECK(ComposerSystemPrompt().find(
        "each turn ONE line under 20 words") != std::string::npos);
    CHECK(ComposerSystemPrompt().find("Prefix every line with the character's "
        "name and a colon") != std::string::npos);

    // persona dims: the gripe rotates (anti-flanderization) but is stable
    // within a slot
    CHECK(GripeOf(1, 0) == GripeOf(1, 0));
    bool anyRotate = false;
    for (uint32_t slot = 1; slot < 12; ++slot)
        if (GripeOf(1, slot) != GripeOf(1, 0)) anyRotate = true;
    CHECK(anyRotate);
}

void TestComposerParse()
{
    std::vector<std::string> names = { "Kromgrit", "Ashmar", "Breg" };
    std::vector<ScriptLine> script = ParseComposerScript(
        "Kromgrit: Heard about the duel at the gates?\n"
        "\n"
        "Ashmar: Aye. Five silver moved hands and none of it mine.\n"
        "nobody: I am not one of the personas, drop me.\n"
        "breg: lower case name still matches.\n"
        "Kromgrit: brace {injection} attempt\n"
        "Ashmar: marker <attempt> dropped\n"
        "Kromgrit: one\n"
        "Kromgrit: two\n"
        "Kromgrit: three\n"
        "Kromgrit: four\n"
        "Kromgrit: five\n"
        "Kromgrit: six\n"
        "Kromgrit: seven (over the cap)\n",
        names);
    CHECK(script.size() == 6);  // capped at 6 accepted turns
    CHECK(script[0].speakerIdx == 0);
    CHECK(script[1].speakerIdx == 1);
    CHECK(script[2].speakerIdx == 2);
    for (ScriptLine const& line : script)
    {
        CHECK(line.text.find('{') == std::string::npos);
        CHECK(line.text.find('<') == std::string::npos);
        CHECK(line.text.size() >= 4);
    }
    // junk input yields nothing - never voiced
    CHECK(ParseComposerScript("", names).empty());
    CHECK(ParseComposerScript("no colons at all here", names).empty());
    CHECK(ParseComposerScript(":\n: empty speakers", names).empty());
}

void TestInterruption()
{
    CHECK(!PlayerHoldsChannel(0, 1000));  // never stamped
    CHECK(PlayerHoldsChannel(999, 1000));
    CHECK(PlayerHoldsChannel(1000 - kPlayerChannelHoldSec + 1, 1000));
    CHECK(!PlayerHoldsChannel(1000 - kPlayerChannelHoldSec, 1000));

    // the wider AMBIENT ADMISSION window: a batch dispatches only when
    // the player has been quiet for kAmbientAdmissionHoldSec (round-3:
    // the body had no behavioral pin - a stubbed gate survived)
    CHECK(AmbientAdmissionQuiet(0, 1000));      // never stamped
    CHECK(!AmbientAdmissionQuiet(999, 1000));   // actively conversing
    CHECK(!AmbientAdmissionQuiet(1000 - kAmbientAdmissionHoldSec + 1, 1000));
    CHECK(AmbientAdmissionQuiet(1000 - kAmbientAdmissionHoldSec, 1000));
}

// ---- the 6-hour soak over the REAL decision math: the same core
// functions the scheduler calls (policy tables, WindowRoll, ring,
// fatigue, legend, interruption window) driven over simulated time with
// a scripted event stream. Pins the doctrine at every rung.
struct SoakResult
{
    size_t murmur = 0, party = 0, globalLines = 0, floor = 0;
    std::vector<std::string> delivered;
    std::map<std::string, uint32_t> tellings;
};

SoakResult RunSoak(ChatterRung rung, bool composerConfigured,
    size_t eventCount, uint32_t rngSeed, bool playerChatter)
{
    SoakResult out;
    ChatterPolicy const policy = ChatterPolicyFor(rung, composerConfigured);
    WorldRing ring;
    ChatterFatigue fatigue;
    uint32_t rng = rngSeed ? rngSeed : 1;
    int64_t const kStart = 1000000;
    int64_t const kSixHours = 6 * 3600;
    int64_t lastPlayerChat = 0;
    int64_t lastMurmur = 0, lastGlobal = 0, lastGlobalWindow = 0, lastFloor = 0;
    int64_t lastFloorWindow = 0;
    std::map<int64_t, std::string> eventAt;  // second -> fact key
    // a scripted event every ~17 min (a live world's real cadence: duels,
    // loot, happenings) - each a DISTINCT fact with genuinely different
    // wording (near-duplicate rows would be world-ring vetoed, which is
    // the ring working, not a soak failure)
    static char const* const kRows[] = {
        "Ash owes two silver over the ale",
        "the miller caught a thief in his loft",
        "a gryphon threw its rider at the keep",
        "smugglers landed crates by the north rocks",
        "the chapel bell cracked at noon",
        "Brannoc won five silver at dice",
        "wolves carried off three sheep",
        "a stranger asked about the old mine",
        "the ferry nearly sank under freight",
        "guards arrested a false herald",
        "Ashmar's mare threw a shoe mid-race",
        "the tavern ran dry before dusk",
        "a lantern fire scorched the stable door",
        "fishermen found a sealed chest",
        "the recruit dueled the drillmaster and won",
        "hail flattened the west field barley",
        "a caravan paid double for escort",
        "the night watch lost the jail keys",
        "children found coin in the fountain",
        "the tinker swore oaths at the crossroads",
        "a boat with no oars drifted ashore",
    };
    size_t const rowCount = sizeof(kRows) / sizeof(kRows[0]);
    for (size_t i = 0; i < eventCount; ++i)
        eventAt[kStart + 900 + (int64_t)i * 1020] = "g:" + std::to_string(100 + i);
    std::map<std::string, std::string> rowText;
    for (size_t i = 0; i < eventCount; ++i)
        rowText["g:" + std::to_string(100 + i)] = std::string(kRows[i % rowCount]) +
            " (" + std::to_string(i) + ")";

    for (int64_t now = kStart; now < kStart + kSixHours; now += 10)
    {
        if (playerChatter)
            lastPlayerChat = now;  // the player converses continuously:
                                   // every conversational trigger holds
                                   // the channel for kPlayerChannelHoldSec
        // the freshest unretired, unheard event (the pick the scheduler
        // performs over the gossip table)
        std::string factKey;
        for (auto itr = eventAt.rbegin(); itr != eventAt.rend(); ++itr)
        {
            if (itr->first > now) continue;
            if (!FatigueAdmits(fatigue, itr->second)) continue;
            if (!CredenceAdmits(fatigue, 7, itr->second)) continue;
            factKey = itr->second;
            break;
        }

        // murmur layer: the SHIPPED topology - one device/composer batch
        // per batch window (the refill gate; the display stagger is the
        // notBefore delay), dispatched only in a quiet channel, delivered
        // only when the player does not hold it (round-1 R3/R5: the soak
        // must model the window-driven refill, not a display-cadence loop)
        if (policy.murmur && policy.generated && !factKey.empty() &&
            now - lastMurmur >= policy.murmurBatchWindowSec &&
            AmbientAdmissionQuiet(lastPlayerChat, now) &&
            !PlayerHoldsChannel(lastPlayerChat, now))
        {
            std::string const text = LegendTellingText(fatigue, factKey,
                rowText[factKey], false);
            std::string line = "Kromgrit: " + text;
            if (policy.generated)
                line += " sure " + std::to_string(now % 977);  // draw variance
            if (RingAdmits(ring, line) && IsMurmurRegister(text))
            {
                RingRemember(ring, line);
                FatigueRecordTelling(fatigue, factKey);
                MarkHeard(fatigue, 7, factKey);
                ++out.murmur;
                out.delivered.push_back(line);
                out.tellings[factKey]++;
                lastMurmur = now;
            }
        }
        // the emergency floor: same event gate, same quiet/dispatch
        // discipline, wider spacing, no generation behind the line
        else if (!policy.generated && policy.murmur && !factKey.empty() &&
            now - lastFloor >= policy.floorMinSpacingSec &&
            policy.murmurBatchWindowSec > 0 &&
            now - lastFloorWindow >= policy.murmurBatchWindowSec &&
            AmbientAdmissionQuiet(lastPlayerChat, now) &&
            !PlayerHoldsChannel(lastPlayerChat, now))
        {
            std::string const line = RenderFloorTemplate(
                (size_t)now % MurmurFloorTemplateCount(), "Kromgrit", "Ashmar",
                rowText[factKey]);
            if (RingAdmits(ring, line))
            {
                RingRemember(ring, line);
                FatigueRecordTelling(fatigue, factKey);
                MarkHeard(fatigue, 7, factKey);
                ++out.floor;
                out.delivered.push_back(line);
                out.tellings[factKey]++;
                lastFloor = now;
                lastFloorWindow = now;
            }
        }
        // global layer: spacing floor + window roll
        if (policy.global && !factKey.empty() &&
            now - lastGlobal >= policy.globalMinSpacingSec &&
            (lastGlobalWindow == 0 ||
                now - lastGlobalWindow >= policy.globalWindowSec))
        {
            lastGlobalWindow = now;
            if (WindowRoll(rng, policy.globalRollPct))
            {
                lastGlobal = now;
                ++out.globalLines;
            }
        }
    }
    out.party = 0;  // party cadence is per-master windowed; the soak pins
                    // murmur/global/floor (the source pins carry the party
                    // window discipline)
    return out;
}

void TestSoak()
{
    // SILENCE DEFAULT: an empty event bank means zero lines at EVERY
    // rung, over the full 6 hours, with generation available
    for (int r = RUNG_EMERGENCY; r <= RUNG_NORMAL; ++r)
    {
        SoakResult quiet = RunSoak((ChatterRung)r, true, 0, 7, false);
        CHECK(quiet.murmur == 0 && quiet.globalLines == 0 && quiet.floor == 0 &&
            quiet.delivered.empty());
    }

    // a live event stream at NORMAL: murmur flows but the ring keeps
    // zero-repeat and fatigue retires every story
    SoakResult normal = RunSoak(RUNG_NORMAL, true, 21, 7, false);
    CHECK(normal.murmur > 20);   // the cadence allows ~300; the doctrine
                                 // gates it to the event stream (~21 facts
                                 // x up to 5 tellings each)
    for (auto const& told : normal.tellings)
        CHECK(told.second <= ChatterFatigue::kMaxFactTellings);
    // world-ring zero-repeat: no delivered pair above the Jaccard bar
    for (size_t i = 0; i < normal.delivered.size(); ++i)
        for (size_t j = i + 1; j < normal.delivered.size(); ++j)
            CHECK(JaccardWords(normal.delivered[i], normal.delivered[j]) <=
                WorldRingMaxJaccard());

    // the interruption rule: a player actively conversing owns the
    // channel - murmur and floor delivery wait (here: fully suppressed
    // for the whole soak; the global set piece is exempt by design)
    SoakResult interrupted = RunSoak(RUNG_NORMAL, true, 21, 7, true);
    CHECK(interrupted.murmur == 0);
    CHECK(interrupted.floor == 0);

    // EMERGENCY: no generation, floor only, wide spacing
    SoakResult emergency = RunSoak(RUNG_EMERGENCY, true, 21, 7, false);
    CHECK(emergency.murmur == 0);
    CHECK(emergency.floor > 0);
    // cadence ceiling: floor lines at most one per floorMinSpacingSec
    CHECK(emergency.floor <= (size_t)(6 * 3600 / 120));

    // CRITICAL: global only
    SoakResult critical = RunSoak(RUNG_CRITICAL, true, 21, 7, false);
    CHECK(critical.murmur == 0 && critical.floor == 0);
    CHECK(critical.globalLines > 0);
    CHECK(critical.globalLines <= (size_t)(6 * 3600 / 180));  // 1/3min max

    // determinism: same seed, same event stream, same deliveries
    SoakResult again = RunSoak(RUNG_NORMAL, true, 21, 7, false);
    CHECK(again.murmur == normal.murmur);
    CHECK(again.delivered.size() == normal.delivered.size());
}

void TestGovernorMath()
{
    // the ambient rate the shipped topology admits is far under the
    // governor budget (the "whisper ack unharmed" arithmetic): the
    // device-RATE bound is the refill window (one batch per window -
    // 270 s NORMAL / 540 s CONSTRAINED); a NORMAL COMPOSER batch
    // delivers up to kMaxFactTellings lines per window, so the worst
    // case is cap/window. Even that is ~1.1 lines/min against
    // GovernorBotMax 8 per 60 s - and the device worker yields whenever
    // an interactive generation is in flight (source pin); the dispatch
    // quiet-gate keeps batches out of live conversations entirely.
    ChatterPolicy co = ChatterPolicyFor(RUNG_CONSTRAINED, false);
    double const deviceLinesPerMinute = 60.0 / co.murmurBatchWindowSec;
    ChatterPolicy no = ChatterPolicyFor(RUNG_NORMAL, true);
    double const composerLinesPerMinute =
        ChatterFatigue::kMaxFactTellings * 60.0 / no.murmurBatchWindowSec;
    CHECK(deviceLinesPerMinute < 8.0);       // bot budget
    CHECK(composerLinesPerMinute < 8.0);     // and the multi-turn batch
    CHECK(composerLinesPerMinute < 24.0);    // global budget (native default)
}

} // namespace

int main()
{
    TestPolicyTable();
    TestWorldRing();
    TestFatigueAndLegend();
    TestRegisterAndFloor();
    TestFrozenWording();
    TestComposerParse();
    TestInterruption();
    TestSoak();
    TestGovernorMath();
    std::printf("chatter core battery: %d checks OK\n", gChecks);
    return 0;
}
