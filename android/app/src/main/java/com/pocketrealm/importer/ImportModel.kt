package com.pocketrealm.importer

import java.util.UUID

enum class ImportPhase {
    IDLE, DISCOVERING, PREFLIGHT, COPYING, VERIFYING, PUBLISHING,
    PREPARING_DATA, COMPLETE, PAUSED, CANCELLED, FAILED,
}

enum class ImportFileState { DISCOVERED, COPYING, VERIFIED, SKIPPED, FAILED }
enum class DataStageState { PENDING, RUNNING, VERIFIED, FAILED }
enum class DataStage { DBC_MAPS, VMAP_EXTRACT, VMAP_ASSEMBLE, MMAPS, MANIFEST }

data class DataCheckpoint(
    val stage: DataStage,
    val state: DataStageState,
    val processed: Int,
    val total: Int,
    val bytesWritten: Long,
    val checkpoint: String?,
    val attempt: Int,
    val lastError: String?,
    val updatedAtMs: Long,
    val startedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
)

/** One recorded full-import benchmark; persisted in the importer journal. */
data class ImportBenchmark(
    val importId: String,
    val deviceLabel: String,
    val model: String,
    val soc: String,
    val activelyCooled: Boolean,
    val abi: String,
    val api: Int,
    val cores: Int,
    val ramBytes: Long,
    val totalMs: Long,
    val copyMs: Long,
    val dataMs: Long,
    val stageDurationsJson: String,
    val mmapMaps: Int,
    val mmapThreads: Int,
    val createdAtMs: Long,
)

data class ActiveImportFile(
    val relativePath: String,
    val expectedBytes: Long,
    val copiedBytes: Long,
)

data class ImportLimits(
    val minFiles: Int = 20,
    val minTotalBytes: Long = 1L * GIB,
    val maxFiles: Int = 100_000,
    val maxEntries: Int = 110_000,
    val maxTotalBytes: Long = 20L * GIB,
    val maxFileBytes: Long = 8L * GIB,
    val maxDepth: Int = 32,
    val maxComponentChars: Int = 255,
) {
    init {
        require(minFiles >= 1 && maxFiles >= minFiles)
        require(maxEntries >= maxFiles)
        require(minTotalBytes >= 0 && maxTotalBytes >= minTotalBytes)
    }
    companion object { const val GIB = 1024L * 1024L * 1024L }
}

data class SafSourceEntry(
    val documentId: String,
    val relativePath: String,
    val directory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val flags: Int,
)

data class SourceInventory(
    val entries: List<SafSourceEntry>,
    val fileCount: Int,
    val totalBytes: Long,
    val fingerprint: String,
)

data class StoragePlan(
    val sourceBytes: Long,
    val extractedDataBytes: Long,
    val databaseBytes: Long,
    val winePrefixAndCacheBytes: Long,
    val minimumSnapshotBytes: Long,
    val workingMarginBytes: Long,
    val requiredBytes: Long,
    val allocatableBytes: Long,
) {
    val canProceed: Boolean get() = allocatableBytes >= requiredBytes
    val lowHeadroom: Boolean get() = allocatableBytes < requiredBytes + 2L * ImportLimits.GIB
}

data class ImportStatus(
    val importId: String? = null,
    val phase: ImportPhase = ImportPhase.IDLE,
    val sourceFingerprint: String? = null,
    val filesProcessed: Int = 0,
    val filesTotal: Int = 0,
    val bytesCopied: Long = 0,
    val bytesTotal: Long = 0,
    val lastRelativePath: String? = null,
    val warningCount: Int = 0,
    val lastError: String? = null,
    val activeGeneration: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    init {
        if (importId != null) UUID.fromString(importId)
        require(filesProcessed >= 0 && filesTotal >= filesProcessed)
        require(bytesCopied >= 0 && bytesTotal >= bytesCopied)
    }
}

class ImportRejected(message: String) : IllegalArgumentException(message)
class ImportInterrupted(message: String) : RuntimeException(message)
