package com.pocketrealm.database

/**
 * The SQLite control plane's pure statement/policy surface —
 * the engine-agnostic shapes the SQLite provider path in DatabaseEngine
 * executes against the datadir's SQLite databases. Kept free of
 * Android-framework types so every shape is JVM-unit-testable; the
 * SQLiteDatabase execution legs are validated on device against
 * the host bridge harness.
 *
 * The ledger DDL is the SQLite-native translation of the MariaDB
 * migration_ledger (DatabaseEngine's createLedger): ENUM becomes a
 * CHECK constraint, ENGINE=InnoDB disappears, VARCHAR widths stay
 * (SQLite ignores them — they document intent), and the refuse-on-
 * mismatch contract is preserved by the consumer comparing
 * sql_sha256/status exactly as before.
 */
internal object DatabaseSqliteControlPlane {

    /** The SQLite databases the realm runtime owns (the seed transcripts
     * ship exactly these four, assets/seed/&lt;db&gt;.sqlz). */
    val DATABASES: List<String> = listOf(
        "classicrealmd", "classiccharacters", "classiclogs", "classicmangos",
    )

    /** Ledger status values (the MariaDB ENUM, as a CHECK constraint). */
    val LEDGER_STATUSES: Set<String> = setOf(
        "PENDING", "APPLIED", "ROLLED_BACK", "FAILED",
    )

    /** The pocketrealm_meta ledger DDL, SQLite-native. The meta
     * database is engine-created (not part of the seed transcripts):
     * same shape contract as the MariaDB original — migration_id
     * primary key, component/commit identities, sql hash, timestamps,
     * status, snapshot/build provenance. */
    const val LEDGER_DDL: String = """
        CREATE TABLE IF NOT EXISTS migration_ledger (
          migration_id TEXT NOT NULL PRIMARY KEY,
          component TEXT NOT NULL,
          source_commit TEXT NOT NULL,
          target_commit TEXT NOT NULL,
          sql_sha256 TEXT NOT NULL,
          started_at INTEGER NOT NULL,
          finished_at INTEGER,
          status TEXT NOT NULL CHECK (status IN ('PENDING','APPLIED','ROLLED_BACK','FAILED')),
          pre_snapshot_id TEXT NOT NULL,
          app_build_id TEXT NOT NULL,
          result_digest TEXT
        );
    """

    /** The revision probe — pragma_table_info replaces the MariaDB
     * information_schema.COLUMNS query. Returns true when [column] is
     * among [table]'s columns in [database]. */
    const val REVISION_PROBE: String =
        "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?;"

    /** Bind order for [REVISION_PROBE]: table, then column. */
    fun revisionProbeBinds(table: String, column: String): Array<String> = arrayOf(table, column)

    /** Corruption handling, the first-boot gate. quick_check first (the
     * fast pass); integrity_check as the full verdict; both must return
     * exactly "ok" (SQLite returns a single row 'ok' when clean). */
    const val QUICK_CHECK: String = "PRAGMA quick_check;"
    const val INTEGRITY_CHECK: String = "PRAGMA integrity_check;"
    const val INTEGRITY_OK: String = "ok"

    /** Corruption handling, the rebuild fallback. VACUUM INTO copies a
     * corruptible live database into a fresh file (defragmented and
     * rebuilt); the caller stages the target as <name>.rebuilt and
     * atomically replaces the live file only after the rebuilt copy
     * passes [INTEGRITY_CHECK] — the same commit-point discipline as
     * the bridge's .partial/os.replace. */
    const val VACUUM_INTO: String = "VACUUM INTO ?;"

    /** The seed asset names (staging ships exactly these). The .sqlz
     * suffix is deliberate — never .sql.gz, because AGP's asset merge
     * auto-gunzips *.gz. */
    fun seedAsset(database: String): String = "seed/$database.sqlz"

    // ------------------------------------------------------------------
    // Datadir layout. The SQLite provider's databases live in their own
    // sibling datadir (never inside the MariaDB datadir - the window keeps
    // both providers' state distinct and the MariaDB datadir is the
    // rollback anchor). One file per database plus the
    // engine-owned meta (ledger) database.
    // ------------------------------------------------------------------

    /** Directory name under the database root (the MariaDB datadir's sibling). */
    const val SQLITE_DATADIR_NAME = "sqlite-datadir"

    /** The SQLite database file for one of [DATABASES] (or the meta db). */
    fun databaseFile(sqliteDatadir: java.io.File, database: String): java.io.File =
        java.io.File(sqliteDatadir, "$database.sqlite3")

    /** The engine-created ledger database (not part of the seed transcripts). */
    const val META_DATABASE = "pocketrealm_meta"

    /**
     * SQLite deletes the -wal/-shm sidecars when the last connection closes
     * cleanly (PERSIST_WAL is not set in this build). A sidecar present at
     * stop time therefore means a connection is still open or died mid-
     * flight - the daemon-drain analog for the daemon-less provider: the
     * clean-stop seal may only be written when every sidecar is gone.
     */
    fun walSidecars(sqliteDatadir: java.io.File): List<java.io.File> {
        val databases = DATABASES.map { databaseFile(sqliteDatadir, it) } +
            databaseFile(sqliteDatadir, META_DATABASE)
        return databases.flatMap { file ->
            listOf(
                java.io.File(file.parentFile, file.name + "-wal"),
                java.io.File(file.parentFile, file.name + "-shm"),
            )
        }
    }

    /** The ledger INSERT for the seed-folds-the-manifest recording (all
     * 412 entries APPLIED by the seed replay; the refuse-on-mismatch
     * contract is the consumer comparing status+sql_sha256 exactly as the
     * MariaDB ledger does). Binds: id, component, source, target, sha,
     * startedAt, finishedAt, snapshotId, buildId (status is the fixed
     * 'APPLIED' literal). */
    const val LEDGER_APPLIED_INSERT: String =
        "INSERT INTO migration_ledger " +
            "(migration_id,component,source_commit,target_commit,sql_sha256,started_at,finished_at,status,pre_snapshot_id,app_build_id) " +
            "VALUES (?,?,?,?,?,?,?,'APPLIED',?,?)"

    // ------------------------------------------------------------------
    // Seed replay: the statement splitter (the host
    // executor's per-statement diagnostics came from sqlite3_complete
    // boundary detection; on device the framework API has no such helper,
    // so the same literal-aware boundary logic lives here, JVM-tested).
    // The SCANNER form is chunk-fed so the engine never materializes the
    // ~117 MiB mangos transcript in the Java heap.
    // ------------------------------------------------------------------

    /** Split a whole transcript in one call (tests + small transcripts).
     * The engine path feeds [SeedStatementScanner] chunks instead (the
     * ~117 MiB mangos transcript must never materialize in the heap). */
    fun splitSeedStatements(transcript: String): List<SeedStatement> {
        val scanner = SeedStatementScanner()
        val statements = scanner.feed(transcript).toMutableList()
        scanner.finish()?.let { statements.add(it) }
        return statements
    }

    /** One split statement with its transcript-local identity (drives
     * the per-statement diagnostics). [offset] is the transcript char offset where the
     * statement's text begins (pre-whitespace-trim). */
    data class SeedStatement(val index: Int, val offset: Int, val sql: String)

    /**
     * Incremental, literal-aware `;` boundary scanner for seed transcripts.
     * The host generator joins statements with ";\n" AND terminates the file
     * with a final ";\n" (per-statement strings carry NO ';';
     * the scanner handles both a terminated and an unterminated tail).
     * Single-quoted strings ('' escape), double-quoted identifiers/strings
     * (""), backtick identifiers (``), line comments (--) and block
     * comments (slash-star) never split. The translator strips comments
     * before emission, so the comment modes are defense-in-depth; chunk
     * boundaries splitting any two-char marker/pair are carried across
     * feeds (pendingMarker/pendingLiteralQuote/pendingBlockStar:
     * quote-after-marker adjacency must reprocess, not
     * swallow). [feed] returns the statements completed by that chunk;
     * [finish] flushes the tail.
     */
    class SeedStatementScanner {
        private enum class Mode { CODE, SINGLE, DOUBLE, BACKTICK, LINE_COMMENT, BLOCK_COMMENT }

        private var mode = Mode.CODE
        private val current = StringBuilder()
        private val completed = mutableListOf<SeedStatement>()
        private var index = 0
        // Absolute stream offset bookkeeping: feedBase counts every char
        // of PREVIOUS feeds; the in-progress statement's start is absolute
        // (a quote-pair consumes two chars in one loop iteration, so a
        // per-iteration counter would undercount - the whole-string and
        // chunk-fed paths must agree by construction, not by luck).
        private var feedBase = 0
        private var pendingStart = 0
        // Chunk-boundary carries: a quote/star/marker whose PAIR may sit in
        // the next chunk. Resolved at the top of the next feed() before the
        // state machine runs (a quote at a chunk tail could be closing the
        // literal or opening a doubled escape; a '*' could precede '/').
        private var pendingMarker: Char? = null // '-' or '/' seen at a chunk tail
        private var pendingLiteralQuote: Char? = null
        private var pendingBlockStar = false

        fun feed(chunk: String): List<SeedStatement> {
            var position = 0
            loop@ while (position < chunk.length) {
                val c = chunk[position]
                if (pendingLiteralQuote != null) {
                    val quote = pendingLiteralQuote!!
                    pendingLiteralQuote = null
                    if (c == quote) {
                        current.append(c) // the doubled escape's second half
                        position++
                        continue@loop
                    }
                    mode = Mode.CODE // it closed the literal; reprocess c
                }
                if (pendingBlockStar) {
                    pendingBlockStar = false
                    if (c == '/') {
                        current.append(c)
                        position++
                        mode = Mode.CODE
                        continue@loop
                    }
                    mode = Mode.BLOCK_COMMENT // lone '*' inside the comment
                }
                when (mode) {
                    Mode.CODE -> {
                        // A non-completing char after a chunk-tail '-'/'/'
                        // clears the marker and is REPROCESSED by the normal
                        // branches below - appending it blindly would swallow
                        // a quote/backtick that must open a literal (the
                        // merged-statement/false-boundary bug class).
                        if (pendingMarker != null) {
                            val completes = (pendingMarker == '-' && c == '-') ||
                                (pendingMarker == '/' && c == '*')
                            val marker = pendingMarker
                            pendingMarker = null
                            if (completes) {
                                mode = if (marker == '-') Mode.LINE_COMMENT else Mode.BLOCK_COMMENT
                                current.append(c)
                                position++
                                continue@loop
                            }
                        }
                        when {
                            c == '\'' -> { mode = Mode.SINGLE; current.append(c); position++ }
                            c == '"' -> { mode = Mode.DOUBLE; current.append(c); position++ }
                            c == '`' -> { mode = Mode.BACKTICK; current.append(c); position++ }
                            c == '-' && position + 1 < chunk.length && chunk[position + 1] == '-' -> {
                                mode = Mode.LINE_COMMENT; current.append(c); position++
                            }
                            c == '-' && position + 1 >= chunk.length -> {
                                pendingMarker = '-'; current.append(c); position++
                            }
                            c == '/' && position + 1 < chunk.length && chunk[position + 1] == '*' -> {
                                mode = Mode.BLOCK_COMMENT; current.append(c); position++
                            }
                            c == '/' && position + 1 >= chunk.length -> {
                                pendingMarker = '/'; current.append(c); position++
                            }
                            c == ';' -> {
                                emit(feedBase + position)
                                position++
                            }
                            else -> {
                                current.append(c); position++
                            }
                        }
                    }
                    Mode.SINGLE, Mode.DOUBLE, Mode.BACKTICK -> {
                        val quote = when (mode) {
                            Mode.SINGLE -> '\''
                            Mode.DOUBLE -> '"'
                            else -> '`'
                        }
                        current.append(c)
                        position++
                        if (c == quote) {
                            if (position < chunk.length) {
                                if (chunk[position] == quote) {
                                    current.append(chunk[position])
                                    position++
                                } else {
                                    mode = Mode.CODE
                                }
                            } else {
                                pendingLiteralQuote = quote
                            }
                        }
                    }
                    Mode.LINE_COMMENT -> {
                        current.append(c)
                        position++
                        if (c == '\n') mode = Mode.CODE
                    }
                    Mode.BLOCK_COMMENT -> {
                        current.append(c)
                        position++
                        if (c == '*') {
                            if (position < chunk.length) {
                                if (chunk[position] == '/') {
                                    current.append('/')
                                    position++
                                    mode = Mode.CODE
                                }
                            } else {
                                pendingBlockStar = true
                            }
                        }
                    }
                }
            }
            feedBase += chunk.length
            val out = completed.toList()
            completed.clear()
            return out
        }

        /** Flush the final statement (an unterminated tail exists only
         * when the transcript omits the file-terminating ";" + newline
         * - the driver emits one, so the tail is normally empty).
         * Unresolved chunk-tail carries are cleared: an unterminated
         * literal at end-of-transcript simply flushes as the tail. */
        fun finish(): SeedStatement? {
            check(pendingMarker == null) { "transcript ended mid comment marker" }
            pendingLiteralQuote = null
            pendingBlockStar = false
            val tail = current.toString().trim()
            current.setLength(0)
            return if (tail.isEmpty()) null else SeedStatement(index, pendingStart, tail)
        }

        private fun emit(semicolonOffset: Int) {
            val sql = current.toString().trim()
            if (sql.isNotEmpty()) {
                completed.add(SeedStatement(index, pendingStart, sql))
                index++
            }
            current.setLength(0)
            pendingStart = semicolonOffset + 1
        }
    }

    /** The import leg's target-column probe: name, declared type, and
     * NOT NULL flag for one table, in declaration order (the bridge
     * Importer's TargetColumn shape; ordered by cid). */
    const val TABLE_COLUMNS_PROBE: String =
        "SELECT name, type, \"notnull\" FROM pragma_table_info(?) ORDER BY cid;"

    fun tableColumnsProbeBinds(table: String): Array<String> = arrayOf(table)
}
