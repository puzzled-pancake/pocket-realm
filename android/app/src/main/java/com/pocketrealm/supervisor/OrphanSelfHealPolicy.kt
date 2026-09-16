package com.pocketrealm.supervisor

/**
 * Pure decision object for the supervisor's orphan self-heal lane.
 *
 * The real orphan is a RUNNING component whose OBSERVED owner is null: the
 * binder-death path cleared the claim while the component-side teardown
 * (owner-loss thread, mid-save) is still in flight. A non-null owner that
 * does not match the journal is NOT an orphan of this kind - that case stays
 * with the pinned `UNVERIFIED_ORPHAN` refusal in
 * [DurableRuntimeSupervisor.recoverLocked] and must never be killed.
 *
 * The decision functions live on the companion so any participant (backend
 * ownership checks included) applies the identical rule; the class instance
 * carries the [RuntimeTokenSource] for the [adoptOwner] factory hook, which
 * pairs the current session with a freshly minted instance token so an
 * adopted owner can legally claim an ownerless component through the same
 * `ComponentOwnership.claim` path a start uses.
 */
class OrphanSelfHealPolicy(private val tokens: RuntimeTokenSource) {

    /** Adopts an ownerless component under the current session with a fresh token. */
    fun adoptOwner(sessionId: String): ComponentOwner =
        ComponentOwner(sessionId, tokens.instanceToken())

    companion object {
        /** Bounded re-observe grace: monitor ticks allowed before acting on an orphan. */
        const val REOBSERVE_GRACE_TICKS = 3

        /**
         * Whether to keep re-observing before acting on an orphan. The grace
         * window is [REOBSERVE_GRACE_TICKS] monitor ticks long: the first
         * `REOBSERVE_GRACE_TICKS - 1` consecutive orphan sightings wait, and
         * the sighting at [REOBSERVE_GRACE_TICKS] exhausts the grace and acts.
         */
        fun shouldReobserve(graceTicksSeen: Int): Boolean =
            graceTicksSeen < REOBSERVE_GRACE_TICKS

        /**
         * A component is a healable orphan ONLY while it is not STOPPED and
         * its observed owner is null. A STOPPED ownerless component already
         * finished its teardown; a component with any non-null owner
         * (matching or not) is never adoptable.
         */
        fun isHealableOrphan(observedState: ComponentLifecycle, observedOwner: ComponentOwner?): Boolean =
            observedState != ComponentLifecycle.STOPPED && observedOwner == null
    }
}
