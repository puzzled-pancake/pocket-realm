package com.pocketrealm.addons

import android.content.Context
import org.json.JSONObject
import java.util.Locale

enum class AddonInstallSource { BUILTIN, GITHUB, REFERENCE }

data class CatalogAddon(
    val matrixId: String,
    val name: String,
    val category: String,
    val description: String,
    val githubUrl: String?,
    val builtinId: String?,
    val assetPath: String?,
    val clientTarget: String,
    val version: String,
    val verifiedUpdate: String,
    val dateConfidence: String,
    val communitySignal: String,
    val maintenance: String,
    val compatibilityNotes: String,
    val researchSources: List<String>,
    val compatibilityRole: String,
    val handheldScore: Double,
    val handheldVerdict: String,
    val installSource: AddonInstallSource,
) {
    val installable: Boolean get() = installSource != AddonInstallSource.REFERENCE
    val installId: String? get() = when (installSource) {
        AddonInstallSource.BUILTIN -> builtinId
        AddonInstallSource.GITHUB -> githubUrl?.let { runCatching { GitHubRepoRef.parse(it).id }.getOrNull() }
        AddonInstallSource.REFERENCE -> null
    }
}

data class AddonCompatibility(
    val addonA: String,
    val addonB: String,
    val status: String,
    val meaning: String,
    val reason: String,
    val action: String,
    val basis: String,
    val evidenceSources: List<String>,
) {
    fun other(matrixId: String): String? = when (matrixId) {
        addonA -> addonB
        addonB -> addonA
        else -> null
    }
}

class AddonCatalog private constructor(
    val researchedAt: String,
    /** The flavor this catalog serves ("vanilla"/"tbc"/"wotlk") — the
     * manifest key the asset resolves for. */
    val flavor: String,
    /** The per-flavor honest copy the add-ons screens render: the
     * AndroidPort exclusion note on the tbc/wotlk catalogs. */
    val flavorNote: String?,
    /** The catalog's own recommended starting set (the vanilla trio is the
     * frozen D29 curation; per-flavor catalogs declare theirs). */
    val recommendedIds: List<String>,
    /** The per-recommended-row copy (catalog data on the per-flavor
     * assets; the vanilla trio stays in RECOMMENDATION_REASONS). */
    val recommendationReasons: Map<String, String>,
    val addons: List<CatalogAddon>,
    val compatibilityPairs: List<AddonCompatibility>,
) {
    private val byMatrixId = addons.associateBy(CatalogAddon::matrixId)
    private val byInstallId = addons.mapNotNull { addon -> addon.installId?.let { it to addon } }.toMap()
    private val pairsByAddon = compatibilityPairs
        .flatMap { pair -> listOf(pair.addonA to pair, pair.addonB to pair) }
        .groupBy({ it.first }, { it.second })

    val categories: List<String> = addons.map(CatalogAddon::category).distinct().sorted()
    /** Tested-on-hardware starting set; every item remains individually
     * optional. The vanilla trio is the frozen D29 curation; the
     * per-flavor catalogs declare their own ids (self-pinning data). */
    val recommended: List<CatalogAddon> = recommendedIds.map { id ->
        checkNotNull(byMatrixId[id]) { "Recommended add-on $id is absent from the catalog" }
    }
    /**
     * The per-row recommendation copy. R6-r2-1: the per-flavor catalogs
     * carry their OWN reasons (parse enforces completeness — every
     * recommended id MUST have one, so the Recommended screen's
     * checkNotNull can never fire); the vanilla trio's copy stays in
     * RECOMMENDATION_REASONS.
     */
    fun recommendationReason(matrixId: String): String? =
        flavorReasons[matrixId] ?: RECOMMENDATION_REASONS[matrixId]
    private val flavorReasons: Map<String, String> = recommendationReasons

    fun addon(matrixId: String): CatalogAddon? = byMatrixId[matrixId]
    fun addonForInstalled(installed: InstalledAddon): CatalogAddon? = byInstallId[installed.id]
    fun compatibilityFor(matrixId: String, installed: List<InstalledAddon>): List<Pair<CatalogAddon, AddonCompatibility>> {
        val installedMatrixIds = installed.mapNotNull { addonForInstalled(it)?.matrixId }.toSet()
        return pairsByAddon[matrixId].orEmpty().mapNotNull { pair ->
            val otherId = pair.other(matrixId) ?: return@mapNotNull null
            if (otherId !in installedMatrixIds) return@mapNotNull null
            byMatrixId[otherId]?.let { it to pair }
        }.sortedByDescending { (_, pair) -> compatibilitySeverity(pair.meaning) }
    }

    fun filter(query: String, category: String?): List<CatalogAddon> {
        val needle = query.trim().lowercase(Locale.ROOT)
        return addons.asSequence()
            .filter { category == null || it.category == category }
            .filter { addon ->
                needle.isBlank() || listOf(
                    addon.name, addon.category, addon.description, addon.compatibilityRole,
                    addon.compatibilityNotes, addon.handheldVerdict,
                ).any { needle in it.lowercase(Locale.ROOT) }
            }
            .sortedWith(compareByDescending<CatalogAddon> { it.installable }
                .thenByDescending { it.handheldScore }
                .thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
    }

    companion object {
        private const val CATALOG_ASSET = "addons/catalog-v1.json"
        private const val TBC_CATALOG_ASSET = "addons/catalog-tbc.json"
        private const val WOTLK_CATALOG_ASSET = "addons/catalog-wotlk.json"
        /** The [com.pocketrealm.client.ActiveFlavor] lane suffix naming the
         * catalog resolution. */
        private const val CATALOG_LANE =
            "the add-on catalog resolves the active flavor"
        /** The AndroidPort built-in's row id — vanilla-catalog-owned. */
        private const val ANDROID_PORT_MATRIX_ID = "151"
        private val RECOMMENDED_IDS = listOf(
            "151", "002", "059",
        )
        private val RECOMMENDATION_REASONS = mapOf(
            "151" to "Optional Vanilla 1.12.1 Android Port UI with 6-inch handheld defaults; Pocket Realm supplies its matching 1-8 face/D-pad, target/use shoulder and LT/RT Winlator control map.",
            "002" to "Standalone quest database that reduces map-search and keyboard friction on a 5.5-inch display.",
            // flavor-neutral — the
            // researched 059 entry covers multiple client lines, so the copy
            // must not claim a Vanilla-only download on projected tbc/wotlk
            // lanes (S7.6 owns the per-line resolver extension).
            "059" to "Special two-part install: the player plus a large voice-data " +
                "download, so you read less on a small screen.",
        )

        @Volatile private var cached: Map<String, AddonCatalog>? = null

        /** The catalog asset for [flavorId]: the byte-frozen vanilla
         * v1 asset for the vanilla lane, the new per-flavor assets
         * otherwise. */
        fun catalogAssetFor(flavorId: String): String = when (flavorId) {
            "vanilla" -> CATALOG_ASSET
            "tbc" -> TBC_CATALOG_ASSET
            "wotlk" -> WOTLK_CATALOG_ASSET
            else -> error("no addon catalog asset registered for flavor \"$flavorId\"")
        }

        /**
         * The ACTIVE flavor's catalog: each flavor browses its own
         * researched asset — the byte-frozen vanilla v1 catalog on the
         * vanilla lane (D29, unchanged), the per-flavor catalogs elsewhere.
         * The per-flavor assets' entries all carry that flavor's client
         * line, so the screens' [AddonFlavorCatalog.forFlavor] projection
         * passes every entry through unchanged.
         */
        fun load(context: Context, flavorId: String): AddonCatalog {
            val cache = cached ?: synchronized(this) {
                cached ?: emptyMap<String, AddonCatalog>().also { cached = it }
            }
            cache[flavorId]?.let { return it }
            return synchronized(this) {
                val current = cached ?: emptyMap()
                current[flavorId] ?: context.applicationContext.assets
                    .open(catalogAssetFor(flavorId)).bufferedReader().use { reader ->
                        parse(reader.readText(), flavorId)
                    }.also { cached = current + (flavorId to it) }
            }
        }

        /** The active-flavor form (the screens' seam). */
        fun load(context: Context): AddonCatalog =
            load(context, com.pocketrealm.client.ActiveFlavor.resolve(CATALOG_LANE).flavorId)

        internal fun parse(text: String, flavorId: String = "vanilla"): AddonCatalog {
            val root = JSONObject(text)
            require(root.getInt("schema") == 1) { "Unsupported addon catalog" }
            require(!root.has("bundled")) { "Retired bundled add-on metadata is not allowed" }
            // the asset's own flavor identity must match the
            // requested lane — a swapped or mislabeled per-flavor asset
            // (catalog-wotlk loaded as tbc) refuses here instead of
            // rendering the other lane's researched set.
            if (flavorId != "vanilla") {
                require(root.optString("flavor") == flavorId) {
                    "the catalog asset declares flavor \"${root.optString("flavor")}\" " +
                        "but was loaded for \"$flavorId\" — a swapped per-flavor " +
                        "asset renders the wrong lane's add-on set"
                }
            }
            val addons = parseAddons(root)
            requireAddonPins(root, flavorId, addons)
            val pairs = parsePairs(root, addons)
            requirePairPins(root, flavorId, pairs)
            val recommendedIds: List<String>
            val flavorReasons: Map<String, String>
            if (flavorId == "vanilla") {
                recommendedIds = RECOMMENDED_IDS
                flavorReasons = emptyMap()
            } else {
                val declared = root.getJSONArray("recommendedIds").let { arr ->
                    List(arr.length()) { arr.getString(it) }
                }
                require(declared.all { it in addons.map { addon -> addon.matrixId } }) {
                    "the $flavorId catalog's recommendedIds reference rows outside " +
                        "the catalog (a hand edit)"
                }
                // EVERY recommended row must carry its per-flavor
                // recommendation copy — the Recommended screen's
                // checkNotNull becomes an unreachability invariant enforced
                // at parse instead of a crash on the tbc/wotlk lanes.
                val reasonsRoot = root.optJSONObject("recommendationReasons")
                    ?: error("the $flavorId catalog carries no recommendationReasons")
                val reasons = linkedMapOf<String, String>()
                for (id in declared) {
                    val reason = reasonsRoot.optString(id).takeIf { it.isNotBlank() }
                        ?: error(
                            "the $flavorId catalog's recommended row $id has no " +
                                "recommendationReasons entry — the Recommended " +
                                "screen would crash rendering it",
                        )
                    reasons[id] = reason
                }
                recommendedIds = declared
                flavorReasons = reasons
            }
            return AddonCatalog(
                researchedAt = root.getString("researchedAt"),
                flavor = flavorId,
                flavorNote = root.optString("flavorNote").takeIf {
                    !root.isNull("flavorNote") && it.isNotBlank()
                },
                recommendedIds = recommendedIds,
                recommendationReasons = flavorReasons,
                addons = addons,
                compatibilityPairs = pairs,
            )
        }

        private fun compatibilitySeverity(meaning: String): Int = when {
            "avoid" in meaning.lowercase(Locale.ROOT) -> 4
            "overlap" in meaning.lowercase(Locale.ROOT) -> 3
            "configure" in meaning.lowercase(Locale.ROOT) -> 2
            "dependency" in meaning.lowercase(Locale.ROOT) -> 2
            else -> 1
        }

        private fun parseAddons(root: JSONObject): List<CatalogAddon> {
            val addonsJson = root.getJSONArray("addons")
            return List(addonsJson.length()) { index ->
                val item = addonsJson.getJSONObject(index)
                val clientTarget = item.getString("clientTarget")
                // research text that
                // names a client line inside a NEGATION clause ("explicitly
                // NOT for 2.4.3", "broken since 3.3.5") must be rejected at
                // load — the flavor projection matches version tokens by
                // substring, so a negated mention would list the add-on on
                // the wrong-flavor lane. Fail loud at parse; the research
                // text must positively list supported client lines.
                AddonFlavorCatalog.requirePositiveClientTarget(
                    clientTarget, item.optString("name"),
                )
                CatalogAddon(
                    matrixId = item.getString("matrixId"),
                    name = item.getString("name"),
                    category = item.getString("category"),
                    description = item.getString("description"),
                    githubUrl = item.optString("githubUrl").takeIf { !item.isNull("githubUrl") && it.isNotBlank() },
                    builtinId = item.optString("builtinId").takeIf { !item.isNull("builtinId") && it.isNotBlank() },
                    assetPath = item.optString("assetPath").takeIf { !item.isNull("assetPath") && it.isNotBlank() },
                    clientTarget = clientTarget,
                    version = item.getString("version"),
                    verifiedUpdate = item.getString("verifiedUpdate"),
                    dateConfidence = item.getString("dateConfidence"),
                    communitySignal = item.getString("communitySignal"),
                    maintenance = item.getString("maintenance"),
                    compatibilityNotes = item.getString("compatibilityNotes"),
                    researchSources = item.getJSONArray("researchSources").let { sources ->
                        List(sources.length()) { sources.getString(it) }
                    },
                    compatibilityRole = item.getString("compatibilityRole"),
                    handheldScore = item.getDouble("handheldScore"),
                    handheldVerdict = item.getString("handheldVerdict"),
                    installSource = AddonInstallSource.valueOf(item.getString("installSource").uppercase(Locale.ROOT)),
                ).also { addon ->
                    require(addon.matrixId.matches(Regex("\\d{3}")))
                    require(addon.name.isNotBlank() && addon.category.isNotBlank())
                    require(addon.handheldScore in 0.0..10.0)
                    require(addon.installSource != AddonInstallSource.BUILTIN ||
                        addon.installId != null && addon.assetPath != null)
                    require(addon.installSource != AddonInstallSource.GITHUB || addon.installId != null)
                }
            }
        }

        /**
         * The self-describing per-flavor pins: the byte-frozen vanilla
         * catalog keeps its hard 154-entry requirement; the per-flavor
         * assets declare their own exact counts and the parse fails loud on
         * any mismatch (a hand edit cannot sneak rows in or out).
         */
        private fun requireAddonPins(
            root: JSONObject,
            flavorId: String,
            addons: List<CatalogAddon>,
        ) {
            if (flavorId == "vanilla") {
                require(
                    addons.size == 154 &&
                        addons.map { it.matrixId }.toSet().size == addons.size,
                ) {
                    "the vanilla catalog declares 154 add-ons but carries " +
                        "${addons.size} (a hand edit) — catalog-v1.json is the " +
                        "byte-frozen D29 asset; restore the pinned bytes"
                }
                return
            }
            val expected = root.getInt("expectedAddonCount")
            require(
                addons.size == expected &&
                    addons.map { it.matrixId }.toSet().size == addons.size,
            ) {
                "the $flavorId catalog declares $expected add-ons but carries " +
                    "${addons.size} (a hand edit) — per-flavor catalogs are " +
                    "self-pinning assets"
            }
            // The AndroidPort built-in is a first-class row on every lane:
            // its absence would silently skip the fresh-install seed on that
            // flavor, so the parse fails loud instead.
            val androidPort = requireNotNull(addons.firstOrNull { it.matrixId == ANDROID_PORT_MATRIX_ID }) {
                "the $flavorId catalog must carry the AndroidPort built-in " +
                    "(matrixId $ANDROID_PORT_MATRIX_ID): it installs by " +
                    "default on every game version"
            }
            require(androidPort.installSource == AddonInstallSource.BUILTIN) {
                "the $flavorId catalog's AndroidPort row must stay the " +
                    "built-in install source"
            }
        }

        private fun parsePairs(
            root: JSONObject,
            addons: List<CatalogAddon>,
        ): List<AddonCompatibility> {
            val pairsJson = root.getJSONArray("compatibilityPairs")
            val matrixIds = addons.map { it.matrixId }
            return List(pairsJson.length()) { index ->
                val item = pairsJson.getJSONObject(index)
                AddonCompatibility(
                    addonA = item.getString("addonA"),
                    addonB = item.getString("addonB"),
                    status = item.getString("status"),
                    meaning = item.getString("meaning"),
                    reason = item.getString("reason"),
                    action = item.getString("action"),
                    basis = item.getString("basis"),
                    evidenceSources = item.getJSONArray("evidenceSources").let { sources ->
                        List(sources.length()) { sources.getString(it) }
                    },
                ).also { pair ->
                    require(pair.addonA in matrixIds && pair.addonB in matrixIds)
                    require(pair.addonA != pair.addonB)
                }
            }
        }

        private fun requirePairPins(
            root: JSONObject,
            flavorId: String,
            pairs: List<AddonCompatibility>,
        ) {
            if (flavorId == "vanilla") {
                require(pairs.size == 266)
                return
            }
            val expectedPairs = root.getInt("expectedCompatibilityPairCount")
            require(pairs.size == expectedPairs) {
                "the $flavorId catalog declares $expectedPairs compatibility " +
                    "pairs but carries ${pairs.size} (a hand edit)"
            }
        }
    }
}
