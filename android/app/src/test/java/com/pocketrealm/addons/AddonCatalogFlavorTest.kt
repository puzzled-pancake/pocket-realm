package com.pocketrealm.addons

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the per-flavor addon catalogs — each flavor loads its OWN researched
 * asset (the byte-frozen vanilla v1 asset unchanged on the vanilla lane,
 * D29); the per-flavor assets are self-pinning (declared counts, the
 * AndroidPort built-in carried as a first-class row); every entry projects
 * through the flavor projection unchanged; and the vanilla parse pins are
 * untouched.
 */
class AddonCatalogFlavorTest {

    @Test fun `vanilla parses unchanged with its frozen pins`() {
        val text = readAsset("addons/catalog-v1.json")
        val catalog = AddonCatalog.parse(text, "vanilla")
        assertEquals(154, catalog.addons.size)
        assertEquals(266, catalog.compatibilityPairs.size)
        assertEquals("vanilla", catalog.flavor)
        assertNull(catalog.flavorNote)
        // The AndroidPort built-in stays the vanilla lane's.
        assertNotNull(catalog.addon("151"))
    }

    @Test fun `per-flavor catalogs parse with their self-declared counts`() {
        for (flavor in listOf("tbc", "wotlk")) {
            val text = readAsset("addons/catalog-$flavor.json")
            val catalog = AddonCatalog.parse(text, flavor)
            assertEquals(flavor, catalog.flavor)
            assertEquals(
                "$flavor: the catalog carries exactly its self-declared rows",
                org.json.JSONObject(text).getInt("expectedAddonCount"),
                catalog.addons.size,
            )
            assertTrue(catalog.compatibilityPairs.isNotEmpty())
            // Every carry-over/new entry names the flavor's own client line.
            val token = if (flavor == "tbc") "2.4.3" else "3.3.5"
            assertTrue(
                "$flavor: every entry must research the $token client line",
                catalog.addons.all { token in it.clientTarget },
            )
            // The AndroidPort built-in is a first-class row on every flavor,
            // installable from the catalog the same way as on vanilla.
            val androidPort = checkNotNull(catalog.addon("151")) {
                "$flavor: the AndroidPort built-in row must be present " +
                    "(it installs by default on every game version)"
            }
            assertEquals(AndroidPortPackage.INSTALL_ID, androidPort.installId)
            assertTrue(androidPort.installable)
            assertTrue(
                "$flavor: row 151 must sit in the recommended set",
                "151" in catalog.recommendedIds,
            )
            checkNotNull(catalog.flavorNote)
            // The projection passes every entry through unchanged (the
            // screens' safety net never hides a curated entry).
            assertEquals(
                catalog.addons.map { it.matrixId },
                AddonFlavorCatalog.forFlavor(
                    catalog.addons,
                    if (flavor == "tbc") ClientFlavors.TBC else ClientFlavors.WOTLK,
                ).map { it.matrixId },
            )
            // Every pair references in-catalog rows.
            catalog.compatibilityPairs.forEach { pair ->
                assertNotNull(catalog.addon(pair.addonA))
                assertNotNull(catalog.addon(pair.addonB))
            }
            // every reference-grade row must be explicitly named
            // as non-installable by the flavorNote (the Recommended screen's
            // copy can never promise an install a reference row can't do).
            val refRows = catalog.addons.filter { !it.installable }
            for (ref in refRows) {
                assertTrue(
                    "$flavor: reference row ${ref.matrixId} must be named by " +
                        "the flavorNote",
                    catalog.flavorNote?.contains(ref.name) == true ||
                        catalog.flavorNote?.contains("reference") == true,
                )
            }
            // A reference-grade recommended row's reason must acknowledge
            // its non-installability.
            for (rec in catalog.recommended) {
                if (!rec.installable) {
                    assertTrue(
                        "$flavor: recommended reference row ${rec.matrixId} " +
                            "must acknowledge non-installability in its reason",
                        catalog.recommendationReason(rec.matrixId)?.contains("Reference") == true ||
                            catalog.recommendationReason(rec.matrixId)?.contains("reference") == true,
                    )
                }
            }
        }
    }

    @Test fun `every recommended row carries its per-flavor reason (R6-r2-1)`() {
        for (flavor in listOf("tbc", "wotlk")) {
            val catalog = AddonCatalog.parse(readAsset("addons/catalog-$flavor.json"), flavor)
            catalog.recommended.forEach { addon ->
                assertNotNull(
                    "recommended row ${addon.matrixId} has no per-flavor " +
                        "recommendation reason (the Recommended screen would crash)",
                    catalog.recommendationReason(addon.matrixId),
                )
            }
        }
        // A recommended row without a reason fails at parse (the crash
        // becomes an unreachability invariant).
        val text = readAsset("addons/catalog-tbc.json")
            .replace("\"recommendationReasons\"", "\"recommendationReasonsX\"")
        assertThrows(IllegalStateException::class.java) {
            AddonCatalog.parse(text, "tbc")
        }
    }

    @Test fun `a swapped per-flavor asset refuses (R2-4, R6-3)`() {
        // catalog-wotlk parsed as tbc (and the mirror) must refuse: the
        // asset's own flavor identity field is checked against the lane.
        val wotlkText = readAsset("addons/catalog-wotlk.json")
        val tbcText = readAsset("addons/catalog-tbc.json")
        val asTbc = assertThrows(IllegalArgumentException::class.java) {
            AddonCatalog.parse(wotlkText, "tbc")
        }
        assertTrue(
            "the refusal must name both flavors: ${asTbc.message}",
            "wotlk" in asTbc.message!! && "tbc" in asTbc.message!!,
        )
        assertThrows(IllegalArgumentException::class.java) {
            AddonCatalog.parse(tbcText, "wotlk")
        }
    }

    @Test fun `hand-edited per-flavor counts fail loud`() {
        val text = readAsset("addons/catalog-tbc.json")
        val tampered = Regex("\"expectedAddonCount\": (\\d+)").replace(text) { match ->
            "\"expectedAddonCount\": ${match.groupValues[1].toInt() + 1}"
        }
        check(tampered != text)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AddonCatalog.parse(tampered, "tbc")
        }
        assertTrue(
            "the refusal must name the self-pinning contract: ${failure.message}",
            "self-pinning" in failure.message!!,
        )
    }

    @Test fun `an androidport row removed from a per-flavor catalog fails loud`() {
        // The must-carry pin: a hand edit dropping the built-in row would
        // silently skip the fresh-install seed on that flavor, so the parse
        // refuses instead.
        val text = readAsset("addons/catalog-tbc.json")
        val smuggled = text.replace("\"matrixId\": \"151\"", "\"matrixId\": \"998\"")
        check(smuggled != text)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AddonCatalog.parse(smuggled, "tbc")
        }
        assertTrue(
            "the refusal must name the must-carry contract: ${failure.message}",
            "must carry the AndroidPort built-in" in failure.message!!,
        )
    }

    @Test fun `a duplicated androidport row in a per-flavor catalog fails loud`() {
        val text = readAsset("addons/catalog-tbc.json")
        val smuggled = text.replace("\"matrixId\": \"201\"", "\"matrixId\": \"151\"")
        check(smuggled != text)
        assertThrows(IllegalArgumentException::class.java) {
            AddonCatalog.parse(smuggled, "tbc")
        }
    }

    @Test fun `unknown flavor ids fail loud never a silent vanilla`() {
        assertThrows(IllegalStateException::class.java) {
            AddonCatalog.catalogAssetFor("wotlk-classic-demo")
        }
        assertEquals("addons/catalog-v1.json", AddonCatalog.catalogAssetFor("vanilla"))
        assertEquals("addons/catalog-tbc.json", AddonCatalog.catalogAssetFor("tbc"))
        assertEquals("addons/catalog-wotlk.json", AddonCatalog.catalogAssetFor("wotlk"))
    }

    @Test fun `the androidport seeding legs skip per flavor instead of crashing`() {
        // Source pin: both lanes' seed/refresh resolve the exclusion through
        // the catalog lookup (?: return), never a checkNotNull crash.
        for (source in listOf(
            "../../android/app/src/main/java/com/pocketrealm/addons/AddonRepository.kt",
            "../../desktop/src/main/kotlin/com/pocketrealm/addons/DesktopAddonRepository.kt",
        )) {
            val text = java.io.File(source).readText()
            assertTrue(
                "$source: the checkNotNull crash on the excluded AndroidPort row re-appeared",
                "checkNotNull(AddonCatalog.load" !in text,
            )
            assertTrue(
                "$source: the exclusion leg is missing",
                ".addon(\"151\")" in text && "?: return@launchOperation" in text,
            )
        }
    }

    private fun readAsset(path: String): String {
        // The android unit-test working dir is android/app; the assets live
        // under src/main/assets.
        return java.io.File("src/main/assets/$path").readText()
    }
}
