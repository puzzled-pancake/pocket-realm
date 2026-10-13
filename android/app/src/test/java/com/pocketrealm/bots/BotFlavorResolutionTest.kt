package com.pocketrealm.bots

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.testFlavor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before

/**
 * The per-flavor bot profile resolution (the BotProfiles lift): the conf
 * emission resolves its population ceiling and random-map set through the
 * [ActiveFlavor] machine at every emission, so the Home flavor switcher's
 * active lane drives the bot conf — proven here against a synthetic
 * second qualified lane (the real registry qualifies vanilla only; TBC's
 * enablement is the S5 data change).
 */
class BotFlavorResolutionTest {
    /** W4 flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }


    @After
    fun restoreRegistryDefaults() {
        ActiveFlavor.reset()
    }

    @Test
    fun emissionResolvesTheActiveLaneSRandomMapsAndLevelCeiling() {
        val vanilla = testFlavor("vanilla", qualified = true)
        val tbc = testFlavor(
            "tbc",
            qualified = true,
            botMaxLevel = 70,
            botRandomMaps = listOf(0, 1, 530),
        )
        ActiveFlavor.qualifiedSource = { listOf(vanilla, tbc) }
        val profile = BotProfiles.ALIVE_REALM_320

        // R5-R6-2 (W4 code-red round 6): the emission is LANE-PARAMETERIZED
        // — the conf values ride the world start's pinned boot lane (the
        // ServerRuntimeFiles emitter passes it), never the :ui holder.
        val tbcLane = com.pocketrealm.client.ClientFlavors.ALL
            .first { it.flavorId == "tbc" }
        val tbcConf = profile.playerbotConfig(tbcLane)
        assertTrue(
            "TBC lane ceiling: ${tbcConf.lineSequence().first { "RandomBotMaxLevel" in it }}",
            "AiPlayerbot.RandomBotMaxLevel = 70" in tbcConf,
        )
        assertTrue("AiPlayerbot.RandomBotMaps = 0,1,530" in tbcConf)

        // the vanilla lane's own emission keeps vanilla semantics (60 cap)
        val vanillaLane = com.pocketrealm.client.ClientFlavors.ALL
            .first { it.flavorId == "vanilla" }
        val vanillaConf = profile.playerbotConfig(vanillaLane)
        assertTrue("AiPlayerbot.RandomBotMaxLevel = 60" in vanillaConf)
    }

    @Test
    fun soleQualifiedLaneKeepsTheVanillaEmission() {
        // The registry default (no selection, one qualified lane) must be
        // byte-identical to the pre-multi-flavor emission.
        val conf = BotProfiles.ALIVE_REALM_320.playerbotConfig(
            com.pocketrealm.client.ClientFlavors.VANILLA,
        )
        assertTrue("AiPlayerbot.RandomBotMaxLevel = 60" in conf)
        assertTrue("AiPlayerbot.RandomBotMaps = 0,1" in conf)
    }
}
