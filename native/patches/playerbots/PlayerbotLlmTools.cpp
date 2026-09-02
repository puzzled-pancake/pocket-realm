#include "PlayerbotLlmTools.h"

#include "PlayerbotLlmBridge.h"
#include "PlayerbotLlmMemory.h"
#include "PlayerbotLlmPrompt.h"
#include "PlayerbotLlmToolsCore.h"
#include "playerbot/playerbot.h"
#include "playerbot/PlayerbotAIConfig.h"
#include "playerbot/ServerFacade.h"
#include "strategy/actions/EmoteAction.h"
#include "Entities/Player.h"
#include "Entities/Bag.h"
#include "Groups/Group.h"
#include "Globals/ObjectAccessor.h"

#include <cctype>
#include <deque>
#include <map>
#include <mutex>
#include <sstream>

namespace {

struct QueuedCall
{
    std::string name;
    std::vector<std::pair<std::string, std::string>> fields;
    PlayerbotLlamaRuntime::LlmCallSource source = PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY;
    // interlocutor attribution: the real player whose turn triggered the
    // generation. Facts/sentiment must attribute to the SPEAKER - at scale
    // the whisperer is usually not the bot's owner (GetMaster()).
    uint32 speakerGuid = 0;
    // license stamp: identifies the note that licensed this call; the
    // executor admits the call only while the bot's live license still
    // carries this stamp AND the tool name (via LicensedLineFor).
    uint64_t licenseStamp = 0;
};

std::mutex g_queueMutex;
std::map<uint32, std::deque<QueuedCall>>& PendingQueue()
{
    static std::map<uint32, std::deque<QueuedCall>> instance;
    return instance;
}

std::string GetField(std::vector<std::pair<std::string, std::string>> const& fields, std::string const& key)
{
    for (auto const& pair : fields)
        if (pair.first == key)
            return pair.second;
    return "";
}

std::string ToLower(std::string const& text)
{
    std::string lower;
    lower.reserve(text.size());
    for (char c : text)
        lower.push_back((char)std::tolower((unsigned char)c));
    return lower;
}

// give_item lookup: the bot's own bags, by item NAME (the trained
// give_item carries a plain noun, not an item link, and
// InventoryParseItems only resolves links/ids/special words). Exact
// match first, then substring (the player says "hammer", the bag holds
// a "Blacksmith Hammer"). Never trusts the model's world claim: nothing
// found means nothing traded.
Item* FindBagItemByName(Player* bot, std::string const& name)
{
    std::string const needle = ToLower(name);
    if (needle.empty())
        return nullptr;

    std::vector<Item*> candidates;
    for (int slot = INVENTORY_SLOT_ITEM_START; slot < INVENTORY_SLOT_ITEM_END; ++slot)
        if (Item* item = bot->GetItemByPos(INVENTORY_SLOT_BAG_0, slot))
            if (item->CanBeTraded())
                candidates.push_back(item);
    for (int bag = INVENTORY_SLOT_BAG_START; bag < INVENTORY_SLOT_BAG_END; ++bag)
        if (Bag* bagObj = (Bag*)bot->GetItemByPos(INVENTORY_SLOT_BAG_0, bag))
            for (int slot = 0; slot < bagObj->GetBagSize(); ++slot)
                if (Item* item = bot->GetItemByPos(bag, slot))
                    if (item->CanBeTraded())
                        candidates.push_back(item);

    for (Item* item : candidates)
        if (ToLower(item->GetProto()->Name1) == needle)
            return item;
    for (Item* item : candidates)
        if (ToLower(item->GetProto()->Name1).find(needle) != std::string::npos)
            return item;
    // the player speaks plurals ("one of your hammers", "torches") -
    // retry the singular and the es-stripped stem once before giving up
    if (needle.size() > 1 && needle.back() == 's')
    {
        std::string const singular = needle.substr(0, needle.size() - 1);
        for (Item* item : candidates)
            if (ToLower(item->GetProto()->Name1).find(singular) != std::string::npos)
                return item;
        std::string const stem = needle.size() > 2 && needle.compare(needle.size() - 2, 2, "es") == 0
            ? needle.substr(0, needle.size() - 2) : std::string();
        if (!stem.empty())
            for (Item* item : candidates)
                if (ToLower(item->GetProto()->Name1).find(stem) != std::string::npos)
                    return item;
    }
    return nullptr;
}

// Pre-commit duel guard (the strictest class of
// live-state validation before the duel cast): every condition is
// checked against the world, none against what the model said. Mirrors
// RpgDuelAction::isUseful's area legality plus both-sides duel state,
// combat and proximity.
bool CanCommitDuel(Player* bot, Player* player)
{
    if (!player || player == bot || !player->isRealPlayer())
        return false;
    if (!bot->IsInWorld() || !bot->IsAlive() || bot->IsInCombat())
        return false;
    if (!player->IsAlive() || player->IsInCombat())
        return false;
    // an arbiter flag already stands on either side
    if (bot->duel || player->duel)
        return false;
    // duels need the area's duel bit and a non-PvP-prohibited zone
    if (sPlayerbotAIConfig.IsInPvpProhibitedZone(sServerFacade.GetAreaId(bot)))
        return false;
    AreaTableEntry const* area = GetAreaEntryByAreaID(sServerFacade.GetAreaId(bot));
    if (area && !(area->flags & AREA_FLAG_DUEL))
        return false;
    if (sServerFacade.GetDistance2d(bot, player) > sPlayerbotAIConfig.sightDistance)
        return false;
    return true;
}

// Every field the BRIDGE decided (names, items, direction, emote, loot
// choice, category) executes from the LICENSED note line - the model's
// copy of those fields is never trusted (a rewritten item=, a flipped
// direction= or a retargeted name= is a mismatch the license simply
// does not carry). Only fill-hint fields (text/reason) are the model's
// own prose and read from the queued call.
std::string LicensedField(std::string const& licensedLine, std::string const& key)
{
    if (licensedLine.empty())
        return "";
    std::string cleaned;
    std::vector<pocketllm::ToolCall> const calls =
        pocketllm::ExtractToolCalls(licensedLine, &cleaned);
    for (pocketllm::ToolCall const& call : calls)
        for (auto const& kv : call.fields)
            if (kv.first == key)
                return kv.second;
    return "";
}

} // namespace

std::string PlayerbotLlmTools::ToolInstructions(uint32 botGuid)
{
    // The trained wording, verbatim - the same per-GUID variant
    // selection the trained system prompt uses (crc32 % 4 over the stable
    // persona id). The earlier hardcoded text licensed
    // model-initiated tool use from free chat ("use them only when they
    // fit"), which the trained contract replaces with note-driven
    // dispatch (no note -> no tools).
    return pocketllm::ToolsNoteForCardId(std::to_string(botGuid));
}

std::string PlayerbotLlmTools::ExtractAndQueue(std::string const& raw, uint32 botGuid,
    uint32 speakerGuid, PlayerbotLlamaRuntime::LlmCallSource source, uint64_t licenseStamp)
{
    std::string cleaned;
    std::vector<pocketllm::ToolCall> const calls =
        pocketllm::ExtractToolCalls(raw, &cleaned);

    if (calls.empty())
        return cleaned;

    // Admission, at queue time: a call must be a KNOWN tool (the
    // banklib vocabulary - anything else is protocol leakage) and must
    // be licensed by the note THAT DROVE THIS GENERATION - the stamp is
    // threaded from the generation's own note build, not read from the
    // bot's live license at completion time, so an interleaved newer
    // note can never cover for this generation and a note-less
    // generation (autonomous RPG/debug, stamp 0) queues nothing at all.
    if (licenseStamp == 0)
        return cleaned; // this generation built no note: nothing licensed
    PlayerbotLlmBridge::ToolLicense const license =
        PlayerbotLlmBridge::CurrentLicense(botGuid);
    if (license.stamp != licenseStamp)
        return cleaned; // a newer note superseded this generation's: drop

    std::lock_guard<std::mutex> lock(g_queueMutex);
    std::deque<QueuedCall>& queue = PendingQueue()[botGuid];
    for (pocketllm::ToolCall const& call : calls)
    {
        if (!pocketllm::IsKnownTool(call.name))
            continue;
        if (!license.tools.count(call.name))
            continue; // this note never licensed the tool
        QueuedCall queued;
        queued.name = call.name;
        queued.fields = call.fields;
        queued.source = source;
        queued.speakerGuid = speakerGuid;
        queued.licenseStamp = licenseStamp;
        if (queue.size() < 8)
            queue.push_back(queued);
    }
    return cleaned;
}

void PlayerbotLlmTools::PlayTextEmote(Player* bot, Player* target, std::string const& emoteName)
{
    if (!bot)
        return;
    uint32 const textEmote = pocketllm::ResolveTextEmote(emoteName);
    if (!textEmote || !bot->IsInWorld() || !bot->IsAlive())
        return;
    PlayerbotAI* ai = bot->GetPlayerbotAI();
    if (!ai)
        return;
    ObjectGuid const oldSelection = bot->GetSelectionGuid();
    if (target && target->IsInWorld())
    {
        // face and target the interlocutor (the emote answers them),
        // like EmoteActionBase::Emote
        bot->SetSelectionGuid(target->GetObjectGuid());
    }
    WorldPacket data(SMSG_TEXT_EMOTE);
    data << textEmote;
    data << urand(0, ai::EmoteActionBase::GetNumberOfEmoteVariants(
        (TextEmotes)textEmote, bot->getRace(), bot->getGender()) - 1);
    data << (bot->GetSelectionGuid() ? bot->GetSelectionGuid() : ObjectGuid());
    bot->GetSession()->HandleTextEmoteOpcode(data);
    if (oldSelection)
        bot->SetSelectionGuid(oldSelection);
}

void PlayerbotLlmTools::ExecutePending(Player* bot)
{
    if (!bot || !bot->GetPlayerbotAI() || !sPlayerbotAIConfig.llmEnabled)
        return;

    std::deque<QueuedCall> calls;
    {
        std::lock_guard<std::mutex> lock(g_queueMutex);
        auto itr = PendingQueue().find(bot->GetGUIDLow());
        if (itr == PendingQueue().end() || itr->second.empty())
            return;
        calls.swap(itr->second);
    }

    // validated against live world state at execution time; the model's claim
    // about the world is never trusted on its own
    for (QueuedCall const& call : calls)
    {
        // Executor license cross-check: a queued call executes only when
        // the note that
        // licensed its QUEUE entry still matches the bot's live license -
        // same stamp, same tool. Zero executions from unlicensed turns,
        // by construction, on every backend. ONE locked read gates and
        // fetches together (RecordLicense fills tools and
        // lineByTool in the same loop, so a non-empty line IS the
        // coverage proof - a separate check would double the locked
        // copies and could straddle a concurrent record). The licensed
        // note line is the field authority for everything the BRIDGE
        // decided; only fill-hint prose (text/reason) reads the model's
        // copy.
        std::string const licensedLine = PlayerbotLlmBridge::LicensedLineFor(
            bot->GetGUIDLow(), call.licenseStamp, call.name);
        if (licensedLine.empty())
            continue; // superseded, unlicensed, or note-less

        // interlocutor attribution: persistence/ACT tools attribute to and act
        // toward the player who actually SPOKE (carried through the
        // generation), resolved live at execution time - never the bot's
        // owner (GetMaster()), which at scale is a different player than
        // the whisperer. Zero means an autonomous turn; the playerTurn
        // guard below then refuses writes and ACT execution.
        Player* player = nullptr;
        if (call.speakerGuid)
        {
            Player* found = sObjectAccessor.FindPlayer(
                ObjectGuid(HIGHGUID_PLAYER, call.speakerGuid));
            if (found && found->isRealPlayer())
                player = found;
        }

        // persistence tools are player-conversation-only: an autonomous (RPG
        // NPC-chat) generation never reflects the player's actual words, so
        // it must not write facts about them or drift the relationship
        // accumulator. ACT tools are the same class - they act toward the
        // speaker. perform_emote is a harmless visual; share_gossip keeps
        // its own verified-event window below.
        bool const playerTurn = call.source == PlayerbotLlamaRuntime::LLM_SRC_CHAT_REPLY;
        PlayerbotAI* ai = bot->GetPlayerbotAI();

        if (call.name == "log_fact")
        {
            std::string text = GetField(call.fields, "text");
            // category is bridge-decided (the ready line carried it); the
            // text is the model's filled prose
            std::string const category = LicensedField(licensedLine, "category");
            if (playerTurn && player && !text.empty() && !category.empty())
                PlayerbotLlmMemory::LogFact(bot->GetGUIDLow(), player->GetGUIDLow(),
                    text, category);
        }
        else if (call.name == "adjust_sentiment")
        {
            // direction is bridge-decided (judgment is the
            // model's weakest axis - the note supplies it ready-made); only
            // the reason string is the model's
            std::string const direction = LicensedField(licensedLine, "direction");
            int32 delta = direction == "+1" ? 1 : direction == "-1" ? -1 : 0;
            if (playerTurn && player && delta)
                PlayerbotLlmMemory::AddBoundedSentimentInput(bot->GetGUIDLow(),
                    player->GetGUIDLow(), delta, GetField(call.fields, "reason"));
        }
        else if (call.name == "share_gossip")
        {
            std::string text = GetField(call.fields, "text");
            // gated: only alongside a verified event, never on free chat — an
            // invented row would persist into every other bot's context
            if (!text.empty() && PlayerbotLlmMemory::HasRecentVerifiedEvent(bot->GetGUIDLow()))
                PlayerbotLlmMemory::ShareGossip(bot->GetGUIDLow(), text, "event");
        }
        else if (call.name == "perform_emote")
        {
            // The module's TEXT-emote path (SMSG_TEXT_EMOTE through
            // HandleTextEmoteOpcode resolves the animation via
            // EmotesText.dbc where one exists AND prints the authentic
            // "X grins." line to every nearby player). The emote VALUE is
            // bridge-decided (the ready line carried it) - the shared
            // delivery is the same path the authored crowd tier uses.
            PlayTextEmote(bot, player, LicensedField(licensedLine, "emote"));
        }
        // ---- ACT tools: mapped to existing playerbots surfaces.
        // Every bridge-decided field executes from the LICENSED line, so
        // the acting target is always the SPEAKER the bridge licensed (a
        // model-rewritten name/item is a mismatch the license does not
        // carry), and only on a player conversation turn.
        else if (call.name == "duel_challenge")
        {
            if (!playerTurn || !player || !CanCommitDuel(bot, player))
                continue;
            if (LicensedField(licensedLine, "name") != player->GetName())
                continue;
            // the RpgDuel idiom: challenge spell 7266 through the cast
            // action, which resolves range/LOS/facing like a hand-typed
            // command would
            ai->DoSpecificAction("cast", Event("llm action",
                ai->GetChatHelper()->formatWorldobject(player) + " 7266"), true);
        }
        else if (call.name == "give_item")
        {
            if (!playerTurn || !player)
                continue;
            if (LicensedField(licensedLine, "player") != player->GetName())
                continue;
            // the item comes from the licensed line too - the model cannot
            // redirect the trade at something the bridge never offered
            std::string const itemName = LicensedField(licensedLine, "item");
            Item* const item = FindBagItemByName(bot, itemName);
            if (!item)
                continue; // the bridge's noun matches nothing tradeable
            // the RpgTradeUsefulAction idiom: open the trade toward the
            // player, then hand the item through the trade handler. A trade
            // window already open with SOMEONE ELSE must not receive the
            // item (TradeAction adds to whatever trade is live) - hand
            // only into a trade with the licensed speaker.
            Player* const trader = bot->GetTrader();
            if (trader && trader != player)
                continue;
            if (!trader)
            {
                WorldPacket packet(CMSG_INITIATE_TRADE);
                packet << player->GetObjectGuid();
                bot->GetSession()->HandleInitiateTradeOpcode(packet);
            }
            std::ostringstream param;
            param << ai->GetChatHelper()->formatWorldobject(player) << " "
                  << ai->GetChatHelper()->formatItem(item);
            ai->DoSpecificAction("trade", Event("llm action", param.str()), true);
        }
        else if (call.name == "follow")
        {
            if (!playerTurn || !player)
                continue;
            // the module's follow machinery follows the bot's MASTER -
            // execute only when the licensed speaker is that master
            // (following a stranger is not a supported relation)
            if (ai->GetMaster() != player)
                continue;
            if (LicensedField(licensedLine, "name") != player->GetName())
                continue;
            ai->ChangeStrategy("+follow", BotState::BOT_STATE_NON_COMBAT);
        }
        else if (call.name == "party_invite")
        {
            if (!playerTurn || !player || player == bot)
                continue;
            if (LicensedField(licensedLine, "name") != player->GetName())
                continue;
            if (bot->GetGroup() && bot->GetGroup()->IsMember(player->GetObjectGuid()))
                continue; // already grouped together
            ai->DoSpecificAction("invite", Event("llm action", player->GetName()), true);
        }
        else if (call.name == "loot_roll")
        {
            // loot choice parity with the trained LOOT_CHOICES, read from
            // the LICENSED line (no beat licenses a roll yet - the branch
            // lights up when an
            // event nudge carries one; the roll handler itself no-ops
            // when no roll is actually open for the bot)
            std::string const choice = LicensedField(licensedLine, "choice");
            if (choice != "need" && choice != "greed" && choice != "pass")
                continue;
            if (!playerTurn || !player)
                continue;
            ai->DoSpecificAction("roll", Event("llm action", choice), true);
        }
        else if (call.name == "move_to")
        {
            // move_to: resolved against the lore loop's POI cards. The place
            // executes from the LICENSED line (bridge-resolved canonical
            // title), re-resolved against the index here - the world (or
            // the corpus) may have changed since the note was built, and
            // an unresolvable place is a refusal, never a blind path.
            // The go action's travel branch keys on a "to <name>" param
            // (the chat command word is already stripped by the time
            // GoAction sees the parameter) and resolves its requester
            // from the event owner - the licensed SPEAKER, so
            // masterless random bots (the usual whisperers) move too.
            if (!playerTurn || !player)
                continue;
            std::string const place = LicensedField(licensedLine, "place");
            std::string canonical;
            if (place.empty() ||
                !PlayerbotLlmBridge::ResolvePoiPlace(place, &canonical))
                continue;
            ai->DoSpecificAction("go", Event("llm action", "to " + canonical, player), true);
        }
    }
}
