package com.pocketrealm.importer

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Archive-lane [ImportSource] implementations over the staged archive file.
 * They consume the [ImportExtractionPolicy.Classified] inventory produced by
 * [ArchiveClientScanner] (same entry decoding, same wrapper rebase), open
 * entries by their original archive name ([ImportSourceEntry.key]), and
 * re-verify per-entry declared sizes during [verify] so a corrupted staged
 * file fails closed before publish.
 *
 * ZIP entry decoding: strict UTF-8 first; when any decoded name contains the
 * replacement character (malformed input without the EFS flag — the usual
 * Russian-archive cp866 case) the archive is re-opened with IBM866.
 */
internal class ZipArchiveSource(
    private val file: File,
    private val classified: ImportExtractionPolicy.Classified,
) : ImportSource {
    private val zip: ZipFile = openWithDecoding()

    private fun openWithDecoding(): ZipFile {
        val utf8 = ZipFile.builder().setFile(file).setCharset(Charsets.UTF_8).get()
        val malformed = utf8.entries.asSequence().any { it.name.contains('\ufffd') }
        if (!malformed) return utf8
        utf8.close()
        return ZipFile.builder().setFile(file).setCharset(charset("IBM866")).get()
    }

    override fun inventory(): SourceInventory {
        val entries = classified.entries.map { entry ->
            val zipEntry = zip.getEntry(entry.key)
                ?: throw ImportRejected("VAL-13: staged archive no longer lists ${entry.relativePath}")
            if (zipEntry.isDirectory != entry.directory || zipEntry.size != entry.size) {
                throw ImportRejected("VAL-13: staged archive entry changed: ${entry.relativePath}")
            }
            if (!entry.directory && zipEntry.isUnixSymlink) {
                throw ImportRejected("VAL-07: symlink entry is not importable: ${entry.relativePath}")
            }
            entry
        }
        return SourceInventory(entries, classified.fileCount, classified.totalBytes, fingerprint(entries))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        require(!entry.directory)
        val zipEntry = zip.getEntry(entry.key)
            ?: throw ImportRejected("VAL-07: source entry is unreadable: ${entry.relativePath}")
        return zip.getInputStream(zipEntry)
            ?: throw ImportRejected("VAL-07: source entry is unreadable: ${entry.relativePath}")
    }

    override fun close() = zip.close()
}

internal class SevenZipArchiveSource(
    private val file: File,
    private val classified: ImportExtractionPolicy.Classified,
) : ImportSource {
    private val archive: SevenZFile = SevenZFile.builder().setFile(file).get()

    /**
     * 7z is sequential-only, so inventory order IS archive order (LinkedHashMap
     * below): the copy loop's per-entry open() then just advances the single
     * forward pass, and resume skips verified entries by name without paying
     * for a re-seek that the format cannot provide anyway.
     */
    override fun inventory(): SourceInventory {
        val byName = LinkedHashMap<String, SevenZArchiveEntry>()
        while (true) {
            val entry = archive.nextEntry ?: break
            byName[entry.name] = entry
        }
        val ordered = byName.values.filter { stored -> classified.entries.any { it.key == stored.name } }
        val entries = ordered.map { stored ->
            val entry = classified.entries.first { it.key == stored.name }
            if (stored.isDirectory != entry.directory || stored.size != entry.size) {
                throw ImportRejected("VAL-13: staged archive entry changed: ${entry.relativePath}")
            }
            if (stored.isAntiItem) {
                throw ImportRejected("VAL-07: anti-item entry is not importable: ${entry.relativePath}")
            }
            entry
        }
        return SourceInventory(entries, classified.fileCount, classified.totalBytes, fingerprint(entries))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        require(!entry.directory)
        while (true) {
            val next = archive.nextEntry
                ?: throw ImportRejected("VAL-07: source entry is unreadable: ${entry.relativePath}")
            if (next.name == entry.key) return SevenZEntryStream(archive)
            // Advancing past an unread entry discards its data: resume-by-skip.
        }
    }

    override fun close() = archive.close()

    private class SevenZEntryStream(private val archive: SevenZFile) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = archive.read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            archive.read(buffer, offset, length)
    }
}

/** Stable archive fingerprint: name + type + size only (container timestamps are not stable). */
internal fun fingerprint(entries: List<ImportSourceEntry>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.forEach { entry ->
        val line = listOf(entry.relativePath, if (entry.directory) "d" else "f", entry.size.toString())
            .joinToString("\u0000") + "\n"
        digest.update(line.toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Shared raw-entry enumeration for a staged ZIP (names, dirs, sizes) with the cp866 fallback. */
internal fun listZipEntries(file: File): List<ArchiveClientScanner.RawEntry> {
    var zip: ZipFile? = null
    try {
        zip = ZipFile.builder().setFile(file).setCharset(Charsets.UTF_8).get()
        val malformed = zip.entries.asSequence().any { it.name.contains('\ufffd') }
        if (malformed) {
            zip.close()
            zip = ZipFile.builder().setFile(file).setCharset(charset("IBM866")).get()
        }
        return zip.entries.asSequence().map { entry: ZipArchiveEntry ->
            ArchiveClientScanner.RawEntry(
                entry.name, entry.isDirectory, entry.size,
                entry.generalPurposeBit.usesEncryption(),
            )
        }.toList()
    } finally {
        zip?.close()
    }
}

/** Shared raw-entry enumeration for a staged 7z (names, dirs, sizes). */
internal fun listSevenZipEntries(file: File): List<ArchiveClientScanner.RawEntry> =
    SevenZFile.builder().setFile(file).get().use { archive ->
        buildList {
            while (true) {
                val entry = archive.nextEntry ?: break
                add(ArchiveClientScanner.RawEntry(entry.name, entry.isDirectory, entry.size))
            }
        }
    }

/** Streams a raw entry's bytes from a staged ZIP for the detection reader. */
internal fun readZipEntry(file: File, rawName: String, maxBytes: Int): ByteArray =
    ZipFile.builder().setFile(file).setCharset(Charsets.UTF_8).get().use { zip ->
        val entry = zip.getEntry(rawName) ?: throw ImportRejected("VAL-13: entry vanished: $rawName")
        zip.getInputStream(entry).use { it.readNBytes(maxBytes) }
    }

/** Streams a raw entry's bytes from a staged 7z (sequential scan) for the detection reader. */
internal fun readSevenZipEntry(file: File, rawName: String, maxBytes: Int): ByteArray =
    SevenZFile.builder().setFile(file).get().use { archive ->
        while (true) {
            val entry = archive.nextEntry ?: throw ImportRejected("VAL-13: entry vanished: $rawName")
            if (entry.name == rawName) {
                val buffer = ByteArray(maxBytes)
                var filled = 0
                while (filled < maxBytes) {
                    val count = archive.read(buffer, filled, maxBytes - filled)
                    if (count <= 0) break
                    filled += count
                }
                return@use buffer.copyOf(filled)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ByteArray(0)
    }
