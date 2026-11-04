package com.pocketrealm.importer

import com.pocketrealm.client.ClientGenerationLease
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.DesktopClientScanner
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * the desktop managed-client import pipeline — the Android
 * importer/ManagedClientImporter's FOLDER lane (row 2) over a plain
 * `java.io.File` source: scan (identity gates, VAL copy) → inventory →
 * storage preflight → copy into the staging generation with per-file
 * digest-verify + fsync + atomic rename → full re-hash verification pass →
 * source-stability fingerprint check → publish through the
 * [ClientGenerationStore] twin (immutable generation + atomic per-flavor
 * active pointer, via the shared lease/pointer plumbing).
 *
 * the lane runs on the [ImportJournal] desktop twin exactly like
 * the Android lane — the durable row exists before the copy (beginOrResume /
 * beginStagingOrResume), every file's VERIFIED/SKIPPED state lands in the
 * journal as it is copied, a crash resumes the SAME import id (verified
 * entries are skipped, then re-validated by the full hash pass), completion
 * pins the generation, and every failure path journals FAILED. The storage
 * preflight is the [ImportStoragePlanner] desktop twin (the Android lane's
 * exact arithmetic). The SAF transport, the process-metrics sampler and the
 * benchmark lane remain Android-specific per DESK-66.
 */
class ManagedClientImporter(
    private val roots: DesktopStorageRoots,
    private val limits: ImportLimits = ImportLimits(),
    private val generations: ClientGenerationStore = ClientGenerationStore(roots),
    // Storage-preflight allocatable read, injectable for the same reason as
    // the planner's: pipeline tests exercise copy/verify/publish, not the
    // host volume's real headroom.
    allocatableBytes: () -> Long = { roots.content.usableSpace.coerceAtLeast(0L) },
) : AutoCloseable {

    private val scanner = DesktopClientScanner()
    private val planner = ImportStoragePlanner(roots, allocatableBytes = allocatableBytes)

    /** Shared per-run copy state: one buffer for the whole copy loop. */
    private class CopyContext(val importId: String, val buffer: ByteArray)

    /**
     * Import the client folder at [sourceRoot] into a managed generation.
     * Returns the published generation; throws [ImportRejected] with the VAL
     * copy on an unsupported selection and [error]-class exceptions on
     * pipeline failures (the caller surfaces the message verbatim).
     */
    suspend fun run(
        sourceRoot: File,
        afterVerified: (Int) -> Unit = {},
        beforePublish: () -> Unit = {},
        afterRenameBeforeActivate: () -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val importLease = ClientGenerationLease.acquireImportOperation(
            File(roots.content, "client"),
        )
        // one journal connection per run — the RESUME state is
        // durable in the db, not in the connection, so a fresh run picks up
        // exactly where a crashed one stopped.
        val journal = ImportJournal(roots)
        try {
            // The identity gate runs FIRST: rejecting a selection must never
            // copy a byte of it (the Android scanner contract).
            // A picked parent folder (or a double "World of Warcraft" wrap)
            // imports the single client folder inside it.
            val clientRoot = DesktopClientScanner.locateClientRoot(sourceRoot)
            val scan = scanner.scan(clientRoot)
            if (!scan.supported || !ClientFlavors.isManagedClientId(scan.clientId)) {
                throw ImportRejected(scan.failures.joinToString("; ").ifBlank { "unsupported client selection" })
            }
            val source = FolderTreeSource(clientRoot, limits)
            val inventory = source.inventory()
            val storage = planner.plan(sourceBytes = inventory.totalBytes)
            if (!storage.canProceed) {
                throw ImportRejected(
                    "STORAGE_PREFLIGHT: need=${storage.requiredBytes} allocatable=${storage.allocatableBytes}",
                )
            }
            // the durable row exists before the copy; resume
            // matches the source path + inventory fingerprint.
            val importId = journal.beginOrResume(clientRoot.absolutePath, inventory)
            journal.recordInventory(importId, inventory.entries)
            val staging = generations.prepare(importId)
            val copy = CopyContext(importId, ByteArray(COPY_BUFFER))
            try {
                val verifiedHashes = copyAllEntries(copy, journal, importId, source, inventory, staging, afterVerified)
                verifyManagedCopy(inventory, staging, verifiedHashes)
                check(source.inventory().fingerprint == inventory.fingerprint) {
                    "SOURCE_CHANGED: source folder changed during import"
                }
                val published = publishManaged(
                    importId = importId,
                    scan = scan,
                    inventory = inventory,
                    verified = verifiedHashes,
                    startedNanos = started,
                    beforePublish = beforePublish,
                    afterRenameBeforeActivate = afterRenameBeforeActivate,
                )
                journal.complete(importId, published.id)
                ImportResult(importId, published.id, inventory, storage, scan.warnings)
            } catch (error: Throwable) {
                // Cancellation leaves the row non-terminal on purpose: it is
                // indistinguishable from a crash and resumes the same way.
                if (error !is kotlinx.coroutines.CancellationException) {
                    runCatching { journal.fail(importId, failureDetail(error)) }
                }
                throw error
            }
        } finally {
            importLease.close()
            journal.close()
        }
    }

    /**
     * the desktop ARCHIVE lane — the Android runArchive's pipeline
     * over a locally picked file (the SAF staging copy is the android-only
     * leg; a local file is already durable and seekable): sniff
     * ([ArchiveFormatSniffer]) → quick check → deep detection through the
     * desktop [ArchiveClientScanner] over entry readers (ZIP/7z/RAR) or
     * post-extraction (Inno installer payloads, extracted verbatim into the
     * staged store's scratch directory through [ScratchArchiveExtractor],
     * parsed with the shared [com.pocketrealm.importer.inno.InnoSetupReader])
     * → the same copy/verify/publish pipeline as the folder lane. DESK-35/64:
     * RAR runs through the desktop [RarArchiveSource] twin (junrar) with the
     * pinned VAL-13 encrypted/multi-volume/solid-RAR4 refusals.
     */
    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught") // one verdict per lane branch, mirroring the Android archive pipeline
    suspend fun runArchive(
        pickedArchive: File,
        afterDetection: () -> Unit = {},
        afterVerified: (Int) -> Unit = {},
        beforePublish: () -> Unit = {},
        afterRenameBeforeActivate: () -> Unit = {},
        onScratchEntry: (Int) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        require(pickedArchive.isFile) { "The selected archive could not be read" }
        val started = System.nanoTime()
        val importLease = ClientGenerationLease.acquireImportOperation(
            File(roots.content, "client"),
        )
        val stagedStore = StagedArchiveStore(File(File(roots.content, "client"), "incoming"))
        val journal = ImportJournal(roots)
        try {
            // live imports keep their staged scratch across
            // runs (a resumed installer lane must find its extraction);
            // terminal rows prune.
            stagedStore.reconcile(activeImportIds = journal.activeImportIds())
            // the journal row exists BEFORE staging and detection
            // (the Android contract) so progress is durable and a crash
            // resumes the same import. The picked file anchors the resume by
            // path + size; once detection adopts the payload totals the
            // staged-file digest becomes the anchor (finishStaging below).
            val importId = journal.beginStagingOrResume(
                pickedArchive.absolutePath,
                expectedBytes = pickedArchive.length(),
            ).importId
            try {
                val header = java.io.RandomAccessFile(pickedArchive, "r").use { raf ->
                    ByteArray(ArchiveFormatSniffer.HEADER_BYTES).also { readTolerant(raf, it) }
                }
                var format = ArchiveFormatSniffer.sniff(header)
                if (format == ArchiveFormat.UNKNOWN) {
                    // A file short of the ISO primary-volume-descriptor
                    // offset reads what is there and is simply not an ISO
                    // (isIso size-guards itself) — the quick check must get
                    // to say VAL-13, not a bare EOFException from readFully.
                    val probe = java.io.RandomAccessFile(pickedArchive, "r").use { raf ->
                        raf.seek(0x8001)
                        ByteArray(5).also { readTolerant(raf, it) }
                    }
                    if (ArchiveFormatSniffer.isIso(probe)) format = ArchiveFormat.ISO
                }
                // For RAR the rawEntries walk IS the refusal gate: the pinned
                // VAL-13 encrypted/multi-volume/solid-RAR4 checks run inside
                // the walk's open, BEFORE staging or detection copies anything.
                val rawEntries = when (format) {
                    ArchiveFormat.ZIP -> listZipEntries(pickedArchive)
                    ArchiveFormat.SEVEN_ZIP -> listSevenZipEntries(pickedArchive)
                    ArchiveFormat.RAR4, ArchiveFormat.RAR5 -> listRarEntries(pickedArchive)
                    else -> null
                }
                val quick = ArchiveQuickCheck.evaluate(format, rawEntries?.map { it.name })
                if (quick is ArchiveQuickCheck.Verdict.Reject) throw ImportRejected(quick.failure)
                if (rawEntries != null && rawEntries.any { it.encrypted }) {
                    throw ImportRejected(
                        "This archive is password-protected — Pocket Realm cannot open encrypted archives; " +
                            "re-pack it without a password (or extract on a PC and import the folder)",
                    )
                }

                // Installer payloads: extract the archive to the staged
                // store's scratch directory and parse the Inno headers; the
                // client files then stream out of the installer as an
                // ordinary ImportSource.
                var installerReader: com.pocketrealm.importer.inno.InnoSetupReader? = null
                if (quick is ArchiveQuickCheck.Verdict.InstallerPayload) {
                    val scratch = stagedStore.scratchDir(importId)
                    if (!stagedStore.hasScratch(importId)) {
                        // A scratch without the completion marker is a partial
                        // extraction from a dead run: start it over rather
                        // than parse (and terminally reject) a truncated payload.
                        scratch.deleteRecursively()
                        val preflight = planner.plan(
                            sourceBytes = pickedArchive.length(),
                            scratchArchiveBytes = pickedArchive.length(),
                        )
                        if (!preflight.canProceed) {
                            throw ImportRejected(
                                "STORAGE_PREFLIGHT: need=${preflight.requiredBytes} " +
                                    "allocatable=${preflight.allocatableBytes}",
                            )
                        }
                        ScratchArchiveExtractor.extract(
                            pickedArchive, format, scratch, limits,
                            checkpoint = { coroutineContext.ensureActive() },
                            onEntryExtracted = onScratchEntry,
                        )
                        stagedStore.markScratchComplete(importId)
                    }
                    installerReader = try {
                        com.pocketrealm.importer.inno.InnoSetupReader.open(scratch)
                    } catch (error: com.pocketrealm.importer.inno.InnoFormatException) {
                        throw ImportRejected("${ArchiveQuickCheck.VAL12_NOT_INNO} (${error.message})")
                    }
                }

                    val located: ArchiveClientScanner.Located
                    val detection: ArchiveClientScanner.Result?
                    val source: ImportSource
                    // Detection adopted the classified inventory: the journal
                    // moves to the payload totals, and the picked archive's
                    // digest becomes the durable resume anchor (the Android
                    // lane's finishStaging; here the picked file IS the staged
                    // copy — one extra read for a multi-GB anchor, the same
                    // cost the staged copy paid on device).
                    journal.finishStaging(
                        importId,
                        pickedArchive.absolutePath,
                        pickedArchive.length(),
                        com.pocketrealm.fs.FileDigests.sha256(pickedArchive),
                    )
                    try {
                    located = if (installerReader != null) {
                        ArchiveClientScanner(limits).locate(
                            InnoPayloadDetection.rawEntries(installerReader.files()),
                        )
                    } else {
                        ArchiveClientScanner(limits).locate(rawEntries!!)
                    }
                    detection = when {
                        installerReader != null -> null // identity gate runs on the extracted tree
                        else -> {
                            val reader: ArchiveClientScanner.EntryReader = when (format) {
                                ArchiveFormat.ZIP -> ArchiveClientScanner.EntryReader { name, maxBytes ->
                                    readZipEntry(pickedArchive, name, maxBytes)
                                }
                                ArchiveFormat.SEVEN_ZIP -> ArchiveClientScanner.EntryReader { name, maxBytes ->
                                    readSevenZipEntry(pickedArchive, name, maxBytes)
                                }
                                ArchiveFormat.RAR4, ArchiveFormat.RAR5 ->
                                    ArchiveClientScanner.EntryReader { name, maxBytes ->
                                        readRarEntry(pickedArchive, name, maxBytes)
                                    }
                                else -> throw ImportRejected(ArchiveQuickCheck.VAL13_UNSUPPORTED)
                            }
                            ArchiveClientScanner(limits).scan(rawEntries!!, reader).also { result ->
                                if (!result.scan.supported || !ClientFlavors.isManagedClientId(result.scan.clientId)) {
                                    throw ImportRejected(
                                        result.scan.failures.joinToString("; ").ifBlank { "unsupported client archive" },
                                    )
                                }
                            }
                        }
                    }
                    afterDetection()
                    source = when {
                        installerReader != null ->
                            InnoArchiveSource(installerReader, located.classified, located.rootPrefix)
                        format == ArchiveFormat.ZIP -> ZipArchiveSource(pickedArchive, detection!!.classified)
                        format == ArchiveFormat.SEVEN_ZIP -> DesktopSevenZipSource(pickedArchive, detection!!.classified)
                        format == ArchiveFormat.RAR4 || format == ArchiveFormat.RAR5 ->
                            RarArchiveSource(pickedArchive, detection!!.classified)
                        else -> throw ImportRejected(ArchiveQuickCheck.VAL13_UNSUPPORTED)
                    }
                } catch (error: Throwable) {
                    installerReader?.close()
                    throw error
                }
                source.use {
                    val inventory = it.inventory()
                    val scratchBytes = if (installerReader != null) pickedArchive.length() else 0L
                    val storage = planner.plan(
                        sourceBytes = inventory.totalBytes,
                        // The archive itself is the lane's staged copy on the
                        // desktop (already durable, held until publish) — the
                        // android budget's stagedArchiveBytes owner.
                        stagedArchiveBytes = pickedArchive.length(),
                        scratchArchiveBytes = scratchBytes,
                    )
                    if (!storage.canProceed) {
                        throw ImportRejected(
                            "STORAGE_PREFLIGHT: need=${storage.requiredBytes} allocatable=${storage.allocatableBytes}",
                        )
                    }
                    // Detection adopted the classified totals: the journal's
                    // archive-lane inventory commit (the android contract).
                    journal.commitInventory(
                        importId, inventory.fileCount, inventory.totalBytes, inventory.fingerprint,
                    )
                    journal.recordInventory(importId, inventory.entries)
                    val stagingDir = generations.prepare(importId)
                    val copy = CopyContext(importId, ByteArray(COPY_BUFFER))
                    try {
                        val verifiedHashes =
                            copyAllEntries(copy, journal, importId, it, inventory, stagingDir, afterVerified)
                        verifyManagedCopy(inventory, stagingDir, verifiedHashes)
                        // The Inno lane's identity gate runs on the EXTRACTED
                        // tree (the client copy now in staging); zip/7z were
                        // gated at detection time.
                        val scan = detection?.scan
                            ?: ArchiveClientScanner(limits).verifyExtracted(stagingDir).also { verified ->
                                if (!verified.supported || !ClientFlavors.isManagedClientId(verified.clientId)) {
                                    throw ImportRejected(
                                        verified.failures.joinToString("; ").ifBlank { "unsupported client archive" },
                                    )
                                }
                            }
                        val fingerprint = it.inventory().fingerprint
                        check(fingerprint == inventory.fingerprint) {
                            "SOURCE_CHANGED: staged archive changed during import"
                        }
                        val published = publishManaged(
                            importId = importId,
                            scan = scan,
                            inventory = inventory,
                            verified = verifiedHashes,
                            startedNanos = started,
                            beforePublish = beforePublish,
                            afterRenameBeforeActivate = afterRenameBeforeActivate,
                        )
                        journal.complete(importId, published.id)
                        // Free the installer scratch (and any staged residue) now
                        // that the generation is published — the android lane's
                        // "delete before data preparation" posture.
                        stagedStore.delete(importId)
                        ImportResult(importId, published.id, inventory, storage, scan.warnings)
                    } catch (error: Throwable) {
                        runCatching { journal.fail(importId, failureDetail(error)) }
                        throw error
                    }
                }
            } catch (error: Throwable) {
                // Cancellation leaves the row non-terminal on purpose: it is
                // indistinguishable from a crash and resumes the same way.
                if (error !is kotlinx.coroutines.CancellationException) {
                    runCatching { journal.fail(importId, failureDetail(error)) }
                }
                if (error is ImportRejected) {
                    // A permanent validation rejection never resumes: drop the
                    // scratch extraction now so multi-GB orphans cannot linger.
                    stagedStore.delete(importId)
                }
                throw error
            }
        } finally {
            importLease.close()
            journal.close()
        }
    }

    /** Human-readable detail for the journal's FAILED row on a lane failure. */
    private fun failureDetail(error: Throwable): String =
        error.message ?: error.javaClass.simpleName

    /** Plain read loop (no readFully): a short file yields a short buffer —
     *  sniff/isIso size-guard themselves — so an unknown-format file falls
     *  through to the quick check's VAL-13 refusal instead of a bare
     *  EOFException. */
    private fun readTolerant(raf: java.io.RandomAccessFile, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = raf.read(target, offset, target.size - offset)
            if (count < 0) break // EOF: keep the bytes read so far
            offset += count
        }
    }

    /**
     * Copy every source file into the staging generation. Returns the
     * per-file sha256 recorded at copy time (the digest streamed from the
     * SOURCE, cross-checked against the copied target afterwards). Safe-mode
     * exclusions follow the Android importer's rule verbatim: root-level
     * executables other than WoW.exe and non-standard root DLLs never enter
     * the managed generation.
     *
     * every outcome is journaled — a resumed run skips the
     * entries a previous attempt already VERIFIED (their copied files are
     * size-checked here; the full hash pass after the loop still validates
     * every byte), so a crash mid-import continues instead of restarting.
     */
    private suspend fun copyAllEntries(
        copy: CopyContext,
        journal: ImportJournal,
        importId: String,
        source: ImportSource,
        inventory: SourceInventory,
        staging: File,
        afterVerified: (Int) -> Unit,
    ): Map<String, String> {
        val verifiedHashes = mutableMapOf<String, String>()
        var verifiedThisRun = 0
        for (entry in inventory.entries.filterNot { it.directory }) {
            coroutineContext.ensureActive()
            if (excludeFromSafeRuntime(entry.relativePath)) {
                journal.markSkipped(importId, entry, SAFE_MODE_SKIP_REASON)
                continue // safe-mode excludes non-primary root executable or injected DLL
            }
            val prior = journal.file(importId, entry.relativePath)
            if (prior?.state == ImportFileState.VERIFIED && prior.sha256 != null) {
                val target = generations.resolve(staging, entry.relativePath)
                if (target.isFile && target.length() == entry.size) {
                    verifiedHashes[entry.relativePath] = prior.sha256!!
                    verifiedThisRun++
                    afterVerified(verifiedThisRun)
                    continue // resume: already verified by a previous attempt
                }
            }
            val hash = copyEntry(copy, journal, importId, entry, source, staging)
            verifiedHashes[entry.relativePath] = hash
            verifiedThisRun++
            afterVerified(verifiedThisRun)
        }
        return verifiedHashes
    }

    /**
     * Copy one entry: stream source → `.partial` (digesting the source
     * bytes), fsync the partial, atomic-rename onto the target, fsync, then
     * re-hash the TARGET and require it to equal the streamed source digest.
     * The journal records COPYING (with the partial name, so an interrupted
     * file is diagnosable), periodic in-file progress, and the final
     * VERIFIED digest. Returns the verified digest.
     */
    private fun copyEntry(
        copy: CopyContext,
        journal: ImportJournal,
        importId: String,
        entry: ImportSourceEntry,
        source: ImportSource,
        staging: File,
    ): String {
        val target = generations.resolve(staging, entry.relativePath)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, ".${target.name}.partial.${copy.importId}")
        runCatching { partial.delete() }
        journal.markCopying(importId, entry, partial.name)
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        try {
            source.open(entry).use { input ->
                FileChannel.open(
                    partial.toPath(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                ).use { channel ->
                    var copied = 0L
                    var lastTouched = 0L
                    while (true) {
                        val count = input.read(copy.buffer)
                        if (count < 0) break
                        sourceDigest.update(copy.buffer, 0, count)
                        channel.write(ByteBuffer.wrap(copy.buffer, 0, count))
                        copied += count
                        if (copied - lastTouched >= COPY_TOUCH_INTERVAL) {
                            journal.touchCopying(importId, entry, copied)
                            lastTouched = copied
                        }
                    }
                    channel.force(true)
                    check(copied == entry.size) {
                        "SOURCE_CHANGED: ${entry.relativePath} expected=${entry.size} copied=$copied"
                    }
                }
            }
            // Os.rename twin for the per-entry publication (no replace needed:
            // the target must not exist inside a fresh staging generation).
            check(!target.exists()) { "staging collision: ${entry.relativePath}" }
            moveAtomic(partial, target)
            val targetDigest = sha256(target)
            check(targetDigest == sourceDigest.digest().joinToString("") { "%02x".format(it) }) {
                "managed-copy verification failed: ${entry.relativePath}"
            }
            journal.markVerified(importId, entry, targetDigest, entry.size)
            return targetDigest
        } finally {
            runCatching { if (partial.isFile) partial.delete() }
        }
    }

    /**
     * The one full verification pass: re-hash every managed file in the
     * staging generation and require the copied tree to still match the
     * digests recorded at copy time (the Android twin re-hashes against the
     * journal; the desktop re-hashes against the run's in-memory record).
     */
    private fun verifyManagedCopy(
        inventory: SourceInventory,
        staging: File,
        verifiedHashes: Map<String, String>,
    ) {
        for ((relativePath, expected) in verifiedHashes) {
            val file = generations.resolve(staging, relativePath)
            val entry = inventory.entries.first { it.relativePath == relativePath }
            check(file.isFile && file.length() == entry.size && sha256(file) == expected) {
                "managed-copy verification failed: $relativePath"
            }
        }
    }

    private fun publishManaged(
        importId: String,
        scan: DesktopClientScanner.Result,
        inventory: SourceInventory,
        verified: Map<String, String>,
        startedNanos: Long,
        beforePublish: () -> Unit,
        afterRenameBeforeActivate: () -> Unit,
    ): ClientGenerationStore.PublishedGeneration {
        beforePublish()
        val wow = inventory.entries.single { !it.directory && it.relativePath.equals("WoW.exe", true) }
        val identity = JSONObject().put("machine", 0x14c).put("optionalMagic", 0x10b)
            .put("version", scan.version).put("build", scan.build)
            .put("sha256", scan.executableSha256).put("size", wow.size)
            .put("locale", "enUS-flat-classic").put("sourceBytes", inventory.totalBytes)
            .put("fileCount", inventory.fileCount).put("warnings", scan.warnings)
        return generations.publish(
            importId,
            ClientGenerationStore.PublishPlan(
                identity,
                inventory.fingerprint,
                ClientGenerationStore.JournalSummary(
                    verified = verified.size,
                    skipped = inventory.fileCount - verified.size,
                    maxAttempt = 1,
                ),
                durationMs = (System.nanoTime() - startedNanos) / 1_000_000L,
            ),
            ClientGenerationStore.PublishCallbacks(
                afterRenameBeforeActivate = afterRenameBeforeActivate,
            ),
        )
    }

    private fun excludeFromSafeRuntime(relative: String): Boolean {
        if ('/' in relative) return false
        val lower = relative.lowercase()
        if (lower.endsWith(".exe") && lower != "wow.exe") return true
        return lower.endsWith(".dll") && lower !in STANDARD_DLLS
    }

    private fun sha256(file: File): String = com.pocketrealm.fs.FileDigests.sha256(file)

    private fun moveAtomic(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    /** Nothing process-wide to release: the DESK-3/66 journal connection is
     * opened per run (its resume state is durable in the db, not the
     * connection), and the staging/lease artifacts are per-run as well. */
    override fun close() = Unit

    data class ImportResult(
        val importId: String,
        val generation: String,
        val inventory: SourceInventory,
        val storage: StoragePlan,
        val warnings: List<String>,
    )

    companion object {
        private const val COPY_BUFFER = 1024 * 1024

        /** In-file journal progress cadence (touchCopying) during a copy. */
        private const val COPY_TOUCH_INTERVAL = 64L * 1024L * 1024L

        /** The safe-mode skip reason journaled for excluded root entries. */
        private const val SAFE_MODE_SKIP_REASON =
            "safe-mode exclusion: non-primary root executable or non-standard root DLL"

        private val STANDARD_DLLS = setOf(
            "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll",
        )
    }
}
