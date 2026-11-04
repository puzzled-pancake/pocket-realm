package com.pocketrealm.discovery.iroh

import com.pocketrealm.log.AppLog
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.presetN0
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The in-app internet HOST (the desktop gateway's HostGateway twin, run
 * in-process inside :supervisor): one iroh endpoint with the realm's
 * persisted secret key accepts joiners on [GatewayContract.ALPN] and pipes
 * each muxed stream to the hosting session's realmd (3724) or world (8085).
 * The host never parses game bytes — the realm-list rewrite lives only on
 * the joiner side. Port 0 is the joiner's keep-alive lane: one byte echoed.
 *
 * The node id and home relay (the listing's join descriptor) are exposed
 * only while the endpoint is READY; a stop or failure drops them, so the
 * publisher never lists a gateway that is down.
 */
object InternetHostGateway {

    sealed interface State {
        data object Idle : State
        data class Starting(val detail: String) : State
        data class Ready(val descriptor: IrohJoinDescriptor) : State
        data class Failed(val code: String, val detail: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var session: Job? = null
    @Volatile private var sessionKey: Pair<String, String>? = null

    /** The host app's answer to one self sign-up request (null = the lane
     * answers SIGNUP_CLOSED). */
    @Volatile var signupHandler: (suspend (SignupProtocol.Request) -> SignupProtocol.Reply)? = null

    /** The sign-up answer that also gets the joiner's identity (the peer
     * is null when the joiner never said hello). Preferred over
     * [signupHandler] when set. */
    @Volatile var peerSignupHandler:
        (suspend (SignupProtocol.Request, JoinPeer?) -> SignupProtocol.Reply)? = null

    /** The hello answer (bans, the password proof and the player limit
     * live here). Null = every joiner is welcome and no hello is required;
     * set = game streams and sign-ups only run on a connection this
     * handler welcomed. */
    @Volatile var admissionHandler:
        (suspend (JoinPeer, JoinHello.Request) -> SignupProtocol.Reply)? = null

    /** Logins seen on the realm lane and connections that ended. */
    @Volatile var peerEvents: ((JoinHello.PeerEvent) -> Unit)? = null

    /** Welcomed connections and who they are (for closing a banned one). */
    private val livePeers = java.util.concurrent.ConcurrentHashMap<Connection, JoinPeer>()

    /** Closes every live connection of this device or node (a ban takes
     * effect at once: the player's game drops). */
    fun disconnect(deviceToken: String?, nodeHex: String?) {
        livePeers.entries
            .filter { entry -> entry.value.deviceToken == deviceToken || entry.value.nodeHex == nodeHex }
            .forEach { entry ->
                AppLog.i(TAG, "closing a banned joiner's connection")
                runCatching { entry.key.close(0L, "banned".toByteArray()) }
            }
    }

    /** Live joiner connections (status copy only). */
    private val connections = AtomicInteger(0)
    val connectedPeers: Int get() = connections.get()

    /** The listing descriptor while READY, else null. */
    fun descriptor(): IrohJoinDescriptor? = (_state.value as? State.Ready)?.descriptor

    val active: Boolean get() = session?.isActive == true

    /**
     * Brings the endpoint up (or keeps the running one when the secret and
     * host address are unchanged) and suspends until READY or FAILED,
     * bounded by [READY_TIMEOUT_MS].
     */
    suspend fun ensure(scope: CoroutineScope, secretHex: String, hostAddress: String): State {
        val key = secretHex to hostAddress
        if (active && sessionKey == key) {
            return withTimeoutOrNull(READY_TIMEOUT_MS) {
                state.first { it is State.Ready || it is State.Failed }
            } ?: _state.value
        }
        stop()
        require(HostGatewayPolicy.isValidSecretHex(secretHex)) { "malformed transport secret" }
        _state.value = State.Starting("Opening the internet endpoint")
        sessionKey = key
        session = scope.launch(Dispatchers.IO + SupervisorJob()) { runSession(secretHex, hostAddress) }
        return withTimeoutOrNull(READY_TIMEOUT_MS) {
            state.first { it is State.Ready || it is State.Failed }
        } ?: run {
            stop()
            fail(GatewayContract.ERR_DIAL_FAILED,
                "the endpoint did not reach its relay within ${READY_TIMEOUT_MS / MILLIS_PER_SECOND}s " +
                    "(no internet? relay unreachable?)")
        }
    }

    /** Closes the endpoint and every joiner flow. Safe when idle. */
    fun stop() {
        session?.cancel()
        session = null
        sessionKey = null
        connections.set(0)
        livePeers.clear()
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    private fun fail(code: String, detail: String): State {
        val failed = State.Failed(code, detail)
        _state.value = failed
        AppLog.w(TAG, "internet host failed: $code: $detail")
        return failed
    }

    private suspend fun runSession(secretHex: String, hostAddress: String) {
        val flows = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var endpoint: Endpoint? = null
        try {
            val bound = Endpoint.bind(
                EndpointOptions(
                    // presetN0's pkarr publisher keeps its default relay-only
                    // address filter: no direct address is published.
                    preset = presetN0(),
                    secretKey = HostGatewayPolicy.hexToBytes(secretHex),
                    alpns = listOf(GatewayContract.ALPN.toByteArray()),
                    relayMode = RelayMode.defaultMode(),
                    bindAddr = null,
                    protocols = null,
                ),
            )
            endpoint = bound
            _state.value = State.Starting("Registering with a home relay")
            withTimeout(ONLINE_TIMEOUT_MS) { bound.online() }
            val nodeHex = HostGatewayPolicy.bytesToHex(bound.id().toBytes())
            val descriptor = HostGatewayPolicy.descriptorOrNull(nodeHex, bound.addr().relayUrl())
            if (descriptor == null) {
                fail(GatewayContract.ERR_DIAL_FAILED, "the endpoint has no usable home relay")
                return
            }
            _state.value = State.Ready(descriptor)
            AppLog.i(TAG, "internet host ready (node ${nodeHex.take(NODE_LOG_CHARS)}… via ${descriptor.relayHost()})")
            acceptLoop(bound, hostAddress, flows)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            fail(GatewayContract.ERR_DIAL_FAILED,
                "${failure.javaClass.simpleName}: ${failure.message ?: "endpoint failed"}")
        } finally {
            flows.cancel()
            endpoint?.let { ep ->
                runCatching { withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { ep.shutdown() } }
                runCatching { ep.close() }
            }
        }
    }

    private suspend fun acceptLoop(endpoint: Endpoint, hostAddress: String, flows: CoroutineScope) {
        val flowBudget = Semaphore(HostGatewayPolicy.MAX_CONCURRENT_FLOWS, true)
        val connectionBudget = Semaphore(HostGatewayPolicy.MAX_CONCURRENT_CONNECTIONS, true)
        while (true) {
            val incoming = try {
                endpoint.acceptNext() ?: break
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (endpoint.isClosed()) break
                AppLog.w(TAG, "accept error: ${failure.message}")
                continue
            }
            if (!connectionBudget.tryAcquire()) {
                AppLog.w(TAG, "connection budget exhausted — refusing a joiner")
                runCatching { incoming.refuse() }
                continue
            }
            flows.launch {
                try {
                    val conn = incoming.accept().connect()
                    connections.incrementAndGet()
                    try {
                        serveConnection(conn, hostAddress, flows, flowBudget)
                    } finally {
                        connections.decrementAndGet()
                        runCatching { conn.close(0L, ByteArray(0)) }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AppLog.i(TAG, "joiner handshake failed: ${failure.message}")
                } finally {
                    connectionBudget.release()
                }
            }
        }
    }

    /** Accepts this connection's streams until it closes or idles out. */
    private suspend fun serveConnection(
        conn: Connection,
        hostAddress: String,
        flows: CoroutineScope,
        flowBudget: Semaphore,
    ) {
        val lastStreamMs = AtomicLong(System.currentTimeMillis())
        val liveFlows = AtomicInteger(0)
        val signupBudget = SignupProtocol.ConnectionBudget()
        val nodeHex = runCatching { HostGatewayPolicy.bytesToHex(conn.remoteId().toBytes()) }.getOrNull()
        val peer = AtomicReference<JoinPeer?>(null)
        val onLogin: (String) -> Unit = { account ->
            peer.get()?.let { known ->
                peerEvents?.invoke(JoinHello.PeerEvent(JoinHello.EVENT_LOGIN, known, account))
            }
        }
        val reaper = flows.launch {
            while (isActive) {
                delay(IDLE_POLL_MS)
                if (liveFlows.get() == 0 &&
                    HostGatewayPolicy.idleExpired(lastStreamMs.get(), System.currentTimeMillis())
                ) {
                    AppLog.i(TAG, "reaping an idle joiner connection")
                    runCatching { conn.close(0L, ByteArray(0)) }
                    break
                }
            }
        }
        try {
            while (true) {
                val stream = try {
                    conn.acceptBi()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    break
                }
                lastStreamMs.set(System.currentTimeMillis())
                val header = runCatching { stream.recv().readExact(PortMux.HEADER_BYTES.toUInt()) }.getOrNull()
                when (val lane = HostGatewayPolicy.classify(header)) {
                    HostGatewayPolicy.Lane.KeepAlive -> flows.launch { echoKeepAlive(stream) }
                    HostGatewayPolicy.Lane.Signup ->
                        flows.launch { answerSignup(stream, signupBudget, peer.get()) }
                    HostGatewayPolicy.Lane.Hello -> flows.launch { answerHello(stream, conn, nodeHex, peer) }
                    is HostGatewayPolicy.Lane.Game -> if (admissionHandler != null && peer.get() == null) {
                        // A joiner the host has not welcomed (an older app,
                        // or a banned device) gets no game stream.
                        runCatching { stream.close() }
                    } else {
                        liveFlows.incrementAndGet()
                        startGameFlow(stream, lane.port, hostAddress, flows, flowBudget, onLogin) {
                            liveFlows.decrementAndGet()
                            lastStreamMs.set(System.currentTimeMillis())
                        } ?: liveFlows.decrementAndGet()
                    }
                    HostGatewayPolicy.Lane.Refused -> runCatching { stream.close() }
                }
            }
        } finally {
            reaper.cancel()
            livePeers.remove(conn)
            peer.get()?.let { left ->
                peerEvents?.invoke(JoinHello.PeerEvent(JoinHello.EVENT_LEAVE, left, null))
            }
        }
    }

    /** One hello in, one answer out; a welcomed peer is remembered on the
     * connection. A refused joiner gets no game access (its own app ends
     * the join on the refusal). */
    private suspend fun answerHello(
        stream: BiStream,
        conn: Connection,
        nodeHex: String?,
        peer: AtomicReference<JoinPeer?>,
    ) {
        runCatching {
            stream.use { bi ->
                val recv = bi.recv()
                val frame = withTimeoutOrNull(JoinHello.TIMEOUT_MS) {
                    SignupProtocol.readFrame { recv.read(SignupProtocol.MAX_FRAME_BYTES.toUInt()) }
                }
                val request = frame?.let(JoinHello::decodeRequest)
                val reply = if (request == null || nodeHex == null) {
                    JoinHello.INVALID_REPLY
                } else {
                    val candidate = JoinPeer(request.deviceToken, nodeHex)
                    val handler = admissionHandler
                    val answer = if (handler == null) {
                        JoinHello.WELCOME_REPLY
                    } else {
                        runCatching { handler(candidate, request) }.getOrElse { JoinHello.NO_ANSWER_REPLY }
                    }
                    if (answer.ok) {
                        peer.set(candidate)
                        livePeers[conn] = candidate
                    }
                    answer
                }
                bi.send().use { send ->
                    send.writeAll(SignupProtocol.encodeReply(reply).toByteArray(Charsets.UTF_8))
                    send.finish()
                }
            }
        }.onFailure { AppLog.i(TAG, "hello stream ended: ${it.message}") }
    }

    /** Starts one budgeted game flow; null (stream closed) when the
     * endpoint-wide flow budget is spent. [onEnd] runs when it ends. */
    private fun startGameFlow(
        stream: BiStream,
        port: Int,
        hostAddress: String,
        flows: CoroutineScope,
        flowBudget: Semaphore,
        onLogin: (String) -> Unit,
        onEnd: () -> Unit,
    ): Job? {
        if (!flowBudget.tryAcquire()) {
            AppLog.w(TAG, "flow budget exhausted — refusing a stream")
            runCatching { stream.close() }
            return null
        }
        return flows.launch {
            try {
                serveFlow(stream, port, hostAddress, onLogin)
            } finally {
                onEnd()
                flowBudget.release()
            }
        }
    }

    /** One probe byte back; both halves released explicitly (QUIC caps
     * open streams per connection). */
    private suspend fun echoKeepAlive(stream: BiStream) {
        runCatching {
            stream.use { probe ->
                probe.recv().use { recv ->
                    val byte = recv.read(1u)
                    probe.send().use { send ->
                        if (byte.isNotEmpty()) send.write(byte)
                        send.finish()
                    }
                    runCatching { recv.stop(0uL) }
                }
            }
        }
    }

    /** One sign-up request in, one reply out (budgeted per connection). */
    private suspend fun answerSignup(
        stream: BiStream,
        budget: SignupProtocol.ConnectionBudget,
        peer: JoinPeer?,
    ) {
        runCatching {
            stream.use { bi ->
                val reply = if (!budget.tryAcquire()) {
                    SignupProtocol.RATE_LIMITED_REPLY
                } else {
                    val recv = bi.recv()
                    val frame = withTimeoutOrNull(SignupProtocol.REPLY_TIMEOUT_MS) {
                        SignupProtocol.readFrame { recv.read(SignupProtocol.MAX_FRAME_BYTES.toUInt()) }
                    }
                    val request = frame?.let(SignupProtocol::decodeRequest)
                    val withPeer = peerSignupHandler
                    val handler = signupHandler
                    when {
                        request == null -> SignupProtocol.INVALID_REPLY
                        withPeer != null -> runCatching { withPeer(request, peer) }.getOrElse {
                            SignupProtocol.Reply(false, SignupProtocol.FAILED, "The host could not answer.")
                        }
                        handler == null -> SignupProtocol.gate(false, 0, emptyList(), request)!!
                        else -> runCatching { handler(request) }.getOrElse {
                            SignupProtocol.Reply(false, SignupProtocol.FAILED, "The host could not answer.")
                        }
                    }
                }
                bi.send().use { send ->
                    send.writeAll(SignupProtocol.encodeReply(reply).toByteArray(Charsets.UTF_8))
                    send.finish()
                }
            }
        }.onFailure { AppLog.i(TAG, "sign-up stream ended: ${it.message}") }
    }

    private suspend fun serveFlow(bi: BiStream, port: Int, hostAddress: String, onLogin: (String) -> Unit) {
        val pumps = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val upstream = try {
            Socket().apply {
                connect(HostGatewayPolicy.dialTarget(port, hostAddress), HostGatewayPolicy.FLOW_DIAL_TIMEOUT_MS)
                tcpNoDelay = true
            }
        } catch (failure: Exception) {
            AppLog.w(TAG, "${GatewayContract.ERR_HOST_STACK}: the realm's port $port did not accept a " +
                "joiner flow (${failure.message}) — is the realm up?")
            runCatching { bi.close() }
            return
        }
        try {
            val toJoiner = pumps.async {
                val input = upstream.getInputStream()
                val output = bi.send()
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) output.writeAll(buffer.copyOf(n))
                }
                output.finish()
            }
            val toRealm = pumps.async {
                val input = bi.recv()
                val output = upstream.getOutputStream()
                // The realm lane's first packet names the account logging
                // in; the bytes pass on untouched.
                val peek = if (port == GatewayContract.PORT_REALM) LogonPeek.Accumulator() else null
                while (true) {
                    val chunk = input.read(CHUNK.toUInt())
                    if (chunk.isEmpty()) break
                    peek?.feed(chunk)?.let { account -> runCatching { onLogin(account) } }
                    output.write(chunk)
                    output.flush()
                }
                runCatching { upstream.shutdownOutput() }
            }
            // The realm closing its socket ends the flow (a joiner hang-up
            // half-closes the realm socket, which then closes it).
            toJoiner.await()
            toRealm.cancel()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.i(TAG, "host flow $port ended: ${failure.message}")
        } finally {
            pumps.cancel()
            runCatching { upstream.close() }
            runCatching { bi.close() }
        }
    }

    private const val TAG = "InternetHost"
    private const val CHUNK = 16 * 1024
    private const val READY_TIMEOUT_MS = 40_000L
    private const val ONLINE_TIMEOUT_MS = 30_000L
    private const val SHUTDOWN_TIMEOUT_MS = 3_000L
    private const val IDLE_POLL_MS = 5_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val NODE_LOG_CHARS = 12
}
