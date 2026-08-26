package com.pocketrealm.importer

/**
 * Name policy for archive lanes. Runs over client-root-relative entry names
 * (the source already rebased the wrapper folder): every entry passes
 * [ImportPathPolicy.normalize], all entries — directories included — join one
 * case-fold namespace (collisions and dir/file type conflicts reject), and
 * root entries outside the known vanilla client layout are excluded with a
 * reason instead of copied ("!1.8 Hack/", launcher scripts, dxvk/, …).
 *
 * Extraction-time byte caps stay in the copy loop; this class is pure naming
 * and layout policy. The folder lane does not use it.
 */
class ImportExtractionPolicy(
    private val limits: ImportLimits = ImportLimits(),
    private val paths: ImportPathPolicy = ImportPathPolicy(limits),
) {

    sealed interface Decision {
        object Copy : Decision
        data class ExcludeRoot(val reason: String) : Decision
    }

    data class RawEntry(val rawName: String, val directory: Boolean, val size: Long)

    data class Excluded(val relativePath: String, val reason: String)

    data class Classified(
        val entries: List<ImportSourceEntry>,
        val excluded: List<Excluded>,
        val fileCount: Int,
        val totalBytes: Long,
    )

    /** Classify a full raw entry list; rejects unsafe names, fold conflicts, and size-cap violations. */
    fun classify(entries: List<RawEntry>): Classified {
        val folded = mutableMapOf<String, Pair<String, Boolean>>()
        val copy = mutableListOf<ImportSourceEntry>()
        val excluded = mutableListOf<Excluded>()
        val excludedRoots = mutableSetOf<String>()
        var fileCount = 0
        var totalBytes = 0L
        for (raw in entries.sortedBy { it.rawName }) {
            val normalized = normalizeEntry(raw.rawName)
            val key = paths.caseFoldKey(normalized)
            val previous = folded.putIfAbsent(key, normalized to raw.directory)
            if (previous != null) {
                if (previous.second != raw.directory) {
                    throw ImportRejected("VAL-06: directory/file type conflict: '${previous.first}' and '$normalized'")
                }
                throw ImportRejected("VAL-06: case-fold collision: '${previous.first}' and '$normalized'")
            }
            val rootName = normalized.substringBefore('/')
            val underExcludedRoot = excludedRoots.any { root -> root.equals(rootName, true) }
            if (underExcludedRoot) {
                excluded += Excluded(normalized, "below excluded root directory")
                continue
            }
            if (raw.directory) {
                if ('/' !in normalized && paths.caseFoldKey(normalized) !in ROOT_DIRECTORIES) {
                    excludedRoots += normalized
                    excluded += Excluded(normalized, "non-client root directory")
                    continue
                }
                copy += ImportSourceEntry(normalized, normalized, true, 0, 0, "dir")
                continue
            }
            if (raw.size < 0) throw ImportRejected("VAL-08: archive entry omitted size: $normalized")
            if ('/' !in normalized && paths.caseFoldKey(normalized) !in ROOT_FILES) {
                excluded += Excluded(normalized, "non-client root file")
                continue
            }
            if (raw.size > limits.maxFileBytes) {
                throw ImportRejected("VAL-08: file exceeds ${limits.maxFileBytes} bytes: $normalized")
            }
            copy += ImportSourceEntry(normalized, normalized, false, raw.size, 0, "file")
            fileCount++
            if (fileCount > limits.maxFiles) throw ImportRejected("VAL-08: file count exceeds ${limits.maxFiles}")
            totalBytes = Math.addExact(totalBytes, raw.size)
            if (totalBytes > limits.maxTotalBytes) {
                throw ImportRejected("VAL-08: source exceeds ${limits.maxTotalBytes} bytes")
            }
        }
        if (copy.size > limits.maxEntries) {
            throw ImportRejected("VAL-08: source entry count exceeds ${limits.maxEntries}")
        }
        return Classified(copy, excluded, fileCount, totalBytes)
    }

    /** Windows-illegal trailing dot/space survives NFKC and would evade the importer's root-executable exclusion. */
    private fun normalizeEntry(rawName: String): String {
        val normalized = paths.normalize(rawName)
        normalized.split('/').forEach { component ->
            if (component.length > 1 && (component.last() == '.' || component.last() == ' ')) {
                throw ImportRejected("VAL-07: trailing dot or space in component: '$component'")
            }
        }
        return normalized
    }

    companion object {
        /** Vanilla-layout root files (folded). Launcher/downloader exes stay
         *  copy-eligible so the importer's own safe-mode skip journals them. */
        private val ROOT_FILES = setOf(
            "wow.exe", "wowerror.exe", "repair.exe", "launcher.exe", "backgrounddownloader.exe",
            "realmlist.wtf", "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll",
            "scan.dll", "unicows.dll",
        )
        private val ROOT_DIRECTORIES = setOf(
            "data", "wtf", "interface", "fonts", "screenshots", "documentation", "errors",
            "movies", "music", "utilities",
        )
    }
}
