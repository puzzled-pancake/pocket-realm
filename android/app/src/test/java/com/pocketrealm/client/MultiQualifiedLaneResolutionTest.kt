package com.pocketrealm.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * P6: every multi-qualified lane the eight former
 * `qualified().single()` sites lifted into the [ActiveFlavor] machine,
 * exercised with TWO qualified flavors registered through
 * [TwoQualifiedFlavors] — the exact resolution shape each site calls:
 *
 *  - the PERSISTED-PICK lanes (cross-process sites that can never see the
 *    UI process's in-memory selection): WineRuntimeStore's ARM runtime
 *    authorization, DatabaseSnapshotStore's restore compatibility gate,
 *    AndroidRuntimeBackend's launch client-build lane and realmd-builds
 *    projection. Each resolves
 *    `ActiveFlavorState(persistedPick).resolve(ActiveFlavor.qualifiedFlavors, lane)`;
 *  - the HOLDER lanes (UI-process sites seeded by the Home switcher):
 *    ClientGenerationStore's active-generation default, DataPreparationStore's
 *    publish AND recover resolves, InGameSettingsEditor's lease gate. Each
 *    resolves `ActiveFlavor.resolve(lane)`.
 *
 * Under two qualified flavors: a tbc pick routes the lane to the REAL TBC
 * identity; the absent pick refuses loud with the lane's own failure copy
 * (never a silent VANILLA default); a declared-but-unqualified pick refuses
 * loud. The lane copies are pinned verbatim — each is the user-facing
 * failure message its site surfaces.
 */
class MultiQualifiedLaneResolutionTest {

    @After
    fun restoreRegistryDefaults() {
        ActiveFlavor.reset()
    }

    // ---- the persisted-pick lanes (shape: ActiveFlavorState(pick).resolve) ----

    /** One cross-process lane: its name and failure copy. */
    private data class PersistedPickLane(val name: String, val lane: String)

    private val persistedPickLanes = listOf(
        PersistedPickLane(
            "WineRuntimeStore ARM runtime authorization",
            "the ARM translated runtime authorizes the active lane's client",
        ),
        PersistedPickLane(
            "DatabaseSnapshotStore restore compatibility gate",
            "the database snapshot compatibility gate resolves the active lane",
        ),
        PersistedPickLane(
            "AndroidRuntimeBackend launch client-build lane",
            "the runtime launch resolves the active lane's client build",
        ),
        PersistedPickLane(
            "AndroidRuntimeBackend realmd-builds projection",
            "the realmlist projection resolves the active lane's accepted builds",
        ),
    )

    @Test
    fun persistedPickLanesRouteToTheSelectedLaneUnderTwoQualifiedFlavors() {
        TwoQualifiedFlavors.install()
        for (site in persistedPickLanes) {
            val resolved = ActiveFlavorState(ClientFlavors.TBC.flavorId)
                .resolve(ActiveFlavor.qualifiedFlavors, site.lane)
            assertEquals(
                "${site.name}: the tbc pick routes the lane to the real TBC identity",
                TwoQualifiedFlavors.TBC,
                resolved,
            )
        }
        // The observable each lane consumes follows the selection — the
        // production TBC values, never a vanilla default.
        val armAuthorization = ActiveFlavorState(ClientFlavors.TBC.flavorId)
            .resolve(ActiveFlavor.qualifiedFlavors, persistedPickLanes[0].lane)
        assertEquals("build 8606", "build ${armAuthorization.build}")
        val snapshotLane = ActiveFlavorState(ClientFlavors.TBC.flavorId)
            .resolve(ActiveFlavor.qualifiedFlavors, persistedPickLanes[1].lane)
        assertEquals("tbc", snapshotLane.flavorId)
        val realmdBuilds = ActiveFlavorState(ClientFlavors.TBC.flavorId)
            .resolve(ActiveFlavor.qualifiedFlavors, persistedPickLanes[3].lane)
        assertEquals("8606", realmdBuilds.realmdAcceptedBuilds)
        // And the vanilla pick routes the same lanes to vanilla.
        val vanilla = ActiveFlavorState(ClientFlavors.VANILLA.flavorId)
            .resolve(ActiveFlavor.qualifiedFlavors, persistedPickLanes[3].lane)
        assertEquals("5875 6005 6141", vanilla.realmdAcceptedBuilds)
    }

    @Test
    fun persistedPickLanesRefuseLoudWithoutAPickUnderTwoQualifiedFlavors() {
        TwoQualifiedFlavors.install()
        for (site in persistedPickLanes) {
            val failure = assertThrows(IllegalStateException::class.java) {
                ActiveFlavorState(null).resolve(ActiveFlavor.qualifiedFlavors, site.lane)
            }
            assertEquals(
                "${site.name}: the ambiguous no-pick state names its own lane",
                "expected exactly one qualified flavor, found 2 — ${site.lane}",
                failure.message,
            )
        }
    }

    @Test
    fun persistedPickLanesRefuseADeclaredButUnqualifiedPick() {
        TwoQualifiedFlavors.install()
        for (site in persistedPickLanes) {
 // Flip: wotlk is QUALIFIED now, so the stale-pick refusal uses
            // a synthetic unqualified lane (the contract is the pick's own
            // qualification, not wotlk's registry state).
            val disabled = ClientFlavors.WOTLK.copy(qualified = false)
            val failure = assertThrows(IllegalStateException::class.java) {
                // the pick is absent from the qualified list entirely (a
                // stale registry read): the machine refuses the stale pick
                // by name instead of downgrading.
                ActiveFlavorState(disabled.flavorId)
                    .resolve(listOf(ClientFlavors.VANILLA), site.lane)
            }
            assertEquals(
                "${site.name}: a stale or declared-but-disabled pick never downgrades the lane",
                "active flavor \"wotlk\" is not qualified — ${site.lane}",
                failure.message,
            )
        }
    }

    // ---- the holder lanes (shape: ActiveFlavor.resolve after select) ----

    /** One UI-process lane: its failure copy. */
    private data class HolderLane(val name: String, val lane: String)

    private val holderLanes = listOf(
        HolderLane(
            "ClientGenerationStore active-generation default",
            "the active client generation resolves the qualified lane",
        ),
        HolderLane(
            "DataPreparationStore publish resolve",
            "server data preparation resolves the qualified lane",
        ),
        HolderLane(
            "DataPreparationStore recover resolve",
            "server data preparation resolves the qualified lane",
        ),
        HolderLane(
            "InGameSettingsEditor lease gate",
            "the in-game settings editor resolves the qualified lane",
        ),
    )

    @Test
    fun holderLanesRouteToTheSelectedLaneUnderTwoQualifiedFlavors() {
        for (selection in listOf(ClientFlavors.VANILLA.flavorId, ClientFlavors.TBC.flavorId)) {
            TwoQualifiedFlavors.install(selection = selection)
            for (site in holderLanes) {
                assertEquals(
                    "${site.name}: the switcher's $selection pick drives the resolve",
                    ActiveFlavor.qualifiedFlavors.first { it.flavorId == selection },
                    ActiveFlavor.resolve(site.lane),
                )
            }
        }
    }

    @Test
    fun holderLanesRefuseLoudWithoutASelectionUnderTwoQualifiedFlavors() {
        TwoQualifiedFlavors.install()
        ActiveFlavor.clearSelection()
        for (site in holderLanes) {
            val failure = assertThrows(IllegalStateException::class.java) {
                ActiveFlavor.resolve(site.lane)
            }
            assertEquals(
                "${site.name}: the ambiguous no-selection state names its own lane",
                "expected exactly one qualified flavor, found 2 — ${site.lane}",
                failure.message,
            )
        }
    }

    // ---- the shared honesty laws under the flip ----

    @Test
    fun theFlipFixtureIsSeamOnly() {
        TwoQualifiedFlavors.install()
 // Flip: the PRODUCTION registry now qualifies all three lanes
        // (the real flip) — the fixture's two-lane list stays
        // a test-only seam and never mutates it. The seam's list is still
        // distinguishable from production's THREE (wotlk absent).
        assertEquals(
            "the two-lane fixture stays a seam: vanilla+tbc, wotlk absent",
            listOf(ClientFlavors.VANILLA, TwoQualifiedFlavors.TBC),
            TwoQualifiedFlavors.lanes,
        )
        assertEquals(
            "the production registry carries all three qualified lanes",
            setOf("vanilla", "tbc", "wotlk"),
            ClientFlavors.qualified().map { it.flavorId }.toSet(),
        )
    }
}
