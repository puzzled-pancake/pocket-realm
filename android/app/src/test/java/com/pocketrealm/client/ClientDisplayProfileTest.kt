package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClientDisplayProfileTest {
    @Test
    fun x86SelectsRetainedBalancedDimensions() {
        val profile = ClientDisplayProfile.forSupportedAbis(listOf("x86_64"))
        assertEquals(ClientDisplayProfile.BALANCED, profile)
        assertEquals(1280, profile.virtualWidth)
        assertEquals(720, profile.virtualHeight)
        assertEquals("1280x720", profile.resolution)
        assertEquals(30, profile.initialFrameCap)
        assertEquals(false, profile.gameMaximized)
        assertEquals(1.5f, profile.exactScaleTo(1920, 1080), 0f)
    }

    @Test
    fun arm64SelectsNativePanelQualityDimensions() {
        val profile = ClientDisplayProfile.forDevice(
            listOf("arm64-v8a", "armeabi-v7a"),
            "Retroid Pocket 6",
        )
        assertEquals(ClientDisplayProfile.QUALITY, profile)
        assertEquals(1920, profile.virtualWidth)
        assertEquals(1080, profile.virtualHeight)
        assertEquals("1920x1080", profile.resolution)
        assertEquals(30, profile.initialFrameCap)
        assertEquals(true, profile.gameMaximized)
        assertEquals(1f, profile.exactScaleTo(1920, 1080), 0f)
        assertEquals(
            ClientDisplayProfile.BALANCED,
            ClientDisplayProfile.forDevice(listOf("arm64-v8a"), "Unqualified ARM Device"),
        )
    }

    @Test
    fun x86WinsForUniversalDevelopmentListAndUnknownAbiFailsClosed() {
        assertEquals(
            ClientDisplayProfile.BALANCED,
            ClientDisplayProfile.forSupportedAbis(listOf("arm64-v8a", "x86_64")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ClientDisplayProfile.forSupportedAbis(listOf("armeabi-v7a"))
        }
    }
}
