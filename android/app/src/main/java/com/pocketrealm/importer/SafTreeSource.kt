package com.pocketrealm.importer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.security.MessageDigest

/**
 * Read-only, bounded traversal of a persisted SAF document tree. Implements
 * the lane-agnostic [ImportSource] over SAF documents: the provider query row
 * stays private (checks run against it) and maps onto [ImportSourceEntry]
 * with key = documentId, attributes = mime type.
 */
class SafTreeSource(
    private val resolver: ContentResolver,
    val treeUri: Uri,
    private val limits: ImportLimits = ImportLimits(),
    private val paths: ImportPathPolicy = ImportPathPolicy(limits),
) : ImportSource {
    override fun inventory(): SourceInventory {
        require(DocumentsContract.isTreeUri(treeUri)) { "VAL-07: source is not a SAF tree URI" }
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val visitedDirectories = mutableSetOf<String>()
        val folded = mutableMapOf<String, String>()
        val entries = mutableListOf<ImportSourceEntry>()
        val pending = ArrayDeque<Pair<String, String?>>()
        pending.add(root to null)
        var fileCount = 0
        var totalBytes = 0L

        while (pending.isNotEmpty()) {
            val (parentId, parentPath) = pending.removeFirst()
            if (!visitedDirectories.add(parentId)) throw ImportRejected("VAL-07: provider directory cycle")
            val children = queryChildren(parentId).map { child ->
                child.copy(name = paths.providerChild(parentPath, child.name))
            }.sortedBy { paths.caseFoldKey(it.name) }

            for (row in children) {
                val key = paths.caseFoldKey(row.name)
                val previous = folded.putIfAbsent(key, row.name)
                if (previous != null) {
                    throw ImportRejected("VAL-06: case-fold collision: '$previous' and '${row.name}'")
                }
                if (row.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) {
                    throw ImportRejected("VAL-07: virtual document is not importable: ${row.name}")
                }
                if (!row.directory) {
                    if (row.mimeType.startsWith("inode/") || row.mimeType == "application/x-symlink") {
                        throw ImportRejected("VAL-07: special file is not importable: ${row.name}")
                    }
                    if (row.size < 0) throw ImportRejected("VAL-08: provider omitted size: ${row.name}")
                    if (row.size > limits.maxFileBytes) {
                        throw ImportRejected("VAL-08: file exceeds ${limits.maxFileBytes} bytes: ${row.name}")
                    }
                }
                entries += row.toSourceEntry()
                if (entries.size > limits.maxEntries) {
                    throw ImportRejected("VAL-08: source entry count exceeds ${limits.maxEntries}")
                }
                if (row.directory) {
                    pending.add(row.documentId to row.name)
                } else {
                    fileCount++
                    if (fileCount > limits.maxFiles) throw ImportRejected("VAL-08: file count exceeds ${limits.maxFiles}")
                    totalBytes = Math.addExact(totalBytes, row.size)
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
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.key)
        return resolver.openInputStream(uri)
            ?: throw ImportRejected("VAL-07: source document is unreadable: ${entry.relativePath}")
    }

    /** Provider query row with the relative path already composed into [name]. */
    private data class DocumentRow(
        val documentId: String,
        val name: String,
        val directory: Boolean,
        val size: Long,
        val lastModified: Long,
        val mimeType: String,
        val flags: Int,
    ) {
        fun toSourceEntry() = ImportSourceEntry(
            key = documentId,
            relativePath = name,
            directory = directory,
            size = size,
            lastModified = lastModified,
            attributes = mimeType,
        )
    }

    private fun queryChildren(parentId: String): List<DocumentRow> {
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
                    add(DocumentRow(
                        documentId = cursor.getString(id),
                        name = cursor.getString(name)
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
