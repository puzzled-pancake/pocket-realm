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
        "choose the extracted client folder itself (Vanilla, TBC, or WotLK), not the installer/launcher"

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
/** W4: the whole MPQ audit for the desktop folder lane — the locale walk,
 * the per-form missing check, and the R1-1/R2-5-2 exact-set leg (an
 * unknown patch-*.mpq on a non-vanilla lane is VAL-04 naming the file;
 * vanilla keeps its documented tolerance). Extracted from scanAccess for
 * nesting depth. */
private fun auditMpqSet(
    access: DesktopClientScanner.Access,
    data: DesktopClientScanner.Entry,
    flavor: ClientFlavor?,
    failures: MutableList<String>,
) {
    val mpqByRelPath = collectMpqTree(access, data)
    appendMissingMpqFailures(mpqByRelPath, flavor, failures)
    appendExactSetFailures(mpqByRelPath, flavor, failures)
    appendMpqHeaderFailures(access, mpqByRelPath, failures)
}

private const val MPQ_WALK_MAX_DEPTH = 4
private val MPQ_MAGIC: ByteArray =
    byteArrayOf(0x4d.toByte(), 0x50.toByte(), 0x51.toByte(), 0x1a.toByte())
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

/** R2-R4-2: a slash key must resolve at that path RELATIVE to Data (a
 * basename-only match accepted a path-doctored Data/decoy/ set with no
 * Data/enUS/); a bare key by basename. A foreign client (no qualified
 * flavor matched) already failed VAL-03; never compare the qualified MPQ
 * union against it. */
private fun appendMissingMpqFailures(
    mpqByRelPath: Map<String, DesktopClientScanner.Entry>,
    flavor: ClientFlavor?,
    failures: MutableList<String>,
) {
    val missing = flavor?.requiredMpqs.orEmpty().filterNot { key ->
        val lowered = key.lowercase()
        if ('/' in lowered) mpqByRelPath.containsKey(lowered)
        else mpqByRelPath.keys.any { it.substringAfterLast('/') == lowered }
    }
    if (missing.isNotEmpty()) failures += "VAL-04: missing base MPQ set: ${missing.joinToString()}"
}

/** R1-1/R2-5-2: the tbc/wotlk mpq_set notes promise exact-set semantics
 * for the patch train — an unknown patch-*.mpq (not in the flavor's own
 * set) is VAL-04 naming the file, enforced now that both flavors qualify.
 * Vanilla keeps its documented tolerance (its own mpq_set note: the
 * official patch train drops unknown patch MPQs freely). */
private fun appendExactSetFailures(
    mpqByRelPath: Map<String, DesktopClientScanner.Entry>,
    flavor: ClientFlavor?,
    failures: MutableList<String>,
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

private fun appendMpqHeaderFailures(
    access: DesktopClientScanner.Access,
    mpqByRelPath: Map<String, DesktopClientScanner.Entry>,
    failures: MutableList<String>,
) {
    for (entry in mpqByRelPath.values) {
        val header = runCatching {
            access.read(entry.id, MPQ_MAGIC_LEN)
        }.getOrDefault(ByteArray(0))
        if (!header.contentEquals(MPQ_MAGIC)) {
            failures += "VAL-04: invalid MPQ header: Data/${entry.name}"
        }
    }
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
            // W4 (the locale-MPQ defect the tbc E2E surfaced): genuine
            // tbc/wotlk trees stage their locale MPQs inside
            // Data/<locale>/ subdirectories while the registry's required
            // keys carry the locale-slash path — the direct-children-only
            // walk refused EVERY genuine later-expansion client with
            // VAL-04. Walk Data (bounded depth) and match required keys
            // by lowercase basename.
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
            auditMpqSet(access, data, flavor, failures)
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
