package com.pocketrealm.ui

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            val accountPayload = """{"account":"traveler-01"}"""
            val account = root.resolve("account.json").apply { writeBytes(accountPayload.toByteArray()) }
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
            assertArrayEquals(accountPayload.toByteArray(), accountBytes)
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

    /** The manifest-supplied snapshot id names the extraction root in both
     * import lanes (android SettingsScreen / desktop DesktopAppModel), so the
     * codec's id gate must carry the snapshot store's validId grammar
     * exactly: traversal ids ("../x", "..\..\escaped"), dot-names (".", "..",
     * ".hidden"), separators, blanks and over-length ids are refused BEFORE
     * the caller mkdirs the target — the per-entry zip-slip gate cannot see
     * a root that itself moved out of the snapshots tree. */
    @Test fun snapshotIdGateMirrorsTheSnapshotStoreValidIdRule() {
        listOf(
            "", ".", "..", ".hidden", "../x", "..\\..\\escaped", "a/b", "a\\b",
            "sp ace", "snapshot\u00e9", "x".repeat(97),
        ).forEach { id ->
            assertFalse("the id must be refused: <$id>", RealmDataArchive.validSnapshotId(id))
        }
        listOf("manual-export-1", "a..b", "snap_2024.v2-x", "x".repeat(96)).forEach { id ->
            assertTrue("a store-legal id must pass: <$id>", RealmDataArchive.validSnapshotId(id))
        }
    }

    /** Zip-slip (Windows): a '\' in an entry name is a path separator on
     * Windows hosts, so "snapshot/..\..\zipslip-evil.exe" hides its traversal
     * from a '/'-only component scan and would extract outside the root. The
     * gate rejects '\' outright on every platform — legit exports only ever
     * carry invariantSeparatorsPath names. */
    @Test fun rejectsBackslashTraversalSnapshotEntries() {
        val root = Files.createTempDirectory("realm-archive-zipslip").toFile()
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                val zip = java.util.zip.ZipOutputStream(output)
                zip.putNextEntry(java.util.zip.ZipEntry("realm-archive.json"))
                zip.write(RealmDataArchive.meta("zipslip-1", 1L, "0.1.0", "x86_64").toString().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("snapshot/..\\..\\zipslip-evil.exe"))
                zip.write("payload".toByteArray())
                zip.closeEntry()
                zip.finish()
                output.toByteArray()
            }
            val target = root.resolve("restored").apply { mkdirs() }
            val thrown = assertThrows(IllegalStateException::class.java) {
                RealmDataArchive.extractSnapshot(ByteArrayInputStream(bytes), target)
            }
            assertTrue("the refusal must name the unsafe path: ${thrown.message}", "unsafe archive path" in thrown.message!!)
            // Where the unchecked entry would have landed: two directories
            // above the extraction root (the host temp directory).
            val escapee = target.parentFile?.parentFile?.resolve("zipslip-evil.exe")
            assertTrue("nothing may be written outside the extraction root", escapee?.exists() != true)
            assertTrue("the extraction root must stay empty", target.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** Containment leg: "snapshot/." passes the component scan (no '\', no
     * "..", not empty) but canonicalizes to the extraction root itself, so
     * the canonical containment check must refuse it before any write. */
    @Test fun rejectsSnapshotEntriesThatResolveToTheExtractionRoot() {
        val root = Files.createTempDirectory("realm-archive-dot").toFile()
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                val zip = java.util.zip.ZipOutputStream(output)
                zip.putNextEntry(java.util.zip.ZipEntry("realm-archive.json"))
                zip.write(RealmDataArchive.meta("zipslip-2", 1L, "0.1.0", "x86_64").toString().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("snapshot/."))
                zip.write("payload".toByteArray())
                zip.closeEntry()
                zip.finish()
                output.toByteArray()
            }
            val target = root.resolve("restored").apply { mkdirs() }
            val thrown = assertThrows(IllegalStateException::class.java) {
                RealmDataArchive.extractSnapshot(ByteArrayInputStream(bytes), target)
            }
            assertTrue(
                "the refusal must name the escaped root: ${thrown.message}",
                "escaped the extraction root" in thrown.message!!,
            )
            assertTrue("the extraction root must stay empty", target.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** Windows exotic-name leg: a drive-lettered entry passes the component
     * scan, and java.io.File two-arg construction APPENDS the drive-qualified
     * child (it does not resolve to the drive root), leaving an invalid
     * in-root path. The import must still fail closed with nothing written. */
    @Test fun exoticDriveLetterSnapshotEntriesFailClosedOnWindowsHosts() {
        org.junit.Assume.assumeTrue(java.io.File.separatorChar == '\\')
        val root = Files.createTempDirectory("realm-archive-drive").toFile()
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                val zip = java.util.zip.ZipOutputStream(output)
                zip.putNextEntry(java.util.zip.ZipEntry("realm-archive.json"))
                zip.write(RealmDataArchive.meta("zipslip-3", 1L, "0.1.0", "x86_64").toString().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("snapshot/C:/zipslip-evil.exe"))
                zip.write("payload".toByteArray())
                zip.closeEntry()
                zip.finish()
                output.toByteArray()
            }
            val target = root.resolve("restored").apply { mkdirs() }
            val outcome = runCatching { RealmDataArchive.extractSnapshot(ByteArrayInputStream(bytes), target) }
            assertTrue("the exotic entry must fail closed", outcome.isFailure)
            assertTrue("the extraction root must stay empty", target.listFiles().isNullOrEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    /** Re-emit [archive] with the account entry's bytes replaced — the
     * hostile-archive builder for the cap and validation legs (the desktop
     * lane's rewrite pattern). */
    private fun rewriteAccountEntry(archive: ByteArray, replacement: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val zip = java.util.zip.ZipOutputStream(output)
        val input = java.util.zip.ZipInputStream(ByteArrayInputStream(archive))
        input.use { stream ->
            generateSequence { stream.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) {
                    writeRewrittenEntry(zip, stream, entry.name, replacement)
                }
            }
        }
        zip.finish()
        return output.toByteArray()
    }

    private fun writeRewrittenEntry(
        zip: java.util.zip.ZipOutputStream,
        stream: java.util.zip.ZipInputStream,
        name: String,
        replacement: ByteArray,
    ) {
        val bytes = if (name == "account.json") replacement else stream.readBytes()
        zip.putNextEntry(java.util.zip.ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** The meta and account entries are the only entries read INTO MEMORY,
     * so a hostile user-picked archive must never size that allocation:
     * the codec caps both reads at the provisioning frame's 4 KiB
     * discipline (FriendProvisionProtocol.MAX_REQUEST_BYTES) and refuses
     * oversize entries like every other gate. Snapshot entries stay
     * streamed and uncapped. */
    @Test fun refusesOversizeMetaAndAccountEntries() {
        val root = Files.createTempDirectory("realm-archive-cap").toFile()
        try {
            val oversize = ByteArray(4_096 + 1) { 'x'.code.toByte() }
            val hostileMeta = ByteArrayOutputStream().use { output ->
                val zip = java.util.zip.ZipOutputStream(output)
                zip.putNextEntry(java.util.zip.ZipEntry("realm-archive.json"))
                zip.write(oversize)
                zip.closeEntry()
                zip.finish()
                output.toByteArray()
            }
            val metaThrown = assertThrows(IllegalStateException::class.java) {
                RealmDataArchive.inspect(ByteArrayInputStream(hostileMeta))
            }
            assertTrue(
                "the refusal must name the cap: ${metaThrown.message}",
                "exceeds 4096" in metaThrown.message!!,
            )

            // Same treatment for the account entry: a digest-valid snapshot
            // archive whose account entry is blown past the cap refuses the
            // extract (the callers' catch removes the partial tree).
            val snapshot = snapshotDir(root)
            val account = root.resolve("account.json").apply { writeBytes(oversize) }
            val valid = ByteArrayOutputStream().use { output ->
                RealmDataArchive.writeArchive(
                    output, snapshot, account,
                    RealmDataArchive.meta("cap-1", 1L, "0.1.0", "x86_64"),
                )
                output.toByteArray()
            }
            val target = root.resolve("capped").apply { mkdirs() }
            val accountThrown = assertThrows(IllegalStateException::class.java) {
                RealmDataArchive.extractSnapshot(
                    ByteArrayInputStream(rewriteAccountEntry(valid, oversize)), target,
                )
            }
            assertTrue(
                "the refusal must name the cap: ${accountThrown.message}",
                "exceeds 4096" in accountThrown.message!!,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /** The cap refuses one byte PAST it, never AT it: a legit entry sized
     * exactly at the cap still round-trips (the +1 probe distinguishes
     * "at the cap" from "over it"). */
    @Test fun accountEntryAtTheCapStillImports() {
        val root = Files.createTempDirectory("realm-archive-atcap").toFile()
        try {
            val snapshot = snapshotDir(root)
            val atCap = ByteArray(4_096) { 'y'.code.toByte() }
            val account = root.resolve("account.json").apply { writeBytes(atCap) }
            val bytes = ByteArrayOutputStream().use { output ->
                RealmDataArchive.writeArchive(
                    output, snapshot, account,
                    RealmDataArchive.meta("cap-2", 1L, "0.1.0", "x86_64"),
                )
                output.toByteArray()
            }
            val target = root.resolve("restored").apply { mkdirs() }
            val (_, accountBytes) = RealmDataArchive.extractSnapshot(ByteArrayInputStream(bytes), target)
            assertArrayEquals(atCap, accountBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    /** The account entry gates the import lanes' credentials write through
     * the store's own decode (UserAccountStore.decodeRecord): only bytes
     * that decode as a real record may replace a device's saved auto-login
     * account — schema, credential grammar, id, privilege and flavor legs
     * all refuse, and unparseable bytes refuse. */
    @Test fun accountEntryValidationMirrorsTheStoreDecode() {
        assertTrue(
            "a real record must pass",
            RealmDataArchive.isValidAccountRecord(
                """{"schema":2,"username":"david","password":"passw0rd","accountId":7,"gmLevel":0}"""
                    .toByteArray(),
            ),
        )
        listOf(
            "",
            "definitely not a user-account record",
            """{"schema":1,"username":"david","password":"passw0rd","accountId":7}""",
            """{"schema":2,"username":"not allowed!","password":"passw0rd","accountId":7}""",
            """{"schema":2,"username":"david","password":"passw0rd","accountId":0}""",
            """{"schema":2,"username":"david","password":"passw0rd","accountId":7,"gmLevel":9}""",
            """{"schema":2,"username":"david","password":"passw0rd","accountId":7,"flavor":"no-such-flavor"}""",
        ).forEach { hostile ->
            assertFalse(
                "an invalid record must be refused: <$hostile>",
                RealmDataArchive.isValidAccountRecord(hostile.toByteArray()),
            )
        }
    }
}
