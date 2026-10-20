package com.pocketrealm.desktop.realmstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PUBLIC limiter keying's header-family pins — the exact shapes
 * the spec's T11/T11b/T11c/T11d rows assert at the HTTP layer:
 *
 *  - a validated CF-Connecting-IP (global-unicast IPv4, CGNAT 100.64/10,
 *    IPv6 2000::/3 in compressed AND full form) gets its OWN per-visitor
 *    bucket;
 *  - forged-private and malformed headers collapse into the GLOBAL bucket;
 *  - the host's no-header loopback view draws the reserved host budget a
 *    visitor flood can never exhaust;
 *  - the bucket map is bounded.
 */
class RealmStatusRateLimiterTest {

    private fun limiter(perVisitor: Int = 100) = RealmStatusRateLimiter(
        perVisitorPerMinute = perVisitor,
        globalPerMinute = 100,
        hostPerMinute = 100,
        clockMs = { 0L },
    )

    private fun keyOf(header: String?) = limiter().publicKey("127.0.0.1", header)

    @Test
    fun `validated global-unicast ipv4 gets its own per-visitor bucket`() {
        assertTrue(keyOf("203.0.113.7") is RealmStatusRateLimiter.Key.Visitor)
        assertTrue(keyOf("198.51.100.9") is RealmStatusRateLimiter.Key.Visitor)
        // Different visitors never share a bucket.
        assertFalse(
            (keyOf("203.0.113.7") as RealmStatusRateLimiter.Key.Visitor).ip ==
                (keyOf("198.51.100.9") as RealmStatusRateLimiter.Key.Visitor).ip,
        )
    }

    @Test
    fun `forged private and malformed headers collapse into the global bucket`() {
        // T11c: forged RFC1918 — the edge could never deliver it.
        assertTrue(keyOf("10.0.0.1") is RealmStatusRateLimiter.Key.Global)
        assertTrue(keyOf("192.168.1.1") is RealmStatusRateLimiter.Key.Global)
        assertTrue(keyOf("172.16.0.1") is RealmStatusRateLimiter.Key.Global)
        // T11b: malformed (not an IP at all).
        assertTrue(keyOf("not-an-ip") is RealmStatusRateLimiter.Key.Global)
        // Loopback / link-local / multicast / unspecified: also rejected.
        assertTrue(keyOf("127.0.0.1") is RealmStatusRateLimiter.Key.Global)
        assertTrue(keyOf("169.254.1.1") is RealmStatusRateLimiter.Key.Global)
        assertTrue(keyOf("224.0.0.1") is RealmStatusRateLimiter.Key.Global)
        assertTrue(keyOf("0.0.0.0") is RealmStatusRateLimiter.Key.Global)
    }

    @Test
    fun `cgnat and ipv6 visitors get their own buckets - not the global one`() {
        // T11d: CGNAT 100.64/10 (carrier-NAT mobile clients are real).
        assertTrue(keyOf("100.64.1.2") is RealmStatusRateLimiter.Key.Visitor)
        // T11d: IPv6 global unicast in the compressed AND full forms.
        assertTrue(keyOf("2606:4700:4700::1111") is RealmStatusRateLimiter.Key.Visitor)
        assertTrue(keyOf("2606:4700:4700:0:0:0:0:1111") is RealmStatusRateLimiter.Key.Visitor)
        // ...and they are distinct buckets from each other and from IPv4.
        val cgnat = keyOf("100.64.1.2") as RealmStatusRateLimiter.Key.Visitor
        val v6 = keyOf("2606:4700:4700::1111") as RealmStatusRateLimiter.Key.Visitor
        assertTrue(cgnat.ip != v6.ip)
    }

    @Test
    fun `the no-header loopback view draws the reserved host budget`() {
        assertTrue(keyOf(null) is RealmStatusRateLimiter.Key.Host)
        // FRIENDS mode keys real peers per-IP; the host's loopback stays reserved.
        val limiter = limiter()
        assertTrue(limiter.friendsKey("127.0.0.1") is RealmStatusRateLimiter.Key.Host)
        assertTrue(limiter.friendsKey("192.168.1.50") is RealmStatusRateLimiter.Key.Peer)
    }

    @Test
    fun `a visitor flood trips the per-visitor limit and never the host budget`() {
        val limiter = RealmStatusRateLimiter(
            perVisitorPerMinute = 3,
            globalPerMinute = 100,
            hostPerMinute = 100,
            clockMs = { 0L },
        )
        val visitor = (1..5).map {
            limiter.check(RealmStatusRateLimiter.Key.Visitor("203.0.113.7")).decision
        }
        assertEquals(
            "the 4th request inside the window trips (T11's shape)",
            listOf(
                RealmStatusRateLimiter.Decision.ALLOWED,
                RealmStatusRateLimiter.Decision.ALLOWED,
                RealmStatusRateLimiter.Decision.ALLOWED,
                RealmStatusRateLimiter.Decision.LIMITED,
                RealmStatusRateLimiter.Decision.LIMITED,
            ),
            visitor,
        )
        assertEquals(
            "the host budget is untouched by the visitor flood",
            RealmStatusRateLimiter.Decision.ALLOWED,
            limiter.check(RealmStatusRateLimiter.Key.Host).decision,
        )
        assertEquals(
            "a second visitor is unaffected by the first's exhaustion",
            RealmStatusRateLimiter.Decision.ALLOWED,
            limiter.check(RealmStatusRateLimiter.Key.Visitor("198.51.100.9")).decision,
        )
    }

    @Test
    fun `the window refills after a minute`() {
        var now = 0L
        val limiter = RealmStatusRateLimiter(
            perVisitorPerMinute = 1,
            globalPerMinute = 100,
            hostPerMinute = 100,
            clockMs = { now },
        )
        val key = RealmStatusRateLimiter.Key.Visitor("203.0.113.7")
        assertEquals(RealmStatusRateLimiter.Decision.ALLOWED, limiter.check(key).decision)
        assertEquals(RealmStatusRateLimiter.Decision.LIMITED, limiter.check(key).decision)
        now = 60_001L
        assertEquals(
            "the next window starts fresh",
            RealmStatusRateLimiter.Decision.ALLOWED,
            limiter.check(key).decision,
        )
    }

    @Test
    fun `the bucket map is bounded under a forged-header flood`() {
        val limiter = RealmStatusRateLimiter(
            perVisitorPerMinute = 100,
            globalPerMinute = 100,
            hostPerMinute = 100,
            clockMs = { 0L },
        )
        // A flood well past the bound: eviction must keep the map at cap.
        for (index in 0 until 4_200) {
            val second = index / 256
            val third = index % 256
            limiter.check(RealmStatusRateLimiter.Key.Visitor("203.$second.$third.7"))
        }
        assertTrue(
            "the map cannot grow without limit (§6.2's bounded map)",
            limiter.trackedBuckets() <= RealmStatusRateLimiter.MAX_BUCKETS,
        )
    }
}
