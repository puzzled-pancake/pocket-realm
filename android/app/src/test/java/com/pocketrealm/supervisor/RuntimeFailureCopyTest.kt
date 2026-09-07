package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-JVM test for the F1 human-copy mapping at the UI decode boundary. */
class RuntimeFailureCopyTest {

    @Test fun `unverified orphan detail maps to human copy`() {
        val human = RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match")
        assertFalse(human.contains("UNVERIFIED_ORPHAN"))
        assertFalse(human.contains("WORLD ownership"))
        assertTrue(human.contains("recover"))
    }

    @Test fun `unverified orphan copy names the consented force-stop repair`() {
        val human = RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match")
        // Truthful about the deadlock: the world cannot be verified, so
        // automatic recovery will not stop it...
        assertTrue(human.contains("cannot verify"))
        assertTrue(human.contains("recovery will not stop it"))
        // ...and the only way forward is the dedicated consented action.
        assertTrue(human.contains("force-stopped"))
        assertTrue(human.contains("Force stop realm"))
        // The old copy advised "Tap Start to recover", which just re-ran the
        // refusal; the new copy must not claim a plain start can clear it.
        assertFalse(human.contains("Tap Start"))
    }

    @Test fun `timeout cancellation detail maps to human copy`() {
        val human = RuntimeFailureCopy.humanize(
            "TimeoutCancellationException: Timed out waiting for 1800000 ms")
        assertFalse(human.contains("TimeoutCancellationException"))
        assertTrue(human.contains("stopped responding"))
        assertTrue(human.contains("recover"))
    }

    @Test fun `component-prefixed timeout detail still maps`() {
        val human = RuntimeFailureCopy.humanize(
            "DATABASE: TimeoutCancellationException: Timed out waiting for 1000 ms")
        assertFalse(human.contains("TimeoutCancellationException"))
    }

    @Test fun `unknown detail passes through unchanged`() {
        assertEquals(
            "world: DB_REVISION ledger drift",
            RuntimeFailureCopy.humanize("world: DB_REVISION ledger drift"),
        )
        assertEquals("", RuntimeFailureCopy.humanize(""))
    }
}
