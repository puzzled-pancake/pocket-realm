package com.pocketrealm.gateway

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * W6-N4 code-red R5-2: the port-bound E2Es (3724/8085) get a
 * cross-seat serialization guard — the cross-seat FILE LOCK is acquired
 * before each test and released after it, so two seats sharing the
 * module dir can never run their port-bound E2Es concurrently (the
 * probe-then-act race that survived the probe-only fix). A seat that
 * cannot acquire the lock inside the budget ABORTS the test as SKIPPED
 * (Assumptions.abort → TestAbortedException), never a phantom red; a
 * non-seat holder (a local realm stack) is caught by the post-lock
 * port probe. Apply with `@ExtendWith(GamePortsGuard::class)`.
 */
class GamePortsGuard : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        if (!acquireGamePortLock()) {
            Assumptions.abort<Unit>(
                "another seat holds the game-port lock (3724/8085) — " +
                    "this port-bound E2E skips instead of a phantom red " +
                    "(W6-N4 code-red R5-2)",
            )
        }
        // A non-seat holder (a local realm stack) or the other seat's
        // just-finished flows never release mid-window: probe FAITHFULLY
        // (the joiner's own no-REUSEADDR bind semantics) under the lock,
        // retrying inside [POST_LOCK_PROBE_GRACE_MS] — a port that stays
        // unbindable aborts this E2E as SKIPPED, never a phantom red
        // (W6-N4 code-red R5-2/R4-r2-3: the permissive probe lied).
        val deadline = System.currentTimeMillis() + POST_LOCK_PROBE_GRACE_MS
        while (!gamePortsFree()) {
            if (System.currentTimeMillis() >= deadline) {
                releaseGamePortLock()
                Assumptions.abort<Unit>(
                    "the game ports 3724/8085 are not bindable by the " +
                        "joiner's own semantics (a live holder or socket " +
                        "residue) — this port-bound E2E skips instead of " +
                        "a phantom red (W6-N4 code-red R5-2)",
                )
            }
            Thread.sleep(PORT_PROBE_CADENCE_MS)
        }
    }

    override fun afterEach(context: ExtensionContext) {
        releaseGamePortLock()
    }
}
