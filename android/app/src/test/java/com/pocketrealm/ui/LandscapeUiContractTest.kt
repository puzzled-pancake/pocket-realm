package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeUiContractTest {
    @Test
    fun fullHdAndDensityScaledLandscapeUseWidePaneLayout() {
        assertEquals(PaneLayout.WIDE, paneLayout(widthDp = 1920f, heightDp = 1080f))
        assertEquals(PaneLayout.WIDE, paneLayout(widthDp = 960f, heightDp = 540f))
        assertEquals(PaneLayout.WIDE, paneLayout(widthDp = 640f, heightDp = 360f))
        assertEquals(PaneLayout.STACKED, paneLayout(widthDp = 540f, heightDp = 960f))
        assertEquals(PaneLayout.STACKED, paneLayout(widthDp = 599f, heightDp = 400f))
    }

    @Test
    fun everyAdvancedControlHasConcisePlainEnglishHelp() {
        // Bot advanced controls moved to the dedicated Bots destination; this
        // contract now covers the controls Settings still renders.
        val required = setOf(
            "Poll interval", "Stable polls", "Login UI settle", "Session timeout",
            "Drain poll", "Input drain timeout", "IME key dwell", "IME key gap",
            "Field settle", "Pointer dwell", "Widescreen FoV fix", "Farclip cap raise",
            "Frill distance raise", "Sound in background", "Sound channel count (64)",
            "Quickloot reverse (shift = manual)", "Nameplate distance (41 yd)",
            "Large address aware", "Camera skip glitch fix", "Max camera distance raise",
        )
        assertEquals(required, advancedSettingExplanations.keys)
        advancedSettingExplanations.values.forEach { explanation ->
            assertTrue(explanation, explanation.endsWith('.'))
            assertTrue(explanation, explanation.split(' ').size >= 7)
        }
    }
}
