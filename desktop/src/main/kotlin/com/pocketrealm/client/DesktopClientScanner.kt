package com.pocketrealm.client

import java.io.File

/**
 * Launcher-only selections (an installer/downloader folder) are the most
 * common wrong pick. Named constant so the desktop suite can pin the
 * "VAL-01: " prefix and the "launcher-only" phrase the Android JVM suite
 * asserts (SafClientScannerMessageTest) — the copy is duplicated verbatim
 * here because the SAF scanner itself is android-bound (DESK-52's twin).
 */
internal const val DESKTOP_VAL01_LAUNCHER_ONLY_SELECTION: String =
    "VAL-01: launcher-only selection; direct WoW.exe is absent — " +
        "choose the extracted 1.12.1 client folder itself, not the installer/launcher"

/**
 * DESK-52/68: the desktop folder-lane twin of SafClientScanner's fast scan —
 * the same identity decision (VAL-01..VAL-05, VAL-10) over a plain
 * `java.io.File` directory instead of a SAF tree, with the same resolver-free
 * [Access] seam structure so the ARCHIVE lane (the desktop
 * ArchiveClientScanner twin, DESK-97) reuses the identical identity logic
 * over entry trees without touching the filesystem. Only the selection
 * root, WoW.exe, and the immediate Data directory are read; import/copy
 * stays a separate operation so rejecting a selection cannot mutate it.
 *
 * The identity logic itself is the SHARED [ClientPeIdentity] parse (compiled
 * from the Android tree via shared-sources.json), so the folder scanner and
 * the archive scanner agree byte-for-byte on what the version resource
 * means, and the VAL wording is pinned by the same registry the Android JVM
 * suite pins.
 */
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

    /** Resolver-free seam (SafClientScanner.Access's twin): the archive lane
     * adapts its entry tree onto this; the folder lane adapts a directory. */
    internal interface Access {
        val rootId: String
        fun children(parentId: String): List<Entry>
        fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean = false): ByteArray
    }

    fun scan(root: File): Result = try {
        scanAccess(FileAccess(root))
    } catch (failure: IllegalStateException) {
        // An unreadable/unusable selection degrades to the honest refusal
        // Result (the archive lane's scanAccess propagates instead).
        Result(
            supported = false, clientId = null, version = null, build = null,
            executableSha256 = null, sourceBytesKnown = 0L,
            warnings = emptyList(),
            failures = listOf(failure.message ?: "VAL-01: selected folder is unreadable"),
        )
    }

    internal fun scanAccess(access: Access): Result {
        val warnings = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val entries = access.children(access.rootId)
        val wow = entries.singleOrNull { !it.directory && it.name.equals("WoW.exe", ignoreCase = true) }
        if (wow == null) {
            // Launcher-only selections (an installer/downloader folder) are the
            // most common wrong pick: the same VAL-01 wording SafClientScanner
            // pins (the "launcher-only" phrase included).
            failures += if (entries.any { it.name.contains("launcher", ignoreCase = true) }) {
                DESKTOP_VAL01_LAUNCHER_ONLY_SELECTION
            } else {
                "VAL-01: direct WoW.exe is absent"
            }
        }

        var identity: ClientPeIdentity.Identity? = null
        var flavor: ClientFlavor? = null
        if (wow != null) {
            if (wow.size > MAX_EXE_BYTES) {
                failures += "VAL-02: selected executable exceeds fast-scan limit"
            }
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
                    warnings += "VAL-03: build confirmed; this WoW.exe is accepted for ${flavor.flavorId} launch, " +
                        "but optional executable tweaks require the inspected byte layout"
                }
            }
        }

        val data = entries.singleOrNull { it.directory && it.name.equals("Data", ignoreCase = true) }
        if (data == null) {
            failures += "VAL-04: Data directory is absent"
        } else {
            val mpqs = access.children(data.id)
                .filter { !it.directory && it.name.endsWith(".mpq", ignoreCase = true) }
                .associateBy { it.name.lowercase() }
            // A foreign client (no qualified flavor matched) already failed
            // VAL-03; never compare the qualified MPQ union against it.
            val missing = flavor?.requiredMpqs.orEmpty().filterNot(mpqs::containsKey)
            if (missing.isNotEmpty()) failures += "VAL-04: missing base MPQ set: ${missing.joinToString()}"
            for ((_, entry) in mpqs) {
                val header = runCatching { access.read(entry.id, 4) }.getOrDefault(ByteArray(0))
                if (!header.contentEquals(byteArrayOf(0x4d, 0x50, 0x51, 0x1a))) {
                    failures += "VAL-04: invalid MPQ header: Data/${entry.name}"
                }
            }
            warnings += "VAL-05: FLAT_ENGLISH_LOCALE_INFERRED"
        }

        val customDlls = entries.filter {
            !it.directory && it.name.endsWith(".dll", ignoreCase = true) &&
                it.name.lowercase() !in STANDARD_ROOT_DLLS
        }.map { it.name }.sorted()
        if (customDlls.isNotEmpty()) warnings += "VAL-10: unrecognized root DLLs: ${customDlls.joinToString()}"

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
                throw IllegalStateException("VAL-01: selected folder is not a readable directory")
            }
            return directory.listFiles()?.map { child ->
                Entry(child.absolutePath, child.name, child.isDirectory, if (child.isDirectory) 0L else child.length())
            } ?: throw IllegalStateException("VAL-01: selected folder is unreadable")
        }

        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            val file = File(documentId)
            if (!file.isFile) throw IllegalStateException("VAL-01: selected document is unreadable")
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
                    throw IllegalArgumentException("VAL-02: selected executable exceeds fast-scan limit")
                }
                output.toByteArray()
            }
        }
    }

    companion object {
        /** VAL-03 "expected" text: the qualified flavors' version resources. */
        private val EXPECTED_VERSION = ClientPeIdentity.expectedVersions

        // Fast-scan read cap (platform budget, not flavor data) — the same
        // 32 MiB budget SafClientScanner applies.
        private const val MAX_EXE_BYTES = 32 * 1024 * 1024

        private val STANDARD_ROOT_DLLS = setOf(
            "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll",
        )
    }
}
