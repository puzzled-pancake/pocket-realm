package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the per-flavor information copy
 * is LIVE, not test-only dead code —
 *
 * - the built-in Android Port installs by default on EVERY game version, so
 *   the add-ons hub intro says so unconditionally (the old non-vanilla
 *   exclusion note is retired with the tbc/wotlk port);
 * - the settings tweaks card renders the plain "not available" copy on
 *   non-vanilla lanes via `ClientRuntimeContract.flavorTweaksCopy`.
 *
 * Compose-level rendering is covered by the screen tests; this pin holds
 * the SOURCE SHAPE (the vanilla-only claims live inside vanilla-conditional
 * branches) so a revert cannot slip past a text-only grep.
 */
class PerFlavorSurfaceCopyPinTest {

    @Test fun `addons hub intro is unconditional across game versions`() {
        val source = java.io.File(
            "src/main/java/com/pocketrealm/ui/AddonsScreen.kt",
        ).readText()
        // The installed-by-default intro renders on all three versions: the
        // string must appear exactly once and outside any flavor conditional.
        val intro = "The built-in Android Port is installed by default"
        assertEquals(
            "the hub intro must state the default install exactly once",
            1, Regex(intro).findAll(source).count(),
        )
        assertFalse(
            "the retired exclusion note must not ride the hub copy",
            "catalog.flavorNote" in source,
        )
    }

    @Test fun `the hardware-tested claim branches by flavor R5-6-3`() {
        // The vanilla trio's frozen evidence backs the tested-on-hardware
        // copy ONLY on the vanilla lane; the other versions lead with the
        // hardware-tested controller UI, so both claims must sit inside
        // vanilla-conditional branches that name the lead.
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
            "the non-vanilla fallback must name the controller-UI lead",
            "hardware-tested controller UI" in source,
        )
        assertTrue(
            "the recommended screen's tested line must be vanilla-conditional",
            Regex(
                "\"Each entry has been tested on Pocket Realm hardware\\. \"",
            ).containsMatchIn(source),
        )
        assertFalse(
            "the retired not-hardware-tested claim must stay out",
            "not hardware-tested." in source,
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
        // the D83 remainder is RETIRED — the
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
