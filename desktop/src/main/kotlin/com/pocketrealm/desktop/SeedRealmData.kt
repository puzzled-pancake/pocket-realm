package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.database.DesktopSqliteSeeder
import java.io.File
import java.util.UUID

/**
 * Phase-3 bring-up entry point: seed the four realm databases from the
 * pinned transcripts into the desktop storage roots, with every pin and
 * integrity gate the Android first boot applies. Run from desktop/:
 *
 *   gradlew seedRealmData [-PstagingRoot=<realm-staging-sqlite dir>]
 *
 * The staging root defaults to the o09 staging layout next to the repo
 * (transcripts under assets, one per database, plus the
 * BUILD_PROVENANCE.json pins file); the datadir defaults to
 * %LOCALAPPDATA%\PocketRealm\database\sqlite-datadir. Re-seeding an
 * already-seeded datadir refuses loudly (delete it first when a fresh
 * generation is genuinely wanted).
 */
fun main(args: Array<String>) {
    val stagingRoot = File(args.getOrElse(0) {
        "../native/.build-o09-x86_64/realm-staging-sqlite"
    }).normalize()
    val seeder = DesktopSqliteSeeder(
        seedDir = File(stagingRoot, "assets"),
        provenanceFile = File(stagingRoot, "BUILD_PROVENANCE.json"),
    )
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()

    val result = seeder.seedAll(UUID.randomUUID().toString(), roots.sqliteDatadir)
    println(result.toString(2))

    for (database in DATABASES) {
        val file = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database)
        val statements = result.getJSONObject("databases")
            .getJSONObject(database).getLong("statements")
        DesktopSqliteConnection(file.absolutePath).use { db ->
            val tables = db.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE type='table';")
            println("$database: ${file.length()} bytes on disk, $tables tables ($statements statements replayed)")
        }
    }
}
