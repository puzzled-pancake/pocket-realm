package com.pocketrealm.desktop.realmstatus

import java.io.File

/**
 * Realm-status browser (design realm-status-browser.md §7): the
 * `uptime.recordSeconds` producer — the longest world session so far,
 * host-persisted. No producer existed in the tree (the design's round-2
 * note), so this is it: a small record store updated on BOTH stop flavors.
 *
 *  - GRACEFUL: the controller's state watcher sees the world component
 *    leave its READY/running lifecycle and folds
 *    `now − components[WORLD].startedAtWallMs` into the record.
 *  - UNCLEAN (crash / hard kill): the session boundary is one world
 *    lifetime per app process (DESK-25, `worldLifetimeSpent()`); at the
 *    NEXT launch the previous process cannot fold anything — so the
 *    recovery fold reads the supervisor journal's LAST snapshot (written
 *    by the dead process) and credits
 *    `updatedAtWallMs − startedAtWallMs` for a world that was still up in
 *    it. Crash-prone hosts therefore keep a real record instead of 0
 *    forever (the design's explicit requirement).
 *
 * Diffs are clamped ≥ 0 and bounded by the store's own sanity cap — a
 * corrupt journal must never poison the record (the app's journal
 * contract: corrupt input degrades, never throws).
 */
class RealmUptimeRecord(recordFile: File) {

    private val file = recordFile
    private val lock = Any()

    /** The longest session so far (seconds; 0 before any fold). */
    fun recordSeconds(): Long = synchronized(lock) { read() }

    /** Folds one finished session of [durationSeconds] into the record. */
    fun foldSession(durationSeconds: Long) {
        if (durationSeconds <= 0) return
        val candidate = durationSeconds.coerceAtMost(MAX_SESSION_SECONDS)
        synchronized(lock) {
            val current = read()
            if (candidate > current) write(candidate)
        }
    }

    /** The recovery fold: the previous process's last journal snapshot
     * shows a world still up — credit the crash-terminated session.
     * [journalLastSnapshot] is the desktop supervisor journal's read(). */
    @Suppress("ReturnCount", "MagicNumber", "MaxLineLength") // the fail-soft guard chain IS the corrupt-journal contract; ms→s is the unit math
    fun foldUncleanFromSnapshot(snapshot: com.pocketrealm.supervisor.RuntimeSnapshot?) {
        val world = snapshot?.components?.get(com.pocketrealm.supervisor.RuntimeComponent.WORLD)
            ?: return
        val startedAt = world.startedAtWallMs ?: return
        val liveAt = snapshot.updatedAtWallMs
        if (liveAt <= startedAt) return
        val stillUp = world.state == com.pocketrealm.supervisor.ComponentLifecycle.READY ||
            world.state == com.pocketrealm.supervisor.ComponentLifecycle.STARTING
        if (!stillUp) return
        foldSession((liveAt - startedAt) / 1_000L)
    }

    private fun read(): Long = runCatching {
        if (!file.isFile) return 0L
        val value = org.json.JSONObject(file.readText())
        if (value.optInt("schema", 0) != SCHEMA) return 0L
        value.optLong("recordSeconds", 0L).coerceIn(0L, MAX_SESSION_SECONDS)
    }.getOrDefault(0L)

    private fun write(seconds: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.${ProcessHandle.current().pid()}.tmp")
            temp.writeText(
                org.json.JSONObject()
                    .put("schema", SCHEMA)
                    .put("recordSeconds", seconds)
                    .put("updatedAt", System.currentTimeMillis())
                    .toString(),
            )
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    companion object {
        private const val SCHEMA = 1

        /** The sanity cap (30 days): a corrupt clock/journal can never
         * poison the record past belief. */
        const val MAX_SESSION_SECONDS = 30L * 24 * 3_600
    }
}
