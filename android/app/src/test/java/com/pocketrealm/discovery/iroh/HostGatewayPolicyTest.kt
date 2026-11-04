package com.pocketrealm.discovery.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class HostGatewayPolicyTest {

    private val node = "ab".repeat(32)

    @Test
    fun aStoredSecretIsKeptAndAMissingOrCorruptOneIsReplaced() {
        val kept = HostGatewayPolicy.secretOrFresh(node)
        assertEquals(node to false, kept)
        for (bad in listOf("", "AB".repeat(32), "ab".repeat(31), "zz".repeat(32))) {
            val (fresh, mustPersist) = HostGatewayPolicy.secretOrFresh(bad)
            assertTrue("a replaced secret must be persisted ($bad)", mustPersist)
            assertTrue(HostGatewayPolicy.isValidSecretHex(fresh))
        }
    }

    @Test
    fun freshSecretsAreLowercaseHexAndDistinct() {
        val random = SecureRandom()
        val a = HostGatewayPolicy.freshSecretHex(random)
        val b = HostGatewayPolicy.freshSecretHex(random)
        assertEquals(HostGatewayPolicy.SECRET_HEX_LENGTH, a.length)
        assertTrue(HostGatewayPolicy.isValidSecretHex(a))
        assertNotEquals(a, b)
    }

    @Test
    fun hexRoundTripsThroughBytes() {
        val hex = HostGatewayPolicy.freshSecretHex()
        val bytes = HostGatewayPolicy.hexToBytes(hex)
        assertEquals(32, bytes.size)
        assertEquals(hex, HostGatewayPolicy.bytesToHex(bytes))
    }

    @Test
    fun theRelayRootDotIsStrippedSoTheDescriptorFenceAdmitsIt() {
        assertEquals(
            "https://usw1-1.relay.n0.iroh.link/",
            HostGatewayPolicy.canonicalRelayUrl("https://usw1-1.relay.n0.iroh.link./"),
        )
        assertEquals(
            "https://relay.example.com:443/",
            HostGatewayPolicy.canonicalRelayUrl("https://relay.example.com.:443./"),
        )
        assertNull(HostGatewayPolicy.canonicalRelayUrl(null))
        val descriptor = HostGatewayPolicy.descriptorOrNull(node, "https://usw1-1.relay.n0.iroh.link./")
        assertEquals("https://usw1-1.relay.n0.iroh.link/", descriptor?.relayUrl)
        assertEquals(node, descriptor?.nodeHex)
    }

    @Test
    fun noRelayOrARefusedRelayMeansNoDescriptor() {
        assertNull(HostGatewayPolicy.descriptorOrNull(node, null))
        assertNull(HostGatewayPolicy.descriptorOrNull(node, "http://relay.example.com/"))
        assertNull(HostGatewayPolicy.descriptorOrNull("AB".repeat(32), "https://relay.example.com/"))
    }

    @Test
    fun theMuxHeaderSelectsTheLane() {
        assertEquals(
            HostGatewayPolicy.Lane.KeepAlive,
            HostGatewayPolicy.classify(PortMux.encodeHeader(GatewayContract.PORT_KEEPALIVE)),
        )
        assertEquals(
            HostGatewayPolicy.Lane.Game(GatewayContract.PORT_REALM),
            HostGatewayPolicy.classify(PortMux.encodeHeader(GatewayContract.PORT_REALM)),
        )
        assertEquals(
            HostGatewayPolicy.Lane.Game(GatewayContract.PORT_WORLD),
            HostGatewayPolicy.classify(PortMux.encodeHeader(GatewayContract.PORT_WORLD)),
        )
        assertEquals(HostGatewayPolicy.Lane.Refused, HostGatewayPolicy.classify(null))
        assertEquals(HostGatewayPolicy.Lane.Refused, HostGatewayPolicy.classify(byteArrayOf(0x0e)))
        // A foreign port (22) is never dialed.
        assertEquals(HostGatewayPolicy.Lane.Refused, HostGatewayPolicy.classify(byteArrayOf(0, 22)))
    }

    @Test
    fun onlyTheTwoGamePortsAreDialedAtTheHostingBindAddress() {
        val host = "localhost"
        val realm = HostGatewayPolicy.dialTarget(GatewayContract.PORT_REALM, host)
        assertEquals(GatewayContract.PORT_REALM, realm.port)
        assertEquals(host, realm.hostString)
        assertEquals(GatewayContract.PORT_WORLD, HostGatewayPolicy.dialTarget(GatewayContract.PORT_WORLD, host).port)
        for (port in listOf(GatewayContract.PORT_KEEPALIVE, 22, 3725)) {
            assertTrue(runCatching { HostGatewayPolicy.dialTarget(port, host) }.isFailure)
        }
        assertTrue(runCatching { HostGatewayPolicy.dialTarget(GatewayContract.PORT_REALM, " ") }.isFailure)
    }

    @Test
    fun anIdleConnectionIsReapedOnlyPastTheBudget() {
        assertFalse(HostGatewayPolicy.idleExpired(0L, HostGatewayPolicy.IDLE_REAP_MS))
        assertTrue(HostGatewayPolicy.idleExpired(0L, HostGatewayPolicy.IDLE_REAP_MS + 1))
    }

    @Test
    fun theSignupLaneIsItsOwnReservedMuxLane() {
        assertEquals(
            HostGatewayPolicy.Lane.Signup,
            HostGatewayPolicy.classify(PortMux.encodeHeader(GatewayContract.PORT_SIGNUP)),
        )
        assertTrue(GatewayContract.PORT_SIGNUP != GatewayContract.PORT_REALM &&
            GatewayContract.PORT_SIGNUP != GatewayContract.PORT_WORLD)
        assertTrue(runCatching { HostGatewayPolicy.dialTarget(GatewayContract.PORT_SIGNUP, "localhost") }.isFailure)
        // The hello lane is reserved too and never dialed.
        assertEquals(
            HostGatewayPolicy.Lane.Hello,
            HostGatewayPolicy.classify(PortMux.encodeHeader(GatewayContract.PORT_HELLO)),
        )
        assertTrue(runCatching { HostGatewayPolicy.dialTarget(GatewayContract.PORT_HELLO, "localhost") }.isFailure)
    }
}
