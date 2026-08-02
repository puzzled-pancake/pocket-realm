package com.pocketrealm.importer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.File
import java.util.UUID

/** Append-safe SQLite state for thousands of SAF entries and data checkpoints. */
class ImportJournal(context: Context) : AutoCloseable {
    private val root = File(context.noBackupFilesDir, "importer").apply { mkdirs() }
    private val helper = Helper(context, File(root, "imports.db").absolutePath).also {
        it.setWriteAheadLoggingEnabled(true)
    }

    fun beginOrResume(uri: Uri, inventory: SourceInventory): String {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val prior = db.rawQuery(
                "SELECT import_id, source_uri, source_fingerprint, phase FROM imports " +
                    "WHERE phase NOT IN ('COMPLETE','CANCELLED') ORDER BY created_at_ms DESC LIMIT 1",
                emptyArray(),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else arrayOf(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
            }
            if (prior != null) {
                if (prior[1] != uri.toString() || prior[2] != inventory.fingerprint) {
                    if (prior[3] == ImportPhase.FAILED.name) {
                        db.update("imports", ContentValues().apply {
                            put("phase", ImportPhase.CANCELLED.name); put("updated_at_ms", System.currentTimeMillis())
                        }, "import_id=?", arrayOf(prior[0]))
                    } else {
                        failLocked(db, checkNotNull(prior[0]), "SOURCE_CHANGED: selected SAF tree no longer matches the durable journal")
                        throw ImportRejected("SOURCE_CHANGED: resume requires the original unchanged SAF tree")
                    }
                } else {
                    db.setTransactionSuccessful()
                    return checkNotNull(prior[0])
                }
            }
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            db.insertOrThrow("imports", null, ContentValues().apply {
                put("import_id", id); put("schema_version", SCHEMA)
                put("source_uri", uri.toString()); put("source_fingerprint", inventory.fingerprint)
                put("phase", ImportPhase.DISCOVERING.name)
                put("files_total", inventory.fileCount); put("bytes_total", inventory.totalBytes)
                put("files_processed", 0); put("bytes_copied", 0)
                put("warning_count", 0); put("created_at_ms", now); put("updated_at_ms", now)
            })
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun recordInventory(importId: String, entries: List<SafSourceEntry>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (entry in entries.filterNot { it.directory }) {
                db.insertWithOnConflict("files", null, ContentValues().apply {
                    put("import_id", importId); put("relative_path", entry.relativePath)
                    put("document_id", entry.documentId); put("expected_size", entry.size)
                    put("expected_mtime", entry.lastModified); put("state", ImportFileState.DISCOVERED.name)
                    put("bytes_copied", 0); put("attempt", 0); put("fsync_marker", 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun file(importId: String, relativePath: String): JournalFile? = helper.readableDatabase.rawQuery(
        "SELECT state, expected_size, expected_mtime, bytes_copied, temp_name, sha256, attempt, fsync_marker " +
            "FROM files WHERE import_id=? AND relative_path=?",
        arrayOf(importId, relativePath),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else JournalFile(
            state = ImportFileState.valueOf(cursor.getString(0)), expectedSize = cursor.getLong(1),
            expectedMtime = cursor.getLong(2), bytesCopied = cursor.getLong(3),
            tempName = cursor.getString(4), sha256 = cursor.getString(5),
            attempt = cursor.getInt(6), fsyncMarker = cursor.getInt(7) != 0,
        )
    }

    fun markCopying(importId: String, entry: SafSourceEntry, tempName: String) {
        helper.writableDatabase.execSQL(
            "UPDATE files SET state='COPYING', bytes_copied=0, temp_name=?, sha256=NULL, " +
                "attempt=attempt+1, last_error=NULL, fsync_marker=0 WHERE import_id=? AND relative_path=?",
            arrayOf(tempName, importId, entry.relativePath),
        )
        update(importId, ImportPhase.COPYING, entry.relativePath)
    }

    fun markVerified(importId: String, entry: SafSourceEntry, sha256: String, copiedBytes: Long) {
        helper.writableDatabase.execSQL(
            "UPDATE files SET state='VERIFIED', bytes_copied=?, sha256=?, temp_name=NULL, " +
                "last_error=NULL, fsync_marker=1 WHERE import_id=? AND relative_path=?",
            arrayOf<Any?>(copiedBytes, sha256, importId, entry.relativePath),
        )
        update(importId, ImportPhase.COPYING, entry.relativePath)
    }

    fun markSkipped(importId: String, entry: SafSourceEntry, reason: String) {
        helper.writableDatabase.execSQL(
            "UPDATE files SET state='SKIPPED', bytes_copied=0, temp_name=NULL, last_error=?, " +
                "fsync_marker=1 WHERE import_id=? AND relative_path=?",
            arrayOf(reason.take(512), importId, entry.relativePath),
        )
        update(importId, ImportPhase.COPYING, entry.relativePath)
    }

    fun markFileFailed(importId: String, entry: SafSourceEntry, error: Throwable) {
        helper.writableDatabase.execSQL(
            "UPDATE files SET state='FAILED', last_error=? WHERE import_id=? AND relative_path=?",
            arrayOf((error.message ?: error.javaClass.simpleName).take(512), importId, entry.relativePath),
        )
        fail(importId, error.message ?: error.javaClass.simpleName)
    }

    fun update(importId: String, phase: ImportPhase, lastPath: String? = null, error: String? = null) {
        val db = helper.writableDatabase
        val progress = db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(CASE WHEN state='VERIFIED' THEN bytes_copied ELSE 0 END),0) " +
                "FROM files WHERE import_id=? AND state IN ('VERIFIED','SKIPPED')",
            arrayOf(importId),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) to cursor.getLong(1) }
        db.update("imports", ContentValues().apply {
            put("phase", phase.name); put("files_processed", progress.first); put("bytes_copied", progress.second)
            put("last_relative_path", lastPath); put("last_error", error?.take(512))
            put("updated_at_ms", System.currentTimeMillis())
        }, "import_id=?", arrayOf(importId))
    }

    fun fail(importId: String, detail: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try { failLocked(db, importId, detail); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    fun complete(importId: String, generation: String) {
        helper.writableDatabase.update("imports", ContentValues().apply {
            put("phase", ImportPhase.COMPLETE.name); put("active_generation", generation)
            putNull("last_error"); put("updated_at_ms", System.currentTimeMillis())
        }, "import_id=?", arrayOf(importId))
    }

    fun dataStage(importId: String, stage: DataStage): DataCheckpoint? = helper.readableDatabase.rawQuery(
        "SELECT state,processed,total,bytes_written,checkpoint,attempt,last_error FROM data_stages " +
            "WHERE import_id=? AND stage=?", arrayOf(importId, stage.name),
    ).use { cursor -> if (!cursor.moveToFirst()) null else DataCheckpoint(
        stage, DataStageState.valueOf(cursor.getString(0)), cursor.getInt(1), cursor.getInt(2),
        cursor.getLong(3), cursor.getString(4), cursor.getInt(5), cursor.getString(6),
    ) }

    fun startDataStage(importId: String, stage: DataStage, total: Int, checkpoint: String? = null) {
        helper.writableDatabase.insertWithOnConflict("data_stages", null, ContentValues().apply {
            put("import_id", importId); put("stage", stage.name); put("state", DataStageState.PENDING.name)
            put("processed", 0); put("total", total); put("bytes_written", 0)
            put("checkpoint", checkpoint); put("attempt", 0); put("updated_at_ms", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        helper.writableDatabase.execSQL(
            "UPDATE data_stages SET state='RUNNING',total=?,attempt=attempt+1,last_error=NULL," +
                "updated_at_ms=? WHERE import_id=? AND stage=?",
            arrayOf<Any?>(total, System.currentTimeMillis(), importId, stage.name),
        )
        update(importId, ImportPhase.PREPARING_DATA, stage.name)
    }

    fun checkpointDataStage(
        importId: String, stage: DataStage, processed: Int, total: Int,
        bytesWritten: Long, checkpoint: String?, verified: Boolean = false,
    ) {
        helper.writableDatabase.execSQL(
            "UPDATE data_stages SET state=?,processed=?,total=?,bytes_written=?,checkpoint=?," +
                "last_error=NULL,updated_at_ms=? WHERE import_id=? AND stage=?",
            arrayOf<Any?>(if (verified) DataStageState.VERIFIED.name else DataStageState.RUNNING.name,
                processed, total, bytesWritten, checkpoint, System.currentTimeMillis(), importId, stage.name),
        )
        update(importId, ImportPhase.PREPARING_DATA, "$stage:${checkpoint ?: processed}")
    }

    fun failDataStage(importId: String, stage: DataStage, detail: String) {
        helper.writableDatabase.execSQL(
            "UPDATE data_stages SET state='FAILED',last_error=?,updated_at_ms=? WHERE import_id=? AND stage=?",
            arrayOf<Any?>(detail.take(512), System.currentTimeMillis(), importId, stage.name),
        )
        fail(importId, "DATA_${stage.name}: $detail")
    }

    fun latest(): ImportStatus = helper.readableDatabase.rawQuery(
        "SELECT import_id, phase, source_fingerprint, files_processed, files_total, bytes_copied, " +
            "bytes_total, last_relative_path, warning_count, last_error, active_generation, updated_at_ms " +
            "FROM imports ORDER BY created_at_ms DESC LIMIT 1", emptyArray(),
    ).use { cursor ->
        if (!cursor.moveToFirst()) ImportStatus() else ImportStatus(
            importId = cursor.getString(0), phase = ImportPhase.valueOf(cursor.getString(1)),
            sourceFingerprint = cursor.getString(2), filesProcessed = cursor.getInt(3),
            filesTotal = cursor.getInt(4), bytesCopied = cursor.getLong(5), bytesTotal = cursor.getLong(6),
            lastRelativePath = cursor.getString(7), warningCount = cursor.getInt(8),
            lastError = cursor.getString(9), activeGeneration = cursor.getString(10), updatedAtMs = cursor.getLong(11),
        )
    }

    fun files(importId: String): List<JournalEntry> = helper.readableDatabase.rawQuery(
        "SELECT relative_path, expected_size, state, sha256, attempt, last_error, fsync_marker " +
            "FROM files WHERE import_id=? ORDER BY relative_path COLLATE NOCASE, relative_path",
        arrayOf(importId),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(JournalEntry(
            cursor.getString(0), cursor.getLong(1), ImportFileState.valueOf(cursor.getString(2)),
            cursor.getString(3), cursor.getInt(4), cursor.getString(5), cursor.getInt(6) != 0,
        ))
    } }

    override fun close() = helper.close()

    private fun failLocked(db: SQLiteDatabase, importId: String, detail: String) {
        db.update("imports", ContentValues().apply {
            put("phase", ImportPhase.FAILED.name); put("last_error", detail.take(512))
            put("updated_at_ms", System.currentTimeMillis())
        }, "import_id=?", arrayOf(importId))
    }

    data class JournalFile(
        val state: ImportFileState, val expectedSize: Long, val expectedMtime: Long,
        val bytesCopied: Long, val tempName: String?, val sha256: String?,
        val attempt: Int, val fsyncMarker: Boolean,
    )
    data class JournalEntry(
        val relativePath: String, val expectedSize: Long, val state: ImportFileState,
        val sha256: String?, val attempt: Int, val lastError: String?, val fsyncMarker: Boolean,
    )

    private class Helper(context: Context, path: String) : SQLiteOpenHelper(context, path, null, SCHEMA) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA synchronous=FULL", null).close()
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE imports(
                import_id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, source_uri TEXT NOT NULL,
                source_fingerprint TEXT NOT NULL, phase TEXT NOT NULL, files_processed INTEGER NOT NULL,
                files_total INTEGER NOT NULL, bytes_copied INTEGER NOT NULL, bytes_total INTEGER NOT NULL,
                last_relative_path TEXT, warning_count INTEGER NOT NULL, last_error TEXT,
                active_generation TEXT, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE files(
                import_id TEXT NOT NULL REFERENCES imports(import_id) ON DELETE CASCADE,
                relative_path TEXT NOT NULL, document_id TEXT NOT NULL, expected_size INTEGER NOT NULL,
                expected_mtime INTEGER NOT NULL, state TEXT NOT NULL, bytes_copied INTEGER NOT NULL,
                temp_name TEXT, sha256 TEXT, attempt INTEGER NOT NULL, last_error TEXT,
                fsync_marker INTEGER NOT NULL, PRIMARY KEY(import_id, relative_path))""")
            db.execSQL("CREATE INDEX files_state_idx ON files(import_id,state)")
            db.execSQL("""CREATE TABLE data_stages(
                import_id TEXT NOT NULL REFERENCES imports(import_id) ON DELETE CASCADE,
                stage TEXT NOT NULL, state TEXT NOT NULL, processed INTEGER NOT NULL DEFAULT 0,
                total INTEGER NOT NULL DEFAULT 0, bytes_written INTEGER NOT NULL DEFAULT 0,
                checkpoint TEXT, attempt INTEGER NOT NULL DEFAULT 0, last_error TEXT, updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(import_id,stage))""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) {
                db.execSQL("ALTER TABLE data_stages ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0")
            } else throw IllegalStateException("unsupported importer journal migration $oldVersion->$newVersion")
        }
    }

    companion object { const val SCHEMA = 2 }
}
