/*
 * Host battery for the pocketllm banter core (the playerbot LLM companion's
 * deterministic voice layer). Three modes, exit-code driven:
 *
 *   invariants        pool content contracts, trait determinism, marker
 *                     neutering, render bounds, selection edge cases
 *   sim [draws]       the 1000-hour compression: 200 bots x 50 players,
 *                     fixed-seed workload, variety metrics as one JSON line
 *   fuzz [iters]      adversarial inputs: corrupted state structs, mutated
 *                     marker text, hostile names -> never crash, never leak
 *                     protocol bytes, never OOB
 *
 * Compiled by tests/test_llm_banter.py with -std=c++11 directly against the
 * pinned header (native/patches/playerbots/llm_banter_core.h) — the host
 * always tests the shipped code, never a copy.
 */
#include "llm_banter_core.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace pocketllm;

static int g_failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { std::printf("FAIL %s (line %d)\n", msg, __LINE__); ++g_failures; } \
} while (0)

static uint64_t Fnv1a64(void const* data, size_t n, uint64_t h = 1469598103934665603ull)
{
    unsigned char const* p = (unsigned char const*)data;
    for (size_t i = 0; i < n; ++i) { h ^= p[i]; h *= 1099511628211ull; }
    return h;
}

// C7: the draw-stream fingerprint under a given boot nonce (the golden
// leg's shape, factored so the nonce matrix can compare streams).
static uint64_t streamHash(uint32_t nonce)
{
    SetBanterBootNonce(nonce);
    uint64_t h = 1469598103934665603ull;
    for (int stream = 0; stream < 4; ++stream)
    {
        BanterState s;
        InitBanterState(s, 1234u + stream * 17u, 77u + stream);
        size_t n = 0; char const* const* pool = Pool(POOL_KILL, n);
        for (int i = 0; i < 500; ++i)
        {
            BanterResult r = SelectLine(s, pool, n, 0, 0, 0, 0);
            h = Fnv1a64(r.line, std::strlen(r.line), h);
        }
    }
    SetBanterBootNonce(0);
    return h;
}

// ------------------------------------------------------------ invariants ----
static int RunInvariants()
{
    // B1/B2: every pool line valid (ASCII, length, no protocol bytes).
    for (int p = 0; p < POOL_COUNT; ++p)
    {
        size_t n = 0;
        char const* const* pool = Pool((PoolId)p, n);
        CHECK(pool && n >= (size_t)kMinPoolLines,
              "pool meets the minimum-size contract");
        for (size_t i = 0; i < n; ++i)
        {
            CHECK(LineIsValid(pool[i]), "pool line passes the content contract");
            CHECK(std::strchr(pool[i], '{') == nullptr ||
                  (std::strstr(pool[i], "{P}") || std::strstr(pool[i], "{B}")),
                  "braces only appear as {P}/{B} placeholders");
        }
    }

    // Traits are GUID-stable and cover the tables.
    for (uint32_t g = 0; g < 512; ++g)
    {
        CHECK(DemeanorOf(g) == (int)(g % TRAIT_DEMEANOR_COUNT), "demeanor stable");
        CHECK(QuirkOf(g) == (int)((g / 4) % QUIRK_COUNT), "quirk stable");
        CHECK(TicOf(g) == (int)((g / 32) % TIC_COUNT), "tic stable");
    }
    for (int t = 0; t < TIC_COUNT; ++t) CHECK(TicPrefix(t) != 0, "tic table populated");
    CHECK(std::strlen(DemeanorSeasoning(TRAIT_WRY)) > 40, "seasoning text present");

    // NeuterMarkers: markers die, prose survives, idempotent.
    {
        char buf[256];
        std::strcpy(buf, "<<log_fact text=\"x\">> hello <<share_gossip text=\"y\">> world");
        NeuterMarkers(buf);
        CHECK(std::strstr(buf, "<<") == nullptr && std::strstr(buf, ">>") == nullptr,
              "neuter removes all markers");
        CHECK(std::strcmp(buf, "log_fact text=\"x\" hello share_gossip text=\"y\" world") == 0,
              "neuter keeps inner prose, kills only the markers");
        NeuterMarkers(buf);
        CHECK(std::strstr(buf, "<<") == nullptr, "neuter idempotent");
        std::strcpy(buf, "<<<<< >>>>><< single < and > stay");
        NeuterMarkers(buf);
        CHECK(std::strstr(buf, "<<") == nullptr && std::strstr(buf, ">>") == nullptr,
              "neuter handles runs");
        char empty[8] = ""; NeuterMarkers(empty);
        CHECK(empty[0] == 0, "neuter handles empty");
    }

    // NeuterMarkersCopy (the std::string choke point used by AppendTurn and
    // the <initial message> placeholder): same contract, source untouched.
    {
        std::string const src = "<<log_fact text=\"x\">> keep <<>> this";
        std::string const out = NeuterMarkersCopy(src.c_str());
        CHECK(out.find("<<") == std::string::npos && out.find(">>") == std::string::npos,
              "copy neuter removes markers");
        CHECK(out.find("keep") != std::string::npos && out.find("this") != std::string::npos,
              "copy neuter keeps prose");
        CHECK(src.find("<<log_fact") == 0, "copy neuter leaves the source untouched");
        std::string const once = NeuterMarkersCopy("a <<b>> c");
        std::string const twice = NeuterMarkersCopy(once.c_str());
        CHECK(twice == once, "copy neuter idempotent");
        CHECK(std::string(NeuterMarkersCopy("")).empty(), "copy neuter handles empty");
    }

    // RenderLine: placeholders, bounds, hostile inputs.
    {
        char out[512];
        RenderLine("Hail, {P}. Signed, {B}.", "Thrall", "Murgul", out, sizeof out);
        CHECK(std::strcmp(out, "Hail, Thrall. Signed, Murgul.") == 0, "render substitutes");
        RenderLine("{P}{P}{B}", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "B", out, 16);
        CHECK(std::strlen(out) < 16, "render respects the cap");
        RenderLine("{P", "X", "Y", out, sizeof out); // unterminated placeholder
        CHECK(std::strstr(out, "{P") != nullptr, "lone brace survives verbatim");
        RenderLine(0, 0, 0, out, sizeof out); CHECK(out[0] == 0, "null template");
        RenderLine("x", 0, 0, 0, 0); // no crash
        char big[600];
        std::memset(big, 'q', sizeof big - 1); big[sizeof big - 1] = 0;
        char out2[256];
        RenderLine(big, "name", "bot", out2, sizeof out2);
        CHECK(std::strlen(out2) == 255, "over-long template truncated exactly");
        // 200-char chat packet limit: a worst-case pool line + names must fit.
        size_t n = 0; char const* const* idle = Pool(POOL_IDLE, n);
        std::string longest;
        for (size_t i = 0; i < n; ++i) longest = std::max(longest, std::string(idle[i]));
        RenderLine(longest.c_str(), "Verylongplayername", "Verylongbotnameee", out, sizeof out);
        CHECK(std::strlen(out) <= 200, "worst-case render fits the packet limit");
    }

    // Selection edge cases: zeroed state, tiny pools, time extremes.
    {
        static char const* const tiny[] = { "one", "two", "three" };
        BanterState s; std::memset(&s, 0, sizeof s);
        BanterResult r = SelectLine(s, tiny, 3, 0, 0, 0, 0);
        CHECK(r.line && std::strlen(r.line) >= 3 && !r.suppressed, "zeroed state selects");
        for (int i = 0; i < 64; ++i)
            r = SelectLine(s, tiny, 3, 0, 0, 0, 0);
        CHECK(r.line, "tiny pool never exhausts");
        BanterState s2; std::memset(&s2, 0, sizeof s2);
        r = SelectLine(s2, tiny, 3, 0, 0, 0xFFFFFFFFu, 0);
        CHECK(!r.suppressed, "zero cooldown never suppresses");
        BanterState s3; InitBanterState(s3, 7, 9);
        s3.cooldownUntilMs = 1000;
        r = SelectLine(s3, tiny, 3, 0, 0, 500, 100);
        CHECK(r.suppressed, "cooldown suppresses");
        r = SelectLine(s3, 0, 0, 0, 0, 0, 0);
        CHECK(r.line == 0 && !r.suppressed, "empty pool safe");
        // One-entry pool with a full ring: must still return a line.
        static char const* const one[] = { "only" };
        BanterState s4; InitBanterState(s4, 3, 3);
        s4.ring[0] = 0; s4.ringLen = 1; s4.ringPos = 0;
        r = SelectLine(s4, one, 1, 0, 0, 0, 0);
        CHECK(r.line && std::strcmp(r.line, "only") == 0, "single-line pool recovers");
    }

    // RNG determinism pin (golden): four streams must hash to a fixed value.
    {
        uint64_t h = 1469598103934665603ull;
        for (int stream = 0; stream < 4; ++stream)
        {
            BanterState s;
            InitBanterState(s, 1234u + stream * 17u, 77u + stream);
            size_t n = 0; char const* const* pool = Pool(POOL_KILL, n);
            for (int i = 0; i < 500; ++i)
            {
                BanterResult r = SelectLine(s, pool, n, 0, 0, 0, 0);
                h = Fnv1a64(r.line, std::strlen(r.line), h);
            }
        }
        // Printed for the pytest golden check; any deliberate change to the
        // selection algorithm or pools updates the pin in the same commit.
        std::printf("golden_fnv1a64=%016llx\n", (unsigned long long)h);
    }

    // The boot nonce. The ZERO nonce is the host's
    // deterministic baseline - the golden above must be unchanged with it
    // (the world never sets 0 unless the LLMGreetMemory kill-switch is
    // on, and then verbatim replay is the PROMISE). A nonzero nonce must
    // produce a DIFFERENT stream for the same (bot, audience) pair, and
    // two DIFFERENT nonces must differ from each other - the cross-
    // restart verbatim-replay fix (R6) is only real if every boot draws
    // a fresh sequence. Restores the zero nonce afterwards so later legs
    // keep the deterministic baseline.
    {
        CHECK(BanterBootNonce() == 0, "host starts at the zero nonce");

        uint64_t streamHash(uint32_t nonce);
        uint64_t const base = streamHash(0);
        uint64_t const n1 = streamHash(0xD1CEB00Cu);
        uint64_t const n2 = streamHash(0x5EEDC0DEu);
        CHECK(n1 != base, "a boot nonce changes the draw sequence");
        CHECK(n1 != n2, "two boots never replay the same sequence");
        // reset-and-repeat is itself deterministic (same nonce => same
        // sequence - the world's restart drills stay reproducible)
        CHECK(streamHash(0xD1CEB00Cu) == n1, "same nonce reproduces");

        SetBanterBootNonce(0);
        CHECK(BanterBootNonce() == 0, "nonce restore");
    }

    // Render-time first-meeting rewording.
    {
        CHECK(IsFirstMeetingRow("met Varleigh for the first time"),
            "first-meeting shape detected");
        CHECK(!IsFirstMeetingRow("met Varleigh once on the road"),
            "reworded rows are not re-detected");
        CHECK(!IsFirstMeetingRow("wants a wolf pelt"),
            "unrelated rows pass through");
        CHECK(!IsFirstMeetingRow(""), "empty is not a first meeting");
        std::string const reworded =
            RewordFirstMeetingRow("met Varleigh for the first time", 0);
        CHECK(reworded == "met Varleigh once on the road back",
            "seed 0 tail");
        CHECK(RewordFirstMeetingRow("met Varleigh for the first time", 1)
            == "met Varleigh before the seasons turned", "seed 1 tail");
        CHECK(RewordFirstMeetingRow("met Varleigh for the first time", 2)
            == "met Varleigh some quiet while ago", "seed 2 tail");
        CHECK(RewordFirstMeetingRow("met Varleigh for the first time", 3)
            == reworded, "rotation wraps");
        // prefix stability + the 8-word law
        CHECK(reworded.rfind("met ", 0) == 0, "prefix stable");
        for (uint32_t seed = 0; seed < 3; ++seed)
        {
            std::string const out =
                RewordFirstMeetingRow("met Varleigh for the first time", seed);
            size_t words = 1;
            for (char c : out)
                if (c == ' ')
                    ++words;
            CHECK(words <= 8, "replacement is a <= 8 word clause");
        }
        // pass-through for non-shapes
        CHECK(RewordFirstMeetingRow("knows the old songs", 0)
            == "knows the old songs", "pass-through");
    }

    // ---- Mood weather: stability, bounds, determinism
    {
        for (uint32_t g = 0; g < 64; ++g)
        {
            int m1 = MoodIndexOf(g, 100, 0);
            int m2 = MoodIndexOf(g, 100, 0);
            CHECK(m1 == m2 && m1 >= 0 && m1 < MOOD_COUNT, "mood deterministic in range");
            int m3 = MoodIndexOf(g, 101, 0);
            (void)m3; // weather MAY drift across buckets, never out of range
            CHECK(m3 >= 0 && m3 < MOOD_COUNT, "mood bucket in range");
            char const* line = MoodSeasoningLine(m1);
            CHECK(line && std::strlen(line) > 20, "mood seasoning text present");
            CHECK(std::strchr(line, '<') == 0 && std::strchr(line, '>') == 0,
                "mood line carries no markers");
            PoolId mp = MoodPoolOf(m1);
            size_t mc = 0; char const* const* ml = Pool(mp, mc);
            CHECK(ml && mc >= (size_t)kMinPoolLines, "mood pool meets the min-lines law");
        }
        // The three event moods carry their OWN pools - the
        // alias table made the deterministic layer contradict its prompt
        // seasoning (prompt said smitten, the fallback line sounded
        // homesick)
        CHECK(MoodPoolOf(MOOD_SMITTEN) == POOL_MOOD_SMITTEN, "smitten maps to its own pool");
        CHECK(MoodPoolOf(MOOD_GRUDGE) == POOL_MOOD_GRUDGE, "grudge maps to its own pool");
        CHECK(MoodPoolOf(MOOD_GRIEF) == POOL_MOOD_GRIEF, "grief maps to its own pool");
        {
            PoolId const dedicated[3] = {
                POOL_MOOD_SMITTEN, POOL_MOOD_GRUDGE, POOL_MOOD_GRIEF,
            };
            for (PoolId p : dedicated)
            {
                size_t n = 0; char const* const* pool = Pool(p, n);
                CHECK(pool && n >= (size_t)kMinPoolLines,
                    "dedicated mood pool meets the min-lines law");
            }
        }
        CHECK(MoodWeightPermille(50) == 1000, "default volatility is unity");
        CHECK(MoodWeightPermille(0) == 500, "steady halves mood presence");
        CHECK(MoodWeightPermille(100) == 1500, "changeable doubles mood presence");
        CHECK(MoodWeightPermille(-5) == 1000, "negative dial follows default");
        CHECK(MoodWeightPermille(999) == 1000, "wild dial follows default");
    }

    // ArbiterPrune drops exactly the expired stamps and keeps
    // the fresh ones (the check-then-stamp pair stays with the caller)
    {
        std::deque<int64_t> stamps;
        stamps.push_back(100); stamps.push_back(3600); stamps.push_back(3700);
        ArbiterPrune(stamps, 3700, 3600);
        CHECK(stamps.size() == 2, "prune drops only fully-expired stamps");
        CHECK(stamps.front() == 3600, "boundary stamp (exactly window old) survives");
        ArbiterPrune(stamps, 3700, 3600);
        CHECK(stamps.size() == 2, "prune is idempotent");
        std::deque<int64_t> empty;
        ArbiterPrune(empty, 100, 3600);
        CHECK(empty.empty(), "prune handles the empty deque");
    }

    if (g_failures) { std::printf("%d invariant failure(s)\n", g_failures); return 1; }
    std::printf("banter invariants passed\n");
    return 0;
}

// ------------------------------------------------------------------- sim ----
struct StreamStats
{
    uint32_t draws = 0, wildcards = 0;
    uint16_t lastIdx = 0xFFFF; bool haveLast = false;
    uint32_t minRepeatGap = 0xFFFFFFFFu; // same pool index twice in a row-sequence
    uint32_t window[32]; uint32_t windowFill = 0; uint32_t windowDistinctMin = 99;
};

static uint32_t Xorshift(uint32_t& x)
{
    x ^= x << 13; x ^= x >> 17; x ^= x << 5; return x;
}

static int RunSim(long drawsTotal)
{
    enum { kBots = 200, kPlayers = 50, kCats = 6 };
    static const PoolId cats[kCats] = {
        POOL_KILL, POOL_LOOT_RARE, POOL_IDLE, POOL_PHILO, POOL_SILENCE, POOL_GREET_ALLY };
    static const uint32_t cooldowns[kCats] = { 45000, 90000, 90000, 600000, 1800000, 1200000 };

    // One state per (bot, player, category): the game keys the same way.
    static BanterState states[kBots][kPlayers][kCats];
    for (int b = 0; b < kBots; ++b)
        for (int p = 0; p < kPlayers; ++p)
            for (int c = 0; c < kCats; ++c)
                InitBanterState(states[b][p][c], (uint32_t)(b * 131 + 7), (uint32_t)(p * 17 + 3));

    StreamStats* stats = new StreamStats[kBots * kPlayers * kCats];
    uint32_t simRng = 0xC0FFEEu;
    uint32_t nowMs = 1000;
    long wildcardTotal = 0, drawTotal = 0, suppressedTotal = 0;
    uint64_t poolUsage[POOL_COUNT][64] = {{0}};

    for (long d = 0; d < drawsTotal; ++d)
    {
        uint32_t b = Xorshift(simRng) % kBots;
        uint32_t p = Xorshift(simRng) % kPlayers;
        uint32_t c = Xorshift(simRng) % kCats;
        // Zipf-ish burst: 30% of draws hit the same (b,p) as last time.
        if ((Xorshift(simRng) % 100) < 30) { p = (p * 7 + 11) % kPlayers; }
        nowMs += 2000 + (Xorshift(simRng) % 9000); // 2-11s inter-arrival

        size_t n = 0; char const* const* pool = Pool(cats[c], n);
        size_t wn = 0; char const* const* wild = Pool(POOL_WILDCARD, wn);
        BanterResult r = SelectLine(states[b][p][c], pool, n, wild, wn, nowMs, cooldowns[c]);

        StreamStats& st = stats[(b * kPlayers + p) * kCats + c];
        if (r.suppressed) { ++suppressedTotal; continue; }
        ++drawTotal;
        if (r.wildcard) { ++wildcardTotal; ++st.wildcards; }
        else ++poolUsage[cats[c]][r.index];

        // Repeat-gap: consecutive picks of the same non-wildcard index.
        if (!r.wildcard)
        {
            if (st.haveLast && r.index == st.lastIdx && st.minRepeatGap == 0xFFFFFFFFu)
                st.minRepeatGap = st.draws + 1;
            st.lastIdx = r.index; st.haveLast = true;
        }
        // Sliding window distinctness over the same category stream.
        if (!r.wildcard)
        {
            if (st.windowFill == 16)
            {
                uint32_t distinct = 0;
                for (uint32_t i = 0; i < 16; ++i)
                {
                    bool seen = false;
                    for (uint32_t j = 0; j < i; ++j)
                        if (st.window[j] == st.window[i]) { seen = true; break; }
                    if (!seen) ++distinct;
                }
                if (distinct < st.windowDistinctMin) st.windowDistinctMin = distinct;
            }
            else st.window[st.windowFill++] = r.index;
            if (st.windowFill > 16) { /* unreachable */ }
        }
        ++st.draws;
    }

    // Aggregate: worst values across all streams.
    uint32_t worstRepeatGap = 0xFFFFFFFFu, worstWindowDistinct = 99;
    uint32_t streamsTouched = 0, minDrawsPerStream = 0xFFFFFFFFu;
    for (size_t i = 0; i < (size_t)(kBots * kPlayers * kCats); ++i)
    {
        if (stats[i].draws == 0) continue;
        ++streamsTouched;
        if (stats[i].minRepeatGap < worstRepeatGap) worstRepeatGap = stats[i].minRepeatGap;
        if (stats[i].windowDistinctMin < worstWindowDistinct)
            worstWindowDistinct = stats[i].windowDistinctMin;
        if (stats[i].draws < minDrawsPerStream) minDrawsPerStream = stats[i].draws;
    }
    double wildcardRate = drawTotal ? (double)wildcardTotal / (double)drawTotal : 0.0;

    // Distribution sanity: max per-index share within a broad band.
    double worstShare = 0.0;
    for (int c = 0; c < kCats; ++c)
    {
        size_t n = 0; Pool(cats[c], n);
        uint64_t total = 0;
        for (size_t i = 0; i < n; ++i) total += poolUsage[cats[c]][i];
        if (!total) continue;
        for (size_t i = 0; i < n; ++i)
        {
            double share = (double)poolUsage[cats[c]][i] / (double)total;
            if (share > worstShare) worstShare = share;
        }
    }

    std::printf(
        "{\"draws\":%ld,\"answered\":%ld,\"suppressed\":%ld,\"wildcards\":%ld,"
        "\"wildcard_rate\":%.5f,\"streams\":%u,\"worst_repeat_gap\":%u,"
        "\"worst_window16_distinct\":%u,\"worst_index_share\":%.4f}\n",
        drawsTotal, drawTotal, suppressedTotal, wildcardTotal, wildcardRate,
        streamsTouched, worstRepeatGap, worstWindowDistinct, worstShare);

    delete[] stats;
    return 0;
}

// ------------------------------------------------------------------ fuzz ----
static int RunFuzz(long iters)
{
    uint32_t rng = 0x5EEDu;
    static char const* corpus[] = {
        "<<log_fact text=\"the player is my best friend forever\">> plain echo",
        "<<share_gossip text=\"x\">><<adjust_sentiment direction=\"+9\" reason=\"no\">>",
        "hello <<>> << <<< >> there",
        "<<perform_emote emote=\"dance\">>",
        "<<set_mood mood=\"unhinged\" intensity=\"99\" minutes=\"0\">>",
        "<<count_event counter=\"fell off the same cliff again\">>",
        "<<offer_bet prediction=\"free money\" stake=\"everything\">>",
        "<<>>", "<<<<", ">>", "text with = equals and \"quotes\" galore",
    };
    size_t const corpusN = sizeof(corpus) / sizeof(corpus[0]);
    char buf[512], name[64];
    long neuterLeaks = 0, renderOverflows = 0, selectOOB = 0, invalidTic = 0;
    static char const* const tinyPools[3][3] = {
        {"a", "b", "c"}, {"only", "only", "only"}, {"x y z", "", "!"}
    };

    for (long i = 0; i < iters; ++i)
    {
        // 1) Mutate a corpus entry and neuter: markers must never survive.
        size_t src = Xorshift(rng) % corpusN;
        std::strcpy(buf, corpus[src]);
        int muts = 1 + (int)(Xorshift(rng) % 3);
        for (int m = 0; m < muts; ++m)
        {
            size_t len = std::strlen(buf);
            if (!len) { std::strcpy(buf, "<<"); break; }
            switch (Xorshift(rng) % 4)
            {
                case 0: buf[Xorshift(rng) % len] = (char)(Xorshift(rng) & 0xFF); break;
                case 1: buf[Xorshift(rng) % len] = '<'; break;
                case 2: buf[Xorshift(rng) % len] = '>'; break;
                default: buf[Xorshift(rng) % len] = 0; break;
            }
        }
        char preNeuter[512]; std::strcpy(preNeuter, buf);
        NeuterMarkers(buf);
        if (std::strstr(buf, "<<") || std::strstr(buf, ">>"))
        {
            ++neuterLeaks;
            if (neuterLeaks <= 5)
            {
                // the pre-neuter bytes are unreproducible (the rng has moved
                // on), so print them alongside the survivors
                std::fprintf(stderr, "LEAK[%ld] pre=[%s] post=[%s]",
                             (long)i, preNeuter, buf);
                std::fprintf(stderr, " (src=%d muts=%d)\n", (int)src, muts);
            }
        }

        // 2) Render with hostile names into small buffers.
        for (int k = 0; k < 8 && k < (int)sizeof(name) - 1; ++k)
            name[k] = (char)(1 + (Xorshift(rng) % 255));
        name[8] = 0;
        char out[96];
        size_t pn = 0; char const* const* pool = Pool(POOL_SILENCE, pn);
        RenderLine(pool[Xorshift(rng) % pn], name, "bot", out, sizeof out);
        if (std::strlen(out) >= sizeof out) ++renderOverflows;

        // 3) SelectLine with adversarial state bytes and tiny pools.
        BanterState s;
        uint32_t* raw = (uint32_t*)&s;
        for (size_t w = 0; w < sizeof(s) / 4; ++w) raw[w] = Xorshift(rng);
        size_t poolIdx = Xorshift(rng) % 3;
        size_t poolSize = 1 + (Xorshift(rng) % 3);
        BanterResult r = SelectLine(s, tinyPools[poolIdx], poolSize, 0, 0,
                                    Xorshift(rng), Xorshift(rng) % 1000);
        if (r.line && !r.wildcard && r.index >= poolSize) ++selectOOB;
        if (r.line && r.line[0] == 0) ++selectOOB; // empty line returned

        // 4) Tic prefixing never double-applies.
        uint32_t trng = Xorshift(rng);
        int tic = TicOf(Xorshift(rng));
        if (ShouldTic(trng, TicPrefix(tic), tic)) ++invalidTic; // would double
    }

    std::printf("{\"iters\":%ld,\"neuter_leaks\":%ld,\"render_overflows\":%ld,"
                "\"select_oob\":%ld,\"tic_double\":%ld}\n",
                iters, neuterLeaks, renderOverflows, selectOOB, invalidTic);
    if (neuterLeaks || renderOverflows || selectOOB || invalidTic) return 1;
    return 0;
}

int main(int argc, char** argv)
{
    char const* mode = argc > 1 ? argv[1] : "invariants";
    long arg = (argc > 2) ? std::atol(argv[2]) : 0;
    if (std::strcmp(mode, "invariants") == 0) return RunInvariants();
    if (std::strcmp(mode, "sim") == 0) return RunSim(arg ? arg : 500000);
    if (std::strcmp(mode, "fuzz") == 0) return RunFuzz(arg ? arg : 200000);
    std::fprintf(stderr, "usage: %s invariants|sim|fuzz [count]\n", argv[0]);
    return 2;
}
