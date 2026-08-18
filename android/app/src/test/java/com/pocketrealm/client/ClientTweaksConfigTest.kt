package com.pocketrealm.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for [ClientTweaksConfig] JSON round-trip (the single
 * JSON-string DataStore key) and [toFlags] emission. Flag identifiers mirror
 * vendored vanilla-tweaks 1.6.0.
 */
class ClientTweaksConfigTest {
    private data class ToggleCase(
        val name: String,
        val enabled: ClientTweaksConfig,
        val disable: (ClientTweaksConfig) -> ClientTweaksConfig,
        val disabledFlag: String?,
        val allowedRanges: List<IntRange>,
    )

    private fun syntheticQualifiedImage(): ByteArray = ByteArray(0x467A00) { index ->
        ((index * 37 + 11) and 0xff).toByte()
    }.also {
        it[0] = 'M'.code.toByte()
        it[1] = 'Z'.code.toByte()
        // Ensure Large Address Aware starts clear in the PE characteristics word.
        it[0x126] = 0x0f
        it[0x127] = 0x01
    }

    private fun changedOffsets(before: ByteArray, after: ByteArray): List<Int> =
        before.indices.filter { before[it] != after[it] }

    @Test fun `defaults round-trip through JSON`() {
        val original = ClientTweaksConfig()
        val parsed = ClientTweaksConfig.fromJson(original.toJson())
        assertEquals(original, parsed)
    }

    @Test fun `malformed or null JSON falls back to defaults`() {
        assertEquals(ClientTweaksConfig(), ClientTweaksConfig.fromJson(null))
        assertEquals(ClientTweaksConfig(), ClientTweaksConfig.fromJson(""))
        assertEquals(ClientTweaksConfig(), ClientTweaksConfig.fromJson("{not valid"))
    }

    @Test fun `disabled patch emits the matching no-flag`() {
        val flags = ClientTweaksConfig(
            fovEnabled = false, largeAddressAwareEnabled = false, quicklootEnabled = false,
        ).toFlags()
        assertTrue(flags.contains("--no-fov"))
        assertTrue(flags.contains("--no-largeaddressaware"))
        assertTrue(flags.contains("--no-quickloot"))
    }

    @Test fun `sound patches follow their own toggles`() {
        val off = ClientTweaksConfig(soundInBackgroundEnabled = false, soundChannelsEnabled = false)
            .toFlags()
        assertTrue(off.contains("--no-sound-in-background"))
        assertTrue(off.contains("--no-soundchannels"))
        // Background audio is common; the higher-CPU 64-channel patch stays opt-in.
        val common = ClientTweaksConfig.commonPreset().toFlags()
        assertFalse(common.contains("--no-sound-in-background"))
        assertTrue(common.contains("--no-soundchannels"))
    }

    @Test fun `common preset leaves higher CPU sound channels disabled`() {
        val flags = ClientTweaksConfig.commonPreset().toFlags()
        assertFalse(flags.contains("--soundchannels"))
        assertTrue(flags.contains("--no-soundchannels"))
    }

    @Test fun `pristine Vanilla is the product default`() {
        val value = ClientTweaksConfig()
        assertFalse(value.hasAnyPatch())
        assertFalse(value.cameraSkipFixEnabled)
        assertFalse(value.maxCameraDistanceEnabled)
    }

    @Test fun `vanilla launch accepts a different build-5875 executable hash`() {
        assertTrue(ClientTweaksConfig().acceptsExecutableForLaunch("0".repeat(64)))
    }

    @Test fun `byte patches still require their verified executable layout`() {
        val patched = ClientTweaksConfig(soundChannelsEnabled = true)
        assertFalse(patched.acceptsExecutableForLaunch("0".repeat(64)))
        assertTrue(patched.acceptsExecutableForLaunch(ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256.uppercase()))
    }

    @Test fun `unknown build-5875 layout falls back to pristine without changing saved request`() {
        val requested = ClientTweaksConfig.commonPreset()
        val resolved = resolveEffectiveClientTweaks(requested, "0".repeat(64))

        assertEquals(ClientTweaksConfig(), resolved.config)
        assertFalse(resolved.config.hasAnyPatch())
        assertTrue(resolved.fallback)
        assertTrue(requested.hasAnyPatch())
    }

    @Test fun `unqualified sound-channel request writes the effective all-off Config`() {
        val requested = ClientTweaksConfig(soundChannelsEnabled = true, soundChannels = 64)
        val resolved = resolveEffectiveClientTweaks(requested, "0".repeat(64))

        assertTrue(resolved.fallback)
        assertEquals(ClientTweaksConfig(), resolved.config)
        assertEquals("", managedSoundChannelsConfigLine("on", resolved.config))
        assertEquals(
            "SET SoundSoftwareChannels \"64\"\n",
            managedSoundChannelsConfigLine("on", requested),
        )
        assertEquals("", managedSoundChannelsConfigLine("off", requested))
        // Resolution is launch-local; Settings keeps the requested switch/value.
        assertTrue(requested.soundChannelsEnabled)
        assertEquals(64, requested.soundChannels)
    }

    @Test fun `qualified build-5875 layout keeps requested tweaks`() {
        val requested = ClientTweaksConfig.commonPreset()
        val resolved = resolveEffectiveClientTweaks(
            requested,
            ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256.uppercase(),
        )
        assertEquals(requested, resolved.config)
        assertFalse(resolved.fallback)
    }

    @Test fun `enabled max-camera-distance emits the override pair`() {
        val flags = ClientTweaksConfig(maxCameraDistanceEnabled = true)
            .toFlags()
        val idx = flags.indexOf("--maxcameradistance")
        assertTrue(idx >= 0)
        assertEquals("100.0", flags[idx + 1])
    }

    @Test fun `legacy no-op camera distance migrates to a real raise`() {
        val legacy = ClientTweaksConfig(maxCameraDistance = 50f).toJson()
        val migrated = ClientTweaksConfig.fromJson(legacy)
        assertEquals(100f, migrated.maxCameraDistance)
    }

    @Test fun `strict control JSON round-trips every toggle and value`() {
        val allEnabled = ClientTweaksConfig(
            fovEnabled = true,
            fov = 2.1f,
            farclipEnabled = true,
            farclip = 5_000f,
            frilldistanceEnabled = true,
            frilldistance = 250f,
            soundInBackgroundEnabled = true,
            soundChannelsEnabled = true,
            soundChannels = 32,
            quicklootEnabled = true,
            nameplateEnabled = true,
            nameplateDistance = 30f,
            largeAddressAwareEnabled = true,
            cameraSkipFixEnabled = true,
            maxCameraDistanceEnabled = true,
            maxCameraDistance = 75f,
        )
        assertEquals(allEnabled, ClientTweaksConfig.fromControlJson(allEnabled.toJson()))
    }

    @Test fun `each toggle independently patches and toggles back to pristine`() {
        val pristine = syntheticQualifiedImage()
        val cases = listOf(
            ToggleCase(
                "fov", ClientTweaksConfig(fovEnabled = true),
                { it.copy(fovEnabled = false) }, "--no-fov", listOf(0x4089B4..0x4089B7),
            ),
            ToggleCase(
                "farclip", ClientTweaksConfig(farclipEnabled = true),
                { it.copy(farclipEnabled = false) }, "--no-farclip", listOf(0x40FED8..0x40FEDB),
            ),
            ToggleCase(
                "frilldistance", ClientTweaksConfig(frilldistanceEnabled = true),
                { it.copy(frilldistanceEnabled = false) }, "--no-frilldistance",
                listOf(0x467958..0x46795B),
            ),
            ToggleCase(
                "sound background", ClientTweaksConfig(soundInBackgroundEnabled = true),
                { it.copy(soundInBackgroundEnabled = false) }, "--no-sound-in-background",
                listOf(0x3A4869..0x3A4869),
            ),
            ToggleCase(
                "sound channels", ClientTweaksConfig(soundChannelsEnabled = true),
                { it.copy(soundChannelsEnabled = false) }, "--no-soundchannels",
                listOf(0x435D38..0x435D3A),
            ),
            ToggleCase(
                "quickloot", ClientTweaksConfig(quicklootEnabled = true),
                { it.copy(quicklootEnabled = false) }, "--no-quickloot",
                listOf(0x0C1ECF..0x0C1ECF, 0x0C2B25..0x0C2B25),
            ),
            ToggleCase(
                "nameplate", ClientTweaksConfig(nameplateEnabled = true),
                { it.copy(nameplateEnabled = false) }, "--no-nameplatedistance",
                listOf(0x40C448..0x40C44B),
            ),
            ToggleCase(
                "large address aware", ClientTweaksConfig(largeAddressAwareEnabled = true),
                { it.copy(largeAddressAwareEnabled = false) }, "--no-largeaddressaware",
                listOf(0x126..0x127),
            ),
            ToggleCase(
                "camera skip", ClientTweaksConfig(cameraSkipFixEnabled = true),
                { it.copy(cameraSkipFixEnabled = false) }, "--no-cameraskipfix",
                listOf(
                    0x02CCD0..0x02CD12,
                    0x02D326..0x02D32A,
                    0x02D334..0x02D339,
                    0x355D15..0x355D29,
                    0x355DDC..0x355DF9,
                ),
            ),
            ToggleCase(
                "max camera", ClientTweaksConfig(maxCameraDistanceEnabled = true),
                { it.copy(maxCameraDistanceEnabled = false) }, null,
                listOf(0x4089A4..0x4089A7),
            ),
        )

        assertArrayEquals(
            pristine,
            ClientTweaksConfig.expectedPatchedBytes(pristine, ClientTweaksConfig()),
        )
        cases.forEach { case ->
            val patched = ClientTweaksConfig.expectedPatchedBytes(pristine, case.enabled)
            val changed = changedOffsets(pristine, patched)
            assertTrue("${case.name} must change at least one authorized byte", changed.isNotEmpty())
            assertTrue(
                "${case.name} changed a byte outside its authorized ranges",
                changed.all { offset -> case.allowedRanges.any { offset in it } },
            )
            case.disabledFlag?.let { flag ->
                assertTrue("all-off must emit $flag", ClientTweaksConfig().toFlags().contains(flag))
                assertFalse(
                    "${case.name} still emitted $flag while enabled",
                    case.enabled.toFlags().contains(flag),
                )
            }

            val disabledAgain = case.disable(case.enabled)
            assertFalse("${case.name} remained enabled", disabledAgain.hasAnyPatch())
            assertArrayEquals(
                "${case.name} did not return to pristine bytes",
                pristine,
                ClientTweaksConfig.expectedPatchedBytes(pristine, disabledAgain),
            )
        }
    }

    @Test fun `signature input is stable for equal configs`() {
        val a = ClientTweaksConfig(farclip = 5000f)
        val b = ClientTweaksConfig(farclip = 5000f)
        assertEquals(a.signatureInput(), b.signatureInput())
    }
}
