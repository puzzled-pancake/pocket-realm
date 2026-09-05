package com.pocketrealm.bots

import com.pocketrealm.server.ChatterPowerMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS-D (plan v2.3 §5): world aliveness without reducing bots.
 *
 * D1 companion: the seven experience presets enable the existing
 * "range"/"map" login criteria — the emitted line is conditional, so every
 * other preset's playerbotConfig() (and thus its adv identity-digest
 * input, which hashes that text) stays byte-identical.
 *
 * D4: the village-ring preset fields ship DARK — count 0 on every catalog
 * profile and no VillageRing key in any emission until a profile opts in;
 * the field law is 0 = off or a village of 3-5 on a ring inside say range
 * (the native ListenRange.Say = 25 yd).
 *
 * D3: street life rides the already-quiet-gated Chatter ambient lane as a
 * per-preset rung CAP on the staged power file — never the legacy
 * masterless-say capacity (RandomBotSayWithoutMaster stays 0 everywhere).
 */
class BotWorldAlivenessTest {

    @Test
    fun everyExperiencePresetEnablesTheRangeAndMapLoginCriteria() {
        assertEquals(7, BotProfiles.experiencePresets.size)
        for (profile in BotProfiles.experiencePresets) {
            assertTrue("experience preset ${profile.id} must prefer near-player logins", profile.loginPreferNearPlayer)
            assertTrue(
                "experience preset ${profile.id} must emit the criteria line",
                profile.playerbotConfig()
                    .contains("AiPlayerbot.DefaultLoginCriteria = maxbots,spareroom,offline,range,map"),
            )
        }
    }

    @Test
    fun nonExperiencePresetsEmitNoLoginCriteriaLineAndNoVillageKeys() {
        // byte-identity law: anything that is not an experience preset
        // must not grow a single new line (the adv digest hashes this text)
        val nonExperience = BotProfiles.ids() - BotProfiles.experiencePresets.map { it.id }.toSet()
        assertTrue("catalog must have non-experience profiles", nonExperience.size >= 10)
        for (id in nonExperience) {
            val conf = BotProfiles.find(id)!!.playerbotConfig()
            assertFalse("$id must not emit DefaultLoginCriteria", conf.contains("DefaultLoginCriteria"))
            assertFalse("$id must not emit VillageRing keys (D4 ships DARK)", conf.contains("VillageRing"))
        }
    }

    @Test
    fun villageRingShipsDarkOnEveryCatalogProfile() {
        for (id in BotProfiles.ids()) {
            assertEquals("D4 ships DARK: $id must keep villageRingCount 0", 0, BotProfiles.find(id)!!.villageRingCount)
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
            .playerbotConfig()
        assertTrue(conf.contains("AiPlayerbot.VillageRingCount = 5"))
        assertTrue(conf.contains("AiPlayerbot.VillageRingMinYd = 10"))
        assertTrue(conf.contains("AiPlayerbot.VillageRingMaxYd = 25"))
        assertEquals(1, Regex("AiPlayerbot.VillageRingCount").findAll(conf).count())
    }

    @Test
    fun randomBotSayWithoutMasterStaysZeroOnEveryCatalogProfile() {
        // D3's negative pin: street life rides the Chatter queue, never the
        // bare masterless bot->Say path (un-arbitrated mechanic-speak)
        for (id in BotProfiles.ids()) {
            assertTrue(
                "$id must keep RandomBotSayWithoutMaster = 0",
                BotProfiles.find(id)!!.playerbotConfig().contains("AiPlayerbot.RandomBotSayWithoutMaster = 0"),
            )
        }
    }

    @Test
    fun experiencePresetsCarryANonOffChatterRungEveryoneElseFollows() {
        for (profile in BotProfiles.experiencePresets) {
            assertEquals(
                "experience preset ${profile.id} stages the Chatter ambient lane",
                BotLlmSpeech.CHATTER_RUNG_NORMAL,
                profile.llmSpeech.chatterRung,
            )
        }
        val nonExperience = BotProfiles.ids() - BotProfiles.experiencePresets.map { it.id }.toSet()
        for (id in nonExperience) {
            assertEquals(
                "$id must follow the computed ambience rung",
                BotLlmSpeech.CHATTER_RUNG_FOLLOW,
                BotProfiles.find(id)!!.llmSpeech.chatterRung,
            )
        }
    }

    @Test
    fun chatterRungFieldLawAndNormalize() {
        // the sentinel follows; 0..4 are the rungs; anything above clamps
        // into the band, negatives normalize to the sentinel
        assertEquals(BotLlmSpeech.CHATTER_RUNG_FOLLOW, BotLlmSpeech.normalize(0, -1, 0, 0).chatterRung)
        assertEquals(0, BotLlmSpeech.normalize(0, -1, 0, 0, chatterRung = 0).chatterRung)
        assertEquals(4, BotLlmSpeech.normalize(0, -1, 0, 0, chatterRung = 99).chatterRung)
        assertEquals(-1, BotLlmSpeech.normalize(0, -1, 0, 0, chatterRung = -7).chatterRung)
        // isDefault covers the new knob
        assertTrue(BotLlmSpeech().isDefault())
        assertFalse(BotLlmSpeech(chatterRung = BotLlmSpeech.CHATTER_RUNG_OFF).isDefault())
        // constructor rejects out-of-band values
        assertThrows { BotLlmSpeech(chatterRung = 5) }
    }

    @Test
    fun rungCapOnlyLowersTheComputedRung() {
        // D3: min(), never max() — the master toggle and the courtesy dim win
        val normal = ChatterPowerMonitor.RUNG_NORMAL
        val constrained = ChatterPowerMonitor.RUNG_CONSTRAINED
        assertEquals(normal, ChatterPowerMonitor.applyRungCap(normal, -1))
        assertEquals(normal, ChatterPowerMonitor.applyRungCap(normal, normal))
        assertEquals(constrained, ChatterPowerMonitor.applyRungCap(normal, constrained))
        assertEquals(constrained, ChatterPowerMonitor.applyRungCap(constrained, normal))
        assertEquals(ChatterPowerMonitor.RUNG_OFF, ChatterPowerMonitor.applyRungCap(normal, 0))
        assertEquals(normal, ChatterPowerMonitor.applyRungCap(normal, 99))
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
