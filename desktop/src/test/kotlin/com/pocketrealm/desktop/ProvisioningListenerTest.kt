package com.pocketrealm.desktop

import com.pocketrealm.server.FriendProvisionProtocol
import com.pocketrealm.supervisor.FriendInviteStore
import com.pocketrealm.supervisor.ProvisionRateLimiter
import com.pocketrealm.supervisor.ProvisioningListener
import java.net.Socket
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The provisioning listener end-to-end on loopback (design-remote-accounts
 * the real [ProvisioningListener] over a real socket, driven by the
 * real shared [FriendAccessClient]. Covers the framing, the refusal-oracle
 * rule, the SUBMITTED → poll → PROVISIONED arc, the rate-limit lockout, and
 * the lifecycle state surface.
 */
class ProvisioningListenerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: FriendInviteStore
    private lateinit var rateLimiter: ProvisionRateLimiter
    private lateinit var listener: ProvisioningListener

    @Before
    fun setUp() {
        store = FriendInviteStore(folder.newFolder("friend-access"))
        rateLimiter = ProvisionRateLimiter(store)
        // Port 0 = an ephemeral loopback bind; the test reads the actual port.
        listener = ProvisioningListener(store, rateLimiter, "127.0.0.1", port = 0)
        listener.start()
    }

    @After
    fun tearDown() {
        listener.stop()
    }

    private val sHex = "aa".repeat(32)
    private val vHex = "bb".repeat(32)

    private fun boundPort(): Int = (listener.currentState() as ProvisioningListener.ListenerState.Listening).port

    private fun sendRaw(payload: ByteArray): String =
        Socket("127.0.0.1", boundPort()).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write(payload); flush() }
            val response = FriendProvisionProtocol.readFrame(
                socket.getInputStream(),
                FriendProvisionProtocol.MAX_RESPONSE_BYTES,
            )
            checkNotNull(response) { "the listener never answered" }
            String(response, Charsets.UTF_8)
        }

    private fun requestJson(inviteId: String, token: String, username: String, s: String, v: String): ByteArray {
        val body = JSONObject()
            .put("schema", 1)
            .put("inviteId", inviteId)
            .put("token", token)
            .put("username", username)
            .put("sHex", s)
            .put("vHex", v)
            .toString()
        return FriendProvisionProtocol.frame(body)
    }

    @Test
    fun theListenerBindsAndReportsItsState() {
        val state = listener.currentState()
        assertTrue("expected Listening, got $state", state is ProvisioningListener.ListenerState.Listening)
        assertEquals("127.0.0.1", (state as ProvisioningListener.ListenerState.Listening).address)
    }

    @Test
    fun aValidSubmissionAnswersSubmittedAndMovesTheRow() {
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        val answer = sendRaw(requestJson(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex))
        assertEquals(FriendProvisionProtocol.encodeSubmitted(created.inviteId), answer)
        assertEquals(FriendInviteStore.State.SUBMITTED, store.rows().single().state)
        assertEquals("127.0.0.1", store.rows().single().sourceIp)
    }

    @Test
    fun everyRefusalIsTheIdenticalBlob() {
        val unknown = sendRaw(requestJson("FRD-NOPE", "whatever", "Dave", sHex, vHex))
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        val wrongToken = sendRaw(requestJson(created.inviteId, "not-the-token", "Dave", sHex, vHex))
        val badVerifier = sendRaw(
            requestJson(created.inviteId, created.tokenBase64Url, "Dave", "zz".repeat(32), vHex),
        )
        assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, unknown)
        assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, wrongToken)
        assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, badVerifier)
    }

    @Test
    fun anOversizeOrMalformedFrameIsTheIdenticalRefusal() {
        val oversize = ByteArray(4 + FriendProvisionProtocol.MAX_REQUEST_BYTES + 1)
        oversize[3] = ((FriendProvisionProtocol.MAX_REQUEST_BYTES + 1) ushr 8).toByte()
        oversize[2] = ((FriendProvisionProtocol.MAX_REQUEST_BYTES + 1) and 0xFF).toByte()
        assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, sendRaw(oversize))

        val garbage = FriendProvisionProtocol.frame("this is not json")
        assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, sendRaw(garbage))
    }

    @Test
    fun approveThenPollRevealsTheAccountIdOnce() {
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        sendRaw(requestJson(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex))

        // The poll before approval is the submitted blob (still no accountId).
        val pending = sendRaw(requestJson(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex))
        assertEquals(FriendProvisionProtocol.encodeSubmitted(created.inviteId), pending)

        val verifier = checkNotNull(store.markApproved(created.inviteId))
        store.markProvisioned(created.inviteId, accountId = 21L)

        val revealed = sendRaw(
            requestJson(created.inviteId, created.tokenBase64Url, "Dave", verifier.sHex, verifier.vHex),
        )
        val verdict = JSONObject(revealed)
        assertTrue(verdict.optBoolean("ok"))
        assertEquals("PROVISIONED", verdict.optString("state"))
        assertEquals(21L, verdict.optLong("accountId"))

        // The token burned on the reveal: further polls refuse identically.
        assertEquals(
            FriendProvisionProtocol.REFUSED_RESPONSE,
            sendRaw(requestJson(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex)),
        )
    }

    @Test
    fun aRejectedInvitePollsAsTheIdenticalRefusal() {
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        sendRaw(requestJson(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex))
        store.reject(created.inviteId)
        assertEquals(
            FriendProvisionProtocol.REFUSED_RESPONSE,
            sendRaw(requestJson(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex)),
        )
    }

    @Test
    fun fiveBadAttemptsLockTheSourceForTheWindow() {
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        repeat(ProvisionRateLimiter.MAX_FAILURES) {
            assertEquals(
                FriendProvisionProtocol.REFUSED_RESPONSE,
                sendRaw(requestJson(created.inviteId, "bad-token", "Dave", sHex, vHex)),
            )
        }
        // Locked: even the CORRECT token now gets the refusal from this source.
        assertEquals(
            FriendProvisionProtocol.REFUSED_RESPONSE,
            sendRaw(requestJson(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex)),
        )
        assertTrue(store.rateRows().first { it.ip == "127.0.0.1" }.locked)
    }

    @Test
    fun stopReleasesTheSocket() {
        listener.stop()
        assertEquals(ProvisioningListener.ListenerState.Off, listener.currentState())
        // A second stop is a no-op, and a restart rebinds.
        listener.stop()
        assertTrue(listener.start() is ProvisioningListener.ListenerState.Listening)
    }

    @Test
    fun tornStartAndStopNeverLeakTheSocketOrTheState() {
        // The torn-lifecycle pin (design-remote-accounts exposure
        // invariant): start() and stop() serialize on the listener's
        // lifecycle lock, so a stop landing between a bind and its state-set
        // can never leave state=Listening on a closed socket, and the socket
        // never outlives the last stop. The owner's final stop is always the
        // last word, and a fresh exclusive bind proves the release (the
        // listener itself binds with reuseAddress; this probe must not).
        val probe = java.net.ServerSocket(0)
        val port = probe.localPort
        probe.close()

        repeat(16) { round ->
            val torn = ProvisioningListener(store, rateLimiter, "127.0.0.1", port = port)
            val fire = java.util.concurrent.CountDownLatch(1)
            val startState = java.util.concurrent.atomic.AtomicReference<ProvisioningListener.ListenerState>()
            val starter = Thread {
                fire.await(5, java.util.concurrent.TimeUnit.SECONDS)
                startState.set(torn.start())
            }
            val stopper = Thread {
                fire.await(5, java.util.concurrent.TimeUnit.SECONDS)
                torn.stop()
            }
            starter.start()
            stopper.start()
            fire.countDown()
            starter.join(5_000)
            stopper.join(5_000)
            // The owner's teardown leg: whatever the race produced, the last
            // word is Off and the socket is gone.
            torn.stop()
            assertTrue(
                "state must end Off (round $round raced to $startState)",
                torn.currentState() is ProvisioningListener.ListenerState.Off,
            )
        }
        // An exclusive re-bind (no SO_REUSEADDR) fails while any listener
        // socket from the rounds above is still open.
        val rebind = java.net.ServerSocket(port)
        rebind.close()
    }
}
