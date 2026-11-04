package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the desktop flavor-switcher pick is
 * PERSISTED and RE-HYDRATED, the Android twin's contract — before this,
 * the desktop lane never wrote the pick and never re-applied it on cold
 * start, so at the multi-qualified flip every restart landed in an
 * unselected registry state whose fail-loud resolvers (add-ons screen,
 * home timeframe line, provisionAccount's accountExpansion, the backend's
 * active-generation lane) refused until a re-pick.
 *
 * Three layers, mirroring the proposed test:
 *  1. the persisted pick RESOLVES where the unselected two-qualified
 *     registry throws (the fail-loud consumer contract);
 *  2. a restart simulation: a fresh holder + a hydrated pick from the
 *     persisted settings key restores the exact lane;
 *  3. the wiring pin: the switcher tap writes the persisted key and the
 *     Home screen re-applies the persisted pick (source contract on the
 *     composables — the machine layer above is the behavioral half).
 */
class FlavorPersistenceWiringTest {

    @Test
    fun hydratedPickResolvesWhereTheUnselectedTwoQualifiedRegistryThrows() {
        TwoQualifiedFlavors.install()
        try {
            // unselected: every fail-loud consumer refuses
            val thrown = runCatching {
                ActiveFlavor.resolve("the add-ons screen resolves the active lane")
            }.exceptionOrNull()
            assertNotNull("an unselected two-qualified registry must refuse", thrown)
            // hydrated with the persisted pick: the same call resolves
            TwoQualifiedFlavors.install(selection = "tbc")
            assertEquals("tbc", ActiveFlavor.resolve("hydrated").flavorId)
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun coldStartReAppliesThePersistedPickAndNeverOverridesAnExplicitPick() {
        try {
            // cold start: fresh holder + a persisted pick arrives
            TwoQualifiedFlavors.install()
            assertEquals(null, ActiveFlavor.selectedId)
            val persisted = SettingsPersistedPick("tbc")
            // the Home screen's hydration leg: persisted + no explicit pick -> re-apply
            if (ActiveFlavor.selectedId == null) {
                persisted.value?.let { ActiveFlavor.select(it) }
            }
            assertEquals("tbc", ActiveFlavor.selectedId)
            // an explicit in-session pick is NEVER overridden by a later
            // hydration (the guard is `selectedId == null`)
            val persistedOther = SettingsPersistedPick("vanilla")
            if (ActiveFlavor.selectedId == null) {
                persistedOther.value?.let { ActiveFlavor.select(it) }
            }
            assertEquals("the explicit pick stays", "tbc", ActiveFlavor.selectedId)
            // a persisted id that stopped being qualified stays refused
            // (no silent downgrade to the sole-qualified lane)
            val stale = runCatching {
                TwoQualifiedFlavors.install()
                ActiveFlavor.select("wotlk")
            }.exceptionOrNull()
            assertNotNull("a stale persisted pick must refuse loud", stale)
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun homeScreenWiringPersistsThePickAndReHydratesOnArrival() {
        // source contract on the two composables (the behavioral machine
        // half is pinned above; the JSON round trip in
        // SettingsSnapshotCodecTest)
        val repoRoot = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { java.io.File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        val home = java.io.File(
            repoRoot, "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/HomeScreen.kt",
        ).readText()
        assertTrue(
            "the switcher tap must persist the pick (model.updateSettings copy(selectedFlavorId))",
            "model.updateSettings { it.copy(selectedFlavorId = chip.game.flavorId) }" in home,
        )
        assertTrue(
            "the hydration leg must re-apply the persisted pick",
            "settings.selectedFlavorOrNull()" in home &&
                "ActiveFlavor.select(persisted)" in home,
        )
        assertTrue(
            "the hydration must never override an explicit in-session pick",
            "if (ActiveFlavor.selectedId == null)" in home,
        )
    }
}

/** The persisted-pick read seam the hydration consumes (the settings
 * snapshot's [com.pocketrealm.storage.Settings.Snapshot.selectedFlavorOrNull]
 * contract, modeled as a value for the machine-level test). */
private class SettingsPersistedPick(persisted: String?) {
    val value: String? = persisted
}
