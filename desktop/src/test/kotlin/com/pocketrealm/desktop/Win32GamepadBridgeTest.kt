package com.pocketrealm.desktop

import com.pocketrealm.client.InputProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESK-4 start-claim pin: [Win32GamepadBridge.start] is a check-then-act
 * (is a poller live? then spawn one) with TWO live callers that race — the
 * post-launch auto-start ([DesktopAppModel.startGamepadBridgeIfUseful] on an
 * IO dispatcher) and the Controls screen's toggle (ControlsScreen.kt, UI
 * thread). Unsynchronized, two interleaved checks both see "not running"
 * and each spawns a poller — two threads double-injecting every mapped key.
 *
 * The fix claims the start atomically under `lifecycleLock` (shared with
 * [Win32GamepadBridge.stop]); these tests pin the invariant from outside:
 * however many threads call start() concurrently, at most ONE
 * "pocketrealm-gamepad" poller ever exists, a second start() is idempotent,
 * and stop() releases the claim so a later start() gets exactly one new
 * poller (the toggle's off→on path).
 *
 * The pollers here never touch a controller or inject input: the pid probe
 * answers null, so the loop takes the idle path (release-nothing, sleep)
 * and stays off the foreground/injection code for its whole short life.
 */
class Win32GamepadBridgeTest {

    private var bridge: Win32GamepadBridge? = null

    @After
    fun stopThePoller() {
        bridge?.stop()
        bridge = null
    }

    /** Live poller threads of the bridge (the spawn is the race's victim:
     * one claim = one thread named [Win32GamepadBridge]'s poller name). */
    private fun livePollers(): Int =
        Thread.getAllStackTraces().keys.count { it.name == POLLER_THREAD_NAME }

    private fun awaitPollerCount(expected: Int): Boolean {
        val deadline = System.currentTimeMillis() + POLLER_SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (livePollers() == expected) return true
            Thread.sleep(POLLER_SETTLE_POLL_MS)
        }
        return livePollers() == expected
    }

    private fun newBridge(): Win32GamepadBridge =
        Win32GamepadBridge(
            profile = { InputProfile.DEFAULT },
            clientPid = { null },
            pollMillis = TEST_POLL_MILLIS,
        ).also { bridge = it }

    @Test
    fun concurrentStartsClaimExactlyOnePoller() {
        val candidate = newBridge()
        if (!candidate.gamepadSupported()) {
            // The inert machine branch is its own contract: no XInput →
            // start() claims nothing and spawns nothing.
            repeat(RACING_STARTERS) { candidate.start() }
            assertFalse("an inert bridge never reports injecting", candidate.injecting)
            assertTrue(awaitPollerCount(0))
            return
        }
        // Fire every starter through one gate so the check-then-act windows
        // overlap as tightly as the JVM allows.
        val gate = CountDownLatch(1)
        val done = CountDownLatch(RACING_STARTERS)
        repeat(RACING_STARTERS) {
            Thread {
                gate.await()
                candidate.start()
                done.countDown()
            }.start()
        }
        gate.countDown()
        assertTrue("every racing starter returned", done.await(STARTER_TIMEOUT_S, TimeUnit.SECONDS))
        assertTrue(awaitPollerCount(1))
        assertEquals("one claim, one poller — never two", 1, livePollers())
        assertTrue(candidate.injecting)
        // A straggler start() after the race is idempotent: still one poller.
        candidate.start()
        assertTrue(awaitPollerCount(1))
        assertEquals(1, livePollers())
    }

    @Test
    fun stopReleasesTheClaimAndARestartSpawnsOneNewPoller() {
        val candidate = newBridge()
        if (!candidate.gamepadSupported()) {
            candidate.start()
            assertFalse(candidate.injecting)
            candidate.stop()
            assertFalse(candidate.injecting)
            assertTrue(awaitPollerCount(0))
            return
        }
        candidate.start()
        assertTrue(awaitPollerCount(1))
        // The Controls toggle's off leg: stop() must end the poller AND the
        // claim, so the next on leg gets a fresh poller — not zero, not two.
        candidate.stop()
        assertTrue("the stopped poller exits", awaitPollerCount(0))
        assertFalse(candidate.injecting)
        candidate.start()
        assertTrue(awaitPollerCount(1))
        assertEquals("the restart owns exactly one poller again", 1, livePollers())
        assertTrue(candidate.injecting)
    }

    private companion object {
        /** The poller thread's name (Win32GamepadBridge's spawn literal). */
        const val POLLER_THREAD_NAME = "pocketrealm-gamepad"

        /** Test poll cadence: real enough to exercise the loop's idle path,
         * slow enough that the idle backoff (x4) stays gentle. */
        const val TEST_POLL_MILLIS = 16L

        /** Starters racing the claim (the auto-start x toggle collision,
         * with margin). */
        const val RACING_STARTERS = 8

        /** Bounds for the race latch and the poller settle waits. */
        const val STARTER_TIMEOUT_S = 10L
        const val POLLER_SETTLE_TIMEOUT_MS = 10_000L
        const val POLLER_SETTLE_POLL_MS = 10L
    }
}
