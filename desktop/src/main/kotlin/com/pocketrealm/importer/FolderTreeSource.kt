package com.pocketrealm.importer

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * the desktop folder-lane [ImportSource] — SafTreeSource's twin
 * over a plain `java.io.File` directory instead of a SAF document tree.
 * Read-only, bounded traversal with the same policy mapping: key = absolute
 * source path, relative paths normalized through [ImportPathPolicy], the
 * same skip rules (non-client root entries, unusable names, case-only
 * duplicates) and the same fingerprint arithmetic, so a folder import and
 * an archive import produce identical inventories.
 */
class FolderTreeSource(
    val sourceRoot: File,
    private val limits: ImportLimits = ImportLimits(),
    private val paths: ImportPathPolicy = ImportPathPolicy(limits),
) : ImportSource {

    /** Entries skipped by the last [inventory] and why (non-client root
     * entries, unusable names, case-only duplicates, symlinks). */
    var skipped: List<ImportExtractionPolicy.Excluded> = emptyList()
        private set

    override fun inventory(): SourceInventory {
        require(sourceRoot.isDirectory) { "The selected folder is not a directory" }
        val root = sourceRoot.canonicalFile
        val visitedDirectories = mutableSetOf<File>()
        val folded = mutableMapOf<String, String>()
        val entries = mutableListOf<ImportSourceEntry>()
        val excluded = mutableListOf<ImportExtractionPolicy.Excluded>()
        val pending = ArrayDeque<Pair<File, String?>>()
        pending.add(root to null)
        var fileCount = 0
        var totalBytes = 0L

        while (pending.isNotEmpty()) {
            val (directory, parentPath) = pending.removeFirst()
            if (!visitedDirectories.add(directory)) continue // a cycle: walk each directory once
            val children = directory.listFiles()
                ?.sortedBy { paths.caseFoldKey(it.name) }
                ?: if (parentPath == null) {
                    throw ImportRejected("The selected folder could not be read")
                } else {
                    excluded += ImportExtractionPolicy.Excluded(parentPath, "unreadable folder")
                    continue
                }

            for (child in children) {
                val joined = if (parentPath.isNullOrEmpty()) child.name else "$parentPath/${child.name}"
                val name = when (val checked = ImportNameCheck.check(paths, joined)) {
                    is ImportNameCheck.Name.Usable -> checked.normalized
                    is ImportNameCheck.Name.Skip -> {
                        excluded += ImportExtractionPolicy.Excluded(joined, checked.reason)
                        continue
                    }
                }
                val key = paths.caseFoldKey(name)
                val isSymlink = runCatching {
                    java.nio.file.Files.isSymbolicLink(child.toPath()) ||
                        java.nio.file.Files.readAttributes(
                            child.toPath(),
                            java.nio.file.attribute.BasicFileAttributes::class.java,
                        ).isSymbolicLink
                }.getOrDefault(false)
                val skipReason = when {
                    parentPath == null && !RootLayout.admitsRoot(key, child.isDirectory) ->
                        if (child.isDirectory) "non-client root directory" else "non-client root file"
                    folded.containsKey(key) -> "duplicate of '${folded[key]}' differing only in case"
                    isSymlink -> "symlink"
                    else -> null
                }
                if (skipReason != null) {
                    excluded += ImportExtractionPolicy.Excluded(name, skipReason)
                    continue
                }
                folded[key] = name
                if (child.isDirectory) {
                    entries += ImportSourceEntry(
                        key = child.absolutePath, relativePath = name, directory = true,
                        size = 0L, lastModified = child.lastModified(), attributes = "directory",
                    )
                    if (entries.size > limits.maxEntries) {
                        throw ImportRejected("Too many entries in the source")
                    }
                    pending.add(child to name)
                } else {
                    val size = child.length()
                    if (size > limits.maxFileBytes) {
                        throw ImportRejected("A file is too large to import: $name")
                    }
                    entries += ImportSourceEntry(
                        key = child.absolutePath, relativePath = name, directory = false,
                        size = size, lastModified = child.lastModified(), attributes = "file",
                    )
                    if (entries.size > limits.maxEntries) {
                        throw ImportRejected("Too many entries in the source")
                    }
                    fileCount++
                    if (fileCount > limits.maxFiles) {
                        throw ImportRejected("Too many files in the source")
                    }
                    totalBytes = Math.addExact(totalBytes, size)
                    if (totalBytes > limits.maxTotalBytes) {
                        throw ImportRejected("The source is too large to import")
                    }
                }
            }
        }
        if (fileCount !in limits.minFiles..limits.maxFiles || totalBytes !in limits.minTotalBytes..limits.maxTotalBytes) {
            throw ImportRejected("The source does not look like a game client (files=$fileCount, bytes=$totalBytes)")
        }
        skipped = excluded
        val ordered = entries.sortedWith(compareBy({ paths.caseFoldKey(it.relativePath) }, { it.relativePath }))
        return SourceInventory(ordered, fileCount, totalBytes, fingerprint(ordered))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        require(!entry.directory)
        val file = File(entry.key)
        if (!file.isFile) throw ImportRejected("A source file could not be read: ${entry.relativePath}")
        return FileInputStream(file)
    }

    /** The same fingerprint arithmetic as SafTreeSource, so inventories from
     * either lane compare equal in shape and change-detection semantics. */
    private fun fingerprint(entries: List<ImportSourceEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            val line = listOf(
                entry.relativePath, if (entry.directory) "d" else "f", entry.size.toString(),
                entry.lastModified.toString(), entry.attributes,
            ).joinToString("\u0000") + "\n"
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
