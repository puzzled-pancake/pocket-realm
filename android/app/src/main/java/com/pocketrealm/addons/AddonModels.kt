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
