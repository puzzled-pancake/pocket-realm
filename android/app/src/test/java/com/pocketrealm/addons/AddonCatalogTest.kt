package com.pocketrealm.addons

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
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

        assertEquals(155, catalog.addons.size)
        assertEquals(311, catalog.compatibilityPairs.size)
        assertEquals("2026-08-11", catalog.researchedAt)
        assertEquals("PocketRealmPad", requireNotNull(catalog.addon("155")).name)
        assertTrue(catalog.filter("quest", null).any { it.name == "pfQuest" })
        assertTrue(catalog.filter("", "Controller UI").isNotEmpty())
    }

    @Test fun `recommended collection is small stable and entirely optional`() {
        val catalog = AddonCatalog.parse(File(assetRoot, "addons/catalog-v1.json").readText())

        assertEquals(
            listOf("PocketRealmPad", "Roid-Macros", "ModifiedPowerAuras", "VanillaGuide-Enhanced", "WoW VoiceOver"),
            catalog.recommended.map { it.name },
        )
        assertTrue(catalog.recommended.all { it.installable })
        assertTrue(catalog.recommended.all { catalog.recommendationReason(it.matrixId).orEmpty().endsWith('.') })
        assertTrue(catalog.addon("155")!!.installSource == AddonInstallSource.BUNDLED)
        val ids = catalog.recommended.map { it.matrixId }.toSet()
        assertTrue(catalog.compatibilityPairs.filter {
            it.addonA in ids && it.addonB in ids
        }.all { it.meaning.contains("Complementary", ignoreCase = true) })
    }

    @Test fun `bundled PocketRealmPad archive matches catalog and validates as Vanilla`() {
        val catalog = AddonCatalog.parse(File(assetRoot, "addons/catalog-v1.json").readText())
        val archive = File(assetRoot, catalog.bundledAssetPath)
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertEquals(catalog.bundledSha256, digest)
        val validated = AddonArchiveValidator().validate(archive, "PocketRealmPad")
        assertEquals(listOf("PocketRealmPad"), validated.addonFolders)
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
