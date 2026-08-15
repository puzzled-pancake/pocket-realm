/*
 * Pocket Realm controller interaction for the pinned Vanilla client.
 *
 * WoW 1.12 has no INTERACTTARGET binding, and the client refuses to transmit
 * solo PARTY addon traffic, so the managed add-on sends one exact ordinary
 * self-whisper instead. The chat handler consumes it on the already
 * authenticated session before normal whisper delivery, so neither the
 * recipient CHAT_MSG_WHISPER nor the sender CHAT_MSG_WHISPER_INFORM is
 * produced and nothing appears in chat. Each whisper still counts toward the
 * realm's chat flood filter, so mashing the trigger roughly ten times a
 * second can mute chat briefly. This code selects only a nearby object that
 * normal cMaNGOS paths would accept. It never grants or stores loot directly.
 */

#include "Common.h"
#include "Server/WorldPacket.h"
#include "Server/WorldSession.h"
#include "Server/Opcodes.h"
#include "World/World.h"
#include "Entities/Player.h"
#include "Entities/Creature.h"
#include "Entities/GameObject.h"
#include "Entities/ObjectDefines.h"
#include "Loot/LootMgr.h"
#include "Spells/Spell.h"
#include "Spells/SpellMgr.h"
#include "Grids/GridNotifiersImpl.h"
#include "Grids/CellImpl.h"
#include "Util/Timer.h"
#include "Util/Util.h"

namespace
{
    char const* const kRequest = "PR6I:1 INTERACT";

    class NearbyDeadCreatureCheck
    {
        public:
            explicit NearbyDeadCreatureCheck(Player const& player) : m_player(player) {}
            WorldObject const& GetFocusObject() const { return m_player; }
            bool operator()(Creature* creature) const
            {
                return creature && !creature->IsAlive() && creature->IsInWorld() &&
                    m_player.IsWithinDistInMap(creature, INTERACTION_DISTANCE) &&
                    m_player.IsWithinLOSInMap(creature);
            }

        private:
            Player const& m_player;
    };

    class NearbyGameObjectCheck
    {
        public:
            explicit NearbyGameObjectCheck(Player const& player) : m_player(player) {}
            WorldObject const& GetFocusObject() const { return m_player; }
            bool operator()(GameObject* object) const
            {
                return object && object->IsInWorld() && object->IsSpawned() &&
                    m_player.IsWithinDistInMap(object, INTERACTION_DISTANCE) &&
                    m_player.IsWithinLOSInMap(object);
            }

        private:
            Player const& m_player;
    };

    bool IsSupportedNearbyGameObject(GameObject* object, Player* player)
    {
        if (!object || !player || !player->IsSelfMover() || player->IsBeingTeleported())
            return false;
        if (!object->IsWithinDistInMap(player, object->GetInteractionDistance()) ||
            !player->IsWithinDistInMap(object, INTERACTION_DISTANCE) ||
            !player->IsWithinLOSInMap(object) || !object->IsSpawned())
            return false;
        if (object->IsInUse() ||
            object->HasFlag(GAMEOBJECT_FLAGS, GO_FLAG_IN_USE) ||
            object->HasFlag(GAMEOBJECT_FLAGS, GO_FLAG_NO_INTERACT))
            return false;
        if (object->GetGOInfo()->CannotBeUsedUnderImmunity() &&
            player->HasFlag(UNIT_FIELD_FLAGS, UNIT_FLAG_IMMUNE))
            return false;

        if (object->GetGoType() == GAMEOBJECT_TYPE_CHEST)
        {
            // A normal Vanilla chest is intentionally spell-locked. Ask the
            // core's own spell validator whether ordinary Opening satisfies
            // the exact lock. Profession, key, and special locks stay out.
            SpellEntry const* opening = sSpellTemplate.LookupEntry<SpellEntry>(3365);
            if (!opening)
                return false;
            Spell openingCheck(player, opening, TRIGGERED_NONE);
            openingCheck.m_targets.setGOTarget(object);
            return openingCheck.CheckCast(true) == SPELL_CAST_OK;
        }

        // These are the direct CMSG_GAMEOBJ_USE preconditions. They must not
        // be applied to a chest, whose normal Opening spell is the authority.
        if (object->GetSpellForLock(player) ||
            object->HasFlag(GAMEOBJECT_FLAGS, GO_FLAG_LOCKED))
            return false;

        switch (object->GetGoType())
        {
            case GAMEOBJECT_TYPE_DOOR:
            case GAMEOBJECT_TYPE_BUTTON:
            case GAMEOBJECT_TYPE_QUESTGIVER:
            case GAMEOBJECT_TYPE_TEXT:
            case GAMEOBJECT_TYPE_GOOBER:
            case GAMEOBJECT_TYPE_CAMERA:
            case GAMEOBJECT_TYPE_MAILBOX:
                return true;
            default:
                return false;
        }
    }

    int InteractionPriority(WorldObject const* object)
    {
        if (object->GetTypeId() == TYPEID_UNIT)
            return 0;
        GameObject const* gameObject = static_cast<GameObject const*>(object);
        if (gameObject->GetGoType() == GAMEOBJECT_TYPE_CHEST ||
            gameObject->GetGOInfo()->GetLootId() != 0)
            return 0;
        if (gameObject->GetGoType() == GAMEOBJECT_TYPE_QUESTGIVER ||
            gameObject->GetGoType() == GAMEOBJECT_TYPE_GOOBER ||
            gameObject->GetGoType() == GAMEOBJECT_TYPE_TEXT)
            return 1;
        return 2;
    }

    bool IsBetterCandidate(Player const* player, WorldObject const* candidate, WorldObject const* current)
    {
        if (!current)
            return true;
        int candidatePriority = InteractionPriority(candidate);
        int currentPriority = InteractionPriority(current);
        if (candidatePriority != currentPriority)
            return candidatePriority < currentPriority;
        float candidateDistance = player->GetDistance(candidate, true, DIST_CALC_COMBAT_REACH);
        float currentDistance = player->GetDistance(current, true, DIST_CALC_COMBAT_REACH);
        if (candidateDistance != currentDistance)
            return candidateDistance < currentDistance;
        if (candidate->GetTypeId() != current->GetTypeId())
            return candidate->GetTypeId() == TYPEID_UNIT;
        return candidate->GetObjectGuid() < current->GetObjectGuid();
    }

    char AsciiLower(char c)
    {
        return (c >= 'A' && c <= 'Z') ? char(c - 'A' + 'a') : c;
    }

    // Character names are stored canonically, so a self-whisper target can
    // differ from GetName() only in capitalisation, never in content.
    bool IsSameNameCaseInsensitive(std::string const& a, std::string const& b)
    {
        if (a.size() != b.size())
            return false;
        for (std::string::size_type i = 0; i < a.size(); ++i)
            if (AsciiLower(a[i]) != AsciiLower(b[i]))
                return false;
        return true;
    }

    char const* ResultName(PocketRealmInteractResult result)
    {
        switch (result)
        {
            case POCKET_REALM_INTERACT_OK_LOOT: return "OK_LOOT";
            case POCKET_REALM_INTERACT_OK_USE: return "OK_USE";
            case POCKET_REALM_INTERACT_NO_TARGET: return "NO_TARGET";
            case POCKET_REALM_INTERACT_BLOCKED: return "BLOCKED";
        }
        return "BLOCKED";
    }
}

bool WorldSession::HandlePocketRealmChatTrigger(std::string const& to, std::string const& message)
{
    // Consume only the exact self-directed trigger; every other whisper
    // falls through to normal delivery, which also keeps a failed match
    // visible in chat instead of silently swallowed.
    if (message != kRequest)
        return false;

    Player* player = GetPlayer();
    if (!player || !IsSameNameCaseInsensitive(to, player->GetName()))
        return false;

    char const* response = "BLOCKED";
    if (!sWorld.getConfig(CONFIG_BOOL_POCKET_REALM_NEARBY_INTERACT))
        response = "DISABLED";
    else
    {
        uint32 now = WorldTimer::getMSTime();
        uint32 cooldownMs = sWorld.getConfig(CONFIG_UINT32_POCKET_REALM_NEARBY_INTERACT_COOLDOWN_MS);
        if (m_lastPocketRealmInteractTime != 0 &&
            WorldTimer::getMSTimeDiff(m_lastPocketRealmInteractTime, now) < cooldownMs)
        {
            response = "THROTTLED";
        }
        else
        {
            m_lastPocketRealmInteractTime = now;
            response = ResultName(HandlePocketRealmNearbyInteract());
        }
    }

    DEBUG_LOG("Pocket Realm nearby interact result: %s", response);
    return true;
}

PocketRealmInteractResult WorldSession::HandlePocketRealmNearbyInteract()
{
    Player* player = GetPlayer();
    if (!player || !player->IsAlive() || !player->IsInWorld() || !player->IsStandState() ||
        player->IsStunned() || !player->IsSelfMover() || player->IsBeingTeleported())
        return POCKET_REALM_INTERACT_BLOCKED;

    WorldObject* best = nullptr;

    CreatureList creatures;
    NearbyDeadCreatureCheck creatureCheck(*player);
    MaNGOS::CreatureListSearcher<NearbyDeadCreatureCheck> creatureSearch(creatures, creatureCheck);
    Cell::VisitGridObjects(player, creatureSearch, INTERACTION_DISTANCE);
    for (Creature* creature : creatures)
    {
        Loot* loot = creature->m_loot;
        if (!loot || !loot->CanLoot(player))
            continue;
        if (IsBetterCandidate(player, creature, best))
            best = creature;
    }

    GameObjectList gameObjects;
    NearbyGameObjectCheck gameObjectCheck(*player);
    MaNGOS::GameObjectListSearcher<NearbyGameObjectCheck> gameObjectSearch(gameObjects, gameObjectCheck);
    Cell::VisitGridObjects(player, gameObjectSearch, INTERACTION_DISTANCE);
    for (GameObject* object : gameObjects)
    {
        if (!IsSupportedNearbyGameObject(object, player))
            continue;
        if (IsBetterCandidate(player, object, best))
            best = object;
    }

    if (!best)
        return POCKET_REALM_INTERACT_NO_TARGET;

    if (best->GetTypeId() == TYPEID_UNIT)
    {
        WorldPacket request(CMSG_LOOT, 8);
        request << best->GetObjectGuid();
        HandleLootOpcode(request);
        return POCKET_REALM_INTERACT_OK_LOOT;
    }

    GameObject* gameObject = static_cast<GameObject*>(best);
    if (gameObject->GetGoType() == GAMEOBJECT_TYPE_CHEST)
    {
        // Vanilla opens chest loot through the ordinary Opening spell. A raw
        // CMSG_GAMEOBJ_USE only runs GameObject::Use, which can consume the
        // chest without creating its normal loot window.
        SpellEntry const* opening = sSpellTemplate.LookupEntry<SpellEntry>(3365);
        if (!opening)
            return POCKET_REALM_INTERACT_BLOCKED;

        SpellCastTargets targets;
        targets.setGOTarget(gameObject);
        Spell* spell = new Spell(player, opening, TRIGGERED_NONE);
        if (spell->SpellStart(&targets) != SPELL_CAST_OK)
            return POCKET_REALM_INTERACT_BLOCKED;
        return POCKET_REALM_INTERACT_OK_USE;
    }

    WorldPacket request(CMSG_GAMEOBJ_USE, 8);
    request << gameObject->GetObjectGuid();
    HandleGameObjectUseOpcode(request);
    return POCKET_REALM_INTERACT_OK_USE;
}
