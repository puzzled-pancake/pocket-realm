package com.pocketrealm.client

/**
 * The multi-flavor active-version state machine — the lift of the former
 * single-supported fail-loud sites (import storage budgets, import size
 * limits, bot population policy, prepared-server-data resolution) to
 * per-flavor state.
 *
 * The machine holds a SUPPORTED-FLAVOR LIST (resolved live from
 * [ClientFlavors.qualified]) plus an ACTIVE flavor selection. Resolution is
 * fail-loud exactly where the single-supported sites were, and never
 * silently guesses for genuinely ambiguous states:
 *
 * - no selection + exactly one supported flavor -> that flavor (today's
 *   registry state; identical to the old `qualified().single()`);
 * - no selection + several supported flavors -> loud ("expected exactly one
 *   qualified flavor, found N — <lane>"), never a silent VANILLA default;
 * - a selection that is not supported -> loud (a stale or declared-but-
 *   disabled pick never silently downgrades to another flavor);
 * - zero supported flavors -> loud.
 *
 * A flavor with [ClientFlavor.qualified] == false is declared but disabled;
 * the Home flavor switcher renders it as a present-but-not-selectable chip,
 * and [select] refuses it — supporting a flavor is a data change in
 * [ClientFlavors], never a UI flag.
 */
data class ActiveFlavorState(val selectedFlavorId: String? = null) {

    /**
     * Select [flavorId] as the active lane. Refuses anything the given
     * list does not contain OR that carries `qualified == false` (the
     * [ActiveFlavor] holder passes the live registry's qualified view;
     * the state machine itself is pure and stays honest if a caller hands
     * it the declared list instead).
     */
    fun select(flavorId: String, qualified: List<ClientFlavor>): ActiveFlavorState {
        val lane = qualified.firstOrNull { it.flavorId == flavorId }
        require(lane != null && lane.qualified) {
            "flavor \"$flavorId\" is not qualified — " +
                (qualified.takeIf { it.isNotEmpty() }
                    ?.joinToString("/") { it.flavorId }
                    ?.let { "qualified lanes: $it" } ?: "no flavor is qualified")
        }
        return ActiveFlavorState(flavorId)
    }

    /** Clears the selection (back to the sole-qualified default resolution). */
    fun clear(): ActiveFlavorState = ActiveFlavorState(null)

    /**
     * The fail-loud resolver the lifted gates call. [lane] is the failure
     * suffix naming the calling gate (e.g. "storage budgets resolve the
     * qualified lane"), preserving each site's original message.
     */
    fun resolve(qualified: List<ClientFlavor>, lane: String = DEFAULT_LANE): ClientFlavor = when {
        qualified.isEmpty() ->
            error("no qualified flavor is registered — $lane")
        selectedFlavorId != null -> {
            val active = qualified.firstOrNull { it.flavorId == selectedFlavorId }
            checkNotNull(active) {
                "active flavor \"$selectedFlavorId\" is not qualified — $lane"
            }
            active
        }
        qualified.size == 1 -> qualified.single()
        else ->
            error("expected exactly one qualified flavor, found ${qualified.size} — $lane")
    }

    companion object {
        /** Failure suffix for callers that do not name a lane. */
        const val DEFAULT_LANE = "the active flavor resolves the qualified lane"
    }
}

/**
 * Process-wide holder over [ActiveFlavorState]: the Home flavor switcher
 * writes [select], the lifted gates read [resolve]. The qualified view is
 * re-derived from [ClientFlavors.qualified] on every access, so a registry
 * change is honored without a restart.
 */
object ActiveFlavor {

    /**
     * The qualified-flavor source. Production reads the live registry; the
     * test lane swaps it to prove multi-qualified behavior against synthetic
     * flavors without mutating [ClientFlavors].
     */
    @Volatile
    internal var qualifiedSource: () -> List<ClientFlavor> = { ClientFlavors.qualified() }

    @Volatile
    private var state: ActiveFlavorState = ActiveFlavorState()

    /** The qualified-flavor LIST the machine resolves against. */
    val qualifiedFlavors: List<ClientFlavor> get() = qualifiedSource()

    /** The explicit selection, or null when resolution falls to the default. */
    val selectedId: String? get() = state.selectedFlavorId

    /**
     * The flavor id the switcher should highlight: the explicit selection,
     * else the sole qualified flavor's id, else null (ambiguous — the
     * machine would fail loud, so no chip claims the lane).
     */
    val displayedId: String?
        get() = state.selectedFlavorId ?: qualifiedFlavors.singleOrNull()?.flavorId

    /** Switches the active lane; returns the resolved [ClientFlavor]. */
    fun select(flavorId: String): ClientFlavor {
        val next = state.select(flavorId, qualifiedFlavors)
        state = next
        return qualifiedFlavors.first { it.flavorId == flavorId }
    }

    /** Clears the explicit selection (the switcher's "no pick" state). */
    fun clearSelection() {
        state = state.clear()
    }

    /** The fail-loud active-flavor resolver the lifted gates call. */
    fun resolve(lane: String = ActiveFlavorState.DEFAULT_LANE): ClientFlavor =
        state.resolve(qualifiedFlavors, lane)

    /**
     * One chip per declared flavor (vanilla / TBC / WotLK): selectable
     * exactly when the machine's qualified list contains it, highlighted
     * per [displayedId]. The switcher UI renders this verbatim; the pure
     * mapping is pinned by tests.
     */
    fun switcherChips(): List<FlavorChip> {
        val qualifiedIds = qualifiedFlavors.map { it.flavorId }.toSet()
        val displayed = displayedId
        return ClientFlavors.ALL.map { flavor ->
            FlavorChip(
                game = flavor,
                selected = displayed == flavor.flavorId,
                selectable = flavor.flavorId in qualifiedIds,
            )
        }
    }

    /** Test/cold-start reset: drops the selection and restores the registry source. */
    fun reset() {
        state = ActiveFlavorState()
        qualifiedSource = { ClientFlavors.qualified() }
    }
}

/** One Home flavor-switcher chip (pure; pinned by ActiveFlavorTest). */
data class FlavorChip(
    val game: ClientFlavor,
    val selected: Boolean,
    val selectable: Boolean,
) {
    /** Switcher label — the short expansion name players know. */
    val label: String
        get() = when (game.flavorId) {
            ClientFlavors.VANILLA.flavorId -> "Vanilla"
            ClientFlavors.TBC.flavorId -> "TBC"
            ClientFlavors.WOTLK.flavorId -> "WotLK"
            else -> game.expansion
        }
}
