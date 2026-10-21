package com.pocketrealm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home 'Active setup' card's
 * tweak-less label must be flavor-true for EVERY qualified flavor. The
 * old copy hard-coded " · Vanilla client", so a TBC or WotLK active
 * setup (where tweaks are always off) was mislabeled "Vanilla client".
 * The label is now flavor-blind by construction ("Unpatched client" =
 * the pristine client, no tweaks — true on every lane); this pin holds
 * the wording so the hard-coded flavor name cannot come back.
 */
class HomeSetupCopyTest {

    /** The module source, resolved the way AGP unit tests see the tree
     * (the module directory is the working directory). */
    private fun homeScreenSource(): String {
        val moduleDir = generateSequence(
            java.io.File(".")
        ) { it.parentFile }.firstOrNull { dir ->
            java.io.File(dir, "src/main/java/com/pocketrealm/ui/HomeScreen.kt").isFile
        } ?: error("cannot locate the HomeScreen.kt source from the test cwd")
        return java.io.File(
            moduleDir, "src/main/java/com/pocketrealm/ui/HomeScreen.kt"
        ).readText()
    }

    @Test
    fun tweakLessSetupLabelIsFlavorNeutralNotVanilla() {
        val source = homeScreenSource()
        val expression = "if (settings.tweaks.hasAnyPatch()) \" · Client tweaks on\" else \" · Unpatched client\""
        assertTrue(
            "the Active-setup tweak-less label must be the flavor-neutral " +
                "wording (found neither: look for the CurrentSetupCard line)",
            expression in source,
        )
        assertFalse(
            "the Active-setup label must never name a flavor (" +
                "\u00b7 Vanilla client\u00b7 class): the same expression " +
                "renders for tbc/wotlk active setups, where it lied",
            "\" · Vanilla client\"" in source,
        )
    }
}
