package com.pocketrealm.client

import com.pocketrealm.update.AppUpdateCoordinator
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The pinned downloader's failure modes
 * all carry exact reasons, delete the partial file, and never import. The
 * seam is [CommunityVulkanDriverDownload.downloadPinned] with MockWebServer.
 */
class CommunityVulkanDriverDownloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun byteBody(bytes: ByteArray): Buffer = Buffer().apply { write(bytes) }

    private fun destination(): File =
        File(temp.newFolder("dest-${System.nanoTime()}"), "community-driver.zip")

    @Test
    fun verifiedDownloadReturnsTheFileWithTheExactContent() {
        MockWebServer().use { server ->
            server.start()
            val body = ByteArray(4096) { (it % 251).toByte() }
            server.enqueue(
                MockResponse.Builder().code(200).body(byteBody(body)).build(),
            )
            val destination = destination()
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = body.size.toLong(),
                expectedSha256 = sha256(body),
                destination = destination,
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val downloaded = outcome as CommunityVulkanDriverDownload.Outcome.Downloaded
            assertArrayEquals(body, downloaded.file.readBytes())
        }
    }

    @Test
    fun digestMismatchIsReportedVerbatimAndDeletesThePartialFile() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200).body("not the driver").build(),
            )
            val destination = destination()
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = 14L,
                expectedSha256 = "0".repeat(64),
                destination = destination,
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertEquals(CommunityVulkanDriverDownload.DIGEST_MISMATCH, failed.reason)
            assertFalse(destination.exists())
        }
    }

    @Test
    fun declaredSizeMismatchIsRejectedBeforeAnyBytesAreTrusted() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200).body(byteBody(ByteArray(64))).build(),
            )
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = 65L,
                expectedSha256 = "0".repeat(64),
                destination = destination(),
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("claims 64 bytes"))
            assertTrue(failed.reason.contains("pinned driver is 65 bytes"))
        }
    }

    @Test
    fun httpErrorsAreRejectedWithTheStatusCode() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).build())
            val destination = destination()
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/gone.zip").toString(),
                expectedSize = 1L,
                expectedSha256 = "0".repeat(64),
                destination = destination,
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("refused by the server (HTTP 404)"))
            assertFalse(destination.exists())
        }
    }

    @Test
    fun redirectsToForeignHostsAreRejectedPerHop() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(302)
                    .setHeader("Location", "https://evil.example/driver.zip")
                    .build(),
            )
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = 1L,
                expectedSha256 = "0".repeat(64),
                destination = destination(),
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("not one Pocket Realm trusts (evil.example)"))
        }
    }

    @Test
    fun redirectLoopsTerminateAtTheCapWithTheExactReason() {
        // Every followed redirect must be https (the per-hop contract), so
        // pinning the loop cap needs a TLS mock server the client trusts.
        val certificate = okhttp3.tls.HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverHandshake = okhttp3.tls.HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientHandshake = okhttp3.tls.HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        MockWebServer().use { server ->
            server.useHttps(serverHandshake.sslSocketFactory())
            server.start()
            val self = server.url("/loop.zip").toString()
            // More hops than the cap: an allowlisted self-redirect cycle
            // must terminate with the bounded-loop reason, not spin.
            repeat(6) {
                server.enqueue(
                    MockResponse.Builder().code(302)
                        .setHeader("Location", self)
                        .build(),
                )
            }
            val destination = destination()
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = self,
                expectedSize = 1L,
                expectedSha256 = "0".repeat(64),
                destination = destination,
                client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .sslSocketFactory(
                        clientHandshake.sslSocketFactory(),
                        clientHandshake.trustManager,
                    )
                    .build(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("redirected more than"))
            assertFalse(destination.exists())
        }
    }

    @Test
    fun cleartextRedirectTargetsAreRejectedEvenOnAllowedHosts() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(302)
                    .setHeader("Location", "http://${server.hostName}:${server.port}/next")
                    .build(),
            )
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = 1L,
                expectedSha256 = "0".repeat(64),
                destination = destination(),
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("redirected to a non-HTTPS URL"))
        }
    }

    @Test
    fun unresolvableRedirectLocationsAreRejected() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(302)
                    // A scheme with no host does not resolve to an HttpUrl.
                    .setHeader("Location", "https://")
                    .build(),
            )
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = 1L,
                expectedSha256 = "0".repeat(64),
                destination = destination(),
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("could not be resolved"))
        }
    }

    @Test
    fun unparseableInitialUrlsAreRejected() {
        val outcome = CommunityVulkanDriverDownload.downloadPinned(
            url = "not a url",
            expectedSize = 1L,
            expectedSha256 = "0".repeat(64),
            destination = destination(),
            client = client(),
            allowedHosts = setOf("localhost"),
        )
        val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
        assertTrue(failed.reason.contains("could not be parsed"))
    }

    @Test
    fun transportFailuresCarryTheExactReasonAndDeleteTheTemp() {
        val destination = destination()
        // Port 1 on localhost: connection refused — no server involved.
        val outcome = CommunityVulkanDriverDownload.downloadPinned(
            url = "http://localhost:1/driver.zip",
            expectedSize = 1L,
            expectedSha256 = "0".repeat(64),
            destination = destination,
            client = client(),
            allowedHosts = setOf("localhost"),
        )
        val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
        assertTrue(failed.reason.startsWith("The download failed: "))
        assertFalse(destination.exists())
    }

    @Test
    fun responsesWithoutALengthAreRefused() {
        MockWebServer().use { server ->
            server.start()
            // Chunked responses carry no Content-Length header.
            server.enqueue(
                MockResponse.Builder().code(200)
                    .chunkedBody("streamed-body", 7)
                    .build(),
            )
            val destination = destination()
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = 13L,
                expectedSha256 = "0".repeat(64),
                destination = destination,
                client = client(),
                allowedHosts = setOf(server.hostName),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("did not report its size"))
            assertFalse(destination.exists())
        }
    }

    @Test
    fun theDefaultAllowlistIsTheUpdatersSharedGitHubSet() {
        MockWebServer().use { server ->
            server.start()
            // No custom hosts: the default must refuse a mock-server target.
            val outcome = CommunityVulkanDriverDownload.downloadPinned(
                url = server.url("/driver.zip").toString(),
                expectedSize = 1L,
                expectedSha256 = "0".repeat(64),
                destination = destination(),
                client = client(),
            )
            val failed = outcome as CommunityVulkanDriverDownload.Outcome.Failed
            assertTrue(failed.reason.contains("not one Pocket Realm trusts"))
            // The allowlist is read from the updater's shared constants at
            // every call (construction-shared, never duplicated).
            assertTrue(AppUpdateCoordinator.allowedHosts.contains("github.com"))
            assertTrue(
                AppUpdateCoordinator.allowedHosts
                    .contains("release-assets.githubusercontent.com"),
            )
        }
    }
}
