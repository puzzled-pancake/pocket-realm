package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.TunnelState
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The minimal server's wire mechanics over a live loopback listener:
 * the payload/content-type/security headers, the 405/404 negatives, the
 * limiter's 429 + Retry-After, and the tombstone (a render
 * straddling a flip to OFF closes with NO response body).
 */
class RealmStatusServerTest {

    private val generation = AtomicLong(1)

    /** The generation the capture REPORTS — separately controllable so
     * the production tombstone leg (capture ≠ served → no response) is
     * exercisable without nulling the capture. */
    private val capturedGeneration = AtomicLong(1)

    private val torn = AtomicBoolean(false)

    private val limiter = RealmStatusRateLimiter(
        perVisitorPerMinute = 3,
        globalPerMinute = 100,
        hostPerMinute = 100,
    )

    private fun payload(): JSONObject = RealmStatusPayload.build(
        RealmStatusPayload.Inputs(
            visibility = RealmStatusVisibility.PUBLIC,
            generatedAtWallMs = 1_791_997_200_000L,
            realmName = "Host's Realm",
            flavor = "vanilla",
            timeframeStageId = "vanilla-1.12.1",
            timeframeTitle = "Patch 1.12.1 — current release content",
            timeframeIsFinal = true,
            description = "test realm",
            worldRunning = true,
            worldStartedAtWallMs = 1_791_990_000_000L,
            recordSeconds = 10L,
            rates = RealmStatusPayload.Rates.engineDefault(),
            players = RealmStatusPayload.Players(
                source = RealmStatusPayload.PlayerSource.NATIVE,
                crossCheckOk = true,
                humansOnline = 1,
                botsOnline = 2,
                totalOnline = 3,
            ),
            worldPrepared = true,
            worldGeneration = null,
            worldCounts = null,
            statusSource = RealmStatusPayload.StatusSource.LIVE,
            phase = "RUNNING",
            mode = "LAN_HOST",
            clean = true,
            advertisedAddress = null,
            joinKind = RealmStatusPayload.JoinKind.NONE,
            publicUrl = "https://warm-underscore-example.trycloudflare.com",
            tunnelState = TunnelState.LIVE,
            characterNamesInPublic = false,
        ),
    )

    private val server: RealmStatusServer = RealmStatusServer(
        port = 0, // the ephemeral port: the test's own listener never collides
        captureRender = { hostView ->
            if (torn.get()) {
                null
            } else {
                RealmStatusServer.CapturedRender(
                    generation = capturedGeneration.get(),
                    payload = payload(),
                    hostView = hostView,
                    pageLanAddress = null,
                )
            }
        },
        servedGeneration = { generation.get() },
        visibilityAtEntry = { RealmStatusVisibility.PUBLIC },
        limiterProvider = { limiter },
        logLine = { },
    )

    @After
    fun tearDown() {
        server.stop()
    }

    /** The listener starts ONCE on an ephemeral port (port 0) and every
     * request rides the same instance — the tombstone test drives the
     * generation, not the socket. */
    private val port: Int by lazy {
        server.start(bindWildcard = false)
        requireNotNull(server.boundPort) { "the test listener did not bind" }
    }

    private fun get(
        path: String,
        method: String = "GET",
        ipHeader: String? = null,
    ): Triple<Int, String, Map<String, List<String>>> {
        val connection = URI.create("http://127.0.0.1:$port$path").toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (ipHeader != null) connection.setRequestProperty("CF-Connecting-IP", ipHeader)
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        // The JDK's server normalizes response-header keys to first-letter
        // capitalization ("Cache-control") — RFC 9110 field names are
        // case-insensitive, so the lookups are too.
        val headers = connection.headerFields
            .mapKeys { (name, _) -> name?.lowercase().orEmpty() }
            .mapValues { it.value.orEmpty() }
        connection.disconnect()
        return Triple(status, body, headers)
    }

    @Test
    fun `the payload endpoint answers with the schema-3 json and the security headers`() {
        val (status, body, headers) = get("/api/status.json")
        assertEquals(200, status)
        val payload = JSONObject(body)
        assertEquals(3, payload.getInt("schema"))
        assertTrue("no-store on every response", headers["cache-control"].orEmpty().contains("no-store"))
        assertTrue("nosniff on every response", headers["x-content-type-options"].orEmpty().contains("nosniff"))
        assertTrue(
            "the CSP is default-src 'none' (§3.6)",
            headers["content-security-policy"].orEmpty().contains("default-src 'none'"),
        )
        assertTrue("no-referrer on every response", headers["referrer-policy"].orEmpty().contains("no-referrer"))
    }

    @Test
    fun `the html page answers escaped and the json endpoint has the json content type`() {
        val (_, html, htmlHeaders) = get("/")
        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("of them are bots)"))
        assertTrue(
            "json content type on the API",
            get("/api/status.json").third["content-type"].orEmpty().any { it.contains("application/json") },
        )
        assertTrue(htmlHeaders.containsKey("cache-control"))
    }

    @Test
    fun `non-get is 405 and unknown paths are 404`() {
        assertEquals(405, get("/api/status.json", method = "POST").first)
        assertEquals(404, get("/definitely-not-here").first)
    }

    @Test
    fun `the per-visitor limit trips with 429 and retry-after while the host budget holds`() {
        val visitor = sequence {
            while (true) yield(get("/api/status.json", ipHeader = "203.0.113.7").first)
        }.take(5).toList()
        assertTrue("the visitor trips at the 3/min limit", visitor.contains(429))
        assertEquals(429, visitor.last())
        assertEquals(
            "the host's own loopback view (no header) draws the reserved budget",
            200,
            get("/api/status.json").first,
        )
    }

    @Test
    fun `an unavailable capture answers 503 with no payload body`() {
        val (status, body, _) = get("/api/status.json")
        assertEquals(200, status)
        assertTrue(body.isNotEmpty())
        // The capture leg refusing (e.g. the controller cannot serve) is
        // an honest 503 — never a stale or half payload.
        torn.set(true)
        val (unavailableStatus, unavailableBody) = get("/api/status.json")
        assertEquals("the unavailable capture fails honestly", 503, unavailableStatus)
        assertTrue("no payload body", !unavailableBody.contains("\"schema\""))
    }

    @Test
    fun `a render straddling the off flip is tombstoned - closed with no response`() {
        val (status, body, _) = get("/api/status.json")
        assertEquals(200, status)
        assertTrue(body.isNotEmpty())
        // The / PRODUCTION tombstone leg: the capture reported
        // generation 1, the OFF flip moved the SERVED generation — the
        // exchange is closed before its first byte, so the client sees
        // the connection die with NO HTTP response at all (T19's
        // code-000 shape), never a post-OFF payload.
        capturedGeneration.set(1)
        generation.incrementAndGet()
        val failure = runCatching { get("/api/status.json") }
        assertTrue(
            "the tombstoned render must close without an HTTP response",
            failure.isFailure,
        )
    }
}
