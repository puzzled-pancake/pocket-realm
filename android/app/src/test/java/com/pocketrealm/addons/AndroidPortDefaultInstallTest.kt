package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in Android Port installs by default on EVERY game version: each
 * flavor's own catalog carries row 151 as an installable built-in (the
 * fresh-install seed resolves exactly that row per flavor), and the row sits
 * in each flavor's recommended set with its reason.
 */
class AndroidPortDefaultInstallTest {
    private val assetRoot: File by lazy {
        listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("android/app/src/main/assets"),
            // Desktop JVM lane: the shared asset tree sits one level up.
            File("../android/app/src/main/assets"),
        ).first { it.isDirectory }
    }

    @Test fun `every flavor catalog carries an installable androidport row`() {
        for (flavor in ClientFlavors.ALL) {
            val asset = when (flavor) {
                ClientFlavors.VANILLA -> "addons/catalog-v1.json"
                ClientFlavors.TBC -> "addons/catalog-tbc.json"
                ClientFlavors.WOTLK -> "addons/catalog-wotlk.json"
                else -> error("unregistered flavor")
            }
            val catalog = AddonCatalog.parse(File(assetRoot, asset).readText(), flavor.flavorId)
            val row = checkNotNull(catalog.addon("151")) {
                "${flavor.flavorId}: the AndroidPort row is missing — the " +
                    "fresh-install seed would skip silently"
            }
            assertEquals(AndroidPortPackage.INSTALL_ID, row.installId)
            assertEquals(AndroidPortPackage.ASSET_PATH, row.assetPath)
            assertTrue("${flavor.flavorId}: the row must be installable", row.installable)
            // The seed resolves this exact row; the projection then binds the
            // add-on to the flavor's client line.
            assertTrue(
                "${flavor.flavorId}: the row must name its own client line",
                flavor.addonInterfaces.all { interfaceNumber ->
                    row.clientTarget.contains(interfaceNumber.toString())
                },
            )
        }
    }

    @Test fun `the androidport row is recommended on every flavor`() {
        for (flavor in listOf("tbc", "wotlk")) {
            val catalog = AddonCatalog.parse(
                File(assetRoot, "addons/catalog-$flavor.json").readText(),
                flavor,
            )
            assertTrue(
                "$flavor: the AndroidPort row must lead the recommended set",
                catalog.recommendedIds.first() == "151",
            )
            assertTrue(
                "$flavor: the recommended row must carry its reason",
                catalog.recommendationReason("151") != null,
            )
        }
    }

    @Test fun `the tbc and wotlk interface numbers land in their row copy`() {
        val tbc = AddonCatalog.parse(
            File(assetRoot, "addons/catalog-tbc.json").readText(), "tbc",
        )
        assertTrue("20400" in checkNotNull(tbc.addon("151")).clientTarget)
        val wotlk = AddonCatalog.parse(
            File(assetRoot, "addons/catalog-wotlk.json").readText(), "wotlk",
        )
        assertTrue("30300" in checkNotNull(wotlk.addon("151")).clientTarget)
    }
}
