package com.pocketrealm.desktop.nostr

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.nostr.ListingStore
import com.pocketrealm.discovery.nostr.NostrEvent
import com.pocketrealm.discovery.nostr.NostrPrivateKey
import com.pocketrealm.discovery.nostr.PendingEvent
import com.pocketrealm.discovery.nostr.RealmListingContract
import com.pocketrealm.discovery.nostr.RealmListingContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W6-N1 code-red round 4 — the adversarial breaker's negative tests
 * (cheap, desktop JVM lane, store-level; no sockets). Each test pins the
 * CORRECT contract; a failure here is a live bypass demonstrated against
 * the current tree:
 *
 *  - the admission fence must bound the per-entry TAG payload (the
 *    content keys are pinned, the tag array is not — a validly-signed
 *    event carrying hundreds of thousands of tags is accepted and
 *    retained verbatim, defeating the "a hostile relay flooding
 *    distinct-pubkey listings cannot pin memory" fence per entry);
 *  - the frozen-schema TYPE pins must hold for every field org.json
 *    stringifies: a non-string `motd` renders as machine JSON in the
 *    browser row (the R2B-5 class, `name` leg only);
 *  - the join admission shape (W6-N3) must refuse EVERY non-object join
 *    spelling (a string join is not a descriptor — refused, never
 *    stringified into the browser row);
 *  - the timeframe TAG must agree with the content stage and be unique
 *    (the exact rule the flavor tag already carries);
 *  - a second `d` tag is a malformed NIP-33 coordinate — refused, not
 *    silently first-wins;
 *  - an empty timeframe stage is not a frozen-schema stage — refused,
 *    not rendered as a dangling "flavor (build N) · " row.
 */
@Suppress("LargeClass")
class NostrBreakerR4NegativeTest {

    // ------------------------------------------------------------ control

    /** CONTROL: the write-ALL/read-ALL duplicate still merges to ONE
     * entry with grown relay evidence (proves the merge contract and the
     * probe harness below run against the live store). */
    @Test
    fun controlExactMirrorDedupesToOneEntry() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val good = signedListing(goldenContent())
        assertTrue(store.accept(good, "wss://relay-a") is ListingStore.AcceptResult.Accepted)
        val mirror = store.accept(good, "wss://relay-b")
        assertTrue("the exact mirror was refused: $mirror", mirror is ListingStore.AcceptResult.Accepted)
        assertEquals(1, store.live().size)
        assertEquals(2, store.live().single().seenOn.size)
    }

    // ------------------------------------------------- admission bounds

    /** NEGATIVE (expected FAILING on the current tree): the CONTENT key
     * set and field bounds are pinned at admission, but the TAG array is
     * not — a validly-signed listing carrying 300k tags (~5 MB) is
     * accepted and RETAINED verbatim. 256 such entries (the admission
     * cap) pin ~1.3 GB, defeating the fence's stated purpose ("a hostile
     * relay flooding distinct-pubkey listings cannot pin memory"), and
     * every duplicate redelivery pays the SHA-256 recompute over the
     * whole payload. */
    @Test
    fun oversizedTagPayloadIsRefusedAtAdmission() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val hostileTags = List(TAG_FLOOD_COUNT) { i ->
            listOf("z", (i % TEN).toString().repeat(TAG_VALUE_WIDTH))
        }
        val event = signedListing(goldenContent(), hostileTags)
        val result = store.accept(event, "wss://relay-a")
        if (result is ListingStore.AcceptResult.Accepted) {
            fail(
                "a listing with $TAG_FLOOD_COUNT tags (serialized ~" +
                    "${event.serializedForId().length / BYTES_PER_KB} KB) was accepted " +
                    "and retained (retainedCount=${store.retainedCount()}) — the " +
                    "admission fence counts entries, not bytes, so a hostile " +
                    "relay pins memory per-entry",
            )
        }
    }

    // ------------------------------------------------------ type pins

    /** NEGATIVE (expected FAILING on the current tree): a non-string
     * `motd` rides org.json's optString coercion (verified: 20240303's
     * optString calls value.toString()) — the frozen-schema TYPE pin the
     * R2B-5 fix applied to `name` must hold for `motd` too, or the
     * browser row renders machine JSON as the realm description. */
    @Test
    fun nonStringMotdIsRefusedNotStringified() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedListing(
            goldenContent().put("motd", org.json.JSONObject("{\"x\":1}")),
        )
        val result = store.accept(event, "wss://relay-a")
        if (result is ListingStore.AcceptResult.Accepted) {
            fail(
                "a JSON-object motd was accepted and stringified to '" +
                    store.live().single().content.motd +
                    "' — the browser row renders this as the realm description",
            )
        }
    }

    /** NEGATIVE (W6-N3 posture): a STRING join ("join": "iroh:…") is not
     * a descriptor — the admission shape refuses every non-object join,
     * never stringifying it into the browser row. */
    @Test
    fun nonObjectJoinIsRefusedLikeAnyNonNullJoin() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedListing(goldenContent().put("join", "iroh-node-id@wss://home"))
        val result = store.accept(event, "wss://relay-a")
        if (result is ListingStore.AcceptResult.Accepted) {
            fail(
                "a string join was accepted (parsed join=" +
                    "${store.live().single().content.join}) — the join " +
                    "admission shape refuses EVERY non-object spelling",
            )
        }
    }

    // ------------------------------------------------------ tag pins

    /** NEGATIVE (expected FAILING on the current tree): the timeframe TAG
     * must agree with the content stage — the exact rule the flavor tag
     * already carries (R2). A listing advertising "wotlk-3.0.2" in its
     * (relay-indexed) tag while serving 3.3.5a content makes relay-side
     * filters diverge from what the store serves. */
    @Test
    fun timeframeTagMustAgreeWithTheContentStage() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedListing(
            goldenContent(),
            extraTags = listOf(listOf("timeframe", "wotlk-3.0.2")),
        )
        val result = store.accept(event, "wss://relay-a")
        assertTrue(
            "timeframe tag/content mismatch accepted: $result",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    /** NEGATIVE (expected FAILING on the current tree): duplicate
     * timeframe tags are refused like duplicate flavor tags (R6-3-7) —
     * two tags index the listing under two stages at tag-filtering
     * relays. */
    @Test
    fun duplicateTimeframeTagsAreRefused() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedListing(
            goldenContent(),
            extraTags = listOf(
                listOf("timeframe", "wotlk-3.3.5a"),
                listOf("timeframe", "wotlk-3.3"),
            ),
        )
        val result = store.accept(event, "wss://relay-a")
        assertTrue(
            "duplicate timeframe tags accepted: $result",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    /** NEGATIVE (expected FAILING on the current tree): a second `d` tag
     * is a malformed NIP-33 coordinate — replaceability is defined by THE
     * d tag; two tags mean different relays may index different
     * coordinates. Refused, not silently first-tag-wins. */
    @Test
    fun secondDTagIsRefusedNotSilentlyIgnored() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedListing(
            goldenContent(),
            extraTags = listOf(listOf("d", "pocketrealm-v2")),
        )
        val result = store.accept(event, "wss://relay-a")
        assertTrue(
            "a second d tag was accepted (verify reads only the first): $result",
            result is ListingStore.AcceptResult.Refused,
        )
    }

    /** NEGATIVE (expected FAILING on the current tree): an EMPTY timeframe
     * stage is not a frozen-schema stage — stageRefusal's
     * `if (timeframe.isEmpty()) return null` admits it, and the browser
     * row renders a dangling "flavor (build N) · " detail with no stage. */
    @Test
    fun emptyTimeframeStageIsRefused() {
        val store = ListingStore { (NOW_SECONDS + 60) * 1000 }
        val event = signedListing(goldenContent().put("timeframe", ""))
        val result = store.accept(event, "wss://relay-a")
        if (result is ListingStore.AcceptResult.Accepted) {
            val stored = store.live().single().content
            fail(
                "an empty timeframe stage was accepted — the row renders '" +
                    stored.flavor + " (build " + stored.build + ") · " +
                    stored.timeframeStage + "' with a dangling separator and no stage",
            )
        }
    }

    // -------------------------------------------------------- fixtures

    companion object {
        private const val NOW_SECONDS: Long = 1_760_000_000L
        private const val TAG_FLOOD_COUNT = 300_000
        private const val TAG_VALUE_WIDTH = 12
        private const val TEN = 10
        private const val BYTES_PER_KB = 1024

        /** The frozen-schema content as a mutable JSON object (the same
         * golden the R2/R3 breaker probes sign). */
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
         * fresh hostile key, with the frozen base tags plus any extras. */
        private fun signedListing(
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
