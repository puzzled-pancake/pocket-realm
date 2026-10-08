package com.pocketrealm.importer

import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.desktop.requireNativeIfDemanded
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-3/66: the desktop import journal twin's crash/resume contract on the
 * DesktopSqlite seam — the same begin/resume/fail/cancel state machine and
 * the same data-stage checkpoints the Android journal tests pin, WAL schema 4
 * included. Requires the seam DLL (skips cleanly when it is not built).
 */
class DesktopImportJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var rootsIndex = 0

    private fun stagedDll(): File =
        File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll").also {
            requireNativeIfDemanded("sqlite seam", it)
        }

    private fun roots(): DesktopStorageRoots =
        DesktopStorageRoots(
            File(folder.root, "roots-${rootsIndex++}").apply { mkdirs() },
        ).also { it.ensureDirectories() }

    private fun journal(roots: DesktopStorageRoots = roots()): ImportJournal {
        stagedDll()
        return ImportJournal(roots)
    }

    private fun inventory(fingerprint: String = "fp-1") = SourceInventory(
        entries = listOf(
            ImportSourceEntry("file://a", "WoW.exe", false, 4_745_728, 0, "file"),
            ImportSourceEntry("file://b", "Data/base.MPQ", false, 13_000_000, 0, "file"),
        ),
        fileCount = 2,
        totalBytes = 17_745_728,
        fingerprint = fingerprint,
    )

    @Test
    fun `a fresh begin creates a row and the reopen resume returns the same import id`() {
        val roots = roots()
        val first = journal(roots)
        val id = first.beginOrResume("file:///client", inventory())
        first.recordInventory(id, inventory().entries)
        first.markCopying(id, inventory().entries[0], ".Wow.exe.partial")
        first.markVerified(id, inventory().entries[0], "deadbeef", 4_745_728)
        first.close() // the crash: no complete, no fail

        val second = journal(roots)
        val resumed = second.beginOrResume("file:///client", inventory())
        assertEquals("the unchanged source resumes the SAME import", id, resumed)
        // The durable per-file state survived the crash.
        val file = second.file(id, "WoW.exe")
        assertTrue(file != null && file.state == ImportFileState.VERIFIED && file.fsyncMarker)
        second.close()
    }

    @Test
    fun `a changed source cancels the failed import or refuses the live one`() {
        val roots = roots()
        val first = journal(roots)
        val id = first.beginOrResume("file:///client", inventory())
        first.fail(id, "boom")
        first.close()

        val second = journal(roots)
        // A FAILED import is resumable-by-cancel: the next begin with a
        // different source cancels it and starts fresh.
        val fresh = second.beginOrResume("file:///other", inventory("fp-2"))
        assertTrue(fresh != id)
        assertEquals(ImportPhase.DISCOVERING, second.latest().phase)
        second.close()
    }

    @Test
    fun `a live import from another source refuses with SOURCE_CHANGED`() {
        val roots = roots()
        val first = journal(roots)
        first.beginOrResume("file:///client", inventory())
        try {
            first.beginOrResume("file:///different", inventory("fp-2"))
            fail("a live mismatched-source resume must refuse")
        } catch (expected: ImportRejected) {
            assertTrue(
                "expected the SOURCE_CHANGED copy: ${expected.message}",
                (expected.message ?: "").startsWith("SOURCE_CHANGED:"),
            )
        }
        first.close()
    }

    @Test
    fun `progress aggregates only verified bytes and complete pins the generation`() {
        val roots = roots()
        val first = journal(roots)
        val id = first.beginOrResume("file:///client", inventory())
        val entries = inventory().entries
        first.recordInventory(id, entries)
        first.markVerified(id, entries[0], "hash-a", 4_745_728)
        first.markSkipped(id, entries[1], "safe-mode exclusion")
        val status = first.latest()
        assertEquals(2, status.filesProcessed)
        assertEquals(4_745_728L, status.bytesCopied)
        first.complete(id, "0f0f0f0f-1111-2222-3333-444455556666")
        val done = first.latest()
        assertEquals(ImportPhase.COMPLETE, done.phase)
        assertEquals("0f0f0f0f-1111-2222-3333-444455556666", done.activeGeneration)
        assertNull(done.lastError)
        // Terminal rows are not resumable.
        assertEquals(emptySet<String>(), first.activeImportIds())
        first.close()
    }

    @Test
    fun `the archive staging lane resumes on the staged file anchor`() {
        val roots = roots()
        val first = journal(roots)
        val resume = first.beginStagingOrResume("file:///client.zip", expectedBytes = 5_000_000L)
        assertEquals(0L, resume.stagedBytes)
        first.touchStaging(resume.importId, 2_000_000)
        first.close() // crash mid-staging

        val second = journal(roots)
        val resumed = second.beginStagingOrResume("file:///client.zip", expectedBytes = 5_000_000L)
        assertEquals("the same import resumes", resume.importId, resumed.importId)
        assertEquals(2_000_000L, resumed.stagedBytes)
        // A size mismatch before staging finished must refuse (a truncated
        // re-stage would destroy the resumable partial).
        try {
            second.beginStagingOrResume("file:///client.zip", expectedBytes = 9_000_000L)
            fail("a staging size mismatch must refuse")
        } catch (expected: ImportRejected) {
            assertTrue((expected.message ?: "").startsWith("SOURCE_CHANGED:"))
        }
        second.finishStaging(resumed.importId, "/staged/client.zip", 5_000_000L, "abc")
        // After finishStaging the size check no longer applies (the staged
        // file anchors identity).
        second.beginStagingOrResume("file:///client.zip", expectedBytes = 1_000_000L)
        second.close()
    }

    @Test
    fun `data stage checkpoints round-trip in row order with attempts`() {
        val roots = roots()
        val first = journal(roots)
        val id = first.beginOrResume("file:///client", inventory())
        first.startDataStage(id, DataStage.DBC_MAPS, 0)
        first.checkpointDataStage(id, DataStage.DBC_MAPS, 1, 1, 123L, "complete", verified = true)
        first.startDataStage(id, DataStage.VMAP_EXTRACT, 0)
        first.checkpointDataStage(id, DataStage.VMAP_EXTRACT, 0, 1, 456L, "map 0 (1/2) tiles 3")
        first.close() // crash between stages

        val second = journal(roots)
        val dbc = second.dataStage(id, DataStage.DBC_MAPS)
        assertTrue(dbc != null && dbc.state == DataStageState.VERIFIED && dbc.completedAtMs != 0L)
        val vmap = second.dataStage(id, DataStage.VMAP_EXTRACT)
        assertTrue(vmap != null && vmap.state == DataStageState.RUNNING && vmap.attempt >= 1)
        assertEquals("map 0 (1/2) tiles 3", vmap?.checkpoint)
        assertEquals(
            listOf(DataStage.DBC_MAPS, DataStage.VMAP_EXTRACT),
            second.dataStages(id).map { it.stage },
        )
        // The failing stage records its error on both rows.
        second.failDataStage(id, DataStage.VMAP_EXTRACT, "extractor exited 3")
        val failed = second.dataStage(id, DataStage.VMAP_EXTRACT)
        assertEquals(DataStageState.FAILED, failed?.state)
        assertEquals(ImportPhase.FAILED, second.latest().phase)
        assertTrue(second.latest().lastError!!.startsWith("DATA_VMAP_EXTRACT:"))
        second.close()
    }

    @Test
    fun `a stage resume after a crash reuses the same row and bumps the attempt`() {
        val roots = roots()
        val first = journal(roots)
        val id = first.beginOrResume("file:///client", inventory())
        first.startDataStage(id, DataStage.MMAPS, 3)
        val firstAttempt = first.dataStage(id, DataStage.MMAPS)?.attempt
        first.close()

        val second = journal(roots)
        second.startDataStage(id, DataStage.MMAPS, 3)
        val stage = second.dataStage(id, DataStage.MMAPS)
        assertEquals(1, second.dataStages(id).size)
        assertEquals((firstAttempt ?: 0) + 1, stage?.attempt)
        second.close()
    }

    @Test
    fun `import timing survives the crash and completes on completion`() {
        val roots = roots()
        val first = journal(roots)
        val id = first.beginOrResume("file:///client", inventory())
        first.close()

        val second = journal(roots)
        val running = second.importTiming(id)
        assertTrue(running != null && running.second == null)
        second.complete(id, "0f0f0f0f-1111-2222-3333-444455556666")
        val done = second.importTiming(id)
        assertTrue(done != null && done.second != null && done.second!! >= done.first)
        second.close()
    }
}
