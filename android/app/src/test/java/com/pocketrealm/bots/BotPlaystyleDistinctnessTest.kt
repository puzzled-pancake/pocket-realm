package com.pocketrealm.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BotPlaystyleDistinctnessTest {
    @Test
    fun namedPlaystylePresetsArePairwiseDistinct() {
        // Verification history: CLASSIC_WORLD and INDEPENDENT were
        // tuple-identical, so both chips rendered selected at once and the
        // recommended default matched both. Pin pairwise distinctness of the
        // applied values so that class of bug cannot regress.
        val presets = BotPlaystylePreset.entries.filter { it != BotPlaystylePreset.CUSTOM }
        presets.forEach { first ->
            presets.forEach { second ->
                if (first != second) {
                    assertNotEquals(
                        "$first and $second apply identical values",
                        first.applyTo(BotCustomConfiguration.fromProfile(BotProfiles.ALIVE_REALM_320)),
                        second.applyTo(BotCustomConfiguration.fromProfile(BotProfiles.ALIVE_REALM_320)),
                    )
                }
            }
        }
    }

    @Test
    fun recommendedDefaultMatchesAtMostOnePreset() {
        val defaultCustom = BotCustomConfiguration.fromProfile(BotProfiles.defaultProfile)
        val matches = BotPlaystylePreset.entries.filter {
            it != BotPlaystylePreset.CUSTOM && it.matches(defaultCustom)
        }
        assertEquals(1, matches.size)
    }
}
