package com.pocketrealm.importer

import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File

/**
 * the desktop import journal — the JVM twin of the Android
 * importer/ImportJournal (android.database.sqlite) over the
 * [DesktopSqliteConnection] seam (the repo-pinned SQLite amalgamation through
 * pocket_sqlite.dll). Same schema (version 4), same imports/files/
 * data_stages/benchmarks tables, same phase and checkpoint semantics, so a
 * generation manifest or a data-stage checkpoint written by either platform
 * reads identically.
 *
 * Platform substitutions (the register's Context→DesktopStorageRoots,
 * SQLiteOpenHelper→DesktopSqlite seam):
 *  - `setWriteAheadLoggingEnabled(true)` → `PRAGMA journal_mode=WAL` through
 *    the seam's row-returning query entry point; `synchronous=FULL` and
 *    foreign-key enforcement are set explicitly like the Android onConfigure;
 *  - android Uri → the picked source's absolute-path string (the column
 *    keeps its `source_uri` name; the resume equality check is unchanged);
 *  - bind parameters → inlined SQL literals (the seam rejects bound
 *    parameters); every string is single-quote-escaped by [sqlString];
 *  - cursor iteration → first-row/first-column probes with LIMIT/OFFSET
 *    (the seam returns one value per query);
 *  - explicit transactions → BEGIN IMMEDIATE/COMMIT/ROLLBACK exec pairs.
 *
 * Single-thread by contract (SQLITE_THREADSAFE=2): every operation takes the
 * journal lock, so suspend callers that hop Dispatchers threads never touch
 * the connection concurrently.
 */
internal class ImportJournal(roots: DesktopStorageRoots) : AutoCloseable {
    private val lock = Any()
    private val directory = File(roots.runtime, "importer")
    private val db: DesktopSqliteConnection

    init {
        check(directory.isDirectory || directory.mkdirs()) { "cannot create the importer journal directory" }
        db = DesktopSqliteConnection(File(directory, "imports.db").absolutePath)
        // Row-returning PRAGMAs go through the query entry points (the seam's
        // contract); the rest are plain execs.
        db.queryText("PRAGMA journal_mode=WAL")
        db.exec("PRAGMA synchronous=FULL")
        db.exec("PRAGMA foreign_keys=ON")
        migrate()
    }

    // ------------------------------------------------------------------
    // Schema (the Android Helper's onCreate/onUpgrade, schema 4).
    // ------------------------------------------------------------------

    private fun migrate() {
        val version = db.queryLong("PRAGMA user_version")?.toInt() ?: 0
        if (version == SCHEMA) return
        check(version <= SCHEMA) { "unsupported importer journal migration $version->$SCHEMA" }
        db.exec("BEGIN IMMEDIATE")
        try {
            if (version < 1) {
                db.exec(IMPORTS_DDL)
                db.exec(FILES_DDL)
                db.exec("CREATE INDEX files_state_idx ON files(import_id,state)")
                db.exec(DATA_STAGES_DDL_WITHOUT_ATTEMPT)
                db.exec(BENCHMARKS_DDL)
            }
            if (version in 1 until 2) {
                db.exec("ALTER TABLE data_stages ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0")
                db.exec("UPDATE data_stages SET attempt=0 WHERE attempt IS NULL")
            }
            if (version in 1 until 3) {
                db.exec("ALTER TABLE imports ADD COLUMN completed_at_ms INTEGER")
                db.exec("ALTER TABLE data_stages ADD COLUMN started_at_ms INTEGER NOT NULL DEFAULT 0")
                db.exec("ALTER TABLE data_stages ADD COLUMN completed_at_ms INTEGER NOT NULL DEFAULT 0")
                db.exec(BENCHMARKS_DDL)
            }
            if (version in 1 until 4) {
                db.exec("ALTER TABLE imports ADD COLUMN source_kind TEXT NOT NULL DEFAULT 'TREE'")
                db.exec("ALTER TABLE imports ADD COLUMN staged_path TEXT")
                db.exec("ALTER TABLE imports ADD COLUMN staged_bytes INTEGER NOT NULL DEFAULT 0")
                db.exec("ALTER TABLE imports ADD COLUMN staged_sha256 TEXT")
            }
            db.exec("PRAGMA user_version=$SCHEMA")
            db.exec("COMMIT")
        } catch (error: Throwable) {
            runCatching { db.exec("ROLLBACK") }
            throw error
        }
    }

    // ------------------------------------------------------------------
    // Import-row lifecycle.
    // ------------------------------------------------------------------

    fun beginOrResume(sourceUri: String, inventory: SourceInventory): String = synchronized(lock) {
        db.exec("BEGIN IMMEDIATE")
        try {
            val prior = latestOpenRow()
            if (prior != null) {
                val (importId, priorUri, fingerprint, phase) = prior
                if (priorUri != sourceUri || fingerprint != inventory.fingerprint) {
                    if (phase == ImportPhase.FAILED.name) {
                        exec(
                            "UPDATE imports SET phase='${ImportPhase.CANCELLED.name}', " +
                                "updated_at_ms=${System.currentTimeMillis()} WHERE import_id=${sqlString(importId)}",
                        )
                    } else {
                        failLocked(importId, SOURCE_CHANGED_FOLDER)
                        db.exec("COMMIT")
                        throw ImportRejected(SOURCE_CHANGED_FOLDER_MESSAGE)
                    }
                } else {
                    db.exec("COMMIT")
                    return importId
                }
            }
            val id = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            exec(
                "INSERT INTO imports(import_id, schema_version, source_uri, source_fingerprint, phase, " +
                    "source_kind, files_total, bytes_total, files_processed, bytes_copied, warning_count, " +
                    "created_at_ms, updated_at_ms) VALUES(" +
                    listOf(
                        sqlString(id), SCHEMA.toString(), sqlString(sourceUri),
                        sqlString(inventory.fingerprint), sqlString(ImportPhase.DISCOVERING.name),
                        sqlString(ImportSourceKind.TREE.name),
                        inventory.fileCount.toString(), inventory.totalBytes.toString(),
                        "0", "0", "0", now.toString(), now.toString(),
                    ).joinToString(", ") + ")",
            )
            db.exec("COMMIT")
            id
        } catch (error: Throwable) {
            rollbackUncommitted(error)
            throw error
        }
    }

    data class StagingResume(
        val importId: String,
        val expectedBytes: Long,
        val stagedBytes: Long,
        val stagedPath: String?,
        val stagedSha256: String?,
    )

    /**
     * Archive lane: the journal row exists BEFORE staging and detection so the
     * UI can show progress and a crash restarts the staged copy. Resume
     * matches the persisted source; the staged file itself is the durable
     * anchor (staged_bytes + staged_sha256).
     */
    fun beginStagingOrResume(sourceUri: String, expectedBytes: Long): StagingResume = synchronized(lock) {
        db.exec("BEGIN IMMEDIATE")
        try {
            val prior = latestOpenRow()
            if (prior != null) {
                val (importId, priorUri, _, phase) = prior
                val kind = textOrNull("SELECT source_kind FROM imports WHERE import_id=${sqlString(importId)}")
                if (kind != ImportSourceKind.ARCHIVE.name) {
                    db.exec("COMMIT")
                    throw ImportRejected("SOURCE_CHANGED: a folder import is still active")
                }
                val stagedBytes = longOrZero(
                    "SELECT staged_bytes FROM imports WHERE import_id=${sqlString(importId)}",
                )
                val stagedPath = textOrNull(
                    "SELECT staged_path FROM imports WHERE import_id=${sqlString(importId)}",
                )
                val stagedSha256 = textOrNull(
                    "SELECT staged_sha256 FROM imports WHERE import_id=${sqlString(importId)}",
                )
                val journaledTotal = longOrZero(
                    "SELECT bytes_total FROM imports WHERE import_id=${sqlString(importId)}",
                )
                // Before staging finishes, the caller's expected size must
                // match the journaled total (a mismatch would re-stage toward
                // a truncated target). After finishStaging, bytes_total
                // legitimately becomes the extraction payload total — the
                // staged file anchors identity instead.
                val sizeChanged = stagedPath == null && journaledTotal != expectedBytes
                if (priorUri != sourceUri || sizeChanged) {
                    val reason = when {
                        priorUri != sourceUri -> "resume requires the original archive"
                        else -> "resume size $expectedBytes != journaled $journaledTotal bytes"
                    }
                    if (phase == ImportPhase.FAILED.name) {
                        exec(
                            "UPDATE imports SET phase='${ImportPhase.CANCELLED.name}', " +
                                "updated_at_ms=${System.currentTimeMillis()} WHERE import_id=${sqlString(importId)}",
                        )
                        db.exec("COMMIT")
                    } else {
                        failLocked(importId, "SOURCE_CHANGED: $reason")
                        db.exec("COMMIT")
                        throw ImportRejected("SOURCE_CHANGED: $reason")
                    }
                } else {
                    db.exec("COMMIT")
                    return StagingResume(importId, expectedBytes, stagedBytes, stagedPath, stagedSha256)
                }
            }
            val id = java.util.UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            exec(
                "INSERT INTO imports(import_id, schema_version, source_uri, source_fingerprint, phase, " +
                    "source_kind, staged_bytes, files_processed, files_total, bytes_copied, bytes_total, " +
                    "warning_count, created_at_ms, updated_at_ms) VALUES(" +
                    listOf(
                        sqlString(id), SCHEMA.toString(), sqlString(sourceUri),
                        sqlString("pending-staging"), sqlString(ImportPhase.STAGING.name),
                        sqlString(ImportSourceKind.ARCHIVE.name), "0", "0", "0", "0",
                        expectedBytes.toString(), "0", now.toString(), now.toString(),
                    ).joinToString(", ") + ")",
            )
            db.exec("COMMIT")
            StagingResume(id, expectedBytes, 0, null, null)
        } catch (error: Throwable) {
            rollbackUncommitted(error)
            throw error
        }
    }

    /** Keeps the journal fresh during the staged copy; rate-limited by the caller. */
    fun touchStaging(importId: String, copiedBytes: Long) = synchronized(lock) {
        exec(
            "UPDATE imports SET staged_bytes=${copiedBytes.coerceAtLeast(0)}, " +
                "phase='${ImportPhase.STAGING.name}', updated_at_ms=${System.currentTimeMillis()} " +
                "WHERE import_id=${sqlString(importId)}",
        )
    }

    /** Staging finished; the staged file's digest becomes the resume anchor. */
    fun finishStaging(importId: String, stagedPath: String, stagedBytes: Long, stagedSha256: String) =
        synchronized(lock) {
            exec(
                "UPDATE imports SET staged_path=${sqlString(stagedPath)}, " +
                    "staged_bytes=${stagedBytes.coerceAtLeast(0)}, staged_sha256=${sqlString(stagedSha256)}, " +
                    "phase='${ImportPhase.DISCOVERING.name}', updated_at_ms=${System.currentTimeMillis()} " +
                    "WHERE import_id=${sqlString(importId)}",
            )
        }

    /**
     * The data-preparation lane's journal anchor: the data_stages rows
     * reference imports(import_id), so a standalone preparation pass needs
     * its import row to exist BEFORE the first stage (on Android the row
     * came from the client import that preceded preparation; the desktop
     * data-prep lane runs standalone, keyed by the client generation id).
     * Idempotent: an existing row (a previous pass or a real import) wins.
     */
    fun beginDataPreparationOrResume(importId: String, sourceUri: String): String = synchronized(lock) {
        require(importId.matches(UUID)) { "invalid data-preparation identity" }
        val existing = textOrNull("SELECT import_id FROM imports WHERE import_id=${sqlString(importId)}")
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        exec(
            "INSERT INTO imports(import_id, schema_version, source_uri, source_fingerprint, phase, " +
                "source_kind, files_total, bytes_total, files_processed, bytes_copied, warning_count, " +
                "created_at_ms, updated_at_ms) VALUES(" +
                listOf(
                    sqlString(importId), SCHEMA.toString(), sqlString(sourceUri),
                    sqlString("data-prep"), sqlString(ImportPhase.PREPARING_DATA.name),
                    sqlString(ImportSourceKind.TREE.name),
                    "0", "0", "0", "0", "0", now.toString(), now.toString(),
                ).joinToString(", ") + ")",
        )
        importId
    }

    fun recordInventory(importId: String, entries: List<ImportSourceEntry>) = synchronized(lock) {
        db.exec("BEGIN IMMEDIATE")
        try {
            for (entry in entries.filterNot { it.directory }) {
                // CONFLICT_IGNORE: the existing row keeps its state.
                exec(
                    "INSERT OR IGNORE INTO files(import_id, relative_path, document_id, expected_size, " +
                        "expected_mtime, state, bytes_copied, attempt, fsync_marker) VALUES(" +
                        listOf(
                            sqlString(importId), sqlString(entry.relativePath), sqlString(entry.key),
                            entry.size.toString(), entry.lastModified.toString(),
                            sqlString(ImportFileState.DISCOVERED.name), "0", "0", "0",
                        ).joinToString(", ") + ")",
                )
            }
            db.exec("COMMIT")
        } catch (error: Throwable) {
            rollbackUncommitted(error)
            throw error
        }
    }

    fun file(importId: String, relativePath: String): JournalFile? = synchronized(lock) {
        val where = "FROM files WHERE import_id=${sqlString(importId)} AND relative_path=${sqlString(relativePath)}"
        val state = textOrNull("SELECT state $where") ?: return null
        JournalFile(
            state = ImportFileState.valueOf(state),
            expectedSize = longOrZero("SELECT expected_size $where"),
            expectedMtime = longOrZero("SELECT expected_mtime $where"),
            bytesCopied = longOrZero("SELECT bytes_copied $where"),
            tempName = textOrNull("SELECT temp_name $where"),
            sha256 = textOrNull("SELECT sha256 $where"),
            attempt = longOrZero("SELECT attempt $where").toInt(),
            fsyncMarker = longOrZero("SELECT fsync_marker $where") != 0L,
        )
    }

    fun markCopying(importId: String, entry: ImportSourceEntry, tempName: String) = synchronized(lock) {
        exec(
            "UPDATE files SET state='COPYING', bytes_copied=0, temp_name=${sqlString(tempName)}, " +
                "sha256=NULL, attempt=attempt+1, last_error=NULL, fsync_marker=0 " +
                "WHERE import_id=${sqlString(importId)} AND relative_path=${sqlString(entry.relativePath)}",
        )
        update(importId, copyPhaseFor(importId), entry.relativePath)
    }

    /** Archive lanes surface the copy loop as EXTRACTING; folder lanes keep COPYING. */
    private fun copyPhaseFor(importId: String): ImportPhase {
        val kind = textOrNull("SELECT source_kind FROM imports WHERE import_id=${sqlString(importId)}")
        return if (kind == ImportSourceKind.ARCHIVE.name) ImportPhase.EXTRACTING else ImportPhase.COPYING
    }

    /** Archive lane: adopt the classified inventory's totals after detection. */
    fun commitInventory(importId: String, fileCount: Int, totalBytes: Long, fingerprint: String) =
        synchronized(lock) {
            exec(
                "UPDATE imports SET files_total=$fileCount, bytes_total=$totalBytes, " +
                    "source_fingerprint=${sqlString(fingerprint)}, updated_at_ms=${System.currentTimeMillis()} " +
                    "WHERE import_id=${sqlString(importId)}",
            )
        }

    /**
     * Re-enter a file whose copy was interrupted mid-way. Unlike markCopying
     * this keeps bytes_copied and temp_name so the partial file can be
     * appended to instead of restarted from byte zero.
     */
    fun markResumed(importId: String, entry: ImportSourceEntry) = synchronized(lock) {
        exec(
            "UPDATE files SET state='COPYING', last_error=NULL, attempt=attempt+1 " +
                "WHERE import_id=${sqlString(importId)} AND relative_path=${sqlString(entry.relativePath)}",
        )
        update(importId, copyPhaseFor(importId), entry.relativePath)
    }

    /**
     * Periodic in-file progress so a multi-minute MPQ copy keeps the journal
     * fresh (watchdog staleness and post-mortem progress both read it).
     * Rate-limited by the caller.
     */
    fun touchCopying(importId: String, entry: ImportSourceEntry, copiedBytes: Long) = synchronized(lock) {
        exec(
            "UPDATE files SET bytes_copied=${copiedBytes.coerceAtLeast(0)} " +
                "WHERE import_id=${sqlString(importId)} AND relative_path=${sqlString(entry.relativePath)}",
        )
        update(importId, copyPhaseFor(importId), entry.relativePath)
    }

    fun markVerified(importId: String, entry: ImportSourceEntry, sha256: String, copiedBytes: Long) =
        synchronized(lock) {
            exec(
                "UPDATE files SET state='VERIFIED', bytes_copied=${copiedBytes.coerceAtLeast(0)}, " +
                    "sha256=${sqlString(sha256)}, temp_name=NULL, last_error=NULL, fsync_marker=1 " +
                    "WHERE import_id=${sqlString(importId)} AND relative_path=${sqlString(entry.relativePath)}",
            )
            update(importId, copyPhaseFor(importId), entry.relativePath)
        }

    fun markSkipped(importId: String, entry: ImportSourceEntry, reason: String) = synchronized(lock) {
        exec(
            "UPDATE files SET state='SKIPPED', bytes_copied=0, temp_name=NULL, last_error=${sqlString(reason.take(ERROR_MAX))}, " +
                "fsync_marker=1 WHERE import_id=${sqlString(importId)} AND relative_path=${sqlString(entry.relativePath)}",
        )
        update(importId, copyPhaseFor(importId), entry.relativePath)
    }

    fun markFileFailed(importId: String, entry: ImportSourceEntry, error: Throwable) = synchronized(lock) {
        exec(
            "UPDATE files SET state='FAILED', last_error=${sqlString(detailOf(error))} " +
                "WHERE import_id=${sqlString(importId)} AND relative_path=${sqlString(entry.relativePath)}",
        )
        fail(importId, detailOf(error))
    }

    fun update(importId: String, phase: ImportPhase, lastPath: String? = null, error: String? = null) =
        synchronized(lock) {
            updateLocked(importId, phase, lastPath, error)
        }

    private fun updateLocked(importId: String, phase: ImportPhase, lastPath: String?, error: String?) {
        val filesProcessed = longOrZero(
            "SELECT COUNT(*) FROM files WHERE import_id=${sqlString(importId)} " +
                "AND state IN ('VERIFIED','SKIPPED')",
        ).toInt()
        val bytesCopied = longOrZero(
            "SELECT COALESCE(SUM(CASE WHEN state='VERIFIED' THEN bytes_copied ELSE 0 END),0) " +
                "FROM files WHERE import_id=${sqlString(importId)} AND state IN ('VERIFIED','SKIPPED')",
        )
        exec(
            "UPDATE imports SET phase=${sqlString(phase.name)}, files_processed=$filesProcessed, " +
                "bytes_copied=$bytesCopied, last_relative_path=${sqlNullable(lastPath)}, " +
                "last_error=${sqlNullable(error?.take(ERROR_MAX))}, updated_at_ms=${System.currentTimeMillis()} " +
                "WHERE import_id=${sqlString(importId)}",
        )
    }

    fun fail(importId: String, detail: String) = synchronized(lock) {
        db.exec("BEGIN IMMEDIATE")
        try {
            failLocked(importId, detail)
            db.exec("COMMIT")
        } catch (error: Throwable) {
            rollbackUncommitted(error)
            throw error
        }
    }

    fun complete(importId: String, generation: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        exec(
            "UPDATE imports SET phase='${ImportPhase.COMPLETE.name}', " +
                "active_generation=${sqlString(generation)}, last_relative_path=NULL, last_error=NULL, " +
                "completed_at_ms=$now, updated_at_ms=$now WHERE import_id=${sqlString(importId)}",
        )
    }

    fun importTiming(importId: String): Pair<Long, Long?>? = synchronized(lock) {
        val where = "FROM imports WHERE import_id=${sqlString(importId)}"
        val created = longOrNull("SELECT created_at_ms $where") ?: return null
        created to longOrNull("SELECT completed_at_ms $where")
    }

    // ------------------------------------------------------------------
    // Benchmarks (schema parity; the desktop lane has no recorder yet).
    // ------------------------------------------------------------------

    fun recordBenchmark(benchmark: ImportBenchmark) = synchronized(lock) {
        exec(
            "INSERT OR REPLACE INTO benchmarks(import_id, device_label, model, soc, actively_cooled, abi, api, " +
                "cores, ram_bytes, total_ms, copy_ms, data_ms, stage_durations, mmap_maps, mmap_threads, " +
                "created_at_ms) VALUES(" +
                listOf(
                    sqlString(benchmark.importId), sqlString(benchmark.deviceLabel), sqlString(benchmark.model),
                    sqlString(benchmark.soc), if (benchmark.activelyCooled) "1" else "0",
                    sqlString(benchmark.abi), benchmark.api.toString(), benchmark.cores.toString(),
                    benchmark.ramBytes.toString(), benchmark.totalMs.toString(), benchmark.copyMs.toString(),
                    benchmark.dataMs.toString(), sqlString(benchmark.stageDurationsJson),
                    benchmark.mmapMaps.toString(), benchmark.mmapThreads.toString(),
                    benchmark.createdAtMs.toString(),
                ).joinToString(", ") + ")",
        )
    }

    fun benchmarks(limit: Int = 8): List<ImportBenchmark> = synchronized(lock) {
        val count = longOrZero("SELECT COUNT(*) FROM benchmarks").toInt()
        buildList {
            for (row in 0 until count) {
                val where = "FROM benchmarks ORDER BY created_at_ms DESC LIMIT 1 OFFSET $row"
                add(
                    ImportBenchmark(
                        importId = requireNotNull(textOrNull("SELECT import_id $where")),
                        deviceLabel = requireNotNull(textOrNull("SELECT device_label $where")),
                        model = requireNotNull(textOrNull("SELECT model $where")),
                        soc = requireNotNull(textOrNull("SELECT soc $where")),
                        activelyCooled = longOrZero("SELECT actively_cooled $where") != 0L,
                        abi = requireNotNull(textOrNull("SELECT abi $where")),
                        api = longOrZero("SELECT api $where").toInt(),
                        cores = longOrZero("SELECT cores $where").toInt(),
                        ramBytes = longOrZero("SELECT ram_bytes $where"),
                        totalMs = longOrZero("SELECT total_ms $where"),
                        copyMs = longOrZero("SELECT copy_ms $where"),
                        dataMs = longOrZero("SELECT data_ms $where"),
                        stageDurationsJson = requireNotNull(textOrNull("SELECT stage_durations $where")),
                        mmapMaps = longOrZero("SELECT mmap_maps $where").toInt(),
                        mmapThreads = longOrZero("SELECT mmap_threads $where").toInt(),
                        createdAtMs = longOrZero("SELECT created_at_ms $where"),
                    ),
                )
                if (size >= limit) return@buildList
            }
        }
    }

    // ------------------------------------------------------------------
    // Data-preparation checkpoints (the DataPreparationStore twin's seam).
    // ------------------------------------------------------------------

    fun dataStage(importId: String, stage: DataStage): DataCheckpoint? = synchronized(lock) {
        readDataStage(importId, stage.name)
    }

    private fun readDataStage(importId: String, stageName: String): DataCheckpoint? {
        val where = "FROM data_stages WHERE import_id=${sqlString(importId)} AND stage=${sqlString(stageName)}"
        val state = textOrNull("SELECT state $where") ?: return null
        return DataCheckpoint(
            DataStage.valueOf(stageName),
            DataStageState.valueOf(state),
            longOrZero("SELECT processed $where").toInt(),
            longOrZero("SELECT total $where").toInt(),
            longOrZero("SELECT bytes_written $where"),
            textOrNull("SELECT checkpoint $where"),
            longOrZero("SELECT attempt $where").toInt(),
            textOrNull("SELECT last_error $where"),
            longOrZero("SELECT updated_at_ms $where"),
            longOrZero("SELECT started_at_ms $where"),
            longOrZero("SELECT completed_at_ms $where"),
        )
    }

    fun dataStages(importId: String): List<DataCheckpoint> = synchronized(lock) {
        val count = longOrZero(
            "SELECT COUNT(*) FROM data_stages WHERE import_id=${sqlString(importId)}",
        ).toInt()
        buildList {
            for (row in 0 until count) {
                val stage = textOrNull(
                    "SELECT stage FROM data_stages WHERE import_id=${sqlString(importId)} " +
                        "ORDER BY rowid LIMIT 1 OFFSET $row",
                ) ?: continue
                readDataStage(importId, stage)?.let(::add)
            }
        }
    }

    fun copyingFile(importId: String): CopyingFile? = synchronized(lock) {
        val where =
            "FROM files WHERE import_id=${sqlString(importId)} AND state='COPYING' ORDER BY relative_path LIMIT 1"
        val relativePath = textOrNull("SELECT relative_path $where") ?: return null
        CopyingFile(
            relativePath = relativePath,
            expectedBytes = longOrZero("SELECT expected_size $where"),
            tempName = textOrNull("SELECT temp_name $where"),
        )
    }

    fun startDataStage(importId: String, stage: DataStage, total: Int, checkpoint: String? = null) =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            exec(
                "INSERT OR IGNORE INTO data_stages(import_id, stage, state, processed, total, bytes_written, " +
                    "checkpoint, attempt, updated_at_ms) VALUES(" +
                    listOf(
                        sqlString(importId), sqlString(stage.name), sqlString(DataStageState.PENDING.name),
                        "0", total.toString(), "0", sqlNullable(checkpoint), "0", now.toString(),
                    ).joinToString(", ") + ")",
            )
            exec(
                "UPDATE data_stages SET state='${DataStageState.RUNNING.name}', total=$total, attempt=attempt+1, " +
                    "last_error=NULL, started_at_ms=$now, completed_at_ms=0, updated_at_ms=$now " +
                    "WHERE import_id=${sqlString(importId)} AND stage=${sqlString(stage.name)}",
            )
            updateLocked(importId, ImportPhase.PREPARING_DATA, stage.name, null)
        }

    fun checkpointDataStage(
        importId: String,
        stage: DataStage,
        processed: Int,
        total: Int,
        bytesWritten: Long,
        checkpoint: String?,
        verified: Boolean = false,
    ) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val state = if (verified) DataStageState.VERIFIED.name else DataStageState.RUNNING.name
        val completion = if (verified) ", completed_at_ms=$now" else ""
        exec(
            "UPDATE data_stages SET state=${sqlString(state)}, processed=$processed, total=$total, " +
                "bytes_written=${bytesWritten.coerceAtLeast(0)}, checkpoint=${sqlNullable(checkpoint)}, " +
                "last_error=NULL$completion, updated_at_ms=$now " +
                "WHERE import_id=${sqlString(importId)} AND stage=${sqlString(stage.name)}",
        )
        updateLocked(importId, ImportPhase.PREPARING_DATA, "$stage:${checkpoint ?: processed}", null)
    }

    fun failDataStage(importId: String, stage: DataStage, detail: String) = synchronized(lock) {
        exec(
            "UPDATE data_stages SET state='${DataStageState.FAILED.name}', " +
                "last_error=${sqlString(detail.take(ERROR_MAX))}, updated_at_ms=${System.currentTimeMillis()} " +
                "WHERE import_id=${sqlString(importId)} AND stage=${sqlString(stage.name)}",
        )
        fail(importId, "DATA_${stage.name}: $detail")
    }

    /** Imports a future run could still resume (anything not terminal). */
    fun activeImportIds(): Set<String> = synchronized(lock) {
        val count = longOrZero(
            "SELECT COUNT(*) FROM imports WHERE phase NOT IN ('COMPLETE','CANCELLED')",
        ).toInt()
        buildSet {
            for (row in 0 until count) {
                textOrNull(
                    "SELECT import_id FROM imports WHERE phase NOT IN ('COMPLETE','CANCELLED') " +
                        "ORDER BY created_at_ms LIMIT 1 OFFSET $row",
                )?.let(::add)
            }
        }
    }

    fun latest(): ImportStatus = synchronized(lock) {
        val where =
            "FROM imports ORDER BY created_at_ms DESC LIMIT 1"
        val importId = textOrNull("SELECT import_id $where") ?: return ImportStatus()
        val filesTotal = longOrZero("SELECT files_total $where").toInt().coerceAtLeast(0)
        val bytesTotal = longOrZero("SELECT bytes_total $where").coerceAtLeast(0)
        ImportStatus(
            importId = importId,
            phase = ImportPhase.valueOf(requireNotNull(textOrNull("SELECT phase $where"))),
            // Lenient kind parse: the Android schema's lowercase 'tree'
            // default never matched the enum either (a latent device-lane
            // bug); the twin inserts the explicit name everywhere and maps
            // any legacy value back to TREE instead of throwing.
            sourceKind = textOrNull("SELECT source_kind $where")
                ?.let { kind ->
                    ImportSourceKind.entries.firstOrNull { it.name == kind }
                        ?: ImportSourceKind.entries.firstOrNull { it.name.equals(kind, true) }
                } ?: ImportSourceKind.TREE,
            sourceFingerprint = textOrNull("SELECT source_fingerprint $where"),
            sourceUri = textOrNull("SELECT source_uri $where"),
            // Clamped AT construction: legacy rows whose copied/processed
            // counters exceed their totals must not throw inside <init> (the
            // Android twin's posture, kept verbatim).
            filesProcessed = longOrZero("SELECT files_processed $where").toInt().coerceAtMost(filesTotal),
            filesTotal = filesTotal,
            bytesCopied = longOrZero("SELECT bytes_copied $where").coerceAtMost(bytesTotal),
            bytesTotal = bytesTotal,
            lastRelativePath = textOrNull("SELECT last_relative_path $where"),
            stagedBytes = longOrZero("SELECT staged_bytes $where"),
            stagedPath = textOrNull("SELECT staged_path $where"),
            warningCount = longOrZero("SELECT warning_count $where").toInt(),
            lastError = textOrNull("SELECT last_error $where"),
            activeGeneration = textOrNull("SELECT active_generation $where"),
            updatedAtMs = longOrZero("SELECT updated_at_ms $where"),
        )
    }

    override fun close() = synchronized(lock) { db.close() }

    // ------------------------------------------------------------------
    // Seam plumbing.
    // ------------------------------------------------------------------

    /** The most recent non-terminal import row (import_id, uri, fingerprint, phase), or null. */
    private fun latestOpenRow(): LatestOpenRow? {
        val importId = textOrNull(
            "SELECT import_id FROM imports WHERE phase NOT IN ('COMPLETE','CANCELLED') " +
                "ORDER BY created_at_ms DESC LIMIT 1",
        ) ?: return null
        val where = "FROM imports WHERE import_id=${sqlString(importId)}"
        return LatestOpenRow(
            importId = importId,
            sourceUri = textOrNull("SELECT source_uri $where").orEmpty(),
            fingerprint = textOrNull("SELECT source_fingerprint $where").orEmpty(),
            phase = textOrNull("SELECT phase $where").orEmpty(),
        )
    }

    private data class LatestOpenRow(
        val importId: String,
        val sourceUri: String,
        val fingerprint: String,
        val phase: String,
    )

    private fun failLocked(importId: String, detail: String) {
        exec(
            "UPDATE imports SET phase='${ImportPhase.FAILED.name}', last_error=${sqlString(detail.take(ERROR_MAX))}, " +
                "updated_at_ms=${System.currentTimeMillis()} WHERE import_id=${sqlString(importId)}",
        )
    }

    /** ROLLBACK is a no-op outside a transaction; only a thrown failure after
     * BEGIN reaches this with the transaction still open. */
    private fun rollbackUncommitted(@Suppress("UNUSED_PARAMETER") error: Throwable) {
        runCatching { db.exec("ROLLBACK") }
    }

    private fun exec(sql: String) = db.exec(sql)

    private fun textOrNull(sql: String): String? = db.queryText(sql)

    private fun longOrNull(sql: String): Long? = db.queryLong(sql)

    private fun longOrZero(sql: String): Long = db.queryLong(sql) ?: 0L

    private fun detailOf(error: Throwable): String = error.message ?: error.javaClass.simpleName

    data class JournalFile(
        val state: ImportFileState,
        val expectedSize: Long,
        val expectedMtime: Long,
        val bytesCopied: Long,
        val tempName: String?,
        val sha256: String?,
        val attempt: Int,
        val fsyncMarker: Boolean,
    )

    data class CopyingFile(
        val relativePath: String,
        val expectedBytes: Long,
        val tempName: String?,
    )

    companion object {
        const val SCHEMA = 4

        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        private const val ERROR_MAX = 512

        private const val SOURCE_CHANGED_FOLDER =
            "SOURCE_CHANGED: selected source no longer matches the durable journal"
        private const val SOURCE_CHANGED_FOLDER_MESSAGE =
            "SOURCE_CHANGED: resume requires the original unchanged source"

        private val IMPORTS_DDL = """CREATE TABLE imports(
                import_id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, source_uri TEXT NOT NULL,
                source_fingerprint TEXT NOT NULL, phase TEXT NOT NULL,
                source_kind TEXT NOT NULL DEFAULT 'TREE', staged_path TEXT,
                staged_bytes INTEGER NOT NULL DEFAULT 0, staged_sha256 TEXT,
                files_processed INTEGER NOT NULL,
                files_total INTEGER NOT NULL, bytes_copied INTEGER NOT NULL, bytes_total INTEGER NOT NULL,
                last_relative_path TEXT, warning_count INTEGER NOT NULL, last_error TEXT,
                active_generation TEXT, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL,
                completed_at_ms INTEGER)"""

        private val FILES_DDL = """CREATE TABLE files(
                import_id TEXT NOT NULL REFERENCES imports(import_id) ON DELETE CASCADE,
                relative_path TEXT NOT NULL, document_id TEXT NOT NULL, expected_size INTEGER NOT NULL,
                expected_mtime INTEGER NOT NULL, state TEXT NOT NULL, bytes_copied INTEGER NOT NULL,
                temp_name TEXT, sha256 TEXT, attempt INTEGER NOT NULL, last_error TEXT,
                fsync_marker INTEGER NOT NULL, PRIMARY KEY(import_id, relative_path))"""

        private val DATA_STAGES_DDL_WITHOUT_ATTEMPT = """CREATE TABLE data_stages(
                import_id TEXT NOT NULL REFERENCES imports(import_id) ON DELETE CASCADE,
                stage TEXT NOT NULL, state TEXT NOT NULL, processed INTEGER NOT NULL DEFAULT 0,
                total INTEGER NOT NULL DEFAULT 0, bytes_written INTEGER NOT NULL DEFAULT 0,
                checkpoint TEXT, attempt INTEGER NOT NULL DEFAULT 0, last_error TEXT, updated_at_ms INTEGER NOT NULL,
                started_at_ms INTEGER NOT NULL DEFAULT 0, completed_at_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(import_id,stage))"""

        private val BENCHMARKS_DDL = """CREATE TABLE IF NOT EXISTS benchmarks(
                import_id TEXT PRIMARY KEY REFERENCES imports(import_id) ON DELETE CASCADE,
                device_label TEXT NOT NULL, model TEXT NOT NULL, soc TEXT NOT NULL,
                actively_cooled INTEGER NOT NULL DEFAULT 0, abi TEXT NOT NULL, api INTEGER NOT NULL,
                cores INTEGER NOT NULL, ram_bytes INTEGER NOT NULL, total_ms INTEGER NOT NULL,
                copy_ms INTEGER NOT NULL DEFAULT 0, data_ms INTEGER NOT NULL DEFAULT 0,
                stage_durations TEXT NOT NULL DEFAULT '{}', mmap_maps INTEGER NOT NULL DEFAULT 0,
                mmap_threads INTEGER NOT NULL DEFAULT 0, created_at_ms INTEGER NOT NULL)"""
    }
}

/** Inlines a Kotlin string as a SQL single-quoted literal (the seam has no
 * bind parameters; the doubled-quote escape is the whole story). */
private fun sqlString(value: String): String = "'${value.replace("'", "''")}'"

/** Inlines a nullable string: SQL NULL when absent, the literal otherwise. */
private fun sqlNullable(value: String?): String = if (value == null) "NULL" else sqlString(value)
