package com.pocketrealm.importer.inno

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * The `setup-N.bin` slice files of an external Inno data set. Each slice is
 * `[8-byte magic "idska16\x1a"|"idska32\x1a"][u32 logical slice size]` with
 * data from offset 12; compressed chunk streams read sequentially and run off
 * the end of one slice into the next. Slice names come from the installer
 * executable's own stem first, then the header's base filename, matched
 * case-insensitively (the way the upstream extractor resolves them).
 */
internal class InnoSliceSet(
    private val directory: File,
    private val stems: List<String>,
    private val slicesPerDisk: Int,
) : AutoCloseable {

    private val open = HashMap<Int, RandomAccessFile>()
    private val sizes = HashMap<Int, Long>()

    init {
        require(stems.isNotEmpty()) { "at least one slice stem required" }
        require(slicesPerDisk >= 1) { "slicesPerDisk must be >= 1" }
    }

    fun sliceFile(index: Int): RandomAccessFile {
        open[index]?.let { return it }
        for (stem in stems) {
            val candidate = File(directory, sliceName(stem, index))
            val resolved = resolveCaseInsensitive(directory, candidate.name) ?: continue
            val file = RandomAccessFile(resolved, "r")
            validate(resolved, file)
            open[index] = file
            return file
        }
        throw InnoFormatException("missing Inno slice ${sliceName(stems.first(), index)}")
    }

    /** Logical data size of a slice (everything after the 12-byte header). */
    fun sliceDataSize(index: Int): Long = sizes.getOrPut(index) {
        val file = sliceFile(index)
        file.seek(SIZE_FIELD_OFFSET)
        val logical = file.readUnsigned4()
        if (logical < SLICE_HEADER_BYTES || logical > file.length()) {
            throw InnoFormatException("implausible size in Inno slice $index")
        }
        logical
    }

    /**
     * A sequential stream starting at [offset] of [slice] that continues into
     * the following slices as needed. The caller bounds consumption to the
     * chunk's compressed size.
     */
    fun stream(slice: Int, offset: Long): InputStream = object : InputStream() {
        private var current: RandomAccessFile? = null
        private var currentSlice = -1
        private var position = 0L
        private var limit = 0L

        private fun advance(): Boolean {
            var index = if (currentSlice < 0) slice else currentSlice + 1
            var start = if (currentSlice < 0) offset else SLICE_HEADER_BYTES
            while (true) {
                val size = sliceDataSize(index)
                when {
                    start < size -> {
                        current = sliceFile(index).also { it.seek(start) }
                        currentSlice = index
                        position = start
                        limit = size
                        return true
                    }
                    // Zero-length (or exactly consumed) slices simply move on
                    // to the next one; a missing slice file ends the walk.
                    start == size -> {
                        index++
                        start = SLICE_HEADER_BYTES
                    }
                    else -> throw InnoFormatException("read past end of Inno slice $index")
                }
            }
        }

        override fun read(): Int {
            if (current == null || position >= limit) {
                if (!advance()) return -1
            }
            val value = current!!.read()
            if (value < 0) throw InnoFormatException("unexpected EOF inside Inno slice $currentSlice")
            position++
            return value
        }

        override fun read(dest: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var done = 0
            while (done < len) {
                if (current == null || position >= limit) {
                    if (!advance()) break
                }
                val take = minOf(len - done, (limit - position).toInt())
                val n = current!!.read(dest, off + done, take)
                if (n < 0) throw InnoFormatException("unexpected EOF inside Inno slice $currentSlice")
                position += n
                done += n
            }
            return if (done == 0) -1 else done
        }
    }

    private fun validate(path: File, file: RandomAccessFile) {
        val magic = ByteArray(8)
        try {
            file.readFully(magic) // member readFully throws EOFException when short
        } catch (e: EOFException) {
            throw InnoFormatException("truncated Inno slice ${path.name}")
        }
        val a16 = "idska16\u001a".toByteArray(Charsets.US_ASCII)
        val a32 = "idska32\u001a".toByteArray(Charsets.US_ASCII)
        if (!magic.contentEquals(a16) && !magic.contentEquals(a32)) {
            throw InnoFormatException("bad magic in Inno slice ${path.name}")
        }
        file.seek(0)
    }

    private fun sliceName(stem: String, index: Int): String {
        val sanitized = stem.replace('/', '_').replace('\\', '_')
        return if (slicesPerDisk == 1) {
            "$sanitized-${index + 1}.bin"
        } else {
            val major = index / slicesPerDisk + 1
            val minor = index % slicesPerDisk
            "$sanitized-$major${'a' + minor}.bin"
        }
    }

    private fun resolveCaseInsensitive(directory: File, name: String): File? {
        val direct = File(directory, name)
        if (direct.isFile) return direct
        val files = directory.list() ?: return null
        val lower = name.lowercase()
        return files.firstOrNull { it.lowercase() == lower }?.let { File(directory, it) }
    }

    override fun close() {
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }

    private companion object {
        const val SIZE_FIELD_OFFSET = 8L
        const val SLICE_HEADER_BYTES = 12L
    }
}

private fun RandomAccessFile.readUnsigned4(): Long {
    var value = 0L
    for (i in 0 until 4) {
        val byte = read()
        if (byte < 0) throw InnoFormatException("truncated slice size field")
        value = value or (byte.toLong() shl (8 * i))
    }
    return value
}
