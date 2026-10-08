package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-ons screens' flavor projection (the multi-flavor lift): the
 * screens list ONLY the active flavor's addon set, resolved from the
 * catalog's own `clientTarget` research data.
 */
class AddonFlavorCatalogTest {

    @Test
    fun vanillaTokensCoverTheWholeResearched112Line() {
        assertTrue(addon("1.12.1").let { AddonFlavorCatalog.supportsFlavor(it, ClientFlavors.VANILLA) })
        assertTrue(addon("1.12 + 2.4.3 + 3.3.5").let { AddonFlavorCatalog.supportsFlavor(it, ClientFlavors.VANILLA) })
    }

    @Test
    fun tbcAndWotlkTokensMatchOnlyResearchThatNamesThoseClients() {
        val dual = addon("1.12.1 + 2.4.3")
        val triple = addon("1.12 + 2.4.3 + 3.3.5")
        val vanillaOnly = addon("1.12.1")
        val wotlkOnly = addon("3.3.5")

        assertTrue(AddonFlavorCatalog.supportsFlavor(dual, ClientFlavors.TBC))
        assertFalse(AddonFlavorCatalog.supportsFlavor(dual, ClientFlavors.WOTLK))
        assertTrue(AddonFlavorCatalog.supportsFlavor(triple, ClientFlavors.TBC))
        assertTrue(AddonFlavorCatalog.supportsFlavor(triple, ClientFlavors.WOTLK))
        assertFalse(AddonFlavorCatalog.supportsFlavor(vanillaOnly, ClientFlavors.TBC))
        assertFalse(AddonFlavorCatalog.supportsFlavor(vanillaOnly, ClientFlavors.WOTLK))
        assertTrue(AddonFlavorCatalog.supportsFlavor(wotlkOnly, ClientFlavors.WOTLK))
        assertFalse(AddonFlavorCatalog.supportsFlavor(wotlkOnly, ClientFlavors.TBC))
    }

    @Test
    fun unknownFlavorResolvesToAnHonestEmptySet() {
        val unknown = testFlavorOf("cata", qualified = true)
        assertTrue(AddonFlavorCatalog.versionTokens(unknown).isEmpty())
        assertFalse(AddonFlavorCatalog.supportsFlavor(addon("1.12.1"), unknown))
    }

    @Test
    fun forFlavorProjectsExactlyTheMatchingEntriesInOrder() {
        val entries = listOf(
            addon("1.12.1", matrixId = "101"),
            addon("1.12.1 + 2.4.3", matrixId = "102"),
            addon("3.3.5", matrixId = "103"),
        )
        assertEquals(
            listOf("101", "102"),
            AddonFlavorCatalog.forFlavor(entries, ClientFlavors.VANILLA).map { it.matrixId },
        )
        assertEquals(
            listOf("102"),
            AddonFlavorCatalog.forFlavor(entries, ClientFlavors.TBC).map { it.matrixId },
        )
        assertEquals(
            listOf("103"),
            AddonFlavorCatalog.forFlavor(entries, ClientFlavors.WOTLK).map { it.matrixId },
        )
    }

    @Test
    fun queryFilterKeepsTheCatalogOrderingAndFields() {
        val entries = listOf(
            addon("1.12.1", name = "pfUI", installable = false, score = 9.0),
            addon("1.12.1", name = "QuestHelper", installable = true, score = 7.0),
            addon("1.12.1", name = "AtlasLoot", installable = true, score = 8.0),
        )
        val filtered = AddonFlavorCatalog.filter(entries, "quest")
        assertEquals(listOf("QuestHelper"), filtered.map { it.name })
        // Installable entries sort ahead of reference-only ones, then score.
        val all = AddonFlavorCatalog.filter(entries, "")
        assertEquals(
            listOf("AtlasLoot", "QuestHelper", "pfUI"),
            all.map { it.name },
        )
    }

    private fun addon(
        clientTarget: String,
        name: String = "Addon",
        installable: Boolean = true,
        score: Double = 8.0,
        matrixId: String = nextMatrixId(),
    ): CatalogAddon = CatalogAddon(
        matrixId = matrixId,
        name = name,
        category = "Test",
        description = "researched for $clientTarget",
        githubUrl = if (installable) "https://github.com/owner/$name" else null,
        builtinId = null,
        assetPath = null,
        clientTarget = clientTarget,
        version = "v1",
        verifiedUpdate = "2026-01-01",
        dateConfidence = "Verified exact",
        communitySignal = "test",
        maintenance = "maintained",
        compatibilityNotes = "",
        researchSources = emptyList(),
        compatibilityRole = "standalone",
        handheldScore = score,
        handheldVerdict = "good",
        installSource = if (installable) AddonInstallSource.GITHUB else AddonInstallSource.REFERENCE,
    )

    private fun nextMatrixId(): String {
        counter += 1
        return counter.toString().padStart(3, '0')
    }

    private fun testFlavorOf(flavorId: String, qualified: Boolean): ClientFlavor =
        com.pocketrealm.client.testFlavor(flavorId, qualified)

    private companion object {
        var counter = 0
    }
}
