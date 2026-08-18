package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientRuntimePosixTzTest {
    @Test
    fun eastOfUtcWholeHourInvertsSign() {
        // NZST is UTC+12; POSIX inverts the sign, and the quoted numeric
        // name avoids depending on any zoneinfo in the box64 rootfs.
        assertEquals("<+12>-12", ClientRuntimeContract.posixTzForOffset(12 * 3600))
    }

    @Test
    fun westOfUtcWholeHour() {
        assertEquals("<-5>+5", ClientRuntimeContract.posixTzForOffset(-5 * 3600))
    }

    @Test
    fun fractionalOffsetsKeepMinutes() {
        assertEquals("<+5:45>-5:45", ClientRuntimeContract.posixTzForOffset(5 * 3600 + 45 * 60))
    }

    @Test
    fun utcIsStable() {
        assertEquals("<+0>-0", ClientRuntimeContract.posixTzForOffset(0))
    }
}
