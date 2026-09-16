#include "PlayerbotLlmBridge.h"

#include "PlayerbotLlmMemory.h"
#include "PlayerbotLlmPrompt.h"
#include "PlayerbotLlmRecallCore.h"
#include "PlayerbotLlmToolsCore.h"
#include "llm_banter_core.h"   // MOOD_* (ceremony mood nudges)
#include "playerbot/playerbot.h"
#include "playerbot/PlayerbotAIConfig.h"
#include "Chat/Chat.h"
#include "Entities/Player.h"
#include "Entities/Creature.h"
#include "Entities/GameObject.h"
#include "Entities/ItemPrototype.h"
#include "Globals/ObjectAccessor.h"
#include "Globals/ObjectMgr.h"
#include "Quests/QuestDef.h"
#include "Server/DBCStores.h"
#include "Server/SQLStorages.h"

#include <atomic>
#include <cctype>
#include <cstring>
#include <map>
#include <mutex>
#include <set>

namespace {

// era-appropriate, deliberately tiny trigger lists: the bridge DECIDES,
// so a false positive voices an off beat while a false negative is only a
// missed one - precision beats recall at this layer (the recall levers
// are the beat table and the tuned weights, not longer lists)
char const* const kInsultTriggers[] = {
    "trash", "useless", "stupid", "idiot", "worthless", "garbage",
    "fool", "clumsy", "drunk gnome", "pig iron",
};
char const* const kGratitudeTriggers[] = {
    "thank", "well done", "good work", "good job", "that's kind",
    "here, take this", "for you", "gladly accept", "much obliged",
};
char const* const kLaughterTriggers[] = {
    "haha", "hehe", "that's funny", "good one", "you actually did it",
};
char const* const kNewsTriggers[] = {
    "any news", "what happened", "news from", "heard anything", "heard about",
};

// Negation-aware trigger scan: "don't thank me",
// "that's not funny", "never follow me" must not fire their beats.
bool ContainsAny(std::string const& text, char const* const* list, size_t count)
{
    std::string lower;
    lower.reserve(text.size());
    for (char c : text)
        lower.push_back((char)std::tolower((unsigned char)c));
    return pocketllm::EarliestHit(lower, list, count) != std::string::npos;
}

// ---- the lore card index (loaded once; empty key = loop off)
pocketllm::LoreIndex const* LoadedLore()
{
    static std::mutex mutex;
    static pocketllm::LoreIndex* index = nullptr;
    static bool tried = false;
    std::lock_guard<std::mutex> lock(mutex);
    if (!tried)
    {
        tried = true;
        if (!sPlayerbotAIConfig.llmLoreFile.empty())
        {
            pocketllm::LoreIndex* fresh = new pocketllm::LoreIndex();
            if (fresh->Load(sPlayerbotAIConfig.llmLoreFile))
            {
                // the load-time era lint drops contaminated cards (they are
                // [RESULT] truth and bypass every other filter); the count
                // is the operator's signal to re-author the file
                if (fresh->EraLintDropped())
                    sLog.outError("BotLLM: era lint dropped %u lore card(s) from %s - re-author the file",
                        (uint32)fresh->EraLintDropped(), sPlayerbotAIConfig.llmLoreFile.c_str());
                index = fresh;
            }
            else
            {
                // distinguish a missing/unreadable file from one the era
                // lint emptied entirely - the latter is an authoring bug,
                // not a quiet configuration
                if (fresh->EraLintDropped())
                    sLog.outError("BotLLM: era lint dropped ALL %u lore card(s) from %s - re-author the file",
                        (uint32)fresh->EraLintDropped(), sPlayerbotAIConfig.llmLoreFile.c_str());
                delete fresh; // unusable file: the loop stays quiet
            }
        }
    }
    return index;
}

// lowers and files one raw name into the shared known-name set (the
// set reference arrives from the caller so first-build recursion is
// impossible; the build itself runs under the set's own mutex)
void AddLoweredName(std::set<std::string>& names, std::string const& raw)
{
    if (raw.empty())
        return;
    std::string lower;
    lower.reserve(raw.size());
    for (char c : raw)
        lower.push_back((char)std::tolower((unsigned char)c));
    names.insert(lower);
}

// The known-name set, built once from the in-memory template caches
// (creatures, quests, items) plus the DBC area table. Names arrive from
// the pure extractor as alpha+space phrases. The build is mutex-guarded:
// IsKnownName also runs on the async generation threads (the invention
// post-filter), not just the world thread.
std::set<std::string> const& KnownTemplateNames()
{
    static std::mutex mutex;
    static std::set<std::string> names;
    static bool built = false;
    std::lock_guard<std::mutex> lock(mutex);
    if (!built)
    {
        for (auto itr = sCreatureStorage.getDataBegin<CreatureInfo>();
             itr < sCreatureStorage.getDataEnd<CreatureInfo>(); ++itr)
            AddLoweredName(names, itr->Name);
        for (auto const& pair : sObjectMgr.GetQuestTemplates())
            AddLoweredName(names, pair.second->GetTitle());
        for (auto itr = sItemStorage.getDataBegin<ItemPrototype>();
             itr < sItemStorage.getDataEnd<ItemPrototype>(); ++itr)
            AddLoweredName(names, itr->Name1);
        for (uint32 id = 0; id < sAreaStore.GetNumRows(); ++id)
            if (AreaTableEntry const* area = sAreaStore.LookupEntry(id))
                if (area->area_name[0])
                    AddLoweredName(names, area->area_name[0]);
        // gameobjects round out the set: their storage is a hash map,
        // but the base-class data iterator walks it fine - folding them
        // here removes the only live DB query from name resolution
        // (repeated unindexed gameobject_template scans ran per question
        // turn, including from async threads)
        for (auto itr = sGOStorage.getDataBegin<GameObjectInfo>();
             itr < sGOStorage.getDataEnd<GameObjectInfo>(); ++itr)
            AddLoweredName(names, itr->name);
        built = true;
    }
    return names;
}

// Known-entity resolution (the shared test behind BuildNoteInner's
// guard and the invention post-filter): the candidate is known when
// the world DB knows it (creatures, quests, items, gameobjects, areas),
// a lore card keys it, or an online player/bot carries it. Caller-side
// caps bound the cost (the gameobject probe is the one live query).
bool IsKnownEntity(Player* bot, Player* player, std::string const& name)
{
    if (name.empty())
        return true; // nothing to guard on
    std::string lower;
    lower.reserve(name.size());
    for (char c : name)
        lower.push_back((char)std::tolower((unsigned char)c));
    if (bot && lower == pocketllm::LowerAscii(bot->GetName()))
        return true;
    if (player && lower == pocketllm::LowerAscii(player->GetName()))
        return true;
    return PlayerbotLlmBridge::IsKnownName(name);
}

// ---- the per-generation license store (executor cross-check)
std::mutex g_licenseMutex;
std::map<uint32, PlayerbotLlmBridge::ToolLicense>& Licenses()
{
    static std::map<uint32, PlayerbotLlmBridge::ToolLicense> instance;
    return instance;
}
std::atomic<uint64_t> g_licenseStamp(0);

// ---- beat bookkeeping (in-memory, mutex-guarded; world-thread
// note builds are the only writers):
// the last tier whose transition the bridge already voiced a ceremony
// for (per pairing). A fresh process seeds silently - a ceremony must
// never fire on stale state, only on an observed transition.
std::mutex g_beatMutex;
std::map<uint64_t, int>& LastVoicedTier()
{
    static std::map<uint64_t, int> instance;
    return instance;
}
// C5: the per-player ceremony-rider coalescer - a simultaneous
// multi-bot crossing yields <= 1 PROSE rider per player per hour (the
// sys line and the mood nudge stay per-crossing: they are the cheap,
// non-generative halves)
std::map<uint32_t, time_t>& CeremonyRiderAt()
{
    static std::map<uint32_t, time_t> instance;
    return instance;
}
// the last greeting-gap beat per pairing: the weave fires once per
// absence gap (never twice inside one sitting).
std::map<uint64_t, time_t>& GreetedGapAt()
{
    static std::map<uint64_t, time_t> instance;
    return instance;
}

uint64_t PairKey(uint32 botGuid, uint32 playerGuid)
{
    return (static_cast<uint64_t>(botGuid) << 32) | playerGuid;
}

// 0 when no ceremony is due; +/- the number of crossed steps otherwise
// (always +/-1 in practice - points move in single stomps). Consume
// updates the map, so a RESTARTED process re-seeds without a ceremony;
// Peek observes WITHOUT consuming (ACT turns defer the crossing to the
// next conversational turn instead of eating it).
// C5: the re-seed is now PARTIALLY persisted - a pairing unseen this
// process seeds from the 0413 last_voiced_tier column, honored only
// while tier_since is fresh (<= 48 h; PersistedLastVoicedTier's gate),
// so a restart within a sitting can still voice the crossing it missed
// while stale state never fires a ceremony. Consume writes the crossing
// back.
int PeekTierTransition(uint32 botGuid, uint32 playerGuid, int tier)
{
    if (!playerGuid)
        return 0;
    {
        std::lock_guard<std::mutex> lock(g_beatMutex);
        uint64_t const key = PairKey(botGuid, playerGuid);
        if (LastVoicedTier().find(key) != LastVoicedTier().end())
        {
            return tier - LastVoicedTier()[key];
        }
    }
    int persisted = -1;
    if (PlayerbotLlmMemory::PersistedLastVoicedTier(botGuid, playerGuid, persisted))
    {
        std::lock_guard<std::mutex> lock(g_beatMutex);
        LastVoicedTier()[PairKey(botGuid, playerGuid)] = persisted;
        return tier - persisted;
    }
    return 0;
}

int ConsumeTierTransition(uint32 botGuid, uint32 playerGuid, int tier)
{
    if (!playerGuid)
        return 0;
    {
        std::lock_guard<std::mutex> lock(g_beatMutex);
        uint64_t const key = PairKey(botGuid, playerGuid);
        auto itr = LastVoicedTier().find(key);
        if (itr != LastVoicedTier().end())
        {
            int const was = itr->second;
            itr->second = tier;
            // C5: the crossing persists (fire-and-forget write; the
            // ceremony survives a restart-inside-a-sitting window)
            PlayerbotLlmMemory::NoteTierVoiced(botGuid, playerGuid, tier);
            return tier - was;
        }
    }
    // C5: a pairing unseen this process seeds from the persisted
    // last_voiced_tier column (the <= 48 h freshness gate lives inside
    // PersistedLastVoicedTier; the sync DB read runs OUTSIDE the beat
    // mutex). A fresh persisted value voices the crossing the restart
    // missed; a stale or absent one seeds silently, exactly the old
    // first-contact behavior.
    int was = tier;
    int persisted = -1;
    if (PlayerbotLlmMemory::PersistedLastVoicedTier(botGuid, playerGuid, persisted))
        was = persisted;
    {
        std::lock_guard<std::mutex> lock(g_beatMutex);
        LastVoicedTier()[PairKey(botGuid, playerGuid)] = tier;
    }
    PlayerbotLlmMemory::NoteTierVoiced(botGuid, playerGuid, tier);
    return tier - was;
}

// true exactly once per absence gap (an hour inside a sitting is one
// gap; a fresh sitting after hours away fires again)
bool ConsumeGreetingGap(uint32 botGuid, uint32 playerGuid)
{
    if (!playerGuid)
        return false;
    std::lock_guard<std::mutex> lock(g_beatMutex);
    uint64_t const key = PairKey(botGuid, playerGuid);
    time_t const now = time(nullptr);
    auto itr = GreetedGapAt().find(key);
    if (itr != GreetedGapAt().end() && now - itr->second < 3600)
        return false;
    GreetedGapAt()[key] = now;
    return true;
}

// The once-per-session lore card set, per (bot, player)
// pairing (a name-drop is grounded once; a repeat is wasted prefill).
// CHECK-AND-CLAIM in one: the first claim returns false (not yet seen)
// and marks; later claims return true
bool ClaimLoreCardOnce(uint32 botGuid, uint32 playerGuid, std::string const& title)
{
    std::lock_guard<std::mutex> lock(g_beatMutex);
    static std::set<uint64_t> seen;
    return !seen.insert(
        PairKey(botGuid, playerGuid) ^ (std::hash<std::string>()(title) & 0x7FFFFFFFull)).second;
}

// the dedupe-exemption budget: at most ONE exempted generation per
// bot per minute (a player spamming "are we square?" would
// otherwise disable the only anti-parrot mechanism for a mandated beat
// that re-fires verbatim every turn). Normal play never feels the cap -
// mandated beats are at most one per turn and turn cadence exceeds a
// minute under real chat; the attack collapses onto the capped path and
// the do-not-repeat reroll takes over (the reply still carries the
// cargo, freshly phrased - which is all the exemption wants).
std::map<uint32, time_t>& MandateExemptAt()
{
    static std::map<uint32, time_t> instance;
    return instance;
}

// tool name at the head of one of OUR OWN note lines (the bridge built
// the line, so the shape is known - no general parsing here)
std::string ToolNameOfLine(std::string const& line)
{
    size_t const open = line.find("<<");
    if (open == std::string::npos)
        return "";
    size_t pos = open + 2;
    while (pos < line.size() && isspace(static_cast<unsigned char>(line[pos])))
        ++pos;
    size_t end = pos;
    while (end < line.size() && !isspace(static_cast<unsigned char>(line[end])) &&
        line[end] != '>' && line[end] != '<')
        ++end;
    return line.substr(pos, end - pos);
}

} // namespace

uint64_t PlayerbotLlmBridge::RecordLicense(uint32 botGuid, Note const& note)
{
    if (!botGuid)
        return 0;
    ToolLicense license;
    license.stamp = ++g_licenseStamp;
    license.mandatesContent = note.mandatesContent;
    license.longFormCued = note.longFormCued;
    for (std::string const& line : note.lines)
    {
        std::string const name = ToolNameOfLine(line);
        if (!name.empty())
        {
            license.tools.insert(name);
            // first line per tool wins (one note never repeats a tool;
            // a defensive cap if one ever does)
            license.lineByTool.emplace(name, line);
        }
    }
    std::lock_guard<std::mutex> lock(g_licenseMutex);
    Licenses()[botGuid] = license;
    return license.stamp;
}

PlayerbotLlmBridge::ToolLicense PlayerbotLlmBridge::CurrentLicense(uint32 botGuid)
{
    std::lock_guard<std::mutex> lock(g_licenseMutex);
    auto itr = Licenses().find(botGuid);
    return itr != Licenses().end() ? itr->second : ToolLicense();
}

std::string PlayerbotLlmBridge::LicensedLineFor(uint32 botGuid, uint64_t stamp,
    std::string const& tool)
{
    // one locked read serves both the coverage check and the line fetch
    // (the former two-read form could straddle a record)
    ToolLicense const license = CurrentLicense(botGuid);
    if (license.stamp == 0 || license.stamp != stamp || !license.tools.count(tool))
        return "";
    auto itr = license.lineByTool.find(tool);
    return itr != license.lineByTool.end() ? itr->second : "";
}

bool PlayerbotLlmBridge::NoteMandatesContent(uint32 botGuid, uint64_t stamp)
{
    if (!botGuid || !stamp)
        return false;
    ToolLicense const license = CurrentLicense(botGuid);
    if (license.stamp != stamp || !license.mandatesContent)
        return false;
    // the exemption is a per-bot budget, not a standing right: claim it
    // (stamped on use, one per minute). Called from the async voice
    // filter - the beat mutex guards the map.
    std::lock_guard<std::mutex> lock(g_beatMutex);
    time_t const now = time(nullptr);
    auto itr = MandateExemptAt().find(botGuid);
    if (itr != MandateExemptAt().end() && now - itr->second < 60)
        return false;
    MandateExemptAt()[botGuid] = now;
    return true;
}

bool PlayerbotLlmBridge::NoteLongFormCued(uint32 botGuid, uint64_t stamp)
{
    // the reply budget's per-turn earning: the wide 4x255 chat
    // budget is spent only by a generation whose OWN note carried the
    // frozen cue. Stamp-checked like NoteMandatesContent - a superseding
    // note never widens an older in-flight generation - and NO budget
    // claim: the widening is not rate-capped (the cue itself fires at
    // most once per conversational turn, and tier-gated besides).
    if (!botGuid || !stamp)
        return false;
    ToolLicense const license = CurrentLicense(botGuid);
    return license.stamp == stamp && license.longFormCued;
}

std::string PlayerbotLlmBridge::NormalizeTurn(std::string const& botName, std::string const& msg)
{
    std::string text = msg;
    // the event drain appends a one-shot "say something" cue; persisting
    // it would replay a stale imperative in every later prompt
    std::string const nudge = std::string(" ") + botName + ", say something!";
    if (text.size() >= nudge.size() &&
        text.compare(text.size() - nudge.size(), nudge.size(), nudge) == 0)
        text.resize(text.size() - nudge.size());
    return text;
}

char const* PlayerbotLlmBridge::SpeakFirst()
{
    // banklib.SPEAK_FIRST verbatim - the trained event-turn directive
    return "No player words this turn - the world moved on its own. "
           "You speak first.";
}

pocketllm::LoreIndex const* PlayerbotLlmBridge::Lore()
{
    return LoadedLore();
}

bool PlayerbotLlmBridge::IsKnownName(std::string const& name)
{
    if (name.empty())
        return true;
    std::string lower;
    lower.reserve(name.size());
    for (char c : name)
        lower.push_back((char)std::tolower((unsigned char)c));
    if (sObjectAccessor.FindPlayerByName(name.c_str()))
        return true;
    if (KnownTemplateNames().count(lower))
        return true;
    if (pocketllm::LoreIndex const* lore = LoadedLore())
        if (lore->KnowsKey(lower))
            return true;
    return false;
}

bool PlayerbotLlmBridge::ResolvePoiPlace(std::string const& place,
    std::string* canonicalOut)
{
    pocketllm::LoreIndex const* lore = LoadedLore();
    if (!lore)
        return false;
    pocketllm::LoreCard const* card = lore->ResolvePoi(place);
    if (!card)
        return false;
    if (canonicalOut)
        *canonicalOut = card->title;
    return true;
}

namespace {

// the beat ladder proper - BuildNote wraps this so every return path
// records its license exactly once (the executor cross-check depends on
// the record matching the note the prompt actually carried)
PlayerbotLlmBridge::Note BuildNoteInner(Player* bot, Player* player,
    std::string const& normalizedMsg, PlayerbotLlmBridge::TurnState const& state)
{
    PlayerbotLlmBridge::Note note;
    PlayerbotLlmBridge::EventKind const eventKind =
        (PlayerbotLlmBridge::EventKind)state.eventKind;

    // An armed curiosity ask consumes the player's next
    // conversational reply as a fact (deterministic capture - the 0.8B
    // fallback's licensed log_fact fires unreliably, and a vanished
    // answer is a broken promise). Event turns never consume the arm
    if (player && bot && !state.eventTurn)
        PlayerbotLlmMemory::ConsumePendingAnswer(bot->GetGUIDLow(),
            player->GetGUIDLow(), normalizedMsg);

    // housekeeping event-nudge: a world event turn
    // licenses ONE memory write about it, plus the KIND's own licensed
    // extra (a level-up earns the cheer emote; a duel outcome earns the
    // sentiment move the bridge decided - a fair win over the bot is
    // earned regard, a flee is a slight; rare loot earns a congratulatory
    // cheer, a long-absence arrival earns a warm second cheer). The
    // [EVENT]-head-with-note compose combination is
    // UNTRAINED (the corpus's event_turn rows carry no lines - only the
    // bare log_fact shape is trained). No guard and no card here: event
    // text is bridge-authored, never a player-acted-on entity. The news
    // cargo is bridge-decided, so the note mandates its content.
    // Reactivity dial: dial <= 25 strips the licensed EXTRAS (the
    // memory write still lands - quiet bots remember, they just cheer
    // less); default 50+ keeps base behavior.
    if (state.eventTurn)
    {
        note.mandatesContent = true;
        note.lines.push_back("<<log_fact text=\"...\" category=\"shared-event\">>");
        note.fills = "Write in place of ...: the news - one line about what just happened.";
        bool const exuberant = sPlayerbotAIConfig.llmRpReactivity > 25;
        switch (eventKind)
        {
        case PlayerbotLlmBridge::EVENT_LEVEL_UP:
            if (exuberant)
                note.lines.push_back("<<perform_emote emote=\"cheer\">>");
            break;
        case PlayerbotLlmBridge::EVENT_RARE_LOOT:
            if (exuberant)
                note.lines.push_back("<<perform_emote emote=\"cheer\">>");
            break;
        case PlayerbotLlmBridge::EVENT_DUEL_LOST:
            note.lines.push_back("<<adjust_sentiment direction=\"+1\" reason=\"...\">>");
            note.fills = "Write in place of the first ...: the news - one line about what "
                         "just happened. In place of the second ...: why the fair win earned "
                         "your regard.";
            break;
        case PlayerbotLlmBridge::EVENT_DUEL_PLAYER_FLED:
            note.lines.push_back("<<adjust_sentiment direction=\"-1\" reason=\"...\">>");
            note.fills = "Write in place of the first ...: the news - one line about what "
                         "just happened. In place of the second ...: why the fleeing sat "
                         "poorly with you.";
            break;
        case PlayerbotLlmBridge::EVENT_DEBT_SETTLED:
            // The trade hook retired the debt row before this
            // turn queued - the kind-1 (debt-forgiven)
            // beat has its event source. The cargo rides the
            // note's directive leg; the log_fact above still persists the
            // settlement as the bot's own memory
            note.extra = pocketllm::TierBeatCargo(player ? player->GetName() : "",
                1, bot ? bot->GetGUIDLow() : 0);
            break;
        default:
            break; // a won duel, the bot's own flee: log only
        }
        return note;
    }

    // ---- question path, computed BEFORE the ladder so the
    // outcome MERGES into whatever beat fires (merge-not-defer: deferring
    // a first-meeting log_fact permanently loses the memory). Exactly one
    // of: a [RESULT] card, or a guard directive on the FIRST unresolved
    // entity (fires <=1/turn), or neither. The card needs a strict
    // question shape; the guard additionally accepts imperative stakes
    // ("take me to X" - directions are player-acted-on entities).
    std::string guardExtra;
    if (pocketllm::IsQuestionShape(normalizedMsg))
    {
        if (pocketllm::LoreIndex const* lore = LoadedLore())
            if (pocketllm::LoreCard const* card = lore->BestCard(normalizedMsg))
                note.result = card->text;
    }
    // Keyword-triggered lore (world-info lite) - a canonical
    // POI/figure TITLE in a QUESTION-OR-STAKES-shaped turn (the widened
    // gate: imperatives like "take me to X" ground their card too; pure
    // declaratives stay ungated - the trained question path above keeps
    // first claim). Once per card per session per pairing: a name-drop
    // is grounded once, a repeat is wasted prefill on the local tier.
    if (note.result.empty() && bot && player &&
        pocketllm::IsQuestionOrStakesShape(normalizedMsg))
    {
        if (pocketllm::LoreIndex const* lore = LoadedLore())
            if (pocketllm::LoreCard const* card = lore->BestCard(normalizedMsg))
                if (!ClaimLoreCardOnce(bot->GetGUIDLow(), player->GetGUIDLow(),
                        card->title))
                    note.result = card->text;
    }
    if (note.result.empty() &&
        pocketllm::IsQuestionOrStakesShape(normalizedMsg))
    {
        for (pocketllm::GuardCandidate const& cand :
            pocketllm::ExtractGuardEntities(normalizedMsg))
        {
            if (IsKnownEntity(bot, player, cand.name))
                continue;
            // people collect descriptors ("is Dughan guard captain here
            // today?"): an unknown PERSON compound whose HEAD word is a
            // known name is that known person, never an invention to
            // deny. Thing/place compounds ("Stormwind falconer badge")
            // still match whole - the invented object is the ask.
            if (cand.cls == "person" &&
                cand.name.find(' ') != std::string::npos)
            {
                std::string const head = cand.name.substr(0,
                    cand.name.find(' '));
                if (IsKnownEntity(bot, player, head))
                    continue;
            }
            guardExtra = pocketllm::GuardDirective(cand.name, cand.cls);
            break;
        }
    }

    // conversational ACT beats: an explicit, actionable request
    // outranks sentiment AND recall - the ask is the ask. The note
    // carries the ready-made line with the SPEAKER's name (target
    // consistency starts here: the model is asked to copy, the executor
    // re-validates the name against the speaker). The Trusted
    // discount procedure rides the give_item fill (warmth changes what
    // the bot DOES: a friend's price is the only price it knows).
    bool actBeat = false;
    if (player)
    {
        switch (pocketllm::SelectConversationalBeat(normalizedMsg))
        {
        case pocketllm::BEAT_DUEL:
            note.lines.push_back("<<duel_challenge name=\""
                + std::string(player->GetName()) + "\">>");
            actBeat = true;
            break;
        case pocketllm::BEAT_GIVE_ITEM:
        {
            std::string item;
            if (pocketllm::ExtractGiftItem(normalizedMsg, &item))
            {
                note.lines.push_back("<<give_item player=\""
                    + std::string(player->GetName())
                    + "\" item=\"" + item + "\">>");
                actBeat = true;
                if (state.tier >= 4)
                    note.fills = "A friend's price is the only price you know - say so "
                                 "while you hand it over.";
            }
            // no noun extracted: fall through to the ladder
            break;
        }
        case pocketllm::BEAT_FOLLOW:
            note.lines.push_back("<<follow name=\""
                + std::string(player->GetName()) + "\">>");
            actBeat = true;
            break;
        case pocketllm::BEAT_PARTY_INVITE:
            note.lines.push_back("<<party_invite name=\""
                + std::string(player->GetName()) + "\">>");
            actBeat = true;
            break;
        case pocketllm::BEAT_MOVE_TO:
        {
            // the place must resolve against a POI card HERE -
            // the licensed line carries the CANONICAL title (the
            // bridge-decided field), so the model cannot redirect the
            // move and the executor re-resolves the same way
            std::string place;
            std::string canonical;
            if (pocketllm::ExtractMovePlace(normalizedMsg, &place) &&
                PlayerbotLlmBridge::ResolvePoiPlace(place, &canonical))
            {
                note.lines.push_back("<<move_to place=\"" + canonical + "\">>");
                actBeat = true;
            }
            // unresolvable place: no license, fall through (the guard
            // above already denied invented places when one was named)
            break;
        }
        default:
            break;
        }
    }

    // insult beat: license the sentiment move the bridge has DECIDED on;
    // the model only phrases the reason. Second-person gate:
    // "this sword is trash" insults an object, not the bot - the beat
    // fires only when the turn addresses the bot (a second-person word
    // or the bot's name)
    if (note.lines.empty() &&
        ContainsAny(normalizedMsg, kInsultTriggers,
            sizeof(kInsultTriggers) / sizeof(kInsultTriggers[0])) &&
        pocketllm::IsSecondPerson(normalizedMsg, bot ? bot->GetName() : ""))
    {
        note.lines.push_back("<<adjust_sentiment direction=\"-1\" reason=\"...\">>");
        note.fills = "Write in place of ...: how he just treated you - the reason.";
    }

    // gratitude/gift beat - same second-person gate as the insult beat:
    // "thank the innkeeper for me" thanks a third party, not the bot
    else if (note.lines.empty() &&
        ContainsAny(normalizedMsg, kGratitudeTriggers,
            sizeof(kGratitudeTriggers) / sizeof(kGratitudeTriggers[0])) &&
        pocketllm::IsSecondPerson(normalizedMsg, bot ? bot->GetName() : ""))
    {
        note.lines.push_back("<<adjust_sentiment direction=\"+1\" reason=\"...\">>");
        note.fills = "Write in place of ...: the kindness or gift - the reason.";
    }

    // ---- recall beats: the
    // player's turn references a MEMORY CLASS; the bridge supplies the
    // fact as cargo and the note mandates the content. One beat, before
    // first-meeting (a debt question on a first meeting has no debt to
    // recall - the queries return empty and the ladder falls through).
    else if (note.lines.empty() && player &&
        pocketllm::IsDebtQuestion(normalizedMsg))
    {
        std::string const fact = PlayerbotLlmMemory::GetNewestRecallFact(
            bot->GetGUIDLow(), player->GetGUIDLow(), pocketllm::FACT_MASK_DEBT);
        if (!fact.empty())
        {
            note.extra = pocketllm::DebtCargo(player->GetName(), fact, bot->GetGUIDLow());
            note.mandatesContent = true;
        }
    }

    else if (note.lines.empty() && player &&
        pocketllm::IsMemoryQuestion(normalizedMsg))
    {
        int const mask = pocketllm::FACT_MASK_GOAL | pocketllm::FACT_MASK_EVENT |
            pocketllm::FACT_MASK_PLAIN;
        std::string const fact = PlayerbotLlmMemory::GetNewestRecallFact(
            bot->GetGUIDLow(), player->GetGUIDLow(), mask);
        if (!fact.empty())
        {
            bool const askAfter = state.tier >= 3 &&
                pocketllm::FactClassOf(fact, "") == pocketllm::FACT_GOAL;
            note.extra = pocketllm::MemoryCargo(player->GetName(), fact, askAfter, bot->GetGUIDLow());
            note.mandatesContent = true;
        }
    }

    // ---- storytelling beat: an explicit ask for a telling. The
    // content is still bridge-anchored to a real shared event (the game
    // is truth - no event fact, no story), and the frozen long-form cue
    // rides the cargo only when this tier's max new tokens clears the
    // long bank. No second-person gate: the trigger phrases carry their
    // own address ("tell me"), unlike the insult/follow idioms.
    else if (note.lines.empty() && player &&
        pocketllm::WantsStorytelling(normalizedMsg) &&
        pocketllm::LongFormLicensed(sPlayerbotAIConfig.llmMaxNewTokens, sPlayerbotAIConfig.llmRpLongForm))
    {
        std::string const fact = PlayerbotLlmMemory::GetNewestRecallFact(
            bot->GetGUIDLow(), player->GetGUIDLow(), pocketllm::FACT_MASK_EVENT);
        if (!fact.empty())
        {
            note.extra = pocketllm::NewsCargo(player->GetName(), fact,
                bot->GetGUIDLow()) + "\n" + pocketllm::kLongFormCue;
            note.mandatesContent = true;
            note.longFormCued = true;
        }
    }

    // ---- tier-5 Bonded open-confidence: a friend asking a friend to
    // really talk, anchored to the player's own goal at length. Tier-5
    // only, second-person-gated (every trigger addresses the bot), and
    // the same long-form token gate.
    else if (note.lines.empty() && player && state.tier >= 5 &&
        pocketllm::WantsOpenConfidence(normalizedMsg) &&
        pocketllm::IsSecondPerson(normalizedMsg, bot ? bot->GetName() : "") &&
        pocketllm::LongFormLicensed(sPlayerbotAIConfig.llmMaxNewTokens, sPlayerbotAIConfig.llmRpLongForm))
    {
        std::string const fact = PlayerbotLlmMemory::GetNewestRecallFact(
            bot->GetGUIDLow(), player->GetGUIDLow(), pocketllm::FACT_MASK_GOAL);
        if (!fact.empty())
        {
            note.extra = pocketllm::MemoryCargo(player->GetName(), fact, true,
                bot->GetGUIDLow()) + "\n" + pocketllm::kLongFormCue;
            note.mandatesContent = true;
            note.longFormCued = true;
        }
    }

    // news-shape recall: the verified-event window keeps first claim
    // (share_gossip below); once it has closed, a remembered event is
    // still the answer to "any news" (3/3 vs 1/6 instruction-only)
    else if (note.lines.empty() && bot &&
        ContainsAny(normalizedMsg, kNewsTriggers,
            sizeof(kNewsTriggers) / sizeof(kNewsTriggers[0])) &&
        !PlayerbotLlmMemory::HasRecentVerifiedEvent(bot->GetGUIDLow()) &&
        player)
    {
        std::string const fact = PlayerbotLlmMemory::GetNewestRecallFact(
            bot->GetGUIDLow(), player->GetGUIDLow(), pocketllm::FACT_MASK_EVENT);
        if (!fact.empty())
        {
            note.extra = pocketllm::NewsCargo(player->GetName(), fact, bot->GetGUIDLow());
            // news deep-dive: the cue rides this recall path whenever
            // the tier clears the long bank. The fact is event-class BY
            // CONSTRUCTION - GetNewestRecallFact(FACT_MASK_EVENT) selects
            // shared-event rows - so re-classifying here would be dead
            // code (FactClassOf without the stored category reads PLAIN,
            // so that gate would never fire).
            if (pocketllm::LongFormLicensed(sPlayerbotAIConfig.llmMaxNewTokens, sPlayerbotAIConfig.llmRpLongForm))
            {
                note.extra += std::string("\n") + pocketllm::kLongFormCue;
                note.longFormCued = true;
            }
            note.mandatesContent = true;
        }
    }

    // greeting-shape recall (once per absence gap): the first
    // conversational turn after hours-or-more away carries the weave
    // pick, any unsettled grudge, and - first in delivery priority -
    // what the town is saying about the player. Absence is the PRE-STOMP
    // read threaded in TurnState.
    else if (note.lines.empty() && player && state.tier >= 2 &&
        (state.absence == "a few hours" || state.absence == "most of a day" ||
            state.absence == "many days") &&
        ConsumeGreetingGap(bot->GetGUIDLow(), player->GetGUIDLow()))
    {
        std::string cargo;
        std::string const gossip = PlayerbotLlmMemory::GossipAbout(player->GetName());
        if (!gossip.empty())
        {
            cargo = pocketllm::GossipCargo(player->GetName(), gossip, bot->GetGUIDLow());
            // belief row: the bot now carries the town's telling, one
            // distortion hop deep (its own retellings drift from here -
            // the world row itself stays pristine). Bounded by gaps.
            PlayerbotLlmMemory::LogFact(bot->GetGUIDLow(), player->GetGUIDLow(),
                std::string("heard the town talk: ") +
                    pocketllm::DistortGossipHop(gossip, bot->GetGUIDLow()),
                "shared-event");
        }
        std::string const grudge = PlayerbotLlmMemory::GetUnresolvedGrudge(
            bot->GetGUIDLow(), player->GetGUIDLow());
        if (!grudge.empty())
            cargo = cargo.empty()
                ? pocketllm::GrudgeCargo(grudge, bot->GetGUIDLow())
                : cargo + "\n" + pocketllm::GrudgeCargo(grudge, bot->GetGUIDLow());
        if (cargo.empty())
        {
            int const mask = pocketllm::FACT_MASK_GOAL | pocketllm::FACT_MASK_EVENT |
                pocketllm::FACT_MASK_PLAIN;
            std::string const fact = PlayerbotLlmMemory::GetNewestRecallFact(
                bot->GetGUIDLow(), player->GetGUIDLow(), mask);
            if (!fact.empty())
            {
                bool const askAfter = state.tier >= 3 &&
                    pocketllm::FactClassOf(fact, "") == pocketllm::FACT_GOAL;
                cargo = pocketllm::MemoryCargo(player->GetName(), fact, askAfter, bot->GetGUIDLow());
            }
        }
        if (!cargo.empty())
        {
            note.extra = cargo;
            note.mandatesContent = true;
        }
    }

    // first-meeting beat: the PRE-STOMP absence read, threaded from the
    // caller ("a first meeting" = no interaction ever stamped) - fires
    // exactly once per (bot, player); a fresh read here would race the
    // async relationship write (double-fire or never-fire)
    else if (note.lines.empty() && player && state.firstMeeting())
    {
        note.lines.push_back("<<log_fact text=\"...\" category=\"shared-event\">>");
        note.fills = "Write in place of ...: one true thing you now know about him "
                     "from what he just said.";
    }

    // gossip beat: only alongside a verified world event (the same gate
    // the executor enforces - the note elicits, the game validates)
    else if (note.lines.empty() &&
        ContainsAny(normalizedMsg, kNewsTriggers,
            sizeof(kNewsTriggers) / sizeof(kNewsTriggers[0])) &&
        PlayerbotLlmMemory::HasRecentVerifiedEvent(bot->GetGUIDLow()))
    {
        note.lines.push_back("<<share_gossip text=\"...\">>");
        note.fills = "Write in place of ...: the news - one line about what just happened.";
    }

    // emote beat: shared laughter licenses the ready-made emote line
    else if (note.lines.empty() &&
        ContainsAny(normalizedMsg, kLaughterTriggers,
            sizeof(kLaughterTriggers) / sizeof(kLaughterTriggers[0])))
    {
        note.lines.push_back("<<perform_emote emote=\"laugh\">>");
    }

    // ---- tier ceremony: a transition the pre-stomp read
    // OBSERVES (one turn after the crossing write landed - the write is
    // async-queued, so the pre-stomp read of the crossing turn still saw
    // the old tier; this is the honest cadence, not a lag bug). The
    // ceremony co-fires with a non-ACT beat only: an actionable request
    // outranks ceremony (the ask is the ask), and an ACT turn does NOT
    // consume the crossing - the next non-ACT turn voices it
    // (consuming-and-suppressing would let a player permanently eat
    // the one-time moment). The Trusted unlock rides the tier-4
    // ceremony: the secret's one-time marker is the licensed log_fact
    // line (the executor persists the model's copy of the text - a
    // paraphrased copy leaves no marker and a LATER re-crossing could
    // re-release; fail direction cosmetic, recorded). A governor-dropped
    // generation consumes the crossing without voicing it - the ceremony
    // is lost until the next crossing (accepted; relationship changes
    // still show in the tier line itself).
    if (player)
    {
        int const crossed = actBeat
            ? PeekTierTransition(bot->GetGUIDLow(), player->GetGUIDLow(), state.tier)
            : ConsumeTierTransition(bot->GetGUIDLow(), player->GetGUIDLow(), state.tier);
        if (crossed && !actBeat)
        {
            // C5: the prose rider is PER-PLAYER COALESCED (<= 1 per
            // player per hour - a five-bot simultaneous crossing is one
            // ceremony, not five). The mood nudge, the sys line and the
            // persisted crossing below stay per-crossing.
            bool riderAdmits = false;
            {
                std::lock_guard<std::mutex> lock(g_beatMutex);
                time_t const nowT = time(nullptr);
                auto ritr = CeremonyRiderAt().find(player->GetGUIDLow());
                if (ritr == CeremonyRiderAt().end() || nowT - ritr->second >= 3600)
                {
                    CeremonyRiderAt()[player->GetGUIDLow()] = nowT;
                    riderAdmits = true;
                }
            }
            // the crossing folds into the mood weather: a Bonded
            // ceremony brightens it, a tier loss quiets it (the bucket
            // rotation keeps either from sticking forever)
            PlayerbotLlmMemory::NudgeMood(bot->GetGUIDLow(),
                crossed > 0 ? pocketllm::MOOD_SMITTEN : pocketllm::MOOD_GRIEF);
            if (!riderAdmits)
            {
                // the crossing still counts - it is consumed and
                // persisted; only the generative rider waits
            }
            else
            {
            std::string cargo = crossed > 0
                ? pocketllm::CeremonyUpCargo(player->GetName(), state.tier, bot->GetGUIDLow())
                : pocketllm::CeremonyDownCargo(player->GetName(), bot->GetGUIDLow());
            if (crossed > 0 && state.tier >= 4 &&
                !PlayerbotLlmMemory::HasFactPrefix(bot->GetGUIDLow(),
                    player->GetGUIDLow(), "secret told:"))
            {
                note.lines.push_back(std::string("<<log_fact text=\"secret told: ")
                    + pocketllm::BackstorySecretOf(bot->GetGUIDLow())
                    + "\" category=\"player-identity\">>");
                cargo += "\n" + pocketllm::SecretCargo(player->GetName(),
                    bot->GetGUIDLow());
            }
            // the Bonded address shift rides the ceremony as a PROCEDURE
            // (the standing tierNote alone never yields nicknames -
            // dispositions are ignored - so the shift is spelled out)
            if (crossed > 0 && state.tier >= 5)
                cargo += "\n" + pocketllm::NicknameAdoptionCargo(
                    player->GetName(), bot->GetGUIDLow());
            note.extra = note.extra.empty() ? cargo : note.extra + "\n" + cargo;
            note.mandatesContent = true;
            }

            // visible progression: the cheap system-colored line rides
            // the same OBSERVED crossing (pure DB-derived, zero generation) -
            // so the progression stays player-visible even when the ceremony
            // generation itself is governor-dropped or silent. World thread:
            // BuildNote runs
            // synchronously inside ChatReplyDo. Sent BEFORE the generation is
            // voiced, never after - it frames the bot's next words.
            // PER-CROSSING by law (C5: only the prose rider coalesces).
            if (player->GetSession())
                ChatHandler(player->GetSession()).SendSysMessage(
                    pocketllm::TierShiftSysLine(bot->GetName(), crossed > 0).c_str());
        }
    }

    // Merge-not-defer lands here: the guard rides as note.extra with
    // whatever beat lines the ladder chose (compose renders extra ABOVE
    // the licensed lines inside the one note).
    // A guard with no beat becomes the note itself (extra-only notes get
    // the directive footer from ComposeUserTurn).
    if (!guardExtra.empty())
        note.extra = note.extra.empty() ? guardExtra : note.extra + "\n" + guardExtra;

    // Scene/homeland furniture - ONE line, riding the
    // bridge extra leg (never the trained [State] fill, never a new
    // segment), default OFF. Precedence: stealth >
    // home ground > enemy capital; conversational turns only
    if (!state.eventTurn && bot && sPlayerbotAIConfig.llmWorldTruthFurniture)
    {
        std::string sceneLine;
        if (bot->HasStealthAura())
            sceneLine = "You are moving unseen - keep your voice low and your words few.";
        else if (AreaTableEntry const* zone = bot->GetPlayerbotAI()
                ? bot->GetPlayerbotAI()->GetCurrentZone() : nullptr)
        {
            std::string const zoneName =
                pocketllm::LowerCopy(bot->GetPlayerbotAI()->GetLocalizedAreaName(zone));
            char const* const home = pocketllm::HomeZoneOfRace(bot->getRace());
            if (*home && zoneName.find(home) != std::string::npos)
                sceneLine = "This is home ground for you; you stand easier here, and it shows.";
            else if (pocketllm::IsEnemyCapitalZone(zoneName, bot->getRace()))
                sceneLine = "This is enemy ground; keep your voice down and your eyes up.";
        }
        if (!sceneLine.empty())
            note.extra = note.extra.empty() ? sceneLine : note.extra + "\n" + sceneLine;
    }

    return note; // plain conversational turn: no note, no tools
}

} // namespace

PlayerbotLlmBridge::Note PlayerbotLlmBridge::BuildNote(Player* bot, Player* player,
    std::string const& normalizedMsg, TurnState const& state)
{
    Note note = BuildNoteInner(bot, player, normalizedMsg, state);
    // The license is recorded at note-construction time - the single
    // choke point every prompt path flows through, before the generation
    // whose emissions it will vet. The issued stamp rides the note out to
    // the callers, which thread it into the generation.
    note.stamp = RecordLicense(bot ? bot->GetGUIDLow() : 0, note);
    return note;
}

std::string PlayerbotLlmBridge::RenderLegacyTurn(Player* bot, Player* player,
    std::string const& msg, TurnState const& state, uint64_t* licenseStamp)
{
    // the nudge strip is event-only: a player whisper that
    // happens to end in the nudge-shaped suffix keeps its actual words
    std::string const normalized = state.eventTurn
        ? NormalizeTurn(bot->GetName(), msg) : msg;
    Note const note = BuildNote(bot, player, normalized, state);
    if (licenseStamp)
        *licenseStamp = note.stamp;
    std::vector<std::string> results;
    if (!note.result.empty())
        results.push_back(note.result);
    if (state.eventTurn && note.empty())
    {
        // event turns always carry the trained speak-first directive
        return pocketllm::ComposeUserTurn("", {}, std::vector<std::string>{normalized},
            {}, "", {}, {}, "", SpeakFirst());
    }
    if (note.empty())
        return std::string();
    if (state.eventTurn)
        return pocketllm::ComposeUserTurn("", {}, std::vector<std::string>{normalized},
            results, "", {}, note.lines, note.fills,
            note.extra.empty() ? SpeakFirst() : note.extra);
    return pocketllm::ComposeUserTurn("", {}, {}, results, "", {},
        note.lines, note.fills, note.extra);
}
