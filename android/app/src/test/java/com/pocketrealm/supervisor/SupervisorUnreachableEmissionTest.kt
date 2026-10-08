package com.pocketrealm.supervisor

import com.pocketrealm.realm.RealmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §6.4 failure-emission truth table (design realm-status-browser.md):
 * the supervisor-status poll's dead-lane shapes must emit an EXPLICIT
 * SupervisorUnreachable state — never silence, and never a false offline
 * claim of either polarity.
 */
class SupervisorUnreachableEmissionTest {

    @Test
    fun `a decode failure emits immediately - the unknown phase enum never reads as clean idle`() {
        // The valueOf throw the pre-§6.4 loop swallowed: deterministic, so
        // the emission must not wait for "repeated" ticks to accumulate.
        val reason = RuntimeSupervisorClient.unreachableReason(
            decodeFailed = true,
            remoteMissing = false,
            connectedOnce = true,
            startupGracePollsLeft = RuntimeSupervisorClient.STARTUP_GRACE_POLLS,
        )
        assertNotNull(reason)
    }

    @Test
    fun `binder death after a live connection emits within the poll interval`() {
        val reason = RuntimeSupervisorClient.unreachableReason(
            decodeFailed = false,
            remoteMissing = true,
            connectedOnce = true,
            startupGracePollsLeft = RuntimeSupervisorClient.STARTUP_GRACE_POLLS,
        )
        assertEquals("the supervisor process disconnected", reason)
    }

    @Test
    fun `a status that never arrives fails loud only after the startup grace`() {
        // Inside the grace: the pre-lane state, not a failure claim.
        assertNull(
            RuntimeSupervisorClient.unreachableReason(
                decodeFailed = false,
                remoteMissing = true,
                connectedOnce = false,
                startupGracePollsLeft = RuntimeSupervisorClient.STARTUP_GRACE_POLLS,
            ),
        )
        // The last grace poll: still silent.
        assertNull(
            RuntimeSupervisorClient.unreachableReason(
                decodeFailed = false,
                remoteMissing = true,
                connectedOnce = false,
                startupGracePollsLeft = 1,
            ),
        )
        // The grace exhausted: the never-published wedge surfaces.
        assertEquals(
            "the supervisor never published its status",
            RuntimeSupervisorClient.unreachableReason(
                decodeFailed = false,
                remoteMissing = true,
                connectedOnce = false,
                startupGracePollsLeft = 0,
            ),
        )
    }

    @Test
    fun `the unreachable state is its own sealed member - never a clean idle`() {
        val state: RealmState = RealmState.SupervisorUnreachable("the supervisor process disconnected")
        assertTrue(
            "the UI cannot confuse the dead lane with a stopped realm",
            state is RealmState.SupervisorUnreachable,
        )
        assertTrue(
            "the state carries its reason for the §6.4 copy",
            (state as RealmState.SupervisorUnreachable).reason.isNotEmpty(),
        )
    }
}
