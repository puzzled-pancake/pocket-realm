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
import java.security.MessageDigest
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
    suspend fun run(
        treeUri: Uri,
        afterVerified: (Int) -> Unit = {},
        beforePublish: () -> Unit = {},
        afterRenameBeforeActivate: () -> Unit = {},
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
        generations.recoverPublished(importId)?.let { published ->
            if (prepareData) dataStore.prepare(importId, published.root)
            journal.complete(importId, published.id)
            return@withContext ImportResult(importId, published.id, inventory, storage, scanner.warnings)
        }
        journal.recordInventory(importId, inventory.entries)
        val staging = generations.prepare(importId)
        var verifiedThisRun = 0
        try {
            for (entry in inventory.entries.filterNot { it.directory }) {
                coroutineContext.ensureActive()
                if (excludeFromSafeRuntime(entry.relativePath)) {
                    journal.markSkipped(importId, entry, "safe-mode excludes non-primary root executable or injected DLL")
                    continue
                }
                val target = generations.resolve(staging, entry.relativePath)
                val prior = journal.file(importId, entry.relativePath)
                if (prior?.state == ImportFileState.VERIFIED && prior.fsyncMarker &&
                    prior.expectedSize == entry.size && prior.expectedMtime == entry.lastModified &&
                    target.isFile && target.length() == entry.size && sha256(target) == prior.sha256) {
                    continue
                }
                val partial = File(target.parentFile, ".${target.name}.partial.$importId")
                target.parentFile?.mkdirs()
                journal.markCopying(importId, entry, partial.name)
                partial.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                try {
                    source.open(entry).use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(COPY_BUFFER)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count); digest.update(buffer, 0, count); copied += count
                            }
                            output.fd.sync()
                        }
                    }
                    if (copied != entry.size) throw ImportRejected(
                        "SOURCE_CHANGED: ${entry.relativePath} expected=${entry.size} copied=$copied")
                    Os.chmod(partial.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
                    Os.rename(partial.absolutePath, target.absolutePath)
                    fsyncDirectory(checkNotNull(target.parentFile))
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    journal.markVerified(importId, entry, hash, copied)
                    verifiedThisRun++
                    afterVerified(verifiedThisRun)
                } catch (error: Throwable) {
                    if (error is ImportInterrupted || error is kotlinx.coroutines.CancellationException) {
                        journal.update(importId, ImportPhase.PAUSED, entry.relativePath, error.message)
                    } else journal.markFileFailed(importId, entry, error)
                    throw error
                }
            }
            journal.update(importId, ImportPhase.VERIFYING)
            val entries = journal.files(importId)
            check(entries.all { it.state in setOf(ImportFileState.VERIFIED, ImportFileState.SKIPPED) && it.fsyncMarker }) {
                "import journal contains incomplete files"
            }
            for (entry in entries.filter { it.state == ImportFileState.VERIFIED }) {
                val file = generations.resolve(staging, entry.relativePath)
                check(file.isFile && file.length() == entry.expectedSize && sha256(file) == entry.sha256) {
                    "managed-copy verification failed: ${entry.relativePath}"
                }
            }
            check(source.inventory().fingerprint == inventory.fingerprint) { "SOURCE_CHANGED: tree changed during import" }
            journal.update(importId, ImportPhase.PUBLISHING)
            beforePublish()
            val wow = inventory.entries.single { !it.directory && it.relativePath.equals("WoW.exe", true) }
            val identity = JSONObject().put("machine", 0x14c).put("optionalMagic", 0x10b)
                .put("version", scanner.version).put("build", scanner.build)
                .put("sha256", scanner.executableSha256).put("size", wow.size)
                .put("locale", "enUS-flat-classic").put("sourceBytes", inventory.totalBytes)
                .put("fileCount", inventory.fileCount).put("warnings", scanner.warnings)
            val published = generations.publish(
                importId, identity, inventory.fingerprint, entries,
                android.os.SystemClock.elapsedRealtime() - started,
                afterRenameBeforeActivate,
            )
            if (prepareData) dataStore.prepare(importId, published.root)
            journal.complete(importId, published.id)
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

    private fun excludeFromSafeRuntime(relative: String): Boolean {
        if ('/' in relative) return false
        val lower = relative.lowercase()
        if (lower.endsWith(".exe") && lower != "wow.exe") return true
        return lower.endsWith(".dll") && lower !in STANDARD_DLLS
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        private val STANDARD_DLLS = setOf(
            "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll",
        )
    }
}
