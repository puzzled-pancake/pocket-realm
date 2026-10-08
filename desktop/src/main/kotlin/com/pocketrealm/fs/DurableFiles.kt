package com.pocketrealm.fs

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop JVM twin of the Android `fs/DurableFiles` (same package + name;
 * the Android original is android-coupled through `android.system.Os` and
 * `android.os.Build`, so it stays unshared). Same temp + fsync + atomic-move
 * publication contract; the one semantic difference is directory fsync —
 * the JVM has no portable directory fd, so [syncDirectory] is a documented
 * best-effort no-op (Windows NTFS metadata for a rename of an fsynced temp
 * is journaled; the file-bytes durability guarantee is unchanged).
 */
object DurableFiles {

    fun atomicWrite(target: File, value: String) {
        val parent = requireNotNull(target.parentFile).apply { mkdirs() }
        val temp = File(parent, ".${target.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(parent)
        } finally {
            temp.delete()
        }
    }

    /** Binary atomic publication with the same durability contract as [atomicWrite]. */
    fun atomicCopy(source: File, target: File) {
        val parent = requireNotNull(target.parentFile).apply { mkdirs() }
        val temp = File(parent, ".${target.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            Files.move(
                temp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(parent)
        } finally {
            temp.delete()
        }
    }

    /**
     * Best-effort directory durability: the JVM cannot open a directory fd
     * (and Windows has no directory fsync at all), so this is deliberately
     * inert on the desktop lane. Kept as a named seam so call sites read
     * identically to the Android twin.
     */
    fun syncDirectory(directory: File) {
        // no-op: see the class doc; the fsynced temp carries the durability.
    }
}
