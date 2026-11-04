package com.pocketrealm.client

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Launcher-only selections (an installer/downloader folder) are the most
 * common wrong pick. Named constant so the JVM and instrumented suites can
 * pin the wording.
 */
internal const val VAL01_LAUNCHER_ONLY_SELECTION: String =
    "This looks like an installer or launcher folder — it has no WoW.exe. " +
        "Choose the extracted game folder itself (Vanilla, TBC, or WotLK)."

/** Read-only SAF fast scan for the client identity decision.
 *
 * Only the selected root, WoW.exe, and the immediate Data directory are read.
 * Import/copy is deliberately a separate operation so rejecting a selection
 * cannot mutate it or make it a runtime dependency.
 *
 * The identity logic lives in the companion [scanAccess] (resolver-free) so
 * the archive lane reuses it without touching SAF.
 */
internal class SafClientScanner(private val resolver: ContentResolver) {
    data class Result(
        val supported: Boolean,
        val clientId: String?,
        val version: String?,
        val build: Int?,
        val executableSha256: String?,
        val sourceBytesKnown: Long,
        val warnings: List<String>,
        val failures: List<String>,
        val sourceMutated: Boolean = false,
        val sourceRuntimeDependency: Boolean = false,
        /** The client folder's document id when the selection holds the
         * client one or more levels down; null = the selection itself. */
        val clientRootDocumentId: String? = null,
    )

    /** Scans the selection; when it is not itself a client folder but holds
     * exactly one (up to [NESTED_CLIENT_DEPTH] levels down — a picked parent
     * folder, a "World of Warcraft/World of Warcraft" double wrap), that
     * client folder is scanned instead. */
    fun scan(treeUri: Uri): Result {
        val top = DocumentsAccess(resolver, treeUri)
        val direct = scanAccess(top)
        if (direct.supported) return direct
        val nested = findNestedClientRoot(top) ?: return direct
        val scanned = scanAccess(DocumentsAccess(resolver, treeUri, nested.first))
        return scanned.copy(
            clientRootDocumentId = nested.first,
            warnings = (listOf("Using the client folder '${nested.second}' found inside the selection") +
                scanned.warnings).distinct(),
        )
    }

    internal fun scan(access: Access): Result = scanAccess(access)

    internal data class Entry(val id: String, val name: String, val directory: Boolean, val size: Long)

    internal interface Access {
        val rootId: String
        fun children(parentId: String): List<Entry>
        fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean = false): ByteArray
    }

    private class DocumentsAccess(
        private val resolver: ContentResolver,
        private val treeUri: Uri,
        rootDocumentId: String? = null,
    ) : Access {
        override val rootId: String = rootDocumentId ?: DocumentsContract.getTreeDocumentId(treeUri)

        override fun children(parentId: String): List<Entry> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            return resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(projection[0])
                val name = cursor.getColumnIndexOrThrow(projection[1])
                val mime = cursor.getColumnIndexOrThrow(projection[2])
                val size = cursor.getColumnIndex(projection[3])
                buildList {
                    while (cursor.moveToNext()) add(Entry(
                        cursor.getString(id), cursor.getString(name),
                        cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else -1,
                    ))
                }
            } ?: error("The selected folder could not be read")
        }

        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            return resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (output.size() < maxBytes) {
                    val remaining = maxBytes - output.size()
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                if (rejectTruncation && output.size() == maxBytes && input.read() >= 0) {
                    error("WoW.exe is too large to scan")
                }
                output.toByteArray()
            } ?: error("A file in the selected folder could not be read")
        }
    }

    companion object {
    /** How far below the selection a lone client folder is looked for. */
    internal const val NESTED_CLIENT_DEPTH = 3
    private const val NESTED_SEARCH_MAX_DIRS = 256

    /** The single folder holding WoW.exe beside Data below [access]'s root
     * (shallowest level wins; two at that level is ambiguous → null), as
     * (document id, relative path). */
    internal fun findNestedClientRoot(access: Access): Pair<String, String>? {
        var level = listOf(access.rootId to "")
        var visited = 0
        repeat(NESTED_CLIENT_DEPTH) {
            val next = mutableListOf<Pair<String, String>>()
            val found = mutableListOf<Pair<String, String>>()
            for ((id, path) in level) {
                val dirs = runCatching { access.children(id) }.getOrDefault(emptyList()).filter { it.directory }
                for (dir in dirs) {
                    if (++visited > NESTED_SEARCH_MAX_DIRS) return null
                    val rel = if (path.isEmpty()) dir.name else "$path/${dir.name}"
                    val inner = runCatching { access.children(dir.id) }.getOrDefault(emptyList())
                    val isClient = inner.any { !it.directory && it.name.equals("WoW.exe", true) } &&
                        inner.any { it.directory && it.name.equals("Data", true) }
                    if (isClient) found += dir.id to rel else next += dir.id to rel
                }
            }
            if (found.isNotEmpty()) return found.singleOrNull()
            level = next
        }
        return null
    }

    internal fun scanAccess(access: Access): Result {
            val warnings = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val root = access.children(access.rootId)
            val wow = root.singleOrNull { !it.directory && it.name.equals("WoW.exe", true) }
            if (wow == null) {
                failures += if (root.any { it.name.contains("launcher", true) })
                    VAL01_LAUNCHER_ONLY_SELECTION
                else "WoW.exe was not found in the selected folder"
            }

            var identity: ClientPeIdentity.Identity? = null
            if (wow != null) {
                if (wow.size > MAX_EXE_BYTES) failures += "WoW.exe is too large to scan"
                identity = runCatching { ClientPeIdentity.parse(access.read(wow.id, MAX_EXE_BYTES, rejectTruncation = true)) }
                    .onFailure { failures += (it.message ?: "WoW.exe is not a valid game executable") }
                    .getOrNull()
            }

            val data = root.singleOrNull { it.directory && it.name.equals("Data", true) }
            // Genuine tbc/wotlk trees stage their locale MPQs inside
            // Data/<locale>/, so Data is walked (bounded depth) and every
            // MPQ is keyed by its path relative to Data.
            val mpqByRelPath = LinkedHashMap<String, SafClientScanner.Entry>()
            fun walkMpqTree(dirId: String, rel: String, depth: Int) {
                if (depth > 4) return
                for (entry in access.children(dirId)) {
                    val childRel = (if (rel.isEmpty()) entry.name else "$rel/${entry.name}").lowercase()
                    if (entry.directory) walkMpqTree(entry.id, childRel, depth + 1)
                    else if (entry.name.endsWith(".mpq", true)) {
                        mpqByRelPath.putIfAbsent(childRel, entry)
                    }
                }
            }
            if (data == null) failures += "The Data folder is missing" else walkMpqTree(data.id, "", 0)
            val verdict = ClientDataAudit.audit(identity, mpqByRelPath.keys, EXPECTED_VERSION, dataPresent = data != null) { rel ->
                mpqByRelPath[rel]?.let { entry -> runCatching { access.read(entry.id, 4) }.getOrNull() }
            }
            failures += verdict.failures
            warnings += verdict.warnings
            val flavor = verdict.flavor
            if (flavor != null) {
                if (flavor.knownExeSha256 != null && identity?.sha256 == flavor.knownExeSha256) {
                    warnings += "WoW.exe matches the original build ${flavor.build} release"
                } else {
                    warnings += "Build confirmed — the client can launch, but optional gameplay tweaks stay off because this WoW.exe differs from the original release"
                }
            }
            if (data != null) warnings += "English (US) locale assumed — no locale folder was found"

            val customDlls = root.filter {
                !it.directory && it.name.endsWith(".dll", true) && it.name.lowercase() !in STANDARD_ROOT_DLLS
            }.map { it.name }.sorted()
            if (customDlls.isNotEmpty()) warnings += "Extra files kept alongside the client: ${customDlls.joinToString()}"

            val knownBytes = root.sumOf { if (it.size >= 0) it.size else 0L }
            val supported = failures.isEmpty() && flavor != null
            return Result(
                supported = supported,
                clientId = if (supported) flavor?.clientId else null,
                version = identity?.version,
                build = identity?.build,
                executableSha256 = identity?.sha256,
                sourceBytesKnown = knownBytes,
                warnings = warnings.distinct(),
                failures = failures.distinct(),
            )
        }

        /** The "expected versions" text shown when a client is not supported. */
        private val EXPECTED_VERSION = ClientPeIdentity.expectedVersions

        // Fast-scan read cap (platform budget, not flavor data).
        private const val MAX_EXE_BYTES = 32 * 1024 * 1024
        private val STANDARD_ROOT_DLLS = setOf("dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll")
    }
}
