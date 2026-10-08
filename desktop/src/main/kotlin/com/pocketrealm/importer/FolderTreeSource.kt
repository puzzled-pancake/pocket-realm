package com.pocketrealm.importer

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * DESK-63/97: the desktop folder-lane [ImportSource] — SafTreeSource's twin
 * over a plain `java.io.File` directory instead of a SAF document tree.
 * Read-only, bounded traversal with the same policy mapping: key = absolute
 * source path, relative paths normalized through [ImportPathPolicy], the
 * same VAL-06/07/08 rejections and the same fingerprint arithmetic, so a
 * folder import and an archive import produce identical inventories.
 */
class FolderTreeSource(
    val sourceRoot: File,
    private val limits: ImportLimits = ImportLimits(),
    private val paths: ImportPathPolicy = ImportPathPolicy(limits),
) : ImportSource {

    override fun inventory(): SourceInventory {
        require(sourceRoot.isDirectory) { "VAL-01: selected source folder is not a directory" }
        val root = sourceRoot.canonicalFile
        val visitedDirectories = mutableSetOf<File>()
        val folded = mutableMapOf<String, String>()
        val entries = mutableListOf<ImportSourceEntry>()
        val pending = ArrayDeque<Pair<File, String?>>()
        pending.add(root to null)
        var fileCount = 0
        var totalBytes = 0L

        while (pending.isNotEmpty()) {
            val (directory, parentPath) = pending.removeFirst()
            if (!visitedDirectories.add(directory)) throw ImportRejected("VAL-07: source directory cycle")
            val children = directory.listFiles()
                ?.sortedBy { paths.caseFoldKey(it.name) }
                ?: throw ImportRejected("VAL-01: selected source folder is unreadable")

            for (child in children) {
                val name = paths.providerChild(parentPath, child.name)
                val key = paths.caseFoldKey(name)
                val previous = folded.putIfAbsent(key, name)
                if (previous != null) {
                    throw ImportRejected("VAL-06: case-fold collision: '$previous' and '$name'")
                }
                val isSymlink = runCatching {
                    java.nio.file.Files.isSymbolicLink(child.toPath()) ||
                        java.nio.file.Files.readAttributes(
                            child.toPath(),
                            java.nio.file.attribute.BasicFileAttributes::class.java,
                        ).isSymbolicLink
                }.getOrDefault(false)
                if (isSymlink) throw ImportRejected("VAL-07: symlink is not importable: $name")
                if (child.isDirectory) {
                    entries += ImportSourceEntry(
                        key = child.absolutePath, relativePath = name, directory = true,
                        size = 0L, lastModified = child.lastModified(), attributes = "directory",
                    )
                    if (entries.size > limits.maxEntries) {
                        throw ImportRejected("VAL-08: source entry count exceeds ${limits.maxEntries}")
                    }
                    pending.add(child to name)
                } else {
                    val size = child.length()
                    if (size < 0) throw ImportRejected("VAL-08: filesystem omitted size: $name")
                    if (size > limits.maxFileBytes) {
                        throw ImportRejected("VAL-08: file exceeds ${limits.maxFileBytes} bytes: $name")
                    }
                    entries += ImportSourceEntry(
                        key = child.absolutePath, relativePath = name, directory = false,
                        size = size, lastModified = child.lastModified(), attributes = "file",
                    )
                    if (entries.size > limits.maxEntries) {
                        throw ImportRejected("VAL-08: source entry count exceeds ${limits.maxEntries}")
                    }
                    fileCount++
                    if (fileCount > limits.maxFiles) {
                        throw ImportRejected("VAL-08: file count exceeds ${limits.maxFiles}")
                    }
                    totalBytes = Math.addExact(totalBytes, size)
                    if (totalBytes > limits.maxTotalBytes) {
                        throw ImportRejected("VAL-08: source exceeds ${limits.maxTotalBytes} bytes")
                    }
                }
            }
        }
        if (fileCount !in limits.minFiles..limits.maxFiles || totalBytes !in limits.minTotalBytes..limits.maxTotalBytes) {
            throw ImportRejected("VAL-08: implausible client size/count: files=$fileCount bytes=$totalBytes")
        }
        val ordered = entries.sortedWith(compareBy({ paths.caseFoldKey(it.relativePath) }, { it.relativePath }))
        return SourceInventory(ordered, fileCount, totalBytes, fingerprint(ordered))
    }

    override fun open(entry: ImportSourceEntry): InputStream {
        require(!entry.directory)
        val file = File(entry.key)
        if (!file.isFile) throw ImportRejected("VAL-07: source file is unreadable: ${entry.relativePath}")
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
