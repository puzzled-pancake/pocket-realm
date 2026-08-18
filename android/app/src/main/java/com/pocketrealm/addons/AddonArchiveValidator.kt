package com.pocketrealm.addons

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

internal class AddonArchiveValidator {
    data class Validated(val entries: List<Entry>, val addonFolders: List<String>)
    data class Entry(val archiveName: String, val relativeName: String, val size: Long)

    data class Policy(
        val maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
        val maxExpandedBytes: Long = MAX_EXPANDED_BYTES,
        val maxEntries: Int = MAX_ENTRIES,
        val acceptedInterfaces: Set<Int> = setOf(11200),
        val expectedFolders: Set<String>? = null,
        val requiredTocFields: Map<String, String> = emptyMap(),
        val preferredRootToc: String? = null,
    ) {
        companion object {
            val VANILLA = Policy()
            val VOICEOVER_PLAYER = Policy(
                expectedFolders = setOf("AI_VoiceOver"),
                preferredRootToc = "AI_VoiceOver.toc",
            )
            val VOICEOVER_DATA = Policy(
                maxArchiveBytes = VOICEOVER_DATA_MAX_ARCHIVE_BYTES,
                maxExpandedBytes = VOICEOVER_DATA_MAX_EXPANDED_BYTES,
                acceptedInterfaces = setOf(11200, 100000),
                expectedFolders = setOf("AI_VoiceOverData_Vanilla"),
                requiredTocFields = mapOf(
                    "OptionalDeps" to "AI_VoiceOver_112, AI_VoiceOver",
                    "X-VoiceOver-DataModule-Version" to "1",
                ),
            )
        }
    }

    fun validate(
        archive: File,
        @Suppress("UNUSED_PARAMETER") repositoryName: String,
        policy: Policy = Policy.VANILLA,
        checkpoint: () -> Unit = {},
    ): Validated {
        checkpoint()
        require(archive.isFile && archive.length() in 1..policy.maxArchiveBytes) {
            "Archive is empty or larger than ${policy.maxArchiveBytes / (1024 * 1024)} MiB"
        }
        ZipFile.builder().setFile(archive).get().use { zip ->
            val raw = ArrayList<ZipArchiveEntry>(minOf(policy.maxEntries, 256))
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                checkpoint()
                require(raw.size < policy.maxEntries) { "Archive file count is not supported" }
                raw += entries.nextElement()
            }
            require(raw.isNotEmpty()) { "Archive file count is not supported" }
            val files = raw.filterNot { it.isDirectory }
            require(files.isNotEmpty()) { "Archive contains no files" }
            val normalized = linkedMapOf<String, Pair<ZipArchiveEntry, String>>()
            var total = 0L
            files.forEach { entry ->
                checkpoint()
                validateEntryType(entry)
                val name = normalizePath(entry.name)
                val collisionKey = name.lowercase(Locale.ROOT)
                require(normalized.put(collisionKey, entry to name) == null) {
                    "Archive contains duplicate or case-colliding paths"
                }
                require(entry.size in 0..MAX_FILE_BYTES) { "Archive entry is too large: $name" }
                total = Math.addExact(total, entry.size)
                require(total <= policy.maxExpandedBytes) { "Expanded archive is too large" }
            }

            val names = normalized.values.map { it.second }
            val wrapper = commonWrapper(names)
            val relative = normalized.values.associate { (entry, name) ->
                val stripped = if (wrapper == null) name else name.removePrefix("$wrapper/")
                stripped.lowercase(Locale.ROOT) to (entry to stripped)
            }
            require(relative.keys.none { it.isBlank() }) { "Archive wrapper contains no addon files" }

            val tocFiles = relative.filterKeys { it.endsWith(".toc") }
            require(tocFiles.isNotEmpty()) { "No Vanilla addon .toc file was found" }
            val rootTocs = tocFiles.values.filter { (_, path) -> '/' !in path }
            val rootToc = if (policy.preferredRootToc != null) {
                rootTocs.filter { (_, path) -> path.equals(policy.preferredRootToc, ignoreCase = true) }
                    .also { require(it.size == 1) { "The expected root .toc file is missing or ambiguous" } }
                    .single()
            } else {
                val compatible = rootTocs.filter { (entry, path) ->
                    declaresSupportedInterface(zip, entry, path, policy, checkpoint)
                }
                require(compatible.size <= 1) {
                    "A repository-root addon must contain exactly one Vanilla 1.12 .toc file"
                }
                compatible.singleOrNull()
            }
            val addonFolders = if (rootToc != null) {
                validateToc(zip, rootToc.first, rootToc.second, relative.keys, policy, checkpoint)
                val folder = rootToc.second.substringBeforeLast('.', rootToc.second)
                require(safeFolder(folder) == folder) { "Root TOC name is not a safe addon folder" }
                listOf(folder)
            } else {
                tocFiles.values.mapNotNull { (entry, path) ->
                    checkpoint()
                    val parts = path.split('/')
                    if (parts.size != 2 || !parts[1].removeSuffix(".toc").equals(parts[0], true)) return@mapNotNull null
                    if (!declaresSupportedInterface(zip, entry, path, policy, checkpoint)) return@mapNotNull null
                    validateToc(zip, entry, path, relative.keys, policy, checkpoint)
                    parts[0]
                }.distinctBy { it.lowercase(Locale.ROOT) }
            }
            require(addonFolders.isNotEmpty()) {
                "Each addon must contain Folder/Folder.toc or a repository-root .toc"
            }
            require(addonFolders.size <= MAX_ADDONS) { "Archive contains too many addons" }
            policy.expectedFolders?.let { expected ->
                require(addonFolders.map { it.lowercase(Locale.ROOT) }.toSet() ==
                    expected.map { it.lowercase(Locale.ROOT) }.toSet()) {
                    "Archive does not contain the expected add-on folders"
                }
            }

            val extracted = relative.values.mapNotNull { (source, path) ->
                checkpoint()
                val mapped = if (rootToc != null) {
                    "${addonFolders.single()}/$path"
                } else {
                    val top = path.substringBefore('/')
                    if (addonFolders.none { it.equals(top, true) }) return@mapNotNull null
                    path
                }
                Entry(source.name, mapped, source.size)
            }
            require(extracted.isNotEmpty()) { "No installable addon files remain after validation" }
            return Validated(extracted, addonFolders)
        }
    }

    private fun validateToc(
        zip: ZipFile,
        entry: ZipArchiveEntry,
        path: String,
        allPaths: Set<String>,
        policy: Policy,
        checkpoint: () -> Unit,
    ) {
        checkpoint()
        require(entry.size in 1..MAX_TOC_BYTES) { "TOC is empty or too large: $path" }
        val text = readToc(zip, entry, path)
        val interfaceValues = declaredInterfaces(text)
        require(interfaceValues.any { it in policy.acceptedInterfaces }) {
            "$path does not declare a supported Vanilla interface"
        }
        policy.requiredTocFields.forEach { (field, expected) ->
            val value = Regex("(?im)^##\\s*${Regex.escape(field)}\\s*:\\s*(.+?)\\s*$")
                .find(text)?.groupValues?.get(1)
            require(value.equals(expected, ignoreCase = true)) {
                "$path does not declare the required $field metadata"
            }
        }
        val base = path.substringBeforeLast('/', "")
        text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { declared ->
                checkpoint()
                require('\u0000' !in declared && !declared.startsWith("http", true)) {
                    "TOC contains an unsafe file entry"
                }
                val candidate = normalizePath(listOf(base, declared.replace('\\', '/'))
                    .filter { it.isNotEmpty() }.joinToString("/"))
                    .lowercase(Locale.ROOT)
                require(candidate in allPaths) { "TOC references a missing file: $declared" }
            }
    }

    private fun declaresSupportedInterface(
        zip: ZipFile,
        entry: ZipArchiveEntry,
        path: String,
        policy: Policy,
        checkpoint: () -> Unit,
    ): Boolean {
        checkpoint()
        if (entry.size !in 1..MAX_TOC_BYTES) return false
        return declaredInterfaces(readToc(zip, entry, path)).any { it in policy.acceptedInterfaces }
    }

    private fun readToc(zip: ZipFile, entry: ZipArchiveEntry, path: String): String =
        zip.getInputStream(entry).use { input ->
            val bytes = input.readBytes()
            require(bytes.size.toLong() <= MAX_TOC_BYTES) { "TOC is too large: $path" }
            String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        }

    private fun declaredInterfaces(text: String): Set<Int> =
        Regex("(?im)^##\\s*Interface\\s*:\\s*([0-9, ]+)$")
            .findAll(text)
            .flatMap { it.groupValues[1].split(',', ' ').asSequence() }
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    private fun validateEntryType(entry: ZipArchiveEntry) {
        require(!entry.isUnixSymlink) { "Symbolic links are not accepted" }
        require(!entry.generalPurposeBit.usesEncryption()) { "Encrypted addon archives are not accepted" }
        val mode = entry.unixMode and 0xF000
        require(mode == 0 || mode == 0x8000) { "Only regular files are accepted" }
        // GitHub and Unix ZIP tools commonly preserve the owner's executable
        // bit on Lua/XML/data files. It is archive metadata, not executable
        // content: AddonArchiveExtractor creates fresh private regular files
        // and never restores archive permissions. Keep rejecting symlinks,
        // special files and executable/native filename payloads, but do not
        // reject an otherwise valid WoW add-on solely for this harmless bit.
    }

    private fun commonWrapper(names: List<String>): String? {
        val first = names.first().substringBefore('/', "")
        if (first.isBlank()) return null
        return first.takeIf { wrapper -> names.all { it.startsWith("$wrapper/") } }
    }

    private fun normalizePath(raw: String): String {
        require(raw.isNotBlank() && '\u0000' !in raw && '\\' !in raw) { "Archive contains an invalid path" }
        require(!raw.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) {
            "Archive contains an absolute path"
        }
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim('/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "Archive contains an absolute path"
        }
        val parts = normalized.split('/')
        require(parts.size <= MAX_DEPTH && parts.all { it.isNotBlank() && it != "." && it != ".." && it.length <= 255 }) {
            "Archive contains an unsafe path"
        }
        return parts.joinToString("/")
    }

    private fun safeFolder(value: String): String = value
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        .take(80)
        .ifBlank { "PocketRealmAddon" }

    companion object {
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 512L * 1024 * 1024
        const val MAX_FILE_BYTES = 128L * 1024 * 1024
        const val MAX_TOC_BYTES = 256L * 1024
        const val MAX_ENTRIES = 20_000
        const val MAX_ADDONS = 128
        const val MAX_DEPTH = 16
        const val VOICEOVER_DATA_MAX_ARCHIVE_BYTES = 1536L * 1024 * 1024
        const val VOICEOVER_DATA_MAX_EXPANDED_BYTES = 1536L * 1024 * 1024
    }
}
