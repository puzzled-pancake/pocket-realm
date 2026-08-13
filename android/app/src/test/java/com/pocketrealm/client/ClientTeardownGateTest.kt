package com.pocketrealm.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTeardownGateTest {
    @Test fun displayReleaseRequiresSuccessfulRuntimeAndTreeDrainProofs() {
        assertTrue(ClientTeardownGate.mayReleaseDisplay(true, true, true))
        assertFalse(ClientTeardownGate.mayReleaseDisplay(false, true, true))
        assertFalse(ClientTeardownGate.mayReleaseDisplay(true, false, true))
        assertFalse(ClientTeardownGate.mayReleaseDisplay(true, true, false))
    }
}
