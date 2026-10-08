package com.pocketrealm.database

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop twin of the Android DatabaseDurability (same package + name so the
 * shared [DatabaseSnapshotStore]'s `DatabaseDurability::syncDirectory` default
 * compiles unmodified — DESK-18's "the one POSIX-flavored seam, injectable").
 *
 * DESK-5 grows the twin to the Android object's FULL durable-write surface
 * (atomicWrite/atomicMove/delete) so the [DesktopMariaDbEngine]'s control
 * records (seals, transactions, secrets, markers) publish through the same
 * discipline as the Android engine's — pid-temp + file-data fsync + atomic
 * rename — instead of ad-hoc writers.
 *
 * Windows posture: there is NO directory fsync — a directory cannot be
 * opened through a FileChannel on Windows, and NTFS journals the metadata
 * (create/rename) the same way `verifyOrRebuildSqlite` and the seeder's
 * atomic publication already assume ("No directory fsync: Windows cannot
 * open a directory through FileChannel and NTFS journals the rename").
 * File DATA durability is unchanged: every written byte still leaves through
 * `FileOutputStream.fd.sync()`.
 */
internal object DatabaseDurability {

    fun atomicWrite(target: File, value: String) {
        val parent = requireNotNull(target.parentFile).apply { mkdirs() }
        val temp = File(parent, ".${target.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveReplacing(temp, target)
            syncDirectory(parent)
        } finally {
            temp.delete()
        }
    }

    fun atomicMove(source: File, target: File) {
        check(source.parentFile == target.parentFile) {
            "DB-DURABILITY: cross-directory atomic move refused"
        }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(requireNotNull(target.parentFile))
    }

    fun delete(file: File) {
        if (!file.exists()) return
        check(file.delete()) { "DB-DURABILITY: could not delete ${file.name}" }
        syncDirectory(requireNotNull(file.parentFile))
    }

    /**
     * Deliberately a no-op on Windows/NTFS (see the object comment); kept as
     * a named seam so call sites and tests stay platform-honest — the shared
     * store's tests inject a recording lambda here.
     */
    @Suppress("UnusedParameter") // the named seam's signature IS the contract (the Android twin takes the directory)
    fun syncDirectory(directory: File) {
        // No-op: NTFS journals directory metadata; see the object comment.
    }

    /** ATOMIC_MOVE first, plain replace as the fallback for filesystems that
     * reject the atomic flag (the ServerRuntimeFiles/secureWrite posture —
     * File.renameTo cannot replace an existing target on Windows). */
    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
