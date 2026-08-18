package com.pocketrealm.database

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable same-filesystem publication primitives for database control records. */
internal object DatabaseDurability {
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

    fun atomicMove(source: File, target: File) {
        check(source.parentFile == target.parentFile) { "DB-DURABILITY: cross-directory atomic move refused" }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(requireNotNull(target.parentFile))
    }

    fun delete(file: File) {
        if (!file.exists()) return
        check(file.delete()) { "DB-DURABILITY: could not delete ${file.name}" }
        syncDirectory(requireNotNull(file.parentFile))
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
