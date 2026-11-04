package com.pocketrealm.desktop.nostr

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.desktop.realmstatus.RealmStatusController
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.discovery.nostr.ListingStore
import com.pocketrealm.discovery.nostr.NostrEvent
import com.pocketrealm.discovery.nostr.NostrPrivateKey
import com.pocketrealm.discovery.nostr.PendingEvent
import com.pocketrealm.discovery.nostr.PrivacyGuard
import com.pocketrealm.discovery.nostr.RealmListingContract
import com.pocketrealm.discovery.nostr.RealmListingContent
import com.pocketrealm.discovery.nostr.RealmListingEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * W6-N1 — the adversarial breaker's negative tests
 * (cheap, desktop JVM lane). Each test pins the CORRECT contract; a
 * failure here is a live bypass demonstrated against the current tree:
 *
 *  - the privacy fence must refuse every CONNECTABLE IPv4/IPv6 spelling,
 *    not just the canonical dotted quad (inet_aton shorthand "10.1",
 *    IPv6 zone ids "fe80::1%eth0");
 *  - the inbound frozen-schema contract must refuse negative/impossible
 *    player counts, non-string name types, and unbounded timeframe text;
 *  - the flavor TAG must agree with the content's flavor (relay-side
 *    filters would otherwise diverge from what the store serves);
 *  - a duplicate event id must still be signature-verified (the store's
 *    KDoc claims a verify-free fast path — pinned here as REFUSING on
 *    tampering, i.e. the fast path must never skip verification);
 *  - relay-frame mischief (wrong arities, junk payloads) is ignored;
 *  - the NIP-09 retract must survive a relay-set edit followed by a gate
 *    drop (the stale listing must not outlive its retract);
 *  - a corrupt stored identity must never silently brick publishing
 *    forever (the recovery path must exist).
 */
@Suppress("LargeClass")
class NostrBreakerR2NegativeTest {

    // ------------------------------------------------------- fence probes

    /** CONTROL: the canonical banned forms still refuse (proves the
     * probe below runs against the live fence, not a stub). */
    @Test
    fun controlCanonicalBannedFormsStillRefuse() {
        listOf(
            "join me at 10.0.0.1:8085",
            "leading zeros 192.168.000.001",
            "v6 2001:db8::1",
            "host join.myrealm.com",
            "dl https://evil.example/client.exe",
            "ws://203.0.113.7",
        ).forEach { text ->
            try {
                PrivacyGuard.refuseIfBanned(text, "motd")
                fail("expected the fence to refuse: $text")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    expected.message.orEmpty().isNotBlank(),
                )
            }
        }
    }

    /** NEGATIVE (expected FAILING on the current tree): inet_aton-style
     * SHORT-HAND IPv4 spellings are connectable addresses (the JVM's own
     * resolver accepts them) yet the fence passes them — a listing can
     * carry an infrastructure pointer with no clean rejection. */
    @Test
    fun inetAtonShorthandIpv4IsRefusedLikeAnyOtherIp() {
        // First prove the spellings are genuinely connectable addresses
        // on this very JVM (numeric literal path, no DNS involved).
        assertEquals("10.0.0.1", java.net.InetAddress.getByName("10.1").hostAddress)
        assertEquals("192.168.0.1", java.net.InetAddress.getByName("192.168.1").hostAddress)
        // Now the fence: each must be refused (never passed).
        listOf(
            "join me at 10.1 port 8085",
            "gateway 192.168.1 tonight",
        ).forEach { text ->
            try {
                PrivacyGuard.refuseIfBanned(text, "motd")
                fail(
                    "the fence passed a connectable IPv4 shorthand " +
                        "($text) — an infrastructure pointer rides into a " +
                        "world-readable listing with no rejection",
                )
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    /** NEGATIVE (expected FAILING on the current tree): IPv6 zone-id
     * spellings (fe80::1%eth0 — link-local WITH its interface scope, and
     * the URL-encoded bracketed form) bypass the R3-5 IPv6 token walk. */
    @Test
    fun ipv6ZoneIdSpellingsAreRefusedLikeAnyOtherIp() {
        listOf(
            "reach me on fe80::1%eth0",
            "[2001:db8::1%25eth0]:8085",
        ).forEach { text ->
            try {
                PrivacyGuard.refuseIfBanned(text, "motd")
                fail("the fence passed an IPv6 zone-id address ($text)")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    /** NEGATIVE (expected FAILING on the current tree): the OUTBOUND
     * signing boundary must refuse the shorthand too — a host typing a
     * shorthand address into their own MOTD must not ship a listing that
     * carries it. */
    @Test
    fun outboundBuildRefusesIpv4ShorthandInMOTD() {
        val key = NostrPrivateKey.generate()
        val content = RealmListingContent(
            name = "Shorthand",
            motd = "join me at 10.1",
            flavor = "wotlk",
            build = ClientFlavors.WOTLK.build,
            timeframeStage = "s",
            rates = null,
            humans = 1,
            bots = 1,
        )
        try {
            val event = RealmListingEvent.build(content, key, NOW_SECONDS)
            fail(
                "a listing SIGNED and published carrying 'join me at 10.1' " +
                    "(id ${event.idHex.take(12)}…) — the frozen schema's " +
                    "NEVER-an-IP rule must hold at the signing boundary",
            )
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    // ------------------------------------------------- inbound contract

    /** NEGATIVE (expected FAILING on the current tree): a validly-signed
     * foreign listing with IMPOSSIBLE player counts (negative humans, a
     * billion bots) must be refused — the store currently accepts and the
     * browser renders the numbers verbatim. */
    @Test
    fun inboundListingRefusesImpossiblePlayerCounts() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedRaw(
            goldenContent()
                .put("players", org.json.JSONObject("{\"humans\":-5,\"bots\":999999999}")),
        )
        val result = store.accept(event, "wss://relay")
        assertTrue(
            "impossible counts accepted: $result",
            result is ListingStore.AcceptResult.Refused,
        )
        assertEquals(0, store.live().size)
    }

    /** NEGATIVE (expected FAILING on the current tree): the frozen schema
     * pins name/motd as STRINGS — a hostile content carrying a JSON
     * OBJECT as the name is stringified by org.json and accepted with
     * garbage rendered in the browser row. */
    @Test
    fun inboundListingRefusesNonStringNameTypes() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedRaw(goldenContent().put("name", org.json.JSONObject("{\"evil\":1}")))
        val result = store.accept(event, "wss://relay")
        assertTrue(
            "a JSON-object name was accepted: " +
                (result as? ListingStore.AcceptResult.Accepted)?.let {
                    store.live().first().content.name
                },
            result is ListingStore.AcceptResult.Refused,
        )
    }

    /** NEGATIVE (expected FAILING on the current tree): timeframe text is
     * rendered in every listing row but has NO length bound inbound —
     * a 200 KB string rides into the UI. */
    @Test
    fun inboundListingBoundsTheTimeframeText() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedRaw(goldenContent().put("timeframe", "x".repeat(200_000)))
        val result = store.accept(event, "wss://relay")
        assertTrue(
            "a 200 KB timeframe string was accepted",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    /** NEGATIVE (expected FAILING on the current tree): the flavor TAG
     * must agree with the content's flavor — a listing advertising
     * flavor "vanilla" in its (relay-indexed) tag while serving wotlk
     * content makes relay-side filters diverge from what the store
     * serves. */
    @Test
    fun inboundListingRefusesFlavorTagContentMismatch() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedRaw(
            content = goldenContent(),
            extraTags = listOf(listOf("flavor", "vanilla")),
        )
        val result = store.accept(event, "wss://relay")
        assertTrue(
            "flavor tag/content mismatch accepted: $result",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    // -------------------------------------------------- dedupe contract

    /** The store's KDoc claims "an EXACT duplicate id skips the crypto
     * verify entirely". PINNED: a duplicate id with a TAMPERED signature
     * must still be REFUSED — whatever fast path exists must never skip
     * verification. (The current code verifies every time; this test
     * holds the line against anyone implementing the comment literally.) */
    @Test
    fun duplicateIdWithTamperedSignatureIsStillRefused() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val good = signedRaw(goldenContent())
        assertTrue(store.accept(good, "wss://relay-a") is ListingStore.AcceptResult.Accepted)
        val tampered = good.copy(sigHex = "ab" + good.sigHex.drop(2))
        val result = store.accept(tampered, "wss://relay-b")
        assertTrue(
            "a tampered duplicate rode the duplicate path past the signature check",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    // --------------------------------------------------- frame mischief

    /** Relay-frame mischief: wrong arities and junk payloads are ignored,
     * never thrown on. */
    @Test
    fun relayFrameMischiefIsIgnored() {
        val good = signedRaw(goldenContent())
        assertTrue(NostrEvent.fromRelayFrame("hello, world").isEmpty())
        assertTrue(NostrEvent.fromRelayFrame("[]").isEmpty())
        assertTrue(NostrEvent.fromRelayFrame("[\"CLOSED\",\"sub\"]").isEmpty())
        // The write-shaped echo is not a relay delivery.
        assertTrue(NostrEvent.fromRelayFrame("[\"EVENT\",\"just-a-string\"]").isEmpty())
        // Four-element frames are outside both NIP-01 shapes.
        assertTrue(
            NostrEvent.fromRelayFrame("[\"EVENT\",\"sub\",${good.toWireJson()},\"extra\"]").isEmpty(),
        )
        // A subscript frame whose event slot is junk is ignored.
        assertTrue(NostrEvent.fromRelayFrame("[\"EVENT\",\"sub\",\"junk\"]").isEmpty())
        // A legitimate subscript frame still parses.
        assertEquals(
            good.idHex,
            NostrEvent.fromRelayFrame("[\"EVENT\",\"sub\",${good.toWireJson()}]").single().second.idHex,
        )
    }

    // ------------------------------------------- publisher lifecycles

    /** NEGATIVE (expected FAILING on the current tree): the retract must
     * survive a relay-set edit followed by an immediate gate drop. The
     * edit recycles the pool; the drop then finds NO pool and silently
     * loses the NIP-09 deletion — the stale listing keeps circulating
     * until the 10-minute freshness expiry says otherwise. */
    @Test
    fun retractSurvivesARelayEditFollowedByAGateDrop() {
        val serverB = MockWebServer()
        val framesB = LinkedBlockingQueue<String>()
        val listenerB = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                framesB.put(text)
            }
        }
        serverB.enqueue(MockResponse.Builder().webSocketUpgrade(listenerB).build())
        serverB.enqueue(MockResponse.Builder().code(200).body("{}").build())
        serverB.start()
        try {
            boot()
            runBlocking { publisher.evaluateGates() }
            assertNotNull(nextEventFrame(10)) // the listing is out on relay A
            publisher.setRelays(
                listOf(
                    serverB.url("/").toString().replaceFirst("http://", "ws://").removeSuffix("/"),
                ),
            )
            // The gate drops BEFORE the next beat can rebuild the pool.
            model.updateSettings { it.copy(onlineHosting = false) }
            runBlocking { publisher.evaluateGates() }
            // THE CONTRACT: a kind-5 deletion for the live listing must
            // reach the relay that carries it (A) — bounded poll.
            val deadline = System.currentTimeMillis() + 3_000
            var deletionOnA: String? = null
            while (deletionOnA == null && System.currentTimeMillis() < deadline) {
                val frame = frames.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (frame.contains("\"kind\":5")) deletionOnA = frame
            }
            assertNotNull(
                "the NIP-09 retract was lost after the relay edit — the " +
                    "deletion never reached relay A and the stale listing " +
                    "circulates until the 10-minute freshness expiry",
                deletionOnA,
            )
            assertEquals(0, framesB.size)
            assertFalse(publisher.uiState.value.active)
        } finally {
            runCatching { serverB.close() }
        }
    }

    /** NEGATIVE (expected FAILING on the current tree): a corrupt stored
     * identity (a hand-edited settings.json) must not brick publishing
     * FOREVER — the off/on toggle is a natural recovery attempt and the
     * key must regenerate or the failure must clear; today the corrupt
     * value is kept and every beat fails with VAL-NOSTR-KEY forever. */
    @Test
    fun corruptStoredIdentityDoesNotBrickPublishingForever() {
        boot()
        runBlocking { publisher.evaluateGates() }
        assertNotNull(nextEventFrame(10)) // baseline beat works
        val corrupt = "0".repeat(64) // a valid hex shape, an invalid BIP-340 key
        model.updateSettings { it.copy(nostrPrivateKeyHex = corrupt) }
        nowMs[0] += RealmListingContract.HEARTBEAT_SECONDS * 1000
        runBlocking { publisher.evaluateGates() }
        // R2B-3 root fix: the corrupt key SELF-HEALS at the next identity
        // use — the beat regenerates the key and publishes (the old tree
        // kept the corrupt value and refused forever; the stored key now
        // changes as evidence of the heal).
        val healed = nextListingFrame(10)
        assertNotNull("the corrupt key must self-heal and publish", healed)
        assertNotEquals(corrupt, model.settings.value.nostrPrivateKeyHex)
        // The natural recovery: toggle the switch off and back on.
        model.updateSettings { it.copy(onlineHosting = false) }
        runBlocking { delay(200) }
        model.updateSettings { it.copy(onlineHosting = true) }
        runBlocking { publisher.evaluateGates() }
        val recovery = nextListingFrame(3)
        assertNotNull(
            "publishing never recovers after a corrupt stored identity — " +
                "the corrupt key ${model.settings.value.nostrPrivateKeyHex.take(12)}… " +
                "is kept forever and every beat dies on VAL-NOSTR-KEY",
            recovery,
        )
    }

    // ------------------------------------------------------ fixtures

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val nowMs = longArrayOf(1_760_000_000_000L)

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

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun tearDown() {
        runCatching { publisher.close() }
        runCatching { server.close() }
        runCatching { model.close() }
        scope.cancel()
    }

    /** The full stack on the mock relay (mirrors NostrListingPublisherTest
     * boot(): switch on, PUBLIC, realm up, native 3+12 split). */
    private fun boot() {
        server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(MockResponse.Builder().webSocketUpgrade(relayListener).build())
        server.enqueue(
            MockResponse.Builder().code(200).body("{\"supported_nips\":[1]}").build(),
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
                payloadProvider = { payload.get() },
                gatePollMs = Long.MAX_VALUE,
                heartbeatSeconds = RealmListingContract.HEARTBEAT_SECONDS,
            ),
        )
        publisher.boot()
        model.updateSettings {
            it.copy(onlineHosting = true, nostrRelays = listOf(relayUrl()))
        }
        runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (model.settings.value.nostrPrivateKeyHex.isBlank()) {
                    delay(10)
                }
            }
        }
        payload.set(greenPayload())
    }

    private fun relayUrl(): String =
        server.url("/").toString().replaceFirst("http://", "ws://").removeSuffix("/")

    /** The next non-REQ frame; null when nothing arrives in time. */
    private fun nextEventFrame(timeoutSeconds: Long): String? {
        var found: String? = null
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (found == null && System.currentTimeMillis() < deadline) {
            val frame = frames.poll(1, TimeUnit.SECONDS) ?: break
            if (!frame.startsWith("[\"REQ\"")) found = frame
        }
        return found
    }

    /** The next kind-30388 LISTING frame (deletions and REQs don't
     * count); null when none arrives in time. */
    private fun nextListingFrame(timeoutSeconds: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            val frame = frames.poll(1, TimeUnit.SECONDS) ?: break
            if (frame.startsWith("[\"EVENT\"") && frame.contains("\"kind\":30388")) return frame
        }
        return null
    }

    private fun greenPayload(): org.json.JSONObject = RealmStatusPayload.build(
        RealmStatusPayload.Inputs(
            visibility = RealmStatusVisibility.PUBLIC,
            generatedAtWallMs = nowMs[0],
            realmName = null,
            flavor = "vanilla",
            timeframeStageId = "vanilla-final",
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

    companion object {
        private const val NOW_SECONDS: Long = 1_760_000_000L

        /** The frozen-schema content as a mutable JSON object. */
        private fun goldenContent(): org.json.JSONObject = org.json.JSONObject(
            RealmListingContent(
                name = "Northdale",
                motd = "Casual quests and dungeons, no rush.",
                flavor = "wotlk",
                build = ClientFlavors.WOTLK.build,
                timeframeStage = "wotlk-3.3.5a",
                rates = RealmListingContent.ListingRates(1.0, 1.0, 1.0),
                humans = 3,
                bots = 12,
            ).toJsonString(),
        )

        /** Signs arbitrary content JSON as a kind-30388 listing under a
         * fresh hostile key (the store-side boundary under test). */
        private fun signedRaw(
            content: org.json.JSONObject,
            extraTags: List<List<String>> = emptyList(),
        ): NostrEvent = NostrEvent.create(
            key = NostrPrivateKey.generate(),
            pending = PendingEvent(
                createdAtSeconds = NOW_SECONDS,
                kind = RealmListingContract.KIND,
                tags = listOf(
                    listOf("d", RealmListingContract.D_TAG),
                    listOf("t", RealmListingContract.T_TAG),
                ) + extraTags,
                content = content.toString(),
            ),
            auxRand32 = ByteArray(32),
        )
    }
}
