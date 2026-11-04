package com.pocketrealm.client

import java.io.File

/**
 * Launcher-only selections (an installer/downloader folder) are the most
 * common wrong pick. Named constant so the desktop suite can pin the same
 * wording the Android suite asserts (SafClientScannerMessageTest) — the
 * copy is duplicated verbatim here because the SAF scanner itself is
 * android-bound.
 */
internal const val DESKTOP_VAL01_LAUNCHER_ONLY_SELECTION: String =
    "This looks like an installer or launcher folder — it has no WoW.exe. " +
        "Choose the extracted game folder itself (Vanilla, TBC, or WotLK)."

/**
 * The desktop folder twin of SafClientScanner's fast scan — the same
 * identity decision over a plain `java.io.File` directory instead of a
 * SAF tree, with the same resolver-free [Access] seam structure so the
 * ARCHIVE scanner (the desktop ArchiveClientScanner twin) reuses the
 * identical identity logic over entry trees without touching the
 * filesystem. Only the selection root, WoW.exe, and the immediate Data
 * directory are read; import/copy stays a separate operation so rejecting
 * a selection cannot mutate it.
 *
 * The identity logic itself is the SHARED [ClientPeIdentity] parse (compiled
 * from the Android tree via shared-sources.json), so the folder scanner and
 * the archive scanner agree byte-for-byte on what the version resource
 * means, and the wording is pinned by the same registry the Android JVM
 * suite pins.
 */
private const val MPQ_WALK_MAX_DEPTH = 4
private const val MPQ_MAGIC_LEN = 4

private fun collectMpqTree(
    access: DesktopClientScanner.Access,
    data: DesktopClientScanner.Entry,
): LinkedHashMap<String, DesktopClientScanner.Entry> {
    val mpqByRelPath = LinkedHashMap<String, DesktopClientScanner.Entry>()
    fun walkMpqTree(dirId: String, rel: String, depth: Int) {
        if (depth >= MPQ_WALK_MAX_DEPTH) return
        for (entry in access.children(dirId)) {
            val childRel =
                (if (rel.isEmpty()) entry.name else "$rel/${entry.name}").lowercase()
            if (entry.directory) walkMpqTree(entry.id, childRel, depth + 1)
            else if (entry.name.endsWith(".mpq", ignoreCase = true)) {
                mpqByRelPath.putIfAbsent(childRel, entry)
            }
        }
    }
    walkMpqTree(data.id, "", 0)
    return mpqByRelPath
}

internal class DesktopClientScanner {

    data class Result(
        val supported: Boolean,
        val clientId: String?,
        val version: String?,
        val build: Int?,
        val executableSha256: String?,
        val sourceBytesKnown: Long,
        val warnings: List<String>,
        val failures: List<String>,
    )

    internal data class Entry(val id: String, val name: String, val directory: Boolean, val size: Long)

    /** Resolver-free seam (SafClientScanner.Access's twin): the archive scanner
     * adapts its entry tree onto this; the folder scanner adapts a directory. */
    internal interface Access {
        val rootId: String
        fun children(parentId: String): List<Entry>
        fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean = false): ByteArray
    }

    fun scan(root: File): Result = try {
        scanAccess(FileAccess(root))
    } catch (failure: IllegalStateException) {
        // An unreadable/unusable selection degrades to the refusal
        // Result (the archive scanner's scanAccess propagates instead).
        Result(
            supported = false, clientId = null, version = null, build = null,
            executableSha256 = null, sourceBytesKnown = 0L,
            warnings = emptyList(),
            failures = listOf(failure.message ?: "The selected folder could not be read"),
        )
    }

    internal fun scanAccess(access: Access): Result {
        val warnings = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val entries = access.children(access.rootId)
        val wow = entries.singleOrNull { !it.directory && it.name.equals("WoW.exe", ignoreCase = true) }
        if (wow == null) {
            // Launcher-only selections (an installer/downloader folder) are
            // the most common wrong pick: the same wording SafClientScanner
            // pins.
            failures += if (entries.any { it.name.contains("launcher", ignoreCase = true) }) {
                DESKTOP_VAL01_LAUNCHER_ONLY_SELECTION
            } else {
                "WoW.exe was not found in the selected folder"
            }
        }

        var identity: ClientPeIdentity.Identity? = null
        if (wow != null) {
            if (wow.size > MAX_EXE_BYTES) {
                failures += "WoW.exe is too large to scan"
            }
            identity = runCatching { ClientPeIdentity.parse(access.read(wow.id, MAX_EXE_BYTES, rejectTruncation = true)) }
                .onFailure { failures += (it.message ?: "WoW.exe is not a valid game executable") }
                .getOrNull()
        }

        val data = entries.singleOrNull { it.directory && it.name.equals("Data", ignoreCase = true) }
        // Genuine tbc/wotlk trees stage their locale MPQs inside
        // Data/<locale>/: walk Data (bounded depth), key MPQs by their
        // path relative to Data, and judge them with the shared audit.
        val mpqByRelPath = if (data != null) collectMpqTree(access, data) else LinkedHashMap()
        if (data == null) failures += "The Data folder is missing"
        val verdict = ClientDataAudit.audit(identity, mpqByRelPath.keys, EXPECTED_VERSION, dataPresent = data != null) { rel ->
            mpqByRelPath[rel]?.let { entry -> runCatching { access.read(entry.id, MPQ_MAGIC_LEN) }.getOrNull() }
        }
        failures += verdict.failures
        warnings += verdict.warnings
        val flavor = verdict.flavor
        if (flavor != null) {
            if (flavor.knownExeSha256 != null && identity?.sha256 == flavor.knownExeSha256) {
                warnings += "WoW.exe matches the original build ${flavor.build} release"
            } else {
                warnings += "Build confirmed — the client can launch, but optional gameplay tweaks stay off " +
                    "because this WoW.exe differs from the original release"
            }
        }
        if (data != null) warnings += "English (US) locale assumed — no locale folder was found"

        val customDlls = entries.filter {
            !it.directory && it.name.endsWith(".dll", ignoreCase = true) &&
                it.name.lowercase() !in STANDARD_ROOT_DLLS
        }.map { it.name }.sorted()
        if (customDlls.isNotEmpty()) warnings += "Extra files kept alongside the client: ${customDlls.joinToString()}"

        val knownBytes = entries.sumOf { if (!it.directory) it.size else 0L }
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

    /** [Access] over one directory: children are the immediate listing;
     * reads are whole-file with the truncation contract. */
    private class FileAccess(private val root: File) : Access {
        override val rootId: String = root.absolutePath

        override fun children(parentId: String): List<Entry> {
            val directory = File(parentId)
            if (!directory.isDirectory) {
                throw IllegalStateException("The selected folder is not a readable directory")
            }
            return directory.listFiles()?.map { child ->
                Entry(child.absolutePath, child.name, child.isDirectory, if (child.isDirectory) 0L else child.length())
            } ?: throw IllegalStateException("The selected folder could not be read")
        }

        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            val file = File(documentId)
            if (!file.isFile) throw IllegalStateException("A file in the selected folder could not be read")
            val input = file.inputStream()
            return input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (output.size() < maxBytes) {
                    val remaining = maxBytes - output.size()
                    val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                if (rejectTruncation && output.size() == maxBytes && stream.read() >= 0) {
                    throw IllegalArgumentException("WoW.exe is too large to scan")
                }
                output.toByteArray()
            }
        }
    }

    companion object {
        private const val NESTED_CLIENT_DEPTH = 3
        private const val NESTED_SEARCH_MAX_DIRS = 256

        /** [selected] itself when it holds WoW.exe; otherwise the single
         * client folder (WoW.exe beside Data) up to [NESTED_CLIENT_DEPTH]
         * levels down. The shallowest wins; two at that level is ambiguous
         * and leaves [selected] for the scan to refuse with its reasons. */
        fun locateClientRoot(selected: File): File {
            fun isClient(dir: File): Boolean {
                val inner = dir.listFiles().orEmpty()
                return inner.any { it.isFile && it.name.equals("WoW.exe", true) } &&
                    inner.any { it.isDirectory && it.name.equals("Data", true) }
            }
            if (selected.listFiles().orEmpty().any { it.isFile && it.name.equals("WoW.exe", true) }) return selected
            var level = listOf(selected)
            var visited = 0
            repeat(NESTED_CLIENT_DEPTH) {
                val dirs = level.flatMap { dir -> dir.listFiles().orEmpty().filter { it.isDirectory } }
                visited += dirs.size
                if (visited > NESTED_SEARCH_MAX_DIRS) return selected
                val found = dirs.filter(::isClient)
                if (found.isNotEmpty()) return found.singleOrNull() ?: selected
                level = dirs
            }
            return selected
        }

        /** The "expected versions" text shown when a client is not supported. */
        private val EXPECTED_VERSION = ClientPeIdentity.expectedVersions

        // Fast-scan read cap (platform budget, not flavor data) — the same
        // 32 MiB budget SafClientScanner applies.
        private const val MAX_EXE_BYTES = 32 * 1024 * 1024

        private val STANDARD_ROOT_DLLS = setOf(
            "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll",
        )
    }
}
