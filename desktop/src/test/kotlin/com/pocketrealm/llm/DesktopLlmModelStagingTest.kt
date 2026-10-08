package com.pocketrealm.llm

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop staging twin's contract (DESK-80): sha256-verified staging
 * from the shared registry descriptors, resumable 206/RANGE, ETag If-Range,
 * checksum-mismatch discard, the exact host allowlist, and the size cap.
 * Served by a local fixture HTTP server registered in the test seam
 * (`extraAllowedHosts`), pure JVM.
 */
class DesktopLlmModelStagingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var server: HttpServer? = null

    @After
    fun tearDown() {
        DesktopLlmModelStaging.extraAllowedHosts.remove("localhost")
        server?.stop(0)
        server = null
    }

    /** A loopback fixture server answering [body] with an optional ETag;
     * honors single Range headers with 206 semantics. */
    private fun serve(body: ByteArray, etag: String? = null): HttpServer {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/model.gguf") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val ifRange = exchange.requestHeaders.getFirst("If-Range")
            if (exchange.requestMethod == "HEAD") {
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            if (ifRange != null && etag != null && ifRange != etag) {
                // validator changed: full restart semantics
                exchange.getResponseHeaders().add("ETag", etag)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
                return@createContext
            }
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").substringBefore('-').toLong()
                if (start in 1 until body.size) {
                    exchange.getResponseHeaders().add("ETag", etag ?: "\"v1\"")
                    exchange.sendResponseHeaders(206, (body.size - start).toLong())
                    exchange.responseBody.use { it.write(body.copyOfRange(start.toInt(), body.size)) }
                    exchange.close()
                    return@createContext
                }
            }
            if (etag != null) exchange.getResponseHeaders().add("ETag", etag)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        http.start()
        server = http
        DesktopLlmModelStaging.extraAllowedHosts.add("localhost")
        return http
    }

    private fun descriptor(url: String, body: ByteArray): LlmModelDescriptor = LlmModelDescriptor(
        id = "fixture-model",
        fileName = "fixture-model.gguf",
        url = url,
        size = body.size.toLong(),
        sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02x".format(it) },
        profile = LlmSamplingProfile(temperature = 0.5, topP = 0.9, topK = 20, maxTokens = 64),
        tierProfile = LlmTierProfile(
            contextLength = 2048, generationTimeoutSec = 30,
            maxSimultaneousGenerations = 1, governorBotMax = 1, governorGlobalMax = 1,
            factsCap = 4, memoriesTail = 2,
        ),
    )

    @Test
    fun downloadVerifiesAndPublishesTheStagedFile() {
        val body = "pocket-realm fixture gguf bytes".toByteArray()
        val http = serve(body, etag = "\"v1\"")
        val root = folder.newFolder("root")
        val file = DesktopLlmModelStaging.download(
            root,
            descriptor("http://localhost:${http.address.port}/model.gguf", body),
        )
        assertTrue(file.isFile)
        assertEquals(body.size.toLong(), file.length())
        // verified copy is REUSED without network: stop the server first
        server?.stop(0)
        server = null
        val reused = DesktopLlmModelStaging.download(
            root,
            descriptor("http://localhost:1/model.gguf", body),
        )
        assertEquals(file.absolutePath, reused.absolutePath)
    }

    @Test
    fun resumeAppendsFromThePartialWithEtagValidator() {
        val body = ByteArray(4096) { (it % 251).toByte() }
        val http = serve(body, etag = "\"resume-etag\"")
        val root = folder.newFolder("root")
        val dir = DesktopLlmModelStaging.modelsDir(root)
        dir.mkdirs()
        // A previous run streamed the first half and died before rename.
        File(dir, "fixture-model.gguf.part").writeBytes(body.copyOfRange(0, 2048))
        File(dir, "fixture-model.etag").writeText("\"resume-etag\"")
        val file = DesktopLlmModelStaging.download(
            root,
            descriptor("http://localhost:${http.address.port}/model.gguf", body),
        )
        assertEquals(body.size.toLong(), file.length())
        assertTrue("resumed bytes must equal the upstream", file.readBytes().contentEquals(body))
    }

    @Test
    fun checksumMismatchDiscardsTheDownload() {
        val body = "real upstream bytes".toByteArray()
        val http = serve(body)
        val root = folder.newFolder("root")
        val descriptor = descriptor("http://localhost:${http.address.port}/model.gguf", body)
            .copy(sha256 = "ab".repeat(32))
        try {
            DesktopLlmModelStaging.download(root, descriptor)
            fail("a checksum mismatch must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "must carry the discard copy: ${expected.message}",
                (expected.message ?: "").contains("checksum mismatch"),
            )
        }
        assertTrue(
            "the mismatched partial must be discarded",
            !File(DesktopLlmModelStaging.modelsDir(root), "fixture-model.gguf").isFile,
        )
    }

    @Test
    fun unverifiedOrMissingStagedFileIsNull() {
        val root = folder.newFolder("root")
        val body = "absent".toByteArray()
        assertNull(DesktopLlmModelStaging.modelIfVerified(root, descriptor("http://localhost:1/x", body)))
        val dir = DesktopLlmModelStaging.modelsDir(root)
        dir.mkdirs()
        // A wrong-size copy is the stale-partial case: null without a hash.
        File(dir, "fixture-model.gguf").writeBytes("wrong size".toByteArray())
        assertNull(DesktopLlmModelStaging.modelIfVerified(root, descriptor("http://localhost:1/x", body)))
    }

    @Test
    fun nonAllowlistedHostRefuses() {
        val http = serve("blocked".toByteArray())
        val root = folder.newFolder("root")
        DesktopLlmModelStaging.extraAllowedHosts.remove("localhost")
        try {
            // 127.0.0.1 is NOT in the allowlist (only the test seam's
            // "localhost" is): the host gate must refuse before any byte.
            DesktopLlmModelStaging.download(
                root,
                descriptor("http://127.0.0.1:${http.address.port}/model.gguf", "blocked".toByteArray()),
            )
            fail("a non-allowlisted host must refuse")
        } catch (expected: IllegalStateException) {
            // resolveRedirects' first gate: "URL host not allowed" (the
            // streamToPartial check's "model host refused" never runs).
            val message = expected.message ?: ""
            assertTrue(
                "must carry the host refusal: $message",
                message.contains("not allowed") || message.contains("host refused"),
            )
        }
    }

    @Test
    fun registryDescriptorsCarryTheirPins() {
        // The staging lane exists to prove the shared registry's pins on
        // desktop: the descriptors the manager stages must carry 64-hex
        // digests and positive sizes (the download's cap contract).
        LlmModelRegistry.all.forEach { model ->
            assertTrue("sha256 pin: ${model.id}", model.sha256.length == 64)
            assertTrue("size cap: ${model.id}", model.size > 0)
            assertNotNull(LlmModelRegistry.byId(model.id))
        }
    }
}
