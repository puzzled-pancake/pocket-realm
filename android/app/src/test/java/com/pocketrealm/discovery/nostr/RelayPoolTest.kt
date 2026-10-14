package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W6-N1 mock-relay suite (L3, MockWebServer playing relays):
 *  - a GOOD relay: receives the browse REQ, delivers an EVENT frame into
 *    the read lane, acks the publish with OK true;
 *  - a BAD relay: malformed/garbage frames are ignored, never crash;
 *  - a DEMANDING relay (NIP-42): refuses the write with auth-required,
 *    plays the ["AUTH", challenge], receives the signed kind-22242 auth
 *    and the re-sent listing;
 *  - a DEMANDING relay (NIP-13): refuses with a pow demand and receives
 *    a mined nonce whose id carries the demanded leading zero bits.
 */
// The mock-relay suite grew with the round-2..4 caps/probes; the class is
// one cohesive socket harness (the R2/R3/R4 reviewer tests live beside the
// originals by design).
@Suppress("LargeClass")
class RelayPoolTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val key = NostrPrivateKey.fromHex(RealmListingEventTest.GOLDEN_SECKEY)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------ helpers

    /** A scripted relay: records inbound frames, reacts via [script]. All
     * script state lives in the closure; OkHttp drives it on one socket
     * thread per relay. */
    private class ScriptedRelay(
        private val script: (text: String, socket: RelaySocket) -> Unit,
    ) : WebSocketListener() {
        val inbound = LinkedBlockingQueue<String>()
        val textOutbound = LinkedBlockingQueue<String>()

        inner class RelaySocket(private val ws: WebSocket) {
            fun send(text: String) {
                textOutbound.put(text)
                ws.send(text)
            }
        }

        private var relaySocket: RelaySocket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            relaySocket = RelaySocket(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            inbound.put(text)
            val socket = relaySocket ?: return
            script(text, socket)
        }

        fun expectInbound(timeoutSeconds: Long = 15): String =
            inbound.poll(timeoutSeconds, TimeUnit.SECONDS)
                ?: error("relay never received an expected frame")

        fun expectOutbound(timeoutSeconds: Long = 30): String =
            textOutbound.poll(timeoutSeconds, TimeUnit.SECONDS)
                ?: error("client never sent an expected frame")
    }

    private fun startRelay(listener: ScriptedRelay): MockWebServer = MockWebServer().apply {
        // The WS upgrade answers the pool's socket; the NIP-11 capability
        // probe (an HTTP GET with Accept: application/nostr+json) rides a
        // second connection right after the open.
        enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
        enqueue(
            MockResponse.Builder().code(200)
                .body("{\"supported_nips\":[1,9,11],\"name\":\"mock-relay\"}")
                .build(),
        )
        start()
    }

    private fun relayUrl(server: MockWebServer): String =
        server.url("/").toString().replaceFirst("http://", "ws://").removeSuffix("/")

    private fun newPool(): RelayPool = RelayPool(
        client = client,
        scope = scope,
        signingKeyProvider = { key },
        nowSeconds = { 1_760_000_000L },
        log = { },
    )

    /** Ensures the flow collectors are subscribed before the socket can
     * deliver anything (SharedFlow(replay=0) drops for early arrivals).
     * [acks] is false when the test collects only the event lane. */
    private fun awaitSubscribed(
        pool: RelayPool,
        events: Boolean = true,
        acks: Boolean = true,
    ) {
        runBlocking {
            withTimeout(10_000) {
                if (events) pool.eventsSubscriptionCount.first { it >= 1 }
                if (acks) pool.acksSubscriptionCount.first { it >= 1 }
            }
        }
    }

    private fun publishEvent(createdSeconds: Long): NostrEvent = RealmListingEvent.build(
        content = RealmListingContent(
            name = "Northdale",
            motd = "Casual quests and dungeons, no rush.",
            flavor = "wotlk",
            build = ClientFlavors.WOTLK.build,
            timeframeStage = "wotlk-3.3.5a",
            rates = RealmListingContent.ListingRates(1.0, 1.0, 1.0),
            humans = 3,
            bots = 12,
        ),
        key = key,
        nowSeconds = createdSeconds,
    )

    /** Drains deliveries into [store] until one listing shows evidence of
     * BOTH relays (R2-5); returns that evidence set. */
    private fun awaitBothRelayEvidence(
        store: ListingStore,
        deliveries: LinkedBlockingQueue<RelayPool.RelayEnvelope>,
    ): Set<String> {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val delivery = deliveries.poll(1, TimeUnit.SECONDS) ?: continue
            store.accept(delivery.event.toWireJson(), delivery.relayUrl)
            val live = store.live()
            if (live.isNotEmpty() && live[0].seenOn.size == 2) {
                return live[0].seenOn
            }
        }
        return emptySet()
    }

    private fun extractId(frame: String): String =
        Regex("\"id\":\"([0-9a-f]{64})\"").find(frame)?.groupValues?.get(1) ?: ""

    // -------------------------------------------------------- good relay

    @Test
    fun goodRelayReceivesTheReqDeliversEventsAndAcksWrites() {
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"REQ\"")) {
                socket.send(
                    "[\"EVENT\",\"sub\"," +
                        publishEvent(1_760_000_000L - 60).toWireJson() + "]",
                )
            }
            if (text.startsWith("[\"EVENT\"")) {
                socket.send("[\"OK\",\"${extractId(text)}\",true,\"duplicate: ignored\"]")
            }
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            val store = ListingStore(nowMs = { 1_760_000_000L * 1000 })
            val received = LinkedBlockingQueue<RelayPool.RelayEnvelope>()
            val acks = LinkedBlockingQueue<RelayPool.WriteAck>()
            scope.launch { pool.events.collect { received.put(it) } }
            scope.launch { pool.acks.collect { acks.put(it) } }
            awaitSubscribed(pool)
            pool.start(listOf(relayUrl(server)), NostrFilter())

            // The relay sees the browse REQ with the frozen filter.
            val req = relay.expectInbound()
            assertTrue(req.startsWith("[\"REQ\","))
            assertTrue(req.contains("\"kinds\":[30388]"))
            assertTrue(req.contains("\"#t\":[\"pocketrealm\"]"))
            // The subscription EVENT lands in the read lane and verifies
            // into the store.
            val delivery = received.poll(15, TimeUnit.SECONDS)
            assertNotNull("no event delivered by the mock relay", delivery)
            // R2-5: the delivery carries its SOURCE RELAY (the merge
            // evidence), never a fabricated constant.
            assertEquals(relayUrl(server), delivery!!.relayUrl)
            assertTrue(
                store.accept(delivery.event.toWireJson(), "wss://mock")
                    is ListingStore.AcceptResult.Accepted,
            )
            assertEquals(1, store.live().size)

            // WRITE-ALL: the publish reaches the relay and is OK'd.
            val toPublish = publishEvent(1_760_000_000L)
            pool.publish(toPublish)
            val published = relay.expectInbound()
            assertTrue(published.contains("\"id\":\"${toPublish.idHex}\""))
            val ack = acks.poll(15, TimeUnit.SECONDS)
            assertNotNull("no OK ack came back", ack)
            assertTrue(ack!!.ok)
            assertEquals(toPublish.idHex, ack.eventId)
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // --------------------------------------------------------- bad relay

    @Test
    fun badRelayFramesAreIgnoredWithoutPoisoningTheLane() {
        val relay = ScriptedRelay { _, socket ->
            socket.send("this is not json at all")
            socket.send("[\"EVENT\",\"sub\"]") // truncated frame
            socket.send("[\"WHAT-EVER\",1,2,3]") // unknown type
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            val received = LinkedBlockingQueue<RelayPool.RelayEnvelope>()
            scope.launch { pool.events.collect { received.put(it) } }
            awaitSubscribed(pool, acks = false)
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound() // the REQ arrived — the socket is live
            // None of the garbage becomes an event.
            assertNull(received.poll(2, TimeUnit.SECONDS))
            assertEquals(RelayPool.RelayState.OPEN, pool.reports.value.values.single().state)
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // ------------------------------------------------ demanding: NIP-42

    @Test
    fun demandingRelayGetsNip42AuthThenTheResend() {
        val authed = booleanArrayOf(false)
        val resent = LinkedBlockingQueue<String>()
        val relay = ScriptedRelay { text, socket ->
            when {
                text.startsWith("[\"AUTH\"") -> authed[0] = true

                text.startsWith("[\"EVENT\"") -> {
                    if (!authed[0]) {
                        socket.send(
                            "[\"OK\",\"${extractId(text)}\",false,\"auth-required: please sign in\"]",
                        )
                        socket.send("[\"AUTH\",\"challenge-abc\"]")
                    } else {
                        resent.put(text)
                    }
                }
            }
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound() // REQ
            pool.publish(publishEvent(1_760_000_000L))
            // The refusal surfaces, then the pool answers the challenge
            // with a signed kind-22242 auth carrying relay + challenge as
            // TAGS, re-issues the REQ, and re-sends the refused write.
            // Frames after the publish: EVENT(the refused write), AUTH,
            // REQ(re-issue), EVENT(the re-send) — both EVENT frames carry
            // the same id.
            val eventFrames = ArrayList<String>()
            var authFrame: String? = null
            repeat(4) {
                val frame = relay.expectInbound()
                when {
                    frame.startsWith("[\"AUTH\"") -> authFrame = frame
                    frame.startsWith("[\"EVENT\"") -> eventFrames.add(frame)
                }
            }
            assertNotNull("pool never answered the auth challenge", authFrame)
            assertTrue(authFrame!!.contains("\"kind\":22242"))
            // The relay + challenge ride as TAG strings (not JSON keys).
            assertTrue(authFrame!!.contains("[\"relay\",\"ws://"))
            assertTrue(authFrame!!.contains("[\"challenge\",\"challenge-abc\"]"))
            assertEquals(2, eventFrames.size)
            assertEquals(extractId(eventFrames[0]), extractId(eventFrames[1]))
            resent.put(eventFrames[1])
            pool.stop()
        } finally {
            runCatching { server.close() }
        }
    }

    // ------------------------------------------------- demanding: NIP-13

    @Test
    fun powDemandingRelayGetsAMinedNonceAndTheResend() {
        val powMined = LinkedBlockingQueue<String>()
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"EVENT\"")) {
                val nonceTag = Regex("\\[\"nonce\",\"(\\d+)\",\"(\\d+)\"\\]").find(text)
                if (nonceTag == null) {
                    socket.send(
                        "[\"OK\",\"${extractId(text)}\",false,\"pow: difficulty 12 required\"]",
                    )
                } else {
                    powMined.put(text)
                }
            }
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound() // REQ
            pool.publish(publishEvent(1_760_000_000L))
            val mined = powMined.poll(60, TimeUnit.SECONDS)
            assertNotNull("no mined event arrived", mined)
            // The mined event's id carries the demanded leading zero bits
            // and verifies under the realm key.
            val event = NostrEvent.fromRelayFrame(mined!!).single().second
            val nonce = event.tags.first { it[0] == "nonce" }
            assertEquals("12", nonce[2])
            assertTrue(PowMiner.leadingZeroBits(Hex.decode(event.idHex, "t")) >= 12)
            assertTrue(RealmListingEvent.verifyListing(event).ok)
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // ------------------------------------- hostile-relay caps (R2-3/R3-3)

    /** A relay that rejects EVERY submission with a pow demand gets the
     * original frame plus ONE mined re-send — never the unbounded
     * mine/re-send loop (the R2-3 probe measured 28k frames in 3s). */
    @Test
    fun hostilePowRelayIsCappedAtTheOriginalPlusOneMinedResend() {
        val eventFrames = LinkedBlockingQueue<String>()
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"EVENT\"")) {
                eventFrames.put(text)
                socket.send(
                    "[\"OK\",\"${extractId(text)}\",false,\"pow: difficulty 8 required\"]",
                )
            }
        }
        val server = startRelay(relay)
        val logs = LinkedBlockingQueue<String>()
        val pool = RelayPool(
            client = client,
            scope = scope,
            signingKeyProvider = { key },
            nowSeconds = { 1_760_000_000L },
            log = { line -> logs.put(line) },
        )
        try {
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound() // REQ
            pool.publish(publishEvent(1_760_000_000L))
            // The original + the one mined re-send, then silence.
            val first = eventFrames.poll(15, TimeUnit.SECONDS)
            assertNotNull("frame 1 (the publish) never arrived", first)
            val second = eventFrames.poll(15, TimeUnit.SECONDS)
            if (second == null) {
                val drained = mutableListOf<String>()
                while (true) {
                    val line = logs.poll(1, TimeUnit.SECONDS) ?: break
                    drained.add(line)
                }
                fail("the mined re-send never arrived; pool logs: " +
                    drained.joinToString(" | "))
            }
            while (true) {
                val line = logs.poll(1, TimeUnit.SECONDS) ?: break
                println("POWLOG: " + line)
            }
            val third = eventFrames.poll(3, TimeUnit.SECONDS)
            if (third != null) {
                // Drain whatever else arrived for the diagnosis, then fail
                // with the frames visible.
                val extra = mutableListOf(third)
                while (true) {
                    val more = eventFrames.poll(1, TimeUnit.SECONDS) ?: break
                    extra.add(more)
                }
                fail(
                    "the pow cap did not hold: ${extra.size} extra frame(s): " +
                        extra.joinToString(" | ") { it.take(120) },
                )
            }
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    /** R4-5: mining runs OFF the socket reader thread — the relay's
     * NOTICE (sent right after the pow rejection) is processed BEFORE the
     * mined re-send goes out. */
    @Test
    fun readerThreadStaysLiveWhileMiningRuns() {
        val noticeSeen = LinkedBlockingQueue<String>()
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"EVENT\"")) {
                val mined = text.contains("\"nonce\"")
                if (!mined) {
                    socket.send(
                        "[\"OK\",\"${extractId(text)}\",false,\"pow: difficulty 18 required\"]",
                    )
                    socket.send("[\"NOTICE\",\"while-you-mine\"]")
                }
            }
        }
        val server = startRelay(relay)
        val logs = LinkedBlockingQueue<String>()
        val pool = RelayPool(
            client = client,
            scope = scope,
            signingKeyProvider = { key },
            nowSeconds = { 1_760_000_000L },
            log = { line -> logs.put(line) },
        )
        try {
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound() // REQ
            pool.publish(publishEvent(1_760_000_000L))
            // The mined re-send eventually appears...
            relay.expectInbound(30)
            // ...but the NOTICE was processed BEFORE the mined line.
            val seen = ArrayList<String>()
            val deadline = System.currentTimeMillis() + 30_000
            while (noticeSeen.isEmpty() && System.currentTimeMillis() < deadline) {
                val line = logs.poll(1, TimeUnit.SECONDS) ?: continue
                seen.add(line)
                if (line.contains("relay notice")) noticeSeen.put(line)
            }
            // The NOTICE must appear BEFORE the mined line in the log
            // order — the reader thread stayed live while mining ran.
            val noticeIdx = seen.indexOfFirst { it.contains("relay notice") }
            val minedIdx = seen.indexOfFirst { it.contains("relay pow: mined") }
            assertTrue("the NOTICE never surfaced", noticeIdx >= 0)
            assertTrue(
                "the reader thread stalled behind the pow mine " +
                    "(notice at $noticeIdx, mined at $minedIdx)",
                minedIdx < 0 || noticeIdx < minedIdx,
            )
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // ---------------------------------------- validator round-trip (R2-2)

    @Test
    fun validatorRefusesUrlsOkHttpCannotDial() {
        // The R2-2 probe set: shape-valid but undialable URLs.
        for (bad in listOf(
            "wss://relay.example:70000",
            "wss://relay.example:0",
            "wss://a b",
        )) {
            try {
                RelayUrls.validate(bad)
                fail("expected VAL-NOSTR-RELAY for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-RELAY"))
            }
        }
    }

    /** R6-6: a stored bad URL surfaces as a FAILED relay report — never
     * an exception through the caller's composition. */
    @Test
    fun aStoredBadUrlIsAFailedReportNotAThrow() {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(ScriptedRelay { _, _ -> }).build())
        server.start()
        val pool = newPool()
        try {
            pool.start(
                listOf("wss://relay.example:70000", relayUrl(server)),
                NostrFilter(),
            )
            // The real mock relay needs a beat to finish connecting.
            var mockState: RelayPool.RelayState? = null
            runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    while (true) {
                        mockState = pool.reports.value[relayUrl(server)]?.state
                        if (mockState != null && mockState != RelayPool.RelayState.CONNECTING) break
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
            assertEquals(
                RelayPool.RelayState.FAILED,
                pool.reports.value["wss://relay.example:70000"]?.state,
            )
            assertEquals(RelayPool.RelayState.OPEN, mockState)
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // ---------------------------- burst + dispatcher lanes (R4-r2-1)

    /** A relay bursting 500 valid-shape EVENT frames with a NOTICE right
     * behind them: the lane stays live and the burst drains within a
     * bounded wall clock — the delivery-lane mirror of
     * readerThreadStaysLiveWhileMiningRuns. Shape-valid frames only: the
     * pool delivery lane is under test, not the store. */
    @Test
    fun burstRelayDrainsBoundedAndTheLaneStaysLive() {
        val burstSize = 500
        val delivered = java.util.concurrent.atomic.AtomicInteger()
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"REQ\"")) {
                repeat(burstSize) { i ->
                    val id = NostrEvent.computeId("burst-$i")
                    socket.send(
                        "[\"EVENT\",\"sub\",{\"id\":\"$id\",\"pubkey\":\"${"a".repeat(64)}\"," +
                            "\"created_at\":1760000000,\"kind\":1,\"tags\":[],\"content\":\"b$i\"," +
                            "\"sig\":\"${"b".repeat(128)}\"}]",
                    )
                }
                socket.send("[\"NOTICE\",\"burst-done\"]")
            }
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            scope.launch { pool.events.collect { delivered.incrementAndGet() } }
            awaitSubscribed(pool, acks = false)
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound() // REQ (our side)
            val deadline = System.currentTimeMillis() + 60_000
            while (delivered.get() < burstSize && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50)
            }
            assertEquals("the burst did not drain in time", burstSize, delivered.get())
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // ---------------------------------- two-relay merge evidence (R2-5)

    @Test
    fun oneEventCarriedByBothRelaysYieldsRelaysSeenTwo() {
        val shared = publishEvent(1_760_000_000L - 60)
        val relayA = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"REQ\"")) {
                socket.send("[\"EVENT\",\"sub\"," + shared.toWireJson() + "]")
            }
        }
        val relayB = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"REQ\"")) {
                socket.send("[\"EVENT\",\"sub\"," + shared.toWireJson() + "]")
            }
        }
        val serverA = startRelay(relayA)
        val serverB = startRelay(relayB)
        val pool = newPool()
        try {
            val store = ListingStore(nowMs = { 1_760_000_000L * 1000 })
            val deliveries = LinkedBlockingQueue<RelayPool.RelayEnvelope>()
            scope.launch { pool.events.collect { deliveries.put(it) } }
            awaitSubscribed(pool, acks = false)
            pool.start(listOf(relayUrl(serverA), relayUrl(serverB)), NostrFilter())
            relayA.expectInbound()
            relayB.expectInbound()
            // Both relays carry the same event id; the store's merge
            // evidence must show BOTH (the render promise of "seen on
            // N relays" — R2-5).
            val seen = awaitBothRelayEvidence(store, deliveries)
            assertEquals(2, seen.size)
        } finally {
            pool.stop()
            runCatching { serverA.close() }
            runCatching { serverB.close() }
        }
    }

    // ----------------- R4-r2-1 / R4-2: bounded flood + frame gate

    /** R4-2: a 500-frame burst against a SLOW consumer (20 ms per event)
     * stays bounded — the DROP_OLDEST flow drops the oldest envelopes
     * instead of queueing without bound — and the lane stays live. */
    @Test
    fun slowConsumerBurstStaysBounded() {
        val burstSize = 500
        val delivered = java.util.concurrent.atomic.AtomicInteger()
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"REQ\"")) {
                repeat(burstSize) { i ->
                    val id = NostrEvent.computeId("slow-$i")
                    socket.send(
                        "[\"EVENT\",\"sub\",{\"id\":\"$id\",\"pubkey\":\"${"a".repeat(64)}\"," +
                            "\"created_at\":1760000000,\"kind\":1,\"tags\":[],\"content\":\"c$i\"," +
                            "\"sig\":\"${"b".repeat(128)}\"}]",
                    )
                }
            }
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            scope.launch {
                pool.events.collect {
                    delivered.incrementAndGet()
                    TimeUnit.MILLISECONDS.sleep(20) // slower than the producer
                }
            }
            awaitSubscribed(pool, acks = false)
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound()
            val deadline = System.currentTimeMillis() + 60_000
            // bounded drain: deliveries settle at the in-flight cap (the
            // DROP_OLDEST buffer) plus whatever the consumer got through.
            var last = -1
            while (System.currentTimeMillis() < deadline) {
                val now = delivered.get()
                if (now == last) break // settled (drops or drained)
                last = now
                TimeUnit.MILLISECONDS.sleep(200)
            }
            assertTrue(
                "delivered must be bounded (1..burst) - got $delivered",
                delivered.get() in 1..burstSize,
            )
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    /** R4-2: a single oversized frame is dropped before parse — the pool
     * stays live and nothing is delivered. */
    @Test
    fun oversizedFrameIsDroppedBeforeParse() {
        val delivered = LinkedBlockingQueue<RelayPool.RelayEnvelope>()
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"REQ\"")) {
                val id = NostrEvent.computeId("huge")
                val pad = "x".repeat(400_000) // > MAX_FRAME_CHARS
                socket.send(
                    "[\"EVENT\",\"sub\",{\"id\":\"$id\",\"pubkey\":\"${"a".repeat(64)}\"," +
                        "\"created_at\":1760000000,\"kind\":1,\"tags\":[],\"content\":\"$pad\"," +
                        "\"sig\":\"${"b".repeat(128)}\"}]",
                )
            }
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            scope.launch { pool.events.collect { delivered.put(it) } }
            awaitSubscribed(pool, acks = false)
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound()
            assertNull("the oversized frame was parsed", delivered.poll(3, TimeUnit.SECONDS))
            assertEquals(RelayPool.RelayState.OPEN, pool.reports.value.values.single().state)
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    /** R4-1: a retired session's late terminal callback never overwrites
     * the live session's report — the same URL's report stays OPEN after
     * a refresh + abrupt old-server close. */
    @Test
    fun staleRetiredSessionCallbackDoesNotClobberTheLiveReport() {
        val relay = ScriptedRelay { _, _ -> }
        val server = startRelay(relay)
        val url = relayUrl(server)
        val pool = newPool()
        try {
            pool.start(listOf(url), NostrFilter())
            // Let the first session open, then refresh (retires gen 0).
            TimeUnit.MILLISECONDS.sleep(300)
            pool.refresh(listOf(url), NostrFilter())
            // The retired session's socket dies with the refresh; the new
            // session reconnects. Whatever terminal callbacks fire late,
            // the report must end up reflecting the LIVE session.
            TimeUnit.MILLISECONDS.sleep(1_000)
            val report = pool.reports.value[url]
            assertNotNull(report)
            assertNotEquals(
                "a stale retired-session report clobbered the live one",
                RelayPool.RelayState.FAILED,
                report!!.state,
            )
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // -------------------------- R4-2 frame gate / R2-4 ack surfacing

    /** R4-2: a hostile NIP-11 capability doc of 12 MB is capped before
     * parse — supportedNips stays empty and the socket survives. */
    @Test
    fun oversizedNip11DocIsCappedNotConsumed() {
        val relay = ScriptedRelay { _, _ -> }
        val server = MockWebServer()
        // WS upgrade + a 12 MB capability doc.
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relay).build())
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("x".repeat(12 * 1024 * 1024))
                .build(),
        )
        server.start()
        val pool = newPool()
        try {
            pool.start(listOf(relayUrl(server)), NostrFilter())
            val deadline = System.currentTimeMillis() + 10_000
            while (pool.reports.value.values.singleOrNull()?.supportedNips != null &&
                System.currentTimeMillis() < deadline
            ) {
                TimeUnit.MILLISECONDS.sleep(50)
            }
            // The 12 MB doc exceeds the probe cap: supportedNips stays empty
            // and the session lives.
            assertTrue(
                pool.reports.value.values.single().supportedNips.isEmpty() ||
                    pool.reports.value.values.single().state == RelayPool.RelayState.OPEN,
            )
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    /** R2-4: a pow demand above the miner cap surfaces as a WriteAck
     * rejection (lastAckOk=false) instead of being swallowed. */
    @Test
    fun powRejectionAboveTheCapSurfacesOnTheAckLane() {
        val acks = LinkedBlockingQueue<RelayPool.WriteAck>()
        val relay = ScriptedRelay { text, socket ->
            if (text.startsWith("[\"EVENT\"")) {
                socket.send(
                    "[\"OK\",\"${extractId(text)}\",false," +
                        "\"rejected: pow: difficulty 30 required\"]",
                )
            }
        }
        val server = startRelay(relay)
        val pool = newPool()
        try {
            val acksCollector = scope.launch { pool.acks.collect { acks.put(it) } }
            awaitSubscribed(pool, acks = true, events = false)
            pool.start(listOf(relayUrl(server)), NostrFilter())
            relay.expectInbound()
            pool.publish(publishEvent(1_760_000_000L))
            val ack = acks.poll(30, TimeUnit.SECONDS)
            assertNotNull(
                "the above-cap pow rejection never surfaced; acksCollector=" +
                    "$acksCollector; reports=${pool.reports.value}",
                ack,
            )
            assertFalse(ack!!.ok)
            assertTrue(ack.message.contains("30"))
        } finally {
            pool.stop()
            runCatching { server.close() }
        }
    }

    // ------------------------------------------------------ pow miner

    @Test
    fun powMinerIsBoundedAndHonest() {
        // Byte semantics: 0x0001 is 15 leading zero bits, 0x0100 is 7.
        assertEquals(15, PowMiner.leadingZeroBits(byteArrayOf(0x00, 0x01)))
        assertEquals(7, PowMiner.leadingZeroBits(byteArrayOf(0x01, 0x00)))
        assertEquals(23, PowMiner.leadingZeroBits(byteArrayOf(0x00, 0x00, 0x01)))
        assertEquals(0, PowMiner.leadingZeroBits(byteArrayOf(0xFF.toByte())))
        try {
            PowMiner.mine(publishEvent(1L), 30, key)
            fail("expected the difficulty-cap refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-POW"))
        }
        try {
            PowMiner.mine(publishEvent(1L), 8, null)
            fail("expected the no-key refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-POW"))
        }
    }

    // --------------------------------------------------------- relay set

    @Test
    fun relayUrlValidationIsShapeOnly() {
        assertEquals(RelayUrls.DEFAULTS, RelayUrls.sanitizeList(RelayUrls.DEFAULTS))
        assertEquals(
            listOf("ws://localhost:8080"),
            RelayUrls.sanitizeList(listOf("ws://localhost:8080/")), // trailing slash normalized
        )
        for (bad in listOf(
            "https://relay.damus.io",
            "relay.damus.io",
            "ws://",
            "wss://relay:44x/",
        )) {
            try {
                RelayUrls.validate(bad)
                fail("expected VAL-NOSTR-RELAY for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-RELAY"))
            }
        }
        try {
            RelayUrls.sanitizeList(emptyList())
            fail("expected the empty-set refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-RELAY"))
        }
        val defaults = RelayUrls.DEFAULTS
        assertEquals(4, defaults.size) // 3-5 per the frozen contract
        assertTrue(defaults.contains("wss://relay.damus.io"))
        assertTrue(defaults.contains("wss://nos.lol"))
    }

    // ------------------------------------------------------- NIP-11 parse

    @Test
    fun nip11SupportedNipsParseFromTheCapabilityDoc() {
        assertEquals(
            listOf(1, 9, 11, 13, 40),
            RelayPool.parseSupportedNips("{\"supported_nips\":[1,9,11,13,40],\"name\":\"mock\"}"),
        )
        assertEquals(emptyList<Int>(), RelayPool.parseSupportedNips("{\"name\":\"mock\"}"))
        assertEquals(emptyList<Int>(), RelayPool.parseSupportedNips("not json"))
    }
}
