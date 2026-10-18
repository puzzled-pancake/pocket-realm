package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.PortMux
import computer.iroh.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Socket

/**
 * W6-N3 host gateway: mangosd stays loopback (the loop posture is NOT
 * relaxed); this process muxes both game ports over ONE iroh endpoint.
 *
 * Per accepted connection: exactly ONE bi-stream, opened with a 2-byte
 * port id ([PortMux]) followed by RAW game bytes both directions. The host
 * dials `127.0.0.1:<port>` per flow — a flow whose loopback dial fails is
 * a loud per-flow error line (GATEWAY-HOST-STACK-UNREACHABLE) and the flow
 * closes; the endpoint keeps serving (realmd may simply be restarting).
 *
 * There is deliberately NO realm-list rewrite anywhere in this role: the
 * rewrite exists ONLY in the joiner gateway (the ask's invariant — the
 * host's realmlist row is never loopback; the host never even parses the
 * game bytes).
 */
internal object HostGateway {

    fun run(parsed: Args): Int = runBlocking {
        val endpoint = bindOnline(parsed, parsed.relayDisabled)
        val nodeHex = hexLower(endpoint.id().toBytes())
        val relayUrl = endpoint.addr().relayUrl()
        GatewayMain.readyLine(
            role = GatewayContract.MODE_HOST,
            nodeHex = nodeHex,
            relayUrl = relayUrl,
            ports = listOf(GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD),
            boundSockets = endpoint.boundSockets(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val acceptJob = scope.launch { acceptLoop(endpoint, parsed, scope) }
            parkOrTimeout(parsed.maxSeconds)
            acceptJob.cancel()
        } finally {
            scope.cancel()
            runCatching { endpoint.close() }
        }
        GatewayContract.ExitCode.OK
    }

    private suspend fun acceptLoop(
        endpoint: computer.iroh.Endpoint,
        parsed: Args,
        scope: CoroutineScope,
    ) {
        // Code-red R4-5 (admission bound): the listing publishes this node
        // id to the world, so the endpoint is internet-reachable — the
        // concurrent FLOW count is budgeted (an over-budget flow is
        // refused loudly before any loopback dial) and an idle connection
        // (handshaked but never opening a stream) is reaped on a timer.
        val flowBudget = java.util.concurrent.Semaphore(MAX_CONCURRENT_FLOWS, true)
        // Code-red R4-r2-2: CONNECTION-level admission — the flow budget
        // alone let handshaked-but-stream-less connections accumulate for
        // the whole reap window. Over-budget connections are closed at
        // accept (the peer sees the close; no loopback dial ever happens).
        val connectionBudget = java.util.concurrent.Semaphore(MAX_CONCURRENT_CONNECTIONS, true)
        while (true) {
            val incoming = try {
                endpoint.acceptNext() ?: break
            } catch (e: Exception) {
                if (endpoint.isClosed()) break
                System.err.println("gateway host: accept error: ${e.message}")
                continue
            }
            if (!connectionBudget.tryAcquire()) {
                GatewayMain.errorLine(
                    GatewayContract.ERR_HOST_STACK,
                    "connection budget exhausted (${MAX_CONCURRENT_CONNECTIONS} " +
                        "connections) — refusing an additional connection",
                )
                runCatching { incoming.accept().connect().close() }
                continue
            }
            scope.launch {
                val conn = try {
                    incoming.accept().connect()
                } catch (e: Exception) {
                    System.err.println("gateway host: handshake failed: ${e.message}")
                    return@launch
                }
                // One iroh connection may carry SEVERAL client flows (the
                // joiner multiplexes its 3724/8085 lanes on one tunnel):
                // the connection closes only when its LAST flow ends.
                val activeFlows = java.util.concurrent.atomic.AtomicInteger(0)
                // The idle reap: a connection that never opens a stream
                // would park this accept loop forever — the watchdog closes
                // it after the idle budget (which unblocks acceptBi with an
                // exception and ends the loop). Round-6 R6-2-1: the trigger
                // is LAST STREAM ACTIVITY (game flow OR keep-alive probe),
                // not a boolean — the joiner's keep-alive probes count as
                // life, so a live tunnel with no game flow is not reaped,
                // while a dead joiner (probes stopped) is.
                val lastStreamMs = java.util.concurrent.atomic.AtomicLong(
                    System.currentTimeMillis(),
                )
                val idleReaper = scope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(IDLE_REAP_POLL_MS)
                        val idleFor = System.currentTimeMillis() - lastStreamMs.get()
                        if (idleFor > IDLE_REAP_POLL_MS * IDLE_REAP_POLLS) {
                            System.err.println("gateway host: reaping an idle connection " +
                                "(no stream within ${IDLE_REAP_POLL_MS * IDLE_REAP_POLLS}ms)")
                            runCatching { conn.close() }
                            break
                        }
                    }
                }
                try {
                    while (true) {
                        val stream = try {
                            conn.acceptBi()
                        } catch (_: Exception) {
                            break
                        }
                        lastStreamMs.set(System.currentTimeMillis())
                        // The header read is hoisted here so the reserved
                        // keep-alive lane (R6-2-1) never touches the game
                        // flow machinery: echo one byte, done.
                        val header = try {
                            stream.recv().readExact(PortMux.HEADER_BYTES.toUInt())
                        } catch (_: Exception) {
                            null
                        }
                        val port = header
                            ?.takeIf { it.size >= PortMux.HEADER_BYTES }
                            ?.let { h ->
                                runCatching { PortMux.decodePort(h) }.getOrNull()
                            }
                        if (port == GatewayContract.PORT_KEEPALIVE) {
                            scope.launch {
                                runCatching {
                                    val probe = stream.recv().read(1u)
                                    if (probe.isNotEmpty()) {
                                        stream.send().write(probe)
                                    }
                                }
                                lastStreamMs.set(System.currentTimeMillis())
                            }
                            continue
                        }
                        if (port != null) {
                            // A real game lane: alive + budgeted flow.
                            activeFlows.incrementAndGet()
                        }
                        idleReaper.cancel()
                        scope.launch {
                            serveFlow(conn, stream, port, parsed, activeFlows, flowBudget)
                        }
                    }
                } finally {
                    idleReaper.cancel()
                    if (activeFlows.get() == 0) runCatching { conn.close() }
                    connectionBudget.release()
                }
            }
        }
    }

    /** Serves ONE mux stream: port-id header, loopback dial, raw pumps.
     * [activeFlows] counts this connection's live streams — the connection
     * closes when the count returns to zero (never mid-bundle). */
    /** Serves ONE mux stream: port-id header, loopback dial, raw pumps.
     * [activeFlows] counts this connection's live streams — the connection
     * closes when the count returns to zero (never mid-bundle). [flowBudget]
     * bounds the endpoint-wide concurrent flows (code-red R4-5): an
     * over-budget flow is refused loudly BEFORE any loopback dial. */
    private suspend fun serveFlow(
        conn: Connection,
        bi: computer.iroh.BiStream,
        port: Int?,
        parsed: Args,
        activeFlows: java.util.concurrent.atomic.AtomicInteger,
        flowBudget: java.util.concurrent.Semaphore,
    ) {
        if (!flowBudget.tryAcquire()) {
            GatewayMain.errorLine(
                GatewayContract.ERR_HOST_STACK,
                "concurrent flow budget exhausted (${MAX_CONCURRENT_FLOWS} " +
                    "flows) — refusing an additional stream",
            )
            runCatching { conn.close() }
            return
        }
        if (port == null) {
            // The header never arrived (or was undecodable): nothing to
            // serve — the accept loop already logged the truncation shape.
            flowBudget.release()
            return
        }
        try {
            val upstream = try {
                dialLoopback(port, parsed)
            } catch (e: Exception) {
                GatewayMain.errorLine(
                    GatewayContract.ERR_HOST_STACK,
                    "loopback:$port did not accept a flow (${e.message}) — is the " +
                        "realm stack up?",
                )
                return
            }
            try {
                val remoteToLocal = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.Dispatchers.IO,
                ).launch {
                    GatewayIo.pumpIrohToSocket(bi, upstream)
                }
                val localToRemote = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.Dispatchers.IO,
                ).launch {
                    GatewayIo.pumpSocketToIroh(upstream, bi)
                }
                // Whichever direction ends first ends the flow.
                localToRemote.join()
                remoteToLocal.cancel()
            } finally {
                runCatching { upstream.close() }
            }
        } finally {
            flowBudget.release()
            if (activeFlows.decrementAndGet() == 0) {
                runCatching { conn.close() }
            }
        }
    }

    /** The loopback dial (5 s bounded): realmd 3724 / mangosd 8085 (or
     * the loopback-pinned --realm-target/--world-target overrides the
     * one-machine E2E passes). */
    internal fun dialLoopback(port: Int, parsed: Args): Socket {
        val target = when (port) {
            GatewayContract.PORT_REALM -> parsed.realmTarget
                ?: InetSocketAddress("127.0.0.1", port)
            GatewayContract.PORT_WORLD -> parsed.worldTarget
                ?: InetSocketAddress("127.0.0.1", port)
            else -> InetSocketAddress("127.0.0.1", port)
        }
        val socket = Socket()
        try {
            socket.connect(target, FLOW_DIAL_TIMEOUT_MS)
            socket.tcpNoDelay = true
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    internal const val FLOW_DIAL_TIMEOUT_MS: Int = 5_000

    /** Code-red R4-5: the endpoint-wide concurrent-flow budget and the
     * idle-connection reap cadence (an internet-reachable endpoint gets an
     * admission bound, not unbounded accept). */
    const val MAX_CONCURRENT_FLOWS = 64
    const val MAX_CONCURRENT_CONNECTIONS = 128
    const val IDLE_REAP_POLL_MS = 5_000L
    const val IDLE_REAP_POLLS = 12
}
