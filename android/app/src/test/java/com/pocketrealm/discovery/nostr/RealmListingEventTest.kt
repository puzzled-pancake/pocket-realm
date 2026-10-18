package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W6-N1 golden vectors for the realm listing event.
 *
 * Grounding (three independent legs):
 *  1. The OFFICIAL BIP-340 test vectors (bitcoin/bips bip-0340
 *     test-vectors.csv): rows 1/2 are fully deterministic (fixed
 *     aux_rand) so our signing must reproduce the official signature
 *     byte-for-byte; rows 6/7/8/13 must refuse to verify.
 *  2. A from-scratch NIP-01 + BIP-340 implementation
 *     (tmp/nostr_golden_authoring.py — it passes ALL official vectors
 *     before producing any pin) computed the full listing pin below:
 *     fixed key, fixed wall clock, aux = 32 zero bytes.
 *  3. Round-trip: our sign verifies under our verify; any tampered byte
 *     refuses.
 */
class RealmListingEventTest {

    private val key = NostrPrivateKey.fromHex(GOLDEN_SECKEY)

    // ------------------------------------------------------- BIP-340 core

    /** Official vector 1: deterministic (aux 0x...01). */
    @Test
    fun bip340OfficialVector1SignsTheOfficialSignature() {
        val sk = NostrPrivateKey.fromHex("b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef")
        val msg = hexToBytes("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")
        val aux = hexToBytes("0000000000000000000000000000000000000000000000000000000000000001")
        val sig = sk.signSchnorr(msg, aux)
        assertEquals(
            "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de3341" +
                "8906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a",
            Hex.encode(sig),
        )
        assertTrue(Secp256k1Verify.schnorr(sig, msg, hexToBytes(BIP340_PUBKEY_1)))
    }

    /** Official vector 2: deterministic (aux 0xC87A...06). */
    @Test
    fun bip340OfficialVector2SignsTheOfficialSignature() {
        val sk = NostrPrivateKey.fromHex("c90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b14e5c9")
        val msg = hexToBytes("7e2d58d8b3bcdf1abadec7829054f90dda9805aab56c77333024b9d0a508b75c")
        val aux = hexToBytes("c87aa53824b4d7ae2eb035a2b5bbbccc080e76cdc6d1692c4b0b62d798e6d906")
        val sig = sk.signSchnorr(msg, aux)
        assertEquals(
            "5831aaeed7b44bb74e5eab94ba9d4294c49bcf2a60728d8b4c200f50dd313c1b" +
                "ab745879a5ad954a72c45a91c3a51d3c7adea98d82f8481e0e1e03674a6f3fb7",
            Hex.encode(sig),
        )
    }

    /** Official verify negatives: negated s (8), sig[32:64] = curve order
     * (13), negated message (7). */
    @Test
    fun bip340OfficialNegativesRefuse() {
        val pub = hexToBytes(BIP340_PUBKEY_1)
        val msg = hexToBytes("243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89")
        val negatedS = hexToBytes(
            "6cff5c3ba86c69ea4b7376f31a9bcb4f74c1976089b2d9963da2e5543e177769" +
                "961764b3aa9b2ffcb6ef947b6887a226e8d7c93e00c5ed0c1834ff0d0c2e6da6",
        )
        assertFalse(Secp256k1Verify.schnorr(negatedS, msg, pub))
        val sEqualsN = hexToBytes(
            "6cff5c3ba86c69ea4b7376f31a9bcb4f74c1976089b2d9963da2e5543e177769" +
                "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
        )
        assertFalse(Secp256k1Verify.schnorr(sEqualsN, msg, pub))
        val negatedMsg = hexToBytes(
            "1fa62e331edbc21c394792d2ab1100a7b432b013df3f6ff4f99fcb33e0e1515f" +
                "28890b3edb6e7189b630448b515ce4f8622a954cfe545735aaea5134fccdb2bd",
        )
        assertFalse(Secp256k1Verify.schnorr(negatedMsg, msg, pub))
    }

    // ------------------------------------------------------ NIP-01 golden

    /** The minimal-JSON escaper against known pairs (this pins the
     * escaping the golden serialization below depends on, independently
     * of the serializer itself). */
    @Test
    fun jsonEscapingIsMinimalAndNip01Exact() {
        assertEquals("\"a\\\"b\"", NostrJson.quote("a\"b"))
        assertEquals("\"a\\\\b\"", NostrJson.quote("a\\b"))
        assertEquals("\"a\\nb\"", NostrJson.quote("a\nb"))
        assertEquals("\"a\\u0001b\"", NostrJson.quote("a\u0001b"))
        // Non-ASCII rides through as real UTF-8 (minimal escaping).
        assertEquals("\"café — nörd\"", NostrJson.quote("café — nörd"))
    }

    @Test
    fun goldenListingProducesThePinnedIdAndSignature() {
        val content = goldenContent()
        val event = RealmListingEvent.build(
            content = content,
            key = key,
            nowSeconds = GOLDEN_CREATED,
            auxRand32 = ByteArray(32), // the authoring aux: deterministic
        )
        assertEquals(GOLDEN_PUBKEY, event.pubkeyHex)
        assertEquals(GOLDEN_ID, event.idHex)
        assertEquals(GOLDEN_SIG, event.sigHex)
        assertEquals(RealmListingContract.KIND, event.kind)
        assertEquals(
            listOf(
                listOf("d", RealmListingContract.D_TAG),
                listOf("t", RealmListingContract.T_TAG),
                listOf("expiration", "1760000600"),
                listOf("flavor", "wotlk"),
                listOf("timeframe", "progression-wotlk-1x"),
            ),
            event.tags,
        )
        assertEquals(GOLDEN_CONTENT, event.content)
        // The serialization, composed from the pinned parts (the escaping
        // is independently pinned by jsonEscapingIsMinimalAndNip01Exact):
        // this exact string hashes to GOLDEN_ID per the authoring script.
        // The tags ride as an ARRAY (never quoted) and the content as one
        // minimally-escaped JSON string.
        assertEquals(
            "[0,\"$GOLDEN_PUBKEY\",1760000000,30388,$GOLDEN_TAGS," +
                NostrJson.quote(GOLDEN_CONTENT) + "]",
            event.serializedForId(),
        )
        // The wire form: the pinned event object.
        assertEquals(
            "{\"id\":\"$GOLDEN_ID\",\"pubkey\":\"$GOLDEN_PUBKEY\",\"created_at\":1760000000," +
                "\"kind\":30388,\"tags\":$GOLDEN_TAGS,\"content\":" +
                NostrJson.quote(GOLDEN_CONTENT) + ",\"sig\":\"$GOLDEN_SIG\"}",
            event.toWireJson(),
        )
        // The id commits to the serialization: recompute and compare.
        assertEquals(event.idHex, NostrEvent.computeId(event.serializedForId()))
    }

    /** The wire frame is `["EVENT", <event>]` — one parse back yields the
     * same event (the relay-path shape). */
    @Test
    fun wireFrameRoundTripsThroughTheRelayParser() {
        val event = RealmListingEvent.build(goldenContent(), key, GOLDEN_CREATED, auxRand32 = ByteArray(32))
        val parsed = NostrEvent.fromRelayFrame(event.toEventFrame())
        assertEquals(1, parsed.size)
        assertEquals(event, parsed[0].second)
    }

    /** Signature round-trip: a freshly signed event verifies; flipping
     * one sig bit, one content byte, or one pubkey byte all refuse. */
    @Test
    fun signatureRoundTripVerifiesAndTamperingRefuses() {
        val freshKey = NostrPrivateKey.generate()
        val event = RealmListingEvent.build(goldenContent(), freshKey, GOLDEN_CREATED)
        assertTrue(RealmListingEvent.verifyListing(event).ok)
        val event2 = RealmListingEvent.build(
            goldenContent(), freshKey, GOLDEN_CREATED, auxRand32 = ByteArray(32) { 1 },
        )
        // A different aux rand derives a different nonce — both verify.
        assertTrue(RealmListingEvent.verifyListing(event2).ok)
        assertNotEquals(event.sigHex, event2.sigHex)
        val tamperedSig = event.copy(
            sigHex = if (event.sigHex[0] == '0') '1' + event.sigHex.drop(1) else '0' + event.sigHex.drop(1),
        )
        assertFalse(RealmListingEvent.verifyListing(tamperedSig).ok)
        val tamperedContent = event.copy(content = event.content.replace("Northdale", "Southdale"))
        assertFalse(RealmListingEvent.verifyListing(tamperedContent).ok)
        val wrongSigner = event.copy(pubkeyHex = GOLDEN_PUBKEY)
        assertFalse(RealmListingEvent.verifyListing(wrongSigner).ok)
    }

    /** The contract checks: wrong kind, wrong d, wrong t, mismatched id —
     * each refuses with its VAL reason. */
    @Test
    fun contractViolationsRefuseLoud() {
        val event = RealmListingEvent.build(goldenContent(), key, GOLDEN_CREATED, auxRand32 = ByteArray(32))
        assertNull(RealmListingEvent.verifyListing(event).reason)
        val wrongKind = event.copy(kind = 1)
        assertTrue(RealmListingEvent.verifyListing(wrongKind).reason.orEmpty().startsWith("VAL-NOSTR-EVENT"))
        val wrongD = event.copy(tags = event.tags.map { if (it[0] == "d") listOf("d", "other") else it })
        assertTrue(RealmListingEvent.verifyListing(wrongD).reason.orEmpty().contains("d tag"))
        val wrongT = event.copy(tags = event.tags.filterNot { it[0] == "t" })
        assertTrue(RealmListingEvent.verifyListing(wrongT).reason.orEmpty().contains("t tag"))
        val wrongId = event.copy(idHex = "00".repeat(32))
        assertTrue(RealmListingEvent.verifyListing(wrongId).reason.orEmpty().contains("id does not match"))
    }

    // ------------------------------------------------------- NIP-09 twin

    @Test
    fun deletionEventCarriesEAndATagsAndEmptyContent() {
        val listing = RealmListingEvent.build(goldenContent(), key, GOLDEN_CREATED, auxRand32 = ByteArray(32))
        val deletion = RealmListingEvent.buildDeletion(listing, key, GOLDEN_CREATED + 1, ByteArray(32))
        assertEquals(RealmListingContract.DELETION_KIND, deletion.kind)
        assertEquals("", deletion.content)
        assertTrue(deletion.tags.contains(listOf("e", listing.idHex)))
        assertTrue(
            deletion.tags.contains(
                listOf(
                    "a",
                    "${RealmListingContract.KIND}:${listing.pubkeyHex}:${RealmListingContract.D_TAG}",
                ),
            ),
        )
        assertTrue(deletion.tags.contains(listOf("k", "30388")))
        // The deletion is itself a correctly signed event.
        assertEquals(deletion.idHex, NostrEvent.computeId(deletion.serializedForId()))
        assertTrue(Secp256k1Verify.schnorr(
            Hex.decode(deletion.sigHex, "test"),
            Hex.decode(deletion.idHex, "test"),
            Hex.decode(deletion.pubkeyHex, "test"),
        ))
    }

    // ------------------------------------------------------- schema edges

    @Test
    fun joinIsNullWhileTheGatewayIsDownAndStatusPageOptional() {
        // W6-N3 semantics: join is the HONEST "host gateway not running"
        // — a listing still publishes without it, never a stale descriptor.
        val content = goldenContent().copy(statusPageUrl = null)
        val event = RealmListingEvent.build(content, key, GOLDEN_CREATED, auxRand32 = ByteArray(32))
        assertTrue(event.content.contains("\"join\":null"))
        assertTrue(event.content.contains("\"statusPageUrl\":null"))
    }

    /**
     * W6-N3 (the stage-3 flip): the join descriptor is CARRIED — node id +
     * home relay URL ONLY (the constraint-2-narrowed form), serialized in
     * a pinned key order. Byte-pinned full join serialization (the same
     * determinism contract as the N1 golden vectors).
     */
    @Test
    fun joinDescriptorRidesTheListingWithPinnedSerialization() {
        val descriptor = com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
            nodeHex = "11".repeat(32),
            relayUrl = "https://usw1-1.relay.n0.iroh.link",
        )
        val content = goldenContent().copy(
            join = descriptor,
            // A REGISTERED wotlk stage (the store's admission ladder checks
            // stage registration; the N1 golden fixture's stage predates it).
            timeframeStage = "wotlk-3.3.5a",
        )
        val event = RealmListingEvent.build(content, key, GOLDEN_CREATED, auxRand32 = ByteArray(32))
        val expectedJoin =
            "\"join\":{\"type\":\"iroh\",\"node\":\"" + "11".repeat(32) + "\"," +
                "\"relay\":\"https://usw1-1.relay.n0.iroh.link\"}"
        assertTrue(
            "the pinned join serialization drifted:\n${event.content}",
            event.content.contains(expectedJoin),
        )
        // The id commits to the descriptor — the serialization is what
        // every verifier re-hashes.
        assertEquals(event.idHex, NostrEvent.computeId(event.serializedForId()))
        // Round-trips through the store's content parse.
        val reparsed = ListingStore { (GOLDEN_CREATED + 60) * 1000 }
            .parseContent(event.content)
        assertTrue(reparsed is ListingStore.ContentParse.Ok)
        assertEquals(descriptor, (reparsed as ListingStore.ContentParse.Ok).content.join)
    }

    /**
     * W6-N3 privacy pin (extends publishedRecordCarriesNoAddrEntry to the
     * join-carrying record): a descriptor whose relay URL is an IP literal
     * must be REFUSED at construction — a public listing never carries a
     * dialable address, in ANY field.
     */
    @Test
    fun joinDescriptorRefusesIpRelayHosts() {
        for (relay in listOf(
            "https://203.0.113.9",
            "https://203.0.113.9:3340",
            "https://2130706433",
        )) {
            try {
                com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
                    nodeHex = "11".repeat(32),
                    relayUrl = relay,
                )
                fail("an IP relay host must refuse: $relay")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-IROH-JOIN"))
            }
        }
    }

    @Test
    fun statusPageUrlOnlyRidesTheShowcaseShape() {
        val content = goldenContent().copy(statusPageUrl = "https://quiet-meadow-abc123.trycloudflare.com")
        val event = RealmListingEvent.build(content, key, GOLDEN_CREATED, auxRand32 = ByteArray(32))
        assertTrue(event.content.contains("\"statusPageUrl\":\"https://quiet-meadow-abc123.trycloudflare.com\""))
        for (bad in listOf(
            "http://quiet-meadow-abc123.trycloudflare.com", // not https
            "https://evil.example.com/steal", // not the tunnel shape
            "https://192.168.1.10:8085/", // an IP, never
        )) {
            try {
                RealmListingEvent.build(goldenContent().copy(statusPageUrl = bad), key, GOLDEN_CREATED)
                fail("expected VAL-NOSTR-CONTENT for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
            }
        }
    }

    @Test
    fun flavorBuildTableIsTheShippedLanes() {
        // The mapping must resolve to the OWNING registry's builds (the
        // listing can never drift from the shipped lane identities).
        assertEquals(ClientFlavors.VANILLA.build, RealmListingContent.buildForFlavor("vanilla"))
        assertEquals(ClientFlavors.TBC.build, RealmListingContent.buildForFlavor("tbc"))
        assertEquals(ClientFlavors.WOTLK.build, RealmListingContent.buildForFlavor("wotlk"))
        try {
            RealmListingContent.buildForFlavor("cata")
            fail("expected the unknown-flavor refusal")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
        }
    }

    // ----------------------------------------------------- privacy fence

    /**
     * W6-N2 gate leg (docs/NOSTR-IROH-BUILD-PLAN.md stage 2; the iroh#4009
     * relay_only ruling), extended by W6-N3: the published discovery record
     * must never carry an `addr=` entry — the direct-address vocabulary of
     * iroh/endpoint discovery — and the join descriptor (now carried)
     * changes nothing about that: its relay URL is hostname-only, its node
     * id is opaque hex. Pinned on the FULL serialized event of BOTH the
     * gateway-down and the gateway-up shapes.
     */
    @Test
    fun publishedRecordCarriesNoAddrEntry() {
        val descriptor = com.pocketrealm.discovery.iroh.IrohJoinDescriptor(
            nodeHex = "11".repeat(32),
            relayUrl = "https://usw1-1.relay.n0.iroh.link",
        )
        for (content in listOf(
            goldenContent().copy(statusPageUrl = "https://quiet-meadow-abc123.trycloudflare.com"),
            goldenContent().copy(
                statusPageUrl = "https://quiet-meadow-abc123.trycloudflare.com",
                join = descriptor,
            ),
        )) {
            val event = RealmListingEvent.build(
                content, key, GOLDEN_CREATED, auxRand32 = ByteArray(32),
            )
            val wire = event.serializedForId()
            assertFalse("the serialized record carries addr=", wire.contains("addr="))
            assertFalse("the serialized record carries an addr key", wire.contains("\"addr\""))
            assertFalse("the record content carries an IP literal", wire.contains("192.168."))
            // Every content key is from the frozen schema — none is an address key.
            val allowed = setOf(
                "name", "motd", "flavor", "build", "timeframe",
                "rates", "players", "join", "statusPageUrl",
            )
            val keys = org.json.JSONObject(event.content).keys().asSequence().toList()
            assertTrue("unexpected content keys: $keys", keys.all { it in allowed })
            // Tags stay in the contract families: d / t / expiration / flavor / timeframe.
            for (tag in event.tags) {
                assertNotEquals("addr", tag.firstOrNull())
            }
        }
    }

    @Test
    fun privacyGuardRefusesIpsHostnamesAndUrls() {
        for (banned in listOf(
            "join me at 192.168.1.10",
            "realm at 10.0.0.1:8085",
            "host at my-realm.example.com",
            "download at https://cdn.example.com/client.exe",
            "direct: wss://myhost.dev/relay",
            "ftp://files.example.com/wow",
            // R3-5/R5-1: the IPv6 and leading-zero spellings are
            // connectable address forms — refused, fail-closed.
            "connect at 192.168.000.001",
            "connect at [2001:db8::1]:8085",
            "2001:db8::1",
            "::ffff:192.168.1.10",
            // R2B-1: inet_aton shorthand + zone-id spellings are
            // connectable address forms — refused, fail-closed.
            "join me at 10.1 port 8085",
            "gateway 192.168.1",
            "reach me on fe80::1%eth0",
            "[2001:db8::1%25eth0]:8085",
            // R3-1/R6-3-1 + R3-3/R6-3-2: the sentence-final period and the
            // dotless decimal are connectable spellings — refused.
            "connect tonight at 10.0.0.1.",
            "join 2130706433 port 8085",
            // R3-r4-1/R2-1: octal and 4-digit-padded spellings are
            // connectable on common resolvers — refused, fail-closed.
            "join me at 0177.0.0.1",
            "connect at 0x7f.0x0.0.1",
            "dial 0x7f000001 today",
            // R3-4/R6-3-3: the IDN hostname and its punycode form are the
            // same resolvable hostname — both refused.
            "水.com",
            "join at xn--1rw.com",
        )) {
            try {
                RealmListingContent.sanitize(name = banned, motd = "")
                fail("expected refusal for: $banned")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
            }
        }
        // R2B-1: the OUTBOUND build path refuses the shorthand too — a
        // signed-and-published listing can never carry "join me at 10.1".
        try {
            RealmListingEvent.build(
                goldenContent().copy(motd = "join me at 10.1"),
                key, GOLDEN_CREATED,
            )
            fail("the outbound build signed an inet_aton shorthand MOTD")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().startsWith("VAL-NOSTR-CONTENT"))
        }
        // Ordinary prose stays clean (versions like "3.3.5a" and "v1.2"
        // are not hostname shapes; a lone TLD-less word never matches).
        RealmListingContent.sanitize(
            "Northdale patch 3.3.5a",
            "Casual quests, 1x rates, no rush. Version v1.2 — see the status page.",
        )
    }

    @Test
    fun listingContentParsesFromStatusPayloadShape() {
        val payload = org.json.JSONObject(
            """
            {"realm":{"name":"Northdale","description":"Casual realm.","flavor":"wotlk",
              "timeframeStageId":"progression-wotlk-1x"},
             "players":{"source":"native","humansOnline":3,"botsOnline":12},
             "rates":{"xpKill":1,"xpQuest":1,"xpHonor":0.5},
             "public":{"url":null,"tunnelState":"down"}}
            """.trimIndent(),
        )
        val content = RealmListingContent.fromStatusPayload(payload)
        assertEquals("Northdale", content?.name)
        assertEquals("wotlk", content?.flavor)
        assertEquals(ClientFlavors.WOTLK.build, content?.build)
        assertEquals("progression-wotlk-1x", content?.timeframeStage)
        assertEquals(3, content?.humans)
        assertEquals(12, content?.bots)
        assertNull(content?.join)
        assertNull(content?.statusPageUrl)
        assertEquals(0.5, content?.rates?.xpHonor ?: -1.0, 0.0)
    }

    /** R1-2: a payload whose flavor key is MISSING (or names an unknown
     * lane) must never default to vanilla — the beat is skipped
     * fail-closed, exactly like buildForFlavor's unknown-flavor refusal. */
    @Test
    fun missingOrUnknownFlavorPublishesNoListing() {
        val missingFlavor = org.json.JSONObject(
            """{"realm":{"name":"N","description":"","timeframeStageId":"s"},
                 "players":{"source":"native","humansOnline":1,"botsOnline":1},
                 "rates":{"xpKill":1,"xpQuest":1,"xpHonor":1},
                 "public":{"url":null,"tunnelState":"down"}}""".trimIndent(),
        )
        assertNull(RealmListingContent.fromStatusPayload(missingFlavor))
        val unknownFlavor = org.json.JSONObject(
            """{"realm":{"name":"N","description":"","flavor":"cata",
                 "timeframeStageId":"s"},
                 "players":{"source":"native","humansOnline":1,"botsOnline":1},
                 "rates":{"xpKill":1,"xpQuest":1,"xpHonor":1},
                 "public":{"url":null,"tunnelState":"down"}}""".trimIndent(),
        )
        assertNull(RealmListingContent.fromStatusPayload(unknownFlavor))
    }

    /** Bots are never padded: a degraded/failed players split refuses to
     * become a listing at all (the beat is skipped). */
    @Test
    fun degradedPlayersSplitPublishesNoListing() {
        for (source in listOf("degraded", "cross-check-failed", "world-down")) {
            val payload = org.json.JSONObject(
                """
                {"realm":{"name":"N","description":"","flavor":"vanilla",
                  "timeframeStageId":"s"},
                 "players":{"source":"$source","humansOnline":null,"botsOnline":null},
                 "rates":{"xpKill":null,"xpQuest":null,"xpHonor":null},
                 "public":{"url":null,"tunnelState":"down"}}
                """.trimIndent(),
            )
            assertNull(RealmListingContent.fromStatusPayload(payload))
        }
    }

    private fun goldenContent() = RealmListingContent(
        name = "Northdale",
        motd = "Casual quests and dungeons, no rush.",
        flavor = "wotlk",
        build = ClientFlavors.WOTLK.build,
        timeframeStage = "progression-wotlk-1x",
        rates = RealmListingContent.ListingRates(1.0, 1.0, 1.0),
        humans = 3,
        bots = 12,
    )

    private fun hexToBytes(hex: String) = Hex.decode(hex, "test")

    // The pinned golden constants (tmp/nostr_golden_authoring.py, a
    // BIP-340-official-vector-verified from-scratch implementation).
    companion object {
        const val GOLDEN_SECKEY: String = "0f01e21b4b3e2e5c6f21a8b3c9d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6"
        const val GOLDEN_PUBKEY: String = "23f00fc13d7d90217ec94045116c041872a9732156798152892e355a0fce8e28"
        const val GOLDEN_CREATED: Long = 1760000000L
        const val GOLDEN_ID: String = "99fc6e950d354e850ab12b4ab2045dbaa9302e6aa866272bb2011eeb3bed2bb0"
        const val GOLDEN_SIG: String =
            "1d78bb09f4823521fd8e86c95ba5d2897ea66b3973ccff319627ef941b6353ec5" +
                "acc1c6230924b41defa7e3e09a3c250731584d310e5eaaeb7e1eccce3bb4307"
        const val GOLDEN_TAGS: String =
            "[[\"d\",\"pocketrealm-v1\"],[\"t\",\"pocketrealm\"]," +
                "[\"expiration\",\"1760000600\"],[\"flavor\",\"wotlk\"]," +
                "[\"timeframe\",\"progression-wotlk-1x\"]]"
        // The build field rides the OWNING registry (the golden vector is
        // only valid for the shipped wotlk lane); the byte-exactness of the
        // composed string is pinned by GOLDEN_ID/GOLDEN_SIG, which fail on
        // a single drifted byte.
        val GOLDEN_CONTENT: String =
            "{\"name\":\"Northdale\",\"motd\":\"Casual quests and dungeons, no rush.\"," +
                "\"flavor\":\"wotlk\",\"build\":${ClientFlavors.WOTLK.build},\"timeframe\":\"progression-wotlk-1x\"," +
                "\"rates\":{\"xpKill\":1.0,\"xpQuest\":1.0,\"xpHonor\":1.0}," +
                "\"players\":{\"humans\":3,\"bots\":12},\"join\":null,\"statusPageUrl\":null}"
        const val BIP340_PUBKEY_1: String = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"
    }
}
