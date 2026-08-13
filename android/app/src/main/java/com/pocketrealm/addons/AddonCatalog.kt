package com.pocketrealm.addons

import android.content.Context
import org.json.JSONObject
import java.util.Locale

enum class AddonInstallSource { GITHUB, BUNDLED, REFERENCE }

data class CatalogAddon(
    val matrixId: String,
    val name: String,
    val category: String,
    val description: String,
    val githubUrl: String?,
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
        AddonInstallSource.GITHUB -> githubUrl?.let { runCatching { GitHubRepoRef.parse(it).id }.getOrNull() }
        AddonInstallSource.BUNDLED -> AddonCatalog.BUNDLED_ADDON_ID
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
    val bundledAssetPath: String,
    val bundledVersion: String,
    val bundledSha256: String,
) {
    private val byMatrixId = addons.associateBy(CatalogAddon::matrixId)
    private val byInstallId = addons.mapNotNull { addon -> addon.installId?.let { it to addon } }.toMap()
    private val pairsByAddon = compatibilityPairs
        .flatMap { pair -> listOf(pair.addonA to pair, pair.addonB to pair) }
        .groupBy({ it.first }, { it.second })

    val categories: List<String> = addons.map(CatalogAddon::category).distinct().sorted()
    /** Small product-curated starting set; every item remains individually optional. */
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
        const val BUNDLED_ADDON_ID = "bundled__pocketrealmpad"
        private const val CATALOG_ASSET = "addons/catalog-v1.json"
        private val RECOMMENDED_IDS = listOf("155", "013", "018", "054", "059")
        private val RECOMMENDATION_REASONS = mapOf(
            "155" to "Optional controller-and-touch interface designed for Pocket Realm handhelds.",
            "013" to "Adds useful conditional macro tools for compact controller-friendly action layouts.",
            "018" to "Shows clear visual alerts for important buffs, debuffs and resources.",
            "054" to "Adds a structured levelling guide with navigation and progress tracking.",
            "059" to "Adds optional voiced quest and NPC dialogue for a more immersive solo world.",
        )

        @Volatile private var cached: AddonCatalog? = null

        fun load(context: Context): AddonCatalog = cached ?: synchronized(this) {
            cached ?: context.applicationContext.assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
                parse(reader.readText())
            }.also { cached = it }
        }

        internal fun parse(text: String): AddonCatalog {
            val root = JSONObject(text)
            require(root.getInt("schema") == 1) { "Unsupported addon catalog" }
            val bundled = root.getJSONObject("bundled")
            val addonsJson = root.getJSONArray("addons")
            val addons = List(addonsJson.length()) { index ->
                val item = addonsJson.getJSONObject(index)
                CatalogAddon(
                    matrixId = item.getString("matrixId"),
                    name = item.getString("name"),
                    category = item.getString("category"),
                    description = item.getString("description"),
                    githubUrl = item.optString("githubUrl").takeIf { !item.isNull("githubUrl") && it.isNotBlank() },
                    clientTarget = item.getString("clientTarget"),
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
                    require(addon.installSource != AddonInstallSource.GITHUB || addon.installId != null)
                }
            }
            require(addons.size == 155 && addons.map { it.matrixId }.toSet().size == addons.size)
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
            require(pairs.size == 311)
            return AddonCatalog(
                researchedAt = root.getString("researchedAt"),
                addons = addons,
                compatibilityPairs = pairs,
                bundledAssetPath = bundled.getString("assetPath"),
                bundledVersion = bundled.getString("version"),
                bundledSha256 = bundled.getString("sha256"),
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
