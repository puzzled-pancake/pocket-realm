package com.pocketrealm.importer

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * RAR lane over the staged file via libarchive (clean-room BSD-2 RAR4/RAR5
 * readers; GPL-3.0-compatible — see docs/plans/universal-client-installer-
 * plan.md §3). Streaming-only like 7z: inventory order IS archive order, the
 * copy loop is one forward pass, and resume skips verified entries with
 * `archive_read_data_skip`.
 *
 * This file is device-only (the libarchive JNI library lives in the APK):
 * JVM tests must not reference it, which is why it is separated from the
 * commons-compress sources in ArchiveSources.kt.
 */
internal class RarArchiveSource(
    private val file: File,
    private val classified: ImportExtractionPolicy.Classified,
) : ImportSource {
    private val archive: Long = openArchive()
    private var closed = false

    private fun openArchive(): Long = try {
        val handle = Archive.readNew()
        try {
            Archive.readSupportFormatRar(handle)
            Archive.readSupportFormatRar5(handle)
            Archive.readOpenFileName(handle, file.absolutePath.toByteArray(Charsets.UTF_8), BLOCK_BYTES.toLong())
            handle
        } catch (error: Throwable) {
            runCatching { Archive.readFree(handle) }
            throw error
        }
    } catch (error: ArchiveException) {
        throw ImportRejected("VAL-13: cannot open the RAR archive — ${error.message}")
    }

    override fun inventory(): SourceInventory {
        val listed = listRarEntries(file)
        val entries = classified.entries.map { entry ->
            val stored = listed.firstOrNull { it.name == entry.key }
                ?: throw ImportRejected("VAL-13: staged RAR no longer lists ${entry.relativePath}")
            if (stored.directory != entry.directory || stored.size != entry.size) {
                throw ImportRejected("VAL-13: staged RAR entry changed: ${entry.relativePath}")
            }
            entry
        }
        return SourceInventory(entries, classified.fileCount, classified.totalBytes, fingerprint(entries))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        require(!entry.directory)
        while (true) {
            val next = nextHeader()
                ?: throw ImportRejected("VAL-07: source entry is unreadable: ${entry.relativePath}")
            val name = entryName(next)
            if (name == entry.key) return RarEntryStream(this)
            // Advancing past an unread entry skips its data: resume-by-skip.
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { Archive.readFree(archive) }
        }
    }

    private fun nextHeader(): Long? = try {
        val entry = Archive.readNextHeader(archive)
        if (entry == 0L) null else entry
    } catch (error: ArchiveException) {
        throw ImportRejected("VAL-13: RAR read failed — ${error.message}")
    }

    internal fun entryName(handle: Long): String {
        val utf8 = ArchiveEntry.pathnameUtf8(handle)
        val bytes = ArchiveEntry.pathname(handle)
        return utf8 ?: bytes?.toString(Charsets.UTF_8) ?: throw ImportRejected("VAL-13: RAR entry has no name")
    }

    /** Reads current-entry data into [buffer]; 0 at end of entry. */
    internal fun readData(buffer: ByteBuffer): Int = try {
        buffer.clear()
        Archive.readData(archive, buffer)
        buffer.flip().limit()
    } catch (error: ArchiveException) {
        throw ImportRejected("VAL-13: RAR extraction failed — ${error.message}")
    }

    private class RarEntryStream(private val source: RarArchiveSource) : InputStream() {
        private val buffer = ByteBuffer.allocateDirect(BLOCK_BYTES)

        override fun read(): Int {
            if (ensureFilled() <= 0) return -1
            return buffer.get().toInt() and 0xff
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (ensureFilled() <= 0) return -1
            val count = minOf(length, buffer.remaining())
            buffer.get(destination, offset, count)
            return count
        }

        private fun ensureFilled(): Int {
            if (buffer.remaining() > 0) return buffer.remaining()
            return source.readData(buffer)
        }
    }

    companion object {
        private const val BLOCK_BYTES = 512 * 1024
    }
}

/**
 * One header walk over a staged RAR (skipping data blocks) producing the raw
 * entry list plus the encrypted flag. Non-solid archives walk cheaply; solid
 * archives pay a decompression pass, which the copy would pay anyway.
 */
internal fun listRarEntries(file: File): List<ArchiveClientScanner.RawEntry> {
    var archive = 0L
    try {
        archive = Archive.readNew()
        Archive.readSupportFormatRar(archive)
        Archive.readSupportFormatRar5(archive)
        Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), (512 * 1024).toLong())
        val entries = mutableListOf<ArchiveClientScanner.RawEntry>()
        while (true) {
            val entry = Archive.readNextHeader(archive)
            if (entry == 0L) break
            val name = ArchiveEntry.pathnameUtf8(entry)
                ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8)
                ?: continue
            entries += ArchiveClientScanner.RawEntry(
                name,
                ArchiveEntry.filetypeIsSet(entry) &&
                    (ArchiveEntry.filetype(entry) and me.zhanghai.android.libarchive.ArchiveEntry.AE_IFMT) ==
                    me.zhanghai.android.libarchive.ArchiveEntry.AE_IFDIR,
                ArchiveEntry.sizeIsSet(entry).let { if (it) ArchiveEntry.size(entry) else -1L },
                ArchiveEntry.isEncrypted(entry),
            )
            Archive.readDataSkip(archive)
            if (entries.size > 110_000) throw ImportRejected("VAL-08: source entry count exceeds 110000")
        }
        return entries
    } catch (error: ArchiveException) {
        throw ImportRejected("VAL-13: cannot read the RAR archive — ${error.message}")
    } finally {
        if (archive != 0L) runCatching { Archive.readFree(archive) }
    }
}

