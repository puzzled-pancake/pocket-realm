package com.pocketrealm.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The simple update-available check from the distribution notes. The
 * Windows port has no distribution infrastructure yet, so the feed is
 * OPT-IN: a URL next to the settings file (update-feed.url, one line)
 * pointing at a JSON document of the shape {"version": "x.y.z"}.
 *
 * No feed configured → the check says so honestly instead of pretending.
 * The comparison is display-only; the package never self-updates.
 */
@Suppress("MagicNumber") // HTTP statuses and socket timeouts
object UpdateCheck {
    const val CURRENT_VERSION: String = APP_VERSION

    /** One-line status for the Settings card; never throws. */
    fun checkOnce(roots: DesktopStorageRoots): String = runCatching {
        val feedFile = File(roots.settings, FEED_FILE)
        if (!feedFile.isFile) {
            return@runCatching "No update feed configured (place a feed URL in ${FEED_FILE} " +
                "under ${roots.settings} to enable the check)."
        }
        val feedUrl = feedFile.readText(Charsets.UTF_8).trim().takeIf { it.startsWith("http") }
            ?: return@runCatching "The configured update feed is not an http(s) URL."
        val connection = URL(feedUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        try {
            if (connection.responseCode != 200) {
                return@runCatching "Update check failed: HTTP ${connection.responseCode}"
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val remote = org.json.JSONObject(body).optString("version")
            if (remote.isBlank()) {
                return@runCatching "The update feed carries no version field."
            }
            if (remote == CURRENT_VERSION) {
                "Up to date ($CURRENT_VERSION)."
            } else {
                "Update available: $remote (installed $CURRENT_VERSION) - download the new " +
                    "package; this app does not self-update."
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { failure -> "Update check failed: ${failure.javaClass.simpleName}" }

    private const val FEED_FILE = "update-feed.url"
}
