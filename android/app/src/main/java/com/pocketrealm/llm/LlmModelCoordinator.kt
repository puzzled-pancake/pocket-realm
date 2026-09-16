package com.pocketrealm.llm

import android.content.Context
import com.pocketrealm.fs.FileDigests
import com.pocketrealm.log.AppLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * On-device LLM model (GGUF) delivery for the in-process playerbot llama
 * backend. Follows the AppUpdateCoordinator download discipline: resumable
 * 206/RANGE streaming into a .part file, ETag If-Range resume, sha256
 * verification with discard-on-mismatch, and reuse of an already-verified
 * file without network traffic.
 *
 * Hugging Face specifics: a `huggingface.co/.../resolve/...` URL 302s to a
 * signed CDN URL that expires. Redirect hops are HEAD-probed within an exact
 * host allowlist, and a resume that fails with 403/412 re-resolves the
 * ORIGINAL resolve URL for a fresh signed target before retrying once; a
 * changed ETag then degrades to a clean restart (200 semantics), never a
 * corrupt merge.
 */
object LlmModelCoordinator {
    private const val TAG = "LlmModel"
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val MAX_REDIRECT_HOPS = 4
    private const val HTTP_REDIRECT_LOW = 300
    private const val HTTP_REDIRECT_HIGH = 399

    /** The resolve-page host plus the exact CDN hosts it signs URLs for. */
    internal val allowedHosts = setOf(
        "huggingface.co",
        "cdn-lfs.hf.co",
        "cdn-lfs.huggingface.co",
        "cas-bridge.xethub.hf.co",
    )

    /** Test seam (mock server addresses); empty in production. */
    internal val extraAllowedHosts = mutableSetOf<String>()

    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15L, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    data class ModelDescriptor(
        val fileName: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    /**
     * The selectable models live in [LlmModelRegistry]; this alias is the
     * download machinery's default descriptor. The DOWNLOAD default is
     * the base model (the only descriptor with a URL); the SELECTED
     * default is the registry's TUNED_E2B (local-only; no download
     * distribution). Callers choosing a model must pass the
     * settings-derived descriptor, not this default.
     */
    val primaryModel: ModelDescriptor = LlmModelRegistry.BASE_E2B.let {
        ModelDescriptor(fileName = it.fileName, url = it.url, size = it.size, sha256 = it.sha256)
    }

    /** Absolute path the native runtime expects (AiPlayerbot.LLMModelPath). */
    fun modelPath(context: Context, descriptor: ModelDescriptor = primaryModel): File =
        File(File(context.filesDir, "models"), descriptor.fileName)

    /** The staged file for a registry descriptor, selected by settings id. */
    fun modelPathFor(context: Context, modelId: String?): File =
        modelPath(context, LlmModelRegistry.byId(modelId).let {
            ModelDescriptor(fileName = it.fileName, url = it.url, size = it.size, sha256 = it.sha256)
        })

    /** The verified model file, or null when it is absent or unverified. */
    fun modelIfVerified(
        context: Context,
        descriptor: ModelDescriptor = primaryModel,
    ): File? {
        val target = modelPath(context, descriptor)
        if (!target.isFile || descriptor.sha256.length != 64) return null
        // A size mismatch is the common stale-partial case: skip the
        // multi-GB hash and fall through to re-download.
        if (descriptor.size > 0 && target.length() != descriptor.size) return null
        return if (sha256File(target).equals(descriptor.sha256, ignoreCase = true)) target else null
    }

    /**
     * Blocking download; run from a worker thread/foreground service. Returns
     * the verified model file. Reuses a verified copy without network use.
     * [isCancelled] is polled per chunk so a cancel actually stops the
     * transfer (OkHttp reads are blocking; coroutines cannot preempt them).
     */
    fun download(
        context: Context,
        descriptor: ModelDescriptor = primaryModel,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File {
        modelIfVerified(context, descriptor)?.let { verified ->
            onProgress(verified.length())
            return verified
        }
        require(descriptor.size > 0) { "model descriptor has no size cap" }
        val dir = File(context.filesDir, "models")
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

        var resolved = descriptor.url
        // a partial already matching the pinned size was fully streamed by a
        // previous run that died before rename (the checksum pass over the
        // full file is a tens-of-seconds window): verify and adopt it instead
        // of deleting and re-downloading the whole file. Only a strictly
        // oversize partial (corruption) is discarded above.
        var attempt = if (partial.isFile && partial.length() == descriptor.size)
            StreamResult.DONE
        else {
            resolved = resolveRedirects(descriptor.url)
            streamToPartial(resolved, descriptor, start, etag, partial, onProgress, isCancelled)
        }
        if (attempt == StreamResult.EXPIRED) {
            // a signed CDN URL is stale or being rejected regardless of
            // resume state: the FIRST get of a freshly resolved URL can also
            // come back 403/412 (repo gated/removed between the HEAD probe
            // and the GET). Re-resolve the original huggingface.co resolve
            // URL and retry once from scratch.
            AppLog.i(TAG, "model URL rejected (403/412), re-resolving")
            partial.delete()
            etagFile.delete()
            etag = null
            start = 0
            resolved = resolveRedirects(descriptor.url)
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
        check(partial.renameTo(base))
        etagFile.delete()
        onProgress(base.length())
        return base
    }

    internal enum class StreamResult { DONE, FAILED, EXPIRED }

    private fun streamToPartial(
        url: String,
        descriptor: ModelDescriptor,
        start: Long,
        etag: String?,
        partial: File,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): StreamResult {
        val host = url.toHttpUrlOrNull()?.host ?: error("invalid URL: $url")
        check(host in allowedHosts + extraAllowedHosts) { "model host refused: $host" }
        val request = Request.Builder().url(url).apply {
            if (start > 0) header("Range", "bytes=$start-")
            if (etag != null) header("If-Range", etag)
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 412) return StreamResult.EXPIRED
            if (!response.isSuccessful) return StreamResult.FAILED
            val body = requireNotNull(response.body) { "empty body" }
            val append = start > 0 && response.code == 206
            if (!append && start > 0) partial.delete()
            // persist the validator immediately: a resume after an interrupted
            // first attempt must send If-Range, not a bare Range that could
            // append to a replaced upstream file. A 206 that does not echo an
            // ETag must NOT blank the validator saved by the earlier attempt
            val validator = response.header("ETag")
            if (validator != null || !append) {
                File(partial.parentFile, partial.nameWithoutExtension + ".etag").let { sidecar ->
                    if (!sidecar.isDirectory) sidecar.writeText(validator ?: "")
                }
            }
            var written = if (append) start else 0L
            FileOutputStream(partial, append).use { output ->
                val input = body.byteStream()
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
            return StreamResult.DONE
        }
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
                val location = client.newCall(
                    Request.Builder().url(current).head().build(),
                ).execute().use { response ->
                    if (response.code in HTTP_REDIRECT_LOW..HTTP_REDIRECT_HIGH) {
                        response.header("Location") ?: error("redirect without Location")
                    } else {
                        return current
                    }
                }
                check(hostOf(location) in allowedHosts + extraAllowedHosts) {
                    "redirect to non-allowlisted host refused: $location"
                }
                current = location
            } else {
                return current
            }
        }
    }
}

private fun hostOf(url: String): String =
    url.toHttpUrlOrNull()?.host ?: error("invalid URL: $url")

private fun sha256File(file: File): String = FileDigests.sha256(file)
