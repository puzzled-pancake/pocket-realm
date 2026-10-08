package com.pocketrealm.importer

/**
 * Name policy for archive lanes. Runs over client-root-relative entry names
 * (the source already rebased the wrapper folder): every entry passes
 * [ImportPathPolicy.normalizeEntry], all entries — directories included — join one
 * case-fold namespace (collisions and dir/file type conflicts reject), and
 * root entries outside the known client layouts are excluded with a reason
 * instead of copied ("!1.8 Hack/", launcher scripts, dxvk/, …).
 *
 * Per-flavor root layouts (census lanes item 17): every archive lane
 * classifies BEFORE the flavor is resolved (the PE identity scan runs on the
 * classified inventory; the RAR/Inno lanes verify post-extraction), so the
 * admitted universe is the UNION of the declared layouts — the identity gate
 * (VAL-03, version resource -> qualified flavor) selects the flavor, this
 * policy only decides copy-vs-exclude. Each [RootLayout] row is derived from
 * the measured staged client root for that flavor (census §0; the vanilla
 * row is today's set, byte-for-byte) and lands add-only.
 *
 * Extraction-time byte caps stay in the copy loop; this class is pure naming
 * and layout policy. The folder lane does not use it.
 */
class ImportExtractionPolicy(
    private val limits: ImportLimits = ImportLimits(),
    private val paths: ImportPathPolicy = ImportPathPolicy(limits),
    rootLayouts: Collection<RootLayout> = RootLayout.DECLARED,
) {

    /** Admitted root universe: the union of the declared per-flavor layouts. */
    private val rootFiles: Set<String> = rootLayouts.flatMapTo(mutableSetOf()) { it.rootFiles }
    private val rootDirectories: Set<String> = rootLayouts.flatMapTo(mutableSetOf()) { it.rootDirectories }

    sealed interface Decision {
        object Copy : Decision
        data class ExcludeRoot(val reason: String) : Decision
    }

    /** [rawName] is client-root-relative (pre-normalization); [rawKey] is the
     *  original archive entry name (wrapper included) the source opens by. */
    data class RawEntry(val rawName: String, val directory: Boolean, val size: Long, val rawKey: String = rawName)

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
            val rawKey = raw.rawKey
            val normalized = paths.normalizeEntry(raw.rawName)
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
                if ('/' !in normalized && paths.caseFoldKey(normalized) !in rootDirectories) {
                    excludedRoots += normalized
                    excluded += Excluded(normalized, "non-client root directory")
                    continue
                }
                copy += ImportSourceEntry(rawKey, normalized, true, 0, 0, "dir")
                continue
            }
            if (raw.size < 0) throw ImportRejected("VAL-08: archive entry omitted size: $normalized")
            if ('/' !in normalized && paths.caseFoldKey(normalized) !in rootFiles) {
                excluded += Excluded(normalized, "non-client root file")
                continue
            }
            if (raw.size > limits.maxFileBytes) {
                throw ImportRejected("VAL-08: file exceeds ${limits.maxFileBytes} bytes: $normalized")
            }
            copy += ImportSourceEntry(rawKey, normalized, false, raw.size, 0, "file")
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
}

/**
 * Per-flavor client-root layout (census lanes item 17), derived from the
 * measured staged clients (census §0: `WoW_Classic_ENG_1.12.1`,
 * `ExcaliburWoW Burning Crusade 2.4.3`, `World.of.Warcraft.3.3.5a.Truewow`).
 * Every set is case-folded (`ImportPathPolicy.caseFoldKey` form); rows follow
 * the vanilla row's canonical-superset style — the staged vanilla root does
 * not literally carry `repair.exe`/`fonts`/`movies` either, but the row
 * admits the era's install layout, NOT the runtime debris a staged copy
 * accumulates (`cache/`, `logs/`, `wdb/` stay excluded-with-reason: the
 * client recreates them on first run, so they are not client data).
 *
 * G3-10 add-only: [VANILLA] is the pre-item-17 allowlist byte-for-byte;
 * later-era rows may only ADD entries to the admitted union.
 */
data class RootLayout(
    val flavorId: String,
    val rootFiles: Set<String>,
    val rootDirectories: Set<String>,
) {
    companion object {
        /**
         * The vanilla layout — today's allowlist, unchanged. Launcher/
         * downloader exes stay copy-eligible so the importer's own safe-mode
         * skip journals them.
         */
        val VANILLA: RootLayout = RootLayout(
            flavorId = "vanilla",
            rootFiles = setOf(
                "wow.exe", "wowerror.exe", "repair.exe", "launcher.exe", "backgrounddownloader.exe",
                "realmlist.wtf", "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll",
                "scan.dll", "unicows.dll",
            ),
            rootDirectories = setOf(
                "data", "wtf", "interface", "fonts", "screenshots", "documentation", "errors",
                "movies", "music", "utilities",
            ),
        )

        /**
         * Measured staged TBC root (`ExcaliburWoW … 2.4.3`): `Wow.exe,
         * WowError.exe, Repair.exe, Launcher.exe, realmlist.wtf,
         * DivxDecoder.dll, ijl15.dll, Scan.dll, unicows.dll` over
         * `Data/ Interface/ WTF/` — every entry already inside the vanilla
         * layout, so the TBC row adds NOTHING to the admitted union. It is
         * still declared (not aliased to [VANILLA]) so a future measured
         * TBC-only root artifact lands here without touching either other
         * row.
         */
        val TBC: RootLayout = RootLayout(
            flavorId = "tbc",
            rootFiles = VANILLA.rootFiles,
            rootDirectories = VANILLA.rootDirectories,
        )

        /**
         * Measured staged WotLK root (`World.of.Warcraft.3.3.5a.Truewow`):
         * the vanilla-style files PLUS the later-era root artifacts
         * `Battle.net.dll`, `Blizzard Updater.exe`, `BNUpdate.exe`,
         * `Microsoft.VC80.CRT.manifest` (+ its `msvcr80.dll` pair),
         * `Patch.html` and `Patch.txt`. Directories measure inside the
         * vanilla layout (`Data/ WTF/`; `Cache/`/`Logs/` are runtime debris),
         * so only the file set grows.
         */
        val WOTLK: RootLayout = RootLayout(
            flavorId = "wotlk",
            rootFiles = VANILLA.rootFiles + setOf(
                "battle.net.dll", "blizzard updater.exe", "bnupdate.exe",
                "microsoft.vc80.crt.manifest", "msvcr80.dll", "patch.html", "patch.txt",
            ),
            rootDirectories = VANILLA.rootDirectories,
        )

        /** Every declared flavor's layout; the default policy admits the union. */
        val DECLARED: List<RootLayout> = listOf(VANILLA, TBC, WOTLK)
    }
}
