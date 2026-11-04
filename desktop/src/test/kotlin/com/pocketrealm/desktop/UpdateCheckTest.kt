package com.pocketrealm.desktop

import com.pocketrealm.fs.FileDigests
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the desktop updater's download/apply contract
 * over a local HTTP fixture: feed parsing, full + resumed (Range/206)
 * downloads, sha256 mismatch refusal, verified-download reuse, the honest
 * display copy, and the consent gate around the app-image swap.
 */
@Suppress("MagicNumber") // fixture payload sizes, HTTP statuses, byte offsets
class UpdateCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var roots: DesktopStorageRoots
    private lateinit var server: HttpServer
    private var requests = 0
    private var lastRange: String? = null
    private var servedBytes = ByteArray(0)
    private var feedBody = "{}"

    private val payload: ByteArray
        get() = ByteArray(PAYLOAD_SIZE) { (it % 251).toByte() }

    private val manifest: UpdateCheck.UpdateManifest
        get() = UpdateCheck.UpdateManifest(
            version = "9.9.9",
            packageUrl = url(PACKAGE_PATH),
            size = servedBytes.size.toLong(),
            sha256 = FileDigests.sha256(tempBytesFile(servedBytes)),
        )

    private fun url(path: String): String = "http://" + server.address.hostName +
        ":" + server.address.port + path

    @Before
    fun start() {
        roots = DesktopStorageRoots(folder.newFolder())
        servedBytes = payload
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(PACKAGE_PATH) { exchange -> servePackage(exchange) }
        server.createContext(FEED_PATH) { exchange ->
            exchange.sendResponseHeaders(200, feedBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(feedBody.toByteArray()) }
        }
        server.start()
        UpdateCheck.testAllowedHttpHosts.add(server.address.hostName)
    }

    @After
    fun stop() {
        server.stop(0)
        UpdateCheck.testAllowedHttpHosts.clear()
    }

    private fun servePackage(exchange: HttpExchange) {
        requests += 1
        lastRange = exchange.getRequestHeaders().getFirst("Range")
        val full = servedBytes
        val range = lastRange
        val (code, body) = if (range == null) {
            200 to full
        } else {
            val start = range.removePrefix("bytes=").substringBefore('-').toInt()
            206 to full.copyOfRange(start, full.size)
        }
        exchange.getResponseHeaders().add("ETag", ETAG)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun tempBytesFile(bytes: ByteArray): File {
        val file = folder.newFile("sha-source-${System.nanoTime()}")
        file.writeBytes(bytes)
        return file
    }

    /** Pre-seeds an interrupted download: the first half of the payload. */
    private fun seedPartialDownload(): File {
        val dir = UpdateCheck.stagingDir(roots)
        dir.mkdirs()
        val part = File(dir, "pocketrealm-update.part")
        part.writeBytes(servedBytes.copyOfRange(0, servedBytes.size / 2))
        File(dir, "pocketrealm-update.etag").writeText(ETAG)
        return part
    }

    /** The opt-in feed pointer file under the settings root. */
    private fun writeFeedFile() {
        File(roots.settings, "update-feed.url")
            .apply { parentFile.mkdirs() }
            .writeText(url(FEED_PATH))
    }

    // -----------------------------------------------------------------
    // Feed parsing + honest copy.
    // -----------------------------------------------------------------

    @Test
    fun feedParsesDisplayOnlyAndDownloadableShapes() {
        val displayOnly = UpdateCheck.parseFeed("""{"version": "1.2.3"}""")
        assertEquals("1.2.3", displayOnly.version)
        assertEquals(false, displayOnly.downloadable)

        val downloadable = UpdateCheck.parseFeed(
            """{"version": "1.2.3", "url": "https://example.invalid/pkg.zip",
               "size": 12345, "sha256": "${"a".repeat(64)}"}""",
        )
        assertEquals(true, downloadable.downloadable)
    }

    @Test
    fun feedRejectsPartialLegsAndMalformedDigest() {
        try {
            UpdateCheck.parseFeed("""{"version": "1.2.3", "url": "https://x/y.zip"}""")
            fail("incomplete download legs must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, "sha256" in expected.message!!)
        }
        try {
            UpdateCheck.parseFeed(
                """{"version": "1.2.3", "url": "https://x/y.zip", "size": 10,
                   "sha256": "nothex"}""",
            )
            fail("malformed sha256 must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, "malformed" in expected.message!!)
        }
    }

    @Test
    fun checkWithoutFeedSaysSoHonestly() {
        val status = UpdateCheck.checkOnce(roots)
        assertTrue(status, "No update feed configured" in status)
    }

    @Test
    fun displayOnlyFeedKeepsTheNeverSelfUpdateCopy() {
        writeFeedFile()
        feedBody = """{"version": "9.9.9"}"""
        val status = UpdateCheck.checkOnce(roots)
        assertTrue(status, "Update available: 9.9.9" in status)
        assertTrue(status, "this app does not self-update" in status)
    }

    @Test
    fun downloadableFeedKeepsTheNeverSilentUnsignedCopy() {
        writeFeedFile()
        feedBody =
            """{"version": "9.9.9", "url": "https://example.invalid/pkg.zip",
               "size": 10, "sha256": "${"a".repeat(64)}"}"""
        val status = UpdateCheck.checkOnce(roots)
        assertTrue(status, "unsigned" in status.lowercase())
        assertTrue(status, "consented install" in status)
    }

    // -----------------------------------------------------------------
    // Resumable verified download.
    // -----------------------------------------------------------------

    @Test
    fun fullDownloadIsVerifiedAndReusedWithoutNetwork() {
        val first = UpdateDownload.download(roots, manifest)
        assertEquals(servedBytes.size.toLong(), first.length())
        assertEquals(1, requests)

        val second = UpdateDownload.download(roots, manifest)
        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(1, requests)
    }

    @Test
    fun downloadResumesFromThePartialWithARangeRequest() {
        val part = seedPartialDownload()
        val seededLength = part.length()
        val result = UpdateDownload.download(roots, manifest)
        assertEquals("bytes=$seededLength-", lastRange)
        assertEquals(1, requests)
        assertEquals(servedBytes.size.toLong(), result.length())
        assertEquals(
            servedBytes.toList(),
            result.readBytes().toList(),
        )
        // The sidecars are consumed by the finalize step.
        assertTrue(!part.exists())
        assertTrue(!File(UpdateCheck.stagingDir(roots), "pocketrealm-update.etag").exists())
    }

    @Test
    fun checksumMismatchRefusesAndDiscardsTheStagedBytes() {
        val wrongDigestManifest = manifest.copy(
            sha256 = "f".repeat(64),
        )
        try {
            UpdateDownload.download(roots, wrongDigestManifest)
            fail("a tampered package must be refused")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!, "checksum mismatch" in expected.message!!)
        }
        assertEquals(1, requests)
        assertNull(UpdateDownload.downloadedIfVerified(roots, wrongDigestManifest))
        assertTrue(
            !File(UpdateCheck.stagingDir(roots), "pocketrealm-update.part").exists() &&
                !File(UpdateCheck.stagingDir(roots), "pocketrealm-update").exists(),
        )
    }

    @Test
    fun nonHttpsFetchIsRefusedOutsideTheTestSeam() {
        UpdateCheck.testAllowedHttpHosts.clear()
        try {
            UpdateDownload.download(roots, manifest)
            fail("a non-https fetch must be refused")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!, "non-https" in expected.message!!)
        }
    }

    @Test
    fun clearStagedDownloadRemovesPackageAndSidecars() {
        UpdateDownload.download(roots, manifest)
        UpdateDownload.clearStagedDownload(roots)
        assertNull(UpdateDownload.downloadedIfVerified(roots, manifest))
    }

    // -----------------------------------------------------------------
    // Consented apply.
    // -----------------------------------------------------------------

    @Test
    fun applyWithoutConsentChangesNothing() {
        val image = writeCurrentImage("old exe")
        val staged = writeStagedZip()
        val outcome = UpdateApply.applyDownloadedUpdate(staged, image, consent = false)
        assertTrue(outcome is UpdateApply.ApplyOutcome.Refused)
        assertEquals("old exe", File(image, "PocketRealm.exe").readText())
        assertTrue(image.exists())
    }

    @Test
    fun applyWithoutAppImageRefusesHonestly() {
        val staged = writeStagedZip()
        val outcome = UpdateApply.applyDownloadedUpdate(staged, null, consent = true)
        assertTrue(outcome is UpdateApply.ApplyOutcome.Refused)
        assertTrue(
            (outcome as UpdateApply.ApplyOutcome.Refused).reason,
            "No jpackage app image" in outcome.reason,
        )
    }

    @Test
    fun applySwapsTheAppImageFromTheZipAndRemovesTheBackup() {
        val image = writeCurrentImage("old exe")
        val staged = writeStagedZip()
        val outcome = UpdateApply.applyDownloadedUpdate(staged, image, consent = true)
        assertTrue("$outcome", outcome is UpdateApply.ApplyOutcome.Swapped)
        assertEquals("new exe", File(image, "PocketRealm.exe").readText())
        assertTrue(
            File(image, "app/app.jar").readText() == "new jar",
        )
        assertEquals(false, File(image.parentFile, "PocketRealm.old").exists())
        // The staged package itself survives (retry / re-audit).
        assertTrue(staged.exists())
    }

    @Test
    fun applyRefusesAZipWithoutAnAppImageInside() {
        val image = writeCurrentImage("old exe")
        folder.newFolder("staged")
        val zip = folder.newFile("staged/bare.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            output.putNextEntry(ZipEntry("readme.txt"))
            output.write("not an image".toByteArray())
            output.closeEntry()
        }
        val outcome = UpdateApply.applyDownloadedUpdate(zip, image, consent = true)
        assertTrue("$outcome", outcome is UpdateApply.ApplyOutcome.Refused)
        assertEquals("old exe", File(image, "PocketRealm.exe").readText())
    }

    @Test
    fun applyRefusesAnUnknownPackageShape() {
        val image = writeCurrentImage("old exe")
        folder.newFolder("staged")
        val staged = folder.newFile("staged/pkg.msi")
        staged.writeText("not zip, not exe")
        val outcome = UpdateApply.applyDownloadedUpdate(staged, image, consent = true)
        assertTrue(outcome is UpdateApply.ApplyOutcome.Refused)
    }

    @Test
    fun installerLaunchFailureIsReportedNotSwallowed() {
        val image = writeCurrentImage("old exe")
        folder.newFolder("staged")
        val staged = folder.newFile("staged/installer.exe")
        staged.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val outcome = UpdateApply.applyDownloadedUpdate(staged, image, consent = true)
        assertTrue("$outcome", outcome is UpdateApply.ApplyOutcome.Failed)
        assertEquals("old exe", File(image, "PocketRealm.exe").readText())
    }

    /** <base>/apps/PocketRealm running-image shape (launcher + fat jar). */
    private fun writeCurrentImage(exeContent: String): File {
        val image = folder.newFolder("apps", "PocketRealm")
        File(image, "PocketRealm.exe").writeText(exeContent)
        val appDir = File(image, "app")
        appDir.mkdirs()
        File(appDir, "app.jar").writeText("old jar")
        return image
    }

    /** A staged app-image zip whose image root is <zip root>/PocketRealm/. */
    private fun writeStagedZip(): File {
        folder.newFolder("staged")
        val zip = folder.newFile("staged/pkg.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            output.putNextEntry(ZipEntry("PocketRealm/PocketRealm.exe"))
            output.write("new exe".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("PocketRealm/app/app.jar"))
            output.write("new jar".toByteArray())
            output.closeEntry()
        }
        return zip
    }

    private companion object {
        const val PACKAGE_PATH = "/pkg.bin"
        const val FEED_PATH = "/feed.json"
        const val PAYLOAD_SIZE = 200_000
        const val ETAG = "\"etag-1\""
    }
}
