package com.pocketrealm.supervisor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The online host's endpoint contract: hosting over the internet binds
 * loopback (the internet gateway forwards to it), so a phone on mobile data
 * with the Wi-Fi off hosts exactly like one on Wi-Fi. LAN players are the
 * extra switch — on, with a live private interface, the session is today's
 * LAN host.
 *
 * The service wiring (the intent handler) is pinned as a source contract the
 * same way LanTunnelWiringContractTest pins the tunnel glue: RealmService
 * needs a live Android context.
 */
class HostOnlineEndpointTest {

    @Test fun noLanPlayersMeansLoopback() {
        val spec = RuntimeLaunchSpec.lanHost("profile", "127.0.0.1")
        assertEquals(RuntimeMode.LAN_HOST, spec.mode)
        assertEquals(RealmEndpoint.LOCAL, spec.endpoint)
        assertFalse("a loopback bind exposes nothing on the LAN", spec.allowLanPlayers)
        assertFalse(spec.tunnelAdvertising)
        assertEquals(
            "the game binds loopback (never the wildcard)",
            "127.0.0.1",
            AndroidRuntimeBackend.bindAddressFor(spec),
        )
        // The restart/AIDL path rebuilds the same spec from the journal.
        assertEquals(spec, RuntimeLaunchSpec.fromJson(spec.toJson()))
    }

    @Test fun lanPlayersPlusInterfaceMeansTheLanAddress() {
        val spec = RuntimeLaunchSpec.lanHost("profile", "192.168.1.20")
        assertEquals(RuntimeMode.LAN_HOST, spec.mode)
        assertEquals("192.168.1.20", spec.endpoint.address)
        assertTrue("a private bind keeps the explicit LAN authorization", spec.allowLanPlayers)
    }

    @Test fun lanPlayersWithoutAnInterfaceFallsBackToLoopback() {
        assertEquals(
            "127.0.0.1",
            HostOnlineEndpointPolicy.selectAddress(lanPlayers = true, available = emptyList()),
        )
    }

    @Test fun theAuthorizationBitStaysBoundToTheBindEndpoint() {
        // A loopback bind cannot claim LAN exposure; a private bind cannot
        // skip its authorization.
        assertIllegalArgument {
            RuntimeLaunchSpec(
                RuntimeMode.LAN_HOST,
                "profile",
                RealmEndpoint.LOCAL,
                includeClient = false,
                allowLanPlayers = true,
            )
        }
        assertIllegalArgument {
            RuntimeLaunchSpec(
                RuntimeMode.LAN_HOST,
                "profile",
                RealmEndpoint.parseLan("192.168.1.20"),
                includeClient = false,
                allowLanPlayers = false,
            )
        }
        // A loopback-bound host never advertises an override address.
        assertIllegalArgument {
            RuntimeLaunchSpec.lanHost("profile", "127.0.0.1", advertised = "100.64.0.5")
        }
    }

    @Test fun aLoopbackHostIsNotStoppedOnInterfaceLoss() {
        // The internet gateway carries the players, so the loss monitor runs
        // NO interface leg: flipping between Wi-Fi and mobile data keeps the
        // session alive. A private bind keeps the LAN leg.
        assertEquals(InterfaceWatch.None, InterfaceWatch.forSession("127.0.0.1", null))
        assertEquals(InterfaceWatch.None, InterfaceWatch.forSession("127.0.0.1", ""))
        assertEquals(InterfaceWatch.Lan, InterfaceWatch.forSession("192.168.1.20", null))
        assertEquals(InterfaceWatch.Lan, InterfaceWatch.forSession(null, null))
    }

    @Test fun theServiceStartsTheOnlineHostThroughTheSharedPolicy() {
        val service = mainSource("service/RealmService.kt")
        assertTrue(
            "the online-host action exists",
            "ACTION_HOST_ONLINE -> submitOperation(\"host-online\")" in service,
        )
        assertTrue(
            "the endpoint choice goes through the shared policy",
            "HostOnlineEndpointPolicy.selectAddress(" in service,
        )
        assertTrue(
            "the LAN-players switch rides the intent",
            "EXTRA_LAN_PLAYERS" in service,
        )
        assertTrue(
            "the hostOnline helper is the one-tap entry point",
            "fun hostOnline(" in service,
        )
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected an IllegalArgumentException")
    }

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/pocketrealm/$relative"),
            File("app/src/main/java/com/pocketrealm/$relative"),
            File("android/app/src/main/java/com/pocketrealm/$relative"),
            File("../android/app/src/main/java/com/pocketrealm/$relative"),
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
        return source.readText(Charsets.UTF_8)
    }
}
