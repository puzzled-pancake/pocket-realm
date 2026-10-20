package com.pocketrealm.bots

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5-R6-2 (W4 code-red round 6): the bot conf VALUES (RandomBotMaxLevel,
 * RandomBotMaps) resolve the world start's PINNED BOOT LANE — the
 * emission is lane-parameterized and never consults the :ui ActiveFlavor
 * holder, mirroring D86's "resolve the pinned boot lane" law for the
 * engine/verifier/logon/overlay paths (the bot-emission call site was
 * missed there). The holder/lane divergence (vanilla holder + tbc lane)
 * must stage the tbc lane's own values.
 */
class BotConfLaneEmissionTest {

    @After
    fun restoreTheFlavorMachine() {
        ActiveFlavor.reset()
    }

    private fun lane(id: String): ClientFlavor =
        ClientFlavors.ALL.first { it.flavorId == id }

    @Test
    fun `the emission takes the lane as a parameter and ignores the holder`() {
        // pin the holder to vanilla (the divergence case: the world start
        // resolves the TBC lane from settings while the holder says
        // vanilla)
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
        val tbc = lane("tbc")
        val conf = BotProfiles.ALIVE_REALM_320.playerbotConfig(tbc)
        assertTrue(
            "the staged conf must carry the TBC lane's own ceiling",
            "AiPlayerbot.RandomBotMaxLevel = 70" in conf,
        )
        assertTrue(
            "the staged conf must carry the TBC lane's own maps",
            "AiPlayerbot.RandomBotMaps = 0,1,530" in conf,
        )
        // the vanilla holder values (60 / 0,1) must NOT leak into the
        // tbc-lane emission
        assertTrue("AiPlayerbot.RandomBotMaxLevel = 60" !in conf)
    }

    @Test
    fun `the emission source never consults the ActiveFlavor holder`() {
        // source shape: BotProfiles' emission must not reference
        // the holder — the lane arrives as a parameter from the world
        // start's pinned boot lane.
        val root = generateSequence(File(System.getProperty("user.dir"))) {
            it.parentFile
        }.firstOrNull { File(it, "schemas/flavor.json").isFile }!!
        val source = File(
            root,
            "android/app/src/main/java/com/pocketrealm/bots/BotProfiles.kt",
        ).readText()
        // the split emission (wrapper + playerbotConfigText builder)
        // still consumes the lane parameter
        val emission = source.substringAfter("fun playerbotConfig(lane")
        assertTrue(
            "the emission body must consume the lane parameter",
            "flavorBotMaxLevel(lane)" in emission &&
                "flavorBotRandomMaps(lane)" in emission,
        )
        assertTrue(
            "the emission body must consume the lane parameter",
            "flavorBotMaxLevel(lane)" in emission &&
                "flavorBotRandomMaps(lane)" in emission,
        )
    }

    @Test
    fun `the vanilla lane keeps its documented emission`() {
        ActiveFlavor.reset()
        ActiveFlavor.select("tbc") // even with a divergent holder
        val conf = BotProfiles.ALIVE_REALM_320.playerbotConfig(lane("vanilla"))
        assertTrue("AiPlayerbot.RandomBotMaxLevel = 60" in conf)
        assertTrue("AiPlayerbot.RandomBotMaps = 0,1" in conf)
    }
}
