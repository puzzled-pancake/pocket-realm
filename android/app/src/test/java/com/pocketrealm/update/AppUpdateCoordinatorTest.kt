package com.pocketrealm.update

import com.pocketrealm.BuildConfig
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class AppUpdateCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun releasesBody(manifestUrl: HttpUrl): String =
        """
        {
          "tag_name": "v9.9.9",
          "assets": [
            { "name": "update-manifest.json",
              "browser_download_url": "$manifestUrl" }
          ]
        }
        """.trimIndent()

    private fun manifestJson(url: HttpUrl, size: Long, sha256: String, versionCode: Int = 99): String =
        """
        {
          "versionCode": $versionCode,
          "versionName": "9.9.9",
          "apkUrl": "$url",
          "size": $size,
          "sha256": "$sha256",
          "minSupportedVersionCode": 1,
          "notes": "test notes"
        }
        """.trimIndent()

    private fun coordinator(server: MockWebServer): AppUpdateCoordinator {
        val coordinator = AppUpdateCoordinator
        coordinator.extraAllowedHosts.clear()
        coordinator.extraAllowedHosts += server.hostName
        return coordinator
    }

    @Test
    fun checkResolvesManifestAndReportsAvailable() {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val apkUrl = server.url("/apk")
            val apkBytes = byteArrayOf(1, 2, 3, 4)
            server.enqueue(
                MockResponse.Builder().code(200).body(releasesBody(server.url("/manifest"))).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body(manifestJson(apkUrl, apkBytes.size.toLong(), sha256(apkBytes))).build(),
            )
            val result = coordinator.check(server.url("/releases/latest").toString())
            assertTrue(result is AppUpdateCoordinator.CheckResult.Available)
            val manifest = (result as AppUpdateCoordinator.CheckResult.Available).manifest
            assertEquals(99, manifest.versionCode)
            assertEquals("9.9.9", manifest.versionName)
            assertEquals(apkBytes.size.toLong(), manifest.size)
            assertEquals("test notes", manifest.notes)
            assertEquals(1, manifest.minSupportedVersionCode)
        }
    }

    @Test
    fun checkReportsUpToDateForOlderRelease() {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val apkUrl = server.url("/apk")
            server.enqueue(
                MockResponse.Builder().code(200).body(releasesBody(server.url("/manifest"))).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body(manifestJson(apkUrl, 4, "0".repeat(64), versionCode = BuildConfig.VERSION_CODE))
                    .build(),
            )
            assertEquals(
                AppUpdateCoordinator.CheckResult.UpToDate,
                coordinator.check(server.url("/releases/latest").toString()),
            )
        }
    }

    @Test
    fun checkReportsUnavailableForGitHubErrorPayload() {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            server.enqueue(
                MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build(),
            )
            val result = coordinator.check(server.url("/releases/latest").toString())
            assertTrue(result is AppUpdateCoordinator.CheckResult.Unavailable)
        }
    }

    @Test
    fun downloadVerifiesChecksumAndProducesTheFile() {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val apkBytes = byteArrayOf(9, 8, 7, 6, 5, 4)
            val manifest = AppUpdateCoordinator.UpdateManifest(
                versionCode = 99,
                versionName = "9.9.9",
                apkUrl = server.url("/apk").toString(),
                size = apkBytes.size.toLong(),
                sha256 = sha256(apkBytes),
                minSupportedVersionCode = 1,
                notes = "",
            )
            server.enqueue(
                MockResponse.Builder().code(200)
                    .headers(
                        okhttp3.Headers.Builder().add("ETag", "\"v1\"").build(),
                    )
                    .body(okio.Buffer().write(apkBytes)).build(),
            )
            val cache = tmp.newFolder()
            val file = ApkDownloader(setOf(server.hostName))
                .download(File(cache, "update-download"), manifest) { }
            assertEquals("update-download", file.nameWithoutExtension)
            assertTrue(file.readBytes().contentEquals(apkBytes))
            // No partials left behind on success.
            assertTrue(File(cache, "update-download.part").isNullSafeAbsent())
        }
    }

    @Test
    fun downloadRejectsChecksumMismatchAndDiscardsThePartial() {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val manifest = AppUpdateCoordinator.UpdateManifest(
                versionCode = 99,
                versionName = "9.9.9",
                apkUrl = server.url("/apk").toString(),
                size = 4,
                sha256 = "f".repeat(64),
                minSupportedVersionCode = 1,
                notes = "",
            )
            server.enqueue(
                MockResponse.Builder().code(200)
                    .body(okio.Buffer().write(byteArrayOf(1, 2, 3, 4))).build(),
            )
            val cache = tmp.newFolder()
            try {
                ApkDownloader(setOf(server.hostName))
                    .download(File(cache, "update-download"), manifest) { }
                throw AssertionError("checksum mismatch must throw")
            } catch (expected: IllegalStateException) {
                // download failures use error() per the repo's detekt style.
                assertTrue(expected.message!!.contains("checksum"))
            }
            assertTrue(File(cache, "update-download.part").isNullSafeAbsent())
            assertTrue(File(cache, "update-download").isNullSafeAbsent())
        }
    }

    @Test
    fun downloadAppendsOnRangeResume() {
        MockWebServer().use { server ->
            server.start()
            val coordinator = coordinator(server)
            val body = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val tail = body.copyOfRange(4, body.size)
            val manifest = AppUpdateCoordinator.UpdateManifest(
                versionCode = 99,
                versionName = "9.9.9",
                apkUrl = server.url("/apk").toString(),
                size = body.size.toLong(),
                sha256 = sha256(body),
                minSupportedVersionCode = 1,
                notes = "",
            )
            // Simulate a prior interrupted download: half the file present.
            val cache = tmp.newFolder()
            File(cache, "update-download.part").writeBytes(body.copyOfRange(0, 4))
            File(cache, "update-download.etag").writeText("\"v1\"")
            server.enqueue(
                MockResponse.Builder().code(206)
                    .addHeader("Content-Range", "bytes 4-${body.size - 1}/${body.size}")
                    .body(okio.Buffer().write(tail)).build(),
            )
            val file = ApkDownloader(setOf(server.hostName))
                .download(File(cache, "update-download"), manifest) { }
            // The resume must APPEND: full artifact present and verified.
            assertTrue(file.readBytes().contentEquals(body))
        }
    }

    private fun File.isNullSafeAbsent(): Boolean = !exists()
}
