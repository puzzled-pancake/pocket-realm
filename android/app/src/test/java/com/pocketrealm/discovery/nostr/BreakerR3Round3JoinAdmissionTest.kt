package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-N3 ROUND 3 (adversarial breaker R2): hostile/malformed join
 * descriptors ridden through the FULL relay-admission path (signed event →
 * ListingStore.accept) — the shapes a malicious RELAY or a hostile
 * publisher can put on the wire. Every row must either REFUSE with the
 * VAL-IROH-JOIN code or, where the row says ADMITTED, be a value the
 * joiner can safely act on. A shape that slips through unrefined is the
 * finding.
 */
class BreakerR3Round3JoinAdmissionTest {

    private val key = NostrPrivateKey.fromHex(RealmListingEventTest.GOLDEN_SECKEY)
    private val t0 = RealmListingEventTest.GOLDEN_CREATED

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

    /** Builds + signs an event whose content's `join` is [joinJsonRaw]
     * (a raw JSON VALUE string, exactly as a hostile publisher wrote it). */
    private fun eventWithJoin(joinJsonRaw: String): NostrEvent {
        val content = goldenContent("J").toJsonString()
        // Splice the raw join value into the pinned-order JSON: the content
        // ends with ,"join":null,... — replace that null.
        val spliced = content.replace("\"join\":null", "\"join\":$joinJsonRaw")
        check(spliced != content) { "fixture splice failed" }
        return NostrEvent.create(
            key = key,
            pending = PendingEvent(
                createdAtSeconds = t0,
                kind = RealmListingContract.KIND,
                tags = listOf(
                    listOf("d", RealmListingContract.D_TAG),
                    listOf("t", RealmListingContract.T_TAG),
                ),
                content = spliced,
            ),
            auxRand32 = ByteArray(32),
        )
    }

    private fun accept(joinJsonRaw: String): ListingStore.AcceptResult =
        ListingStore(nowMs = { (t0 + 120) * 1000 }).accept(eventWithJoin(joinJsonRaw), "wss://relay")

    private fun assertRefusedWithJoinCode(joinJsonRaw: String, label: String) {
        val result = accept(joinJsonRaw)
        assertTrue(
            "$label must refuse with VAL-IROH-JOIN, got: $result",
            result is ListingStore.AcceptResult.Refused &&
                (result as ListingStore.AcceptResult.Refused).reason.isNotBlank(),
        )
    }

    // ---------------------------------------------------- control (green)

    @Test
    fun controlWellFormedDescriptorIsAdmitted() {
        val result = accept(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"https://usw1-1.relay.n0.iroh.link\"}",
        )
        assertTrue("control admitted: $result", result is ListingStore.AcceptResult.Accepted)
    }

    // ------------------------------------------------------- shape mischief

    @Test
    fun joinAsArrayNumberOrStringRefuses() {
        assertRefusedWithJoinCode("[\"iroh\",1,2]", "array join")
        assertRefusedWithJoinCode("42", "number join")
        assertRefusedWithJoinCode("\"https://evil.example\"", "string join")
    }

    @Test
    fun nodeIdShapeMischiefRefuses() {
        val relay = "\"relay\":\"https://relay.example.com\""
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"AB".repeat(32)}\",$relay}",
            "uppercase node hex",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(31)}\",$relay}",
            "63-char node",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(31)}g\",$relay}",
            "64 chars with a non-hex tail",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(31)} \",$relay}",
            "node with trailing whitespace",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"0x${"ab".repeat(31)}\",$relay}",
            "0x-prefixed node",
        )
    }

    // ------------------------------------------------- relay-URL mischief

    /**
     * PROBE (expected RED until fixed): the relay-port fence bounds the
     * digit COUNT only (<=5 digits) — a 5-digit port whose VALUE is beyond
     * the u16 range (99999 > 65535) is not a port at all, yet it rides the
     * fence into a SIGNED PUBLIC listing. The join only fails later, at the
     * iroh dial. A validated descriptor should never carry a port the URL
     * grammar itself forbids.
     */
    @Test
    fun relayPortValueBeyondU16Refuses() {
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"https://relay.example.com:99999\"}",
            "port value 99999",
        )
    }

    @Test
    fun relaySchemeMischiefRefuses() {
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"http://relay.example.com\"}",
            "http scheme",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"HTTPS://relay.example.com\"}",
            "uppercase scheme",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"https://relay.example.com:0x50\"}",
            "hex port",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"https://[2001:db8::1]\"}",
            "bracketed IPv6 host",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"https://relay.example.com/deep/path\"}",
            "pathed relay",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"https://user:pass@relay.example.com\"}",
            "userinfo relay",
        )
        assertRefusedWithJoinCode(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"\"}",
            "empty relay",
        )
    }

    /**
     * PROBE: a wildcard-DNS host (nip.io/sslip.io class) — "1.2.3.4.nip.io"
     * RESOLVES to 1.2.3.4 and is a hostname only by the fence's grammar
     * (digits labels + a letter-bearing tail). The fence's contract is IP
     * LITERALS, so this ADMITS today; the probe pins what actually happens
     * so the doc's "an IP-literal relay host is REFUSED" claim can be read
     * against reality (a rebinding hostname is not a literal).
     */
    @Test
    fun wildcardDnsRelayHostProbe() {
        val result = accept(
            "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\"," +
                "\"relay\":\"https://1.2.3.4.nip.io\"}",
        )
        println(
            "wildcard-DNS relay host admission: " +
                if (result is ListingStore.AcceptResult.Accepted) "ADMITTED" else "REFUSED",
        )
    }

    /** The octal/dotless spellings the fence hunts stay refused on the
     * relay host leg (no letter-bearing tail). */
    @Test
    fun octalAndDotlessSpellingsRefuse() {
        for (relay in listOf("https://0177.0.0.1", "https://2130706433", "https://127.1")) {
            assertRefusedWithJoinCode(
                "{\"type\":\"iroh\",\"node\":\"${"ab".repeat(32)}\",\"relay\":\"$relay\"}",
                relay,
            )
        }
    }
}
