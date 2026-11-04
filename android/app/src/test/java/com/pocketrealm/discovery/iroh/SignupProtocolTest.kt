package com.pocketrealm.discovery.iroh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignupProtocolTest {

    private val s = "1a".repeat(32)
    private val v = "2b".repeat(32)
    private val request = SignupProtocol.Request("Wanderer7", s, v)

    @Test
    fun theRequestCarriesOnlyTheNameAndTheVerifierAndRoundTrips() {
        val line = SignupProtocol.encodeRequest(request)
        assertTrue(line.endsWith("\n"))
        assertEquals(setOf("schema", "username", "sHex", "vHex"), org.json.JSONObject(line).keys().asSequence().toSet())
        assertEquals(request, SignupProtocol.decodeRequest(line))
    }

    @Test
    fun malformedRequestsDecodeToNull() {
        val good = org.json.JSONObject(SignupProtocol.encodeRequest(request).trim())
        assertNull(SignupProtocol.decodeRequest(good.put("password", "x").toString()))
        val wrongSchema = org.json.JSONObject(good.toString()).apply {
            remove("password")
            put("schema", 2)
        }
        assertNull(SignupProtocol.decodeRequest(wrongSchema.toString()))
        for (name in listOf("", "has space", "x".repeat(17), "näme", "a-b")) {
            val bad = org.json.JSONObject(SignupProtocol.encodeRequest(request).trim()).put("username", name)
            assertNull("name '$name' must refuse", SignupProtocol.decodeRequest(bad.toString()))
        }
        val upper = org.json.JSONObject(SignupProtocol.encodeRequest(request).trim()).put("vHex", v.uppercase())
        assertNull(SignupProtocol.decodeRequest(upper.toString()))
        assertNull(SignupProtocol.decodeRequest("not json"))
        assertNull(SignupProtocol.decodeRequest("x".repeat(SignupProtocol.MAX_FRAME_BYTES + 1)))
        assertTrue(runCatching { SignupProtocol.Request("ok", "zz", v) }.isFailure)
    }

    @Test
    fun repliesRoundTripWithABoundedDetail() {
        val reply = SignupProtocol.Reply(false, SignupProtocol.NAME_TAKEN, "x".repeat(500))
        val decoded = SignupProtocol.decodeReply(SignupProtocol.encodeReply(reply))!!
        assertFalse(decoded.ok)
        assertEquals(SignupProtocol.NAME_TAKEN, decoded.code)
        assertTrue(decoded.detail.length <= 200)
        assertNull(SignupProtocol.decodeReply("{}"))
    }

    @Test
    fun aFrameIsReadAcrossChunksAndBoundedInSize() = runBlocking {
        val chunks = ArrayDeque(listOf("{\"a\":".toByteArray(), "1}\nrest".toByteArray()))
        assertEquals("{\"a\":1}", SignupProtocol.readFrame { chunks.removeFirstOrNull() ?: ByteArray(0) })
        val endless = ByteArray(512) { 'x'.code.toByte() }
        assertNull(SignupProtocol.readFrame { endless })
        assertNull(SignupProtocol.readFrame { ByteArray(0) })
    }

    @Test
    fun theHostGateRefusesClosedFullOrDuplicateBeforeAnyWrite() {
        assertEquals(SignupProtocol.SIGNUP_CLOSED, SignupProtocol.gate(false, 0, emptyList(), request)?.code)
        assertEquals(SignupProtocol.CAP_REACHED, SignupProtocol.gate(true, 2, listOf("a", "b"), request)?.code)
        assertEquals(SignupProtocol.NAME_TAKEN, SignupProtocol.gate(true, 0, listOf("WANDERER7"), request)?.code)
        assertNull(SignupProtocol.gate(true, 0, listOf("other"), request))
        assertNull(SignupProtocol.gate(true, 3, listOf("a", "b"), request))
    }

    @Test
    fun provisionCodesMapToJoinerReplies() {
        assertTrue(SignupProtocol.fromProvisionCode(true, "ACCOUNT_CREATED", "x").ok)
        assertEquals(SignupProtocol.NAME_TAKEN, SignupProtocol.fromProvisionCode(false, "ACCOUNT_EXISTS", "x").code)
        assertEquals(SignupProtocol.NAME_INVALID, SignupProtocol.fromProvisionCode(false, "ACCOUNT_INVALID", "x").code)
        assertEquals(
            SignupProtocol.REALM_NOT_READY,
            SignupProtocol.fromProvisionCode(false, "WORLD_NOT_READY", "x").code,
        )
        assertEquals(SignupProtocol.FAILED, SignupProtocol.fromProvisionCode(false, "ACCOUNT_CONTROL_FAILED", "x").code)
    }

    @Test
    fun eachConnectionGetsAFewSpacedRequests() {
        var now = 0L
        val budget = SignupProtocol.ConnectionBudget { now }
        assertTrue(budget.tryAcquire())
        assertFalse("too soon", budget.tryAcquire())
        now += SignupProtocol.MIN_REQUEST_INTERVAL_MS
        assertTrue(budget.tryAcquire())
        now += SignupProtocol.MIN_REQUEST_INTERVAL_MS
        assertTrue(budget.tryAcquire())
        now += SignupProtocol.MIN_REQUEST_INTERVAL_MS * 10
        assertFalse("the per-connection allowance is spent", budget.tryAcquire())
    }

    @Test
    fun channelLinesCorrelateById() {
        val line = SignupProtocol.encodeChannelLine(
            GatewayContract.SIGNUP_REQUEST_LINE_PREFIX, 7L, SignupProtocol.encodeRequest(request),
        )
        assertFalse(line.contains("\n"))
        val (id, frame) = SignupProtocol.decodeChannelLine(GatewayContract.SIGNUP_REQUEST_LINE_PREFIX, line)!!
        assertEquals(7L, id)
        assertEquals(request, SignupProtocol.decodeRequest(frame))
        assertNull(SignupProtocol.decodeChannelLine(GatewayContract.SIGNUP_REPLY_LINE_PREFIX, line))
    }
}
