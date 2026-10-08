package com.pocketrealm.llm

import com.pocketrealm.fs.FileDigests
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The desktop staging twin of the Android llm/LlmModelCoordinator (DESK-80:
 * okhttp -> java.net.http; the FGS delivery lane of LlmModelDownloadService
 * collapses into this downloader's progress/cancel seams — a desktop has no
 * foreground-service contract). Resumable 206/RANGE streaming into a .part
 * file, ETag If-Range resume, sha256 verification with discard-on-mismatch
 * (the shared [FileDigests]), and reuse of an already-verified file without
 * network traffic. Host discipline matches the Android twin exactly: the
 * exact huggingface.co resolve host + its signed CDN hosts, HEAD-probed
 * redirect hops, [extraAllowedHosts] as the test seam.
 */
@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "LongParameterList",
    "MagicNumber",
    "ComplexCondition",
) // verbatim twin of the Android coordinator's streaming discipline (linted
// there under the app baseline; forking the shape to satisfy lint here would
// fork the resume/verify semantics this lane's integrity depends on)
object DesktopLlmModelStaging {

    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val MAX_REDIRECT_HOPS = 4
    private const val HTTP_REDIRECT_LOW = 300
    private const val HTTP_REDIRECT_HIGH = 399
    private val CONNECT_TIMEOUT = Duration.ofSeconds(15)
    private val REQUEST_TIMEOUT = Duration.ofSeconds(60)

    /** The resolve-page host plus the exact CDN hosts it signs URLs for. */
    internal val allowedHosts = setOf(
        "huggingface.co",
        "cdn-lfs.hf.co",
        "cdn-lfs.huggingface.co",
        "cas-bridge.xethub.hf.co",
    )

    /** Test seam (local fixture servers); empty in production. */
    internal val extraAllowedHosts = mutableSetOf<String>()

    internal val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    /** The models directory under the caller's llm staging root: the
     * desktop roots resolve that to %LOCALAPPDATA%/PocketRealm/runtime/llm
     * (DESK-28's letter), so the staged layout is runtime/llm/models. */
    fun modelsDir(llmRoot: File): File = File(llmRoot, "models")

    /** Absolute path the sidecar launch expects (the AiPlayerbot.LLMModelPath
     * twin — the registry descriptor's file name under the models dir). */
    fun modelPath(root: File, descriptor: LlmModelDescriptor): File =
        File(modelsDir(root), descriptor.fileName)

    /** The verified model file, or null when it is absent or unverified. */
    fun modelIfVerified(root: File, descriptor: LlmModelDescriptor): File? {
        val target = modelPath(root, descriptor)
        if (!target.isFile || descriptor.sha256.length != 64) return null
        // A size mismatch is the common stale-partial case: skip the
        // multi-GB hash and fall through to re-download.
        if (descriptor.size > 0 && target.length() != descriptor.size) return null
        return if (sha256File(target).equals(descriptor.sha256, ignoreCase = true)) target else null
    }

    /**
     * Blocking download; run from a worker thread. Returns the verified
     * model file. Reuses a verified copy without network use. [isCancelled]
     * is polled per chunk so a cancel actually stops the transfer (the
     * Android twin's chunk-poll discipline).
     */
    fun download(
        root: File,
        descriptor: LlmModelDescriptor,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        modelIfVerified(root, descriptor)?.let { verified ->
            onProgress(verified.length())
            return verified
        }
        require(descriptor.size > 0) { "model descriptor has no size cap" }
        val dir = modelsDir(root)
        dir.mkdirs()
        val base = File(dir, descriptor.fileName)
        val partial = File(dir, descriptor.fileName + ".part")
        val etagFile = File(dir, descriptor.fileName + ".etag")

        if (partial.isFile && partial.length() > descriptor.size) partial.delete()
        var start = partial.length()
        var etag = if (start > 0 && etagFile.isFile) etagFile.readText().takeIf { it.isNotBlank() } else null
        if (start == 0L) {
            base.delete()
            partial.delete()
        }

        // a partial already matching the pinned size was fully streamed by a
        // previous run that died before rename: verify and adopt it instead
        // of re-downloading (the Android twin's adopt-a-complete-partial leg).
        var attempt = if (partial.isFile && partial.length() == descriptor.size) {
            StreamResult.DONE
        } else {
            val resolved = resolveRedirects(descriptor.url)
            streamToPartial(resolved, descriptor, start, etag, partial, onProgress, isCancelled)
        }
        if (attempt == StreamResult.EXPIRED) {
            // a signed CDN URL is stale or being rejected regardless of resume
            // state: re-resolve the ORIGINAL resolve URL and retry once from
            // scratch (the Android twin's single re-resolve retry).
            partial.delete()
            etagFile.delete()
            etag = null
            start = 0
            val resolved = resolveRedirects(descriptor.url)
            attempt = streamToPartial(resolved, descriptor, start, null, partial, onProgress, isCancelled)
            if (attempt == StreamResult.EXPIRED) {
                error("model URL rejected (403/412) after re-resolve — is the repo gated or removed?")
            }
        }
        if (attempt == StreamResult.FAILED) error("model download failed")

        if (partial.length() != descriptor.size) {
            onProgress(partial.length())
            error("model download incomplete (${partial.length()}/${descriptor.size})")
        }
        if (descriptor.sha256.length == 64 &&
            !sha256File(partial).equals(descriptor.sha256, ignoreCase = true)
        ) {
            partial.delete()
            error("model checksum mismatch — download discarded")
        }
        if (base.isFile) base.delete()
        check(partial.renameTo(base)) { "cannot publish the staged model file" }
        etagFile.delete()
        onProgress(base.length())
        return base
    }

    internal enum class StreamResult { DONE, FAILED, EXPIRED }

    private fun streamToPartial(
        url: String,
        descriptor: LlmModelDescriptor,
        start: Long,
        etag: String?,
        partial: File,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): StreamResult {
        val host = hostOf(url)
        check(host in allowedHosts + extraAllowedHosts) { "model host refused: $host" }
        val request = requestBuilder(url)
            .apply {
                if (start > 0) header("Range", "bytes=$start-")
                if (etag != null) header("If-Range", etag)
            }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() == 403 || response.statusCode() == 412) {
            runCatching { response.body().close() }
            return StreamResult.EXPIRED
        }
        if (response.statusCode() !in 200..299) {
            runCatching { response.body().close() }
            return StreamResult.FAILED
        }
        val append = start > 0 && response.statusCode() == 206
        if (!append && start > 0) partial.delete()
        // persist the validator immediately: a resume after an interrupted
        // first attempt must send If-Range, not a bare Range that could
        // append to a replaced upstream file. A 206 that does not echo an
        // ETag must NOT blank the validator saved by the earlier attempt.
        val validator = response.headers().firstValue("ETag").orElse(null)
        if (validator != null || !append) {
            File(partial.parentFile, partial.nameWithoutExtension + ".etag").let { sidecar ->
                if (!sidecar.isDirectory) sidecar.writeText(validator ?: "")
            }
        }
        var written = if (append) start else 0L
        FileOutputStream(partial, append).use { output ->
            response.body().use { input ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (isCancelled()) {
                        output.fd.sync()
                        throw IOException("model download cancelled")
                    }
                    output.write(buffer, 0, read)
                    written += read
                    check(written <= descriptor.size) { "model exceeds size cap" }
                    onProgress(written)
                }
                output.fd.sync()
            }
        }
        return StreamResult.DONE
    }

    /** HEAD-probes huggingface.co resolve hops within the exact allowlist. */
    internal fun resolveRedirects(url: String): String {
        var current = url
        check(hostOf(current) in allowedHosts + extraAllowedHosts) {
            "URL host not allowed: $current"
        }
        var hops = 0
        while (true) {
            if (hostOf(current) == "huggingface.co") {
                check(hops < MAX_REDIRECT_HOPS) { "too many redirects" }
                hops += 1
                val request = requestBuilder(current)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() in HTTP_REDIRECT_LOW..HTTP_REDIRECT_HIGH) {
                    val location = response.headers().firstValue("Location").orElse(null)
                        ?: error("redirect without Location")
                    check(hostOf(location) in allowedHosts + extraAllowedHosts) {
                        "redirect to non-allowlisted host refused: $location"
                    }
                    current = location
                } else {
                    return current
                }
            } else {
                return current
            }
        }
    }

    private fun requestBuilder(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)

    private fun hostOf(url: String): String =
        runCatching { URI.create(url).host?.lowercase() }.getOrNull()
            ?: error("invalid URL: $url")

    private fun sha256File(file: File): String = FileDigests.sha256(file)
}
