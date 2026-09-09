package com.pocketrealm.database

import com.pocketrealm.database.DatabaseSqliteControlPlane.DATABASES
import com.pocketrealm.database.DatabaseSqliteControlPlane.INTEGRITY_CHECK
import com.pocketrealm.database.DatabaseSqliteControlPlane.INTEGRITY_OK
import com.pocketrealm.database.DatabaseSqliteControlPlane.QUICK_CHECK
import com.pocketrealm.database.DatabaseSqliteControlPlane.SeedStatementScanner
import com.pocketrealm.database.DatabaseSqliteConfigPolicy.renderConnectionPragmas
import com.pocketrealm.database.DatabaseUserStateBridge.Importer.partialFor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Desktop twin of the engine's seed-replay leg (DatabaseEngine.
 * seedSqliteDatadir / replaySeedDatabase), executing through the
 * DesktopSqlite seam with the exact Android discipline:
 *
 *  - transcripts verified against the BUILD_PROVENANCE pins (gzip
 *    bytes, decompressed bytes, decompressed size — the device replay
 *    is itself a pin check, not just a copy);
 *  - one `<db>.sqlite3.partial` per database, connection pragmas from
 *    the shared [DatabaseSqliteConfigPolicy], single replay
 *    transaction, quick_check + integrity_check both exactly "ok",
 *    wal_checkpoint(TRUNCATE), no WAL sidecars after close, then the
 *    atomic partial→live rename;
 *  - the generation marker (`.pocketrealm-generation.json`, same
 *    schema as the Android DatabaseDurableState marker) written once
 *    per fresh datadir.
 *
 * Differences from Android, both documented posture: no directory-entry
 * fsync (Windows cannot open a directory through FileChannel; NTFS
 * journals the rename — same stance as the desktop supervisor journal
 * twin), and the meta migration ledger + revision verification land
 * with the full engine twin (the servers only need the four seeded
 * databases + marker to boot).
 *
 * Single-thread by contract (SQLITE_THREADSAFE=2): the caller drives
 * seeding from its database thread.
 */
internal class DesktopSqliteSeeder(
    private val seedDir: File,
    private val provenanceFile: File,
) {
    data class SeedPin(
        val database: String,
        val sha256: String,
        val gzipSha256: String,
        val size: Long,
        val gzipSize: Long,
    )

    private data class ReplayAccounting(val executed: Int, val rawBytes: Long)

    /** The four transcripts' pins from BUILD_PROVENANCE.json — fail-loud
     * when any database is unpinned (a transcript without a pin would
     * replay unverified, which this lane never allows). */
    fun provenancePins(): Map<String, SeedPin> {
        val root = JSONObject(provenanceFile.readText(StandardCharsets.UTF_8))
        val transcripts = root.getJSONObject("seed_transcripts")
        return DATABASES.associateWith { database ->
            val pin = transcripts.getJSONObject(database)
            SeedPin(
                database = database,
                sha256 = pin.getString("sha256"),
                gzipSha256 = pin.getString("gzip_sha256"),
                size = pin.getLong("size"),
                gzipSize = pin.getLong("gzip_size"),
            )
        }
    }

    /** Seed every database into [sqliteDatadir]. Returns the per-database
     * replay record (statement count + published byte size). */
    fun seedAll(generationUuid: String, sqliteDatadir: File): JSONObject {
        require(UUID_REGEX.matches(generationUuid)) { "not a generation uuid: $generationUuid" }
        val pins = provenancePins()
        check(DATABASES.all { database ->
            !DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database).isFile
        }) { "DB-SEED: datadir already holds seeded databases: $sqliteDatadir" }
        sqliteDatadir.mkdirs()
        writeGenerationMarker(sqliteDatadir, generationUuid)
        val perDatabase = JSONObject()
        for (database in DATABASES) {
            perDatabase.put(database, replayDatabase(sqliteDatadir, database, pins.getValue(database)))
        }
        return JSONObject().put("ok", true)
            .put("provider", DatabaseRuntimeContract.SQLITE_PROVIDER_ID)
            .put("generation", generationUuid)
            .put("databases", perDatabase)
    }

    /** One transcript: gunzip-verify-execute against a .partial database,
     * then atomically publish. Mirrors the Android replaySeedDatabase. */
    private fun replayDatabase(sqliteDatadir: File, database: String, pin: SeedPin): JSONObject {
        val seed = File(seedDir, DatabaseSqliteControlPlane.seedAsset(database))
        val live = DatabaseSqliteControlPlane.databaseFile(sqliteDatadir, database)
        val partial = partialFor(live)
        check(!live.exists()) { "DB-SEED: $database already exists" }
        partial.delete()
        check(seed.isFile) { "DB-SEED: $database transcript missing: $seed" }
        check(seed.length() == pin.gzipSize) { "DB-SEED: $database gzip size mismatch" }

        val gzipDigest = MessageDigest.getInstance("SHA-256")
        val rawDigest = MessageDigest.getInstance("SHA-256")
        val connection = DesktopSqliteConnection(partial.absolutePath)
        val accounting: ReplayAccounting
        try {
            // Row-returning PRAGMAs (journal_mode, wal_checkpoint) cross
            // the query entry point; every policy pragma routes through
            // it, same as the Android execPragma split.
            for (pragma in renderConnectionPragmas()) {
                connection.queryText(pragma)
            }
            accounting = replayTranscript(connection, database, seed, gzipDigest, rawDigest)
            check(hex(gzipDigest.digest()) == pin.gzipSha256) { "DB-SEED: $database gzip digest mismatch" }
            check(hex(rawDigest.digest()) == pin.sha256) { "DB-SEED: $database transcript digest mismatch" }
            check(accounting.rawBytes == pin.size) { "DB-SEED: $database transcript size mismatch" }
            integrityRequired(connection)
            connection.queryText("PRAGMA wal_checkpoint(TRUNCATE);")
        } finally {
            connection.close()
        }
        check(!File(partial.parentFile, partial.name + "-wal").exists() &&
            !File(partial.parentFile, partial.name + "-shm").exists()) {
            "DB-SEED: $database replay left WAL sidecars behind"
        }
        check(partial.renameTo(live) || (live.delete() && partial.renameTo(live))) {
            "DB-SEED: cannot publish $database"
        }
        return JSONObject().put("statements", accounting.executed).put("bytes", live.length())
    }

    /** Stream one gunzipped transcript through the shared splitter into
     * the replay transaction, updating both digest accumulators. The
     * BEGIN/COMMIT pairing is this function's; a failure rolls back so
     * the .partial never holds a half generation. */
    @Suppress("TooGenericExceptionCaught") // mirrors the Android twin: any replay failure must roll back
    private fun replayTranscript(
        connection: DesktopSqliteConnection,
        database: String,
        seed: File,
        gzipDigest: MessageDigest,
        rawDigest: MessageDigest,
    ): ReplayAccounting {
        var executed = 0
        var rawBytes = 0L
        val scanner = SeedStatementScanner()
        connection.exec("BEGIN;")
        try {
            DigestInputStream(FileInputStream(seed), gzipDigest).use { checked ->
                GZIPInputStream(checked).use { zipped ->
                    forEachUtf8Chunk(zipped) { chunk ->
                        // The pin is BYTES; a char count would under-count
                        // multibyte-dense corpus.
                        val encoded = chunk.toByteArray(Charsets.UTF_8)
                        rawDigest.update(encoded)
                        rawBytes += encoded.size.toLong()
                        for (statement in scanner.feed(chunk)) {
                            executeSeedStatement(connection, database, statement)
                            executed++
                        }
                    }
                }
            }
            scanner.finish()?.let { statement ->
                executeSeedStatement(connection, database, statement)
                executed++
            }
            connection.exec("COMMIT;")
        } catch (failure: Throwable) {
            runCatching { connection.exec("ROLLBACK;") }
            throw failure
        }
        return ReplayAccounting(executed, rawBytes)
    }

    /** Seed-statement failures carry the exact statement index + offset
     * assigned by the shared seed splitter (the Android wrapper's
     * locality contract). */
    @Suppress("TooGenericExceptionCaught") // mirrors Android executeSeedStatement: any engine failure gets locality
    private fun executeSeedStatement(
        connection: DesktopSqliteConnection,
        name: String,
        statement: DatabaseSqliteControlPlane.SeedStatement,
    ) {
        try {
            connection.exec(statement.sql)
        } catch (failure: Throwable) {
            throw IllegalStateException(
                "DB-SEED: $name statement ${statement.index} at offset ${statement.offset} failed " +
                    "(...${statement.sql.takeLast(STATEMENT_TAIL_CHARS)}): ${failure.message}",
                failure,
            )
        }
    }

    /** quick_check first (the fast pass), then integrity_check as the
     * full verdict; both must return exactly "ok" — the shared
     * control-plane contract. */
    private fun integrityRequired(connection: DesktopSqliteConnection) {
        check(connection.queryText(QUICK_CHECK) == INTEGRITY_OK) {
            "DB-SQLITE: quick_check failed"
        }
        check(connection.queryText(INTEGRITY_CHECK) == INTEGRITY_OK) {
            "DB-SQLITE: integrity_check failed"
        }
    }

    private fun forEachUtf8Chunk(stream: InputStream, block: (String) -> Unit) {
        InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            val buffer = CharArray(STREAM_BUFFER_CHARS)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) return
                block(String(buffer, 0, read))
            }
        }
    }

    private fun writeGenerationMarker(sqliteDatadir: File, generationUuid: String) {
        val marker = File(sqliteDatadir, ".pocketrealm-generation.json")
        val temp = File(sqliteDatadir, ".pocketrealm-generation.${ProcessHandle.current().pid()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(
                JSONObject()
                    .put("schema", 1)
                    .put("generationUuid", generationUuid)
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            )
            stream.fd.sync()
        }
        try {
            Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        const val STREAM_BUFFER_CHARS = 64 * 1024
        const val STATEMENT_TAIL_CHARS = 200
    }
}
