package com.pocketrealm.discovery.iroh

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.log.AppLog
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.RelayMode
import computer.iroh.SecretKey
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The in-app internet join (the desktop gateway's JoinGateway twin, run
 * in-process inside :supervisor instead of as a child JVM): binds the
 * loopback listeners 3724/8085 FIRST (a local realm stack owning them
 * refuses before any network effect), dials the listing host's iroh node
 * through the listing's home relay (relay-only by default — no direct
 * address is dialed), then pipes every client flow over one iroh connection
 * with the shared port-id mux. The 3724 lane's host→client direction rides
 * [AuthStreamFilter], which rebuilds each SMSG_REALM_LIST so the realm row
 * names 127.0.0.1:8085: the local client plays the internet realm exactly
 * like a LAN realm, and a frame that fails the rewrite ends the join loudly
 * (the client must never be handed the host's raw address).
 *
 * A keep-alive on the reserved port-0 lane detects a dead host within
 * seconds and fails the join instead of leaving the client hanging.
 */
object InternetJoinGateway {

    sealed interface State {
        data object Idle : State
        data class Connecting(val detail: String) : State
        data class Ready(val nodeHex: String) : State
        data class Failed(val code: String, val detail: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile private var session: Job? = null
    @Volatile private var listeners: Map<Int, ServerSocket> = emptyMap()
    @Volatile private var liveConnection: Connection? = null

    /** True when the iroh native transport is packaged in this build. */
    fun transportAvailable(): Boolean = runCatching {
        SecretKey.generate()
        true
    }.getOrElse { failure ->
        AppLog.w(TAG, "iroh transport unavailable: ${failure.javaClass.simpleName}: ${failure.message}")
        false
    }

    /**
     * Starts a join and suspends until it is [State.Ready] or [State.Failed]
     * (bounded by [READY_TIMEOUT_MS]); the session keeps running in
     * [scope] after a Ready answer until [stop].
     */
    suspend fun start(
        scope: CoroutineScope,
        descriptor: IrohJoinDescriptor,
        flavor: ClientFlavor,
        relayOnly: Boolean,
        /** This device's identity for this host: the per-host iroh key
         * and the device token the hello lane presents. Null = a random
         * key and no hello (hosts that hand out bans refuse that). */
        identity: Identity? = null,
        /** The join password's HMAC key ([JoinPasscode.keyHex] of the
         * password the player typed), or null when none was. The derived
         * key — never the password — reaches the proof math. */
        passwordKeyHex: String? = null,
    ): State {
        stop()
        if (passwordKeyHex != null) {
            require(JoinPasscode.isKeyHex(passwordKeyHex)) { "the pass key must be 64 lowercase hex" }
        }
        _state.value = State.Connecting("Opening the local game ports")
        val bound = try {
            bindListeners()
        } catch (failure: Exception) {
            return fail(GatewayContract.ERR_PORT_BIND, failure.message ?: "port bind failed")
        }
        listeners = bound
        session = scope.launch(Dispatchers.IO + SupervisorJob()) {
            runSession(bound, descriptor, flavor, relayOnly, identity, passwordKeyHex)
        }
        return withTimeoutOrNull(READY_TIMEOUT_MS) {
            state.first { it is State.Ready || it is State.Failed }
        } ?: run {
            stop()
            fail(
                GatewayContract.ERR_DIAL_FAILED,
                "the host did not answer within ${READY_TIMEOUT_MS / MILLIS_PER_SECOND}s " +
                    "(host offline? relay unreachable?)",
            )
        }
    }

    /** Ends the join: closes the listeners and the tunnel. Safe when idle. */
    fun stop() {
        session?.cancel()
        session = null
        listeners.values.forEach { runCatching { it.close() } }
        listeners = emptyMap()
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    val active: Boolean get() = session?.isActive == true

    /** The joiner's per-host identity ([DeviceIdentity]). */
    data class Identity(val deviceToken: String, val secretHex: String) {
        init {
            require(DeviceIdentity.isToken(deviceToken)) { "the device token must be 64 lowercase hex" }
            require(HostGatewayPolicy.isValidSecretHex(secretHex)) { "the join key must be 64 lowercase hex" }
        }

        companion object {
            /** The identity this device presents to [hostNodeHex]. */
            fun forHost(deviceSecret: ByteArray, hostNodeHex: String): Identity = Identity(
                DeviceIdentity.tokenFor(deviceSecret, hostNodeHex),
                DeviceIdentity.joinSecretHex(deviceSecret, hostNodeHex),
            )
        }
    }

    private fun fail(code: String, detail: String): State {
        val failed = State.Failed(code, detail)
        _state.value = failed
        AppLog.w(TAG, "internet join failed: $code: $detail")
        return failed
    }

    private suspend fun runSession(
        bound: Map<Int, ServerSocket>,
        descriptor: IrohJoinDescriptor,
        flavor: ClientFlavor,
        relayOnly: Boolean,
        identity: Identity?,
        passwordKeyHex: String?,
    ) {
        val flows = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var endpoint: Endpoint? = null
        var conn: Connection? = null
        try {
            _state.value = State.Connecting("Reaching the realm's relay")
            val bound0 = Endpoint.bind(
                EndpointOptions(
                    preset = presetN0(),
                    secretKey = identity?.let { HostGatewayPolicy.hexToBytes(it.secretHex) }
                        ?: SecretKey.generate().toBytes(),
                    alpns = listOf(GatewayContract.ALPN.toByteArray()),
                    relayMode = if (relayOnly) {
                        RelayMode.customFromUrls(listOf(descriptor.relayUrl))
                    } else {
                        RelayMode.defaultMode()
                    },
                    bindAddr = null,
                    protocols = null,
                ),
            )
            endpoint = bound0
            withTimeout(ONLINE_TIMEOUT_MS) { bound0.online() }
            _state.value = State.Connecting("Dialing the host")
            val dialed = withTimeout(DIAL_TIMEOUT_MS) {
                bound0.connect(
                    EndpointAddr(
                        EndpointId.fromBytes(hexToBytes(descriptor.nodeHex)),
                        descriptor.relayUrl,
                        emptyList(),
                    ),
                    GatewayContract.ALPN.toByteArray(),
                )
            }
            conn = dialed
            if (identity != null) {
                _state.value = State.Connecting("Saying hello to the host")
                // The password proof binds THIS joiner's node id, so it is
                // derived after the endpoint exists (a captured proof only
                // works from this same device).
                val proof = passwordKeyHex?.let { key ->
                    val ownNodeHex = HostGatewayPolicy.bytesToHex(bound0.id().toBytes())
                    JoinPasscode.proof(key, descriptor.nodeHex, ownNodeHex)
                }.orEmpty()
                val hello = sayHello(dialed, identity.deviceToken, proof)
                if (!hello.ok) {
                    fail(GatewayContract.ERR_JOIN_REFUSED, hello.detail)
                    return
                }
            }
            liveConnection = dialed
            _state.value = State.Ready(descriptor.nodeHex)
            AppLog.i(TAG, "internet join ready (node ${descriptor.nodeHex.take(NODE_LOG_CHARS)}…)")
            for ((port, server) in bound) {
                flows.launch {
                    while (isActive && !server.isClosed) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        flows.launch { serveFlow(client, port, dialed, flavor) }
                    }
                }
            }
            keepAlive(dialed)
        } catch (rewrite: RealmListRewrite.RealmListRewriteException) {
            fail(GatewayContract.ERR_REWRITE_FAILED, rewrite.message ?: "realm list rewrite failed")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            fail(
                GatewayContract.ERR_DIAL_FAILED,
                "${failure.javaClass.simpleName}: ${failure.message ?: "dial failed"}",
            )
        } finally {
            liveConnection = null
            flows.cancel()
            bound.values.forEach { runCatching { it.close() } }
            conn?.let { runCatching { it.close(0L, ByteArray(0)) } }
            endpoint?.let { ep ->
                runCatching { withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { ep.shutdown() } }
                runCatching { ep.close() }
            }
        }
    }

    /** The hello lane: one device-token line (schema 2 carries the join
     * password's proof) out, the host's answer back. A host without the
     * hello lane (an older app) answers nothing — that counts as welcome,
     * so older hosts stay joinable. */
    private suspend fun sayHello(
        conn: Connection,
        deviceToken: String,
        passwordProof: String = "",
    ): SignupProtocol.Reply =
        runCatching {
            withTimeout(JoinHello.TIMEOUT_MS) {
                conn.openBi().use { bi ->
                    bi.send().use { send ->
                        send.writeAll(
                            PortMux.encodeHeader(GatewayContract.PORT_HELLO) +
                                JoinHello.encodeRequest(deviceToken, passwordProof)
                                    .toByteArray(Charsets.UTF_8),
                        )
                        send.finish()
                    }
                    bi.recv().use { recv ->
                        SignupProtocol.readFrame { recv.read(SignupProtocol.MAX_FRAME_BYTES.toUInt()) }
                            ?.let(SignupProtocol::decodeReply)
                            ?: JoinHello.WELCOME_REPLY
                    }
                }
            }
        }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException &&
                failure !is kotlinx.coroutines.TimeoutCancellationException
            ) {
                throw failure
            }
            // Only an explicit refusal stops the join: the host itself
            // refuses game streams to a joiner it did not welcome.
            JoinHello.WELCOME_REPLY
        }

    /**
     * Self sign-up on the READY tunnel: one request on the reserved
     * sign-up lane, one reply back (bounded by
     * [SignupProtocol.REPLY_TIMEOUT_MS]). The request carries only the
     * name and the locally derived salt/verifier.
     */
    suspend fun signup(request: SignupProtocol.Request): SignupProtocol.Reply {
        val conn = liveConnection
            ?: return SignupProtocol.Reply(false, SignupProtocol.FAILED, "the tunnel to the host is not up")
        return runCatching {
            withTimeout(SignupProtocol.REPLY_TIMEOUT_MS) {
                conn.openBi().use { bi ->
                    bi.send().use { send ->
                        send.writeAll(
                            PortMux.encodeHeader(GatewayContract.PORT_SIGNUP) +
                                SignupProtocol.encodeRequest(request).toByteArray(Charsets.UTF_8),
                        )
                        send.finish()
                    }
                    bi.recv().use { recv ->
                        val frame = SignupProtocol.readFrame { recv.read(SignupProtocol.MAX_FRAME_BYTES.toUInt()) }
                        frame?.let(SignupProtocol::decodeReply)
                            ?: SignupProtocol.Reply(false, SignupProtocol.FAILED,
                                "the host sent no sign-up answer (an older host app?)")
                    }
                }
            }
        }.getOrElse { failure ->
            SignupProtocol.Reply(false, SignupProtocol.FAILED,
                "the sign-up request did not complete (${failure.javaClass.simpleName})")
        }
    }

    /** Round-trips one byte on the reserved keep-alive lane every beat; a
     * missed echo means the host's endpoint is gone. Each probe's stream is
     * finished, stopped and released explicitly: QUIC caps the open
     * streams per connection, and a probe stream left for the GC to free
     * would exhaust that cap within minutes and fake a lost host. */
    private suspend fun keepAlive(conn: Connection) {
        while (true) {
            delay(KEEPALIVE_INTERVAL_MS)
            val lost = runCatching {
                withTimeout(KEEPALIVE_PROBE_TIMEOUT_MS) {
                    conn.openBi().use { bi ->
                        bi.send().use { send ->
                            send.write(PortMux.encodeHeader(GatewayContract.PORT_KEEPALIVE) + byteArrayOf(KEEPALIVE_BYTE))
                            send.finish()
                        }
                        bi.recv().use { recv ->
                            val echo = recv.read(1u)
                            runCatching { recv.stop(0uL) }
                            echo.isEmpty()
                        }
                    }
                }
            }.getOrDefault(true)
            if (lost) {
                fail(
                    GatewayContract.ERR_DIAL_FAILED,
                    "connection lost: the host's realm stopped answering (join again to reconnect)",
                )
                return
            }
        }
    }

    private suspend fun serveFlow(client: Socket, port: Int, conn: Connection, flavor: ClientFlavor) {
        val pumps = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var opened: computer.iroh.BiStream? = null
        try {
            client.tcpNoDelay = true
            val bi = conn.openBi().also { opened = it }
            bi.send().writeAll(PortMux.encodeHeader(port))
            val toClient = pumps.async {
                val input = bi.recv()
                val output = client.getOutputStream()
                val filter = if (port == GatewayContract.PORT_REALM) AuthStreamFilter(flavor) else null
                while (true) {
                    val chunk = input.read(CHUNK.toUInt())
                    val eof = chunk.isEmpty()
                    if (filter != null) {
                        filter.feed(chunk, eof = eof).forEach { output.write(it) }
                    } else if (!eof) {
                        output.write(chunk)
                    }
                    output.flush()
                    if (eof) break
                }
                runCatching { client.shutdownOutput() }
            }
            val toHost = pumps.async {
                val input = client.getInputStream()
                val output = bi.send()
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) output.writeAll(buffer.copyOf(n))
                }
                output.finish()
            }
            toClient.await()
            toHost.await()
        } catch (rewrite: RealmListRewrite.RealmListRewriteException) {
            fail(GatewayContract.ERR_REWRITE_FAILED, rewrite.message ?: "realm list rewrite failed")
            stop()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.i(TAG, "join flow $port ended: ${failure.message}")
        } finally {
            pumps.cancel()
            runCatching { client.close() }
            opened?.let { runCatching { it.close() } }
        }
    }

    private fun bindListeners(): Map<Int, ServerSocket> {
        val bound = LinkedHashMap<Int, ServerSocket>()
        try {
            for (port in listOf(GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD)) {
                val server = ServerSocket()
                try {
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port), BACKLOG)
                } catch (failure: Exception) {
                    runCatching { server.close() }
                    throw IllegalStateException(
                        "port $port is already in use on this device — stop the local realm " +
                            "(it owns 3724/8085) and try again",
                        failure,
                    )
                }
                bound[port] = server
            }
        } catch (failure: Exception) {
            bound.values.forEach { runCatching { it.close() } }
            throw failure
        }
        return bound
    }

    internal fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], HEX_RADIX) shl NIBBLE_BITS) +
                Character.digit(hex[i * 2 + 1], HEX_RADIX)).toByte()
        }

    private const val TAG = "InternetJoin"
    private const val LOOPBACK = "127.0.0.1"
    private const val BACKLOG = 16
    private const val CHUNK = 16 * 1024
    private const val READY_TIMEOUT_MS = 45_000L
    private const val ONLINE_TIMEOUT_MS = 30_000L
    private const val DIAL_TIMEOUT_MS = 30_000L
    private const val SHUTDOWN_TIMEOUT_MS = 3_000L
    private const val KEEPALIVE_INTERVAL_MS = 2_000L
    private const val KEEPALIVE_PROBE_TIMEOUT_MS = 3_000L
    private const val KEEPALIVE_BYTE: Byte = 0x42
    private const val MILLIS_PER_SECOND = 1_000L
    private const val NODE_LOG_CHARS = 12
    private const val HEX_RADIX = 16
    private const val NIBBLE_BITS = 4
}
