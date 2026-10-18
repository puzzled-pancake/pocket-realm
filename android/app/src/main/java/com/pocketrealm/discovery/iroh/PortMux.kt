@file:Suppress("MagicNumber")
/*
 * MagicNumber suppression (reviewed): the BE assembly masks and the port
 * ranges ARE this protocol's wire shape (the two allowed ports are named
 * constants on GatewayContract).
 */
package com.pocketrealm.discovery.iroh

/**
 * W6-N3: the port mux framing — how ONE iroh endpoint carries both game
 * lanes. Every accepted bi-stream opens with a 2-byte big-endian port id
 * ([GatewayContract.PORT_REALM] or [GatewayContract.PORT_WORLD]); after
 * that prefix the stream is RAW game bytes both directions (no further
 * framing — the game protocols own their wire). This is the exact "port id
 * + raw bytes framing" the plan's stage 3 names.
 *
 * Pure functions: the framing units run on both lanes (shared manifest) and
 * inside the gateway process; the D92 lock-step finding does not apply here
 * (no length-prefixed chunks after the header — pumps copy raw bytes).
 */
object PortMux {

    /** The header length: one u16 BE port id. */
    const val HEADER_BYTES: Int = 2

    /** The BE assembly's shift width and byte mask. */
    private const val BYTE_HIGH_SHIFT = 8
    private const val BYTE_MASK = 0xff

    /** Encodes the opening header for [port]. Refuses non-game ports at
     * construction (fail-closed — a future third port is a contract bump,
     * not a value someone passes by accident). */
    fun encodeHeader(port: Int): ByteArray {
        require(isAllowedPort(port)) {
            "VAL-IROH-MUX: port $port is not a muxed game port"
        }
        return byteArrayOf(
            ((port ushr BYTE_HIGH_SHIFT) and BYTE_MASK).toByte(),
            (port and BYTE_MASK).toByte(),
        )
    }

    /** Decodes the port id from the first [HEADER_BYTES] bytes of a stream.
     * Throws VAL-IROH-MUX on a short header or an unknown port — the caller
     * closes the stream (an unknown port is a foreign client or a version
     * mismatch, never a lane to guess). */
    fun decodePort(header: ByteArray): Int {
        require(header.size >= HEADER_BYTES) {
            "VAL-IROH-MUX: mux header truncated (${header.size} bytes)"
        }
        val port = ((header[0].toInt() and BYTE_MASK) shl BYTE_HIGH_SHIFT) or
            (header[1].toInt() and BYTE_MASK)
        require(isAllowedPort(port)) {
            "VAL-IROH-MUX: unknown mux port $port"
        }
        return port
    }

    /** True for the two game lanes and the reserved keep-alive lane
     * (round-6 R6-2-1: the joiner's loss probe — never a game lane). */
    fun isAllowedPort(port: Int): Boolean =
        port == GatewayContract.PORT_KEEPALIVE ||
            port == GatewayContract.PORT_REALM ||
            port == GatewayContract.PORT_WORLD
}
