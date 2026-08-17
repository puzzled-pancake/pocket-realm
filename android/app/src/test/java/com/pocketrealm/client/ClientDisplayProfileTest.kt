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
        assertEquals(ClientFrameCap.FPS_30, ClientDisplaySelection(
            profile, ClientFrameCap.FPS_30, profile.nominalDisplay(),
        ).frameCap)
        // Windowed-maximized: a non-maximized 1.12 window falls back to its
        // built-in 800x600 size on the ARM DXVK lane and letterboxes the
        // desktop. Maximized covers the virtual desktop on every lane.
        assertEquals(true, profile.gameMaximized)
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
        assertEquals(ClientFrameCap.FPS_30, ClientDisplaySelection.defaultForDevice(
            listOf("arm64-v8a"), "Retroid Pocket 6",
        ).frameCap)
        assertEquals(true, profile.gameMaximized)
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

    @Test
    fun sixteenNinePanelResolvesExactReferenceGeometry() {
        val balanced = ClientDisplayProfile.BALANCED.resolveFor(1920, 1080)
        assertEquals("1280x720", balanced.resolution)
        assertEquals(1.5f, balanced.uniformScaleTo(1920, 1080), 0f)

        val quality = ClientDisplayProfile.QUALITY.resolveFor(1920, 1080)
        assertEquals("1920x1080", quality.resolution)
        assertEquals(1f, quality.uniformScaleTo(1920, 1080), 0f)
    }

    @Test
    fun widerThanSixteenNinePanelAdoptsPanelAspect() {
        // 20:9 phone panel in landscape: uniform 1.5x scale fills 2400x1080.
        val display = ClientDisplayProfile.BALANCED.resolveFor(2400, 1080)
        assertEquals(1600, display.width)
        assertEquals(720, display.height)
        assertEquals(1.5f, display.uniformScaleTo(2400, 1080), 0f)
    }

    @Test
    fun narrowerThanSixteenNinePanelAdoptsPanelAspect() {
        // 4:3 panel in landscape: the desktop narrows so 1.5x fills 1440x1080.
        val display = ClientDisplayProfile.BALANCED.resolveFor(1440, 1080)
        assertEquals(960, display.width)
        assertEquals(720, display.height)
    }

    @Test
    fun adaptationRoundsWidthDownToEven() {
        val display = ClientDisplayProfile.BALANCED.resolveFor(2560, 1080)
        assertEquals(0, display.width % 2)
        org.junit.Assert.assertTrue(display.width in 1704..1708)
    }

    @Test
    fun resolutionFailsClosedWhenHeightClassExceedsPanel() {
        assertThrows(IllegalArgumentException::class.java) {
            ClientDisplayProfile.BALANCED.resolveFor(1280, 480)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClientDisplayProfile.QUALITY.resolveFor(1280, 720)
        }
    }

    @Test
    fun selectionResolvesVirtualDesktopForPanel() {
        val selection = ClientDisplaySelection.forPhysical(
            ClientDisplayProfile.BALANCED,
            ClientFrameCap.FPS_30,
            2400,
            1080,
        )
        assertEquals("1600x720", selection.resolution)
        assertEquals(1600, selection.virtualWidth)
        assertEquals(720, selection.virtualHeight)
    }

    @Test
    fun restoredDisplayIsDowngradedOnlyWhenItExceedsPhysicalBounds() {
        val retained = ClientDisplayCapabilities.normalizeProfileForPhysical(
            ClientDisplayProfile.QUALITY,
            ClientDisplayProfile.BALANCED,
            1920,
            1080,
        )
        assertEquals(ClientDisplayProfile.QUALITY, retained.profile)
        assertEquals(false, retained.changed)

        val downgraded = ClientDisplayCapabilities.normalizeProfileForPhysical(
            ClientDisplayProfile.QUALITY,
            ClientDisplayProfile.BALANCED,
            1280,
            720,
        )
        assertEquals(ClientDisplayProfile.BALANCED, downgraded.profile)
        assertEquals(true, downgraded.changed)
        assertEquals(
            listOf(ClientDisplayProfile.BALANCED),
            ClientDisplayProfile.availableForPhysical(720, 1280),
        )
    }

    @Test
    fun qualityFitsTallNonWidePanelsByAdaptingWidth() {
        // A 4:3 1440x1080 panel hosts Quality at 1440x1080; the old fixed
        // 1920x1080 geometry did not fit and silently downgraded to Balanced.
        assertEquals(
            listOf(
                ClientDisplayProfile.BALANCED,
                ClientDisplayProfile.QUALITY,
                ClientDisplayProfile.CLASSIC_43,
            ),
            ClientDisplayProfile.availableForPhysical(1440, 1080),
        )
        assertEquals("1440x1080", ClientDisplayProfile.QUALITY.resolveFor(1440, 1080).resolution)
    }

    @Test
    fun classic43KeepsExactGeometryAndPillarboxesOnWidePanels() {
        assertEquals(
            listOf(
                ClientDisplayProfile.BALANCED,
                ClientDisplayProfile.QUALITY,
                ClientDisplayProfile.CLASSIC_43,
            ),
            ClientDisplayProfile.availableForPhysical(1920, 1080),
        )
        // Exact geometry on a 16:9 panel: no width adaptation to 1706x960.
        assertEquals(
            "1280x960",
            ClientDisplayProfile.CLASSIC_43.resolveFor(1920, 1080).resolution,
        )
        // And on a native 4:3 panel it fills exactly.
        assertEquals(
            "1280x960",
            ClientDisplayProfile.CLASSIC_43.resolveFor(1440, 1080).resolution,
        )
        // Too tall for shorter panels.
        assertEquals(
            listOf(ClientDisplayProfile.BALANCED),
            ClientDisplayProfile.availableForPhysical(1280, 720),
        )
    }
}
