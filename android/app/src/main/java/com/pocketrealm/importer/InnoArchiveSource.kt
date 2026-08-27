package com.pocketrealm.importer

import com.pocketrealm.importer.inno.InnoSetupReader
import java.io.InputStream

/**
 * [ImportSource] over an extracted Inno installer payload: the entries are
 * the installer's `{app}` files, already client-rooted and chunk-ordered.
 * Chunk order matters — solid payloads share one LZMA stream, and the
 * session decodes it exactly once when files are consumed in this order.
 * Every [open] stream verifies the installer's per-file digest.
 */
internal class InnoArchiveSource(
    private val reader: InnoSetupReader,
    private val classified: ImportExtractionPolicy.Classified,
) : ImportSource {

    private val session = reader.session()
    private val byPath = reader.files().associateBy { it.path }

    override fun inventory(): SourceInventory {
        val allowed = classified.entries.filter { !it.directory }.map { it.relativePath }.toHashSet()
        // Chunk order, not the classified alphabetical order: see the class
        // doc. Entries the client policy excluded drop out here.
        val entries = reader.files().filter { it.path in allowed }.map { file ->
            ImportSourceEntry(
                key = file.path,
                relativePath = file.path,
                directory = false,
                size = file.size,
                lastModified = 0L,
                attributes = "file",
            )
        }
        val totalBytes = entries.sumOf { it.size }
        if (entries.size != classified.fileCount) {
            throw ImportRejected(
                "VAL-13: installer payload listing changed between detection and import",
            )
        }
        return SourceInventory(entries, entries.size, totalBytes, fingerprint(entries))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        val file = byPath[entry.relativePath]
            ?: throw ImportRejected("VAL-13: installer payload no longer lists ${entry.relativePath}")
        return session.open(file)
    }

    override fun close() {
        session.close()
        reader.close()
    }
}

/** The Inno lane's view of an installer payload for pre-copy detection. */
internal object InnoPayloadDetection {

    /**
     * Raw entries for [ArchiveClientScanner.locate]: the `{app}` files plus
     * synthesized parent directories (installer headers carry no directory
     * entries, while the client-root detection requires a `Data` directory).
     */
    fun rawEntries(files: List<InnoSetupReader.InnoPayloadFile>): List<ArchiveClientScanner.RawEntry> {
        val dirs = LinkedHashSet<String>()
        files.forEach { file ->
            var parent = file.path.substringBefore('/', "")
            while (parent.isNotEmpty()) {
                dirs += parent
                parent = parent.substringBefore('/', "")
            }
        }
        return files.map { ArchiveClientScanner.RawEntry(it.path, false, it.size) } +
            dirs.map { ArchiveClientScanner.RawEntry(it, true, 0) }
    }
}
