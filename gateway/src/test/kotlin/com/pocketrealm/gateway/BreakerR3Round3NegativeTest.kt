package com.pocketrealm.gateway

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.iroh.AuthStreamFilter
import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.RealmListRewrite
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

/**
 * W6-N3 code-red ROUND 3 (adversarial breaker R2) — the lanes rounds 1-2
 * did not cover:
 *
 *  - the BELT phase's per-byte drain COST under a hostile host's bulk
 *    non-0x10 flood (the joiner runs whatever the host streams — the
 *    drain's complexity is the finding; the measured milliseconds are the
 *    evidence);
 *  - the EOF-tail leg with a REALM-LIST-SHAPED partial frame (must die
 *    loudly, never relay a truncated list that still carries the host's
 *    address bytes);
 *  - a belt-split realm-list frame (header feed, body feed, EOF mid-body);
 *  - the framed phase AFTER a good frame (a follow-on foreign opcode);
 *  - the args gate's malformed-target / missing-relay lanes.
 */
class BreakerR3Round3NegativeTest {

    private val hostAddress = "198.51.100.66:8085"

    // ------------------------------------------------------------ fixtures

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun challenge(): ByteArray = byteArrayOf(0x00, 0x00) + ByteArray(32) +
        byteArrayOf(0x01, 0x07) + byteArrayOf(32) + ByteArray(32) +
        ByteArray(32) + ByteArray(16) + byteArrayOf(0x00)

    private fun wotlkProof(): ByteArray = byteArrayOf(0x01, 0x00) + ByteArray(20) +
        ByteArray(4) + ByteArray(4) + byteArrayOf(0, 0) // 32 bytes

    private fun cstr(s: String): List<Byte> =
        s.toByteArray(Charsets.US_ASCII).toList() + 0.toByte()

    private fun modernFrame(count: Int, address: String): ByteArray {
        val payload = ArrayList<Byte>()
        repeat(4) { payload.add(0) }
        payload.add(count.toByte()); payload.add(0)
        repeat(count) {
            payload.add(0); payload.add(0); payload.add(0x24)
            payload.addAll(cstr("Pocket Realm"))
            payload.addAll(cstr(address))
            payload.addAll(byteArrayOf(0, 0, 0, 0x3f, 3, 1, 0x2c).toList())
            payload.addAll(byteArrayOf(3, 3, 5, 0x34, 0x30).toList())
        }
        payload.addAll(byteArrayOf(0x10, 0x00).toList())
        return framed(payload.toByteArray())
    }

    private fun framed(payload: ByteArray): ByteArray = byteArrayOf(
        RealmListRewrite.CMD_REALM_LIST.toByte(),
        (payload.size and 0xff).toByte(),
        ((payload.size shr 8) and 0xff).toByte(),
    ) + payload

    // ================================================== BELT drain cost ===

    /**
     * PROBE (documents the cost lane): the belt passes every non-0x10 byte
     * by re-copying the remaining buffer per byte. A hostile host that
     * flips the filter to BELT (one foreign leading byte) and then streams
     * N junk bytes costs the joiner O(N^2) byte copies INSIDE ONE feed()
     * call — the join lane freezes with no rejection at all. The two runs
     * below scale 8x in bytes; a quadratic drain scales ~64x in time.
     */
    @Test
    fun beltDrainCostScalesQuadraticallyWithTheHostFlood() {
        val t16 = beltDrainMillis(16 * 1024)
        val t128 = beltDrainMillis(128 * 1024)
        println("BELT drain: 16KiB=${t16}ms 128KiB=${t128}ms ratio=${t128 / t16.coerceAtLeast(1)}")
        // remedy threshold: the bulk-drain fix makes the BELT scan
        // O(N) — 128 KiB of hostile junk drains in well under the 100 ms
        // bound (the quadratic drain measured ~577 ms at this size).
        assertTrue(t128 < 100, "128KiB BELT drain must stay under 100ms, got ${t128}ms")
        // The probe documents; the correctness invariant below is the pin:
        // even after the flood, a real realm list is still rewritten.
        val filter = beltFilter()
        filter.feed(ByteArray(64 * 1024) { 0x41 })
        val emitted = filter.feed(modernFrame(1, hostAddress))
        val text = String(emitted.reduce { a, b -> a + b }, Charsets.US_ASCII)
        assertTrue(
            text.contains("127.0.0.1:8085") && !text.contains(hostAddress),
            "after the flood the realm list must still be rewritten (got $text)",
        )
    }

    /** A filter parked in the BELT phase: challenge consumed, then a FULL
     * 32-byte non-proof flips PROOF -> BELT with an empty remainder. */
    private fun beltFilter(): AuthStreamFilter {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challenge())
        filter.feed(byteArrayOf(0x02.toByte()) + ByteArray(31)) // proof mismatch -> BELT
        return filter
    }

    private fun beltDrainMillis(bytes: Int): Long {
        val filter = beltFilter()
        val junk = ByteArray(bytes) { 0x41 } // no 0x10 anywhere
        return measureTimeMillis {
            val emitted = filter.feed(junk)
            assertEquals(bytes, emitted.sumOf { it.size })
        }
    }

    // ==================================================== EOF-tail legs ===

    /**
     * A realm-list-SHAPED partial at EOF (0x10 + declared size + a TRUNCATED
     * body that still carries the address bytes) must die loudly — never
     * drain verbatim (a dropped/relayed realm list at EOF would ship the
     * host's raw address).
     */
    @Test
    fun eofTailWithRealmListShapedPartialDiesLoudly() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challenge() + wotlkProof()) // -> FRAMED
        val full = modernFrame(1, hostAddress)
        val partial = full.copyOfRange(0, 3 + 40) // header + body incl. the address bytes
        val exception = assertThrows<RealmListRewrite.RealmListRewriteException> {
            filter.feed(partial, eof = true)
        }
        println("EOF-tail partial refusal: ${exception.message}")
    }

    /** A belt-mode realm-list frame whose body never completes by EOF is
     * the same refusal (belt holds; the EOF leg validates-and-rewrites or
     * throws). */
    @Test
    fun beltSplitFrameWithEofMidBodyDiesLoudly() {
        val filter = beltFilter()
        val full = modernFrame(1, hostAddress)
        filter.feed(full.copyOfRange(0, 3)) // header only; the belt holds
        // The tail carries the address bytes but not the whole frame.
        assertThrows<RealmListRewrite.RealmListRewriteException> {
            filter.feed(full.copyOfRange(3, full.size - 4), eof = true)
        }
    }

    /** A belt-split frame that DOES complete across two feeds rewrites. */
    @Test
    fun beltSplitFrameCompletingAcrossFeedsRewrites() {
        val filter = beltFilter()
        val full = modernFrame(1, hostAddress)
        filter.feed(full.copyOfRange(0, 3))
        val emitted = filter.feed(full.copyOfRange(3, full.size))
        val text = String(emitted.reduce { a, b -> a + b }, Charsets.US_ASCII)
        assertTrue(text.contains("127.0.0.1:8085"), "must rewrite: $text")
        assertTrue(!text.contains(hostAddress), "must not leak: $text")
    }

    // ============================================ framed after a good one ==

    /** Post-auth stream: one GOOD realm list, then a foreign opcode frame.
     * The strict framed phase refuses the follow-on loudly (a misframed
     * stream — never a passthrough). */
    @Test
    fun framedPhaseRefusesAForeignOpcodeAfterAGoodFrame() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challenge() + wotlkProof())
        val good = filter.feed(modernFrame(1, hostAddress))
        val goodText = String(good.reduce { a, b -> a + b }, Charsets.US_ASCII)
        assertTrue(goodText.contains("127.0.0.1:8085") && !goodText.contains(hostAddress))
        val bogus = byteArrayOf(0x11, 0x04, 0x00, 1, 2, 3, 4)
        assertThrows<RealmListRewrite.RealmListRewriteException> {
            filter.feed(bogus)
        }
    }

    /** Declared size smaller than the bytes actually sent: the frame
     * completes from the first bytes and the REMAINDER becomes the next
     * header — junk remainder is a loud foreign-opcode refusal, never a
     * passthrough. (The framed "budget" leg can never fire — the header
     * bound already refuses oversized declarations — so every misframing
     * must land on one of the loud legs.) */
    @Test
    fun underDeclaredFrameJunkRemainderRefusesLoudly() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challenge() + wotlkProof())
        val full = modernFrame(1, hostAddress)
        // Shrink the declared size by 10: the last 10 bytes become the
        // next "header" — 0x2c is not 0x10.
        val shrunk = full.copyOfRange(0, full.size - 10)
        val out = ArrayList<ByteArray>()
        assertThrows<RealmListRewrite.RealmListRewriteException> {
            out.addAll(filter.feed(shrunk + ByteArray(10) { 0x2c }))
        }
    }

    // ======================================================== args gates ===

    /**
     * PROBE (documents the args hole): a relay-only join with NO --relay
     * parses clean — and endpointOptions then falls through to
     * RelayMode.defaultMode(), so the "relay-only dials the listing's home
     * relay and NOTHING else" posture silently becomes the DEFAULT N0
     * relay lane. The app never builds this argv (the descriptor's
     * constructor forbids a blank relay), but the process contract itself
     * fail-OPENS instead of refusing: GATEWAY-BAD-ARGS is the documented
     * posture for mismatched input.
     */
    @Test
    fun relayOnlyJoinWithNoRelayParsesCleanInsteadOfRefusing() {
        // Code-red R3-4 remedy: relay-only pins the dial to the LISTING's
        // home relay — a missing --relay refuses loudly (never a silent
        // downgrade to iroh's default relay set).
        try {
            Args.parse(
                arrayOf(
                    GatewayContract.ARG_MODE, GatewayContract.MODE_JOIN,
                    GatewayContract.ARG_SECRET_HEX, "ab".repeat(32),
                    GatewayContract.ARG_NODE_HEX, "cd".repeat(32),
                    GatewayContract.ARG_FLAVOR, "tbc",
                    GatewayContract.ARG_RELAY_ONLY,
                ),
            )
            kotlin.test.fail("a relay-only join with no --relay must refuse")
        } catch (expected: BadArgsExit) {
            kotlin.test.assertTrue(
                expected.message!!.contains("--relay"),
                "the refusal must name the missing home relay: ${expected.message}",
            )
        }
    }

    /** A malformed loopback target port explodes as a raw
     * IllegalArgumentException from InetSocketAddress — NOT the
     * GATEWAY-BAD-ARGS class the contract pins (still loud + before any
     * side effect, so minor: the wrong error class, not a wrong outcome). */
    @Test
    fun malformedTargetPortThrowsTheWrongExceptionClass() {
        val raw = arrayOf(
            GatewayContract.ARG_MODE, GatewayContract.MODE_HOST,
            GatewayContract.ARG_SECRET_HEX, "ab".repeat(32),
            GatewayContract.ARG_REALM_TARGET, "127.0.0.1:99999",
        )
        val thrown = runCatching { Args.parse(raw) }.exceptionOrNull()
        // R5-r2-4 (round-6 minor): assert on the concrete refusal path's
        // CONTENT (the out-of-range port) — never a compiler-tautological
        // instanceof.
        assertTrue(
            thrown is IllegalArgumentException &&
                thrown.message!!.contains("port out of range"),
            "got: $thrown",
        )
        assertTrue(thrown !is BadArgsExit, "not the args-gate class")
    }
}
