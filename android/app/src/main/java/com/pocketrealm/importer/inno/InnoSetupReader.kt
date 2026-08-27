package com.pocketrealm.importer.inno

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Reader for extracted Inno Setup installer payloads (setup.exe plus its
 * setup-N.bin slices in one directory). Field layout follows the Inno
 * 5.0.0 – 5.5.6 data format as documented by the zlib-licensed innoextract
 * reference implementation; this is an independent Kotlin port for the
 * client import lane. Every structural layer is CRC- or MD5-verified so a
 * drifted or truncated payload fails closed.
 *
 * Header blocks are parsed from setup.exe by offset; payload files stream
 * from the slices through the chunk pipeline: framed bytes -> LZMA1/zlib/
 * stored -> solid-compression offset -> optional x86 call filter -> MD5.
 */
class InnoSetupReader private constructor(
    private val slices: InnoSliceSet,
    val version: InnoVersion,
    val appName: String,
    val appVersion: String,
    private val dataEntries: List<InnoDataEntry>,
    private val payloadFiles: List<InnoPayloadFile>,
) : AutoCloseable {

    /**
     * One installable file: the destination with the leading directory
     * constant (`{app}` and aliases) resolved, plus the backing data entry.
     */
    class InnoPayloadFile internal constructor(
        val path: String,
        val rawDestination: String,
        val size: Long,
        internal val dataEntryIndex: Int,
    )

    /** Per-file digest from the data entry: MD5 before 5.3.9, SHA-1 after. */
    class Digest internal constructor(internal val algorithm: String, internal val value: ByteArray)

    internal class InnoDataEntry(
        val firstSlice: Int,
        val chunkOffset: Long,
        val fileOffset: Long,
        val fileSize: Long,
        val chunkSize: Long,
        val digest: Digest?,
        val callFilter: InnoCallFilterInputStream.Variant?,
        internal val encrypted: Boolean,
        internal val compression: Int,
    )

    /** Payload files in chunk order (slice, chunk offset, file offset). */
    fun files(): List<InnoPayloadFile> = payloadFiles

    /**
     * Streams one payload file: opens its chunk, skips over solid-compression
     * predecessors, applies the call filter when the entry is flagged and
     * verifies the per-file MD5 at end of stream. Each call re-opens the
     * chunk from its start — fine for spot checks, quadratic for a full
     * solid payload. Use [session] for ordered bulk extraction.
     *
     * One payload stream may be active at a time (here or through a
     * [Session]): the slice set shares file pointers between streams.
     */
    fun open(file: InnoPayloadFile): InputStream = try {
        openPositioned(file, chunk = null)
    } catch (error: InnoFormatException) {
        throw error
    } catch (error: EOFException) {
        throw InnoFormatException("truncated installer payload: ${error.message}")
    } catch (error: java.io.IOException) {
        throw InnoFormatException("unreadable installer payload: ${error.message}")
    } catch (error: IllegalStateException) {
        throw InnoFormatException("corrupt installer layout: ${error.message}")
    }

    /**
     * A forward-only bulk reader over the payload. Solid Inno payloads pack
     * every file into one huge chunk, so random access is quadratic; a
     * session keeps a single decompressed chunk stream and serves files in
     * [files] order, decoding the chunk exactly once. A request behind the
     * current position (a resumed import restarting mid-chunk) transparently
     * re-opens and skips forward once. One session per reader at a time.
     */
    fun session(): Session = Session()

    inner class Session internal constructor() : AutoCloseable {
        private var chunk: CountingInputStream? = null
        private var chunkKey: Pair<Int, Long>? = null

        fun open(file: InnoPayloadFile): InputStream = try {
            openEntry(file)
        } catch (error: InnoFormatException) {
            throw error
        } catch (error: EOFException) {
            throw InnoFormatException("truncated installer payload: ${error.message}")
        } catch (error: java.io.IOException) {
            throw InnoFormatException("unreadable installer payload: ${error.message}")
        } catch (error: IllegalStateException) {
            throw InnoFormatException("corrupt installer chunk layout: ${error.message}")
        }

        private fun openEntry(file: InnoPayloadFile): InputStream {
            val entry = dataEntries[file.dataEntryIndex]
            val key = entry.firstSlice to entry.chunkOffset
            val current = chunk
            if (current == null || chunkKey != key || current.position > entry.fileOffset) {
                current?.close()
                val fresh = CountingInputStream(chunkStream(entry))
                chunk = fresh
                chunkKey = key
                return openAt(fresh, entry, file)
            }
            return openAt(current, entry, file)
        }

        override fun close() {
            chunk?.close()
            chunk = null
        }
    }

    private fun openPositioned(file: InnoPayloadFile, chunk: CountingInputStream?): InputStream {
        val entry = dataEntries[file.dataEntryIndex]
        var stream = chunk ?: CountingInputStream(chunkStream(entry))
        if (stream.position != entry.fileOffset) {
            if (stream.position > entry.fileOffset) {
                stream.close()
                stream = CountingInputStream(chunkStream(entry))
            }
            var skipped = 0L
            while (stream.position < entry.fileOffset) {
                val n = stream.skip(entry.fileOffset - stream.position)
                if (n <= 0) throw InnoFormatException("truncated chunk before file data")
                skipped += n
            }
        }
        return finishOpen(stream, entry, file)
    }

    /** Positions [chunk] at the entry's file offset (skip or reopen) and reads it. */
    private fun openAt(chunk: CountingInputStream, entry: InnoDataEntry, file: InnoPayloadFile): InputStream {
        if (chunk.position > entry.fileOffset) {
            throw IllegalStateException("chunk position regressed")
        }
        while (chunk.position < entry.fileOffset) {
            if (chunk.skip(entry.fileOffset - chunk.position) <= 0) {
                throw InnoFormatException("truncated chunk before file data")
            }
        }
        return finishOpen(chunk, entry, file)
    }

    private fun finishOpen(chunk: CountingInputStream, entry: InnoDataEntry, file: InnoPayloadFile): InputStream {
        if (entry.encrypted) throw InnoFormatException("installer payload file is encrypted")
        val bounded = BoundedInputStream(chunk, entry.fileSize)
        val filtered = entry.callFilter?.let { InnoCallFilterInputStream(bounded, it) } ?: bounded
        return DigestVerifyingInputStream(filtered, entry.digest, file.path, entry.fileSize)
    }

    private fun chunkStream(entry: InnoDataEntry): InputStream {
        val raw = slices.stream(entry.firstSlice, entry.chunkOffset)
        val magic = ByteArray(4)
        readFullyStream(raw, magic)
        if (String(magic, Charsets.US_ASCII) != "zlb\u001a") {
            throw InnoFormatException("bad chunk magic in installer payload")
        }
        val compressedBytes = BoundedInputStream(raw, entry.chunkSize)
        return when (entry.compression) {
            COMPRESSION_STORED -> compressedBytes
            COMPRESSION_ZLIB -> java.util.zip.InflaterInputStream(
                compressedBytes, java.util.zip.Inflater(), 1,
            )
            COMPRESSION_LZMA1 -> InnoBlockReader.lzma1(compressedBytes)
            else -> throw InnoFormatException("unsupported chunk compression")
        }
    }

    override fun close() = slices.close()

    companion object {
        internal const val COMPRESSION_STORED = 0
        internal const val COMPRESSION_ZLIB = 1
        internal const val COMPRESSION_BZIP2 = 2
        internal const val COMPRESSION_LZMA1 = 3
        internal const val COMPRESSION_LZMA2 = 4

        /**
         * Parses an extracted installer payload. [scratchDir] must contain
         * setup.exe and its slices, either at the top level or inside a
         * single wrapper subdirectory.
         * @throws InnoFormatException for anything unsupported or corrupt
         * (truncated or unreadable payloads included); the archive lane maps
         * this to a VAL-12/VAL-13 rejection.
         */
        fun open(scratchDir: File): InnoSetupReader = try {
            openInstaller(scratchDir)
        } catch (error: InnoFormatException) {
            throw error
        } catch (error: EOFException) {
            throw InnoFormatException("truncated installer payload: ${error.message}")
        } catch (error: java.io.IOException) {
            throw InnoFormatException("unreadable installer payload: ${error.message}")
        } catch (error: IllegalStateException) {
            throw InnoFormatException("corrupt installer layout: ${error.message}")
        }

        private fun openInstaller(scratchDir: File): InnoSetupReader {
            val setup = findSetupExe(scratchDir)
                ?: throw InnoFormatException("no setup.exe in the extracted installer payload")
            val dir = setup.parentFile
            RandomAccessFile(setup, "r").use { exe ->
                val offsets = InnoLoaderOffsets.load(exe)
                val source = InnoBlockReader.RandomFileSource(exe)
                val signature = ByteArray(64)
                InnoBlockReader.readFully(source, offsets.headerOffset, signature)
                val version = InnoVersion.parse(signature)
                val (primary, afterPrimary) = InnoBlockReader.open(source, offsets.headerOffset + 64)
                val primaryBytes = readAll(primary, MAX_HEADER_BYTES)

                // First pass with a byte-transparent charset to reach the
                // language entries and learn the installer's codepage, then a
                // full re-parse decoding strings in it (the upstream reader
                // does the same two-step).
                val probe = InnoHeaderParser(
                    InnoDataReader(primaryBytes.inputStream(), Charsets.ISO_8859_1), version,
                )
                val codepage = probe.parseLanguageCodepage()
                val charset = charsetFor(codepage, version)
                val primaryStream = primaryBytes.inputStream()
                val parser = InnoHeaderParser(
                    InnoDataReader(primaryStream, charset), version,
                )
                val header = parser.parse()
                requireFullyConsumed(primaryStream.available(), "primary")

                val (secondary, _) = InnoBlockReader.open(source, afterPrimary)
                val secondaryBytes = readAll(secondary, MAX_HEADER_BYTES)
                val secondaryStream = secondaryBytes.inputStream()
                val dataEntries = parser.parseDataEntries(
                    InnoDataReader(secondaryStream, charset),
                    header.dataEntryCount, header.compression,
                )
                requireFullyConsumed(secondaryStream.available(), "secondary")

                val stems = buildList {
                    add(setup.nameWithoutExtension)
                    if (header.baseFilename.isNotEmpty()) add(header.baseFilename)
                }
                val slices = InnoSliceSet(dir, stems, header.slicesPerDisk)
                val files = resolvePayloadFiles(header.fileEntries, dataEntries)
                return InnoSetupReader(
                    slices, version, header.appName, header.appVersionedName,
                    dataEntries, files,
                )
            }
        }

        internal fun findSetupExe(root: File): File? {
            root.listFiles()?.sortedBy { it.name }?.forEach { candidate ->
                if (candidate.isFile && candidate.name.equals("setup.exe", ignoreCase = true)) {
                    return candidate
                }
            }
            root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
                dir.listFiles()?.forEach { candidate ->
                    if (candidate.isFile && candidate.name.equals("setup.exe", ignoreCase = true)) {
                        return candidate
                    }
                }
            }
            return null
        }

        /** The upstream reader rejects trailing header bytes; so do we. */
        private fun requireFullyConsumed(remaining: Int, label: String) {
            if (remaining != 0) throw InnoFormatException("unknown data at end of $label header stream")
        }

        private fun charsetFor(codepage: Long, version: InnoVersion): java.nio.charset.Charset {
            if (version.unicode) return Charsets.UTF_16LE
            return when (codepage.toInt()) {
                0 -> Charsets.ISO_8859_1 // byte-transparent decode of an undeclared codepage
                // windows-1252 differs from Latin-1 in 0x80–0x9F; fall back to
                // the byte-transparent decode if the charset is unavailable.
                1252 -> runCatching { java.nio.charset.Charset.forName("windows-1252") }
                    .getOrElse { Charsets.ISO_8859_1 }
                1250 -> windowsCharset("windows-1250")
                1251 -> windowsCharset("windows-1251")
                1253 -> windowsCharset("windows-1253")
                1254 -> windowsCharset("windows-1254")
                1255 -> windowsCharset("windows-1255")
                1256 -> windowsCharset("windows-1256")
                1257 -> windowsCharset("windows-1257")
                1258 -> windowsCharset("windows-1258")
                else -> throw InnoFormatException(
                    "installer uses codepage $codepage, which is not supported",
                )
            }
        }

        private fun windowsCharset(name: String) =
            runCatching { java.nio.charset.Charset.forName(name) }.getOrElse {
                throw InnoFormatException("charset $name unavailable for installer strings")
            }

        internal fun resolvePayloadFiles(
            fileEntries: List<InnoHeaderParser.ParsedFileEntry>,
            dataEntries: List<InnoDataEntry>,
        ): List<InnoPayloadFile> {
            val payload = ArrayList<InnoPayloadFile>()
            for (entry in fileEntries) {
                if (entry.type != FILE_TYPE_USER) continue
                if (entry.location !in dataEntries.indices) {
                    throw InnoFormatException("file entry references missing data entry")
                }
                val path = appRelativePath(entry.destination) ?: continue
                if (path.isEmpty()) continue
                payload.add(
                    InnoPayloadFile(
                        path, entry.destination,
                        dataEntries[entry.location].fileSize, entry.location,
                    ),
                )
            }
            payload.sortWith(
                compareBy(
                    { dataEntries[it.dataEntryIndex].firstSlice },
                    { dataEntries[it.dataEntryIndex].chunkOffset },
                    { dataEntries[it.dataEntryIndex].fileOffset },
                ),
            )
            return payload
        }

        /** `{app}\WoW.exe` -> `WoW.exe`; destinations elsewhere -> null. */
        internal fun appRelativePath(destination: String): String? {
            val normalized = destination.replace('\\', '/')
            for (constant in APP_DIR_CONSTANTS) {
                if (normalized.startsWith("{$constant}/", ignoreCase = true)) {
                    return normalized.substring(constant.length + 3)
                }
            }
            return null
        }

        private const val FILE_TYPE_USER = 0
        private val APP_DIR_CONSTANTS = listOf("app", "autopf", "autoprograms")
        private const val MAX_HEADER_BYTES = 32 shl 20
    }
}

private fun readFullyStream(input: InputStream, buffer: ByteArray) {
    var done = 0
    while (done < buffer.size) {
        val n = input.read(buffer, done, buffer.size - done)
        if (n < 0) throw EOFException("truncated installer payload stream")
        done += n
    }
}

private fun readAll(input: InputStream, cap: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > cap) throw InnoFormatException("Inno header stream exceeds plausible size")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Reads at most [limit] bytes and reports EOF at the boundary. */
internal class BoundedInputStream(
    private val source: InputStream,
    private val limit: Long,
) : InputStream() {    private var served = 0L

    override fun read(): Int {
        if (served >= limit) return -1
        val value = source.read()
        if (value >= 0) served++
        return value
    }

    override fun read(dest: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (served >= limit) return -1
        val take = minOf(len.toLong(), limit - served).toInt()
        val n = source.read(dest, off, take)
        if (n > 0) served += n
        return n
    }

    override fun skip(n: Long): Long {
        if (served >= limit) return 0
        val take = minOf(n, limit - served)
        var skipped = 0L
        while (skipped < take) {
            val s = source.skip(take - skipped)
            if (s > 0) {
                skipped += s
            } else if (source.read() < 0) {
                break
            } else {
                skipped++
            }
        }
        served += skipped
        return skipped
    }
}

/** Pass-through stream that tracks the decompressed chunk position. */
internal class CountingInputStream(private val source: InputStream) : InputStream() {
    var position = 0L
        private set

    override fun read(): Int {
        val value = source.read()
        if (value >= 0) position++
        return value
    }

    override fun read(dest: ByteArray, off: Int, len: Int): Int {
        val n = source.read(dest, off, len)
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = source.skip(n)
        position += skipped
        return skipped
    }

    override fun close() = source.close()
}

/** Verifies the data entry's digest once the stream is fully consumed. */
private class DigestVerifyingInputStream(
    private val source: InputStream,
    private val expected: InnoSetupReader.Digest?,
    private val path: String,
    private val expectedSize: Long,
) : InputStream() {
    private val digest = expected?.let { MessageDigest.getInstance(it.algorithm) }
    private var completed = false
    private var served = 0L

    override fun read(): Int {
        val value = source.read()
        if (value >= 0) {
            digest?.update(value.toByte())
            served++
        } else {
            verify()
        }
        return value
    }

    override fun read(dest: ByteArray, off: Int, len: Int): Int {
        val n = source.read(dest, off, len)
        if (n > 0) {
            digest?.update(dest, off, n)
            served += n
        }
        if (n < 0) verify()
        return n
    }

    override fun close() {
        // A close before full consumption is a caller-side abort, not
        // corruption: only a stream read to EOF or to its exact declared
        // size has earned a digest check.
        if (served >= expectedSize) verify()
        source.close()
    }

    private fun verify() {
        if (completed) return
        completed = true
        val digest = this.digest ?: return
        val expected = this.expected ?: return
        if (!digest.digest().contentEquals(expected.value)) {
            throw InnoFormatException("installer payload ${expected.algorithm} mismatch for $path")
        }
    }
}
