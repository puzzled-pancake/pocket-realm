package com.pocketrealm.supervisor

import com.pocketrealm.server.FriendProvisionProtocol
import com.pocketrealm.server.ServerRuntimeContract
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * The host-side provisioning TCP listener (design-remote-accounts §6): a
 * small server IN THE HOST APP PROCESS — never in realmd, never in the
 * world — serving exactly one purpose: receiving a friend's
 * `{inviteId, token, username, sHex, vHex}` request and moving the invite
 * row to SUBMITTED for the host's approval.
 *
 * Lifecycle (§6, the round-1 gap this closes): the listener is a property
 * of a HOSTING SESSION, not of the app. It starts when a start with friend
 * access enabled reaches WORLD_READY and stops with the realm (the owning
 * [FriendAccessController] drives start/stop from the supervisor state).
 * With friend access off it is never bound — the closed state refuses
 * everything; nothing listens on loopback either.
 *
 * Exposure invariant (§6): enabling friend access adds exactly ONE socket
 * to the LAN host's posture (design-lan's "only 3724 and 8085" holds while
 * it is off). The socket binds the hosting endpoint address — the same
 * address realmd binds, never 0.0.0.0 — on [ServerRuntimeContract.PROVISION_PORT].
 *
 * Protocol (§6): length-prefixed UTF-8 JSON ([FriendProvisionProtocol]),
 * ONE request per connection, a hard 4 KiB request cap and a short read
 * deadline. v1 auth is the bearer invite token; v1 confidentiality is the
 * LAN/tunnel itself (documented assumption, §2; TLS-with-pinned-fingerprint
 * is the v2 upgrade).
 *
 * Refusal oracle rule (§5): unknown token, expired, burned, malformed, and
 * rate-locked all get the byte-identical [FriendProvisionProtocol.REFUSED_RESPONSE].
 */
// TooManyFunctions: the listener owns the §6 lifecycle (start/stop/state)
// plus the one-request protocol (accept/serve/serveOne/answerPoll/refuse/
// answer) — one verb per design rule, the RuntimeBackend suppress pattern.
// TooGenericExceptionCaught: the per-connection guard converts ANY request
// failure to a close, never a listener death; the accept loop dies loudly
// (Failed) on a live-socket accept failure, never silently (§6).
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount")
class ProvisioningListener(
    private val store: FriendInviteStore,
    private val rateLimiter: ProvisionRateLimiter,
    private val bindAddress: String,
    private val port: Int = ServerRuntimeContract.PROVISION_PORT,
    private val logWarning: (String) -> Unit = {},
    private val logInfo: (String) -> Unit = {},
) {
    sealed class ListenerState {
        data object Off : ListenerState()

        data class Listening(val address: String, val port: Int) : ListenerState() {
            override fun toString(): String = "$address:$port"
        }

        data class Failed(val reason: String) : ListenerState()
    }

    private val state = AtomicReference<ListenerState>(ListenerState.Off)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Serializes [start] against [stop]: without it, a stop landing
     * between start()'s bind and its state-set leaves state=Listening on a
     * closed socket, and two concurrent starts can both bind before either
     * publishes. */
    private val lifecycleLock = Any()

    fun currentState(): ListenerState = state.get()

    /** Binds the listener. Returns the resulting state (also observable via
     * [currentState]); a bind failure lands in [ListenerState.Failed] — the
     * UI must show it, never fake listening (§6 exposure invariant). */
    fun start(): ListenerState = synchronized(lifecycleLock) {
        if (state.get() is ListenerState.Listening) return state.get()
        stopLocked()
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, port))
            }
        } catch (failure: java.io.IOException) {
            val failed = ListenerState.Failed(failure.message ?: failure.javaClass.simpleName)
            state.set(failed)
            logWarning("provisioning listener failed to bind $bindAddress:$port — ${failed.reason}")
            return failed
        }
        serverSocket = socket
        // The OBSERVED bound port, never the constructor's: a port-0 (test/
        // ephemeral) bind reports the port the OS actually assigned.
        val boundPort = socket.localPort
        state.set(ListenerState.Listening(bindAddress, boundPort))
        val worker = Thread({ acceptLoop(socket) }, "friend-provision-listener")
        worker.isDaemon = true
        worker.start()
        acceptThread = worker
        logInfo("provisioning listener bound $bindAddress:$boundPort (friend access on)")
        return state.get()
    }

    /** Stops the listener and releases the socket. Idempotent. */
    fun stop() = synchronized(lifecycleLock) { stopLocked() }

    private fun stopLocked() {
        state.set(ListenerState.Off)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (state.get() is ListenerState.Listening) {
            val client = try {
                socket.accept()
            } catch (failure: java.io.IOException) {
                failIfStillLive(socket, failure.message ?: failure.javaClass.simpleName)
                return
            }
            // The concurrency gate (§6 layer 1): over capacity → the
            // identical refusal blob, then close.
            if (!rateLimiter.tryAcquireConnection()) {
                answerAndClose(client, FriendProvisionProtocol.REFUSED_RESPONSE)
                continue
            }
            Thread({ safelyServe(client) }, "friend-provision-request").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** An accept failure is a deliberate stop ONLY when this loop's socket
     * was already swapped out under [lifecycleLock] ([stopLocked] clears
     * Listening before it closes, and a superseding [start] installs a new
     * socket). A socket that is still the live one failed on its own —
     * EMFILE and friends arrive as SocketException too — and the loop must
     * die loudly: [ListenerState.Failed], never a silent Listening over a
     * dead accept loop (§6 exposure invariant). */
    private fun failIfStillLive(socket: ServerSocket, reason: String) {
        synchronized(lifecycleLock) {
            val current = state.get()
            if (current !is ListenerState.Listening || serverSocket !== socket) return
            state.set(ListenerState.Failed(reason))
            serverSocket = null
        }
        runCatching { socket.close() }
        logWarning("provisioning listener accept failed — $reason")
    }

    private fun safelyServe(client: Socket) {
        try {
            serve(client)
        } catch (failure: Throwable) {
            logWarning("provisioning request failed: ${failure.message ?: failure.javaClass.simpleName}")
            runCatching { client.close() }
        } finally {
            rateLimiter.releaseConnection()
        }
    }

    private fun serve(client: Socket) {
        val sourceIp = runCatching {
            (client.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: ""
        }.getOrDefault("")
        try {
            client.soTimeout = READ_DEADLINE_MS
            client.use { socket -> serveOne(socket, sourceIp) }
        } finally {
            runCatching { client.close() }
        }
    }

    /** One request on one connection (the protocol's whole lifetime). */
    private fun serveOne(socket: Socket, sourceIp: String) {
        if (sourceIp.isNotEmpty() && rateLimiter.isLocked(sourceIp)) {
            answer(socket, FriendProvisionProtocol.REFUSED_RESPONSE)
            return
        }
        val payload = FriendProvisionProtocol.readFrame(
            socket.getInputStream(),
            FriendProvisionProtocol.MAX_REQUEST_BYTES,
        )
        val request = payload?.let { FriendProvisionProtocol.decodeRequest(it) }
        if (request == null) {
            refuse(socket, sourceIp)
            return
        }
        val inviteId = request.optString("inviteId")
        val token = request.optString("token")
        when (
            store.submit(
                inviteId, token, request.optString("username"),
                request.optString("sHex"), request.optString("vHex"), sourceIp,
            )
        ) {
            is FriendInviteStore.SubmitResult.Accepted -> {
                rateLimiter.recordSuccess(sourceIp)
                answer(socket, FriendProvisionProtocol.encodeSubmitted(inviteId))
            }
            FriendInviteStore.SubmitResult.Refused -> answerPoll(socket, request, inviteId, sourceIp)
        }
    }

    /** The friend's read-only decision poll (§5): still pending → the
     * submitted blob; approved → the ONE response carrying the accountId;
     * anything else → the identical refusal. */
    private fun answerPoll(socket: Socket, request: org.json.JSONObject, inviteId: String, sourceIp: String) {
        when (val poll = store.poll(inviteId, request.optString("token"))) {
            FriendInviteStore.PollResult.Pending ->
                answer(socket, FriendProvisionProtocol.encodeSubmitted(inviteId))
            is FriendInviteStore.PollResult.Provisioned -> answer(
                socket,
                FriendProvisionProtocol.encodeProvisioned(inviteId, request.optString("username"), poll.accountId),
            )
            FriendInviteStore.PollResult.Refused -> refuse(socket, sourceIp)
        }
    }

    /** Every refusal — the identical blob, then the counter. */
    private fun refuse(socket: Socket, sourceIp: String) {
        rateLimiter.recordFailure(sourceIp)
        answer(socket, FriendProvisionProtocol.REFUSED_RESPONSE)
    }

    private fun answer(socket: Socket, body: String) = answerAndClose(socket, body)

    private fun answerAndClose(socket: Socket, body: String) {
        try {
            socket.getOutputStream().apply {
                write(FriendProvisionProtocol.frame(body))
                flush()
            }
        } catch (_: java.io.IOException) {
            // the peer vanished mid-answer; the verdict is already durable
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** §6: "a short read deadline". One request per connection; a client
         * that cannot produce its frame inside this window is a refusal. */
        internal const val READ_DEADLINE_MS = 5_000
    }
}
