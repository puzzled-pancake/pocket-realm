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

class AddonCatalog private constructor(
    val researchedAt: String,
    val addons: List<CatalogAddon>,
    val compatibilityPairs: List<AddonCompatibility>,
) {
    private val byMatrixId = addons.associateBy(CatalogAddon::matrixId)
    private val byInstallId = addons.mapNotNull { addon -> addon.installId?.let { it to addon } }.toMap()
    private val pairsByAddon = compatibilityPairs
        .flatMap { pair -> listOf(pair.addonA to pair, pair.addonB to pair) }
        .groupBy({ it.first }, { it.second })

    val categories: List<String> = addons.map(CatalogAddon::category).distinct().sorted()
    /** Tested-on-hardware starting set; every item remains individually optional. */
    val recommended: List<CatalogAddon> = RECOMMENDED_IDS.map { id ->
        checkNotNull(byMatrixId[id]) { "Recommended add-on $id is absent from the catalog" }
    }

    fun addon(matrixId: String): CatalogAddon? = byMatrixId[matrixId]
    fun addonForInstalled(installed: InstalledAddon): CatalogAddon? = byInstallId[installed.id]
    fun recommendationReason(matrixId: String): String? = RECOMMENDATION_REASONS[matrixId]

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

        @Volatile private var cached: AddonCatalog? = null

        /**
         * The bundled researched catalog through the desktop asset resolver.
         * A missing bundle fails loud — every caller needs the catalog to
         * even name the built-in add-on.
         */
        fun load(): AddonCatalog = cached ?: synchronized(this) {
            cached ?: parse(readBundledCatalogText()).also { cached = it }
        }

        /** Test seam: re-parse and re-cache from an explicit text source. */
        internal fun reloadForTests(text: String): AddonCatalog = synchronized(this) {
            parse(text).also { cached = it }
        }

        private fun readBundledCatalogText(): String {
            val asset = requireNotNull(DesktopAddonAssets.catalogJson()) {
                "The bundled add-on catalog (addons/catalog-v1.json) is missing"
            }
            return asset.readText(Charsets.UTF_8)
        }

        internal fun parse(text: String): AddonCatalog {
            val root = JSONObject(text)
            require(root.getInt("schema") == 1) { "Unsupported addon catalog" }
            require(!root.has("bundled")) { "Retired bundled add-on metadata is not allowed" }
            val addonsJson = root.getJSONArray("addons")
            val addons = List(addonsJson.length()) { index ->
                val item = addonsJson.getJSONObject(index)
                // R2UI-1/R6-r2-1 (android-ui-tests review round 2): the same
                // load-time negation guard as the Android twin — research
                // text that names a client line inside a negation clause
                // ("explicitly NOT for 2.4.3") is rejected at parse, never
                // silently matched as SUPPORT by the flavor projection.
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
            require(addons.size == 154 && addons.map { it.matrixId }.toSet().size == addons.size)
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
            require(pairs.size == 266)
            return AddonCatalog(
                researchedAt = root.getString("researchedAt"),
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
