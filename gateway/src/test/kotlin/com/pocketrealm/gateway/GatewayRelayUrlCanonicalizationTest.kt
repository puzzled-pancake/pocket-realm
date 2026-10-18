package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W6-N4: the READY relay URL canonization pin — the producer-side half
 * of the FQDN-root-dot finding (see [GatewayMain.canonicalRelayUrl]).
 * iroh hands us N0's relay URLs with a trailing root dot (the N2 spike's
 * captured READY line is the receipt); the descriptor ladder fail-closes
 * on it. The gateway canonizes; the descriptor boundary stays STRICT.
 */
class GatewayRelayUrlCanonicalizationTest {

    /** The exact shape N2's captured READY carried (out/completion/w6/
     * n2/relay-host.log: relay=https://usw1-1.relay.n0.iroh.link./) —
     * the root dot goes, the rest survives byte-for-byte. */
    @Test
    fun `the n0 fqdn root-dot form canonizes to the descriptor-legal url`() {
        assertEquals(
            "https://usw1-1.relay.n0.iroh.link/",
            GatewayMain.canonicalRelayUrl("https://usw1-1.relay.n0.iroh.link./"),
        )
    }

    @Test
    fun `already-canonical urls are untouched`() {
        val url = "https://usw1-1.relay.n0.iroh.link/"
        assertEquals(url, GatewayMain.canonicalRelayUrl(url))
        val bare = "https://relay.example.com"
        assertEquals(bare, GatewayMain.canonicalRelayUrl(bare))
    }

    @Test
    fun `a root dot with a port and without a path canonizes`() {
        assertEquals(
            "https://relay.example.com:443/",
            GatewayMain.canonicalRelayUrl("https://relay.example.com.:443/"),
        )
        assertEquals(
            "https://relay.example.com",
            GatewayMain.canonicalRelayUrl("https://relay.example.com."),
        )
    }

    @Test
    fun `interior dots and a single root dot only`() {
        assertEquals(
            "https://a.b.c.example.internal/",
            GatewayMain.canonicalRelayUrl("https://a.b.c.example.internal./"),
        )
        // Exactly ONE trailing dot goes; a doubled dot is not silently
        // "fixed" (a malformed input stays malformed for the validator).
        assertEquals(
            "https://relay.example.com./",
            GatewayMain.canonicalRelayUrl("https://relay.example.com../"),
        )
    }

    @Test
    fun `null stays null and a non-https body is not canonized`() {
        assertEquals(null, GatewayMain.canonicalRelayUrl(null))
        // Canonization is not validation: anything that is not the
        // https:// authority shape passes through unchanged and remains
        // the DESCRIPTOR's problem.
        assertEquals("not-a-url.", GatewayMain.canonicalRelayUrl("not-a-url."))
    }

    /** The pairing pin: the canonical N0 URL CONSTRUCTS a descriptor,
     * and the dot form is STILL refused (canonization never widened the
     * public trust boundary). */
    @Test
    fun `the descriptor accepts the canonical url and still refuses the dot form`() {
        val canonical = IrohJoinDescriptor(
            nodeHex = "ab".repeat(32),
            relayUrl = "https://usw1-1.relay.n0.iroh.link/",
        )
        assertEquals(
            "https://usw1-1.relay.n0.iroh.link/",
            canonical.relayUrl,
        )
        val refused = runCatching {
            IrohJoinDescriptor(
                nodeHex = "ab".repeat(32),
                relayUrl = "https://usw1-1.relay.n0.iroh.link./",
            )
        }
        assertTrue(
            refused.isFailure,
            "the dot form must keep failing the descriptor ladder",
        )
        assertTrue(
            refused.exceptionOrNull()?.message?.contains("VAL-IROH-JOIN") == true,
            "the refusal must carry the VAL code",
        )
    }


    /** R5-4/R2-N4-r3 (round-6 minors): out-of-design shapes pass through
     * UNTOUCHED (a bracketed IPv6-literal authority is never mangled at
     * the first colon) and the port-segment root dot IS stripped, so the
     * doc comment's 'exactly one FQDN root dot from the authority' holds
     * for every segment. */
    @Test
    fun `a bracketed ipv6 authority passes through untouched`() {
        val input = "https://[2001:db8::1]:443/"
        assertEquals(
            input, GatewayMain.canonicalRelayUrl(input),
            "a bracketed IPv6-literal authority must round-trip byte-identical",
        )
        val noPort = "https://[::1]/"
        assertEquals(noPort, GatewayMain.canonicalRelayUrl(noPort))
    }

    @Test
    fun `the port-segment root dot is stripped too`() {
        assertEquals(
            "https://relay.example.com:443/",
            GatewayMain.canonicalRelayUrl("https://relay.example.com.:443./"),
        )
    }
}
