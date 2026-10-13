package com.pocketrealm.client

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Launcher-only selections (an installer/downloader folder) are the most
 * common wrong pick. Named constant so the JVM suite can pin the "VAL-01: "
 * prefix and the "launcher-only" phrase the instrumented suite asserts.
 */
internal const val VAL01_LAUNCHER_ONLY_SELECTION: String =
    "VAL-01: launcher-only selection; direct WoW.exe is absent — " +
        "choose the extracted client folder itself (Vanilla, TBC, or WotLK), not the installer/launcher"

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
    )

    fun scan(treeUri: Uri): Result = scanAccess(DocumentsAccess(resolver, treeUri))

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
    ) : Access {
        override val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

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
            } ?: error("VAL-01: selected SAF tree is unreadable")
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
                    error("VAL-02: selected executable exceeds fast-scan limit")
                }
                output.toByteArray()
            } ?: error("VAL-01: selected SAF document is unreadable")
        }
    }

    companion object {
        /** R1-1/R2-5-2: the tbc/wotlk mpq_set notes promise exact-set
     * semantics for the patch train — an unknown patch-*.mpq (not in the
     * flavor's own set) is VAL-04 naming the file, enforced now that both
     * flavors qualify. Vanilla keeps its documented tolerance (its own
     * mpq_set note: the official patch train drops unknown patch MPQs
     * freely). Extracted from scanAccess for nesting depth. */
    private fun appendExactSetFailures(
        failures: MutableList<String>,
        flavor: ClientFlavor?,
        mpqByRelPath: Map<String, Entry>,
    ) {
        if (flavor == null || flavor.flavorId == ClientFlavors.VANILLA.flavorId) return
        for ((rel, entry) in mpqByRelPath) {
            val base = rel.substringAfterLast('/')
            val known = flavor.requiredMpqs.any {
                val lowered = it.lowercase()
                lowered == rel || lowered.substringAfterLast('/') == base
            }
            if (!known && base.startsWith("patch-") && base.endsWith(".mpq")) {
                failures += "VAL-04: unknown patch MPQ not in the exact set: Data/$rel"
            }
        }
    }

    internal fun scanAccess(access: Access): Result {
            val warnings = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val root = access.children(access.rootId)
            val wow = root.singleOrNull { !it.directory && it.name.equals("WoW.exe", true) }
            if (wow == null) {
                failures += if (root.any { it.name.contains("launcher", true) })
                    VAL01_LAUNCHER_ONLY_SELECTION
                else "VAL-01: direct WoW.exe is absent"
            }

            var identity: ClientPeIdentity.Identity? = null
            var flavor: ClientFlavor? = null
            if (wow != null) {
                if (wow.size > MAX_EXE_BYTES) failures += "VAL-02: selected executable exceeds fast-scan limit"
                identity = runCatching { ClientPeIdentity.parse(access.read(wow.id, MAX_EXE_BYTES, rejectTruncation = true)) }
                    .onFailure { failures += (it.message ?: "VAL-02: invalid WoW.exe") }
                    .getOrNull()
                flavor = ClientPeIdentity.qualifiedFlavor(identity)
                if (identity != null && flavor == null) {
                    failures += "VAL-03: expected $EXPECTED_VERSION, detected ${ClientPeIdentity.detected(identity)}"
                }
                if (flavor != null) {
                    if (flavor.knownExeSha256 != null && identity?.sha256 == flavor.knownExeSha256) {
                        warnings += "VAL-03: executable hash matches the inspected build-${flavor.build} copy"
                    } else {
                        warnings += "VAL-03: build confirmed; this WoW.exe is accepted for ${flavor.flavorId} launch, but optional executable tweaks require the inspected byte layout"
                    }
                }
            }

            val data = root.singleOrNull { it.directory && it.name.equals("Data", true) }
            if (data == null) {
                failures += "VAL-04: Data directory is absent"
            } else {
                // W4 (the locale-MPQ defect the tbc E2E surfaced): genuine
                // tbc/wotlk trees stage their locale MPQs inside
                // Data/<locale>/ subdirectories while the registry's
                // required keys carry the locale-slash path — the
                // direct-children-only walk refused EVERY genuine
                // later-expansion client with VAL-04. Walk Data (bounded
                // depth) and match each required key by its own form: a
                // slash key ("enus/locale-enus.mpq") must resolve at that
                // path RELATIVE to Data (R2-R4-2: a basename-only match
                // accepted a path-doctored set — Data/decoy/ with no
                // Data/enUS/ — whose client cannot load its data), a bare
                // key matches by basename (the vanilla flat set).
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
                walkMpqTree(data.id, "", 0)
                // A foreign client (no qualified flavor matched) already failed
                // VAL-03; never compare the qualified MPQ union against it.
                val missing = flavor?.requiredMpqs.orEmpty().filterNot { key ->
                    val lowered = key.lowercase()
                    if ('/' in lowered) mpqByRelPath.containsKey(lowered)
                    else mpqByRelPath.keys.any { it.substringAfterLast('/') == lowered }
                }
                appendExactSetFailures(failures, flavor, mpqByRelPath)
                if (missing.isNotEmpty()) failures += "VAL-04: missing base MPQ set: ${missing.joinToString()}"
                val mpqs = mpqByRelPath
                for (entry in mpqs.values) {
                    val header = runCatching { access.read(entry.id, 4) }.getOrDefault(ByteArray(0))
                    if (!header.contentEquals(byteArrayOf(0x4d, 0x50, 0x51, 0x1a))) {
                        failures += "VAL-04: invalid MPQ header: Data/${entry.name}"
                    }
                }
                warnings += "VAL-05: FLAT_ENGLISH_LOCALE_INFERRED"
            }

            val customDlls = root.filter {
                !it.directory && it.name.endsWith(".dll", true) && it.name.lowercase() !in STANDARD_ROOT_DLLS
            }.map { it.name }.sorted()
            if (customDlls.isNotEmpty()) warnings += "VAL-10: unrecognized root DLLs: ${customDlls.joinToString()}"

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

        /** VAL-03 "expected" text: the qualified flavors' version resources. */
        private val EXPECTED_VERSION = ClientPeIdentity.expectedVersions

        // Fast-scan read cap (platform budget, not flavor data).
        private const val MAX_EXE_BYTES = 32 * 1024 * 1024
        private val STANDARD_ROOT_DLLS = setOf("dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll")
    }
}
