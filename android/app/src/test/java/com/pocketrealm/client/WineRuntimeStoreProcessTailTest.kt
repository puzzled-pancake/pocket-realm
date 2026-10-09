package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * R4-2 (android-core review round 2): the prep-child run discipline. The
 * old wait-then-drain order deadlocked any child whose merged output
 * exceeded the OS pipe buffer (~64 KiB) — the child blocked on a full
 * pipe, waitFor timed out, and a healthy long-running prep failed
 * spuriously as "runtime preparation command timed out". The extracted
 * [runProcessCapturingTail] drains DURING the wait: a 1 MiB-emitting child
 * completes within the timeout and its output tail is captured.
 */
class WineRuntimeStoreProcessTailTest {

    /** A child command emitting [bytes] bytes to merged stdout+stderr. */
    private fun emitCommand(bytes: Int, target: File): List<String> {
        target.writeText(ByteArray(bytes) { 'x'.code.toByte() }.toString(Charsets.ISO_8859_1))
        return if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd", "/c", "type", target.absolutePath)
        } else {
            listOf("cat", target.absolutePath)
        }
    }

    @Test
    fun `a child emitting 1 MiB completes within the timeout with its tail captured`() {
        val workingDir = File(".")
        val payload = File.createTempFile("r4-2-payload", ".txt")
        try {
            val command = emitCommand(1024 * 1024, payload)
            val tail = runProcessCapturingTail(
                command, workingDir, timeoutSeconds = 60,
            )
            assertEquals(2_000, tail.length)
            assertTrue(
                "the tail must carry the child's output (its last bytes)",
                tail.contains("x"),
            )
        } finally {
            payload.delete()
        }
    }

    @Test
    fun `a nonzero child fails with its output tail for diagnosis`() {
        val workingDir = File(".")
        val payload = File.createTempFile("r4-2-fail", ".txt")
        try {
            payload.writeText("the failure context line")
            val command = if (
                System.getProperty("os.name").lowercase().contains("windows")
            ) {
                listOf("cmd", "/c", "exit", "3")
            } else {
                listOf("sh", "-c", "echo context; exit 3")
            }
            val failure = assertThrows(IllegalStateException::class.java) {
                runProcessCapturingTail(command, workingDir, timeoutSeconds = 60)
            }
            assertTrue("runtime preparation command failed" in failure.message!!)
        } finally {
            payload.delete()
        }
    }

    @Test
    fun `a timed-out child is destroyed and names the command`() {
        val workingDir = File(".")
        // a child that genuinely outlives a 1-second timeout (cmd's timeout
        // requires interactive stdin and exits immediately under pipes)
        val command = if (
            System.getProperty("os.name").lowercase().contains("windows")
        ) {
            listOf("ping", "-n", "30", "127.0.0.1")
        } else {
            listOf("sleep", "30")
        }
        val failure = assertThrows(IllegalStateException::class.java) {
            runProcessCapturingTail(command, workingDir, timeoutSeconds = 1)
        }
        assertTrue(
            "the timeout refusal names the command",
            failure.message!!.contains("timed out"),
        )
    }
}
