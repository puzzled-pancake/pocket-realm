package com.pocketrealm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionAvailabilityTest {
    @Test fun clientGraphicsFailureNeverBlocksServerOnlyRealmStart() {
        val actions = homeActionAvailability(
            settingsReady = true,
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
        val ready = homeActionAvailability(true, null, canLaunchGame = true, clientRetryPending = false)
        assertTrue(ready.startRealm)
        assertTrue(ready.startRealmAndGame)
        assertTrue(ready.joinLan)
        assertTrue(ready.launchClient)

        val noAccount = homeActionAvailability(true, null, canLaunchGame = false, clientRetryPending = false)
        assertTrue(noAccount.startRealm)
        assertFalse(noAccount.startRealmAndGame)
        assertTrue(noAccount.joinLan)
        assertFalse(noAccount.launchClient)

        val retrying = homeActionAvailability(true, null, canLaunchGame = true, clientRetryPending = true)
        assertFalse(retrying.launchClient)

        // Before the first settings emission every start action reads as
        // unavailable instead of enabled-but-silent; client-only actions
        // (retry/enter game) stay available because they read no preset.
        val loading = homeActionAvailability(false, null, canLaunchGame = true, clientRetryPending = false)
        assertFalse(loading.startRealm)
        assertFalse(loading.startRealmAndGame)
        assertTrue(loading.joinLan)
        assertTrue(loading.launchClient)
    }
}
