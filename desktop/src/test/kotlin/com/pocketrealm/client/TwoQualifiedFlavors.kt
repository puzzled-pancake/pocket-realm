package com.pocketrealm.client

/**
 * GAP-3 (owner census UIUX-3): the two-qualified-flavors test fixture — the
 * S5 flip's own lever modeled in Kotlin through [ActiveFlavor.qualifiedSource]
 * (the same seam `ActiveFlavorTest` drives), NEVER an edit to
 * `schemas/flavor.json`: qualification is Kotlin registry state
 * ([ClientFlavors.qualified], ClientFlavor.kt), and the repo's committed
 * flag state stays vanilla-only (tools/check_flavor.py's pin — the GAP-3
 * gate proves it unchanged).
 *
 * The second lane is the REAL TBC identity with `qualified = true`, so
 * pointer namespaces (`active-tbc.json`), clientIds and builds exercise
 * production values. Only sites routed through the [ActiveFlavor] machine
 * observe the flip: `ClientFlavors.qualified()` itself (the production
 * registry read) still returns vanilla-only, keeping the fixture surgical.
 */
object TwoQualifiedFlavors {

    /** Vanilla (the unchanged registry object) + TBC with the S5 flag flipped. */
    val lanes: List<ClientFlavor> =
        listOf(ClientFlavors.VANILLA, ClientFlavors.TBC.copy(qualified = true))

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
