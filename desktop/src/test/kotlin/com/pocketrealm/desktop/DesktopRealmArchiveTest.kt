package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.supervisor.UserAccountStore
import com.pocketrealm.ui.RealmDataArchive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * the realm data archive lane over temp-root seeded
 * datadirs — the SHARED RealmDataArchive codec driven by the app model:
 * export = named backup of the stopped datadir + single-file ZIP into the
 * exports root (credentials only on explicit opt-in), inspect = manifest
 * read without extraction, import = digest-verified extract + the
 * transactional restore + the paired credentials write. Archives are
 * byte-compatible with the Android lane (same codec, same manifest shape).
 * The restore legs need the sqlite seam DLL (the integrity gate) and skip
 * cleanly without it.
 */
class DesktopRealmArchiveTest {
    /** flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val seamDll = File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll")

    private fun assumeSeamStaged() = requireNativeIfDemanded("sqlite seam", seamDll)

    private fun seedDatadir(roots: DesktopStorageRoots, sentinel: String) {
        roots.sqliteDatadir.mkdirs()
        DATABASES.forEach { database ->
            DesktopSqliteConnection(
                DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).absolutePath,
            ).use { connection ->
                connection.queryText("PRAGMA journal_mode=WAL;")
                connection.exec("CREATE TABLE realm_note(value TEXT);")
                connection.exec("INSERT INTO realm_note VALUES('$sentinel');")
            }
        }
    }

    /** The dev lane's roots with the SQLITE provider SELECTED: the shipped
     * default flipped to MariaDB, and this class pins the sqlite
     * datadir's archive lane — the export's named backup and the import's
     * restore are provider-aware now, so an explicit selection keeps these
     * legs on the lane they pin regardless of the default. */
    private fun sqliteRoots(name: String): DesktopStorageRoots {
        val roots = DesktopStorageRoots(folder.newFolder(name))
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
 // Flip: the lane selection rides every settings rewrite.
                selectedFlavorId = "vanilla",
            ).toJson(),
        )
        return roots
    }

    private fun readSentinel(roots: DesktopStorageRoots): String =
        DesktopSqliteConnection(
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd").absolutePath,
        ).use { connection -> requireNotNull(connection.queryText("SELECT value FROM realm_note;")) }

    private fun entryNames(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) names.add(entry.name)
            }
        }
        return names
    }

    @Test
    fun exportInspectImportRoundTripMovesCharactersBetweenInstalls() {
        assumeSeamStaged()
        val sourceRoots = sqliteRoots("source")
        seedDatadir(sourceRoots, "exported-characters")
        // The source install's stored credentials (the paired account).
        UserAccountStore(File(sourceRoots.root, "user-account"))
            .save("Exporter", "passw0rdA", accountId = 7)

        DesktopAppModel(sourceRoots).use { source ->
            // Default export: NO credentials entry, and the file lands in
            // the exports root — the only user-facing output root.
            val plain = runBlocking { source.exportRealmArchive(includeAccount = false) }
            assertTrue("the export zip must exist: ${plain.file}", plain.file.isFile)
            assertTrue(
                "the export must land in the exports root: ${plain.file}",
                plain.file.absolutePath.startsWith(sourceRoots.exports.absolutePath),
            )
            assertFalse(
                "credentials must not ride a default export",
                entryNames(plain.file).contains("account.json"),
            )

            // Opt-in export: the credentials entry rides verbatim. (A short
            // sleep keeps the two backup ids — name + millis — distinct.)
            Thread.sleep(5)
            val withAccount = runBlocking { source.exportRealmArchive(includeAccount = true) }
            assertTrue(
                "the opt-in export must carry the credentials entry",
                entryNames(withAccount.file).contains("account.json"),
            )

            // Inspect reads only the manifest.
            val info = source.inspectRealmArchive(withAccount.file)
            assertEquals(RealmDataArchive.KIND, info?.kind)
            assertEquals(withAccount.snapshotId, info?.snapshotId)

            // Import on a SECOND install: digest-verified extract, the
            // transactional restore (proof passed), then the credentials.
            val destRoots = sqliteRoots("dest")
            seedDatadir(destRoots, "local-characters")
            DesktopAppModel(destRoots).use { dest ->
                assertEquals("local-characters", readSentinel(destRoots))
                val operation = runBlocking { dest.importRealmArchive(withAccount.file) { null } }
                assertTrue("import must restore: ${operation.detail}", operation.ok)
                assertTrue(
                    "the import must disclose the replaced login: ${operation.detail}",
                    "Saved login replaced from the archive" in operation.detail,
                )
                assertEquals(
                    "the archive's snapshot bytes must be live on the second install",
                    "exported-characters",
                    readSentinel(destRoots),
                )
                val imported = dest.storedAccount.value
                assertEquals("Exporter", imported?.username)
                assertEquals("passw0rdA", imported?.password)
            }
        }
    }

    @Test
    fun importRefusesWhenTheSnapshotAlreadyExists() {
        assumeSeamStaged()
        val sourceRoots = sqliteRoots("src-exists")
        seedDatadir(sourceRoots, "source-bytes")
        DesktopAppModel(sourceRoots).use { source ->
            val export = runBlocking { source.exportRealmArchive(includeAccount = false) }
            val destRoots = sqliteRoots("dst-exists")
            seedDatadir(destRoots, "dest-bytes")
            DesktopAppModel(destRoots).use { dest ->
                // The same snapshot id already published on this install.
                File(destRoots.databaseSnapshots, export.snapshotId).mkdirs()
                val operation = runBlocking { dest.importRealmArchive(export.file) { null } }
                assertFalse(operation.ok)
                assertTrue(
                    "the refusal must name the collision: ${operation.detail}",
                    "already exists" in operation.detail,
                )
                assertEquals("the live datadir must be untouched", "dest-bytes", readSentinel(destRoots))
            }
        }
    }

    @Test
    fun importRefusesAnArchiveWithoutThePocketRealmManifest() {
        assumeSeamStaged()
        val destRoots = sqliteRoots("dst-junk")
        seedDatadir(destRoots, "dest-bytes")
        val junk = folder.newFile("junk.zip")
        ZipOutputStream(junk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("other.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        DesktopAppModel(destRoots).use { dest ->
            val operation = runBlocking { dest.importRealmArchive(junk) { null } }
            assertFalse(operation.ok)
            assertTrue(
                "the refusal must be the unreadable-archive verdict: ${operation.detail}",
                "Could not read the selected archive" in operation.detail,
            )
            assertEquals("dest-bytes", readSentinel(destRoots))
        }
    }

    @Test
    fun importRefusesAnArchiveWhoseSnapshotIdEscapesTheSnapshotsRoot() {
        assumeSeamStaged()
        val sourceRoots = sqliteRoots("src-trav")
        seedDatadir(sourceRoots, "source-bytes")
        DesktopAppModel(sourceRoots).use { source ->
            val export = runBlocking { source.exportRealmArchive(includeAccount = false) }
            // Same digest-valid snapshot payload, manifest rewritten with a
            // traversal id: the id names the extraction root, so the import
            // must refuse it BEFORE any directory is created — never mkdirs/
            // extract outside the snapshots tree, and never rely on the
            // store's own (restore-time, post-write) validId refusal.
            val hostile = folder.newFile("hostile.zip")
            ZipOutputStream(hostile.outputStream()).use { out ->
                ZipInputStream(export.file.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val bytes = if (entry.name == "realm-archive.json") {
                            val meta = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            meta.put("snapshotId", "..\\..\\escaped")
                            meta.toString().toByteArray(Charsets.UTF_8)
                        } else {
                            zip.readBytes()
                        }
                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(bytes)
                        out.closeEntry()
                    }
                }
            }
            val destRoots = sqliteRoots("dst-trav")
            DesktopAppModel(destRoots).use { dest ->
                val operation = runBlocking { dest.importRealmArchive(hostile) { null } }
                assertFalse(operation.ok)
                assertTrue(
                    "the refusal must be the invalid-id verdict: ${operation.detail}",
                    "invalid snapshot id" in operation.detail,
                )
                val escaped = File(destRoots.databaseSnapshots, "..\\..\\escaped")
                assertFalse("nothing may be created outside the snapshots root: $escaped", escaped.exists())
                assertTrue(
                    "nothing may be extracted into the snapshots root either",
                    destRoots.databaseSnapshots.listFiles().isNullOrEmpty(),
                )
            }
        }
    }

    @Test
    fun importCleansUpTheExtractedSnapshotWhenTheRestoreRefuses() {
        assumeSeamStaged()
        val sourceRoots = sqliteRoots("src-refuse")
        seedDatadir(sourceRoots, "source-bytes")
        DesktopAppModel(sourceRoots).use { source ->
            val export = runBlocking { source.exportRealmArchive(includeAccount = false) }
            val destRoots = sqliteRoots("dst-refuse")
            seedDatadir(destRoots, "dest-bytes")
            DesktopAppModel(destRoots).use { dest ->
                // A digest-valid extract whose restore never proves
                // world-ready: the rolled-back import must not leave the
                // extracted snapshot published in the snapshots root.
                val operation = runBlocking { dest.importRealmArchive(export.file) { "proof failed" } }
                assertFalse(operation.ok)
                assertFalse(
                    "a refused restore must clean up the extracted snapshot",
                    File(destRoots.databaseSnapshots, export.snapshotId).exists(),
                )
                assertEquals("the live datadir must be untouched", "dest-bytes", readSentinel(destRoots))
            }
        }
    }

    /** Re-emit [archive] with the account entry's bytes replaced — the
     * hostile-archive builder for the invalid-record leg (the escape-id
     * test's rewrite pattern, split shallow for detekt). */
    private fun rewriteAccountEntry(archive: File, replacement: ByteArray): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val out = ZipOutputStream(output)
        ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) {
                    writeRewrittenEntry(out, zip, entry.name, replacement)
                }
            }
        }
        out.finish()
        return output.toByteArray()
    }

    private fun writeRewrittenEntry(
        out: ZipOutputStream,
        zip: ZipInputStream,
        name: String,
        replacement: ByteArray,
    ) {
        val bytes = if (name == "account.json") replacement else zip.readBytes()
        out.putNextEntry(ZipEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    /** Round-3: the archive's account entry is user-picked bytes — the
     * import must run them through the store's own decode
     * (RealmDataArchive.isValidAccountRecord → UserAccountStore.decodeRecord)
     * BEFORE they may replace the saved auto-login record, and an invalid
     * record rejects ONLY that leg: the database restore stays, nothing is
     * written under user-account/, and the operation detail says so. */
    @Test
    fun importKeepsTheDatabaseAndRejectsAnInvalidAccountRecord() {
        assumeSeamStaged()
        val sourceRoots = sqliteRoots("src-badacct")
        seedDatadir(sourceRoots, "source-bytes")
        DesktopAppModel(sourceRoots).use { source ->
            UserAccountStore(File(sourceRoots.root, "user-account"))
                .save("Exporter", "passw0rdA", accountId = 7)
            val export = runBlocking { source.exportRealmArchive(includeAccount = true) }
            // Same archive with the account entry rewritten to bytes that
            // are not a store record (every snapshot entry stays
            // byte-identical, digest included).
            val hostile = folder.newFile("hostile-account.zip")
            hostile.writeBytes(rewriteAccountEntry(export.file, NOT_A_RECORD.toByteArray()))
            val destRoots = sqliteRoots("dst-badacct")
            seedDatadir(destRoots, "dest-bytes")
            DesktopAppModel(destRoots).use { dest ->
                val operation = runBlocking { dest.importRealmArchive(hostile) { null } }
                assertTrue("the database import must stay: ${operation.detail}", operation.ok)
                assertTrue(
                    "the detail must disclose the rejected account leg: ${operation.detail}",
                    "saved login was invalid" in operation.detail,
                )
                assertEquals(
                    "the archive's snapshot bytes must be live on the second install",
                    "source-bytes",
                    readSentinel(destRoots),
                )
                assertTrue("no credentials may be written", dest.storedAccount.value == null)
                assertFalse(
                    "no record file may be written",
                    File(destRoots.root, "user-account/account.json").isFile,
                )
            }
        }
    }

    /** Round-4: the credentials write's own IO (the raw FileOutputStream +
     * Files.move under user-account/) can fail AFTER the character restore
     * has committed — the committed import must still report ok, with the
     * failure disclosed in the note, never throw past the model as an
     * "Import failed" for characters that were in fact restored. A regular
     * FILE squatting on the user-account directory name fails the temp
     * write's FileOutputStream leg while the record itself stays valid. */
    @Test
    fun importDisclosesAnUnwritableAccountLegWithoutFailingTheCommittedRestore() {
        assumeSeamStaged()
        val sourceRoots = sqliteRoots("src-acct-io")
        seedDatadir(sourceRoots, "source-bytes")
        DesktopAppModel(sourceRoots).use { source ->
            UserAccountStore(File(sourceRoots.root, "user-account"))
                .save("Exporter", "passw0rdA", accountId = 7)
            val export = runBlocking { source.exportRealmArchive(includeAccount = true) }
            val destRoots = sqliteRoots("dst-acct-io")
            seedDatadir(destRoots, "dest-bytes")
            // A regular file where the user-account directory would go: the
            // record decodes fine, but the temp FileOutputStream cannot be
            // created under a file — the write leg's IO-failure shape.
            File(destRoots.root, "user-account").writeText("not a directory")
            DesktopAppModel(destRoots).use { dest ->
                val operation = runBlocking { dest.importRealmArchive(export.file) { null } }
                assertTrue("the committed restore must stay ok: ${operation.detail}", operation.ok)
                assertTrue(
                    "the detail must disclose the failed credentials write: ${operation.detail}",
                    "saved login could not be written" in operation.detail,
                )
                assertEquals(
                    "the archive's snapshot bytes must be live on the second install",
                    "source-bytes",
                    readSentinel(destRoots),
                )
                assertTrue("no credentials may have landed", dest.storedAccount.value == null)
            }
        }
    }

    private companion object {
        const val NOT_A_RECORD = "definitely not a user-account record"
    }
}
