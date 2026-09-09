package com.pocketrealm.database

/**
 * Desktop twin of the android.database.sqlite execution seam: a small
 * JNI surface over the repo-pinned SQLite 3.46.1 amalgamation
 * (pocket_sqlite.dll, built by tools/build_win_sqlite_seam.py with the
 * same PRODUCTION define set as the fidelity harness). The pure
 * [DatabaseSqliteControlPlane] legs — seed replay, integrity gate,
 * revision probes, ledger DDL — execute through here on the desktop,
 * the role SQLiteDatabase plays on Android.
 *
 * Semantics mirror the framework API deliberately (see the JNI source
 * header): [execNative] takes exactly one statement and rejects
 * trailing SQL, bind parameters, and row-returning statements;
 * row-returning PRAGMAs (journal_mode, wal_checkpoint) go through the
 * query entry points; every failure raises IllegalStateException with
 * sqlite3's own message and extended result code. Connections are
 * single-thread by contract (SQLITE_THREADSAFE=2 in the production
 * set) — the engine drives each database from its database thread.
 */
internal object DesktopSqlite {
    init { System.loadLibrary("pocket_sqlite") }

    external fun openNative(path: String): Long

    external fun closeNative(db: Long): Int

    external fun execNative(db: Long, sql: String)

    external fun queryLongNative(db: Long, sql: String): Long?

    external fun queryTextNative(db: Long, sql: String): String?

    external fun versionNative(): String
}

/**
 * Handle-checked convenience over [DesktopSqlite] for callers that
 * prefer try-with-resources. Mechanical only — policy (fresh-file
 * discipline, digest pins, WAL sidecar checks, publication renames)
 * stays in the engine, exactly as on Android.
 */
internal class DesktopSqliteConnection(path: String) : AutoCloseable {
    private var handle: Long = DesktopSqlite.openNative(path)

    fun exec(sql: String) = DesktopSqlite.execNative(handle, sql)

    fun queryLong(sql: String): Long? = DesktopSqlite.queryLongNative(handle, sql)

    fun queryText(sql: String): String? = DesktopSqlite.queryTextNative(handle, sql)

    override fun close() {
        val closing = handle
        handle = 0
        if (closing != 0L) {
            DesktopSqlite.closeNative(closing)
        }
    }
}
