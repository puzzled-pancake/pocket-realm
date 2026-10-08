package com.pocketrealm.desktop

import java.io.File
import java.lang.ProcessBuilder
import java.lang.ProcessHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The relaunch handoff pins (the spawn-first race both the spent-world
 * relauncher and the updater's restart-into-update live in): the stamp is
 * what the fresh instance's entry keys on, the bounded wait retries only
 * held-lock failures, succeeds when the predecessor releases, and returns
 * null when the window closes — a non-held failure propagates honestly.
 */
class RelaunchHandoffTest {
    @Test
    fun `stamp names this process as the draining predecessor`() {
        val builder = ProcessBuilder("irrelevant")
        RelaunchHandoff.stamp(builder)
        assertTrue(
            "the env marker must ride the spawned process",
            builder.environment().containsKey(RelaunchHandoff.ENV),
        )
        assertEquals(
            ProcessHandle.current().pid().toString(),
            builder.environment().get(RelaunchHandoff.ENV),
        )
    }

    @Test
    fun `wait succeeds once the predecessor releases the lock`() {
        var attempts = 0
        val result = RelaunchHandoff.waitFor(
            factory = {
                if (attempts++ < 2) throw AppInstanceLockHeld(4242, File("app-instance.lock"))
                "model"
            },
            pollMs = 1,
            deadlineMs = 10_000,
            sleeper = {},
        )
        assertEquals("model", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `wait returns null when the window closes on a held lock`() {
        var attempts = 0
        val result = RelaunchHandoff.waitFor(
            factory = {
                attempts++
                throw AppInstanceLockHeld(4242, File("app-instance.lock"))
            },
            pollMs = 1,
            deadlineMs = 5,
            sleeper = {},
            now = { attempts * 10L },
        )
        assertNull("the window closing is the honest timeout verdict", result)
    }

    @Test
    fun `wait rethrows a failure that is not a held lock`() {
        try {
            RelaunchHandoff.waitFor(
                factory = { error("constructor exploded") },
                pollMs = 1,
                deadlineMs = 10_000,
                sleeper = {},
            )
            fail("non-held failures must propagate, never be retried")
        } catch (expected: IllegalStateException) {
            assertEquals("constructor exploded", expected.message)
        }
    }
}
