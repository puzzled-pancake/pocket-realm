package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotPolicyTest {
    @Test fun livelyProfileIsExplicitBoundedNearbyBiasedAndKeepsUnsafeServicesDisabled() {
        val profile = BotProfiles.LIVELY_700
        val config = profile.playerbotConfig()

        assertEquals("mobile-lively-b700-v2", profile.id)
        assertEquals(700, profile.selectedTarget)
        assertEquals(700, profile.maximumOnline)
        assertEquals(25, profile.minimumOnline)
        assertEquals(80, profile.accountCount)
        assertTrue(config.contains("AiPlayerbot.MaxRandomBots = 700"))
        assertTrue(config.contains("AiPlayerbot.RandomBotAccountCount = 80"))
        assertTrue(config.contains("AiPlayerbot.ForceActiveWhenNearPlayer = 1"))
        assertTrue(config.contains("AiPlayerbot.RandomBotTeleportNearPlayer = 1"))
        assertTrue(config.contains("AiPlayerbot.RandomBotTeleportNearPlayerMaxAmount = 18"))
        assertTrue(config.contains("AiPlayerbot.RandomBotTeleportNearPlayerMaxAmountRadius = 250"))
        assertTrue(config.contains("AiPlayerbot.RandomBotTeleportTeleportMinInterval = 1800"))
        assertTrue(config.contains("AiPlayerbot.RandomBotTeleportTeleportMaxInterval = 7200"))
        assertTrue(config.contains("AiPlayerbot.RandomBotsMaxLoginsPerInterval = 4"))
        assertTrue(config.contains("AiPlayerbot.CommandServerPort = 0"))
        assertTrue(config.contains("AiPlayerbot.LLMEnabled = 0"))
        assertTrue(config.contains("AiPlayerbot.RandomBotJoinBG = 0"))
        assertFalse(config.contains("AuctionHouseBot"))
        assertTrue(BotProfiles.ids().contains(profile.id))
    }

    @Test fun reportProfilesEmitThePinnedLowCpuContract() {
        val efficient = BotProfiles.LOW_CPU_160
        val fresh = BotProfiles.FRESH_REALM_240

        assertEquals(120, efficient.minimumOnline)
        assertEquals(160, efficient.maximumOnline)
        assertEquals(3, efficient.loginBatchSize)
        assertEquals(12, efficient.maintenanceBatchSize)
        assertEquals(12, efficient.nearPlayerTeleportMaxAmount)
        assertEquals(3_600, efficient.teleportMinIntervalSeconds)
        assertEquals(14_400, efficient.teleportMaxIntervalSeconds)

        assertEquals(180, fresh.minimumOnline)
        assertEquals(240, fresh.maximumOnline)
        assertEquals(4, fresh.loginBatchSize)
        assertEquals(16, fresh.maintenanceBatchSize)
        assertTrue(fresh.syncLevelWithPlayers)
        assertEquals(0.35f, fresh.randomBotMaxLevelChance)
        assertEquals(3_600, fresh.randomizeMinIntervalSeconds)
        assertEquals(10_800, fresh.randomizeMaxIntervalSeconds)

        listOf(efficient, fresh, BotProfiles.LIVELY_700).forEach { profile ->
            val config = profile.playerbotConfig()
            assertTrue(config.contains("AiPlayerbot.RandomBotLoginAtStartup = 0"))
            assertTrue(config.contains("AiPlayerbot.RandomBotLoginWithPlayer = 1"))
            assertTrue(config.contains("AiPlayerbot.RandomBotMaps = 0,1"))
            assertTrue(config.contains("AiPlayerbot.DisableBotOptimizations = 0"))
            assertTrue(config.contains("AiPlayerbot.DisableActivityPriorities = 0"))
            assertTrue(config.contains("AiPlayerbot.ForceActiveWhenNearPlayer = 1"))
            assertTrue(config.contains("AiPlayerbot.botActiveAlone = 5"))
            assertTrue(config.contains("AiPlayerbot.DiffWithPlayer = 100"))
            assertTrue(config.contains("AiPlayerbot.DiffEmpty = 200"))
            assertTrue(config.contains("AiPlayerbot.EnableMinimalMove = 1"))
            assertTrue(config.contains("AiPlayerbot.IterationsPerTick = 10"))
            assertTrue(config.contains("AiPlayerbot.RandomBotJoinLfg = 0"))
            assertTrue(config.contains("AiPlayerbot.RandomBotJoinBG = 0"))
            assertTrue(config.contains("AiPlayerbot.ShouldQueryAHListingsOutsideOfAH = 0"))
            assertTrue(profile.accountCount * 9 >= profile.maximumOnline)
        }
    }

    @Test fun settingsTargetsMapOnlyToMeasuredProfiles() {
        assertEquals(BotProfiles.LOW_CPU_160, BotProfiles.forRequestedTarget(160))
        assertEquals(BotProfiles.FRESH_REALM_240, BotProfiles.forRequestedTarget(240))
        assertEquals(BotProfiles.LIVELY_700, BotProfiles.forRequestedTarget(700))
        assertEquals(listOf(160, 240, 700), BotProfiles.userSelectable().map { it.selectedTarget })
    }

    @Test fun advancedProfileRoundTripsAcrossProcessesAndEmitsOnlyBoundedOverrides() {
        val settings = BotAdvancedSettings(
            nearbyBotLimit = 30,
            nearbyRadius = 350,
            loginBatchSize = 6,
            maintenanceBatchSize = 24,
            updateIntervalMs = 2_000,
            teleportMinMinutes = 60,
            teleportMaxMinutes = 480,
            iterationsPerTick = 7,
            admissionWorldP99Ms = 200,
            syncLevelWithPlayers = true,
        )
        val profile = BotProfiles.advanced(500, settings)
        val decoded = BotProfiles.find(profile.id)

        assertEquals(profile, decoded)
        assertTrue(profile.id.length <= 64)
        val replacement = if (profile.id.last() == '0') '1' else '0'
        assertEquals(null, BotProfiles.find(profile.id.dropLast(1) + replacement))
        assertEquals(500, profile.selectedTarget)
        assertEquals(500, profile.maximumOnline)
        assertTrue(profile.accountCount * 9 >= 500)
        assertEquals(30, profile.nearPlayerTeleportMaxAmount)
        assertEquals(350, profile.nearPlayerTeleportRadius)
        assertEquals(7, profile.iterationsPerTick)
        assertEquals(200, profile.admission.maxWorldP99Ms)
        val config = profile.playerbotConfig()
        assertTrue(config.contains("AiPlayerbot.IterationsPerTick = 7"))
        assertTrue(config.contains("AiPlayerbot.RandomBotsMaxLoginsPerInterval = 6"))
        assertTrue(config.contains("AiPlayerbot.RandomBotsPerInterval = 24"))
        assertTrue(config.contains("AiPlayerbot.CommandServerPort = 0"))
        assertTrue(config.contains("AiPlayerbot.LLMEnabled = 0"))
    }

    @Test fun advancedProfileRejectsUnknownOrOutOfPolicyIdentity() {
        assertEquals(null, BotProfiles.find("advanced-v1-999-1-250-1-1-1000-30-120-10-250-0"))
        assertEquals(null, BotProfiles.find("advanced-v1-700-99-250-4-16-1250-30-120-10-250-1"))
        assertEquals(null, BotProfiles.find("advanced-v1-700-18-0-4-16-1250-30-120-10-250-1"))
    }

    @Test fun livelyProfileShedsLoadInFiftyBotStepsWithoutChangingSelection() {
        val profile = BotProfiles.LIVELY_700
        val controller = BotAdmissionController(profile)
        val overloaded = controller.observe(BotResourceSample(
            elapsedMs = 0,
            onlineBots = 700,
            worldP99Ms = 100,
            freeMemoryMiB = profile.admission.minFreeMemoryMiB - 1,
            freeStorageMiB = 8_192,
            thermal = ThermalLevel.NONE,
        ))

        assertTrue(overloaded.changed)
        assertEquals(650, overloaded.effectiveTarget)
        assertEquals(700, overloaded.selectedTarget)
        assertTrue(overloaded.adapted)
        assertEquals("memory-floor", overloaded.reason)
    }

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

    @Test fun anUnchangedRollingStallWindowIsNotCountedAgain() {
        val profile = BotProfiles.LIVELY_700
        val controller = BotAdmissionController(profile)
        val warmup = profile.admission.performanceWarmupMs
        controller.observe(BotResourceSample(
            0, 700, 100, 4_000, 4_000, ThermalLevel.NONE,
            hardStallCount = 0, hardStallTotal = 0,
        ))
        val first = controller.observe(BotResourceSample(
            warmup, 700, 100, 4_000, 4_000, ThermalLevel.NONE,
            hardStallCount = 2, hardStallTotal = 2,
        ))
        assertTrue(first.changed)
        assertEquals(650, first.effectiveTarget)

        val stale = controller.observe(BotResourceSample(
            warmup + profile.admission.changeCooldownMs, 650, 100,
            4_000, 4_000, ThermalLevel.NONE,
            hardStallCount = 2, hardStallTotal = 2,
        ))
        assertFalse(stale.changed)
        assertEquals(650, stale.effectiveTarget)
        assertEquals("healthy-hold", stale.reason)

        val fresh = controller.observe(BotResourceSample(
            warmup + 2 * profile.admission.changeCooldownMs, 650, 100,
            4_000, 4_000, ThermalLevel.NONE,
            hardStallCount = 4, hardStallTotal = 4,
        ))
        assertTrue(fresh.changed)
        assertEquals(600, fresh.effectiveTarget)
        assertEquals("repeated-hard-stall", fresh.reason)
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
