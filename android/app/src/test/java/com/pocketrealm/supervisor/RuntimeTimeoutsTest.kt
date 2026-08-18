package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeTimeoutsTest {
    @Test fun `bot profile extends only world startup`() {
        val value = RuntimeTimeouts(
            databaseStartMs = 11,
            realmStartMs = 12,
            worldStartMs = 13,
            clientStartMs = 14,
            botWorldStartMs = 99,
        )

        assertEquals(13, value.start(RuntimeComponent.WORLD, botProfile = false))
        assertEquals(99, value.start(RuntimeComponent.WORLD, botProfile = true))
        assertEquals(11, value.start(RuntimeComponent.DATABASE, botProfile = true))
        assertEquals(12, value.start(RuntimeComponent.REALM, botProfile = true))
        assertEquals(14, value.start(RuntimeComponent.CLIENT, botProfile = true))
    }
}
