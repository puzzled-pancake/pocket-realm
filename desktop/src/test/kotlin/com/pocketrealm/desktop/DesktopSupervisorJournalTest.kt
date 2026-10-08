package com.pocketrealm.desktop

import com.pocketrealm.supervisor.Recoverability
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import com.pocketrealm.supervisor.RuntimeSnapshotJournalCodec
import java.nio.file.Files
import org.json.JSONObject
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
 * corrupt-file ERROR/dirty posture (never a throw) that is also durable and
 * loud (LVF-server-db-007: the ERROR/dirty snapshot is persisted back
 * through write() and logged, so recovery never resets the history
 * silently), atomic replace leaves no temp debris.
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

        // LVF-server-db-007: the materialization is DURABLE — the ERROR/dirty
        // snapshot was written back through write(), so the on-disk journal
        // carries the corruption evidence and a recovery re-read sees the
        // honest state instead of the corrupt bytes (never a silent reset).
        val persistedText = journal.fileForTest().readText(Charsets.UTF_8)
        assertTrue(persistedText.contains("JOURNAL_CORRUPT"))
        val persisted = RuntimeSnapshotJournalCodec.decode(JSONObject(persistedText))
        assertEquals(RuntimePhase.ERROR, persisted.phase)
        assertTrue(!persisted.clean)
        assertEquals("journal-decode-failed", persisted.lastDurableAction)
        assertEquals(read.lastError, persisted.lastError)
        assertEquals(Recoverability.USER_ACTION_REQUIRED, persisted.recoverability)
        val reread = journal.read()
        assertNotNull(reread)
        assertEquals(RuntimePhase.ERROR, reread!!.phase)
        assertTrue(!reread.clean)
        assertEquals(persisted.lastError, reread.lastError)

        // And the corruption is LOUD: an ERROR line carrying the
        // JOURNAL_CORRUPT detail landed in the log ring.
        assertTrue(
            DesktopLog.snapshot().any { line ->
                line.level == "ERROR" && line.kind == "SupJournal" &&
                    line.message.contains("JOURNAL_CORRUPT")
            },
        )

        // The durable rewrite went through the same atomic path: no debris.
        val leftovers = dir.listFiles { file -> file.name.endsWith(".tmp") }
        assertTrue(leftovers!!.isEmpty())
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
