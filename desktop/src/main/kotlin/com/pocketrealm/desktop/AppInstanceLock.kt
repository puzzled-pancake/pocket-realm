package com.pocketrealm.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets

/**
 * Raised by [AppInstanceLock.acquire] when a second app instance tries to
 * start while the first still holds the single-owner lock. Carries the
 * holder's pid (stamped into the lock file by the winner) so the refusal
 * dialog can name it, and never a second supervisor/backend: the app model
 * throws before any runtime seam is constructed.
 */
class AppInstanceLockHeld(
    val holderPid: Int?,
    val lockFile: File,
) : IllegalStateException(
    "Pocket Realm is already running" + (holderPid?.let { " (pid $it)" } ?: "") +
        "; single-instance lock held: $lockFile",
)

/**
 * The desktop single-owner runtime domain guard (the Android ownership
 * discipline's process-level twin): one exclusive OS file lock on
 * `<roots.realm>/app-instance.lock` held for the whole process lifetime.
 *
 * Both instances share `%LOCALAPPDATA%\PocketRealm`, so a second app would
 * atomically replace the same supervisor journal (last-writer-wins) and run
 * its recovery guards against its own empty per-process state while the
 * first instance's natives still run — the corruption window this lock
 * closes. The lock is an OS byte-range lock, not a marker file: a crashed
 * or taskkilled instance releases it when the process dies, so there is no
 * stale-lock recovery path to get wrong.
 *
 * Layout: the LOCK is the first byte; the holder's pid follows it.
 * Windows denies reads of a byte range another process has locked
 * (ERROR_LOCK_VIOLATION) — a whole-file lock therefore made the pid
 * unreadable by the refused instance exactly when the refusal dialog needs
 * it. Locking one byte keeps the pid region readable by anyone, while the
 * lock itself stays exclusive. The winner truncates and rewrites the
 * payload, so a previous owner's longer pid never leaves stale digits.
 */
internal class AppInstanceLock private constructor(
    private val file: File,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        // Release is best-effort: the OS drops the lock at channel close and
        // at process death either way.
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        const val FILE_NAME = "app-instance.lock"

        /** The locked byte range (the sentinel byte; see the class doc). */
        private const val LOCK_RANGE_BYTES = 1L
        private const val SENTINEL = "L"

        /** Acquires the single-instance lock under [directory], throwing
         * [AppInstanceLockHeld] (naming the holder pid when legible) when
         * another live instance holds it. OverlappingFileLockException (a
         * second channel of THIS jvm on the already-locked range throws
         * instead of returning null) maps to the same held verdict. */
        fun acquire(directory: File): AppInstanceLock {
            directory.mkdirs()
            val file = File(directory, FILE_NAME)
            val channel = RandomAccessFile(file, "rw").channel
            val lock = try {
                channel.tryLock(0, LOCK_RANGE_BYTES, false)
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                val holder = heldPid(directory)
                channel.close()
                throw AppInstanceLockHeld(holder, file)
            }
            channel.truncate(0)
            channel.write(
                ByteBuffer.wrap(
                    (SENTINEL + ProcessHandle.current().pid())
                        .toByteArray(StandardCharsets.UTF_8),
                ),
            )
            return AppInstanceLock(file, channel, lock)
        }

        /** The pid the current holder stamped (read from a fresh read-only
         * handle over the never-locked region), or null when the payload is
         * absent or unreadable. */
        fun heldPid(directory: File): Int? = runCatching {
            RandomAccessFile(File(directory, FILE_NAME), "r").use { random ->
                random.seek(SENTINEL.length.toLong())
                val size = (random.length() - SENTINEL.length.toLong()).coerceAtLeast(0).toInt()
                val payload = ByteArray(size)
                random.readFully(payload)
                String(payload, StandardCharsets.UTF_8).trim().toIntOrNull()
            }
        }.getOrNull()
    }
}
