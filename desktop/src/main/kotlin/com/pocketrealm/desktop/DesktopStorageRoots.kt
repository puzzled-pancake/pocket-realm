package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import java.io.File

/**
 * Desktop path policy — the twin of the Android StorageRoots layout, rooted
 * at %LOCALAPPDATA%\PocketRealm (per-user, no elevation, no Program Files
 * write issues). Subdirectory names mirror the Android StorageRoots contract
 * so shared policies that reason about layout (import generations, run
 * artifacts) keep their shape.
 */
class DesktopStorageRoots(baseDir: File? = null) {
    val root: File = baseDir ?: defaultRoot()

    /** Durable supervisor journal + recovery records. */
    val realm: File = File(root, "realm")

    /** Seeded realm databases (the SQLite provider's datadir lives under
     * here, mirroring the Android database root layout). */
    val database: File = File(root, "database")

    /** Imported client content + prepared DBC/maps data. */
    val content: File = File(root, "content")

    /** Runtime working state (conf files, logs, run markers). */
    val runtime: File = File(root, "runtime")

    /** Long-lived user settings. */
    val settings: File = File(root, "settings")

    val settingsFile: File = File(settings, "settings.json")

    val supervisorJournalDir: File = File(realm, "runtime-supervisor")

    val logs: File = File(runtime, "logs")

    /** The SQLite provider's datadir (the shared control plane's
     * SQLITE_DATADIR_NAME under the database root). */
    val sqliteDatadir: File = File(database, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME)

    fun ensureDirectories() {
        listOf(root, realm, database, content, runtime, settings, supervisorJournalDir, logs)
            .forEach { it.mkdirs() }
    }

    companion object {
        fun defaultRoot(): File = File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
            "PocketRealm",
        )
    }
}
