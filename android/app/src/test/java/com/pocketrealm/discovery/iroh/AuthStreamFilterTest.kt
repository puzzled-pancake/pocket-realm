package com.pocketrealm.discovery.iroh

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W6-N3 code-red rounds 1+2: the filter's unit suite feeds the REAL auth
 * lane shapes (RAW challenge/proof responses, then the framed realm list)
 * AND the adversarial probes: a foreign leading byte, an auth-
 * error reply, and the wrong-flavor pairing — in EVERY case the invariant
 * holds: nothing carrying the host's raw address reaches the client, and
 * realm-list-shaped corruption dies loudly.
 */
class AuthStreamFilterTest {

    // ------------------------------------------------- real wire shapes

    /** The realmd logon-challenge response (AuthSocket.cpp:489-543): 118
     * bytes, NO size field. */
    private fun challengeResponse(): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x00) // cmd
        out.add(0x00) // err = SUCCESS
        repeat(32) { out.add(((it * 31 + 7) and 0xff).toByte()) } // B
        out.add(0x01) // g_len
        out.add(0x07) // g
        out.add(32) // N_len
        repeat(32) { out.add(((it * 17 + 3) and 0xff).toByte()) } // N
        repeat(32) { out.add(((it * 5 + 11) and 0xff).toByte()) } // s
        repeat(16) { out.add(((it * 13 + 1) and 0xff).toByte()) } // VersionChallenge
        out.add(0x00) // securityFlags = NONE
        return out.toByteArray()
    }

    /** The logon-proof response (AuthSocket.cpp:135-143): the flavor's
     * registered size, NO size field. */
    private fun proofResponse(flavor: ClientFlavor): ByteArray =
        ByteArray(requireNotNull(flavor.proofResponseBytes)).also { it[0] = 0x01 }

    /** The pinned TBC fixture (RealmListRewriteTest owns the byte map). */
    private val tbcFrame = hex(
        "103500000000000100000024506f636b6574205265616c6d00" +
            "3230332e302e3131332e373a38303835000000003f03012c0204039e211000",
    )

    private fun rewrittenTbcFrame(): ByteArray = hex(
        "103300000000000100000024506f636b6574205265616c6d00" +
            "3132372e302e302e313a38303835000000003f03012c0204039e211000",
    )

    /** The vanilla-framed realm list (a vanilla realmd's shape). */
    private fun vanillaList(address: String): ByteArray {
        val name = "Pocket Realm".toByteArray(Charsets.US_ASCII) + 0
        val addr = address.toByteArray(Charsets.US_ASCII) + 0
        val payload = ArrayList<Byte>()
        repeat(4) { payload.add(0) } // u32 header word
        payload.add(1) // u8 count
        repeat(4) { payload.add(0) } // u32 icon
        payload.add(0x20) // flags
        name.forEach { payload.add(it) }
        addr.forEach { payload.add(it) }
        byteArrayOf(0x00, 0x00, 0x00, 0x3f).forEach { payload.add(it) } // pop 0.5f
        payload.add(3); payload.add(1); payload.add(0) // chars, cat, unk
        payload.add(0x02); payload.add(0x00) // vanilla trailer
        return byteArrayOf(
            0x10, (payload.size and 0xff).toByte(),
            ((payload.size shr 8) and 0xff).toByte(),
        ) + payload
    }

    // ------------------------------------------------------------ tests

    @Test
    fun `the real auth flow passes raw and rewrites only the realm list`() {
        val filter = AuthStreamFilter(ClientFlavors.TBC)
        val emitted = ArrayList<ByteArray>()
        emitted += filter.feed(challengeResponse())
        emitted += filter.feed(proofResponse(ClientFlavors.TBC))
        emitted += filter.feed(tbcFrame)
        val joined = emitted.reduce { acc, bytes -> acc + bytes }
        val expected = challengeResponse() +
            proofResponse(ClientFlavors.TBC) + rewrittenTbcFrame()
        assertArrayEquals(
            "the emitted stream must equal the input except the rewritten list",
            expected,
            joined,
        )
        assertFalse("nothing may stay buffered", filter.hasPendingBytes())
    }

    @Test
    fun `byte-at-a-time fragmentation of the real flow still works`() {
        val filter = AuthStreamFilter(ClientFlavors.TBC)
        val whole = challengeResponse() +
            proofResponse(ClientFlavors.TBC) + tbcFrame
        val emitted = ArrayList<ByteArray>()
        var total = 0
        for (b in whole) {
            val out = filter.feed(byteArrayOf(b))
            if (out.isNotEmpty()) {
                total += out.sumOf { it.size }
                println("FRAG emit at " + total + " size " + out.sumOf { it.size } +
                    " head=" + String(out[0], Charsets.ISO_8859_1).take(12)
                    .replace(Regex("[^ -~]"), "."))
            }
            emitted += out
        }
        val joined = emitted.reduce { acc, bytes -> acc + bytes }
        println("FRAG joined=" + joined.size + " whole=" + whole.size)
        println(
            "FRAG joined: " + String(joined, Charsets.ISO_8859_1)
                .replace(Regex("[^ -~]"), "."),
        )
        val expected = challengeResponse() +
            proofResponse(ClientFlavors.TBC) + rewrittenTbcFrame()
        assertArrayEquals(expected, joined)
        assertFalse(filter.hasPendingBytes())
    }

    /** Code-red R2-2-1/R7-N3-1: a hostile host prepends ONE foreign byte —
     * the ENTIRE session (including the realm list the client acts on)
     * must still be rewritten, never relayed verbatim. */
    @Test
    fun `a foreign leading byte never turns the session unrewritten`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        val stream = byteArrayOf(0x10) + challengeResponse() +
            proofResponse(ClientFlavors.WOTLK) +
            vanillaList("203.0.113.7:8085")
        // The invariant: rewritten-or-loud. A zero-size/oversized candidate
        // is realm-list-shaped corruption and THROWS (the loud kill); the
        // only forbidden outcome is the host's address reaching the client.
        val joined = runCatching { feedAll(filter, stream) }
            .getOrElse { ByteArray(0) }
        assertFalse(
            "the host's raw address reached the client through a " +
                "foreign-leading-byte stream",
            String(joined, Charsets.US_ASCII).contains("203.0.113.7"),
        )
    }

    /** Code-red R7-N3-1 + round-6 R6-2-2: a valid realm list as the
     * FIRST server bytes (no auth at all) is still VALIDATED — and a
     * vanilla-layout list on a wotlk-declared join is the misdeclared-
     * host signal: the loud refusal, never a cross-layout re-emit the
     * client would misread, and never the host's raw address. */
    @Test
    fun `a valid realm list as the first server bytes is validated loudly`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        val thrown = runCatching {
            feedAll(filter, vanillaList("203.0.113.7:8085"))
        }.exceptionOrNull()
        assertTrue(
            "the misdeclared list must refuse loudly",
            thrown is RealmListRewrite.RealmListRewriteException &&
                thrown.message!!.contains("misdeclared"),
        )
    }

    /** Code-red R7-N3-1 + round-6 R6-2-2: after an upstream auth-error
     * reply the lane arms the belt — a following realm list is still
     * VALIDATED, and a foreign-layout list on this declared join refuses
     * loudly (the misdeclared-host signal), never relayed and never
     * cross-layout re-emitted. */
    @Test
    fun `a realm list after an auth-error reply is still validated loudly`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        val errorReply = byteArrayOf(0x00, 0x02)
        val thrown = runCatching {
            feedAll(filter, errorReply + vanillaList("203.0.113.7:8085"))
        }.exceptionOrNull()
        assertTrue(
            "the post-error realm list must still be validated loudly",
            thrown is RealmListRewrite.RealmListRewriteException &&
                thrown.message!!.contains("misdeclared"),
        )
    }

    /** Code-red R2-2-2: the wrong-flavor pairing (a vanilla realmd's
     * 26-byte proof behind a wotlk join) must neither leak nor stall —
     * the lane dies loudly. */
    @Test
    fun `the wrong-flavor pairing dies loudly - never stalls or leaks`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        val vanillaProof = byteArrayOf(0x01, 0x00) + ByteArray(24) // 26 bytes
        var loud = false
        try {
            filter.feed(challengeResponse())
            filter.feed(vanillaProof)
            filter.feed(vanillaList("203.0.113.7:8085"))
            filter.feed(ByteArray(17 * 1024)) // force the budget past any wait
            filter.feed(ByteArray(0), eof = true)
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            loud = true
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
        assertTrue(
            "the wrong-flavor pairing must die loudly (never stall silently)",
            loud,
        )
    }

    /** Code-red R1-r3-2: the VANILLA phase machine leg end-to-end — the
     * vanilla proof response is 26 bytes (the one flavor whose proof wire
     * length differs), consumed from the OWNING registry, and the vanilla
     * framed list rewrites to the loopback target with the recomputed u16
     * LE size (the round-1 vanilla golden owns the expected bytes). */
    @Test
    fun `vanilla phase machine consumes the 26-byte proof and rewrites the list`() {
        val filter = AuthStreamFilter(ClientFlavors.VANILLA)
        val challenge = challengeResponse()
        val proof = ByteArray(26).also { it[0] = 0x01; it[1] = 0x00 } // vanilla 26
        val emitted = ArrayList<ByteArray>()
        emitted += filter.feed(challenge)
        emitted += filter.feed(proof)
        emitted += filter.feed(vanillaList("203.0.113.7:8085"))
        val joined = emitted.reduce { acc, bytes -> acc + bytes }
        // The proof bytes pass through unrewritten; the final emission is
        // the vanilla frame whose only delta is the address C-string.
        assertArrayEquals(
            challenge + proof,
            joined.copyOfRange(0, challenge.size + proof.size),
        )
        val rewritten = RealmListRewrite.rewrite(
            vanillaList("203.0.113.7:8085"), ClientFlavors.VANILLA,
            RealmListRewrite.JOINER_WORLD_TARGET,
        )
        assertArrayEquals(rewritten, joined.copyOfRange(challenge.size + proof.size, joined.size))
        assertFalse(
            "the host's raw address must never reach the client",
            String(joined, Charsets.US_ASCII).contains("203.0.113.7"),
        )
        assertFalse(filter.hasPendingBytes())
    }

    @Test
    fun `a corrupt realm list in the framed phase fails LOUDLY`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challengeResponse())
        filter.feed(proofResponse(ClientFlavors.WOTLK))
        val corrupt = tbcFrame.copyOf()
        corrupt[corrupt.size - 2] = 0x11
        try {
            filter.feed(corrupt)
            fail("a corrupt realm list in the framed phase must throw")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
    }

    @Test
    fun `a huge declared realm-list size refuses instead of stalling`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challengeResponse())
        filter.feed(proofResponse(ClientFlavors.WOTLK))
        try {
            filter.feed(byteArrayOf(0x10, 0xff.toByte(), 0xff.toByte()) + ByteArray(64))
            fail("an oversized declared realm list must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("pinned post-auth bound"))
        }
    }

    /** Code-red R3-r2-2: in the BELT phase a realm-list candidate that
     * FAILS the parse under EVERY registered layout is corruption — the
     * loud refusal (the belt never relays realm-list bytes verbatim). */
    @Test
    fun `a belt candidate that fails the parse dies loudly`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challengeResponse())
        filter.feed(ByteArray(requireNotNull(ClientFlavors.WOTLK.proofResponseBytes)) { 0x55 })
        // A frame whose body parses under NO registered layout (trailer
        // 0x1111 + wotlk/mangled mix): the belt rewrite refuses and the
        // join dies loudly.
        val mangled = hex(
            "103500000000000100000024506f636b6574205265616c6d00" +
                "3230332e302e3131332e373a38303835000000003f03012c0204039e211111",
        )
        try {
            filter.feed(mangled)
            fail("a belt candidate failing every layout must die loudly")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
    }

    /** Code-red round-5 R2-r5-1: a realm-list frame truncated to EXACTLY
     * its 3-byte header rides the EOF tail — the shape test is the
     * OPCODE alone, so the header-only frame dies loudly (the old
     * `size > header` conjunct relayed it verbatim and the client hung
     * at logon on a short read). */
    @Test
    fun `a realm-list header truncated to its 3-byte header at EOF refuses loudly`() {
        val filter = AuthStreamFilter(ClientFlavors.VANILLA)
        filter.feed(challengeResponse())
        filter.feed(proofResponse(ClientFlavors.VANILLA))
        try {
            filter.feed(byteArrayOf(0x10, 0x05, 0x00), eof = true)
            fail("a header-only realm list at EOF must refuse loudly")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
    }

    /** its 1-byte edge: even a bare opcode byte at EOF is the loud
     * refusal — never an inert relay. */
    @Test
    fun `a bare realm-list opcode byte at EOF refuses loudly`() {
        val filter = AuthStreamFilter(ClientFlavors.VANILLA)
        filter.feed(challengeResponse())
        filter.feed(proofResponse(ClientFlavors.VANILLA))
        try {
            filter.feed(byteArrayOf(0x10), eof = true)
            fail("a bare opcode byte at EOF must refuse loudly")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
    }

    /** its control: a NON-realm-list EOF tail held in the BELT
     * phase stays inert (the opcode shape test must not turn ordinary
     * remnants loud). The belt is reached through its real door — the
     * proof-mismatch fallback (a vanilla filter fed a wotlk proof);
     * the FRAMED phase after a GOOD proof is strict by design and has
     * no inert leg at all. */
    @Test
    fun `a non-realm-list EOF tail still passes through inert`() {
        val filter = AuthStreamFilter(ClientFlavors.VANILLA)
        filter.feed(challengeResponse())
        // The belt door: a proof-sized response whose success byte is
        // NOT LOGON_SUCCESS — consumeProof's mismatch fallback arms the
        // belt and drains the rejected proof through it.
        filter.feed(proofResponse(ClientFlavors.VANILLA).also { it[1] = 0x7f })
        // Held (shorter than the 3-byte header): first byte is not the
        // realm-list opcode, so the EOF drain relays it inert.
        val tail = byteArrayOf(0x07, 0x07)
        val out = filter.feed(tail, eof = true)
        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(tail))
    }

    /** Code-red round-6 R1-r6-1: the PRE-AUTH phases have a byte budget
     * — a stream that never emits a valid challenge must not grow the
     * child's heap without bound. The budget fires loud (the same
     * refusal class as the framed budget). */
    @Test
    fun `a pre-auth stream past the byte budget refuses loudly`() {
        val filter = AuthStreamFilter(ClientFlavors.VANILLA)
        val chunk = ByteArray(16 * 1024) { 0x41 }
        try {
            // 16 KiB x 8 = 128 KiB — twice the pinned 64 KiB pre-auth
            // budget; the throw must land well before the feed ends.
            repeat(8) {
                filter.feed(chunk)
            }
            fail("a hostile pre-auth flood must refuse loudly")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
            assertTrue(expected.message!!.contains("pre-auth"))
        }
    }

    /** its belt path: a VALID foreign-layout list on a
     * misdeclared join dies loudly through the filter (the probe's
     * garbage-dial class) — never a cross-layout re-emit. */
    @Test
    fun `a belt candidate that parses only under a foreign layout dies loudly`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        filter.feed(challengeResponse())
        filter.feed(proofResponse(ClientFlavors.WOTLK))
        try {
            // vanillaList builds a true VANILLA-layout frame (verified
            // builder); the join declares wotlk — the count-width
            // difference makes the wotlk walk refuse while the vanilla
            // parse succeeds: exactly the MISDECLARED-HOST diagnosis
            // (never a cross-layout re-emit).
            filter.feed(vanillaList("203.0.113.7:8085"))
            fail("a foreign-layout belt candidate must die loudly")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(
                "got: " + expected.message,
                expected.message!!.contains("misdeclared"),
            )
        }
    }

    @Test
    fun `empty feeds emit nothing`() {
        val filter = AuthStreamFilter(ClientFlavors.WOTLK)
        assertTrue(filter.feed(ByteArray(0)).isEmpty())
        assertFalse(filter.hasPendingBytes())
    }

    // ---------------------------------------------------------- helpers

    /** Feeds [stream], then drains the EOF tail, and joins EVERYTHING that
     * came out (the invariant assertions run over the whole stream). */
    private fun feedAll(filter: AuthStreamFilter, stream: ByteArray): ByteArray {
        val emitted = ArrayList<ByteArray>()
        emitted += filter.feed(stream)
        emitted += filter.feed(ByteArray(0), eof = true)
        return emitted.reduce { acc, bytes -> acc + bytes }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }
}
