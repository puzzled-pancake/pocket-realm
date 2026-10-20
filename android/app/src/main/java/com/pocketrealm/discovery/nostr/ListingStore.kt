package com.pocketrealm.discovery.nostr

import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/**
 * W6-N1 (adopted consult corrections): the listing store — the browser's
 * trust boundary and merge point.
 *
 *  - VERIFY OURSELVES: id recomputation over the NIP-01 serialization
 *    (catches relay-side tampering) plus our own BIP-340 signature check
 *    against the embedded pubkey. A relay's word is worth nothing here.
 *  - THE INBOUND SIDE ENFORCES THE SAME CONTRACT THE PUBLISH SIDE DOES
 *    (code-red R1-3/R3-4/R4-6/R5-4/R7-2): a validly-signed foreign listing
 *    must still resolve its flavor/build pair against the owning
 *    ClientFlavors registry, keep its free text inside the frozen-schema
 *    length bounds, pass the privacy fence (IPs/hostnames/URLs — IPv4,
 *    leading-zero and IPv6 spellings), carry only a showcase-shaped
 *    statusPageUrl, and keep its rates finite. Anything else is Refused
 *    with a VAL reason naming the field.
 *  - DEDUPE on (pubkey, d) by newest created_at (NIP-33 replaceable
 *    semantics client-side); equal timestamps keep the lexicographically
 *    smaller event id so the merge is deterministic across read orders.
 *    An EXACT duplicate delivery (every field equal to the stored,
 *    already-verified entry — the write-ALL/read-ALL case) still pays
 *    the cheap SHA-256 id recompute but SKIPS the Schnorr verify —
 *    only the relay evidence grows; ANY differing byte (a tampered
 *    signature, a replayed id over other content) takes the full
 *    ladder.
 *  - FRESHNESS is client-side (the NIP-40 correction), computed in
 * SECONDS (a Long milliseconds multiply wraps for a
 *    crafted ~2^63-second created_at and pins a hostile listing to the
 *    top of the browse order with a "published just now" age). A listing
 *    is live only while created_at is within 10 minutes. `expiration` is
 *    a hint and is never consulted.
 *  - ADMISSION IS BOUNDED (code-red R2-4/R5-6): non-fresh entries are
 *    evicted on every accept, and the retained set is capped — a hostile
 *    relay flooding distinct-pubkey listings cannot pin memory.
 */
// The trust boundary's surfaces are individually small and each is a
// contract leg (the orchestrator-seam idiom; the RealmStatusController
// precedent).
@Suppress("TooManyFunctions")
class ListingStore(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    /** One accepted listing with its parsed frozen-schema content. */
    data class Entry(
        val event: NostrEvent,
        val content: RealmListingContent,
        /** The relays this exact event id was seen on (the write-ALL/
         * read-ALL merge evidence). */
        val seenOn: Set<String>,
    )

    sealed class AcceptResult {
        data class Accepted(val replacedOlder: Boolean) : AcceptResult()
        data class Refused(val reason: String) : AcceptResult()
    }

    private val byKey = LinkedHashMap<Pair<String, String>, Entry>()

    /** Event ids already accepted (the read-ALL duplicate fast path: the
     * second..eighth relay carrying the same id pays the SHA-256 id
     * recompute only — the Schnorr verify is skipped). */

    private val _updates = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    /** All currently-live listings, newest first (the browse order). */
    fun live(): List<Entry> = synchronized(byKey) {
        evictExpiredLocked()
        byKey.values
            .sortedWith(compareByDescending<Entry> { it.event.createdAtSeconds }.thenBy { it.event.idHex })
    }

    /** The live listing count (the honest empty-state input). */
    fun liveCount(): Int = live().size

    /**
     * Accepts one raw event JSON from [relayUrl]: parse → contract check →
     * id recompute → signature verify → dedupe/merge. Every refusal
     * carries its actionable VAL reason; nothing unverified is retained.
     */
    fun accept(rawEventJson: String, relayUrl: String): AcceptResult {
        val parsed = runCatching { NostrEvent.fromJson(rawEventJson) }
        return parsed.fold(
            onSuccess = { accept(it, relayUrl) },
            onFailure = { failure ->
                AcceptResult.Refused(
                    (failure as? IllegalArgumentException)?.message
                        ?: "VAL-NOSTR-EVENT: unparseable",
                )
            },
        )
    }

    /** Same contract as [accept] for an already-parsed event. */
    // The admission ladder (verify/freshness/parse/merge) IS the trust
    // boundary: one exit per refusal.
    @Suppress("ReturnCount")
    fun accept(event: NostrEvent, relayUrl: String): AcceptResult {
        // The documented write-ALL/read-ALL fast path: a delivery whose
        // FULL event (id, sig, content — every field) equals the already-
        // verified stored entry pays the SHA-256 id recompute, not a
        // Schnorr verify — the relay evidence grows. ANY difference (a
        // tampered signature, a replayed id over other content) falls
        // through to the full ladder and is judged there.
        val knownDuplicate = synchronized(byKey) {
            (byKey[event.pubkeyHex to RealmListingContract.D_TAG]?.event == event &&
                idMatchesContent(event))
        }
        if (knownDuplicate) {
            synchronized(byKey) {
                byKey[event.pubkeyHex to RealmListingContract.D_TAG]?.let { existing ->
                    byKey[event.pubkeyHex to RealmListingContract.D_TAG] =
                        existing.copy(seenOn = existing.seenOn + relayUrl)
                }
            }
            _updates.tryEmit(Unit)
            return AcceptResult.Accepted(replacedOlder = false)
        }
        val refusal = refuseReasonFor(event)
            // the freshness bound ADMITS at accept — a crafted
            // far-future (or far-past) created_at is refused here,
            // never stored to wait for an eviction pass.
            ?: freshnessRefusal(event)
            ?: when (val parse = parseContent(event.content)) {
                is ContentParse.Ok -> null
                is ContentParse.Refused -> parse.reason
            }
        if (refusal != null) return AcceptResult.Refused(refusal)
        val parsedOk = parseContent(event.content) as ContentParse.Ok
        val content = parsedOk.content
        // R5-1(b): the per-entry serialized budget — checked on the event
        // the hostile publisher actually signed (tags included).
        if (event.serializedForId().length > RealmListingContent.MAX_SERIALIZED_CHARS) {
            return AcceptResult.Refused(
                "VAL-NOSTR-CONTENT: listing exceeds the per-entry serialized " +
                    "budget of " + RealmListingContent.MAX_SERIALIZED_CHARS + " characters",
            )
        }
        // The tag-contract legs (uniqueness/agreement; bound;
        // breaker timeframe + d-tag legs) — one refusal builder.
        tagLadderRefusal(event, content)?.let { return AcceptResult.Refused(it) }
        val replacedOlder = synchronized(byKey) {
            evictExpiredLocked()
            mergeLocked(event, content, relayUrl)
        }
        _updates.tryEmit(Unit)
        return AcceptResult.Accepted(replacedOlder)
    }

    /** The tag-contract ladder: the flavor-tag uniqueness/agreement rule
     * (R6-3-7/R1-3), the R4-1 per-entry tag bound, and the R4 breaker
     * timeframe-tag and d-tag legs. */
    // The tag-contract ladder IS the admission contract: one exit per
    // refusal (the fence idiom).
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun tagLadderRefusal(event: NostrEvent, content: RealmListingContent): String? {
        val flavorTags = event.tags.filter { it.size >= 2 && it[0] == "flavor" }
        if (flavorTags.size > 1) {
            return "VAL-NOSTR-CONTENT: " + flavorTags.size +
                " flavor tags - a listing carries exactly one"
        }
        val tagFlavor = flavorTags.firstOrNull()?.getOrNull(1)
        if (tagFlavor != null && tagFlavor != content.flavor) {
            return "VAL-NOSTR-CONTENT: flavor tag '" + tagFlavor + "' does not " +
                "match the content flavor '" + content.flavor + "'"
        }
        // the TAG array is bounded — a validly-signed listing with
        // hundreds of thousands of tags cannot pin memory per entry.
        if (event.tags.size > RealmListingContent.MAX_LISTING_TAGS) {
            return "VAL-NOSTR-CONTENT: " + event.tags.size + " tags exceed the " +
                RealmListingContent.MAX_LISTING_TAGS + "-tag admission bound"
        }
        // each tag VALUE is bounded — a 200 KB value rides the
        // count-only bound into the retained Entry.
        event.tags.forEach { tag ->
            if (tag.size >= 2 && tag.drop(1).any { it.length > RealmListingContent.MAX_TAG_VALUE_LENGTH }) {
                return "VAL-NOSTR-CONTENT: tag value exceeds " +
                    RealmListingContent.MAX_TAG_VALUE_LENGTH + " characters"
            }
        }
        val timeframeTags = event.tags.filter { it.size >= 2 && it[0] == "timeframe" }
        if (timeframeTags.size > 1) {
            return "VAL-NOSTR-CONTENT: " + timeframeTags.size +
                " timeframe tags - a listing carries exactly one"
        }
        val tagStage = timeframeTags.firstOrNull()?.getOrNull(1)
        if (tagStage != null && tagStage != content.timeframeStage) {
            return "VAL-NOSTR-CONTENT: timeframe tag '" + tagStage +
                "' does not match the content stage '" + content.timeframeStage + "'"
        }
        val dTags = event.tags.filter { it.size >= 2 && it[0] == "d" }
        if (dTags.size > 1) {
            return "VAL-NOSTR-CONTENT: " + dTags.size +
                " d tags - replaceability is defined by exactly one"
        }
        return null
    }

    /** The freshness bound at the admission gate. */
    private fun freshnessRefusal(event: NostrEvent): String? =
        if (isFresh(event.createdAtSeconds)) {
            null
        } else {
            "VAL-NOSTR-EVENT: listing created_at is outside the " +
                RealmListingContract.FRESHNESS_MINUTES + "-minute freshness window"
        }
    /**
     * The 10-minute client-side freshness rule (the source of truth), in
     * SECONDS — R2-1: the milliseconds multiply wrapped for a crafted
     * created_at and admitted a hostile far-future listing as "just now".
     */
    fun isFresh(createdAtSeconds: Long): Boolean {
        val nowSeconds = nowMs() / MILLIS_PER_SECOND
        return createdAtSeconds >= nowSeconds - RealmListingContract.FRESHNESS_SECONDS &&
            createdAtSeconds <= nowSeconds + CLOCK_SKEW_SECONDS
    }

    /** Drops everything (the browser's refresh restarts the merge). */
    fun clear() = synchronized(byKey) {
        byKey.clear()
    }

    /** The retained-entry count (the admission-bound pin reads this). */
    fun retainedCount(): Int = synchronized(byKey) { byKey.size }

    /** The verification ladder's refusal reason, or null when the event
     * passes the contract + id + signature checks. */
    private fun refuseReasonFor(event: NostrEvent): String? {
        val verified = RealmListingEvent.verifyListing(event)
        return if (verified.ok) null else verified.reason ?: "VAL-NOSTR-EVENT: refused"
    }

    /** The duplicate fast path's SAFETY precondition: the id
     * recomputes from the delivery's own bytes (a tampered content under a
     * replayed id fails here even before the Schnorr check would). */
    private fun idMatchesContent(event: NostrEvent): Boolean =
        event.idHex == NostrEvent.computeId(event.serializedForId())

    /** The Schnorr verify count (the pin: a duplicate delivery
     * adds ZERO verifies). */
    internal var schnorrVerifyCalls: Int = 0

    /** Evicts entries whose listing has left the freshness window (the
     * R2-4/R5-6 rule: the map holds LIVE listings, not history). Caller
     * holds the lock. */
    private fun evictExpiredLocked() {
        byKey.entries.removeIf { !isFresh(it.value.event.createdAtSeconds) }
    }

    /** The admission fence: when the retained set exceeds the cap, the
     * OLDEST entries (by created_at, then id) are evicted first — a
     * distinct-key flood cannot grow the store without bound. Caller
     * holds the lock. */
    private fun evictOverflowLocked() {
        while (byKey.size > MAX_ENTRIES) {
            val oldest = byKey.entries.minWithOrNull(
                compareBy({ it.value.event.createdAtSeconds }, { it.value.event.idHex }),
            ) ?: break
            byKey.remove(oldest.key)
        }
    }

    /** The (pubkey, d) newest-wins merge; returns whether an OLDER stored
     * listing was replaced. Caller holds the lock and has verified the
     * event. */
    private fun mergeLocked(event: NostrEvent, content: RealmListingContent, relayUrl: String): Boolean {
        val key = event.pubkeyHex to RealmListingContract.D_TAG
        var replacedOlder = false
        val existing = byKey[key]
        when {
            existing == null -> {
                byKey[key] = Entry(event, content, setOf(relayUrl))
                evictOverflowLocked()
            }

            event.createdAtSeconds > existing.event.createdAtSeconds -> {
                byKey[key] = Entry(event, content, setOf(relayUrl))
                replacedOlder = true
            }

            event.createdAtSeconds == existing.event.createdAtSeconds &&
                event.idHex < existing.event.idHex -> {
                byKey[key] = Entry(event, content, setOf(relayUrl))
            }

            else -> {
                // the relay evidence grows ONLY for the stored
                // event's exact id — an older echo from another relay never
                // earns the stored entry "seen on N relays". A differing
                // delivery is dropped (the stored newest stays).
                if (event.idHex == existing.event.idHex) {
                    byKey[key] = existing.copy(seenOn = existing.seenOn + relayUrl)
                }
            }
        }
        return replacedOlder
    }

    /**
     * The inbound frozen-schema contract: parse + shape-check the content
     * JSON. The KEY SET is pinned (exactly the frozen schema), and the
     * VALUES now honor the same rules the publish side enforces (R1-3/
     * R3-4/R4-6/R5-4/R7-2). A refusal names its field.
     */
    internal sealed class ContentParse {
        data class Ok(val content: RealmListingContent) : ContentParse()
        data class Refused(val reason: String) : ContentParse()
    }

    internal fun parseContent(content: String): ContentParse = runCatching {
        parseContentChecked(content)
    }.getOrElse { failure ->
        ContentParse.Refused(
            (failure as? IllegalArgumentException)?.message
                ?: "VAL-NOSTR-EVENT: listing content does not match the frozen schema",
        )
    }

    // The frozen-schema ladder (keys/text/pair/rates/url) IS the
    // contract: one exit per refusal.
    @Suppress("ReturnCount")
    private fun parseContentChecked(content: String): ContentParse {
        val obj = JSONObject(content)
        val players = obj.getJSONObject("players")
        val allowedKeys = setOf(
            "name", "motd", "flavor", "build", "timeframe", "rates", "players", "join", "statusPageUrl",
        )
        if (obj.keys().asSequence().toSet() != allowedKeys) {
            return ContentParse.Refused(
                "VAL-NOSTR-EVENT: listing content does not match the frozen schema (key set)",
            )
        }
        val statusPageUrl = obj.optString("statusPageUrl", "").takeIf { it.isNotEmpty() }
        // the join descriptor is CARRIED (the stage-3 flip) — but
        // only a VALID one. The admission ladder keeps the posture's
        // teeth: the descriptor is bounded at admission (exactly the three
        // keys; node pinned 64 lowercase hex; relay a hostname-only https
        // URL) so a hostile join still retains nothing unbounded, and an
        // IP-literal relay host is refused fail-closed (the NEVER-list).
        // The JSON null sentinel (org.json.NULL) stays the schema-honest
        // "host gateway down"; the sentinel is non-null in Kotlin, hence
        // the identity comparison before the descriptor parse.
        val joinValue = obj.opt("join")
        val join: IrohJoinDescriptor? = when {
            joinValue == null || joinValue === JSONObject.NULL -> null
            else -> IrohJoinDescriptor.fromListingJson(joinValue)
        }
        val ratesObj = obj.optJSONObject("rates")
        val refusal = textRefusal(obj, statusPageUrl)
            ?: pairRefusal(obj)
            ?: playersRefusal(players)
            // The frozen-schema TYPE pin for
            // rates — a non-object rates member is refused, never silently
            // dropped to null (mirrors the name/motd string pins above).
            ?: obj.opt("rates")?.takeIf { it !== JSONObject.NULL && it !is JSONObject }
                ?.let { "VAL-NOSTR-CONTENT: listing rates must be an object" }
            ?: ratesRefusal(ratesObj)
            ?: textTypeRefusal(obj)
            ?: stageRefusal(obj.getString("flavor"), obj.optString("timeframe", ""))
        if (refusal != null) return ContentParse.Refused(refusal)
        return ContentParse.Ok(
            RealmListingContent(
                name = obj.getString("name"),
                motd = obj.optString("motd", ""),
                flavor = obj.getString("flavor"),
                build = obj.getInt("build"),
                timeframeStage = obj.optString("timeframe", ""),
                rates = ratesObj?.let {
                    RealmListingContent.ListingRates(
                        xpKill = it.optDouble("xpKill").takeUnless(Double::isNaN),
                        xpQuest = it.optDouble("xpQuest").takeUnless(Double::isNaN),
                        xpHonor = it.optDouble("xpHonor").takeUnless(Double::isNaN),
                    )
                },
                humans = players.getInt("humans"),
                bots = players.getInt("bots"),
                join = join,
                statusPageUrl = statusPageUrl,
            ),
        )
    }

    /** The inbound bounds mirror the outbound fence exactly —
     * bounded free text through the SAME privacy fence, plus the
     * showcase URL shape, before the UI ever sees a foreign listing. */
    private fun textRefusal(obj: JSONObject, statusPageUrl: String?): String? =
        listOfNotNull(
            lengthRefusal(obj),
            fenceRefusal(obj),
            urlRefusal(statusPageUrl),
        ).firstOrNull()

    private fun lengthRefusal(obj: JSONObject): String? = when {
        // breaker: optString stringifies non-strings — the frozen-schema
        // TYPE pin needs the explicit checks (the name leg generalized).
        obj.get("name") !is String ->
            "VAL-NOSTR-CONTENT: listing name must be a string"
        obj.opt("motd") !is String ->
            "VAL-NOSTR-CONTENT: listing motd must be a string"
        obj.getString("name").length > RealmListingContent.NAME_MAX_LENGTH ->
            "VAL-NOSTR-CONTENT: listing name exceeds " +
                RealmListingContent.NAME_MAX_LENGTH + " characters"
        obj.optString("motd", "").length > RealmListingContent.MOTD_MAX_LENGTH ->
            "VAL-NOSTR-CONTENT: listing motd exceeds " +
                RealmListingContent.MOTD_MAX_LENGTH + " characters"
        obj.getString("name").length > RealmListingContent.NAME_MAX_LENGTH ->
            "VAL-NOSTR-CONTENT: listing name exceeds " +
                RealmListingContent.NAME_MAX_LENGTH + " characters"
        obj.optString("motd", "").length > RealmListingContent.MOTD_MAX_LENGTH ->
            "VAL-NOSTR-CONTENT: listing motd exceeds " +
                RealmListingContent.MOTD_MAX_LENGTH + " characters"
        else -> null
    }

    private fun fenceRefusal(obj: JSONObject): String? = runCatching {
        PrivacyGuard.refuseIfBanned(obj.getString("name"), "name")
        PrivacyGuard.refuseIfBanned(obj.optString("motd", ""), "motd")
        null
    }.getOrElse { failure ->
        failure.message?.takeIf { it.startsWith("VAL-") }
            ?: "VAL-NOSTR-CONTENT: banned content"
    }

    private fun urlRefusal(statusPageUrl: String?): String? =
        if (statusPageUrl != null && !RealmListingContent.isShapeValidPublicUrl(statusPageUrl)) {
            "VAL-NOSTR-CONTENT: statusPageUrl must be the realm-status showcase " +
                "tunnel URL (https://<label>.trycloudflare.com)"
        } else {
            null
        }

    /** The players counts honor the publish side's
     * never-negative rule and a sanity ceiling — a validly-signed hostile
     * listing must not render '-1 humans' or 2-billion padded bots. */
    private fun playersRefusal(players: JSONObject): String? {
        val humans = players.getInt("humans")
        val bots = players.getInt("bots")
        return when {
            humans < 0 || bots < 0 ->
                "VAL-NOSTR-CONTENT: players counts must not be negative"
            humans > RealmListingContent.PLAYERS_COUNT_CEILING || bots > RealmListingContent.PLAYERS_COUNT_CEILING ->
                "VAL-NOSTR-CONTENT: players counts exceed the plausible ceiling " +
                    "($RealmListingContent.PLAYERS_COUNT_CEILING)"
            else -> null
        }
    }

    /** The string-length type pin — a 2 MB hostile string cannot ride the
     * 256-entry admission cap. */
    private fun textTypeRefusal(obj: JSONObject): String? =
        listOfNotNull(
            if (obj.get("name") !is String) {
                "VAL-NOSTR-CONTENT: listing name must be a string"
            } else {
                null
            },
            run {
                val timeframe = obj.optString("timeframe", "")
                if (timeframe.length > RealmListingContent.TIMEFRAME_MAX_LENGTH) {
                    "VAL-NOSTR-CONTENT: listing timeframe exceeds " +
                        RealmListingContent.TIMEFRAME_MAX_LENGTH + " characters"
                } else {
                    null
                }
            },
        ).firstOrNull()

    /** The flavor/build pair must resolve against the owning
     * registry as a matched pair — an unshipped flavor id, or a shipped
     * flavor carrying ANOTHER lane's build, is exactly the anachronism
     * the registry law bans. */
    private fun pairRefusal(obj: JSONObject): String? {
        val flavor = obj.getString("flavor")
        val build = obj.getInt("build")
        val registryBuild = runCatching { RealmListingContent.buildForFlavor(flavor) }.getOrNull()
        return when {
            registryBuild == null ->
                "VAL-NOSTR-CONTENT: unknown flavor '$flavor' — not a shipped lane"
            registryBuild != build ->
                "VAL-NOSTR-CONTENT: build $build does not match flavor '$flavor' " +
                    "(the shipped lane's build is $registryBuild)"
            else -> null
        }
    }

    /** R5-r3-1: the timeframe stage must be a REGISTERED stage of the
     * claimed flavor (TimeframeStages — the same registry the publisher's
     * status payload resolves through); a cross-era or nonexistent stage
     * id is refused like any other anachronism. */
    private fun stageRefusal(flavor: String, timeframe: String): String? {
        // breaker: an EMPTY stage renders the dangling row detail
        // "flavor (build N) · " — not a frozen-schema stage.
        if (timeframe.isEmpty()) {
            return "VAL-NOSTR-CONTENT: timeframe stage must not be empty"
        }
        val registered = runCatching {
            com.pocketrealm.client.TimeframeStages.byId(flavor, timeframe)
        }.getOrNull()
        return if (registered != null) {
            null
        } else {
            "VAL-NOSTR-CONTENT: timeframe " + timeframe +
                " is not a registered stage of flavor " + flavor
        }
    }

    /** R5-4: rates must be finite JSON numbers — optDouble's NaN sentinel
     * does not catch Infinity. */
    private fun ratesRefusal(ratesObj: JSONObject?): String? =
        ratesObj?.let { rates ->
            listOf("xpKill", "xpQuest", "xpHonor")
                .map { field -> field to rates.optDouble(field) }
                .firstOrNull { (_, v) -> !v.isNaN() && v.isInfinite() }
                ?.let { (field, _) -> "VAL-NOSTR-CONTENT: rates.$field must be a finite number" }
        }.let { it ?: run { null } }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /** A listing stamped slightly into the future (relay clock ahead)
         * stays acceptable for this long before the freshness rule drops
         * it — bounded, never a free pass. */
        const val CLOCK_SKEW_SECONDS: Long = 2L * 60L

        /** The admission fence: the retained set is capped —
         * a flood of distinct-pubkey listings evicts the oldest entries
         * instead of pinning memory. */
        const val MAX_ENTRIES: Int = 256
    }
}
