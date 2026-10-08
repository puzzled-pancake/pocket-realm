package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Rate limiting layer 1 (design-remote-accounts §6): five failed token/parse
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
