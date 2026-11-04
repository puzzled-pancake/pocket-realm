package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Rate limiting layer 1 (design-remote-accounts): five failed token/parse
 * attempts from one source → a 10-minute lockout that survives a store
 * reopen; a distinct source is untouched; the global concurrency gate holds
 * at [ProvisionRateLimiter.MAX_CONCURRENT_CONNECTIONS].
 */
class ProvisionRateLimiterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var nowMs = 2_000_000L

    private fun limiter(store: FriendInviteStore? = null): ProvisionRateLimiter =
        ProvisionRateLimiter(
            store ?: FriendInviteStore(folder.newFolder("friend-access"), clock = { nowMs }),
            clock = { nowMs },
        )

    @Test
    fun fiveFailuresTripTheTenMinuteLockout() {
        val limiter = limiter()
        repeat(ProvisionRateLimiter.MAX_FAILURES - 1) { attempt ->
            assertFalse("failure ${attempt + 1} must not lock yet", limiter.recordFailure("10.0.0.1"))
            assertFalse(limiter.isLocked("10.0.0.1"))
        }
        assertTrue("the fifth failure trips the lockout", limiter.recordFailure("10.0.0.1"))
        assertTrue(limiter.isLocked("10.0.0.1"))
    }

    @Test
    fun theLockoutSurvivesAReopenFromThePersistedRow() {
        val directory = folder.newFolder("friend-access")
        val store = FriendInviteStore(directory, clock = { nowMs })
        val limiter = ProvisionRateLimiter(store, clock = { nowMs })
        repeat(ProvisionRateLimiter.MAX_FAILURES) { limiter.recordFailure("10.0.0.7") }
        assertTrue(limiter.isLocked("10.0.0.7"))

        // A fresh process (new limiter, same store): the persisted row holds.
        val reopenedStore = FriendInviteStore(directory, clock = { nowMs })
        val reopened = ProvisionRateLimiter(reopenedStore, clock = { nowMs })
        assertTrue("a restart must not amnesty a locked source", reopened.isLocked("10.0.0.7"))

        // The window lapsing clears it (live and persisted).
        nowMs += ProvisionRateLimiter.LOCKOUT_MS + 1_000
        assertFalse(reopened.isLocked("10.0.0.7"))
        assertEquals(0L, reopenedStore.lockedUntilMs("10.0.0.7"))
    }

    @Test
    fun lockoutsArePerSource() {
        val limiter = limiter()
        repeat(ProvisionRateLimiter.MAX_FAILURES) { limiter.recordFailure("10.0.0.1") }
        assertTrue(limiter.isLocked("10.0.0.1"))
        assertFalse("another device is untouched", limiter.isLocked("10.0.0.2"))
    }

    @Test
    fun aSuccessResetsTheFailureCounter() {
        val limiter = limiter()
        repeat(ProvisionRateLimiter.MAX_FAILURES - 1) { limiter.recordFailure("10.0.0.3") }
        limiter.recordSuccess("10.0.0.3")
        repeat(ProvisionRateLimiter.MAX_FAILURES - 1) { limiter.recordFailure("10.0.0.3") }
        assertFalse("2 failures then success then 4 failures must not lock", limiter.isLocked("10.0.0.3"))
        assertTrue(limiter.recordFailure("10.0.0.3"))
    }

    @Test
    fun theMergedFailureCountPersistsAndResetsAtTheTrip() {
        val store = FriendInviteStore(folder.newFolder("friend-access"), clock = { nowMs })
        val limiter = ProvisionRateLimiter(store, clock = { nowMs })
        repeat(ProvisionRateLimiter.MAX_FAILURES - 1) { attempt ->
            limiter.recordFailure("10.0.0.9")
            assertEquals(
                "failure ${attempt + 1} must persist exactly the merged count",
                attempt + 1,
                store.rateRows().single { it.ip == "10.0.0.9" }.failures,
            )
        }
        assertTrue(limiter.recordFailure("10.0.0.9"))
        assertEquals(
            "the trip resets the counter",
            0,
            store.rateRows().single { it.ip == "10.0.0.9" }.failures,
        )
    }

    @Test
    fun theConcurrencyGateHoldsTheGlobalCap() {
        val limiter = limiter()
        val acquired = (1..ProvisionRateLimiter.MAX_CONCURRENT_CONNECTIONS).count { limiter.tryAcquireConnection() }
        assertEquals(ProvisionRateLimiter.MAX_CONCURRENT_CONNECTIONS, acquired)
        assertFalse("over capacity refuses", limiter.tryAcquireConnection())
        limiter.releaseConnection()
        assertTrue(limiter.tryAcquireConnection())
    }
}

// ---------------------------------------------------------------------------
// the live per-IP maps are keyed by
// attacker-supplied source IPs and used to grow without bound or eviction
// for the process lifetime - a many-unique-source spray (the exact hostile
// pattern layer 1 exists for) monotonically grew the host app heap. The
// maps are now capped at MAX_TRACKED_IPS with oldest-first eviction that
// never drops a locked-while-locked source (the same amnesty shape the
// persisted layer's MAX_RATE_ROWS rule applies to unlocked rows).
// ---------------------------------------------------------------------------

class ProvisionRateLimiterBudgetTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var nowMs = 5_000_000L

    private fun limiter(cap: Int): ProvisionRateLimiter =
        ProvisionRateLimiter(
            FriendInviteStore(folder.newFolder("friend-$cap"), clock = { nowMs }),
            clock = { nowMs },
            maxTrackedIps = cap,
        )

    @Test
    fun theLiveIpMapsStayBoundedUnderADistinctSourceSpray() {
        val cap = 8
        val limiter = limiter(cap)
        repeat(cap * 3) { index -> limiter.recordFailure("10.1.0.$index") }
        assertTrue(
            "a distinct-source spray must not grow the live maps past the " +
                "cap (tracked ${limiter.trackedIpCount()})",
            limiter.trackedIpCount() <= cap,
        )
        // Oldest-first eviction: the earliest sprayed source lost its
        // in-memory failure counter, so a fresh failure from it starts
        // counting from zero again (the same amnesty the persisted layer
        // applies to displaced unlocked rows).
        nowMs += 1
        assertFalse(
            "the oldest sprayed source must have been evicted (its counter " +
                "amnestied, a new failure starts a fresh count)",
            limiter.recordFailure("10.1.0.0"),
        )
    }

    @Test
    fun aLockedIpIsNeverEvictedWhileItsLockoutIsLive() {
        val cap = 4
        val limiter = limiter(cap)
        val locked = (0 until cap).map { "10.2.0.$it" }
        for (ip in locked) {
            repeat(ProvisionRateLimiter.MAX_FAILURES) { limiter.recordFailure(ip) }
        }
        for (ip in locked) assertTrue("precondition: $ip locked", limiter.isLocked(ip))
        // The budget is saturated with LOCKED sources: a new spray cannot
        // evict them (the eviction skips locked-while-locked sources), and
        // the tracked count may transiently exceed the cap by the new
        // sources rather than dropping a live lockout.
        repeat(cap * 2) { index -> limiter.recordFailure("10.3.0.$index") }
        for (ip in locked) {
            assertTrue(
                "a live lockout must never be evicted while locked: $ip",
                limiter.isLocked(ip),
            )
        }
        assertTrue(
            "the new sources stay tracked (the transient overshoot)",
            limiter.trackedIpCount() >= locked.size,
        )
    }
}
