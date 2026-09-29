package com.pocketrealm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Rate.XP block staged into the generated mangosd.conf from realm
 * settings (issue: "Add configurable XP rate options to realm settings").
 * ServerRuntimeFiles.xpRateConfLines interpolates this pure emission; the
 * mapping — not the string template — is the contract under test.
 */
class XpRateConfTest {

    @Test
    fun defaultsEmitTheCmangosBaseline() {
        val lines = ServerRuntimeFiles.xpRateConfLines(1.0, 1.0, 1.0, 1.0)
        assertEquals(
            "Rate.XP.Kill = 1\n" +
                "Rate.XP.Quest = 1\n" +
                "Rate.XP.Explore = 1\n" +
                "Rate.Pet.XP.Kill = 1",
            lines,
        )
    }

    @Test
    fun presetsFormatWithoutTrailingDecimals() {
        assertEquals("2", XpRatePolicy.format(2.0))
        assertEquals("3", XpRatePolicy.format(3.0))
        assertEquals("5", XpRatePolicy.format(5.0))
        val lines = ServerRuntimeFiles.xpRateConfLines(2.0, 2.0, 3.0, 5.0)
        assertTrue(lines.contains("Rate.XP.Kill = 2"))
        assertTrue(lines.contains("Rate.XP.Quest = 2"))
        assertTrue(lines.contains("Rate.XP.Explore = 3"))
        assertTrue(lines.contains("Rate.Pet.XP.Kill = 5"))
    }

    @Test
    fun fractionalRatesKeepOneDecimal() {
        assertEquals("1.5", XpRatePolicy.format(1.5))
        assertEquals("0.5", XpRatePolicy.format(0.5))
    }

    @Test
    fun outOfBandValuesClampIntoTheSupportedBand() {
        assertEquals(XpRatePolicy.MIN_RATE, XpRatePolicy.normalize(0.0), 1e-9)
        assertEquals(XpRatePolicy.MIN_RATE, XpRatePolicy.normalize(-4.0), 1e-9)
        assertEquals(XpRatePolicy.MAX_RATE, XpRatePolicy.normalize(99.0), 1e-9)
        assertEquals(
            XpRatePolicy.DEFAULT_RATE,
            XpRatePolicy.normalize(Double.NaN),
            1e-9,
        )
    }

    @Test
    fun normalizedValuesSnapToTenthsAndCompareExactlyWithPresets() {
        // 0.1 cannot be represented exactly in binary; the tenths
        // representation must still land every normalized value exactly on
        // its preset so the settings UI selection state is stable.
        XpRatePolicy.PRESET_RATES.forEach { preset ->
            assertEquals(preset, XpRatePolicy.normalize(preset), 0.0)
            assertEquals(preset, XpRatePolicy.normalize(preset + 0.04), 0.0)
        }
    }
}
