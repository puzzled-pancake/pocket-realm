package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

class BotExperiencePresetTest {
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

    @Test fun experiencePresetsAreLightStandardAndMassive() {
        assertEquals(
            listOf(200, 500, 1_000),
            BotProfiles.experiencePresets.map { it.selectedTarget },
        )
        assertEquals(
            listOf(200, 500, 1_000),
            BotProfiles.userSelectable().map { it.selectedTarget },
        )
        // The built-in ceiling, not the custom ceiling.
        assertTrue(
            BotProfiles.experiencePresets.all {
                it.selectedTarget <= BotPopulationPolicy.MAX_BUILT_IN_TARGET
            },
        )
    }

    @Test fun experiencePresetAdmissionMemoryFloorIsMonotonic() {
        // each step up the curated experience ladder must demand at
        // least the free memory of the step below - a dip would let a
        // "bigger" preset admit on devices the smaller one already
        // rejects. Scoped to experiencePresets ONLY: a union-catalog pin is
        // wrong - BENCH_FORCED_1000 legitimately exceeds every experience
        // floor at 3_072, and the frozen legacy adv4 catalog (CROWDED_400
        // at 1_792) is never re-tuned.
        val floors = BotProfiles.experiencePresets.map { it.admission.minFreeMemoryMiB }
        assertEquals(listOf(1_536L, 1_536L, 1_536L), floors)
        floors.zipWithNext().forEach { (smaller, bigger) ->
            assertTrue("experience ladder memory floor dipped at $bigger (below $smaller)", bigger >= smaller)
        }
    }

    @Test fun standardFiveHundredIsTheDefaultWithUpstreamPlayerbotSettings() {
        val standard = BotProfiles.defaultProfile
        assertEquals(BotProfiles.STANDARD_500, standard)
        assertEquals(500, standard.selectedTarget)
        assertEquals(150, standard.initialTarget)
        assertEquals(100, standard.minimumOnline)
        // The upstream playerbots defaults: 10% of distant bots active,
        // a 1 s AI pass, no combat limiter, bots group and invite.
        assertEquals(50, BotCustomConfiguration.fromProfile(standard).estimatedActiveBots())
        assertEquals(1_000, standard.randomBotUpdateIntervalMs)
        assertEquals(4_000, standard.passiveDelayMs)
        assertFalse(standard.limitCombatActivity)
        assertFalse(standard.syncLevelWithPlayers)
        assertTrue(standard.autoDoQuests)
        assertTrue(standard.groupNearby)
        assertTrue(standard.wanderWhenIdle)
        assertTrue(standard.enableOffSpecStrategies)
        assertFalse(standard.allowBotChat)
        assertTrue(standard.allowPlayerInvites)
        assertTrue(standard.accountCount * BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT >= 500)
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
            val config = preset.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA)
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
        assertFalse(BotProfiles.LAUNCH_DAY_700.userSelectable)
        // Legacy ladder members stay resolvable but are not user-selectable.
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
        // custom values beyond every historical UI cap must validate
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
        // direct entry of arbitrary valid numbers (not %-25 ladders).
        val profile = configuration.resolve(
            BotPresetIdentities.mint("a".repeat(32), 1, configuration), "725 experiment",
        )
        assertEquals(725, profile.selectedTarget)
        assertTrue(profile.playerbotConfig(com.pocketrealm.client.ClientFlavors.VANILLA)
            .contains("AiPlayerbot.MaxRandomBots = 725"))
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
        // per classic bot account, +20% headroom.
        assertEquals(9, BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT)
        assertEquals(81, BotPopulationPolicy.requiredAccounts(725))
        val allocated = BotPopulationPolicy.allocatedAccounts(725)
        assertTrue("allocated=$allocated", allocated in 81..98)
        assertTrue(BotPopulationPolicy.capacityFor(allocated) >= 725)
        assertEquals(9, BotPopulationPolicy.requiredAccounts(80))
        assertTrue(BotPopulationPolicy.allocatedAccounts(80) >= 9)
    }

    @Test fun activityAndPlaystyleAreIndependentlyCombinable() {
        // 600 bots + Smart AI must be constructible — warnings are fine,
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

    @Test fun freshInstallSnapshotSelectsStandard() {
        val snapshot = com.pocketrealm.storage.Settings.Snapshot()
        assertEquals(BotProfiles.STANDARD_500.id, snapshot.botProfileId)
        assertEquals(500, snapshot.botPopulationTarget)
        assertNull(snapshot.botSavedPresetId)
        // Legacy advanced defaults mirror the recommended preset exactly, so
        // opening the old advanced editor can never shift a value.
        assertEquals(
            BotAdvancedSettings.fromProfile(BotProfiles.STANDARD_500),
            snapshot.botAdvanced,
        )
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
