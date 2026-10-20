package com.pocketrealm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    /** design-remote-accounts §6: the provisioning port sits next to the
     * realm port; both platforms pin the exact value so the firewall copy
     * and the listener cannot disagree. */
    @Test fun provisionPortIsPinnedNextToTheRealmPort() {
        assertEquals(3725, ServerRuntimeContract.PROVISION_PORT)
        assertEquals(ServerRuntimeContract.REALM_PORT + 1, ServerRuntimeContract.PROVISION_PORT)
    }

    /** §4.3/§6.3: The WrongPass lockout emission is conditional on the bind —
     * every non-loopback lane gets the decided values; the loopback-only
     * LOCAL lane emits NOTHING (the IP-autoban would ban 127.0.0.1 itself).
     * The desktop ServerRuntimeFiles twin pins the same contract. */
    @Test fun wrongPassLockoutFollowsTheBindCondition() {
        assertEquals("", ServerRuntimeContract.wrongPassLockoutConfLines("127.0.0.1"))
        val lan = ServerRuntimeContract.wrongPassLockoutConfLines("192.168.1.50")
        assertTrue("WrongPass.MaxCount = 10" in lan)
        assertTrue("WrongPass.BanTime = 900" in lan)
        assertTrue("WrongPass.BanType = 0" in lan)
        assertTrue(
            "link-local is a non-loopback bind too",
            "WrongPass.MaxCount = 10" in ServerRuntimeContract.wrongPassLockoutConfLines("169.254.10.20"),
        )
    }

    // ---- design-tunnel §4.4.2: the wildcard-bind lane ------------------
    // (the desktop DesktopTunnelServerFilesTest contract, pinned on the
    // shared resolver both platforms' conf writers now feed)

    @Test fun theWildcardIsTheOneRawBindAcceptedBesideTheTypedGrammar() {
        assertEquals(
            "0.0.0.0",
            ServerRuntimeContract.resolveBindAddress(
                com.pocketrealm.supervisor.RealmEndpoint.WILDCARD_BIND),
        )
        assertEquals(
            "192.168.1.50",
            ServerRuntimeContract.resolveBindAddress("192.168.1.50"),
        )
        assertEquals(
            "loopback stays the exact literal",
            "127.0.0.1",
            ServerRuntimeContract.resolveBindAddress("127.0.0.1"),
        )
    }

    @Test fun nonTypedBindsStayRefused() {
        listOf("0.0.0.0/0", "0.0.0.00", "0::0", "8.8.8.8", "localhost", "100.64.0.9")
            .forEach { address ->
                assertThrows(address, IllegalArgumentException::class.java) {
                    ServerRuntimeContract.resolveBindAddress(address)
                }
            }
        // The wildcard never becomes a RealmEndpoint (the typed path stays
        // strict; the desktop test pins the same invariant).
        assertThrows(IllegalArgumentException::class.java) {
            com.pocketrealm.supervisor.RealmEndpoint.parseLan("0.0.0.0")
        }
    }
}
