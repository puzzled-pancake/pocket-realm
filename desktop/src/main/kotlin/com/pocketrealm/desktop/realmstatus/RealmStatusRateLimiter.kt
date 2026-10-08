package com.pocketrealm.desktop.realmstatus

import java.util.concurrent.ConcurrentHashMap

/**
 * Realm-status browser (design realm-status-browser.md §6.2): the page
 * limiter with the PUBLIC-aware keying.
 *
 * The limiter MUST NOT key on the socket peer in PUBLIC mode — every
 * tunnel visitor arrives with peer 127.0.0.1, so a per-IP key would be one
 * shared bucket a single visitor can exhaust for everyone. PUBLIC mode keys:
 *  - a VALIDATED `CF-Connecting-IP` header → its own per-visitor bucket.
 *    Valid = IPv4 global-unicast PLUS the CGNAT 100.64/10 range (carrier-NAT
 *    mobile ISPs legitimately present CGNAT client IPs at Cloudflare's edge;
 *    a local forger could forge a global-unicast IP anyway) PLUS IPv6 global
 *    unicast `2000::/3` (Cloudflare sets the header for v6 visitors;
 *    rejecting v6 would collapse all v6 visitors into the shared bucket —
 *    the round-3 fixture defect T11d pins);
 *  - a PRESENT-BUT-REJECTED header (loopback/RFC1918/link-local/multicast/
 *    unspecified — forgery the edge could never deliver) → the global bucket;
 *  - no header from the host's own loopback view → the RESERVED HOST BUDGET
 *    a visitor flood can never exhaust;
 *  - no header from any other peer (FRIENDS mode keys here per real IP) →
 *    the peer's own per-IP bucket.
 *
 * The bucket map is BOUNDED: max entries with expired-then-oldest eviction,
 * so forged-header floods cannot grow memory without limit.
 */
@Suppress("MagicNumber", "ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
// the address-grammar validators are explicit bit/byte arithmetic on purpose
// (the fixture's round-3 v6-shift defect is exactly what explicit arithmetic
// + the T11d pin defend against); the early returns are the reject table
class RealmStatusRateLimiter(
    private val perVisitorPerMinute: Int,
    private val globalPerMinute: Int,
    private val hostPerMinute: Int,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    /** Which bucket a request draws from (the server resolves PUBLIC vs
     * FRIENDS keying into these). */
    sealed class Key {
        data class Visitor(val ip: String) : Key()
        data class Peer(val ip: String) : Key()
        object Global : Key()
        object Host : Key()
    }

    enum class Decision { ALLOWED, LIMITED }

    data class Verdict(val decision: Decision, val retryAfterSeconds: Int)

    private class Bucket {
        var windowStartMs = 0L
        var count = 0
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val evictionLock = Any()

    /** The public-mode key resolution for one request (§6.2). [peerIp] is
     * the socket peer; [connectingIpHeader] the raw header value or null. */
    fun publicKey(peerIp: String, connectingIpHeader: String?): Key = when {
        connectingIpHeader == null && peerIp == HOST_LOOPBACK -> Key.Host
        connectingIpHeader == null -> Key.Global
        isValidPublicVisitorIp(connectingIpHeader) -> Key.Visitor(connectingIpHeader)
        else -> Key.Global
    }

    /** FRIENDS mode keys per-IP normally (peers are real tunnel/LAN
     * addresses); the host's own loopback keeps its reserved budget. */
    fun friendsKey(peerIp: String): Key =
        if (peerIp == HOST_LOOPBACK) Key.Host else Key.Peer(peerIp)

    /** One rate decision against the bucket [key] names. */
    fun check(key: Key): Verdict {
        val limit = when (key) {
            is Key.Visitor -> perVisitorPerMinute
            is Key.Peer -> perVisitorPerMinute
            Key.Global -> globalPerMinute
            Key.Host -> hostPerMinute
        }
        val name = key.bucketName()
        val now = clockMs()
        if (buckets.size >= MAX_BUCKETS) evictBounded(now)
        val bucket = buckets.computeIfAbsent(name) { Bucket() }
        synchronized(bucket) {
            if (now - bucket.windowStartMs >= WINDOW_MS) {
                bucket.windowStartMs = now
                bucket.count = 0
            }
            if (bucket.count < limit) {
                bucket.count += 1
                return Verdict(Decision.ALLOWED, retryAfterSeconds = 0)
            }
            val elapsed = now - bucket.windowStartMs
            return Verdict(
                Decision.LIMITED,
                retryAfterSeconds = ((WINDOW_MS - elapsed) / 1_000L).toInt().coerceAtLeast(1),
            )
        }
    }

    /** The bounded-map eviction (§6.2): expired buckets first, then the
     * oldest — a forged-header flood cannot grow memory without limit. */
    private fun evictBounded(now: Long) {
        synchronized(evictionLock) {
            if (buckets.size < MAX_BUCKETS) return
            val expired = buckets.entries.filter { now - it.value.windowStartMs >= WINDOW_MS }
            expired.forEach { buckets.remove(it.key, it.value) }
            while (buckets.size >= MAX_BUCKETS) {
                buckets.entries.minByOrNull { it.value.windowStartMs }?.let { oldest ->
                    buckets.remove(oldest.key, oldest.value)
                } ?: return
            }
        }
    }

    /** Live bucket count (the bounded-map assertion's probe). */
    fun trackedBuckets(): Int = buckets.size

    private fun Key.bucketName(): String = when (this) {
        is Key.Visitor -> "visitor:$ip"
        is Key.Peer -> "peer:$ip"
        Key.Global -> "global"
        Key.Host -> "host"
    }

    companion object {
        /** The limiter window (the "per minute" contract's granularity). */
        const val WINDOW_MS = 60_000L

        /** Bound on tracked buckets — forged-header floods cannot grow the
         * map past this (§6.2's bounded map). */
        const val MAX_BUCKETS = 4_096

        const val HOST_LOOPBACK = "127.0.0.1"

        /**
         * The CF-Connecting-IP validator (§6.2): IPv4 global-unicast plus
         * CGNAT 100.64/10, or IPv6 global-unicast 2000::/3. Rejected
         * (impossible from the edge): loopback, RFC1918 private, IPv4/v6
         * link-local, multicast, unspecified.
         */
        fun isValidPublicVisitorIp(raw: String): Boolean {
            val value = raw.trim()
            if (value.contains(':')) return isValidGlobalUnicastIpv6(value)
            return isValidGlobalUnicastIpv4(value)
        }

        /** Canonical IPv4 parse (the shared [com.pocketrealm.supervisor
         * .RealmEndpoint.parseCanonicalIpv4] grammar's local twin: digits
         * and dots, four non-zero-padded octets in 0..255). */
        internal fun parseIpv4(raw: String): IntArray? {
            if (raw.length !in 7..15 || raw.any { it != '.' && it !in '0'..'9' }) return null
            val pieces = raw.split('.')
            if (pieces.size != 4) return null
            val octets = IntArray(4)
            for (index in pieces.indices) {
                val piece = pieces[index]
                if (piece.isEmpty() || piece.length > 3 || (piece.length > 1 && piece[0] == '0')) {
                    return null
                }
                val value = piece.toIntOrNull() ?: return null
                if (value !in 0..255) return null
                octets[index] = value
            }
            return octets
        }

        private fun isValidGlobalUnicastIpv4(raw: String): Boolean {
            val octets = parseIpv4(raw) ?: return false
            val first = octets[0]
            val second = octets[1]
            return when {
                // Loopback, unspecified, and the 0/8 block: never an edge client.
                first == 0 || first == 127 -> false
                // RFC1918 private ranges: the edge never delivers them.
                first == 10 -> false
                first == 172 && second in 16..31 -> false
                first == 192 && second == 168 -> false
                // IPv4 link-local (APIPA): not a routable client address.
                first == 169 && second == 254 -> false
                // CGNAT 100.64/10: a legitimate mobile/carrier client at the edge.
                first == 100 && second in 64..127 -> true
                // Multicast + reserved classes D/E.
                first in 224..255 -> false
                else -> true
            }
        }

        /** IPv6 global unicast 2000::/3 — both the FULL uncompressed
         * 8-group form and the ::-compressed forms a header can carry.
         * Zone IDs and IPv4-mapped tails are rejected (the edge reports
         * plain global unicast). */
        internal fun isValidGlobalUnicastIpv6(raw: String): Boolean {
            val value = raw.trim().lowercase()
            if (value.isEmpty() || value.contains('%') || value.contains('.')) return false
            val groups: List<Int> = run {
                val doubleColon = value.indexOf("::")
                if (doubleColon < 0) {
                    // The full form: exactly eight groups, nothing dropped.
                    val parsed = expandGroups(value) ?: return false
                    if (parsed.size != 8) return false
                    parsed
                } else {
                    if (value.indexOf("::", doubleColon + 1) >= 0) return false
                    val head = expandGroups(value.substring(0, doubleColon)) ?: return false
                    val tail = expandGroups(value.substring(doubleColon + 2)) ?: return false
                    // "::" must actually compress at least one group.
                    if (head.size + tail.size >= 8) return false
                    head + List(8 - head.size - tail.size) { 0 } + tail
                }
            }
            if (groups.size != 8) return false
            // 2000::/3: the first three bits 001 → first group in 0x2000..0x3FFF.
            return groups.first() in 0x2000..0x3FFF
        }

        private fun expandGroups(raw: String): List<Int>? {
            if (raw.isEmpty()) return emptyList()
            val groups = raw.split(':')
            val values = ArrayList<Int>(groups.size)
            for (group in groups) {
                if (group.isEmpty() || group.length > 4) return null
                val value = group.toIntOrNull(16) ?: return null
                values.add(value)
            }
            return values
        }
    }
}
