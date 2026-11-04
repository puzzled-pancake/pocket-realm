package com.pocketrealm.gateway

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.iroh.AuthStreamFilter
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import com.pocketrealm.discovery.iroh.PortMux
import com.pocketrealm.discovery.iroh.RealmListRewrite
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Adversarial negative probes the first pass did
 * not run. Two kinds live here:
 *
 *  - LEAK PROBES (expected RED until fixed): they assert the invariant the
 *    wave pins ("the client must NEVER receive a frame that would dial the
 *    host's raw address" — JoinGateway/RealmListRewrite KDocs) against the
 *    phase-machine lanes the FRAMED-phase loud refusal does not cover. A
 *    red run IS the finding: the system does the wrong thing with no clean
 *    rejection.
 *  - DEFENSE PINS (expected GREEN): crafted wrong-flavor dual-parse frames
 *    (adversarial, not the round-1 natural fixtures), a full mux-port sweep,
 *    and the relay-host fence's hostile shapes.
 */
class BreakerR2Round2NegativeTest {

    private val hostAddress = "203.0.113.7:8085"

    /** Deterministic LCG for the fuzz frames. */
    private var seed: Int = 0x574F574C // 'WOWL'

    private fun rnd(bound: Int): Int {
        seed = seed * 1_103_515_245 + 12_345
        return ((seed ushr 16) and 0x7fff) % bound
    }

    // ============================== LEAK PROBES (expected RED) =========

    /**
     * PROBE (documents the breach, full scenario): a hostile host prepends
     * ONE foreign byte to the server→client stream. The filter flips to
     * PASSTHROUGH forever — so the ENTIRE session, including the REAL
     * post-auth realm list the client acts on, is relayed UNREWRITTEN. The
     * client logs in fine (bytes pass verbatim) and receives the host's raw
     * address — the exact dial the joiner gateway exists to prevent — with
     * no exception, no error line, no join kill.
     */
    @Test
    fun probeOneForeignLeadingByteTurnsTheWholeSessionUnrewritten() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        // The hostile host's stream: a stray 0x10 byte, then the honest-
        // looking auth dance, then the realm list it wants the client to
        // dial (carrying the host's raw address).
        val challenge = byteArrayOf(0x00, 0x00) + ByteArray(32) +
            byteArrayOf(0x01, 0x07) + byteArrayOf(32) + ByteArray(32) +
            ByteArray(32) + ByteArray(16) + byteArrayOf(0x00)
        val proof = byteArrayOf(0x01, 0x00) + ByteArray(20) +
            ByteArray(4) + ByteArray(4) + byteArrayOf(0, 0) // 32 bytes
        val stream = byteArrayOf(0x10) + challenge + proof +
            modernFrame(count = 1, address = hostAddress)
        // The contract: REWRITTEN or KILLED LOUDLY — never relayed
        // verbatim. A RealmListRewriteException IS the loud kill.
        val outcome = runCatching { filter.feed(stream) }
        outcome.onSuccess { emitted ->
            val text = String(emitted.reduce { a, b -> a + b }, Charsets.US_ASCII)
            assertTrue(
                !text.contains(hostAddress),
                "LEAK CONFIRMED: after one foreign leading byte the filter " +
                    "relays the session verbatim — the realm list carrying " +
                    "$hostAddress reached the client unrewritten; emitted: " +
                    text.take(200),
            )
        }.onFailure { failure ->
            assertTrue(
                failure is RealmListRewrite.RealmListRewriteException,
                "the loud kill must carry the gateway refusal code: $failure",
            )
        }
    }

    /**
     * PROBE (documents the breach, wrong-flavor pairing): a vanilla realmd
     * (26-byte proof) behind a wotlk join. The proof consume steals 6 bytes
     * of the next frame; the FRAMED parser misreads a huge declared size
     * and WAITS forever — the lane silently stalls: no delivery, no
     * exception, no error line, no exit. "Fail the join loudly on
     * dial/rewrite failure" is not met: the wrong-flavor join neither
     * completes nor refuses.
     */
    @Test
    fun probeWrongFlavorRealmdNeitherCompletesNorFailsLoudly() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        val challenge = byteArrayOf(0x00, 0x00) + ByteArray(32) +
            byteArrayOf(0x01, 0x07) + byteArrayOf(32) + ByteArray(32) +
            ByteArray(32) + ByteArray(16) + byteArrayOf(0x00)
        val vanillaProof = byteArrayOf(0x01, 0x00) + ByteArray(24) // 26 bytes
        val vanillaList = vanillaFrame(count = 1, address = hostAddress)
        // The contract: the pairing must neither leak nor STALL —
        // a loud refusal (RealmListRewriteException) is the fixed behavior.
        val outcome = runCatching {
            filter.feed(challenge + vanillaProof + vanillaList)
        }
        outcome.onSuccess { emitted ->
            val text = String(emitted.reduce { a, b -> a + b }, Charsets.US_ASCII)
            assertTrue(
                !text.contains(hostAddress),
                "LEAK CONFIRMED: the wrong-flavor pairing delivered the " +
                    "host's raw address; emitted=${text.take(120)}",
            )
        }.onFailure { failure ->
            assertTrue(
                failure is RealmListRewrite.RealmListRewriteException,
                "the loud refusal must carry the gateway code: $failure",
            )
        }
    }

    // ============================== DEFENSE PINS (expected GREEN) ======

    /**
     * Adversarial dual-parse fuzz: frames built in ONE flavor's grammar
     * (randomized counts, icons, flags, names, addresses, tails — including
     * embedded 0x0002/0x0010 pairs crafted to spoof the OTHER flavor's
     * trailer) must NEVER parse under the other flavor's walker. Round 1
     * pinned the natural frames; this hammers the crafted collision space
     * (the trailer+atEnd argument, empirically).
     */
    @Test
    fun craftedFramesNeverParseUnderTheWrongFlavor() {
        fun randomVanillaFrame(): ByteArray {
            val payload = ArrayList<Byte>()
            repeat(4) { payload.add(0) } // u32 header word
            val count = rnd(4)
            payload.add(count.toByte())
            repeat(count) {
                repeat(4) { payload.add(rnd(256).toByte()) } // u32 icon
                payload.add(rnd(256).toByte()) // flags
                payload.addAll(cstr(randomToken(rnd(12) + 1)))
                payload.addAll(cstr(randomAddress(rnd(20))))
                repeat(4 + 3) { payload.add(rnd(256).toByte()) }
            }
            payload.addAll(byteArrayOf(0x02, 0x00).toList()) // vanilla trailer
            return framed(payload.toByteArray())
        }

        fun randomModernFrame(): ByteArray {
            val payload = ArrayList<Byte>()
            repeat(4) { payload.add(0) }
            val count = rnd(4)
            payload.add(count.toByte()); payload.add(0)
            repeat(count) {
                payload.add(rnd(256).toByte()) // icon
                payload.add(rnd(256).toByte()) // lock
                payload.add(rnd(256).toByte()) // flags
                payload.addAll(cstr(randomToken(rnd(12) + 1)))
                payload.addAll(cstr(randomAddress(rnd(20))))
                repeat(4 + 3) { payload.add(rnd(256).toByte()) }
                if (rnd(2) == 0) repeat(3 + 2) { payload.add(rnd(256).toByte()) }
            }
            payload.addAll(byteArrayOf(0x10, 0x00).toList()) // modern trailer
            return framed(payload.toByteArray())
        }

        repeat(400) { i ->
            val vanilla = randomVanillaFrame()
            val modern = randomModernFrame()
            try {
                RealmListRewrite.parse(vanilla, ClientFlavors.TBC)
                fail("crafted vanilla frame #$i parsed under tbc: ${vanilla.toHex()}")
            } catch (_: RealmListRewrite.RealmListRewriteException) {
                // the defense held
            }
            try {
                RealmListRewrite.parse(modern, ClientFlavors.VANILLA)
                fail("crafted modern frame #$i parsed under vanilla: ${modern.toHex()}")
            } catch (_: RealmListRewrite.RealmListRewriteException) {
                // the defense held
            }
        }
    }

    /**
     * A hostile host can make the frame's count field read HUGE under the
     * wrong flavor (vanilla u8 count 0xFF + icon 0x00.. becomes modern u16
     * count 0x00FF=255 > the 64-entry admission bound). The bound must
     * refuse before any entry walk, under BOTH flavors' readings.
     */
    @Test
    fun aCraftedCountCollisionRefusesUnderTheWrongFlavor() {
        // vanilla frame, count=0x01, icon u32 b0=0x01: the modern reading
        // takes u16 count = 0x01 | (0x01 << 8) = 257 — over the bound.
        val payload = byteArrayOf(0, 0, 0, 0, 0x01, 0x01, 0, 0, 0, 0x20) +
            cstr("A") + cstr(hostAddress) +
            byteArrayOf(0, 0, 0, 0x3f, 1, 0, 0, 0x02, 0x00)
        val frame = framed(payload)
        val refusal = assertThrows<RealmListRewrite.RealmListRewriteException> {
            RealmListRewrite.parse(frame, ClientFlavors.TBC)
        }
        assertTrue(
            refusal.message!!.contains("pinned bound"),
            "the wrong-flavor reading must die on the admission bound, got: ${refusal.message}",
        )
        // The honest pairing still parses (the bound did not eat it).
        RealmListRewrite.parse(frame, ClientFlavors.VANILLA)
    }

    /**
     * Mux mischief sweep: of all 65 536 possible 2-byte headers, EXACTLY
     * the two game lanes decode plus the RESERVED keep-alive lane
     * (round-6 R6-2-1's port id 0 — the joiner's loss probe, never a
     * game lane); every other header (and every encode of a non-lane
     * port) refuses — no fourth lane can be smuggled open.
     */
    @Test
    fun theMuxOpensExactlyTwoLanesAcrossTheWholeHeaderSpace() {
        val allowed = mutableSetOf<Int>()
        for (port in 0..0xffff) {
            val header = byteArrayOf(
                ((port ushr 8) and 0xff).toByte(),
                (port and 0xff).toByte(),
            )
            try {
                allowed.add(PortMux.decodePort(header))
            } catch (_: IllegalArgumentException) {
                // refused — the only legal outcomes are the two game ports
            }
        }
        assertEquals(
            setOf(
                GatewayContractPorts.keepalive,
                GatewayContractPorts.signup,
                GatewayContractPorts.hello,
                GatewayContractPorts.realm,
                GatewayContractPorts.world,
            ),
            allowed,
            "the mux must open exactly the two game lanes plus the " +
                "reserved keep-alive, sign-up and hello lanes",
        )
        for (port in 0..0xffff) {
            if (port == GatewayContractPorts.keepalive ||
                port == GatewayContractPorts.signup ||
                port == GatewayContractPorts.hello ||
                port == GatewayContractPorts.realm ||
                port == GatewayContractPorts.world
            ) {
                continue
            }
            try {
                PortMux.encodeHeader(port)
                fail("encodeHeader accepted non-lane port $port")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /**
     * The relay-host fence vs hostile LAST-label shapes: the fence's KDoc
     * pins "labels of letters/digits/hyphen" — the last label's charset is
     * currently never validated, so numeric-only (dotless-decimal-adjacent),
     * underscore, and query-suffix junk ride a validated descriptor into a
     * SIGNED PUBLIC listing. EXPECTED RED until the fence closes the leg.
     */
    @Test
    fun probeHostileLastLabelRelayHostsRideTheFence() {
        for (relay in listOf(
            "https://evil.12345678", // all-digits last label (8-digit inet_aton shape)
            "https://example.my_relay", // underscore last label
            "https://relay.example.com?x=1", // query junk
        )) {
            try {
                IrohJoinDescriptor.fromListingJson(
                    org.json.JSONObject()
                        .put("type", "iroh")
                        .put("node", "ab".repeat(32))
                        .put("relay", relay),
                )
                fail("relay '$relay' carries a non-hostname last label — must refuse")
            } catch (_: IllegalArgumentException) {
                // the fence held
            }
        }
    }

    /** The honest relay shapes stay legal (the fence closes WITHOUT the
     * self-hosted escape hatch breaking). */
    @Test
    fun honestRelayShapesStayLegal() {
        for (relay in listOf(
            "https://relay.example.com",
            "https://relay.example.com:443",
            "https://use-your-own.relay.example.com/",
        )) {
            IrohJoinDescriptor.fromListingJson(
                org.json.JSONObject()
                    .put("type", "iroh")
                    .put("node", "ab".repeat(32))
                    .put("relay", relay),
            )
        }
    }

    // ------------------------------------------------------------- helpers

    private fun randomToken(len: Int): String =
        (1..len).map { ('A' + rnd(26)) }.joinToString("")

    /** A printable "host:port"-shaped address (or a deliberately weird one). */
    private fun randomAddress(kind: Int): String = when (kind % 4) {
        0 -> "203.0.113.$((kind % 250) + 1):8085"
        1 -> "realm$kind.example.com:3724"
        2 -> "10.0.$kind.7:8085"
        else -> "a:1"
    }

    private fun cstr(s: String): List<Byte> =
        (s.toByteArray(Charsets.US_ASCII).toList()) + 0.toByte()

    private fun vanillaFrame(count: Int, address: String): ByteArray {
        val payload = ArrayList<Byte>()
        repeat(4) { payload.add(0) }
        payload.add(count.toByte())
        repeat(count) {
            repeat(4) { payload.add(0) } // u32 icon
            payload.add(0x20) // flags
            payload.addAll(cstr("Pocket Realm"))
            payload.addAll(cstr(address))
            payload.addAll(byteArrayOf(0, 0, 0, 0x3f, 3, 1, 0).toList())
        }
        payload.addAll(byteArrayOf(0x02, 0x00).toList())
        return framed(payload.toByteArray())
    }

    private fun modernFrame(count: Int, address: String): ByteArray {
        val payload = ArrayList<Byte>()
        repeat(4) { payload.add(0) }
        payload.add(count.toByte()); payload.add(0)
        repeat(count) {
            payload.add(0); payload.add(0); payload.add(0x24)
            payload.addAll(cstr("Pocket Realm"))
            payload.addAll(cstr(address))
            payload.addAll(byteArrayOf(0, 0, 0, 0x3f, 3, 1, 0x2c).toList())
            payload.addAll(byteArrayOf(3, 3, 5, 0x34, 0x30).toList()) // build block
        }
        payload.addAll(byteArrayOf(0x10, 0x00).toList())
        return framed(payload.toByteArray())
    }

    private fun framed(payload: ByteArray): ByteArray = byteArrayOf(
        RealmListRewrite.CMD_REALM_LIST.toByte(),
        (payload.size and 0xff).toByte(),
        ((payload.size shr 8) and 0xff).toByte(),
    ) + payload

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** The port contract values restated (this module pins them elsewhere;
     * the sweep reads them here so a contract drift fails BOTH). */
    private object GatewayContractPorts {
        val realm: Int = com.pocketrealm.discovery.iroh.GatewayContract.PORT_REALM
        val world: Int = com.pocketrealm.discovery.iroh.GatewayContract.PORT_WORLD
        val keepalive: Int = com.pocketrealm.discovery.iroh.GatewayContract.PORT_KEEPALIVE
        val signup: Int = com.pocketrealm.discovery.iroh.GatewayContract.PORT_SIGNUP
        val hello: Int = com.pocketrealm.discovery.iroh.GatewayContract.PORT_HELLO
    }
}
