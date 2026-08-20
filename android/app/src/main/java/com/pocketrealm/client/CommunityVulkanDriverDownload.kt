package com.pocketrealm.client

import com.pocketrealm.fs.FileDigests
import com.pocketrealm.update.AppUpdateCoordinator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads one pinned community driver artifact and verifies it against the
 * reviewed manifest before anything may import it (C2): declared size
 * preflight, cap-during-copy, and a full SHA-256 check. The verified file is
 * handed to [UserVulkanDriverRegistry.import] by the caller — this path
 * never bypasses the ordinary import gates (C3), and every failure deletes
 * the partial file and returns its exact reason (never a silent fallback).
 */
object CommunityVulkanDriverDownload {

    sealed class Outcome {
        data class Downloaded(val file: File) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private sealed interface Hop {
        data class Redirect(val url: HttpUrl) : Hop
        data class Terminal(val outcome: Outcome) : Hop
    }

    fun download(
        driver: CommunityVulkanDriver,
        destination: File,
        onProgress: (Long) -> Unit = {},
    ): Outcome {
        // Defense in depth: the manifest projection already pins HTTPS URLs.
        if (!driver.url.startsWith("https://")) {
            return Outcome.Failed("The pinned community driver URL is not HTTPS.")
        }
        return downloadPinned(driver.url, driver.size, driver.sha256, destination, onProgress)
    }

    /**
     * Test/production seam. The initial URL's shape is trusted from the
     * reviewed manifest (HTTPS pinned by the generator); unit tests point
     * this at plain-HTTP mock servers, so only *redirect* targets are
     * required to be HTTPS here. Hosts are checked on every hop against the
     * self-updater's shared allowlist.
     */
    internal fun downloadPinned(
        url: String,
        expectedSize: Long,
        expectedSha256: String,
        destination: File,
        onProgress: (Long) -> Unit = {},
        client: OkHttpClient = sharedClient,
        allowedHosts: Set<String> = AppUpdateCoordinator.allowedHosts +
            AppUpdateCoordinator.extraAllowedHosts,
    ): Outcome {
        var current = url
        var redirects = 0
        while (true) {
            val target = current.toHttpUrlOrNull()
                ?: return failed(
                    destination,
                    "The download target URL could not be parsed ($current).",
                )
            if (target.host !in allowedHosts) {
                return failed(
                    destination,
                    "The download target host is not one Pocket Realm trusts " +
                        "(${target.host}).",
                )
            }
            val response = try {
                client.newCall(Request.Builder().url(target).build()).execute()
            } catch (error: Exception) {
                return failed(
                    destination,
                    "The download failed: ${error.message ?: error.javaClass.simpleName}.",
                )
            }
            val step = try {
                hop(response, target, expectedSize, expectedSha256, destination, onProgress)
            } catch (error: Exception) {
                // Mid-stream IO failures (reset, timeout, truncated body) must
                // not leak the partially written temp (C2).
                return failed(
                    destination,
                    "The download failed: ${error.message ?: error.javaClass.simpleName}.",
                )
            }
            when (step) {
                is Hop.Redirect -> {
                    if (++redirects > MAX_REDIRECTS) {
                        return failed(
                            destination,
                            "The download redirected more than $MAX_REDIRECTS times.",
                        )
                    }
                    current = step.url.toString()
                }
                is Hop.Terminal -> return step.outcome
            }
        }
    }

    /** Consumes (and closes) one HTTP response: follow, fail, or verified file. */
    private fun hop(
        response: Response,
        target: HttpUrl,
        expectedSize: Long,
        expectedSha256: String,
        destination: File,
        onProgress: (Long) -> Unit,
    ): Hop = response.use {
        if (it.isRedirect) {
            val resolved = it.header("Location")
                ?.let { location -> target.resolve(location) }
                ?: return Hop.Terminal(
                    failed(destination, "The download redirected to a URL that could not be resolved."),
                )
            if (!resolved.toString().startsWith("https://")) {
                return Hop.Terminal(
                    failed(
                        destination,
                        "The download redirected to a non-HTTPS URL ($resolved).",
                    ),
                )
            }
            return Hop.Redirect(resolved)
        }
        if (!it.isSuccessful) {
            return Hop.Terminal(
                failed(destination, "The download was refused by the server (HTTP ${it.code})."),
            )
        }
        val declared = it.header("Content-Length")?.toLongOrNull()
            ?: return Hop.Terminal(
                failed(destination, "The download did not report its size; refusing an unpinned download."),
            )
        if (declared != expectedSize) {
            return Hop.Terminal(
                failed(
                    destination,
                    "The download claims $declared bytes but the pinned driver is " +
                        "$expectedSize bytes.",
                ),
            )
        }
        destination.outputStream().use { output ->
            val body = it.body
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = body.byteStream().read(buffer)
                if (read < 0) break
                total += read
                if (total > expectedSize) {
                    return Hop.Terminal(
                        failed(
                            destination,
                            "The download streamed more bytes than the pinned driver " +
                                "size ($total > $expectedSize).",
                        ),
                    )
                }
                output.write(buffer, 0, read)
                onProgress(total)
            }
        }
        if (destination.length() != expectedSize) {
            return Hop.Terminal(
                failed(
                    destination,
                    "The downloaded file is ${destination.length()} bytes; the pinned " +
                        "driver is $expectedSize bytes.",
                ),
            )
        }
        if (!FileDigests.sha256(destination).equals(expectedSha256, ignoreCase = true)) {
            return Hop.Terminal(failed(destination, DIGEST_MISMATCH))
        }
        return Hop.Terminal(Outcome.Downloaded(destination))
    }

    private fun failed(destination: File, reason: String): Outcome.Failed {
        destination.delete()
        return Outcome.Failed(reason)
    }

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    const val DIGEST_MISMATCH =
        "The downloaded file does not match its pinned SHA-256 — the source file " +
            "changed after review; the download was discarded."

    private const val MAX_REDIRECTS = 3
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
}
