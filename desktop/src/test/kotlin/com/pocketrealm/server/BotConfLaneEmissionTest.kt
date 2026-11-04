package com.pocketrealm.server

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the desktop twin of the bot-conf lane
 * emission pin — the world start's conf VALUES ride the pinned boot lane
 * (ServerRuntimeFiles passes the resolved lane into playerbotConfig), and
 * BotProfiles never consults the :ui ActiveFlavor holder.
 */
class BotConfLaneEmissionTest {
    private fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir"))) {
        it.parentFile
    }.firstOrNull { File(it, "schemas/flavor.json").isFile }!!

    private fun botProfilesSource(): String =
        File(
            repoRoot(),
            "android/app/src/main/java/com/pocketrealm/bots/BotProfiles.kt",
        ).readText()

    @Test
    fun `the desktop emitter passes the pinned boot lane into the emission`() {
        val source = File(
            repoRoot(),
            "desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt",
        ).readText()
        assertTrue(
            "the desktop world start must pass the pinned boot lane into " +
                "playerbotConfig (R5-R6-2)",
            "botProfile.playerbotConfig(lane)" in source,
        )
    }

    @Test
    fun `bot profiles never consult the ActiveFlavor holder`() {
        val source = botProfilesSource()
        assertTrue(
            "BotProfiles must not consult the :ui ActiveFlavor holder",
            "ActiveFlavor.resolve" !in source &&
                "ActiveFlavor.displayedId" !in source &&
                "ActiveFlavor.select" !in source,
        )
        assertTrue(
            "the emission body must consume the lane parameter",
            "flavorBotMaxLevel(lane)" in source &&
                "flavorBotRandomMaps(lane)" in source,
        )
    }
}
