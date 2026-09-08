package com.pocketrealm.desktop

import com.pocketrealm.supervisor.AndroidRuntimeClock
import com.pocketrealm.supervisor.Recoverability
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import com.pocketrealm.supervisor.RuntimeSnapshotJournalCodec
import com.pocketrealm.supervisor.SupervisorJournal
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * Desktop twin of AtomicSupervisorJournal: identical durable semantics on the
 * JVM — same shared codec (single source of truth for the format), temp +
 * fsync + atomic rename, and the same corrupt-file posture (a decode failure
 * materializes an ERROR/dirty snapshot so the supervisor's one-attempt
 * recovery owns the verdict instead of the journal throwing).
 *
 * fsync discipline mirrors the Android journal where Windows allows it:
 * file contents are forced before the atomic rename. The Android journal's
 * directory-entry fsync has no Windows equivalent (directories cannot be
 * opened through FileChannel; NTFS journals the rename itself).
 */
class DesktopSupervisorJournal(private val directory: File) : SupervisorJournal {
    private val lock = Any()
    private val journal = File(directory, "journal.json")

    init {
        directory.mkdirs()
    }

    // Corrupt-file posture mirrors the Android journal: any decode failure
    // materializes an ERROR/dirty snapshot instead of throwing.
    @Suppress("TooGenericExceptionCaught")
    override fun read(): RuntimeSnapshot? = synchronized(lock) {
        if (!journal.isFile) return null
        try {
            RuntimeSnapshotJournalCodec.decode(JSONObject(journal.readText(Charsets.UTF_8)))
        } catch (error: Throwable) {
            RuntimeSnapshot(
                phase = RuntimePhase.ERROR,
                clean = false,
                lastDurableAction = "journal-decode-failed",
                lastError = "JOURNAL_CORRUPT: ${
                    (error.message ?: error.javaClass.simpleName).take(CORRUPT_DETAIL_MAX)
                }",
                recoverability = Recoverability.USER_ACTION_REQUIRED,
                updatedAtWallMs = System.currentTimeMillis(),
                updatedAtElapsedMs = AndroidRuntimeClock.elapsedMs(),
            )
        }
    }

    override fun write(snapshot: RuntimeSnapshot) {
        synchronized(lock) {
            require(snapshot.schema == RuntimeSnapshot.JOURNAL_SCHEMA)
            directory.mkdirs()
            val temp = File(directory, ".journal.${ProcessHandle.current().pid()}.tmp")
            FileOutputStream(temp).use { stream ->
                stream.write(
                    RuntimeSnapshotJournalCodec.encode(snapshot).toString().toByteArray(Charsets.UTF_8),
                )
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    journal.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // No directory-entry fsync: Windows cannot open a directory
            // through FileChannel and NTFS journals the rename itself; the
            // contents were forced above, before the move.
        }
    }

    fun fileForTest(): File = journal

    private companion object {
        const val CORRUPT_DETAIL_MAX = 384
    }
}
