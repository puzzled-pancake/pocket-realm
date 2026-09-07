#include "PlayerbotLlmMemory.h"

#include "PlayerbotLlmBridge.h"
#include "PlayerbotLlmChatter.h"
#include "PlayerbotLlmChatterCore.h"
#include "PlayerbotLlmPersona.h"
#include "PlayerbotLlmPrompt.h"
#include "PlayerbotLlmRecallCore.h"
#include "PlayerbotLlmTools.h"
#include "PlayerbotLLMInterface.h"
#include "llm_banter_core.h"
#include "playerbot/playerbot.h"
#include "playerbot/PlayerbotAIConfig.h"
#include "playerbot/RandomPlayerbotMgr.h"
#include "playerbot/ServerFacade.h"
#include "Chat/Chat.h"
#include "Entities/Player.h"
#include "Globals/ObjectAccessor.h"
#include "Grids/CellImpl.h"
#include "Grids/GridNotifiers.h"
#include "Grids/GridNotifiersImpl.h"
#include "Groups/Group.h"
#include "Maps/Map.h"
#include "Weather/Weather.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <deque>
#include <fstream>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <thread>

namespace {

std::string EscapeSql(std::string const& text)
{
    std::string out;
    out.reserve(text.size() + 8);
    for (char c : text)
    {
        if (c == '\'')
            out += "''";
#ifdef DO_SQLITE
        // SQLite string literals have no backslash escapes (only the ''
        // doubling above): doubling backslashes here would store every
        // user backslash twice (chat text is full of them).
#else
        else if (c == '\\')
            out += "\\\\";
#endif
        else
            out += c;
    }
    return out;
}

uint32 RaceId(Player* bot) { return bot->getRace(); }
uint32 ClassId(Player* bot) { return bot->getClass(); }

// byte-bound truncation that never splits a multibyte UTF-8 character: a
// split sequence would make strict-mode MariaDB reject the whole statement
std::string TruncUtf8(std::string const& text, size_t maxBytes)
{
    std::string s = text.substr(0, maxBytes);
    while (!s.empty() && (static_cast<unsigned char>(s.back()) & 0xC0) == 0x80)
        s.pop_back(); // drop orphaned continuation bytes
    if (!s.empty() && (static_cast<unsigned char>(s.back()) & 0xC0) == 0xC0)
        s.pop_back(); // drop a lead byte whose sequence was cut
    return s;
}
// Phase-1 prompt pack: read the staged pack file ONCE per process (the
// path is fixed at world start; the file is staged at realm start) and
// cache the rendered seasoning overlay. Empty/missing/unreadable =
// trained default (empty seasoning, byte-identical). Reloads are NOT
// supported mid-process — the pack applies at the next realm start like
// every llm* setting. Mutex-guarded check-then-resolve like the sibling
// one-shot loaders (EraBiasJson / LoadedLore): map threads hit this
// concurrently on the first generation, and the flag must only flip
// AFTER the cache is populated (an early flip would pin an empty
// seasoning for the process lifetime).
std::string LoadPackSeasoning()
{
    static std::mutex mutex;
    static bool loaded = false;
    static std::string cached;
    std::lock_guard<std::mutex> lock(mutex);
    if (loaded)
        return cached;
    std::string seasoning;
    std::string const& path = sPlayerbotAIConfig.llmPromptPackFile;
    if (!path.empty() && path.size() <= 512)
    {
        std::ifstream in(path.c_str());
        if (in.is_open())
        {
            std::string json;
            json.reserve(4096);
            char buf[1024];
            while (in.good() && json.size() < 65536)
            {
                in.read(buf, sizeof(buf));
                json.append(buf, static_cast<size_t>(in.gcount()));
            }
            seasoning = pocketllm::SeasoningFromPackJson(
                json, sPlayerbotAIConfig.llmPromptBlockOverride);
            // plan v5 5.1: the hard seasoning budget - whole trailing
            // lines drop past ~1200 bytes (the 300-token cap at ~4
            // chars/token). The pack editor's soft meter warns; this is
            // the enforced ceiling that keeps every local generation's
            // prefill bounded
            if (seasoning.size() > 1200)
            {
                size_t cut = seasoning.rfind('\n', 1200);
                seasoning = cut == std::string::npos
                    ? TruncUtf8(seasoning, 1200)
                    : seasoning.substr(0, cut);
            }
        }
    }
    cached = seasoning;
    loaded = true;
    return cached;
}


// The LLM tables ship as utf8 (= utf8mb3 on MariaDB): astral-plane
// characters (4-byte UTF-8 sequences, e.g. emoji) are valid UTF-8 but
// rejected by those columns in strict mode, which would silently drop the
// whole async INSERT (the write path never sees the error). 1-3 byte
// sequences - all Latin/CJK/etc - pass through untouched.
std::string StripAstral(std::string const& text)
{
    std::string out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size();)
    {
        unsigned char const c = static_cast<unsigned char>(text[i]);
        if ((c & 0xF8) == 0xF0)
        {
            // drop the whole sequence so its continuation bytes cannot
            // leak through as stray bytes
            i += std::min<size_t>(4, text.size() - i);
            continue;
        }
        out.push_back(text[i]);
        ++i;
    }
    return out;
}

std::string RaceName(uint32 race)
{
    switch (race)
    {
        case 1: return "human";
        case 2: return "orc";
        case 3: return "dwarf";
        case 4: return "night elf";
        case 5: return "undead";
        case 6: return "tauren";
        case 7: return "gnome";
        case 8: return "troll";
        default: return "wanderer";
    }
}

std::string ClassName(uint32 cls)
{
    switch (cls)
    {
        case 1: return "warrior";
        case 2: return "paladin";
        case 3: return "hunter";
        case 4: return "rogue";
        case 5: return "priest";
        case 7: return "shaman";
        case 8: return "mage";
        case 9: return "warlock";
        case 11: return "druid";
        default: return "adventurer";
    }
}

// staged personality drift: small, predefined trait shifts unlocked per tier
char const* TierTrait(char const* tier)
{
    if (!strcmp(tier, "bonded")) return "You would follow them anywhere, and they know it.";
    if (!strcmp(tier, "trusted")) return "You now share private thoughts and old memories freely.";
    if (!strcmp(tier, "ally")) return "You speak freely and joke with them like an old comrade.";
    if (!strcmp(tier, "acquaintance")) return "You are warm but still a little reserved.";
    return "You keep a polite distance; you hardly know them yet.";
}

// journal-surface rendering of a storage tier: prose, never the enum (the
// thresholds themselves live only in the SQL tier expression)
char const* TierProse(char const* tier)
{
    if (!strcmp(tier, "bonded")) return "one you would follow anywhere";
    if (!strcmp(tier, "trusted")) return "one you trust with anything";
    if (!strcmp(tier, "ally")) return "a comrade of many battles";
    if (!strcmp(tier, "acquaintance")) return "well met, not yet well known";
    return "a stranger to you still";
}

// the trained absence line shape (banklib card absence): "{name} was last
// seen {bucket} ago." / first-meeting variant
std::string AbsenceLineFor(std::string const& playerName, std::string const& bucket)
{
    if (bucket == "a first meeting")
        return "You are meeting " + playerName + " for the first time.";
    return playerName + " was last seen " + bucket + " ago.";
}

// short trained-style trait phrase for the default-backstory quirks slot,
// derived from the same GUID-stable quirk the banter layer voices
char const* QuirkPhrase(uint32 botGuid)
{
    static char const* const phrases[] = {
        "superstitious about omens",
        "morbid about graves",
        "poetic about small things",
        "mischievous with a straight face",
        "boastful when the tale is good",
        "miserly with coin",
        "devout in casual ways",
        "curious about strange objects",
    };
    return phrases[pocketllm::QuirkOf(botGuid) % (sizeof(phrases) / sizeof(phrases[0]))];
}

// the bot's current zone name in the client locale (trained sysm zone slot)
std::string ZoneNameOf(Player* bot)
{
    if (bot->GetPlayerbotAI())
        if (AreaTableEntry const* zone = bot->GetPlayerbotAI()->GetCurrentZone())
            return bot->GetPlayerbotAI()->GetLocalizedAreaName(zone);
    // real players carry no PlayerbotAI: resolve their own zone through the
    // area table in the session's locale (plan v5 W1/W3 pass real players
    // here - the victim, the tapper)
    if (AreaTableEntry const* zone = GetAreaEntryByAreaID(bot->GetZoneId()))
        if (bot->GetSession())
            return zone->area_name[bot->GetSession()->GetSessionDbcLocale()];
    return "the wilds";
}

// one rolling turn, speaker kept SEPARATE from the line so the trained
// format can render role-separated history without name prefixes (the
// legacy renderer joins them back into "Speaker: line" byte-identically)
struct HistoryLine
{
    std::string speaker;
    std::string line;
    HistoryLine() {}
    HistoryLine(std::string const& s, std::string const& l) : speaker(s), line(l) {}
};

struct RollingHistory
{
    std::mutex mutex;
    std::map<uint64, std::deque<HistoryLine>> turns;
    // per-conversation request counter for the companion state-flavor
    // rotation (advances once per BuildTrainedChatRequest, so it
    // alternates even when the history window saturates)
    std::map<uint64, uint32> stateRotation;
    // C8: the persistence lane - hydrated keys (the lazy DB load into
    // the deque happened this process) and the per-key monotone seq the
    // bot_player_history PK needs. Both guarded by `mutex`.
    std::set<uint64> hydrated;
    std::map<uint64, uint32> lastSeq;
};

RollingHistory& History()
{
    static RollingHistory instance;
    return instance;
}

uint64 HistoryKey(uint32 bot, uint32 playerOrChannel)
{
    return (static_cast<uint64>(bot) << 32) | playerOrChannel;
}

// C8: lazily load a pairing's persisted tail into the deque (once per
// key per process). Called with `mutex` HELD; the sync read is
// once-per-pairing so the lock hold is bounded.
void HydrateHistoryIfNeeded(RollingHistory& history, uint32 bot,
    uint32 playerOrChannel, std::deque<HistoryLine>& turns)
{
    uint64 const key = HistoryKey(bot, playerOrChannel);
    if (history.hydrated.count(key))
        return;
    history.hydrated.insert(key);
    if (!sPlayerbotAIConfig.llmHistoryPersist)
        return;
    auto result = CharacterDatabase.PQuery(
        "SELECT `seq`, `speaker`, `line` FROM `bot_player_history` "
        "WHERE `bot` = '%u' AND `player_or_channel` = '%u' "
        "ORDER BY `seq` DESC LIMIT 32",
        bot, playerOrChannel);
    if (!result)
        return;
    std::vector<HistoryLine> loaded;
    uint32 maxSeq = 0;
    do
    {
        Field* fields = result->Fetch();
        uint32 const seq = fields[0].GetUInt32();
        if (seq > maxSeq)
            maxSeq = seq;
        loaded.push_back(HistoryLine(fields[1].GetString(), fields[2].GetString()));
    } while (result->NextRow());
    // fetched newest-first: prepend oldest-first ahead of whatever the
    // process already holds (nothing, by construction - hydration runs
    // on first touch)
    for (auto itr = loaded.rbegin(); itr != loaded.rend(); ++itr)
        turns.push_front(*itr);
    history.lastSeq[key] = maxSeq;
}

void AppendHistoryTurn(uint32 bot, uint32 playerOrChannel,
    std::string const& speaker, std::string const& line)
{
    RollingHistory& history = History();
    std::lock_guard<std::mutex> lock(history.mutex);
    std::deque<HistoryLine>& turns = history.turns[HistoryKey(bot, playerOrChannel)];
    HydrateHistoryIfNeeded(history, bot, playerOrChannel, turns);
    // bounded per turn as well: unbounded lines could crowd out the
    // stable segments in the prompt budget below
    turns.push_back(HistoryLine(speaker, TruncUtf8(line, 240)));
    // C8: the persistent tail rides the same choke point (LLMHistoryPersist,
    // default on; 0 keeps history process-local exactly as before). The
    // fire-and-forget INSERT never blocks the conversation; the seq is
    // the per-key monotone the (bot, player_or_channel, seq) PK needs.
    if (sPlayerbotAIConfig.llmHistoryPersist)
    {
        uint64 const key = HistoryKey(bot, playerOrChannel);
        uint32 const seq = ++history.lastSeq[key];
        CharacterDatabase.PExecute(
#ifdef DO_SQLITE
            // strftime('now') is UTC; MySQL's UNIX_TIMESTAMP reads the
            // session tz - each engine is internally consistent with its
            // own reads (the ts column is a display hint, never keyed)
            "INSERT INTO `bot_player_history` (`bot`, `player_or_channel`, `seq`, `speaker`, `line`, `ts`) "
            "VALUES ('%u', '%u', '%u', '%s', '%s', strftime('%%s','now'))",
#else
            "INSERT INTO `bot_player_history` (`bot`, `player_or_channel`, `seq`, `speaker`, `line`, `ts`) "
            "VALUES ('%u', '%u', '%u', '%s', '%s', UNIX_TIMESTAMP())",
#endif
            bot, playerOrChannel, seq,
            EscapeSql(TruncUtf8(speaker, 12)).c_str(),
            EscapeSql(TruncUtf8(line, 240)).c_str());
        // trim beyond the store cap + slack so the table stays bounded
        if (seq > 40)
            CharacterDatabase.PExecute(
                "DELETE FROM `bot_player_history` WHERE `bot` = '%u' AND `player_or_channel` = '%u' AND `seq` <= '%u'",
                bot, playerOrChannel, seq - 32);
    }
    // tail-capped: only the oldest rolling turns ever drop, never the stable
    // prompt segments (which never live here). The storage cap must clear
    // the reader's largest window (BuildTrainedChatRequest): 32 on the
    // external API tier, 20 keeps the device-era footprint otherwise.
    size_t const storeCap = PlayerbotLlmMemory::ExternalApiTierActive() ? 32 : 20;
    while (turns.size() > storeCap)
        turns.pop_front();
}

// Prompt-framing control tokens must never persist into memory text: a
// fact or gossip row carrying "[BRIDGE AI] ..." (model-copied from player
// input) would render inside the SYSTEM facts segment later - it cannot
// forge tool syntax (markers die in the neuter), but it can steer tone in
// a slot the model was trained to treat as instruction-bearing.

// verified-event window gating share_gossip. Writers include map-thread
// core hooks (GiveLevel), so every access is guarded.
// LOCK-ORDER CONTRACT (round-6 R1#3, now stated): StateMutex is a leaf
// - it is taken while the caller may hold the chat-drain
// chatRepliesMutex (SayAction's claim leg), but StateMutex scopes must
// never acquire chatRepliesMutex (or queue a chat reply) in return;
// the reverse ordering would invert silently.
std::mutex& StateMutex()
{
    static std::mutex instance;
    return instance;
}

std::map<uint32, time_t>& VerifiedEvents()
{
    static std::map<uint32, time_t> instance;
    return instance;
}

// rate limiter for the bounded conversational sentiment input
std::map<uint64, time_t>& SentimentRate()
{
    static std::map<uint64, time_t> instance;
    return instance;
}

// C6: the per-pairing daily TURN-award counters (keyed like
// SentimentRate; day-bucketed UTC, process-local - a restart resets
// them, the same documented reset semantics every quota carries)
std::map<uint64, std::pair<time_t, uint32>>& TurnAwards()
{
    static std::map<uint64, std::pair<time_t, uint32>> instance;
    return instance;
}

// C4: the per-master party-digest window (a bounded 12-line deque) and
// the monotone window index that keys the MintOnceFact dedupe
std::map<uint32, std::deque<std::string>>& PartyDigestWindow()
{
    static std::map<uint32, std::deque<std::string>> instance;
    return instance;
}

std::map<uint32, uint32>& PartyDigestIndex()
{
    static std::map<uint32, uint32> instance;
    return instance;
}

// gossip slice cache: pinning the slice per bot keeps the prompt segment
// byte-stable between turns (world-pool churn only re-reads after expiry)
std::map<uint32, std::pair<time_t, std::string>>& GossipCache()
{
    static std::map<uint32, std::pair<time_t, std::string>> instance;
    return instance;
}

// per-pairing acknowledgment rate limiter (the never-cleaned
// GUID-statics class, bounded by pairing populations)
std::map<uint64, time_t>& AckTimestamps()
{
    static std::map<uint64, time_t> instance;
    return instance;
}

// The onboarding line's once-per-character dedupe.
// SendInitialPacketsAfterAddToMap runs at login AND on every cross-map far
// teleport (MoveWorldportAck) - the DB no-pairing gate alone would re-voice
// the line at every portal. One world-process voice per character GUID
// (bounded by character population, the accepted statics class).
std::set<uint32>& OnboardedPlayers()
{
    static std::set<uint32> instance;
    return instance;
}

// The standing one-liner's once-per-session dedupe (one world-process
// voice per pairing - "first whisper of a session")
std::set<uint64>& StandingVoicedPairs()
{
    static std::set<uint64> instance;
    return instance;
}

// Player-facing conversations counter (diagnostics; relaxed - a
// monotonic count with no ordering requirement)
std::atomic<uint64_t>& ConversationCounter()
{
    static std::atomic<uint64_t> instance(0);
    return instance;
}

// Phase-3 mood weather: per-bot nudge counters (bounded by bot
// population, the accepted statics class). The hourly bucket comes
// from time()/3600 so weather drifts slowly; event nudges fold in
// immediately - grudge (a wronging: negative bounded sentiment, a duel
// loss or a player's cheap flee), smitten (a tier-up ceremony), grief
// (a tier-loss crossing, from the bridge).
std::map<uint32, uint32>& MoodNudges()
{
    static std::map<uint32, uint32> instance;
    return instance;
}

// plan v5 F6 (generic mint-once marker): per-pairing last-mint stamps so
// "watched X fall in Duskwood" or "traveled with X to Westfall for the
// first time" mints at most once per window (deaths and zone crossings
// are frequent; the LEDGER must not drown in them)
std::map<uint64, time_t>& MintOnceAt()
{
    static std::map<uint64, time_t> instance;
    return instance;
}

// plan v5 W1: the pending post-wipe shaken line - bot -> (player, expiry).
// Armed at the wipe (when every bot is dead and cannot speak), consumed by
// TickInitiative when the bot is alive and the player is back in range
std::map<uint32, std::pair<uint32, time_t>>& PendingAftermath()
{
    static std::map<uint32, std::pair<uint32, time_t>> instance;
    return instance;
}

// plan v5 W5: the curiosity state - per-pair asked-question bitmask (the
// question bank is 16 wide; the mask is GUID-stable-ordered, one ask per
// question per pairing per process), the 30-minute ask floor, and the
// armed pending answer (player -> (bot, expiry); consumed by the bridge
// on the player's next conversational turn)
std::map<uint64, uint32>& CuriosityAskedMask()
{
    static std::map<uint64, uint32> instance;
    return instance;
}

std::map<uint64, time_t>& CuriosityLastAsk()
{
    static std::map<uint64, time_t> instance;
    return instance;
}

std::map<uint64, std::pair<uint32, time_t>>& PendingAnswer()
{
    static std::map<uint64, std::pair<uint32, time_t>> instance;
    return instance;
}

// plan v5 F2: the dyad ledger - ordered pair key -> (affinity, newest
// event, voiced flag). Process-lifetime by design
struct DyadEntry
{
    int points = 0;
    std::string newestEvent;
    bool voiced = false;
};
std::map<uint64, DyadEntry>& Dyads()
{
    static std::map<uint64, DyadEntry> instance;
    return instance;
}

// plan v5 F7: the authored-line hourly ledger - one global deque plus one
// per category, caller-guarded by StateMutex (check-then-stamp for the
// global+category pair happens under the one lock so a stamp can never
// land without its line)
struct AuthoredArbiter
{
    std::deque<int64_t> global;
    std::deque<int64_t> cat[3];
};
AuthoredArbiter& Arbiter()
{
    static AuthoredArbiter instance;
    return instance;
}

} // namespace

// Class-member definitions must sit OUTSIDE the anonymous namespace
// above (defining PlayerbotLlmMemory:: members inside an unnamed
// namespace is ill-formed and breaks the world build; the host
// batteries never compile this TU, so only a real compiler sees it).
int PlayerbotLlmMemory::MoodNow(uint32 botGuid)
{
    uint32 bucket = (uint32)(time(nullptr) / 3600);
    uint32 nudges = 0;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        std::map<uint32, uint32>::const_iterator it = MoodNudges().find(botGuid);
        if (it != MoodNudges().end())
            nudges = it->second;
    }
    return pocketllm::MoodIndexOf(botGuid, bucket, nudges);
}

void PlayerbotLlmMemory::NudgeMood(uint32 botGuid, int mood)
{
    if (mood != pocketllm::MOOD_GRUDGE && mood != pocketllm::MOOD_SMITTEN &&
        mood != pocketllm::MOOD_GRIEF)
        return;
    std::lock_guard<std::mutex> lock(StateMutex());
    // fold the nudge into the counter so the weather shifts; the bucket
    // rotation keeps it from sticking forever
    MoodNudges()[botGuid] += (uint32)(mood + 1);
}

std::string PlayerbotLlmMemory::MoodLineFor(uint32 botGuid)
{
    return pocketllm::MoodSeasoningLine(MoodNow(botGuid));
}

std::string PlayerbotLlmMemory::ScrubControlTokens(std::string const& text)
{
    static char const* const tokens[] = {
        "[BRIDGE AI]", "[EVENT]", "[RESULT]", "[say]", "[Memories]", "[State]",
    };
    std::string out = text;
    // Fixpoint pass: deleting one token can FUSE the
    // remains of another ("[RESU[Memories]LT]" -> "[RESULT]"), and a
    // single ordered sweep re-checks only the token it is currently
    // deleting - the fused live token was already scanned. Loop the
    // whole table until a full pass removes nothing (the same law
    // NeuterMarkers applies to marker fusions).
    bool removed = true;
    while (removed)
    {
        removed = false;
        for (char const* token : tokens)
        {
            for (size_t at = out.find(token); at != std::string::npos;
                 at = out.find(token))
            {
                out.replace(at, strlen(token), "");
                removed = true;
            }
        }
    }
    return out;
}

std::string PlayerbotLlmMemory::GetOrCreateBackstory(Player* bot)
{
    uint32 const botGuid = bot->GetGUIDLow();

    auto result = CharacterDatabase.PQuery(
        "SELECT `text` FROM `bot_backstory` WHERE `bot` = '%u'", botGuid);
    if (result)
    {
        std::string text = result->Fetch()[0].GetString();
        return text;
    }

    // varied per bot (guid-seeded, still deterministic) and deliberately
    // level-free: a creation-time level reads wrong thirty levels later
    static char const* const flavours[] = {
        "who has walked hard roads and kept long watches",
        "who came up in the alleys of a crowded city, mending what others broke",
        "who learned their trade from a patient master in a quiet village",
        "who traded a settled life for the road and never looked back",
    };
    std::ostringstream out;
    out << "Background: " << bot->GetName() << " is a " << RaceName(RaceId(bot))
        << " " << ClassName(ClassId(bot)) << " "
        << flavours[botGuid % (sizeof(flavours) / sizeof(flavours[0]))]
        << ". Their past is their own.";
    std::string text = out.str();

    CharacterDatabase.PExecute(
#ifdef DO_SQLITE
        "INSERT OR IGNORE INTO `bot_backstory` (`bot`, `text`) VALUES ('%u', '%s')",
#else
        "INSERT IGNORE INTO `bot_backstory` (`bot`, `text`) VALUES ('%u', '%s')",
#endif
        botGuid, EscapeSql(text).c_str());
    return text;
}

void PlayerbotLlmMemory::AppendTurn(uint32 bot, uint32 playerOrChannel, bool sharedChannel,
    std::string const& speaker, std::string const& line)
{
    (void)sharedChannel;
    // injection choke point: every history writer funnels through here, so
    // player-carried tool markers AND prompt furniture die before the text
    // can ever reach a later prompt (the model must not be able to echo
    // protocol back as a forged call, nor to persist a forged [RESULT]/
    // [BRIDGE AI] line as bridge-authored truth). Speaker names are
    // server-side and clean by construction. Order per the injection law:
    // deletions first, neuter LAST (strip/scrub fusions cannot re-open).
    AppendHistoryTurn(bot, playerOrChannel, speaker,
        pocketllm::NeuterMarkersCopy(ScrubControlTokens(
            StripAstral(line)).c_str()));
}

std::string PlayerbotLlmMemory::BuildPromptContext(Player* bot, Player* player, int chatChannelSource,
    std::string const& chanName)
{
    (void)chanName;
    std::ostringstream out;

    // segment 1: the backstory anchor, byte-identical every turn
    out << GetOrCreateBackstory(bot) << "\n";

    // segment 1b: per-bot trait seasoning (GUID-derived, byte-stable for the
    // bot's life) - the same temperament/habit the authored banter layer
    // voices, injected so the MODEL keeps the voice too. Never changes, so
    // the warm-slot prefix stays valid (format version v4 covers the insert).
    uint32 const botGuid = bot->GetGUIDLow();
    out << pocketllm::DemeanorSeasoning(pocketllm::DemeanorOf(botGuid)) << "\n";
    out << pocketllm::QuirkSeasoning(pocketllm::QuirkOf(botGuid)) << "\n";

    // segment 2: relationship tier in the TRAINED dialect ("Relationship
    // with X: Warm (tier 3 of 5)." - the DB's four storage tiers map onto
    // the trained 1-5 scale, Bonded deriving from points >= 120 on read),
    // plus the wall-clock absence line in the trained shape
    if (player)
    {
        int const tier = GetTrainedTier(bot, player);
        out << "Relationship with " << player->GetName() << ": "
            << pocketllm::TierLabel(tier) << " (tier " << tier << " of 5). "
            << TierTrait(pocketllm::TierStorageName(tier)) << "\n";
        out << AbsenceLineFor(player->GetName(), GetAbsenceBucket(bot, player)) << "\n";
    }

    // Upstream LimitContext (SayAction) trims the FRONT of this context once
    // the pre-prompt, prompt and these segments together exceed
    // AiPlayerbot.LLMContextLength - the backstory and standing lines must
    // fit that window by construction, not by hope. The waterfall reserves
    // room for the caller's pre/post prompt and tool instructions, then
    // admits the facts (newest win), the gossip slice and finally the
    // rolling turns; within each tier the oldest entries drop first, so the
    // front of the context is never what gets cut. A zero window means
    // LimitContext is disabled: fall back to the fixed rolling cap.
    uint32 const window = sPlayerbotAIConfig.llmContextLength;
    size_t used = out.str().size();
    size_t reserve = 0;
    if (window)
    {
        reserve = sPlayerbotAIConfig.llmPrePrompt.size() + sPlayerbotAIConfig.llmPrompt.size()
            + sPlayerbotAIConfig.llmPostPrompt.size()
            + 384; // separator + expanded placeholders (a 255-char whisper
                   // can land whole inside <initial message>) + saved custom prompt
        if (sPlayerbotAIConfig.llmToolsEnabled)
            reserve += PlayerbotLlmTools::ToolInstructions(bot->GetGUIDLow()).size();
        reserve += 256; // the bridge note block appended to <post prompt>
    }
    auto fits = [&](size_t cost) { return !window || used + reserve + cost <= window; };

    // segment 3: injected facts, append-only, category-prioritized, stable
    // order - TRAINED dialect: inline, "; "-joined, oldest-first
    if (player)
    {
        auto result = CharacterDatabase.PQuery(
            "SELECT `fact_text` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
            "ORDER BY `id` DESC LIMIT 8",
            bot->GetGUIDLow(), player->GetGUIDLow());
        if (result)
        {
            std::vector<std::string> facts;
            do
            {
                facts.push_back(result->Fetch()[0].GetString());
            } while (result->NextRow());

            if (!facts.empty())
            {
                std::string const header = std::string("Facts you remember about ")
                    + player->GetName() + ": ";
                // inline join; under window pressure the OLDEST facts drop
                // (resize keeps the head of the newest-first fetch)
                if (window)
                {
                    size_t factsUsed = 0;
                    size_t keep = 0;
                    for (std::string const& fact : facts)
                    {
                        size_t const cost = fact.size() + 3;
                        if (!fits(header.size() + factsUsed + cost))
                            break;
                        factsUsed += cost;
                        ++keep;
                    }
                    facts.resize(keep);
                }
                if (!facts.empty())
                {
                    std::string seg = header;
                    for (auto itr = facts.rbegin(); itr != facts.rend(); ++itr)
                    {
                        if (itr != facts.rbegin())
                            seg += "; ";
                        seg += *itr;
                    }
                    seg += "\n";
                    if (fits(seg.size()))
                    {
                        out << seg;
                        used += seg.size();
                    }
                }
            }
        }
    }

    // segment 4: a small, fresh slice of world gossip anyone may reference
    std::string gossip = GetGossipSlice(bot->GetGUIDLow());
    if (!gossip.empty())
    {
        std::string const seg = "Recent talk in the world: " + gossip + "\n";
        if (fits(seg.size()))
        {
            out << seg;
            used += seg.size();
        }
    }

    // segment 5: rolling short-term turns (the only trimmed part). Whisper
    // history is per (bot, player); party/raid history is shared per
    // (bot, channel) including the bot's own lines. The tail is rendered
    // newest-first within the character budget left by the segments above
    // and only the OLDEST turns drop, so the upstream LimitContext (which
    // cuts from the front) can never bite into the stable segments above.
    // channel keys carry the high bit so they can never collide with a
    // player GUID low in the (bot, playerOrChannel) key space
    size_t const rollingCap = window
        ? std::min<size_t>(3000, used + reserve < window ? window - used - reserve : 0)
        : 3000;
    uint32 playerOrChannel = (chatChannelSource == (int)ChatChannelSource::SRC_WHISPER && player)
        ? player->GetGUIDLow()
        : (0x80000000u | static_cast<uint32>(chatChannelSource));
    RollingHistory& history = History();
    std::lock_guard<std::mutex> lock(history.mutex);
    auto itr = history.turns.find(HistoryKey(bot->GetGUIDLow(), playerOrChannel));
    if (itr != history.turns.end() && !itr->second.empty() && rollingCap > 0)
    {
        std::deque<HistoryLine> const& turns = itr->second;
        size_t usedRolling = 0, count = 0;
        for (auto rev = turns.rbegin(); rev != turns.rend(); ++rev)
        {
            size_t const lineLen = rev->speaker.size() + rev->line.size() + 2;
            if (count > 0 && usedRolling + lineLen + 1 > rollingCap)
                break;
            usedRolling += lineLen + 1;
            ++count;
        }
        for (size_t i = turns.size() - count; i < turns.size(); ++i)
            out << turns[i].speaker << ": " << turns[i].line << "\n";
    }

    return out.str();
}

std::string PlayerbotLlmMemory::BuildTrainedChatRequest(Player* bot, Player* player,
    std::string const& playerLine, uint32 playerOrChannel,
    std::string const& preStompAbsence, int preStompTier,
    bool isEventTurn, uint32 eventKind, uint64_t* licenseStamp)
{
    uint32 const botGuid = bot->GetGUIDLow();

    // ---- persona: live bot data + a GUID-stable trained bible/traits
    pocketllm::PromptPersona persona;
    persona.id = std::to_string(botGuid);
    persona.name = bot->GetName();
    persona.race = RaceName(RaceId(bot));
    persona.cls = ClassName(ClassId(bot));
    persona.role = "adventurer";
    persona.zone = ZoneNameOf(bot);
    persona.companion = player && player->GetGroup() && bot->GetGroup() &&
        bot->GetGroup()->IsMember(player->GetObjectGuid());
    persona.bible = pocketllm::BibleForCardId(persona.id);
    persona.quirks = QuirkPhrase(botGuid);

    pocketllm::PromptPlayer promptPlayer;
    promptPlayer.name = player->GetName();
    promptPlayer.sex = player->getGender() == GENDER_FEMALE ? "female" : "male";
    promptPlayer.race = RaceName(player->getRace());
    promptPlayer.cls = ClassName(player->getClass());
    promptPlayer.level = player->GetLevel();

    // the PRE-STOMP tier threaded from ChatReplyDo (a fresh
    // read races the async relationship write; the caller captured this
    // before the stomp queued). The Bonded address shift rides the
    // tierNote leg (the banklib sysm field exists for exactly this):
    // warmth changes what the bot DOES - a Bonded bot calls the player
    // by its private name.
    int const tier = preStompTier;
    if (tier >= 5 && player)
        persona.tierNote = pocketllm::NicknameTierNote(player->GetName(), botGuid);

    // ---- facts: newest-first fetch, oldest-first stable render, tier-capped
    // (trained memory depth); the [Memories] tail re-surfaces the newest rows -
    // the training corpus duplicates the recalled fact between the system
    // facts and the tail by design (recall cue, not redundancy)
    std::vector<std::string> facts;
    {
        uint32 const factsCap = std::max<uint32>(1, sPlayerbotAIConfig.llmFactsCap);
        auto result = CharacterDatabase.PQuery(
            "SELECT `fact_text` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
            "ORDER BY `id` DESC LIMIT %u",
            botGuid, player->GetGUIDLow(), factsCap);
        if (result)
        {
            uint32 rewordSeed = 0;
            bool const pastFirstMeeting = tier >= 2; // acquaintance+
            do
            {
                std::string text = result->Fetch()[0].GetString();
                // tone rows render WITHOUT the machine prefix (a
                // "(tone -)" prefix in the system facts segment is an
                // echoable non-word); the journal strip, generalized
                size_t const toneEnd = text.find(") ");
                if (text.rfind("(tone", 0) == 0 && toneEnd != std::string::npos)
                    text = text.substr(toneEnd + 2);
                // C1: render-time first-meeting rewording - a pairing
                // past acquaintance never reads "met X for the first
                // time" again (tier-gated so the ledger never
                // contradicts Standing:; the write stays untouched)
                if (pastFirstMeeting)
                    text = pocketllm::RewordFirstMeetingRow(text, rewordSeed++);
                facts.push_back(text);
            } while (result->NextRow());
            std::reverse(facts.begin(), facts.end());
        }
    }
    std::vector<std::string> mems;
    if (!facts.empty())
    {
        uint32 const tail = std::max<uint32>(1, sPlayerbotAIConfig.llmMemoriesTail);
        size_t const first = facts.size() > tail ? facts.size() - tail : 0;
        mems.assign(facts.begin() + first, facts.end());
    }

    // ---- history: role-separated, the just-recorded current turn excluded
    // (it IS this request's user message). Depth scales with the tier's
    // context: on-device tiers keep the trained 8-turn window (5 on the
    // ambient SAY channel — the cross-injection window: a town scene, not
    // a transcript); the external API tier (128k ctx) carries 32 turns
    // (16 on SAY) so 1M-ctx models hold the whole scene. The per-key
    // rotation counter (state flavors) advances once per REQUEST so it
    // alternates regardless of window saturation or turn parity.
    std::vector<pocketllm::HistoryTurn> history;
    uint32 stateRotation = 0;
    {
        RollingHistory& h = History();
        std::lock_guard<std::mutex> lock(h.mutex);
        auto itr = h.turns.find(HistoryKey(botGuid, playerOrChannel));
        if (itr != h.turns.end())
        {
            std::deque<HistoryLine> const& turns = itr->second;
            size_t n = turns.size();
            // the memory block records the CURRENT turn before this call:
            // drop exactly that newest entry - the player's line, or the
            // "(event)" pseudo-speaker on event-reaction turns (it renders
            // as THIS request's [EVENT] head, not as a prior turn)
            if (n && (turns[n - 1].speaker == player->GetName() ||
                      turns[n - 1].speaker == "(event)"))
                --n;
            bool const apiTier = ExternalApiTierActive();
            size_t const cap =
                (playerOrChannel == (0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY)))
                    ? (apiTier ? 16 : 5) : (apiTier ? 32 : 8);
            size_t const first = n > cap ? n - cap : 0;
            for (size_t i = first; i < n; ++i)
                history.push_back(pocketllm::HistoryTurn(
                    turns[i].speaker == bot->GetName(), turns[i].line));
        }
        stateRotation = ++h.stateRotation[HistoryKey(botGuid, playerOrChannel)];
    }

    // ---- [State]: card_state twin - companion road/camp flavors rotate by
    // turn index; combat overrides; an ungrouped bot sits at its spot
    std::string state;
    if (bot->IsInCombat())
    {
        state = persona.companion
            ? pocketllm::CompanionState(std::string("in a hard fight beside ") + player->GetName())
            : pocketllm::NpcSpotState(persona.zone);
    }
    else if (persona.companion)
    {
        // ONLY the trained state flavors: the corpus's companion cards
        // rotate exactly ["on the road with {player}", "at camp with
        // {player}"] in peaceful turns, with "in a hard fight beside
        // {player}" as the combat override (banklib card_state) - no
        // invented flavors. Rotation comes from the
        // per-key request counter read under the history lock above.
        static char const* const flavors[2] = {
            "on the road with {p}", "at camp with {p}",
        };
        std::string flavor = flavors[stateRotation % 2];
        flavor.replace(flavor.find("{p}"), 3, player->GetName());
        state = pocketllm::CompanionState(flavor);
    }
    else
        state = pocketllm::NpcSpotState(persona.zone);

    // The caller captured the bucket BEFORE the relationship
    // stomp (the async write would race a fresh read) - one read serves
    // both the absence line and the bridge's first-meeting beat. The
    // contract is explicit: an empty
    // bucket is a caller bug and degrades to the first-meeting reading,
    // never to a racing read.
    std::string const absenceBucket = preStompAbsence.empty()
        ? std::string("a first meeting") : preStompAbsence;
    // Phase-3: the bot's current weather rides the system prompt alongside
    // the pack seasoning (same instruction span, never a new segment).
    std::string const sysm = pocketllm::SysmForCard(persona, promptPlayer, tier,
        AbsenceLineFor(player->GetName(), absenceBucket), facts,
        LoadPackSeasoning(), MoodLineFor(botGuid));

    // The bridge owns the turn. Event reactions render through the
    // trained [EVENT] head + speak-first directive (no player words this
    // turn); conversational turns carry at most ONE note (ONE-NOTE law).
    // The history already holds the RAW event text (AppendTurn recorded it
    // before this call) - it stays there as a prior turn, while THIS
    // request renders it as the [EVENT] head of the current turn only.
    // The event flag is the drain flag threaded from the event site -
    // never derived from the text (the "(event) " prefix was player-
    // forgeable and is retired as a signal).
    // the nudge strip is event-only: a player whisper that
    // happens to end in the nudge-shaped suffix keeps its actual words.
    // The CURRENT turn is scrubbed of prompt furniture before compose:
    // a player line carrying "[RESULT] ..." or
    // "[BRIDGE AI] ..." would otherwise render as bridge-authored truth
    // in the very turn that teaches the model to trust those tokens;
    // markers were already neuted upstream (SayAction's fill site)
    std::string const turnText = isEventTurn
        ? ScrubControlTokens(PlayerbotLlmBridge::NormalizeTurn(
              bot->GetName(), playerLine))
        : ScrubControlTokens(playerLine);
    PlayerbotLlmBridge::TurnState turnState;
    turnState.absence = absenceBucket;
    turnState.tier = tier;
    turnState.eventTurn = isEventTurn;
    turnState.eventKind = eventKind;
    PlayerbotLlmBridge::Note const note =
        PlayerbotLlmBridge::BuildNote(bot, player, turnText, turnState);
    // the note's license stamp rides out to the caller, which threads it
    // into the generation (stamp threading)
    if (licenseStamp)
        *licenseStamp = note.stamp;
    std::string noteExtra = note.extra;
    if (turnState.eventTurn && noteExtra.empty())
        noteExtra = PlayerbotLlmBridge::SpeakFirst();
    std::vector<std::string> events;
    if (turnState.eventTurn)
        events.push_back(turnText);
    // The lore loop's card rides the turn head as [RESULT] (the
    // trained compose furniture renders it; full card density, the
    // denial-card shortcut is banned by design)
    std::vector<std::string> results;
    if (!note.result.empty())
        results.push_back(note.result);
    std::string const user = pocketllm::ComposeUserTurn(
        turnState.eventTurn ? std::string() : turnText, {}, events, results,
        state, mems, note.lines, note.fills, noteExtra);

    pocketllm::RequestSampling sampling;
    sampling.temperature = sPlayerbotAIConfig.llmTemp;
    sampling.topP = sPlayerbotAIConfig.llmTopP;
    sampling.topK = sPlayerbotAIConfig.llmTopK;
    sampling.repeatPenalty = sPlayerbotAIConfig.llmRepeatPenalty;
    sampling.minP = sPlayerbotAIConfig.llmMinP;
    sampling.presencePenalty = sPlayerbotAIConfig.llmPresencePenalty;
    sampling.maxTokens = sPlayerbotAIConfig.llmMaxNewTokens;
    sampling.thinkingKwargs = sPlayerbotAIConfig.llmThinkingKwargs != 0;
    sampling.providerSafe = sPlayerbotAIConfig.llmApiProviderSafe != 0;

    // prompt-dump hook: one JSON line per trained-format generation so
    // the byte-diff contract can be verified on-device against the same
    // vectors the host test suite uses. The line is assembled whole and handed
    // to the stream in one write() call, which keeps the common case (a
    // sub-buffer line) atomic under O_APPEND; very large lines can still
    // interleave across stream-buffer flushes - accepted for a dev-only
    // diagnostics file (corruption is JSON-detectable).
    if (!sPlayerbotAIConfig.llmPromptDumpFile.empty())
    {
        try
        {
            std::string line;
            line.reserve(512);
            line += "{\"bot\":";
            line += std::to_string(botGuid);
            line += ",\"sysm\":\"" + pocketllm::EscapeJsonString(sysm) + "\"";
            line += ",\"user\":\"" + pocketllm::EscapeJsonString(user) + "\"";
            line += ",\"history\":[";
            for (size_t i = 0; i < history.size(); ++i)
            {
                if (i) line += ",";
                line += "{\"assistant\":";
                line += history[i].assistant ? "true" : "false";
                line += ",\"content\":\"" + pocketllm::EscapeJsonString(history[i].content) + "\"}";
            }
            line += "]}\n";
            std::ofstream dump(sPlayerbotAIConfig.llmPromptDumpFile.c_str(),
                std::ios::app);
            if (dump.is_open())
                dump.write(line.data(), line.size());
        }
        catch (...)
        {
            // diagnostics-only path: never let a dump failure break a reply
        }
    }

    // providerSafe (the external API tier) also suppresses reasoning
    // models' chain-of-thought at the source; the response-side
    // StripThinking in HygienePass stays as the backstop
    return pocketllm::BuildChatRequestBody(sPlayerbotAIConfig.llmApiModel, sysm,
        history, user, sampling, sampling.providerSafe);
}

std::string PlayerbotLlmMemory::GetRelationshipTier(Player* bot, Player* player)
{
    auto result = CharacterDatabase.PQuery(
        "SELECT `tier` FROM `bot_player_relationship` WHERE `bot` = '%u' AND `player` = '%u'",
        bot->GetGUIDLow(), player->GetGUIDLow());
    if (!result)
        return "stranger";
    std::string tier = result->Fetch()[0].GetString();
    return tier;
}

int PlayerbotLlmMemory::GetTrainedTier(Player* bot, Player* player)
{
    // the schema's enum/CHECK pins the four STORED tier values; the trained
    // scale's fifth step (Bonded) derives from the point total on read -
    // no migration, and a write can never fail a constraint over it
    auto result = CharacterDatabase.PQuery(
        "SELECT `tier`, `points` FROM `bot_player_relationship` WHERE `bot` = '%u' AND `player` = '%u'",
        bot->GetGUIDLow(), player->GetGUIDLow());
    if (!result)
        return 1;
    Field* fields = result->Fetch();
    std::string const tier = fields[0].GetString();
    int32 const points = fields[1].GetInt32();
    if (points >= 120)
        return 5;
    return pocketllm::TierFromStorage(tier);
}

void PlayerbotLlmMemory::AddRelationshipPoints(Player* bot, Player* player, int32 points)
{
    CharacterDatabase.PExecute(
#ifdef DO_SQLITE
        // SQLite reads PRE-update column values in DO UPDATE (MySQL's ODKU
        // assigns left-to-right), so the increment is folded into every
        // threshold test: the tier must reflect the POST-increment points
        // or the 10/30/60 boundaries lag one interaction behind.
        // The stored enum stays the schema's four values - the trained
        // scale's fifth step (Bonded) derives from the point total on READ
        // (GetTrainedTier), so no migration is needed and the CHECK/enum
        // constraints can never reject a write.
        // C5: tier_since stamps ONLY on a crossing - the CASE compares the
        // POST-increment tier against the row's PRE-update tier (the seed
        // column tier_since already exists; NULL = never crossed).
        "INSERT INTO `bot_player_relationship` (`bot`, `player`, `tier`, `points`, `last_interaction_at`, `tier_since`) VALUES ('%u', '%u', 'stranger', %d, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
        "ON CONFLICT(`bot`, `player`) DO UPDATE SET `points` = `points` + excluded.`points`, "
        "`tier` = CASE WHEN `points` + excluded.`points` >= 60 THEN 'trusted' "
        "WHEN `points` + excluded.`points` >= 30 THEN 'ally' "
        "WHEN `points` + excluded.`points` >= 10 THEN 'acquaintance' ELSE 'stranger' END, "
        "`tier_since` = CASE WHEN `tier` <> (CASE WHEN `points` + excluded.`points` >= 60 THEN 'trusted' "
        "WHEN `points` + excluded.`points` >= 30 THEN 'ally' "
        "WHEN `points` + excluded.`points` >= 10 THEN 'acquaintance' ELSE 'stranger' END) "
        "THEN CURRENT_TIMESTAMP ELSE `tier_since` END, "
        "`last_interaction_at` = CURRENT_TIMESTAMP",
#else
        // C5: the tier_since assignment PRECEDES the tier assignment -
        // MySQL assigns left-to-right, so the IF compares the NEW tier
        // expression against the row's still-OLD tier column and stamps
        // only on a real crossing (pinned by the sqlite ODKU fixture).
        "INSERT INTO `bot_player_relationship` (`bot`, `player`, `tier`, `points`, `last_interaction_at`, `tier_since`) VALUES ('%u', '%u', 'stranger', '%d', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) "
        "ON DUPLICATE KEY UPDATE `points` = `points` + '%d', "
        "`tier_since` = IF(IF(`points` >= 60, 'trusted', IF(`points` >= 30, 'ally', IF(`points` >= 10, 'acquaintance', 'stranger'))) <> `tier`, CURRENT_TIMESTAMP, `tier_since`), "
        "`tier` = IF(`points` >= 60, 'trusted', "
        "IF(`points` >= 30, 'ally', IF(`points` >= 10, 'acquaintance', 'stranger'))), "
        "`last_interaction_at` = CURRENT_TIMESTAMP",
#endif
        // Both branches share this 4-arg call site; the DO_SQLITE template
        // has one conversion fewer (3 vs 4 - the MySQL ODKU's second %d).
        // Extra vsnprintf args are ignored by definition; do NOT "fix" by
        // branching the call site.
        bot->GetGUIDLow(), player->GetGUIDLow(), points, points);
}

bool PlayerbotLlmMemory::AddBoundedSentimentInput(uint32 bot, uint32 player, int32 clampedDelta,
    std::string const& reason)
{
    if (clampedDelta > 2)
        clampedDelta = 2;
    if (clampedDelta < -2)
        clampedDelta = -2;

    uint64 const key = (static_cast<uint64>(bot) << 32) | player;
    time_t const now = time(nullptr);
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        std::map<uint64, time_t>& rate = SentimentRate();
        if (rate.find(key) != rate.end() && now - rate[key] < 60)
            return false; // rate-limited: one bounded input per (bot, player) per minute
        rate[key] = now;
    }

    if (Player* botPlayer = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, bot)))
        if (Player* target = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, player)))
        {
            AddRelationshipPoints(botPlayer, target, clampedDelta);
            // a wronging folds into the mood weather immediately (the
            // hourly bucket alone drifts too slowly for a fresh grudge)
            if (clampedDelta < 0)
                NudgeMood(bot, pocketllm::MOOD_GRUDGE);
            if (!reason.empty())
            {
                // The tone prefix carries the bridge-decided SIGN
                // - the grudge surface reads it ("any unsettled grudge":
                // the newest opinion row negative). GetJournal strips
                // every "(tone ...)" shape.
                char const* tone = clampedDelta < 0 ? "(tone -) " : "(tone +) ";
                CharacterDatabase.PExecute(
                    "INSERT INTO `bot_player_facts` (`bot`, `player`, `fact_text`, `category`) VALUES ('%u', '%u', '%s%s', 'opinion')",
                    bot, player, tone, EscapeSql(TruncUtf8(
                        // injection hygiene: a tool value can carry a live
                        // marker (the model echoed protocol text verbatim);
                        // a persisted marker would replay into every later
                        // prompt as a forged-call exemplar. Neuter AFTER the
                        // astral strip: StripAstral deletes 4-byte sequences
                        // and would fuse a padded `<X<` back into a live `<<`.
                        pocketllm::NeuterMarkersCopy(ScrubControlTokens(
                            StripAstral(reason)).c_str()), 200)).c_str());
            }
        }
    return true;
}

std::string PlayerbotLlmMemory::GetAbsenceBucket(Player* bot, Player* player)
{
    auto result = CharacterDatabase.PQuery(
#ifdef DO_SQLITE
        // %%s: PExecute printf-formats the template; the SQL the engine
        // sees is strftime('%s', ...). Epoch is UTC on SQLite vs MySQL's
        // session tz - values are engine-internal and
        // the export bridge normalizes to UTC.
        "SELECT strftime('%%s', `last_interaction_at`) FROM `bot_player_relationship` "
        "WHERE `bot` = '%u' AND `player` = '%u'",
#else
        "SELECT UNIX_TIMESTAMP(`last_interaction_at`) FROM `bot_player_relationship` "
        "WHERE `bot` = '%u' AND `player` = '%u'",
#endif
        bot->GetGUIDLow(), player->GetGUIDLow());
    if (!result)
        return "a first meeting";
    Field* fields = result->Fetch();
    bool isNull = fields[0].IsNULL();
    time_t last = isNull ? 0 : static_cast<time_t>(fields[0].GetUInt64());
    if (isNull || last == 0)
        return "a first meeting";

    time_t const elapsed = time(nullptr) - last;
    if (elapsed < 300)
        return "a few moments";
    if (elapsed < 3600)
        return "a short while";
    if (elapsed < 21600)
        return "a few hours";
    if (elapsed < 86400)
        return "most of a day";
    return "many days";
}

PlayerbotLlmMemory::PreStompState PlayerbotLlmMemory::GetPreStompState(Player* bot, Player* player)
{
    PreStompState out;
    // ONE query serves both reads (absence bucket + the tier the
    // ceremony observes), taken before the relationship stomp queues -
    // a fresh read after the push races the async write.
    auto result = CharacterDatabase.PQuery(
#ifdef DO_SQLITE
        "SELECT strftime('%%s', `last_interaction_at`), `tier`, `points` FROM `bot_player_relationship` "
        "WHERE `bot` = '%u' AND `player` = '%u'",
#else
        "SELECT UNIX_TIMESTAMP(`last_interaction_at`), `tier`, `points` FROM `bot_player_relationship` "
        "WHERE `bot` = '%u' AND `player` = '%u'",
#endif
        bot->GetGUIDLow(), player->GetGUIDLow());
    if (!result)
    {
        out.absence = "a first meeting";
        return out;
    }
    Field* fields = result->Fetch();
    bool isNull = fields[0].IsNULL();
    time_t last = isNull ? 0 : static_cast<time_t>(fields[0].GetUInt64());
    if (isNull || last == 0)
        out.absence = "a first meeting";
    else
    {
        time_t const elapsed = time(nullptr) - last;
        if (elapsed < 300)
            out.absence = "a few moments";
        else if (elapsed < 3600)
            out.absence = "a short while";
        else if (elapsed < 21600)
            out.absence = "a few hours";
        else if (elapsed < 86400)
            out.absence = "most of a day";
        else
            out.absence = "many days";
    }
    std::string const tier = fields[1].GetString();
    int32 const points = fields[2].GetInt32();
    out.tier = points >= 120 ? 5 : pocketllm::TierFromStorage(tier);
    return out;
}

void PlayerbotLlmMemory::LogFact(uint32 bot, uint32 player, std::string const& text, std::string const& category)
{
    std::string const safeCategory =
        (category == "preference" || category == "shared-event" || category == "opinion" ||
            category == "player-identity") ? category : "shared-event";
    // astral-plane characters are stripped before measuring: the utf8mb3
    // columns would reject them in strict mode and silently drop the whole
    // fact. 256 surviving bytes escape to at most 512 chars - exactly the
    // varchar(512) column width. Markers are neutered AFTER the astral
    // strip: StripAstral deletes 4-byte sequences and would fuse a padded
    // `<X<` back into a live `<<`, and a persisted marker would render into
    // every later prompt as a forged-call exemplar (the same law AppendTurn
    // and the <initial message> placeholder enforce for turn text).
    CharacterDatabase.PExecute(
        "INSERT INTO `bot_player_facts` (`bot`, `player`, `fact_text`, `category`) VALUES ('%u', '%u', '%s', '%s')",
        bot, player, EscapeSql(TruncUtf8(
            pocketllm::NeuterMarkersCopy(ScrubControlTokens(
                StripAstral(text)).c_str()), 256)).c_str(), safeCategory.c_str());
}

std::string PlayerbotLlmMemory::GetNewestRecallFact(uint32 bot, uint32 player, int classMask)
{
    // newest-20 window, classified with the pure core: recall surfaces
    // the recent past, not the whole ledger; tone rows (opinions) are
    // never weave cargo - grudges have their own surface below.
    auto result = CharacterDatabase.PQuery(
        "SELECT `fact_text`, `category` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
        "ORDER BY `id` DESC LIMIT 20",
        bot, player);
    if (!result)
        return "";
    do
    {
        Field* fields = result->Fetch();
        std::string const text = fields[0].GetString();
        if (text.rfind("(tone", 0) == 0)
            continue;
        int const cls = pocketllm::FactClassOf(text, fields[1].GetString());
        if (cls != pocketllm::FACT_PLAIN && (classMask & (1 << cls)))
            return text;
        if (cls == pocketllm::FACT_PLAIN && (classMask & 1))
            return text;
    } while (result->NextRow());
    return "";
}

std::string PlayerbotLlmMemory::GetUnresolvedGrudge(uint32 bot, uint32 player)
{
    // the newest negative tone row with no newer positive one - the
    // greeting beat's "any unsettled grudge" leg: the NEWEST opinion row
    // is negative exactly when the grudge stands (a later kindness row
    // resolves it). The (tone -)/(tone +) prefixes are written by
    // AddBoundedSentimentInput with the bridge-decided direction.
    auto result = CharacterDatabase.PQuery(
        "SELECT `fact_text` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
        "AND `category` = 'opinion' ORDER BY `id` DESC LIMIT 1",
        bot, player);
    if (!result)
        return "";
    std::string const raw = result->Fetch()[0].GetString();
    if (raw.rfind("(tone -) ", 0) == 0)
        return raw.substr(9);
    return "";
}

bool PlayerbotLlmMemory::HasFactPrefix(uint32 bot, uint32 player, std::string const& prefix)
{
    // category-constrained: the ONLY writer of a reserved prefix in
    // player-identity is the tier-4 ceremony's licensed log_fact line
    // (the bridge decides the category). A model-filled fact copying a
    // player-whispered "secret told: ..." lands in shared-event and must
    // not lock the real secret out (the forgery vector).
    std::string const safe = EscapeSql(prefix);
    auto result = CharacterDatabase.PQuery(
        "SELECT 1 FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
        "AND `category` = 'player-identity' AND `fact_text` LIKE '%s%%' LIMIT 1",
        bot, player, safe.c_str());
    return !!result;
}

std::string PlayerbotLlmMemory::GossipAbout(std::string const& playerName)
{
    // Delivery priority: the newest rows matched in code (no LIKE -
    // player names are arbitrary strings and %/_ would be wildcards),
    // CASE-SENSITIVELY on word boundaries: the capitalization is what
    // makes a token a name ("Ash" is not "the ash of the fire" and not
    // "Ashmar" - either match would misattribute the line). The world pool is
    // small, pruned, and this runs once per greeting gap - the newest-8
    // fetch bounds the scan.
    if (playerName.empty())
        return "";
    auto result = WorldDatabase.PQuery(
        "SELECT `text` FROM `world_gossip` WHERE (`expires_at` IS NULL OR `expires_at` > CURRENT_TIMESTAMP) "
        "ORDER BY `id` DESC LIMIT 8");
    if (!result)
        return "";
    do
    {
        std::string const text = result->Fetch()[0].GetString();
        if (pocketllm::ContainsWordExact(text, playerName))
            return text;
    } while (result->NextRow());
    return "";
}

std::vector<std::string> PlayerbotLlmMemory::GetJournal(Player* bot, Player* player)
{
    std::vector<std::string> lines;
    // newest-40 window on an append-only table: an ASC scan would freeze the
    // journal at the first 40 facts forever while chat keeps recalling
    // newer ones
    auto result = CharacterDatabase.PQuery(
        "SELECT `fact_text`, `category` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' ORDER BY `id` DESC LIMIT 40",
        bot->GetGUIDLow(), player->GetGUIDLow());
    if (!result)
        return lines;
    do
    {
        Field* fields = result->Fetch();
        // prose labels, never the storage enum: the journal is a player-read
        // surface ("Bygdok's Journal"), not a database dump
        std::string cat = fields[1].GetString();
        std::string label = "Remembered";
        if (cat == "preference") label = "Likes";
        else if (cat == "opinion") label = "Feels";
        else if (cat == "player-identity") label = "Knows";
        std::string text = fields[0].GetString();
        // every tone shape strips ("(tone) " legacy rows, the signed
        // "(tone -)/(tone +)" rows): the journal is a player-read surface
        size_t const toneEnd = text.find(") ");
        if (text.rfind("(tone", 0) == 0 && toneEnd != std::string::npos)
            text = text.substr(toneEnd + 2);
        // C1: the journal renders the same reworded shape the prompt
        // sees (tier-gated: a stranger's journal still reads the first
        // meeting for what it was)
        if (GetTrainedTier(bot, player) >= 2)
            text = pocketllm::RewordFirstMeetingRow(text, (uint32)lines.size());
        std::ostringstream line;
        line << label << ": " << text;
        lines.push_back(line.str());
    } while (result->NextRow());
    // fetched newest-first, rendered oldest-first: a journal reads forward
    std::reverse(lines.begin(), lines.end());
    return lines;
}

std::vector<std::string> PlayerbotLlmMemory::GetJournalLines(Player* bot, Player* player)
{
    // one entry per whisper line: the delivery path paces these out like a
    // diary being read (LinesToPackets), which a single multi-KB SMSG is not
    std::vector<std::string> lines;
    std::string const backstory = GetOrCreateBackstory(bot);
    if (!backstory.empty())
        lines.push_back(backstory);
    lines.push_back(std::string("Standing: ")
        + TierProse(pocketllm::TierStorageName(GetTrainedTier(bot, player))) + ".");
    for (std::string const& line : GetJournal(bot, player))
        lines.push_back(line);
    // Phase-4: anniversaries + tier beats are journal-visible. The
    // anniversary derives from the OLDEST fact id distance (each pairing's
    // first fact is its first meeting — "met <name> for the first time");
    // without rows there is no tenure to celebrate. Tier beats ride the
    // current tier: ally+ earns the vouch line, tier 5 the bickering line.
    if (bot && player)
    {
        int const days = PairingAgeDays(bot->GetGUIDLow(), player->GetGUIDLow());
        char const* ann = pocketllm::AnniversaryLine(
            pocketllm::AnniversaryBucket(days));
        if (ann && *ann)
            lines.push_back(std::string("Milestone: ") + ann);
        int const tier = GetTrainedTier(bot, player);
        // journal prose, not cargo: the player reads these lines, so the
        // beat observes the relationship third-person (the second-person
        // frames are bot instructions and stay in the prompt path only)
        if (tier >= 5)
            lines.push_back(std::string("Bond: ") +
                pocketllm::TierBeatJournalLine(2));
        else if (tier >= 3)
            lines.push_back(std::string("Bond: ") +
                pocketllm::TierBeatJournalLine(0));
    }
    return lines;
}

void PlayerbotLlmMemory::ShareGossip(uint32 bot, std::string const& text, std::string const& category)
{
    WorldDatabase.PExecute(
#ifdef DO_SQLITE
        // datetime('now', ...) is UTC; MySQL's NOW() reads the session tz
        // - each engine is internally consistent
        // between this write and the CURRENT_TIMESTAMP reads below.
        "INSERT INTO `world_gossip` (`text`, `category`, `source_bot`, `expires_at`) "
        "VALUES ('%s', '%s', '%u', datetime('now', '+7 day'))",
#else
        "INSERT INTO `world_gossip` (`text`, `category`, `source_bot`, `expires_at`) "
        "VALUES ('%s', '%s', '%u', DATE_ADD(NOW(), INTERVAL 7 DAY))",
#endif
        // injection hygiene, same law as LogFact (strip -> scrub ->
        // neuter: deletions can FUSE markers, so the neuter runs LAST):
        // gossip renders into OTHER bots' prompts for its 7-day life
        EscapeSql(TruncUtf8(
            pocketllm::NeuterMarkersCopy(ScrubControlTokens(
                StripAstral(text)).c_str()), 250)).c_str(), EscapeSql(TruncUtf8(StripAstral(category), 32)).c_str(), bot);
    // the read filter never deletes, so expired rows are pruned here
    // opportunistically - otherwise the table grows without bound on-device
    WorldDatabase.PExecute(
        "DELETE FROM `world_gossip` WHERE `expires_at` IS NOT NULL AND `expires_at` < CURRENT_TIMESTAMP");
}

std::string PlayerbotLlmMemory::GetGossipSlice(uint32 bot)
{
    {
        // pinned per bot for 60s: the segment stays byte-stable between
        // turns so the warm-slot prefix is not truncated here
        std::lock_guard<std::mutex> lock(StateMutex());
        auto cached = GossipCache().find(bot);
        if (cached != GossipCache().end() && time(nullptr) - cached->second.first < 60)
            return cached->second.second;
    }
    auto result = WorldDatabase.PQuery(
        "SELECT `text` FROM `world_gossip` WHERE (`expires_at` IS NULL OR `expires_at` > CURRENT_TIMESTAMP) "
        "AND `source_bot` <> '%u' ORDER BY `id` DESC LIMIT 3", bot);
    std::string slice;
    if (result)
    {
        std::ostringstream out;
        bool first = true;
        do
        {
            if (!first)
                out << " ";
            out << result->Fetch()[0].GetString();
            first = false;
        } while (result->NextRow());
        slice = out.str();
    }
    // the empty pool is cached too: otherwise every turn and every prewarm
    // pass queries WorldDatabase while the pool is empty (the common state)
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        GossipCache()[bot] = {time(nullptr), slice};
    }
    return slice;
}

void PlayerbotLlmMemory::NoteVerifiedEvent(uint32 bot, uint32 eventId)
{
    (void)eventId;
    std::lock_guard<std::mutex> lock(StateMutex());
    VerifiedEvents()[bot] = time(nullptr);
}

bool PlayerbotLlmMemory::HasRecentVerifiedEvent(uint32 bot)
{
    std::lock_guard<std::mutex> lock(StateMutex());
    std::map<uint32, time_t> const& events = VerifiedEvents();
    auto itr = events.find(bot);
    return itr != events.end() && time(nullptr) - itr->second < 120;
}

namespace {

std::map<uint32, std::deque<PlayerbotLlmMemory::EventReaction>>& EventReactions()
{
    static std::map<uint32, std::deque<PlayerbotLlmMemory::EventReaction>> instance;
    return instance;
}

void QueueForPartyBots(Player* player, std::string const& text, int32 points,
    uint32 eventKind)
{
    // real players only: bot level-ups and bot loot must not grow bot-to-bot
    // relationship rows, open the share_gossip verified-event window, or evict
    // pending real-player reactions from the two-slot queue
    if (!player || !player->isRealPlayer())
        return;

    Group* group = player->GetGroup();
    if (!group)
        return;

    for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
    {
        Player* member = itr->getSource();
        if (!member || member == player || !member->GetPlayerbotAI())
            continue;

        // milestones grow the deterministic relationship accumulator
        PlayerbotLlmMemory::AddRelationshipPoints(member, player, points);
        // and open the verified-event window that gates share_gossip
        PlayerbotLlmMemory::NoteVerifiedEvent(member->GetGUIDLow(), 0);

        PlayerbotLlmMemory::EventReaction reaction;
        reaction.playerGuid = player->GetGUIDLow();
        reaction.text = text;
        reaction.eventKind = eventKind;
        std::lock_guard<std::mutex> lock(StateMutex());
        EventReactions()[member->GetGUIDLow()].push_back(reaction);
        while (EventReactions()[member->GetGUIDLow()].size() > 2)
            EventReactions()[member->GetGUIDLow()].pop_front();
    }
}

} // namespace

void PlayerbotLlmMemory::OnPlayerLevelUp(Player* player, uint32 newLevel)
{
    if (!player || !sPlayerbotAIConfig.llmEnabled)
        return;
    // Phase-3 reactivity: dial 0 skips every other level-up note (quiet
    // bots celebrate less), 100 always notes. Default 50 = base behavior.
    if (sPlayerbotAIConfig.llmRpReactivity <= 25 && (newLevel & 1))
        return;

    std::ostringstream out;
    // the event reaction's nature rides the drain's isEventTurn flag +
    // event kind; the text itself is plain narration (the former
    // "(event) " prefix was only ever a string signal for what the flag
    // now carries)
    out << player->GetName() << " just reached level " << newLevel
        << " while fighting at our side.";
    QueueForPartyBots(player, out.str(), 2, PlayerbotLlmBridge::EVENT_LEVEL_UP);

    // plan RP E1: the authored cheer leg - the small delivery decision at
    // the event drain. ONE grouped bot (the condolence pattern's bounded
    // speaker pick) also voices a pool cheer 2-5 s after the event via the
    // authored reaction queue; the generated note above keeps its own
    // cadence, so the beat is generation + a guaranteed voiced line.
    if (!sPlayerbotAIConfig.llmBanterEnabled || !sPlayerbotAIConfig.llmEventReactionsEnabled)
        return;
    if (Group* cheerGroup = player->GetGroup())
    {
        std::vector<Player*> cheerers;
        for (GroupReference* itr = cheerGroup->GetFirstMember(); itr; itr = itr->next())
        {
            Player* member = itr->getSource();
            if (member && member != player && member->GetPlayerbotAI() && member->IsInWorld())
                cheerers.push_back(member);
        }
        if (cheerers.empty())
            return;
        Player* speaker = cheerers[urand(0, uint32(cheerers.size() - 1))];
        std::string const cheer = PlayerbotLlmPersona::CheerLine(speaker, player);
        if (cheer.empty())
            return;
        EventReaction cheerReaction;
        cheerReaction.authored = true;
        cheerReaction.playerGuid = player->GetGUIDLow();
        cheerReaction.text = cheer;
        cheerReaction.msgtype = CHAT_MSG_PARTY;
        cheerReaction.notBefore = time(nullptr) + urand(2, 5);
        QueueAuthoredReaction(speaker, cheerReaction);
    }
}

void PlayerbotLlmMemory::OnPlayerRareLoot(Player* player, uint32 itemId)
{
    if (!player || !sPlayerbotAIConfig.llmEnabled)
        return;

    ItemPrototype const* proto = sObjectMgr.GetItemPrototype(itemId);
    if (!proto || proto->Quality < ITEM_QUALITY_RARE)
        return;

    // a verified event for the looting player's bots: share_gossip may ride
    // along on the reaction generation
    std::ostringstream out;
    out << player->GetName() << " just looted " << proto->Name1
        << ", a rare find.";
    QueueForPartyBots(player, out.str(), 1, PlayerbotLlmBridge::EVENT_RARE_LOOT);
}

void PlayerbotLlmMemory::OnDuelComplete(Player* participant, Player* opponent,
    uint32 participantType)
{
    if (!participant || !opponent || !sPlayerbotAIConfig.llmEnabled)
        return;

    // DuelCompleteType: DUEL_INTERRUPTED (=0) carries no outcome - the
    // flag despawned, a participant left the world; nobody won anything
    // worth a beat
    if (participantType == 0)
        return;

    // player-vs-bot duels only: two bots never grow relationship rows or
    // verified-event windows (bot2bot scope), two real players involve
    // no bot at all
    Player* const bot = participant->GetPlayerbotAI() ? participant
        : (opponent->GetPlayerbotAI() ? opponent : nullptr);
    if (!bot)
        return;
    Player* const duelPlayer = bot == participant ? opponent : participant;
    if (!duelPlayer->isRealPlayer())
        return;

    // party-banter event note: a duel by/against a real player
    // outranks the party layer's idle timer (the event-bark cadence).
    // Player-vs-player duels involve no bot and reach no observer here.
    PlayerbotLlmChatter::OnDuelCompleted(participant, opponent);

    // outcome semantics (cmangos): the damage-win site calls DuelComplete
    // on the LOSER with DUEL_WON; the flee sites call it on the FLEEING
    // player with DUEL_FLED. The participant lost and the opponent won,
    // so: bot == opponent means the BOT won (and a type-2 participant is
    // the PLAYER fleeing); bot == participant with type 2 is the BOT
    // fleeing.
    bool const botWon = (bot == opponent);
    bool const fled = participantType == 2;

    uint32 kind = PlayerbotLlmBridge::EVENT_DUEL_LOST;
    std::ostringstream out;
    std::ostringstream fact;
    std::ostringstream town;
    if (botWon && !fled)
    {
        kind = PlayerbotLlmBridge::EVENT_DUEL_WON;
        out << "You beat " << duelPlayer->GetName()
            << " in the duel, fair and square.";
        fact << "you beat " << duelPlayer->GetName()
             << " in a duel, fair and square";
        town << duelPlayer->GetName() << " lost a duel to " << bot->GetName();
    }
    else if (botWon && fled)
    {
        kind = PlayerbotLlmBridge::EVENT_DUEL_PLAYER_FLED;
        out << duelPlayer->GetName()
            << " fled your duel before it was settled.";
        fact << duelPlayer->GetName()
             << " fled a duel against you before it was settled";
        town << duelPlayer->GetName() << " fled a duel against " << bot->GetName();
    }
    else if (!botWon && fled)
    {
        kind = PlayerbotLlmBridge::EVENT_DUEL_BOT_FLED;
        out << "You fled your duel with " << duelPlayer->GetName() << ".";
        fact << "you fled a duel against " << duelPlayer->GetName();
        town << bot->GetName() << " fled a duel against " << duelPlayer->GetName();
    }
    else
    {
        kind = PlayerbotLlmBridge::EVENT_DUEL_LOST;
        out << duelPlayer->GetName()
            << " beat you in the duel, fair and square.";
        fact << duelPlayer->GetName()
             << " beat you in a duel, fair and square";
        town << duelPlayer->GetName() << " won a duel against " << bot->GetName();
    }

    // the verified-event window opens for the dueled bot: a later news
    // beat may voice it as gossip
    NoteVerifiedEvent(bot->GetGUIDLow(), 0);
    // a loss or a cheap player-flee sours the weather (the grudge mood
    // shows at the edges of the next replies; the bucket rotation keeps
    // it from sticking). The flee class ALSO licenses a -1 sentiment
    // tool whose execution nudges grudge again - accepted: the counter
    // is a hash input, not a gauge, and both folds are real signals.
    if (kind == PlayerbotLlmBridge::EVENT_DUEL_LOST ||
        kind == PlayerbotLlmBridge::EVENT_DUEL_PLAYER_FLED)
        NudgeMood(bot->GetGUIDLow(), pocketllm::MOOD_GRUDGE);

    // The outcome becomes MEMORY - the bot's own fact (the
    // news-recall beat's cargo) and a player-subject world_gossip row
    // (the cheapest legend mechanic: "the world knows what I did").
    // Both texts are bridge-authored from server-side names, so the
    // injection chain is satisfied by construction; LogFact still runs
    // its own chain defensively.
    LogFact(bot->GetGUIDLow(), duelPlayer->GetGUIDLow(), fact.str(), "shared-event");
    ShareGossip(bot->GetGUIDLow(), town.str(), "duel");

    PlayerbotLlmMemory::EventReaction reaction;
    reaction.playerGuid = duelPlayer->GetGUIDLow();
    reaction.text = out.str();
    reaction.msgtype = CHAT_MSG_WHISPER; // a duel is a private exchange
    reaction.eventKind = kind;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        EventReactions()[bot->GetGUIDLow()].push_back(reaction);
        while (EventReactions()[bot->GetGUIDLow()].size() > 2)
            EventReactions()[bot->GetGUIDLow()].pop_front();
    }
}

namespace {

// pairing existence for the partyless death fan-out: a random town bot
// mourning a passing stranger is noise, a KNOWN bot is a moment (one
// query per candidate, bounded to four candidates)
bool HasPairingRow(uint32 bot, uint32 player)
{
    auto result = CharacterDatabase.PQuery(
        "SELECT 1 FROM `bot_player_relationship` WHERE `bot` = '%u' AND `player` = '%u' LIMIT 1",
        bot, player);
    return bool(result);
}

// plan v5 W3: DB-side once-only check for authored fact shapes - the
// in-process mint-once map dies with the process, but "for the first
// time" must never mint twice, so the prefix is checked against the
// ledger itself (shared-event only: the player-identity category is
// reserved for the tier-4 secret)
bool HasSharedFactPrefix(uint32 bot, uint32 player, std::string const& prefix)
{
    auto result = CharacterDatabase.PQuery(
        "SELECT 1 FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
        "AND `category` = 'shared-event' AND `fact_text` LIKE '%s%%' LIMIT 1",
        bot, player, EscapeSql(prefix).c_str());
    return bool(result);
}

// plan v5 F6: the generic mint-once marker - LogFact at most once per
// (bot, player, key, window). Returns true when the fact minted
bool MintOnceFact(uint32 bot, uint32 player, uint64 key, time_t windowSec,
    std::string const& text, std::string const& category)
{
    time_t const now = time(nullptr);
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        auto itr = MintOnceAt().find(key);
        if (itr != MintOnceAt().end() && now - itr->second < windowSec)
            return false;
        MintOnceAt()[key] = now;
    }
    PlayerbotLlmMemory::LogFact(bot, player, text, category);
    return true;
}

// the F7 budget math under a HELD StateMutex (the public wrappers and
// TryClaimAmbientSlot already own the lock - a recursive acquisition
// would deadlock). roomOnly peeks; otherwise the check-then-stamp pair
// lands atomically (a stamp can never land without its line)
bool AuthoredLineAdmitsLocked(uint32 category, bool exempt, bool roomOnly)
{
    if (category >= PlayerbotLlmMemory::ARB_COUNT)
        category = PlayerbotLlmMemory::ARB_AMBIENT;
    // A7.2 two-tier budgets: the lane is evaluated INSIDE the locked
    // helper (conf-static; no lane argument threads through the call
    // sites). Device branch: exactly today's caps of the authored
    // ceiling (8/hr). Cloud branch: the ambient cloud budget with
    // PROPORTIONAL category caps - without them the street quota is
    // unreachable (min(3,30) = 3/hr = 72/day < the 200/day street
    // allowance).
    bool const cloudTier = PlayerbotLlmMemory::ExternalApiTierActive();
    uint32 const globalCap = cloudTier
        ? sPlayerbotAIConfig.llmCloudLineBudgetPerHour
        : sPlayerbotAIConfig.llmAuthoredLinesPerHour;
    if (!globalCap)
        return exempt;
    uint32 const catCap = cloudTier
        ? (category == PlayerbotLlmMemory::ARB_AMBIENT
            ? std::max<uint32>(1, globalCap / 4)
            : (category == PlayerbotLlmMemory::ARB_SCENE
                ? globalCap
                : std::max<uint32>(1, globalCap / 8)))
        : (category == PlayerbotLlmMemory::ARB_AMBIENT
            ? std::min<uint32>(3, globalCap)
            : (category == PlayerbotLlmMemory::ARB_SCENE
                ? globalCap
                : std::min<uint32>(2, globalCap)));
    int64_t const now = (int64_t)time(nullptr);
    AuthoredArbiter& arb = Arbiter();
    pocketllm::ArbiterPrune(arb.global, now, 3600);
    pocketllm::ArbiterPrune(arb.cat[category], now, 3600);
    if (exempt)
        return true;
    if (arb.global.size() >= globalCap || arb.cat[category].size() >= catCap)
        return false;
    if (roomOnly)
        return true;
    arb.global.push_back(now);
    arb.cat[category].push_back(now);
    return true;
}

} // namespace

bool PlayerbotLlmMemory::AuthoredLineAdmits(uint32 category, bool exempt)
{
    std::lock_guard<std::mutex> lock(StateMutex());
    return AuthoredLineAdmitsLocked(category, exempt, /*roomOnly=*/false);
}

bool PlayerbotLlmMemory::AuthoredBudgetHasRoom(uint32 category)
{
    std::lock_guard<std::mutex> lock(StateMutex());
    return AuthoredLineAdmitsLocked(category, /*exempt=*/false, /*roomOnly=*/true);
}

void PlayerbotLlmMemory::OnPlayerDied(Player* victim)
{
    if (!victim || !sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmBanterEnabled ||
        !sPlayerbotAIConfig.llmEventReactionsEnabled)
        return;
    if (victim->GetPlayerbotAI() || !victim->GetSession() || !victim->isRealPlayer())
        return;

    // wipe classification: every other group member who is in the world is
    // down too (a lone survivor anywhere means the death is not a wipe)
    bool wipe = false;
    Group* group = victim->GetGroup();
    if (group)
    {
        wipe = true;
        for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
        {
            Player* member = itr->getSource();
            if (member && member != victim && member->IsInWorld() && member->IsAlive())
            {
                wipe = false;
                break;
            }
        }
    }

    std::string const zone = ZoneNameOf(victim);

    // reactors: grouped bots for the party player; for the partyless
    // player, KNOWN bots within say range (bounded fan-out of four)
    std::vector<Player*> condolence;   // alive bots that can speak now
    std::vector<Player*> aftermath;    // grouped bots (any state) for wipe arming
    if (group)
    {
        for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
        {
            Player* member = itr->getSource();
            if (!member || member == victim || !member->GetPlayerbotAI())
                continue;
            aftermath.push_back(member);
            if (member->IsAlive() && member->IsInWorld())
                condolence.push_back(member);
        }
    }
    else
    {
        for (auto& entry : sRandomPlayerbotMgr.GetPlayers())
        {
            Player* other = entry.second;
            if (!other || !other->GetPlayerbotAI() || !other->IsInWorld() || !other->IsAlive())
                continue;
            if (other->GetMapId() != victim->GetMapId())
                continue;
            if (sServerFacade.GetDistance2d(other, victim) > 30.0f)
                continue;
            if (!HasPairingRow(other->GetGUIDLow(), victim->GetGUIDLow()))
                continue;
            condolence.push_back(other);
            if (condolence.size() >= 4)
                break;
        }
    }

    if (wipe)
    {
        // the world retells a wipe; the bots' own words wait for their
        // revival (PendingAftermath -> TickInitiative)
        time_t const now = time(nullptr);
        for (Player* bot : aftermath)
        {
            uint32 const botGuid = bot->GetGUIDLow();
            uint64 const key = ((uint64)botGuid << 40) ^ ((uint64)victim->GetGUIDLow() << 20) ^ 0x77697065ull;
            MintOnceFact(botGuid, victim->GetGUIDLow(), key, 6 * 3600,
                "the whole party fell in " + zone, "shared-event");
            NudgeMood(botGuid, pocketllm::MOOD_GRIEF);
            std::lock_guard<std::mutex> lock(StateMutex());
            PendingAftermath()[botGuid] = std::make_pair(victim->GetGUIDLow(), now + 3600);
        }
        // plan v5 F2: shared suffering bonds - every wiped pair gains
        // affinity and carries the event as a later party topic
        for (size_t i = 0; i < aftermath.size(); ++i)
            for (size_t j = i + 1; j < aftermath.size(); ++j)
                NoteDyadEvent(aftermath[i]->GetGUIDLow(), aftermath[j]->GetGUIDLow(), 1,
                    "were wiped together in " + zone);
        if (!aftermath.empty())
            ShareGossip(aftermath[0]->GetGUIDLow(),
                std::string(victim->GetName()) + "'s party was wiped out in " + zone, "wipe");
        return;
    }

    if (condolence.empty())
        return;

    // the guaranteed beat: ONE speaker over the body, outside every pacing
    // budget (deaths are rare, and rare must land)
    Player* speaker = condolence[urand(0, uint32(condolence.size() - 1))];
    uint32 const speakerGuid = speaker->GetGUIDLow();
    std::string const line = PlayerbotLlmPersona::ReactionLine(
        speaker, PlayerbotLlmPersona::REACTION_CONDOLENCE, victim);
    if (line.empty())
        return; // the mint and the nudge wait for a confirmed voice
    NudgeMood(speakerGuid, pocketllm::MOOD_GRIEF);
    uint64 const key = ((uint64)speakerGuid << 40) ^ ((uint64)victim->GetGUIDLow() << 20) ^ 0x6465617468ull;
    MintOnceFact(speakerGuid, victim->GetGUIDLow(), key, 6 * 3600,
        std::string("stood over ") + victim->GetName() + "'s body in " + zone,
        "shared-event");
    EventReaction reaction;
    reaction.authored = true;
    reaction.playerGuid = victim->GetGUIDLow();
    reaction.text = line;
    reaction.msgtype = CHAT_MSG_PARTY;
    reaction.notBefore = time(nullptr) + urand(2, 5);
    QueueAuthoredReaction(speaker, reaction);
}

void PlayerbotLlmMemory::OnTradeCompleted(Player* accepter, Player* initiator)
{
    if (!accepter || !initiator || !sPlayerbotAIConfig.llmEnabled)
        return;
    // bot<->bot trades involve no real player; two real players involve
    // no bot (the RpgSubActions give_item flow lands here too - a bot
    // giving TO the player must not count as the player's kindness)
    Player* const bot = accepter->GetPlayerbotAI() ? accepter
        : (initiator->GetPlayerbotAI() ? initiator : nullptr);
    if (!bot)
        return;
    Player* const real = bot == accepter ? initiator : accepter;
    if (!real->isRealPlayer() || !real->GetSession())
        return;
    TradeData* const realTrade = real->GetTradeData();
    if (!realTrade)
        return;

    uint32 const money = realTrade->GetMoney();
    bool gaveItem = false;
    for (int slot = 0; slot < TRADE_SLOT_TRADED_COUNT; ++slot)
        if (realTrade->GetItem((TradeSlots)slot))
        {
            gaveItem = true;
            break;
        }
    if (!money && !gaveItem)
        return;

    // a real->bot trade is a bounded kindness: the +1 tone row also
    // resolves any standing grudge (the newest opinion row turns
    // positive) - the W4 refusal lifts with it
    bool const sentimentAdmitted = AddBoundedSentimentInput(bot->GetGUIDLow(),
        real->GetGUIDLow(), 1, std::string("traded fairly with ") + bot->GetName());
    // C6 (round-1 R7#3): the trade deed rides the SAME 60 s
    // SentimentRate admission as the tone row - N completed trades in a
    // minute award exactly ONE deed (the farm law). The deed delta
    // itself stays EXEMPT from the +-2 clamp by living here, outside
    // AddBoundedSentimentInput, and never consumes the per-pairing
    // daily cap (trades are already scarce)
    if (sentimentAdmitted)
        if (uint32 const deed = sPlayerbotAIConfig.llmDeedPointsTrade)
            AddRelationshipPoints(bot, real,
                sPlayerbotAIConfig.llmTurnAwardWeighting ? (int32)deed : 1);

    // debt settlement: money TO the bot retires the newest unresolved
    // debt row (the reminder engine reads by class, so the row must go,
    // not just age) and fires the kind-1 beat
    if (!money || !sPlayerbotAIConfig.llmEventReactionsEnabled)
        return;
    auto result = CharacterDatabase.PQuery(
        "SELECT `id`, `fact_text`, `category` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
        "ORDER BY `id` DESC LIMIT 6",
        bot->GetGUIDLow(), real->GetGUIDLow());
    if (!result)
        return;
    uint32 debtRowId = 0;
    do
    {
        Field* fields = result->Fetch();
        std::string const text = fields[1].GetString();
        if (text.rfind("(tone", 0) == 0)
            continue;
        if (pocketllm::FactClassOf(text, fields[2].GetString()) == pocketllm::FACT_DEBT)
        {
            debtRowId = fields[0].GetUInt32();
            break;
        }
    } while (result->NextRow());
    if (!debtRowId)
        return;

    CharacterDatabase.PExecute(
        "DELETE FROM `bot_player_facts` WHERE `id` = '%u'", debtRowId);
    LogFact(bot->GetGUIDLow(), real->GetGUIDLow(),
        std::string(real->GetName()) + " paid the debt square", "shared-event");

    EventReaction reaction;
    reaction.playerGuid = real->GetGUIDLow();
    reaction.text = std::string(real->GetName()) + " paid what was owed, every copper.";
    reaction.eventKind = PlayerbotLlmBridge::EVENT_DEBT_SETTLED;
    QueueAuthoredReaction(bot, reaction);
}

void PlayerbotLlmMemory::OnPlayerExploredArea(Player* player, uint32 zoneOrAreaId)
{
    if (!player || !sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmBanterEnabled ||
        !sPlayerbotAIConfig.llmEventReactionsEnabled)
        return;
    if (player->GetPlayerbotAI() || !player->GetSession() || !player->isRealPlayer())
        return;
    AreaTableEntry const* entry = GetAreaEntryByAreaID(zoneOrAreaId);
    if (!entry)
        return;

    // reactors: grouped bots, plus KNOWN nearby bots for the partyless
    // player (bounded fan-out; a stranger bot has no memory to anchor)
    std::vector<Player*> bots;
    if (Group* group = player->GetGroup())
    {
        for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
        {
            Player* member = itr->getSource();
            if (member && member != player && member->GetPlayerbotAI())
                bots.push_back(member);
        }
    }
    else
    {
        for (auto& nearEntry : sRandomPlayerbotMgr.GetPlayers())
        {
            Player* other = nearEntry.second;
            if (!other || !other->GetPlayerbotAI() || !other->IsInWorld())
                continue;
            if (other->GetMapId() != player->GetMapId())
                continue;
            if (sServerFacade.GetDistance2d(other, player) > 30.0f)
                continue;
            if (!HasPairingRow(other->GetGUIDLow(), player->GetGUIDLow()))
                continue;
            bots.push_back(other);
            if (bots.size() >= 4)
                break;
        }
    }

    for (Player* bot : bots)
    {
        // the zone name renders in the bot's locale idiom where the AI
        // provides one; the entry name is the fallback
        std::string zoneName;
        if (bot->GetPlayerbotAI())
            zoneName = bot->GetPlayerbotAI()->GetLocalizedAreaName(entry);
        if (zoneName.empty())
            zoneName = "new country";
        std::string const prefix =
            std::string("traveled with ") + player->GetName() + " to " + zoneName;
        if (HasSharedFactPrefix(bot->GetGUIDLow(), player->GetGUIDLow(), prefix))
            continue;
        // C6: the first-visit deed rides the explore bit (naturally
        // once per zone per pairing); 0 disables the AWARD, the fact at
        // this hook continues
        if (uint32 const deed = sPlayerbotAIConfig.llmDeedPointsFirstVisit)
            AddRelationshipPoints(bot, player,
                sPlayerbotAIConfig.llmTurnAwardWeighting ? (int32)deed : 1);
        LogFact(bot->GetGUIDLow(), player->GetGUIDLow(),
            prefix + " for the first time", "shared-event");
    }
}

bool PlayerbotLlmMemory::ConsumePendingAnswer(uint32 bot, uint32 player, std::string const& reply)
{
    if (!sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmEventReactionsEnabled)
        return false;
    uint64 const key = (static_cast<uint64>(bot) << 32) | player;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        auto itr = PendingAnswer().find(key);
        if (itr == PendingAnswer().end())
            return false;
        bool const expired = time(nullptr) > itr->second.second;
        PendingAnswer().erase(itr);
        if (expired || reply.empty())
            return false;
    }
    // the answer becomes permanent recall cargo, deterministically - the
    // 0.8B fallback's licensed log_fact fires unreliably, and a vanished
    // answer to an asked question is a broken promise. Same hygiene chain
    // as every fact write. The name-free POV keeps the fact stable across
    // the pairing's history
    LogFact(bot, player,
        std::string("asked, and the answer was: ") + TruncUtf8(
            pocketllm::NeuterMarkersCopy(ScrubControlTokens(
                StripAstral(reply)).c_str()), 160),
        "shared-event");
    return true;
}

// ---- authored kill banter (rare by design) ------------------------------
// The cadence contract: most kills pass in silence. Even when the dice hit,
// one bot speaks at most, its TOTAL bot-initiated chatter (kill quips + idle
// mood lines) is capped by the shared ambient slot, and the party hears at
// most one quip per KILL_BANTER_STAGGER seconds.
namespace {

uint32 const KILL_BANTER_ROLL_N = 24;      // ~4% of group kills even roll
time_t const KILL_BANTER_STAGGER = 480;    // >= 8 min between party-wide quips
uint32 const AMBIENT_MIN_INTERVAL = 900;   // per-bot: >= 15 min between lines

time_t& LastKillBanter()
{
    static time_t last = 0;
    return last;
}

} // namespace

bool PlayerbotLlmMemory::TryClaimAmbientSlot(uint32 botGuid, uint32 minIntervalSeconds,
    uint32 arbCategory)
{
    static std::map<uint32, time_t> spokenAt;
    std::lock_guard<std::mutex> lock(StateMutex());
    time_t const now = time(nullptr);
    auto itr = spokenAt.find(botGuid);
    if (itr != spokenAt.end() && now - itr->second < time_t(minIntervalSeconds))
        return false;
    // F7: the hourly authored-line budget. A rejection must NOT burn the
    // per-bot interval (the kill-banter law: silence never opens windows)
    if (!AuthoredLineAdmitsLocked(arbCategory, /*exempt=*/false, /*roomOnly=*/false))
        return false;
    spokenAt[botGuid] = now;
    return true;
}

// ---- the initiative scheduler --------------------------------------
namespace {

// per-bot cadence: initiative classes scan at most this often
uint32 const INITIATIVE_SCAN_SECS = 20;
// a player counts as RETURNING after this long out of the bot's range
time_t const INITIATIVE_RETURN_GAP = 900;
// the crowd tier: staggered emote delay bounds (seconds)
uint32 const CROWD_DELAY_MIN = 2, CROWD_DELAY_MAX = 5;

// Phase-3 RP dial scaling (llmRp* conf values, 0-100, 50 = default).
// Initiative dial scales the 10-minute zero-spam floor: 0 doubles the
// quiet (1200 s), 100 halves it (300 s). Reactivity scales event-shortcut
// eagerness inline at the call sites (level-up parity at <= 25, kill-roll
// sides x2/x/2, the bridge's exuberant > 25). Pure functions of the dial
// so the host tests can pin the curves.
inline uint32 InitiativeFloorSecs(uint32 dial)
{
    if (dial > 100) dial = 50;
    // 1200 at 0 .. 600 at 50 .. 300 at 100 (linear halves)
    return dial <= 50 ? 1200 - dial * 12 : 900 - dial * 6;
}

std::map<uint32, time_t>& InitiativeScanAt()
{
    static std::map<uint32, time_t> instance;
    return instance;
}

// per (bot, player): last time the bot saw the player in say range
std::map<uint64, time_t>& LastSeenNear()
{
    static std::map<uint64, time_t> instance;
    return instance;
}

// per (bot, player): the fact ids already carried by an initiative beat
// ("new facts want out once; stale facts stay quiet")
std::map<uint64, std::set<uint32>>& InitiatedFactIds()
{
    static std::map<uint64, std::set<uint32>> instance;
    return instance;
}

// the crowd-tier throttle: at most a couple of emotes per short
// window, world-wide (ambient say events fan out to every bot in range;
// the cap belongs to the EVENT, not the bot)
time_t& LastCrowdEmoteAt()
{
    static time_t last = 0;
    return last;
}

uint64 InitiativeKey(uint32 bot, uint32 player)
{
    return (static_cast<uint64>(bot) << 32) | player;
}

} // namespace

std::string PlayerbotLlmMemory::AuthoredArrivalGreeting(Player* bot, Player* player,
    std::string const& absenceBucket)
{
    if (!bot || !player)
        return "";
    std::string line = PlayerbotLlmPersona::GreetingLine(bot, player);
    if (line.empty())
        return "";
    // C7: the delivered greeting persists to the relationship row so
    // the next boot's GreetingLine redraws past it (cross-restart
    // verbatim replay); the marker rides the composer - every delivery
    // site (interceptor, fallback, initiative arrival) lands here
    NoteGreetingVoiced(bot, player, line);
    // The absence beat carries MAGNITUDE, never a passive line
    std::string const magnitude = pocketllm::AbsenceMagnitudeLine(absenceBucket);
    if (!magnitude.empty())
        line += " " + magnitude;
    // What the town says about the player rides the greeting (the
    // belief row logs with one distortion hop - the world's telling
    // drifts as it travels)
    std::string const gossip = GossipAbout(player->GetName());
    if (!gossip.empty())
    {
        line += " Town talk says '" + gossip + "'. Make of it what you will.";
        LogFact(bot->GetGUIDLow(), player->GetGUIDLow(),
            std::string("heard the town talk: ") +
                pocketllm::DistortGossipHop(gossip, bot->GetGUIDLow()),
            "shared-event");
    }
    return line;
}

bool PlayerbotLlmMemory::QueueCrowdEmote(Player* bot, Player* speaker)
{
    if (!bot || !speaker || !sPlayerbotAIConfig.llmEnabled ||
        !sPlayerbotAIConfig.llmBanterEnabled)
        return false;
    if (!bot->IsInWorld() || !bot->IsAlive() || bot->IsInCombat())
        return false;
    if (urand(1, 10) != 1)
        return false; // most ambient lines pass in silence
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        if (LastCrowdEmoteAt() && time(nullptr) - LastCrowdEmoteAt() < 12)
            return false; // 1-2 emotes per event window, world-wide
    }
    if (!TryClaimAmbientSlot(bot->GetGUIDLow(), InitiativeFloorSecs(sPlayerbotAIConfig.llmRpInitiative)))
        return false;
    // stamp only on a confirmed emote (a rejected claim
    // must not burn the world window in silence - the kill-banter law)
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        LastCrowdEmoteAt() = time(nullptr);
    }

    // Deterministic text emotes only - the crowd tier never pays a
    // generation (the fallback library was measured better on calm
    // beats). Persona-paced 2-5s stagger, seeded by the bot.
    static char const* const emotes[] = {
        "grin", "nod", "laugh", "shrug", "wave", "chuckle", "whistle",
    };
    std::string const emote = emotes[(bot->GetGUIDLow() + speaker->GetGUIDLow()) %
        (sizeof(emotes) / sizeof(emotes[0]))];

    EventReaction reaction;
    reaction.authored = true;
    reaction.emote = true;
    reaction.text = emote;
    reaction.playerGuid = speaker->GetGUIDLow();
    reaction.notBefore = time(nullptr) + urand(CROWD_DELAY_MIN, CROWD_DELAY_MAX);
    std::lock_guard<std::mutex> lock(StateMutex());
    EventReactions()[bot->GetGUIDLow()].push_back(reaction);
    while (EventReactions()[bot->GetGUIDLow()].size() > 2)
        EventReactions()[bot->GetGUIDLow()].pop_front();
    return true;
}

namespace
{
// A2: mapId -> {botGuid -> expiresAtMs} (steady-clock ms). TTL 300 s;
// a live dialogue RE-ARMS (its expiry extends), so a logout mid-dialogue
// leaks at most one ghost entry for <= the TTL - there is deliberately
// no decrement path.
std::map<uint32, std::map<uint32, uint64_t>>& DialogueOccupancy()
{
    static std::map<uint32, std::map<uint32, uint64_t>> occupancy;
    return occupancy;
}

uint64_t SteadyNowMs()
{
    return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// A6: the street windows - per-AreaId zone window (beside the crowd
// emote's world-wide window; zone granularity matches the crowd branch)
// and the per-bot street slot. Quota-first admission: the street lane is
// EXEMPT from the authored arbiter - its caps are this interval + the
// daily street quota, so the authored ambient category caps cannot
// starve the street allowance.
std::map<uint32, time_t>& LastStreetSayAt()
{
    static std::map<uint32, time_t> instance;
    return instance;
}

std::map<uint32, time_t>& StreetSlotAt()
{
    static std::map<uint32, time_t> instance;
    return instance;
}

uint32 const STREET_ZONE_WINDOW_SEC = 12;  // the crowd event window
uint32 const STREET_BOT_INTERVAL_SEC = 90; // one street say per bot per window

// the detached street worker's immutable job: strings and guids only -
// no Player*/Session* crosses the thread boundary
struct StreetJob
{
    uint32 botGuid;
    uint32 speakerGuid;
    std::string botName;
    std::string race;
    std::string cls;
    std::string zone;
    std::string speakerName;
    std::string heard;
    StreetJob() : botGuid(0), speakerGuid(0) {}
};

void RunStreetReaction(StreetJob job)
{
    // the generation leg: the heard text through the compose-site scrub
    // chain (player words die here - the street body carries none), the
    // street request via BuildChatRequestBody, then E0's kStreetShort
    // pool as the failure fallback (a dead endpoint still answers the
    // crowd). Delivery rides the authored SAY EventReaction
    // (world-thread drain, 2-5 s stagger) - never the chatter queue,
    // and never an A2 arm.
    std::string const heard = pocketllm::NeuterMarkersCopy(
        PlayerbotLlmMemory::ScrubControlTokens(job.heard).c_str());
    std::string line;
    if (!heard.empty())
    {
        std::string const body = pocketllm::BuildChatRequestBody(
            sPlayerbotAIConfig.llmChatterComposerModel,
            pocketllm::StreetSystemMessage(job.botName, job.race, job.cls, job.zone),
            std::vector<pocketllm::HistoryTurn>(),
            pocketllm::StreetNote(job.speakerName, heard),
            [&]
            {
                pocketllm::RequestSampling s;
                s.temperature = 0.9f;
                s.topP = 0.95f;
                s.maxTokens = 80;
                s.providerSafe = true;  // cloud endpoints reject unknown keys
                return s;
            }(),
            true);
        std::string const http = PlayerbotLLMInterface::PostChatHttp(
            body, sPlayerbotAIConfig.llmGenerationTimeout, nullptr, nullptr);
        pocketllm::CompletionEnvelope envelope =
            pocketllm::ParseCompletionEnvelope(http);
        if (envelope.parsed && pocketllm::ContentUsable(envelope))
            line = pocketllm::FirstStreetLine(envelope.content);
    }
    if (line.empty())
        line = PlayerbotLlmPersona::StreetShortLine(job.botGuid);
    if (line.empty())
        return; // even the pool draw failed: silence (SelectLine {0} safety)

    PlayerbotLlmMemory::EventReaction reaction;
    reaction.authored = true;
    reaction.msgtype = CHAT_MSG_SAY;
    reaction.text = line;
    reaction.playerGuid = job.speakerGuid;
    // steady-derived stagger (2-5 s): urand is world-thread only
    reaction.notBefore = time(nullptr) + CROWD_DELAY_MIN +
        (SteadyNowMs() % (CROWD_DELAY_MAX - CROWD_DELAY_MIN + 1));
    std::lock_guard<std::mutex> lock(StateMutex());
    std::deque<PlayerbotLlmMemory::EventReaction>& queue =
        EventReactions()[job.botGuid];
    queue.push_back(reaction);
    while (queue.size() > 2)
        queue.pop_front();
}
} // namespace

void PlayerbotLlmMemory::ArmDialogue(uint32 botGuid, uint32 mapId, bool interlocutor)
{
    // the fast-lane key gates the ARMING site itself (0 = the window
    // never opens; DialogueActive then reads nothing but expired dust)
    if (!sPlayerbotAIConfig.llmDialogueFastLane)
        return;
    uint64_t const nowMs = SteadyNowMs();
    std::vector<PlayerbotLlmGates::DialogueOccupant> occupants;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        auto itr = DialogueOccupancy().find(mapId);
        if (itr != DialogueOccupancy().end())
            for (auto& kv : itr->second)
                occupants.push_back({kv.first, kv.second});
    }
    // the pure helper owns prune + admission semantics (host-pinned):
    // the interlocutor always admits; anyone else needs occupancy < 16
    if (!PlayerbotLlmGates::EvictDialogueVictim(occupants, nowMs, botGuid, 16, botGuid))
        return;
    std::lock_guard<std::mutex> lock(StateMutex());
    DialogueOccupancy()[mapId][botGuid] = nowMs + 300ull * 1000ull;
}

bool PlayerbotLlmMemory::DialogueActive(uint32 botGuid)
{
    uint64_t const nowMs = SteadyNowMs();
    std::lock_guard<std::mutex> lock(StateMutex());
    for (auto const& mapKv : DialogueOccupancy())
    {
        auto itr = mapKv.second.find(botGuid);
        if (itr != mapKv.second.end() && itr->second > nowMs)
            return true;
    }
    return false;
}

std::string PlayerbotLlmMemory::DrawFailureFallback(
    PlayerbotLlmGates::FallbackPlan const& plan, uint32 botGuid, uint32 playerGuid)
{
    if (!plan.active)
        return "";
    // pointers re-resolve at draw time (the AddBoundedSentimentInput
    // precedent) - no Player* crossed the async wait, and a vanished
    // bot or player means nobody is left to speak to: silence is correct
    Player* bot = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, botGuid));
    if (!bot)
        return "";
    if (plan.kind == PlayerbotLlmGates::FBK_GREET)
    {
        Player* player = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, playerGuid));
        if (!player)
            return "";
        return AuthoredArrivalGreeting(bot, player, plan.absence);
    }
    if (plan.kind == PlayerbotLlmGates::FBK_PERSONA)
        return PlayerbotLlmPersona::FallbackLine(bot,
            (PlayerbotLlmPersona::HardCategory)plan.personaCategory, plan.whisper);
    return "";
}

void PlayerbotLlmMemory::QueueConversationalFallback(uint32 botGuid,
    uint32 playerGuid, uint32 msgtype, std::string const& text, uint32 mapId)
{
    if (text.empty())
        return;
    // the fallback delivery: the authored EventReaction queue (world-
    // thread drain - the async region never touches a Session*) and the
    // RELOCATED cloud-fallback delivery site the A2 arming pin follows
    // (the conversation window extends despite the failed turn)
    if (mapId)
        ArmDialogue(botGuid, mapId, true);
    EventReaction reaction;
    reaction.authored = true;
    reaction.msgtype = msgtype;
    reaction.text = text;
    reaction.playerGuid = playerGuid;
    std::lock_guard<std::mutex> lock(StateMutex());
    std::deque<EventReaction>& queue = EventReactions()[botGuid];
    queue.push_back(reaction);
    while (queue.size() > 2)
        queue.pop_front();
}

void PlayerbotLlmMemory::AddRelationshipPointsByGuid(uint32 botGuid,
    uint32 playerGuid, int32 points)
{
    if (!playerGuid || !points)
        return;
    if (Player* bot = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, botGuid)))
        if (Player* player = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, playerGuid)))
            AddRelationshipPoints(bot, player, points);
}

bool PlayerbotLlmMemory::QueueStreetReaction(Player* bot, Player* speaker,
    std::string const& heard)
{
    // cloud lane only (the conjunction, never the bare key); every
    // other lane no-ops here and the caller falls to the crowd emote,
    // byte-identical to the pre-A6 crowd branch
    if (!CloudLaneOpen() || !sPlayerbotAIConfig.llmBanterEnabled)
        return false;
    if (!bot || !speaker || heard.empty())
        return false;
    if (!bot->IsInWorld() || !bot->IsAlive() || bot->IsInCombat())
        return false;
    if (!bot->GetMap())
        return false;
    // the ladder, in the pinned order: world/zone window claim ->
    // per-bot slot -> pct roll -> daily quota -> dispatch. Any rejection
    // falls to the emote; quota exhaustion is emote-only, symmetric
    // with pct = 0. A6 consume (round-1 R1#3): the stages resolve lazily
    // IN ORDER (a rejected claim must not burn the later stages - the
    // quota only spends on a live dispatch), and the pure
    // StreetAdmissionOrder fold names the verdict so the runtime order
    // IS the host-pinned order.
    time_t const now = time(nullptr);
    uint32 const areaId = bot->GetAreaId();
    bool worldWindowClaimed = true;
    bool botSlotFree = true;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        if (LastCrowdEmoteAt() && now - LastCrowdEmoteAt() < 12)
            worldWindowClaimed = false;         // reject:world-window
        else
        {
            time_t& lastInArea = LastStreetSayAt()[areaId];
            if (lastInArea && now - lastInArea < STREET_ZONE_WINDOW_SEC)
                worldWindowClaimed = false;     // reject:zone-window
        }
        if (worldWindowClaimed)
        {
            time_t& lastForBot = StreetSlotAt()[bot->GetGUIDLow()];
            if (lastForBot && now - lastForBot < STREET_BOT_INTERVAL_SEC)
                botSlotFree = false;            // reject:bot-slot
        }
    }
    bool const pctRollHit = worldWindowClaimed && botSlotFree &&
        sPlayerbotAIConfig.llmCloudStreetSayPct &&
        urand(0, 99) < sPlayerbotAIConfig.llmCloudStreetSayPct;
    bool const quotaAdmits = pctRollHit &&
        CloudQuotaAdmits("street", sPlayerbotAIConfig.llmStreetSayPerDay);
    if (PlayerbotLlmGates::StreetAdmissionOrder(worldWindowClaimed,
            botSlotFree, pctRollHit, quotaAdmits) != "dispatch")
        return false;
    {
        // stamps land only on a confirmed dispatch (a rejected claim
        // must not burn the windows in silence - the kill-banter law);
        // the street say IS this window's crowd reaction, so the shared
        // world window stamps too
        std::lock_guard<std::mutex> lock(StateMutex());
        LastCrowdEmoteAt() = now;
        LastStreetSayAt()[areaId] = now;
        StreetSlotAt()[bot->GetGUIDLow()] = now;
    }

    StreetJob job;
    job.botGuid = bot->GetGUIDLow();
    job.speakerGuid = speaker->GetGUIDLow();
    job.botName = bot->GetName();
    job.race = pocketllm::RaceWord(bot->getRace());
    job.cls = pocketllm::ClassWord(bot->getClass());
    job.zone = ZoneNameOf(bot);
    job.speakerName = speaker->GetName();
    job.heard = heard;
    // the emote is dropped (not deferred) for this event: the caller
    // only queues the emote when this returns false
    std::thread(RunStreetReaction, job).detach();
    return true;
}

std::string PlayerbotLlmMemory::LastGreetLine(Player* bot, Player* player)
{
    if (!bot || !player || !sPlayerbotAIConfig.llmGreetMemory)
        return "";
    auto result = CharacterDatabase.PQuery(
        "SELECT `last_greet_line` FROM `bot_player_relationship` "
        "WHERE `bot` = '%u' AND `player` = '%u'",
        bot->GetGUIDLow(), player->GetGUIDLow());
    if (!result)
        return "";
    // rows without the column value mean "never voiced" (the 0413
    // no-backfill law) - an empty string, never a NULL deref
    return result->Fetch()[0].GetString();
}

void PlayerbotLlmMemory::NoteGreetingVoiced(Player* bot, Player* player,
    std::string const& line)
{
    if (!bot || !player || line.empty() || !sPlayerbotAIConfig.llmGreetMemory)
        return;
    CharacterDatabase.PExecute(
        "UPDATE `bot_player_relationship` SET `last_greeted_at` = CURRENT_TIMESTAMP, "
        "`last_greet_line` = '%s' WHERE `bot` = '%u' AND `player` = '%u'",
        EscapeSql(TruncUtf8(line, 250)).c_str(), bot->GetGUIDLow(), player->GetGUIDLow());
}

void PlayerbotLlmMemory::NoteTierVoiced(uint32 bot, uint32 player, int tier)
{
    if (!bot || !player || tier <= 0)
        return;
    CharacterDatabase.PExecute(
        "UPDATE `bot_player_relationship` SET `last_voiced_tier` = '%u' "
        "WHERE `bot` = '%u' AND `player` = '%u'",
        (uint32)tier, bot, player);
}

bool PlayerbotLlmMemory::PersistedLastVoicedTier(uint32 bot, uint32 player,
    int& tierOut)
{
    // the 0413 column, honored only while the tier is FRESH (tier_since
    // within 48 h) - the bridge's never-fire-on-stale-state doctrine,
    // amended deliberately by C5: a persisted ceremony re-arms only the
    // crossing that still reads as recent history
    tierOut = -1;
    auto result = CharacterDatabase.PQuery(
#ifdef DO_SQLITE
        "SELECT `last_voiced_tier`, strftime('%%s', `tier_since`) FROM `bot_player_relationship` "
        "WHERE `bot` = '%u' AND `player` = '%u'",
#else
        "SELECT `last_voiced_tier`, UNIX_TIMESTAMP(`tier_since`) FROM `bot_player_relationship` "
        "WHERE `bot` = '%u' AND `player` = '%u'",
#endif
        bot, player);
    if (!result)
        return false;
    Field* fields = result->Fetch();
    if (fields[0].IsNULL())
        return false;
    int const persisted = (int)fields[0].GetUInt32();
    time_t since = 0;
    if (!fields[1].IsNULL())
        since = static_cast<time_t>(fields[1].GetUInt64());
    if (!since || time(nullptr) - since > 48 * 3600)
        return false; // stale: the map stays process-local for this pairing
    tierOut = persisted;
    return true;
}

bool PlayerbotLlmMemory::AwardCappedPoints(Player* bot, Player* player, int32 points)
{
    if (!bot || !player)
        return false;
    // the per-pairing daily cap (the SentimentRate keyed-map pattern -
    // NOT the realm-global CloudQuotaAdmits) consumed by TURNS and
    // SHARED-KILLS: a scripted whisper farm or an elite-grind loop tops
    // out at LLMTurnAwardDailyCap points per pairing per UTC day.
    // 0 = uncapped (the kill-switch row). Trade/quest/first-visit stay
    // exempt (already scarce: rate-limited, non-repeatable, once-ever).
    uint32 const cap = sPlayerbotAIConfig.llmTurnAwardDailyCap;
    if (cap)
    {
        uint64 const key = (static_cast<uint64>(bot->GetGUIDLow()) << 32) | player->GetGUIDLow();
        time_t const today = time(nullptr) / 86400;
        std::lock_guard<std::mutex> lock(StateMutex());
        std::map<uint64, std::pair<time_t, uint32>>& awards = TurnAwards();
        auto itr = awards.find(key);
        if (itr == awards.end() || itr->second.first != today)
            awards[key] = {today, 0};
        if (awards[key].second >= cap)
            return false;
        awards[key].second += 1;
    }
    AddRelationshipPoints(bot, player, points);
    return true;
}

bool PlayerbotLlmMemory::AwardChatTurn(Player* bot, Player* player)
{
    return AwardCappedPoints(bot, player, 1);
}

bool PlayerbotLlmMemory::AwardChatTurnByGuid(uint32 botGuid, uint32 playerGuid)
{
    // the A4 closure's guid form: re-resolve, then the same capped path
    if (!playerGuid)
        return false;
    Player* bot = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, botGuid));
    Player* player = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, playerGuid));
    if (!bot || !player)
        return false;
    return AwardChatTurn(bot, player);
}

void PlayerbotLlmMemory::OnQuestRewarded(Player* player, uint32 questId,
    std::string const& questTitle, bool repeatable)
{
    // the CORE_REWARDQUEST anchor's single leg: grouped bots award the
    // quest deed + mint the fact. Anti-farm law: a repeatable quest
    // awards NOTHING and mints nothing (the farm surface is the turn-in
    // loop, not the first completion); 0 on the deed key disables the
    // AWARD while the fact at this hook continues.
    if (!player || player->GetPlayerbotAI() || !player->isRealPlayer())
        return;
    if (!sPlayerbotAIConfig.llmEnabled)
        return;
    if (repeatable)
        return;
    Group* group = player->GetGroup();
    if (!group)
        return;
    std::string const title = questTitle.empty()
        ? std::string("an unnamed errand") : questTitle;
    for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
    {
        Player* member = itr->getSource();
        if (!member || member == player || !member->GetPlayerbotAI())
            continue;
        LogFact(member->GetGUIDLow(), player->GetGUIDLow(),
            std::string("finished the errand '") + title +
                "' alongside " + player->GetName(), "shared-event");
        uint32 const deed = sPlayerbotAIConfig.llmDeedPointsQuest;
        if (deed)
            AddRelationshipPoints(member, player,
                sPlayerbotAIConfig.llmTurnAwardWeighting ? (int32)deed : 1);
    }
    (void)questId;
}

void PlayerbotLlmMemory::NotePartyDigestLine(uint32 masterGuid, std::string const& line)
{
    // the C4 window: a bounded per-master rolling deque (12 entries,
    // 160 B each) written at the A3-restructured party block. The
    // EXISTING partyLineAt single-slot roundtable row is untouched.
    if (!masterGuid || line.empty())
        return;
    std::lock_guard<std::mutex> lock(StateMutex());
    std::deque<std::string>& window = PartyDigestWindow()[masterGuid];
    window.push_back(TruncUtf8(line, 160));
    while (window.size() > 12)
        window.pop_front();
}

void PlayerbotLlmMemory::MaybeMintPartyDigest(uint32 masterGuid, uint32 groupId)
{
    // every window close (a full 12-line deque), ONE writer mints the
    // digest: the tier >= 3 storyteller pick over the group candidates
    // (SelectResponder - the same deterministic pick the A3 responder
    // uses), a register-native 5-24 word clause (verb-initial, the
    // fact corpus law), MintOnceFact keyed (bot, windowIndex) under
    // the LLMPartyDigestPerDay quota. voiced_at stays unset (only a
    // SURFACE may stamp it - the C2 law).
    if (!masterGuid || !groupId || !sPlayerbotAIConfig.llmEnabled)
        return;
    std::vector<std::string> window;
    uint32 windowIndex = 0;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        std::deque<std::string>& deque = PartyDigestWindow()[masterGuid];
        if (deque.size() < 12)
            return;
        window.assign(deque.begin(), deque.end());
        deque.clear();
        windowIndex = ++PartyDigestIndex()[masterGuid];
    }
    if (!CloudQuotaAdmits("party-digest", sPlayerbotAIConfig.llmPartyDigestPerDay))
        return; // the window still closed; the quota only bounds the rows

    std::vector<PlayerbotLlmGates::ResponderCandidate> candidates;
    if (!CollectPartyCandidates(groupId, masterGuid, candidates))
        return;
    // the storyteller is the deterministic pick among tier >= 3 members
    std::vector<PlayerbotLlmGates::ResponderCandidate> storytellers;
    for (auto const& c : candidates)
        if (c.tier >= 3)
            storytellers.push_back(c);
    if (storytellers.empty())
        return;
    uint32 const writer = PlayerbotLlmGates::SelectResponder(storytellers);
    if (!writer)
        return;

    // the topic: the window's longest line trimmed to six words (a
    // clause anchor, not a quote - the digest is a remembered beat)
    std::string topic;
    for (std::string const& line : window)
        if (line.size() > topic.size())
            topic = line;
    size_t words = 0, cut = 0;
    for (size_t i = 0; i < topic.size() && words < 6; ++i)
        if (topic[i] == ' ')
        {
            ++words;
            cut = i;
        }
    if (words >= 6 && cut)
        topic.resize(cut);

    static char const* const kDigest[3] = {
        "argued through a long march with the party, mostly over ",
        "marched with the party while they argued over ",
        "shared the road with the party, arguing over ",
    };
    std::ostringstream digest;
    digest << "party talk: " << kDigest[windowIndex % 3] << topic;
    // MintOnceFact keyed (bot, windowIndex): a closed window mints at
    // most once ever, even across process restarts within the day
    MintOnceFact(writer, masterGuid,
        (static_cast<uint64>(writer) << 32) ^ (0xD16E57u * (windowIndex + 1)),
        86400, digest.str(), "shared-event");
}

void PlayerbotLlmMemory::TickInitiative(Player* bot)
{
    if (!bot || !bot->IsInWorld() || !sPlayerbotAIConfig.llmEnabled ||
        !sPlayerbotAIConfig.llmBanterEnabled)
        return;
    if (bot->IsInCombat())
        return;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        time_t const now = time(nullptr);
        auto itr = InitiativeScanAt().find(bot->GetGUIDLow());
        if (itr != InitiativeScanAt().end() && now - itr->second < INITIATIVE_SCAN_SECS)
            return;
        InitiativeScanAt()[bot->GetGUIDLow()] = now;
    }

    // W1 wipe aftermath: a revived bot greets the returning player shaken.
    // The pending line survives until its expiry (the player may still be
    // running back as a ghost) and is consumed only when spoken; it is the
    // guaranteed first beat after a wipe, so it rides OUTSIDE the pacing
    // budgets (deaths are rare, and rare must land)
    {
        Player* aftermathPlayer = nullptr;
        {
            std::lock_guard<std::mutex> lock(StateMutex());
            auto pend = PendingAftermath().find(bot->GetGUIDLow());
            if (pend != PendingAftermath().end())
            {
                if (time(nullptr) > pend->second.second)
                {
                    PendingAftermath().erase(pend);
                }
                else
                {
                    Player* candidate = sObjectAccessor.FindPlayer(
                        ObjectGuid(HIGHGUID_PLAYER, pend->second.first));
                    if (candidate && candidate->isRealPlayer() &&
                        candidate->GetMapId() == bot->GetMapId() &&
                        sServerFacade.GetDistance2d(candidate, bot) <= 30.0f)
                        aftermathPlayer = candidate;
                }
            }
        }
        if (aftermathPlayer && bot->IsAlive() && !bot->IsInCombat())
        {
            std::string const line = PlayerbotLlmPersona::ReactionLine(
                bot, PlayerbotLlmPersona::REACTION_SHAKEN, aftermathPlayer);
            if (!line.empty())
            {
                {
                    std::lock_guard<std::mutex> lock(StateMutex());
                    PendingAftermath().erase(bot->GetGUIDLow());
                }
                bot->Say(line, LANG_UNIVERSAL);
                AppendTurn(bot->GetGUIDLow(),
                    0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY), true,
                    bot->GetName(), line);
                return; // one voice per scan
            }
        }
    }

    // the seen-set: real players within say range (the module's own
    // proximity idiom - sRandomPlayerbotMgr tracks the real players)
    std::vector<Player*> nearby;
    for (auto& entry : sRandomPlayerbotMgr.GetPlayers())
    {
        Player* player = entry.second;
        if (!player || !player->isRealPlayer() || player == bot)
            continue;
        if (player->GetMapId() != bot->GetMapId())
            continue;
        if (sServerFacade.GetDistance2d(player, bot) > 30.0f)
            continue;
        nearby.push_back(player);
    }

    // arrival detection: a REMEMBERED player newly back in range after a
    // long gap earns the greet-first packet: bots must speak first.
    // "Remembered" = a relationship row exists.
    std::vector<Player*> arrivals;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        time_t const now = time(nullptr);
        for (Player* player : nearby)
        {
            uint64 const key = InitiativeKey(bot->GetGUIDLow(), player->GetGUIDLow());
            auto itr = LastSeenNear().find(key);
            bool returning = itr == LastSeenNear().end() ||
                now - itr->second > INITIATIVE_RETURN_GAP;
            LastSeenNear()[key] = now;
            if (returning)
                arrivals.push_back(player);
        }
    }

    for (Player* player : arrivals)
    {
        // eligibility FIRST, slot claim LAST (a stranger's
        // arrival or an empty greeting draw must not burn the bot's whole
        // 10-minute initiative budget in silence)
        std::string const absence = GetAbsenceBucket(bot, player);
        if (absence == "a first meeting")
            continue; // never met: no greeting memory to draw on
        // plan v5 W3: the pairing-tenure milestone mints as a living fact
        // ("have known you a full month") the first time each bucket is
        // crossed - the journal already renders the anniversary; this way
        // the greeting/ask-after surfaces can voice it too
        if (sPlayerbotAIConfig.llmEventReactionsEnabled)
        {
            int const days = PairingAgeDays(bot->GetGUIDLow(), player->GetGUIDLow());
            int const bucket = pocketllm::AnniversaryBucket(days);
            if (bucket)
            {
                std::string const tenureWord = bucket >= 365 ? "a whole year"
                    : (bucket >= 100 ? "a hundred days" : "a full month");
                std::string const prefix =
                    std::string("have known ") + player->GetName();
                if (!HasSharedFactPrefix(bot->GetGUIDLow(), player->GetGUIDLow(),
                        prefix + " for"))
                    LogFact(bot->GetGUIDLow(), player->GetGUIDLow(),
                        prefix + " for " + tenureWord, "shared-event");
            }
        }
        std::string const line = AuthoredArrivalGreeting(bot, player, absence);
        if (line.empty())
            continue;
        if (!TryClaimAmbientSlot(bot->GetGUIDLow(), InitiativeFloorSecs(sPlayerbotAIConfig.llmRpInitiative)))
            return; // the zero-spam cap outranks every class
        bot->Say(line, LANG_UNIVERSAL);
        AppendTurn(bot->GetGUIDLow(),
            0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY), true,
            bot->GetName(), line);
        // A18: a player walking up on two bots may catch them mid-talk -
        // a rare authored exchange, staggered so it reads as two voices.
        // One fact-free opener set only (weather, blades, roads): an
        // authored line must never fabricate ledger state. The reply
        // matches its OWN opener and answers on /say, the
        // channel the bystander heard the opener on.
        if (urand(1, 6) == 1)
        {
            std::list<Player*> players;
            MaNGOS::AnyPlayerInObjectRangeCheck check(bot, 30.0f);
            MaNGOS::PlayerListSearcher<MaNGOS::AnyPlayerInObjectRangeCheck> searcher(
                players, check);
            Cell::VisitWorldObjects(bot, searcher, 30.0f);
            for (Player* other : players)
            {
                if (!other || other == bot || !other->GetPlayerbotAI() ||
                    !other->IsAlive() || other->IsInCombat())
                    continue;
                static char const* const openers[] = {
                    "Still keeping that old blade sharp, ",
                    "The roads east get worse every week, don't they, ",
                    "Cold one tonight, ",
                };
                static char const* const replies[] = {
                    "Sharp enough. Sharper than your wit, at any rate.",
                    "Worse, and the militia knows it. Guard your pack.",
                    "Aye. Cold enough to make the watch short and the fire crowded.",
                };
                uint32 const pick = bot->GetGUIDLow() % 3;
                bot->Say(std::string(openers[pick]) + other->GetName() + ".",
                    LANG_UNIVERSAL);
                EventReaction reply;
                reply.authored = true;
                reply.playerGuid = player->GetGUIDLow();
                reply.text = replies[pick];
                reply.msgtype = CHAT_MSG_SAY;
                reply.notBefore = time(nullptr) + urand(CROWD_DELAY_MIN, CROWD_DELAY_MAX);
                {
                    std::lock_guard<std::mutex> lock(StateMutex());
                    EventReactions()[other->GetGUIDLow()].push_back(reply);
                    while (EventReactions()[other->GetGUIDLow()].size() > 2)
                        EventReactions()[other->GetGUIDLow()].pop_front();
                }
                break; // one exchange, one partner
            }
        }
        return; // one initiative per scan at most
    }

    // debt reminders and goal ask-afters need a player present who is
    // not brand new to the bot's range (the arrival greeting covered
    // that moment); each fact initiates AT MOST ONCE
    if (nearby.empty())
        return;
    for (Player* player : nearby)
    {
        uint64 const key = InitiativeKey(bot->GetGUIDLow(), player->GetGUIDLow());
        auto result = CharacterDatabase.PQuery(
            "SELECT `id`, `fact_text`, `category`, `voiced_at` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
            "ORDER BY `id` DESC LIMIT 6",
            bot->GetGUIDLow(), player->GetGUIDLow());
        if (!result)
            continue;
        struct FactRow { uint32 id; std::string text; std::string category; bool voiced; };
        std::vector<FactRow> rows;
        do
        {
            Field* fields = result->Fetch();
            rows.push_back(FactRow{fields[0].GetUInt32(), fields[1].GetString(),
                fields[2].GetString(), !fields[3].IsNULL()});
        } while (result->NextRow());
        // C2 (round-1 R4#1): the lazy per-pair seed - a row whose
        // voiced_at is set already had its one initiation in a previous
        // process (NULL = never voiced; no backfill: historical rows
        // keep today's per-process behavior). No boot-time scan.
        {
            std::lock_guard<std::mutex> lock(StateMutex());
            for (FactRow const& row : rows)
                if (row.voiced)
                    InitiatedFactIds()[key].insert(row.id);
        }

        std::string line;
        uint32 usedFactId = 0;
        // one tier read per player per scan (the per-row
        // GetTrainedTier re-query was the one wasteful shape in the scan)
        int const playerTier = GetTrainedTier(bot, player);
        for (FactRow const& row : rows)
        {
            std::string const& text = row.text;
            if (text.rfind("(tone", 0) == 0)
                continue;
            int const cls = pocketllm::FactClassOf(text, row.category);
            if (cls == pocketllm::FACT_DEBT)
            {
                line = pocketllm::DebtReminderLine(player->GetName(),
                    pocketllm::MoneyPhrase(text));
                usedFactId = row.id;
                break;
            }
            if (cls == pocketllm::FACT_GOAL && playerTier >= 3)
            {
                line = pocketllm::GoalAskAfterLine(player->GetName(), text);
                usedFactId = row.id;
                break;
            }
        }
        if (line.empty())
            continue;
        {
            std::lock_guard<std::mutex> lock(StateMutex());
            if (InitiatedFactIds()[key].count(usedFactId))
                continue; // this fact already had its one initiation
        }
        if (!TryClaimAmbientSlot(bot->GetGUIDLow(), InitiativeFloorSecs(sPlayerbotAIConfig.llmRpInitiative)))
            return; // the zero-spam cap outranks every class
        {
            std::lock_guard<std::mutex> lock(StateMutex());
            InitiatedFactIds()[key].insert(usedFactId);
        }
        // C2: the durable half - exactly one stamp site, at the
        // delivery block where the fact id is in hand synchronously
        // (never at enqueue on delayed paths). Debt/goal initiations
        // no longer re-fire after a restart.
        CharacterDatabase.PExecute(
            "UPDATE `bot_player_facts` SET `voiced_at` = '%u' WHERE `id` = '%u'",
            (uint32)time(nullptr), usedFactId);
        bot->Say(line, LANG_UNIVERSAL);
        AppendTurn(bot->GetGUIDLow(),
            0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY), true,
            bot->GetName(), line);
        return; // one initiative per scan at most
    }

    // plan v5 W5: the bot-curiosity class - a GUID-stable-ordered question
    // from the 16-wide bank, tier >= 2, one ask per question per pairing,
    // a 30-minute floor per pairing, and the ambient slot's zero-spam cap.
    // The ask arms the pending answer; the player's next conversational
    // reply mints the fact deterministically (ConsumePendingAnswer at the
    // bridge - the 0.8B answer-capture law)
    if (sPlayerbotAIConfig.llmEventReactionsEnabled &&
        sPlayerbotAIConfig.llmCuriosityEnabled)
    {
        for (Player* player : nearby)
        {
            if (GetTrainedTier(bot, player) < 2)
                continue;
            uint64 const key = InitiativeKey(bot->GetGUIDLow(), player->GetGUIDLow());
            std::string question;
            size_t questionIdx = pocketllm::CuriosityQuestionCount();
            {
                std::lock_guard<std::mutex> lock(StateMutex());
                time_t const now = time(nullptr);
                auto lastAsk = CuriosityLastAsk().find(key);
                if (lastAsk != CuriosityLastAsk().end() &&
                    now - lastAsk->second < 1800)
                    continue; // the 30-minute question floor
                uint32& mask = CuriosityAskedMask()[key];
                for (size_t q = 0; q < pocketllm::CuriosityQuestionCount(); ++q)
                {
                    if (mask & (1u << (q % 32)))
                        continue;
                    questionIdx = q;
                    break;
                }
                if (questionIdx >= pocketllm::CuriosityQuestionCount())
                    continue; // the whole bank is asked out (per process)
                question = pocketllm::CuriosityQuestionLine(questionIdx, player->GetName());
            }
            if (question.empty())
                continue;
            if (!TryClaimAmbientSlot(bot->GetGUIDLow(), InitiativeFloorSecs(sPlayerbotAIConfig.llmRpInitiative)))
                return; // the zero-spam cap outranks every class
            {
                std::lock_guard<std::mutex> lock(StateMutex());
                CuriosityAskedMask()[key] |= (1u << (questionIdx % 32));
                CuriosityLastAsk()[key] = time(nullptr);
                PendingAnswer()[key] = std::make_pair(
                    bot->GetGUIDLow(), time(nullptr) + 600);
            }
            bot->Say(question, LANG_UNIVERSAL);
            AppendTurn(bot->GetGUIDLow(),
                0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY), true,
                bot->GetName(), question);
            return; // one initiative per scan at most
        }
    }
}

void PlayerbotLlmMemory::OnPlayerGroupKill(Player* tapper, Unit* victim)
{
    if (!tapper || !victim || !sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmBanterEnabled)
        return;
    // real players only; no PvP quips (the banter corpus is PvE-voiced)
    if (!tapper->isRealPlayer() || victim->GetTypeId() == TYPEID_PLAYER)
        return;

    // plan v5 W3: an ELITE kill is a moment the game itself verifies - it
    // skips the quip roll entirely and mints memory instead: every grouped
    // bot records watching the player fell the elite (prefix-checked, so
    // farming the same elite mints once per pairing), and one town row
    // feeds the rumor mill with the player as legend material
    if (sPlayerbotAIConfig.llmEventReactionsEnabled &&
        victim->GetTypeId() == TYPEID_UNIT)
    {
        CreatureInfo const* const ci =
            ObjectMgr::GetCreatureTemplate(victim->GetEntry());
        if (ci && ci->Rank >= CREATURE_ELITE_ELITE)
        {
            Group* eliteGroup = tapper->GetGroup();
            std::string const victimName = victim->GetName();
            std::string const zone = ZoneNameOf(tapper);
            bool townRowMinted = false;
            if (eliteGroup)
            {
                std::vector<Player*> groupedBots;
                for (GroupReference* itr = eliteGroup->GetFirstMember(); itr; itr = itr->next())
                {
                    Player* member = itr->getSource();
                    if (!member || !member->GetPlayerbotAI() || member == tapper)
                        continue;
                    groupedBots.push_back(member);
                    std::string const prefix = std::string("watched ") +
                        tapper->GetName() + " fell " + victimName;
                    if (!HasSharedFactPrefix(member->GetGUIDLow(), tapper->GetGUIDLow(), prefix))
                        LogFact(member->GetGUIDLow(), tapper->GetGUIDLow(),
                            prefix + " in " + zone, "shared-event");
                    // C6: the shared-kill deed CONSUMES the per-pairing
                    // daily cap (an elite-grind loop is the same farm
                    // surface as a whisper farm); 0 disables the award,
                    // the witnessed fact and the town row continue
                    if (uint32 const deed = sPlayerbotAIConfig.llmDeedPointsSharedKill)
                        AwardCappedPoints(member, tapper,
                            sPlayerbotAIConfig.llmTurnAwardWeighting ? (int32)deed : 1);
                    if (!townRowMinted)
                    {
                        ShareGossip(member->GetGUIDLow(),
                            std::string(tapper->GetName()) + " felled " + victimName +
                            " in " + zone, "kill");
                        townRowMinted = true;
                    }
                }
                // plan v5 F2: every pair of bots that shared the elite
                // kill earns dyad affinity and a witnessed event the
                // party topic (W6) can voice later
                for (size_t i = 0; i < groupedBots.size(); ++i)
                    for (size_t j = i + 1; j < groupedBots.size(); ++j)
                        NoteDyadEvent(groupedBots[i]->GetGUIDLow(),
                            groupedBots[j]->GetGUIDLow(), 1,
                            std::string("felled ") + victimName + " together in " + zone);
            }
        }
    }

    // Phase-3 reactivity: the 1-in-24 kill roll halves at dial 0
    // (1-in-48) and doubles at dial 100 (1-in-12). Default 50 = base.
    uint32 killSides = KILL_BANTER_ROLL_N;
    if (sPlayerbotAIConfig.llmRpReactivity <= 25)
        killSides *= 2;
    else if (sPlayerbotAIConfig.llmRpReactivity >= 75)
        killSides /= 2;
    if (urand(1, killSides) != 1)
        return;

    Group* group = tapper->GetGroup();
    if (!group)
        return;

    std::vector<Player*> bots;
    for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
    {
        Player* member = itr->getSource();
        if (member && member != tapper && member->GetPlayerbotAI())
            bots.push_back(member);
    }
    if (bots.empty())
        return;

    {
        std::lock_guard<std::mutex> lock(StateMutex());
        time_t const now = time(nullptr);
        if (LastKillBanter() && now - LastKillBanter() < KILL_BANTER_STAGGER)
            return;
    }

    // one bot speaks; its shared ambient slot bounds total chatter (the
    // reaction category shares the 2/hr cap with the initiative asks)
    Player* chosen = bots[urand(0, uint32(bots.size() - 1))];
    if (!TryClaimAmbientSlot(chosen->GetGUIDLow(), AMBIENT_MIN_INTERVAL, ARB_REACTION))
        return;

    std::string const line = PlayerbotLlmPersona::KillBanterLine(chosen, tapper);
    if (line.empty())
        return;

    // quip confirmed: only now does the party-wide stagger window open - a
    // quip dropped by the slot claim or an empty draw must not burn the
    // window in silence (Unit::Kill is world-thread-only, so the
    // check-then-stamp sequence cannot interleave)
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        LastKillBanter() = time(nullptr);
    }

    // the quip joins the shared party rolling history so later prompts see
    // the bot actually said it (mutex-guarded; world thread here)
    AppendTurn(chosen->GetGUIDLow(),
        0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_PARTY), true,
        chosen->GetName(), line);

    EventReaction reaction;
    reaction.authored = true;
    reaction.playerGuid = tapper->GetGUIDLow();
    reaction.text = line;
    std::lock_guard<std::mutex> lock(StateMutex());
    EventReactions()[chosen->GetGUIDLow()].push_back(reaction);
    while (EventReactions()[chosen->GetGUIDLow()].size() > 2)
        EventReactions()[chosen->GetGUIDLow()].pop_front();
}

bool PlayerbotLlmMemory::DrainEventReaction(uint32 botGuid, EventReaction& reaction)
{
    std::lock_guard<std::mutex> lock(StateMutex());
    std::map<uint32, std::deque<EventReaction>>& reactions = EventReactions();
    auto itr = reactions.find(botGuid);
    if (itr == reactions.end() || itr->second.empty())
        return false;
    // Pacing: a staggered reaction waits its turn (front of the
    // queue - later reactions never jump ahead of it)
    if (itr->second.front().notBefore > time(nullptr))
        return false;
    reaction = itr->second.front();
    itr->second.pop_front();
    return true;
}

void PlayerbotLlmMemory::QueueAuthoredReaction(Player* bot, EventReaction& reaction)
{
    if (!bot)
        return;
    std::lock_guard<std::mutex> lock(StateMutex());
    EventReactions()[bot->GetGUIDLow()].push_back(reaction);
    while (EventReactions()[bot->GetGUIDLow()].size() > 2)
        EventReactions()[bot->GetGUIDLow()].pop_front();
}

bool PlayerbotLlmMemory::PrewarmDue(uint32 botGuid)
{
    static std::map<uint32, time_t> prewarmAt;
    std::lock_guard<std::mutex> lock(StateMutex());
    time_t const now = time(nullptr);
    auto itr = prewarmAt.find(botGuid);
    if (itr != prewarmAt.end() && now - itr->second < 30)
        return false;
    prewarmAt[botGuid] = now;
    return true;
}

// plan v5 C1.3: the cloud quota ledger - surface -> (day, count). The
// day bucket is UTC-days so a long session rolls at a stable boundary
namespace {
std::map<std::string, std::pair<int64_t, uint32>>& CloudQuotaUsed()
{
    static std::map<std::string, std::pair<int64_t, uint32>> instance;
    return instance;
}

// A7.1: the interactive tier's keyed map (player guid -> hour,used),
// the CloudQuotaUsed pattern
static std::map<uint32, std::pair<int64_t, uint32>>& InteractiveBudgetUsed()
{
    static std::map<uint32, std::pair<int64_t, uint32>> instance;
    return instance;
}

// A7.3 (round-1 R1#2): the bot2bot depth ledger - bot guid ->
// consecutive autonomous lines since the last real-player
// conversational trigger reached that bot
static std::map<uint32, uint32>& BotToBotConsecutive()
{
    static std::map<uint32, uint32> instance;
    return instance;
}

// the autonomous-exchange depth law: at most this many consecutive
// bot2bot lines per bot between real-player interactions
uint32 const BOT2BOT_MAX_CONSECUTIVE = 3;

// plan v5 C5: the dossier's 7-day gate, per player (a per-day quota
// cannot express weekly; a process-local stamp is honest about what a
// restart resets)
std::map<uint32, time_t>& LastDossierAt()
{
    static std::map<uint32, time_t> instance;
    return instance;
}
} // namespace

bool PlayerbotLlmMemory::ExternalApiTierActive()
{
    return sPlayerbotAIConfig.llmApiProviderSafe != 0 &&
        sPlayerbotAIConfig.llmContextLength >= 65536;
}

// ---- plan v5 F2: the dyad ledger ------------------------------------------

namespace {
uint64 DyadKey(uint32 a, uint32 b)
{
    return a < b ? ((uint64)a << 32) | b : ((uint64)b << 32) | a;
}
} // namespace

void PlayerbotLlmMemory::NoteDyadEvent(uint32 botA, uint32 botB, int points,
    std::string const& eventText)
{
    if (botA == botB)
        return;
    std::lock_guard<std::mutex> lock(StateMutex());
    DyadEntry& entry = Dyads()[DyadKey(botA, botB)];
    entry.points += points;
    if (entry.points > 5)
        entry.points = 5;
    if (entry.points < -3)
        entry.points = -3;
    if (!eventText.empty())
    {
        entry.newestEvent = eventText;
        entry.voiced = false;
    }
}

int PlayerbotLlmMemory::DyadAffinity(uint32 botA, uint32 botB)
{
    std::lock_guard<std::mutex> lock(StateMutex());
    auto itr = Dyads().find(DyadKey(botA, botB));
    return itr == Dyads().end() ? 0 : itr->second.points;
}

bool PlayerbotLlmMemory::ClaimNewestDyadEvent(uint32 botA, uint32 botB, std::string& eventOut)
{
    if (botA == botB)
        return false;
    std::lock_guard<std::mutex> lock(StateMutex());
    auto itr = Dyads().find(DyadKey(botA, botB));
    if (itr == Dyads().end() || itr->second.voiced || itr->second.newestEvent.empty())
        return false;
    eventOut = itr->second.newestEvent;
    itr->second.voiced = true;
    return true;
}

bool PlayerbotLlmMemory::PeekNewestDyadEvent(uint32 botA, uint32 botB, std::string& eventOut)
{
    if (botA == botB)
        return false;
    std::lock_guard<std::mutex> lock(StateMutex());
    auto itr = Dyads().find(DyadKey(botA, botB));
    if (itr == Dyads().end() || itr->second.voiced || itr->second.newestEvent.empty())
        return false;
    eventOut = itr->second.newestEvent;
    return true;
}

// A7.1 tier I - interactive: whisper, addressed say, the one party
// responder and A2 continuations are EXEMPT from the ambient arbiter and
// ride their own per-player hourly budget instead (the SentimentRate
// keyed-map pattern; the addressed interlocutor is always admitted - the
// budget bounds the sustained rate, not the first reply). Keyed by real
// player guid; 0 disables interactive cloud replies (device behavior).
bool PlayerbotLlmMemory::InteractiveBudgetAdmits(uint32 playerGuid)
{
    if (!playerGuid)
        return false;
    uint32 const cap = sPlayerbotAIConfig.llmCloudInteractivePerPlayerHour;
    if (!ExternalApiTierActive())
        return true;  // device lane: the governor is the only limiter
    if (!cap)
        return false;
    int64_t const hour = (int64_t)(time(nullptr) / 3600);
    std::lock_guard<std::mutex> lock(StateMutex());
    std::pair<int64_t, uint32>& used = InteractiveBudgetUsed()[playerGuid];
    if (used.first != hour)
        used = std::make_pair(hour, 0u);
    if (used.second >= cap)
        return false;
    ++used.second;
    return true;
}

// A7.3 (round-1 R1#2 wiring): bot2bot containment - the tier-II daily
// quota (CloudQuotaAdmits surface, realm-global, process-local,
// 0 = surface off) AND the autonomous-exchange depth cap. The chance
// sites broadcast (no single peer guid is threaded through them), so
// the depth counter is per BOT: at most 3 consecutive autonomous lines
// between real-player interactions - for the dominant two-bot reply
// loop this bounds the exchange and kills the only self-amplifying
// lane. Device lane: unchanged (the authored-lines ceiling stays its
// only bound; the 0.13 mirror-case byte-identity law).
bool PlayerbotLlmMemory::BotToBotAdmits(uint32 botGuid)
{
    if (!ExternalApiTierActive())
        return true;
    // depth first, in its own lock scope (round-2 R1#1/R8#1): a later
    // stage's rejection must not spend an earlier stage's quota - the
    // street-ladder law. A depth-saturated bot's attempts stop burning
    // the realm-global daily budget.
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        if (BotToBotConsecutive()[botGuid] >= BOT2BOT_MAX_CONSECUTIVE)
            return false;
    }
    // the quota takes StateMutex itself - no lock may be held here
    if (!CloudQuotaAdmits("bot2bot", sPlayerbotAIConfig.llmBotToBotPerDay))
        return false;
    std::lock_guard<std::mutex> lock(StateMutex());
    ++BotToBotConsecutive()[botGuid];
    return true;
}

// the reset half of the depth law: a real player's conversational
// trigger reaching this bot clears its autonomous-exchange depth
void PlayerbotLlmMemory::NoteBotPlayerInteraction(uint32 botGuid)
{
    std::lock_guard<std::mutex> lock(StateMutex());
    BotToBotConsecutive().erase(botGuid);
}

bool PlayerbotLlmMemory::CloudQuotaAdmits(char const* surface, uint32 perDay)
{
    if (!surface)
        return false;
    int64_t const day = (int64_t)(time(nullptr) / 86400);
    std::lock_guard<std::mutex> lock(StateMutex());
    std::pair<int64_t, uint32>& used = CloudQuotaUsed()[surface];
    if (used.first != day)
        used = std::make_pair(day, 0u);
    if (used.second >= perDay)
        return false;
    ++used.second;
    return true;
}

// ---- A3: the exactly-one party responder -----------------------------------

namespace {

struct PartyResponderClaim
{
    uint32 botGuid;
    int64_t expiresAt;
    PartyResponderClaim() : botGuid(0), expiresAt(0) {}
};

// the claim window bounds every reachable chat-drain stagger (round-6
// R1: the engine's UpdateAIInternal delays run 3-7 s on teleport/cast
// chains, so the fan-out does NOT resolve within one tick; a marker or
// claim younger than this owns the line outright)
int64_t const PARTY_CLAIM_WINDOW_SECONDS = 30;

// Round-9 R1 (the per-entry straddle; premise corrected round-10 R1
// MINOR): the group receive handlers run sequentially on the world
// thread and one fan-out USUALLY crosses at most a single wall-clock
// tick, so a later member's queue entry can carry m_time = T+1 beside
// a marker/claim stamped at T - the drain TTL subtracts this margin
// so entries that could outlive their line's tokens drop early. But
// the fan-out span is NOT bounded at one tick: the queue push blocks
// on the receiving bot's chatRepliesMutex, which a concurrently
// draining member holds across its entire ChatReplyDo (pre-existing
// upstream scope, the synchronous tier queries included), so a
// mid-drain member can widen the straddle past this margin. The
// exactly-one law therefore does NOT rest on this constant anymore:
// the round-10 first-heard registry (NotePartyLineHeard) anchors the
// claim grant to the LINE's earliest receive - straddle-free and
// divergence-free. The margin now governs only drop TIMING (each
// second of it is one more second of live lines the drain drops: a
// missed reply, never a second generation - the conservative
// direction), and the claim grant bound reuses this same
// window-minus-margin expression so both sites read ONE law.
int64_t const PARTY_CLAIM_FANOUT_STRADDLE_SECONDS = 1;

// one key per (speaker, line): every bot hearing the same party line
// computes the identical key, so the claim is per-LINE, not per-bot.
// Round-8 R1: the group id is deliberately NOT in the key. The drain
// can only key by the draining bot's CURRENT group - a listener kicked
// and re-invited to another group inside the window computed a FRESH
// key, claimed beside the original winner, and delivered a second
// generation to a group that never heard the line. A speaker stands in
// at most one group, so (speaker, hash) cannot collide across two live
// groups; the one cross-group shape (the speaker moves groups and
// repeats the identical text inside the window) now refuses the repeat
// - conservative, the same direction as the adjudicated party/raid
// shared-key residue, never a double generation.
uint64 PartyClaimKey(uint32 speakerGuid, uint64_t msgHash)
{
    return (static_cast<uint64>(speakerGuid) << 24) ^
        (msgHash * 0x9E3779B97F4A7C15ull) ^
        0x2545F4914F6CDD1Dull;
}

std::map<uint64, PartyResponderClaim>& PartyClaims()
{
    static std::map<uint64, PartyResponderClaim> instance;
    return instance;
}

// the rotation stamp (the anti-monopolization field SelectResponder
// consumes): bot guid -> ms-ordered mark of its last winning claim
std::map<uint32, uint64_t>& PartyLastWonMs()
{
    static std::map<uint32, uint64_t> instance;
    return instance;
}

// the flood gate's per-speaker admission stamp
std::map<uint32, time_t>& PartyFloodLastAt()
{
    static std::map<uint32, time_t> instance;
    return instance;
}

// A1's once-per-bot-per-session gate-refusal stamp (the dead-gate
// diagnostic): process-local by design - it dies with the world
// process, matching the quota-restart semantics
std::set<uint32>& GateRefusalNoted()
{
    static std::set<uint32> instance;
    return instance;
}

// Round-10 R1 (the first-heard registry): the LINE's earliest receive
// instant, min-stamped by every group member's receive handler (the
// handlers run sequentially on the world thread inside one broadcast,
// so the first writer IS the earliest receive; min() keeps the
// invariant total against any out-of-order path). This is the
// freshness authority both the claim grant and the stand-down marker
// consult: a token stamps/grants only inside window-margin of
// firstHeard, and every accepted token is stamped at >= its writer's
// receive >= firstHeard (so expiring at >= firstHeard+window) - a
// grant therefore can never meet an expired prior token, whatever
// the drain's mid-work clock divergence, and for any fan-out
// straddle inside the window (beyond it the line re-registers as
// fresh - the header's stated premise; round-11 R1 closed the one
// ungated stamper that could stamp BELOW firstHeard).
std::map<uint64, int64_t>& PartyLineHeardMap()
{
    static std::map<uint64, int64_t> instance;
    return instance;
}

// Round-12 R1 (the cross-line residue): true when the key's existing
// token belongs to the CURRENT registry generation (it owns the line);
// false when absent - or when it is residue of a PRIOR identical-text
// line whose registry entry was pruned and re-registered fresh, in
// which case the stale token is erased here (erase-and-replace). The
// discriminator is exact: the gates' >= refusal admits now-firstHeard
// only up to window-margin-1, so every accepted stamp sits in
// [firstHeard, firstHeard+window-margin-1] of its OWN generation
// (round-13 R8's wording note), so a current-
// generation token expires at >= firstHeard+window; the registry
// prunes only past the window and re-inserts fresh, so the new
// firstHeard strictly exceeds the prior generation's last possible
// stamp (+2) - a token expiring strictly before firstHeard+window was
// stamped before this generation began. R1's demonstrated attack (a
// verbatim repeat >=31 s later, its addressee-marker refused by the
// dead line's residue token, the residue dying inside the repeat's
// grant window beside the addressee's own dispatch) dies on this
// erase: the repeat's marker owns its own line. Round-13 R1: the
// scoping itself re-opened the OLD line for its own straggled
// drainers (the entry-side mirror of this check) - closed at the
// drain's TTL gate by PartyClaimGenerationMovedPast, which drops
// entries predating the current generation before any claim can
// consult this discriminator.
// Caller holds StateMutex.
bool TokenOwnsCurrentLine(uint64 key, int64_t firstHeard)
{
    std::map<uint64, PartyResponderClaim>& claims = PartyClaims();
    auto itr = claims.find(key);
    if (itr == claims.end())
        return false;                            // first writer
    if (itr->second.expiresAt <
        firstHeard + PARTY_CLAIM_WINDOW_SECONDS)
    {
        claims.erase(itr);                       // prior-line residue
        return false;
    }
    return true;                                 // a current token owns it
}

} // namespace

uint64_t PlayerbotLlmMemory::PartyMsgHash(std::string const& msg)
{
    // FNV-1a 64: no seeded state, stable across processes - the N-bot
    // fan-out must agree on the claim key for one line
    uint64_t h = 14695981039346656037ull;
    for (size_t i = 0; i < msg.size(); ++i)
    {
        h ^= (unsigned char)msg[i];
        h *= 1099511628211ull;
    }
    return h;
}

void PlayerbotLlmMemory::NotePartyLineHeard(uint32 speakerGuid,
    uint64_t msgHash)
{
    // Round-10 R1 (the mid-drain clock divergence): the drain reads
    // the clock at its TTL gate and AGAIN inside the claim/stand-down
    // helpers, with real work between (ChatReplyDo's scans and
    // CollectPartyCandidates' synchronous tier queries) - an entry
    // admitted at the gate's second could hit a claim prune one second
    // later where the line's marker/claim just died. The exactly-one
    // law is now anchored to the LINE, not any entry's m_time: every
    // member's receive min-stamps the line's earliest heard instant
    // here, and TryClaimPartyResponder grants only inside window-margin
    // of it - no marker/claim for the line can expire that early, so a
    // granted claim never races a dead token regardless of straddle or
    // divergence. Absent entry at claim time = stale (fail closed).
    if (!speakerGuid)
        return;
    int64_t const now = (int64_t)time(nullptr);
    uint64 const key = PartyClaimKey(speakerGuid, msgHash);
    std::lock_guard<std::mutex> lock(StateMutex());
    std::map<uint64, int64_t>& heard = PartyLineHeardMap();
    // Round-12 R1 (prune-before-insert): a receive PAST the window
    // must re-register its line deterministically - with the insert
    // first, the receive found the old entry (min-law: no update),
    // then the prune erased it and the receive's own instant was
    // LOST, leaving a lone-member repeat line with no registry (all
    // its claims fail-closed: a missed reply where a fresh
    // generation was owed). Prune first, then insert/min-stamp.
    for (auto pruneItr = heard.begin(); pruneItr != heard.end();)
    {
        if (now - pruneItr->second > PARTY_CLAIM_WINDOW_SECONDS)
            pruneItr = heard.erase(pruneItr);
        else
            ++pruneItr;
    }
    auto itr = heard.find(key);
    if (itr == heard.end())
        heard[key] = now;
    else if (now < itr->second)
        itr->second = now;
}

bool PlayerbotLlmMemory::PartyClaimGenerationMovedPast(uint32 speakerGuid,
    uint64_t msgHash, time_t lineTime)
{
    // Round-13 R1 (the old-line straggler re-open): the round-12
    // generation scoping made BOTH freshness gates and the residue
    // discriminator read the CURRENT registry generation - so a
    // TTL-live straggled entry of a PRIOR identical-text line (the
    // registry re-registered fresh at a verbatim repeat past the
    // window) passed them all: the gates measured its age against
    // the NEW firstHeard, and TokenOwnsCurrentLine erased the old
    // line's still-live winner token as residue - the straggler
    // claimed and dispatched a second generation for the OLD line
    // while the repeat's own responder was refused beside its token
    // (R1's probe: 84,825/84,825 combos). This is the drop side of
    // the same law: an entry whose m_time predates the CURRENT
    // generation's firstHeard cannot belong to it. firstHeard is
    // the MIN receive of the current generation and each member's
    // registry write follows its own queue push in the same receive
    // handler (sequential world-thread handlers: every later
    // member's push postdates the first write), so every
    // same-generation entry carries m_time >= firstHeard - except
    // one first-writer push/write second-boundary straddle
    // (m_time = firstHeard-1: one entry dropped, a missed reply,
    // never a second generation). Under the documented within-window
    // straddle premise every prior-generation entry carries
    // m_time <= fh_old+30 < fh_new (the registry prunes only past
    // the window and the flip therefore lands at >= fh_old+31) -
    // always dropped. Absent key = false: the registry holds only
    // armed-lane party/raid real-speaker lines, so every other lane
    // misses the lookup and stays byte-identical; the one
    // present-key cross-lane shape (the same speaker repeating
    // identical text on another channel - the round-8 shared-key
    // class) drops conservatively, the same direction as the
    // claim's own refusal there. Caller: the chat drain, which
    // holds chatRepliesMutex and already nests StateMutex through
    // ChatReplyDo's claim leg (StateMutex stays the leaf).
    if (!speakerGuid || lineTime == 0)
        return false;
    uint64 const key = PartyClaimKey(speakerGuid, msgHash);
    std::lock_guard<std::mutex> lock(StateMutex());
    std::map<uint64, int64_t> const& heard = PartyLineHeardMap();
    auto itr = heard.find(key);
    if (itr == heard.end())
        return false;
    return itr->second > (int64_t)lineTime;
}

bool PlayerbotLlmMemory::TryClaimPartyResponder(uint32 botGuid, uint32 speakerGuid,
    uint64_t msgHash)
{
    if (!botGuid || !speakerGuid)
        return false;
    int64_t const now = (int64_t)time(nullptr);
    uint64 const key = PartyClaimKey(speakerGuid, msgHash);
    std::lock_guard<std::mutex> lock(StateMutex());
    // prune expired claims first: the state stays bounded and a stale
    // claim can never block a later line. Round-6 R1: the window must
    // exceed every reachable chat-drain stagger - each bot drains its
    // chatReplies inside UpdateAIInternal, whose delay the engine
    // routinely sets to seconds (near/far teleport chains 3-7 s, cast
    // time + react + avg, the reactDelay*10 floor), so a 5 s window
    // expired BEFORE late bystanders evaluated the line and the
    // rotation stamp armed the next tie-order bot to re-claim it. 30 s
    // bounds every realistic drain while the lazy prune keeps the map
    // bounded.
    std::map<uint64, PartyResponderClaim>& claims = PartyClaims();
    for (auto itr = claims.begin(); itr != claims.end();)
    {
        if (itr->second.expiresAt <= now)
            itr = claims.erase(itr);
        else
            ++itr;
    }
    // Round-10 R1 (the claim-side freshness gate): grant only if the
    // LINE (not any entry's m_time) is young at the claim's own
    // clock. Every current-generation token is stamped at >= its
    // writer's receive >= firstHeard, so it expires at >=
    // firstHeard+window; a grant inside firstHeard+window-margin can
    // never meet an expired current-generation token, whatever the
    // fan-out straddle or the drain's mid-work clock divergence that
    // admit this drainer. Absent firstHeard = unprovable freshness =
    // refuse (a missed reply, never a second generation). The gate
    // runs BEFORE the ownership check: the round-12 residue
    // discriminator needs a live registry entry.
    std::map<uint64, int64_t> const& heard = PartyLineHeardMap();
    auto heardItr = heard.find(key);
    if (heardItr == heard.end() ||
        now - heardItr->second >=
            PARTY_CLAIM_WINDOW_SECONDS - PARTY_CLAIM_FANOUT_STRADDLE_SECONDS)
        return false;
    // Round-12 R1: first-writer-wins over the CURRENT generation only
    // (a claim OR a stand-down marker); residue of a prior
    // identical-text line is erased (TokenOwnsCurrentLine).
    if (TokenOwnsCurrentLine(key, heardItr->second))
        return false;
    PartyResponderClaim& claim = claims[key];
    claim.botGuid = botGuid;
    claim.expiresAt = now + PARTY_CLAIM_WINDOW_SECONDS;
    // the winner stamps the rotation map so the NEXT line's
    // SelectResponder prefers a different equal-tier bot
    PartyLastWonMs()[botGuid] = (uint64_t)now * 1000ull;
    return true;
}

bool PlayerbotLlmMemory::TryStandDownPartyLine(uint32 speakerGuid,
    uint64_t msgHash)
{
    // round-6 R1 (the addressed-line sibling): an ADDRESSED line stands
    // down with a MARKER, not just silence - a staggered late drain
    // re-evaluates the line after the addressee left the group
    // mid-fan-out (its own queued turn already dispatched), finds no
    // named member, and would otherwise take a fresh ordering pick
    // beside the addressee's turn. The marker (winner 0 = the
    // addressee's own arm owns the line) rides the same claim map,
    // window and lazy prune; first writer wins. Round-8 R1: the key is
    // GROUP-FREE (see PartyClaimKey) so a listener that switches groups
    // between receive and drain still finds the line owned.
    if (!speakerGuid)
        return false;
    int64_t const now = (int64_t)time(nullptr);
    uint64 const key = PartyClaimKey(speakerGuid, msgHash);
    std::lock_guard<std::mutex> lock(StateMutex());
    std::map<uint64, PartyResponderClaim>& claims = PartyClaims();
    for (auto itr = claims.begin(); itr != claims.end();)
    {
        if (itr->second.expiresAt <= now)
            itr = claims.erase(itr);
        else
            ++itr;
    }
    // Round-11 R1 (the ungated drain-side marker): this helper's
    // SECOND caller - the drain-side stand-down branch - runs under
    // NO isAiChat/strategy armament, so a strategy-less member (its
    // receive never wrote the first-heard registry) could stamp the
    // line's FIRST token at a clock read BELOW firstHeard while the
    // fan-out was still stalled behind mid-drain members: that
    // early-expiring marker then died INSIDE the claim grant range
    // and a fresh claim re-opened the line beside the original
    // winner. The marker now carries the SAME freshness gate as the
    // claim: absent registry = unprovable freshness = refuse (the
    // receive-path caller is never refused as absent or backward -
    // its own registry write precedes it in the same handler, so
    // firstHeard <= now; round-12 R8 wording: a STALE refusal there,
    // inside the stated straddle envelope, is conservative - a
    // missed marker never dispatches), and with the gate every
    // ACCEPTED token stamp >= firstHeard, the grant proof's premise
    // for both legs. Round-12 R1: the ownership check below is
    // generation-scoped (prior-line residue is erased).
    std::map<uint64, int64_t> const& heard = PartyLineHeardMap();
    auto heardItr = heard.find(key);
    if (heardItr == heard.end() ||
        now - heardItr->second >=
            PARTY_CLAIM_WINDOW_SECONDS - PARTY_CLAIM_FANOUT_STRADDLE_SECONDS)
        return false;
    if (TokenOwnsCurrentLine(key, heardItr->second))
        return false; // a current-generation claim or marker owns the line
    PartyResponderClaim& marker = claims[key];
    marker.botGuid = 0;
    marker.expiresAt = now + PARTY_CLAIM_WINDOW_SECONDS;
    return true;
}

bool PlayerbotLlmMemory::PartyClaimWindowElapsed(time_t lineTime)
{
    // Round-7 R1 (claim-window class closure): the drain staggers are
    // ADDITIVE (IncreaseAIInternalUpdateDelay accumulates: a master's
    // repeated "wait" adds up to 20 s per invocation, teleport and cast
    // chains stack on top), so NO fixed claim window can exceed every
    // reachable first drain. The window still bounds the claim map; the
    // line itself is bounded HERE instead. On the armed surface the
    // queue path is noDelay (the queued m_time IS the fan-out instant)
    // and the drain cannot run before m_time, so a line older than the
    // window at its drain has no live claim or marker left - the caller
    // drops it rather than let a deferred bot re-open the line beside
    // its original winner. Pure time compare: no state, no mutex.
    // Round-8 R1: the >= (not >) is LOAD-BEARING. The prune kills a
    // claim/marker at expiresAt <= now (it dies AT stamp+30), so a
    // strict-> oracle left one live-line/dead-claim SECOND (the
    // boundary second re-opened both legs). Round-9 R1: that invariant
    // was per-ENTRY, not per-LINE - a fan-out crossing a second
    // boundary gives a later member's entry its own later m_time
    // (T+1 beside a marker stamped at T), and that entry reached age
    // 29 - processed - exactly when the marker pruned; with the
    // winner gone a fresh ordering pick claimed beside the original
    // generation. The margin closes the demonstrated class (straddle
    // <= margin): a processed drainer then has now <= m_time +
    // window - margin - 1, and with m_time <= T + margin (T the
    // fan-out's earliest push) that is <= T + window - 1 < T + window
    // <= every claim/marker expiry (each stamps at >= T). Wider
    // straddles are possible (round-10 R1 MINOR: the fan-out's queue
    // push blocks on a mid-drain member's chatRepliesMutex, held
    // across its whole ChatReplyDo), and this entry-side envelope
    // then misses the drainer - a MISSED REPLY at worst, because the
    // round-10 first-heard registry gates the CLAIM itself to the
    // line's earliest receive (see NotePartyLineHeard): a second
    // generation stays unreachable at any straddle or drain clock
    // divergence.
    return lineTime != 0 &&
        time(nullptr) - lineTime >=
        PARTY_CLAIM_WINDOW_SECONDS - PARTY_CLAIM_FANOUT_STRADDLE_SECONDS;
}

bool PlayerbotLlmMemory::CollectPartyCandidates(uint32 groupId, uint32 speakerGuid,
    std::vector<PlayerbotLlmGates::ResponderCandidate>& out)
{
    out.clear();
    if (!groupId)
        return false;
    Group* group = sObjectMgr.GetGroupById(groupId);
    if (!group)
        return false;
    Player* speaker = speakerGuid
        ? sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, speakerGuid))
        : nullptr;
    // snapshot the rotation stamps under the lock, then resolve tiers
    // OUTSIDE it (GetTrainedTier hits the DB; StateMutex is never held
    // across a query)
    std::map<uint32, uint64_t> lastWon;
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        lastWon = PartyLastWonMs();
    }
    for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
    {
        Player* member = itr->getSource();
        if (!member || !member->GetPlayerbotAI() || !member->IsAlive())
            continue; // bots only, and a dead bot cannot answer
        PlayerbotLlmGates::ResponderCandidate c;
        c.guid = member->GetGUIDLow();
        c.tier = speaker ? GetTrainedTier(member, speaker) : 1;
        auto won = lastWon.find(c.guid);
        c.lastWonMs = won != lastWon.end() ? won->second : 0;
        out.push_back(c);
    }
    return !out.empty();
}

bool PlayerbotLlmMemory::PartyFloodAdmits(uint32 speakerGuid)
{
    if (!speakerGuid)
        return false;
    time_t const now = time(nullptr);
    std::lock_guard<std::mutex> lock(StateMutex());
    time_t& lastAt = PartyFloodLastAt()[speakerGuid];
    if (lastAt && now - lastAt < 2)
        return false; // N lines within 2 s = ONE generation
    lastAt = now;
    return true;
}

void PlayerbotLlmMemory::PartyFloodRefund(uint32 speakerGuid, time_t stampedAt)
{
    // Round-7 R1 MINOR: a claim the caller LOST consumed the speaker's
    // flood slot for nothing (identical-text re-send within the window,
    // or the adjudicated party/raid shared key) - without this refund
    // the speaker's next DISTINCT line inside 2 s was denied beside a
    // generation that never happened. CAS-shaped erase: only the
    // attempt that stamped the slot lifts it, so a concurrent winner's
    // stamp (set between this attempt's admit and its claim loss)
    // survives and keeps coalescing real generations.
    if (!speakerGuid || !stampedAt)
        return;
    std::lock_guard<std::mutex> lock(StateMutex());
    std::map<uint32, time_t>& stamps = PartyFloodLastAt();
    std::map<uint32, time_t>::iterator itr = stamps.find(speakerGuid);
    if (itr != stamps.end() && itr->second == stampedAt)
        stamps.erase(itr);
}

bool PlayerbotLlmMemory::NoteGateRefusalOnce(uint32 botGuid)
{
    // A1 (round-3 R1#2): the reply gate refused a hard trigger - the
    // dead-gate signature. Once per bot per session: the first refusal
    // logs, every later one stays quiet (a 320-bot realm never floods).
    if (!botGuid)
        return false;
    std::lock_guard<std::mutex> lock(StateMutex());
    std::set<uint32>& noted = GateRefusalNoted();
    if (noted.find(botGuid) != noted.end())
        return false;
    noted.insert(botGuid);
    return true;
}

std::vector<std::string> PlayerbotLlmMemory::RenderRecapDigest(Player* player)
{
    std::vector<std::string> lines;
    if (!player || !player->GetSession())
        return lines;

    // the offline floor: the player's last active moment across all their
    // pairings (read at login, before any interaction stamps it)
    time_t offlineFloor = 0;
    {
        auto result = CharacterDatabase.PQuery(
#ifdef DO_SQLITE
            "SELECT strftime('%%s', MAX(`last_interaction_at`)) FROM `bot_player_relationship` WHERE `player` = '%u'",
#else
            "SELECT UNIX_TIMESTAMP(MAX(`last_interaction_at`)) FROM `bot_player_relationship` WHERE `player` = '%u'",
#endif
            player->GetGUIDLow());
        if (result && !result->Fetch()[0].IsNULL())
            offlineFloor = static_cast<time_t>(result->Fetch()[0].GetUInt64());
    }
    time_t const now = time(nullptr);
    if (offlineFloor <= 0 || now - offlineFloor < 3600)
        return lines; // a quick relog is not a session return

    // ledger rows minted while the player was away (rare by nature: bots
    // mint on shared moments - these are the arrivals and retells that
    // named them)
    {
        auto result = CharacterDatabase.PQuery(
#ifdef DO_SQLITE
            "SELECT `fact_text` FROM `bot_player_facts` WHERE `player` = '%u' "
            "AND strftime('%%s', `created_at`) > '%u' ORDER BY `id` DESC LIMIT 3",
#else
            "SELECT `fact_text` FROM `bot_player_facts` WHERE `player` = '%u' "
            "AND `created_at` > FROM_UNIXTIME('%u') ORDER BY `id` DESC LIMIT 3",
#endif
            player->GetGUIDLow(), (uint32)offlineFloor);
        if (result)
        {
            do
            {
                std::string text = result->Fetch()[0].GetString();
                size_t const toneEnd = text.find(") ");
                if (text.rfind("(tone", 0) == 0 && toneEnd != std::string::npos)
                    text = text.substr(toneEnd + 2);
                if (!text.empty())
                    lines.push_back("The ledger grew: " + text + ".");
            } while (result->NextRow());
        }
    }

    // the legend traveled: town talk naming the player
    std::string const town = GossipAbout(player->GetName());
    if (!town.empty())
        lines.push_back("Word traveled while you were away: '" + town + "'.");

    // tenure milestones crossed (the top pairings only)
    {
        auto result = CharacterDatabase.PQuery(
            "SELECT `bot` FROM `bot_player_relationship` WHERE `player` = '%u' "
            "ORDER BY `points` DESC LIMIT 3",
            player->GetGUIDLow());
        if (result)
        {
            do
            {
                uint32 const bot = result->Fetch()[0].GetUInt32();
                int const days = PairingAgeDays(bot, player->GetGUIDLow());
                char const* const ann = pocketllm::AnniversaryLine(
                    pocketllm::AnniversaryBucket(days));
                if (ann && *ann)
                {
                    if (Player* botPlayer = sObjectAccessor.FindPlayer(
                            ObjectGuid(HIGHGUID_PLAYER, bot)))
                        lines.push_back(std::string("With ") +
                            botPlayer->GetName() + ": " + ann);
                }
            } while (result->NextRow());
        }
    }

    // the silence doctrine: a recap below three rows is noise
    if (lines.size() < 3)
        lines.clear();
    return lines;
}

std::vector<std::string> PlayerbotLlmMemory::SceneReadLines(Player* bot, Player* player)
{
    std::vector<std::string> lines;
    if (!bot || !player || !player->GetSession() || !player->isRealPlayer())
        return lines;
    if (!sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmSceneReadEnabled)
        return lines;

    std::string const zone = ZoneNameOf(player);
    bool const deep = player->GetMap() && player->GetMap()->IsDungeon();
    lines.push_back(std::string(deep ? "You are deep inside " : "You are in ") + zone + ".");

    if (player->IsInCombat())
        lines.push_back("You are in a fight right now.");
    else if (player->HasStealthAura())
        lines.push_back("You are moving unseen - your own footsteps sound loud to you.");

    // wounded party members (the two worst; a raid roster would be noise)
    if (Group* group = player->GetGroup())
    {
        std::multimap<uint32, std::string> wounded;
        for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
        {
            Player* member = itr->getSource();
            if (!member || member == player)
                continue;
            uint32 const hp = member->GetHealthPercent();
            if (hp > 0 && hp < 30)
                wounded.insert(std::make_pair(hp, member->GetName()));
        }
        size_t named = 0;
        for (auto const& wound : wounded)
        {
            if (named >= 2)
                break;
            lines.push_back(wound.second + " is badly hurt.");
            ++named;
        }
    }

    // hour and weather, read through the same accessors the W7a bias uses.
    // The player has no PlayerbotAI, so the zone id comes from the bot's
    // own AI (the bot is at the player's side by construction of the
    // whisper exchange)
    time_t nowT = time(nullptr);
    if (struct tm const* lt = localtime(&nowT))
        if (lt->tm_hour < 6 || lt->tm_hour >= 21)
            lines.push_back("Night lies on the land.");
    if (sPlayerbotAIConfig.llmWorldTruthAmbient && player->GetMap() &&
        player->GetMap()->GetWeatherSystem() &&
        bot->GetPlayerbotAI() && bot->GetPlayerbotAI()->GetCurrentZone())
    {
        if (Weather* weather = player->GetMap()->GetWeatherSystem()->FindWeather(
                bot->GetPlayerbotAI()->GetCurrentZone()->ID))
        {
            WeatherType const type = weather->GetWeatherType();
            if (weather->GetWeatherGrade() > 0.0f)
            {
                if (type == WEATHER_TYPE_RAIN)
                    lines.push_back("Rain falls here.");
                else if (type == WEATHER_TYPE_STORM)
                    lines.push_back("A storm is breaking over this place.");
                else if (type == WEATHER_TYPE_SNOW)
                    lines.push_back("Snow is falling.");
            }
        }
    }

    // the current live rumor naming the player (their traveling legend)
    std::string const town = GossipAbout(player->GetName());
    if (!town.empty())
        lines.push_back("Word on the street: '" + town + "'.");

    // one authored in-character nudge (the persona ring/tic laws hold)
    std::string const nudge = PlayerbotLlmPersona::SceneNudgeLine(bot, player);
    if (!nudge.empty())
        lines.push_back(nudge);
    return lines;
}

std::vector<std::string> PlayerbotLlmMemory::StoryLines(Player* bot, Player* player)
{
    std::vector<std::string> lines;
    if (!bot || !player || !player->isRealPlayer())
        return lines;
    // the saga rows: what the town holds about the player (the codex's
    // living archive - newest first, bounded)
    auto result = WorldDatabase.PQuery(
        "SELECT `text` FROM `world_gossip` WHERE (`expires_at` IS NULL OR `expires_at` > CURRENT_TIMESTAMP) "
        "AND `category` = 'saga' ORDER BY `id` DESC LIMIT 3");
    if (result)
    {
        do
        {
            std::string const row = result->Fetch()[0].GetString();
            if (pocketllm::ContainsWordExact(row, player->GetName()))
                lines.push_back("The fire remembers: " + row);
        } while (result->NextRow());
    }
    // the pairing's own milestone closes the page
    if (GetTrainedTier(bot, player) >= 3)
    {
        int const tier = GetTrainedTier(bot, player);
        lines.push_back(std::string("Bond: ") +
            pocketllm::TierBeatJournalLine(tier >= 5 ? 2 : 0));
    }
    return lines;
}

void PlayerbotLlmMemory::MintWeeklyDossier(Player* player)
{
    if (!player || !player->GetSession() || !player->isRealPlayer())
        return;
    if (!sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmDossierEnabled)
        return;
    time_t const now = time(nullptr);
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        auto itr = LastDossierAt().find(player->GetGUIDLow());
        if (itr != LastDossierAt().end() && now - itr->second < 7 * 86400)
            return;
    }

    // the deterministic half always mints: the newest remembered truth
    // about the player becomes the town's one-line word on them (the
    // gossip slice carries it into every bot's prompt)
    uint32 dossierBot = 0;
    std::string topFact;
    std::string topCategory = "shared-event";
    {
        // C3: the pick widens beyond shared-event to the full LogFact
        // whitelist; the per-category template supplies the subject.
        // C4 coordination: party-digest rows ("party talk: ...") are
        // EXCLUDED so a chatty group cannot crowd the weekly dossier.
        auto result = CharacterDatabase.PQuery(
            "SELECT `bot`, `fact_text`, `category` FROM `bot_player_facts` WHERE `player` = '%u' "
            "AND `category` IN ('shared-event', 'preference', 'opinion', 'player-identity') "
            "AND `fact_text` NOT LIKE 'party talk:%%' "
            "ORDER BY `id` DESC LIMIT 1",
            player->GetGUIDLow());
        if (result)
        {
            Field* row = result->Fetch();
            dossierBot = row[0].GetUInt32();
            std::string text = row[1].GetString();
            topCategory = row[2].GetString();
            size_t const toneEnd = text.find(") ");
            if (text.rfind("(tone", 0) == 0 && toneEnd != std::string::npos)
                text = text.substr(toneEnd + 2);
            topFact = text;
        }
    }
    if (!dossierBot || topFact.empty())
        return; // a fact-less week stays silent, the gate unstamped
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        LastDossierAt()[player->GetGUIDLow()] = now;
    }

    // C3: the row mints DE-FRAMED - a bare town-talk clause. The
    // per-category template supplies the grammatical subject (the
    // player's name: noun-phrase facts read whole in any frame, and the
    // name keeps GossipAbout's word-boundary match working). The
    // greeting rider and the murmur {E} templates own the single frame
    // - the old "The word on X: <fact>" row double-framed at both.
    // This lambda is copied into a detached thread - value captures ONLY
    std::string const dossierName = player->GetName();
    std::string const dossierClause =
        pocketllm::TownTalkClause(topCategory, dossierName, topFact);
    auto mintDeterministic = [dossierBot, dossierClause]()
    {
        ShareGossip(dossierBot, dossierClause, "dossier");
    };

    // the cloud half upgrades the wording once per week on the external
    // tier (one capped call; ANY failure falls back to the deterministic
    // row - fail-closed on wording, never on the row itself). C3 drops
    // the 7-day pairing-age gate: the day-one reword is wanted (the
    // weekly STAMP still bounds the cadence - one dossier per player
    // per 7 days regardless of lane)
    if (!ExternalApiTierActive() ||
        !CloudQuotaAdmits("dossier", sPlayerbotAIConfig.llmDossierPerDay))
    {
        mintDeterministic();
        return;
    }
    try
    {
    std::thread([mintDeterministic, fact = topFact, dossierBot, playerName = std::string(player->GetName())]() mutable
    {
        if (!ExternalApiTierActive())
        {
            mintDeterministic();
            return; // tier flipped while the login walked: the row stands
        }
        try
        {
            std::string const user = "One true thing is known about the adventurer " +
                playerName + ": " + fact +
                "\nWrite the single line the whole town says about them - under "
                "twenty words, plain speech, warm and a little nosy.";
            pocketllm::RequestSampling s;
            s.temperature = 1.0f;
            s.topP = 0.95f;
            s.maxTokens = 60;
            s.minP = 0.05f;
            s.providerSafe = true;
            std::string const body = pocketllm::BuildChatRequestBody(
                sPlayerbotAIConfig.llmApiModel,
                "You write one line of small-town talk about an adventurer in a "
                "fantasy world, based only on the given truth. No mechanics, no "
                "questions.",
                std::vector<pocketllm::HistoryTurn>(), user, s, true);
            std::string const http = PlayerbotLLMInterface::PostChatHttp(
                body, sPlayerbotAIConfig.llmGenerationTimeout, nullptr, nullptr);
            pocketllm::CompletionEnvelope envelope =
                pocketllm::ParseCompletionEnvelope(http);
            if (envelope.parsed && pocketllm::ContentUsable(envelope))
            {
                std::vector<std::string> const lines =
                    pocketllm::SplitNarratorBlock(envelope.content);
                // C3: the clamp + hygiene gate BEFORE the row exists -
                // a 60-word run-on or marker-bearing line never becomes
                // town talk (the old gate was only lines.size() == 1),
                // and the row must CARRY the player's name on a word
                // boundary - GossipAbout's match is the row's lifeblood
                if (lines.size() == 1 && pocketllm::TownTalkLineUsable(lines[0]) &&
                    pocketllm::ContainsWordExact(lines[0], playerName))
                {
                    ShareGossip(dossierBot, lines[0], "dossier");
                    return;
                }
            }
            mintDeterministic();
        }
        catch (...)
        {
            mintDeterministic();
        }
    }).detach();
    }
    catch (...)
    {
        mintDeterministic(); // spawn failed: the row still mints
    }
}

bool PlayerbotLlmMemory::PlayerHasAnyPairing(uint32 playerGuid)
{
    // plain existence read, valid on both SQL dialects; the
    // login/first-contact call sites are world-thread sync reads
    auto result = CharacterDatabase.PQuery(
        "SELECT 1 FROM `bot_player_relationship` WHERE `player` = '%u' LIMIT 1",
        playerGuid);
    return bool(result);
}

int PlayerbotLlmMemory::PairingAgeDays(uint32 bot, uint32 player)
{
    // tenure from the OLDEST fact row's created_at (both dialects read
    // unix seconds; the column defaults to CURRENT_TIMESTAMP on insert).
    // No facts yet = no tenure (-1, caller renders nothing).
    auto result = CharacterDatabase.PQuery(
#ifdef DO_SQLITE
        "SELECT strftime('%%s', MIN(`created_at`)) FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u'",
#else
        "SELECT UNIX_TIMESTAMP(MIN(`created_at`)) FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u'",
#endif
        bot, player);
    if (!result)
        return -1;
    Field* fields = result->Fetch();
    if (fields[0].IsNULL())
        return -1;
    time_t first = static_cast<time_t>(fields[0].GetUInt64());
    if (first <= 0)
        return -1;
    time_t const days = (time(nullptr) - first) / 86400;
    return days < 0 ? -1 : (int)days;
}

void PlayerbotLlmMemory::AcknowledgeWhisper(Player* bot, Player* player)
{
    if (!bot || !player || !player->isRealPlayer() || !sPlayerbotAIConfig.llmEnabled)
        return;
    if (!bot->IsInWorld() || !bot->IsAlive())
        return;
    uint64 const pairKey = (uint64(bot->GetGUIDLow()) << 32) | player->GetGUIDLow();
    time_t const now = time(nullptr);
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        time_t& last = AckTimestamps()[pairKey];
        if (now - last < 4)
            return;
        last = now;
    }
    // turn to face + one deterministic text emote ("X nods."), on its
    // own delivery path - no generation, no governor budget
    bot->SetFacingToObject(player);
    PlayerbotLlmTools::PlayTextEmote(bot, player,
        (bot->GetGUIDLow() & 1) ? "nod" : "wave");
}

void PlayerbotLlmMemory::NoteConversation()
{
    ConversationCounter().fetch_add(1, std::memory_order_relaxed);
}

uint64_t PlayerbotLlmMemory::ConversationCount()
{
    return ConversationCounter().load(std::memory_order_relaxed);
}

void PlayerbotLlmMemory::OnPlayerLogin(Player* player)
{
    if (!player || !sPlayerbotAIConfig.llmEnabled)
        return;
    // bots log in through the same Player paths - only real players see
    // the onboarding line
    if (player->GetPlayerbotAI() || !player->GetSession() || !player->isRealPlayer())
        return;
    {
        // once per character per world process: the anchor site also fires
        // on cross-map teleports (SendInitialPacketsAfterAddToMap), and the
        // DB check below is only worth paying once
        std::lock_guard<std::mutex> lock(StateMutex());
        if (!OnboardedPlayers().insert(player->GetGUIDLow()).second)
            return;
    }
    // Once per character - the line rides logins only while the player
    // has never contacted a bot (pure DB read; the first pairing silences
    // it forever). ASCII hyphen: the sys-line channel predates the clamp.
    if (PlayerHasAnyPairing(player->GetGUIDLow()))
    {
        // plan v5 C2: the returning player with pairings gets the session
        // recap instead - "the realm remembers between sessions" (digest
        // first, cloud prose when the tier + quota admit; both silence
        // below three rows by the doctrine)
        DeliverSessionRecap(player);
        // plan v5 C5: the weekly dossier mints alongside the recap (the
        // login is the natural weekly moment; both halves fail closed)
        MintWeeklyDossier(player);
        return;
    }
    ChatHandler(player->GetSession()).SendSysMessage(
        "The people of this realm will talk back - walk up and greet them by name.");
}

// plan v5 C2: the session recap delivery. The deterministic digest always
// renders (zero calls, works offline); the prose variant replaces it when
// the external tier is active, the prose toggle is on and the daily quota
// admits - one capped call, fail-closed to silence on any failure
void PlayerbotLlmMemory::DeliverSessionRecap(Player* player)
{
    if (!sPlayerbotAIConfig.llmEventReactionsEnabled || !sPlayerbotAIConfig.llmRecapEnabled)
        return;
    std::vector<std::string> const lines = RenderRecapDigest(player);
    if (lines.empty())
        return;

    bool const prose = ExternalApiTierActive() && sPlayerbotAIConfig.llmRecapProse &&
        CloudQuotaAdmits("recap-prose", sPlayerbotAIConfig.llmRecapProsePerDay);
    if (!prose)
    {
        ChatHandler(player->GetSession()).SendSysMessage("Previously, in your realm:");
        for (std::string const& line : lines)
            ChatHandler(player->GetSession()).SendSysMessage(line.c_str());
        return;
    }

    // the prose call: copied values only (no Player* crosses threads and
    // no chat packet is sent from a worker - the lines marshal through
    // the chatter queue and deliver on the world thread). ANY failure -
    // tier flip, dead endpoint, unusable or short output - falls back to
    // the deterministic digest lines (a dead endpoint must never cost the
    // player the digest too)
    uint32 const playerGuid = player->GetGUIDLow();
    std::vector<std::string> const digest = lines;
    auto deliverDigest = [playerGuid, digest]()
    {
        std::vector<std::string> out;
        out.push_back("Previously, in your realm:");
        for (std::string const& line : digest)
            out.push_back(line);
        PlayerbotLlmChatter::DeliverSysLines(playerGuid, out);
    };
    try
    {
        std::thread([playerGuid, digest, deliverDigest]()
        {
            if (!ExternalApiTierActive())
            {
                deliverDigest(); // the digest is free and deterministic
                return;
            }
            try
            {
                std::string user = "True things from the ledger:\n";
                for (std::string const& line : digest)
                    user += "- " + line + "\n";
                user += "Write the recap.";
                pocketllm::RequestSampling s;
                s.temperature = 0.9f;
                s.topP = 0.95f;
                s.maxTokens = 220;
                s.minP = 0.05f;
                s.providerSafe = true;
                std::string const body = pocketllm::BuildChatRequestBody(
                    sPlayerbotAIConfig.llmApiModel, pocketllm::RecapSystemPrompt(),
                    std::vector<pocketllm::HistoryTurn>(), user, s, true);
                std::string const http = PlayerbotLLMInterface::PostChatHttp(
                    body, sPlayerbotAIConfig.llmGenerationTimeout, nullptr, nullptr);
                pocketllm::CompletionEnvelope envelope =
                    pocketllm::ParseCompletionEnvelope(http);
                if (!envelope.parsed || !pocketllm::ContentUsable(envelope))
                {
                    deliverDigest(); // dead endpoint: the digest stands
                    return;
                }
                std::vector<std::string> const block =
                    pocketllm::SplitNarratorBlock(envelope.content);
                if (block.size() < 2)
                {
                    deliverDigest();
                    return;
                }
                PlayerbotLlmChatter::DeliverSysLines(playerGuid, block);
            }
            catch (...)
            {
                deliverDigest();
            }
        }).detach();
    }
    catch (...)
    {
        deliverDigest(); // thread spawn failed: deliver synchronously
    }
}

void PlayerbotLlmMemory::MaybeSessionStandingLine(Player* bot, Player* player,
    std::string const& preStompAbsence)
{
    // "standing" one-liner on the first whisper of a session (one voice
    // per pairing per world process). A first meeting has no standing worth
    // voicing - the welcome owns that moment.
    if (!bot || !player || !player->isRealPlayer() || !player->GetSession())
        return;
    if (preStompAbsence == "a first meeting")
        return;
    uint64 const pairKey = (uint64(bot->GetGUIDLow()) << 32) | player->GetGUIDLow();
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        if (!StandingVoicedPairs().insert(pairKey).second)
            return;
    }
    std::string const line = StandingLine(bot, player);
    if (!line.empty())
        ChatHandler(player->GetSession()).SendSysMessage(line.c_str());
}

std::string PlayerbotLlmMemory::AuthoredFirstContactWelcome(Player* bot, Player* player)
{
    // The player's first-ever bot contact is scripted - a reliable,
    // in-character first impression even on a cold model - and hints that
    // bots remember, which is true: the pairing's first-meeting fact
    // forms right here through the same native write the licensed
    // log_fact line persists through (a scripted voice cannot emit tool
    // markers; the memory law beats the voice law at the opening moment).
    if (!bot || !player || !player->isRealPlayer())
        return "";
    if (PlayerHasAnyPairing(player->GetGUIDLow()))
        return "";
    LogFact(bot->GetGUIDLow(), player->GetGUIDLow(),
        std::string("met ") + player->GetName() + " for the first time", "shared-event");
    std::ostringstream out;
    out << bot->GetName() << " looks you over. \"Well met, " << player->GetName()
        << ". Mind your manners around here and folk will remember you - I know I will.\"";
    return out.str();
}

std::string PlayerbotLlmMemory::StandingLine(Player* bot, Player* player)
{
    if (!bot || !player)
        return "";
    std::ostringstream out;
    out << bot->GetName() << " thinks of you as "
        << TierProse(pocketllm::TierStorageName(GetTrainedTier(bot, player))) << ".";
    return out.str();
}

std::vector<std::string> PlayerbotLlmMemory::GossipLines(Player* bot, Player* player)
{
    std::vector<std::string> lines;
    if (!bot || !player)
        return lines;
    // the same rows the greeting surfaces carry (write-time hygiene chain
    // already applied), rendered on demand for the "gossip" keyword
    std::string const town = GossipAbout(player->GetName());
    if (!town.empty())
        lines.push_back("Word around town: " + town);
    return lines;
}
