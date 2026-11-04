package com.pocketrealm.ingame

import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the desktop Config.wtf overlay — the merged file is golden-pinned
 * against the shared ManagedConfigPolicy's enforced keys (the desktop launch
 * conditions: windowed, movie off, TOS/EULA read, d3d, uncapped, audio leg
 * per DESK-139). Pure JVM.
 */
class DesktopConfigWtfTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val screen = 1920 to 1080

    private fun conditions(
        audioMode: String = "on",
        endpoint: RealmEndpoint = RealmEndpoint.LOCAL,
        tweaks: ClientTweaksConfig = ClientTweaksConfig(),
    ): DesktopConfigWtf.DesktopLaunchConditions =
        DesktopConfigWtf.conditions(audioMode, endpoint, tweaks, screenSize = screen)

    /** The stock-install base: unknown lines round-trip, stale keys die. */
    private val stockBase = """
        SET gxWindow "0"
        SET gxMaximize "1"
        SET maxFPS "30"
        SET SoundMixRate "22050"
        SET MasterSoundEffects "1"
        SET M2UseShaders "1"
        SET realmName "SomeOtherRealm"
        SET SoundSoftwareChannels "12"
        SET usetextmode "1"
    """.trimIndent() + "\r\n"

    @Test
    fun freshInstallGetsTheGateKillingWindowedOverlay() {
        val dir = folder.newFolder("client")
        val merged = DesktopConfigWtf.enforceAtLaunch(dir, conditions())
        val values = ConfigWtfCodec.parse(merged)
        // The EULA/TOS/intro gates: the collision DESK-50 exists to kill.
        assertEquals("1", values["readTOS"])
        assertEquals("1", values["readEULA"])
        assertEquals("1", values["readScanning"])
        assertEquals("0", values["movie"])
        // Windowed at the current screen, not maximized, d3d, uncapped.
        assertEquals("1", values["gxWindow"])
        assertEquals("0", values["gxMaximize"])
        assertEquals("d3d", values["gxApi"])
        assertEquals("1920x1080", values["gxResolution"])
        assertEquals("1920x1080", values["gxWindowedResolution"])
        assertEquals("0", values["maxFPS"])
        assertEquals("0", values["scriptMemory"])
        // Audio ON: master sound stays user-owned (absent from the set).
        assertNull(ManagedConfigPolicy.enforcedKeys(
            ManagedConfigPolicy.LaunchConditions(
                renderer = "d3d", resolution = "1920x1080", gameMaximized = false,
                frameCap = 0, uiScale = null, audioMode = "on", realmLoopback = true,
                soundChannelsEnabled = false, soundChannels = 64,
            ),
        ).firstOrNull { it.key == ManagedConfigPolicy.MASTER_SOUND_CVAR })
        // Audio-ON overlay lines.
        assertEquals("48000", values["SoundMixRate"])
        assertEquals("100", values["SoundBufferSize"])
        assertFalse(values.containsKey("SoundSoftwareChannels"))
        // Loopback realm name; d3d owns no M2UseShaders line.
        assertEquals("MaNGOS", values["realmName"])
        assertFalse(values.containsKey("M2UseShaders"))
        assertFalse(values.containsKey("useUiScale"))
        assertFalse(values.containsKey("uiScale"))
    }

    @Test
    fun unknownLinesRoundTripAndStaleAppOwnedKeysDie() {
        val dir = folder.newFolder("client")
        File(dir, "WTF").mkdirs()
        File(dir, "WTF/Config.wtf").writeText(stockBase)
        val merged = DesktopConfigWtf.enforceAtLaunch(dir, conditions())
        val values = ConfigWtfCodec.parse(merged)
        // Unknown CVar survives verbatim (the merge engine's base-authority rule).
        assertEquals("1", values["usetextmode"])
        // App-owned flips: windowed+uncapped replace the stock choices; the
        // stale realm/renderer/channel lines die with the flip.
        assertEquals("1", values["gxWindow"])
        assertEquals("0", values["gxMaximize"])
        assertEquals("0", values["maxFPS"])
        assertFalse(values.containsKey("M2UseShaders"))
        assertNull(values["SoundSoftwareChannels"])
        // Audio ON keeps the user's master-sound choice; stale rates rewritten.
        assertEquals("1", values["MasterSoundEffects"])
        assertEquals("48000", values["SoundMixRate"])
        // Non-SET content (comments) round-trips verbatim in order.
        assertTrue("the file is CRLF-terminated", merged.endsWith("\r\n"))
        val onDisk = File(dir, "WTF/Config.wtf").readText()
        assertEquals("the merged text is what lands on disk", merged, onDisk)
    }

    @Test
    fun audioOffLaunchEnforcesTheMasterSoundLeg() {
        val dir = folder.newFolder("client")
        File(dir, "WTF").mkdirs()
        File(dir, "WTF/Config.wtf").writeText(stockBase)
        val merged = DesktopConfigWtf.enforceAtLaunch(dir, conditions(audioMode = "off"))
        val values = ConfigWtfCodec.parse(merged)
        assertEquals("0", values["MasterSoundEffects"])
        // The audio-off deletes: no rate/buffer/channels lines survive.
        assertFalse(values.containsKey("SoundMixRate"))
        assertFalse(values.containsKey("SoundBufferSize"))
        assertFalse(values.containsKey("SoundSoftwareChannels"))
    }

    @Test
    fun channelPatchedAudioOnLaunchCarriesTheSoftwareChannelsLine() {
        val dir = folder.newFolder("client")
        val tweaks = ClientTweaksConfig(soundChannelsEnabled = true, soundChannels = 64)
        val merged = DesktopConfigWtf.enforceAtLaunch(dir, conditions(tweaks = tweaks))
        assertEquals("64", ConfigWtfCodec.parse(merged)["SoundSoftwareChannels"])
    }

    @Test
    fun lanJoinLaunchLeavesTheRealmNameUserOwned() {
        val dir = folder.newFolder("client")
        File(dir, "WTF").mkdirs()
        File(dir, "WTF/Config.wtf").writeText(stockBase)
        val lan = RealmEndpoint.parseLan("192.168.1.50")
        val merged = DesktopConfigWtf.enforceAtLaunch(dir, conditions(endpoint = lan))
        // The policy is written-or-deleted: a non-loopback launch REMOVES the
        // stale realmName line (a stale realm name must not survive the
        // loopback→LAN flip — the Android contract), leaving it user-owned
        // for whatever the client writes next.
        assertFalse(ConfigWtfCodec.parse(merged).containsKey("realmName"))
        assertFalse("\"SomeOtherRealm\"" in merged)
        // The unknown CVar still round-trips (the delete is key-scoped).
        assertEquals("1", ConfigWtfCodec.parse(merged)["usetextmode"])
    }

    @Test
    fun primaryScreenSizeFallsBackRatherThanThrowing() {
        val (width, height) = DesktopConfigWtf.primaryScreenSize()
        assertTrue("screen width must be positive: $width", width > 0)
        assertTrue("screen height must be positive: $height", height > 0)
    }
}
