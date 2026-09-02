#include "PlayerbotLlmChatter.h"

#include "PlayerbotLlmChatterCore.h"
#include "PlayerbotLlmFilters.h"
#include "PlayerbotLlmJson.h"
#include "PlayerbotLlmMemory.h"
#include "PlayerbotLlmPrompt.h"
#include "PlayerbotLlmToolsCore.h"
#include "PlayerbotLLMInterface.h"
#include "llm_banter_core.h"

#include "playerbot/playerbot.h"
#include "playerbot/BroadcastHelper.h"
#include "playerbot/PlayerbotAIConfig.h"
#include "playerbot/RandomPlayerbotMgr.h"
#include "playerbot/ServerFacade.h"
#include "Chat/Channel.h"
#include "Chat/ChannelMgr.h"
#include "Entities/Player.h"
#include "Globals/ObjectAccessor.h"
#include "Grids/CellImpl.h"
#include "Grids/GridNotifiers.h"
#include "Grids/GridNotifiersImpl.h"
#include "Groups/Group.h"
#include "Server/DBCStores.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <deque>
#include <fstream>
#include <map>
#include <mutex>
#include <set>
#include <thread>
#include <vector>

namespace {

// ---- local naming helpers (PlayerbotLlmMemory keeps its own copies in
// its anonymous namespace; the murmur system message needs only the
// approximate descriptor, and duplicating the full table here would be
// a drift surface - the chatter sysm is NOT the trained contract)
std::string RaceWord(uint32 race)
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

std::string ClassWord(uint32 cls)
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
        default: return "laborer";
    }
}

std::string ZoneWord(Player* bot)
{
    AreaTableEntry const* zone = bot->GetPlayerbotAI() ? bot->GetPlayerbotAI()->GetCurrentZone() : nullptr;
    if (!zone || !bot->GetPlayerbotAI())
        return "these parts";
    return bot->GetPlayerbotAI()->GetLocalizedAreaName(zone);
}

uint32 GripeSlot(time_t now)
{
    // the slow persona-gripe rotation bucket (~50 min): stable within a
    // session, different across long absences (anti-flanderization)
    return static_cast<uint32>(now / 3000);
}

// ---- the shared scheduler state (world thread for every mutation of
// the queue/ring/fatigue EXCEPT the workers' validated appends; one
// mutex covers all of it)
struct PendingLine
{
    pocketllm::ChatterLayer layer;
    uint32 speakerGuid;
    uint32 listenerGuid;   // murmur listener (0 otherwise)
    std::string text;      // final delivery text
    std::string factKey;   // fatigue key ("g:<id>" / "f:<bot>:<id>")
    bool originator;       // the telling carries the row verbatim
    size_t templateIdx;    // authored floor rows (else kNoTemplate)
    time_t notBefore;
    bool floor;            // authored floor entry (EMERGENCY class)
    PendingLine()
        : layer(pocketllm::LAYER_MURMUR), speakerGuid(0), listenerGuid(0),
          originator(false), templateIdx(0), notBefore(0), floor(false) {}
};

size_t const kNoTemplate = static_cast<size_t>(-1);
size_t const kQueueCap = 12;

struct ChatterState
{
    std::mutex mutex;
    std::deque<PendingLine> queue;
    pocketllm::WorldRing ring;
    pocketllm::ChatterFatigue fatigue;
    time_t lastPlayerChatAt = 0;
    time_t lastMurmurBatchAt = 0;
    time_t lastGlobalAt = 0;
    time_t lastGlobalWindowAt = 0;
    time_t lastFloorAt = 0;
    std::map<uint32, time_t> partyWindowAt;    // per master guid
    std::map<uint32, time_t> partyDuelNoteAt;  // per master guid (consumed on fire)
    // the reconciled power state (RUNG_OFF until a fresh file is seen)
    pocketllm::ChatterRung rung = pocketllm::RUNG_OFF;
    uint32 rng = 0;
};

ChatterState& State()
{
    static ChatterState instance;
    return instance;
}

std::atomic<bool>& BatchInFlight()
{
    static std::atomic<bool> instance(false);
    return instance;
}

// ---- the power file (AiPlayerbot.LLMChatterPowerFile): three flat
// lines "enabled=N", "rung=N", "at=N" (epoch seconds), refreshed by the
// app's ChatterPowerMonitor. Missing/unparseable = OFF; stale beyond
// kPowerFreshSec = the authored floor only (the writer died; fail toward
// silence, never toward unbounded generation).
time_t const kPowerFreshSec = 600;

bool ParsePowerFile(std::string const& path, bool& enabled, int& rung, time_t& at)
{
    std::ifstream in(path.c_str());
    if (!in.is_open())
        return false;
    enabled = false; rung = 0; at = 0;
    std::string line;
    while (std::getline(in, line))
    {
        size_t const eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string const key = line.substr(0, eq);
        std::string const val = line.substr(eq + 1);
        if (key == "enabled") enabled = atoi(val.c_str()) != 0;
        else if (key == "rung") rung = atoi(val.c_str());
        else if (key == "at") at = (time_t)atoll(val.c_str());
    }
    return true;
}

// Reconciles the rung from the conf + the file. Called under the mutex.
void ReconcilePower(time_t now)
{
    ChatterState& s = State();
    if (!sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmChatterEnabled ||
        sPlayerbotAIConfig.llmChatterPowerFile.empty())
    {
        s.rung = pocketllm::RUNG_OFF;
        s.queue.clear();
        return;
    }
    bool enabled = false; int rung = 0; time_t at = 0;
    if (!ParsePowerFile(sPlayerbotAIConfig.llmChatterPowerFile, enabled, rung, at))
    {
        s.rung = pocketllm::RUNG_OFF;
        s.queue.clear();
        return;
    }
    if (!enabled || rung < pocketllm::RUNG_EMERGENCY || rung > pocketllm::RUNG_NORMAL)
    {
        s.rung = pocketllm::RUNG_OFF;
        s.queue.clear();  // the master toggle kill: drop pending lines too
        return;
    }
    // a stale writer degrades to the authored floor: generated batches
    // stop, and already-generated queue entries are dropped (the plan's
    // "generation stops; authored texture floor only" - round-2 R6);
    // event-grounded floor lines may continue (documented fail-safe).
    // A missing/zero stamp or one from the future (a clock jump after
    // the writer died) counts as stale - never as fresh forever.
    bool const stale = at <= 0 || at > now + kPowerFreshSec ||
        (now > at && now - at > kPowerFreshSec);
    if (stale)
    {
        for (auto itr = s.queue.begin(); itr != s.queue.end();)
        {
            if (!itr->floor)
                itr = s.queue.erase(itr);
            else
                ++itr;
        }
        s.rung = pocketllm::RUNG_EMERGENCY;
    }
    else
        s.rung = (pocketllm::ChatterRung)rung;
}

// ---- candidate events. SILENCE DEFAULT: every picker returns false
// with an empty out when the bank has nothing fresh + unretired - the
// callers may not roll without one.
struct ChatterEventRow
{
    std::string factKey;
    std::string text;
    bool originatorForSpeaker = false;  // speaker == source_bot
    bool valid = false;
};

bool PickGossipRow(uint32 speakerGuid, bool preferDuelClass, ChatterEventRow& out)
{
    auto result = WorldDatabase.PQuery(
        "SELECT `id`, `text`, `category`, `source_bot` FROM `world_gossip` "
        "WHERE (`expires_at` IS NULL OR `expires_at` > CURRENT_TIMESTAMP)%s "
        "ORDER BY `id` DESC LIMIT 8",
        preferDuelClass ? " AND `category` = 'duel'" : "");
    if (!result)
        return false;
    std::vector<std::pair<uint32, std::array<std::string, 3>>> rows;
    do
    {
        Field* f = result->Fetch();
        std::array<std::string, 3> cols = { f[1].GetString(), f[2].GetString(), f[3].GetString() };
        rows.push_back(std::make_pair(f[0].GetUInt32(), cols));
    } while (result->NextRow());

    // player-subject rows (the duel class) get delivery priority on every
    // layer; when the preferred query found none, a plain pass picks any
    // fresh row but still prefers the duel class by ordering
    if (preferDuelClass && rows.empty())
        return false;
    if (!preferDuelClass)
        std::stable_sort(rows.begin(), rows.end(),
            [](std::pair<uint32, std::array<std::string, 3>> const& a,
               std::pair<uint32, std::array<std::string, 3>> const& b)
            { return a.second[1] == "duel" && b.second[1] != "duel"; });

    std::lock_guard<std::mutex> lock(State().mutex);
    for (auto const& row : rows)
    {
        std::string const key = "g:" + std::to_string(row.first);
        if (!pocketllm::FatigueAdmits(State().fatigue, key))
            continue;
        uint32 const source = (uint32)atoll(row.second[2].c_str());
        if (source != speakerGuid &&
            !pocketllm::CredenceAdmits(State().fatigue, speakerGuid, key))
            continue;  // this bot already HEARD the story: no retell
        out.factKey = key;
        out.text = row.second[0];
        out.originatorForSpeaker = source == speakerGuid;
        out.valid = true;
        return true;
    }
    return false;
}

// party topic: the group's newest debt/goal/event fact about the master,
// else any fresh gossip row. speakerGuid only gates the gossip credence.
bool PickPartyTopic(Player* master, uint32 speakerGuid, ChatterEventRow& out)
{
    Group* group = master->GetGroup();
    if (group)
    {
        for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
        {
            Player* member = itr->getSource();
            if (!member || !member->GetPlayerbotAI() || member == master)
                continue;
            auto result = CharacterDatabase.PQuery(
                "SELECT `id`, `fact_text`, `category` FROM `bot_player_facts` "
                "WHERE `bot` = '%u' AND `player` = '%u' ORDER BY `id` DESC LIMIT 6",
                member->GetGUIDLow(), master->GetGUIDLow());
            if (!result)
                continue;
            std::vector<std::pair<uint32, std::pair<std::string, std::string>>> facts;
            do
            {
                Field* f = result->Fetch();
                facts.push_back(std::make_pair(f[0].GetUInt32(),
                    std::make_pair(f[1].GetString(), f[2].GetString())));
            } while (result->NextRow());
            for (auto const& fact : facts)
            {
                if (fact.second.first.rfind("(tone", 0) == 0)
                    continue;
                int const cls = pocketllm::FactClassOf(fact.second.first, fact.second.second);
                if (cls != pocketllm::FACT_DEBT && cls != pocketllm::FACT_GOAL &&
                    cls != pocketllm::FACT_EVENT)
                    continue;
                std::string const key =
                    "f:" + std::to_string(member->GetGUIDLow()) + ":" +
                    std::to_string(fact.first);
                std::lock_guard<std::mutex> lock(State().mutex);
                if (!pocketllm::FatigueAdmits(State().fatigue, key))
                    continue;
                out.factKey = key;
                out.text = fact.second.first;
                out.originatorForSpeaker = true;  // the group lived it
                out.valid = true;
                return true;
            }
        }
    }
    return PickGossipRow(speakerGuid, false, out);
}

// ---- the legend telling: computed at PICK time (the generation needs
// the text); the tellings COUNT burns only at delivery, so a dropped
// batch costs at most one drift of color, never a retirement.
std::string TellingTextFor(ChatterEventRow const& row)
{
    std::lock_guard<std::mutex> lock(State().mutex);
    return pocketllm::LegendTellingText(State().fatigue, row.factKey,
        row.text, row.originatorForSpeaker);
}

// ---- async batch jobs (copied values only; no Player* crosses threads)
struct PersonaDims
{
    std::string name;
    std::string race, cls, zone;
    std::string demeanor, quirk, gripe;
};

struct DeviceJob
{
    pocketllm::ChatterLayer layer;
    uint32 speakerGuid;
    uint32 listenerGuid;
    PersonaDims persona;
    std::string listenerName;   // murmur listener / party master
    std::string telling;        // the event text to voice around
    std::string factKey;
    bool originator;
    uint32 displayMinSec, displayMaxSec;  // the rung's display cadence
    DeviceJob()
        : layer(pocketllm::LAYER_MURMUR), speakerGuid(0), listenerGuid(0),
          originator(false), displayMinSec(20), displayMaxSec(45) {}
};

struct ComposerJob
{
    pocketllm::ChatterLayer layer;   // party scripts or murmur exchanges
    std::vector<PersonaDims> personas;
    std::vector<std::string> eventRows;
    std::vector<std::string> factKeys;
    std::vector<uint32> speakerGuids;
    uint32 displayMinSec, displayMaxSec;
    ComposerJob() : layer(pocketllm::LAYER_PARTY), displayMinSec(6), displayMaxSec(20) {}
};

pocketllm::RequestSampling AmbientSampling()
{
    // SS4.6b ambience voice: T ~1.0 with min_p 0.05 - the diversity pair
    // for ambient generation (page: "min_p 0.05-0.1 with T ~1.0-1.2")
    pocketllm::RequestSampling s;
    s.temperature = 1.0f;
    s.topP = 0.95f;
    s.topK = 20;
    s.repeatPenalty = 1.0f;
    s.minP = 0.05f;
    s.maxTokens = 48;
    s.providerSafe = sPlayerbotAIConfig.llmApiProviderSafe != 0;
    s.thinkingKwargs = sPlayerbotAIConfig.llmThinkingKwargs != 0;
    return s;
}

std::string NoteForLayer(pocketllm::ChatterLayer layer,
    std::string const& listenerName, std::string const& telling)
{
    if (layer == pocketllm::LAYER_GLOBAL)
        return pocketllm::GlobalNote(telling);
    if (layer == pocketllm::LAYER_PARTY)
        return pocketllm::PartyNote(listenerName, telling);
    return pocketllm::MurmurNote(listenerName, telling);
}

bool RegisterAdmits(std::string const& text)
{
    return pocketllm::IsMurmurRegister(text);
}

// worker-thread rand: urand is the world-thread core RNG, so the async
// batch paths use their own atomic-seeded SplitMix32 stream instead.
uint32 WorkerRand(uint32 lo, uint32 hi)
{
    static std::atomic<uint32> seed(0x51ED2701u);
    uint32_t state = seed.fetch_add(0x9E3779B9u) + 1u;
    uint32_t const roll = pocketllm::SplitMix32(state) % (hi - lo + 1u);
    return lo + roll;
}

// The one validation + enqueue shared by the device and composer
// workers: murmur register, the chatter line-safety law, hygiene, ring,
// fatigue/credence (credence is checked at PICK on the world thread; a
// queued speaker can still be marked heard by a nearby delivery during
// its 30-60 s display wait - delivery re-vets ring/fatigue but not
// credence, bounded to one extra retell; the round-3 ledger).
void EnqueueValidated(pocketllm::ChatterLayer layer, uint32 speakerGuid,
    uint32 listenerGuid, std::string const& rawLine, std::string const& factKey,
    bool originator, uint32 displayMin, uint32 displayMax)
{
    // tools are never licensed in chatter: any tool-bearing draw is
    // off-register and dropped whole (the pure scanner previews without
    // queueing - ExtractAndQueue is never called on this path)
    std::string cleaned;
    if (!pocketllm::ExtractToolCalls(rawLine, &cleaned).empty())
        return;
    std::string line = PlayerbotLlmFilters::HygienePass(cleaned, speakerGuid);
    if (!RegisterAdmits(line))
        return;
    line = pocketllm::ClampMurmurBytes(line, pocketllm::kMurmurMaxBytes);
    if (!pocketllm::ChatterLineSafe(line))
        return;  // newlines, pipes, protocol braces, emote leads: dropped

    std::lock_guard<std::mutex> lock(State().mutex);
    if (!pocketllm::RingAdmits(State().ring, line))
        return;
    if (!pocketllm::FatigueAdmits(State().fatigue, factKey))
        return;
    if (State().queue.size() >= kQueueCap)
        return;
    PendingLine entry;
    entry.layer = layer;
    entry.speakerGuid = speakerGuid;
    entry.listenerGuid = listenerGuid;
    entry.text = line;
    entry.factKey = factKey;
    entry.originator = originator;
    entry.templateIdx = kNoTemplate;
    entry.notBefore = time(nullptr) + WorkerRand(displayMin, displayMax);
    State().queue.push_back(entry);
}

// the device worker: ONE single-bot line against the resident embedded
// server through the shared HTTP client + governor (never a second
// runtime; never bypassing the interactive lane's pressure). The body
// runs under a catch-all so no residual throw (bad_alloc in the string
// building) can strand the batch flag (round-2 R1).
void RunDeviceBatchInner(DeviceJob const& job);
void RunComposerBatchInner(ComposerJob const& job);

void RunDeviceBatch(DeviceJob job)
{
    try
    {
        RunDeviceBatchInner(job);
    }
    catch (...)
    {
    }
    BatchInFlight().store(false);
}

void RunComposerBatch(ComposerJob job)
{
    try
    {
        RunComposerBatchInner(job);
    }
    catch (...)
    {
    }
    BatchInFlight().store(false);
}

void RunDeviceBatchInner(DeviceJob const& job)
{
    if (PlayerbotLLMInterface::InteractiveGenerationInFlight() ||
        !PlayerbotLLMInterface::GovernorAdmit(job.speakerGuid))
        return;
    std::string const note = NoteForLayer(job.layer, job.listenerName, job.telling);
    std::string const system = pocketllm::MurmurSystemMessage(
        job.persona.name, job.persona.race, job.persona.cls, job.persona.zone,
        job.persona.demeanor, job.persona.quirk, job.persona.gripe);
    std::string const user = note;
    std::string const body = pocketllm::BuildChatRequestBody(
        sPlayerbotAIConfig.llmApiModel, system, std::vector<pocketllm::HistoryTurn>(),
        user, AmbientSampling());
    std::string const http = PlayerbotLLMInterface::PostChatHttp(
        body, sPlayerbotAIConfig.llmGenerationTimeout, nullptr, nullptr);
    pocketllm::CompletionEnvelope envelope = pocketllm::ParseCompletionEnvelope(http);
    if (envelope.parsed && pocketllm::ContentUsable(envelope))
        EnqueueValidated(job.layer, job.speakerGuid, job.listenerGuid,
            envelope.content, job.factKey, job.originator,
            job.displayMinSec, job.displayMaxSec);
}

// the composer worker: ONE cloud call, 2-4 personas + the event rows,
// speaker-tagged script (the multi-party composer pattern). The parsed
// lines are speaker-validated against the persona list and each passes
// the same register/safety/ring/fatigue vetting before queueing. The
// enqueue loop stops at the fact's remaining telling budget - the 6th
// turn of a single-fact script would die at delivery re-vet anyway
// (round-1 R6: kMaxFactTellings caps every fact's chatter deliveries).
void RunComposerBatchInner(ComposerJob const& job)
{
    std::vector<std::string> personaLines, names;
    for (PersonaDims const& p : job.personas)
    {
        names.push_back(p.name);
        personaLines.push_back(p.name + " (" + p.race + " " + p.cls + ", " +
            p.gripe + ")");
    }
    std::string const body = pocketllm::BuildChatRequestBody(
        sPlayerbotAIConfig.llmChatterComposerModel,
        pocketllm::ComposerSystemPrompt(), std::vector<pocketllm::HistoryTurn>(),
        pocketllm::ComposerUserPrompt(personaLines, job.eventRows),
        [&]
        {
            pocketllm::RequestSampling s;
            s.temperature = 1.05f;
            s.topP = 0.95f;
            s.maxTokens = 280;
            s.minP = 0.05f;
            s.providerSafe = true;  // cloud endpoints reject unknown keys
            return s;
        }());
    std::string const http = PlayerbotLLMInterface::PostChatHttp(
        body, 30, &sPlayerbotAIConfig.llmChatterComposerUrlParsed,
        &sPlayerbotAIConfig.llmChatterComposerKey);
    pocketllm::CompletionEnvelope envelope = pocketllm::ParseCompletionEnvelope(http);
    if (envelope.parsed && pocketllm::ContentUsable(envelope) &&
        !job.factKeys.empty())
    {
        std::vector<pocketllm::ScriptLine> script =
            pocketllm::ParseComposerScript(envelope.content, names);
        // the multi-party script DELIVERS: every accepted turn queues
        // (speaker-tagged, staggered at the rung's display cadence),
        // capped by the persona count (the parser bounds speakerIdx by
        // it) and the fact's telling budget - a single-fact script
        // cannot borrow more than kMaxFactTellings deliveries.
        size_t const turnCap = std::min(script.size(),
            std::min(job.speakerGuids.size(),
                (size_t)pocketllm::ChatterFatigue::kMaxFactTellings));
        // turns of ONE exchange deliver IN ORDER: one base draw plus a
        // monotone per-turn stagger. Independent random draws reorder a
        // reply ahead of its setup line ~1/3 of the time (round-3
        // R1/R5/R6) - a scripted exchange reads as conversation only if
        // its order survives the queue.
        uint32 const perTurn = std::max<uint32>(6u,
            job.displayMaxSec > job.displayMinSec
                ? (job.displayMaxSec - job.displayMinSec) /
                    (uint32)std::max<size_t>(1, turnCap)
                : 0u);
        uint32 const base = WorkerRand(job.displayMinSec,
            std::max<uint32>(job.displayMinSec + perTurn,
                job.displayMaxSec > perTurn * (uint32)(turnCap - 1)
                    ? job.displayMaxSec - perTurn * (uint32)(turnCap - 1)
                    : job.displayMinSec + perTurn));
        for (size_t i = 0; i < turnCap; ++i)
        {
            std::string const& factKey = job.factKeys[std::min(i, job.factKeys.size() - 1)];
            EnqueueValidated(job.layer, job.speakerGuids[script[i].speakerIdx],
                0, script[i].text, factKey, true,
                base + (uint32)i * perTurn, base + (uint32)i * perTurn);
        }
    }
}

PersonaDims DimsOf(Player* bot, time_t now)
{
    PersonaDims d;
    d.name = bot->GetName();
    d.race = RaceWord(bot->getRace());
    d.cls = ClassWord(bot->getClass());
    d.zone = ZoneWord(bot);
    d.demeanor = pocketllm::DemeanorSeasoning(pocketllm::DemeanorOf(bot->GetGUIDLow()));
    d.quirk = pocketllm::QuirkSeasoning(pocketllm::QuirkOf(bot->GetGUIDLow()));
    d.gripe = pocketllm::GripeOf(bot->GetGUIDLow(), GripeSlot(now));
    return d;
}

// ---- delivery (world thread; the caller holds State().mutex).
// Returns false ONLY for the interruption deferral (the entry goes back
// with a short delay); every other failure is a final fail-silent drop -
// the queue refills on the next batch.
bool DeliverLine(PendingLine& entry, time_t now)
{
    ChatterState& s = State();
    Player* speaker = sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, entry.speakerGuid));
    if (!speaker || !speaker->IsInWorld() || !speaker->GetPlayerbotAI() ||
        !speaker->IsAlive() || speaker->IsInCombat())
        return true;  // fail-silent: the queue refills on the next batch
    if (speaker->GetPlayerbotAI()->IsRealPlayer())
        return true;  // human characters never speak for the chatter layer

    // re-vet at delivery: the ring may have moved under the queued line
    if (!pocketllm::RingAdmits(s.ring, entry.text))
        return true;
    if (!pocketllm::FatigueAdmits(s.fatigue, entry.factKey))
        return true;
    if (pocketllm::PlayerHoldsChannel(s.lastPlayerChatAt, now) &&
        entry.layer != pocketllm::LAYER_GLOBAL)
        return false;  // player chat owns the channel; retry shortly

    bool delivered = false;
    Player* listener = entry.listenerGuid
        ? sObjectAccessor.FindPlayer(ObjectGuid(HIGHGUID_PLAYER, entry.listenerGuid))
        : nullptr;

    if (entry.layer == pocketllm::LAYER_GLOBAL)
    {
        // rare set piece into the speaker's zone General channel (1.12
        // general is zone-scoped; bots join their zone's channel - the
        // JoinChatChannels idiom)
        ChannelMgr* cMgr = channelMgr(speaker->GetTeam());
        AreaTableEntry const* zone = speaker->GetPlayerbotAI()->GetCurrentZone();
        std::string const zoneName = zone
            ? speaker->GetPlayerbotAI()->GetLocalizedAreaName(zone) : "";
        if (cMgr && !zoneName.empty())
        {
            uint8 const locale = BroadcastHelper::GetLocale();
            for (uint32 i = 0; i < sChatChannelsStore.GetNumRows() && !delivered; ++i)
            {
                ChatChannelsEntry const* channel = sChatChannelsStore.LookupEntry(i);
                if (!channel || channel->ChannelID != ChatChannelId::GENERAL)
                    continue;
                char nameBuf[100];
                snprintf(nameBuf, sizeof(nameBuf), channel->pattern[locale],
                    zoneName.c_str());
#ifdef MANGOSBOT_ZERO
                Channel* chn = cMgr->GetJoinChannel(nameBuf);
#else
                Channel* chn = cMgr->GetJoinChannel(nameBuf, channel->ChannelID);
#endif
                if (chn)
                {
                    chn->Say(speaker, entry.text.c_str(), LANG_UNIVERSAL);
                    delivered = true;
                }
            }
        }
    }
    else if (entry.layer == pocketllm::LAYER_PARTY)
    {
        if (speaker->GetGroup())
        {
            speaker->GetPlayerbotAI()->SayToParty(entry.text);
            delivered = true;
        }
    }
    else
    {
        if (listener)
            speaker->SetFacingToObject(listener);  // two people talking
        speaker->Say(entry.text, LANG_UNIVERSAL);
        delivered = true;
    }
    if (!delivered)
        return true;

    // ---- the delivery-time ledger (the counter Skyrim lacked)
    pocketllm::RingRemember(s.ring, entry.text);
    pocketllm::FatigueRecordTelling(s.fatigue, entry.factKey);
    PlayerbotLlmFilters::RememberReply(entry.speakerGuid, entry.text);
    if (entry.floor && entry.templateIdx != kNoTemplate)
        pocketllm::TemplateSpacingRecord(s.fatigue, entry.templateIdx,
            entry.speakerGuid, entry.listenerGuid, now);

    // history: the murmur line joins the shared SAY channel window (the
    // A18 cross-injection cap applies at read time); party lines join
    // the party history
    uint32 const channelKey = entry.layer == pocketllm::LAYER_PARTY
        ? (0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_PARTY))
        : (0x80000000u | static_cast<uint32>(ChatChannelSource::SRC_SAY));
    PlayerbotLlmMemory::AppendTurn(entry.speakerGuid, channelKey, true,
        speaker->GetName(), entry.text);

    // credence: everyone who could hear it, heard it - bots in say range
    // of the speaker (murmur) or the speaker's group (party) never
    // RETELL this fact afterwards
    if (entry.layer == pocketllm::LAYER_MURMUR)
    {
        std::list<Player*> around;
        MaNGOS::AnyPlayerInObjectRangeCheck check(speaker, 30.0f);
        MaNGOS::PlayerListSearcher<MaNGOS::AnyPlayerInObjectRangeCheck> searcher(
            around, check);
        Cell::VisitWorldObjects(speaker, searcher, 30.0f);
        for (Player* other : around)
            if (other && other->GetPlayerbotAI())
                pocketllm::MarkHeard(s.fatigue, other->GetGUIDLow(), entry.factKey);
    }
    else if (entry.layer == pocketllm::LAYER_PARTY && speaker->GetGroup())
    {
        for (GroupReference* itr = speaker->GetGroup()->GetFirstMember(); itr; itr = itr->next())
            if (Player* member = itr->getSource())
                if (member->GetPlayerbotAI())
                    pocketllm::MarkHeard(s.fatigue, member->GetGUIDLow(), entry.factKey);
    }
    return true;
}

// bots near a real player, eligible to murmur (alive, out of combat)
std::vector<Player*> MurmurCandidatesNear(Player* player)
{
    std::vector<Player*> bots;
    std::list<Player*> around;
    MaNGOS::AnyPlayerInObjectRangeCheck check(player, 28.0f);
    MaNGOS::PlayerListSearcher<MaNGOS::AnyPlayerInObjectRangeCheck> searcher(
        around, check);
    Cell::VisitWorldObjects(player, searcher, 28.0f);
    for (Player* other : around)
        if (other && other->GetPlayerbotAI() && other->IsAlive() &&
            !other->IsInCombat() && !other->GetPlayerbotAI()->IsRealPlayer())
            bots.push_back(other);
    return bots;
}

std::vector<Player*> RealPlayersInWorld()
{
    std::vector<Player*> players;
    for (auto& entry : sRandomPlayerbotMgr.GetPlayers())
    {
        Player* player = entry.second;
        if (player && player->IsInWorld() && player->isRealPlayer())
            players.push_back(player);
    }
    return players;
}

void DispatchDeviceJob(DeviceJob job)
{
    BatchInFlight().store(true);
    // std::thread throws std::system_error on resource exhaustion - the
    // flag must not stick true and the world thread must not die (an
    // uncaught throw here terminates the world process; round-1 R5)
    try
    {
        std::thread(RunDeviceBatch, job).detach();
    }
    catch (...)
    {
        BatchInFlight().store(false);
    }
}

void DispatchComposerJob(ComposerJob job)
{
    BatchInFlight().store(true);
    try
    {
        std::thread(RunComposerBatch, job).detach();
    }
    catch (...)
    {
        BatchInFlight().store(false);
    }
}

} // namespace

// ---- public entry points (world thread) ------------------------------------

void PlayerbotLlmChatter::NotePlayerInteraction(uint32 playerGuid)
{
    (void)playerGuid;  // the hold is global (one channel per area scene);
                       // per-player holds would let two players fence the
                       // murmur layer in permanently
    std::lock_guard<std::mutex> lock(State().mutex);
    State().lastPlayerChatAt = time(nullptr);
}

void PlayerbotLlmChatter::OnDuelCompleted(Player* participant, Player* opponent)
{
    if (!participant || !opponent)
        return;
    Player* real = participant->isRealPlayer() ? participant
        : (opponent->isRealPlayer() ? opponent : nullptr);
    if (!real)
        return;  // bot-vs-bot duels are not world news at this layer
    std::lock_guard<std::mutex> lock(State().mutex);
    State().partyDuelNoteAt[real->GetGUIDLow()] = time(nullptr);
}

void PlayerbotLlmChatter::Tick()
{
    if (!sPlayerbotAIConfig.llmEnabled || !sPlayerbotAIConfig.llmChatterEnabled)
        return;

    time_t const now = time(nullptr);
    ChatterState& s = State();
    if (!s.rng)
        s.rng = (uint32)now | 1u;

    bool composerConfigured = false;
    pocketllm::ChatterPolicy policy;
    {
        std::lock_guard<std::mutex> lock(s.mutex);
        ReconcilePower(now);
        if (s.rung == pocketllm::RUNG_OFF)
            return;
        composerConfigured =
            !sPlayerbotAIConfig.llmChatterComposerUrlParsed.hostname.empty();
        policy = pocketllm::ChatterPolicyFor(s.rung, composerConfigured);
    }

    // ---- drain: due entries deliver oldest-first (max ~2 per tick so a
    // deep queue never machine-guns the channel); an interruption
    // deferral goes back to the FRONT with a short retry delay. The scan
    // SKIPS not-yet-due heads instead of stopping at them - a deferred
    // murmur head must not block a due global entry behind it
    // (round-2 R6).
    {
        std::deque<PendingLine> due;
        std::deque<PendingLine> deferred;
        {
            std::lock_guard<std::mutex> lock(s.mutex);
            int taken = 0;
            for (auto itr = s.queue.begin(); itr != s.queue.end() && taken < 2;)
            {
                if (itr->notBefore > now)
                {
                    ++itr;
                    continue;
                }
                due.push_back(*itr);
                itr = s.queue.erase(itr);
                ++taken;
            }
        }
        for (PendingLine& entry : due)
        {
            std::lock_guard<std::mutex> lock(s.mutex);
            if (!DeliverLine(entry, now))
            {
                entry.notBefore = now + 10;  // the player holds the channel
                deferred.push_back(entry);
            }
        }
        if (!deferred.empty())
        {
            std::lock_guard<std::mutex> lock(s.mutex);
            for (auto itr = deferred.rbegin(); itr != deferred.rend(); ++itr)
                s.queue.push_front(*itr);
        }
    }

    if (!policy.murmur && !policy.party && !policy.global)
        return;

    std::vector<Player*> players = RealPlayersInWorld();
    if (players.empty())
        return;

    // ---- murmur refill (never just-in-time: the queue drains at display
    // cadence; refill fires when the queue runs low AND the batch window
    // elapsed AND no batch is in flight AND the player has been quiet -
    // ambient batches never race a live conversation for the lane)
    if (policy.murmur && !BatchInFlight().load())
    {
        size_t murmurQueued = 0;
        time_t lastBatch = 0;
        bool quiet = false;
        {
            std::lock_guard<std::mutex> lock(s.mutex);
            for (PendingLine const& entry : s.queue)
                if (entry.layer == pocketllm::LAYER_MURMUR || entry.floor)
                    ++murmurQueued;
            lastBatch = s.lastMurmurBatchAt;
            quiet = pocketllm::AmbientAdmissionQuiet(s.lastPlayerChatAt, now);
        }
        if (quiet && murmurQueued < policy.murmurQueueLowWater &&
            (lastBatch == 0 || now - lastBatch >= policy.murmurBatchWindowSec))
        {
            {
                std::lock_guard<std::mutex> lock(s.mutex);
                s.lastMurmurBatchAt = now;
            }
            for (Player* player : players)
            {
                std::vector<Player*> bots = MurmurCandidatesNear(player);
                if (bots.size() < 2)
                    continue;
                size_t const speakerIdx = urand(0, uint32(bots.size() - 1));
                Player* speaker = bots[speakerIdx];
                Player* listener = bots[(speakerIdx + 1 + urand(0, uint32(bots.size() - 2))) % bots.size()];
                if (!speaker || !listener || speaker == listener)
                    continue;

                ChatterEventRow row;
                if (!PickGossipRow(speaker->GetGUIDLow(), false, row))
                    break;  // silence default: no event, no line, no floor
                std::string const telling = TellingTextFor(row);

                if (policy.composer)
                {
                    // SS4.6b: murmur is bot-to-bot (the player only
                    // overhears), so at NORMAL with a composer configured
                    // the batch is a CLOUD script over the nearby
                    // personas; per-bot device calls are the fallback
                    size_t const count = std::min<size_t>(3, bots.size());
                    ComposerJob job;
                    job.layer = pocketllm::LAYER_MURMUR;
                    for (size_t i = 0; i < count; ++i)
                    {
                        job.personas.push_back(DimsOf(bots[i], now));
                        job.speakerGuids.push_back(bots[i]->GetGUIDLow());
                    }
                    job.eventRows.push_back(telling);
                    job.factKeys.push_back(row.factKey);
                    job.displayMinSec = policy.murmurDisplayMinSec;
                    job.displayMaxSec = policy.murmurDisplayMaxSec;
                    DispatchComposerJob(job);
                }
                else if (policy.generated)
                {
                    DeviceJob job;
                    job.layer = pocketllm::LAYER_MURMUR;
                    job.speakerGuid = speaker->GetGUIDLow();
                    job.listenerGuid = listener->GetGUIDLow();
                    job.persona = DimsOf(speaker, now);
                    job.listenerName = listener->GetName();
                    job.telling = telling;
                    job.factKey = row.factKey;
                    job.originator = row.originatorForSpeaker;
                    job.displayMinSec = policy.murmurDisplayMinSec;
                    job.displayMaxSec = policy.murmurDisplayMaxSec;
                    DispatchDeviceJob(job);
                }
                else
                {
                    // EMERGENCY: the authored event-grounded floor. The
                    // template draw is fatigue-spaced per (template x
                    // speaker x listener) - the authored law. The
                    // line-safety law covers the floor too: the DB event
                    // text can carry pipes/newlines past the write chain
                    // (model-authored share_gossip rows) - fail silent,
                    // never voice them (round-2 R1/R6).
                    size_t const tpl = urand(0, uint32(pocketllm::MurmurFloorTemplateCount() - 1));
                    std::string const floorText = pocketllm::ClampMurmurBytes(
                        pocketllm::RenderFloorTemplate(tpl, speaker->GetName(),
                            listener->GetName(), telling),
                        pocketllm::kMurmurMaxBytes);
                    if (pocketllm::ChatterLineSafe(floorText))
                    {
                        std::lock_guard<std::mutex> lock(s.mutex);
                        if (now - s.lastFloorAt >= policy.floorMinSpacingSec &&
                            pocketllm::TemplateSpacingAdmits(s.fatigue, tpl,
                                speaker->GetGUIDLow(), listener->GetGUIDLow(),
                                now, policy.floorMinSpacingSec) &&
                            s.queue.size() < kQueueCap)
                        {
                            PendingLine entry;
                            entry.layer = pocketllm::LAYER_MURMUR;
                            entry.floor = true;
                            entry.speakerGuid = speaker->GetGUIDLow();
                            entry.listenerGuid = listener->GetGUIDLow();
                            entry.text = floorText;
                            entry.factKey = row.factKey;
                            entry.originator = row.originatorForSpeaker;
                            entry.templateIdx = tpl;
                            entry.notBefore = now + urand(policy.murmurDisplayMinSec,
                                policy.murmurDisplayMaxSec);
                            if (pocketllm::RingAdmits(s.ring, entry.text))
                            {
                                s.queue.push_back(entry);
                                s.lastFloorAt = now;
                            }
                        }
                    }
                }
                break;  // one murmur site per tick
            }
        }
    }

    // ---- party banter: per-master idle windows (the ~10-15 min Dragon
    // Age cadence) + the duel event note outranking the timer. The window
    // stamps on the ROLL (a lost roll waits out the next window - it must
    // not retry every 10 s tick; round-1 R6) and the duel note is
    // CONSUMED when it fires (a one-shot event bark, not a permanent
    // fast-fire switch; round-1 R1/R6 P0).
    if (policy.party && !BatchInFlight().load())
    {
        for (Player* master : players)
        {
            Group* group = master->GetGroup();
            if (!group || master->IsInCombat())
                continue;
            std::vector<Player*> bots;
            for (GroupReference* itr = group->GetFirstMember(); itr; itr = itr->next())
            {
                Player* member = itr->getSource();
                if (member && member != master && member->GetPlayerbotAI() &&
                    member->IsAlive() && !member->IsInCombat())
                    bots.push_back(member);
            }
            if (bots.size() < 2)
                continue;

            time_t duelNote = 0;
            {
                std::lock_guard<std::mutex> lock(s.mutex);
                auto itr = s.partyDuelNoteAt.find(master->GetGUIDLow());
                if (itr != s.partyDuelNoteAt.end())
                    duelNote = itr->second;
            }
            bool fire = false;
            {
                std::lock_guard<std::mutex> lock(s.mutex);
                time_t& windowAt = s.partyWindowAt[master->GetGUIDLow()];
                bool const windowElapsed = windowAt == 0 ||
                    now - windowAt >= policy.partyIdleWindowSec;
                if (duelNote && now - duelNote >= 60 && duelNote < now)
                {
                    // the event bark outranks the idle timer: fires once
                    // on the note (any window >= 60 s old), then the note
                    // is consumed - back to the idle cadence. The note is
                    // consumed ONLY when the bark will actually dispatch:
                    // a held channel leaves it armed to retry next tick
                    // (round-2 R1 - a consumed-in-silence bark is a lost
                    // event)
                    if ((windowAt == 0 || now - windowAt >= 60) &&
                        pocketllm::AmbientAdmissionQuiet(s.lastPlayerChatAt, now))
                    {
                        fire = true;
                        s.partyDuelNoteAt.erase(master->GetGUIDLow());
                        windowAt = now;
                    }
                }
                else if (windowElapsed)
                {
                    windowAt = now;  // the roll consumes the window, win or lose
                    fire = pocketllm::WindowRoll(s.rng, policy.partyIdleRollPct);
                    // a held channel defers the fired idle bark too (the
                    // roll already consumed its window - no stacking)
                    if (fire && !pocketllm::AmbientAdmissionQuiet(s.lastPlayerChatAt, now))
                        fire = false;
                }
            }
            if (!fire)
                continue;

            Player* speaker = bots[urand(0, uint32(bots.size() - 1))];
            ChatterEventRow topic;
            if (!PickPartyTopic(master, speaker->GetGUIDLow(), topic))
                continue;  // silence default: no topic, no banter

            std::string const telling = TellingTextFor(topic);
            if (policy.composer)
            {
                size_t const count = std::min<size_t>(4, std::max<size_t>(2, bots.size()));
                ComposerJob job;
                job.layer = pocketllm::LAYER_PARTY;
                for (size_t i = 0; i < count; ++i)
                {
                    job.personas.push_back(DimsOf(bots[i], now));
                    job.speakerGuids.push_back(bots[i]->GetGUIDLow());
                }
                job.eventRows.push_back(telling);
                job.factKeys.push_back(topic.factKey);
                DispatchComposerJob(job);
            }
            else if (policy.generated)
            {
                DeviceJob job;
                job.layer = pocketllm::LAYER_PARTY;
                job.speakerGuid = speaker->GetGUIDLow();
                job.listenerGuid = 0;
                job.persona = DimsOf(speaker, now);
                job.listenerName = master->GetName();
                job.telling = telling;
                job.factKey = topic.factKey;
                job.originator = true;
                job.displayMinSec = 6;
                job.displayMaxSec = 20;
                DispatchDeviceJob(job);
            }
            break;  // one party site per tick
        }
    }

    // ---- the rare global set piece: hard spacing floor + window roll,
    // duel-class (player-subject) rows preferred - "the world knows"
    if (policy.global)
    {
        bool roll = false;
        {
            std::lock_guard<std::mutex> lock(s.mutex);
            if (now - s.lastGlobalAt >= policy.globalMinSpacingSec &&
                (s.lastGlobalWindowAt == 0 ||
                    now - s.lastGlobalWindowAt >= policy.globalWindowSec))
            {
                s.lastGlobalWindowAt = now;
                roll = pocketllm::WindowRoll(s.rng, policy.globalRollPct);
            }
        }
        if (roll)
        {
            for (Player* player : players)
            {
                std::vector<Player*> bots = MurmurCandidatesNear(player);
                if (bots.empty())
                    continue;
                Player* speaker = bots[urand(0, uint32(bots.size() - 1))];
                ChatterEventRow row;
                if (!PickGossipRow(speaker->GetGUIDLow(), true, row))
                    if (!PickGossipRow(speaker->GetGUIDLow(), false, row))
                        break;  // nothing fresh to headline
                std::string const telling = TellingTextFor(row);
                {
                    std::lock_guard<std::mutex> lock(s.mutex);
                    s.lastGlobalAt = now;  // the spacing burns on the ROLL,
                                           // not the delivery - a dropped
                                           // line must not machine-gun rolls
                }
                if (policy.generated)
                {
                    if (BatchInFlight().load())
                        break;  // a busy lane skips this roll entirely - the
                                // authored floor is EMERGENCY-only (round-1 R1)
                    DeviceJob job;
                    job.layer = pocketllm::LAYER_GLOBAL;
                    job.speakerGuid = speaker->GetGUIDLow();
                    job.listenerGuid = 0;
                    job.persona = DimsOf(speaker, now);
                    job.listenerName = player->GetName();
                    job.telling = telling;
                    job.factKey = row.factKey;
                    job.originator = row.originatorForSpeaker;
                    job.displayMinSec = 5;
                    job.displayMaxSec = 15;
                    DispatchDeviceJob(job);
                }
                else
                {
                    // EMERGENCY global: the authored floor headline, held
                    // to the same line-safety law (round-2 R1/R6)
                    size_t const headlineTpl = 2;
                    std::string const headline = pocketllm::ClampMurmurBytes(
                        pocketllm::RenderFloorTemplate(headlineTpl, speaker->GetName(),
                            "everyone", telling),
                        pocketllm::kMurmurMaxBytes);
                    if (!pocketllm::ChatterLineSafe(headline))
                        break;
                    std::lock_guard<std::mutex> lock(s.mutex);
                    if (s.queue.size() < kQueueCap)
                    {
                        PendingLine entry;
                        entry.layer = pocketllm::LAYER_GLOBAL;
                        entry.floor = true;
                        entry.speakerGuid = speaker->GetGUIDLow();
                        entry.text = headline;
                        entry.factKey = row.factKey;
                        entry.originator = row.originatorForSpeaker;
                        entry.templateIdx = headlineTpl;
                        entry.notBefore = now + 5;
                        if (pocketllm::RingAdmits(s.ring, entry.text))
                            s.queue.push_back(entry);
                    }
                }
                break;  // one global attempt per tick
            }
        }
    }
}
