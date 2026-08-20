package com.pocketrealm.client

/**
 * One pinned community Mesa Turnip build offered for download in Settings.
 *
 * Everything here is reviewed compile-time data: the download URL is a GitHub
 * release asset of [repo]/[release], pinned by [size] + [sha256] (the
 * artifact digest) and — when known — [librarySha256] (the inner `.so`,
 * which marks an entry "already imported" against the user registry). These
 * ids are manifest keys only: an imported community driver always enters the
 * `user-` namespace like any manual import (C3 — same gates, same guard).
 */
data class CommunityVulkanDriver(
    val id: String,
    val label: String,
    val version: String,
    val repo: String,
    val release: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val librarySha256: String?,
    val format: String,
    val license: String,
    val upstream: String,
    val summary: String,
    val note: String,
) {
    init {
        require(ID.matches(id)) { "invalid community Vulkan driver id: $id" }
        require(label.isNotBlank() && label.length <= 64) {
            "community Vulkan driver label must be 1..64 chars"
        }
        require(version.isNotBlank()) { "community Vulkan driver version is absent" }
        require(REPO.matches(repo)) { "invalid community Vulkan driver source repo" }
        require(repo.split("/").none { it == "." || it == ".." }) {
            "community Vulkan driver source repo has dot segments"
        }
        require(release.isNotBlank()) { "community Vulkan driver release is absent" }
        require(RELEASE_URL.matches(url)) {
            "community Vulkan driver URL is not a pinned GitHub release asset"
        }
        require(url.startsWith("https://github.com/$repo/releases/download/$release/")) {
            "community Vulkan driver URL does not match its source repo/release"
        }
        require(size in 1..MAX_DOWNLOAD_BYTES) { "community Vulkan driver size is invalid" }
        require(SHA256.matches(sha256)) { "invalid community Vulkan driver sha256" }
        require(librarySha256 == null || SHA256.matches(librarySha256)) {
            "invalid community Vulkan driver librarySha256"
        }
        require(format in FORMATS) { "unsupported community Vulkan driver format" }
        require(license == "MIT") {
            "community Vulkan drivers must be MIT-licensed Mesa builds"
        }
        require(upstream.isNotBlank()) { "community Vulkan driver upstream is absent" }
        require(summary.isNotBlank()) { "community Vulkan driver summary is absent" }
        require(note.isNotBlank()) { "community Vulkan driver note is absent" }
    }

    companion object {
        /** Mirrors tools/generate_community_vulkan_drivers.py (MAX_DOWNLOAD_BYTES). */
        const val MAX_DOWNLOAD_BYTES: Long = 64L * 1024 * 1024

        private val ID = Regex("community-[a-z0-9][a-z0-9.-]{2,63}")
        private val REPO = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        private val RELEASE_URL = Regex(
            "https://github\\.com/[^/\\s]+/[^/\\s]+/releases/download/[^/?#\\s]+/[^/?#\\s]+",
        )
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FORMATS = setOf("adrenotools-zip", "bare-so")
    }
}

/**
 * Curated, APK-bundled list of community Mesa Turnip builds (Eden/
 * Winlator-ecosystem packs). Downloads verify the pinned digests and then
 * run the ordinary USER-lane import — no bypass, no silent fallback. The
 * list itself is never fetched at runtime (C4); it changes only through
 * repo review, exactly like the closed packaged catalog.
 */
object CommunityVulkanDrivers {
    private val drivers = GeneratedCommunityVulkanDrivers.drivers.also { generated ->
        check(GeneratedCommunityVulkanDrivers.SCHEMA == 1)
        check(GeneratedCommunityVulkanDrivers.POLICY == "pinned-digest-download-only")
        check(generated.map(CommunityVulkanDriver::id).toSet().size == generated.size)
    }

    fun all(): List<CommunityVulkanDriver> = drivers

    fun find(id: String?): CommunityVulkanDriver? =
        id?.let { wanted -> drivers.firstOrNull { it.id == wanted } }

    /** The entry whose inner library digest matches — the "already imported" mark. */
    fun forLibrarySha256(sha256: String): CommunityVulkanDriver? =
        drivers.firstOrNull { it.librarySha256 == sha256 }
}
