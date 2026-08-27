package com.pocketrealm.importer

import java.io.File

/**
 * Extracts a staged archive verbatim into a scratch directory for the Inno
 * installer lane. Unlike the client copy pipeline this applies no client
 * root allow-list — the payload is setup.exe plus its setup-N.bin slices —
 * but it keeps the shared path-safety policy (control characters, absolute
 * paths, traversal, case-fold collisions) so a hostile archive cannot write
 * outside the scratch directory.
 */
internal object ScratchArchiveExtractor {

    fun extract(
        stagedFile: File,
        format: ArchiveFormat,
        target: File,
        limits: ImportLimits,
        checkpoint: () -> Unit = {},
        onEntryExtracted: (filesDone: Int) -> Unit = {},
    ) {
        val raw = when (format) {
            ArchiveFormat.ZIP -> listZipEntries(stagedFile)
            ArchiveFormat.SEVEN_ZIP -> listSevenZipEntries(stagedFile)
            ArchiveFormat.RAR4, ArchiveFormat.RAR5 -> listRarEntries(stagedFile)
            else -> throw ImportRejected(ArchiveQuickCheck.VAL13_UNSUPPORTED)
        }
        if (raw.any { it.encrypted }) {
            throw ImportRejected(
                "VAL-13: the archive is password-protected — Pocket Realm cannot open encrypted archives",
            )
        }
        val classified = classifyForScratch(raw, limits)
        val source: ImportSource = when (format) {
            ArchiveFormat.ZIP -> ZipArchiveSource(stagedFile, classified)
            ArchiveFormat.SEVEN_ZIP -> SevenZipArchiveSource(stagedFile, classified)
            else -> RarArchiveSource(stagedFile, classified)
        }
        source.use { open ->
            val inventory = open.inventory()
            var filesDone = 0
            inventory.entries.forEach { entry ->
                checkpoint()
                if (entry.directory) return@forEach
                val destination = File(target, entry.relativePath)
                destination.parentFile?.mkdirs()
                open.open(entry).use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var remaining = entry.size
                        // Never write past the declared size, and never
                        // accept a stream that dries up early: headers are
                        // not trusted for space safety anywhere else either.
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) {
                                throw ImportRejected("VAL-08: scratch extraction ended early: ${entry.relativePath}")
                            }
                            output.write(buffer, 0, n)
                            remaining -= n
                            checkpoint()
                        }
                    }
                }
                filesDone++
                onEntryExtracted(filesDone)
            }
        }
    }

    /**
     * Raw classification: every entry kept, names normalized through the
     * shared path policy, directories preserved. Keys stay the archive's
     * original names (the sources look entries up by them); relative paths
     * are the normalized forms used for the scratch layout. Mirrors the
     * fold/collision/aggregate-cap guarantees of
     * [ImportExtractionPolicy.classify] without its client allow-lists.
     */
    private fun classifyForScratch(
        entries: List<ArchiveClientScanner.RawEntry>,
        limits: ImportLimits,
    ): ImportExtractionPolicy.Classified {
        val policy = ImportPathPolicy(limits)
        val folded = HashMap<String, Pair<String, Boolean>>()
        var fileCount = 0
        var totalBytes = 0L
        val out = ArrayList<ImportSourceEntry>(entries.size)
        for (entry in entries.sortedBy { it.name }) {
            val unified = entry.name.replace('\\', '/').trimEnd('/')
            if (unified.isEmpty()) continue
            val normalized = policy.normalize(unified)
            val key = policy.caseFoldKey(normalized)
            val previous = folded.putIfAbsent(key, normalized to entry.directory)
            if (previous != null) {
                if (previous.second != entry.directory) {
                    throw ImportRejected("VAL-06: directory/file type conflict: '${previous.first}' and '$normalized'")
                }
                throw ImportRejected("VAL-06: duplicate archive entry '$normalized' and '${previous.first}'")
            }
            if (entry.directory) {
                out += ImportSourceEntry(entry.name, normalized, true, 0, 0, "dir")
                continue
            }
            if (entry.size < 0) throw ImportRejected("VAL-08: archive entry omitted size: $normalized")
            if (entry.size > limits.maxFileBytes) {
                throw ImportRejected("VAL-08: file exceeds ${limits.maxFileBytes} bytes: $normalized")
            }
            out += ImportSourceEntry(entry.name, normalized, false, entry.size, 0, "file")
            fileCount++
            if (fileCount > limits.maxFiles) throw ImportRejected("VAL-08: file count exceeds ${limits.maxFiles}")
            totalBytes = Math.addExact(totalBytes, entry.size)
            if (totalBytes > limits.maxTotalBytes) {
                throw ImportRejected("VAL-08: source exceeds ${limits.maxTotalBytes} bytes")
            }
        }
        if (out.size > limits.maxEntries) {
            throw ImportRejected("VAL-08: source entry count exceeds ${limits.maxEntries}")
        }
        return ImportExtractionPolicy.Classified(out, emptyList(), fileCount, totalBytes)
    }
}
