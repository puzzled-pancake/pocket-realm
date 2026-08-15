package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotExperiencePresetTest {
    @Test fun experiencePresetsFormTheCuratedLadderPeakingAtSixHundred() {
        assertEquals(
            listOf(80, 160, 240, 320, 400, 500, 600),
            BotProfiles.experiencePresets.map { it.selectedTarget },
        )
        assertEquals(
            listOf(80, 160, 240, 320, 400, 500, 600, 700),
            BotProfiles.userSelectable().map { it.selectedTarget },
        )
        // 600 is the curated built-in ceiling, not the custom ceiling.
        assertTrue(
            BotProfiles.experiencePresets.all {
                it.selectedTarget <= BotPopulationPolicy.MAX_BUILT_IN_TARGET
            },
        )
    }

    @Test fun aliveRealmIsTheRecommendedDefault() {
        val alive = BotProfiles.defaultProfile
        assertEquals(BotProfiles.ALIVE_REALM_320, alive)
        assertEquals(320, alive.selectedTarget)
        assertEquals(50, alive.initialTarget)
        assertEquals(50, alive.minimumOnline)
        // ~12% active of 320 ≈ 38 normally-active bots (brief §8).
        assertEquals(38, BotCustomConfiguration.fromProfile(alive).estimatedActiveBots())
        assertEquals(15, alive.iterationsPerTick)
        assertEquals(2_000, alive.randomBotUpdateIntervalMs)
        assertEquals(16, alive.nearPlayerTeleportMaxAmount)
        assertFalse(alive.syncLevelWithPlayers)
        assertTrue(alive.autoDoQuests)
        assertTrue(alive.groupNearby)
        assertTrue(alive.wanderWhenIdle)
        assertTrue(alive.enableOffSpecStrategies)
        assertFalse(alive.allowBotChat)
        assertFalse(alive.allowPlayerInvites)
        // Account pool covers the population with headroom.
        assertTrue(alive.accountCount * BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT >= 320)
    }

    @Test fun everyExperiencePresetCombinesPopulationWithRealAiChoices() {
        BotProfiles.experiencePresets.forEach { preset ->
            assertTrue(preset.accountCount * 9 >= preset.maximumOnline)
            assertTrue(preset.iterationsPerTick in 1..20)
            assertTrue(preset.activeBotPercent in 1..20)
            assertTrue(preset.randomBotUpdateIntervalMs in 500..60_000)
            assertTrue(preset.initialTarget in preset.minimumOnline..preset.selectedTarget)
            // All presets keep human-adjacent bots fast (locality priority).
            assertTrue(preset.forceActiveWhenNearPlayer)
            val config = preset.playerbotConfig()
            assertTrue(config.contains("AiPlayerbot.MaxRandomBots = ${preset.maximumOnline}"))
            assertTrue(config.contains("AiPlayerbot.RandomBotAccountCount = ${preset.accountCount}"))
            assertTrue(config.contains("AiPlayerbot.CommandServerPort = 0"))
            assertTrue(config.contains("AiPlayerbot.LLMEnabled = 0"))
        }
        // Lively is the fast-bots end; Massive prioritizes population.
        assertTrue(
            BotProfiles.LIVELY_160.iterationsPerTick > BotProfiles.MASSIVE_REALM_600.iterationsPerTick,
        )
        assertTrue(
            BotProfiles.LIVELY_160.activeBotPercent > BotProfiles.MASSIVE_REALM_600.activeBotPercent,
        )
    }

    @Test fun legacyProfilesRemainResolvableAndSevenHundredStaysLaunchable() {
        listOf(
            BotProfiles.QUIET_25, BotProfiles.TYPICAL_50, BotProfiles.BALANCED_100,
            BotProfiles.POPULATED_250, BotProfiles.CROWDED_400, BotProfiles.BUSY_600,
            BotProfiles.LAUNCH_DAY_700, BotProfiles.LOW_25, BotProfiles.LOW_CPU_160,
            BotProfiles.FRESH_REALM_240, BotProfiles.LIVELY_700,
        ).forEach { profile ->
            assertEquals(profile, BotProfiles.find(profile.id))
        }
        assertEquals(BotProfiles.LAUNCH_DAY_700, BotProfiles.find(BotProfiles.LAUNCH_DAY_700.id))
        assertTrue(BotProfiles.LAUNCH_DAY_700.userSelectable)
        // Legacy ladder members stay resolvable but are no longer featured.
        assertFalse(BotProfiles.QUIET_25.userSelectable)
        assertFalse(BotProfiles.BALANCED_100.userSelectable)
        // Legacy target migration still lands on the same legacy identities.
        assertEquals(BotProfiles.QUIET_25, BotProfiles.migrateLegacyTarget(25))
        assertEquals(BotProfiles.LAUNCH_DAY_700, BotProfiles.migrateLegacyTarget(725))
    }

    @Test fun adv4IdentitiesRoundTripIdenticallyAfterTheCatalogChange() {
        // Values exercising multiple adv4 base buckets, including the 500
        // bucket that sits between two legacy ladder entries.
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
            limitCombatActivity = false,
            activeBotPercent = 8,
            autoDoQuests = true,
            allowBotChat = true,
            allowPlayerInvites = true,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
        )
        listOf(100, 250, 400, 500, 600, 700).forEach { target ->
            val minted = BotProfiles.advanced(target, settings)
            assertEquals(minted, BotProfiles.find(minted.id))
        }
    }

    @Test fun customPopulationIsNotCappedAtTheBuiltInCeiling() {
        // §10: custom values beyond every historical UI cap must validate
        // against real constraints, not substitute one cap for another.
        assertTrue(BotPopulationPolicy.MAX_SUPPORTED_TARGET > 600)
        assertTrue(BotPopulationPolicy.MAX_SUPPORTED_TARGET > 700)
        assertTrue(BotPopulationPolicy.MAX_SUPPORTED_TARGET > 1_500)
        BotPopulationPolicy.validateTarget(725)
        BotPopulationPolicy.validateTarget(1_000)
        BotPopulationPolicy.validateTarget(9_999)

        val configuration = BotCustomConfiguration.fromBasePreset(BotProfiles.ALIVE_REALM_320)
            .withTarget(725)
        assertEquals(725, configuration.selectedTarget)
        assertTrue(
            configuration.accountCount * BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT >= 725,
        )
        // §11: direct entry of arbitrary valid numbers (not %-25 ladders).
        val profile = configuration.resolve(
            BotPresetIdentities.mint("a".repeat(32), 1, configuration), "725 experiment",
        )
        assertEquals(725, profile.selectedTarget)
        assertTrue(profile.playerbotConfig().contains("AiPlayerbot.MaxRandomBots = 725"))
    }

    @Test fun customPopulationRejectsInvalidShapesAndOverflow() {
        assertThrows<IllegalArgumentException> { BotPopulationPolicy.validateTarget(9) }
        assertThrows<IllegalArgumentException> {
            BotPopulationPolicy.validateTarget(BotPopulationPolicy.MAX_SUPPORTED_TARGET + 1)
        }
        assertThrows<IllegalArgumentException> {
            BotPopulationPolicy.validateTarget(Int.MAX_VALUE)
        }
        assertThrows<IllegalArgumentException> {
            BotPopulationPolicy.validatePopulation(100, 200, 150, 160)
        }
        assertThrows<IllegalArgumentException> {
            BotPopulationPolicy.validatePopulation(10, 50, 40, 30)
        }
    }

    @Test fun accountPoolAutoSizesFromTheVerifiedCharactersPerAccount() {
        // Verified against the pinned RandomPlayerbotFactory: 9 characters
        // per classic bot account, +20% headroom (brief §12 example).
        assertEquals(9, BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT)
        assertEquals(81, BotPopulationPolicy.requiredAccounts(725))
        val allocated = BotPopulationPolicy.allocatedAccounts(725)
        assertTrue("allocated=$allocated", allocated in 81..98)
        assertTrue(BotPopulationPolicy.capacityFor(allocated) >= 725)
        assertEquals(9, BotPopulationPolicy.requiredAccounts(80))
        assertTrue(BotPopulationPolicy.allocatedAccounts(80) >= 9)
    }

    @Test fun activityAndPlaystyleAreIndependentlyCombinable() {
        // §13: 600 bots + Smart AI must be constructible — warnings are fine,
        // artificial blocking is not.
        val massiveSmart = BotCustomConfiguration.fromBasePreset(BotProfiles.MASSIVE_REALM_600)
            .let(BotActivityPreset.SMART::applyTo)
        assertEquals(600, massiveSmart.selectedTarget)
        assertEquals(20, massiveSmart.iterationsPerTick)
        assertEquals(1_000, massiveSmart.randomBotUpdateIntervalMs)

        assertTrue(BotActivityPreset.SMART.matches(massiveSmart))
        assertTrue(
            BotPlaystylePreset.CLASSIC_WORLD.matches(
                BotPlaystylePreset.CLASSIC_WORLD.applyTo(massiveSmart),
            ),
        )
        // CUSTOM activity matches anything the named bundles do not.
        val odd = massiveSmart.copy(iterationsPerTick = 13)
        assertTrue(BotActivityPreset.CUSTOM.matches(odd))
        assertFalse(BotActivityPreset.SMART.matches(odd))
    }
}

private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
        error("expected ${T::class.simpleName} to be thrown")
    } catch (expected: Throwable) {
        if (!T::class.java.isInstance(expected)) throw expected
    }
}
