package com.pocketrealm.desktop

import com.pocketrealm.supervisor.RuntimePhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the ONE save-exit drain behind both session-end entry
 * points — the window's onCloseRequest (requestAsync) and the JVM shutdown
 * hook that covers Windows logoff/shutdown/restart/taskkill
 * (runBlockingOnShutdown). The once-flag must hold in BOTH orders, and the
 * drain must work on a REAL app model (a booted supervisor + journal, no
 * window) — the model-level shape of the saveExitGate proof.
 */
class SaveExitDrainTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun windowCloseThenShutdownHookDrainsExactlyOnce() {
        var drains = 0
        var closes = 0
        val drain = SaveExitDrain(drain = { drains++ }, close = { closes++ })
        val settled = CountDownLatch(1)
        assertTrue("the first window-close request must win the once-flag", drain.requestAsync { settled.countDown() })
        assertTrue("the async drain must settle", settled.await(10, TimeUnit.SECONDS))
        // The shutdown hook firing after a window-close drain is a no-op.
        drain.runBlockingOnShutdown()
        assertEquals("one drain, one exit", 1, drains)
        assertEquals("the model closes exactly once", 1, closes)
        assertTrue(drain.requested())
    }

    /** A logoff/shutdown DURING a window-close drain must not tear the JVM
     * down under the save: the hook blocks on the in-flight drain instead
     * of bailing on the once-flag. */
    @Test
    fun shutdownHookWaitsForAnInFlightWindowCloseDrain() {
        var drains = 0
        var closes = 0
        val drainStarted = CountDownLatch(1)
        val releaseDrain = CountDownLatch(1)
        val drain = SaveExitDrain(
            drain = {
                drains++
                drainStarted.countDown()
                releaseDrain.await(10, TimeUnit.SECONDS)
            },
            close = { closes++ },
        )
        assertTrue(drain.requestAsync(onSettled = {}))
        assertTrue("precondition: the drain must be in flight", drainStarted.await(10, TimeUnit.SECONDS))
        // The hook fires (logoff) while the drain is mid-save.
        val hook = Thread({ drain.runBlockingOnShutdown() }, "test-save-exit-hook")
        hook.start()
        Thread.sleep(250)
        assertTrue(
            "the hook must still be waiting on the in-flight drain",
            hook.isAlive,
        )
        assertEquals("the hook must not have closed the model early", 0, closes)
        releaseDrain.countDown()
        hook.join(10_000)
        assertFalse("the hook must return once the drain settles", hook.isAlive)
        assertEquals("one drain, one exit", 1, drains)
        assertEquals(1, closes)
    }

    @Test
    fun shutdownHookFirstThenWindowCloseIsAbsorbed() {
        var drains = 0
        var closes = 0
        val drain = SaveExitDrain(drain = { drains++ }, close = { closes++ })
        // OS shutdown without a window close: the hook drains synchronously.
        drain.runBlockingOnShutdown()
        assertEquals(1, drains)
        assertEquals(1, closes)
        // A late window close is absorbed (and settles nothing twice).
        val settled = CountDownLatch(1)
        assertFalse("the once-flag must hold in both orders", drain.requestAsync { settled.countDown() })
        assertEquals(1, settled.count)
        assertEquals(1, drains)
    }

    @Test
    fun drainAndCloseFailuresNeverEscapeTheDrain() {
        val drain = SaveExitDrain(
            drain = { error("save failed") },
            close = { error("close failed") },
        )
        val settled = CountDownLatch(1)
        drain.requestAsync { settled.countDown() }
        assertTrue(settled.await(10, TimeUnit.SECONDS))
        assertTrue("a failed drain must still mark itself done", drain.requested())
    }

    /** DESK-111's model-level shape: the hook's synchronous drain runs a
     * REAL app model (supervisor + journal + the single-instance lock) to
     * a clean at-rest stack WITHOUT any window — and the drain's close
     * releases the lock, so a successor instance can start. */
    @Test
    fun shutdownHookDrainsARealModelStackWithoutAWindow() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        val model = DesktopAppModel(roots)
        val drain = SaveExitDrain(model)
        drain.runBlockingOnShutdown()
        val journal = model.state.value
        assertEquals(
            "the drained stack must be at rest",
            RuntimePhase.STOPPED,
            journal.phase,
        )
        assertTrue("the drained stack must be clean: ${journal.lastError}", journal.clean)
        // The lock released with the drain's close (a crashed instance
        // releases at process death; a drained one at close()).
        DesktopAppModel(roots).close()
    }
}
