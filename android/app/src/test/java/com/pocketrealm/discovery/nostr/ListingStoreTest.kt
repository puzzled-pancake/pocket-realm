package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ListingStore units: self-verification (never the relay's word),
 * (pubkey, d) newest-wins dedupe, the 10-minute client-side freshness
 * rule, and the frozen-schema content key-set pin. All fixtures ride the
 * golden key from RealmListingEventTest (real signatures, zero mocking).
 */
class ListingStoreTest {

    private val key = NostrPrivateKey.fromHex(RealmListingEventTest.GOLDEN_SECKEY)

    private fun storeAt(nowMs: Long) = ListingStore(nowMs = { nowMs })

    private fun listingEvent(nowSeconds: Long, name: String = "Northdale", aux: Byte = 0): NostrEvent =
        RealmListingEvent.build(
            content = goldenContent(name),
            key = key,
            nowSeconds = nowSeconds,
            auxRand32 = ByteArray(32) { aux },
        )

    private fun goldenContent(name: String) = RealmListingContent(
        name = name,
        motd = "Casual quests and dungeons, no rush.",
        flavor = "wotlk",
        build = ClientFlavors.WOTLK.build,
        timeframeStage = "wotlk-3.3.5a",
        rates = RealmListingContent.ListingRates(1.0, 1.0, 1.0),
        humans = 3,
        bots = 12,
    )

    // ------------------------------------------------------------ accept

    @Test
    fun acceptsAVerifiedListingAndReportsTheRelay() {
        val store = storeAt(nowMs = (RealmListingEventTest.GOLDEN_CREATED + 60) * 1000)
        val result = store.accept(listingEvent(RealmListingEventTest.GOLDEN_CREATED), "wss://relay-one")
        assertTrue(result is ListingStore.AcceptResult.Accepted)
        val live = store.live()
        assertEquals(1, live.size)
        assertEquals(setOf("wss://relay-one"), live[0].seenOn)
        assertEquals("Northdale", live[0].content.name)
        assertEquals(12, live[0].content.bots)
    }

    @Test
    fun refusesATamperedSignatureIdOrContract() {
        val nowSeconds = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((nowSeconds + 60) * 1000)
        val good = listingEvent(nowSeconds)
        val badSig = good.copy(
            sigHex = "ff" + good.sigHex.drop(2),
        )
        assertTrue(store.accept(badSig, "relay") is ListingStore.AcceptResult.Refused)
        val badId = good.copy(idHex = "ab" + good.idHex.drop(2))
        assertTrue(store.accept(badId, "relay") is ListingStore.AcceptResult.Refused)
        val wrongKind = good.copy(kind = 30389)
        assertTrue(store.accept(wrongKind, "relay") is ListingStore.AcceptResult.Refused)
        val noD = good.copy(tags = good.tags.filterNot { it[0] == "d" })
        assertTrue(store.accept(noD, "relay") is ListingStore.AcceptResult.Refused)
        val noT = good.copy(tags = good.tags.filterNot { it[0] == "t" })
        assertTrue(store.accept(noT, "relay") is ListingStore.AcceptResult.Refused)
        val junk = "not json at all"
        assertTrue(store.accept(junk, "relay") is ListingStore.AcceptResult.Refused)
        assertEquals(0, store.live().size)
    }

    /** The relay cannot launder a listing by re-stamping it: a re-signed
     * event under ANOTHER key is a different (pubkey, d) key — but a
     * content edit by the same key invalidates the signature. */
    @Test
    fun relaySideContentEditIsRefusedByOurSignatureCheck() {
        val nowSeconds = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((nowSeconds + 60) * 1000)
        val good = listingEvent(nowSeconds)
        val edited = good.copy(content = good.content.replace("Northdale", "FakeRealm"))
        val result = store.accept(edited, "wss://evil-relay")
        assertTrue(result is ListingStore.AcceptResult.Refused)
        assertTrue(
            (result as ListingStore.AcceptResult.Refused).reason.contains("VAL-NOSTR-SIG").or(
                result.reason.contains("VAL-NOSTR-EVENT"),
            ),
        )
        assertEquals(0, store.live().size)
    }

    // ------------------------------------------------------------- dedupe

    @Test
    fun samePubkeyDedupesNewestWinsAcrossRelays() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val older = listingEvent(t0, name = "Older")
        val newer = listingEvent(t0 + 30, name = "Newer")
        store.accept(older, "wss://relay-a")
        store.accept(newer, "wss://relay-b")
        val live = store.live()
        assertEquals(1, live.size)
        assertEquals("Newer", live[0].content.name)
    }

    @Test
    fun olderRelayEchoNeverReplacesTheStoredNewest() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        store.accept(listingEvent(t0 + 30, name = "Newer"), "wss://relay-a")
        store.accept(listingEvent(t0, name = "Older"), "wss://relay-b")
        assertEquals(1, store.live().size)
        assertEquals("Newer", store.live()[0].content.name)
    }

    @Test
    fun equalTimestampsKeepTheDeterministicallySmallerId() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val a = listingEvent(t0, name = "Same", aux = 1)
        val b = listingEvent(t0, name = "Same", aux = 2)
        // Same key/clock/name, different aux → different ids; the store
        // must converge on the lexicographically smaller id regardless of
        // arrival order.
        val smaller = if (a.idHex < b.idHex) a else b
        store.accept(b, "wss://relay-a")
        store.accept(a, "wss://relay-b")
        assertEquals(smaller.idHex, store.live()[0].event.idHex)
    }

    @Test
    fun differentPubkeysAreDifferentRealms() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val other = RealmListingEvent.build(goldenContent("Other"), NostrPrivateKey.generate(), t0)
        store.accept(listingEvent(t0, name = "Mine"), "wss://relay-a")
        store.accept(other, "wss://relay-a")
        assertEquals(2, store.live().size)
    }

    @Test
    fun repeatSeeGrowsTheRelayEvidenceNotTheStore() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val event = listingEvent(t0)
        store.accept(event, "wss://relay-a")
        store.accept(event, "wss://relay-b")
        store.accept(event, "wss://relay-a")
        val live = store.live()
        assertEquals(1, live.size)
        assertEquals(setOf("wss://relay-a", "wss://relay-b"), live[0].seenOn)
    }

    // ---------------------------------------------------------- freshness

    @Test
    fun freshnessIsTenMinutesClientSide() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        // Exactly at the boundary (10 min after publish): still fresh.
        assertTrue(storeAt((t0 + 600) * 1000).isFresh(t0))
        // One second past the boundary: gone.
        assertFalse(storeAt((t0 + 601) * 1000).isFresh(t0))
        // A future stamp tolerates bounded skew only.
        assertTrue(storeAt((t0 - 60) * 1000).isFresh(t0))
        assertFalse(storeAt((t0 - 301) * 1000).isFresh(t0))
    }

    @Test
    fun staleListingsLeaveTheLiveViewWithoutBeingTrusted() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val nowHolder = longArrayOf((t0 + 120) * 1000)
        val store = ListingStore(nowMs = { nowHolder[0] })
        store.accept(listingEvent(t0), "wss://relay-a")
        assertEquals(1, store.live().size)
        nowHolder[0] = (t0 + 601) * 1000
        assertEquals(0, store.live().size)
    }

    /** The live order is newest-first (the browse order). */
    @Test
    fun liveViewIsNewestFirstAcrossRealms() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val otherKey = NostrPrivateKey.generate()
        store.accept(listingEvent(t0, name = "Oldest"), "r")
        store.accept(
            RealmListingEvent.build(goldenContent("Middle"), otherKey, t0 + 10, auxRand32 = ByteArray(32)),
            "r",
        )
        store.accept(
            RealmListingEvent.build(
                goldenContent("Newest"), NostrPrivateKey.generate(), t0 + 20, auxRand32 = ByteArray(32),
            ),
            "r",
        )
        assertEquals(
            listOf("Newest", "Middle", "Oldest"),
            store.live().map { it.content.name },
        )
    }

    // ------------------------------------------------------ content shape

    @Test
    fun contentParserPinsTheFrozenSchemaKeySet() {
        val store = storeAt(0L)
        val good = goldenContent("N")
        val json = good.toJsonString()
        val parsed = store.parseContent(json)
        assertTrue(parsed is ListingStore.ContentParse.Ok)
        assertEquals("N", (parsed as ListingStore.ContentParse.Ok).content.name)

        // An extra key refuses: the content is exactly the frozen schema.
        val extra = org.json.JSONObject(json).put("adminUrl", "https://x")
        assertTrue(store.parseContent(extra.toString()) is ListingStore.ContentParse.Refused)
        // A missing key refuses.
        val missing = org.json.JSONObject(json).remove("players")
        assertTrue(store.parseContent(missing.toString()) is ListingStore.ContentParse.Refused)
    }

    // ------------------------------------------- inbound contract

    /** A validly-signed foreign listing honors the SAME contract the
     * publish side enforces: registry-resolved flavor/build pairs (as a
     * matched pair), bounded free text, the privacy fence, the showcase
     * URL shape, finite rates. */
    @Test
    fun inboundContractRefusesRegistryForeignOrMalformedValues() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)

        // Malformed-content events sign through NostrEvent.create directly
        // (build() is the PUBLISHER fence and would refuse first) — this
        // test exercises the STORE's inbound boundary.
        fun signedRaw(contentJson: String): NostrEvent =
            NostrEvent.create(
                key = NostrPrivateKey.generate(),
                pending = PendingEvent(
                    createdAtSeconds = t0,
                    kind = RealmListingContract.KIND,
                    tags = listOf(
                        listOf("d", RealmListingContract.D_TAG),
                        listOf("t", RealmListingContract.T_TAG),
                    ),
                    content = contentJson,
                ),
                auxRand32 = ByteArray(32),
            )

        // The build number rides the OWNING registry (never restated).
        fun contentJson(name: String, flavor: String, build: Int, motd: String = "fine"): String =
            RealmListingContent(
                name = name, motd = motd, flavor = flavor, build = build,
                // a REAL registered stage of the claimed flavor (the store's
                // stage-registry leg refuses fabricated ids)
                timeframeStage = "wotlk-3.3.5a", rates = null, humans = 1, bots = 1,
            ).toJsonString()

        fun wotlkContent(name: String, build: Int = ClientFlavors.WOTLK.build, motd: String = "fine"): String =
            contentJson(name, "wotlk", build, motd)

        // unknown flavor
        val cata = signedRaw(contentJson("Cata", "cata", 40644))
        val cataResult = store.accept(cata, "r")
        assertTrue(cataResult is ListingStore.AcceptResult.Refused)
        assertTrue(
            (cataResult as ListingStore.AcceptResult.Refused).reason.contains("unknown flavor"),
        )

        // mismatched pair (vanilla carrying wotlk's build)
        val mismatch = signedRaw(
            contentJson("Odd", "vanilla", ClientFlavors.WOTLK.build),
        )
        val mismatchResult = store.accept(mismatch, "r")
        assertTrue(mismatchResult is ListingStore.AcceptResult.Refused)
        assertTrue(
            (mismatchResult as ListingStore.AcceptResult.Refused).reason.contains(
                "does not match flavor",
            ),
        )

        // over-long name / motd
        val longName = signedRaw(wotlkContent("N".repeat(200)))
        val longNameResult = store.accept(longName, "r")
        assertTrue(longNameResult is ListingStore.AcceptResult.Refused)
        assertTrue((longNameResult as ListingStore.AcceptResult.Refused).reason.contains("name exceeds"))
        val longMotd = signedRaw(
            wotlkContent("M", motd = "x".repeat(500)),
        )
        assertTrue(store.accept(longMotd, "r") is ListingStore.AcceptResult.Refused)

        // privacy-banned free text (an address a hostile host typed) —
        // incl. the R2-1/R5-r6-2 padded-octal and hex spellings
        val hostile = signedRaw(wotlkContent("Join me at 203.0.113.7:8085"))
        val hostileOctal = signedRaw(wotlkContent("join me at 0177.0.0.1"))
        assertTrue(store.accept(hostileOctal, "r") is ListingStore.AcceptResult.Refused)
        val hostileHex = signedRaw(wotlkContent("connect at 0x7f.0x0.0.1"))
        assertTrue(store.accept(hostileHex, "r") is ListingStore.AcceptResult.Refused)
        val hostileResult = store.accept(hostile, "r")
        assertTrue(hostileResult is ListingStore.AcceptResult.Refused)
        assertTrue(
            (hostileResult as ListingStore.AcceptResult.Refused).reason.contains(
                "VAL-NOSTR-CONTENT",
            ),
        )

        // an arbitrary statusPageUrl is refused (only the showcase shape rides)
        val evilJson = org.json.JSONObject(
            goldenContent("E").toJsonString(),
        ).put("statusPageUrl", "http://evil.example")
        val evilUrl = signedRaw(evilJson.toString())
        assertTrue(store.accept(evilUrl, "r") is ListingStore.AcceptResult.Refused)

        // non-finite rates (1e999 parses through optDouble as Infinity)
        val infiniteRates = org.json.JSONObject(
            goldenContent("I").toJsonString(),
        ).getJSONObject("rates").put("xpKill", "1e999")
        val infiniteContent = org.json.JSONObject(
            goldenContent("I").toJsonString(),
        ).put("rates", infiniteRates)
        val infinite = signedRaw(infiniteContent.toString())
        assertTrue(store.accept(infinite, "r") is ListingStore.AcceptResult.Refused)

        // A non-OBJECT rates member is a
        // frozen-schema TYPE violation — refused with the rates reason,
        // never silently dropped to null (mirrors the name/motd type pins).
        val ratesString = org.json.JSONObject(
            goldenContent("I").toJsonString(),
        ).put("rates", "3x")
        val ratesStringRaw = signedRaw(ratesString.toString())
        val ratesStringResult = store.accept(ratesStringRaw, "r")
        assertTrue(ratesStringResult is ListingStore.AcceptResult.Refused)
        assertTrue(
            (ratesStringResult as ListingStore.AcceptResult.Refused).reason.contains(
                "VAL-NOSTR-CONTENT",
            ),
        )
        val ratesNumber = org.json.JSONObject(
            goldenContent("I").toJsonString(),
        ).put("rates", 3.5)
        val ratesNumberRaw = signedRaw(ratesNumber.toString())
        val ratesNumberResult = store.accept(ratesNumberRaw, "r")
        assertTrue(ratesNumberResult is ListingStore.AcceptResult.Refused)
        assertEquals(0, store.live().size)

        // and a WELL-FORMED foreign listing still passes the same boundary
        val fine = signedRaw(wotlkContent("Fine"))
        assertTrue(store.accept(fine, "r") is ListingStore.AcceptResult.Accepted)

        assertEquals(1, store.live().size)
    }

    // ------------------------------------------- freshness bound

    /** A crafted far-future created_at (the probe's wrap target: the
     * milliseconds multiply folding it to "a minute ago") is REFUSED —
     * the freshness arithmetic runs in SECONDS with a bounded future
     * tolerance. */
    @Test
    fun craftedFarFutureCreatedAtIsRefused() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt(t0 * 1000)
        val targetMs = (t0 - 60) * 1000
        // x*1000 ≡ targetMs (mod 2^64) with x huge: x = (t/8)*125⁻¹ + 3*2^62
        // (adding k*2^62 preserves the wrap because 1000*2^62 ≡ 0 mod 2^64).
        // The probe's exact construction.
        val inv125 = 2066035336255469781L // 125⁻¹ mod 2^64
        val craftedSeconds = (targetMs / 8) * inv125 + 3L * (1L shl 62)
        val crafted = RealmListingEvent.build(
            goldenContent("Wrapped"), key, craftedSeconds, auxRand32 = ByteArray(32),
        )
        // The wrap lands in the far-unsigned-future (a NEGATIVE signed
        // Long) — either way it is outside the freshness window.
        assertTrue(
            crafted.createdAtSeconds < 0 ||
                crafted.createdAtSeconds > (1L shl 62),
        )
        val result = store.accept(crafted, "r")
        assertTrue(result is ListingStore.AcceptResult.Refused)
        assertFalse(store.isFresh(crafted.createdAtSeconds))
        assertEquals(0, store.live().size)
    }

    // --------------------------------- eviction + admission

    @Test
    fun expiredListingsAreEvictedFromTheStore() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val nowHolder = longArrayOf((t0 + 120) * 1000)
        val store = ListingStore(nowMs = { nowHolder[0] })
        repeat(5) { i ->
            store.accept(
                RealmListingEvent.build(
                    goldenContent("E$i"),
                    NostrPrivateKey.generate(),
                    t0 + i.toLong(),
                    auxRand32 = ByteArray(32),
                ),
                "r",
            )
        }
        assertEquals(5, store.retainedCount())
        nowHolder[0] += 20 * 60 * 1000 // 20 minutes: everything expired
        assertEquals(0, store.live().size)
        assertEquals(0, store.retainedCount())
        // Re-accepting after expiry still works (a fresh stamp).
        store.accept(listingEvent(t0 + 20 * 60 + 30, name = "Fresh"), "r")
        assertEquals(1, store.live().size)
    }

    @Test
    fun admissionIsCappedOldestFirst() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val nowHolder = longArrayOf((t0 + 120) * 1000)
        val store = ListingStore(nowMs = { nowHolder[0] })
        val cap = ListingStore.MAX_ENTRIES
        // A distinct-key flood of cap+5 validly-signed listings stays bounded.
        // now sits MID-WINDOW so every stamp stays fresh (the freshness span
        // now-600..now+120 covers t0+0..t0+260).
        nowHolder[0] = (t0 + 300) * 1000
        repeat(cap + 5) { i ->
            store.accept(
                RealmListingEvent.build(
                    goldenContent("Flood$i"),
                    NostrPrivateKey.generate(),
                    t0 + i.toLong(),
                    auxRand32 = ByteArray(32),
                ),
                "r",
            )
        }
        assertEquals(cap, store.retainedCount())
        assertEquals(cap, store.live().size)
        assertTrue(store.live().all { it.content.name.startsWith("Flood") })
    }

    /** The players counts honor the publish side's never-negative rule and
     * a sanity ceiling — a validly-signed hostile listing must not render
     * '-1 humans' or 2-billion padded bots. */
    @Test
    fun inboundListingRefusesImpossiblePlayerCounts() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)

        fun signedRaw(contentJson: String): NostrEvent =
            NostrEvent.create(
                key = NostrPrivateKey.generate(),
                pending = PendingEvent(
                    createdAtSeconds = t0,
                    kind = RealmListingContract.KIND,
                    tags = listOf(
                        listOf("d", RealmListingContract.D_TAG),
                        listOf("t", RealmListingContract.T_TAG),
                    ),
                    content = contentJson,
                ),
                auxRand32 = ByteArray(32),
            )

        val negative = org.json.JSONObject(goldenContent("N").toJsonString())
            .getJSONObject("players").put("humans", -5).put("bots", -1)
        val negativeContent = org.json.JSONObject(goldenContent("N").toJsonString())
            .put("players", negative)
        val negativeResult = store.accept(signedRaw(negativeContent.toString()), "r")
        assertTrue(negativeResult is ListingStore.AcceptResult.Refused)
        assertTrue(
            (negativeResult as ListingStore.AcceptResult.Refused).reason.contains("negative"),
        )

        val padded = org.json.JSONObject(goldenContent("P").toJsonString())
            .getJSONObject("players").put("bots", 2000000000)
        val paddedContent = org.json.JSONObject(goldenContent("P").toJsonString())
            .put("players", padded)
        val paddedResult = store.accept(signedRaw(paddedContent.toString()), "r")
        assertTrue(paddedResult is ListingStore.AcceptResult.Refused)
        assertTrue(
            (paddedResult as ListingStore.AcceptResult.Refused).reason.contains("ceiling"),
        )
    }

    @Test
    fun inboundListingBoundsTheTimeframeText() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val huge = org.json.JSONObject(goldenContent("T").toJsonString())
            .put("timeframe", "x".repeat(200_000))
        val result = store.accept(
            NostrEvent.create(
                key = NostrPrivateKey.generate(),
                pending = PendingEvent(
                    createdAtSeconds = t0,
                    kind = RealmListingContract.KIND,
                    tags = listOf(
                        listOf("d", RealmListingContract.D_TAG),
                        listOf("t", RealmListingContract.T_TAG),
                    ),
                    content = huge.toString(),
                ),
                auxRand32 = ByteArray(32),
            ),
            "r",
        )
        assertTrue(result is ListingStore.AcceptResult.Refused)
        assertTrue((result as ListingStore.AcceptResult.Refused).reason.contains("timeframe exceeds"))
    }



    // ------------------------- round-3 pins (R3-2/R3-5/R5-r3-1/R6-3-7)

    /**
     * W6-N3 (the stage-3 flip of the R3-2/R6-3-4 fence): the join
     * descriptor is CARRIED when it parses — node id + home relay URL
     * only — and a WELL-FORMED descriptor survives the admission ladder
     * intact (the joiner dials exactly what the listing says).
     */
    @Test
    fun inboundWellFormedJoinIsAcceptedAndRetained() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val descriptor = com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
            nodeHex = "ab".repeat(32),
            relayUrl = "https://usw1-1.relay.n0.iroh.link",
        )
        val content = goldenContent("J").copy(join = descriptor)
        val result = store.accept(
            NostrEvent.create(
                key = NostrPrivateKey.generate(),
                pending = PendingEvent(
                    createdAtSeconds = t0,
                    kind = RealmListingContract.KIND,
                    tags = listOf(
                        listOf("d", RealmListingContract.D_TAG),
                        listOf("t", RealmListingContract.T_TAG),
                    ),
                    content = content.toJsonString(),
                ),
                auxRand32 = ByteArray(32),
            ),
            "r",
        )
        assertTrue("a well-formed join must be accepted: $result", result is ListingStore.AcceptResult.Accepted)
        assertEquals(descriptor, store.live().single().content.join)
    }

    /** The fence's teeth stay: a HOSTILE join (unknown keys, a padded
     * payload) is still refused at admission — bounded, never retained. */
    @Test
    fun inboundHostileJoinIsRefused() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val pad = "A".repeat(2_000_000)
        val content = org.json.JSONObject(goldenContent("J").toJsonString())
            .put("join", org.json.JSONObject().put("pad", pad))
        val result = store.accept(
            NostrEvent.create(
                key = NostrPrivateKey.generate(),
                pending = PendingEvent(
                    createdAtSeconds = t0,
                    kind = RealmListingContract.KIND,
                    tags = listOf(
                        listOf("d", RealmListingContract.D_TAG),
                        listOf("t", RealmListingContract.T_TAG),
                    ),
                    content = content.toString(),
                ),
                auxRand32 = ByteArray(32),
            ),
            "r",
        )
        assertTrue(result is ListingStore.AcceptResult.Refused)
        assertTrue((result as ListingStore.AcceptResult.Refused).reason.contains("VAL-IROH-JOIN"))
        assertEquals(0, store.live().size)
    }

    /** An IP-literal relay host is refused inbound (the NEVER-list holds
     * for the descriptor too — any spelling). */
    @Test
    fun inboundIpRelayJoinIsRefused() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        for (relay in listOf("https://203.0.113.9", "https://2130706433")) {
            val store = storeAt((t0 + 120) * 1000)
            val content = org.json.JSONObject(goldenContent("J").toJsonString())
                .put(
                    "join",
                    org.json.JSONObject()
                        .put("type", "iroh")
                        .put("node", "ab".repeat(32))
                        .put("relay", relay),
                )
            val result = store.accept(
                NostrEvent.create(
                    key = NostrPrivateKey.generate(),
                    pending = PendingEvent(
                        createdAtSeconds = t0,
                        kind = RealmListingContract.KIND,
                        tags = listOf(
                            listOf("d", RealmListingContract.D_TAG),
                            listOf("t", RealmListingContract.T_TAG),
                        ),
                        content = content.toString(),
                    ),
                    auxRand32 = ByteArray(32),
                ),
                "r",
            )
            assertTrue("relay $relay must refuse", result is ListingStore.AcceptResult.Refused)
            assertEquals(0, store.live().size)
        }
    }

    /** R5-r3-1: the timeframe stage must be a REGISTERED stage of the
     * claimed flavor — a cross-era stage (vanilla's id on a wotlk
     * listing) and a registered-nowhere stage are both refused; every
     * real stage of the flavor round-trips (no false bans). */
    @Test
    fun inboundStageMustBeRegisteredForTheClaimedFlavor() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)

        fun signedStage(stage: String): NostrEvent =
            NostrEvent.create(
                key = NostrPrivateKey.generate(),
                pending = PendingEvent(
                    createdAtSeconds = t0,
                    kind = RealmListingContract.KIND,
                    tags = listOf(
                        listOf("d", RealmListingContract.D_TAG),
                        listOf("t", RealmListingContract.T_TAG),
                    ),
                    content = RealmListingContent(
                        name = "N", motd = "fine", flavor = "wotlk",
                        build = ClientFlavors.WOTLK.build, timeframeStage = stage,
                        rates = null, humans = 1, bots = 1,
                    ).toJsonString(),
                ),
                auxRand32 = ByteArray(32),
            )

        val crossEra = store.accept(signedStage("vanilla-1.12.1"), "r")
        assertTrue(crossEra is ListingStore.AcceptResult.Refused)
        assertTrue(
            (crossEra as ListingStore.AcceptResult.Refused).reason.contains("timeframe"),
        )
        val nowhere = store.accept(signedStage("cata-4.0.1"), "r")
        assertTrue(nowhere is ListingStore.AcceptResult.Refused)

        // No false bans: every real wotlk stage round-trips for wotlk.
        for (stage in com.pocketrealm.client.TimeframeStages.stagesFor("wotlk")) {
            val ok = store.accept(signedStage(stage.id), "r")
            assertTrue(
                "registered stage ${stage.id} must not be banned",
                ok is ListingStore.AcceptResult.Accepted,
            )
        }
    }

    /** An OLDER echo from another relay must not grow the
     * stored entry's relay evidence (the evidence names the relays that
     * delivered the STORED event id). */
    @Test
    fun olderEchoFromAnotherRelayDoesNotGrowTheEvidence() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val newer = listingEvent(t0 + 30, name = "Newer")
        val older = listingEvent(t0, name = "Older")
        store.accept(newer, "wss://relay-a")
        store.accept(older, "wss://relay-b")
        assertEquals(setOf("wss://relay-a"), store.live().single().seenOn)
    }

    /** R6-3-7: duplicate flavor tags are refused (a listing indexes under
     * exactly one flavor at tag-filtering relays). */
    @Test
    fun duplicateFlavorTagsAreRefused() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        // Properly SIGNED with two flavor tags (a copy() would keep the
        // old signature and die on the id recompute before the tag leg).
        val doubleTagged = RealmListingEvent.build(
            goldenContent("D"), key, t0, auxRand32 = ByteArray(32),
        ).let { event ->
            event.copy(tags = event.tags + listOf(listOf("flavor", "tbc")))
        }
        val reSigned = NostrEvent.create(
            key = key,
            pending = PendingEvent(
                createdAtSeconds = doubleTagged.createdAtSeconds,
                kind = doubleTagged.kind,
                tags = doubleTagged.tags,
                content = doubleTagged.content,
            ),
            auxRand32 = ByteArray(32),
        )
        val result = store.accept(reSigned, "r")
        assertTrue(result is ListingStore.AcceptResult.Refused)
        assertTrue((result as ListingStore.AcceptResult.Refused).reason.contains("flavor tags"))
    }

    /** R7-r2-2: the duplicate fast path — a second relay carrying the same
     * id adds ZERO Schnorr verifies (the SHA-256 recompute still ran). */
    @Test
    fun duplicateDeliveryAddsNoSchnorrVerify() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val event = listingEvent(t0)
        com.pocketrealm.discovery.nostr.Secp256k1Verify.schnorrVerifyCalls = 0
        store.accept(event, "wss://relay-a")
        val afterFirst = com.pocketrealm.discovery.nostr.Secp256k1Verify.schnorrVerifyCalls
        assertTrue("the first accept must verify", afterFirst >= 1)
        store.accept(event, "wss://relay-b")
        assertEquals(
            "the duplicate delivery must add zero Schnorr verifies",
            afterFirst,
            com.pocketrealm.discovery.nostr.Secp256k1Verify.schnorrVerifyCalls,
        )
    }

    // --------------------- duplicate fast path + merge evidence

    @Test
    fun duplicateIdFromASecondRelayMergesEvidence() {
        val t0 = RealmListingEventTest.GOLDEN_CREATED
        val store = storeAt((t0 + 120) * 1000)
        val event = listingEvent(t0)
        assertTrue(store.accept(event, "wss://relay-a") is ListingStore.AcceptResult.Accepted)
        assertTrue(store.accept(event, "wss://relay-b") is ListingStore.AcceptResult.Accepted)
        assertEquals(setOf("wss://relay-a", "wss://relay-b"), store.live().single().seenOn)
    }
}
