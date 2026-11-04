package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the desktop lane ships the VANILLA catalog (row
 * 151 always present), so the AndroidPort per-flavor exclusion must gate on
 * the ACTIVE FLAVOR — the old `addon("151") ?: return` skip was dead code
 * (never null). The seed decision is extracted as a pure function and
 * pinned; the two seed/refresh legs are source-pinned to consult it.
 */
class DesktopAndroidPortExclusionTest {

    @Test fun `the androidport seeds only on the vanilla lane`() {
        assertTrue(
            "vanilla: the built-in seeds",
            DesktopAddonRepository.seedAndroidPortForLane(
                ClientFlavors.VANILLA.flavorId,
            ),
        )
        for (flavor in listOf(ClientFlavors.TBC, ClientFlavors.WOTLK)) {
            assertTrue(
                "${flavor.flavorId}: the AndroidPort seed must be excluded " +
                    "(owner decision, completion-plan table #8)",
                !DesktopAddonRepository.seedAndroidPortForLane(flavor.flavorId),
            )
        }
    }

    @Test fun `both seed and refresh legs consult the lane gate`() {
        // Source pin: BOTH legs (seed + refresh) check isVanillaLane() —
        // a leg that reverts to the row-absence skip would silently seed
        // the vanilla-only AndroidPort on a tbc/wotlk lane.
        val source = java.io.File(
            "src/main/kotlin/com/pocketrealm/addons/DesktopAddonRepository.kt",
        ).readText()
        val legs = source.split("launchOperation(").size - 1
        assertTrue(
            "both legs must consult the lane gate",
            Regex("""if \(!isVanillaLane\(\)\) return@launchOperation""").findAll(source).count() >= 2,
        )
        assertTrue(
            "the checkNotNull skip shape re-appeared",
            "checkNotNull(AddonCatalog.load" !in source,
        )
        assertTrue(legs >= 2)
    }
}
