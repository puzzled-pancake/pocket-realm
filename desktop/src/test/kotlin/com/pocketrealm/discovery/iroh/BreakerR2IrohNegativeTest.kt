package com.pocketrealm.discovery.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W6-N3 code-red R2 (adversarial breaker) — negative probes against the
 * shared iroh core, run on the desktop lane (the same shared sources the
 * gateway compiles):
 *
 *  1. the join descriptor's relay-host fence vs ADDRESS SPELLINGS its KDoc
 *     claims are refused ("an IP literal (any spelling ... hex) is refused");
 *  2. the rewriter's u16 size field under a hostile oversized frame (a
 *     realmd frame is bounded by its own u16 size — can a legal INPUT frame
 *     rewrite into one whose recomputed size no longer fits u16?);
 *  3. the "verbatim except the address" byte-fidelity promise for non-ASCII
 *     realm-name bytes (US-ASCII decode/encode round trip);
 *  4. the cross-flavor misparse impossibility (trailer constants);
 *  5. the zero-size realm-list frame refusal at the stream filter;
 *  6. the address-shape ladder's port/host legs.
 */
class BreakerR2IrohNegativeTest {

    private val node = "ab".repeat(32)

    // --------------------------------------------- 1. relay-host spellings

    @Test
    fun `a hex-dotted IP relay host refuses - the KDoc's any-spelling claim`() {
        for (relay in listOf(
            "https://0x7f.0x00.0x00.0x01",
            "https://0x7f.0.0.1:3340",
            "https://0X7F.0X00.0X00.0X01",
        )) {
            try {
                IrohJoinDescriptor.fromListingJson(
                    org.json.JSONObject()
                        .put("type", "iroh")
                        .put("node", node)
                        .put("relay", relay),
                )
                fail("relay '$relay' is an address literal in hex spelling — must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "wrong refusal for $relay: ${expected.message}",
                    expected.message!!.contains("VAL-IROH-JOIN"),
                )
            }
        }
    }

    @Test
    fun `a non-ASCII-digit relay port refuses`() {
        // Char.isDigit() is true for Unicode digits (e.g. U+0663) — the port
        // leg must be ASCII-only or a URL the iroh layer will reject rides a
        // validated descriptor.
        val relay = "https://relay.example:\u0663\u0664\u0665"
        try {
            IrohJoinDescriptor.fromListingJson(
                org.json.JSONObject()
                    .put("type", "iroh")
                    .put("node", node)
                    .put("relay", relay),
            )
            fail("a Unicode-digit relay port must not validate: $relay")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("VAL-IROH-JOIN"))
        }
    }

    // --------------------------------------- 2. the u16 size-field ceiling

    @Test
    fun `a legal input frame never rewrites past the u16 size field`() {
        // One vanilla entry whose name C-string pads the frame near the u16
        // ceiling and whose address is minimal ("a:1", the shortest shape
        // validateAddressShape accepts): the rewrite GROWS the payload by
        // 11 bytes (the loopback target is longer), and the recomputed u16
        // must still name the real payload — never a silent wrap.
        val nameBytes = ByteArray(65_505) { 'A'.code.toByte() } + 0
        val addr = "a:1".toByteArray(Charsets.US_ASCII) + 0
        val payload = byteArrayOf(
            0, 0, 0, 0, // u32 LE header word
            0x01, // u8 count
            0, 0, 0, 0, // u32 LE icon
            0x20, // flags
        ) + nameBytes + addr + byteArrayOf(
            0x00, 0x00, 0x00, 0x3f, // f32 LE population
            0x03, 0x01, 0x00, // chars, category, unk
            0x02, 0x00, // u16 LE trailer
        )
        assertTrue(
            "fixture payload must fit the u16 the wire declares",
            payload.size <= 0xffff,
        )
        val frame = byteArrayOf(
            0x10,
            (payload.size and 0xff).toByte(),
            ((payload.size shr 8) and 0xff).toByte(),
        ) + payload

        // Round-1 R2-2 remedy: the grown payload crossing the ceiling is a
        // LOUD refusal (never a masked wrap, never a rewritten frame whose
        // declared size lies about the real payload).
        try {
            RealmListRewrite.rewrite(
                frame, com.pocketrealm.client.ClientFlavors.VANILLA,
                RealmListRewrite.JOINER_WORLD_TARGET,
            )
            fail(
                "the rewrite grew past the u16 ceiling and was accepted — " +
                    "the declared size would wrap and desync the client",
            )
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("u16 size field"))
        }
    }

    // --------------------------------- 3. name byte fidelity (verbatim EXCEPT address)

    @Test
    fun `non-ASCII realm name bytes are copied verbatim`() {
        // "Realm" + UTF-8 Ä (0xc3 0x84): the rewriter promises every byte
        // verbatim except the address C-string. US-ASCII decode/encode would
        // silently map both bytes to '?' (0x3f) — same length, corrupted name.
        val nameBytes = "Realm".toByteArray(Charsets.US_ASCII) + 0xc3.toByte() + 0x84.toByte() + 0
        val addr = "203.0.113.7:8085".toByteArray(Charsets.US_ASCII) + 0
        val payload = byteArrayOf(
            0, 0, 0, 0,
            0x01,
            0, 0, 0, 0,
            0x20,
        ) + nameBytes + addr + byteArrayOf(
            0x00, 0x00, 0x00, 0x3f,
            0x03, 0x01, 0x00,
            0x02, 0x00,
        )
        val frame = byteArrayOf(
            0x10,
            (payload.size and 0xff).toByte(),
            ((payload.size shr 8) and 0xff).toByte(),
        ) + payload

        val rewritten = RealmListRewrite.rewrite(
            frame, com.pocketrealm.client.ClientFlavors.VANILLA,
            RealmListRewrite.JOINER_WORLD_TARGET,
        )
        // The rebuilt frame must carry the name bytes verbatim.
        val nameStart = 3 + 4 + 1 + 4 + 1 // header + header word + count + icon + flags
        for (i in nameBytes.indices) {
            assertEquals(
                "name byte $i was rewritten (only the address may change)",
                nameBytes[i],
                rewritten[nameStart + i],
            )
        }
    }

    // ------------------------------- 4. cross-flavor misparse impossibility

    @Test
    fun `every wrong-flavor parse refuses - the trailer pins the layout`() {
        val vanilla = vanillaFrame(name = "Pocket Realm", address = "203.0.113.7:8085")
        val modern = modernFrame(name = "Pocket Realm", address = "203.0.113.7:8085")
        val flavors = listOf(
            com.pocketrealm.client.ClientFlavors.VANILLA,
            com.pocketrealm.client.ClientFlavors.TBC,
            com.pocketrealm.client.ClientFlavors.WOTLK,
        )
        for ((frame, home) in listOf(vanilla to "vanilla", modern to "modern")) {
            for (flavor in flavors) {
                val homeFlavor = when (home) {
                    "vanilla" -> com.pocketrealm.client.ClientFlavors.VANILLA
                    else -> flavor.takeIf { it != com.pocketrealm.client.ClientFlavors.VANILLA }
                        ?: com.pocketrealm.client.ClientFlavors.TBC
                }
                if (RealmListRewrite.isVanillaLayout(flavor) == RealmListRewrite.isVanillaLayout(homeFlavor)) {
                    continue // the honest pairing parses fine
                }
                try {
                    RealmListRewrite.parse(frame, flavor)
                    fail(
                        "a $home-layout frame parsed under ${flavor.flavorId} — " +
                            "cross-flavor misparse must refuse",
                    )
                } catch (expected: RealmListRewrite.RealmListRewriteException) {
                    assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
                }
            }
        }
    }

    // --------------------------- 5. the zero-size realm-list frame refusal

    @Test
    fun `a zero-size realm-list frame fails loudly at the filter`() {
        // Code-red R5-1 remedy: the phase machine only arms the loud
        // refusal AFTER the raw auth dance (pre-auth, a 0x10 byte is
        // indistinguishable from SRP data and passes through by design).
        val filter = AuthStreamFilter(com.pocketrealm.client.ClientFlavors.WOTLK)
        // The REAL auth dance first (raw challenge + raw proof responses —
        // AuthSocket.cpp:489-543/:135-143): FRAMED is where the loud
        // zero-size refusal is armed.
        filter.feed(
            byteArrayOf(0x00, 0x00) + ByteArray(32) + byteArrayOf(0x01, 0x07) +
                byteArrayOf(32) + ByteArray(32) + ByteArray(32) + ByteArray(16) +
                byteArrayOf(0x00),
        )
        filter.feed(
            byteArrayOf(0x01, 0x00) + ByteArray(20) + ByteArray(4) +
                ByteArray(4) + byteArrayOf(0, 0),
        ) // 32 bytes: cmd+err+M2(20)+accountFlags+surveyId+unkFlags
        try {
            filter.feed(byteArrayOf(0x10, 0x00, 0x00))
            fail("a zero-size realm-list frame must refuse, never sit pending")
        } catch (expected: RealmListRewrite.RealmListRewriteException) {
            assertTrue(expected.message!!.contains("VAL-IROH-REALMLIST"))
        }
    }

    // ----------------------------------- 6. the address-shape ladder's legs

    @Test
    fun `address shapes a hostile host might send refuse`() {
        for (bad in listOf(
            "",
            "host",
            "host:",
            ":8085",
            "host:808599", // 6-digit port
            "host:80 5", // space in port
            "host\u0001:8085", // non-printable host byte
        )) {
            try {
                RealmListRewrite.validateAddressShape(bad, ::refuseFail)
                fail("address shape '$bad' must refuse")
            } catch (expected: IllegalStateException) {
                // the fail continuation threw — the refusal fired
            }
        }
        // The shortest legal shape passes (the rewriter's own growth bound).
        RealmListRewrite.validateAddressShape("a:1", ::refuseFail)
    }

    /** The (String) -> Nothing fail continuation the shape ladder takes
     * (mirrors the production refusal: an IllegalStateException). */
    private fun refuseFail(reason: String): Nothing = throw IllegalStateException(reason)

    // ------------------------------------------------------------- helpers

    private fun vanillaFrame(name: String, address: String): ByteArray {
        val payload = byteArrayOf(
            0, 0, 0, 0, 0x01,
            0, 0, 0, 0, 0x20,
        ) + cstr(name) + cstr(address) + byteArrayOf(
            0x00, 0x00, 0x00, 0x3f, 0x03, 0x01, 0x00, 0x02, 0x00,
        )
        return framed(payload)
    }

    private fun modernFrame(name: String, address: String): ByteArray {
        val payload = byteArrayOf(
            0, 0, 0, 0, 0x01, 0x00,
            0x00, 0x00, 0x24,
        ) + cstr(name) + cstr(address) + byteArrayOf(
            0x00, 0x00, 0x00, 0x3f, 0x03, 0x01, 0x2c,
            0x03, 0x03, 0x05, 0x34, 0x30,
            0x10, 0x00,
        )
        return framed(payload)
    }

    private fun cstr(s: String): ByteArray =
        s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private fun framed(payload: ByteArray): ByteArray = byteArrayOf(
        0x10,
        (payload.size and 0xff).toByte(),
        ((payload.size shr 8) and 0xff).toByte(),
    ) + payload
}
