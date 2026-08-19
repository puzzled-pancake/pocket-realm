package com.pocketrealm.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.pocketrealm.log.AppLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app update delivery from a dedicated public GitHub "updates"
 * repository (the updates repo contains only
 * releases: the signed APK plus an update-manifest.json asset). Two tracks
 * share this coordinator: a check-only card (fetch the manifest, compare
 * version codes) and the full in-place install (resumable download with
 * sha256 verification, then a PackageInstaller session).
 *
 * Data preservation is the core requirement: updates install over the
 * existing app with the same signature — Android itself refuses a
 * mismatched signature rather than wiping, and nothing here ever uninstalls.
 */
object AppUpdateCoordinator {
    private const val TAG = "AppUpdate"

    /**
     * The release channel repository (owner/name): this project's own
     * Releases page. If no release is published yet, a check fails softly
     * with "update channel unavailable".
     */
    const val UPDATES_REPO = "puzzled-pancake/pocket-realm"

    /** Browser URL of the latest release (the Settings "Release page" button). */
    const val RELEASES_PAGE_URL: String =
        "https://github.com/$UPDATES_REPO/releases/latest"

    // Mirrors AddonRepository's GitHub host allowlist.
    internal val allowedHosts = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "codeload.github.com",
    )

    /**
     * Test seam: hosts additionally allowed for fetch targets (mock server
     * addresses in unit tests). Production leaves it empty.
     */
    internal val extraAllowedHosts = mutableSetOf<String>()

    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    data class UpdateManifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String,
        val minSupportedVersionCode: Int,
        val notes: String,
    ) {
        val appliesToThisApp: Boolean
            get() = com.pocketrealm.BuildConfig.VERSION_CODE >= minSupportedVersionCode
        val isNewer: Boolean
            get() = versionCode > com.pocketrealm.BuildConfig.VERSION_CODE
    }

    sealed class CheckResult {
        data class Available(val manifest: UpdateManifest) : CheckResult()
        data object UpToDate : CheckResult()
        data class Unavailable(val reason: String) : CheckResult()
    }

    /** Track 1: resolve the latest release's update manifest. */
    fun check(
        releasesUrl: String = "https://api.github.com/repos/$UPDATES_REPO/releases/latest",
    ): CheckResult = runCatching { fetchManifest(releasesUrl) }.fold(
        onSuccess = { manifest ->
            when {
                !manifest.appliesToThisApp -> CheckResult.Unavailable(
                    "This app version is older than the release's minimum supported " +
                        "version; a manual migration is required.",
                )
                manifest.isNewer -> CheckResult.Available(manifest)
                else -> CheckResult.UpToDate
            }
        },
        onFailure = { CheckResult.Unavailable("Update channel unavailable: ${it.message}") },
    )

    /**
     * Track 2: resumable, checksum-verified APK download into persistent
     * app storage (not cacheDir — the OS must not trim a staged update).
     * Returns the verified file, reusing an already-verified download
     * without any network traffic.
     */
    fun downloadApk(context: Context, manifest: UpdateManifest, onProgress: (Long) -> Unit = {}): File {
        downloadedApkIfVerified(context, manifest)?.let { verified ->
            onProgress(verified.length())
            return verified
        }
        val dir = File(context.filesDir, "updates")
        // mkdirs before the usableSpace read: a nonexistent path reports 0.
        dir.mkdirs()
        // Storage preflight (ensureVoiceOverStorage pattern): the partial
        // plus headroom for the PackageInstaller session write.
        val required = manifest.size * DOWNLOAD_HEADROOM_NUMERATOR /
            DOWNLOAD_HEADROOM_DENOMINATOR
        check(dir.usableSpace >= required) {
            "Not enough free storage: need about ${required / MIB} MB for the update"
        }
        return ApkDownloader(allowedHosts + extraAllowedHosts)
            .download(File(dir, DOWNLOAD_BASE_NAME), manifest, onProgress)
    }

    /** The staged APK for [manifest] when a complete verified copy is on disk. */
    fun downloadedApkIfVerified(context: Context, manifest: UpdateManifest): File? {
        val target = File(File(context.filesDir, "updates"), DOWNLOAD_BASE_NAME)
        return if (isVerifiedUpdate(target, manifest)) target else null
    }

    /**
     * Reclaims a staged update download (after a committed install, or for
     * a superseded release). Never called right after commit — a user
     * cancelling the confirmation dialog must still retry from disk. The
     * cacheDir pass covers artifacts left by versions that staged updates
     * there.
     */
    fun clearDownloadedApk(context: Context) {
        clearDownloadedApkInDirs(context.cacheDir, File(context.filesDir, "updates"))
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < INSTALL_PERMISSION_API_FLOOR ||
            context.packageManager.canRequestPackageInstalls()

    fun manageUnknownSourcesIntent(): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${com.pocketrealm.BuildConfig.APPLICATION_ID}"),
    )

    /**
     * In-place session install. The system's signature check is the
     * data-preservation guard: a same-signature update replaces the app
     * without touching its data; a mismatched one is refused here.
     */
    fun install(context: Context, apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        // A prior attempt that stalled before its confirmation dialog
        // leaves the session pending; drop leftovers so this commit is the
        // only live one. Abandoning an already-finalizing session throws,
        // which is not fatal here.
        installer.mySessions.forEach { stale ->
            runCatching { installer.abandonSession(stale.sessionId) }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            // The status must reach an ACTIVITY, not a broadcast receiver:
            // the system launches this activity itself (reviving the
            // process if it was killed), so the installer's confirmation
            // UI is then started from a foreground activity — a receiver's
            // startActivity is silently blocked as a background activity
            // launch whenever the app is not visible at commit time.
            // Mutable so the system can fill in the status extras.
            session.commit(
                android.app.PendingIntent.getActivity(
                    context,
                    sessionId,
                    Intent(context, AppUpdateInstallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_MUTABLE,
                ).intentSender,
            )
        }
        AppLog.i(TAG, "update session $sessionId committed")
        return sessionId
    }
}

// ---------------------------------------------------------------------------
// Manifest resolution (top-level so the coordinator object stays small).
// ---------------------------------------------------------------------------

private const val MANIFEST_ASSET_NAME = "update-manifest.json"
private const val MAX_MANIFEST_BYTES = 64L * 1024
private const val SHA256_HEX_LENGTH = 64
private const val MAX_REDIRECT_HOPS = 3
private const val HTTP_REDIRECT_LOW = 300
private const val HTTP_REDIRECT_HIGH = 399
private const val GITHUB_RELEASES_HOST = "github.com"
private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val READ_TIMEOUT_SECONDS = 30L
private const val INSTALL_PERMISSION_API_FLOOR = 26
private const val DOWNLOAD_HEADROOM_NUMERATOR = 2L
private const val DOWNLOAD_HEADROOM_DENOMINATOR = 1L
private const val MIB = 1024L * 1024
private const val STREAM_BUFFER_BYTES = 64 * 1024
private const val DOWNLOAD_BASE_NAME = "update-download"
private val DOWNLOAD_FILE_SUFFIXES = arrayOf("", ".part", ".etag")

private fun allowedFetchHosts(): Set<String> =
    AppUpdateCoordinator.allowedHosts + AppUpdateCoordinator.extraAllowedHosts

/**
 * Reclaims staged update artifacts in [dirs]: the complete download plus
 * its .part/.etag sidecars (older versions staged these in cacheDir;
 * current ones live in filesDir/updates). Contents-only — the directory
 * itself is kept so a download never races a missing parent.
 */
internal fun clearDownloadedApkInDirs(vararg dirs: File) {
    for (dir in dirs) {
        for (suffix in DOWNLOAD_FILE_SUFFIXES) {
            File(dir, DOWNLOAD_BASE_NAME + suffix).delete()
        }
    }
}

/** True when [file] holds a complete, checksum-verified copy of [manifest]. */
internal fun isVerifiedUpdate(
    file: File,
    manifest: AppUpdateCoordinator.UpdateManifest,
): Boolean = file.isFile && file.length() == manifest.size &&
    sha256File(file).equals(manifest.sha256, ignoreCase = true)

private fun sha256File(file: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** okhttp's JVM-pure URL type (android.net.Uri is stubbed in unit tests). */
private fun hostOf(url: String): String =
    url.toHttpUrlOrNull()?.host ?: error("invalid URL: $url")

private fun fetchManifest(releasesUrl: String): AppUpdateCoordinator.UpdateManifest {
    val release = fetchJson(releasesUrl, MAX_MANIFEST_BYTES)
    if (release.has("message") && !release.isNull("message")) {
        throw IOException("GitHub: ${release.getString("message")}")
    }
    val manifestUrl = followRedirects(manifestAssetUrl(release))
    return parseManifest(fetchJson(manifestUrl, MAX_MANIFEST_BYTES))
}

private fun manifestAssetUrl(release: JSONObject): String {
    val assets: JSONArray = release.optJSONArray("assets") ?: error("release has no assets")
    val url = (0 until assets.length())
        .asSequence()
        .mapNotNull { assets.optJSONObject(it) }
        .firstOrNull { it.optString("name") == MANIFEST_ASSET_NAME }
        ?.optString("browser_download_url")
        ?.takeIf { it.isNotBlank() }
    return url ?: error("release carries no $MANIFEST_ASSET_NAME asset")
}

private fun parseManifest(manifest: JSONObject): AppUpdateCoordinator.UpdateManifest {
    val sha256 = manifest.getString("sha256").lowercase()
    require(sha256.length == SHA256_HEX_LENGTH && sha256.all { it in '0'..'f' }) {
        "manifest sha256 is malformed"
    }
    return AppUpdateCoordinator.UpdateManifest(
        versionCode = manifest.getInt("versionCode"),
        versionName = manifest.getString("versionName"),
        apkUrl = manifest.getString("apkUrl"),
        size = manifest.getLong("size"),
        sha256 = sha256,
        minSupportedVersionCode = manifest.optInt("minSupportedVersionCode", 1),
        notes = manifest.optString("notes"),
    )
}

private fun fetchJson(url: String, maxBytes: Long): JSONObject {
    check(hostOf(url) in allowedFetchHosts()) { "URL host not allowed: $url" }
    val request = Request.Builder().url(url).build()
    AppUpdateCoordinator.client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val body = requireNotNull(response.body) { "empty body" }
        // Manifests come from allowlisted GitHub assets; the cap is checked
        // after the (small) read as a sanity bound.
        val bytes = body.bytes().also {
            check(it.size.toLong() <= maxBytes) { "manifest exceeds size cap" }
        }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
}

/** Resolves redirect hops within the allowlist, up to [MAX_REDIRECT_HOPS]. */
private fun followRedirects(url: String): String {
    var current = url
    // The initial host must itself be allowed before any probe is sent.
    check(hostOf(current) in allowedFetchHosts()) { "URL host not allowed: $current" }
    var hops = 0
    while (true) {
        val host = hostOf(current)
        if (host != GITHUB_RELEASES_HOST) return current
        check(hops < MAX_REDIRECT_HOPS) { "too many redirects" }
        hops += 1
        val location = AppUpdateCoordinator.client.newCall(
            Request.Builder().url(current).head().build(),
        ).execute().use { response ->
            if (response.code in HTTP_REDIRECT_LOW..HTTP_REDIRECT_HIGH) {
                response.header("Location") ?: error("redirect without Location")
            } else {
                return current
            }
        }
        val locationHost = hostOf(location)
        check(locationHost in allowedFetchHosts()) {
            "redirect to non-GitHub host refused: $locationHost"
        }
        current = location
    }
}

/**
 * Resumable APK download with sha256 verification (F6 Track 2). The body is
 * STREAMED to the .part file through a fixed buffer (the artifact is
 * hundreds of MB — never buffered in heap); a 206 response APPENDS to the
 * existing partial, any other code restarts from zero. Resume state
 * (.part/.etag sidecars) survives process death; a failed, short, or
 * tampered download is discarded, never installed.
 */
internal class ApkDownloader(private val allowedHosts: Set<String>) {
    fun download(
        baseName: File,
        manifest: AppUpdateCoordinator.UpdateManifest,
        onProgress: (Long) -> Unit = {},
    ): File {
        // A previously verified download is reused as-is — no network, no
        // delete-and-redownload. This must precede both the start==0
        // cleanup below (which would delete the verified file) and the
        // redirect resolution (which HEAD-probes github.com). A complete
        // baseName and a stale .part cannot coexist — the rename that
        // produces baseName atomically consumes .part — so skipping
        // sidecar cleanup here is safe.
        if (isVerifiedUpdate(baseName, manifest)) {
            onProgress(baseName.length())
            return baseName
        }
        val partial = File(baseName.parentFile, baseName.name + ".part")
        val etagFile = File(baseName.parentFile, baseName.name + ".etag")
        if (partial.isFile && partial.length() >= manifest.size) partial.delete()
        val start = partial.length()
        val etag = if (start > 0 && etagFile.isFile) etagFile.readText() else null
        if (start == 0L) {
            if (baseName.isFile) baseName.delete()
            partial.delete()
        }
        // The APK URL is a github.com browser_download_url that redirects to
        // the signed CDN; resolve hops once per attempt so the Range request
        // targets the final host.
        val spec = StreamSpec(
            url = followRedirects(manifest.apkUrl),
            allowedHosts = allowedHosts + AppUpdateCoordinator.allowedHosts,
            total = manifest.size,
            start = start,
            etag = etag,
            partial = partial,
        )
        val (_, newEtag) = streamToPartial(spec, onProgress)
        if (newEtag != null) etagFile.writeText(newEtag)
        if (partial.length() != manifest.size) {
            onProgress(partial.length())
            error("download incomplete (${partial.length()}/${manifest.size})")
        }
        if (!sha256File(partial).equals(manifest.sha256, ignoreCase = true)) {
            partial.delete()
            error("checksum mismatch — download discarded")
        }
        if (baseName.isFile) baseName.delete()
        check(partial.renameTo(baseName))
        etagFile.delete()
        onProgress(baseName.length())
        return baseName
    }

    /** Streams one request into [partial]; returns (httpCode, etag). */
    private fun streamToPartial(spec: StreamSpec, onProgress: (Long) -> Unit): Pair<Int, String?> {
        val host = hostOf(spec.url)
        check(host in spec.allowedHosts) { "download host refused: $host" }
        val request = Request.Builder().url(spec.url).apply {
            if (spec.start > 0) header("Range", "bytes=${spec.start}-")
            if (spec.etag != null) header("If-Range", spec.etag)
        }.build()
        AppUpdateCoordinator.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "empty body" }
            streamBody(response.code, body, spec, onProgress)
            return response.code to response.header("ETag")
        }
    }

    private data class StreamSpec(
        val url: String,
        val allowedHosts: Set<String>,
        val total: Long,
        val start: Long,
        val etag: String?,
        val partial: File,
    )

    /** Writes the body: appends on 206 resume, restarts otherwise. */
    private fun streamBody(
        code: Int,
        body: okhttp3.ResponseBody,
        spec: StreamSpec,
        onProgress: (Long) -> Unit,
    ) {
        val append = spec.start > 0 && code == APPLIED_RANGE_CODE
        if (!append && spec.start > 0) spec.partial.delete()
        var written = if (append) spec.start else 0L
        FileOutputStream(spec.partial, append).use { output ->
            val input = body.byteStream()
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                check(written <= spec.total) { "download exceeds size cap" }
                onProgress(written)
            }
            // One durability point per attempt: power-loss corruption is
            // caught by the length + sha256 gates anyway; per-chunk fsync
            // would add minutes of flash sync overhead on a large APK.
            output.fd.sync()
        }
    }

    private companion object {
        const val APPLIED_RANGE_CODE = 206
    }
}
