package com.pocketrealm.supervisor

import com.pocketrealm.server.FriendProvisionProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.nio.file.Files

/**
 * R2 adversarial breaker (android-core round 1): the provisioning listener
 * under an active adversary — real sockets against the full wired cluster
 * ([ProvisioningListener] + [FriendInviteStore] + [ProvisionRateLimiter]).
 *
 * Attack classes:
 *  - token brute force → lockout, and the LOCKED answer must be the byte
 *    identical refusal blob (no oracle between unknown/expired/locked);
 *  - the lockout must survive a full listener+store+limiter restart;
 *  - a valid submission and its decision poll must be indistinguishable
 *    while pending (the §5 oracle rule);
 *  - the one PROVISIONED reveal must burn the token on first use;
 *  - malformed frames must count as failures and never half-serve;
 *  - an expired decision window must collapse into the same refusal.
 */
class R2ProvisioningAdversaryTest {

    // ------------------------------------------------------------------
    // Wire adversary (real loopback sockets)
    // ------------------------------------------------------------------

    @Test
    fun `five bad tokens lock the source and every answer stays the identical blob`() {
        host().use { host ->
            val (inviteId, _) = host.createInvite()
            repeat(5) { attempt ->
                val answer = host.ask(host.request(inviteId, "wrong-token-$attempt"))
                assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, answer?.decodeText())
            }
            // Locked at the serve gate now — and STILL the identical blob.
            val locked = host.ask(host.request(inviteId, "wrong-token-late"))
            assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, locked?.decodeText())
            // Even the REAL token is refused while the source is locked —
            // and indistinguishably so.
            val realToken = host.ask(host.request(inviteId, host.token))
            assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, realToken?.decodeText())
            assertEquals(0, host.limiter.activeConnections())
        }
    }

    @Test
    fun `the lockout survives a full listener store and limiter restart`() {
        val dir = tempDir()
        try {
            runHost(dir).use { first ->
                val (inviteId, _) = first.createInvite()
                repeat(5) { attempt ->
                    first.ask(first.request(inviteId, "spray-$attempt"))
                }
            }
            // Fresh store + limiter + listener over the SAME durable
            // directory: the persisted lock row must hold (no amnesty).
            runHost(dir).use { second ->
                val rows = second.store.rateRows()
                assertTrue("lock row persisted", rows.any { it.locked })
                val answer = second.ask(second.request("FRD-XXXXXX", "anything"))
                assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, answer?.decodeText())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a pending decision poll is byte-identical to the first submit answer`() {
        host().use { host ->
            val (inviteId, _) = host.createInvite()
            val first = host.ask(host.request(inviteId, host.token))
            val poll = host.ask(host.request(inviteId, host.token))
            val expected = FriendProvisionProtocol.encodeSubmitted(inviteId)
            assertEquals(expected, first?.decodeText())
            assertEquals(expected, poll?.decodeText())
        }
    }

    @Test
    fun `the provisioned reveal answers once then burns into the identical refusal`() {
        host().use { host ->
            val (inviteId, _) = host.createInvite()
            host.ask(host.request(inviteId, host.token))
            host.store.markApproved(inviteId)
            host.store.markProvisioned(inviteId, 42L)
            val reveal = host.ask(host.request(inviteId, host.token))
            val json = JSONObject(reveal?.decodeText() ?: "")
            assertTrue(json.optBoolean("ok"))
            assertEquals("PROVISIONED", json.optString("state"))
            assertEquals(42L, json.optLong("accountId"))
            // Second poll: the burned token lands in the same blob as any
            // other refusal.
            val burned = host.ask(host.request(inviteId, host.token))
            assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, burned?.decodeText())
        }
    }

    @Test
    fun `malformed frames count as failures and never half-serve`() {
        host().use { host ->
            val (inviteId, _) = host.createInvite()
            // Oversized length prefix, zero length, and a truncated body —
            // each connection must answer the refusal blob and count.
            val oversized = byteArrayOf(0x7F, -1, -1, -1) + "x".toByteArray()
            val zero = ByteArray(4)
            val truncated = frameOf("12345678".toByteArray()).copyOfRange(0, 6)
            for (attack in listOf(oversized, zero, truncated)) {
                assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, host.ask(attack)?.decodeText())
            }
            // After MAX_FAILURES such failures the source is locked, and a
            // fully valid request is refused like everything else.
            host.ask(host.request(inviteId, "another-guess-1"))
            host.ask(host.request(inviteId, "another-guess-2"))
            val validAfterLock = host.ask(host.request(inviteId, host.token))
            assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, validAfterLock?.decodeText())
        }
    }

    @Test
    fun `an expired decision window collapses into the identical refusal`() {
        var now = 1_770_000_000_000L
        val dir = tempDir()
        try {
            val store = FriendInviteStore(dir, clock = { now })
            runHost(dir, store).use { host ->
                val (inviteId, _) = host.createInvite()
                assertEquals(
                    FriendProvisionProtocol.encodeSubmitted(inviteId),
                    host.ask(host.request(inviteId, host.token))?.decodeText(),
                )
                now += 16L * 60L * 1_000L // past the 15-minute decision window
                assertEquals(
                    FriendProvisionProtocol.REFUSED_RESPONSE,
                    host.ask(host.request(inviteId, host.token))?.decodeText(),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Protocol-level fuzz (decode + framing, no sockets)
    // ------------------------------------------------------------------

    @Test
    fun `decodeRequest refuses every off-shape payload`() {
        fun raw(schema: Int = 1, inviteId: String = "FRD-AAAAAA", token: String = "t",
                username: String = "friend", s: String = "aa".repeat(32), v: String = "bb".repeat(32)) =
            JSONObject().put("schema", schema).put("inviteId", inviteId).put("token", token)
                .put("username", username).put("sHex", s).put("vHex", v).toString().toByteArray()

        assertTrue(FriendProvisionProtocol.decodeRequest(raw()) != null) // control: decodes
        assertNull(FriendProvisionProtocol.decodeRequest(
            raw().decodeText().let { JSONObject(it).put("extra", 1).toString() }.toByteArray()))
        assertNull(FriendProvisionProtocol.decodeRequest(raw(schema = 2)))
        assertNull(FriendProvisionProtocol.decodeRequest(raw(username = "seventeenchars171")))
        assertNull(FriendProvisionProtocol.decodeRequest(raw(username = "friend@host")))
        assertNull(FriendProvisionProtocol.decodeRequest(raw(s = "aa".repeat(31) + "a")))
        assertNull(FriendProvisionProtocol.decodeRequest(raw(v = "BB".repeat(32)))) // uppercase hex
        assertNull(FriendProvisionProtocol.decodeRequest(raw(token = "")))
        assertNull(FriendProvisionProtocol.decodeRequest(ByteArray(FriendProvisionProtocol.MAX_REQUEST_BYTES + 1)))
        assertNull(FriendProvisionProtocol.decodeRequest("not json".toByteArray()))
    }

    @Test
    fun `readFrame refuses zero negative oversize and truncated prefixes`() {
        assertNull(FriendProvisionProtocol.readFrame(ByteArrayInputStream(ByteArray(4)), 64))
        assertNull(FriendProvisionProtocol.readFrame(
            ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1)), 64))
        assertNull(FriendProvisionProtocol.readFrame(
            ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x20, 0x00)), 64)) // 8192 > 64
        assertNull(FriendProvisionProtocol.readFrame(
            ByteArrayInputStream(frameOf("1234567890".toByteArray()).copyOfRange(0, 8)), 64))
        assertEquals("1234567890", FriendProvisionProtocol.readFrame(
            ByteArrayInputStream(frameOf("1234567890".toByteArray())), 64)!!.decodeText())
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("r2-provision").toFile()

    private class Host(
        dir: File,
        val store: FriendInviteStore = FriendInviteStore(dir),
    ) : AutoCloseable {
        val limiter = ProvisionRateLimiter(store)
        private val listener = ProvisioningListener(store, limiter, "127.0.0.1", port = 0)
        val port: Int = (listener.start() as ProvisioningListener.ListenerState.Listening).port
        var token: String = ""

        fun createInvite(): Pair<String, String> {
            val created = store.create("r2") as FriendInviteStore.CreateResult.Created
            token = created.tokenBase64Url
            return created.inviteId to created.tokenBase64Url
        }

        fun request(inviteId: String, token: String): ByteArray =
            FriendProvisionProtocol.encodeRequest(
                inviteId, token, "friendone", "aa".repeat(32), "bb".repeat(32),
            )

        fun ask(payload: ByteArray): ByteArray? = Socket("127.0.0.1", port).use { socket ->
            // The client must out-wait the listener's 5 s read deadline so a
            // late refusal (truncated-frame leg) still arrives.
            socket.soTimeout = 20_000
            socket.getOutputStream().apply { write(payload); flush() }
            FriendProvisionProtocol.readFrame(socket.getInputStream(), FriendProvisionProtocol.MAX_RESPONSE_BYTES)
        }

        override fun close() {
            listener.stop()
        }
    }

    private fun host(): Host = Host(tempDir())

    private fun runHost(dir: File, store: FriendInviteStore = FriendInviteStore(dir)): Host = Host(dir, store)

    private fun frameOf(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((payload.size ushr 24) and 0xFF)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun ByteArray.decodeText(): String = String(this, Charsets.UTF_8)
}
