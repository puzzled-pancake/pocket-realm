package com.pocketrealm.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Android twin of DesktopServerRuntimeFilesTest's worldlog-rotation
 * pins (its oversized-rotation leg and its failed-rotation legs 1-3).
 * The android ServerRuntimeFiles copy is NOT in shared-sources.json, so
 * the ported never-delete-then-rename rotation carries its own pins in
 * this unit set. Pure JVM: the rotation core is the pure companion seam
 * ([ServerRuntimeFiles.rotateRestartLogBytes]) the instance lane feeds —
 * no app context required.
 *
 * The locked-file legs hold a plain FileInputStream: on Windows a file
 * open without FILE_SHARE_DELETE refuses both renames onto it and away
 * from it — exactly the antivirus/indexer hold that destroyed a 77.8 MB
 * world.log.1 under the old delete-then-rename (reexec-1).
 */
class ServerRuntimeFilesRotationTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The instance lane's MAX_NORMAL_LOG_BYTES default, passed explicitly. */
    private val maximumBytes = 4L * 1024L * 1024L

    @Test
    fun oversizedPreviousSessionLogRotatesExactlyOnce() {
        val logs = folder.newFolder("logs")
        val world = File(logs, "world.log").apply { writeText("x".repeat(5 * 1024 * 1024)) }
        File(logs, "world.log.1").writeText("previous")
        ServerRuntimeFiles.rotateRestartLogBytes(world, maximumBytes, pid = 4242)
        // The oversized live log takes the .1 slot atomically (the incumbent
        // is REPLACED, never retired first — the failed-rotation pins below
        // hold that line), and the native writer recreates world.log on start.
        val rotated = File(logs, "world.log.1")
        assertTrue(rotated.isFile)
        assertEquals(5L * 1024L * 1024L, rotated.length())
        assertTrue(!world.isFile)
        assertTrue(
            "no stranded temp may survive a completed rotation: ${logs.list()?.toList()}",
            logs.list()!!.none { it.endsWith(".tmp") },
        )
    }

    /**
     * worldlog-rotation pin, leg 1 — the archive survives a FAILED rotation.
     * A locked archive slot (the antivirus/indexer hold that destroyed a
     * 77.8 MB world.log.1 under the old delete-then-rename in reexec-1)
     * refuses the rotation with the pinned WRONG_STATE code and leaves the
     * incumbent archive byte-identical: the slot is never retired before
     * the successor is in hand.
     */
    @Test
    fun failedRotationPreservesTheExistingArchive() {
        val logs = folder.newFolder("logs")
        File(logs, "world.log").apply { writeText("x".repeat(5 * 1024 * 1024)) }
        val archive = File(logs, "world.log.1").apply { writeText("previous-archive") }
        java.io.FileInputStream(archive).use { _ ->
            try {
                ServerRuntimeFiles.rotateRestartLogBytes(
                    File(logs, "world.log"),
                    maximumBytes,
                    pid = 4242,
                )
                fail("a rotation whose archive slot is locked must refuse")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must carry the WRONG_STATE code: ${expected.message}",
                    "WRONG_STATE" in expected.message!!,
                )
            }
        }
        assertEquals(
            "the incumbent archive must survive the failed rotation",
            "previous-archive",
            archive.readText(),
        )
    }

    /**
     * worldlog-rotation pin, legs 2+3 — a failed rotation loses no log data
     * and every refusal surfaces WRONG_STATE. Both refusal points leave
     * every byte on disk under its honest name: an archive-move failure
     * restores the stepped-aside live log byte-identical, and a live-rename
     * failure never moves the live log at all — no delete-then-rename
     * window, no stranded temp.
     */
    @Test
    fun failedRotationKeepsTheLiveLogIntactAndRefusesWrongState() {
        val logs = folder.newFolder("logs")
        val live = File(logs, "world.log")

        // Leg A: the archive slot refuses the successor move (locked by
        // another reader, the realistic AV/indexer hold) — the live log
        // must come back under its own name, byte-identical.
        val oversized = "y".repeat(5 * 1024 * 1024)
        live.writeText(oversized)
        File(logs, "world.log.1").writeText("previous-archive")
        java.io.FileInputStream(File(logs, "world.log.1")).use { _ ->
            try {
                ServerRuntimeFiles.rotateRestartLogBytes(live, maximumBytes, pid = 4242)
                fail("the locked archive slot must refuse the rotation")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must carry the WRONG_STATE code: ${expected.message}",
                    "WRONG_STATE" in expected.message!!,
                )
            }
        }
        assertEquals(
            "the live log must be restored byte-identical",
            oversized,
            live.readText(),
        )
        assertTrue(
            "no stranded temp may survive a refused rotation: ${logs.list()?.toList()}",
            logs.list()!!.none { it.endsWith(".tmp") },
        )
        assertEquals(
            "the archive the refused rotation kept must be intact too",
            "previous-archive",
            File(logs, "world.log.1").readText(),
        )

        // Leg B: the live log itself refuses to step aside (locked) —
        // nothing was renamed away, so world.log must sit exactly as the
        // native writer left it, under its own name.
        val locked = "z".repeat(5 * 1024 * 1024)
        live.writeText(locked)
        java.io.FileInputStream(live).use { _ ->
            try {
                ServerRuntimeFiles.rotateRestartLogBytes(live, maximumBytes, pid = 4242)
                fail("a rotation whose live log is locked must refuse")
            } catch (expected: IllegalStateException) {
                assertTrue(
                    "refusal must carry the WRONG_STATE code: ${expected.message}",
                    "WRONG_STATE" in expected.message!!,
                )
            }
        }
        assertEquals(
            "the locked live log must be untouched",
            locked,
            live.readText(),
        )
    }
}
