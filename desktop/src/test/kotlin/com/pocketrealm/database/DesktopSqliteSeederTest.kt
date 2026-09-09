package com.pocketrealm.database

import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop seed-replay twin contract: a synthetic staging tree (real
 * digests computed over synthetic transcripts) driven through the FULL
 * Android discipline — pins, replay transaction, integrity, WAL
 * sidecars, atomic publication, generation marker — on the seam DLL.
 *
 * Requires the seam lane output (skips cleanly otherwise), but NOT the
 * proprietary corpus: the real 29 MB transcripts belong to the
 * bring-up run (gradlew seedRealmData), not the unit suite.
 */
class DesktopSqliteSeederTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun stagedDll(): File =
        File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll").also {
            assumeTrue("sqlite seam not built (run tools/build_win_sqlite_seam.py): $it", it.isFile)
        }

    /** A valid staging tree: seed/&lt;db&gt;.sqlz + BUILD_PROVENANCE.json
     * with true digests. Returns the transcript SQL per database. */
    private fun stageSynthetic(staging: File): Map<String, String> {
        // The seeder consumes the ASSETS ROOT (seedAsset already carries
        // the seed/ prefix); the staging tree writes into seed/.
        val seedDir = File(staging, "assets/seed").apply { mkdirs() }
        val transcripts = mapOf(
            "classicrealmd" to (
                "CREATE TABLE realmlist (id INTEGER PRIMARY KEY, address TEXT, port INTEGER);\n" +
                    "INSERT INTO realmlist VALUES (1, '127.0.0.1', 3724);\n"
                ),
            "classiccharacters" to (
                "CREATE TABLE characters (guid INTEGER PRIMARY KEY, name TEXT);\n" +
                    "INSERT INTO characters VALUES (1, 'First');\n" +
                    "INSERT INTO characters VALUES (2, 'Second');\n"
                ),
            "classiclogs" to "CREATE TABLE logs (id INTEGER PRIMARY KEY);\n",
            "classicmangos" to (
                "CREATE TABLE creature_template (entry INTEGER PRIMARY KEY, name TEXT);\n" +
                    "INSERT INTO creature_template VALUES (42, 'Semicolon; Creature');\n"
                ),
        )
        assertEquals(DATABASES.sorted(), transcripts.keys.sorted())
        val pins = JSONObject()
        for ((database, sql) in transcripts) {
            val raw = sql.toByteArray(StandardCharsets.UTF_8)
            val gzipped = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(raw) }
            }.toByteArray()
            File(seedDir, "$database.sqlz").writeBytes(gzipped)
            pins.put(
                database,
                JSONObject()
                    .put("sha256", sha256Hex(raw))
                    .put("gzip_sha256", sha256Hex(gzipped))
                    .put("size", raw.size.toLong())
                    .put("gzip_size", gzipped.size.toLong()),
            )
        }
        File(staging, "BUILD_PROVENANCE.json").writeText(
            JSONObject().put("seed_transcripts", pins).toString(),
        )
        return transcripts
    }

    @Test
    fun seedsAllFourWithFullDisciplineAndPublishesAtomically() {
        stagedDll()
        val staging = folder.newFolder("staging")
        val transcripts = stageSynthetic(staging)
        val datadir = folder.newFolder("datadir")
        val generation = UUID.randomUUID().toString()

        val result = DesktopSqliteSeeder(
            File(staging, "assets"),
            File(staging, "BUILD_PROVENANCE.json"),
        ).seedAll(generation, datadir)

        assertTrue(result.getBoolean("ok"))
        assertEquals("sqlite-3.46.1-in-tree", result.getString("provider"))
        val marker = JSONObject(File(datadir, ".pocketrealm-generation.json").readText())
        assertEquals(1, marker.getInt("schema"))
        assertEquals(generation, marker.getString("generationUuid"))
        for (database in DATABASES) {
            val live = File(datadir, "$database.sqlite3")
            assertTrue("$database not published", live.isFile)
            assertEquals(
                "partials must be gone for $database",
                0,
                datadir.listFiles { f -> f.name.startsWith("$database.sqlite3.") }!!.size,
            )
        }
        // Spot content checks through the seam.
        DesktopSqliteConnection(File(datadir, "classicrealmd.sqlite3").absolutePath).use { db ->
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM realmlist;"))
            assertEquals("127.0.0.1", db.queryText("SELECT address FROM realmlist WHERE id = 1;"))
        }
        DesktopSqliteConnection(File(datadir, "classiccharacters.sqlite3").absolutePath).use { db ->
            assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM characters;"))
        }
        assertEquals(3L, result.getJSONObject("databases").getJSONObject("classiccharacters").getLong("statements"))
        // "bytes" is the published FILE size (SQLite page-rounded), the
        // same accounting the Android record carries.
        assertTrue(
            result.getJSONObject("databases").getJSONObject("classiccharacters").getLong("bytes") > 0L,
        )
    }

    @Test
    fun reseedOfPopulatedDatadirRefusesLoudly() {
        stagedDll()
        val staging = folder.newFolder("staging")
        stageSynthetic(staging)
        val datadir = folder.newFolder("datadir")
        val seeder = DesktopSqliteSeeder(
            File(staging, "assets"),
            File(staging, "BUILD_PROVENANCE.json"),
        )
        seeder.seedAll(UUID.randomUUID().toString(), datadir)
        try {
            seeder.seedAll(UUID.randomUUID().toString(), datadir)
            fail("re-seed must refuse")
        } catch (expected: IllegalStateException) {
            assertTrue("unexpected message: ${expected.message}", "already" in (expected.message ?: ""))
        }
    }

    @Test
    fun transcriptDigestMismatchFailsBeforePublication() {
        stagedDll()
        val staging = folder.newFolder("staging")
        stageSynthetic(staging)
        // Same gzip size, different content: flip the provenance pin so
        // the replay's own accounting is what catches the mismatch.
        val provenance = File(staging, "BUILD_PROVENANCE.json")
        val json = JSONObject(provenance.readText())
        json.getJSONObject("seed_transcripts").getJSONObject("classiclogs")
            .put("sha256", "e".repeat(64))
        provenance.writeText(json.toString())

        val datadir = folder.newFolder("datadir")
        try {
            DesktopSqliteSeeder(File(staging, "assets"), provenance)
                .seedAll(UUID.randomUUID().toString(), datadir)
            fail("digest mismatch must fail the seed")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "unexpected message: ${expected.message}",
                "classiclogs transcript digest mismatch" in (expected.message ?: ""),
            )
        }
        assertFalse("the failing database must never publish", File(datadir, "classiclogs.sqlite3").exists())
        assertTrue(
            "earlier databases stay published (honest partial generation; seedAll refuses until the caller resets)",
            File(datadir, "classicrealmd.sqlite3").isFile,
        )
    }

    @Test
    fun failingStatementCarriesSplitterLocality() {
        stagedDll()
        val staging = folder.newFolder("staging")
        stageSynthetic(staging)
        // Replace the logs transcript with one whose SECOND statement is
        // broken; recompute its pins so the failure is the SQL itself.
        val sql = "CREATE TABLE logs (id INTEGER PRIMARY KEY);\nINSERT INTO missing_table VALUES (1);\n"
        val raw = sql.toByteArray(StandardCharsets.UTF_8)
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(raw) }
        }.toByteArray()
        File(staging, "assets/seed/classiclogs.sqlz").writeBytes(gzipped)
        val provenance = File(staging, "BUILD_PROVENANCE.json")
        val json = JSONObject(provenance.readText())
        json.getJSONObject("seed_transcripts").put(
            "classiclogs",
            JSONObject()
                .put("sha256", sha256Hex(raw))
                .put("gzip_sha256", sha256Hex(gzipped))
                .put("size", raw.size.toLong())
                .put("gzip_size", gzipped.size.toLong()),
        )
        provenance.writeText(json.toString())

        val datadir = folder.newFolder("datadir")
        try {
            DesktopSqliteSeeder(File(staging, "assets"), provenance)
                .seedAll(UUID.randomUUID().toString(), datadir)
            fail("broken SQL must fail the seed")
        } catch (expected: IllegalStateException) {
            val message = expected.message ?: ""
            assertTrue("must name the database: $message", "classiclogs" in message)
            assertTrue("must carry the statement index: $message", "statement 1" in message)
            assertTrue("must carry the offset: $message", "offset" in message)
            assertTrue("must carry sqlite's own text: $message", "missing_table" in message)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
