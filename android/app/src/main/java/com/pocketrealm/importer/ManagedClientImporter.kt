package com.pocketrealm.importer

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.client.ClientGenerationLease
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.client.SafClientScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

class ManagedClientImporter(
    private val context: Context,
    private val limits: ImportLimits = ImportLimits(),
    private val journal: ImportJournal = ImportJournal(context),
    private val storagePlanner: ImportStoragePlanner = ImportStoragePlanner(context),
    private val generations: ClientGenerationStore = ClientGenerationStore(context),
    private val prepareData: Boolean = true,
    private val dataStore: DataPreparationStore = DataPreparationStore(context, journal),
) : AutoCloseable {
    /** Shared per-run copy state (F8 E: one buffer for the whole copy loop). */
    private class CopyContext(val importId: String, val buffer: ByteArray)

    suspend fun run(
        treeUri: Uri,
        afterVerified: (Int) -> Unit = {},
        beforePublish: () -> Unit = {},
        afterRenameBeforeActivate: () -> Unit = {},
        onDataStageTick: () -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val started = android.os.SystemClock.elapsedRealtime()
        val importLease = ClientGenerationLease.acquireImportOperation(
            File(context.noBackupFilesDir, "client"),
        )
        try {
        val scanner = SafClientScanner(context.contentResolver).scan(treeUri)
        if (!scanner.supported || scanner.clientId != ClientRuntimeContract.WOW_5875_ID) {
            throw ImportRejected(scanner.failures.joinToString("; ").ifBlank { "unsupported client selection" })
        }
        val source = SafTreeSource(context.contentResolver, treeUri, limits)
        val inventory = source.inventory()
        val storage = storagePlanner.plan(inventory.totalBytes)
        if (!storage.canProceed) {
            throw ImportRejected("STORAGE_PREFLIGHT: need=${storage.requiredBytes} allocatable=${storage.allocatableBytes}")
        }
        val importId = journal.beginOrResume(treeUri, inventory)
        recoverAndFinish(importId, inventory, storage, scanner, onDataStageTick)?.let { return@withContext it }
        journal.recordInventory(importId, inventory.entries)
        val staging = generations.prepare(importId)
        try {
            val copy = CopyContext(importId, ByteArray(COPY_BUFFER))
            copyAllEntries(copy, source, inventory, staging, afterVerified)
            verifyManagedCopy(importId, staging)
            check(source.inventory().fingerprint == inventory.fingerprint) { "SOURCE_CHANGED: tree changed during import" }
            val published = publishManaged(
                PublishInputs(importId, scanner, inventory, started), beforePublish, afterRenameBeforeActivate,
            )
            if (prepareData) dataStore.prepare(importId, published.root, onDataStageTick)
            journal.complete(importId, published.id)
            recordBenchmark(importId)
            ImportResult(importId, published.id, inventory, storage, scanner.warnings)
        } catch (error: Throwable) {
            if (error !is ImportInterrupted && error !is kotlinx.coroutines.CancellationException &&
                journal.latest().phase != ImportPhase.FAILED) journal.fail(importId, error.message ?: error.javaClass.simpleName)
            throw error
        }
        } finally {
            importLease.close()
        }
    }

    /** An already-published generation for this import finishes immediately. */
    private suspend fun recoverAndFinish(
        importId: String,
        inventory: SourceInventory,
        storage: StoragePlan,
        scanner: SafClientScanner.Result,
        onDataStageTick: () -> Unit,
    ): ImportResult? {
        val published = generations.recoverPublished(importId) ?: return null
        if (prepareData) dataStore.prepare(importId, published.root, onDataStageTick)
        journal.complete(importId, published.id)
        recordBenchmark(importId)
        return ImportResult(importId, published.id, inventory, storage, scanner.warnings)
    }

    private suspend fun copyAllEntries(
        copy: CopyContext,
        source: SafTreeSource,
        inventory: SourceInventory,
        staging: File,
        afterVerified: (Int) -> Unit,
    ) {
        var verifiedThisRun = 0
        for (entry in inventory.entries.filterNot { it.directory }) {
            coroutineContext.ensureActive()
            if (excludeFromSafeRuntime(entry.relativePath)) {
                journal.markSkipped(
                    copy.importId, entry,
                    "safe-mode excludes non-primary root executable or injected DLL",
                )
                continue
            }
            if (copyEntry(copy, entry, source, staging)) {
                verifiedThisRun++
                afterVerified(verifiedThisRun)
            }
        }
    }

    /** What the journal says to do with one source entry. */
    private sealed interface EntryDecision {
        /** F8 A1: VERIFIED row + fsync marker + unchanged source + full target. */
        object Skipped : EntryDecision

        /** Round-2 NIT: kill between rename and journal commit — just close the row. */
        data class RenamedNotJournaled(val target: File) : EntryDecision

        data class Transfer(val target: File, val partial: File, val resume: Boolean) : EntryDecision
    }

    private fun sourceMatches(prior: ImportJournal.JournalFile?, entry: SafSourceEntry): Boolean =
        prior != null && prior.expectedSize == entry.size && prior.expectedMtime == entry.lastModified

    private fun partialFor(target: File, importId: String): File =
        File(target.parentFile, ".${target.name}.partial.$importId")

    /**
     * F8 A3: an interrupted partial (its prefix bytes are page-cache durable
     * across a process kill) can be appended to instead of paying a full
     * re-copy of the largest file after every death. A partial that already
     * reached full size covers a kill between the last write and the rename.
     */
    private fun resumablePrefix(
        prior: ImportJournal.JournalFile?,
        entry: SafSourceEntry,
        partial: File,
        sourceUnchanged: Boolean,
    ): Boolean {
        val partialMatches = prior?.tempName == partial.name &&
            partial.isFile && partial.length() in 1..entry.size
        return sourceUnchanged && prior?.state == ImportFileState.COPYING && partialMatches
    }

    /** Round-2 NIT: kill between rename and journal commit. */
    private fun renamedWithoutJournal(
        prior: ImportJournal.JournalFile?,
        sourceUnchanged: Boolean,
        targetComplete: Boolean,
        partial: File,
    ): Boolean {
        val copyingRow = sourceUnchanged && prior?.state == ImportFileState.COPYING
        return copyingRow && targetComplete && !partial.isFile
    }

    private fun classifyEntry(importId: String, entry: SafSourceEntry, staging: File): EntryDecision {
        val target = generations.resolve(staging, entry.relativePath)
        val prior = journal.file(importId, entry.relativePath)
        val sourceUnchanged = sourceMatches(prior, entry)
        val targetComplete = target.isFile && target.length() == entry.size
        val verifiedEarlier = prior?.state == ImportFileState.VERIFIED &&
            prior.fsyncMarker && sourceUnchanged
        val partial = partialFor(target, importId)
        return when {
            verifiedEarlier && targetComplete -> EntryDecision.Skipped
            resumablePrefix(prior, entry, partial, sourceUnchanged) ->
                EntryDecision.Transfer(target, partial, true)
            renamedWithoutJournal(prior, sourceUnchanged, targetComplete, partial) ->
                EntryDecision.RenamedNotJournaled(target)
            else -> EntryDecision.Transfer(target, partial, false)
        }
    }

    /**
     * Copy one source entry into the staging generation. Returns true when the
     * file was copied and verified now; false when the journal plus a durable
     * fsync marker already covered it.
     */
    private suspend fun copyEntry(
        copy: CopyContext,
        entry: SafSourceEntry,
        source: SafTreeSource,
        staging: File,
    ): Boolean {
        val importId = copy.importId
        return when (val decision = classifyEntry(importId, entry, staging)) {
            EntryDecision.Skipped -> false
            is EntryDecision.RenamedNotJournaled -> {
                journal.markVerified(importId, entry, sha256(decision.target), entry.size)
                true
            }
            is EntryDecision.Transfer -> {
                transferEntry(copy, entry, source, decision)
                true
            }
        }
    }

    private suspend fun transferEntry(
        copy: CopyContext,
        entry: SafSourceEntry,
        source: SafTreeSource,
        decision: EntryDecision.Transfer,
    ) {
        val importId = copy.importId
        decision.target.parentFile?.mkdirs()
        if (decision.resume) journal.markResumed(importId, entry)
        else {
            journal.markCopying(importId, entry, decision.partial.name)
            decision.partial.delete()
        }
        try {
            copyEntryBytes(copy, entry, source, decision.partial, decision.resume)
            Os.chmod(decision.partial.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            Os.rename(decision.partial.absolutePath, decision.target.absolutePath)
            fsyncDirectory(checkNotNull(decision.target.parentFile))
            journal.markVerified(importId, entry, sha256(decision.target), entry.size)
        } catch (error: Throwable) {
            recordEntryFailure(importId, entry, error)
            throw error
        }
    }

    private fun recordEntryFailure(importId: String, entry: SafSourceEntry, error: Throwable) {
        if (error is ImportInterrupted || error is kotlinx.coroutines.CancellationException) {
            journal.update(importId, ImportPhase.PAUSED, entry.relativePath, error.message)
        } else journal.markFileFailed(importId, entry, error)
    }

    private suspend fun copyEntryBytes(
        copy: CopyContext,
        entry: SafSourceEntry,
        source: SafTreeSource,
        partial: File,
        resume: Boolean,
    ) {
        source.open(entry).use { input ->
            var resumeFrom = if (resume) partial.length() else 0L
            if (resumeFrom > 0L && !skipSourcePrefix(input, resumeFrom, copy.buffer)) resumeFrom = 0L
            FileOutputStream(partial, resumeFrom > 0L).use { output ->
                val copied = pump(input, output, copy, entry, resumeFrom)
                output.fd.sync()
                check(copied == entry.size) {
                    "SOURCE_CHANGED: ${entry.relativePath} expected=${entry.size} copied=$copied"
                }
            }
        }
    }

    /** Streams source bytes to the partial file, journaling periodic progress. */
    private suspend fun pump(
        input: InputStream,
        output: FileOutputStream,
        copy: CopyContext,
        entry: SafSourceEntry,
        startCopied: Long,
    ): Long {
        var copied = startCopied
        var sinceProgressTick = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(copy.buffer)
            if (count < 0) return copied
            output.write(copy.buffer, 0, count); copied += count; sinceProgressTick += count
            // F8 D: keep the journal fresh during a multi-minute MPQ so
            // watchdog staleness and post-mortem progress stay truthful. The
            // sync bounds power-loss damage to the tick interval (round 2).
            if (sinceProgressTick >= COPY_PROGRESS_TICK_BYTES) {
                sinceProgressTick = 0
                output.fd.sync()
                journal.touchCopying(copy.importId, entry, copied)
            }
        }
    }

    /**
     * Re-hash every managed file against the journal before publish (the one
     * full verification pass; F8 A1 removed the per-restart duplicate).
     */
    private fun verifyManagedCopy(importId: String, staging: File) {
        journal.update(importId, ImportPhase.VERIFYING)
        val entries = journal.files(importId)
        check(entries.all { it.state in setOf(ImportFileState.VERIFIED, ImportFileState.SKIPPED) && it.fsyncMarker }) {
            "import journal contains incomplete files"
        }
        // F8 C: time-throttled journal ticks so this multi-minute pass shows
        // which file is being verified instead of a silent card (round 2:
        // per-file ticks made the WAL commit cost quadratic under
        // synchronous=FULL for large file counts).
        val toVerify = entries.filter { it.state == ImportFileState.VERIFIED }
        var lastTickMs = 0L
        for ((index, entry) in toVerify.withIndex()) {
            val file = generations.resolve(staging, entry.relativePath)
            check(file.isFile && file.length() == entry.expectedSize && sha256(file) == entry.sha256) {
                "managed-copy verification failed: ${entry.relativePath}"
            }
            val now = System.currentTimeMillis()
            if (now - lastTickMs >= JOURNAL_TICK_INTERVAL_MS || index == toVerify.lastIndex) {
                lastTickMs = now
                journal.update(importId, ImportPhase.VERIFYING, "(${index + 1}/${toVerify.size}) ${entry.relativePath}")
            }
        }
    }

    private class PublishInputs(
        val importId: String,
        val scan: SafClientScanner.Result,
        val inventory: SourceInventory,
        val startedMs: Long,
    )

    private fun publishManaged(
        inputs: PublishInputs,
        beforePublish: () -> Unit,
        afterRenameBeforeActivate: () -> Unit,
    ): ClientGenerationStore.PublishedGeneration {
        val importId = inputs.importId
        journal.update(importId, ImportPhase.PUBLISHING)
        beforePublish()
        // Round 2: throttle the publish manifest ticks like VERIFYING's.
        var lastTickMs = 0L
        val wow = inputs.inventory.entries.single { !it.directory && it.relativePath.equals("WoW.exe", true) }
        val identity = JSONObject().put("machine", 0x14c).put("optionalMagic", 0x10b)
            .put("version", inputs.scan.version).put("build", inputs.scan.build)
            .put("sha256", inputs.scan.executableSha256).put("size", wow.size)
            .put("locale", "enUS-flat-classic").put("sourceBytes", inputs.inventory.totalBytes)
            .put("fileCount", inputs.inventory.fileCount).put("warnings", inputs.scan.warnings)
        return generations.publish(
            importId,
            ClientGenerationStore.PublishPlan(
                identity, inputs.inventory.fingerprint, journal.files(importId),
                android.os.SystemClock.elapsedRealtime() - inputs.startedMs,
            ),
            ClientGenerationStore.PublishCallbacks(
                afterRenameBeforeActivate = afterRenameBeforeActivate,
                // F8 C: throttled per-file ticks so the publish manifest hash
                // shows life (round 2: quadratic-commit fix as in VERIFYING).
                onManifestFile = { relative ->
                    val now = System.currentTimeMillis()
                    if (now - lastTickMs >= JOURNAL_TICK_INTERVAL_MS) {
                        lastTickMs = now
                        journal.update(importId, ImportPhase.PUBLISHING, relative)
                    }
                },
            ),
        )
    }

    fun status(): ImportStatus = journal.latest().copy(activeGeneration = generations.activeGeneration())

    fun dataCheckpoints(importId: String?): List<DataCheckpoint> =
        importId?.let(journal::dataStages).orEmpty()

    fun activeFile(importId: String?): ActiveImportFile? {
        val id = importId ?: return null
        val file = journal.copyingFile(id) ?: return null
        return ActiveImportFile(
            relativePath = file.relativePath,
            expectedBytes = file.expectedBytes,
            copiedBytes = generations.partialLength(id, file.relativePath, file.tempName)
                .coerceAtMost(file.expectedBytes),
        )
    }

    fun deviceProfile(): DeviceProfile.Info = DeviceProfile.current(context)

    fun benchmarks(limit: Int = 8): List<ImportBenchmark> = journal.benchmarks(limit)

    /**
     * Persist a timed benchmark row for a completed import. Telemetry only:
     * a recording failure must never fail the import itself.
     */
    private fun recordBenchmark(importId: String) {
        runCatching {
            val timing = journal.importTiming(importId) ?: return
            val completedAt = timing.second ?: return
            val stages = journal.dataStages(importId)
            val durations = JSONObject()
            var firstStageStart = 0L
            stages.forEach { checkpoint ->
                if (checkpoint.startedAtMs > 0L && (firstStageStart == 0L || checkpoint.startedAtMs < firstStageStart)) {
                    firstStageStart = checkpoint.startedAtMs
                }
                if (checkpoint.startedAtMs > 0L && checkpoint.completedAtMs > checkpoint.startedAtMs) {
                    durations.put(checkpoint.stage.name, checkpoint.completedAtMs - checkpoint.startedAtMs)
                }
            }
            val device = DeviceProfile.current(context)
            journal.recordBenchmark(ImportBenchmark(
                importId = importId,
                deviceLabel = device.label,
                model = device.model,
                soc = device.soc,
                activelyCooled = device.activelyCooled,
                abi = device.abi,
                api = device.api,
                cores = device.cores,
                ramBytes = device.ramBytes,
                totalMs = (completedAt - timing.first).coerceAtLeast(0L),
                copyMs = if (firstStageStart > timing.first) firstStageStart - timing.first else 0L,
                dataMs = if (firstStageStart > 0L) (completedAt - firstStageStart).coerceAtLeast(0L) else 0L,
                stageDurationsJson = durations.toString(),
                mmapMaps = stages.firstOrNull { it.stage == DataStage.MMAPS }?.total ?: 0,
                mmapThreads = DataPreparationStore.MMAP_THREADS,
                createdAtMs = completedAt,
            ))
        }.onFailure { android.util.Log.w("ImportBenchmark", "benchmark recording failed", it) }
    }

    private fun excludeFromSafeRuntime(relative: String): Boolean {
        if ('/' in relative) return false
        val lower = relative.lowercase()
        if (lower.endsWith(".exe") && lower != "wow.exe") return true
        return lower.endsWith(".dll") && lower !in STANDARD_DLLS
    }

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)

    /**
     * F8 A3: position a SAF stream at [bytes]. Document streams are usually
     * seek-backed so skip() is cheap; when it is not, read-and-discard through
     * the shared copy buffer. False means the source is shorter than the
     * recorded partial and the caller must restart the file from zero.
     */
    private fun skipSourcePrefix(input: InputStream, bytes: Long, buffer: ByteArray): Boolean {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    private fun fsyncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    override fun close() = journal.close()

    data class ImportResult(
        val importId: String, val generation: String, val inventory: SourceInventory,
        val storage: StoragePlan, val warnings: List<String>,
    )

    companion object {
        private const val COPY_BUFFER = 1024 * 1024
        private const val COPY_PROGRESS_TICK_BYTES = 64L * 1024 * 1024
        private const val JOURNAL_TICK_INTERVAL_MS = 2_000L
        private val STANDARD_DLLS = setOf(
            "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll",
        )
    }
}
