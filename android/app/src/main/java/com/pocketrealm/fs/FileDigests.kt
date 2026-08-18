package com.pocketrealm.fs

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * The one SHA-256 implementation for file content (19 files
 * previously hand-rolled their own MessageDigest loops). String digests keep
 * using their local one-liners only where they delegate here.
 */
object FileDigests {

    private const val CHUNK = 1024 * 1024

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
