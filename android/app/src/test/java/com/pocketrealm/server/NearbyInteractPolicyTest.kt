package com.pocketrealm.server

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyInteractPolicyTest {
    @Test
    fun defaultAndSupportedValuesRemainExact() {
        assertEquals(250, NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS)
        assertEquals(100, NearbyInteractPolicy.normalizeTriggerGuardMs(100))
        assertEquals(600, NearbyInteractPolicy.normalizeTriggerGuardMs(600))
        assertEquals(2_000, NearbyInteractPolicy.normalizeTriggerGuardMs(2_000))
    }

    @Test
    fun valuesOutsideRealmBoundsAreClamped() {
        assertEquals(100, NearbyInteractPolicy.normalizeTriggerGuardMs(-1))
        assertEquals(2_000, NearbyInteractPolicy.normalizeTriggerGuardMs(Int.MAX_VALUE))
    }
}
