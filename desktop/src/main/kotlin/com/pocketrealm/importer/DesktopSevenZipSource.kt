package com.pocketrealm.importer

import java.io.File
import java.io.InputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * the desktop 7z source over commons-compress 1.28's RANDOM-ACCESS
 * API. The SHARED [SevenZipArchiveSource] (an android-tree file, untouched
 * here) drives the single sequential pass — but its own inventory() has
 * already exhausted that pass before the copy loop opens the first entry, so
 * every open() would surface "VAL-07: source entry is unreadable" (observed
 * on the desktop archive-lane test with a synthetic COPY-method 7z). This
 * twin validates against [SevenZFile.getEntries] (a header-only, non-
 * consuming listing) and decodes on demand through
 * [SevenZFile.getInputStream] (COMPRESS-646), so validation and the copy
 * loop never fight over one forward pass.
 *
 * Solid archives decode forward from the stream start per entry (slow but
 * correct); when commons-compress refuses an entry it cannot access, the
 * failure surfaces as the honest VAL-13 copy.
 */
internal class DesktopSevenZipSource(
    private val file: File,
    private val classified: ImportExtractionPolicy.Classified,
) : ImportSource {
    private val archive: SevenZFile = SevenZFile.builder().setFile(file).get()
    private val byName: Map<String, SevenZArchiveEntry> =
        archive.entries.associateBy { it.name }

    override fun inventory(): SourceInventory {
        val entries = classified.entries.map { entry ->
            val stored = byName[entry.key]
                ?: throw ImportRejected("The archive changed during import — an entry is gone: ${entry.relativePath}")
            if (stored.isDirectory != entry.directory || stored.size != entry.size) {
                throw ImportRejected("The archive changed during import: ${entry.relativePath}")
            }
            if (stored.isAntiItem) {
                throw ImportRejected("This archive entry cannot be imported: ${entry.relativePath}")
            }
            entry
        }
        return SourceInventory(entries, classified.fileCount, classified.totalBytes, fingerprint(entries))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        require(!entry.directory)
        val stored = byName[entry.key]
            ?: throw ImportRejected("An archive entry could not be read: ${entry.relativePath}")
        return try {
            archive.getInputStream(stored)
        } catch (failure: UnsupportedOperationException) {
            throw ImportRejected(
                "This 7z entry cannot be read in place (${failure.message}) — " +
                    "re-pack the client as .zip",
            )
        }
    }

    override fun close() = archive.close()
}
