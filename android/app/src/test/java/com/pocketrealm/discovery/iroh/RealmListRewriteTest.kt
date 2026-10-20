package com.pocketrealm.discovery.iroh

import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * the realm-list parse/rebuild fixtures — ONE canonical real frame
 * per flavor, byte-pinned, plus the rebuild assertions the ask names:
 * the rewritten list is EXACTLY what the client would dial, the size field
 * is recomputed (never a same-length swap), and the rewrite target is the
 * joiner's own loopback world listener.
 *
 * Each fixture is transcribed field-by-field from the SHIPPED server
 * sources (the citations live on [RealmListRewrite]'s doc): the realmd
 * frame header, the flavor's count width, the entry field widths, the
 * C-string addresses realmd produces by appending ":port"
 * (native/cmangos/src/realmd/RealmList.cpp:155-158), and the per-flavor
 * trailer. NUMERIC FIELDS ARE LITTLE-ENDIAN (mangos ByteBuffer raw-memory
 * append, EndianConvert a no-op on x86 — the endianness doc on
 * [RealmListRewrite]); the fixtures carry the real wire order. The
 * addresses are TEST-NET documentation ranges — fixtures, not real
 * infrastructure; the joiner gateway replaces them at run time.
 */
class RealmListRewriteTest {

    // ---------------------------------------------------------- fixtures

    /** 1.12 (the vanilla build): u32 LE header word, u8 count, u32 LE icon, u8
     * flags, CStr name, CStr address, f32 LE pop, u8 chars, u8 category,
     * u8 unk, u16 LE trailer 0x0002. One realm, SPECIFYBUILD never set
     * upstream. */
    private val vanillaFixture = hex(
        "103100" + // opcode 0x10, u16 LE payload size 49
            "00000000" + // u32 LE unused header word
            "01" + // u8 realm count
            "00000000" + // u32 LE icon (realm type)
            "20" + // u8 flags: REALM_FLAG_NEW_PLAYERS
            "506f636b6574205265616c6d00" + // CStr "Pocket Realm"
            "3230332e302e3131332e373a3830383500" + // CStr "203.0.113.7:8085"
            "0000003f" + // f32 LE population 0.5
            "03" + // u8 character count
            "01" + // u8 category
            "00" + // u8 unk
            "0200", // u16 LE trailer (1.12's 0x0002)
    )

    /** 2.4.3 (the tbc build): u32 LE header word, u16 LE count, u8 icon, u8
     * lock, u8 flags, CStr name, CStr address, f32 LE pop, u8 chars, u8
     * category, u8 unk(0x2C), SPECIFYBUILD block {u8 2, u8 4, u8 3, u16 LE
     * build}, u16 LE trailer 0x0010. */
    private val tbcFixture = hex(
        "103500" + // opcode 0x10, u16 LE payload size 53
            "00000000" +
            "0100" + // u16 LE realm count (2.x/3.x width!)
            "00" + // u8 icon
            "00" + // u8 lock
            "24" + // u8 flags: NEW_PLAYERS | SPECIFYBUILD
            "506f636b6574205265616c6d00" +
            "3230332e302e3131332e373a3830383500" +
            "0000003f" +
            "03" +
            "01" +
            "2c" + // u8 unk (upstream's constant 0x2C)
            "020403" + // build block major.minor.bugfix
            "9e21" + // u16 LE build (the TBC registry build, little-endian)
            "1000", // u16 LE trailer (2.x/3.x's 0x0010)
    )

    /** 3.3.5 (the wotlk build): same structure as TBC, different build block
     * and a different host address — the per-flavor fixtures are
     * independent. */
    private val wotlkFixture = hex(
        "103700" + // opcode 0x10, u16 LE payload size 55
            "00000000" +
            "0100" +
            "00" +
            "00" +
            "24" +
            "506f636b6574205265616c6d00" +
            "3139382e35312e3130302e32333a3830383500" + // CStr "198.51.100.23:8085"
            "0000003f" +
            "03" +
            "01" +
            "2c" +
            "030305" + // build block major.minor.bugfix
            "3430" + // u16 LE build (the WotLK registry build, little-endian)
            "1000",
    )

    // ----------------------------------------------------------- rebuild

    @Test
    fun `vanilla fixture rebuilds to exactly the loopback dial target`() {
        val rebuilt = RealmListRewrite.rewrite(
            vanillaFixture,
            ClientFlavors.VANILLA,
            RealmListRewrite.JOINER_WORLD_TARGET,
        )
        // Byte-pinned expectation: identical frame, address C-string
        // replaced, size recomputed 0x0031 -> 0x002f (127.0.0.1 is two
        // bytes shorter than the fixture's TEST-NET address — the same-
        // length swap the plan forbids would NOT produce this).
        assertEquals(
            "102f00" +
                "00000000" +
                "01" +
                "00000000" +
                "20" +
                "506f636b6574205265616c6d00" +
                "3132372e302e302e313a3830383500" + // CStr "127.0.0.1:8085"
                "0000003f" +
                "03" +
                "01" +
                "00" +
                "0200",
            hex(rebuilt),
        )
    }

    @Test
    fun `tbc fixture rebuilds with size recompute and build block preserved`() {
        val rebuilt = RealmListRewrite.rewrite(
            tbcFixture,
            ClientFlavors.TBC,
            RealmListRewrite.JOINER_WORLD_TARGET,
        )
        assertEquals(
            "103300" +
                "00000000" +
                "0100" +
                "00" + "00" + "24" +
                "506f636b6574205265616c6d00" +
                "3132372e302e302e313a3830383500" +
                "0000003f" +
                "03" +
                "01" +
                "2c" +
                "020403" + // the SPECIFYBUILD block survives verbatim
                "9e21" +
                "1000",
            hex(rebuilt),
        )
    }

    @Test
    fun `wotlk fixture rebuilds with size recompute and build block preserved`() {
        val rebuilt = RealmListRewrite.rewrite(
            wotlkFixture,
            ClientFlavors.WOTLK,
            RealmListRewrite.JOINER_WORLD_TARGET,
        )
        assertEquals(
            "103300" +
                "00000000" +
                "0100" +
                "00" + "00" + "24" +
                "506f636b6574205265616c6d00" +
                "3132372e302e302e313a3830383500" +
                "0000003f" +
                "03" +
                "01" +
                "2c" +
                "030305" +
                "3430" +
                "1000",
            hex(rebuilt),
        )
    }

    @Test
    fun `the rebuild target is the joiner's own loopback world listener`() {
        // The ask's invariant: the rewritten list is exactly what the
        // client would dial, and the dial target is loopback (the joiner
        // gateway's world lane) — never the host's address. The rewrite
        // exists ONLY in the joiner path; the host gateway muxes raw bytes
        // and holds no rewriter at all (pinned by the gateway E2E).
        assertTrue(
            "the rewrite target must be loopback:port",
            RealmListRewrite.JOINER_WORLD_TARGET.startsWith("127.0.0.1:"),
        )
        assertEquals(
            "the world lane is the mux's 8085 port",
            GatewayContract.PORT_WORLD,
            RealmListRewrite.JOINER_WORLD_TARGET.substringAfter(':').toInt(),
        )
    }

    @Test
    fun `rebuild of the rebuilt frame is a fixed point`() {
        for ((fixture, flavor) in listOf(
            vanillaFixture to ClientFlavors.VANILLA,
            tbcFixture to ClientFlavors.TBC,
            wotlkFixture to ClientFlavors.WOTLK,
        )) {
            val once = RealmListRewrite.rewrite(
                fixture, flavor, RealmListRewrite.JOINER_WORLD_TARGET,
            )
            val twice = RealmListRewrite.rewrite(
                once, flavor, RealmListRewrite.JOINER_WORLD_TARGET,
            )
            assertTrue("rebuild must be a fixed point", once.contentEquals(twice))
        }
    }

    @Test
    fun `multi-entry frames rebuild every address`() {
        // Two 1.12 entries in one frame: both addresses become the loopback
        // target; count, order, and names preserved.
        val twoVanilla = hex(
            "105300" + // u16 LE payload size 83
                "00000000" +
                "02" +
                "00000000" + "20" +
                "5265616c6d204100" + // CStr "Realm A"
                "3230332e302e3131332e373a3830383500" +
                "0000003f" + "03" + "01" + "00" +
                "00000000" + "40" + // u32 LE icon, flags RECOMMENDED
                "5265616c6d204200" + // CStr "Realm B"
                "3139382e35312e3130302e32333a3830383500" +
                "00000000" + "00" + "02" + "00" + // pop 0.0f LE, 0 chars, cat 2, unk
                "0200",
        )
        val parsed = RealmListRewrite.parse(twoVanilla, ClientFlavors.VANILLA)
        assertEquals(2, parsed.entries.size)
        assertEquals("203.0.113.7:8085", parsed.entries[0].address)
        assertEquals("198.51.100.23:8085", parsed.entries[1].address)
        val rebuilt = RealmListRewrite.rewrite(
            twoVanilla, ClientFlavors.VANILLA, RealmListRewrite.JOINER_WORLD_TARGET,
        )
        val reparsed = RealmListRewrite.parse(rebuilt, ClientFlavors.VANILLA)
        assertEquals(2, reparsed.entries.size)
        assertEquals("Realm A", reparsed.entries[0].name)
        assertEquals("Realm B", reparsed.entries[1].name)
        reparsed.entries.forEach { entry ->
            assertEquals(RealmListRewrite.JOINER_WORLD_TARGET, entry.address)
        }
    }

    @Test
    fun `no-specifybuild modern frames parse without a build block`() {
        // The same TBC frame with flags 0x20 (no SPECIFYBUILD): the walker
        // must NOT consume a build block that is not there.
        val noBuild = hex(
            "103000" + // u16 LE payload size 48
                "00000000" +
                "0100" +
                "00" + "00" + "20" +
                "506f636b6574205265616c6d00" +
                "3230332e302e3131332e373a3830383500" +
                "0000003f" +
                "03" + "01" + "2c" +
                "1000",
        )
        val parsed = RealmListRewrite.parse(noBuild, ClientFlavors.TBC)
        assertEquals(1, parsed.entries.size)
        assertFalse(parsed.entries[0].specifyBuild)
        val rebuilt = RealmListRewrite.rewrite(
            noBuild, ClientFlavors.TBC, RealmListRewrite.JOINER_WORLD_TARGET,
        )
        assertEquals(
            RealmListRewrite.JOINER_WORLD_TARGET,
            RealmListRewrite.parse(rebuilt, ClientFlavors.TBC).entries[0].address,
        )
    }

    // ------------------------------------------- code-red round-1 probes

    /** Code-red R5-3: the 1.12 SPECIFYBUILD bit is LEGAL — upstream
     * appends the version to the NAME (AuthSocket.cpp:934-938) and writes
     * the flags byte with the bit still set (:948); no build block rides
     * the vanilla wire. A host with the show-version realmlist config
     * must join, not refuse. */
    @Test
    fun `vanilla frames with the SPECIFYBUILD bit parse and rewrite`() {
        val name = "Pocket Realm (1,12,1)".toByteArray(Charsets.US_ASCII) + 0
        val body = ArrayList<Byte>()
        body.add(0); body.add(0); body.add(0); body.add(0) // u32 LE header word
        body.add(1) // u8 count
        body.add(0); body.add(0); body.add(0); body.add(0) // u32 LE icon
        body.add(0x24) // flags: NEW_PLAYERS | SPECIFYBUILD (legal on 1.12)
        name.forEach { body.add(it) }
        cstr("203.0.113.7:8085").forEach { body.add(it) }
        byteArrayOf(0x00, 0x00, 0x00, 0x3f).forEach { body.add(it) } // pop 0.5f
        body.add(3); body.add(1); body.add(0) // chars, category, unk
        body.add(2); body.add(0) // u16 LE trailer 0x0002
        val payload = body.toByteArray()
        val vanillaWithBit = byteArrayOf(
            0x10, (payload.size and 0xff).toByte(),
            ((payload.size shr 8) and 0xff).toByte(),
        ) + payload
        val parsed = RealmListRewrite.parse(vanillaWithBit, ClientFlavors.VANILLA)
        assertTrue(parsed.entries[0].specifyBuild)
        assertEquals(
            "Pocket Realm (1,12,1)",
            parsed.entries[0].name,
        )
        val rebuilt = RealmListRewrite.rewrite(
            vanillaWithBit, ClientFlavors.VANILLA, RealmListRewrite.JOINER_WORLD_TARGET,
        )
        assertEquals(
            RealmListRewrite.JOINER_WORLD_TARGET,
            RealmListRewrite.parse(rebuilt, ClientFlavors.VANILLA).entries[0].address,
        )
    }

    /** Code-red R2-5: non-ASCII realm-name bytes are copied VERBATIM (a
     * US-ASCII re-encode mapped them to '?' and corrupted UTF-8 names). */
    @Test
    fun `non-ASCII realm name bytes are copied verbatim`() {
        // Build a vanilla frame whose name carries 0xC3 0x80 ("À" in
        // UTF-8) — high bytes the old US-ASCII round-trip destroyed.
        val nameBytes = byteArrayOf(
            0x50, 0x69, 0xC3.toByte(), 0x80.toByte(), 0x6f, 0x21, 0x00,
        ) // "PiÀo!" + NUL
        val payload = bytes(
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x20,
        ) + nameBytes + cstr("203.0.113.7:8085") +
            bytes(0x00, 0x00, 0x00, 0x3f, 0x03, 0x01, 0x00, 0x02, 0x00)
        val frame = bytes(0x10, payload.size and 0xff, (payload.size shr 8) and 0xff) + payload
        val rebuilt = RealmListRewrite.rewrite(
            frame, ClientFlavors.VANILLA, RealmListRewrite.JOINER_WORLD_TARGET,
        )
        // The name's raw bytes (high bytes included) survive VERBATIM in
        // the rebuilt frame — no '?' (0x3f) substitution anywhere.
        val nameRaw = nameBytes.dropLast(1).toByteArray() // without the NUL
        val idx = indexOf(rebuilt, nameRaw)
        assertTrue(
            "the name's raw bytes must survive the rewrite verbatim",
            idx >= 0,
        )
        assertTrue(
            "the rebuilt frame must carry the loopback address",
            String(rebuilt, Charsets.US_ASCII).contains("127.0.0.1:8085"),
        )
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** Code-red R2-2: a legal input frame whose rewrite GROWS past the u16
     * size ceiling refuses loudly — never a silently wrapped size field
     * (declared 4, actual 65540) desyncing the client's frame parser. */
    @Test
    fun `a rewrite past the u16 size ceiling refuses loudly`() {
        // 64 entries with ~900-byte names and a tiny address: the input is
        // wire-legal (< 0xFFFF) but the rewrite swaps each 4-byte address
        // for the 14-byte loopback target — the grown payload crosses
        // 0xFFFF and must refuse.
        val payload = ArrayList<Byte>()
        fun addBytes(bs: ByteArray) = bs.forEach { payload.add(it) }
        fun addInts(vararg ints: Int) = ints.forEach { payload.add(it.toByte()) }
        addInts(0, 0, 0, 0) // u32 LE header word
        addInts(64, 0) // u16 LE count = 64 (the pinned cap)
        repeat(64) {
            addInts(0, 0, 0) // u8 icon, u8 lock, u8 flags
            addBytes(ByteArray(1008) { 0x61.toByte() } + byteArrayOf(0))
            addBytes(cstr("a:1"))
            addBytes(bytes(0x00, 0x00, 0x00, 0x3f, 0x03, 0x01, 0x2c))
        }
        addBytes(bytes(0x10, 0x00)) // u16 LE trailer
        val body = payload.toByteArray()
        val frame = bytes(0x10, body.size and 0xff, (body.size shr 8) and 0xff) + body
        // Sanity: the INPUT is wire-legal (declared size ≤ 0xFFFF).
        assertTrue("input must be wire-legal", body.size <= 0xffff)
        assertEquals(64, RealmListRewrite.parse(frame, ClientFlavors.TBC).entries.size)
        try {
            RealmListRewrite.rewrite(
                frame, ClientFlavors.TBC, RealmListRewrite.JOINER_WORLD_TARGET,
            )
            fail("a rewrite past the u16 ceiling must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("u16 size field"))
        }
    }

    // ---------------------------------------------------------- refusals

    @Test
    fun `a foreign-layout list refuses instead of re-emitting - R6-2-2`() {
        // Code-red round-6 R6-2-2: a frame that parses ONLY under a
        // NON-declared layout is the misdeclared-host signal — the old
        // cross-layout re-emit shipped the declared client a frame its
        // naive walk read as a garbage dial target (the realm NAME).
        // rewriteUnderAnyLayout must refuse, naming both layouts.
        try {
            RealmListRewrite.rewriteUnderAnyLayout(
                vanillaFixture,
                ClientFlavors.WOTLK,
                RealmListRewrite.JOINER_WORLD_TARGET,
            )
            fail("a vanilla list on a wotlk-declared join must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
            assertTrue(expected.message!!.contains("vanilla"))
            assertTrue(expected.message!!.contains("wotlk"))
            assertTrue(expected.message!!.contains("misdeclared"))
        }
        // The reverse pairing: a modern frame on a vanilla-declared join.
        try {
            RealmListRewrite.rewriteUnderAnyLayout(
                wotlkFixture,
                ClientFlavors.VANILLA,
                RealmListRewrite.JOINER_WORLD_TARGET,
            )
            fail("a modern list on a vanilla-declared join must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("misdeclared"))
        }
        // The control: the DECLARED layout still rewrites exactly as
        // before (the refusal never touches the honest path).
        val rebuilt = RealmListRewrite.rewriteUnderAnyLayout(
            vanillaFixture,
            ClientFlavors.VANILLA,
            RealmListRewrite.JOINER_WORLD_TARGET,
        )
        assertTrue(hex(rebuilt).contains("3132372e302e302e31"))
    }

    @Test
    fun `a vanilla layout refused against the tbc flavor`() {
        // The count width differs (u8 vs u16): cross-flavor parsing must
        // fail loudly, never misparse into a silent wrong rewrite.
        try {
            RealmListRewrite.parse(vanillaFixture, ClientFlavors.TBC)
            fail("cross-flavor parse must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
    }

    @Test
    fun `truncated frames refuse loudly`() {
        for (cut in 1 until vanillaFixture.size) {
            try {
                RealmListRewrite.parse(
                    vanillaFixture.copyOf(cut), ClientFlavors.VANILLA,
                )
                // A CUT frame can never match its own declared payload
                // size (or its trailer), so every cut must refuse.
                fail("truncated frame at $cut must refuse")
            } catch (expected: RealmListRewrite.RealmListRewriteException) {
                assertTrue(
                    "the cut frame's refusal must carry the gate code",
                    expected.message!!.contains("VAL-IROH-REALMLIST"),
                )
            }
        }
    }

    @Test
    fun `a wrong trailer refuses`() {
        val wrong = vanillaFixture.copyOf(vanillaFixture.size)
        wrong[wrong.size - 2] = 0x03 // LE trailer 0x0002 -> 0x0003
        try {
            RealmListRewrite.parse(wrong, ClientFlavors.VANILLA)
            fail("wrong trailer must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("trailer"))
        }
    }

    @Test
    fun `an address without a port suffix refuses`() {
        // realmd always appends :port (RealmList.cpp:155-158), so a bare
        // address is a hostile frame — refused, never guessed around.
        val payload = bytes(
            0x00, 0x00, 0x00, 0x00, // u32 LE header word
            0x01, // count
            0x00, 0x00, 0x00, 0x00, // u32 LE icon
            0x20, // flags
        ) + cstr("Pocket Realm") + cstr("203.0.113.7") +
            bytes(0x00, 0x00, 0x00, 0x3f) + // f32 LE population 0.5
            bytes(0x03, 0x01, 0x00) +
            bytes(0x02, 0x00) // u16 LE trailer
        val frame = bytes(
            0x10,
            payload.size and 0xff,
            (payload.size shr 8) and 0xff, // u16 LE size
        ) + payload
        try {
            RealmListRewrite.parse(frame, ClientFlavors.VANILLA)
            fail("address without :port must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains(":port"))
        }
    }

    @Test
    fun `an unknown flavor id refuses fail-closed`() {
        try {
            RealmListRewrite.flavorFor("cata")
            fail("unknown flavor must refuse")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
    }

    @Test
    fun `per-flavor build blocks carry the owning registry's builds`() {
        // The pinned fixture build blocks must equal the OWNING registry's
        // build numbers (no drift between the fixture hex and ClientFlavors).
        assertEquals(ClientFlavors.TBC.build, u16LeAt(tbcFixture, tbcFixture.size - 4))
        assertEquals(ClientFlavors.WOTLK.build, u16LeAt(wotlkFixture, wotlkFixture.size - 4))
    }

    @Test
    fun `non realm-list opcodes route to passthrough`() {
        // The auth lane's other frames (0x00 logon challenge, 0x01 proof,
        // 0x08 server ping) pass through untouched.
        assertFalse(RealmListRewrite.isRealmListOpcode(0x00))
        assertFalse(RealmListRewrite.isRealmListOpcode(0x01))
        assertFalse(RealmListRewrite.isRealmListOpcode(0x08))
        assertTrue(RealmListRewrite.isRealmListOpcode(0x10))
    }

    // ---------------------------------------------------------- helpers

    /** Little-endian u16 at [offset] (the pinned-build assertions). */
    private fun u16LeAt(frame: ByteArray, offset: Int): Int =
        (frame[offset].toInt() and 0xff) or ((frame[offset + 1].toInt() and 0xff) shl 8)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    private fun bytes(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

    private fun cstr(s: String): ByteArray =
        s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
}
