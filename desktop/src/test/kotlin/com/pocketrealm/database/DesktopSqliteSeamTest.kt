package com.pocketrealm.database

import com.pocketrealm.database.DatabaseSqliteControlPlane.SeedStatementScanner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import com.pocketrealm.desktop.requireNativeIfDemanded
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop SQLite execution seam contract: pocket_sqlite.dll over
 * the repo-pinned 3.46.1 amalgamation, behaving like
 * android.database.sqlite for the DatabaseSqliteControlPlane legs.
 *
 * Requires the seam lane output: run tools/build_win_sqlite_seam.py
 * first; every test SKIPS (does not fail) when the DLL is not staged,
 * so a Kotlin-only checkout stays green.
 */
class DesktopSqliteSeamTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val seamDir = File("..").resolve("native/.build-win-x86_64/sqlite-seam-build")

    private fun stagedDll(): File =
        seamDir.resolve("pocket_sqlite.dll").also {
            requireNativeIfDemanded("sqlite seam", it)
        }

    @Test
    fun versionMatchesThePinnedAmalgamation() {
        stagedDll()
        assertEquals("3.46.1", DesktopSqlite.versionNative())
    }

    @Test
    fun ddlInsertQueryRoundTripPersistsAcrossReopen() {
        stagedDll()
        val file = folder.newFile("roundtrip.sqlite3")
        assertTrue(file.delete())
        DesktopSqliteConnection(file.absolutePath).use { db ->
            db.exec("CREATE TABLE probe (id INTEGER PRIMARY KEY, name TEXT);")
            db.exec("INSERT INTO probe (id, name) VALUES (1, 'alpha');")
            db.exec("INSERT INTO probe (id, name) VALUES (2, 'omega');")
            assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM probe;"))
            assertEquals("omega", db.queryText("SELECT name FROM probe WHERE id = 2;"))
            assertNull(db.queryText("SELECT name FROM probe WHERE id = 3;"))
            assertNull(db.queryLong("SELECT id FROM probe WHERE id = 3;"))
        }
        DesktopSqliteConnection(file.absolutePath).use { db ->
            assertEquals(2L, db.queryLong("SELECT COUNT(*) FROM probe;"))
        }
    }

    @Test
    fun utf8FileEncodingMatchesTheAndroidLane() {
        stagedDll()
        val file = folder.newFile("encoding.sqlite3")
        assertTrue(file.delete())
        DesktopSqliteConnection(file.absolutePath).use { db ->
            db.exec("CREATE TABLE probe (name TEXT);")
            db.exec("INSERT INTO probe VALUES ('féé-Ω-😀');")
            assertEquals(
                "féé-Ω-😀",
                db.queryText("SELECT name FROM probe;"),
            )
        }
        // The database FILE must carry the UTF-8 encoding marker (the
        // 4-byte big-endian field at header offset 56), matching files
        // the Android lane creates — sqlite3_open16 alone would have
        // defaulted new files to UTF-16.
        val header = file.readBytes().sliceArray(56..<60)
        assertEquals(1L, header.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) })
    }

    @Test
    fun rowReturningPragmasCrossTheQuerySeam() {
        stagedDll()
        val file = folder.newFile("pragmas.sqlite3")
        assertTrue(file.delete())
        DesktopSqliteConnection(file.absolutePath).use { db ->
            assertEquals("wal", db.queryText("PRAGMA journal_mode=WAL;"))
            db.exec("CREATE TABLE probe (id INTEGER);")
            db.exec("INSERT INTO probe VALUES (7);")
            // First column of wal_checkpoint's row is the busy flag —
            // the same column the Android execPragma reads.
            assertEquals("0", db.queryText("PRAGMA wal_checkpoint(TRUNCATE);"))
        }
    }

    @Test
    fun execEnforcesTheFrameworkSqlSemantics() {
        stagedDll()
        val file = folder.newFile("exec-semantics.sqlite3")
        assertTrue(file.delete())
        DesktopSqliteConnection(file.absolutePath).use { db ->
            db.exec("CREATE TABLE probe (id INTEGER);")

            assertSeamFails(db, "CREATE TABLE t2 (x); INSERT INTO t2 VALUES (1);") {
                "exactly one statement" in it
            }
            assertSeamFails(db, "SELECT ?;") { "bind parameters" in it }
            assertSeamFails(db, "SELECT COUNT(*) FROM probe;") { "returns rows" in it }
            assertSeamFails(db, "INSERT INTO missing_table VALUES (1);") { "missing_table" in it }
            assertSeamFails(db, "") { "not a statement" in it }
        }
    }

    @Test
    @Suppress("NestedBlockDepth") // the replay loop is the contract: one pass, chunk-straddling splits
    fun seedReplayThroughTheSharedControlPlaneScanner() {
        stagedDll()
        // A synthetic transcript with the splitter's hard cases: a
        // semicolon and a quote-escaped apostrophe inside a string
        // literal, a -- comment that would swallow a naive splitter's
        // semicolon, and a statement long enough to straddle chunks.
        val transcript = buildString {
            append("CREATE TABLE seed_probe (id INTEGER PRIMARY KEY, note TEXT);\n")
            append("-- comment; with a semicolon\n")
            append("INSERT INTO seed_probe VALUES (1, 'semi;colon and it''s quoted');\n")
            append("INSERT INTO seed_probe VALUES (2, '")
            repeat(600) { append('x') }
            append("');\n")
            append("INSERT INTO seed_probe VALUES (3, 'tail');\n")
        }
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(transcript.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()

        val rawDigest = MessageDigest.getInstance("SHA-256")
        var rawBytes = 0L
        var executed = 0
        val scanner = SeedStatementScanner()
        val file = folder.newFile("seed-replay.sqlite3")
        assertTrue(file.delete())
        DesktopSqliteConnection(file.absolutePath).use { db ->
            db.exec("BEGIN;")
            InputStreamReader(GZIPInputStream(ByteArrayInputStream(gzipped)), Charsets.UTF_8).use { reader ->
                val buffer = CharArray(7) // adversarially small: statements must straddle chunks
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    val chunk = String(buffer, 0, read)
                    val encoded = chunk.toByteArray(Charsets.UTF_8)
                    rawDigest.update(encoded)
                    rawBytes += encoded.size
                    for (statement in scanner.feed(chunk)) {
                        db.exec(statement.sql)
                        executed++
                    }
                }
            }
            scanner.finish()?.let { statement ->
                db.exec(statement.sql)
                executed++
            }
            db.exec("COMMIT;")
        }

        assertEquals(4, executed)
        assertEquals(transcript.toByteArray(Charsets.UTF_8).size.toLong(), rawBytes)
        DesktopSqliteConnection(file.absolutePath).use { db ->
            assertEquals(3L, db.queryLong("SELECT COUNT(*) FROM seed_probe;"))
            assertEquals("semi;colon and it's quoted", db.queryText("SELECT note FROM seed_probe WHERE id = 1;"))
            assertEquals(600, db.queryText("SELECT note FROM seed_probe WHERE id = 2;")!!.length)
        }
    }

    @Test
    fun statementFailuresCarrySqlitesOwnMessage() {
        stagedDll()
        val file = folder.newFile("errors.sqlite3")
        assertTrue(file.delete())
        DesktopSqliteConnection(file.absolutePath).use { db ->
            db.exec("CREATE TABLE probe (id INTEGER NOT NULL);")
            try {
                db.exec("INSERT INTO probe VALUES (NULL);")
                fail("expected the NOT NULL constraint to raise")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "message should carry sqlite's own text: ${expected.message}",
                    "NOT NULL" in (expected.message ?: ""),
                )
                assertTrue(
                    "message should carry the result code: ${expected.message}",
                    (expected.message ?: "").contains("rc="),
                )
            }
        }
    }

    private fun assertSeamFails(db: DesktopSqliteConnection, sql: String, predicate: (String) -> Boolean) {
        try {
            db.exec(sql)
            fail("expected exec to reject: ${sql.take(60)}")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "unexpected rejection message: ${expected.message}",
                predicate(expected.message ?: ""),
            )
        }
    }
}
