import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native" / "patches" / "cmangos" / "PocketRealmInteraction.cpp"
BUILD = ROOT / "tools" / "build_o09_realm_runtime.py"


class PocketRealmNearbyInteractSourceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding="utf-8")
        cls.build = BUILD.read_text(encoding="utf-8")

    def test_vanilla_trigger_rides_self_whisper_before_normal_delivery(self):
        self.assertIn("case CHAT_MSG_WHISPER:", self.build)
        self.assertIn("if (HandlePocketRealmChatTrigger(to, msg))", self.build)
        self.assertNotIn("HandlePocketRealmAddonMessage", self.build)
        self.assertNotIn("lang == LANG_ADDON && HandlePocketRealmChatTrigger", self.build)
        self.assertNotIn("GetPlayer()->Whisper", self.source)

    def test_corpses_use_normal_loot_opcode(self):
        self.assertIn("WorldPacket request(CMSG_LOOT, 8);", self.source)
        self.assertIn("HandleLootOpcode(request);", self.source)

    def test_chests_use_standard_opening_spell(self):
        chest_branch = self.source.split(
            "if (gameObject->GetGoType() == GAMEOBJECT_TYPE_CHEST)", 1
        )[1].split("WorldPacket request(CMSG_GAMEOBJ_USE, 8);", 1)[0]
        self.assertIn("LookupEntry<SpellEntry>(3365)", chest_branch)
        self.assertIn("targets.setGOTarget(gameObject);", chest_branch)
        self.assertIn("new Spell(player, opening, TRIGGERED_NONE)", chest_branch)
        self.assertIn("spell->SpellStart(&targets)", chest_branch)
        self.assertNotIn("HandleGameObjectUseOpcode", chest_branch)

    def test_chest_eligibility_accepts_only_the_normal_opening_lock(self):
        eligibility = self.source.split(
            "bool IsSupportedNearbyGameObject", 1
        )[1].split("int InteractionPriority", 1)[0]
        chest_check = "if (object->GetGoType() == GAMEOBJECT_TYPE_CHEST)"
        direct_use_filter = "if (object->GetSpellForLock(player) ||"
        self.assertIn("Spell openingCheck(player, opening, TRIGGERED_NONE)", eligibility)
        self.assertIn("openingCheck.m_targets.setGOTarget(object)", eligibility)
        self.assertIn("openingCheck.CheckCast(true) == SPELL_CAST_OK", eligibility)
        self.assertLess(eligibility.index(chest_check), eligibility.index(direct_use_filter))
        chest_eligibility = eligibility.split(chest_check, 1)[1].split(direct_use_filter, 1)[0]
        self.assertNotIn("GO_FLAG_LOCKED", chest_eligibility)
        self.assertIn("LookupEntry<SpellEntry>(3365)", chest_eligibility)

    def test_ordinary_use_objects_retain_direct_lock_filters(self):
        eligibility = self.source.split(
            "bool IsSupportedNearbyGameObject", 1
        )[1].split("int InteractionPriority", 1)[0]
        direct_use = eligibility.split("// These are the direct CMSG_GAMEOBJ_USE preconditions.", 1)[1]
        self.assertIn("object->GetSpellForLock(player)", direct_use)
        self.assertIn("GO_FLAG_LOCKED", direct_use)

    def test_ordinary_objects_retain_normal_use_opcode(self):
        ordinary_branch = self.source.rsplit(
            "WorldPacket request(CMSG_GAMEOBJ_USE, 8);", 1
        )[1]
        self.assertIn("HandleGameObjectUseOpcode(request);", ordinary_branch)

    def test_no_direct_loot_grant_or_auto_collection(self):
        for forbidden in ("new Loot", "ShowContentTo", "LootSlot("):
            self.assertNotIn(forbidden, self.source)

    def test_candidates_reject_object_state_and_flag_in_use(self):
        self.assertIn("object->IsInUse()", self.source)
        self.assertIn("GO_FLAG_IN_USE", self.source)


class FlavorScopedChestPathContractTest(unittest.TestCase):
    """R1-1 (native-server round 2): the nearby-interact overlay is
    PER-FLAVOR. The shared source is vanilla-scoped — its Opening 3365
    emulation is the vanilla client's own chest cast, and port plan S2.3
    demands the Interact Target semantics re-derived per client. The tbc/
    wotlk variants derive the chest-opening spell from the core's OWN lock
    store (GameObject::GetSpellForLock — the same per-flavor authority
    CMSG_GAMEOBJ_USE refuses spell-locked objects with), so no vanilla-era
    single-flavor constant rides an expansion build."""

    @classmethod
    def setUpClass(cls):
        patches = ROOT / "native" / "patches" / "cmangos"
        cls.vanilla = (patches / "PocketRealmInteraction.cpp").read_text(encoding="utf-8")
        cls.variants = {
            flavor: (patches / f"PocketRealmInteraction-{flavor}.cpp").read_text(encoding="utf-8")
            for flavor in ("tbc", "wotlk")
        }
        cls.build = BUILD.read_text(encoding="utf-8")

    def test_the_opening_constant_is_vanilla_scoped(self):
        self.assertIn("LookupEntry<SpellEntry>(3365)", self.vanilla)
        for flavor, text in self.variants.items():
            self.assertNotIn(
                "3365", text,
                f"{flavor} variant carries the vanilla-era Opening constant — "
                "the chest path must derive from this client's lock store")

    def test_expansion_chest_paths_consult_the_core_lock_store(self):
        for flavor, text in self.variants.items():
            self.assertIn("object->GetSpellForLock(player)", text, flavor)
            self.assertIn("gameObject->GetSpellForLock(player)", text, flavor)
            # the core's own spell validator still gates both sites
            self.assertIn("CheckCast(true) == SPELL_CAST_OK", text, flavor)
            self.assertIn("spell->SpellStart(&targets)", text, flavor)
            self.assertIn("new Spell(player, opening, TRIGGERED_NONE)", text, flavor)

    def test_build_stages_the_per_flavor_variant_and_fails_loud_without_it(self):
        # both the staging write and the restore-verify leg resolve the
        # ACTIVE flavor's variant
        self.assertEqual(self.build.count("pocket_interact_source(ACTIVE_FLAVOR)"), 2, (
            "the interact staging and restore-verify legs must both route "
            "through the per-flavor source resolver"))
        self.assertIn("PocketRealmInteraction-{flavor_id}.cpp", self.build)
        self.assertIn("shared vanilla source must not ride", self.build)


if __name__ == "__main__":
    unittest.main()
