package com.pocketrealm.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.security.MessageDigest

/** Read-only, bounded traversal of a persisted SAF document tree. */
class SafTreeSource(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    private val limits: ImportLimits = ImportLimits(),
    private val paths: ImportPathPolicy = ImportPathPolicy(limits),
) {
    fun inventory(): SourceInventory {
        require(DocumentsContract.isTreeUri(treeUri)) { "VAL-07: source is not a SAF tree URI" }
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val visitedDirectories = mutableSetOf<String>()
        val folded = mutableMapOf<String, String>()
        val entries = mutableListOf<SafSourceEntry>()
        val pending = ArrayDeque<Pair<String, String?>>()
        pending.add(root to null)
        var fileCount = 0
        var totalBytes = 0L

        while (pending.isNotEmpty()) {
            val (parentId, parentPath) = pending.removeFirst()
            if (!visitedDirectories.add(parentId)) throw ImportRejected("VAL-07: provider directory cycle")
            val children = queryChildren(parentId).map { child ->
                child.copy(relativePath = paths.providerChild(parentPath, child.relativePath))
            }.sortedBy { paths.caseFoldKey(it.relativePath) }

            for (entry in children) {
                val key = paths.caseFoldKey(entry.relativePath)
                val previous = folded.putIfAbsent(key, entry.relativePath)
                if (previous != null) {
                    throw ImportRejected("VAL-06: case-fold collision: '$previous' and '${entry.relativePath}'")
                }
                if (entry.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                    throw ImportRejected("VAL-07: virtual document is not importable: ${entry.relativePath}")
                }
                entries += entry
                if (entries.size > limits.maxEntries) {
                    throw ImportRejected("VAL-08: source entry count exceeds ${limits.maxEntries}")
                }
                if (entry.directory) {
                    pending.add(entry.documentId to entry.relativePath)
                } else {
                    if (entry.mimeType.startsWith("inode/") || entry.mimeType == "application/x-symlink") {
                        throw ImportRejected("VAL-07: special file is not importable: ${entry.relativePath}")
                    }
                    if (entry.size < 0) throw ImportRejected("VAL-08: provider omitted size: ${entry.relativePath}")
                    if (entry.size > limits.maxFileBytes) {
                        throw ImportRejected("VAL-08: file exceeds ${limits.maxFileBytes} bytes: ${entry.relativePath}")
                    }
                    fileCount++
                    if (fileCount > limits.maxFiles) throw ImportRejected("VAL-08: file count exceeds ${limits.maxFiles}")
                    totalBytes = Math.addExact(totalBytes, entry.size)
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

    fun open(entry: SafSourceEntry): InputStream {
        require(!entry.directory)
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.documentId)
        return resolver.openInputStream(uri)
            ?: throw ImportRejected("VAL-07: source document is unreadable: ${entry.relativePath}")
    }

    private fun queryChildren(parentId: String): List<SafSourceEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return resolver.query(children, projection, null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(projection[0])
            val name = cursor.getColumnIndexOrThrow(projection[1])
            val mime = cursor.getColumnIndexOrThrow(projection[2])
            val size = cursor.getColumnIndex(projection[3])
            val modified = cursor.getColumnIndex(projection[4])
            val flags = cursor.getColumnIndex(projection[5])
            buildList {
                while (cursor.moveToNext()) {
                    val type = cursor.getString(mime) ?: "application/octet-stream"
                    add(SafSourceEntry(
                        documentId = cursor.getString(id),
                        relativePath = cursor.getString(name)
                            ?: throw ImportRejected("VAL-07: provider entry has no display name"),
                        directory = type == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1,
                        lastModified = if (modified >= 0 && !cursor.isNull(modified)) cursor.getLong(modified) else 0,
                        mimeType = type,
                        flags = if (flags >= 0 && !cursor.isNull(flags)) cursor.getInt(flags) else 0,
                    ))
                }
            }
        } ?: throw ImportRejected("VAL-01: selected SAF tree is unreadable")
    }

    private fun fingerprint(entries: List<SafSourceEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            val line = listOf(
                entry.relativePath, if (entry.directory) "d" else "f", entry.size.toString(),
                entry.lastModified.toString(), entry.mimeType,
            ).joinToString("\u0000") + "\n"
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
