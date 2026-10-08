package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.supervisor.RuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visibility state machine's pins (design realm-status-browser.md §4
 * + the gates §3.3/§5.3/§6.6 feed it): the linear OFF→FRIENDS→PUBLIC
 * shape, the fail-closed refusals that keep the PRIOR state, the never-
 * gated OFF teardown, and the §4 boot semantics (FRIENDS restores,
 * PUBLIC re-arms without resuming).
 */
class RealmVisibilityMachineTest {

    @Suppress("LongParameterList") // the test helper mirrors the machine's own gate list
    private fun transition(
        current: RealmStatusVisibility,
        target: RealmStatusVisibility,
        hostingMode: RuntimeMode = RuntimeMode.LAN_HOST,
        bindAllowed: Boolean = true,
        crossCheckOk: Boolean = true,
        orphanGateClear: Boolean = true,
    ): RealmTransitionVerdict = RealmVisibilityMachine.transition(
        current = current,
        target = target,
        hostingMode = hostingMode,
        bindAllowed = bindAllowed,
        crossCheckOk = crossCheckOk,
        orphanGateClear = orphanGateClear,
    )

    @Test
    fun `off enables friends on a hosting session through the clean gates`() {
        val verdict = transition(RealmStatusVisibility.OFF, RealmStatusVisibility.FRIENDS)
        assertTrue(verdict is RealmTransitionVerdict.Allowed)
        verdict as RealmTransitionVerdict.Allowed
        assertEquals(RealmStatusVisibility.FRIENDS, verdict.next)
        assertTrue("a fresh bind is a rebind", verdict.rebind)
    }

    @Test
    fun `friends enables public with a rebind`() {
        val verdict = transition(RealmStatusVisibility.FRIENDS, RealmStatusVisibility.PUBLIC)
        assertTrue(verdict is RealmTransitionVerdict.Allowed)
        verdict as RealmTransitionVerdict.Allowed
        assertEquals(RealmStatusVisibility.PUBLIC, verdict.next)
        assertTrue("wildcard → loopback is a rebind", verdict.rebind)
    }

    @Test
    fun `local solo sessions are not offered friends`() {
        val verdict = transition(
            RealmStatusVisibility.OFF,
            RealmStatusVisibility.FRIENDS,
            hostingMode = RuntimeMode.LOCAL,
        )
        assertTrue(verdict is RealmTransitionVerdict.Refused)
        assertEquals(RefusalReason.LOCAL_NOT_OFFERED, (verdict as RealmTransitionVerdict.Refused).reason)
    }

    @Test
    fun `a taken 18080 refuses with the honest port copy and no pid or path`() {
        val verdict = transition(
            RealmStatusVisibility.OFF,
            RealmStatusVisibility.FRIENDS,
            bindAllowed = false,
        )
        assertTrue(verdict is RealmTransitionVerdict.Refused)
        val refused = verdict as RealmTransitionVerdict.Refused
        assertEquals(RefusalReason.BIND_CONFLICT, refused.reason)
        assertTrue("the copy names the port", refused.copy.contains("18080"))
        assertFalse("no pid in the copy (§3.2)", refused.copy.contains("pid"))
        assertFalse("no filesystem path in the copy", refused.copy.contains(":\\"))
    }

    @Test
    fun `public enable is refused while the cross-check fails`() {
        // §3.3 / T3d: the PUBLIC flip is the gate that refuses; FRIENDS
        // does not need the cross-check.
        val publicVerdict = transition(
            RealmStatusVisibility.FRIENDS,
            RealmStatusVisibility.PUBLIC,
            crossCheckOk = false,
        )
        assertTrue(publicVerdict is RealmTransitionVerdict.Refused)
        val refused = publicVerdict as RealmTransitionVerdict.Refused
        assertEquals(
            "T3d's machine-readable reason rides the refusal",
            RefusalReason.CROSS_CHECK_FAILING,
            refused.reason,
        )
        val friendsVerdict = transition(
            RealmStatusVisibility.OFF,
            RealmStatusVisibility.FRIENDS,
            crossCheckOk = false,
        )
        assertTrue("FRIENDS does not need the cross-check", friendsVerdict is RealmTransitionVerdict.Allowed)
    }

    @Test
    fun `a lost-record orphan holds every transition that binds 18080 - friends included`() {
        // §5.3: the resurrect scenario runs through FRIENDS — the gate
        // covers it, not just PUBLIC spawn.
        val friendsVerdict = transition(
            RealmStatusVisibility.OFF,
            RealmStatusVisibility.FRIENDS,
            orphanGateClear = false,
        )
        assertTrue(friendsVerdict is RealmTransitionVerdict.Refused)
        assertEquals(
            RefusalReason.ORPHAN_PENDING,
            (friendsVerdict as RealmTransitionVerdict.Refused).reason,
        )
        val publicVerdict = transition(
            RealmStatusVisibility.FRIENDS,
            RealmStatusVisibility.PUBLIC,
            orphanGateClear = false,
        )
        assertEquals(
            RefusalReason.ORPHAN_PENDING,
            (publicVerdict as RealmTransitionVerdict.Refused).reason,
        )
    }

    @Test
    fun `turning off is never gated - even with every gate closed`() {
        val verdict = transition(
            RealmStatusVisibility.PUBLIC,
            RealmStatusVisibility.OFF,
            bindAllowed = false,
            crossCheckOk = false,
            orphanGateClear = false,
        )
        assertTrue("teardown must always be reachable", verdict is RealmTransitionVerdict.Allowed)
        verdict as RealmTransitionVerdict.Allowed
        assertEquals(RealmStatusVisibility.OFF, verdict.next)
        assertFalse("OFF never rebinds a server that is being torn down", verdict.rebind)
    }

    @Test
    fun `the same-state switch is a no-op that never bounces a healthy listener`() {
        val verdict = transition(RealmStatusVisibility.FRIENDS, RealmStatusVisibility.FRIENDS)
        assertTrue(verdict is RealmTransitionVerdict.Allowed)
        verdict as RealmTransitionVerdict.Allowed
        assertEquals(RealmStatusVisibility.FRIENDS, verdict.next)
        assertFalse(verdict.rebind)
    }

    @Test
    fun `boot restores a persisted friends session`() {
        val outcome = RealmVisibilityMachine.bootOutcome(RealmStatusVisibility.FRIENDS)
        assertEquals(RealmStatusVisibility.FRIENDS, outcome.restored)
        assertFalse(outcome.publicArmed)
    }

    @Test
    fun `boot re-arms a persisted public session without auto-resuming`() {
        // §4: the app never auto-starts cloudflared at launch — the
        // restored state is OFF and the card shows the armed notice.
        val outcome = RealmVisibilityMachine.bootOutcome(RealmStatusVisibility.PUBLIC)
        assertEquals(RealmStatusVisibility.OFF, outcome.restored)
        assertTrue(outcome.publicArmed)
    }

    @Test
    fun `boot keeps the fail-closed off default`() {
        val outcome = RealmVisibilityMachine.bootOutcome(RealmStatusVisibility.OFF)
        assertEquals(RealmStatusVisibility.OFF, outcome.restored)
        assertFalse(outcome.publicArmed)
    }
}
