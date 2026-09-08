package com.pocketrealm.supervisor

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Atomic temp+fsync+rename journal for durable supervisor state. */
class AtomicSupervisorJournal(context: Context) : SupervisorJournal {
    private val lock = Any()
    private val directory = File(context.noBackupFilesDir, "runtime-supervisor").apply { mkdirs() }
    private val journal = File(directory, "journal.json")

    override fun read(): RuntimeSnapshot? = synchronized(lock) {
        if (!journal.isFile) return null
        try {
            RuntimeSnapshotJournalCodec.decode(JSONObject(journal.readText(Charsets.UTF_8)))
        } catch (error: Throwable) {
            RuntimeSnapshot(
                phase = RuntimePhase.ERROR,
                clean = false,
                lastDurableAction = "journal-decode-failed",
                lastError = "JOURNAL_CORRUPT: ${(error.message ?: error.javaClass.simpleName).take(384)}",
                recoverability = Recoverability.USER_ACTION_REQUIRED,
                updatedAtWallMs = System.currentTimeMillis(),
                updatedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    override fun write(snapshot: RuntimeSnapshot) = synchronized(lock) {
        require(snapshot.schema == RuntimeSnapshot.JOURNAL_SCHEMA)
        directory.mkdirs()
        val temp = File(directory, ".journal.${android.os.Process.myPid()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(RuntimeSnapshotJournalCodec.encode(snapshot).toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        // rename(2) replaces the same-directory target atomically; never
        // introduce a delete window in which the durable journal is absent.
        Os.rename(temp.absolutePath, journal.absolutePath)
        // Persist the directory entry as well as the file contents.
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    fun fileForTest(): File = journal
}
