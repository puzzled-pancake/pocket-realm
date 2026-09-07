package com.pocketrealm.ui

import com.pocketrealm.realm.RealmState
import com.pocketrealm.supervisor.RuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNVERIFIED_ORPHAN repair contract (the pinned refusal stays automatic-lanes
 * only): exactly that failure exposes the consented "Force stop realm"
 * affordance instead of the generic error surface, and one confirmation
 * dispatches the sanctioned stop verb exactly once.
 */
class HomeOrphanForceStopTest {

    @Test
    fun onlyTheUnverifiedOrphanFailureExposesTheForceStopAffordance() {
        val orphan = RealmState.Failed(
            "A previous realm start left a world running that this device cannot verify as its own.",
            unverifiedOrphan = true,
        )
        assertNotNull(unverifiedOrphanFailure(orphan))

        // The flag is the only trigger: similar copy on a generic failure,
        // and every non-failure state, keep the generic surface.
        assertNull(unverifiedOrphanFailure(RealmState.Failed("cannot verify as its own")))
        assertNull(unverifiedOrphanFailure(RealmState.Failed("runtime failed")))
        assertNull(unverifiedOrphanFailure(RealmState.Idle))
        assertNull(unverifiedOrphanFailure(RealmState.Starting(1)))
        assertNull(unverifiedOrphanFailure(
            RealmState.Running(System.currentTimeMillis(), RuntimeMode.LOCAL),
        ))
    }

    @Test
    fun confirmingOnceDispatchesTheStopVerbExactlyOnce() {
        var dispatches = 0
        val consent = OrphanForceStopConsent { dispatches++ }

        // The player confirmation dispatches the stop verb...
        assertTrue(consent.confirm())
        assertEquals(1, dispatches)

        // ...and a double-tap on the confirm button never dispatches it a
        // second time.
        assertFalse(consent.confirm())
        assertEquals(1, dispatches)

        // Leaving the orphan failure (Home resets on any non-orphan state)
        // re-arms the gate for a later episode.
        consent.reset()
        assertTrue(consent.confirm())
        assertEquals(2, dispatches)
    }
}
