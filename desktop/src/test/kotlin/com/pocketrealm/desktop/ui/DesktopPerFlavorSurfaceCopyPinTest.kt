package com.pocketrealm.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6-1/R6-5 (code-red round 4): the desktop add-ons screen's AndroidPort
 * copy is FLAVOR-CONDITIONAL (mirroring the android fix) and the desktop
 * in-game settings screen's vanilla-catalog remainder is recorded for the
 * W4 flip checklist.
 */
class DesktopPerFlavorSurfaceCopyPinTest {

    @Test fun `addons hub copy is flavor-conditional on the catalog flavor`() {
        val source = java.io.File(
            "src/main/kotlin/com/pocketrealm/desktop/ui/AddonsScreen.kt",
        ).readText()
        val conditionals = Regex(
            "catalog\\.flavor == com\\.pocketrealm\\.client\\.ClientFlavors\\.VANILLA\\.flavorId",
        ).findAll(source).count()
        assertTrue(
            "Desktop AddonsScreen must gate the AndroidPort copy on the catalog " +
                "flavor (found $conditionals conditionals, need >= 3)",
            conditionals >= 3,
        )
        assertTrue(
            "Desktop AddonsScreen must render catalog.flavorNote",
            "catalog.flavorNote" in source,
        )
    }

    @Test fun `the tweaks card gates on the flavor contract R6-w3-1`() {
        // Desktop parity of the android ClientTweaksCard gate: the vanilla
        // tweak set is validated against the 1.12.1 client only, so the
        // desktop card must consult ClientRuntimeContract.flavorTweaksCopy
        // and hide the toggle set behind the unavailable branch on a
        // non-vanilla lane.
        val source = java.io.File(
            "src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt",
        ).readText()
        assertTrue(
            "the desktop ClientTweaksCard must consult ClientRuntimeContract" +
                ".flavorTweaksCopy",
            "com.pocketrealm.client.ClientRuntimeContract" in source &&
                ".flavorTweaksCopy(" in source,
        )
        assertTrue(
            "the card must resolve the ACTIVE lane for the gate",
            "com.pocketrealm.client.ActiveFlavor.resolve(" in source,
        )
        assertTrue(
            "the toggle set must sit behind the unavailable branch (a " +
                "non-vanilla lane never renders the vanilla patch toggles)",
            Regex("if \\(tweaksUnavailable != null\\)[\\s\\S]*?return@Column")
                .containsMatchIn(source),
        )
    }

    @Test fun `the desktop ClientRuntimeContract twin stays identical to android R6-w3-1`() {
        // The desktop twin of the android contract object must carry the
        // SAME flavorTweaksCopy body — a wording drift would render
        // different honest copy per lane.
        fun copyBody(path: String): String {
            val text = java.io.File(path).readText()
            val at = text.indexOf("fun flavorTweaksCopy(")
            // The expression-bodied function ends with the shared string
            // literal in both twins — slice to it inclusively.
            val end = text.indexOf("1.12.1 client only.\"", at)
            // Line-wrapping differences are formatting, not contract
            // drift: compare the token stream (whitespace, the string-
            // concatenation operator, and the string delimiters stripped
            // — the SIGNATURE and the rendered string content must match).
            return text.substring(at, end + "1.12.1 client only.\"".length)
                .replace(Regex("[\\s+\"]"), "")
        }
        val desktop = copyBody(
            "src/main/kotlin/com/pocketrealm/client/ClientRuntimeContract.kt",
        )
        val android = copyBody(
            "../android/app/src/main/java/com/pocketrealm/client/ClientRuntime.kt",
        )
        org.junit.Assert.assertEquals(
            "the desktop ClientRuntimeContract.flavorTweaksCopy twin drifted " +
                "from the android original — keep the contract bodies identical",
            android, desktop,
        )
    }

    @Test fun `the tweaks contract hides the vanilla toggles on new lanes R6-w3-1`() {
        // The behavior leg: the gating contract itself — vanilla's toggle
        // set is available (null copy), the tbc/wotlk lanes refuse it with
        // the honest expansion-named copy. A tbc/wotlk desktop lane can
        // therefore never expose the nine vanilla patch toggles.
        val vanilla = com.pocketrealm.client.ClientFlavors.VANILLA
        val tbc = com.pocketrealm.client.ClientFlavors.TBC
        val wotlk = com.pocketrealm.client.ClientFlavors.WOTLK
        org.junit.Assert.assertNull(
            "vanilla's tweak set must stay available",
            com.pocketrealm.client.ClientRuntimeContract.flavorTweaksCopy(vanilla),
        )
        assertTrue(
            "vanilla must be the only tweaks-enabled lane",
            vanilla.vanillaTweaksEnabled &&
                !tbc.vanillaTweaksEnabled && !wotlk.vanillaTweaksEnabled,
        )
        for (flavor in listOf(tbc, wotlk)) {
            val copy = com.pocketrealm.client.ClientRuntimeContract
                .flavorTweaksCopy(flavor)
            org.junit.Assert.assertNotNull(
                "${flavor.flavorId}: the honest unavailable copy must render",
                copy,
            )
            assertTrue(
                "the copy must name the expansion: $copy",
                flavor.expansion in copy!!,
            )
        }
    }

    @Test fun `the hardware-tested claim branches by flavor R5-6-3`() {
        // Desktop parity of the android R5-6-3 fix: the tested-on-hardware
        // copy is the vanilla trio's evidence and must sit in
        // vanilla-conditional branches naming the honest fallback.
        val source = java.io.File(
            "src/main/kotlin/com/pocketrealm/desktop/ui/AddonsScreen.kt",
        ).readText()
        assertTrue(
            "the hub's hardware-tested summary must be present in a branch",
            Regex(
                "\"The three add-ons tested on Pocket Realm hardware\\.\"",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the non-vanilla fallback must say research reference, not tested",
            "research \" +\n                            \"reference, not hardware-tested." in source,
        )
        assertTrue(
            "the recommended screen's tested line must be present in a branch",
            Regex(
                "\"Each entry has been tested on Pocket Realm hardware\\. \"",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the non-vanilla recommended copy must carry the reference-grade wording",
            "(reference-grade evidence, not hardware-tested). " in source,
        )
    }
    @Test fun `the desktop settings screen routes through the flavor-routed seam R5-1`() {
        // R5-1 (W4 code-red round 5): the D83 remainder is RETIRED - the
        // desktop settings screen resolves every catalog read through the
        // flavor-routed seam (WowFlavorCatalogs + the editor's ACTIVE
        // lane), never a hard-coded vanilla object.
        val source = java.io.File(
            "src/main/kotlin/com/pocketrealm/desktop/ui/InGameSettingsScreen.kt",
        ).readText()
        assertTrue(
            "the desktop settings screen lost the flavor-routed catalog " +
                "reads (R5-1)",
            "WowFlavorCatalogs" in source,
        )
        assertFalse(
            "a hard-coded vanilla catalog reference remains in the desktop " +
                "settings screen (R5-1)",
            Regex("WowVanilla(Settings|Binding)Catalog\\.\\w+").containsMatchIn(source),
        )
    }
}
