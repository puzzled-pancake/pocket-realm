package com.pocketrealm.addons

import org.json.JSONObject

/**
 * Strictly resolves the two upstream artifacts required by mrthinger's
 * VoiceOver for the ACTIVE flavor. R5-3 (code-red round 1): the resolver was
 * hard-pinned to the Vanilla 1.12 artifacts (player zip
 * AI_VoiceOver-WoW_1.12, data folder AI_VoiceOverData_Vanilla) — on tbc/
 * wotlk lanes that installed a wrong-era root toc (the vanilla interface) plus
 * vanilla-only audio while the catalogs claimed per-expansion artifacts.
 * [VoiceOverEra] carries the per-expansion tokens the upstream release
 * ships (AI_VoiceOver-WoW_{1.12,2.4.3,3.3.5}); the caller resolves the era
 * from the active lane.
 */
internal object VoiceOverReleaseResolver {
    const val INSTALL_ID = "mrthinger__wow-voiceover"
    const val DISPLAY_NAME = "WoW VoiceOver"
    const val PLAYER_FOLDER = "AI_VoiceOver"

    /** The per-expansion upstream tokens (R5-3): [VoiceOverEra.playerToken]
     * is the player zip's client token; [VoiceOverEra.dataFolder] the data
     * pack's folder/toc identity. Vanilla is the default for callers that
     * predate flavor threading. */
    enum class VoiceOverEra(
        val playerToken: String,
        val dataFolder: String,
        /** The user-facing display label (never the raw folder name). */
        val displayLabel: String,
    ) {
        VANILLA("1.12", "AI_VoiceOverData_Vanilla", "Vanilla"),
        TBC("2.4.3", "AI_VoiceOverData_TBC", "TBC"),
        WOTLK("3.3.5", "AI_VoiceOverData_WotLK", "WotLK"),
    }

    /** The active lane's era, resolved from the flavor id. */
    fun eraFor(flavorId: String): VoiceOverEra = when (flavorId) {
        com.pocketrealm.client.ClientFlavors.VANILLA.flavorId -> VoiceOverEra.VANILLA
        com.pocketrealm.client.ClientFlavors.TBC.flavorId -> VoiceOverEra.TBC
        com.pocketrealm.client.ClientFlavors.WOTLK.flavorId -> VoiceOverEra.WOTLK
        else -> error("no VoiceOver era registered for flavor \"$flavorId\"")
    }

    const val MAX_PLAYER_BYTES = 128L * 1024 * 1024
    const val MAX_DATA_BYTES = AddonArchiveValidator.VOICEOVER_DATA_MAX_ARCHIVE_BYTES

    data class Asset(val id: Long, val tag: String, val name: String, val url: String, val size: Long)
    data class Resolved(val player: Asset, val data: Asset) {
        val remoteIdentity: String = sha256(listOf(player, data).joinToString("\n") { asset ->
            "${asset.id}\u0000${asset.tag}\u0000${asset.name}\u0000${asset.url}\u0000${asset.size}"
        })
    }

    /** The pre-flavor form: the VANILLA era (keeps every legacy call site
     * and the trailing-lambda shape compiling). */
    fun resolve(latestRelease: JSONObject, releaseByTag: (String) -> JSONObject): Resolved =
        resolve(latestRelease, releaseByTag, VoiceOverEra.VANILLA)

    /** The era-aware resolution (R5-3): the era rides the ACTIVE lane. */
    fun resolve(
        latestRelease: JSONObject,
        releaseByTag: (String) -> JSONObject,
        era: VoiceOverEra,
    ): Resolved {
        val latestTag = safeTag(latestRelease.getString("tag_name"))
        val playerName = "AI_VoiceOver-WoW_${era.playerToken}-$latestTag.zip"
        val player = exactAsset(latestRelease, latestTag, playerName, MAX_PLAYER_BYTES)

        val dataReferences = dataAssetRegex(era).findAll(latestRelease.optString("body"))
            .map { match -> safeTag(match.groupValues[1]) to match.groupValues[2] }
            .distinct()
            .toList()
        require(dataReferences.size == 1) {
            "VoiceOver release must identify exactly one ${era.dataFolder} sound pack"
        }
        val (dataTag, dataName) = dataReferences.single()
        require(dataName.startsWith(era.dataFolder)) {
            "VoiceOver sound pack ${dataName.substringBefore('-')} is not the " +
                "${era.dataFolder} pack the active lane requires"
        }
        val dataRelease = releaseByTag(dataTag)
        require(safeTag(dataRelease.getString("tag_name")) == dataTag) {
            "VoiceOver sound-pack release identity changed"
        }
        val data = exactAsset(dataRelease, dataTag, dataName, MAX_DATA_BYTES)
        return Resolved(player, data)
    }

    private fun exactAsset(release: JSONObject, tag: String, name: String, maxBytes: Long): Asset {
        require(SAFE_ASSET_NAME.matches(name)) { "VoiceOver release asset name is unsafe" }
        val expectedUrl = "https://github.com/mrthinger/wow-voiceover/releases/download/$tag/$name"
        val assets = release.getJSONArray("assets")
        val matches = buildList {
            repeat(assets.length()) { index ->
                val item = assets.getJSONObject(index)
                if (item.optString("name") == name && item.optString("browser_download_url") == expectedUrl) {
                    add(Asset(item.getLong("id"), tag, name, expectedUrl, item.getLong("size")))
                }
            }
        }
        require(matches.size == 1) { "VoiceOver release asset is missing or ambiguous: $name" }
        return matches.single().also { asset ->
            require(asset.id > 0) { "VoiceOver release asset identity is invalid: $name" }
            require(asset.size in 1..maxBytes) { "VoiceOver release asset has an unsupported size: $name" }
        }
    }

    private fun safeTag(value: String): String = value.also {
        require(SAFE_TAG.matches(it)) { "VoiceOver release tag is unsafe" }
    }

    /** The per-era data-pack URL pattern (the release body references the
     * era's own sound pack). */
    private fun dataAssetRegex(era: VoiceOverEra) = Regex(
        "https://github\\.com/mrthinger/wow-voiceover/releases/download/" +
            "([A-Za-z0-9._-]{1,100})/(" + Regex.escape(era.dataFolder) +
            "-[A-Za-z0-9._-]{1,160}\\.zip)",
    )

    private val SAFE_TAG = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val SAFE_ASSET_NAME = Regex("^[A-Za-z0-9._-]{1,200}\\.zip$")

    private fun sha256(value: String): String = com.pocketrealm.fs.FileDigests.sha256(value)
}
