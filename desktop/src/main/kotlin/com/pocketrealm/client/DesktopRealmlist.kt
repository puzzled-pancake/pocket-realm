package com.pocketrealm.client

import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.fs.FileDigests
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import java.io.FileOutputStream

/**
 * the user-owned `realmlist.wtf` handling for native Windows.
 *
 * On Android the target file is app-owned (the managed client generation),
 * so a wholesale replacement is safe. On Windows the file is user property
 * OUTSIDE the app roots and the stock install carries directives beyond the
 * one `set realmlist` line (a `set patchlist` line, comments), so the shared
 * [ClientRealmEndpointProjection] — left untouched for Android byte-parity —
 * is wrapped here instead:
 *
 *  - before the FIRST projection of a session the original bytes + sha256
 *    are backed up under [DesktopStorageRoots.runtime];
 *  - the projection MERGES: only the `set realmlist` line is rewritten (in
 *    place when present, appended when not); every other line round-trips
 *    verbatim in content and order;
 *  - graceful teardown/force-stop restores the original bytes and retires
 *    the backup, so the session's only durable change to the user's install
 *    is the one they launched against.
 *
 * The backup slot is per client directory (hashed into the backup file name)
 * and never overwritten once taken: a subsequent session keeps restoring the
 * SAME original until a successful restore retires it.
 */
internal object DesktopRealmlist {

    private const val TARGET_NAME = "realmlist.wtf"
    private const val BACKUP_DIR_NAME = "realmlist-backup"

    /** One session's backup slot: where the original bytes live and the
     * sha256 file that authenticates them. */
    data class Backup(val file: File, val sha256File: File) {
        internal fun retire() {
            file.delete()
            sha256File.delete()
        }
    }

    /**
     * The session's projection entry point: backs the user's file up (first
     * projection only) and merges [endpoint]'s `set realmlist` line over the
     * current content. A missing realmlist.wtf (some fresh installs) has
     * nothing to preserve: the file is created with just the projected line
     * and no backup slot is taken. Every projection failure is an honest
     * throw — the launch refuses rather than starting a client pointed at
     * stale topology.
     */
    fun projectForSession(
        clientDir: File,
        endpoint: RealmEndpoint,
        roots: DesktopStorageRoots,
        session: Session,
    ) {
        val target = File(clientDir, TARGET_NAME)
        if (!target.isFile) {
            merge(target, endpoint)
        } else {
            if (session.backup == null) {
                session.backup = takeBackup(target, clientDir, roots)
            }
            merge(target, endpoint)
        }
        // WotLK 3.3.5a reads Data/<locale>/realmlist.wtf, not the root file:
        // each locale copy is backed up (or recorded as created) and merged.
        for (locale in ClientRealmEndpointProjection.localeRealmlists(clientDir)) {
            if (locale.isFile) {
                if (locale.absolutePath !in session.localeBackups) {
                    session.localeBackups[locale.absolutePath] = takeBackup(locale, locale.parentFile, roots)
                }
            } else if (locale.absolutePath !in session.created) {
                session.created += locale.absolutePath
            }
            merge(locale, endpoint)
        }
    }

    /**
     * Restores the session's original bytes over the projected file and
     * retires the backup. Best-effort by contract: a missing backup (nothing
     * projected this session) or an already-restored slot reports
     * "not restored" without throwing into the teardown path.
     */
    fun restoreForSession(clientDir: File, roots: DesktopStorageRoots, session: Session): Boolean {
        restoreLocaleFiles(session)
        val backup = session.backup ?: return false
        session.backup = null
        val target = File(clientDir, TARGET_NAME)
        return runCatching {
            val recordedSha = backup.sha256File.readText(Charsets.UTF_8).trim()
            check(backup.file.isFile && FileDigests.sha256(backup.file) == recordedSha) {
                "realmlist backup failed its sha256 re-verification"
            }
            replaceAtomic(target, backup.file.readBytes())
            backup.retire()
            true
        }.getOrElse { failure ->
            com.pocketrealm.desktop.DesktopLog.w(
                LOG_TAG,
                "realmlist restore failed: ${failure.message}",
            )
            false
        }
    }

    /** Restores each backed-up locale realmlist and deletes the ones this
     * session created. Best-effort, like the root restore. */
    private fun restoreLocaleFiles(session: Session) {
        for ((path, backup) in session.localeBackups) {
            runCatching {
                val recordedSha = backup.sha256File.readText(Charsets.UTF_8).trim()
                check(backup.file.isFile && FileDigests.sha256(backup.file) == recordedSha) {
                    "locale realmlist backup failed its sha256 re-verification"
                }
                replaceAtomic(File(path), backup.file.readBytes())
                backup.retire()
            }.onFailure { failure ->
                com.pocketrealm.desktop.DesktopLog.w(LOG_TAG, "locale realmlist restore failed: ${failure.message}")
            }
        }
        session.localeBackups.clear()
        session.created.forEach { runCatching { File(it).delete() } }
        session.created.clear()
    }

    /**
     * The merge writer: the `set realmlist <addr>` line is replaced in place
     * (first occurrence) or appended; all other lines round-trip verbatim
     * (US-ASCII, CRLF terminators — the client's own file format).
     */
    private fun merge(target: File, endpoint: RealmEndpoint) {
        val current = if (target.isFile) target.readText(Charsets.US_ASCII) else ""
        val replacement = "set realmlist ${endpoint.address}"
        var replaced = false
        val merged = current.lineSequence()
            .map { line ->
                if (!replaced && line.trimStart().startsWith("set realmlist")) {
                    replaced = true
                    replacement
                } else {
                    line
                }
            }
            .joinToString("\r\n") + (if (current.isEmpty()) "" else "\r\n")
        val text = if (replaced) merged else "$merged$replacement\r\n"
        replaceAtomic(target, text.toByteArray(Charsets.US_ASCII))
    }

    /** First-projection backup: copy + sha256 sidecar under the runtime root. */
    private fun takeBackup(
        target: File,
        clientDir: File,
        roots: DesktopStorageRoots,
    ): Backup {
        val bytes = target.readBytes()
        val dir = File(roots.runtime, BACKUP_DIR_NAME).apply { mkdirs() }
        val key = FileDigests.sha256(clientDir.absolutePath.lowercase()).take(16)
        val backup = Backup(File(dir, "$TARGET_NAME.$key.orig"), File(dir, "$TARGET_NAME.$key.sha256"))
        if (!backup.file.isFile || !backup.sha256File.isFile) {
            // A partial slot (crash between the two writes) is retaken.
            backup.file.delete()
            backup.sha256File.delete()
            replaceAtomic(backup.file, bytes)
            replaceAtomic(
                backup.sha256File,
                FileDigests.sha256(backup.file).toByteArray(Charsets.UTF_8),
            )
        }
        return backup
    }

    private fun replaceAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    /** Per-launch-session state the backend owns and hands back on teardown. */
    class Session {
        internal var backup: Backup? = null
        /** Locale realmlists backed up this session, by absolute path. */
        internal val localeBackups: MutableMap<String, Backup> = linkedMapOf()
        /** Locale realmlists this session created (deleted on restore). */
        internal val created: MutableList<String> = mutableListOf()
    }

    private const val LOG_TAG = "Client"
}
