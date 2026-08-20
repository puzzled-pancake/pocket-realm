package com.pocketrealm.fs

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable same-filesystem publication (the fsync-less
 * temp+rename pattern was copy-pasted into 7+ files; this is the
 * DatabaseDurability contract generalized for everyone).
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

    fun syncDirectory(directory: File) {
        val openFlags = OsConstants.O_RDONLY or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) OsConstants.O_CLOEXEC else 0
        val fd = Os.open(
            directory.absolutePath,
            openFlags,
            0,
        )
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
