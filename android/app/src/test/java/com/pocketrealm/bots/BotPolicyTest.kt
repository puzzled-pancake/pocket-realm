package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotPolicyTest {
    @Test fun lowProfileNeverInheritsUnsafeUpstreamServicesOrCounts() {
        val profile = BotProfiles.LOW_25
        val config = profile.playerbotConfig()

        assertEquals(25, profile.selectedTarget)
        assertEquals(3, profile.accountCount)
        assertTrue(config.contains("AiPlayerbot.MinRandomBots = 20"))
        assertTrue(config.contains("AiPlayerbot.MaxRandomBots = 25"))
        assertTrue(config.contains("AiPlayerbot.RandomBotsMaxLoginsPerInterval = 5"))
        assertTrue(config.contains("PocketRealm.GenerationBatchSize = 5"))
        assertTrue(config.contains("PocketRealm.GenerationYieldMs = 250"))
        assertTrue(config.contains("AiPlayerbot.CommandServerPort = 0"))
        assertTrue(config.contains("AiPlayerbot.LLMEnabled = 0"))
        assertTrue(config.contains("AiPlayerbot.RandomBotJoinBG = 0"))
        assertTrue(config.contains("AiPlayerbot.RandomBotArenaTeamCount = 0"))
        assertTrue(config.contains("AiPlayerbot.AllowMultiAccountAltBots = 0"))
        assertTrue(config.contains("AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 0"))
        assertFalse(config.contains("AiPlayerbot.MinRandomBots = 1000"))
        assertFalse(config.contains("AiPlayerbot.MaxRandomBots = 1000"))
        assertFalse(config.contains("AuctionHouseBot"))
    }

    @Test fun overloadReducesByOneBoundedStepAndHonorsCooldownAndFloor() {
        val controller = BotAdmissionController(BotProfiles.LOW_25)
        val warmup = BotProfiles.LOW_25.admission.performanceWarmupMs
        fun sample(at: Long) = BotResourceSample(at, 25, 251, 2_000, 4_000, ThermalLevel.NONE)

        assertFalse(controller.observe(sample(0)).changed)
        val first = controller.observe(sample(warmup))
        assertTrue(first.changed)
        assertEquals(20, first.effectiveTarget)
        val duringCooldown = controller.observe(sample(warmup + 5_000))
        assertFalse(duringCooldown.changed)
        assertEquals(20, duringCooldown.effectiveTarget)
        val atFloor = controller.observe(sample(warmup + 20_000))
        assertFalse(atFloor.changed)
        assertEquals(20, atFloor.effectiveTarget)
        assertEquals(25, atFloor.selectedTarget)
    }

    @Test fun fiveHealthyMinutesRestoreSelectedTargetWithoutChangingProfile() {
        val controller = BotAdmissionController(BotProfiles.LOW_25)
        val overloaded = BotResourceSample(0, 25, 100, 700, 4_000, ThermalLevel.NONE)
        assertEquals(20, controller.observe(overloaded).effectiveTarget)
        val warmup = BotProfiles.LOW_25.admission.performanceWarmupMs
        val healthy = { at: Long -> BotResourceSample(warmup + at, 20, 100, 2_000, 4_000, ThermalLevel.NONE) }

        // First healthy sample starts the post-ramp performance warm-up.
        assertEquals(20, controller.observe(healthy(10_000)).effectiveTarget)
        assertEquals(20, controller.observe(healthy(warmup + 10_000)).effectiveTarget)
        val restored = controller.observe(healthy(warmup + 310_000))
        assertTrue(restored.changed)
        assertEquals(25, restored.effectiveTarget)
        assertFalse(restored.adapted)
        assertEquals(25, restored.selectedTarget)
    }

    @Test fun moderateThermalPausesRampWhileSevereThermalReduces() {
        val moderate = BotAdmissionController(BotProfiles.LOW_25).observe(
            BotResourceSample(0, 25, 100, 2_000, 4_000, ThermalLevel.MODERATE),
        )
        assertFalse(moderate.changed)
        assertEquals(25, moderate.effectiveTarget)

        val severe = BotAdmissionController(BotProfiles.LOW_25).observe(
            BotResourceSample(0, 25, 100, 2_000, 4_000, ThermalLevel.SEVERE),
        )
        assertTrue(severe.changed)
        assertEquals(20, severe.effectiveTarget)
    }

    @Test fun repeatedHardStallsReduceEvenWhenPercentileIsBelowBudget() {
        val controller = BotAdmissionController(BotProfiles.LOW_25)
        val warmup = BotProfiles.LOW_25.admission.performanceWarmupMs
        controller.observe(BotResourceSample(0, 25, 100, 2_000, 4_000, ThermalLevel.NONE))
        val state = controller.observe(BotResourceSample(warmup,
            25, 100, 2_000, 4_000, ThermalLevel.NONE, hardStallCount = 2))
        assertTrue(state.changed)
        assertEquals(20, state.effectiveTarget)
        assertEquals("repeated-hard-stall", state.reason)
    }

    @Test fun startupLatencyIsIgnoredButSafetyFloorsRemainImmediate() {
        val p99 = BotAdmissionController(BotProfiles.LOW_25).observe(
            BotResourceSample(10_000, 25, 900, 2_000, 4_000, ThermalLevel.NONE),
        )
        assertFalse(p99.changed)
        assertEquals(25, p99.effectiveTarget)
        assertEquals("startup-warmup", p99.reason)

        val memory = BotAdmissionController(BotProfiles.LOW_25).observe(
            BotResourceSample(10_000, 25, 10, 700, 4_000, ThermalLevel.NONE),
        )
        assertTrue(memory.changed)
        assertEquals(20, memory.effectiveTarget)
        assertEquals("memory-floor", memory.reason)
    }

    @Test fun aLongGenerationDoesNotConsumeThePostRampWarmup() {
        val controller = BotAdmissionController(BotProfiles.LOW_25)
        val longGenerationMs = 12 * 60_000L
        val ramp = controller.observe(
            BotResourceSample(longGenerationMs, 0, 900, 2_000, 4_000, ThermalLevel.NONE),
        )
        assertFalse(ramp.changed)
        assertEquals("bot-ramp", ramp.reason)

        val firstOnline = controller.observe(
            BotResourceSample(longGenerationMs + 10_000, 25, 900, 2_000, 4_000, ThermalLevel.NONE),
        )
        assertFalse(firstOnline.changed)
        assertEquals(25, firstOnline.effectiveTarget)
        assertEquals("startup-warmup", firstOnline.reason)
    }
}
