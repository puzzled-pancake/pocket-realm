package com.pocketrealm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionAvailabilityTest {
    @Test fun clientGraphicsFailureNeverBlocksServerOnlyRealmStart() {
        val actions = homeActionAvailability(
            clientUnavailableReason = "selected Vulkan driver is unavailable",
            canLaunchGame = true,
            clientRetryPending = false,
        )

        assertTrue(actions.startRealm)
        assertFalse(actions.startRealmAndGame)
        assertFalse(actions.joinLan)
        assertFalse(actions.launchClient)
    }

    @Test fun gameActionsRequireClientAvailabilityAccountAndNoPendingRetry() {
        val ready = homeActionAvailability(null, canLaunchGame = true, clientRetryPending = false)
        assertTrue(ready.startRealm)
        assertTrue(ready.startRealmAndGame)
        assertTrue(ready.joinLan)
        assertTrue(ready.launchClient)

        val noAccount = homeActionAvailability(null, canLaunchGame = false, clientRetryPending = false)
        assertTrue(noAccount.startRealm)
        assertFalse(noAccount.startRealmAndGame)
        assertTrue(noAccount.joinLan)
        assertFalse(noAccount.launchClient)

        val retrying = homeActionAvailability(null, canLaunchGame = true, clientRetryPending = true)
        assertFalse(retrying.launchClient)
    }
}
