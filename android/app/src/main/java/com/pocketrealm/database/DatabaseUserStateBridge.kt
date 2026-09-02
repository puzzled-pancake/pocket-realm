package com.pocketrealm.database

import java.io.File

/** P5/G7 user-state bridge failure: every raise is a loud refusal, never
 * a silent substitution (the masked-default class the host harness
 * proved SQLite would otherwise make). */
class BridgeError(message: String) : Exception(message)

/**
 * P5/G7 user-state bridge (MariaDB datadir -> SQLite provider), Kotlin
 * side of tools/sqlite_user_state_bridge.py.
 *
 * The dual-provider window's first boot translates the sealed MariaDB
 * datadir: the OLD provider must still boot to export (F13/F44 - the
 * fail-closed seals make the window mandatory), the export runs through
 * the shipped MariaDB client in batch-TSV mode, and the import lands in
 * the freshly seeded SQLite datadirs with user state winning over seed
 * rows. This object holds the JVM-pure core (codec + query shaping);
 * the Android-framework legs ( SQLiteDatabase import, the
 * .partial/os.replace commit point) live in [Importer] and are
 * validated on device (P7) against the host harness
 * (tests/test_sqlite_user_state_bridge.py).
 *
 * WIRE CONTRACT (must match the client invocation and the host tests):
 * the mysqldump --tab convention - fields tab-separated, records
 * newline-terminated, backslash escapes for tab/newline/CR/backslash/
 * NUL inside fields, NULL as the two-char "\N". A literal backslash-N
 * text value arrives escaped as "\\N" and decodes back to itself, so
 * the NULL marker is unambiguous.
 */
object DatabaseUserStateBridge {

    /** The exporter prelude: pin the session timezone so every
     * TIMESTAMP read converts to UTC (the P3 tz footnote's
     * normalization, by construction). DATETIME columns are tz-agnostic
     * literals — they cross as stored (device-local wall clock); the
     * recorded residual lives in the plan ledger (P5 R1, I-84). */
    const val UTC_PIN: String = "SET time_zone = '+00:00'"

    /** The two longblob columns (characters.sql:61/:156) that corrupt
     * without HEX encoding on export (F44). */
    private val BLOB_COLUMNS: Map<String, Set<String>> = mapOf(
        "account_data" to setOf("data"),
        "character_account_data" to setOf("data"),
    )

    /** HEX text exactly: ASCII hex digits, even length (possibly empty). */
    private val HEX_FIELD = Regex("^(?:[0-9A-Fa-f]{2})*$")

    /** A strict UTF-8 decoder (REPORT on malformed/unmappable) - shared
     * by the emitter and the import coercer. JVM String(bytes, UTF_8)
     * substitutes U+FFFD silently; both call sites must fail loud. */
    internal fun strictUtf8Decoder(): java.nio.charset.CharsetDecoder =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

    /** The classicrealmd user-state slice (the classiccharacters slice is
     * derived from the source schema at export time: every table except
     * the regenerable ai_playerbot_* tables). Mirrors
     * USER_STATE_TABLES in tools/sqlite_user_state_bridge.py. */
    val REALMD_USER_STATE_TABLES: List<String> = listOf(
        "account", "account_banned", "ip_banned", "realmcharacters",
        "system_fingerprint_usage",
    )

    /** One decoded TSV field. Returns null for the NULL marker. */
    fun decodeField(field: String): ByteArray? {
        if (field == "\\N") return null
        val out = ByteArrayBuilder()
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && i + 1 < field.length) {
                when (val next = field[i + 1]) {
                    't' -> { out.append(0x09); i += 2 }
                    'n' -> { out.append(0x0A); i += 2 }
                    'r' -> { out.append(0x0D); i += 2 }
                    '0' -> { out.append(0x00); i += 2 }
                    '\\' -> { out.append(0x5C); i += 2 }
                    else -> { out.appendUtf16(c); i += 1 }
                }
            } else {
                out.appendUtf16(c)
                i += 1
            }
        }
        return out.finish()
    }

    /** The inverse of [decodeField] (client-convention emitter).
     * The value must be VALID UTF-8: TEXT columns carry UTF-8 by
     * contract, and blob columns travel as HEX text fields (the host
     * reverse round-trip leg's convention) - an arbitrary-byte value
     * reaching this emitter is a bug, so it fails LOUD rather than
     * silently storing U+FFFD mojibake (the host's surrogateescape path
     * exists to keep Python str lossless; this port refuses instead).
     * Escaping happens in the STRING, keeping multi-byte sequences
     * intact. */
    fun encodeField(value: ByteArray?): String {
        if (value == null) return "\\N"
        val text = try {
            strictUtf8Decoder().decode(java.nio.ByteBuffer.wrap(value)).toString()
        } catch (failure: Throwable) {
            throw BridgeError(
                "user-state value is not valid UTF-8 for TSV emission: " +
                    value.copyOf(minOf(value.size, 40)).contentToString(),
            )
        }
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\u0000' -> sb.append("\\0")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Full batch-TSV text -> rows of fields (never-null bytes). */
    fun decodeTsv(text: String): List<List<ByteArray?>> {
        if (text.isEmpty()) return emptyList()
        val rows = ArrayList<List<ByteArray?>>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            rows.add(line.split('\t').map { decodeField(it) })
        }
        return rows
    }

    /** Rows of value bytes -> batch-TSV text (host encode_tsv parity;
     * the emitter behind the reverse round-trip proof). */
    fun encodeTsv(rows: List<List<ByteArray?>>): String =
        rows.joinToString("") { row ->
            row.joinToString("\t") { encodeField(it) } + "\n"
        }

    /**
     * Staged-file entry (P5 R2, C2-2): decode batch-TSV BYTES directly,
     * so no unpinned String boundary sits in front of the strict-UTF-8
     * gate (a lenient String(bytes, UTF_8) would turn invalid bytes
     * into U+FFFD *before* [Importer] could refuse them). Escaped
     * fields never contain raw 0x09/0x0A bytes (the server escapes
     * them), so byte-level splitting is exact. An unterminated trailing
     * row fails loud; an interior empty line is a legal single-column
     * empty-string row (preserved — the true LOAD DATA convention).
     */
    fun decodeTsvBytes(bytes: ByteArray): List<List<ByteArray?>> {
        if (bytes.isEmpty()) return emptyList()
        val rows = ArrayList<List<ByteArray?>>()
        var row = ArrayList<ByteArray?>()
        var fieldStart = 0
        for (index in bytes.indices) {
            if (bytes[index] == 0x09.toByte()) {
                row.add(decodeFieldSlice(bytes, fieldStart, index))
                fieldStart = index + 1
            } else if (bytes[index] == 0x0A.toByte()) {
                row.add(decodeFieldSlice(bytes, fieldStart, index))
                rows.add(row)
                row = ArrayList()
                fieldStart = index + 1
            }
        }
        if (fieldStart != bytes.size || row.isNotEmpty()) {
            throw BridgeError("unterminated trailing TSV row - interrupted staging?")
        }
        return rows
    }

    private fun decodeFieldSlice(bytes: ByteArray, start: Int, end: Int): ByteArray? {
        val slice = bytes.copyOfRange(start, end)
        val text = try {
            strictUtf8Decoder().decode(java.nio.ByteBuffer.wrap(slice)).toString()
        } catch (failure: Throwable) {
            throw BridgeError("TSV field is not valid UTF-8: ${slice.contentToString()}")
        }
        return decodeField(text)
    }

    // ------------------------------------------------------------------
    // Export wire (P5 orchestrator leg) — SELECT ... INTO OUTFILE
    // ------------------------------------------------------------------
    //
    // WIRE CONTRACT (recorded in the plan ledger): the host core's
    // contract is the mysqldump --tab convention, and INTO OUTFILE is
    // literally that mechanism — mysqldump --tab generates exactly this
    // statement, and the query spells the field/line clauses EXACTLY so
    // the wire is sql_mode-independent (under NO_BACKSLASH_ESCAPES the
    // server would otherwise emit unescaped fields and NULL as the
    // literal word "NULL", which the codec stores as text). The server's
    // default escape set is \\ \t \n \0 inside fields + the two-char \N
    // for NULL (a raw \r crosses UNescaped — the codec decodes both
    // forms, so values round-trip either way). No client-stdout boundary
    // exists in the data path. (The earlier --batch --raw +
    // query-side-REPLACE-chain design was withdrawn in P5 R1: the JNI
    // runner captures only the last 16 KiB of child stdout — I-77.)
    // Outfiles land under secure-file-priv (databaseRoot/import) and
    // require the FILE privilege: pages run as pocket_admin, like the
    // migration runner. A zero-row page produces an EMPTY file.

    /** Source DATA_TYPE values that export as HEX text (F44 blob
     * family; the name map below pins the two known longblobs as
     * defense in depth — verified the only blob columns in the slice,
     * but the append path gets the family rule, not the two names). */
    private val EXPORT_BLOB_TYPES: Set<String> = setOf(
        "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "VARBINARY", "BINARY",
    )

    /** True when the column exports as HEX text (source side). */
    fun isExportBlobColumn(table: String, name: String, sourceDataType: String): Boolean =
        name in (BLOB_COLUMNS[table] ?: emptySet()) ||
            sourceDataType.substringBefore(' ').uppercase() in EXPORT_BLOB_TYPES

    /** True when the column imports as bytes from HEX text (target
     * side: the P4 translator normalizes the whole blob family to
     * exactly "BLOB"). */
    fun isTargetBlobColumn(table: String, name: String, declaredType: String): Boolean =
        name in (BLOB_COLUMNS[table] ?: emptySet()) ||
            declaredType.substringBefore(' ').uppercase() == "BLOB"

    /** The per-page export statement: UTC-pinned session + charset pin,
     * sql_mode pin (mysqldump precedent; read-only session — makes the
     * backslash-escape literals parse identically regardless of the
     * server's global mode, P5 R3 I-107), HEX blobs, bit columns
     * normalized to 0/1 text (CAST AS UNSIGNED — a raw bit would cross
     * as a control byte), deterministic order (PRIMARY KEY when
     * present, otherwise ALL columns — four slice tables have no PK,
     * and undefined LIMIT/OFFSET order can silently duplicate/drop rows
     * at page boundaries, P5 R2), LIMIT/OFFSET pagination, the
     * mysqldump --tab field/line clauses spelled EXACTLY as SQL escape
     * sequences, and a QUALIFIED db.table (the shipped page invocation
     * passes no --database — I-E1R3). */
    fun outfileExportQuery(
        database: String,
        table: String,
        columns: List<Pair<String, String>>,
        outFile: String,
        primaryKey: List<String> = emptyList(),
        offset: Int = 0,
        limit: Int = 500,
    ): String {
        val cols = columns.joinToString(", ") { (name, type) ->
            val base = type.substringBefore(' ').uppercase()
            when {
                isExportBlobColumn(table, name, type) -> "HEX(`$name`)"
                base == "BIT" -> "CAST(`$name` AS UNSIGNED)"
                else -> "`$name`"
            }
        }
        val orderColumns = if (primaryKey.isEmpty()) columns.map { it.first } else primaryKey
        val order = " ORDER BY " + orderColumns.joinToString(", ") { "`$it`" }
        return "$UTC_PIN;\nSET NAMES utf8mb4;\nSET SESSION sql_mode='';\n" +
            "SELECT $cols FROM `$database`.`$table`$order " +
            "LIMIT $limit OFFSET $offset INTO OUTFILE '$outFile' " +
            "FIELDS TERMINATED BY '\\t' ENCLOSED BY '' ESCAPED BY '\\\\' " +
            "LINES TERMINATED BY '\\n';\n"
    }

    /** The source-truth row count used to cross-check every staged
    table (catches any page-loss class independent of transport).
    INVOCATION CONTRACT: the FROM is UNqualified (fixture-consistent
    tables are qualified) — the caller MUST pass the database to the
    client invocation (the engine's count leg does; the page queries
    are qualified and need none — P5 R4, E idea 1). */
    fun countQuery(table: String): String = "SELECT COUNT(*) FROM `$table`;"

    /**
     * The import-side scaffolding (host import_table/import_database
     * parity). JVM-pure row validation and statement shaping; the
     * SQLiteDatabase execution that consumes them is the Android
     * framework leg validated on device (P7) against the host
     * harness (tests/test_sqlite_user_state_bridge.py).
     */
    object Importer {

        /** One target column from PRAGMA table_info (host table_columns
         * parity: name, declared type, NOT NULL flag). */
        data class TargetColumn(val name: String, val declaredType: String, val notNull: Boolean)

        /** The user-wins-over-seed statement (host parity). */
        fun importSql(table: String, columns: List<TargetColumn>): String =
            "INSERT OR REPLACE INTO \"$table\" (" +
                columns.joinToString(", ") { "\"${it.name}\"" } + ") VALUES (" +
                columns.joinToString(", ") { "?" } + ")"

        /**
         * One decoded TSV row -> bind parameters, with every per-row
         * loud-refusal gate: ragged rows (interrupted export), NULL for
         * a NOT NULL target (SQLite's INSERT OR REPLACE would silently
         * store the column DEFAULT - empirically verified on the host),
         * blob columns that did not arrive as HEX text, and non-UTF-8
         * text-column bytes.
         */
        fun rowParams(table: String, columns: List<TargetColumn>, row: List<ByteArray?>): List<Any?> {
            if (row.size != columns.size) {
                throw BridgeError(
                    "$table: ragged TSV row (${row.size} fields vs ${columns.size} columns)" +
                        " - interrupted export?",
                )
            }
            return row.zip(columns).map { (value, column) -> param(table, column, value) }
        }

        private fun param(table: String, column: TargetColumn, value: ByteArray?): Any? {
            if (value == null) {
                if (column.notNull) {
                    throw BridgeError(
                        "$table.${column.name}: NULL for a NOT NULL target column " +
                            "(INSERT OR REPLACE would silently store the DEFAULT - " +
                            "source/export anomaly)",
                    )
                }
                return null
            }
            if (isTargetBlobColumn(table, column.name, column.declaredType)) {
                val text = value.toString(Charsets.UTF_8)
                if (HEX_FIELD.matches(text).not()) {
                    throw BridgeError(
                        "$table.${column.name}: blob column did not arrive as HEX text",
                    )
                }
                return ByteArray(text.length / 2) { index ->
                    text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }
            // STRICT decode: String(bytes, UTF_8) substitutes U+FFFD
            // silently - the host core fails loud here and so must this
            // port (the I-51 mojibake class).
            return try {
                strictUtf8Decoder().decode(java.nio.ByteBuffer.wrap(value)).toString()
            } catch (failure: Throwable) {
                throw BridgeError(
                    "$table.${column.name}: user-state value is not valid UTF-8 " +
                        "for a ${column.declaredType.ifEmpty { "TEXT" }} column",
                )
            }
        }

        /**
         * The atomic commit point's staging name: imports write to
         * `<name>.partial` and os.replace() onto the live path only when
         * clean. A leftover `.partial` from a killed run is discarded
         * before the next attempt - the live database was never touched.
         */
        fun partialFor(live: File): File = File(live.parentFile, live.name + ".partial")
    }

    /** Minimal growable byte buffer with UTF-8 appending. VALID
     * surrogate pairs combine into the standard 4-byte form (P5 R1
     * I-79: the high surrogate used to collapse to a lone 0xFF — any
     * astral character in user text would have aborted the whole
     * translation); a lone/trailing HIGH surrogate maps to 0xFF, and a
     * lone LOW surrogate encodes an invalid 3-byte sequence — both
     * refuse loud at the strict-UTF-8 import decode (the wire's
     * valid-UTF-8 domain should never carry either). */
    private class ByteArrayBuilder {
        private var data = ByteArray(64)
        private var size = 0
        private var pendingHighSurrogate: Char? = null

        fun append(b: Int) {
            ensure(1)
            data[size++] = b.toByte()
        }

        fun appendUtf16(c: Char) {
            if (pendingHighSurrogate != null) {
                val high = pendingHighSurrogate!!
                pendingHighSurrogate = null
                if (c.isLowSurrogate()) {
                    val codePoint = 0x10000 +
                        (((high.code - 0xD800) shl 10) or (c.code - 0xDC00))
                    append(0xF0 or (codePoint shr 18))
                    append(0x80 or ((codePoint shr 12) and 0x3F))
                    append(0x80 or ((codePoint shr 6) and 0x3F))
                    append(0x80 or (codePoint and 0x3F))
                    return
                }
                append(0xFF) // the pending high surrogate was lone
            }
            when {
                c.code < 0x80 -> append(c.code)
                c.code < 0x800 -> {
                    append(0xC0 or (c.code shr 6))
                    append(0x80 or (c.code and 0x3F))
                }
                c.isHighSurrogate() -> pendingHighSurrogate = c
                else -> {
                    append(0xE0 or (c.code shr 12))
                    append(0x80 or ((c.code shr 6) and 0x3F))
                    append(0x80 or (c.code and 0x3F))
                }
            }
        }

        fun finish(): ByteArray {
            pendingHighSurrogate?.let {
                // trailing lone high surrogate: record the replacement
                append(0xFF)
                pendingHighSurrogate = null
            }
            return data.copyOf(size)
        }

        private fun ensure(n: Int) {
            if (size + n > data.size) {
                data = data.copyOf(maxOf(data.size * 2, size + n))
            }
        }
    }
}
