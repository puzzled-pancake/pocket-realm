package com.pocketrealm.importer

import android.content.Context
import android.os.storage.StorageManager
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.storage.ContentVolume
import com.pocketrealm.storage.StorageRoots
import kotlin.math.ceil

class ImportStoragePlanner(
    private val context: Context,
    // Flavor data: the qualified flavor's measured extraction budget
    // (schemas/flavor.json storage.extracted_estimate_bytes); the same
    // flavor block's storage.max_total_bytes is the import-total cap,
    // routed through ImportLimits.maxTotalBytes' default (ImportModel.kt).
    private val extractedEstimate: Long = flavorExtractedEstimate(),
    // The prepared-server-data OUTPUT the
    // flow writes after the import - the flavor's measured figure
    // (storage.prepared_estimate_bytes); see the desktop twin.
    private val preparedEstimate: Long = flavorPreparedEstimate(),
    // wineEstimate and minimumReserve are platform caps, not flavor data.
    private val wineEstimate: Long = 1L * ImportLimits.GIB,
    private val minimumReserve: Long = 2L * ImportLimits.GIB,
) {
    /**
     * Archive lanes additionally hold the staged archive copy until publish;
     * the installer lane also holds a scratch extraction of that archive
     * while the client streams out of it.
     */
    fun plan(
        sourceBytes: Long,
        stagedArchiveBytes: Long = 0L,
        scratchArchiveBytes: Long = 0L,
    ): StoragePlan {
        require(sourceBytes >= 0 && stagedArchiveBytes >= 0 && scratchArchiveBytes >= 0)
        val roots = StorageRoots.get(context)
        val database = directoryBytes(roots.databaseDatadir)
        val snapshot = maxOf(directoryBytes(roots.databaseSnapshots), database)
        val allocatable = context.getSystemService(StorageManager::class.java)
            .getAllocatableBytes(StorageManager.UUID_DEFAULT)
        val onRemovable = roots.contentVolume == ContentVolume.REMOVABLE
        val contentAllocatable = if (onRemovable) roots.content.usableSpace else 0L
        return calculate(sourceBytes, stagedArchiveBytes, scratchArchiveBytes, extractedEstimate,
            preparedEstimate, database, wineEstimate, snapshot, minimumReserve, allocatable,
            onRemovable, contentAllocatable)
    }

    private fun directoryBytes(root: java.io.File): Long {
        if (!root.exists()) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        internal fun calculate(
            source: Long, stagedArchive: Long, scratchArchive: Long, extracted: Long,
            prepared: Long = 0L, database: Long, wine: Long, snapshot: Long,
            minimumReserve: Long, allocatable: Long,
            contentOnRemovable: Boolean = false, contentAllocatable: Long = 0L,
        ): StoragePlan {
            require(
                listOf(
                    source, stagedArchive, scratchArchive, extracted, prepared, database,
                    wine, snapshot, minimumReserve, allocatable, contentAllocatable,
                ).all { it >= 0 },
            )
            if (contentOnRemovable) {
                val contentSubtotal = Math.addExact(
                    Math.addExact(
                        Math.addExact(Math.addExact(source, stagedArchive), scratchArchive),
                        extracted,
                    ),
                    prepared,
                )
                val internalSubtotal = Math.addExact(
                    Math.addExact(database, wine),
                    snapshot,
                )
                val contentMargin = ceil(contentSubtotal * 0.20).toLong()
                val internalMargin = maxOf(minimumReserve, ceil(internalSubtotal * 0.20).toLong())
                return StoragePlan(
                    source, extracted, prepared, database, wine, snapshot, internalMargin,
                    Math.addExact(internalSubtotal, internalMargin), allocatable,
                    contentOnRemovable = true,
                    contentVolumeRequiredBytes = Math.addExact(contentSubtotal, contentMargin),
                    contentVolumeAllocatableBytes = contentAllocatable,
                )
            }
            val subtotal = Math.addExact(
                Math.addExact(
                    Math.addExact(Math.addExact(source, stagedArchive), scratchArchive),
                    extracted,
                ),
                Math.addExact(
                    Math.addExact(Math.addExact(prepared, database), wine),
                    snapshot,
                ),
            )
            val margin = maxOf(minimumReserve, ceil(subtotal * 0.20).toLong())
            return StoragePlan(source, extracted, prepared, database, wine, snapshot, margin,
                Math.addExact(subtotal, margin), allocatable)
        }
    }
}

/**
 * The active flavor's measured extraction budget
 * (schemas/flavor.json storage.extracted_estimate_bytes), resolved through
 * the multi-flavor [ActiveFlavor] machine: the Home flavor switcher's
 * active lane drives the budget, and genuinely ambiguous registry states
 * (no qualified flavor, several qualified flavors without an explicit
 * active selection, a stale non-qualified selection) fail loud instead of
 * silently keeping VANILLA semantics.
 */
private fun flavorExtractedEstimate(): Long {
    val flavor = ActiveFlavor.resolve("storage budgets resolve the qualified lane")
    return requireNotNull(flavor.extractedEstimateBytes) {
        "${flavor.flavorId} is qualified without a measured storage budget"
    }
}

private fun flavorPreparedEstimate(): Long {
    val flavor = ActiveFlavor.resolve("storage budgets resolve the qualified lane")
    return flavor.preparedEstimateBytes ?: 0L
}
