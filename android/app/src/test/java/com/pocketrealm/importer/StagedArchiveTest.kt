package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CancellationException

class StagedArchiveCopierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class CancelDuringCopy(val bytesWritten: Long) : CancellationException("checkpoint")

    @Test fun fullCopyFsyncsAndReturnsExpectedBytes() {
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val target = File(folder.newFolder(), "staged.pkg")
        val copier = StagedArchiveCopier(bufferBytes = 64 * 1024, progressTickBytes = 1024 * 1024)
        var ticks = 0
        val copied = copier.copy(target, payload.size.toLong(), 0,
            { ByteArrayInputStream(payload) }, { ticks++ }, {})
        assertEquals(payload.size.toLong(), copied)
        assertEquals(target.length(), payload.size.toLong())
        assertTrue("progress ticks at 1 MiB intervals", ticks >= 3)
    }

    @Test fun cancellationMidCopyLeavesResumablePartial() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 197).toByte() }
        val dir = folder.newFolder()
        val target = File(dir, "staged.pkg")
        val copier = StagedArchiveCopier(bufferBytes = 64 * 1024, progressTickBytes = 64 * 1024)
        var thrown: CancelDuringCopy? = null
        try {
            copier.copy(target, payload.size.toLong(), 0, { ByteArrayInputStream(payload) }, {}, {
                if (target.length() >= 512 * 1024) throw CancelDuringCopy(target.length())
            })
        } catch (error: CancelDuringCopy) {
            thrown = error
        }
        assertTrue(thrown != null)
        val partialBytes = thrown!!.bytesWritten
        assertTrue("partial progress retained: $partialBytes", partialBytes in 1 until payload.size)

        // Second run resumes by appending: byte-identical to the payload.
        val resumed = copier.copy(target, payload.size.toLong(), partialBytes,
            { ByteArrayInputStream(payload) }, {}, {})
        assertEquals(payload.size.toLong(), resumed)
        assertTrue(target.readBytes().contentEquals(payload))
    }

    @Test fun resumeAgainstAShorterSourceRejectsVal13() {
        val target = File(folder.newFolder(), "staged.pkg")
        target.writeBytes(ByteArray(4096))
        val copier = StagedArchiveCopier()
        org.junit.Assert.assertThrows(ImportRejected::class.java) {
            copier.copy(target, 8192, 4096, { ByteArrayInputStream(ByteArray(16)) }, {}, {})
        }
    }
}

class StagedArchiveStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): StagedArchiveStore = StagedArchiveStore(folder.newFolder())

    @Test fun partialLifecycleResumesPromotesAndReports() {
        val store = store()
        val id = "11111111-1111-4111-8111-111111111111"
        val partial = store.partialFor(id)
        partial.writeBytes(ByteArray(1024))
        assertEquals(1024L, store.resumableBytes(id, 4096))
        store.stagedFile(id).writeBytes(ByteArray(4096))
        assertEquals(4096L, store.resumableBytes(id, 4096))
        store.promotePartial(id)
        assertTrue(store.stagedFile(id).isFile)
        store.delete(id)
        assertFalse(store.stagedFile(id).exists())
        assertFalse(store.partialFor(id).exists())
    }

    @Test fun reconcileDeletesOrphanPartialsButKeepsActiveAndFreshStaged() {
        val store = store()
        val active = "22222222-2222-4222-8222-222222222222"
        val orphan = "33333333-3333-4333-8333-333333333333"
        store.stagedFile(active).writeBytes(ByteArray(16))
        store.stagedFile(orphan).writeBytes(ByteArray(16))
        store.partialFor(active).writeBytes(ByteArray(16))
        store.partialFor(orphan).writeBytes(ByteArray(16))
        store.reconcile(activeImportIds = setOf(active))
        assertTrue("active staged file kept", store.stagedFile(active).isFile)
        assertTrue("active partial kept", store.partialFor(active).isFile)
        // Partials have no value without their journal row: deleted immediately.
        assertFalse("orphan partial deleted", store.partialFor(orphan).exists())
        // Fresh unreferenced staged files survive the stale cutoff (conservative).
        assertTrue("fresh orphan staged kept until stale", store.stagedFile(orphan).isFile)
    }
}
