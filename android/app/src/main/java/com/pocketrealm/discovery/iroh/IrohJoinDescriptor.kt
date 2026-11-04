package com.pocketrealm.discovery.iroh

import com.pocketrealm.discovery.nostr.NostrJson
import org.json.JSONObject

/**
 * the iroh join descriptor — the frozen listing schema's `join`
 * field, no longer null. This is the ONLY thing a joiner dials by:
 * the host's iroh NODE ID and its HOME RELAY URL. Never an IP address,
 * never a hostname pair, never a port — the owner-adopted constraint-2
 * narrowing (NOSTR-IROH-BUILD-PLAN.md ruling 3) in its final form.
 *
 * Wire shape (pinned key order, byte-stable golden vectors):
 *   {"type":"iroh","node":"<64 lowercase hex>","relay":"<https URL>"}
 *
 * The relay URL is the iroh endpoint's home-relay address (an https URL
 * whose host is a HOSTNAME — N0's fleet is hostname-addressed). Fail-closed
 * privacy rule: an IP-literal relay host is REFUSED at construction — a
 * world-readable listing must never carry an address someone can dial
 * outside the iroh transport. (The self-hosted-relay escape hatch stays
 * open: any hostname-addressed relay works, including a private one.)
 */
data class IrohJoinDescriptor(
    val nodeHex: String,
    val relayUrl: String,
) {
    init {
        // Fail-closed at CONSTRUCTION (the trust boundary is the type, not
        // one parse path): a descriptor that exists, validates. The same
        // ladder fromListingJson runs — an IP relay host or a malformed
        // node can never ride a copy() or a hand-built instance into a
        // signed listing.
        if (nodeHex.length != NODE_HEX_LENGTH || nodeHex.any { !isLowerHex(it) }) {
            throw descriptorRefusal(
                "join node must be $NODE_HEX_LENGTH lowercase hex characters",
            )
        }
        validateRelayUrl(relayUrl)?.let { throw descriptorRefusal(it) }
    }

    val type: String get() = TYPE_IROH

    /** The pinned-order JSON object, exactly as it rides the listing. */
    fun toJsonString(): String = buildString {
        append('{')
        append("\"type\":")
        append(NostrJson.quote(TYPE_IROH))
        append(",\"node\":")
        append(NostrJson.quote(nodeHex))
        append(",\"relay\":")
        append(NostrJson.quote(relayUrl))
        append('}')
    }

    /** A render-ready dial summary — node id, relay host — never an IP
     * (there is none to render; the constructor already refused one). */
    override fun toString(): String = "iroh node ${nodeHex.take(NODE_DISPLAY_CHARS)}… " +
        "via ${relayHost()}"

    /** The relay URL's hostname (display only). */
    fun relayHost(): String = relayUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore(':')

    companion object {

        const val TYPE_IROH: String = "iroh"

        /** A 32-byte iroh node id, lowercase hex. */
        const val NODE_HEX_LENGTH: Int = 64

        /** The node-id prefix the UI/log copy renders. */
        const val NODE_DISPLAY_CHARS: Int = 12

        /** The frozen descriptor's exactly-three keys. */
        private val ALLOWED_KEYS = setOf("type", "node", "relay")

        /** The relay port's digit budget and a DNS label's length cap. */
        private const val MAX_RELAY_PORT_DIGITS = 5

        /** The u16 port ceiling (65535) — a decimal port above it is not a
         * port at all. */
        private const val MAX_RELAY_PORT_VALUE = 65535L

        /** The port floor: 0 is the socket wildcard, never a relay
         * port a client could dial. */
        private const val MIN_RELAY_PORT_VALUE = 1L

        /** The relay URL's total cap: real relay URLs are
         * far below it; a hostile many-label hostname must refuse at the
         * descriptor fence, not die on the child process's command-line
         * cap. */
        const val MAX_RELAY_URL_LENGTH = 2048
        private const val MAX_LABEL_LENGTH = 63

        /**
         * Parses and validates the `join` value of a listing content JSON.
         * Returns the descriptor, or a VAL-IROH-JOIN refusal reason:
         *  - the value must be an object of EXACTLY the three keys
         *    (type/node/relay) — unknown keys are a hostile extension;
         *  - type must be "iroh" (the only transport v1 speaks);
         *  - node must be exactly 64 lowercase hex characters;
         *  - relay must be an https URL with a HOSTNAME host — an IP
         *    literal (any spelling the [org.json] layer could hide: bare,
         *    bracketed v6, dotless decimal, hex) is refused, fail-closed.
         */
        fun fromListingJson(value: Any?): IrohJoinDescriptor {
            val refusal = listingRefusal(value)
            if (refusal != null) throw descriptorRefusal(refusal)
            val obj = value as JSONObject
            // The constructor's init validates the node/relay shapes (the
            // same refusal either way).
            return IrohJoinDescriptor(
                nodeHex = obj.optString("node", ""),
                relayUrl = obj.optString("relay", ""),
            )
        }

        /** The admission ladder as DATA (the single throw lives in
         * [fromListingJson]): object shape, exact key set, transport
         * type. */
        private fun listingRefusal(value: Any?): String? = when {
            value !is JSONObject -> "join must be an object"
            value.keys().asSequence().toSet() != setOf("type", "node", "relay") ->
                "join carries keys ${value.keys().asSequence().toSet()}, " +
                    "expected exactly $ALLOWED_KEYS"

            value.optString("type", "") != TYPE_IROH ->
                "join type '${value.optString("type", "")}' is not '$TYPE_IROH'"

            else -> null
        }

        /**
         * The relay URL gate: scheme https, a hostname host (labels of
         * letters/digits/hyphen, dot-separated), optional :port, empty or
         * "/" path. An IP-literal host in ANY spelling is refused (the
         * privacy NEVER-list; a hostname-shaped token that parses as an
         * address is caught by the same digits-and-dots rule).
         */
        fun validateRelayUrl(relay: String): String? = when {
            // the TOTAL length is bounded BEFORE the leg
            // walk — the per-label caps admit a hostile hostname of many
            // ≤63-char labels (~32k chars) that would ride the gateway
            // child's argv and die on Windows' CreateProcess cap (an
            // uncaught IOException, no VAL refusal). Real relay URLs are
            // <200 chars; the cap sits two orders above them.
            relay.length > MAX_RELAY_URL_LENGTH ->
                "join relay exceeds the $MAX_RELAY_URL_LENGTH-character bound"
            !relay.startsWith("https://") -> "join relay must be an https URL"
            else -> relayPartsRefusal(relay.removePrefix("https://"))
        }

        /** The https:// body's legs: an empty-or-root path only, then the
         * host:port pair's port and host ladders. */
        private fun relayPartsRefusal(rest: String): String? {
            val hostPort = rest.substringBefore('/')
            val path = rest.removePrefix(hostPort)
            if (path.isNotEmpty() && path != "/") {
                return "join relay must not carry a path"
            }
            // Hostname labels never carry a colon — the last colon is the
            // port separator (or absent).
            val colon = hostPort.lastIndexOf(':')
            val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
            val port = if (colon >= 0) hostPort.substring(colon + 1) else ""
            return portRefusal(port) ?: hostRefusal(host)
        }

        /** The port leg: present-but-malformed (charset/length) or
         * outside the 1..65535 port range refuses (code-red round-4
         * R2-2: the lower bound is CHECKED — port 0 is a real socket
         * wildcard, never a relay port, and rode the old upper-bound-only
         * check into a signed listing); absent (":/" or no colon — the
         * scheme's default port) passes. ASCII DIGITS ONLY (code-red
         * R2-4: Char.isDigit() is true for U+0663-class digits, so a
         * Unicode-digit port rode a "validated" descriptor the iroh URL
         * layer would later reject far from this fence); leading zeros
         * parse URL-decimal-faithfully (":0663" IS port 663), and the
         * numeric VALUE is range-checked (code-red R7-N3-1: a 5-digit
         * 99999 rode the length-only leg into a signed listing and only
         * died at dial time). */
        private fun portRefusal(port: String): String? {
            if (port.isEmpty()) return null
            val malformed = port.length > MAX_RELAY_PORT_DIGITS ||
                port.any { it !in '0'..'9' } ||
                port.toLong() !in MIN_RELAY_PORT_VALUE..MAX_RELAY_PORT_VALUE
            return if (malformed) "join relay port is malformed" else null
        }

        /** The host leg: a hostname of usable labels — never empty, never
         * an address literal in any spelling (the NEVER-list: all-digits
         * AND hex tokens, code-red R2-3 — "0x7f.0x00.0x00.0x01" carries
         * letters and sailed the no-letters leg), dot-separated with EVERY
         * label in RFC shape and a letters-bearing tail (code-red
         * R7-N3-3/R2-2-3: the last label is held to the same charset — an
         * all-digits or underscore/query tail like "evil.12345678",
         * "example.my_relay", "relay.example.com?x=1" refuses). */
        // contract: one exit per refusal (the admission ladder IS the
        // contract — the same shape ListingStore.parseContentChecked pins).
        @Suppress("ReturnCount")
        private fun hostRefusal(host: String): String? {
            if (host.isEmpty()) return "join relay host is empty"
            if (host.none { it.isLetter() }) {
                return "join relay host must be a hostname — an address " +
                    "literal is never published"
            }
            if (isHexAddressHost(host)) {
                return "join relay host must be a hostname — an address " +
                    "literal is never published"
            }
            val labels = host.split('.')
            if (labels.size < 2 || labels.any(::labelIsUnusable)) {
                return "join relay host is not a hostname"
            }
            if (labels.any { label ->
                    label.any { !it.isLetterOrDigit() && it != '-' }
                }
            ) {
                return "join relay host is not a hostname"
            }
            // TLD shape: the last label carries a letter — an all-digits
            // tail is an inet_aton-shaped fragment, never a hostname.
            if (labels.last().none { it.isLetter() }) {
                return "join relay host must be a hostname — an address " +
                    "literal is never published"
            }
            return null
        }

        /**
         * The hex-address spelling (the N1 PrivacyGuard isHexAddressToken
         * rule, restated for the relay-host leg): the token carries a 0x
         * prefix anywhere and EVERY dot-chunk is hex ("0x7f.0.0.1" — the
         * prefix need only appear once; the uppercase 0X form included). */
        private fun isHexAddressHost(host: String): Boolean {
            val lowered = host.lowercase()
            if (!lowered.contains("0x")) return false
            return lowered.split('.').all { chunk ->
                chunk.isNotEmpty() &&
                    chunk.removePrefix("0x").all { it in '0'..'9' || it in 'a'..'f' }
            }
        }

        /** One DNS label: 1..63 chars, no leading/trailing hyphen. */
        private fun labelIsUnusable(label: String): Boolean =
            label.isEmpty() || label.length > MAX_LABEL_LENGTH ||
                label.startsWith("-") || label.endsWith("-")

        private fun descriptorRefusal(reason: String): Nothing =
            throw IllegalArgumentException("$reason")

        private fun isLowerHex(c: Char): Boolean =
            (c in '0'..'9') || (c in 'a'..'f')
    }
}
