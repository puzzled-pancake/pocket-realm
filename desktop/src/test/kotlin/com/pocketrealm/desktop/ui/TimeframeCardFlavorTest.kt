package com.pocketrealm.desktop.ui

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GAP-3 (owner census UIUX-3, the desktop timeframe-card lane): with two
 * qualified flavors the "World timeframe" card's lane resolves the ACTIVE
 * flavor through the flavor machine — the selection drives the stage list
 * instead of the former `singleOrNull() ?: VANILLA` silent default that
 * pinned the card to vanilla (and crashed Settings composition at the
 * requireNotNull when a stored stage belonged to the other lane). The
 * desktop harness is JVM-unit only (no compose UI-test dependency), so the
 * resolution is pinned through the pure [timeframeCardFlavor] seam the
 * composable calls; the composition wiring itself is review-pinned.
 */
class TimeframeCardFlavorTest {

    @After
    fun restoreRegistryDefaults() {
        ActiveFlavor.reset()
    }

    @Test
    fun timeframeCardFlavorResolvesTheActiveLaneWhenTwoFlavorsQualify() {
        TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)
        // The fixture's tbc lane is the real registry TBC with qualified
        // flipped — assert the LANE IDENTITY (what drives the stage list),
        // not data-class equality against the qualified=false registry object.
        assertEquals(
            "the card renders the SELECTED lane's stages, not the vanilla default",
            ClientFlavors.TBC.flavorId,
            timeframeCardFlavor().flavorId,
        )
        assertEquals(ClientFlavors.TBC.build, timeframeCardFlavor().build)

        ActiveFlavor.select(ClientFlavors.VANILLA.flavorId)
        assertEquals(
            "the selection — not registry order — drives the card",
            ClientFlavors.VANILLA,
            timeframeCardFlavor(),
        )
    }

    @Test
    fun timeframeCardFlavorResolvesTheSoleQualifiedLaneWithoutASelection() {
        // Today's registry state (vanilla only): the card keeps resolving
        // vanilla with no explicit selection — the byte-identical
        // single-qualified behavior the machine guarantees.
        assertEquals(ClientFlavors.VANILLA, timeframeCardFlavor())
    }
}
