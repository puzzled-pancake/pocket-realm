package com.pocketrealm.addons

import org.json.JSONObject
import java.util.Locale

/**
 * Desktop JVM twin of the Android `addons/AddonCatalog` (same package +
 * names; the Android original is Context/assets-coupled, so it stays
 * unshared). The catalog model, parse rules, and recommended trio are kept
 * verbatim so the shared addon suites (AddonCatalogTest, VanillaAddonPinTest)
 * exercise the exact same contract on both builds; only `load()` changes —
 * the bundled `addons/catalog-v1.json` resolves through
 * [DesktopAddonAssets] (jpackage $APPDIR, else the checkout asset tree)
 * instead of Android assets.
 */
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

@Suppress("LongParameterList")
class AddonCatalog private constructor(
    val researchedAt: String,
    val flavor: String,
    val flavorNote: String?,
    val recommendedIds: List<String>,
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
    /** Tested-on-hardware starting set; every item remains individually optional.
     * R6-r2-1: reasons resolve from the catalog's own per-flavor data first
     * (parse enforces completeness), falling back to the vanilla trio map. */
    val recommended: List<CatalogAddon> = recommendedIds.map { id ->
        checkNotNull(byMatrixId[id]) { "Recommended add-on $id is absent from the catalog" }
    }

    fun addon(matrixId: String): CatalogAddon? = byMatrixId[matrixId]
    fun addonForInstalled(installed: InstalledAddon): CatalogAddon? = byInstallId[installed.id]

    fun recommendationReason(matrixId: String): String? =
        recommendationReasons[matrixId] ?: RECOMMENDATION_REASONS[matrixId]

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
        private val RECOMMENDED_IDS = listOf(
            "151", "002", "059",
        )
        private val RECOMMENDATION_REASONS = mapOf(
            "151" to "Optional Vanilla 1.12.1 Android Port UI with 6-inch handheld defaults; Pocket Realm supplies its matching 1-8 face/D-pad, target/use shoulder and LT/RT Winlator control map.",
            "002" to "Standalone quest database that reduces map-search and keyboard friction on a 5.5-inch display.",
            "059" to "Special two-part install: the player plus about 1.2 GB of Vanilla voice data for less small-screen reading.",
        )

        @Volatile private var cached: Map<String, AddonCatalog>? = null

        /** The catalog asset for [flavorId] (mirroring the android
         * lane): the byte-frozen vanilla asset for the vanilla lane, the
         * per-flavor assets otherwise. */
        fun catalogAssetFor(flavorId: String): String = when (flavorId) {
            "vanilla" -> "catalog-v1.json"
            "tbc" -> "catalog-tbc.json"
            "wotlk" -> "catalog-wotlk.json"
            else -> error("no addon catalog asset registered for flavor \"$flavorId\"")
        }

        /**
         * The ACTIVE flavor's bundled catalog (R6-r2-5): the desktop lane
         * browses the same per-flavor researched sets the android lane
         * ships. A missing bundle fails loud — every caller needs the
         * catalog to even name the built-in add-on.
         */
        fun load(flavorId: String): AddonCatalog {
            val cache = cached ?: synchronized(this) {
                cached ?: emptyMap<String, AddonCatalog>().also { cached = it }
            }
            cache[flavorId]?.let { return it }
            return synchronized(this) {
                val current = cached ?: emptyMap()
                current[flavorId] ?: parse(readBundledCatalogText(flavorId), flavorId)
                    .also { cached = current + (flavorId to it) }
            }
        }

        /** The active-flavor form (the screens' seam). */
        fun load(): AddonCatalog = load(
            com.pocketrealm.client.ActiveFlavor.resolve(
                "the desktop add-on catalog resolves the active lane",
            ).flavorId,
        )

        /** Test seam: re-parse and re-cache from an explicit text source. */
        internal fun reloadForTests(text: String, flavorId: String = "vanilla"): AddonCatalog =
            synchronized(this) {
                parse(text, flavorId).also { cached = (cached ?: emptyMap()) + (flavorId to it) }
            }

        private fun readBundledCatalogText(flavorId: String): String {
            val asset = requireNotNull(DesktopAddonAssets.catalogJson(flavorId)) {
                "The bundled add-on catalog (addons/${catalogAssetFor(flavorId)}) is missing"
            }
            return asset.readText(Charsets.UTF_8)
        }

        @Suppress("LongMethod", "CyclomaticComplexMethod")
        internal fun parse(text: String, flavorId: String = "vanilla"): AddonCatalog {
            val root = JSONObject(text)
            require(root.getInt("schema") == 1) { "Unsupported addon catalog" }
            require(!root.has("bundled")) { "Retired bundled add-on metadata is not allowed" }
            // desktop parity: the asset's own flavor identity must
            // match the requested lane.
            if (flavorId != "vanilla") {
                require(root.optString("flavor") == flavorId) {
                    "the catalog asset declares flavor \"${root.optString("flavor")}\" " +
                        "but was loaded for \"$flavorId\" - a swapped per-flavor " +
                        "asset renders the wrong lane's add-on set"
                }
            }
            val addonsJson = root.getJSONArray("addons")
            val addons = List(addonsJson.length()) { index ->
                val item = addonsJson.getJSONObject(index)
                val clientTarget = item.getString("clientTarget")
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
            if (flavorId == "vanilla") {
                require(addons.size == 154 && addons.map { it.matrixId }.toSet().size == addons.size)
            } else {
                val expected = root.getInt("expectedAddonCount")
                require(
                    addons.size == expected &&
                        addons.map { it.matrixId }.toSet().size == addons.size,
                ) {
                    "the $flavorId catalog declares $expected add-ons but carries " +
                        "${addons.size} (a hand edit) - per-flavor catalogs are " +
                        "self-pinning assets"
                }
                require(addons.none { it.matrixId == "151" }) {
                    "the $flavorId catalog must NOT carry the AndroidPort built-in " +
                        "(matrixId 151): it ships for the vanilla client only " +
                        "(owner decision, completion-plan table #8)"
                }
            }
            val pairsJson = root.getJSONArray("compatibilityPairs")
            val pairs = List(pairsJson.length()) { index ->
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
                    require(pair.addonA in addons.map { it.matrixId } && pair.addonB in addons.map { it.matrixId })
                    require(pair.addonA != pair.addonB)
                }
            }
            if (flavorId == "vanilla") {
                require(pairs.size == 266)
            } else {
                val expectedPairs = root.getInt("expectedCompatibilityPairCount")
                require(pairs.size == expectedPairs) {
                    "the $flavorId catalog declares $expectedPairs compatibility " +
                        "pairs but carries ${pairs.size} (a hand edit)"
                }
            }
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
                val reasonsRoot = root.optJSONObject("recommendationReasons")
                    ?: error("the $flavorId catalog carries no recommendationReasons")
                val reasons = linkedMapOf<String, String>()
                for (id in declared) {
                    val reason = reasonsRoot.optString(id).takeIf { it.isNotBlank() }
                        ?: error(
                            "the $flavorId catalog's recommended row $id has no " +
                                "recommendationReasons entry - the Recommended " +
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
    }
}
