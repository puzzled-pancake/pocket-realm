package com.pocketrealm.server

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provisioning wire (design-remote-accounts): framing, request
 * decode strictness, the refusal-oracle rule, and the friend client's
 * verdict mapping. Pure JVM.
 */
class FriendProvisionProtocolTest {
    private val sHex = "aa".repeat(32)
    private val vHex = "bb".repeat(32)

    @Test
    fun frameRoundTripCarriesThePayloadBehindALengthPrefix() {
        val payload = FriendProvisionProtocol
            .encodeRequest("INV-001", "tok", "Dave", sHex, vHex)
        assertTrue("framed size = 4-byte prefix + UTF-8 payload", payload.size > 4 + sHex.length)
        val decoded = FriendProvisionProtocol.readFrame(payload.inputStream(), 4_096)
        assertNotNull(decoded)
        assertEquals(
            "the decoded frame re-parses to the same request",
            "Dave",
            JSONObject(String(decoded!!, Charsets.UTF_8)).getString("username"),
        )
    }

    @Test
    fun oversizeAndTruncatedFramesAreRefusedAtTheRead() {
        val big = ByteArray(FriendProvisionProtocol.MAX_REQUEST_BYTES + 1) { 0x20 }
        val oversizeLength = FriendProvisionProtocol.MAX_REQUEST_BYTES + 1 // 4097 = 0x1001
        assertNull(
            "a frame over the cap never parses",
            FriendProvisionProtocol.readFrame(
                (
                    byteArrayOf(
                        (oversizeLength ushr 24).toByte(),
                        (oversizeLength ushr 16).toByte(),
                        (oversizeLength ushr 8).toByte(),
                        oversizeLength.toByte(),
                    ) + big
                    ).inputStream(),
                FriendProvisionProtocol.MAX_REQUEST_BYTES,
            ),
        )
        assertNull(
            "a truncated payload is not a frame",
            FriendProvisionProtocol.readFrame(
                byteArrayOf(0, 0, 0x01, 0, 0x7B).inputStream(),
                FriendProvisionProtocol.MAX_REQUEST_BYTES,
            ),
        )
    }

    @Test
    fun decodeAcceptsExactlyTheSchemaShape() {
        val request = FriendProvisionProtocol
            .encodeRequest("INV-001", "tok", "Dave", sHex, vHex)
        val decoded = FriendProvisionProtocol.readFrame(request.inputStream(), 4_096)!!
        val value = FriendProvisionProtocol.decodeRequest(decoded)
        assertNotNull(value)
        assertEquals("INV-001", value!!.getString("inviteId"))

        // One extra key, one missing key, a wrong schema — all malformed.
        val extra = JSONObject(String(decoded, Charsets.UTF_8)).put("extra", 1).toString()
        assertNull(FriendProvisionProtocol.decodeRequest(extra.toByteArray(Charsets.UTF_8)))
        val missing = JSONObject(String(decoded, Charsets.UTF_8)).remove("vHex").toString()
        assertNull(FriendProvisionProtocol.decodeRequest(missing.toByteArray(Charsets.UTF_8)))
        val wrongSchema = JSONObject(String(decoded, Charsets.UTF_8)).put("schema", 2).toString()
        assertNull(FriendProvisionProtocol.decodeRequest(wrongSchema.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun decodeRefusesNonRealmIdentitiesAndNonHexVerifiers() {
        fun raw(username: String, s: String, v: String): ByteArray = JSONObject()
            .put("schema", 1)
            .put("inviteId", "INV-001")
            .put("token", "tok")
            .put("username", username)
            .put("sHex", s)
            .put("vHex", v)
            .toString().toByteArray(Charsets.UTF_8)

        assertNull("non-alnum username is refused", FriendProvisionProtocol.decodeRequest(raw("da ve", sHex, vHex)))
        assertNull("over-16 username is refused", FriendProvisionProtocol.decodeRequest(raw("a".repeat(17), sHex, vHex)))
        assertNull("uppercase hex is refused", FriendProvisionProtocol.decodeRequest(raw("Dave", sHex.uppercase(), vHex)))
        assertNull("short verifier is refused", FriendProvisionProtocol.decodeRequest(raw("Dave", sHex, "bb".repeat(31))))
    }

    @Test
    fun theRefusalBlobIsTheStableIdenticalResponse() {
        // byte-identical for unknown token, expired, burned, and
        // rate-locked — pinned literally so an edit here is a reviewed event.
        assertEquals(
            "{\"schema\":1,\"ok\":false,\"error\":\"REQUEST_REFUSED\"}",
            FriendProvisionProtocol.REFUSED_RESPONSE,
        )
    }

    @Test
    fun verdictBlobsRoundTrip() {
        val submitted = JSONObject(FriendProvisionProtocol.encodeSubmitted("INV-001"))
        assertTrue(submitted.optBoolean("ok"))
        assertEquals("SUBMITTED", submitted.optString("state"))
        val provisioned = JSONObject(FriendProvisionProtocol.encodeProvisioned("INV-001", "Dave", 42L))
        assertTrue(provisioned.optBoolean("ok"))
        assertEquals("PROVISIONED", provisioned.optString("state"))
        assertEquals(42L, provisioned.optLong("accountId"))
    }

    @Test
    fun srpHexAcceptsOnlySixtyFourLowercaseHexChars() {
        assertTrue(FriendProvisionProtocol.isSrpHex("ab".repeat(32)))
        assertTrue(!FriendProvisionProtocol.isSrpHex("AB".repeat(32)))
        assertTrue(!FriendProvisionProtocol.isSrpHex("ab".repeat(31)))
        assertTrue(!FriendProvisionProtocol.isSrpHex("zz".repeat(32)))
    }
}
