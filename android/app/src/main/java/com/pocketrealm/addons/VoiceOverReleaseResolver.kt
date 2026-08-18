package com.pocketrealm.addons

import org.json.JSONObject

/** Strictly resolves the two upstream artifacts required by mrthinger's Vanilla 1.12 VoiceOver. */
internal object VoiceOverReleaseResolver {
    const val INSTALL_ID = "mrthinger__wow-voiceover"
    const val DISPLAY_NAME = "WoW VoiceOver"
    const val PLAYER_FOLDER = "AI_VoiceOver"
    const val DATA_FOLDER = "AI_VoiceOverData_Vanilla"
    const val MAX_PLAYER_BYTES = 128L * 1024 * 1024
    const val MAX_DATA_BYTES = AddonArchiveValidator.VOICEOVER_DATA_MAX_ARCHIVE_BYTES

    data class Asset(val id: Long, val tag: String, val name: String, val url: String, val size: Long)
    data class Resolved(val player: Asset, val data: Asset) {
        val remoteIdentity: String = sha256(listOf(player, data).joinToString("\n") { asset ->
            "${asset.id}\u0000${asset.tag}\u0000${asset.name}\u0000${asset.url}\u0000${asset.size}"
        })
    }

    fun resolve(latestRelease: JSONObject, releaseByTag: (String) -> JSONObject): Resolved {
        val latestTag = safeTag(latestRelease.getString("tag_name"))
        val playerName = "AI_VoiceOver-WoW_1.12-$latestTag.zip"
        val player = exactAsset(latestRelease, latestTag, playerName, MAX_PLAYER_BYTES)

        val dataReferences = DATA_ASSET_URL.findAll(latestRelease.optString("body"))
            .map { match -> safeTag(match.groupValues[1]) to match.groupValues[2] }
            .distinct()
            .toList()
        require(dataReferences.size == 1) {
            "VoiceOver release must identify exactly one Vanilla sound pack"
        }
        val (dataTag, dataName) = dataReferences.single()
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

    private val SAFE_TAG = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val SAFE_ASSET_NAME = Regex("^[A-Za-z0-9._-]{1,200}\\.zip$")
    private val DATA_ASSET_URL = Regex(
        "https://github\\.com/mrthinger/wow-voiceover/releases/download/" +
            "([A-Za-z0-9._-]{1,100})/(AI_VoiceOverData_Vanilla-[A-Za-z0-9._-]{1,160}\\.zip)",
    )

    private fun sha256(value: String): String = com.pocketrealm.fs.FileDigests.sha256(value)
}
