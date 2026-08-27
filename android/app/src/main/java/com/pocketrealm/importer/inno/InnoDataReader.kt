package com.pocketrealm.importer.inno

import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Little-endian cursor over an Inno Setup header stream: fixed-width scalars,
 * length-prefixed strings in the installer's codepage, one-byte enums and the
 * packed bitfields Inno writes as one byte per eight flags (LSB first, with
 * three-byte sets padded to four). Everything reads exactly or throws — a
 * field drift anywhere fails the parse instead of misframing the stream.
 */
class InnoDataReader(private val input: InputStream, val charset: Charset) {

    fun u8(): Int {
        val v = input.read()
        if (v < 0) throw EOFException("unexpected end of Inno header stream")
        return v
    }

    fun bool(): Boolean = u8() != 0

    fun u16(): Int = u8() or (u8() shl 8)

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long = u32Low().toLong() and 0xffffffffL

    private fun u32Low(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)

    fun i32(): Int = u32Low()

    fun u64(): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or (u8().toLong() shl (8 * i))
        }
        return value
    }

    fun i64(): Long = u64()

    fun bytes(count: Int): ByteArray {
        require(count >= 0)
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = input.read(out, done, count - done)
            if (n < 0) throw EOFException("unexpected end of Inno header stream")
            done += n
        }
        return out
    }

    /** u32-length-prefixed byte string, undecoded. */
    fun binaryString(): ByteArray {
        val length = u32().toInt()
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw InnoFormatException("implausible string length $length in Inno header")
        }
        return bytes(length)
    }

    /** Binary string decoded in the active charset (the Unicode builds are UTF-16LE). */
    fun encodedString(): String = String(binaryString(), charset)

    fun skip(bytes: Long) {
        var left = bytes
        while (left > 0) {
            if (input.read() < 0) throw EOFException("unexpected end of Inno header stream")
            left--
        }
    }

    /**
     * One-byte stored enum. Inno's own extractor warns and substitutes a
     * default on out-of-range values; we fail closed — a drifted layout must
     * not silently misparse the rest of the stream.
     */
    fun enum(maxValue: Int): Int {
        val v = u8()
        if (v > maxValue) throw InnoFormatException("out-of-range enum value $v (max $maxValue)")
        return v
    }

    /**
     * Reads a packed bitfield of [bitCount] bits and hands each declared bit
     * to [consumer] in declaration order. Mirrors the upstream lazy reader,
     * including the three-byte-pad rule, without materializing an enum.
     */
    fun flags(bitCount: Int, consumer: (index: Int, set: Boolean) -> Unit) {
        require(bitCount > 0)
        val bytes = (bitCount + 7) / 8
        var current = 0
        var remaining = 0
        for (bit in 0 until bitCount) {
            if (remaining == 0) {
                current = u8()
                remaining = 8
            }
            consumer(bit, (current and 1) != 0)
            current = current ushr 1
            remaining--
        }
        // Three-byte sets are padded to four.
        if (bytes == 3) u8()
    }

    /** The Inno windows_version_range: 20 bytes for 5.x streams. */
    fun windowsVersionRange() = skip(20)

    companion object {
        private const val MAX_STRING_BYTES = 64 shl 20
    }
}
