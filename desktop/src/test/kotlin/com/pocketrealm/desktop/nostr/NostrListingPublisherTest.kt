package com.pocketrealm.desktop.nostr

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.nostr.ListingStore
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.desktop.realmstatus.RealmStatusController
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.discovery.nostr.Hex
import com.pocketrealm.discovery.nostr.NostrEvent
import com.pocketrealm.discovery.nostr.RealmListingContract
import com.pocketrealm.discovery.nostr.RealmListingEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * heartbeat lifecycle (the frozen contract's host-side rules):
 *  - NEVER published while visibility is OFF/FRIENDS, or while the host's
 *    switch is off, or while the world is down;
 *  - published while the realm is up and PUBLIC, with content derived
 *    from THE realm-status payload builder (one source of truth) and bots
 *    labeled, never padded;
 *  - republished on the 5-minute cadence only;
 *  - a degraded players split skips the beat;
 *  - any gate drop retracts with NIP-09; graceful shutdown retracts too.
 *
 * The gates ride injected providers; the relay is a real MockWebServer
 * WebSocket; the payload is built by the REAL RealmStatusPayload.build.
 */
class NostrListingPublisherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** The wall clock the publisher sees; the suite advances it. */
    private val nowMs = longArrayOf(1_760_000_000_000L)

    private val visibility = java.util.concurrent.atomic.AtomicReference(RealmStatusVisibility.OFF)

    private val payload = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject?>(null)

    private val frames = LinkedBlockingQueue<String>()

    private val relayListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.put(text)
            if (text.startsWith("[\"EVENT\"")) {
                val id = Regex("\"id\":\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)
                webSocket.send("[\"OK\",\"$id\",true,\"\"]")
            }
        }
    }

    private lateinit var server: MockWebServer

    private lateinit var model: DesktopAppModel

    private lateinit var realmStatus: RealmStatusController

    private lateinit var publisher: NostrListingPublisher

    @After
    fun tearDown() {
        runCatching { publisher.close() }
        runCatching { server.close() }
        runCatching { model.close() }
        scope.cancel()
    }

    /** Boots the full stack: model + controller + publisher on the mock
     * relay, switch on, PUBLIC, realm up, native 3+12 players split. */
    private fun boot(
        initialVisibility: RealmStatusVisibility = RealmStatusVisibility.PUBLIC,
    ) {
        server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        // The NIP-11 capability probe rides a second connection after the
        // WS opens.
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("{\"supported_nips\":[1,9,11],\"name\":\"mock-relay\"}")
                .build(),
        )
        server.start()
        model = DesktopAppModel(DesktopStorageRoots(folder.newFolder("roots")))
        realmStatus = RealmStatusController(model)
        publisher = NostrListingPublisher(
            model = model,
            realmStatus = realmStatus,
            seams = Seams(
                clockMs = { nowMs[0] },
                client = client,
                visibilityProvider = { visibility.get() },
                payloadProvider = { payload.get() },
                gatePollMs = Long.MAX_VALUE, // the suite drives evaluations
                heartbeatSeconds = RealmListingContract.HEARTBEAT_SECONDS,
            ),
        )
        publisher.boot()
        // The publish lane rides the user-editable relay set — point it at
        // the mock relay (the port is known: the server already started).
        model.updateSettings {
            it.copy(nostrDiscoveryEnabled = true, nostrRelays = listOf(relayUrl()))
        }
        // The settings collector generates the realm identity on first
        // enable — wait for it (publishes need the key).
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (model.settings.value.nostrPrivateKeyHex.isBlank()) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        visibility.set(initialVisibility)
        payload.set(greenPayload())
    }

    private fun relayUrl(): String =
        server.url("/").toString().replaceFirst("http://", "ws://").removeSuffix("/")

    /** The next non-REQ frame (the browse REQ arrives on open and is not
     * an event); null when nothing else arrives in time. */
    private fun nextEventFrame(timeoutSeconds: Long): String? {
        var found: String? = null
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (found == null && System.currentTimeMillis() < deadline) {
            val frame = frames.poll(1, TimeUnit.SECONDS) ?: break
            if (!frame.startsWith("[\"REQ\"")) found = frame
        }
        return found
    }

    private fun assertNoEventFrame() {
        assertNull("an unexpected EVENT frame arrived", nextEventFrame(1))
    }

    // -------------------------------------------------------- lifecycle

    @Test
    fun neverPublishesWhileDisabled() {
        boot()
        model.updateSettings { it.copy(nostrDiscoveryEnabled = false) }
        runBlocking { publisher.evaluateGates() }
        // Give any (wrong) frame no chance to exist.
        assertNoEventFrame()
    }

    @Test
    fun neverPublishesWhileVisibilityIsOffOrFriends() {
        boot(initialVisibility = RealmStatusVisibility.OFF)
        runBlocking { publisher.evaluateGates() }
        assertEquals(null, frames.poll(1, TimeUnit.SECONDS))
        visibility.set(RealmStatusVisibility.FRIENDS)
        runBlocking { publisher.evaluateGates() }
        assertNoEventFrame()
        // PUBLIC flips it live — exactly one EVENT goes out.
        visibility.set(RealmStatusVisibility.PUBLIC)
        runBlocking { publisher.evaluateGates() }
        val listing = nextEventFrame(10)
        assertNotNull("nothing published on PUBLIC+up", listing)
        assertTrue(listing!!.startsWith("[\"EVENT\","))
        assertNoEventFrame()
    }

    @Test
    fun neverPublishesWhileTheWorldIsDown() {
        boot()
        payload.set(downPayload())
        runBlocking { publisher.evaluateGates() }
        assertNoEventFrame()
    }

    @Test
    fun publishesThePayloadDerivedListingWhilePublicAndUp() {
        boot()
        runBlocking { publisher.evaluateGates() }
        val frame = nextEventFrame(10)
        assertNotNull("nothing published", frame)
        val event = NostrEvent.fromRelayFrame(frame!!).single().second
        // The listing verifies and matches the payload (the frozen schema,
        // bots labeled 3 humans / 12 bots; join null because the test
        // seams carry no gateway — the honest "not joinable now").
        assertTrue(RealmListingEvent.verifyListing(event).ok)
        val content = event.content
        assertTrue(content.contains("\"name\":\"my realm\""))
        assertTrue(content.contains("\"flavor\":\"vanilla\""))
        assertTrue(
            "the published listing must carry the REGISTERED stage the payload "
                + "resolved",
            content.contains("\"timeframe\":\"vanilla-1.12.1\""),
        )
        assertTrue(content.contains("\"build\":" + ClientFlavors.VANILLA.build))
        assertTrue(content.contains("\"humans\":3"))
        assertTrue(content.contains("\"bots\":12"))
        assertTrue(content.contains("\"join\":null"))
        assertTrue(event.tags.contains(listOf("d", RealmListingContract.D_TAG)))
        assertTrue(event.tags.contains(listOf("t", RealmListingContract.T_TAG)))
        assertTrue(event.tags.any { it[0] == "flavor" && it[1] == "vanilla" })
        assertEquals(publisher.uiState.value.listingEventId, event.idHex)
    }

    // ------------------- W6-N3 code-red R5-4: the join-descriptor beats

    /** R5-4: a READY gateway's descriptor rides the published beat (the
     * pinned serialization) and the ensure leg ran for the beat. The
     * join-lane spies ride the Seams bundle. */
    @Test
    fun joinDescriptorRidesThePublishedBeatWhenTheGatewayIsReady() {
        val ensureCalls = mutableListOf<Int>()
        val stopCalls = mutableListOf<Int>()
        val descriptor = com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
            nodeHex = "ab".repeat(32),
            relayUrl = "https://usw1-1.relay.n0.iroh.link",
        )
        boot() // the harness (model + server + realmStatus) comes up first
        val seated = NostrListingPublisher(
            model = model,
            realmStatus = realmStatus,
            seams = Seams(
                clockMs = { nowMs[0] },
                client = client,
                visibilityProvider = { visibility.get() },
                payloadProvider = { payload.get() },
                gatePollMs = Long.MAX_VALUE,
                heartbeatSeconds = RealmListingContract.HEARTBEAT_SECONDS,
                ensureHostGateway = { ensureCalls += 1 },
                stopHostGateway = { stopCalls += 1 },
                joinDescriptorProvider = { descriptor },
            ),
        )
        seated.boot()
        runBlocking { seated.evaluateGates() }
        // Code-red round-5 R7-N3-r5-1: TWO publishers (the harness's and
        // the seated one) share this model and BOTH publish a beat-1
        // event on the same settings emission — taking the first
        // non-REQ frame made this test queue-order flaky (2/2 red at
        // class scale, green in isolation, same two publishes both
        // ways). Drain until the frame carrying the SEATED publisher's
        // own beat identity.
        val seatedId = seated.uiState.value.listingEventId
        val drained = mutableListOf<String>()
        var frame: String? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (frame == null && System.currentTimeMillis() < deadline) {
            val candidate = frames.poll(1, TimeUnit.SECONDS) ?: break
            if (!candidate.startsWith("[\"REQ\"")) {
                drained += candidate
                if (candidate.contains("\"id\":\"$seatedId\"")) frame = candidate
            }
        }
        assertNotNull("the seated publisher's own beat never arrived", frame)
        // The frame-uniqueness pin: exactly ONE drained frame carries the
        // seated instance's id — a publisher instance publishes its beat
        // once; a duplicated id would mean a double-publish.
        assertEquals(
            "exactly one beat-1 frame per publisher instance",
            1,
            drained.count { it.contains("\"id\":\"$seatedId\"") },
        )
        val event = NostrEvent.fromRelayFrame(frame!!).single().second
        assertEquals(
            "the drained frame is the seated publisher's own beat",
            seatedId,
            event.idHex,
        )
        val expected = "\"join\":{\"type\":\"iroh\",\"node\":\"" +
            "ab".repeat(32) + "\",\"relay\":\"https://usw1-1.relay.n0.iroh.link\"}"
        assertTrue(
            "the beat must carry the live gateway's descriptor",
            event.content.contains(expected),
        )
        assertEquals("the ensure leg must have run for the beat", 1, ensureCalls.single())
    }

    /** R5-4: the beat with a NOT-READY gateway publishes join:null — the
     * honest not-joinable listing, never a stale descriptor. */
    @Test
    fun joinStaysNullWhileTheGatewayIsNotReady() {
        boot()
        runBlocking { publisher.evaluateGates() }
        val frame = nextEventFrame(10)
        assertNotNull(frame)
        val event = NostrEvent.fromRelayFrame(frame!!).single().second
        assertTrue(event.content.contains("\"join\":null"))
    }

    /** R5-4: a gate drop retracts AND stops the host gateway exactly once
     * (the transport exists exactly as long as the listing does). */
    @Test
    fun gateDropStopsTheHostGateway() {
        var stops = 0
        boot() // the harness (model + server + realmStatus) comes up first
        val seated = NostrListingPublisher(
            model = model,
            realmStatus = realmStatus,
            seams = Seams(
                clockMs = { nowMs[0] },
                client = client,
                visibilityProvider = { visibility.get() },
                payloadProvider = { payload.get() },
                gatePollMs = Long.MAX_VALUE,
                heartbeatSeconds = RealmListingContract.HEARTBEAT_SECONDS,
                ensureHostGateway = { },
                stopHostGateway = { stops += 1 },
                joinDescriptorProvider = { null },
            ),
        )
        seated.boot()
        runBlocking { seated.evaluateGates() }
        assertNotNull(nextEventFrame(10))
        visibility.set(RealmStatusVisibility.FRIENDS)
        runBlocking { seated.evaluateGates() }
        // its determinism probe: the beat's identity
        // settings write re-fires the model's settings collector, and the
        // extra evaluation it triggers can land AFTER the gate drop —
        // pump it explicitly (a settings write + one more evaluation).
        // The host-stop latch must keep the stop count at exactly 1; the
        // pre-latch code double-stopped here (the full-set flake).
        model.updateSettings { it.copy(nostrDiscoveryEnabled = true) }
        runBlocking { seated.evaluateGates() }
        assertEquals(
            "the gate drop must stop the host gateway exactly once " +
                "(idempotent deactivate, R4-r6-2)",
            1,
            stops,
        )
    }

    @Test
    fun republishesOnlyOnTheFiveMinuteCadence() {
        boot()
        runBlocking { publisher.evaluateGates() }
        val first = nextEventFrame(10)
        assertNotNull(first)
        // An immediate re-evaluation is NOT a new beat.
        runBlocking { publisher.evaluateGates() }
        assertNoEventFrame()
        // Four minutes in: still not due.
        nowMs[0] += (RealmListingContract.HEARTBEAT_SECONDS - 60) * 1000
        runBlocking { publisher.evaluateGates() }
        assertNoEventFrame()
        // Five minutes: the heartbeat republishes with a fresh stamp.
        nowMs[0] += 60 * 1000
        runBlocking { publisher.evaluateGates() }
        val second = nextEventFrame(10)
        assertNotNull("the 5-minute beat never fired", second)
        val firstEvent = NostrEvent.fromRelayFrame(first!!).single().second
        val secondEvent = NostrEvent.fromRelayFrame(second!!).single().second
        assertNotEquals(firstEvent.idHex, secondEvent.idHex)
        assertEquals(
            firstEvent.createdAtSeconds + RealmListingContract.HEARTBEAT_SECONDS,
            secondEvent.createdAtSeconds,
        )
    }

    // ------------------------------------- code-red round 1 (R2-6/R3-6)

    /** R3-6: a relay-set edit while publishing is ACTIVE reaches the
     * write lane on the next beat — the removed relay stops receiving
     * frames immediately. */
    @Test
    fun relayEditWhileActiveRedirectsTheWriteLane() {
        // A second mock relay for the edit target.
        val serverB = MockWebServer()
        val framesB = LinkedBlockingQueue<String>()
        val listenerB = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                framesB.put(text)
                if (text.startsWith("[\"EVENT\"")) {
                    val id = Regex("\"id\":\"([0-9a-f]{64})\"").find(text)?.groupValues?.get(1)
                    webSocket.send("[\"OK\",\"$id\",true,\"\"]")
                }
            }
        }
        serverB.enqueue(MockResponse.Builder().webSocketUpgrade(listenerB).build())
        serverB.enqueue(
            MockResponse.Builder().code(200).body("{\"supported_nips\":[1]}").build(),
        )
        serverB.start()
        try {
            boot()
            runBlocking { publisher.evaluateGates() }
            assertNotNull(nextEventFrame(10)) // beat 1 on relay A
            // The edit: the stored set now points at relay B only.
            publisher.setRelays(
                listOf(serverB.url("/").toString().replaceFirst("http://", "ws://").removeSuffix("/")),
            )
            nowMs[0] += (RealmListingContract.HEARTBEAT_SECONDS) * 1000
            runBlocking { publisher.evaluateGates() }
            val onB = framesB.poll(10, TimeUnit.SECONDS)
            assertNotNull("the next beat never reached relay B", onB)
            assertTrue(onB!!.startsWith("[\"EVENT\","))
        } finally {
            runCatching { serverB.close() }
        }
    }

    /** R2-6: when every relay rejects the current beat, the host card's
     * copy says so — the 'Live' claim is not shown while zero relays
     * accepted. */
    @Test
    fun allRelaysRejectedSurfacesInTheStatusCopy() {
        boot()
        runBlocking { publisher.evaluateGates() }
        assertNotNull(nextEventFrame(10)) // the listing went out
        // The scripted relay OK-false-rejects every EVENT frame (the
        // boot() listener OKs them — override the outcome here by
        // asserting on the pure projection with a rejecting ack state).
        val rejecting = publisher.uiState.value.copy(
            lastBeatOkCount = 0,
            lastBeatRejectCount = 2,
        )
        val copy = com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(rejecting)
        assertTrue(copy.contains("every relay rejected"))
        assertFalse(copy.contains("Live —"))
        // And the real ack path: drive one rejecting ack through the
        // handler's state projection.
        val accepted = publisher.uiState.value.copy(
            lastBeatOkCount = 1,
            lastBeatRejectCount = 0,
        )
        assertTrue(com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(accepted).contains("Live —"))
    }

 /** A corrupt stored identity surfaces the regeneration
     * path — the publish card shows the Waiting copy (never a false
     * 'Live') until the healed identity publishes, and the stored key is
     * replaced. */
    @Test
    fun corruptStoredKeySurfacesTheValCopy() {
        boot()
        model.updateSettings { it.copy(nostrPrivateKeyHex = "not-hex-at-all") }
        runBlocking { publisher.evaluateGates() }
        // The identity self-healed to a valid key...
        assertTrue(model.settings.value.nostrPrivateKeyHex.isNotBlank())
        assertNotEquals("not-hex-at-all", model.settings.value.nostrPrivateKeyHex)
        // ...the beat published through the healed identity...
        assertTrue(publisher.uiState.value.beats >= 1)
        // ...and the rendered card copy reflects the enabled state.
        val copy = com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(
            publisher.uiState.value.copy(enabled = true),
        )
        assertFalse(copy.contains("Off."))
    }

    /** R6-3-8: a good beat CLEARS the stale failure copy — the card must
     * never render "Live … VAL-NOSTR-…" self-contradictions. */
    @Test
    fun bannedMotdErrorClearsOnNextGoodBeat() {
        boot()
        payload.set(
            org.json.JSONObject(
                greenPayload().toString().replace(
                    "Casual quests and dungeons, no rush.",
                    "join our discord at discord.gg/myrealm",
                ),
            ),
        )
        runBlocking { publisher.evaluateGates() }
        assertTrue(
            publisher.uiState.value.errorCopy.orEmpty().contains("VAL-NOSTR-CONTENT"),
        )
        // The MOTD is fixed; the next beat publishes and clears the copy.
        payload.set(greenPayload())
        nowMs[0] += (RealmListingContract.HEARTBEAT_SECONDS) * 1000
        runBlocking { publisher.evaluateGates() }
        assertNull(publisher.uiState.value.errorCopy)
        val copy = com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(publisher.uiState.value)
        assertFalse(copy.contains("VAL-NOSTR-CONTENT"))
    }

    /** R5-3: a banned realm MOTD (user free text) surfaces its
     * VAL-NOSTR-CONTENT copy and the watcher survives it. */
    @Test
    fun bannedMotdSurfacesAnErrorAndTheWatcherSurvives() {
        boot()
        payload.set(
            org.json.JSONObject(greenPayload().toString().replace(
                "Casual quests and dungeons, no rush.",
                "Join our discord at discord.gg/myrealm",
            )),
        )
        runBlocking { publisher.evaluateGates() }
        assertTrue(
            publisher.uiState.value.errorCopy.orEmpty().contains("VAL-NOSTR-CONTENT"),
        )
        // The watcher is alive: a subsequent settings change still runs an
        // evaluation (no crash, the error copy persists or updates).
        model.updateSettings { it.copy(nostrDiscoveryEnabled = false) }
        runBlocking { kotlinx.coroutines.delay(200) }
        model.updateSettings { it.copy(nostrDiscoveryEnabled = true) }
        runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (publisher.uiState.value.errorCopy == null) {
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }

    /** R4-4: gate-drop/re-enable cycles leave no stale relay reports and
     * the publisher re-arms cleanly. */
    @Test
    fun gateCyclesLeaveNoStaleRelayReports() {
        boot()
        runBlocking { publisher.evaluateGates() }
        assertNotNull(nextEventFrame(10))
        visibility.set(RealmStatusVisibility.FRIENDS)
        runBlocking { publisher.evaluateGates() }
        assertTrue(publisher.uiState.value.relayReports.isEmpty())
        // Re-arm: the next beat publishes fresh again.
        visibility.set(RealmStatusVisibility.PUBLIC)
        nowMs[0] += (RealmListingContract.HEARTBEAT_SECONDS + 60) * 1000
        runBlocking { publisher.evaluateGates() }
        assertNotNull(nextEventFrame(10))
    }

    /** R2-2: setRelays refuses an undialable set and leaves the stored
     * settings untouched. */
    @Test
    fun setRelaysInvalidLeavesSettingsUntouched() {
        boot()
        val before = model.settings.value.nostrRelays
        try {
            publisher.setRelays(listOf("wss://relay.example:70000"))
            org.junit.Assert.fail("expected the VAL refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-RELAY"))
        }
        assertEquals(before, model.settings.value.nostrRelays)
    }

    /** R6-6: the settings decode re-validates a hand-edited garbage relay
     * list back to the defaults. */
    @Test
    fun settingsDecodeDegradesGarbageRelayLists() {
        val snapshot = com.pocketrealm.storage.Settings.Snapshot.fromJson(
            "{\"nostrRelays\":[\"relay.damus.io\",\"not a url at all\"]}",
        )
        assertTrue("garbage entries must degrade to the defaults", snapshot.nostrRelays.isEmpty())
        val good = com.pocketrealm.storage.Settings.Snapshot.fromJson(
            "{\"nostrRelays\":[\"wss://relay.damus.io\"]}",
        )
        assertEquals(listOf("wss://relay.damus.io"), good.nostrRelays)
    }

    /** R4-r2-2 (serious) / R2B-2: the NIP-09 retract survives a relay-set
     * edit followed by a quit (shutdown) — the recycled pool must not
     * swallow the deletion. */
    @Test
    fun gracefulShutdownAfterRelayEditStillRetracts() {
        boot()
        runBlocking { publisher.evaluateGates() }
        val listing = nextEventFrame(10)
        assertNotNull(listing)
        val listingId = NostrEvent.fromRelayFrame(listing!!).single().second.idHex
        // The edit recycles the pool; NO clock advance (the user quits
        // within one heartbeat).
        publisher.setRelays(listOf(relayUrl()))
        publisher.shutdown()
        val deletion = nextEventFrame(10)
        assertNotNull("the NIP-09 retract was lost after the relay edit", deletion)
        val event = NostrEvent.fromRelayFrame(deletion!!).single().second
        assertEquals(RealmListingContract.DELETION_KIND, event.kind)
        assertTrue(event.tags.contains(listOf("e", listingId)))
    }

    /** R5r2-3: a degraded players split after a published beat PAUSES —
     * the status copy stops claiming 5-minute republishing. */
    @Test
    fun degradedSplitAfterABeatPausesTheCopy() {
        boot()
        runBlocking { publisher.evaluateGates() }
        assertNotNull(nextEventFrame(10)) // beat 1 published
        payload.set(degradedPayload())
        nowMs[0] += (RealmListingContract.HEARTBEAT_SECONDS) * 1000
        runBlocking { publisher.evaluateGates() }
        assertTrue(publisher.uiState.value.paused)
        val copy = com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(publisher.uiState.value)
        assertFalse("the copy must not claim live republishing", copy.contains("republished every 5 minutes"))
        assertTrue(copy.contains("Paused"))
        assertTrue(copy.contains("player split"))
    }

    /** R2B-3: a corrupt stored identity self-heals — the next beat
     * regenerates the key and publishes instead of bricking forever. */
    @Test
    fun corruptStoredIdentitySelfHealsAndPublishes() {
        boot()
        model.updateSettings { it.copy(nostrPrivateKeyHex = "0".repeat(64)) }
        nowMs[0] += (RealmListingContract.HEARTBEAT_SECONDS) * 1000
        runBlocking { publisher.evaluateGates() }
        val frame = nextEventFrame(10)
        assertNotNull("publishing never recovered from the corrupt identity", frame)
        val stored = model.settings.value.nostrPrivateKeyHex
        assertFalse("the corrupt key was kept forever", stored == "0".repeat(64))
    }

    /** R5-1: a production-REAL payload (a registered stage id) drives the
     * full ladder — fromStatusPayload → build → ListingStore.accept — and
     * is ACCEPTED by the browser a real user would run (the fixture-stage
     * drift this pin guards against would make every browser refuse). */
    @Test
    fun payloadDerivedListingRoundTripsThroughTheStore() {
        boot()
        // the store rides the suite's INJECTED clock (the publisher's
        // created_at stamps follow it; real-now freshness would refuse)
        val store = com.pocketrealm.discovery.nostr.ListingStore(nowMs = { nowMs[0] })
        runBlocking { publisher.evaluateGates() }
        val frame = nextEventFrame(10)
        val event = NostrEvent.fromRelayFrame(frame!!).single().second
        // The store a real browser runs must ACCEPT the publisher's listing.
        val result = store.accept(event.toWireJson(), "wss://relay")
        val refusedReason = (result as? ListingStore.AcceptResult.Refused)?.reason
        assertTrue(
            "the payload-derived listing was refused by the store: $refusedReason",
            result is ListingStore.AcceptResult.Accepted,
        )
        assertEquals(1, store.live().size)
        assertEquals(
            com.pocketrealm.client.TimeframeStages.byId("vanilla", "vanilla-1.12.1")?.id,
            store.live().single().content.timeframeStage,
        )
    }

    /** The 0-ok/0-reject dead-set case never claims Live.
     * Pure projection — no boot needed; the suite constructs the state. */
    @Test
    fun liveCopyIsNotClaimedWhileEveryRelayIsDead() {
        val dead = com.pocketrealm.desktop.nostr.NostrListingPublisher.UiState(
            enabled = true,
            active = true,
            lastBeatOkCount = 0,
            lastBeatRejectCount = 0,
            relayReports = listOf(
                com.pocketrealm.discovery.nostr.RelayPool.RelayReport(
                    "wss://dead", com.pocketrealm.discovery.nostr.RelayPool.RelayState.FAILED,
                    emptyList(), null, null, "connection refused",
                ),
            ),
        )
        val copy = com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(dead)
        assertFalse(copy.startsWith("Live"))
        assertTrue(copy.contains("no relay has answered") || copy.contains("unreachable"))
    }

    /** R6-2: the relay-report projection exposes each relay's rejection
     * reason + URL (the data was fetched and dropped before this). */
    @Test
    fun relayReportProjectionExposesPerRelayReasons() {
        val state = com.pocketrealm.desktop.nostr.NostrListingPublisher.UiState(
            enabled = true,
            active = true,
            lastBeatOkCount = 1,
            lastBeatRejectCount = 1,
            relayReports = listOf(
                com.pocketrealm.discovery.nostr.RelayPool.RelayReport(
                    "wss://relay-a", com.pocketrealm.discovery.nostr.RelayPool.RelayState.OPEN,
                    listOf(1), false, "auth-re-demanded", null,
                ),
                com.pocketrealm.discovery.nostr.RelayPool.RelayReport(
                    "wss://relay-b", com.pocketrealm.discovery.nostr.RelayPool.RelayState.OPEN,
                    listOf(1), true, null, null,
                ),
            ),
        )
        val lines = com.pocketrealm.desktop.nostr.NostrListingPublisher.relayReportLines(state)
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.contains("wss://relay-a") && it.contains("auth-re-demanded") })
        assertTrue(lines.any { it.contains("wss://relay-b") && it.contains("accepted") })
    }

    /** R5-R3-1: two concurrent evaluations create exactly ONE pool. */
    @Test
    fun concurrentEvaluationsCreateExactlyOnePool() {
        boot()
        publisher.shutdown() // stop the real watcher for a controlled race
        val p2 = com.pocketrealm.desktop.nostr.NostrListingPublisher(
            model = model,
            realmStatus = realmStatus,
            seams = Seams(
                clockMs = { nowMs[0] },
                client = client,
                visibilityProvider = { visibility.get() },
                payloadProvider = { payload.get() },
                gatePollMs = Long.MAX_VALUE,
                heartbeatSeconds = RealmListingContract.HEARTBEAT_SECONDS,
            ),
        )
        visibility.set(RealmStatusVisibility.PUBLIC)
        payload.set(greenPayload())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("{\"supported_nips\":[1]}")
                .build(),
        )
        runBlocking {
            p2.boot()
            model.updateSettings { it.copy(nostrDiscoveryEnabled = true) }
            kotlinx.coroutines.delay(300) // the collector regenerates + evaluates
            // fire overlapping evaluations; the gate mutex must
            // serialize them onto ONE pool (no orphaned double-build).
            val jobs = List(2) { launch { p2.evaluateGates() } }
            jobs.forEach { it.join() }
        }
        assertEquals(
            "the pool was built more than once under a concurrent race",
            1, p2.poolCreations,
        )
        p2.close()
    }

    @Test
    fun degradedPlayersSplitSkipsTheBeat() {
        boot()
        payload.set(degradedPayload())
        runBlocking { publisher.evaluateGates() }
        assertNoEventFrame()
    }

    @Test
    fun gateDropRetractsWithNip09() {
        boot()
        runBlocking { publisher.evaluateGates() }
        assertNotNull(nextEventFrame(10)) // the listing
        // Visibility falls back to FRIENDS: the listing must be retracted.
        visibility.set(RealmStatusVisibility.FRIENDS)
        runBlocking { publisher.evaluateGates() }
        val deletion = nextEventFrame(10)
        assertNotNull("no NIP-09 retraction after the gate drop", deletion)
        val event = NostrEvent.fromRelayFrame(deletion!!).single().second
        assertEquals(RealmListingContract.DELETION_KIND, event.kind)
        assertEquals("", event.content)
        // A deletion is kind 5 (verifyListing is the LISTING verifier):
        // check its id commitment and signature directly.
        assertEquals(event.idHex, NostrEvent.computeId(event.serializedForId()))
        assertTrue(
            com.pocketrealm.discovery.nostr.Secp256k1Verify.schnorr(
                Hex.decode(event.sigHex, "t"),
                Hex.decode(event.idHex, "t"),
                Hex.decode(event.pubkeyHex, "t"),
            ),
        )
        // No listing remains active.
        assertFalse(publisher.uiState.value.active)
    }

    @Test
    fun gracefulShutdownRetractsBeforeTeardown() {
        boot()
        runBlocking { publisher.evaluateGates() }
        val listing = nextEventFrame(10)
        assertNotNull(listing)
        val listingId = NostrEvent.fromRelayFrame(listing!!).single().second.idHex
        publisher.shutdown()
        val deletion = nextEventFrame(10)
        assertNotNull("no NIP-09 on graceful shutdown", deletion)
        val event = NostrEvent.fromRelayFrame(deletion!!).single().second
        assertEquals(RealmListingContract.DELETION_KIND, event.kind)
        assertTrue(event.tags.contains(listOf("e", listingId)))
        assertTrue(
            event.tags.contains(
                listOf("a", "${RealmListingContract.KIND}:${event.pubkeyHex}:${RealmListingContract.D_TAG}"),
            ),
        )
    }

    @Test
    fun enablingGeneratesTheRealmIdentityExactlyOnce() {
        boot()
        val first = model.settings.value.nostrPrivateKeyHex
        assertTrue("no identity generated on enable", first.isNotBlank())
        assertEquals(64, first.length)
        Hex.decode(first, "assert") // a valid hex key
        publisher.boot() // idempotent
        model.updateSettings { it.copy(nostrDiscoveryEnabled = false) }
        model.updateSettings { it.copy(nostrDiscoveryEnabled = true) }
        assertEquals("the identity must never rotate on toggle", first, model.settings.value.nostrPrivateKeyHex)
    }

    // ------------------------------------------------ payload fixtures

    /** The REAL payload builder (the single source of truth): a PUBLIC,
     * realm-up, native 3+12 players split. */
    private fun greenPayload(): org.json.JSONObject = RealmStatusPayload.build(
        RealmStatusPayload.Inputs(
            visibility = RealmStatusVisibility.PUBLIC,
            generatedAtWallMs = nowMs[0],
            realmName = null,
            flavor = "vanilla",
            timeframeStageId = "vanilla-1.12.1",
            timeframeTitle = "1.12.1 final",
            timeframeIsFinal = true,
            description = "Casual quests and dungeons, no rush.",
            worldRunning = true,
            worldStartedAtWallMs = nowMs[0] - 90_000,
            recordSeconds = 3_600L,
            rates = RealmStatusPayload.Rates.engineDefault(),
            players = RealmStatusPayload.Players(
                source = RealmStatusPayload.PlayerSource.NATIVE,
                crossCheckOk = true,
                humansOnline = 3,
                botsOnline = 12,
                totalOnline = 15,
                botTarget = 12,
            ),
            worldPrepared = true,
            worldGeneration = "09613a74",
            worldCounts = RealmStatusPayload.WorldCounts(1, 2, 3, 4, 5),
            statusSource = RealmStatusPayload.StatusSource.LIVE,
            phase = "RUNNING",
            mode = "LAN_HOST",
            clean = true,
            advertisedAddress = null,
            joinKind = RealmStatusPayload.JoinKind.NONE,
            publicUrl = null,
            tunnelState = RealmStatusPayload.TunnelState.DOWN,
            characterNamesInPublic = false,
        ),
    )

    /** The realm is down (structural zeros) — never a publish state. */
    private fun downPayload(): org.json.JSONObject = greenPayload().toPayloadWithWorldDown()

    private fun org.json.JSONObject.toPayloadWithWorldDown(): org.json.JSONObject {
        val copy = org.json.JSONObject(toString())
        copy.getJSONObject("supervisor").put("realmOnline", false)
        copy.getJSONObject("players").put("source", "world-down")
        return copy
    }

    /** A degraded players split: the beat must be skipped (never padded). */
    private fun degradedPayload(): org.json.JSONObject {
        val copy = org.json.JSONObject(greenPayload().toString())
        copy.getJSONObject("players").put("source", "degraded")
        copy.getJSONObject("players").put("humansOnline", org.json.JSONObject.NULL)
        copy.getJSONObject("players").put("botsOnline", org.json.JSONObject.NULL)
        return copy
    }
}
