package com.pocketrealm.client

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
        // Upstream defaults: both sound patches applied → no --no-sound-* emitted.
        val common = ClientTweaksConfig.commonPreset().toFlags()
        assertFalse(common.contains("--no-sound-in-background"))
        assertFalse(common.contains("--no-soundchannels"))
    }

    @Test fun `common preset sound channels emits no numeric override`() {
        val flags = ClientTweaksConfig.commonPreset().toFlags()
        assertFalse(flags.contains("--soundchannels"))
        assertFalse(flags.contains("--no-soundchannels"))
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
        val flags = ClientTweaksConfig(maxCameraDistanceEnabled = true, maxCameraDistance = 100f)
            .toFlags()
        val idx = flags.indexOf("--maxcameradistance")
        assertTrue(idx >= 0)
        assertEquals("100.0", flags[idx + 1])
    }

    @Test fun `signature input is stable for equal configs`() {
        val a = ClientTweaksConfig(farclip = 5000f)
        val b = ClientTweaksConfig(farclip = 5000f)
        assertEquals(a.signatureInput(), b.signatureInput())
    }
}
