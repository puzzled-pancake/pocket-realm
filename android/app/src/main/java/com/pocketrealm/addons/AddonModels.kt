package com.pocketrealm.addons

import java.util.Locale

data class InstalledAddon(
    val id: String,
    val repository: String,
    val displayName: String,
    val commitSha: String,
    val archiveSha256: String,
    val installedAtEpochMs: Long,
    val packagePath: String,
    val folders: List<String>,
)

enum class AddonStage(val label: String) {
    RESOLVING("Checking repository"),
    DOWNLOADING("Downloading"),
    VALIDATING("Checking Vanilla compatibility"),
    INSTALLING("Installing"),
    REMOVING("Removing"),
}

data class AddonOperation(
    val stage: AddonStage,
    val repository: String,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    /** False once the operation has crossed its atomic publication boundary. */
    val cancellable: Boolean = true,
)

data class AddonCatalogState(
    val installed: List<InstalledAddon> = emptyList(),
    /** Installed add-on id -> latest GitHub commit when it differs from the installed commit. */
    val availableUpdates: Map<String, String> = emptyMap(),
    val updatesCheckedAtEpochMs: Long? = null,
    val operation: AddonOperation? = null,
    val notice: String? = null,
    val errorTitle: String? = null,
    val error: String? = null,
)

data class GitHubRepoRef(val owner: String, val repo: String) {
    val slug: String get() = "$owner/$repo"
    val id: String get() = "${owner.lowercase(Locale.ROOT)}__${repo.lowercase(Locale.ROOT)}"

    companion object {
        private val component = Regex("^[A-Za-z0-9_.-]{1,100}$")

        fun parse(value: String): GitHubRepoRef {
            val trimmed = value.trim().removeSuffix("/").removeSuffix(".git")
            val match = Regex("^https://github\\.com/([^/]+)/([^/]+)$", RegexOption.IGNORE_CASE)
                .matchEntire(trimmed)
                ?: throw IllegalArgumentException("Use a public GitHub URL such as https://github.com/owner/addon")
            val owner = match.groupValues[1]
            val repo = match.groupValues[2]
            require(component.matches(owner) && component.matches(repo) && owner != "." && repo != ".") {
                "GitHub owner or repository name is invalid"
            }
            return GitHubRepoRef(owner, repo)
        }
    }
}

/**
 * Pure codec for the installed-addon registry (`registry.json`,
 * `registry.previous.json`), extracted from `AddonRepository` so the durable
 * format is fixture-testable on the JVM (G3-6 item 3): `InstalledAddon.id`
 * stays the UN-prefixed install id on every flavor — flavor scoping of
 * installed sets happens at the projection/rollback layer (F3-5), never by
 * rewriting ids, and a pre-multi-flavor registry fixture must keep loading
 * and resolving through `AddonCatalog.addonForInstalled` unchanged.
 */
internal object AddonRegistryCodec {
    const val SCHEMA = 1

    fun encode(installed: List<InstalledAddon>): String =
        org.json.JSONObject().put("schema", SCHEMA).put("installed", org.json.JSONArray().apply {
            installed.forEach { addon ->
                put(org.json.JSONObject()
                    .put("id", addon.id)
                    .put("repository", addon.repository)
                    .put("displayName", addon.displayName)
                    .put("commitSha", addon.commitSha)
                    .put("archiveSha256", addon.archiveSha256)
                    .put("installedAtEpochMs", addon.installedAtEpochMs)
                    .put("packagePath", addon.packagePath)
                    .put("folders", org.json.JSONArray(addon.folders)))
            }
        }).toString(2)

    /** The [AddonRepository.loadRegistryStrict] mapping; [registryRoot] backs the package-path presence check. */
    fun decodeInstalled(registryRoot: java.io.File, text: String): List<InstalledAddon> {
        val array = rootArray(text)
        return List(array.length()) { index ->
            decodeRow(array.getJSONObject(index))
                .also { require(java.io.File(registryRoot, it.packagePath).isDirectory) }
        }
    }

    /**
     * The pure row parse with no filesystem coupling — the tolerance lane the
     * launch-boundary projector reads through: package presence stays the
     * caller's own per-folder policy there (a missing package surfaces when
     * its folder is staged, not while the durable format is being read).
     */
    fun decode(text: String): List<InstalledAddon> {
        val array = rootArray(text)
        return List(array.length()) { index -> decodeRow(array.getJSONObject(index)) }
    }

    private fun rootArray(text: String): org.json.JSONArray {
        val rootJson = org.json.JSONObject(text)
        require(rootJson.getInt("schema") == SCHEMA)
        return rootJson.getJSONArray("installed")
    }

    private fun decodeRow(item: org.json.JSONObject): InstalledAddon {
        val id = item.getString("id")
        return InstalledAddon(
            id = id,
            repository = item.getString("repository"),
            displayName = if (id == AndroidPortPackage.INSTALL_ID) {
                AndroidPortPackage.DISPLAY_NAME
            } else {
                item.getString("displayName")
            },
            commitSha = item.getString("commitSha"),
            archiveSha256 = item.getString("archiveSha256"),
            installedAtEpochMs = item.getLong("installedAtEpochMs"),
            packagePath = item.getString("packagePath"),
            folders = item.getJSONArray("folders").let { folders ->
                List(folders.length()) { folders.getString(it) }
            },
        )
    }
}
