package com.pocketrealm.desktop.realmstatus

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Realm-status browser (design realm-status-browser.md) the minimal
 * HTTP server over the JDK-builtin `com.sun.net.httpserver` (module
 * `jdk.httpserver`, zero new dependencies).
 *
 * `GET /` → the server-rendered HTML page; `GET /api/status.json`
 * → the payload. Everything else 404; non-GET 405. No cookies, no JS.
 *  - The bind follows §4: loopback-only (127.0.0.1:18080) in PUBLIC,
 *    wildcard (0.0.0.0:18080) in FRIENDS. A bind failure refuses the
 *    transition honestly (copy names the port, never a pid or path).
 * Security headers on EVERY response: no-store, nosniff,
 *    no-referrer, `default-src 'none'` CSP.
 * The limiter is PUBLIC-aware (the [RealmStatusRateLimiter]).
 * Connection posture: a BOUNDED request executor (fixed threads
 *    + bounded queue — never an unbounded thread-per-connection pool) and
 *    the JDK server's request/response time caps set before creation, so
 *    a stalled peer cannot pin a thread forever.
 * Torn-render discipline: every render snapshots the served
 *    state at request entry and is TOMBSTONE-CHECKED before its first
 *    byte — a render straddling a flip to OFF is closed with NO response,
 *    so no post-OFF payload ever carries `public.*` or an advertised
 *    address.
 */
internal class RealmStatusServer(
    private val port: Int = PAGE_PORT,
    /** One render capture — runs ONCE per request on the caller's thread;
     * [hostView] is true only for the host's own loopback view in PUBLIC
     * (the §8.7 blocks); null = cannot currently serve (honest 503). */
    private val captureRender: (hostView: Boolean) -> CapturedRender?,
    /** The served-state generation; a mismatch with the capture's is the
     * OFF-flip tombstone. */
    private val servedGeneration: () -> Long,
    /** The visibility at request entry (the limiter's keying mode). */
    private val visibilityAtEntry: () -> RealmStatusVisibility,
    /** The live limiter (re-keyable through the settings test hook). */
    private val limiterProvider: () -> RealmStatusRateLimiter,
    private val logLine: (String) -> Unit = {},
) {
    /** One captured render: the payload JSON plus the page-only extras the
     * HTML needs (host view flag, the plain-LAN join line). */
    data class CapturedRender(
        val generation: Long,
        val payload: JSONObject,
        val hostView: Boolean,
        val pageLanAddress: String?,
    )

    @Volatile private var server: HttpServer? = null

    /** The actually-bound port (readback for the ephemeral-port test lane
     * and honest introspection); null while stopped. */
    @Volatile var boundPort: Int? = null
        private set

    private val generationCounter = AtomicLong(0)

    /** The tombstone generation: renders compare their capture's value
     * against this before the first byte (§4). */
    fun currentGeneration(): Long = generationCounter.get()

    private fun bumpGeneration() {
        generationCounter.incrementAndGet()
    }

    /** Binds the listener; false (with the honest copy) when 18080 is taken. */
    fun start(bindWildcard: Boolean): StartOutcome {
        stop()
        bumpGeneration()
        return try {
            val address = if (bindWildcard) {
                InetSocketAddress(InetAddress.getByName(WILDCARD_BIND), port)
            } else {
                InetSocketAddress(InetAddress.getByName(LOOPBACK_BIND), port)
            }
            applyConnectionTimeouts()
            val created = HttpServer.create(address, REQUEST_BACKLOG)
            created.executor = boundedExecutor()
            created.createContext("/") { exchange -> handle(exchange) }
            created.start()
            server = created
            boundPort = created.address.port
            logLine(
                "realm-status page listening on " +
                    (if (bindWildcard) "0.0.0.0" else "127.0.0.1") + ":$port",
            )
            StartOutcome.Started
        } catch (failure: java.net.BindException) {
            logLine("realm-status page bind refused: ${failure.message}")
            StartOutcome.BindConflict
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            logLine("realm-status page start failed: ${failure.message}")
            StartOutcome.Failed(failure.message ?: failure.javaClass.simpleName)
        }
    }

    sealed class StartOutcome {
        object Started : StartOutcome()
        object BindConflict : StartOutcome()
        data class Failed(val detail: String) : StartOutcome()
    }

    /** Bounded drain (§4's rebind ordering): stop accepting, give in-flight
     * exchanges the bounded delay, then close. */
    fun stop() {
        server?.let { current ->
            runCatching { current.stop(STOP_DELAY_SECONDS) }
        }
        server = null
        boundPort = null
        bumpGeneration()
    }

    val isRunning: Boolean get() = server != null

    private fun boundedExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
        EXECUTOR_THREADS,
        EXECUTOR_THREADS,
        EXECUTOR_KEEPALIVE_SECONDS,
        TimeUnit.SECONDS,
        // Bounded queue: a flood past the limiter cannot grow memory
        // without limit; the JDK server closes the exchange when the
        // executor refuses it (an honest failure mode, never a hang).
        ArrayBlockingQueue(EXECUTOR_QUEUE_CAPACITY),
    ) { runnable ->
        Thread(runnable, "realm-status-page-http").apply { isDaemon = true }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    // the request dispatch is one reviewed sequence; the early returns
    // are the honest 405/404/429/503 legs (§6.2's negatives)
    private fun handle(exchange: HttpExchange) {
        try {
            exchange.use { http ->
                val method = http.requestMethod.uppercase()
                if (method != "GET") {
                    respond(http, METHOD_NOT_ALLOWED, "method not allowed", emptyMap())
                    return
                }
                val path = http.requestURI.path ?: "/"
                val wantsJson = path == STATUS_PATH
                if (path != "/" && !wantsJson) {
                    respond(http, NOT_FOUND, "not found", emptyMap())
                    return
                }
                // The limiter keys by the visibility AT REQUEST ENTRY (§6.2).
                val visibility = visibilityAtEntry()
                val limiter = limiterProvider()
                val key = when (visibility) {
                    RealmStatusVisibility.PUBLIC -> limiter.publicKey(
                        peerAddress(http),
                        http.requestHeaders.getFirst(CONNECTING_IP_HEADER),
                    )
                    else -> limiter.friendsKey(peerAddress(http))
                }
                val verdict = limiter.check(key)
                if (verdict.decision == RealmStatusRateLimiter.Decision.LIMITED) {
                    respond(
                        http,
                        TOO_MANY_REQUESTS,
                        "rate limited",
                        mapOf("Retry-After" to verdict.retryAfterSeconds.toString()),
                    )
                    return
                }
                val captured = captureRender(
                    visibility == RealmStatusVisibility.PUBLIC &&
                        key is RealmStatusRateLimiter.Key.Host,
                )
                    ?: run {
                        respond(http, SERVICE_UNAVAILABLE, "status unavailable", emptyMap())
                        return
                    }
                // The tombstone: a render straddling a flip to OFF (or any
                // rebind is closed with NO response (§4; T19).
                if (servedGeneration() != captured.generation) {
                    logLine("realm-status render discarded: generation moved mid-flight")
                    return
                }
                val (contentType, body) = if (wantsJson) {
                    "application/json" to captured.payload.toString()
                } else {
                    "text/html; charset=utf-8" to RealmStatusPage.render(
                        captured.payload,
                        captured.hostView,
                        captured.pageLanAddress,
                    )
                }
                respond(http, OK, body, mapOf("Content-Type" to contentType))
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            logLine("realm-status exchange failed: ${failure.message}")
            runCatching { exchange.close() }
        }
    }

    private fun peerAddress(exchange: HttpExchange): String =
        runCatching { exchange.remoteAddress.address.hostAddress ?: "" }.getOrDefault("")

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
        extraHeaders: Map<String, String>,
    ) {
        val headers = exchange.responseHeaders
        // Security headers on EVERY response (§6.2).
        headers.set("Cache-Control", "no-store")
        headers.set("X-Content-Type-Options", "nosniff")
        headers.set("Referrer-Policy", "no-referrer")
        headers.set("Content-Security-Policy", "default-src 'none'")
        extraHeaders.forEach { (name, value) -> headers.set(name, value) }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { stream -> stream.write(bytes) }
    }

    /** The JDK server's request/response caps (the slow-peer posture;
     * set only when absent so a host-configured value wins). */
    private fun applyConnectionTimeouts() {
        setTimeoutOnce("sun.net.httpserver.maxReqTime", REQUEST_TIMEOUT_SECONDS)
        setTimeoutOnce("sun.net.httpserver.maxRspTime", REQUEST_TIMEOUT_SECONDS)
    }

    private fun setTimeoutOnce(key: String, seconds: String) {
        if (System.getProperty(key) == null) {
            runCatching { System.setProperty(key, seconds) }
        }
    }

    companion object {
        /** The page port: the tunnel-lane convention series (the
         * friends' world forwarder convention is 18085). Fixed, not user
         * input — and NEVER a game port: 3724/8085 stay the game's. */
        const val PAGE_PORT = 18080

        const val STATUS_PATH = "/api/status.json"

        private const val LOOPBACK_BIND = "127.0.0.1"
        private const val WILDCARD_BIND = "0.0.0.0"

        private const val CONNECTING_IP_HEADER = "CF-Connecting-IP"

        // Status codes (named; detekt MagicNumber).
        private const val OK = 200
        private const val METHOD_NOT_ALLOWED = 405
        private const val NOT_FOUND = 404
        private const val TOO_MANY_REQUESTS = 429
        private const val SERVICE_UNAVAILABLE = 503

        /** The connection posture: bounded executor + bounded queue
         * + capped request lifetimes. */
        private const val EXECUTOR_THREADS = 4
        private const val EXECUTOR_QUEUE_CAPACITY = 64
        private const val EXECUTOR_KEEPALIVE_SECONDS = 30L
        private const val REQUEST_TIMEOUT_SECONDS = "30"
        private const val REQUEST_BACKLOG = 64
        private const val STOP_DELAY_SECONDS = 1
    }
}
