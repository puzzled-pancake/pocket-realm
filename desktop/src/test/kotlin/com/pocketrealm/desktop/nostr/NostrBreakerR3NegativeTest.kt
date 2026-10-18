package com.pocketrealm.desktop.nostr

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.nostr.ListingStore
import com.pocketrealm.discovery.nostr.NostrEvent
import com.pocketrealm.discovery.nostr.NostrPrivateKey
import com.pocketrealm.discovery.nostr.PendingEvent
import com.pocketrealm.discovery.nostr.PrivacyGuard
import com.pocketrealm.discovery.nostr.RealmListingContract
import com.pocketrealm.discovery.nostr.RealmListingContent
import com.pocketrealm.discovery.nostr.RealmListingEvent
import com.pocketrealm.discovery.nostr.RelayPool
import com.pocketrealm.discovery.nostr.NostrFilter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W6-N1 code-red round 3 — the adversarial breaker's negative tests
 * (cheap, desktop JVM lane). Each NEGATIVE test pins the CORRECT
 * contract; a failure here is a live bypass demonstrated against the
 * current tree. Round-3 attack surface (none covered by the round-1/2
 * suites):
 *
 *  - the privacy fence vs SENTENCE-FINAL PUNCTUATION: "10.0.0.1." — the
 *    canonical regex's trailing boundary class and the loose/shorthand
 *    walk both die on a trailing period, yet the address is unchanged;
 *  - the fence vs the DOTLESS DECIMAL IPv4 spelling (inet_aton's
 *    integer form — connectable on this very JVM);
 *  - the fence vs IDN (non-ASCII) hostnames, whose punycode form the
 *    SAME fence refuses — one hostname, two spellings, one verdict;
 *  - the inbound store must refuse a HOSTILE join (W6-N3 flipped the
 *    frozen schema to CARRY a well-formed descriptor, but the admission
 *    shape stays bounded: exactly type/node/relay, a 64-hex node, a
 *    hostname-only https relay) — any other shape is refused, so a
 *    hostile signed listing still cannot pin megabytes past the
 *    256-entry admission cap (the cap counts entries, not bytes);
 *  - the merge-evidence contract: a relay that delivered an OLDER,
 *    different-id echo must NOT be recorded as having seen the stored
 *    newest id (the "seen on N relays" copy would lie);
 *  - relay mischief: repeated NIP-42 AUTH challenges must not drive an
 *    unbounded sign+re-REQ loop (the OK-path cap never engages here);
 *  - the freshness clock-skew upper bound (+120 s) is pinned exactly;
 *  - duplicate `flavor` tags (a relay-side filter would index BOTH
 *    flavors) are refused, not silently first-wins.
 */
class NostrBreakerR3NegativeTest {

    // ------------------------------------------------------- fence probes

    /** CONTROL: canonical banned forms still refuse (proves the probes
     * below run against the live fence, not a stub). */
    @Test
    fun controlCanonicalBannedFormsStillRefuse() {
        listOf(
            "join me at 10.0.0.1:8085",
            "v6 2001:db8::1",
            "host join.myrealm.com",
            "dl https://evil.example/client.exe",
        ).forEach { text ->
            try {
                PrivacyGuard.refuseIfBanned(text, "motd")
                fail("expected the fence to refuse: $text")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
            }
        }
    }

    /** NEGATIVE (expected FAILING on the current tree): a canonical
     * connectable IPv4 followed by a SENTENCE-FINAL PERIOD — the most
     * ordinary English punctuation — passes every fence rule: the
     * canonical regex's trailing `($|[^0-9.])` cannot match a '.', the
     * loose regex dies the same way, and the shorthand walk excludes 4
     * dots. The IP rides into a world-readable listing unchanged. */
    @Test
    fun sentenceFinalPeriodCanonicalIpv4IsRefused() {
        listOf(
            "connect tonight at 10.0.0.1.",
            "we moved. new hub 192.168.1.7.",
            "10.0.0.1.",
        ).forEach { text ->
            try {
                PrivacyGuard.refuseIfBanned(text, "motd")
                fail(
                    "the fence passed a canonical IPv4 wearing a sentence-final " +
                        "period ($text) — the address is byte-for-byte the banned " +
                        "one; an infrastructure pointer rides into a world-readable " +
                        "listing with no rejection",
                )
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
            }
        }
    }

    /** NEGATIVE (expected FAILING on the current tree): the DOTLESS
     * DECIMAL spelling — inet_aton's integer form — is a connectable
     * address on this very JVM (numeric parse, no DNS), yet the fence
     * only walks dot-carrying tokens. */
    @Test
    fun dotlessDecimalIpv4IsRefusedLikeAnyOtherIp() {
        // Prove the spellings are genuinely connectable on this JVM
        // (numeric literal path — no network, no DNS).
        assertEquals("127.0.0.1", java.net.InetAddress.getByName("2130706433").hostAddress)
        assertEquals("192.168.0.1", java.net.InetAddress.getByName("3232235521").hostAddress)
        listOf(
            "join 2130706433 port 8085",
            "hub is 3232235521 tonight",
        ).forEach { text ->
            try {
                PrivacyGuard.refuseIfBanned(text, "motd")
                fail(
                    "the fence passed a dotless decimal IPv4 ($text) that this " +
                        "JVM's own resolver accepts as a connectable address",
                )
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
            }
        }
    }

    /** NEGATIVE (expected FAILING on the current tree): an IDN (non-ASCII)
     * hostname passes the fence while its PUNYCODE spelling of the SAME
     * hostname is refused by the same fence — one hostname, two
     * spellings, opposite verdicts (fail-open on the ASCII-only regex). */
    @Test
    fun idnHostnameIsRefusedLikeItsPunycodeSpelling() {
        val unicode = "水.com"
        val punycode = java.net.IDN.toASCII(unicode)
        // The fence's own hostname rule refuses the punycode spelling…
        try {
            PrivacyGuard.refuseIfBanned("join $punycode", "motd")
            fail("control: the fence should refuse the punycode form $punycode")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
        }
        // …so it must refuse the unicode spelling of the same hostname.
        try {
            PrivacyGuard.refuseIfBanned("join $unicode", "motd")
            fail(
                "the fence passed the IDN hostname $unicode (punycode " +
                    "$punycode) — a resolvable hostname rides into a " +
                    "world-readable listing with no rejection",
            )
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
        }
    }

    // -------------------------------------------- inbound store contract

    /** NEGATIVE (W6-N3 posture): the join descriptor is now CARRIED, but
     * only in the pinned bounded shape — an object carrying unknown keys
     * (here a 2 MB "pad") refuses at admission, so the unbounded
     * retention hole the R3-2 fence closed stays closed. */
    @Test
    fun inboundNonNullJoinIsRefusedUntilN3() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val pad = "A".repeat(2_000_000)
        val event = signedRaw(
            goldenContent().put("join", org.json.JSONObject().put("pad", pad)),
        )
        val result = store.accept(event, "wss://relay")
        assertTrue(
            "a hostile join (unknown key, $pad.length-byte " +
                "payload) was ACCEPTED and retained (${store.retainedCount()} entry(ies), " +
                "live=${store.live().size}) — the admission shape must stay " +
                "bounded (the cap counts entries, not bytes)",
            result is ListingStore.AcceptResult.Refused,
        )
        assertEquals(0, store.live().size)
    }

    /** NEGATIVE (expected FAILING on the current tree): a validly-signed
     * foreign listing whose MOTD carries the sentence-final-period IP
     * must be refused by the store's inbound fence — end-to-end proof
     * that the fence bypass above reaches every browser row. */
    @Test
    fun inboundSignedListingWithTrailingPeriodIpIsRefused() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedRaw(
            goldenContent().put("motd", "Connect tonight at 10.0.0.1."),
        )
        val result = store.accept(event, "wss://relay")
        assertTrue(
            "a signed listing whose motd carries '10.0.0.1.' was accepted: " +
                (result as? ListingStore.AcceptResult.Accepted)?.let {
                    "rendered motd='${store.live().first().content.motd}'"
                },
            result is ListingStore.AcceptResult.Refused,
        )
    }

    /** NEGATIVE (expected FAILING on the current tree): the OUTBOUND
     * signing boundary must refuse the sentence-final-period spelling —
     * a host typing normal punctuation into their own MOTD must not ship
     * a signed listing carrying an IP. */
    @Test
    fun outboundSignRefusesTrailingPeriodIp() {
        val key = NostrPrivateKey.generate()
        val content = RealmListingContent(
            name = "Punctuated",
            motd = "Connect tonight at 10.0.0.1.",
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
                "a listing was SIGNED and published carrying '10.0.0.1.' " +
                    "(id ${event.idHex.take(12)}…) — the NEVER-an-IP rule must " +
                    "hold at the signing boundary for ordinary punctuation",
            )
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
        }
    }

    // ------------------------------------------------- merge evidence

    /** NEGATIVE (expected FAILING on the current tree): the merge's
     * else-branch records the delivering relay on the STORED (newest)
     * entry even when the delivered event was an OLDER, DIFFERENT id —
     * the entry's seenOn set then claims a relay "saw" an id it never
     * delivered, and the UI's "seen on N relays" copy lies. */
    @Test
    fun olderEchoFromAnotherRelayMustNotGrowTheStoredEvidence() {
        val store = ListingStore { (NOW_SECONDS + 120) * 1000 }
        val key = NostrPrivateKey.generate()
        val newer = buildListing(key, NOW_SECONDS + 30, name = "Newer")
        val older = buildListing(key, NOW_SECONDS, name = "Older")
        store.accept(newer, "wss://relay-a")
        store.accept(older, "wss://relay-b")
        assertEquals(1, store.live().size)
        val evidence = store.live()[0].seenOn
        assertTrue(
            "the stored listing (id ${store.live()[0].event.idHex.take(8)}…) claims " +
                "evidence $evidence, but relay-b delivered the OLDER id " +
                "${older.idHex.take(8)}…, never the stored one — the 'seen on N " +
                "relays' copy is fed unearned evidence",
            evidence == setOf("wss://relay-a"),
        )
    }

    // ---------------------------------------------------- relay mischief

    /** NEGATIVE (expected FAILING on the current tree): a hostile relay
     * sending repeated NIP-42 AUTH challenges drives an UNBOUNDED
     * sign-and-re-REQ loop — every ["AUTH", c] frame costs a fresh
     * Schnorr sign, a re-issued REQ (the relay may then re-deliver its
     * whole `since` window for re-verification), and there is NO cap on
     * this path (the R2-3 cap only guards the auth-required-OK ladder). */
    @Test
    @Suppress("LongMethod") // the reviewer's bound-poll harness: the frame counting IS the test
    fun repeatedAuthChallengesStayBounded() {
        val server = MockWebServer()
        val inbound = LinkedBlockingQueue<String>()
        var challengesSent = 0
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Fire the challenge flood once, unprovoked.
                repeat(AUTH_CHALLENGE_FLOOD) { i ->
                    webSocket.send("[\"AUTH\",\"flood-challenge-$i\"]")
                    challengesSent += 1
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The client's replies arrive here (server side of the
                // one socket).
                inbound.put(text)
            }
        }
        server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        server.start()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val pool = RelayPool(
            client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build(),
            scope = scope,
            signingKeyProvider = { NostrPrivateKey.generate() },
            nowSeconds = { NOW_SECONDS },
            log = { },
        )
        try {
            pool.start(
                listOf(
                    server.url("/").toString()
                        .replaceFirst("http://", "ws://").removeSuffix("/"),
                ),
                NostrFilter(),
            )
            // Drain the client's replies for a quiet window (a short gap
            // ends the pass; two passes catch stragglers).
            var authFrames = 0
            var reqFrames = 0
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                val frame = inbound.poll(1, TimeUnit.SECONDS) ?: break
                when {
                    frame.startsWith("[\"AUTH\"") -> authFrames += 1
                    frame.startsWith("[\"REQ\"") -> reqFrames += 1
                }
            }
            while (System.currentTimeMillis() < deadline) {
                val frame = inbound.poll(500, TimeUnit.MILLISECONDS) ?: break
                when {
                    frame.startsWith("[\"AUTH\"") -> authFrames += 1
                    frame.startsWith("[\"REQ\"") -> reqFrames += 1
                }
            }
            assertTrue(
                "the client answered $authFrames AUTH challenges (of " +
                    "$challengesSent sent) with $reqFrames REQ re-issues — an " +
                    "unbounded attacker-driven sign+re-fetch loop (each re-REQ " +
                    "lets the relay re-deliver its whole since-window for " +
                    "re-verification); the OK-path cap (R2-3) has no " +
                    "counterpart on the challenge path",
                authFrames <= 1 && reqFrames <= 2,
            )
        } finally {
            pool.stop()
            scope.cancel()
            runCatching { server.close() }
        }
    }

    // ------------------------------------------------ boundary + schema

    /** PIN (expected PASSING): the clock-skew upper bound is exactly
     * +120 s — a listing stamped +121 s into the future is refused, one
     * at exactly +120 s is admitted. */
    @Test
    fun clockSkewUpperBoundaryIsPinned() {
        val store = ListingStore { NOW_SECONDS * 1000 }
        val refused = store.accept(buildListing(NostrPrivateKey.generate(), NOW_SECONDS + 121), "wss://r")
        assertTrue(
            "created_at now+121 must refuse: $refused",
            refused is ListingStore.AcceptResult.Refused,
        )
        val admitted = store.accept(buildListing(NostrPrivateKey.generate(), NOW_SECONDS + 120), "wss://r")
        assertTrue(
            "created_at now+120 (exactly the skew bound) must admit: $admitted",
            admitted is ListingStore.AcceptResult.Accepted,
        )
    }

    /** NEGATIVE (expected FAILING on the current tree): duplicate
     * `flavor` tags — relay-side #flavor filters index BOTH values, so a
     * listing tagged flavor=wotlk+flavor=tbc is discoverable under the
     * wrong flavor while the store silently keeps only the first. */
    @Test
    fun duplicateFlavorTagsAreRefused() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedRaw(
            goldenContent(),
            extraTags = listOf(listOf("flavor", "wotlk"), listOf("flavor", "tbc")),
        )
        val result = store.accept(event, "wss://relay")
        assertTrue(
            "a listing with TWO flavor tags (wotlk+tbc, content wotlk) was " +
                "accepted; relay-side filters would index it under both " +
                "flavors — ambiguous tag sets must refuse",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    // --------------------------------------------------------- fixtures

    private companion object {
        const val NOW_SECONDS: Long = 1_760_000_000L
        const val AUTH_CHALLENGE_FLOOD: Int = 6

        /** The frozen-schema content as a mutable JSON object. */
        fun goldenContent(): org.json.JSONObject = org.json.JSONObject(
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
        fun signedRaw(
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

        /** Builds a valid signed listing from ONE key (for same-pubkey
         * dedupe pairs) with the standard frozen tag set. */
        fun buildListing(
            key: NostrPrivateKey,
            createdAtSeconds: Long,
            name: String = "Northdale",
        ): NostrEvent = RealmListingEvent.build(
            content = RealmListingContent(
                name = name,
                motd = "Casual quests and dungeons, no rush.",
                flavor = "wotlk",
                build = ClientFlavors.WOTLK.build,
                timeframeStage = "wotlk-3.3.5a",
                rates = RealmListingContent.ListingRates(1.0, 1.0, 1.0),
                humans = 3,
                bots = 12,
            ),
            key = key,
            nowSeconds = createdAtSeconds,
            auxRand32 = ByteArray(32),
        )
    }
}
