package com.pocketrealm.discovery.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The joiner's device identity, the hello lane's lines, and the host's
 * read of the account name on the realm lane. */
class JoinAdmissionTest {

    @get:Rule val temp = TemporaryFolder()

    private val hostA = "a1".repeat(32)
    private val hostB = "b2".repeat(32)

    // ------------------------------------------------------------ identity

    @Test
    fun theTokenIsStablePerHostAndDifferentAcrossHosts() {
        val secret = DeviceIdentity.deviceSecret("11".repeat(32), "0123456789abcdef")
        val a1 = DeviceIdentity.tokenFor(secret, hostA)
        val a2 = DeviceIdentity.tokenFor(DeviceIdentity.deviceSecret("11".repeat(32), "0123456789abcdef"), hostA)
        val b = DeviceIdentity.tokenFor(secret, hostB)
        assertEquals(a1, a2)
        assertNotEquals("two hosts must not be able to match one device", a1, b)
        assertTrue(DeviceIdentity.isToken(a1))
        // The host node id's case never changes the token.
        assertEquals(a1, DeviceIdentity.tokenFor(secret, hostA.uppercase()))
    }

    @Test
    fun theRawPlatformIdNeverAppearsInWhatTheHostSees() {
        val platformId = "0123456789abcdef"
        val secret = DeviceIdentity.deviceSecret("11".repeat(32), platformId)
        val token = DeviceIdentity.tokenFor(secret, hostA)
        val key = DeviceIdentity.joinSecretHex(secret, hostA)
        assertFalse(token.contains(platformId))
        assertFalse(key.contains(platformId))
        assertNotEquals("the join key and the device token must differ", token, key)
    }

    @Test
    fun thePlatformIdOutlivesAReinstallAndTheInstallIdIsOnlyTheFallback() {
        val platform = "0123456789abcdef"
        // A reinstall makes a new install id but keeps the platform id.
        assertTrue(
            DeviceIdentity.deviceSecret("11".repeat(32), platform)
                .contentEquals(DeviceIdentity.deviceSecret("22".repeat(32), platform)),
        )
        // Without a platform id (or with the known shared emulator id) the
        // install id decides.
        for (none in listOf(null, "", "  ", "9774d56d682e549c")) {
            assertFalse(
                DeviceIdentity.deviceSecret("11".repeat(32), none)
                    .contentEquals(DeviceIdentity.deviceSecret("22".repeat(32), none)),
            )
        }
    }

    @Test
    fun theInstallIdIsCreatedOnceAndReadBack() {
        val file = temp.root.resolve("device-install-id")
        val first = DeviceIdentity.loadOrCreateInstallId(file)
        assertTrue(DeviceIdentity.isToken(first))
        assertEquals(first, DeviceIdentity.loadOrCreateInstallId(file))
        file.writeText("not an id")
        val healed = DeviceIdentity.loadOrCreateInstallId(file)
        assertTrue(DeviceIdentity.isToken(healed))
        assertNotEquals(first, healed)
    }

    @Test
    fun tokenShapeIsSixtyFourLowercaseHex() {
        assertTrue(DeviceIdentity.isToken("0f".repeat(32)))
        assertFalse(DeviceIdentity.isToken("0F".repeat(32)))
        assertFalse(DeviceIdentity.isToken("0f".repeat(31)))
        assertFalse(DeviceIdentity.isToken(null))
        assertEquals("0f0f0f0f", DeviceIdentity.shortLabel("0f".repeat(32)))
    }

    // --------------------------------------------------------------- hello

    @Test
    fun theHelloLineRoundTripsAndRefusesAnythingElse() {
        val token = "ab".repeat(32)
        val line = JoinHello.encodeRequest(token)
        assertTrue(line.endsWith("\n"))
        val decoded = JoinHello.decodeRequest(line)!!
        assertEquals(JoinHello.SCHEMA, decoded.schema)
        assertEquals(token, decoded.deviceToken)
        assertEquals("", decoded.passwordProof)
        assertNull(JoinHello.decodeRequest("{\"schema\":1,\"device\":\"short\"}"))
        // Schema 2 must carry the pass field (even empty).
        assertNull(JoinHello.decodeRequest("{\"schema\":2,\"device\":\"$token\"}"))
        assertNull(JoinHello.decodeRequest("{\"schema\":1,\"device\":\"$token\",\"extra\":1}"))
        assertNull(JoinHello.decodeRequest("not json"))
        assertTrue(runCatching { JoinHello.encodeRequest("nope") }.isFailure)
    }

    @Test
    fun theSchema2HelloCarriesThePasswordProofAndSchema1StillDecodes() {
        val token = "ab".repeat(32)
        val proof = JoinPasscode.proof(JoinPasscode.keyHex("sesame"), hostA, token)
        val decoded = JoinHello.decodeRequest(JoinHello.encodeRequest(token, proof))!!
        assertEquals(JoinHello.SCHEMA, decoded.schema)
        assertEquals(proof, decoded.passwordProof)
        assertTrue(decoded.speaksSchema2)
        // The schema-1 shape still decodes (an older app), with no proof.
        val v1 = JoinHello.decodeRequest("{\"schema\":1,\"device\":\"$token\"}")!!
        assertEquals(JoinHello.SCHEMA_1, v1.schema)
        assertFalse(v1.speaksSchema2)
        // A malformed proof refuses the whole line.
        assertNull(JoinHello.decodeRequest("{\"schema\":2,\"device\":\"$token\",\"pass\":\"zz\"}"))
    }

    @Test
    fun thePasswordProofMatchesOnBothSidesAndBindsTheJoinerNode() {
        val joiner = "ab".repeat(32)
        val key = JoinPasscode.keyHex("sesame")
        val proof = JoinPasscode.proof(key, hostA, joiner)
        // The host recomputes the same value from its own password.
        assertEquals(proof, JoinPasscode.proof(JoinPasscode.keyHex("sesame"), hostA, joiner))
        assertTrue(JoinPasscode.matches(proof, proof))
        // A proof from ANOTHER joiner node does not match (the transport
        // authenticates the node, so a captured proof only works from the
        // same device).
        assertFalse(JoinPasscode.matches(JoinPasscode.proof(key, hostA, "cd".repeat(32)), proof))
        // Another host derives another proof from the same password.
        assertFalse(JoinPasscode.matches(JoinPasscode.proof(key, hostB, joiner), proof))
        // A different password never matches.
        assertFalse(JoinPasscode.matches(JoinPasscode.proof(JoinPasscode.keyHex("open"), hostA, joiner), proof))
        assertTrue(JoinPasscode.isKeyHex(key))
        assertFalse(JoinPasscode.isKeyHex("nothex"))
    }

    @Test
    fun theRepliesTravelAsSignupReplies() {
        for (reply in listOf(JoinHello.WELCOME_REPLY, JoinHello.BANNED_REPLY, JoinHello.INVALID_REPLY)) {
            assertEquals(reply, SignupProtocol.decodeReply(SignupProtocol.encodeReply(reply)))
        }
        assertTrue(JoinHello.WELCOME_REPLY.ok)
        assertFalse(JoinHello.BANNED_REPLY.ok)
        assertFalse("ban copy must stay plain", JoinHello.BANNED_REPLY.detail.contains("_"))
    }

    @Test
    fun theHostChannelLinesRoundTrip() {
        val peer = JoinPeer("cd".repeat(32), "ef".repeat(32))
        val admit = JoinHello.encodeAdmitLine(GatewayContract.ADMIT_REQUEST_LINE_PREFIX, 7L, peer)
        val decodedAdmit = JoinHello.decodeAdmitLine(GatewayContract.ADMIT_REQUEST_LINE_PREFIX, admit)!!
        assertEquals(7L, decodedAdmit.id)
        assertEquals(peer, decodedAdmit.peer)
        assertEquals("", decodedAdmit.passwordProof)
        assertNull(JoinHello.decodeAdmitLine(GatewayContract.PEER_EVENT_LINE_PREFIX, admit))
        // The proof rides the admit line, and a line without it (an older
        // gateway) decodes with an empty proof.
        val proof = JoinPasscode.proof(JoinPasscode.keyHex("sesame"), hostA, peer.nodeHex)
        val withProof = JoinHello.encodeAdmitLine(GatewayContract.ADMIT_REQUEST_LINE_PREFIX, 8L, peer, proof)
        assertEquals(
            proof,
            JoinHello.decodeAdmitLine(GatewayContract.ADMIT_REQUEST_LINE_PREFIX, withProof)!!.passwordProof,
        )
        val legacy = GatewayContract.ADMIT_REQUEST_LINE_PREFIX +
            "{\"id\":9,\"device\":\"" + peer.deviceToken + "\",\"node\":\"" + peer.nodeHex + "\"}"
        assertEquals(
            "",
            JoinHello.decodeAdmitLine(GatewayContract.ADMIT_REQUEST_LINE_PREFIX, legacy)!!.passwordProof,
        )

        val login = JoinHello.PeerEvent(JoinHello.EVENT_LOGIN, peer, "WANDERER")
        val loginLine = JoinHello.encodeEventLine(GatewayContract.PEER_EVENT_LINE_PREFIX, login)
        assertEquals(login, JoinHello.decodeEventLine(GatewayContract.PEER_EVENT_LINE_PREFIX, loginLine))
        val leave = JoinHello.PeerEvent(JoinHello.EVENT_LEAVE, peer, null)
        assertEquals(
            leave,
            JoinHello.decodeEventLine(
                GatewayContract.PEER_EVENT_LINE_PREFIX,
                JoinHello.encodeEventLine(GatewayContract.PEER_EVENT_LINE_PREFIX, leave),
            ),
        )

        val drop = JoinHello.encodeDropLine(GatewayContract.DROP_LINE_PREFIX, peer.deviceToken, null)
        assertEquals(peer.deviceToken to null, JoinHello.decodeDropLine(GatewayContract.DROP_LINE_PREFIX, drop))
        assertNull(
            JoinHello.decodeDropLine(
                GatewayContract.DROP_LINE_PREFIX,
                JoinHello.encodeDropLine(GatewayContract.DROP_LINE_PREFIX, null, null),
            ),
        )
    }

    @Test
    fun aSignupRequestLineCarriesTheJoinerBesideTheFrame() {
        val peer = JoinPeer("cd".repeat(32), "ef".repeat(32))
        val frame = SignupProtocol.encodeRequest(SignupProtocol.Request("Wanderer", "1a".repeat(32), "2b".repeat(32)))
        val prefix = GatewayContract.SIGNUP_REQUEST_LINE_PREFIX
        val line = SignupProtocol.encodeChannelLine(prefix, 3L, frame, peer)
        // An app that only knows the old shape still reads the line.
        assertEquals(3L to frame.trimEnd('\n'), SignupProtocol.decodeChannelLine(prefix, line))
        assertEquals(peer, SignupProtocol.decodeChannelPeer(prefix, line))
        assertNull(SignupProtocol.decodeChannelPeer(prefix, SignupProtocol.encodeChannelLine(prefix, 3L, frame)))
    }

    @Test
    fun theHelloLaneIsItsOwnReservedMuxPort() {
        assertTrue(PortMux.isAllowedPort(GatewayContract.PORT_HELLO))
        assertEquals(
            GatewayContract.PORT_HELLO,
            PortMux.decodePort(PortMux.encodeHeader(GatewayContract.PORT_HELLO)),
        )
        val reserved = setOf(GatewayContract.PORT_KEEPALIVE, GatewayContract.PORT_SIGNUP, GatewayContract.PORT_HELLO)
        assertEquals(3, reserved.size)
        assertFalse(GatewayContract.PORT_HELLO in setOf(GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD))
    }

    // ---------------------------------------------------------- logon peek

    /** An AUTH_LOGON_CHALLENGE as the client sends it (the layout all
     * three game versions share). */
    private fun challenge(name: String, cmd: Int = 0x00): ByteArray {
        val header = ByteArray(33)
        header[0] = cmd.toByte()
        header[1] = 0x08
        return header + byteArrayOf(name.length.toByte()) + name.toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun theAccountNameIsReadFromTheLogonChallenge() {
        assertEquals(LogonPeek.Result.Account("WANDERER"), LogonPeek.parse(challenge("WANDERER")))
        assertEquals(LogonPeek.Result.Account("WANDERER"), LogonPeek.parse(challenge("wanderer", cmd = 0x02)))
        assertEquals(LogonPeek.Result.NeedMore, LogonPeek.parse(challenge("WANDERER").copyOf(20)))
        assertEquals(LogonPeek.Result.NotALogon, LogonPeek.parse(byteArrayOf(0x10, 0, 0)))
        assertEquals(LogonPeek.Result.NotALogon, LogonPeek.parse(challenge("BAD NAME")))
    }

    @Test
    fun theAccumulatorAnswersOnceAcrossSplitChunks() {
        val bytes = challenge("WANDERER")
        val peek = LogonPeek.Accumulator()
        assertNull(peek.feed(bytes.copyOfRange(0, 10)))
        assertNull(peek.feed(bytes.copyOfRange(10, 36)))
        assertEquals("WANDERER", peek.feed(bytes.copyOfRange(36, bytes.size)))
        assertNull("one answer per connection", peek.feed(bytes))
        val other = LogonPeek.Accumulator()
        assertNull(other.feed(byteArrayOf(0x10)))
        assertNull("not a logon: never looks again", other.feed(bytes))
    }
}
