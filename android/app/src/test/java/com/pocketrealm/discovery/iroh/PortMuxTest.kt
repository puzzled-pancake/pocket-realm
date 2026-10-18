package com.pocketrealm.discovery.iroh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** W6-N3: the port-mux framing units (port id + raw bytes). */
class PortMuxTest {

    @Test
    fun `header encodes big-endian port ids`() {
        assertArrayEquals(byteArrayOf(0x0e.toByte(), 0x8c.toByte()), PortMux.encodeHeader(GatewayContract.PORT_REALM))
        assertArrayEquals(byteArrayOf(0x1f.toByte(), 0x95.toByte()), PortMux.encodeHeader(GatewayContract.PORT_WORLD))
        // Round-trips.
        assertEquals(
            GatewayContract.PORT_REALM,
            PortMux.decodePort(PortMux.encodeHeader(GatewayContract.PORT_REALM)),
        )
        assertEquals(
            GatewayContract.PORT_WORLD,
            PortMux.decodePort(PortMux.encodeHeader(GatewayContract.PORT_WORLD)),
        )
    }

    @Test
    fun `the mux carries exactly the two game ports`() {
        // The two constants restate the owning tables' (round-4 R1-r4-1:
        // the cross-spelling pin is IrohJoinGuardsTest.
        // `the shared contract's ports are the game's fixed ports` on the
        // desktop lane, whose classpath carries every owning tree).
        assertTrue(PortMux.isAllowedPort(GatewayContract.PORT_REALM))
        assertTrue(PortMux.isAllowedPort(GatewayContract.PORT_WORLD))
        assertFalse(PortMux.isAllowedPort(3725))
        assertFalse(PortMux.isAllowedPort(8080))
        // Round-6 R6-2-1: port 0 is the RESERVED keep-alive lane (the
        // joiner's loss probe) — allowed at the mux, never a game lane.
        assertTrue(PortMux.isAllowedPort(GatewayContract.PORT_KEEPALIVE))
        assertFalse(PortMux.isAllowedPort(-1))
        assertFalse(PortMux.isAllowedPort(65536))
    }

    @Test
    fun `an unknown port refuses at encode and decode`() {
        // Round-6 R6-2-1: port 0 is the RESERVED keep-alive lane (allowed
        // at the mux, never a game lane) — the refused set drops it.
        for (port in listOf(1, 2, 80, 443, 3725, 8080, 65535)) {
            try {
                PortMux.encodeHeader(port)
                fail("encode port $port must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("VAL-IROH-MUX"))
            }
            try {
                PortMux.decodePort(byteArrayOf((port shr 8).toByte(), port.toByte()))
                fail("decode port $port must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("VAL-IROH-MUX"))
            }
        }
    }

    @Test
    fun `a truncated header refuses`() {
        for (n in 0 until PortMux.HEADER_BYTES) {
            try {
                PortMux.decodePort(ByteArray(n))
                fail("short header of $n bytes must refuse")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("VAL-IROH-MUX"))
            }
        }
    }
}
