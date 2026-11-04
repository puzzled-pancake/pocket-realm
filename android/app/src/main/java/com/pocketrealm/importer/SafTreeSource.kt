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
    /** The client root inside the granted tree (a nested client folder the
     * scanner located); null = the tree root itself. */
    private val rootDocumentId: String? = null,
) : ImportSource {
    /** Entries skipped by the last [inventory] and why (non-client root
     * entries, unusable names, case-only duplicates, special files). */
    var skipped: List<ImportExtractionPolicy.Excluded> = emptyList()
        private set

    override fun inventory(): SourceInventory {
        require(DocumentsContract.isTreeUri(treeUri)) { "The selected source is not a folder" }
        val root = rootDocumentId ?: DocumentsContract.getTreeDocumentId(treeUri)
        val visitedDirectories = mutableSetOf<String>()
        val folded = mutableMapOf<String, String>()
        val entries = mutableListOf<ImportSourceEntry>()
        val excluded = mutableListOf<ImportExtractionPolicy.Excluded>()
        val pending = ArrayDeque<Pair<String, String?>>()
        pending.add(root to null)
        var fileCount = 0
        var totalBytes = 0L

        while (pending.isNotEmpty()) {
            val (parentId, parentPath) = pending.removeFirst()
            if (!visitedDirectories.add(parentId)) continue // a provider cycle: walk each directory once
            val children = queryChildren(parentId).mapNotNull { child ->
                val joined = if (parentPath.isNullOrEmpty()) child.name else "$parentPath/${child.name}"
                when (val name = if ('/' in child.name || '\\' in child.name) {
                    ImportNameCheck.Name.Skip("unusable name skipped (path separator in a file name)")
                } else {
                    ImportNameCheck.check(paths, joined)
                }) {
                    is ImportNameCheck.Name.Usable -> child.copy(name = name.normalized)
                    is ImportNameCheck.Name.Skip -> {
                        excluded += ImportExtractionPolicy.Excluded(joined, name.reason)
                        null
                    }
                }
            }.sortedBy { paths.caseFoldKey(it.name) }

            for (row in children) {
                val key = paths.caseFoldKey(row.name)
                val skipReason = when {
                    parentPath == null && !RootLayout.admitsRoot(key, row.directory) ->
                        if (row.directory) "non-client root directory" else "non-client root file"
                    folded.containsKey(key) -> "duplicate of '${folded[key]}' differing only in case"
                    row.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0 ->
                        "virtual document (no bytes to copy)"
                    !row.directory && (row.mimeType.startsWith("inode/") || row.mimeType == "application/x-symlink") ->
                        "special file"
                    else -> null
                }
                if (skipReason != null) {
                    excluded += ImportExtractionPolicy.Excluded(row.name, skipReason)
                    continue
                }
                folded[key] = row.name
                if (!row.directory) {
                    if (row.size < 0) {
                        // Data is the client itself: an MPQ of unknown size
                        // cannot be planned or verified. Anything else is skipped.
                        if (key.startsWith("data/")) throw ImportRejected("A provider entry is missing its size: ${row.name}")
                        excluded += ImportExtractionPolicy.Excluded(row.name, "provider reported no size")
                        continue
                    }
                    if (row.size > limits.maxFileBytes) {
                        throw ImportRejected("A file is too large to import: ${row.name}")
                    }
                }
                entries += row.toSourceEntry()
                if (entries.size > limits.maxEntries) {
                    throw ImportRejected("Too many entries in the source")
                }
                if (row.directory) {
                    pending.add(row.documentId to row.name)
                } else {
                    fileCount++
                    if (fileCount > limits.maxFiles) throw ImportRejected("Too many files in the source")
                    totalBytes = Math.addExact(totalBytes, row.size)
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
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.key)
        return resolver.openInputStream(uri)
            ?: throw ImportRejected("A source file could not be read: ${entry.relativePath}")
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
                    // A provider row with no display name has nothing to copy to.
                    val displayName = cursor.getString(name) ?: continue
                    add(DocumentRow(
                        documentId = cursor.getString(id),
                        name = displayName,
                        directory = type == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1,
                        lastModified = if (modified >= 0 && !cursor.isNull(modified)) cursor.getLong(modified) else 0,
                        mimeType = type,
                        flags = if (flags >= 0 && !cursor.isNull(flags)) cursor.getInt(flags) else 0,
                    ))
                }
            }
        } ?: throw ImportRejected("The selected folder could not be read")
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
