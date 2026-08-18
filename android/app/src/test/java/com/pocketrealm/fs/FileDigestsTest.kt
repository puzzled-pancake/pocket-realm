package com.pocketrealm.fs

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM-verifiable contract for the shared digest. */
class FileDigestsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun reference(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `file digest matches reference for empty, small, and multi-chunk inputs`() {
        for (size in listOf(0, 1, 1024 * 1024 - 1, 1024 * 1024, 1024 * 1024 + 7)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val file = tmp.newFile("blob-$size")
            file.writeBytes(payload)
            assertEquals("size $size", reference(payload), FileDigests.sha256(file))
        }
    }

    @Test fun `text digest is utf-8 lowercase hex`() {
        assertEquals(
            reference("pocket realm".toByteArray(Charsets.UTF_8)),
            FileDigests.sha256("pocket realm"),
        )
        // Known-answer: sha256("") hex.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FileDigests.sha256(""),
        )
    }

    @Test fun `path and string overloads are distinct and stable`() {
        val file = tmp.newFile("same.txt")
        file.writeText("same bytes")
        assertEquals(FileDigests.sha256(file), FileDigests.sha256("same bytes"))
    }
}
