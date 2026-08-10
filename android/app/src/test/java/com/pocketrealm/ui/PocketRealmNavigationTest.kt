package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PocketRealmNavigationTest {
    @Test
    fun routesResolveWithoutStaticInitializationCycles() {
        assertEquals("Home", Screen.fromRoute("home")?.label)
        assertEquals("Add-ons", Screen.fromRoute("addons")?.label)
        assertEquals("Controls", Screen.fromRoute("controls")?.label)
        assertEquals("Settings", Screen.fromRoute("settings")?.label)
        assertEquals("Game setup", Screen.fromRoute("client")?.label)
        assertNull(Screen.fromRoute(null))
        assertNull(Screen.fromRoute("unknown"))
    }

    @Test
    fun nearbyBotSliderNeverRoundsPastThePopulationTarget() {
        assertEquals(25, normalizeNearbyBotLimit(raw = 25f, target = 25))
        assertEquals(24, normalizeNearbyBotLimit(raw = 24.1f, target = 25))
        assertEquals(50, normalizeNearbyBotLimit(raw = 50f, target = 700))
        assertEquals(0, normalizeNearbyBotLimit(raw = 0f, target = 25))
    }
}
