package com.pocketrealm.importer

import com.pocketrealm.client.SafClientScanner

/**
 * Detection over a client archive's raw entry list: finds the client root
 * (WoW.exe at depth ≤ 2 counting the wrapper folder), rebases entries under
 * it, then delegates identity/MPQ/warning checks to [SafClientScanner] via an
 * [SafClientScanner.Access] adapter — every folder-lane VAL code applies
 * unchanged. Adds archive-specific information: variant (wrapper name),
 * locale from WTF/Config.wtf, the informational realm target, and the
 * extraction-policy exclusion list.
 */
internal class ArchiveClientScanner(
    private val limits: ImportLimits = ImportLimits(),
    private val policy: ImportExtractionPolicy = ImportExtractionPolicy(limits),
) {
    /** Raw archive entry: name exactly as stored (may use backslashes). */
    data class RawEntry(val name: String, val directory: Boolean, val size: Long, val encrypted: Boolean = false)

    /** Reads up to [maxBytes] of a raw entry (random access for the folder of entries). */
    fun interface EntryReader {
        fun read(rawName: String, maxBytes: Int): ByteArray
    }

    data class Result(
        val scan: SafClientScanner.Result,
        val variant: String?,
        val locale: String?,
        val realmTarget: String?,
        val excluded: List<ImportExtractionPolicy.Excluded>,
        /** Policy-classified inventory (client-root-relative) for the copy lane. */
        val classified: ImportExtractionPolicy.Classified,
    )

    /** Root finding + policy classification without any entry reads. */
    data class Located(
        val rootPrefix: String,
        val variant: String?,
        val excluded: List<ImportExtractionPolicy.Excluded>,
        val classified: ImportExtractionPolicy.Classified,
    )

    fun locate(entries: List<RawEntry>): Located {
        val rootPrefix = findClientRoot(entries)
        val wrapperItself = rootPrefix.trimEnd('/')
        val rebased = mutableListOf<ImportExtractionPolicy.RawEntry>()
        val outsideRoot = mutableListOf<ImportExtractionPolicy.Excluded>()
        for (entry in entries) {
            val unified = entry.name.replace('\\', '/').trimEnd('/')
            val relative = rebase(entry.name, rootPrefix)
            when {
                relative != null -> rebased += ImportExtractionPolicy.RawEntry(relative, entry.directory, entry.size, entry.name)
                unified.equals(wrapperItself, true) || unified.isEmpty() -> Unit // the wrapper entry itself
                else -> outsideRoot += ImportExtractionPolicy.Excluded(unified, "outside client root")
            }
        }
        val classified = policy.classify(rebased)
        return Located(
            rootPrefix = rootPrefix,
            variant = rootPrefix.takeIf { it.isNotEmpty() }?.trimEnd('/', '\\'),
            excluded = classified.excluded + outsideRoot,
            classified = classified,
        )
    }

    /** Deep detection over entries (ZIP/7z lanes). RAR lanes verify post-extraction. */
    fun scan(entries: List<RawEntry>, reader: EntryReader): Result {
        val located = locate(entries)
        val access = ArchiveAccess(entries, located.rootPrefix, reader)
        val scan = SafClientScanner.scanAccess(access)
        return Result(
            scan = scan,
            variant = located.variant,
            locale = readConfigLocale(entries, located.rootPrefix, reader),
            realmTarget = readRealmTarget(entries, located.rootPrefix, reader),
            excluded = located.excluded,
            classified = located.classified,
        )
    }

    /**
     * Post-extraction identity gate for streaming lanes (RAR): parse the
     * extracted WoW.exe, verify the Data MPQ set headers, and pin the same
     * identity fields the folder/zip lanes pin at detection time.
     */
    fun verifyExtracted(root: java.io.File, warnings: List<String> = emptyList()): SafClientScanner.Result {
        val exe = java.io.File(root, "WoW.exe")
        val failures = mutableListOf<String>()
        var identity: com.pocketrealm.client.ClientPeIdentity.Identity? = null
        if (!exe.isFile) {
            failures += "VAL-01: direct WoW.exe is absent"
        } else {
            identity = com.pocketrealm.client.ClientPeIdentity.parse(exe.readBytes())
            if (identity.version != EXPECTED_VERSION) {
                failures += "VAL-03: expected $EXPECTED_VERSION, detected ${identity.version ?: "no matching version resource"}"
            }
        }
        val data = java.io.File(root, "Data")
        if (!data.isDirectory) {
            failures += "VAL-04: Data directory is absent"
        } else {
            val mpqs = data.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".mpq", true) }
                .associateBy { it.name.lowercase() }
            val missing = REQUIRED_MPQS.filterNot(mpqs::containsKey)
            if (missing.isNotEmpty()) failures += "VAL-04: missing base MPQ set: ${missing.joinToString()}"
            for (file in mpqs.values) {
                val header = file.inputStream().use { runCatching { it.readNBytes(4) }.getOrDefault(ByteArray(0)) }
                if (!header.contentEquals(byteArrayOf(0x4d, 0x50, 0x51, 0x1a))) {
                    failures += "VAL-04: invalid MPQ header: Data/${file.name}"
                }
            }
        }
        val supported = failures.isEmpty() && identity?.version == EXPECTED_VERSION
        return SafClientScanner.Result(
            supported = supported,
            clientId = if (supported) com.pocketrealm.client.ClientRuntimeContract.WOW_5875_ID else null,
            version = identity?.version,
            build = identity?.build,
            executableSha256 = identity?.sha256,
            sourceBytesKnown = exe.length(),
            warnings = (warnings + "VAL-05: FLAT_ENGLISH_LOCALE_INFERRED").distinct(),
            failures = failures.distinct(),
        )
    }

    private companion object {
        private const val EXPECTED_VERSION = "1.12.1.5875"
        private val REQUIRED_MPQS = setOf(
            "base.mpq", "dbc.mpq", "fonts.mpq", "interface.mpq", "misc.mpq", "model.mpq",
            "sound.mpq", "speech.mpq", "terrain.mpq", "texture.mpq", "wmo.mpq",
        )
    }

    /**
     * The client root is the directory that directly contains BOTH WoW.exe
     * and a `Data` directory — at the archive root or inside one wrapper
     * folder. Requiring `Data` disambiguates the ENG-style contamination
     * where `!1.8 Hack/wow.exe` sits beside the real wrapper.
     */
    private fun findClientRoot(entries: List<RawEntry>): String {
        val unified = entries.map { it.name.replace('\\', '/').trimEnd('/') to it }
        fun hasData(parent: String): Boolean {
            val dataPath = if (parent.isEmpty()) "Data" else "$parent/Data"
            return unified.any { (name, entry) -> entry.directory && name.equals(dataPath, true) }
        }
        val candidates = unified.filter { (name, entry) ->
            !entry.directory && name.substringAfterLast('/').equals("WoW.exe", true) &&
                name.count { it == '/' } <= 1
        }.groupBy { it.first.substringBeforeLast('/', "") }
        val rooted = candidates.keys.filter(::hasData)
        when {
            candidates.isEmpty() -> throw ImportRejected(
                "VAL-01: no WoW.exe at the archive root or inside a single wrapper folder — " +
                    "choose the client archive itself, not a launcher, downloader or installer",
            )
            rooted.size > 1 -> throw ImportRejected(
                "VAL-01: multiple WoW.exe candidates with a Data directory " +
                    "(${rooted.joinToString()}) — the archive must contain exactly one client",
            )
            rooted.isEmpty() -> throw ImportRejected(
                "VAL-04: WoW.exe found but no Data directory beside it " +
                    "(${candidates.keys.joinToString()}) — the archive must be the client itself",
            )
            else -> return if (rooted.single().isEmpty()) "" else "${rooted.single()}/"
        }
    }

    private fun rebase(rawName: String, rootPrefix: String): String? {
        val unified = rawName.replace('\\', '/')
        val prefix = if (rootPrefix.isEmpty()) "" else rootPrefix
        val relative = when {
            prefix.isEmpty() -> unified
            unified.trimEnd('/').equals(prefix.trimEnd('/'), true) -> return null // the wrapper entry itself
            unified.startsWith(prefix, true) -> unified.substring(prefix.length)
            else -> return null
        }
        return relative.trimEnd('/').ifEmpty { null }
    }

    private fun lastSegment(name: String): String =
        name.replace('\\', '/').substringAfterLast('/')

    private fun depth(name: String): Int =
        name.replace('\\', '/').count { it == '/' }

    private fun readConfigLocale(entries: List<RawEntry>, rootPrefix: String, reader: EntryReader): String? {
        val config = entries.firstOrNull { entry ->
            !entry.directory && rebase(entry.name, rootPrefix)?.equals("WTF/Config.wtf", true) == true
        } ?: return null
        return reader.read(config.name, 64 * 1024).decodeToString()
            .lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("SET locale", true) || it.startsWith("locale", true) }
            ?.substringAfter('"', "")?.substringBefore('"')?.takeIf { it.isNotEmpty() }
    }

    private fun readRealmTarget(entries: List<RawEntry>, rootPrefix: String, reader: EntryReader): String? {
        val realmlist = entries.firstOrNull { entry ->
            !entry.directory && rebase(entry.name, rootPrefix)?.equals("realmlist.wtf", true) == true
        } ?: return null
        return reader.read(realmlist.name, 4096).decodeToString()
            .lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("set realmlist", true) }
            ?.substringAfter("set realmlist", "")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** SafClientScanner.Access over raw archive entries rooted at the client prefix. */
    private class ArchiveAccess(
        entries: List<RawEntry>,
        rootPrefix: String,
        private val reader: EntryReader,
    ) : SafClientScanner.Access {
        private val tree = mutableMapOf<String, MutableList<SafClientScanner.Entry>>()

        override val rootId: String = ""

        init {
            val explicitDirs = mutableSetOf<Pair<String, String>>() // (parent, name)
            for (entry in entries) {
                val unified = entry.name.replace('\\', '/')
                val prefix = rootPrefix.trimEnd('/')
                val relative = when {
                    prefix.isEmpty() && unified.isNotEmpty() -> unified
                    prefix.isNotEmpty() && unified.equals(prefix, true) -> continue
                    prefix.isNotEmpty() && unified.startsWith("$prefix/", true) -> unified.substring(prefix.length + 1)
                    else -> continue
                }
                if (relative.isEmpty()) continue
                val parent = relative.substringBeforeLast('/', "")
                val name = relative.substringAfterLast('/')
                if (entry.directory) explicitDirs += parent to name
                tree.getOrPut(parent) { mutableListOf() }.add(
                    SafClientScanner.Entry(unified, name, entry.directory, entry.size),
                )
            }
            // Directory entries are implicit in archives; synthesize them for
            // parents that only appear as path prefixes (common in zips).
            tree.keys.toList().forEach { dir ->
                var parent = dir
                while (parent.isNotEmpty()) {
                    val grandParent = parent.substringBeforeLast('/', "")
                    val name = parent.substringAfterLast('/')
                    if ((grandParent to name) !in explicitDirs) {
                        explicitDirs += grandParent to name
                        tree.getOrPut(grandParent) { mutableListOf() }.add(
                            SafClientScanner.Entry(parent, name, true, 0),
                        )
                    }
                    parent = grandParent
                }
            }
        }

        override fun children(parentId: String): List<SafClientScanner.Entry> =
            tree[parentId.trimEnd('/')] ?: emptyList()

        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            val bytes = reader.read(documentId, maxBytes + 1)
            if (rejectTruncation && bytes.size > maxBytes) {
                throw IllegalArgumentException("VAL-02: selected executable exceeds fast-scan limit")
            }
            return bytes.copyOf(minOf(bytes.size, maxBytes))
        }
    }
}
