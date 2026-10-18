package com.pocketrealm.discovery.nostr

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * W6-N1 (docs/NOSTR-IROH-BUILD-PLAN.md, frozen listing schema): the
 * kind-30388 realm listing event — NIP-01 serialization, SHA-256 id, and
 * BIP-340 Schnorr signing, plus the NIP-09 deletion twin.
 *
 * Frozen contract (owner-approved 2026-10-08; join flipped to carried by
 * stage N3 per the same document):
 *  - kind 30388 (NIP-33 parameterized replaceable), `d` tag
 *    "pocketrealm-v1", `t` tag "pocketrealm";
 *  - tags: ["expiration", now+600] (a HINT only — the client-side 10-minute
 *    freshness rule is the source of truth), ["flavor", ...], ["timeframe", ...];
 *  - content JSON exactly per the frozen schema: name, motd, flavor, build,
 *    timeframe, rates, players{humans,bots} (bots labeled, never padded),
 *    join (the iroh join descriptor — node id + home relay URL ONLY, never
 *    an IP; null while the host's gateway is down), statusPageUrl (optional);
 *  - NEVER an IP address, geo, or client-download URL; the only
 *    hostnames a listing may carry are the join descriptor's home-relay
 *    URL (a validated hostname-only https URL, IrohJoinDescriptor) and
 *    the optional status-page link.
 *
 * Determinism note: the id serialization and the content string are built
 * with the pinned-order manual serializer in [NostrJson] — NOT through
 * JSONObject (whose key iteration order is HashMap-unstable). Byte-stable
 * golden vectors are pinned by the shared suite on both platforms.
 */
object RealmListingContract {
    const val KIND: Int = 30388
    const val D_TAG: String = "pocketrealm-v1"
    const val T_TAG: String = "pocketrealm"

    /** NIP-09 deletion event kind. */
    const val DELETION_KIND: Int = 5

    /** NIP-42 auth event kind. */
    const val AUTH_KIND: Int = 22242

    /** The client-side freshness window (the adopted NIP-40 correction:
     * a listing is fresh only if created_at is within 10 minutes). */
    const val FRESHNESS_MS: Long = 10L * 60L * 1_000L

    /** The same window in SECONDS — the store's freshness arithmetic runs
     * in the created_at domain (code-red R2-1: a Long milliseconds
     * multiply wraps for a crafted far-future created_at). */
    const val FRESHNESS_SECONDS: Long = 10L * 60L

    /** The same window in minutes (the refusal copy's unit). */
    const val FRESHNESS_MINUTES: Long = 10L

    /** The expiration tag hint (600 s), never load-bearing. */
    const val EXPIRATION_HINT_SECONDS: Long = 600L

    /** The heartbeat cadence while the realm is up and public. */
    const val HEARTBEAT_SECONDS: Long = 300L
}

/**
 * One realm listing's content — the frozen schema, exactly. Every text
 * field passes the privacy guard ([sanitize] refuses IP-shaped strings,
 * URL schemes, and over-long input at CONSTRUCTION time, fail-closed).
 */
data class RealmListingContent(
    val name: String,
    val motd: String,
    val flavor: String,
    val build: Int,
    val timeframeStage: String,
    val rates: ListingRates?,
    val humans: Int,
    val bots: Int,
    /**
     * The iroh join descriptor (W6-N3: no longer null by contract). Null
     * means the host's gateway is not running — the honest "not joinable
     * over the internet right now". A non-null descriptor is validated at
     * construction ([IrohJoinDescriptor]: node id + home relay URL, never
     * an IP) and serialized in a pinned key order.
     */
    val join: IrohJoinDescriptor? = null,
    /** The realm-status showcase tunnel URL — the ONLY allowed URL, and
     * only in the pinned trycloudflare shape. */
    val statusPageUrl: String? = null,
) {

    /** The frozen-schema content JSON, pinned key order. */
    fun toJsonString(): String = buildString {
        append("{\"name\":")
        append(NostrJson.quote(name))
        append(",\"motd\":")
        append(NostrJson.quote(motd))
        append(",\"flavor\":")
        append(NostrJson.quote(flavor))
        append(",\"build\":")
        append(build)
        append(",\"timeframe\":")
        append(NostrJson.quote(timeframeStage))
        append(",\"rates\":")
        if (rates == null) {
            append("null")
        } else {
            append(rates.toJsonString())
        }
        append(",\"players\":{\"humans\":")
        append(humans)
        append(",\"bots\":")
        append(bots)
        append("},\"join\":")
        append(join?.toJsonString() ?: "null")
        append(",\"statusPageUrl\":")
        if (statusPageUrl == null) {
            append("null")
        } else {
            append(NostrJson.quote(statusPageUrl))
        }
        append("}")
    }

    /** The rates block. Nulls publish as JSON nulls (the honest n/a). */
    data class ListingRates(val xpKill: Double?, val xpQuest: Double?, val xpHonor: Double?) {
        fun toJsonString(): String = buildString {
            append("{\"xpKill\":")
            append(NostrJson.numberOrNull(xpKill))
            append(",\"xpQuest\":")
            append(NostrJson.numberOrNull(xpQuest))
            append(",\"xpHonor\":")
            append(NostrJson.numberOrNull(xpHonor))
            append("}")
        }
    }

    companion object {

        const val NAME_MAX_LENGTH = 60
        const val MOTD_MAX_LENGTH = 280
        const val TIMEFRAME_MAX_LENGTH = 64
        const val PLAYERS_COUNT_CEILING = 1_000_000

        /** R4-1: the per-entry TAG bound (room for d/t/expiration/flavor/
         * timeframe + a NIP-13 nonce, many times over). */
        const val MAX_LISTING_TAGS = 64

        /** R5-2: a single tag VALUE may not exceed this length. */
        const val MAX_TAG_VALUE_LENGTH = 128

        /** R5-1(b): the per-entry SERIALIZED budget — the admission fence
         * bounds entries AND bytes. */
        const val MAX_SERIALIZED_CHARS = 32_768

        /**
         * The privacy guard (constraint 2 narrowed + constraint 3, adopted
         * ruling 3 and the frozen schema's NEVER list): fail-closed at
         * construction. A name/motd carrying an IPv4 literal, a URL
         * scheme, or a hostname-shaped token is refused with VAL-NOSTR-
         * CONTENT — a listing is a name and a description, not an
         * infrastructure pointer. Control characters are stripped first;
         * over-length input is REFUSED (fail-closed — the settings/payload
         * write paths clamp before the fence ever sees it).
         */
        fun sanitize(
            name: String,
            motd: String,
            statusPageUrl: String? = null,
        ): Pair<String, String> {
            val cleanName = clean(name, NAME_MAX_LENGTH, "name")
            val cleanMotd = clean(motd, MOTD_MAX_LENGTH, "motd")
            for (candidate in listOf(cleanName to "name", cleanMotd to "motd")) {
                PrivacyGuard.refuseIfBanned(candidate.first, candidate.second)
            }
            if (statusPageUrl != null && !isShapeValidPublicUrl(statusPageUrl)) {
                throw IllegalArgumentException(
                    "VAL-NOSTR-CONTENT: statusPageUrl must be the realm-status showcase " +
                        "tunnel URL (https://<label>.trycloudflare.com)",
                )
            }
            return cleanName to cleanMotd
        }

        /** The SAME trycloudflare share-URL shape the realm-status payload
         * gates its public URL with (kept literal here: the shared module
         * cannot reach the desktop twin). */
        val TRYCLOUDFLARE_SHAPE: Regex = Regex("^https://[a-z0-9-]+\\.trycloudflare\\.com$")

        fun isShapeValidPublicUrl(raw: String?): Boolean =
            raw != null && TRYCLOUDFLARE_SHAPE.matches(raw)

        /** Builds the content from the realm-status payload JSON (the
         * heartbeat's single source of truth — the exact payload the
         * status page serves maps onto the frozen schema). Returns null
         * when the payload cannot honestly fill the schema (players split
         * not live — bots must never be padded), so the caller skips the
         * beat. */
        @Suppress("ReturnCount")
        // contract (bots never padded): one exit per refusal
        fun fromStatusPayload(payload: JSONObject): RealmListingContent? {
            val realm = payload.optJSONObject("realm") ?: return null
            val players = payload.optJSONObject("players") ?: return null
            val rates = payload.optJSONObject("rates")
            val public = payload.optJSONObject("public")
            // The frozen schema publishes real numbers only (R5's honesty
            // rule: bots labeled, never padded). A degraded/failed split
            // publishes NO listing this beat.
            if (players.optString("source") != "native") return null
            val humans = players.optInt("humansOnline", -1)
            val bots = players.optInt("botsOnline", -1)
            if (humans < 0 || bots < 0) return null
            val publicUrl = public?.optString("url", "")?.takeIf { it.isNotEmpty() }
                ?.takeIf { isShapeValidPublicUrl(it) }
            // R1-2: a missing/unknown flavor key NEVER defaults to vanilla
            // (a fail-open wrong-flavor leak into a world-readable listing)
            // — the beat is skipped fail-closed, exactly like
            // buildForFlavor's unknown-flavor refusal.
            val flavor = realm.optString("flavor", "")
            val build = runCatching { buildForFlavor(flavor) }.getOrNull() ?: return null
            val (cleanName, cleanMotd) = sanitize(
                name = realm.optString("name", "my realm"),
                motd = realm.optString("description", ""),
                statusPageUrl = publicUrl,
            )
            return RealmListingContent(
                name = cleanName,
                motd = cleanMotd,
                flavor = flavor,
                build = build,
                timeframeStage = realm.optString("timeframeStageId", ""),
                rates = ListingRates(
                    xpKill = rates?.optDouble("xpKill")?.takeUnless { it.isNaN() },
                    xpQuest = rates?.optDouble("xpQuest")?.takeUnless { it.isNaN() },
                    xpHonor = rates?.optDouble("xpHonor")?.takeUnless { it.isNaN() },
                ),
                humans = humans,
                bots = bots,
                // W6-N3: the payload has no dial descriptor — the PUBLISHER
                // attaches the live gateway's join descriptor (node + home
                // relay) after this builder returns, so the listing only
                // ever carries a descriptor of a gateway that is UP.
                join = null,
                statusPageUrl = publicUrl,
            )
        }

        /** flavor id → client build, resolved through the OWNING registry
         * (client/ClientFlavor.kt's ClientFlavors) — the build numbers live
         * exactly once in the tree, and a listing can never drift from the
         * shipped lane identities. */
        fun buildForFlavor(flavor: String): Int = when (flavor.lowercase()) {
            ClientFlavors.VANILLA.flavorId -> ClientFlavors.VANILLA.build
            ClientFlavors.TBC.flavorId -> ClientFlavors.TBC.build
            ClientFlavors.WOTLK.flavorId -> ClientFlavors.WOTLK.build
            else -> throw IllegalArgumentException(
                "VAL-NOSTR-CONTENT: unknown flavor '$flavor' for the listing build field",
            )
        }

        private fun clean(raw: String, maxLength: Int, field: String): String {
            val stripped = raw.filterNot { it.isISOControl() }
            return if (stripped.length <= maxLength) {
                stripped
            } else {
                stripped.take(maxLength).also {
                    throw IllegalArgumentException(
                        "VAL-NOSTR-CONTENT: listing $field exceeds $maxLength characters",
                    )
                }
            }
        }
    }
}

/**
 * The banned-content fence (the frozen schema's NEVER list): IPv4
 * literals, URL schemes, and hostname-shaped tokens never enter a
 * listing's free-text fields. Hostnames are banned because a listing is
 * world-readable — a "join me at myhost.example.com" MOTD is an
 * infrastructure pointer in exactly the sense constraint 2 narrowed.
 */
object PrivacyGuard {

    /** Canonical dotted-quad IPv4 (each octet 0-255 shape). The trailing
     * boundary admits ONE sentence-final period ("...at 10.0.0.1." —
     * R3-1/R6-3-1) but never a dot followed by a digit (that is a longer
     * dotted numeric, judged by the loose/shorthand legs instead). */
    private val IPV4: Regex = Regex(
        "(^|[^0-9.])((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}" +
            "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(\\.(?![0-9.])|($|[^0-9.]))",
    )

    /** Any 4-part dotted numeric — catches the LEADING-ZERO spellings
     * ("192.168.000.001") that common stacks accept as connectable
     * addresses while the canonical regex's strict octets refuse them
     * (R5-1; fail-closed). Same sentence-final-period boundary as the
     * canonical rule (R3-1/R6-3-1). */
    private val IPV4_LOOSE: Regex = Regex(
        "(^|[^0-9.])([0-9]{1,3}\\.){3}[0-9]{1,3}(\\.(?![0-9.])|($|[^0-9.]))",
    )

    /** R2B-1: inet_aton SHORTHAND — 1-3 part dotted numerics ("10.1",
     * "192.168.1") are connectable addresses on common stacks (the JVM's
     * own InetAddress.getByName resolves them; 2-part forms encode any
     * IPv4). Any whitespace-delimited token of ONLY digits and dots
     * carrying at least one dot is address-shaped; fail-closed (a bare
     * "3.3.5" version token is refused too — spell it "v3.3.5"). */
    fun isIpV4ShorthandToken(token: String): Boolean =
        // R2-1: NO caps on part width or dot count — inet_aton and common
        // resolvers accept 4-digit leading-zero octets ("0177.0.0.1",
        // "1.1.1.0100") and 2/3-part shorthand; any digits+dots token with
        // a dot is address-shaped, fail-closed.
        token.contains('.') &&
            token.length > 1 &&
            token.all { it.isDigit() || it == '.' } &&
            token.count { it == '.' } >= 1 &&
            token.split('.').all { part -> part.isNotEmpty() }

    /** R3-3: the DOTLESS decimal spelling — inet_aton's integer form
     * ("2130706433" = 127.0.0.1, "3232235521" = 192.168.0.1; both resolve
     * on the JVM without DNS). Any 8-10 digit token is address-shaped;
     * fail-closed per the fence's stated posture (a bare long number in
     * prose is refused — spell it out or prefix it). */
    fun isDotlessDecimalToken(token: String): Boolean =
        token.length in MIN_DOTLESS_DIGITS..MAX_DOTLESS_DIGITS && token.all(Char::isDigit)

    /** R3-r4-1: a HEX address spelling — "0x7f000001" (dotless) and
     * "0x7f.0x0.0.1" (dotted hex) are connectable on common resolvers.
     * Any 0x-prefixed token of hex characters is address-shaped;
     * fail-closed. */
    /** R3-r4-1: hex NOTATION requires the "0x" prefix on at least one
     * chunk ("0x7f.0x0.0.1", "0x7f000001") — a bare pure-hex word like
     * "deadbeef" or "E0" stays legal prose; fail-closed. */
    private val HEX_CHUNK = Regex("^(0x)?[0-9a-f]+$")

    fun isHexAddressToken(token: String): Boolean =
        token.lowercase().contains("0x") && token.lowercase().split('.')
            .filter { it.isNotEmpty() }
            .all { chunk -> HEX_CHUNK.matchEntire(chunk) != null }

    /** Any connectable address spelling (v6/v4 shorthand/hex/dotless). */
    private fun isIpLikeToken(token: String): Boolean =
        isIpV6ShapedToken(token) || isIpV4ShorthandToken(token) ||
            isHexAddressToken(token) || isDotlessDecimalToken(token)


    /** The hex/colon/dot character set an IPv6 spelling draws from. */
    private const val IPV6_TOKEN_CHARS = "0123456789abcdefABCDEF:."


    /** The inet_aton integer spellings of any IPv4 are 8-10 digits
     * (the reviewer's bound: 2130706433 = 127.0.0.1). */
    private const val MIN_DOTLESS_DIGITS = 8
    private const val MAX_DOTLESS_DIGITS = 10

    /** TLD shape bounds for the IDN leg. */
    private const val MIN_TLD_LENGTH = 2
    private const val MAX_TLD_LENGTH = 24

    /** R3-5: an IPv6 shape check for one whitespace-delimited token —
     * "2001:db8::1", "[2001:db8::1]:8085", "::ffff:192.168.1.10". A
     * token carrying 2+ colons and only address characters is
     * address-shaped; prose colons never survive the token split.
     * Fail-closed: an exotic spelling is refused, not passed. */
    fun isIpV6ShapedToken(token: String): Boolean {
        // "[2001:db8::1]:8085" — brackets go first. R2B-1: zone-ids are
        // part of the address — strip BOTH the raw ("%eth0") and the
        // URL-encoded ("%25eth0") forms. A trailing all-digit group is a
        // PORT only when what remains still carries 2+ colons ("fe80::1"
        // keeps its last group; "[2001:db8::1]:8085" loses ":8085").
        var core = token
            .filterNot { it == '[' || it == ']' }
            .replace("%25", "%")
            .substringBefore('%')
        val tail = core.substringAfterLast(':')
        if (tail.isNotEmpty() && tail.all(Char::isDigit)) {
            val candidate = core.substringBeforeLast(':')
            if (candidate.count { it == ':' } >= 2) core = candidate
        }
        if (core.count { it == ':' } < 2) return false
        return core.all { it in IPV6_TOKEN_CHARS } && core.any { it.isDigit() }
    }

    /** R3-4: an IDN hostname spelling — the ASCII HOSTNAME regex only
     * sees [a-z0-9-], so "水.com" sailed while its punycode equivalent
     * ("xn--1rw.com", IDN.toASCII-proven the same hostname) was refused.
     * Fail-closed: any token whose LAST dot-separated label is letters-
     * only (any script) of TLD shape, carried by a non-numeric token, is
     * hostname-shaped. */
    fun isIdnHostnameShapedToken(token: String): Boolean {
        val dotted = token.contains('.')
        val spaced = token.any { it.isWhitespace() }
        if (!dotted || spaced) return false
        val labels = token.split('.').filter { it.isNotEmpty() }
        // The last label must be a letters-only TLD shape (any script);
        // fail-closed on exotic unicode rather than passing it (R3-4).
        // The rest must not be a pure number (an IP/decimal leg of the
        // fence already judged those) and must not be a route/path shape.
        val tldOk = labels.size >= 2 &&
            labels.last().length in MIN_TLD_LENGTH..MAX_TLD_LENGTH &&
            labels.last().all(Char::isLetter)
        val head = labels.dropLast(1).joinToString(".")
        return tldOk && head.any { it.isLetterOrDigit() } && !head.contains('/')
    }

    /** Any URL scheme: a free-text URL is either a download pointer or a
     * server address — both banned. */
    private val URL_SCHEME: Regex = Regex("(?i)\\b(https?|ws|wss|ftp|irc)://")

    /** Hostname shape: word-hyphen label + a dot + a label + a known-ish
     * TLD-ish suffix (2-24 letters). Keeps "chill vibes only" clean while
     * catching "join.myrealm.com". */
    private val HOSTNAME: Regex = Regex(
        "(?i)(^|[^a-z0-9-])" +
            "([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,24}($|[^a-z0-9-])",
    )

    fun refuseIfBanned(text: String, field: String) {
        // The NEVER list in review order; the first match names itself.
        val canonical = sequenceOf(
            IPV4 to "an IP address — never published",
            URL_SCHEME to "a URL — addresses and download links are never published",
            HOSTNAME to "a hostname — never published",
        ).firstOrNull { it.first.containsMatchIn(text) }?.second
        // R3-5/R5-1: the IPv6 and leading-zero spellings ride the token
        // walk — their shapes defeat the single canonical regexes. The
        // walk runs ALWAYS, not only when a canonical rule matched.
        val banned = canonical ?: text.split(Regex("\\s+")).firstOrNull { token ->
            isIpV6ShapedToken(token) ||
                isIpV4ShorthandToken(token) ||
                isHexAddressToken(token) ||
                isDotlessDecimalToken(token) ||
                isIdnHostnameShapedToken(token) ||
                IPV4_LOOSE.containsMatchIn(token)
        }?.let { token ->
            if (isIpLikeToken(token)) {
                "an IP address — never published"
            } else {
                "a hostname — never published"
            }
        }
        if (banned == null) return
        throw IllegalArgumentException(
            "VAL-NOSTR-CONTENT: listing $field carries $banned",
        )
    }
}


/**
 * The unsigned body of an event about to be signed (the [NostrEvent.create]
 * input bundle).
 */
data class PendingEvent(
    val createdAtSeconds: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

/**
 * One Nostr event (any kind this module produces or consumes).
 * Construction goes through [create]/[fromJson]; the id is always the
 * SHA-256 of the NIP-01 serialization of the parsed/built fields.
 */
data class NostrEvent(
    val idHex: String,
    val pubkeyHex: String,
    val createdAtSeconds: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sigHex: String,
) {

    /** The wire form: one compact JSON object, pinned key order. */
    fun toWireJson(): String = buildString {
        append("{\"id\":")
        append(NostrJson.quote(idHex))
        append(",\"pubkey\":")
        append(NostrJson.quote(pubkeyHex))
        append(",\"created_at\":")
        append(createdAtSeconds)
        append(",\"kind\":")
        append(kind)
        append(",\"tags\":")
        append(NostrJson.tagsToJson(tags))
        append(",\"content\":")
        append(NostrJson.quote(content))
        append(",\"sig\":")
        append(NostrJson.quote(sigHex))
        append("}")
    }

    /** The NIP-01 serialization this event's id commits to. */
    fun serializedForId(): String =
        serializedForId(pubkeyHex, createdAtSeconds, kind, tags, content)

    /** The MSG frame `["EVENT", <event>]` for the relay write lane. */
    fun toEventFrame(): String = "[\"EVENT\"," + toWireJson() + "]"

    companion object {

        /** NIP-01 §serialization: [0,<pubkey>,<created_at>,<kind>,<tags>,
         * <content>] — compact, minimal escaping, no whitespace. */
        fun serializedForId(
            pubkeyHex: String,
            createdAtSeconds: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): String = buildString {
            append("[0,")
            append(NostrJson.quote(pubkeyHex.lowercase()))
            append(',')
            append(createdAtSeconds)
            append(',')
            append(kind)
            append(',')
            append(NostrJson.tagsToJson(tags))
            append(',')
            append(NostrJson.quote(content))
            append(']')
        }

        /** SHA-256 over the UTF-8 serialization, lowercase hex. */
        fun computeId(serialized: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(serialized.toByteArray(Charsets.UTF_8))
            return Hex.encode(digest)
        }

        /** Signs and completes a pending event: id = sha256(serialized),
         * sig = BIP-340(id). */
        fun create(
            key: NostrPrivateKey,
            pending: PendingEvent,
            auxRand32: ByteArray? = null,
        ): NostrEvent {
            val serialized = serializedForId(
                key.xOnlyPublicKeyHex, pending.createdAtSeconds,
                pending.kind, pending.tags, pending.content,
            )
            val id = computeId(serialized)
            val sig = key.signSchnorr(Hex.decode(id, "VAL-NOSTR-EVENT"), auxRand32)
            return NostrEvent(
                idHex = id,
                pubkeyHex = key.xOnlyPublicKeyHex,
                createdAtSeconds = pending.createdAtSeconds,
                kind = pending.kind,
                tags = pending.tags.map { it.toList() },
                content = pending.content,
                sigHex = Hex.encode(sig),
            )
        }

        /** Parses a relay-frame event object. Structural only — signature
         * verification is the caller's job (ListingStore verifies). */
        fun fromJson(text: String): NostrEvent {
            val obj = try {
                JSONObject(text)
            } catch (_: org.json.JSONException) {
                null
            }
            val id = obj?.optString("id", "").orEmpty()
            val pubkey = obj?.optString("pubkey", "").orEmpty()
            val sig = obj?.optString("sig", "").orEmpty()
            val createdAt = obj?.optLong("created_at", -1L) ?: -1L
            val kind = obj?.optInt("kind", -1) ?: -1
            val content = obj?.optString("content", "").orEmpty()
            val tagsJson = requireNotNull(obj) {
                "VAL-NOSTR-EVENT: not a JSON object"
            }.optJSONArray("tags").let { tags ->
                requireNotNull(tags) { "VAL-NOSTR-EVENT: missing tags" }
            }
            val problem = when {
                !HEX_64.matches(id) || !HEX_64.matches(pubkey) || !HEX_128.matches(sig) ->
                    "id/pubkey/sig shapes invalid"

                createdAt < 0 -> "created_at missing"
                else -> null
            }
            require(problem == null) { "VAL-NOSTR-EVENT: $problem" }
            val tags = (0 until tagsJson.length()).map { i ->
                val tag = requireNotNull(tagsJson.optJSONArray(i)) {
                    "VAL-NOSTR-EVENT: tag $i not an array"
                }
                (0 until tag.length()).map { j -> tag.optString(j, "") }
            }
            return NostrEvent(id, pubkey, createdAt, kind, tags, content, sig)
        }

        /** Extracts the event from one relay text frame: the subscription
         * shape `["EVENT", <sub id>, <event>]` AND the write shape
         * `["EVENT", <event>]` (what we publish). Returns an empty list
         * for frames this parser ignores. */
        @Suppress("ReturnCount")
        // contract: publish shape, subscription shape, non-event
        fun fromRelayFrame(text: String): List<Pair<String, NostrEvent>> {
            // A relay speaks arbitrary bytes on an open socket: an
            // unparseable frame is a NON-EVENT by contract — ignored
            // (never a crash); the parse detail is relay noise, dropped
            // deliberately.
            val arr = try {
                JSONArray(text)
            } catch (_: org.json.JSONException) {
                return emptyList()
            }
            if (arr.optString(0) != "EVENT") return emptyList()
            when (arr.length()) {
                WRITE_FRAME_ARITY -> {
                    val eventJson = arr.optJSONObject(WRITE_EVENT_INDEX) ?: return emptyList()
                    return listOf("" to fromJson(eventJson.toString()))
                }

                SUBSCRIPTION_FRAME_ARITY -> {
                    val subId = arr.optString(SUBSCRIPTION_ID_INDEX)
                    val eventJson = arr.optJSONObject(SUBSCRIPTION_EVENT_INDEX) ?: return emptyList()
                    return listOf(subId to fromJson(eventJson.toString()))
                }

                else -> return emptyList()
            }
        }

        // NIP-01 frame arities/indexes: we publish ["EVENT", <event>];
        // relays answer ["EVENT", <sub id>, <event>].
        private const val WRITE_FRAME_ARITY = 2
        private const val WRITE_EVENT_INDEX = 1
        private const val SUBSCRIPTION_FRAME_ARITY = 3
        private const val SUBSCRIPTION_ID_INDEX = 1
        private const val SUBSCRIPTION_EVENT_INDEX = 2

        private val HEX_64: Regex = Regex("^[0-9a-f]{64}$")
        private val HEX_128: Regex = Regex("^[0-9a-f]{128}$")
    }
}

/**
 * The kind-30388 listing event surface: build (sign), deletion twin, and
 * the self-verification every browser applies before trusting a listing.
 */
object RealmListingEvent {

    /** Builds and signs one listing event per the frozen schema. The
     * privacy guard runs HERE (the signing boundary) — a content object
     * assembled by [RealmListingContent.copy] or any other path that
     * skipped the constructor-time sanitize cannot smuggle a banned
     * payload into a signed event. */
    fun build(
        content: RealmListingContent,
        key: NostrPrivateKey,
        nowSeconds: Long,
        expirationHintSeconds: Long = RealmListingContract.EXPIRATION_HINT_SECONDS,
        auxRand32: ByteArray? = null,
    ): NostrEvent {
        RealmListingContent.sanitize(content.name, content.motd, content.statusPageUrl)
        return NostrEvent.create(
            key = key,
            pending = PendingEvent(
                createdAtSeconds = nowSeconds,
                kind = RealmListingContract.KIND,
                tags = tagsFor(content, nowSeconds, expirationHintSeconds),
                content = content.toJsonString(),
            ),
            auxRand32 = auxRand32,
        )
    }

    /** The frozen schema's tag set: d, t, expiration (hint), flavor,
     * timeframe. Nothing else. */
    fun tagsFor(
        content: RealmListingContent,
        nowSeconds: Long,
        expirationHintSeconds: Long,
    ): List<List<String>> = listOf(
        listOf("d", RealmListingContract.D_TAG),
        listOf("t", RealmListingContract.T_TAG),
        listOf("expiration", (nowSeconds + expirationHintSeconds).toString()),
        listOf("flavor", content.flavor),
        listOf("timeframe", content.timeframeStage),
    )

    /** NIP-09 deletion for one listing (graceful shutdown's retract): kind
     * 5 with the e-tag on the listing id and the a-tag on the replaceable
     * coordinate, empty content. */
    fun buildDeletion(
        listing: NostrEvent,
        key: NostrPrivateKey,
        nowSeconds: Long,
        auxRand32: ByteArray? = null,
    ): NostrEvent = NostrEvent.create(
        key = key,
        pending = PendingEvent(
            createdAtSeconds = nowSeconds,
            kind = RealmListingContract.DELETION_KIND,
            tags = listOf(
                listOf("e", listing.idHex),
                listOf(
                    "a",
                    "${RealmListingContract.KIND}:${listing.pubkeyHex}:${RealmListingContract.D_TAG}",
                ),
                listOf("k", RealmListingContract.KIND.toString()),
            ),
            content = "",
        ),
        auxRand32 = auxRand32,
    )

    /**
     * The browser-side verification (the adopted ruling: WE verify, never
     * the relay): contract shape (kind, d tag, t tag), id recomputation,
     * and BIP-340 signature check against the embedded pubkey. [reason]
     * carries the actionable VAL code on refusal.
     */
    @Suppress("ReturnCount")
    // trust boundary: one exit per refusal, each with its VAL code
    fun verifyListing(event: NostrEvent): Verified {
        if (event.kind != RealmListingContract.KIND) {
            return Verified(false, "VAL-NOSTR-EVENT: kind ${event.kind} is not a realm listing")
        }
        val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.getOrNull(1)
        if (d != RealmListingContract.D_TAG) {
            return Verified(false, "VAL-NOSTR-EVENT: d tag missing or wrong for a realm listing")
        }
        if (event.tags.none { it.size >= 2 && it[0] == "t" && it[1] == RealmListingContract.T_TAG }) {
            return Verified(false, "VAL-NOSTR-EVENT: t tag missing or wrong for a realm listing")
        }
        val recomputed = NostrEvent.computeId(event.serializedForId())
        if (recomputed != event.idHex) {
            return Verified(false, "VAL-NOSTR-EVENT: id does not match the serialization")
        }
        val ok = runCatching {
            Secp256k1Verify.schnorr(
                signature = Hex.decode(event.sigHex, "VAL-NOSTR-SIG"),
                message = Hex.decode(event.idHex, "VAL-NOSTR-SIG"),
                xOnlyPub = Hex.decode(event.pubkeyHex, "VAL-NOSTR-SIG"),
            )
        }.getOrDefault(false)
        return if (ok) {
            Verified(true, null)
        } else {
            Verified(false, "VAL-NOSTR-SIG: signature check failed — listing refused")
        }
    }

    data class Verified(val ok: Boolean, val reason: String?)
}

/** Verify seam (kept one call away so the desktop/Android twins share it). */
internal object Secp256k1Verify {
    /** Test instrumentation (R7-r2-2): counts the Schnorr verifies so the
     * duplicate fast path can pin "a duplicate delivery adds zero". */
    internal var schnorrVerifyCalls: Int = 0

    fun schnorr(signature: ByteArray, message: ByteArray, xOnlyPub: ByteArray): Boolean {
        schnorrVerifyCalls += 1
        return fr.acinq.secp256k1.Secp256k1.verifySchnorr(signature, message, xOnlyPub)
    }
}
