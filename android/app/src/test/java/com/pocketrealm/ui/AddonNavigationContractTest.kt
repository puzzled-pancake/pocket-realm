package com.pocketrealm.ui

import com.pocketrealm.addons.AddonCatalog
import com.pocketrealm.addons.InstalledAddon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonNavigationContractTest {
    private val assetRoot: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"), File("android/app/src/main/assets"))
            .first { it.isDirectory }
    }

    @Test fun `addon child routes have focused titles and safe identities`() {
        assertEquals("Add-ons", screenTitle(AddonRoutes.HUB))
        assertEquals("Recommended add-ons", screenTitle(AddonRoutes.RECOMMENDED))
        assertEquals("My add-ons", screenTitle(AddonRoutes.INSTALLED))
        assertEquals("Browse add-ons", screenTitle(AddonRoutes.BROWSE))
        assertEquals("Install from GitHub", screenTitle(AddonRoutes.CUSTOM))
        assertEquals("addons/catalog/155", AddonRoutes.catalogDetail("155"))
        assertTrue(AddonRoutes.installedDetail("owner__custom-addon").startsWith("addons/installed/"))
        assertTrue(runCatching { AddonRoutes.catalogDetail("../155") }.isFailure)
    }

    @Test fun `custom installed addons are preserved even without a catalog row`() {
        val catalog = AddonCatalog.parse(File(assetRoot, "addons/catalog-v1.json").readText())
        val custom = InstalledAddon(
            id = "owner__custom-addon",
            repository = "https://github.com/owner/custom-addon",
            displayName = "CustomAddon",
            commitSha = "a".repeat(40),
            archiveSha256 = "b".repeat(64),
            installedAtEpochMs = 1,
            packagePath = "packages/custom",
            folders = listOf("CustomAddon"),
        )
        val rows = installedAddonPresentations(catalog, listOf(custom))

        assertEquals(1, rows.size)
        assertEquals(custom, rows.single().installed)
        assertNull(rows.single().catalogAddon)
    }
}
