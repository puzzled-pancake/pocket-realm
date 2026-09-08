package com.pocketrealm.importer

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Resumable copy of the picked archive into app storage, mirroring the copy
 * loop's durability discipline: a `.partial` file, fsync every progress tick,
 * and cooperative cancellation through the [checkpoint] callback. Pure JVM —
 * the caller supplies the source stream opener and journals progress.
 */
class StagedArchiveCopier(
    private val bufferBytes: Int = 1024 * 1024,
    private val progressTickBytes: Long = 64L * 1024 * 1024,
) {
    /**
     * Copies [expectedBytes] from [openStream] into [target], appending when
     * [resumeFrom] bytes already exist durably. Returns the total bytes in
     * [target]; the caller asserts equality with [expectedBytes].
     */
    fun copy(
        target: File,
        expectedBytes: Long,
        resumeFrom: Long,
        openStream: () -> InputStream,
        onTick: (totalCopied: Long) -> Unit,
        checkpoint: () -> Unit,
    ): Long {
        require(resumeFrom in 0..expectedBytes)
        val buffer = ByteArray(bufferBytes)
        openStream().use { input ->
            skipPrefix(input, resumeFrom, buffer)
            FileOutputStream(target, resumeFrom > 0).use { output ->
                var copied = resumeFrom
                var sinceTick = 0L
                while (copied < expectedBytes) {
                    checkpoint()
                    // Length must be derived in Long: archives over 4 GiB make
                    // expectedBytes - copied exceed Int range, and its 2^32
                    // point truncates to 0 — read(buf, 0, 0) returns 0 forever
                    // and the loop spins without progress.
                    val count = input.read(
                        buffer, 0,
                        minOf(buffer.size.toLong(), expectedBytes - copied).toInt(),
                    )
                    if (count < 0) return copied
                    output.write(buffer, 0, count)
                    copied += count
                    sinceTick += count
                    if (sinceTick >= progressTickBytes) {
                        sinceTick = 0
                        output.fd.sync()
                        onTick(copied)
                    }
                }
                output.fd.sync()
                return copied
            }
        }
    }

    private fun skipPrefix(input: InputStream, bytes: Long, buffer: ByteArray) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw ImportRejected("VAL-13: staged archive is shorter than the journal recorded")
            remaining -= read
        }
    }
}

/**
 * Owns `<noBackup>/client/incoming/`: staged `.pkg` files and their partials.
 * The reconciler deletes anything not referenced by an active journal row
 * (stale cutoff as in UserVulkanDriverRegistry.reconcile) so cancel, crash,
 * disk-full, and rejection never strand multi-GB orphans.
 */
class StagedArchiveStore(private val root: File) {
    fun stagedFile(importId: String): File {
        root.mkdirs()
        return File(root, "$importId.pkg")
    }

    fun partialFor(importId: String): File = File(root, ".$importId.pkg.partial")

    /**
     * Scratch directory holding an installer payload extracted from the
     * staged archive (setup.exe plus its setup-N.bin slices). Shares the
     * staged file's lifecycle: kept for crash resume, deleted with it at
     * every terminal state.
     */
    fun scratchDir(importId: String): File = File(root, "$importId.pkg.d")

    /**
     * True when a scratch extraction already exists for a resumed import —
     * proven by the completion marker, so a process death mid-extraction is
     * re-extracted instead of parsed as a corrupt payload.
     */
    fun hasScratch(importId: String): Boolean = scratchMarker(importId).isFile

    /** Written only after a scratch extraction fully finished. */
    fun scratchMarker(importId: String): File = File(scratchDir(importId), ".complete")

    fun markScratchComplete(importId: String) {
        scratchDir(importId).mkdirs()
        scratchMarker(importId).writeText("complete")
    }

    /** Expected durable length when resuming, 0 when starting fresh. */
    fun resumableBytes(importId: String, expectedBytes: Long): Long {
        val staged = stagedFile(importId)
        if (staged.isFile && staged.length() == expectedBytes) return expectedBytes
        val partial = partialFor(importId)
        return if (partial.isFile && partial.length() in 1 until expectedBytes) partial.length() else 0L
    }

    /** Promotes a completed partial to the staged name (rename + dir fsync). */
    fun promotePartial(importId: String) {
        val partial = partialFor(importId)
        val staged = stagedFile(importId)
        if (partial.isFile) {
            partial.renameTo(staged)
        }
    }

    fun delete(importId: String) {
        stagedFile(importId).delete()
        partialFor(importId).delete()
        scratchDir(importId).deleteRecursively()
    }

    /**
     * Deletes staged/partial/scratch files nobody can still resume. Files of
     * a fresh (within the staleness window) active import are kept — that is
     * what makes the staged-copy partial and the scratch dir survive a
     * process restart. Anything older than the window is swept so a paused
     * import cannot leak storage forever; resuming such an import costs a
     * re-stage. Dot-partials of inactive imports go immediately.
     */
    fun reconcile(activeImportIds: Set<String>, staleAfterMs: Long = STALE_AFTER_MS) {
        if (!root.isDirectory) return
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { file ->
            val id = idOf(file) ?: return@forEach
            if (id in activeImportIds && now - file.lastModified() <= staleAfterMs) return@forEach
            if (now - file.lastModified() > staleAfterMs || file.name.startsWith(".")) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
    }

    private fun idOf(file: File): String? = when {
        file.name.endsWith(".pkg.d") -> file.name.removeSuffix(".pkg.d")
        file.name.endsWith(".pkg") -> file.name.removeSuffix(".pkg")
        file.name.startsWith(".") && file.name.endsWith(".pkg.partial") ->
            file.name.removePrefix(".").removeSuffix(".pkg.partial")
        else -> null
    }

    companion object { private const val STALE_AFTER_MS = 60L * 60 * 1000 }
}
