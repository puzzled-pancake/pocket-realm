package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM truth table for the F1 orphan self-heal decisions plus the
 * adoptOwner fresh-token factory hook. Pure logic: no Android classes.
 */
class OrphanSelfHealPolicyTest {

    @Test fun `re-observe grace is bounded at three ticks`() {
        assertTrue(OrphanSelfHealPolicy.shouldReobserve(1))
        assertTrue(OrphanSelfHealPolicy.shouldReobserve(2))
        assertFalse(OrphanSelfHealPolicy.shouldReobserve(3))
        assertFalse(OrphanSelfHealPolicy.shouldReobserve(4))
        assertEquals(3, OrphanSelfHealPolicy.REOBSERVE_GRACE_TICKS)
    }

    @Test fun `healable orphan requires a non-stopped state and a null owner`() {
        val owner = ComponentOwner("session-a", "ab".repeat(32))
        assertTrue(OrphanSelfHealPolicy.isHealableOrphan(ComponentLifecycle.READY, null))
        assertTrue(OrphanSelfHealPolicy.isHealableOrphan(ComponentLifecycle.STARTING, null))
        assertTrue(OrphanSelfHealPolicy.isHealableOrphan(ComponentLifecycle.STOPPING, null))
        assertTrue(OrphanSelfHealPolicy.isHealableOrphan(ComponentLifecycle.FAILED, null))
        // A stopped component finished its teardown; nothing to heal.
        assertFalse(OrphanSelfHealPolicy.isHealableOrphan(ComponentLifecycle.STOPPED, null))
        // Any non-null owner (matching or foreign) is never adoptable: the
        // pinned UNVERIFIED_ORPHAN refusal owns that case.
        assertFalse(OrphanSelfHealPolicy.isHealableOrphan(ComponentLifecycle.READY, owner))
        assertFalse(OrphanSelfHealPolicy.isHealableOrphan(ComponentLifecycle.STOPPED, owner))
    }

    @Test fun `adopt owner pairs the session with a fresh token per call`() {
        val policy = OrphanSelfHealPolicy(SequentialTokens())
        val first = policy.adoptOwner("session-a")
        val second = policy.adoptOwner("session-a")
        assertEquals("session-a", first.sessionId)
        assertEquals("session-a", second.sessionId)
        assertNotEquals(first.instanceToken, second.instanceToken)
        assertTrue(first.instanceToken.matches(Regex("[0-9a-f]{64}")))
    }

    private class SequentialTokens : RuntimeTokenSource {
        private var next = 0
        override fun sessionId() = "session"
        override fun instanceToken(): String = (++next).toString(16).padStart(2, '0').repeat(32)
    }
}
