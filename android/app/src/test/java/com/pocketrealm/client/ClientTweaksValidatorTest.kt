package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for the pure vanilla-tweaks decision helpers
 * [ClientTweaksConfig.peMagicOk] and [ClientTweaksConfig.firstLocaleMismatch]
 * — the PE-sanity + locale-guard logic that wraps the device-only patcher
 * binary. Driven by a synthetic PE and the qualified enUS-5875 diagnostic
 * signature, while runtime authorization remains the complete executable hash.
 */
class ClientTweaksValidatorTest {

    private fun syntheticPe(planted: Map<Int, Byte>, size: Int = 0x500_000): ByteArray {
        val bytes = ByteArray(size)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        planted.forEach { (off, value) -> if (off in bytes.indices) bytes[off] = value }
        return bytes
    }

    @Test fun `peMagicOk accepts a valid MZ image`() {
        assertTrue(ClientTweaksConfig.peMagicOk(syntheticPe(emptyMap())))
    }

    @Test fun `peMagicOk rejects an undersized buffer`() {
        // Valid MZ magic but below the 0x40 floor.
        assertFalse(ClientTweaksConfig.peMagicOk(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte())))
    }

    @Test fun `peMagicOk rejects a non-MZ image`() {
        val bytes = syntheticPe(emptyMap())
        bytes[0] = 'X'.code.toByte()
        assertFalse(ClientTweaksConfig.peMagicOk(bytes))
    }

    @Test fun `firstLocaleMismatch returns null when every expected offset matches`() {
        val expected = mapOf(
            0x3A4869 to 0x27.toByte(),          // sound-in-background
            0x435d38 to '6'.code.toByte(),      // sound channels string "64"
        )
        val pe = syntheticPe(expected)
        assertNull(ClientTweaksConfig.firstLocaleMismatch(pe, expected))
    }

    @Suppress("DEPRECATION")
    @Test fun `qualified build signature contains every principal patch site`() {
        val expected = ClientTweaksConfig.expectedOriginalBytes()
        assertTrue(expected.isNotEmpty())
        assertEquals(0x14.toByte(), expected[0x3A4869])
        assertEquals(0x74.toByte(), expected[0x0C1ECF])
        assertEquals(0x74.toByte(), expected[0x0C2B25])
        assertEquals('1'.code.toByte(), expected[0x435D38])
        assertEquals('2'.code.toByte(), expected[0x435D39])
    }

    @Test fun `firstLocaleMismatch flags the first wrong byte (locale guard)`() {
        val expected = mapOf(0x3A4869 to 0x27.toByte(), 0x435d38 to '6'.code.toByte())
        val pe = syntheticPe(expected)
        // Corrupt the sound-in-background offset — exactly what a non-enUS
        // 5875 build would look like at this fixed offset. The guard must
        // refuse to patch rather than overwrite blind.
        pe[0x3A4869] = 0x00
        assertEquals(0x3A4869, ClientTweaksConfig.firstLocaleMismatch(pe, expected))
    }

    @Test fun `firstLocaleMismatch treats an out-of-range offset as a mismatch`() {
        val expected = mapOf(
            0x3A4869 to 0x27.toByte(),
            0x5000_0000 to 0x00.toByte(),       // beyond any plausible PE size
        )
        val pe = syntheticPe(mapOf(0x3A4869 to 0x27.toByte()))
        assertEquals(0x5000_0000, ClientTweaksConfig.firstLocaleMismatch(pe, expected))
    }
}
