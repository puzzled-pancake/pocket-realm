package com.pocketrealm.ui

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest

class RealmDataArchiveTest {
    private fun snapshotDir(root: java.io.File): java.io.File {
        val dir = root.resolve("snapshot").apply { mkdirs() }
        dir.resolve("data").mkdirs()
        dir.resolve("data/ib_logfile0").writeBytes(byteArrayOf(1, 2, 3))
        dir.resolve("secrets.json").writeBytes("""{"secret":true}""".toByteArray())
        val manifest = JSONObject().put("schema", 2).put("snapshotId", "manual-export-1")
        dir.resolve("manifest.json").writeBytes(manifest.toString().toByteArray())
        val digest = MessageDigest.getInstance("SHA-256").digest(manifest.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        dir.resolve("manifest.sha256").writeText("$digest\n")
        return dir
    }

    @Test fun roundTripsSnapshotAndAccount() {
        val root = Files.createTempDirectory("realm-archive").toFile()
        try {
            val snapshot = snapshotDir(root)
            val account = root.resolve("account.json").apply { writeBytes("""{"account":"david"}""".toByteArray()) }
            val bytes = ByteArrayOutputStream().use { output ->
                RealmDataArchive.writeArchive(
                    output, snapshot, account,
                    RealmDataArchive.meta("manual-export-1", 123L, "0.1.0", "arm64-v8a"),
                )
                output.toByteArray()
            }

            val info = RealmDataArchive.inspect(ByteArrayInputStream(bytes))
            assertEquals(RealmDataArchive.KIND, info.kind)
            assertEquals("manual-export-1", info.snapshotId)
            assertEquals(123L, info.createdAtMs)
            assertEquals("0.1.0", info.appVersionName)

            val target = root.resolve("restored").apply { mkdirs() }
            val (parsed, accountBytes) = RealmDataArchive.extractSnapshot(ByteArrayInputStream(bytes), target)
            assertEquals("manual-export-1", parsed.snapshotId)
            assertArrayEquals("""{"account":"david"}""".toByteArray(), accountBytes)
            assertTrue(target.resolve("data/ib_logfile0").length() == 3L)
            assertTrue(target.resolve("manifest.json").isFile)
            assertEquals(
                target.resolve("manifest.sha256").readText().trim(),
                MessageDigest.getInstance("SHA-256").digest(target.resolve("manifest.json").readBytes())
                    .joinToString("") { "%02x".format(it) },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsArchivesWithoutPocketRealmManifest() {
        val root = Files.createTempDirectory("realm-archive-bad").toFile()
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                val zip = java.util.zip.ZipOutputStream(output)
                zip.putNextEntry(java.util.zip.ZipEntry("other.txt"))
                zip.write("hello".toByteArray())
                zip.closeEntry()
                zip.finish()
                output.toByteArray()
            }
            assertThrows(IllegalArgumentException::class.java) {
                RealmDataArchive.inspect(ByteArrayInputStream(bytes))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun tamperedSnapshotDigestFailsClosed() {
        val root = Files.createTempDirectory("realm-archive-tamper").toFile()
        try {
            val snapshot = snapshotDir(root)
            // Corrupt the manifest content after the digest was published so
            // the archive carries bytes that no longer match manifest.sha256.
            snapshot.resolve("manifest.json").writeBytes(
                snapshot.resolve("manifest.json").readBytes() + byteArrayOf(' '.code.toByte()),
            )
            val bytes = ByteArrayOutputStream().use { output ->
                RealmDataArchive.writeArchive(
                    output, snapshot, null,
                    RealmDataArchive.meta("manual-export-1", 1L, "0.1.0", "arm64-v8a"),
                )
                output.toByteArray()
            }
            val target = root.resolve("restored").apply { mkdirs() }
            assertThrows(IllegalStateException::class.java) {
                RealmDataArchive.extractSnapshot(ByteArrayInputStream(bytes), target)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
