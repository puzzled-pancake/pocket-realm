#include "PlayerbotLlmMemory.h"

#include "PlayerbotLlmBridge.h"
#include "PlayerbotLlmChatter.h"
#include "PlayerbotLlmPersona.h"
#include "PlayerbotLlmPrompt.h"
#include "PlayerbotLlmRecallCore.h"
#include "PlayerbotLlmTools.h"
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

void AppendHistoryTurn(uint32 bot, uint32 playerOrChannel,
    std::string const& speaker, std::string const& line)
{
    RollingHistory& history = History();
    std::lock_guard<std::mutex> lock(history.mutex);
    std::deque<HistoryLine>& turns = history.turns[HistoryKey(bot, playerOrChannel)];
    // bounded per turn as well: 20 unbounded lines could crowd out the
    // stable segments in the prompt budget below
    turns.push_back(HistoryLine(speaker, TruncUtf8(line, 240)));
    // tail-capped: only the oldest rolling turns ever drop, never the stable
    // prompt segments (which never live here)
    while (turns.size() > 20)
        turns.pop_front();
}

// Prompt-framing control tokens must never persist into memory text: a
// fact or gossip row carrying "[BRIDGE AI] ..." (model-copied from player
// input) would render inside the SYSTEM facts segment later - it cannot
// forge tool syntax (markers die in the neuter), but it can steer tone in
// a slot the model was trained to treat as instruction-bearing.

// verified-event window gating share_gossip. Writers include map-thread
// core hooks (GiveLevel), so every access is guarded.
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

// gossip slice cache: pinning the slice per bot keeps the prompt segment
// byte-stable between turns (world-pool churn only re-reads after expiry)
std::map<uint32, std::pair<time_t, std::string>>& GossipCache()
{
    static std::map<uint32, std::pair<time_t, std::string>> instance;
    return instance;
}

// S9/E1: per-pairing acknowledgment rate limiter (the never-cleaned
// GUID-statics class, bounded by pairing populations - S6-ledger (d))
std::map<uint64, time_t>& AckTimestamps()
{
    static std::map<uint64, time_t> instance;
    return instance;
}

// S9/E2: the onboarding line's once-per-character dedupe.
// SendInitialPacketsAfterAddToMap runs at login AND on every cross-map far
// teleport (MoveWorldportAck) - the DB no-pairing gate alone would re-voice
// the line at every portal. One world-process voice per character GUID
// (bounded by character population, the accepted statics class).
std::set<uint32>& OnboardedPlayers()
{
    static std::set<uint32> instance;
    return instance;
}

// S9/E4: the standing one-liner's once-per-session dedupe (one world-process
// voice per pairing - "first whisper of a session")
std::set<uint64>& StandingVoicedPairs()
{
    static std::set<uint64> instance;
    return instance;
}

// S9/E4: player-facing conversations counter (diagnostics; relaxed - a
// monotonic count with no ordering requirement)
std::atomic<uint64_t>& ConversationCounter()
{
    static std::atomic<uint64_t> instance(0);
    return instance;
}

} // namespace

std::string PlayerbotLlmMemory::ScrubControlTokens(std::string const& text)
{
    static char const* const tokens[] = {
        "[BRIDGE AI]", "[EVENT]", "[RESULT]", "[say]", "[Memories]", "[State]",
    };
    std::string out = text;
    // FIXPOINT pass (round-2 R6): deleting one token can FUSE the
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

void PlayerbotLlmMemory::RecordBotLine(Player* bot, uint32 msgtype, std::string const& message,
    std::string const& chanName, std::string const& name)
{
    if (!sPlayerbotAIConfig.llmEnabled)
        return;

    // mirrors the context-key scheme of ChatReplyDo: whisper history is
    // per (bot, player), everything else is shared per (bot, channel)
    ChatChannelSource source = bot->GetPlayerbotAI()->GetChatChannelSource(bot, msgtype, chanName);
    if (source == ChatChannelSource::SRC_WHISPER)
    {
        if (Player* player = sObjectAccessor.FindPlayerByName(name.c_str()))
            AppendTurn(bot->GetGUIDLow(), player->GetGUIDLow(), false, bot->GetName(), message);
        return;
    }

    AppendTurn(bot->GetGUIDLow(), 0x80000000u | static_cast<uint32>(source), true, bot->GetName(), message);
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

    // segment 2: relationship tier in the TRAINED dialect (A5: "Relationship
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
        reserve += 256; // S5/A1: the bridge note block appended to <post prompt>
    }
    auto fits = [&](size_t cost) { return !window || used + reserve + cost <= window; };

    // segment 3: injected facts, append-only, category-prioritized, stable
    // order - TRAINED dialect (A5): inline, "; "-joined, oldest-first
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

    // the PRE-STOMP tier threaded from ChatReplyDo (the S5 law: a fresh
    // read races the async relationship write; the caller captured this
    // before the stomp queued). A16's Bonded address shift rides the
    // tierNote leg (the banklib sysm field exists for exactly this):
    // warmth changes what the bot DOES - a Bonded bot calls the player
    // by its private name.
    int const tier = preStompTier;
    if (tier >= 5 && player)
        persona.tierNote = pocketllm::NicknameTierNote(player->GetName(), botGuid);

    // ---- facts: newest-first fetch, oldest-first stable render, tier-capped
    // (§2.1 memory depth); the [Memories] tail re-surfaces the newest rows -
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
            do
            {
                std::string text = result->Fetch()[0].GetString();
                // tone rows render WITHOUT the machine prefix (round-1 R6:
                // a "(tone -)" prefix in the system facts segment is an
                // echoable non-word); the journal strip, generalized
                size_t const toneEnd = text.find(") ");
                if (text.rfind("(tone", 0) == 0 && toneEnd != std::string::npos)
                    text = text.substr(toneEnd + 2);
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
    // (it IS this request's user message); last 8 turns - except the
    // ambient SAY channel, which A18 caps at the last 5 lines (the
    // cross-injection window: a town scene, not a transcript). The
    // per-key rotation counter (state flavors) advances once per REQUEST
    // so it alternates regardless of window saturation or turn parity.
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
            size_t const cap =
                (playerOrChannel == (0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY)))
                    ? 5 : 8;
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
        // invented flavors (round-1 R6 P1). Rotation comes from the
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

    // S5/A1: the caller captured the bucket BEFORE the relationship
    // stomp (the async write would race a fresh read) - one read serves
    // both the absence line and the bridge's first-meeting beat. The
    // former live-read fallback was a trap for callers (the S5 record's
    // "assert or remove"): the contract is now explicit - an empty
    // bucket is a caller bug and degrades to the first-meeting reading,
    // never to a racing read.
    std::string const absenceBucket = preStompAbsence.empty()
        ? std::string("a first meeting") : preStompAbsence;
    std::string const sysm = pocketllm::SysmForCard(persona, promptPlayer, tier,
        AbsenceLineFor(player->GetName(), absenceBucket), facts);

    // A1: the bridge owns the turn. Event reactions render through the
    // trained [EVENT] head + speak-first directive (no player words this
    // turn); conversational turns carry at most ONE note (ONE-NOTE law).
    // The history already holds the RAW event text (AppendTurn recorded it
    // before this call) - it stays there as a prior turn, while THIS
    // request renders it as the [EVENT] head of the current turn only.
    // The event flag is the drain flag threaded from the event site -
    // never derived from the text (the "(event) " prefix was player-
    // forgeable and is retired as a signal).
    // the nudge strip is event-only (round-1 P2): a player whisper that
    // happens to end in the nudge-shaped suffix keeps its actual words.
    // The CURRENT turn is scrubbed of prompt furniture before compose
    // (round-1 R6): a player line carrying "[RESULT] ..." or
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
    // into the generation (A2 stamp threading)
    if (licenseStamp)
        *licenseStamp = note.stamp;
    std::string noteExtra = note.extra;
    if (turnState.eventTurn && noteExtra.empty())
        noteExtra = PlayerbotLlmBridge::SpeakFirst();
    std::vector<std::string> events;
    if (turnState.eventTurn)
        events.push_back(turnText);
    // A11: the lore loop's card rides the turn head as [RESULT] (the
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

    // M1a prompt-dump hook: one JSON line per trained-format generation so
    // the byte-diff contract can be verified on-device against the same
    // vectors the host battery uses. The line is assembled whole and handed
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

    return pocketllm::BuildChatRequestBody(sPlayerbotAIConfig.llmApiModel, sysm,
        history, user, sampling);
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
        // or the 10/30/60 boundaries lag one interaction behind (F28/F43).
        // The stored enum stays the schema's four values - the trained
        // scale's fifth step (Bonded) derives from the point total on READ
        // (GetTrainedTier), so no migration is needed and the CHECK/enum
        // constraints can never reject a write.
        "INSERT INTO `bot_player_relationship` (`bot`, `player`, `tier`, `points`, `last_interaction_at`) VALUES ('%u', '%u', 'stranger', %d, CURRENT_TIMESTAMP) "
        "ON CONFLICT(`bot`, `player`) DO UPDATE SET `points` = `points` + excluded.`points`, "
        "`tier` = CASE WHEN `points` + excluded.`points` >= 60 THEN 'trusted' "
        "WHEN `points` + excluded.`points` >= 30 THEN 'ally' "
        "WHEN `points` + excluded.`points` >= 10 THEN 'acquaintance' ELSE 'stranger' END, "
        "`last_interaction_at` = CURRENT_TIMESTAMP",
#else
        "INSERT INTO `bot_player_relationship` (`bot`, `player`, `tier`, `points`, `last_interaction_at`) VALUES ('%u', '%u', 'stranger', '%d', CURRENT_TIMESTAMP) "
        "ON DUPLICATE KEY UPDATE `points` = `points` + '%d', `tier` = IF(`points` >= 60, 'trusted', "
        "IF(`points` >= 30, 'ally', IF(`points` >= 10, 'acquaintance', 'stranger'))), "
        "`last_interaction_at` = CURRENT_TIMESTAMP",
#endif
        // Both branches share this 4-arg call site; the DO_SQLITE template
        // has one conversion fewer (3 vs 4 - the MySQL ODKU's second %d).
        // Extra vsnprintf args are ignored by definition; do NOT "fix" by
        // branching the call site.
        bot->GetGUIDLow(), player->GetGUIDLow(), points, points);
}

void PlayerbotLlmMemory::AddBoundedSentimentInput(uint32 bot, uint32 player, int32 clampedDelta,
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
            return; // rate-limited: one bounded input per (bot, player) per minute
        rate[key] = now;
    }

    if (Player* botPlayer = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, bot)))
        if (Player* target = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, player)))
        {
            AddRelationshipPoints(botPlayer, target, clampedDelta);
            if (!reason.empty())
            {
                // S8/A13: the tone prefix carries the bridge-decided SIGN
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
}

std::string PlayerbotLlmMemory::GetAbsenceBucket(Player* bot, Player* player)
{
    auto result = CharacterDatabase.PQuery(
#ifdef DO_SQLITE
        // %%s: PExecute printf-formats the template; the SQL the engine
        // sees is strftime('%s', ...). Epoch is UTC on SQLite vs MySQL's
        // session tz (the P3 tz footnote) - values are engine-internal and
        // the P5 export bridge normalizes to UTC.
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
    // ONE query serves both S8 reads (absence bucket + the tier the A16
    // ceremony observes), taken before the relationship stomp queues -
    // a fresh read after the push races the async write (the S5 law).
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
    // not lock the real secret out (the round-1 R6 forgery vector).
    std::string const safe = EscapeSql(prefix);
    auto result = CharacterDatabase.PQuery(
        "SELECT 1 FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
        "AND `category` = 'player-identity' AND `fact_text` LIKE '%s%%' LIMIT 1",
        bot, player, safe.c_str());
    return !!result;
}

std::string PlayerbotLlmMemory::GossipAbout(std::string const& playerName)
{
    // A19 delivery priority: the newest rows matched in code (no LIKE -
    // player names are arbitrary strings and %/_ would be wildcards),
    // CASE-SENSITIVELY on word boundaries: the capitalization is what
    // makes a token a name ("Ash" is not "the ash of the fire" and not
    // "Ashmar" - the round-1 R6 misattribution fix). The world pool is
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
        // every tone shape strips ("(tone) " legacy rows, the S8 signed
        // "(tone -)/(tone +)" rows): the journal is a player-read surface
        size_t const toneEnd = text.find(") ");
        if (text.rfind("(tone", 0) == 0 && toneEnd != std::string::npos)
            text = text.substr(toneEnd + 2);
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
    return lines;
}

void PlayerbotLlmMemory::ShareGossip(uint32 bot, std::string const& text, std::string const& category)
{
    WorldDatabase.PExecute(
#ifdef DO_SQLITE
        // datetime('now', ...) is UTC; MySQL's NOW() reads the session tz
        // (the P3 tz footnote) - each engine is internally consistent
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

    std::ostringstream out;
    // the event reaction's nature rides the drain's isEventTurn flag +
    // event kind; the text itself is plain narration (the former
    // "(event) " prefix was only ever a string signal for what the flag
    // now carries)
    out << player->GetName() << " just reached level " << newLevel
        << " while fighting at our side.";
    QueueForPartyBots(player, out.str(), 2, PlayerbotLlmBridge::EVENT_LEVEL_UP);
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

    // S10/E6 party-banter event note: a duel by/against a real player
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

    // S8/A13+A19: the outcome becomes MEMORY - the bot's own fact (the
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

// ---- M6 authored kill banter (rare by design) ------------------------------
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

bool PlayerbotLlmMemory::TryClaimAmbientSlot(uint32 botGuid, uint32 minIntervalSeconds)
{
    static std::map<uint32, time_t> spokenAt;
    std::lock_guard<std::mutex> lock(StateMutex());
    time_t const now = time(nullptr);
    auto itr = spokenAt.find(botGuid);
    if (itr != spokenAt.end() && now - itr->second < time_t(minIntervalSeconds))
        return false;
    spokenAt[botGuid] = now;
    return true;
}

// ---- S8/A17: the initiative scheduler --------------------------------------
namespace {

// per-bot cadence: initiative classes scan at most this often
uint32 const INITIATIVE_SCAN_SECS = 20;
// the plan's zero-spam gate: one bot-initiated line per bot per 10 min
uint32 const INITIATIVE_MIN_INTERVAL = 600;
// a player counts as RETURNING after this long out of the bot's range
time_t const INITIATIVE_RETURN_GAP = 900;
// the A18 crowd tier: staggered emote delay bounds (seconds)
uint32 const CROWD_DELAY_MIN = 2, CROWD_DELAY_MAX = 5;

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

// the A18 crowd-tier throttle: at most a couple of emotes per short
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
    // A13: the absence beat carries MAGNITUDE, never a passive line
    std::string const magnitude = pocketllm::AbsenceMagnitudeLine(absenceBucket);
    if (!magnitude.empty())
        line += " " + magnitude;
    // A19: what the town says about the player rides the greeting (the
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
    if (!TryClaimAmbientSlot(bot->GetGUIDLow(), INITIATIVE_MIN_INTERVAL))
        return false;
    // stamp only on a confirmed emote (round-1 R5/R6: a rejected claim
    // must not burn the world window in silence - the kill-banter law)
    {
        std::lock_guard<std::mutex> lock(StateMutex());
        LastCrowdEmoteAt() = time(nullptr);
    }

    // A18: deterministic text emotes only - the crowd tier never pays a
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
    // long gap earns the greet-first packet (the validated finding: bots
    // must speak first). "Remembered" = a relationship row exists.
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
        // eligibility FIRST, slot claim LAST (round-1 R1/R5: a stranger's
        // arrival or an empty greeting draw must not burn the bot's whole
        // 10-minute initiative budget in silence)
        std::string const absence = GetAbsenceBucket(bot, player);
        if (absence == "a first meeting")
            continue; // never met: no greeting memory to draw on
        std::string const line = AuthoredArrivalGreeting(bot, player, absence);
        if (line.empty())
            continue;
        if (!TryClaimAmbientSlot(bot->GetGUIDLow(), INITIATIVE_MIN_INTERVAL))
            return; // the zero-spam cap outranks every class
        bot->Say(line, LANG_UNIVERSAL);
        AppendTurn(bot->GetGUIDLow(),
            0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY), true,
            bot->GetName(), line);
        // A18: a player walking up on two bots may catch them mid-talk -
        // a rare authored exchange, staggered so it reads as two voices.
        // One fact-free opener set only (weather, blades, roads): an
        // authored line must never fabricate ledger state. The reply
        // matches its OWN opener (round-1 R1) and answers on /say, the
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
            "SELECT `id`, `fact_text`, `category` FROM `bot_player_facts` WHERE `bot` = '%u' AND `player` = '%u' "
            "ORDER BY `id` DESC LIMIT 6",
            bot->GetGUIDLow(), player->GetGUIDLow());
        if (!result)
            continue;
        std::vector<std::pair<uint32, std::pair<std::string, std::string>>> rows;
        do
        {
            Field* fields = result->Fetch();
            rows.push_back(std::make_pair(fields[0].GetUInt32(),
                std::make_pair(fields[1].GetString(), fields[2].GetString())));
        } while (result->NextRow());

        std::string line;
        uint32 usedFactId = 0;
        // one tier read per player per scan (round-1 R5: the per-row
        // GetTrainedTier re-query was the one wasteful shape in the scan)
        int const playerTier = GetTrainedTier(bot, player);
        for (auto const& row : rows)
        {
            std::string const& text = row.second.first;
            if (text.rfind("(tone", 0) == 0)
                continue;
            int const cls = pocketllm::FactClassOf(text, row.second.second);
            if (cls == pocketllm::FACT_DEBT)
            {
                line = pocketllm::DebtReminderLine(player->GetName(),
                    pocketllm::MoneyPhrase(text));
                usedFactId = row.first;
                break;
            }
            if (cls == pocketllm::FACT_GOAL && playerTier >= 3)
            {
                line = pocketllm::GoalAskAfterLine(player->GetName(), text);
                usedFactId = row.first;
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
        if (!TryClaimAmbientSlot(bot->GetGUIDLow(), INITIATIVE_MIN_INTERVAL))
            return; // the zero-spam cap outranks every class
        {
            std::lock_guard<std::mutex> lock(StateMutex());
            InitiatedFactIds()[key].insert(usedFactId);
        }
        bot->Say(line, LANG_UNIVERSAL);
        AppendTurn(bot->GetGUIDLow(),
            0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY), true,
            bot->GetName(), line);
        return; // one initiative per scan at most
    }
}

void PlayerbotLlmMemory::OnPlayerGroupKill(Player* tapper, Unit* victim)
{
    if (!tapper || !victim || !sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmBanterEnabled)
        return;
    // real players only; no PvP quips (the banter corpus is PvE-voiced)
    if (!tapper->isRealPlayer() || victim->GetTypeId() == TYPEID_PLAYER)
        return;

    if (urand(1, KILL_BANTER_ROLL_N) != 1)
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

    // one bot speaks; its shared ambient slot bounds total chatter
    Player* chosen = bots[urand(0, uint32(bots.size() - 1))];
    if (!TryClaimAmbientSlot(chosen->GetGUIDLow(), AMBIENT_MIN_INTERVAL))
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
    // S8/A18 pacing: a staggered reaction waits its turn (front of the
    // queue - later reactions never jump ahead of it)
    if (itr->second.front().notBefore > time(nullptr))
        return false;
    reaction = itr->second.front();
    itr->second.pop_front();
    return true;
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


// ---- S9: the player surface (E1 pacing, E2 first contact, E4 progression)

bool PlayerbotLlmMemory::PlayerHasAnyPairing(uint32 playerGuid)
{
    // plain existence read, valid on both SQL dialects (the S7 one-time-set
    // class); the login/first-contact call sites are world-thread sync
    // reads, the accepted TickInitiative class
    auto result = CharacterDatabase.PQuery(
        "SELECT 1 FROM `bot_player_relationship` WHERE `player` = '%u' LIMIT 1",
        playerGuid);
    return bool(result);
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
    // E1: turn to face + one deterministic text emote ("X nods."), through
    // A3's own delivery path - no generation, no governor budget
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
    // E2: once per character - the line rides logins only while the player
    // has never contacted a bot (pure DB read; the first pairing silences
    // it forever). ASCII hyphen: the sys-line channel predates the clamp.
    if (PlayerHasAnyPairing(player->GetGUIDLow()))
        return;
    ChatHandler(player->GetSession()).SendSysMessage(
        "The people of this realm will talk back - walk up and greet them by name.");
}

void PlayerbotLlmMemory::MaybeSessionStandingLine(Player* bot, Player* player,
    std::string const& preStompAbsence)
{
    // E4: "standing" one-liner on the first whisper of a session (one voice
    // per pairing per world process). A first meeting has no standing worth
    // voicing - the E2 welcome owns that moment.
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
    // E2: the player's first-ever bot contact is scripted - a reliable,
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
