package com.pocketrealm.importer.inno

import java.io.EOFException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import org.tukaani.xz.LZMAInputStream

/**
 * Inno Setup compressed-block streams. A block is
 * `[u32 headerCRC][u32 storedSize][u8 compressedFlag]` followed by
 * `storedSize` bytes of `[u32 crc32][<=4096B]` frames wrapping the compressed
 * bytes; the LZMA1 variant starts with the classic five-byte props/dict-size
 * header and carries no end marker (the block length is the framing).
 * Every CRC is verified while reading; corruption fails closed.
 */
internal object InnoBlockReader {

    const val COMPRESSION_STORED = 0
    const val COMPRESSION_ZLIB = 1
    const val COMPRESSION_LZMA1 = 2

    /** A slice-agnostic positioned source over setup.exe or a slice file. */
    interface Source : AutoCloseable {
        /** Reads up to [len] bytes at [position]; returns the count read. */
        fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int
    }

    class RandomFileSource(private val file: RandomAccessFile) : Source {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            file.seek(position)
            return file.read(buffer, offset, len)
        }

        override fun close() = file.close()
    }

    /**
     * Opens the decompressed stream for the block at [blockOffset]. The
     * returned stream ends exactly at the block's uncompressed end; the
     * caller advances to the next block via [nextBlockOffset].
     */
    fun open(source: Source, blockOffset: Long): Pair<InputStream, Long> {
        val header = ByteArray(9)
        readFully(source, blockOffset, header)
        val expectedCrc = le32(header, 0)
        val storedSize = le32(header, 4).toInt()
        val compressedFlag = header[8].toInt() and 0xff
        if (storedSize < 0 || storedSize > MAX_STORED_BYTES) {
            throw InnoFormatException("implausible block size $storedSize")
        }
        val crc = CRC32()
        crc.update(header, 4, 5)
        if (crc.value != expectedCrc) {
            throw InnoFormatException("block header CRC mismatch")
        }
        // Every supported data version (>= 4.1.6) maps flag 1 to LZMA1; the
        // zlib mapping only exists in older layouts we reject at the version
        // gate.
        val compression = when (compressedFlag) {
            0 -> COMPRESSION_STORED
            1 -> COMPRESSION_LZMA1
            else -> throw InnoFormatException("unknown block compression flag $compressedFlag")
        }
        val framed = FramedInput(source, blockOffset + 9, storedSize.toLong())
        val stream: InputStream = when (compression) {
            COMPRESSION_STORED -> framed
            COMPRESSION_ZLIB -> InflaterInputStream(framed, Inflater(), 1)
            else -> lzma1(framed)
        }
        return stream to blockOffset + 9 + storedSize.toLong()
    }

    /** LZMA1 with Inno's framing: props byte + LE32 dict, no end marker. */
    fun lzma1(framed: InputStream): InputStream {
        val header = ByteArray(5)
        var done = 0
        while (done < 5) {
            val n = framed.read(header, done, 5 - done)
            if (n < 0) throw EOFException("truncated LZMA1 header")
            done += n
        }
        val props = header[0].toInt() and 0xff
        if (props > 9 * 5 * 5) throw InnoFormatException("invalid LZMA1 properties byte")
        val dictSize = (header[1].toLong() and 0xff) or
            ((header[2].toLong() and 0xff) shl 8) or
            ((header[3].toLong() and 0xff) shl 16) or
            ((header[4].toLong() and 0xff) shl 24)
        if (dictSize == 0L || dictSize > MAX_DICT_BYTES) {
            throw InnoFormatException("implausible LZMA1 dictionary size $dictSize")
        }
        // uncompSize -1 lets the decoder run until the framing is exhausted;
        // the block CRCs and the per-file digests verify the result.
        return LZMAInputStream(framed, -1L, header[0], dictSize.toInt(), null)
    }

    /** Reads exactly [len] bytes at [position] into [buffer]. */
    fun readFully(source: Source, position: Long, buffer: ByteArray, offset: Int = 0, len: Int = buffer.size) {
        var done = 0
        while (done < len) {
            val n = source.readAt(position + done, buffer, offset + done, len - done)
            if (n < 0) throw EOFException("unexpected end of Inno data")
            done += n
        }
    }

    private fun le32(buffer: ByteArray, offset: Int): Long =
        (buffer[offset].toLong() and 0xff) or
            ((buffer[offset + 1].toLong() and 0xff) shl 8) or
            ((buffer[offset + 2].toLong() and 0xff) shl 16) or
            ((buffer[offset + 3].toLong() and 0xff) shl 24)

    /**
     * Stream over the framed stored bytes: yields frame payloads only, after
     * verifying each frame's CRC32. Reads never cross [storedSize].
     */
    private class FramedInput(
        private val source: Source,
        private val start: Long,
        private val storedSize: Long,
    ) : InputStream() {
        private var position = start
        private var remaining = storedSize
        private var buffer = ByteArray(0)
        private var bufferAt = 0

        private fun fill() {
            if (bufferAt < buffer.size) return
            if (remaining == 0L) return
            if (remaining < 5L) throw InnoFormatException("trailing bytes at end of Inno block")
            val header = ByteArray(4)
            readFully(source, position, header)
            position += 4
            remaining -= 4
            val payload = minOf(FRAME_BYTES.toLong(), remaining).toInt()
            buffer = ByteArray(payload)
            readFully(source, position, buffer)
            position += payload
            remaining -= payload
            val crc = CRC32()
            crc.update(buffer)
            if (crc.value != le32(header, 0)) {
                throw InnoFormatException("Inno block frame CRC mismatch")
            }
            bufferAt = 0
        }

        override fun read(): Int {
            fill()
            if (bufferAt >= buffer.size) return -1
            return buffer[bufferAt++].toInt() and 0xff
        }

        override fun read(dest: ByteArray, off: Int, len: Int): Int {
            fill()
            var done = 0
            while (done < len && bufferAt < buffer.size) {
                dest[off + done] = buffer[bufferAt++]
                done++
                // Refill mid-call only after draining the current frame.
                if (bufferAt >= buffer.size && done < len) fill()
            }
            return if (done == 0) -1 else done
        }
    }

    private const val FRAME_BYTES = 4096
    private const val MAX_STORED_BYTES = 64 shl 20
    private const val MAX_DICT_BYTES = 64L shl 20
}
