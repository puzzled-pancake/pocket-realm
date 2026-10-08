package com.pocketrealm.desktop

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * The desktop update CHECK (the AppUpdateCoordinator twin, DESK-14/32/45/91/
 * 104). The feed stays OPT-IN: a URL next to the settings file
 * (update-feed.url, one line) pointing at a JSON document. A feed that
 * carries only {"version": "x.y.z"} keeps the original display-only check;
 * a feed that additionally carries the download legs {"url", "size",
 * "sha256"} unlocks the verified download (UpdateDownload) and the consented
 * apply (UpdateApply). The honest unsigned copy stays everywhere: the
 * Windows port is unsigned, a package is verified only against the feed's
 * own sha256, and nothing ever updates silently. No feed configured → the
 * check says so honestly instead of pretending.
 */
@Suppress("MagicNumber") // HTTP statuses, socket timeouts, digest length
object UpdateCheck {
    const val CURRENT_VERSION: String = APP_VERSION

    /** One-line status for the Settings card; never throws. */
    fun checkOnce(roots: DesktopStorageRoots): String = runCatching {
        displayStatus(checkDetailed(roots))
    }.getOrElse { failure -> "Update check failed: ${failure.javaClass.simpleName}" }

    /** The display projection of [CheckResult]; shared by the card and [checkOnce]. */
    internal fun displayStatus(result: CheckResult): String = when (result) {
        is CheckResult.Unavailable -> result.reason
        is CheckResult.UpToDate -> "Up to date ($CURRENT_VERSION)."
        is CheckResult.Available ->
            if (result.manifest.downloadable) {
                "Update available: ${result.manifest.version} " +
                    "(installed $CURRENT_VERSION) - a verified download with a " +
                    "consented install is available below. The build stays " +
                    "unsigned; nothing updates silently."
            } else {
                "Update available: ${result.manifest.version} " +
                    "(installed $CURRENT_VERSION) - download the new package; " +
                    "this app does not self-update."
            }
    }

    /**
     * Resolves the opt-in feed into a structured verdict the Settings card
     * can act on; [checkOnce] is its display-only projection.
     */
    fun checkDetailed(roots: DesktopStorageRoots): CheckResult = runCatching {
        val feedFile = File(roots.settings, FEED_FILE)
        if (!feedFile.isFile) {
            return@runCatching CheckResult.Unavailable(
                "No update feed configured (place a feed URL in $FEED_FILE " +
                    "under ${roots.settings} to enable the check).",
            )
        }
        // https-only: a cleartext feed would let a man-in-the-middle inject
        // a fake "update available" line (or a malicious download leg).
        val feedUrl = feedFile.readText(Charsets.UTF_8).trim()
            .takeIf(::isFetchAllowed)
            ?: return@runCatching CheckResult.Unavailable(
                "The configured update feed must be an https:// URL.",
            )
        val connection = openConnection(feedUrl, CHECK_TIMEOUT_MS, CHECK_TIMEOUT_MS)
        try {
            if (connection.responseCode != 200) {
                return@runCatching CheckResult.Unavailable(
                    "Update check failed: HTTP ${connection.responseCode}",
                )
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val manifest = parseFeed(body)
            if (manifest.version == CURRENT_VERSION) {
                CheckResult.UpToDate(manifest.version)
            } else {
                CheckResult.Available(manifest)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { failure ->
        CheckResult.Unavailable("Update check failed: ${failure.javaClass.simpleName}")
    }

    /**
     * Parses the feed JSON: {"version": "x.y.z"} for the display-only check,
     * plus optional download legs {"url", "size", "sha256"} which must arrive
     * together (a partial leg set is a malformed feed, not a degraded mode).
     */
    internal fun parseFeed(body: String): UpdateManifest {
        val json = JSONObject(body)
        val version = json.optString("version")
        require(version.isNotBlank()) { "The update feed carries no version field." }
        val url = json.optString("url").takeIf { it.isNotBlank() }
        val size = if (json.has("size")) json.getLong("size") else null
        val sha256 = json.optString("sha256").takeIf { it.isNotBlank() }?.lowercase()
        if (url == null && size == null && sha256 == null) {
            return UpdateManifest(version = version, packageUrl = null, size = null, sha256 = null)
        }
        require(url != null && size != null && size > 0 && sha256 != null) {
            "the feed's download legs are incomplete: url, size and sha256 must all be present"
        }
        require(sha256.length == SHA256_HEX_LENGTH && sha256.all { it in '0'..'f' }) {
            "the feed's sha256 is malformed"
        }
        return UpdateManifest(version = version, packageUrl = url, size = size, sha256 = sha256)
    }

    /**
     * The single scheme policy for every update fetch (feed AND download):
     * https-only everywhere, with one internal test seam for the local HTTP
     * fixture in unit tests. Production leaves the seam empty — the check
     * then refuses every non-https URL.
     */
    internal val testAllowedHttpHosts = mutableSetOf<String>()

    internal fun stagingDir(roots: DesktopStorageRoots): File = File(roots.settings, DOWNLOADS_DIR)

    internal fun openConnection(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        if (!isFetchAllowed(url)) {
            throw IOException("refusing non-https update fetch: $url")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        return connection
    }

    private fun isFetchAllowed(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return parsed.protocol == "https" ||
            (parsed.protocol == "http" && parsed.host in testAllowedHttpHosts)
    }

    data class UpdateManifest(
        val version: String,
        val packageUrl: String?,
        val size: Long?,
        val sha256: String?,
    ) {
        /** True when the feed carries a complete, verifiable download leg set. */
        val downloadable: Boolean
            get() = packageUrl != null && size != null && size > 0 && sha256 != null
    }

    sealed class CheckResult {
        data class UpToDate(val version: String) : CheckResult()
        data class Available(val manifest: UpdateManifest) : CheckResult()
        data class Unavailable(val reason: String) : CheckResult()
    }

    internal const val FEED_FILE = "update-feed.url"
    internal const val DOWNLOADS_DIR = "downloads"
    internal const val PACKAGE_BASE_NAME = "pocketrealm-update"
    private const val CHECK_TIMEOUT_MS = 5_000
    private const val SHA256_HEX_LENGTH = 64
}
