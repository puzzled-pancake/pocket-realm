package com.pocketrealm.desktop.iroh

import com.pocketrealm.supervisor.RuntimePhase

/**
 * W6-N3: the host+join refusal ladder's decision table — the ONE place
 * the "refuse simultaneous host+join on one instance" contract lives
 * (the ask's clash invariant; 3724/8085 are single-owner on an instance).
 * The model verbs call these; [IrohJoinGuardsTest] pins every row.
 */
internal object IrohJoinGuards {

    /** The refusal code the app copy and the journal carry. */
    const val VAL_IROH_JOIN_CLASH = "VAL-IROH-JOIN-CLASH"

    /** The phases a realm start is accepted in (the supervisor's
     * STARTABLE mirror — a realm stack OWNS 3724/8085 in every other
     * phase). */
    val STARTABLE_PHASES: Set<RuntimePhase> = setOf(
        RuntimePhase.STOPPED,
        RuntimePhase.UNCONFIGURED,
        RuntimePhase.ERROR,
    )

    /**
     * Whether an iroh join may start now. Returns null (allowed) or the
     * actionable refusal copy, in review order: a running realm stack owns
     * the loopback game ports; a join already in flight owns the join
     * lane; the local client IS the active flavor (a listing for another
     * flavor would fail auth mid-join — the row names the switch); and a
     * device with NO flavor picked refuses first with the pick-first row
     * (round-4 R1-r4-2's pinned posture — the dial must never precede the
     * flavor decision).
     */
    fun joinRefusal(
        phase: RuntimePhase,
        joinState: IrohGatewayManager.JoinState,
        activeFlavorId: String? = null,
        listingFlavorId: String? = null,
    ): String? = when {
        phase !in STARTABLE_PHASES ->
            "$VAL_IROH_JOIN_CLASH: this instance is running a realm on ports " +
                "${portRealm()}/${portWorld()} — stop it before joining an " +
                "internet realm"

        joinState is IrohGatewayManager.JoinState.Starting ||
            joinState is IrohGatewayManager.JoinState.Ready ->
            "$VAL_IROH_JOIN_CLASH: an internet join is already running " +
                "on this instance — stop it first"

        activeFlavorId != null && listingFlavorId != null && activeFlavorId != listingFlavorId ->
            "VAL-IROH-JOIN-FLAVOR: the listing serves the '$listingFlavorId' " +
                "realm but this device's active client is '$activeFlavorId' — " +
                "switch flavors before joining"

        // Code-red round-4 R1-r4-2, the PINNED null-active posture (it was
        // an accidental skip): displayedId is null exactly when no Home
        // flavor is picked and more than one flavor is qualified — the
        // ambiguous state the ActiveFlavor machine refuses loud. A join
        // would dial, bind 3724/8085, and only then die on "no client is
        // available" — the guard refuses FIRST with the pick-first row.
        activeFlavorId == null && listingFlavorId != null ->
            "VAL-IROH-JOIN-FLAVOR: this device has no realm flavor picked " +
                "yet — pick this install's realm flavor (Home) first, then " +
                "join the '$listingFlavorId' realm"

        else -> null
    }

    /** Whether a realm start may proceed now (the symmetric half): a live
     * internet join owns the loopback game ports a boot would bind. */
    fun realmStartRefusal(joinState: IrohGatewayManager.JoinState): String? {
        val active = joinState is IrohGatewayManager.JoinState.Starting ||
            joinState is IrohGatewayManager.JoinState.Ready
        if (active) {
            return "$VAL_IROH_JOIN_CLASH: an internet join is running on ports " +
                "${portRealm()}/${portWorld()} — stop it before hosting"
        }
        return null
    }

    // The game ports (the shared contract's values restated: this desktop
    // package reads the shared core already — delegate directly).
    private fun portRealm(): Int = com.pocketrealm.discovery.iroh.GatewayContract.PORT_REALM
    private fun portWorld(): Int = com.pocketrealm.discovery.iroh.GatewayContract.PORT_WORLD
}
