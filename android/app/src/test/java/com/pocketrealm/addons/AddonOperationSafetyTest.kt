package com.pocketrealm.addons

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AddonOperationSafetyTest {
    @Test fun `cancelling a blocked download cancels the active HTTP call`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
            server.start()
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/archive.zip")).build())
            val token = AddonOperationToken()
            token.attach(call)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val blocked = executor.submit<Throwable?> {
                    runCatching { call.execute().use { it.body.string() } }.exceptionOrNull()
                }
                assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
                assertTrue(token.cancel())
                assertTrue(call.isCanceled())
                assertTrue(blocked.get(2, TimeUnit.SECONDS) != null)
                assertThrows(CancellationException::class.java, token::checkpoint)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun `commit fence makes cancellation unavailable`() {
        val token = AddonOperationToken()
        token.beginCommit()
        assertFalse(token.isCancellable)
        assertFalse(token.cancel())
        token.checkpoint()
    }

    @Test fun `mid extraction cancellation cannot alter published package or registry`() {
        val root = Files.createTempDirectory("addon-safety-").toFile().apply { deleteOnExit() }
        val published = File(root, "packages/example/old").apply { mkdirs() }
        File(published, "marker.txt").writeText("old-package")
        val registry = File(root, "registry.json").apply { writeText("old-registry") }
        val archive = File(root, "archive.zip")
        ZipArchiveOutputStream(archive).use { output ->
            val payload = ByteArray(256 * 1024) { (it and 0xff).toByte() }
            listOf(
                "wrapper/Example.toc" to "## Interface: 11200\nExample.lua\nPayload.bin\n".toByteArray(),
                "wrapper/Example.lua" to "-- safe\n".toByteArray(),
                "wrapper/Payload.bin" to payload,
            ).forEach { (name, bytes) ->
                output.putArchiveEntry(ZipArchiveEntry(name))
                output.write(bytes)
                output.closeArchiveEntry()
            }
        }
        val validated = AddonArchiveValidator().validate(archive, "DifferentRepo")
        val staging = File(root, "packages/.staging-test").apply { mkdirs() }
        var checkpoints = 0
        assertThrows(CancellationException::class.java) {
            AddonArchiveExtractor().extract(archive, validated, staging) {
                if (++checkpoints == 6) throw CancellationException("cancel extraction")
            }
        }
        staging.deleteRecursively() // mirrors AddonRepository's finally cleanup

        assertEquals("old-registry", registry.readText())
        assertEquals("old-package", File(published, "marker.txt").readText())
        assertFalse(staging.exists())
    }
}
