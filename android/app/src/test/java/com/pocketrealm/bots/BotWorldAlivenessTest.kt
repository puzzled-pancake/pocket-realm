package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * World aliveness without reducing bot counts.
 *
 * The seven experience presets enable the existing "range"/"map" login
 * criteria — the emitted line is conditional, so every other preset's
 * playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA) (and thus its adv identity-digest input, which hashes
 * that text) stays byte-identical.
 *
 * The village-ring preset fields ship dark: count 0 on every catalog
 * profile and no VillageRing key in any emission until a profile opts
 * in; the field is either 0 (off) or a village of 3-5 on a ring inside
 * say range (the native ListenRange.Say = 25 yd).
 *
 * The catalog never opens the legacy masterless-say capacity
 * (RandomBotSayWithoutMaster stays 0 on every catalog profile).
 */
class BotWorldAlivenessTest {
    /** flip fixture: the sole-qualified degrade is retired; machinery
     * tests pin the VANILLA lane (holder select) so resolvers see one lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }


    @Test
    fun everyExperiencePresetEnablesTheRangeAndMapLoginCriteria() {
        assertEquals(3, BotProfiles.experiencePresets.size)
        for (profile in BotProfiles.experiencePresets) {
            assertTrue("experience preset ${profile.id} must prefer near-player logins", profile.loginPreferNearPlayer)
            assertTrue(
                "experience preset ${profile.id} must emit the criteria line",
                profile.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA)
                    .contains("AiPlayerbot.DefaultLoginCriteria = maxbots,spareroom,offline,range,map"),
            )
        }
    }

    @Test
    fun nonExperiencePresetsEmitNoLoginCriteriaLineAndNoVillageKeys() {
        // byte-identity law: anything that is not an experience preset
        // must not grow a single new line (the adv digest hashes this text)
        val nonExperience = BotProfiles.ids() -
            (BotProfiles.experiencePresets + BotProfiles.legacySelectablePresets)
                .filter { it.loginPreferNearPlayer }.map { it.id }.toSet()
        assertTrue("catalog must have non-experience profiles", nonExperience.size >= 10)
        for (id in nonExperience) {
            val conf = BotProfiles.find(id)!!.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA)
            assertFalse("$id must not emit DefaultLoginCriteria", conf.contains("DefaultLoginCriteria"))
            assertFalse("$id must not emit VillageRing keys", conf.contains("VillageRing"))
        }
    }

    @Test
    fun villageRingShipsDarkOnEveryCatalogProfile() {
        for (id in BotProfiles.ids()) {
            assertEquals("$id must keep villageRingCount 0", 0, BotProfiles.find(id)!!.villageRingCount)
        }
        // and the yd defaults travel with the field everywhere
        for (id in BotProfiles.ids()) {
            val profile = BotProfiles.find(id)!!
            assertEquals(10, profile.villageRingMinYd)
            assertEquals(25, profile.villageRingMaxYd)
        }
    }

    @Test
    fun villageRingFieldLawIsOffOrAVillageOfThreeToFiveInsideSayRange() {
        val base = BotProfiles.experiencePresets.first()
        assertThrows { base.copy(villageRingCount = 1) }
        assertThrows { base.copy(villageRingCount = 2) }
        assertThrows { base.copy(villageRingCount = 6) }
        assertThrows { base.copy(villageRingMaxYd = 26) }
        assertThrows { base.copy(villageRingMinYd = 26, villageRingMaxYd = 26) }
        // 3-5 on a 10-25 band is the legal village
        assertEquals(4, base.copy(villageRingCount = 4).villageRingCount)
    }

    @Test
    fun anOptedInProfileEmitsExactlyTheThreeVillageRingKeys() {
        val conf = BotProfiles.experiencePresets.first()
            .copy(villageRingCount = 5)
            .playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA)
        assertTrue(conf.contains("AiPlayerbot.VillageRingCount = 5"))
        assertTrue(conf.contains("AiPlayerbot.VillageRingMinYd = 10"))
        assertTrue(conf.contains("AiPlayerbot.VillageRingMaxYd = 25"))
        assertEquals(1, Regex("AiPlayerbot.VillageRingCount").findAll(conf).count())
    }

    @Test
    fun randomBotSayWithoutMasterStaysZeroOnEveryCatalogProfile() {
        // negative pin: the catalog never opens the bare masterless
        // bot->Say path (un-arbitrated mechanic-speak)
        for (id in BotProfiles.ids()) {
            assertTrue(
                "$id must keep RandomBotSayWithoutMaster = 0",
                BotProfiles.find(id)!!.playerbotConfig(
                    com.pocketrealm.client.ClientFlavors.VANILLA,
                ).contains("AiPlayerbot.RandomBotSayWithoutMaster = 0"),
            )
        }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
