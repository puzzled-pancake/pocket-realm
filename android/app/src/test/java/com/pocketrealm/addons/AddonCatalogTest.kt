package com.pocketrealm.addons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonCatalogTest {
    private val assetRoot: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets"),
        ).first { it.isDirectory }
    }

    @Test fun `integrated workbook catalog is complete and searchable`() {
        val catalog = AddonCatalog.parse(File(assetRoot, "addons/catalog-v1.json").readText())

        assertEquals(154, catalog.addons.size)
        assertEquals(266, catalog.compatibilityPairs.size)
        assertEquals("2026-08-11", catalog.researchedAt)
        assertNull(catalog.addon("155"))
        assertTrue(catalog.filter("quest", null).any { it.name == "pfQuest" })
        assertTrue(catalog.filter("", "Questing").isNotEmpty())
    }

    @Test fun `recommended collection is small stable and entirely optional`() {
        val catalog = AddonCatalog.parse(File(assetRoot, "addons/catalog-v1.json").readText())

        assertEquals(
            listOf(
                "Android Port", "pfQuest", "ShaguTweaks", "Bagnon", "Flyout", "MinimapButtonFrame",
                "Roid-Macros", "ModifiedPowerAuras", "VanillaGuide-Enhanced", "WoW VoiceOver",
            ),
            catalog.recommended.map { it.name },
        )
        assertTrue(catalog.recommended.all { it.installable })
        assertTrue(catalog.recommended.all { catalog.recommendationReason(it.matrixId).orEmpty().endsWith('.') })
        val ids = catalog.recommended.map { it.matrixId }.toSet()
        assertTrue(catalog.compatibilityPairs.filter {
            it.addonA in ids && it.addonB in ids
        }.none { it.meaning.contains("Avoid", ignoreCase = true) })
    }

    @Test fun `vanilla console port is a project owned built in identity`() {
        val addon = requireNotNull(AddonCatalog.parse(File(assetRoot, "addons/catalog-v1.json").readText()).addon("151"))
        assertEquals(AddonInstallSource.BUILTIN, addon.installSource)
        assertEquals(VanillaConsolePortPackage.INSTALL_ID, addon.installId)
        assertEquals("0.4.0 Pocket Realm", addon.version)
        assertEquals("0.4.0", VanillaConsolePortPackage.VERSION)
        assertNull(addon.githubUrl)
        assertTrue(addon.communitySignal.contains("not externally rated"))
        assertEquals(listOf("builtin:addons/vanilla-console-port"), addon.researchSources)
    }

    @Test fun `catalog exposes installed compatibility exceptions`() {
        val catalog = AddonCatalog.parse(File(assetRoot, "addons/catalog-v1.json").readText())
        val pfUi = requireNotNull(catalog.addon("001"))
        val shaguTweaks = requireNotNull(catalog.addon("003"))
        val installed = InstalledAddon(
            id = requireNotNull(shaguTweaks.installId),
            repository = requireNotNull(shaguTweaks.githubUrl),
            displayName = shaguTweaks.name,
            commitSha = "a".repeat(40),
            archiveSha256 = "b".repeat(64),
            installedAtEpochMs = 1,
            packagePath = "packages/test",
            folders = listOf("ShaguTweaks"),
        )

        val relation = catalog.compatibilityFor(pfUi.matrixId, listOf(installed)).single()
        assertEquals("ShaguTweaks", relation.first.name)
        assertTrue(relation.second.meaning.contains("Overlap", ignoreCase = true))
    }
}
