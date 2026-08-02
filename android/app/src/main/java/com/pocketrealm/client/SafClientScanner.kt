package com.pocketrealm.client

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Read-only SAF fast scan for the Gate-G1 client identity decision.
 *
 * Only the selected root, WoW.exe, and the immediate Data directory are read.
 * Import/copy is deliberately a separate operation so rejecting a selection
 * cannot mutate it or make it a runtime dependency.
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

    fun scan(treeUri: Uri): Result = scan(DocumentsAccess(resolver, treeUri))

    internal fun scan(access: Access): Result {
        val warnings = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val root = access.children(access.rootId)
        val wow = root.singleOrNull { !it.directory && it.name.equals("WoW.exe", true) }
        if (wow == null) {
            failures += if (root.any { it.name.contains("launcher", true) })
                "VAL-01: launcher-only selection; direct WoW.exe is absent"
            else "VAL-01: direct WoW.exe is absent"
        }

        var identity: PeIdentity? = null
        if (wow != null) {
            if (wow.size > MAX_EXE_BYTES) failures += "VAL-02: selected executable exceeds fast-scan limit"
            identity = runCatching { parsePe(access.read(wow.id, MAX_EXE_BYTES, rejectTruncation = true)) }
                .onFailure { failures += (it.message ?: "VAL-02: invalid WoW.exe") }
                .getOrNull()
            if (identity != null && identity.version != EXPECTED_VERSION) {
                failures += "VAL-03: expected $EXPECTED_VERSION, detected ${identity.version ?: "no matching version resource"}"
            }
            if (identity?.version == EXPECTED_VERSION) {
                if (identity.sha256 == KNOWN_EXE_SHA256) warnings += "VAL-03: executable hash matches the inspected build-5875 copy"
                else warnings += "VAL-03: build confirmed but executable hash is not on the local evidence allowlist"
            }
        }

        val data = root.singleOrNull { it.directory && it.name.equals("Data", true) }
        if (data == null) {
            failures += "VAL-04: Data directory is absent"
        } else {
            val children = access.children(data.id)
            val mpqs = children.filter { !it.directory && it.name.endsWith(".mpq", true) }
                .associateBy { it.name.lowercase() }
            val missing = REQUIRED_MPQS.filterNot(mpqs::containsKey)
            if (missing.isNotEmpty()) failures += "VAL-04: missing base MPQ set: ${missing.joinToString()}"
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
        val supported = failures.isEmpty() && identity?.version == EXPECTED_VERSION
        return Result(
            supported = supported,
            clientId = if (supported) ClientRuntimeContract.WOW_5875_ID else null,
            version = identity?.version,
            build = identity?.build,
            executableSha256 = identity?.sha256,
            sourceBytesKnown = knownBytes,
            warnings = warnings.distinct(),
            failures = failures.distinct(),
        )
    }

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

    private data class PeIdentity(val version: String?, val build: Int?, val sha256: String)

    private fun parsePe(bytes: ByteArray): PeIdentity {
        fun u16(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        fun u32(offset: Int): Long = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        require(bytes.size >= 512 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            "VAL-02: WoW.exe is not a PE executable"
        }
        val pe = u32(0x3c).toInt()
        require(pe >= 0 && pe + 26 <= bytes.size && bytes.copyOfRange(pe, pe + 4).contentEquals(byteArrayOf(0x50, 0x45, 0, 0))) {
            "VAL-02: WoW.exe has no valid PE header"
        }
        require(u16(pe + 4) == 0x14c && u16(pe + 24) == 0x10b) {
            "VAL-02: WoW.exe is not IMAGE_FILE_MACHINE_I386 PE32"
        }
        var version: String? = null
        var build: Int? = null
        for (offset in 0..bytes.size - 16) {
            if (bytes[offset] != 0xbd.toByte() || bytes[offset + 1] != 0x04.toByte() ||
                bytes[offset + 2] != 0xef.toByte() || bytes[offset + 3] != 0xfe.toByte()) continue
            if (u32(offset) != 0xfeef04bdL) continue
            val ms = u32(offset + 8)
            val ls = u32(offset + 12)
            val candidate = listOf((ms ushr 16).toInt(), (ms and 0xffff).toInt(), (ls ushr 16).toInt(), (ls and 0xffff).toInt())
            if (candidate[0] == 1 && candidate[1] == 12 && candidate[2] == 1) {
                version = candidate.joinToString(".")
                build = candidate[3]
                break
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return PeIdentity(version, build, digest)
    }

    private companion object {
        const val EXPECTED_VERSION = "1.12.1.5875"
        const val KNOWN_EXE_SHA256 = "b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7"
        const val MAX_EXE_BYTES = 32 * 1024 * 1024
        val REQUIRED_MPQS = setOf("base.mpq", "dbc.mpq", "fonts.mpq", "interface.mpq", "misc.mpq", "model.mpq", "sound.mpq", "speech.mpq", "terrain.mpq", "texture.mpq", "wmo.mpq")
        val STANDARD_ROOT_DLLS = setOf("dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll")
    }
}
