package com.pocketrealm.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Design-timeframe /: the ANDROID stage-overlay applier — the
 * execution leg of the world-start overlay over the SHARED
 * [TimeframeOverlayPlan] (the desktop leg is `DesktopTimeframeOverlay`;
 * the decision surface cannot fork). Called by WorldRuntimeService's three
 * world-start legs AFTER the conf write and BEFORE the native boot, with
 * ANY failure refusing the world start loudly — never boot a world whose
 * conf names a stage the DB never reached.
 *
 * v1 honesty: the staged overlays are the inert marker scaffolding
 * (INSERT OR IGNORE only, never DELETE); the DEFAULT stage records and
 * touches no world DB — existing worlds are unaffected by construction.
 *
 * Connection discipline mirrors the engine's SQLite lane
 * ([DatabaseSqliteConfigPolicy.renderConnectionPragmas] on every writable
 * open; `wal_checkpoint(TRUNCATE)` and a sidecar assertion before the meta
 * publish, like the engine's ledger write). The SQLiteDatabase execution
 * legs are device-lane (the documented posture for the framework API);
 * the plan itself — statement policy, splitting, meta text — is shared and
 * JVM-tested.
 */
// The generic catches are the fail-loud contract: ANY failure (sqlite,
// framework, policy) must surface as the loud world-start refusal with its
// cause attached, never a partial application.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
internal object TimeframeOverlayApplier {

    /** The durable applied-stage record. */
    data class AppliedState(
        val appliedStage: String,
        val overlaySha256: String,
        val appliedAtMs: Long,
    )

    /** The WAL-sidecar-clearing checkpoint recordApplied runs before the
     * meta close (the engine's ledger-publish discipline). Row-returning
     * like journal_mode - and empty-tolerant like every pragma. */
    internal const val CHECKPOINT_PRAGMA = "PRAGMA wal_checkpoint(TRUNCATE);"

    fun readAppliedStage(sqliteDatadir: File): String? {
        if (!DatabaseSqliteControlPlane.databaseFile(
                sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE,
            ).isFile
        ) {
            return null
        }
        openMeta(sqliteDatadir).use { meta ->
            return queryTextOrNull(meta, TimeframeOverlayPlan.APPLIED_STAGE_QUERY)
        }
    }

    /**
     * The world-start leg: bring the datadir from its recorded stage to
     * [desiredStage] (the concrete stage the world conf names). The DEFAULT
     * stage is the seed itself: recorded, never executed against the world
     * DB. Returns the recorded state. The MARIADB lane guard runs BEFORE
     * any short-circuit — a durable ledger already naming the desired
     * stage must never license a MariaDB world at a non-default stage
     * (that ledger describes the retained SQLite datadir; the MariaDB
     * world's DB never received the overlay).
     */
    fun prepareForStart(sqliteDatadir: File, desiredStage: String): AppliedState {
        requireStagingLane(sqliteDatadir, desiredStage)
        val applied = readAppliedStage(sqliteDatadir)
        if (applied == desiredStage) {
            return recordedState(sqliteDatadir, desiredStage)
        }
        return when {
            TimeframeOverlayPlan.isDefaultStage(desiredStage) ->
                recordApplied(sqliteDatadir, desiredStage, overlaySha256 = "default-seed")
            else -> applyAndRecord(sqliteDatadir, desiredStage)
        }
    }

    /** The non-default leg: pinned statement plan, execution, ledger (the
     * lane check already ran in [prepareForStart]). */
    private fun applyAndRecord(sqliteDatadir: File, desiredStage: String): AppliedState {
        val statements = TimeframeOverlayPlan.statementsFor(desiredStage)
        applyOverlay(sqliteDatadir, desiredStage, statements)
        return recordApplied(sqliteDatadir, desiredStage, overlaySha256 = stagedSha256(desiredStage))
    }

    private fun stagedSha256(desiredStage: String): String =
        TimeframeOverlayPlan.overlaySha256(desiredStage)
            ?: throw IllegalStateException(TimeframeOverlayPlan.unstagedRefusal(desiredStage))

    /** The overlay lane is the SQLite provider's; a MARIADB active-provider
     * marker must never pair with a non-default stage (the default stage is
     * the seed itself and needs no overlay leg on any lane). */
    private fun requireStagingLane(sqliteDatadir: File, desiredStage: String) {
        if (TimeframeOverlayPlan.isDefaultStage(desiredStage)) return
        val marker = DatabaseDurableState.parseActiveProviderMarker(
            File(sqliteDatadir.parentFile, DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME)
                .takeIf(File::isFile)?.readText(),
        )
        check(marker?.mode != DatabaseDurableState.ProviderMode.MARIADB) {
            "timeframe overlay: the MARIADB provider lane has no overlay leg in v1 - " +
                "return the world to the default stage (or clear the marker) first"
        }
    }

    /** Execute the plan's statements against the world DB (the seed's own
     * `classicmangos`), re-checked through the shared policy (defense-in-
     * depth: an applier never executes an unvetted statement), each wrapped
     * with per-statement diagnostics. */
    private fun applyOverlay(
        sqliteDatadir: File,
        desiredStage: String,
        statements: List<String>,
    ) {
        val vetted = TimeframeOverlayPlan.policyChecked(
            statements.mapIndexed { index, sql ->
                DatabaseSqliteControlPlane.SeedStatement(index, 0, sql)
            },
            desiredStage,
        )
        val worldDb = DatabaseSqliteControlPlane.databaseFile(
            sqliteDatadir, TimeframeOverlayPlan.worldDatabase(),
        )
        check(worldDb.isFile) {
            "timeframe overlay: the world database is missing at $worldDb - " +
                "the datadir must be seeded before a non-default stage can apply"
        }
        val database = openWritable(worldDb)
        try {
            vetted.forEach { statement ->
                try {
                    database.execSQL(statement)
                } catch (failure: Throwable) {
                    throw IllegalStateException(
                        "timeframe overlay for \"$desiredStage\": statement " +
                            "(${statement.take(TimeframeOverlayPlan.POLICY_EXCERPT_CHARS)}) failed: ${failure.message}",
                        failure,
                    )
                }
            }
        } finally {
            database.close()
        }
    }

    private fun recordApplied(
        sqliteDatadir: File,
        stage: String,
        overlaySha256: String,
    ): AppliedState {
        val appliedAt = System.currentTimeMillis()
        openMeta(sqliteDatadir).use { meta ->
            meta.compileStatement(TimeframeOverlayPlan.stageStateUpsert(stage, overlaySha256, appliedAt))
                .execute()
            // The engine's own meta-publish discipline: checkpoint the WAL
            // away BEFORE close.
            execPragma(meta, CHECKPOINT_PRAGMA)
        }
        // ...and the sidecar assertion lands AFTER the close (a WAL-mode
        // file carries its -wal sidecar - even truncated - while a
        // connection is open; a stopped datadir must carry none).
        assertNoSidecars(sqliteDatadir)
        return AppliedState(stage, overlaySha256, appliedAt)
    }

    private fun recordedState(sqliteDatadir: File, stage: String): AppliedState {
        openMeta(sqliteDatadir).use { meta ->
            val sha = queryTextOrNull(meta, TimeframeOverlayPlan.APPLIED_SHA_QUERY)
            val at = queryLongOrNull(meta, TimeframeOverlayPlan.APPLIED_AT_QUERY)
            return AppliedState(stage, sha ?: "", at ?: 0L)
        }
    }

    /** Opens (creating if absent) the meta database with the engine's
     * ledger DDL plus the stage-state table, under the engine's connection
     * policy. */
    private fun openMeta(sqliteDatadir: File): SQLiteDatabase {
        val file = DatabaseSqliteControlPlane.databaseFile(
            sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE,
        )
        file.parentFile?.mkdirs()
        val meta = SQLiteDatabase.openDatabase(
            file.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
        )
        try {
            DatabaseSqliteConfigPolicy.renderConnectionPragmas().forEach { pragma ->
                execPragma(meta, pragma)
            }
            meta.execSQL(DatabaseSqliteControlPlane.LEDGER_DDL)
            meta.execSQL(TimeframeOverlayPlan.STAGE_STATE_DDL)
            return meta
        } catch (failure: Throwable) {
            runCatching { meta.close() }
            throw failure
        }
    }

    private fun openWritable(databaseFile: File): SQLiteDatabase {
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            DatabaseSqliteConfigPolicy.renderConnectionPragmas().forEach { pragma ->
                execPragma(database, pragma)
            }
            return database
        } catch (failure: Throwable) {
            runCatching { database.close() }
            throw failure
        }
    }

    private fun assertNoSidecars(sqliteDatadir: File) {
        val meta = DatabaseSqliteControlPlane.databaseFile(
            sqliteDatadir, DatabaseSqliteControlPlane.META_DATABASE,
        )
        check(!File(meta.parentFile, meta.name + "-wal").exists()) {
            "timeframe overlay: the meta write left a WAL sidecar behind"
        }
    }

    /** Execute one PRAGMA under the engine's semantics: every pragma
     * routes through rawQuery (execSQL forbids the row-returning forms
     * like journal_mode and wal_checkpoint), and an EMPTY cursor is
     * SUCCESS (null), never a failure - the set-only policy assignments
     * (synchronous, busy_timeout, foreign_keys, secure_delete,
     * cache_size) return no row by design, and reading a value back from
     * them (compileStatement().simpleQueryForString()) throws
     * SQLiteDoneException with a null message, which surfaced as
     * "DB-CONFIG: PRAGMA synchronous=NORMAL; failed: null" and refused
     * the whole world start (LVF-server-db-009). The DB-CONFIG failure
     * vocabulary stays reserved for genuine errors (bad syntax, unknown
     * pragma). */
    private fun execPragma(database: SQLiteDatabase, pragma: String): String? =
        try {
            database.rawQuery(pragma, null).use { cursor -> pragmaRowText(cursor) }
        } catch (failure: Throwable) {
            throw IllegalStateException("DB-CONFIG: $pragma failed: ${failure.message}", failure)
        }

    /** The engine's pragma read (DatabaseEngine.execPragma): the first
     * row's single column, or null when the cursor is empty. Internal so
     * the JVM plan lane can pin the empty-is-success semantics against a
     * cursor fake - the SQLiteDatabase execution leg itself stays the
     * documented device lane. */
    internal fun pragmaRowText(cursor: Cursor): String? =
        if (cursor.moveToFirst()) cursor.getString(0) else null

    /** Null when the row is missing (simpleQueryForString throws
     * SQLiteDoneException on an empty result — the applied-stage read must
     * treat "no record" as data, not an error). */
    private fun queryTextOrNull(database: SQLiteDatabase, query: String): String? =
        database.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun queryLongOrNull(database: SQLiteDatabase, query: String): Long? =
        database.rawQuery(query, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
}
