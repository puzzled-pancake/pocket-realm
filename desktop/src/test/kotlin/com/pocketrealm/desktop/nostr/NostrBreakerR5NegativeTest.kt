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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * W6-N1 code-red round 5 — the adversarial breaker's negative tests
 * (cheap, desktop JVM lane). Round 5 probes the gaps rounds 2-4 left:
 *
 *  - the frozen-schema key-set pin covers the TOP-LEVEL keys only — a
 *    validly-signed listing whose `rates` (or `players`) SUB-object
 *    carries a huge foreign key must not ride 200 KB of junk past
 *    admission (the retained-Entry carries the raw content string);
 *  - the tag-contract ladder bounds tag COUNT (64) but must bound tag
 *    VALUE length too — a 200 KB `expiration` tag value is retained
 *    memory behind a valid signature;
 *  - a shipped flavor carrying ANOTHER lane's client build must refuse
 *    as the anachronism it is (the pairs come from the owning registry);
 *  - a kind-5 NIP-09 deletion signed by a FOREIGN key must never retract
 *    a stored listing (the browser trusts only its own verify ladder);
 *  - an expired listing's exact-duplicate echo must never re-animate it
 *    in the live view (the duplicate fast path must not bypass the
 *    freshness eviction);
 *  - an Infinity rate literal must refuse (not coerce);
 *  - the LOWER freshness boundary (created_at = now-601 s) must refuse;
 *  - the host card's publish-status copy must not claim "Live" while
 *    every relay is dead (the zero-ack/zero-reject case).
 */
@Suppress("LargeClass")
class NostrBreakerR5NegativeTest {

    // ------------------------------------------------- retained-memory attacks

    /** The frozen-schema contract pins the TOP-LEVEL key set and bounds the
     * known TEXT fields — but the `rates` SUB-object is read with optDouble
     * for three fields and its key set is never pinned: a validly-signed
     * listing can carry a 200 KB foreign key inside `rates`, and the raw
     * content string (with the junk) is RETAINED per accepted Entry. */
    @Test
    fun ratesSubObjectJunkIsRefusedAtAdmission() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val ratesJunk = goldenContent()
            .put("rates", org.json.JSONObject().put("xpKill", 1.0).put("junk", "A".repeat(200_000)))
        val result = store.accept(signedRaw(ratesJunk), "wss://relay-hostile")
        assertTrue(
            "a 200 KB junk key inside the rates sub-object was ACCEPTED " +
                "(raw content retained per entry, x256 entries): $result",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    /** Same attack one level up: the tag-contract ladder bounds tag COUNT
     * (64) but no leg bounds a tag VALUE's length — a validly-signed event
     * with a 200 KB `expiration` tag value retains the junk per entry. */
    @Test
    fun giantTagValueIsRefusedAtAdmission() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedRaw(
            goldenContent(),
            extraTags = listOf(listOf("expiration", "A".repeat(200_000))),
            dropExpiration = true,
        )
        val result = store.accept(event, "wss://relay-hostile")
        assertTrue(
            "a 200 KB tag VALUE was ACCEPTED (the tag bound counts tags, " +
                "not bytes): $result",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    // ------------------------------------------------- flavor/build pairing

    /** A shipped flavor carrying ANOTHER lane's client build is exactly the
     * cross-era anachronism the registry law bans — refused both ways. */
    @Test
    fun wrongFlavorBuildPairsAreRefused() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        listOf(
            "tbc" to ClientFlavors.WOTLK.build,
            "wotlk" to ClientFlavors.VANILLA.build,
            "vanilla" to ClientFlavors.TBC.build,
        ).forEach { (flavor, build) ->
            val result = store.accept(
                signedRaw(goldenContent().put("flavor", flavor).put("build", build)),
                "wss://relay-hostile",
            )
            assertTrue(
                "the wrong-flavor/build pair ($flavor, $build) was accepted: $result",
                result is ListingStore.AcceptResult.Refused,
            )
        }
        assertEquals(0, store.live().size)
    }

    // ------------------------------------------------- deletion mischief

    /** A kind-5 NIP-09 deletion signed by a FOREIGN key (an attacker's
     * throwaway identity) must never retract a stored listing — the
     * browser ignores every kind that is not 30388 and trusts only its own
     * verify ladder. */
    @Test
    fun foreignKind5DeletionCannotRetractAStoredListing() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val listing = signedRaw(goldenContent())
        assertTrue(store.accept(listing, "wss://relay-a") is ListingStore.AcceptResult.Accepted)
        // The attacker signs a deletion TWEETING at the victim's listing id.
        val deletion = NostrEvent.create(
            key = NostrPrivateKey.generate(),
            pending = PendingEvent(
                createdAtSeconds = NOW_SECONDS,
                kind = RealmListingContract.DELETION_KIND,
                tags = listOf(
                    listOf("e", listing.idHex),
                    listOf(
                        "a",
                        "${RealmListingContract.KIND}:${listing.pubkeyHex}:" +
                            RealmListingContract.D_TAG,
                    ),
                    listOf("k", RealmListingContract.KIND.toString()),
                ),
                content = "",
            ),
            auxRand32 = ByteArray(32),
        )
        val result = store.accept(deletion, "wss://relay-hostile")
        assertTrue(
            "a kind-5 deletion reached the store unrefused: $result",
            result is ListingStore.AcceptResult.Refused,
        )
        assertEquals(1, store.live().size)
        assertEquals(listing.idHex, store.live().single().event.idHex)
    }

    // ------------------------------------------------- freshness ladder

    /** An expired listing's EXACT duplicate echo (the write-ALL/read-ALL
     * fast path) must never re-animate it: the fast path may return
     * whatever it likes, but the live view must evict and stay empty. */
    @Test
    fun expiredDuplicateEchoNeverReanimatesTheListing() {
        val nowMs = longArrayOf((NOW_SECONDS + 60) * 1000)
        val store = ListingStore { nowMs[0] }
        val listing = signedRaw(goldenContent())
        assertTrue(store.accept(listing, "wss://relay-a") is ListingStore.AcceptResult.Accepted)
        assertEquals(1, store.live().size)
        nowMs[0] += 11 * 60 * 1000 // the listing is now stale everywhere
        val echo = store.accept(listing, "wss://relay-b")
        assertTrue(
            "an 11-minutes-stale echo re-appeared in the LIVE view " +
                "(the duplicate fast path bypassed the freshness eviction): " +
                "${store.live().size} live after echo ($echo)",
            store.live().isEmpty(),
        )
    }

    /** The LOWER freshness boundary: created_at exactly at now-600 s is the
     * last fresh second; now-601 s must refuse. */
    @Test
    fun lowerFreshnessBoundaryIsPinned() {
        val store = ListingStore { NOW_SECONDS * 1000 }
        val edge = store.accept(signedRaw(goldenContent(), createdAt = NOW_SECONDS - 600), "wss://r")
        assertTrue("the exact 600 s edge refused: $edge", edge is ListingStore.AcceptResult.Accepted)
        val past = store.accept(signedRaw(goldenContent(), createdAt = NOW_SECONDS - 601), "wss://r")
        assertTrue("601 s stale accepted: $past", past is ListingStore.AcceptResult.Refused)
    }

    /** An Infinity rate literal (1e999 parses to Infinity) must REFUSE —
     * never coerce to a rendered number or null. */
    @Test
    fun infiniteRateLiteralIsRefused() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val raw = goldenContent()
            .put("rates", org.json.JSONObject("{\"xpKill\":1e999,\"xpQuest\":1.0,\"xpHonor\":1.0}"))
        val result = store.accept(signedRaw(raw), "wss://relay-hostile")
        assertTrue("an Infinity rate was accepted: $result", result is ListingStore.AcceptResult.Refused)
    }

    // ------------------------------------------- publisher honesty probe

    /** The host card must not claim "Live" while the relay set is DEAD:
     * with every relay FAILED there are zero acks AND zero rejections, the
     * all-rejected copy never fires, and `active` alone drives LIVE_COPY —
     * the host believes the realm is discoverable while no relay ever
     * received the frame. */
    @Test
    fun liveCopyIsNotClaimedWhileEveryRelayIsDead() {
        boot(deadRelay = true)
        runBlocking { publisher.evaluateGates() }
        val deadline = System.currentTimeMillis() + 10_000
        while (publisher.uiState.value.listingEventId == null &&
            System.currentTimeMillis() < deadline
        ) {
            runBlocking { delay(100) }
        }
        val state = publisher.uiState.value
        assertNotNull("the beat never fired against the dead relay set", state.listingEventId)
        assertTrue(state.active)
        assertEquals(0, state.lastBeatOkCount)
        assertEquals(0, state.lastBeatRejectCount)
        assertTrue(
            "every relay FAILED yet the card claims Live: " +
                "\"${com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(state)}\"",
            !com.pocketrealm.desktop.nostr.NostrListingPublisher.publishStatusCopy(state).startsWith("Live"),
        )
    }

    // ------------------------------------------------------------ fixtures

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val nowMs = longArrayOf(1_760_000_000_000L)

    private val visibility =
        java.util.concurrent.atomic.AtomicReference(RealmStatusVisibility.OFF)

    private val payload = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject?>(null)

    private val frames = LinkedBlockingQueue<String>()

    private val silentRelayListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.put(text)
            // Deliberately NO OK ack — the dead-write lane.
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

    /** The full stack against a relay that REFUSES the WebSocket upgrade
     * (every session lands FAILED — the dead-relay-set case). */
    private fun boot(deadRelay: Boolean) {
        server = MockWebServer()
        if (deadRelay) {
            repeat(8) { server.enqueue(MockResponse.Builder().code(500).body("no").build()) }
        } else {
            repeat(8) {
                server.enqueue(MockResponse.Builder().webSocketUpgrade(silentRelayListener).build())
            }
        }
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
                gatePollMs = Long.MAX_VALUE,
                heartbeatSeconds = RealmListingContract.HEARTBEAT_SECONDS,
            ),
        )
        publisher.boot()
        model.updateSettings {
            it.copy(nostrDiscoveryEnabled = true, nostrRelays = listOf(relayUrl()))
        }
        runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                while (model.settings.value.nostrPrivateKeyHex.isBlank()) {
                    delay(10)
                }
            }
        }
        visibility.set(RealmStatusVisibility.PUBLIC)
        payload.set(greenPayload())
    }

    private fun relayUrl(): String =
        server.url("/").toString().replaceFirst("http://", "ws://").removeSuffix("/")

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

    companion object {
        private const val NOW_SECONDS: Long = 1_760_000_000L

        /** The frozen-schema content as a mutable JSON object (a registered
         * wotlk stage — TimeframeStages "wotlk-3.3.5a"). */
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
            createdAt: Long = NOW_SECONDS,
            dropExpiration: Boolean = false,
        ): NostrEvent = NostrEvent.create(
            key = NostrPrivateKey.generate(),
            pending = PendingEvent(
                createdAtSeconds = createdAt,
                kind = RealmListingContract.KIND,
                tags = listOf(
                    listOf("d", RealmListingContract.D_TAG),
                    listOf("t", RealmListingContract.T_TAG),
                ) +
                    (if (dropExpiration) {
                        emptyList()
                    } else {
                        listOf(
                            listOf(
                                "expiration",
                                (createdAt + RealmListingContract.EXPIRATION_HINT_SECONDS).toString(),
                            ),
                        )
                    }) + extraTags,
                content = content.toString(),
            ),
            auxRand32 = ByteArray(32),
        )
    }
}
