package com.pocketrealm.desktop

import com.pocketrealm.supervisor.Recoverability
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Desktop journal twin contract: the same durable semantics as the Android
 * AtomicSupervisorJournal — shared codec round-trip, absent-file null,
 * corrupt-file ERROR/dirty posture (never a throw), atomic replace leaves no
 * temp debris.
 */
class DesktopSupervisorJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun snapshot(clean: Boolean = true): RuntimeSnapshot = RuntimeSnapshot(
        phase = if (clean) RuntimePhase.STOPPED else RuntimePhase.ERROR,
        clean = clean,
        lastDurableAction = "test-action",
        lastError = if (clean) null else "boom",
        recoverability = if (clean) Recoverability.NONE else Recoverability.USER_ACTION_REQUIRED,
        updatedAtWallMs = 1_700_000_000_000L,
        updatedAtElapsedMs = 42L,
    )

    @Test
    fun absentFileReadsNull() {
        val journal = DesktopSupervisorJournal(folder.newFolder())
        assertNull(journal.read())
    }

    @Test
    fun writeThenReadRoundTripsThroughTheSharedCodec() {
        val journal = DesktopSupervisorJournal(folder.newFolder())
        val original = snapshot()
        journal.write(original)
        val read = journal.read()
        assertNotNull(read)
        assertEquals(original.phase, read!!.phase)
        assertEquals(original.clean, read.clean)
        assertEquals(original.lastDurableAction, read.lastDurableAction)
        assertEquals(original.lastError, read.lastError)
        assertEquals(original.recoverability, read.recoverability)
        assertEquals(original.updatedAtWallMs, read.updatedAtWallMs)
        assertEquals(original.runtimeMode, read.runtimeMode)
    }

    @Test
    fun corruptFileMaterializesErrorDirtySnapshotNotAThrow() {
        val dir = folder.newFolder()
        val journal = DesktopSupervisorJournal(dir)
        journal.write(snapshot())
        Files.writeString(journal.fileForTest().toPath(), "{\"schema\": 2, \"components\": ")
        val read = journal.read()
        assertNotNull(read)
        assertEquals(RuntimePhase.ERROR, read!!.phase)
        assertTrue(!read.clean)
        assertTrue(read.lastError!!.startsWith("JOURNAL_CORRUPT"))
        assertEquals(Recoverability.USER_ACTION_REQUIRED, read.recoverability)
    }

    @Test
    fun rewriteReplacesAtomicallyWithoutTempDebris() {
        val dir = folder.newFolder()
        val journal = DesktopSupervisorJournal(dir)
        journal.write(snapshot(clean = true))
        journal.write(snapshot(clean = false))
        val read = journal.read()
        assertEquals(RuntimePhase.ERROR, read!!.phase)
        val leftovers = dir.listFiles { file -> file.name.endsWith(".tmp") }
        assertTrue(leftovers!!.isEmpty())
    }
}
