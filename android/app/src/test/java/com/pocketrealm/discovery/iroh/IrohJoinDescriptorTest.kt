package com.pocketrealm.discovery.iroh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * the join descriptor — the frozen schema's `join` field, now
 * carried (never an IP), with byte-stable serialization and a fail-closed
 * admission ladder.
 */
class IrohJoinDescriptorTest {

    private val node = "a".repeat(32) + "b".repeat(32) // 64 lowercase hex

    @Test
    fun `serializes in the pinned key order`() {
        val descriptor = IrohJoinDescriptor(
            nodeHex = node,
            relayUrl = "https://usw1-1.relay.n0.iroh.link",
        )
        assertEquals(
            "{\"type\":\"iroh\",\"node\":\"$node\"," +
                "\"relay\":\"https://usw1-1.relay.n0.iroh.link\"}",
            descriptor.toJsonString(),
        )
        // Round-trips through the listing parser.
        assertEquals(
            descriptor,
            IrohJoinDescriptor.fromListingJson(JSONObject(descriptor.toJsonString())),
        )
    }

    @Test
    fun `display copy never shows a full node id`() {
        val descriptor = IrohJoinDescriptor(nodeHex = node, relayUrl = "https://r.example.relay")
        val rendered = descriptor.toString()
        assertTrue(rendered.contains(node.take(IrohJoinDescriptor.NODE_DISPLAY_CHARS)))
        assertTrue(!rendered.contains(node.take(node.length / 2)))
    }

    @Test
    fun `a non-object join refuses`() {
        for (bad in listOf(null, "iroh", 42, true)) {
            try {
                IrohJoinDescriptor.fromListingJson(bad)
                fail("non-object join must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `unknown keys refuse`() {
        val bad = JSONObject()
            .put("type", "iroh")
            .put("node", node)
            .put("relay", "https://r.example.relay")
            .put("addr", "203.0.113.9:8085") // hostile extension
        try {
            IrohJoinDescriptor.fromListingJson(bad)
            fail("unknown join keys must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun `a non-iroh type refuses`() {
        val bad = JSONObject()
            .put("type", "direct")
            .put("node", node)
            .put("relay", "https://r.example.relay")
        try {
            IrohJoinDescriptor.fromListingJson(bad)
            fail("non-iroh type must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun `node id shape is pinned to 64 lowercase hex`() {
        for (bad in listOf(
            "",
            node.dropLast(1), // 63
            node + "a", // 65
            node.uppercase(), // uppercase hex is not the wire shape
            node.dropLast(1) + "g", // non-hex
        )) {
            try {
                IrohJoinDescriptor.fromListingJson(
                    JSONObject().put("type", "iroh").put("node", bad)
                        .put("relay", "https://r.example.relay"),
                )
                fail("node shape '$bad' must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `an IP-literal relay host refuses - any spelling`() {
        for (relay in listOf(
            "https://203.0.113.9",
            "https://203.0.113.9:3340",
            "https://2130706433", // dotless decimal
            "https://0x7f000001", // hex spelling
            "http://192.168.1.10", // wrong scheme too, but the IP leg holds
            "ws://203.0.113.9:3340", // wrong scheme
        )) {
            try {
                IrohJoinDescriptor.fromListingJson(
                    JSONObject().put("type", "iroh").put("node", node).put("relay", relay),
                )
                fail("relay '$relay' must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `hostname relays pass - the self-hosted escape hatch stays open`() {
        for (relay in listOf(
            "https://usw1-1.relay.n0.iroh.link",
            "https://my-own-relay.example.com",
            "https://localhost.relay.example:3340",
            "https://relay.lan",
        )) {
            assertEquals(
                relay,
                IrohJoinDescriptor.fromListingJson(
                    JSONObject().put("type", "iroh").put("node", node).put("relay", relay),
                ).relayUrl,
            )
        }
    }

    @Test
    fun `a hex-dotted IP relay host refuses - the KDoc's any-spelling claim`() {
        // Code-red R2-3: hex spellings carry LETTERS and sailed the
        // no-letters leg — the host leg must run the same isHexAddressToken
        // rule the N1 PrivacyGuard pins ("0x" prefix + all-hex chunks).
        for (relay in listOf(
            "https://0x7f.0x00.0x00.0x01",
            "https://0x7f.0.0.1:3340",
            "https://0X7F.0X00.0X00.0X01",
        )) {
            try {
                IrohJoinDescriptor.fromListingJson(
                    JSONObject().put("type", "iroh").put("node", node).put("relay", relay),
                )
                fail("relay '$relay' is an address literal in hex spelling — must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `a non-ASCII-digit relay port refuses`() {
        // Code-red R2-4: Char.isDigit() is true for U+0663-class digits —
        // a Unicode-digit port rode a "validated" descriptor the iroh URL
        // layer would reject far from this fence. ASCII digits only.
        val unicodePort = "https://relay.example:" + "٣٣٣"
        try {
            IrohJoinDescriptor.fromListingJson(
                JSONObject()
                    .put("type", "iroh")
                    .put("node", node)
                    .put("relay", unicodePort),
            )
            fail("a Unicode-digit relay port must not validate")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun `relay port zero refuses - the range floor is real`() {
        // Code-red round-4 R2-2: port 0 is the socket WILDCARD, never a
        // relay port a client could dial — the old upper-bound-only check
        // admitted it into a signed listing.
        for (relay in listOf(
            "https://relay.example.com:0",
            "https://relay.example.com:00000",
        )) {
            try {
                IrohJoinDescriptor.fromListingJson(
                    JSONObject()
                        .put("type", "iroh")
                        .put("node", node)
                        .put("relay", relay),
                )
                fail("relay '$relay' must refuse: port below the 1..65535 range")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `relay port edges stay honest - leading zeros parse, absent passes, 65536 refuses`() {
        // R2-2's documented posture: ':0663' IS port 663 (URL-decimal
        // faithful, accepted); ':/' is the ABSENT leg (scheme default);
        // one past the ceiling still refuses.
        val accepted = IrohJoinDescriptor.fromListingJson(
            JSONObject()
                .put("type", "iroh")
                .put("node", node)
                .put("relay", "https://relay.example.com:0663"),
        )
        assertTrue("leading-zero port 0663 is port 663 — accepted", accepted != null)
        val absent = IrohJoinDescriptor.fromListingJson(
            JSONObject()
                .put("type", "iroh")
                .put("node", node)
                .put("relay", "https://relay.example.com/"),
        )
        assertTrue("':/' rides the absent-port leg — accepted", absent != null)
        try {
            IrohJoinDescriptor.fromListingJson(
                JSONObject()
                    .put("type", "iroh")
                    .put("node", node)
                    .put("relay", "https://relay.example.com:65536"),
            )
            fail("port 65536 must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun `a relay URL over the total bound refuses - many small labels cannot stack`() {
        // Code-red round-5 R6-2: the per-label caps admit a hostile
        // hostname of many <=63-char labels (~32k chars) that rode the
        // gateway child's argv and died on Windows' CreateProcess cap.
        // The TOTAL length is bounded at the descriptor fence; every
        // label here is individually legal, so the TOTAL cap is what
        // fires.
        val giant = "https://" +
            (0 until 500).joinToString(".") { "label$it" } + ".example.com"
        assertTrue(giant.length > 2048)
        try {
            IrohJoinDescriptor.fromListingJson(
                JSONObject()
                    .put("type", "iroh")
                    .put("node", node)
                    .put("relay", giant),
            )
            fail("a ${'t'}otal-bound-exceeding relay must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
            assertTrue(
                "the refusal must name the bound",
                expected.message!!.contains("2048-character bound"),
            )
        }
    }

    @Test
    fun `a long legal relay under the total bound stays admitted`() {
        // The cap is the TOTAL length, two orders above real relay URLs:
        // a ~1.9k-char multi-label hostname (all labels individually
        // legal — exactly the shape that sailed the old fence) admits.
        val body = (0 until 30).joinToString(".") { row ->
            (0 until 60).map { 'a' + ((row + it) % 26) }.joinToString("")
        }
        val relay = "https://" + body + ".example.com"
        assertTrue(relay.length in 1000..IrohJoinDescriptor.MAX_RELAY_URL_LENGTH)
        val descriptor = IrohJoinDescriptor.fromListingJson(
            JSONObject()
                .put("type", "iroh")
                .put("node", node)
                .put("relay", relay),
        )
        assertEquals(relay, descriptor.relayUrl)
    }

    @Test
    fun `malformed relay shapes refuse`() {
        for (relay in listOf(
            "",
            "https://",
            "https://one-label",
            "https://host/path/to/relay",
            "https://host:notaport",
            "https://-leadingdash.example.com",
            "https://host..example.com",
        )) {
            try {
                IrohJoinDescriptor.fromListingJson(
                    JSONObject().put("type", "iroh").put("node", node).put("relay", relay),
                )
                fail("relay '$relay' must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }
}
