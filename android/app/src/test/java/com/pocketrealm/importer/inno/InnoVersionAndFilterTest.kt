package com.pocketrealm.importer.inno

import com.pocketrealm.importer.SyntheticInnoInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version-signature and x86 call-filter unit tests. */
class InnoVersionAndFilterTest {

    private fun signature(text: String): ByteArray {
        val buffer = ByteArray(64)
        text.toByteArray(Charsets.ISO_8859_1).copyInto(buffer)
        return buffer
    }

    @Test fun parsesKnownAnsiAndUnicodeSignatures() {
        assertEquals("5.3.5", InnoVersion.parse(signature("Inno Setup Setup Data (5.3.5)")).toString())
        assertEquals(
            "5.3.5 (unicode)",
            InnoVersion.parse(signature("Inno Setup Setup Data (5.3.5) (u)")).toString(),
        )
        assertEquals("5.0.0", InnoVersion.parse(signature("Inno Setup Setup Data (5.0.0)")).toString())
        assertEquals("5.5.6", InnoVersion.parse(signature("Inno Setup Setup Data (5.5.6)")).toString())
    }

    @Test fun rejectsUnsupportedAndForeignSignatures() {
        assertThrows(InnoFormatException::class.java) {
            InnoVersion.parse(signature("Inno Setup Setup Data (4.1.6)"))
        }
        assertThrows(InnoFormatException::class.java) {
            InnoVersion.parse(signature("Inno Setup Setup Data (6.0.0) (u)"))
        }
        assertThrows(InnoFormatException::class.java) {
            InnoVersion.parse(signature("i1.2.10--16\u001a"))
        }
        assertThrows(InnoFormatException::class.java) {
            InnoVersion.parse(signature("Some Other Installer Format (5.3.5)"))
        }
    }

    @Test fun callFilterRoundTripsThroughEncodeAndDecode() {
        // Synthetic x86-ish stream: opcodes, plausible addresses (high byte
        // 00/ff and otherwise), and instructions crossing a 64 KiB edge.
        val raw = ByteArray(0x20000 + 64) { ((it * 31) and 0xff).toByte() }
        var i = 16
        while (i < raw.size - 8) {
            raw[i] = if (i % 64 < 32) 0xe8.toByte() else 0xe9.toByte()
            raw[i + 4] = if (i % 128 == 0) 0x77.toByte() else 0xff.toByte()
            i += 9
        }
        val encoded = SyntheticInnoInstaller.encodeCallFilter(raw)
        assertTrue(encoded.contentEquals(raw) == false || raw.size == 0)

        val decoded = java.io.ByteArrayInputStream(encoded).use { input ->
            InnoCallFilterInputStream(input, InnoCallFilterInputStream.Variant.SINCE_5_2_0)
                .use { it.readBytes() }
        }
        // Sites whose high byte is neither 00 nor ff pass through untouched,
        // so the encoder must have left those sites alone too — round trip.
        assertTrue(decoded.contentEquals(raw))
    }

    @Test fun callFilterChangesHighByte00Sites() {
        val raw = byteArrayOf(
            0x11, 0xe8.toByte(), 0x10, 0x00, 0x00, 0x00, 0x22,
            0xe9.toByte(), 0x05, 0x00, 0x00, 0xff.toByte(), 0x33,
        )
        val encoded = SyntheticInnoInstaller.encodeCallFilter(raw)
        // First site: opcode at 1, so rel 0x10 + (1+5) = 0x16.
        assertEquals(0x16, encoded[2].toInt() and 0xff)
        assertEquals(0, encoded[3].toInt() and 0xff)
        // Second site: opcode at 7, so rel 5 + (7+5) = 0x11; high stays 0xff.
        assertEquals(0x11, encoded[8].toInt() and 0xff)
        assertEquals(0xff, encoded[11].toInt() and 0xff)
        val decoded = java.io.ByteArrayInputStream(encoded).use { input ->
            InnoCallFilterInputStream(input, InnoCallFilterInputStream.Variant.SINCE_5_2_0)
                .use { it.readBytes() }
        }
        assertTrue(decoded.contentEquals(raw))
    }
}
