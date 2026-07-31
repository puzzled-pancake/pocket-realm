package com.pocketrealm.realm

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * State-machine assertions for [RealmSupervisor]. These pin the legal-transition
 * table that the UI, notification, and (future) native lifecycle all rely on.
 *
 * They directly cover the O02 supervisor contract and the M2 fix (that Saving
 * reaches Stopping rather than short-circuiting to Idle). No Android framework
 * state is touched: this is a pure model test.
 */
@RunWith(AndroidJUnit4::class)
class RealmSupervisorTest {

    private fun fullHealth() = RealmHealth(HealthCondition.values().associateWith { true })

    @Test
    fun starts_idle() {
        val s = RealmSupervisor()
        assertTrue("fresh supervisor is Idle", s.state.value is RealmState.Idle)
    }

    @Test
    fun requestStart_allowed_from_idle() {
        val s = RealmSupervisor()
        assertTrue(s.requestStart())
        val st = s.state.value
        assertTrue("Idle -> Starting", st is RealmState.Starting)
        assertEquals(1, (st as RealmState.Starting).attempt)
    }

    @Test
    fun requestStart_rejected_when_running() {
        val s = RealmSupervisor()
        s.requestStart()
        s.markRunning(fullHealth())
        assertFalse("cannot re-start from Running", s.requestStart())
        assertTrue("still Running after rejected start", s.state.value is RealmState.Running)
    }

    @Test
    fun requestStart_allowed_from_failed_retry() {
        val s = RealmSupervisor()
        s.markFailed("boom")
        assertTrue("retry from Failed", s.requestStart())
        assertTrue(s.state.value is RealmState.Starting)
    }

    @Test
    fun markRunning_requires_allReady() {
        val s = RealmSupervisor()
        s.requestStart()
        // markRunning with a not-allReady health MUST throw — the android rule
        // forbids reporting playing before health holds. allReady requires every
        // condition true AND the map non-empty, so a map with a false value
        // triggers the guard. (A single-true map would be allReady, so use a
        // false entry to actually exercise the rejection.)
        val notReady = RealmHealth(mapOf(HealthCondition.DATABASE_OPEN to true,
                                         HealthCondition.AUTH_READY to false))
        assertThrows("markRunning must reject incomplete health",
            IllegalStateException::class.java) {
            s.markRunning(notReady)
        }
    }

    @Test
    fun markRunning_ignored_when_not_starting() {
        val s = RealmSupervisor()
        // Calling markRunning from Idle must NOT silently enter Running.
        s.markRunning(fullHealth())
        assertTrue("markRunning ignored from Idle", s.state.value is RealmState.Idle)
    }

    @Test
    fun requestStop_rejected_when_idle() {
        val s = RealmSupervisor()
        assertFalse("no-op stop from Idle", s.requestStop(forced = false))
        assertTrue(s.state.value is RealmState.Idle)
    }

    @Test
    fun full_round_trip_idle_starting_running_saving_stopping_idle() {
        // This is the core lifecycle the service drives, and it specifically
        // proves the M2 fix path: Saving -> Stopping -> Idle is a legal route
        // (previously saveExit() called markIdle() before stop, so requestStop
        // rejected as already-Idle and Stopping was never observed).
        val s = RealmSupervisor()
        assertTrue(s.requestStart())
        assertTrue(s.state.value is RealmState.Starting)
        s.markRunning(fullHealth())
        assertTrue(s.state.value is RealmState.Running)

        assertTrue(s.requestSave(SaveReason.USER_SAVE_EXIT))
        assertTrue("Saving reached", s.state.value is RealmState.Saving)

        assertTrue(s.requestStop(forced = false))
        assertTrue("Saving -> Stopping", s.state.value is RealmState.Stopping)

        s.markIdle()
        assertTrue("Stopping -> Idle", s.state.value is RealmState.Idle)
    }

    @Test
    fun isLive_only_when_running_or_saving() {
        val s = RealmSupervisor()
        assertFalse(s.isLive)
        s.requestStart()
        assertFalse("Starting is not yet live/playable", s.isLive)
        s.markRunning(fullHealth())
        assertTrue(s.isLive)
        s.requestSave(SaveReason.LOW_STORAGE)
        assertTrue("Saving is still live", s.isLive)
        s.requestStop(forced = true)
        assertFalse("Stopping is not live", s.isLive)
    }

    @Test
    fun can_start_twice_in_one_process() {
        // O04 acceptance precursor: the lifecycle must repeat without a process
        // restart. The supervisor must return cleanly to Idle and accept a new
        // start.
        val s = RealmSupervisor()
        for (i in 1..2) {
            assertTrue("start #$i", s.requestStart())
            s.markRunning(fullHealth())
            assertTrue(s.state.value is RealmState.Running)
            assertTrue(s.requestSave(SaveReason.USER_SAVE_EXIT))
            assertTrue(s.requestStop(forced = false))
            s.markIdle()
            assertTrue("back to Idle after round $i", s.state.value is RealmState.Idle)
        }
    }
}
