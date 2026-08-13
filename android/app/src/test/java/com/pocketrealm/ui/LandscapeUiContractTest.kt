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
        val required = setOf(
            "Population target", "Nearby density", "Nearby radius", "Login batch",
            "Maintenance batch", "Background update interval", "Bot work per tick",
            "Reduce population above world p99", "Fully active background bots",
            "Limit background combat work", "Quest and level autonomously",
            "Chat without a player master", "Invite the player",
            "Form groups with nearby bots", "Wander when idle", "Use off-spec strategies",
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
