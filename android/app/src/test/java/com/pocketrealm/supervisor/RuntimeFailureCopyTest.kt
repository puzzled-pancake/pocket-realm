package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host-JVM test for the human-copy mapping at the UI decode boundary. */
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

    @Test fun `db owned by dead session detail maps to human copy`() {
        val human = RuntimeFailureCopy.humanize(
            "DATABASE: DB_OWNED_BY_DEAD_SESSION: ended own-session database owner " +
                "could not be released: injected drain failure")
        assertFalse(human.contains("DB_OWNED_BY_DEAD_SESSION"))
        assertFalse(human.contains("DATABASE:"))
        // Truthful about the wedge: the lock belongs to an ended session on
        // this device, so retrying the start cannot clear it...
        assertTrue(human.contains("database"))
        assertTrue(human.contains("ended"))
        assertTrue(human.contains("retrying"))
        // ...and the only way forward is the dedicated consented action.
        assertTrue(human.contains("Force stop realm"))
        assertTrue(human.contains("checked and repaired"))
    }

    @Test fun `db owned by dead session copy keeps the unverified orphan copy distinct`() {
        val stale = RuntimeFailureCopy.humanize("DB_OWNED_BY_DEAD_SESSION: database claim rejected")
        val orphan = RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match")
        // The two repair classes never collapse into each other's copy.
        assertTrue(stale.contains("locked"))
        assertFalse(stale.contains("cannot verify"))
        assertTrue(orphan.contains("cannot verify"))
        assertFalse(orphan.contains("locked"))
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
