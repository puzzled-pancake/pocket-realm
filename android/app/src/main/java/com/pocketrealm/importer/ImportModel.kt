package com.pocketrealm.importer

import java.util.UUID

enum class ImportPhase {
    IDLE, STAGING, DISCOVERING, PREFLIGHT, COPYING, EXTRACTING, VERIFYING, PUBLISHING,
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
    // Flavor data, not a hard-coded cap: the qualified flavor's measured
    // storage.max_total_bytes (schemas/flavor.json), via [qualifiedMaxTotalBytes].
    val maxTotalBytes: Long = qualifiedMaxTotalBytes(),
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

/**
 * The active flavor's measured import-total cap
 * (schemas/flavor.json storage.max_total_bytes), resolved through the
 * multi-flavor [com.pocketrealm.client.ActiveFlavor] machine: the Home
 * flavor switcher's active lane drives the cap, and genuinely ambiguous
 * registry states (no qualified flavor, several qualified flavors without
 * an explicit active selection, a stale non-qualified selection) fail
 * loud. There is no fallback: a qualified flavor without a measured
 * budget fails loud.
 */
private fun qualifiedMaxTotalBytes(): Long {
    val flavor = com.pocketrealm.client.ActiveFlavor
        .resolve("import size limits resolve the qualified lane")
    return requireNotNull(flavor.maxTotalBytes) {
        "${flavor.flavorId} is qualified without a measured storage.max_total_bytes"
    }
}

data class SourceInventory(
    val entries: List<ImportSourceEntry>,
    val fileCount: Int,
    val totalBytes: Long,
    val fingerprint: String,
)

data class StoragePlan(
    val sourceBytes: Long,
    val extractedDataBytes: Long,
    val preparedDataBytes: Long = 0L,
    val databaseBytes: Long,
    val winePrefixAndCacheBytes: Long,
    val minimumSnapshotBytes: Long,
    val workingMarginBytes: Long,
    val requiredBytes: Long,
    val allocatableBytes: Long,
    val contentOnRemovable: Boolean = false,
    val contentVolumeRequiredBytes: Long = 0L,
    val contentVolumeAllocatableBytes: Long = 0L,
) {
    val canProceed: Boolean get() = allocatableBytes >= requiredBytes &&
        (!contentOnRemovable || contentVolumeAllocatableBytes >= contentVolumeRequiredBytes)
    val lowHeadroom: Boolean get() =
        allocatableBytes < requiredBytes + 2L * ImportLimits.GIB ||
            (contentOnRemovable &&
                contentVolumeAllocatableBytes < contentVolumeRequiredBytes + 2L * ImportLimits.GIB)

    fun preflightMessage(): String =
        if (!contentOnRemovable) {
            "STORAGE_PREFLIGHT: need=$requiredBytes allocatable=$allocatableBytes"
        } else {
            "STORAGE_PREFLIGHT: internal(need=$requiredBytes allocatable=$allocatableBytes) " +
                "sdcard(need=$contentVolumeRequiredBytes allocatable=$contentVolumeAllocatableBytes)"
        }
}

enum class ImportSourceKind { TREE, ARCHIVE }

data class ImportStatus(
    val importId: String? = null,
    val phase: ImportPhase = ImportPhase.IDLE,
    val sourceKind: ImportSourceKind = ImportSourceKind.TREE,
    val sourceFingerprint: String? = null,
    val sourceUri: String? = null,
    val filesProcessed: Int = 0,
    val filesTotal: Int = 0,
    val bytesCopied: Long = 0,
    val bytesTotal: Long = 0,
    val lastRelativePath: String? = null,
    val stagedBytes: Long = 0,
    val stagedPath: String? = null,
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
