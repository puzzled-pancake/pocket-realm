package com.pocketrealm.supervisor

import com.pocketrealm.server.FriendProvisionProtocol
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The lifecycle pins for the host-side provisioning listener: the accept
 * loop dies LOUDLY — a live-socket accept failure (EMFILE and friends arrive
 * as SocketException on a healthy ServerSocket) publishes
 * [ProvisioningListener.ListenerState.Failed], so the hub/UI never shows
 * Listening over a dead provisioning socket — while a deliberate stop stays
 * the quiet Off it always was.
 */
class ProvisioningListenerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sHex = "aa".repeat(32)
    private val vHex = "bb".repeat(32)

    private fun listener(dir: File, warnings: MutableList<String> = mutableListOf()): ProvisioningListener {
        val store = FriendInviteStore(dir)
        return ProvisioningListener(
            store = store,
            rateLimiter = ProvisionRateLimiter(store),
            bindAddress = "127.0.0.1",
            port = 0,
            logWarning = { warnings += it },
        )
    }

    /** The listener's private ServerSocket — the only portable way to make
     * accept() fail on an otherwise-live listener (no stop(), so the
     * published state is still Listening when the loop wakes). */
    private fun serverSocketBehind(listener: ProvisioningListener): ServerSocket {
        val field = ProvisioningListener::class.java.getDeclaredField("serverSocket")
        field.isAccessible = true
        return field.get(listener) as ServerSocket
    }

    private fun awaitTrue(message: String, deadlineMs: Long = 5_000, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }

    @Test
    fun aLiveSocketAcceptFailurePublishesFailedNeverSilentListening() {
        val warnings = mutableListOf<String>()
        val listener = listener(folder.newFolder(), warnings)
        assertTrue(listener.start() is ProvisioningListener.ListenerState.Listening)

        serverSocketBehind(listener).close()

        awaitTrue("an accept failure on a live socket must publish Failed, not silent Listening") {
            listener.currentState() is ProvisioningListener.ListenerState.Failed
        }
        assertTrue("the accept failure must warn", warnings.any { it.contains("accept failed") })

        listener.stop()
        assertEquals(ProvisioningListener.ListenerState.Off, listener.currentState())
    }

    @Test
    fun aDeliberateStopStaysQuietOffNeverFailed() {
        val warnings = mutableListOf<String>()
        val listener = listener(folder.newFolder(), warnings)
        assertTrue(listener.start() is ProvisioningListener.ListenerState.Listening)
        listener.stop()
        assertEquals(ProvisioningListener.ListenerState.Off, listener.currentState())
        // Give a racing accept thread time to (wrongly) publish over the stop.
        Thread.sleep(250)
        assertEquals(
            "stop() is a quiet teardown: no Failed, no accept warning",
            ProvisioningListener.ListenerState.Off,
            listener.currentState(),
        )
        assertTrue(warnings.none { it.contains("accept failed") })
    }

    @Test
    fun failedIsRecoverableOnTheNextStart() {
        val listener = listener(folder.newFolder())
        assertTrue(listener.start() is ProvisioningListener.ListenerState.Listening)
        serverSocketBehind(listener).close()
        awaitTrue("precondition: the accept failure published Failed") {
            listener.currentState() is ProvisioningListener.ListenerState.Failed
        }
        assertTrue(
            "a new hosting session must be able to rebind after Failed",
            listener.start() is ProvisioningListener.ListenerState.Listening,
        )
        listener.stop()
    }

    @Test
    fun oneRequestPerConnectionStillServesTheProtocol() {
        val dir = folder.newFolder()
        val store = FriendInviteStore(dir)
        val listener = ProvisioningListener(
            store = store,
            rateLimiter = ProvisionRateLimiter(store),
            bindAddress = "127.0.0.1",
            port = 0,
        )
        val listening = listener.start() as ProvisioningListener.ListenerState.Listening
        val created = store.create("for Dave") as FriendInviteStore.CreateResult.Created

        Socket().use { client ->
            client.connect(InetSocketAddress("127.0.0.1", listening.port), 2_000)
            client.soTimeout = 2_000
            client.getOutputStream().apply {
                write(
                    FriendProvisionProtocol.encodeRequest(
                        created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex,
                    ),
                )
                flush()
            }
            val response = FriendProvisionProtocol.readFrame(
                client.getInputStream(),
                FriendProvisionProtocol.MAX_REQUEST_BYTES,
            )
            val verdict = JSONObject(String(requireNotNull(response), Charsets.UTF_8))
            assertTrue(verdict.optBoolean("ok"))
            assertEquals(FriendProvisionProtocol.STATE_SUBMITTED, verdict.optString("state"))
            assertEquals(created.inviteId, verdict.optString("inviteId"))
        }
        assertEquals(FriendInviteStore.State.SUBMITTED, store.rows().single().state)
        listener.stop()
    }
}
