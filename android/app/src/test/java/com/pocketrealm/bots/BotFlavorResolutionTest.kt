package com.pocketrealm.bots

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.testFlavor
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-flavor bot profile resolution (the BotProfiles lift): the conf
 * emission resolves its population ceiling and random-map set through the
 * [ActiveFlavor] machine at every emission, so the Home flavor switcher's
 * active lane drives the bot conf — proven here against a synthetic
 * second qualified lane (the real registry qualifies vanilla only; TBC's
 * enablement is the S5 data change).
 */
class BotFlavorResolutionTest {

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

        ActiveFlavor.select("tbc")
        val tbcConf = profile.playerbotConfig()
        assertTrue(
            "TBC lane ceiling: ${tbcConf.lineSequence().first { "RandomBotMaxLevel" in it }}",
            "AiPlayerbot.RandomBotMaxLevel = 70" in tbcConf,
        )
        assertTrue("AiPlayerbot.RandomBotMaps = 0,1,530" in tbcConf)

        ActiveFlavor.clearSelection()
        // Multi-qualified + no selection is genuinely ambiguous: emission
        // fails loud instead of silently keeping VANILLA semantics.
        var failed = false
        try {
            profile.playerbotConfig()
        } catch (expected: IllegalStateException) {
            failed = true
            assertTrue(
                expected.message.orEmpty().contains(
                    "bot population policy resolves the qualified lane",
                ),
            )
        }
        assertTrue("ambiguous lane must fail loud", failed)
    }

    @Test
    fun soleQualifiedLaneKeepsTheVanillaEmission() {
        // The registry default (no selection, one qualified lane) must be
        // byte-identical to the pre-multi-flavor emission.
        val conf = BotProfiles.ALIVE_REALM_320.playerbotConfig()
        assertTrue("AiPlayerbot.RandomBotMaxLevel = 60" in conf)
        assertTrue("AiPlayerbot.RandomBotMaps = 0,1" in conf)
    }
}
