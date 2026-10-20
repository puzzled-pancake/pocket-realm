package com.pocketrealm.gateway

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.discovery.iroh.AuthStreamFilter
import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.PortMux
import com.pocketrealm.discovery.iroh.RealmListRewrite
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * join gateway: dials the host's iroh node id (relay-only default:
 * the LISTING's home relay URL is the only relay this endpoint joins
 * through; no direct address is ever dialed), then binds the joiner-side
 * LOOPBACK listeners 3724 and 8085 so the local client plays "through the
 * internet realm" exactly like a LAN realm — the client's realmlist points
 * at 127.0.0.1 and every game byte rides the tunnel.
 *
 * The 3724 lane carries the ONE rewrite: every SMSG_REALM_LIST frame is
 * parsed per the flavor's layout and rebuilt with the address C-string
 * replaced by the joiner's own loopback world target (size recomputed).
 * A frame that fails the parse is a LOUD exit (REALMLIST-REWRITE-FAILED) —
 * the client must never receive a list that would dial the host's raw
 * address.
 *
 * Loudness: bind clash on 3724/8085 (another realm stack — typically this
 * app's own hosting) exits PORT_BIND-FAILED before any dial; a dial
 * failure exits DIAL-FAILED with the error line; both surface verbatim in
 * the app (the refusal copy's inputs).
 */
internal object JoinGateway {

    fun run(parsed: Args): Int = runBlocking {
        val flavor = RealmListRewrite.flavorFor(parsed.flavorId!!)
        // 1) Bind the loopback listeners FIRST: a port clash with a local
        //    realm stack is refused before any network effect.
        val listeners = bindListeners()
        try {
            // 2) Dial the host (bounded). Relay-only (the default) dials the
            //    listing's home relay and NOTHING else; explicit addresses
            //    exist only on the --direct-allowed friend lane.
            val endpoint = bindOnline(parsed, parsed.relayDisabled)
            val conn = try {
                withTimeout(JOIN_DIAL_TIMEOUT_MS) {
                    endpoint.connect(
                        EndpointAddr(
                            EndpointId.fromBytes(hexToBytes(parsed.nodeHex!!)),
                            parsed.relayUrl?.takeIf { it.isNotBlank() },
                            parsed.directAddresses,
                        ),
                        GatewayContract.ALPN.toByteArray(),
                    )
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                runCatching { endpoint.close() }
                throw DialFailedExit(
                    "the host's node did not answer within ${JOIN_DIAL_TIMEOUT_MS / 1000}s " +
                        "(host offline? relay unreachable?)",
                )
            } catch (e: Exception) {
                runCatching { endpoint.close() }
                throw DialFailedExit(
                    "dial failed: ${e::class.simpleName}: ${e.message}" +
                        (e.cause?.let { " (cause ${it::class.simpleName}: ${it.message})" } ?: ""),
                )
            }
            val nodeHex = hexLower(endpoint.id().toBytes())
            val relayUrl = endpoint.addr().relayUrl() ?: parsed.relayUrl
            GatewayMain.readyLine(
                role = GatewayContract.MODE_JOIN,
                nodeHex = nodeHex,
                relayUrl = relayUrl,
                ports = listOf(GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD),
            )
            // 3) Serve the client: per TCP flow, one iroh bi-stream with
            //    the port-id header, then raw bytes (3724 filtered).
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                // the CONNECTION-LOSS WATCHER. A dead host
                // used to leave this process alive with zero error lines —
                // the app's Ready never left and a dialing client hung.
                // The reserved keep-alive lane (port id 0) round-trips one
                // byte every beat; a missed echo means the host's endpoint
                // is gone: one PR_GATEWAY_ERROR line, then the loud exit
                // (the app-side post-READY monitors need exactly that).
                scope.launch {
                    while (true) {
                        delay(KEEPALIVE_INTERVAL_MS)
                        val lost = runCatching {
                            kotlinx.coroutines.withTimeout(KEEPALIVE_PROBE_TIMEOUT_MS) {
                                val bi = conn.openBi()
                                bi.send().write(
                                    PortMux.encodeHeader(GatewayContract.PORT_KEEPALIVE) +
                                        byteArrayOf(0x42),
                                )
                                bi.recv().read(1u).isEmpty()
                            }
                        }.getOrDefault(true)
                        if (lost) {
                            GatewayMain.errorLine(
                                GatewayContract.ERR_DIAL_FAILED,
                                "connection lost: the keep-alive probe went " +
                                    "unanswered - the host's endpoint is gone " +
                                    "(the tunnel is down; dial again to rejoin)",
                            )
                            kotlin.system.exitProcess(
                                GatewayContract.ExitCode.DIAL_FAILED,
                            )
                        }
                    }
                }
                acceptLoop(listeners, endpoint, conn, flavor, scope)
                parkOrTimeout(parsed.maxSeconds)
            } finally {
                scope.cancel()
                runCatching { conn.close() }
                runCatching { endpoint.close() }
            }
        } finally {
            listeners.values.forEach { runCatching { it.close() } }
        }
        GatewayContract.ExitCode.OK
    }

    /** Binds BOTH loopback listeners before any network effect. */
    private fun bindListeners(): Map<Int, ServerSocket> {
        val bound = LinkedHashMap<Int, ServerSocket>()
        try {
            for (port in listOf(GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD)) {
                val server = ServerSocket()
                try {
                    server.bind(InetAddress.getByName("127.0.0.1").let { InetSocketAddress(it, port) }, BACKLOG)
                    server.soTimeout = 0
                    bound[port] = server
                } catch (e: Exception) {
                    throw PortBindFailedExit(
                        "loopback port $port is already in use — a realm stack " +
                            "(hosting) or another gateway is running on this " +
                            "instance (${e.javaClass.simpleName})",
                    )
                }
            }
        } catch (e: Exception) {
            bound.values.forEach { runCatching { it.close() } }
            throw e
        }
        return bound
    }

    private fun acceptLoop(
        listeners: Map<Int, ServerSocket>,
        endpoint: Endpoint,
        conn: computer.iroh.Connection,
        flavor: ClientFlavor,
        scope: CoroutineScope,
    ) {
        // ONE accepter coroutine per lane; flows are served on their own.
        for ((port, server) in listeners) {
            scope.launch {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (server.isClosed) break
                        System.err.println("gateway join/$port accept error: ${e.message}")
                        continue
                    }
                    scope.launch { serveFlow(client, port, scope, conn, flavor) }
                }
            }
        }
    }

    /**
     * Serves ONE client flow: open a bi-stream, write the port header,
     * pump. The 3724 lane's remote→client direction rides the
     * [AuthStreamFilter]; the 8085 lane is raw.
     */
    internal suspend fun serveFlow(
        client: Socket,
        port: Int,
        scope: CoroutineScope,
        conn: computer.iroh.Connection,
        flavor: ClientFlavor,
    ) {
        try {
            client.tcpNoDelay = true
            val bi = conn.openBi()
            bi.send().writeAll(PortMux.encodeHeader(port))
            // BOTH pumps run as async jobs whose failures are HELD for
            // await: the filtered pump's LOUD rewrite refusal must reach
            // THIS body's catch — launched-and-cancelled (the first cut)
            // it died in a SupervisorJob worker and the join half-served
            // Awaiting the client-ward pump first
            // surfaces the rewrite failure immediately; a normal flow
            // still terminates through the server-side EOF cascade.
            val toClient = scope.async {
                if (port == GatewayContract.PORT_REALM) {
                    val filter = AuthStreamFilter(flavor)
                    GatewayIo.pumpIrohToSocketFiltered(bi, client, filter)
                } else {
                    GatewayIo.pumpIrohToSocket(bi, client)
                }
            }
            val toRemote = scope.async { GatewayIo.pumpSocketToIroh(client, bi) }
            toClient.await()
            toRemote.await()
        } catch (rewrite: RealmListRewrite.RealmListRewriteException) {
            // THE loud failure: the client would dial the host's address.
            GatewayMain.errorLine(
                GatewayContract.ERR_REWRITE_FAILED,
                rewrite.message ?: "realm list rewrite failed",
            )
            runCatching { client.close() }
            kotlin.system.exitProcess(GatewayContract.ExitCode.REALMLIST_REWRITE_FAILED)
        } catch (e: Exception) {
            System.err.println("gateway join/$port flow ended: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    internal const val JOIN_DIAL_TIMEOUT_MS: Long = 30_000L
    internal const val BACKLOG: Int = 16

    /** The connection-loss watcher's beat and per-probe budget
     * R6-2-1): loss is declared within ~KEEPALIVE_INTERVAL_MS +
     * KEEPALIVE_PROBE_TIMEOUT_MS of the host's endpoint dying — seconds,
     * not QUIC's ~26s idle timeout. */
    internal const val KEEPALIVE_INTERVAL_MS: Long = 2_000L
    internal const val KEEPALIVE_PROBE_TIMEOUT_MS: Long = 3_000L

    internal fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
