package com.pocketrealm.desktop.realmstatus

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Realm-status browser (design realm-status-browser.md) the DURABLE
 * record of every cloudflared child this app has spawned —
 * `{pid, full command line, process start-time, spawn wall-clock}` per
 * child. The record is what makes the kill rules SAFE:
 *
 *  - killing is OWN-RECORDS-ONLY, always, and a leftover is killed only
 *    when pid AND full command line AND process start-time ALL match the
 *    record at kill time (after a reboot a recorded pid can name an
 *    unrelated process — pid reuse — so the start-time + command-line
 *    match is what makes the kill safe);
 *  - the store is shared PER INSTALL, so a process matching a LIVE record
 *    from any running instance is never announced as an orphan by another
 *    instance's sweep (the record store is consulted FIRST).
 *
 * Durability lives under the storage roots' durable `realm` area (the
 * supervisor journal's neighborhood), so a crash that orphans a child
 * leaves the record behind for the sweep's own-records kill.
 */
class TunnelChildRecords(storeFile: File) {

    data class Record(
        val pid: Long,
        val commandLine: String,
        val startUnixSeconds: Long?,
        val spawnWallMs: Long,
        val instanceId: String,
    )

    private val file = storeFile
    private val lock = Any()

    /** The live records (crash leftovers included — that is the point). */
    fun all(): List<Record> = synchronized(lock) { readAll() }

    /** Writes one spawn record (atomically replaced, the settings-store
     * posture: crash mid-write can never lose the previous records). */
    fun put(record: Record) {
        synchronized(lock) {
            val records = readAll().filterNot { it.pid == record.pid } + record
            writeAll(records)
        }
    }

    /** Removes one record (the child exited or was killed under the rules). */
    fun remove(pid: Long) {
        synchronized(lock) {
            writeAll(readAll().filterNot { it.pid == pid })
        }
    }

    /** Triple-match lookup: pid AND command line AND start-time.
     * The command-line leg compares through the shared
     * [TunnelOrphanSweep.commandLinesMatch] — the recorded line is the
     * spawn's REALIZED line, and the process table's may differ only in
     * the executable head's OS-added quoting. A record with a null start
     * time (the OS refused the query at spawn) matches on pid + command
     * line only — recorded, never invented. */
    fun matching(pid: Long, commandLine: String, startUnixSeconds: Long?, toleranceSeconds: Long = 2L): Record? =
        synchronized(lock) {
            readAll().firstOrNull { record ->
                record.pid == pid &&
                    TunnelOrphanSweep.commandLinesMatch(record.commandLine, commandLine) &&
                    (record.startUnixSeconds == null || startUnixSeconds == null ||
                        kotlin.math.abs(record.startUnixSeconds - startUnixSeconds) <= toleranceSeconds)
            }
        }

    private fun readAll(): List<Record> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val value = JSONArray(file.readText())
            (0 until value.length()).mapNotNull { index ->
                val entry = value.optJSONObject(index) ?: return@mapNotNull null
                Record(
                    pid = entry.optLong("pid", -1L),
                    commandLine = entry.optString("commandLine", ""),
                    startUnixSeconds = if (entry.isNull("startUnixSeconds")) {
                        null
                    } else {
                        entry.optLong("startUnixSeconds")
                    },
                    spawnWallMs = entry.optLong("spawnWallMs", 0L),
                    instanceId = entry.optString("instanceId", ""),
                ).takeIf { it.pid > 0 && it.commandLine.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(records: List<Record>) {
        file.parentFile?.mkdirs()
        val payload = JSONArray()
        records.forEach { record ->
            payload.put(
                JSONObject()
                    .put("pid", record.pid)
                    .put("commandLine", record.commandLine)
                    .put("startUnixSeconds", record.startUnixSeconds ?: JSONObject.NULL)
                    .put("spawnWallMs", record.spawnWallMs)
                    .put("instanceId", record.instanceId),
            )
        }
        val temp = File(file.parentFile, ".${file.name}.${ProcessHandle.current().pid()}.tmp")
        temp.writeText(payload.toString())
        java.nio.file.Files.move(
            temp.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
