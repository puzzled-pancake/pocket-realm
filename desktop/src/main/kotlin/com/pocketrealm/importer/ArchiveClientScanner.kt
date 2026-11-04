package com.pocketrealm.importer

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientPeIdentity
import com.pocketrealm.client.DesktopClientScanner
import java.io.File

/**
 * the desktop twin of the Android importer/ArchiveClientScanner —
 * detection over a client archive's raw entry list: finds the client root
 * (WoW.exe at depth ≤ 2 counting the wrapper folder), rebases entries under
 * it, then delegates identity/MPQ/warning checks to the DESKTOP
 * [DesktopClientScanner] via its [DesktopClientScanner.Access] seam — every
 * folder-lane VAL code applies unchanged. Adds archive-specific information:
 * variant (wrapper name), locale from WTF/Config.wtf, the informational
 * realm target, and the extraction-policy exclusion list.
 *
 * The root-finding/policy half is ported verbatim from the Android original
 * (unshared: its Result embeds the SAF scanner); the identity half resolves
 * through the same shared [ClientPeIdentity] parse, so both platforms'
 * scanners refuse the same archives byte-for-byte.
 */
internal @Suppress("TooManyFunctions")
class ArchiveClientScanner(
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
        val scan: DesktopClientScanner.Result,
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

    /** Deep detection over entries (ZIP/7z lanes). Installer lanes verify post-extraction. */
    fun scan(entries: List<RawEntry>, reader: EntryReader): Result {
        val located = locate(entries)
        val access = ArchiveAccess(entries, located.rootPrefix, reader)
        val scan = DesktopClientScanner().scanAccess(access)
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
     * Post-extraction identity gate for streaming lanes (the Inno installer
     * lane): parse the extracted WoW.exe, verify the Data MPQ set headers,
     * and pin the same identity fields the folder/zip lanes pin at detection
     * time.
     */
    fun verifyExtracted(root: File, warnings: List<String> = emptyList()): DesktopClientScanner.Result {
        val children = root.listFiles().orEmpty()
        val exe = children.firstOrNull { it.isFile && it.name.equals("WoW.exe", true) } ?: File(root, "WoW.exe")
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var identity: ClientPeIdentity.Identity? = null
        if (!exe.isFile) {
            failures += "WoW.exe was not found in the archive"
        } else {
            identity = runCatching { ClientPeIdentity.parse(exe.readBytes()) }
                .onFailure { failures += (it.message ?: "WoW.exe is not a valid game executable") }
                .getOrNull()
        }
        val data = children.firstOrNull { it.isDirectory && it.name.equals("Data", true) }
        if (data == null) failures += "The Data folder is missing"
        val verdict = auditMpqSet(identity, data)
        failures += verdict.failures
        notes += verdict.warnings
        val flavor = verdict.flavor
        val supported = failures.isEmpty() && flavor != null
        return DesktopClientScanner.Result(
            supported = supported,
            clientId = if (supported) flavor?.clientId else null,
            version = identity?.version,
            build = identity?.build,
            executableSha256 = identity?.sha256,
            sourceBytesKnown = exe.length(),
            warnings = (warnings + notes + "English (US) locale assumed — no locale folder was found").distinct(),
            failures = failures.distinct(),
        )
    }

    private companion object {
        /** The "expected versions" text shown when a client is not supported. */
        private val EXPECTED_VERSION = ClientPeIdentity.expectedVersions
    }

    /**
     * The client root is the directory that directly contains BOTH WoW.exe
     * and a `Data` directory — at the archive root or inside one wrapper
     * folder. Requiring `Data` disambiguates the ENG-style contamination
     * where `!1.8 Hack/wow.exe` sits beside the real wrapper.
     */
    private val MAX_WRAPPER_DEPTH = 3

    private fun findClientRoot(entries: List<RawEntry>): String {
        val unified = entries.map { it.name.replace('\\', '/').trimEnd('/') to it }
        fun hasData(parent: String): Boolean {
            val dataPath = if (parent.isEmpty()) "Data" else "$parent/Data"
            // a real client zipped without explicit directory
            // entries (python zipfile / many Unix tools) still carries
            // every file path through Data/ - derive the directory from
            // the file paths instead of demanding a literal dir entry.
            return unified.any { (name, _) ->
                name.equals(dataPath, true) || name.startsWith("$dataPath/", true)
            }
        }
        val candidates = unified.filter { (name, entry) ->
            !entry.directory && name.substringAfterLast('/').equals("WoW.exe", true) &&
                name.count { it == '/' } <= MAX_WRAPPER_DEPTH
        }.groupBy { it.first.substringBeforeLast('/', "") }
        // Up to MAX_WRAPPER_DEPTH wrapper folders ("WoW 3.3.5a/World of
        // Warcraft/Wow.exe"); the shallowest client root wins, and only two
        // roots at that same depth are ambiguous.
        val withData = candidates.keys.filter(::hasData)
        val shallowest = withData.minOfOrNull { root -> if (root.isEmpty()) 0 else root.count { it == '/' } + 1 }
        val rooted = withData.filter { root -> (if (root.isEmpty()) 0 else root.count { it == '/' } + 1) == shallowest }
        when {
            candidates.isEmpty() -> throw ImportRejected(
                "WoW.exe was not found at the archive root or inside up to $MAX_WRAPPER_DEPTH wrapper folders — " +
                    "choose the client archive itself, not a launcher, downloader or installer",
            )
            rooted.size > 1 -> throw ImportRejected(
                "Multiple WoW.exe candidates with a Data directory " +
                    "(${rooted.joinToString()}) — the archive must contain exactly one client",
            )
            rooted.isEmpty() -> throw ImportRejected(
                "WoW.exe was found but there is no Data directory beside it " +
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

    /** [DesktopClientScanner.Access] over raw archive entries rooted at the client prefix. */
    private class ArchiveAccess(
        entries: List<RawEntry>,
        rootPrefix: String,
        private val reader: EntryReader,
    ) : DesktopClientScanner.Access {
        private val tree = mutableMapOf<String, MutableList<DesktopClientScanner.Entry>>()

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
                    DesktopClientScanner.Entry(unified, name, entry.directory, entry.size),
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
                            DesktopClientScanner.Entry(parent, name, true, 0),
                        )
                    }
                    parent = grandParent
                }
            }
        }

        override fun children(parentId: String): List<DesktopClientScanner.Entry> =
            tree[parentId.trimEnd('/')] ?: emptyList()

        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            val bytes = reader.read(documentId, maxBytes + 1)
            if (rejectTruncation && bytes.size > maxBytes) {
                throw IllegalArgumentException("WoW.exe is too large to scan")
            }
            return bytes.copyOf(minOf(bytes.size, maxBytes))
        }
    }
}

/** The shared identity + MPQ audit ([com.pocketrealm.client.ClientDataAudit])
 * over an extracted Data tree (bounded-depth walk, locale subdirectories
 * included). */
private fun auditMpqSet(
    identity: ClientPeIdentity.Identity?,
    data: File?,
): com.pocketrealm.client.ClientDataAudit.Verdict {
    val mpqByRelPath = LinkedHashMap<String, File>()
    fun walkMpqTree(dir: File, rel: String, depth: Int) {
        if (depth > MPQ_WALK_DEPTH) return
        for (child in dir.listFiles().orEmpty()) {
            val childRel = (if (rel.isEmpty()) child.name else "$rel/${child.name}").lowercase()
            if (child.isDirectory) walkMpqTree(child, childRel, depth + 1)
            else if (child.name.endsWith(".mpq", true)) mpqByRelPath.putIfAbsent(childRel, child)
        }
    }
    if (data != null) walkMpqTree(data, "", 0)
    return com.pocketrealm.client.ClientDataAudit.audit(
        identity, mpqByRelPath.keys, ClientPeIdentity.expectedVersions, dataPresent = data != null,
    ) { rel ->
        mpqByRelPath[rel]?.let { file -> runCatching { file.inputStream().use { it.readNBytes(MPQ_PROBE_BYTES) } }.getOrNull() }
    }
}

private const val MPQ_WALK_DEPTH = 4
private const val MPQ_PROBE_BYTES = 4
