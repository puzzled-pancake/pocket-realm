package com.pocketrealm.addons

import com.pocketrealm.addons.AddonCatalog
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    // ------------------------------------------------------------------
    // R2-1 (android-ui-tests review round 1): research text that names a
    // client line inside a NEGATION clause ("explicitly NOT for 2.4.3",
    // "broken since 3.3.5") must never read as SUPPORT — bare substring
    // matching listed such add-ons on the wrong-flavor lane.
    // ------------------------------------------------------------------

    @Test
    fun negatedClientTargetsNeverListOnTheWrongFlavorLane() {
        assertFalse(
            "explicitly NOT for 2.4.3 must not read as tbc support",
            AddonFlavorCatalog.supportsFlavor(
                addon("stable on 1.12; explicitly NOT for 2.4.3"),
                ClientFlavors.TBC,
            ),
        )
        assertFalse(
            "broken since 3.3.5 must not read as wotlk support",
            AddonFlavorCatalog.supportsFlavor(
                addon("broken since 3.3.5, do not use"),
                ClientFlavors.WOTLK,
            ),
        )
        // the positive reads are untouched
        assertTrue(
            AddonFlavorCatalog.supportsFlavor(
                addon("stable on 1.12"),
                ClientFlavors.VANILLA,
            ),
        )
        assertTrue(
            AddonFlavorCatalog.supportsFlavor(
                addon("1.12 + 2.4.3"),
                ClientFlavors.TBC,
            ),
        )
    }

    @Test
    fun negationPhrasingCorpusNeverReadsAsSupport() {
        // R2UI-2/R2-1/R6-r2-2 (android-ui-tests review round 2): the round-1
        // 8-cue regex missed every phrasing below (each returned true — the
        // add-on listed on the wrong-flavor lane). The widened window/cue
        // model must refuse them ALL, including the canonical suffixed
        // wotlk spelling and cue-AFTER-token negations.
        val probes = listOf(
            "incompatible with 2.4.3" to ClientFlavors.TBC,
            "unsupported on 3.3.5" to ClientFlavors.WOTLK,
            "won't load on 2.4.3" to ClientFlavors.TBC,
            "doesn't work on 3.3.5" to ClientFlavors.WOTLK,
            "crashes on 3.3.5" to ClientFlavors.WOTLK,
            "unusable on 2.4.3" to ClientFlavors.TBC,
            "No longer supported. 2.4.3 broke it." to ClientFlavors.TBC,
            "explicitly NOT for 3.3.5a" to ClientFlavors.WOTLK,
            "broken since 3.3.5a" to ClientFlavors.WOTLK,
            "2.4.3 not supported" to ClientFlavors.TBC,
            "3.3.5 not supported" to ClientFlavors.WOTLK,
        )
        for ((text, flavor) in probes) {
            assertFalse(
                "negation phrasing \"$text\" must not read as ${flavor.flavorId} support",
                AddonFlavorCatalog.supportsFlavor(addon(text), flavor),
            )
        }
        // positive controls unchanged
        assertTrue(AddonFlavorCatalog.supportsFlavor(addon("stable on 1.12"), ClientFlavors.VANILLA))
        assertTrue(AddonFlavorCatalog.supportsFlavor(addon("1.12 + 2.4.3"), ClientFlavors.TBC))
    }

    @Test
    fun theSuffixedWotlkSpellingIsTheSameClientLine() {
        // "3.3.5a" (wow-3.3.5a-12340) is the canonical wotlk spelling: the
        // boundary match must treat the suffixed form as the 3.3.5 line.
        assertTrue(
            AddonFlavorCatalog.versionTokenMatches("3.3.5a hotfix", "3.3.5"),
        )
        assertFalse(
            AddonFlavorCatalog.versionTokenMatches("13.3.5 hotfix", "3.3.5"),
        )
    }

    @Test
    fun aNegatedButOtherwiseValidGithubEntryIsRefusedOnBothTwins() {
        // R2UI-1/R6-r2-1 (android-ui-tests review round 2): the negated
        // fixture must be GITHUB-shaped and otherwise fully valid, so the
        // ONLY thing that can reject it is the negation guard — on BOTH
        // twins (the desktop parse lacks the guard == IAE masking shape).
        val githubEntry = """
            {"schema": 1, "addons": [{
                "matrixId": "m-1", "name": "Negation Probe Github",
                "category": "Test", "description": "probe",
                "githubUrl": "https://github.com/owner/probe",
                "installSource": "GITHUB",
                "clientTarget": "stable on 1.12; explicitly NOT for 2.4.3",
                "version": "v1", "verifiedUpdate": "2026-01-01",
                "dateConfidence": "Verified exact", "communitySignal": "test",
                "maintenance": "maintained", "compatibilityNotes": "",
                "researchSources": [], "compatibilityRole": "standalone",
                "handheldScore": 8.0, "handheldVerdict": "good"
            }]}
        """.trimIndent()
        val failure = assertThrows(IllegalStateException::class.java) {
            AddonCatalog.parse(githubEntry)
        }
        assertTrue(
            "the refusal must name the negation-clause contract: " + failure.message,
            "negation" in failure.message!!,
        )
    }

    @Test
    fun negationClausesAreRejectedAtCatalogParse() {
        val text = """
            {"schema": 1, "addons": [{
                "matrixId": "m-1", "name": "Negation Probe",
                "category": "Test", "description": "probe",
                "clientTarget": "stable on 1.12; explicitly NOT for 2.4.3",
                "version": "v1", "verifiedUpdate": "2026-01-01",
                "dateConfidence": "Verified exact", "communitySignal": "test",
                "maintenance": "maintained", "compatibilityNotes": "",
                "researchSources": [], "compatibilityRole": "standalone",
                "handheldScore": 8.0, "handheldVerdict": "good",
                "installSource": "BUILTIN"
            }]}
        """.trimIndent()
        val failure = assertThrows(IllegalStateException::class.java) {
            AddonCatalog.parse(text)
        }
        assertTrue(
            "the parse refusal must name the negation-clause contract: " +
                failure.message,
            "negation" in failure.message!!,
        )
    }

    @Test
    fun versionTokensNeverMatchInsideALongerVersion() {
        // boundary discipline: 2.4.3 must not match inside 2.4.35
        assertFalse(
            AddonFlavorCatalog.supportsFlavor(
                addon("2.4.35 hotfix"),
                ClientFlavors.TBC,
            ),
        )
        assertTrue(
            AddonFlavorCatalog.supportsFlavor(
                addon("2.4.3 hotfix"),
                ClientFlavors.TBC,
            ),
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
