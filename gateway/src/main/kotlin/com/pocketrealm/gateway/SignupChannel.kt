package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.JoinHello
import com.pocketrealm.discovery.iroh.JoinPeer
import com.pocketrealm.discovery.iroh.PortMux
import com.pocketrealm.discovery.iroh.SignupProtocol
import computer.iroh.BiStream
import computer.iroh.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The self sign-up lane in the gateway process ([GatewayContract.PORT_SIGNUP]).
 * The process owns the tunnel but not the realm's accounts, so the
 * request is relayed to the app over the stdio channel
 * ([GatewayContract.ARG_SIGNUP_CHANNEL]): the stdin pipe stays open after
 * the secret line and carries correlated JSON lines.
 *
 *  - HOST: a joiner's request is validated here, printed as one
 *    [GatewayContract.SIGNUP_REQUEST_LINE_PREFIX] line, and the app's
 *    [GatewayContract.SIGNUP_REPLY_LINE_PREFIX] line on stdin goes back
 *    down the stream. Without the channel the lane answers SIGNUP_CLOSED.
 *  - JOIN: the app writes request lines on stdin; each is sent to the host
 *    on its own stream and the host's answer is printed as a reply line.
 */
internal object SignupChannel {

    /** The stdin reader the secret was read from (the same buffer must
     * serve the channel — a second reader would lose buffered lines). */
    @Volatile var stdin: BufferedReader? = null

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val admitPending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val ids = AtomicLong()

    /** HOST: the app's order to close a banned device's or node's live
     * connections (set by the host gateway). */
    @Volatile var onDrop: ((deviceToken: String?, nodeHex: String?) -> Unit)? = null

    private fun reader(): BufferedReader = stdin ?: BufferedReader(
        java.io.InputStreamReader(System.`in`, Charsets.UTF_8),
    ).also { stdin = it }

    /** HOST: route the app's reply lines to their waiting streams. */
    fun startHostReader() {
        val input = reader()
        Thread({
            runCatching {
                while (true) {
                    val line = input.readLine() ?: break
                    when {
                        line.startsWith(GatewayContract.SIGNUP_REPLY_LINE_PREFIX) ->
                            SignupProtocol.decodeChannelLine(GatewayContract.SIGNUP_REPLY_LINE_PREFIX, line)
                                ?.let { (id, frame) -> pending.remove(id)?.complete(frame) }
                        line.startsWith(GatewayContract.ADMIT_REPLY_LINE_PREFIX) ->
                            SignupProtocol.decodeChannelLine(GatewayContract.ADMIT_REPLY_LINE_PREFIX, line)
                                ?.let { (id, frame) -> admitPending.remove(id)?.complete(frame) }
                        line.startsWith(GatewayContract.DROP_LINE_PREFIX) ->
                            JoinHello.decodeDropLine(GatewayContract.DROP_LINE_PREFIX, line)
                                ?.let { (device, node) -> runCatching { onDrop?.invoke(device, node) } }
                    }
                }
            }
        }, "gateway-signup-replies").apply { isDaemon = true }.start()
    }

    /** HOST: ask the app whether this joiner is welcome (bans live in the
     * app). No answer in time is a refusal: a host with the channel never
     * lets an unchecked joiner in. */
    suspend fun admit(peer: JoinPeer, passwordProof: String): SignupProtocol.Reply {
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<String>()
        admitPending[id] = waiter
        println(JoinHello.encodeAdmitLine(GatewayContract.ADMIT_REQUEST_LINE_PREFIX, id, peer, passwordProof))
        System.out.flush()
        val frame = withTimeoutOrNull(ADMIT_TIMEOUT_MS) { waiter.await() }
        admitPending.remove(id)
        return frame?.let(SignupProtocol::decodeReply) ?: JoinHello.NO_ANSWER_REPLY
    }

    /** HOST: report a login seen on the realm lane, or a connection end. */
    fun peerEvent(event: JoinHello.PeerEvent) {
        println(JoinHello.encodeEventLine(GatewayContract.PEER_EVENT_LINE_PREFIX, event))
        System.out.flush()
    }

    /** The app answers admission from memory; this leaves the joiner's
     * own hello wait room to spare. */
    private const val ADMIT_TIMEOUT_MS: Long = 6_000L

    /** HOST: one joiner stream — read, validate, ask the app, answer. */
    suspend fun answer(
        stream: BiStream,
        budget: SignupProtocol.ConnectionBudget,
        channel: Boolean,
        peer: JoinPeer? = null,
    ) {
        runCatching {
            stream.use { bi ->
                val reply = when {
                    !budget.tryAcquire() -> SignupProtocol.RATE_LIMITED_REPLY
                    else -> {
                        val recv = bi.recv()
                        val frame = withTimeoutOrNull(SignupProtocol.REPLY_TIMEOUT_MS) {
                            SignupProtocol.readFrame { recv.read(SignupProtocol.MAX_FRAME_BYTES.toUInt()) }
                        }
                        val request = frame?.let(SignupProtocol::decodeRequest)
                        when {
                            request == null -> SignupProtocol.INVALID_REPLY
                            !channel -> SignupProtocol.gate(false, 0, emptyList(), request)!!
                            // With the channel the host requires a hello first.
                            peer == null -> SignupProtocol.HELLO_REQUIRED_REPLY
                            else -> askApp(request, peer)
                        }
                    }
                }
                bi.send().use { send ->
                    send.writeAll(SignupProtocol.encodeReply(reply).toByteArray(Charsets.UTF_8))
                    send.finish()
                }
            }
        }.onFailure { System.err.println("gateway host: sign-up stream ended: ${it.message}") }
    }

    private suspend fun askApp(request: SignupProtocol.Request, peer: JoinPeer): SignupProtocol.Reply {
        val id = ids.incrementAndGet()
        val waiter = CompletableDeferred<String>()
        pending[id] = waiter
        println(
            SignupProtocol.encodeChannelLine(
                GatewayContract.SIGNUP_REQUEST_LINE_PREFIX, id, SignupProtocol.encodeRequest(request), peer,
            ),
        )
        System.out.flush()
        val frame = withTimeoutOrNull(SignupProtocol.REPLY_TIMEOUT_MS) { waiter.await() }
        pending.remove(id)
        return frame?.let(SignupProtocol::decodeReply)
            ?: SignupProtocol.Reply(false, SignupProtocol.FAILED, "The host app did not answer in time.")
    }

    /** JOIN: relay the app's request lines over [conn], one stream each. */
    fun startJoinReader(conn: Connection, scope: CoroutineScope) {
        val input = reader()
        Thread({
            runCatching {
                while (true) {
                    val line = input.readLine() ?: break
                    val (id, frame) = SignupProtocol.decodeChannelLine(
                        GatewayContract.SIGNUP_REQUEST_LINE_PREFIX, line,
                    ) ?: continue
                    scope.launch { printReply(id, send(conn, frame)) }
                }
            }
        }, "gateway-signup-requests").apply { isDaemon = true }.start()
    }

    private suspend fun send(conn: Connection, frame: String): SignupProtocol.Reply {
        val request = SignupProtocol.decodeRequest(frame) ?: return SignupProtocol.INVALID_REPLY
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
                        SignupProtocol.readFrame { recv.read(SignupProtocol.MAX_FRAME_BYTES.toUInt()) }
                            ?.let(SignupProtocol::decodeReply)
                            ?: SignupProtocol.Reply(false, SignupProtocol.FAILED,
                                "the host sent no sign-up answer (an older host app?)")
                    }
                }
            }
        }.getOrElse {
            SignupProtocol.Reply(false, SignupProtocol.FAILED,
                "the sign-up request did not complete (${it::class.simpleName})")
        }
    }

    private fun printReply(id: Long, reply: SignupProtocol.Reply) {
        println(
            SignupProtocol.encodeChannelLine(
                GatewayContract.SIGNUP_REPLY_LINE_PREFIX, id, SignupProtocol.encodeReply(reply),
            ),
        )
        System.out.flush()
    }
}
