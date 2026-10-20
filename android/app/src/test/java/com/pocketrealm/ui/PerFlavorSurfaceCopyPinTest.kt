package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6-1/R6-4 (code-red round 1): the per-flavor exclusion/information copy
 * is LIVE, not test-only dead code —
 *
 * - the add-ons screens render the catalog's honest AndroidPort exclusion
 *   note on non-vanilla lanes and never claim "installed by default" there;
 * - the settings tweaks card renders the honest "not available" copy on
 *   non-vanilla lanes via `ClientRuntimeContract.flavorTweaksCopy`.
 *
 * Compose-level rendering is covered by the screen tests; this pin holds
 * the SOURCE SHAPE (the copy strings live inside vanilla-conditional
 * branches) so a revert cannot slip past a text-only grep.
 */
class PerFlavorSurfaceCopyPinTest {

    @Test fun `addons hub copy is flavor-conditional on the catalog flavor`() {
        val source = java.io.File(
            "src/main/java/com/pocketrealm/ui/AddonsScreen.kt",
        ).readText()
        // The conditional shape rides `catalog.flavor == VANILLA.flavorId`.
        val conditionals = Regex(
            "catalog\\.flavor == com\\.pocketrealm\\.client\\.ClientFlavors\\.VANILLA\\.flavorId",
        ).findAll(source).count()
        assertTrue(
            "AddonsScreen must gate the AndroidPort copy on the catalog flavor " +
                "(found $conditionals conditionals, need >= 3: hub intro, " +
                "recommended row, empty-installed line)",
            conditionals >= 3,
        )
        // The authored honest copy renders through catalog.flavorNote.
        assertTrue(
            "AddonsScreen must render catalog.flavorNote (the honest exclusion note)",
            "catalog.flavorNote" in source,
        )
    }

    @Test fun `the hardware-tested claim branches by flavor R5-6-3`() {
        // The vanilla trio's frozen evidence backs the tested-on-hardware
        // copy ONLY on the vanilla lane; the tbc/wotlk curated sets are
        // research reference (the round-3 demotion), so both claims must sit
        // inside vanilla-conditional branches that name the honest fallback.
        val source = java.io.File(
            "src/main/java/com/pocketrealm/ui/AddonsScreen.kt",
        ).readText()
        assertTrue(
            "the hub's hardware-tested summary must be vanilla-conditional",
            Regex(
                "\"The three add-ons tested on Pocket Realm hardware\\.\"",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the non-vanilla fallback must say research reference, not tested",
            "research" in source && "reference, not hardware-tested." in source,
        )
        assertTrue(
            "the recommended screen's tested line must be vanilla-conditional",
            Regex(
                "\"Each entry has been tested on Pocket Realm hardware\\. \"",
            ).containsMatchIn(source),
        )
        assertTrue(
            "the non-vanilla recommended copy must carry the reference-grade wording",
            "(reference-grade evidence, not hardware-tested). " in source,
        )
    }

    @Test fun `tweaks card renders the honest copy via flavorTweaksCopy`() {
        val source = java.io.File(
            "src/main/java/com/pocketrealm/ui/SettingsScreen.kt",
        ).readText()
        assertTrue(
            "SettingsScreen must consult ClientRuntimeContract.flavorTweaksCopy",
            "flavorTweaksCopy" in source,
        )
        assertTrue(
            "the tweaks card must resolve the active flavor",
            source.contains("ActiveFlavor.resolve") || source.contains("ActiveFlavor.displayedId"),
        )
    }

    @Test fun `renderer card renders the honest per-flavor copy R6-r3-4`() {
        val source = java.io.File(
            "src/main/java/com/pocketrealm/ui/SettingsScreen.kt",
        ).readText()
        assertTrue(
            "SettingsScreen must consult ClientRuntimeContract.flavorGraphicsRouteCopy",
            "flavorGraphicsRouteCopy" in source,
        )
        assertTrue(
            "the renderer card must resolve the active lane flavor",
            source.contains("ActiveFlavor.resolve") || source.contains("ActiveFlavor.displayedId"),
        )
    }
    @Test fun `the settings screens route through the flavor-routed seam R5-1`() {
        // R5-1 (W4 code-red round 5): the D83 remainder is RETIRED — the
        // settings/bindings/controls screens resolve every catalog read
        // through the flavor-routed seam (WowFlavorCatalogs + the active
        // lane), never a hard-coded vanilla object.
        val files = listOf(
            "src/main/java/com/pocketrealm/ui/InGameSettingsScreens.kt",
            "src/main/java/com/pocketrealm/ui/InGameBindingsScreen.kt",
            "src/main/java/com/pocketrealm/ui/ControlsScreen.kt",
        )
        for (f in files) {
            val text = java.io.File(f).readText()
            assertTrue(
                "$f: lost the flavor-routed catalog reads (R5-1)",
                "WowFlavorCatalogs" in text,
            )
            assertFalse(
                "$f: a hard-coded vanilla catalog reference remains (R5-1)",
                Regex("WowVanilla(Settings|Binding)Catalog\\.\\w+").containsMatchIn(text),
            )
        }
    }
}
