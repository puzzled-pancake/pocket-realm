package com.pocketrealm.ingame

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-process mutual exclusion for in-game settings file writes
 * (docs/INGAME_SETTINGS_PLAN_2026-08-16.md §5.3). Taken exclusively by the
 * editor around each read-modify-write and exclusively by
 * `enforceManagedSafeMode` around its Config/uvar/bindings write phase.
 *
 * The lock file lives at the **stable client root**
 * (`noBackupFilesDir/client/`, beside `.generation-publication.lock`),
 * never inside a generation — generations are deleted on activation retire
 * and a lock file inside one could vanish between acquisition and use.
 * Acquisition order is fixed everywhere: shared `ClientGenerationLease`
 * first, then this lock, so no lock-order cycle exists.
 */
internal class InGameSettingsEditLock private constructor(
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isHeld: Boolean get() = !closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }

    companion object {
        const val LOCK_FILE_NAME = ".ingame-settings-edit.lock"

        private val monitor = Any()
        private var heldChannel: FileChannel? = null
        private var heldLock: FileLock? = null
        private var references = 0

        /**
         * Blocking exclusive acquisition. Reentrant within this process
         * (both production users are distinct processes; the refcount keeps
         * unit tests and any future same-process nesting correct).
         */
        fun acquire(clientRoot: File): InGameSettingsEditLock = synchronized(monitor) {
            if (references > 0) {
                references++
                return InGameSettingsEditLock { release() }
            }
            val root = clientRoot.canonicalFile.apply { mkdirs() }
            check(root.isDirectory) { "managed client root is unavailable" }
            val lockFile = File(root, LOCK_FILE_NAME).also { file ->
                if (!file.exists()) file.createNewFile()
                file.setReadable(false, false)
                file.setWritable(false, false)
                check(file.setReadable(true, true) && file.setWritable(true, true)) {
                    "in-game settings edit lock permissions could not be restricted"
                }
            }
            val file = RandomAccessFile(lockFile, "rw")
            try {
                val channel = file.channel
                val lock = try {
                    channel.lock()
                } catch (_: OverlappingFileLockException) {
                    // Same-JVM overlap: another holder exists; wait is not
                    // expressible here, so fail fast with a clear cause.
                    file.close()
                    error("in-game settings edit lock is already held in this process")
                }
                heldChannel = channel
                heldLock = lock
                references = 1
                InGameSettingsEditLock { release() }
            } catch (error: Throwable) {
                runCatching { file.close() }
                throw error
            }
        }

        private fun release() = synchronized(monitor) {
            references--
            if (references <= 0) {
                references = 0
                runCatching { heldLock?.release() }
                runCatching { heldChannel?.close() }
                heldLock = null
                heldChannel = null
            }
        }
    }
}
