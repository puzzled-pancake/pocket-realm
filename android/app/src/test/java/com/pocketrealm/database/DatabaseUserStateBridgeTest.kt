package com.pocketrealm.database

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-pure legs of the user-state bridge (codec + query
 * shaping). The SQLite-touching import legs are validated on device
 * against the host harness tests/test_sqlite_user_state_bridge.py,
 * which proves the same contract byte-for-byte.
 */
class DatabaseUserStateBridgeTest {

    @Test fun codecRoundTripsEveryEscapeClass() {
        val values: List<ByteArray?> = listOf(
            "plain".toByteArray(),
            "tab\there".toByteArray(),
            "new\nline".toByteArray(),
            "carriage\rreturn".toByteArray(),
            "back\\slash".toByteArray(),
            "nul\u0000byte".toByteArray(),
            "mix\t\\\n\r\u0000end".toByteArray(),
            "multi-byte ã — 中文".toByteArray(),
            "astral mail 😀! emoji 🎉".toByteArray(), // astral surrogate pairs
            ByteArray(0),                    // empty string
            null,                            // NULL
            "\\N".toByteArray(),             // the literal two-char string
            "NULL".toByteArray(),            // never a NULL marker
        )
        for (v in values) {
            val field = DatabaseUserStateBridge.encodeField(v)
            val back = DatabaseUserStateBridge.decodeField(field)
            if (v == null) assertNull(back) else assertArrayEquals(v, back)
        }
        // The emitter's domain is VALID UTF-8 (TEXT contract; blobs
        // travel as HEX fields) - arbitrary bytes fail LOUD, never
        // silently mojibake through a replacement-coded decode.
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.encodeField(byteArrayOf(0x80.toByte()))
        }
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.encodeField(ByteArray(256) { it.toByte() })
        }
    }

    @Test fun decodeFollowsTheMysqldumpConvention() {
        assertNull(DatabaseUserStateBridge.decodeField("\\N"))
        assertArrayEquals(
            "a\tb".toByteArray(),
            DatabaseUserStateBridge.decodeField("a\\tb"),
        )
        assertArrayEquals(
            "a\\b".toByteArray(),
            DatabaseUserStateBridge.decodeField("a\\\\b"),
        )
        // an UNescaped backslash before a non-escape char stays literal
        assertArrayEquals(
            "a\\qb".toByteArray(),
            DatabaseUserStateBridge.decodeField("a\\qb"),
        )
    }

    @Test fun recordStructureRoundTrips() {
        val rows = listOf(
            listOf("1".toByteArray(), null, "x\ty".toByteArray()),
            listOf(null, "\\N".toByteArray(), ByteArray(0)),
        )
        val text = rows.joinToString("\n") { row ->
            row.joinToString("\t") { DatabaseUserStateBridge.encodeField(it) }
        } + "\n"
        val decoded = DatabaseUserStateBridge.decodeTsv(text)
        // ByteArray identity equality is meaningless - compare content
        assertEquals(rows.size, decoded.size)
        for (i in rows.indices) {
            assertEquals(rows[i].size, decoded[i].size)
            for (j in rows[i].indices) {
                val expected = rows[i][j]
                val actual = decoded[i][j]
                if (expected == null) assertNull(actual)
                else assertArrayEquals(expected, actual!!)
            }
        }
    }

    @Test fun encodeTsvMatchesTheHostEmitter() {
        val rows = listOf(
            listOf("1".toByteArray(), null, "x".toByteArray()),
            listOf(null, "\\N".toByteArray(), ByteArray(0)),
            listOf("tab\there".toByteArray(), "nl\nhere".toByteArray(), "cr\rhere".toByteArray()),
        )
        val expected =
            "1\t\\N\tx\n" +                      // 1 <TAB> NULL <TAB> x
            "\\N\t\\\\N\t\n" +                   // NULL <TAB> literal \N <TAB> empty
            "tab\\there\tnl\\nhere\tcr\\rhere\n"
        assertEquals(expected, DatabaseUserStateBridge.encodeTsv(rows))
        // and it is decodeTsv's exact inverse (content-wise: ByteArray
        // identity equality is meaningless)
        val decoded = DatabaseUserStateBridge.decodeTsv(DatabaseUserStateBridge.encodeTsv(rows))
        assertEquals(rows.size, decoded.size)
        for (i in rows.indices) {
            for (j in rows[i].indices) {
                if (rows[i][j] == null) assertNull(decoded[i][j])
                else assertArrayEquals(rows[i][j]!!, decoded[i][j]!!)
            }
        }
    }

    @Test fun realmdSliceMatchesTheRecordedSliceDecision() {
        assertEquals(
            listOf("account", "account_banned", "ip_banned", "realmcharacters",
                "system_fingerprint_usage"),
            DatabaseUserStateBridge.REALMD_USER_STATE_TABLES,
        )
    }

    @Test fun outfileExportQueryIsExactlyTheMysqldumpTabMechanism() {
        // SHARED PARITY FIXTURE: this test and the host
        // twin's test_outfile_export_query_is_the_shipped_wire both
        // compare against the SAME checked-in canonical statement bytes
        // (tests/p5_outfile_wire_fixture.txt), so the two runtimes can
        // never silently diverge on the export wire again.
        val columns = listOf(
            "guid" to "INT",
            "name" to "VARCHAR",
            "data" to "LONGBLOB",
            "flag" to "BIT",
        )
        val q = DatabaseUserStateBridge.outfileExportQuery(
            "classiccharacters", "account_data", columns,
            "/data/root/import/page.tsv",
            primaryKey = listOf("guid", "name"), offset = 1000, limit = 500,
        )
        val fixture = resolveFixture()
        assertEquals(fixture, q)
        // no primary key -> ORDER BY ALL columns (four slice tables have
        // no PK; undefined LIMIT/OFFSET order silently drops/duplicates)
        val q2 = DatabaseUserStateBridge.outfileExportQuery(
            "classiccharacters", "account_data", columns, "/p.tsv", offset = 0,
        )
        // the no-PK form is SECOND-fixture-pinned (both suites compare
        // the same bytes)
        assertEquals(resolveFixture("p5_outfile_wire_fixture_nopk.txt"), q2)
        org.junit.Assert.assertTrue("ORDER BY `guid`, `name`, `data`, `flag`" in q2)
    }

    private fun resolveFixture(name: String = "p5_outfile_wire_fixture.txt"): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = java.io.File(dir, "tests/$name")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("$name not found from ${System.getProperty("user.dir")}")
    }

    @Test fun decodeTsvBytesIsTheStrictStagedFileEntry() {
        // byte-level entry: no String boundary in front of the strict
        // UTF-8 gate; an interior empty line is one empty-string
        // row (single-column table convention); an unterminated
        // trailing row fails loud
        val rows = listOf(
            listOf("1".toByteArray(), null),
            listOf("tab\there".toByteArray(), "astral 😀".toByteArray()),
            listOf("".toByteArray()),
        )
        val decoded = DatabaseUserStateBridge.decodeTsvBytes(
            DatabaseUserStateBridge.encodeTsv(rows).toByteArray(Charsets.UTF_8),
        )
        assertEquals(rows.size, decoded.size)
        assertArrayEquals(rows[0][0], decoded[0][0]); assertNull(decoded[0][1])
        assertArrayEquals(rows[1][0], decoded[1][0])
        assertArrayEquals(rows[1][1], decoded[1][1])  // astral pair survives
        assertArrayEquals(ByteArray(0), decoded[2][0])
        // invalid UTF-8 in a field refuses loud (never U+FFFD)
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.decodeTsvBytes(byteArrayOf(0x31, 0x09, 0xFF.toByte(), 0x0A))
        }
        // an unterminated trailing row is interrupted staging, not data
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.decodeTsvBytes(byteArrayOf(0x31, 0x0A, 0x33))
        }
    }

    @Test fun blobFamilyExportsAsHexByTypeNotJustByTheTwoKnownNames() {
        // the append path rule: every blob-family DATA_TYPE HEX-wraps
        for (type in listOf("BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "VARBINARY", "BINARY")) {
            org.junit.Assert.assertTrue(
                DatabaseUserStateBridge.isExportBlobColumn("future_table", "payload", type),
            )
        }
        org.junit.Assert.assertFalse(
            DatabaseUserStateBridge.isExportBlobColumn("future_table", "payload", "VARCHAR"),
        )
        // the two known blob columns are pinned by name as defense in depth
        org.junit.Assert.assertTrue(
            DatabaseUserStateBridge.isExportBlobColumn("account_data", "data", "SOMETHINGELSE"),
        )
        // target side: the translator normalizes the family to BLOB
        org.junit.Assert.assertTrue(
            DatabaseUserStateBridge.isTargetBlobColumn("account_data", "data", "BLOB"),
        )
        org.junit.Assert.assertFalse(
            DatabaseUserStateBridge.isTargetBlobColumn("account_data", "other", "TEXT"),
        )
    }

    @Test fun countQueryIsTheSourceTruthCrossCheck() {
        assertEquals("SELECT COUNT(*) FROM `characters`;", DatabaseUserStateBridge.countQuery("characters"))
    }

    @Test fun importSqlIsUserWinsOverSeed() {
        val columns = listOf(
            DatabaseUserStateBridge.Importer.TargetColumn("guid", "INTEGER", true),
            DatabaseUserStateBridge.Importer.TargetColumn("name", "TEXT", false),
        )
        assertEquals(
            "INSERT OR REPLACE INTO \"characters\" (\"guid\", \"name\") VALUES (?, ?)",
            DatabaseUserStateBridge.Importer.importSql("characters", columns),
        )
    }

    @Test fun rowParamsDecodesHexBlobsAndNulls() {
        val columns = listOf(
            DatabaseUserStateBridge.Importer.TargetColumn("account", "INTEGER", true),
            DatabaseUserStateBridge.Importer.TargetColumn("data", "BLOB", false),
        )
        val params = DatabaseUserStateBridge.Importer.rowParams(
            "account_data", columns,
            listOf("42".toByteArray(), "00FF10".toByteArray()),
        )
        assertEquals("42", params[0])
        assertArrayEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x10), params[1] as ByteArray)

        val nulls = DatabaseUserStateBridge.Importer.rowParams(
            "account_data", columns, listOf("42".toByteArray(), null),
        )
        assertNull(nulls[1])
    }

    @Test fun rowParamsFailsLoudOnEveryMaskedCorruptionClass() {
        val notNull = listOf(
            DatabaseUserStateBridge.Importer.TargetColumn("guid", "INTEGER", true),
            DatabaseUserStateBridge.Importer.TargetColumn("name", "TEXT", true),
        )
        // NULL for a NOT NULL target (INSERT OR REPLACE would silently
        // store the DEFAULT)
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.Importer.rowParams("characters", notNull, listOf(null, "x".toByteArray()))
        }
        // ragged row (interrupted export)
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.Importer.rowParams("characters", notNull, listOf("1".toByteArray()))
        }
        // blob column that did not arrive as HEX text
        val blobCols = listOf(
            DatabaseUserStateBridge.Importer.TargetColumn("id", "INTEGER", true),
            DatabaseUserStateBridge.Importer.TargetColumn("data", "BLOB", false),
        )
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.Importer.rowParams(
                "account_data", blobCols, listOf("1".toByteArray(), "nothex!".toByteArray()),
            )
        }
        // invalid UTF-8 in a text column (strict decode, never U+FFFD)
        org.junit.Assert.assertThrows(BridgeError::class.java) {
            DatabaseUserStateBridge.Importer.rowParams(
                "characters", notNull,
                listOf("1".toByteArray(), byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            )
        }
    }

    @Test fun partialForNamesTheAtomicCommitPoint() {
        val live = java.io.File(java.io.File("/data/root"), "classiccharacters.sqlite")
        assertEquals(
            java.io.File(java.io.File("/data/root"), "classiccharacters.sqlite.partial"),
            DatabaseUserStateBridge.Importer.partialFor(live),
        )
    }
}
