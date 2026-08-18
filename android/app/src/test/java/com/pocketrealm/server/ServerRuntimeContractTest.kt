package com.pocketrealm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerRuntimeContractTest {
    @Test fun stateAndErrorCodesAreStable() {
        assertEquals("READY", ServerRuntimeContract.stateName(2))
        assertEquals("DB_REVISION", ServerRuntimeContract.errorName(5))
        assertEquals("UNKNOWN", ServerRuntimeContract.stateName(99))
    }

    @Test fun accountTokensRejectCliInjectionAndOversizeValues() {
        ServerRuntimeContract.requireAccountToken("username", "Player01")
        ServerRuntimeContract.requireAccountToken("password", "SafePass9")
        for (invalid in listOf("", "has space", "line\nbreak", "semi;colon", "nonascii\u00e9", "12345678901234567")) {
            assertThrows(IllegalArgumentException::class.java) {
                ServerRuntimeContract.requireAccountToken("value", invalid)
            }
        }
    }
}
