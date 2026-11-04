package com.pocketrealm.client

/**
 * P6: the two-qualified-flavors test fixture — the desktop
 * twin's own idiom (desktop/src/test/kotlin/com/pocketrealm/client/
 * TwoQualifiedFlavors.kt), modeled in Kotlin through
 * [ActiveFlavor.qualifiedSource] (the same seam [ActiveFlavorTest] drives),
 * NEVER an edit to `schemas/flavor.json`: qualification is Kotlin registry
 * state ([ClientFlavors.qualified]), and the repo's committed flag state
 * stays vanilla-only (tools/check_flavor.py's pin).
 *
 * The second lane is the REAL TBC identity with `qualified = true`, so the
 * lanes the multi-qualified gates resolve carry production values
 * (clientId "wow-2.4.3-8606", build 8606, the tbc pointer namespace,
 * realmdAcceptedBuilds "8606"). Only sites routed through the
 * [ActiveFlavor] machine observe the flip: `ClientFlavors.qualified()`
 * itself (the production registry read) still returns vanilla-only,
 * keeping the fixture surgical.
 */
object TwoQualifiedFlavors {

    /** Vanilla (the unchanged registry object) + TBC with the flag flipped. */
    val lanes: List<ClientFlavor> =
        listOf(ClientFlavors.VANILLA, ClientFlavors.TBC.copy(qualified = true))

    val VANILLA: ClientFlavor get() = lanes[0]
    val TBC: ClientFlavor get() = lanes[1]

    /**
     * Installs the two-qualified registry through the seam and (optionally)
     * selects the active lane, mirroring the Home switcher's write. Callers
     * restore the real registry with [ActiveFlavor.reset] in `@After`/finally.
     */
    fun install(selection: String? = null) {
        ActiveFlavor.reset()
        ActiveFlavor.qualifiedSource = { lanes }
        if (selection != null) {
            ActiveFlavor.select(selection)
        }
    }
}
