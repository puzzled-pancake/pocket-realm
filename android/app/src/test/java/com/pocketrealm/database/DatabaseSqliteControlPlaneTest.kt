package com.pocketrealm.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6/G8: the SQLite control plane's pure shapes. The SQLite-touching
 * execution legs (SQLiteDatabase against real datadirs) are the
 * registered P7 on-device validation; these tests pin the statement
 * surface the engine executes.
 */
class DatabaseSqliteControlPlaneTest {

    @Test fun ledgerDdlIsSqliteNativeAndShapePreserving() {
        val ddl = DatabaseSqliteControlPlane.LEDGER_DDL
        // G8: the ENUM becomes a CHECK over the exact MariaDB value set
        assertTrue(ddl.contains("CHECK (status IN ('PENDING','APPLIED','ROLLED_BACK','FAILED'))"))
        assertEquals(DatabaseSqliteControlPlane.LEDGER_STATUSES,
            setOf("PENDING", "APPLIED", "ROLLED_BACK", "FAILED"))
        // the InnoDB/ENUM machinery is gone
        assertFalse(ddl.contains("ENGINE=InnoDB"))
        assertFalse(ddl.contains("ENUM("))
        // the identity/provenance columns the refuse-on-mismatch contract
        // compares survive the translation
        for (column in listOf(
            "migration_id TEXT NOT NULL PRIMARY KEY", "component TEXT NOT NULL",
            "source_commit TEXT NOT NULL", "target_commit TEXT NOT NULL",
            "sql_sha256 TEXT NOT NULL", "started_at INTEGER NOT NULL",
            "finished_at INTEGER", "status TEXT NOT NULL", "pre_snapshot_id TEXT NOT NULL",
            "app_build_id TEXT NOT NULL", "result_digest TEXT",
        )) {
            assertTrue(ddl.contains(column))
        }
    }

    @Test fun revisionProbeUsesPragmaTableInfoWithTwoBinds() {
        // G8: replaces the information_schema.COLUMNS probe — the
        // refuse-on-mismatch contract keeps its mandatory negative test
        // by probing a deliberately wrong column and requiring zero.
        assertEquals(
            "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?;",
            DatabaseSqliteControlPlane.REVISION_PROBE,
        )
        assertEquals(
            arrayOf("db_version", "required_z9999_not_real"),
            DatabaseSqliteControlPlane.revisionProbeBinds(
                "db_version", "required_z9999_not_real",
            ),
        )
    }

    @Test fun integrityGatesAreTheDocumentedPair() {
        assertEquals("PRAGMA quick_check;", DatabaseSqliteControlPlane.QUICK_CHECK)
        assertEquals("PRAGMA integrity_check;", DatabaseSqliteControlPlane.INTEGRITY_CHECK)
        assertEquals("ok", DatabaseSqliteControlPlane.INTEGRITY_OK)
        assertEquals("VACUUM INTO ?;", DatabaseSqliteControlPlane.VACUUM_INTO)
    }

    @Test fun seedAssetsAreSqlzPerTheAgpGunzipGotcha() {
        // gotcha #16: AGP's asset merge auto-gunzips *.gz — the seed
        // ships as .sqlz exactly like the MariaDB migrations
        assertEquals("seed/classicmangos.sqlz", DatabaseSqliteControlPlane.seedAsset("classicmangos"))
        assertEquals(
            listOf("classicrealmd", "classiccharacters", "classiclogs", "classicmangos"),
            DatabaseSqliteControlPlane.DATABASES,
        )
        // none of the asset names may end .gz
        DatabaseSqliteControlPlane.DATABASES.forEach { database ->
            assertFalse(DatabaseSqliteControlPlane.seedAsset(database).endsWith(".gz"))
        }
    }

    // ------------------------------------------------------------------
    // P6(b): the seed-replay statement scanner (the I-56 inheritance).
    // ------------------------------------------------------------------

    @Test fun splitterSplitsOnSemicolonsAndPreservesLiterals() {
        val transcript = """
            CREATE TABLE a (x TEXT);
            INSERT INTO a VALUES ('semi; colon');
            INSERT INTO a VALUES ('it''s');
            INSERT INTO a VALUES ("dq;string");
            INSERT INTO a VALUES (`back;tick`);
        """.trimIndent()
        val statements = DatabaseSqliteControlPlane.splitSeedStatements(transcript)
        assertEquals(5, statements.size)
        assertEquals("CREATE TABLE a (x TEXT)", statements[0].sql)
        assertEquals("INSERT INTO a VALUES ('semi; colon')", statements[1].sql)
        assertEquals("INSERT INTO a VALUES ('it''s')", statements[2].sql)
        assertEquals("INSERT INTO a VALUES (\"dq;string\")", statements[3].sql)
        assertEquals("INSERT INTO a VALUES (`back;tick`)", statements[4].sql)
        // I-56 diagnostics: sequential index + monotonically increasing offsets
        assertEquals(0, statements[0].index)
        assertEquals(4, statements[4].index)
        assertTrue(statements.zipWithNext().all { (a, b) -> b.offset > a.offset })
    }

    @Test fun splitterHandlesNewlinesInsideStatementsAndTheNoSemicolonTail() {
        // the P4 corpus style: multi-row INSERTs one row per line, joined
        // with ";\n", NO trailing semicolon (gotcha #15)
        val transcript = "INSERT INTO t VALUES\n(1, 'a'),\n(2, 'b');\nINSERT INTO t VALUES (3, 'c')"
        val statements = DatabaseSqliteControlPlane.splitSeedStatements(transcript)
        assertEquals(2, statements.size)
        assertTrue(statements[0].sql.contains("(1, 'a'),"))
        assertTrue(statements[0].sql.contains("(2, 'b')"))
        assertEquals("INSERT INTO t VALUES (3, 'c')", statements[1].sql)
    }

    @Test fun scannerChunkFeedMatchesWholeStringSplit() {
        // the engine path feeds 64 KiB char chunks; chunk boundaries must
        // not change the split — including boundaries INSIDE literals and
        // mid comment marker
        val transcript = buildString {
            repeat(200) {
                append("INSERT INTO t$it VALUES ('text; with $\u00e9, ''q''', 42);\n")
            }
            append("-- a; comment\n")
            append("/* block; comment */ INSERT INTO final VALUES (1);\n")
            // R1 C2: quote/backtick immediately after a chunk-tail '-'/'/'
            // must OPEN a literal, never be swallowed as ordinary text
            append("INSERT INTO q1 VALUES (5 -'a;b');\n")
            append("INSERT INTO q2 VALUES (6 /\"c;d\");\n")
            append("INSERT INTO q3 VALUES (7 -`e;f`);\n")
            append("INSERT INTO q4 VALUES (8 /'g;h');\n")
            append("INSERT INTO tail VALUES ('unterminated literal tail')")
        }
        val whole = DatabaseSqliteControlPlane.splitSeedStatements(transcript)
        for (chunkSize in listOf(1, 7, 64, 1000, transcript.length)) {
            val scanner = DatabaseSqliteControlPlane.SeedStatementScanner()
            val chunked = mutableListOf<DatabaseSqliteControlPlane.SeedStatement>()
            var position = 0
            while (position < transcript.length) {
                val end = minOf(position + chunkSize, transcript.length)
                chunked.addAll(scanner.feed(transcript.substring(position, end)))
                position = end
            }
            scanner.finish()?.let { chunked.add(it) }
            assertEquals("chunkSize=$chunkSize", whole, chunked)
        }
        // the comment classes never split; the adjacency literals each
        // carry their ';' inside quotes and must not split either
        assertEquals(206, whole.size)
        assertTrue(whole[201].sql.endsWith("(5 -'a;b')"))
        assertTrue(whole[202].sql.endsWith("(6 /\"c;d\")"))
        assertTrue(whole[203].sql.endsWith("(7 -`e;f`)"))
        assertTrue(whole[204].sql.endsWith("(8 /'g;h')"))
    }

    @Test fun scannerRefusesATranscriptEndingMidCommentMarker() {
        val scanner = DatabaseSqliteControlPlane.SeedStatementScanner()
        // a complete "--" at end-of-transcript is a legal unterminated
        // line comment; the refusal is the PENDING single marker
        scanner.feed("INSERT INTO a VALUES (1); -- a comment")
        scanner.finish() // completes cleanly
        val pending = DatabaseSqliteControlPlane.SeedStatementScanner()
        pending.feed("INSERT INTO a VALUES (1); -")
        assertThrows<IllegalStateException> { pending.finish() }
    }

    @Test fun scannerTreatsPendingMarkerAsOrdinaryTextWhenNotCompleted() {
        // '-' followed by a non-'-' char is subtraction/ordinary text
        val statements = DatabaseSqliteControlPlane.splitSeedStatements(
            "INSERT INTO a VALUES (5 - 3); INSERT INTO b VALUES ('/not/comment')",
        )
        assertEquals(2, statements.size)
        assertEquals("INSERT INTO a VALUES (5 - 3)", statements[0].sql)
    }

    // ------------------------------------------------------------------
    // P6(b): the datadir layout + the ledger insert shape.
    // ------------------------------------------------------------------

    @Test fun sqliteDatadirLayoutIsNamespaced() {
        assertEquals("sqlite-datadir", DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)
        assertEquals("pocketrealm_meta", DatabaseSqliteControlPlane.META_DATABASE)
        val datadir = java.io.File("/data/root", DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)
        assertEquals(
            java.io.File(datadir, "classicmangos.sqlite3"),
            DatabaseSqliteControlPlane.databaseFile(datadir, "classicmangos"),
        )
        // the drain proof names every database's -wal/-shm sidecar pair
        val sidecars = DatabaseSqliteControlPlane.walSidecars(datadir)
        assertEquals((DatabaseSqliteControlPlane.DATABASES.size + 1) * 2, sidecars.size)
        assertTrue(sidecars.all { it.name.endsWith("-wal") || it.name.endsWith("-shm") })
    }

    @Test fun ledgerAppliedInsertHasTheExactShape() {
        assertEquals(
            "INSERT INTO migration_ledger " +
                "(migration_id,component,source_commit,target_commit,sql_sha256,started_at,finished_at,status,pre_snapshot_id,app_build_id) " +
                "VALUES (?,?,?,?,?,?,?,'APPLIED',?,?)",
            DatabaseSqliteControlPlane.LEDGER_APPLIED_INSERT,
        )
        assertEquals(
            "SELECT name, type, \"notnull\" FROM pragma_table_info(?) ORDER BY cid;",
            DatabaseSqliteControlPlane.TABLE_COLUMNS_PROBE,
        )
        assertEquals(
            arrayOf("characters"),
            DatabaseSqliteControlPlane.tableColumnsProbeBinds("characters"),
        )
    }
}

private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
    } catch (expected: Throwable) {
        check(expected is T) { "expected ${T::class.simpleName} but got ${expected}" }
        return
    }
    throw AssertionError("expected ${T::class.simpleName}")
}
