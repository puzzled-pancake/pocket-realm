package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-profile PassiveDelay field and the benchmark-twin presets.
 *
 * Byte-identity: the DEFAULT 10_000 keeps every non-experience preset's
 * playerbotConfig() emission byte-identical (the adv/usr5 identity-digest
 * inputs must not shift); the seven experience presets emit 3_000 so a
 * greeted bot answers inside the 5 s greet bound.
 */
class BotPassiveDelayTest {

    @Test
    fun everyNonExperiencePresetKeepsTheLegacyTenSecondEmission() {
        // the legacy + bench catalog ids (the profiles map itself is
        // private; find() is the public resolver)
        val nonExperienceIds = listOf(
            "mobile-quiet-b25-v1", "mobile-typical-b50-v1",
            "mobile-balanced-b100-v1", "mobile-populated-b250-v1",
            "mobile-crowded-b400-v1", "mobile-busy-b600-v1",
            "mobile-launch-day-b700-v1",
        )
        val nonExperience = nonExperienceIds.mapNotNull(BotProfiles::find)
        assertTrue("catalog must resolve the legacy ids", nonExperience.size >= 5)
        for (profile in nonExperience) {
            assertEquals(
                "legacy byte-identity broken for ${profile.id}",
                10_000,
                profile.passiveDelayMs,
            )
        }
    }

    @Test
    fun everyExperiencePresetEmitsThreeSeconds() {
        assertEquals(7, BotProfiles.experiencePresets.size)
        for (profile in BotProfiles.experiencePresets) {
            assertEquals(
                "experience preset ${profile.id} must answer inside the greet bound",
                3_000,
                profile.passiveDelayMs,
            )
        }
    }

    @Test
    fun playerbotConfigEmitsTheFieldAndLegacyEmissionIsByteIdentical() {
        val legacy = BotProfiles.find("mobile-quiet-b25-v1")!!
        val conf = legacy.playerbotConfig()
        assertTrue("the emission carries the field", conf.contains("AiPlayerbot.PassiveDelay = 10000"))
        val tuned = BotProfiles.experiencePresets.first()
        assertTrue(
            tuned.playerbotConfig().contains("AiPlayerbot.PassiveDelay = 3000"),
        )
    }

    @Test
    fun theFieldIsBounded() {
        // the constructor rejects values outside 1_000..60_000
        // (spot-check both edges)
        assertThrows(IllegalArgumentException::class.java) {
            BotProfiles.experiencePresets.first().copy(passiveDelayMs = 999)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BotProfiles.experiencePresets.first().copy(passiveDelayMs = 60_001)
        }
    }

    @Test
    fun b4BenchTwinsCarryTheProposedTuplesAndStayUnselectable() {
        val low = BotProfiles.find("bench-low-power-b80-v2")!!
        val alive = BotProfiles.find("bench-alive-realm-b320-v2")!!
        // the proposed retunes: 1250 ms / 16 iter / 8% and 1500/18/15
        assertEquals(1_250, low.randomBotUpdateIntervalMs)
        assertEquals(16, low.iterationsPerTick)
        assertEquals(8, low.activeBotPercent)
        assertEquals(1_500, alive.randomBotUpdateIntervalMs)
        assertEquals(18, alive.iterationsPerTick)
        assertEquals(15, alive.activeBotPercent)
        // the retuned bench profiles stay outside the experience ladder
        // and are not player-selectable
        assertTrue(low !in BotProfiles.experiencePresets)
        assertTrue(alive !in BotProfiles.experiencePresets)
        assertTrue(low !in BotProfiles.legacySelectablePresets)
        assertTrue(alive !in BotProfiles.legacySelectablePresets)
        // the v1 presets stay resolvable and UNCHANGED (stored
        // selections keep their recorded behavior)
        assertEquals(2_500, BotProfiles.LOW_POWER_80.randomBotUpdateIntervalMs)
        assertEquals(8, BotProfiles.LOW_POWER_80.iterationsPerTick)
        assertEquals(3, BotProfiles.LOW_POWER_80.activeBotPercent)
        assertEquals(2_000, BotProfiles.ALIVE_REALM_320.randomBotUpdateIntervalMs)
        assertEquals(15, BotProfiles.ALIVE_REALM_320.iterationsPerTick)
        assertEquals(12, BotProfiles.ALIVE_REALM_320.activeBotPercent)
    }

    private fun assertThrows(
        expected: Class<IllegalArgumentException>,
        block: () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("expected ${expected.simpleName}")
        } catch (e: Throwable) {
            if (!expected.isInstance(e)) throw e
        }
    }
}
