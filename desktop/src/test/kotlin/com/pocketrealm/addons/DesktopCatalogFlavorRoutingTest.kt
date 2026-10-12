package com.pocketrealm.addons

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R6-r2-5/R2-4 (code-red round 2): the desktop addon lane is FLAVOR-ROUTED
 * — the catalog resolves the active lane's per-flavor asset (the researched
 * tbc/wotlk sets, not the vanilla catalog's free-text projection), the
 * asset's own flavor identity is enforced, and the vanilla lane stays
 * byte-frozen.
 */
class DesktopCatalogFlavorRoutingTest {

    /** The repo's android assets tree is the desktop dev asset source
     * (DesktopAddonAssets' walk-up leg). */
    private fun readAsset(name: String): String {
        val dir = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .firstOrNull { java.io.File(it, "android/app/src/main/assets/addons/$name").isFile() }
            ?: error("assets root not found from ${java.io.File(".").absolutePath}")
        return java.io.File(dir, "android/app/src/main/assets/addons/$name").readText()
    }

    @Test fun `catalogAssetFor mirrors the android lane mapping`() {
        assertEquals("catalog-v1.json", DesktopAddonAssets.catalogAssetName("vanilla"))
        assertEquals("catalog-tbc.json", DesktopAddonAssets.catalogAssetName("tbc"))
        assertEquals("catalog-wotlk.json", DesktopAddonAssets.catalogAssetName("wotlk"))
        assertThrows(IllegalStateException::class.java) {
            DesktopAddonAssets.catalogAssetName("wotlk-classic-demo")
        }
    }

    @Test fun `per-flavor assets parse with their own identity and counts`() {
        val tbc = AddonCatalog.parse(readAsset("catalog-tbc.json"), "tbc")
        assertEquals("tbc", tbc.flavor)
        assertEquals(7, tbc.addons.size)
        val wotlk = AddonCatalog.parse(readAsset("catalog-wotlk.json"), "wotlk")
        assertEquals("wotlk", wotlk.flavor)
        assertEquals(8, wotlk.addons.size)
        // The researched 2xx rows the vanilla projection never surfaced.
        assertTrue(tbc.addons.any { it.matrixId == "201" })
        assertTrue(wotlk.addons.any { it.matrixId == "211" })
        // A swapped asset refuses on the desktop lane too.
        assertThrows(IllegalArgumentException::class.java) {
            AddonCatalog.parse(readAsset("catalog-wotlk.json"), "tbc")
        }
    }

    @Test fun `the active-flavor load resolves the lane's own catalog`() {
        // The vanilla lane resolves the byte-frozen asset.
        ActiveFlavor.reset()
        val vanilla = AddonCatalog.load("vanilla")
        assertEquals(154, vanilla.addons.size)
        // Per-flavor lanes resolve their own sets through the same seam the
        // screens use.
        val tbc = AddonCatalog.load("tbc")
        assertEquals("tbc", tbc.flavor)
        assertTrue(
            "the tbc browse set must surface the researched 2xx rows",
            tbc.addons.any { it.matrixId == "201" },
        )
    }
}
