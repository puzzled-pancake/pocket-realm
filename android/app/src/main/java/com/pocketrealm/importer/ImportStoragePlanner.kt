package com.pocketrealm.importer

import android.content.Context
import android.os.storage.StorageManager
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.storage.StorageRoots
import kotlin.math.ceil

class ImportStoragePlanner(
    private val context: Context,
    // Flavor data: the qualified flavor's measured extraction budget
    // (schemas/flavor.json storage.extracted_estimate_bytes); the same
    // flavor block's storage.max_total_bytes is the import-total cap,
    // routed through ImportLimits.maxTotalBytes' default (ImportModel.kt).
    private val extractedEstimate: Long = flavorExtractedEstimate(),
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
        return calculate(sourceBytes, stagedArchiveBytes, scratchArchiveBytes, extractedEstimate,
            database, wineEstimate, snapshot, minimumReserve, allocatable)
    }

    private fun directoryBytes(root: java.io.File): Long {
        if (!root.exists()) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        internal fun calculate(
            source: Long, stagedArchive: Long, scratchArchive: Long, extracted: Long,
            database: Long, wine: Long, snapshot: Long, minimumReserve: Long, allocatable: Long,
        ): StoragePlan {
            require(
                listOf(
                    source, stagedArchive, scratchArchive, extracted, database, wine,
                    snapshot, minimumReserve, allocatable,
                ).all { it >= 0 },
            )
            val subtotal = Math.addExact(
                Math.addExact(Math.addExact(Math.addExact(source, stagedArchive), scratchArchive), extracted),
                Math.addExact(Math.addExact(database, wine), snapshot),
            )
            val margin = maxOf(minimumReserve, ceil(subtotal * 0.20).toLong())
            return StoragePlan(source, extracted, database, wine, snapshot, margin,
                Math.addExact(subtotal, margin), allocatable)
        }
    }
}

/**
 * The qualified flavor's measured extraction budget
 * (schemas/flavor.json storage.extracted_estimate_bytes). Exactly one
 * flavor may be qualified; any other registry state fails loud instead
 * of silently keeping VANILLA semantics.
 */
private fun flavorExtractedEstimate(): Long {
    val flavor = qualifiedFlavor()
    return requireNotNull(flavor.extractedEstimateBytes) {
        "${flavor.flavorId} is qualified without a measured storage budget"
    }
}

/** The single qualified flavor; any other registry state fails loud. */
private fun qualifiedFlavor(): com.pocketrealm.client.ClientFlavor {
    val qualified = ClientFlavors.qualified()
    require(qualified.size == 1) {
        "expected exactly one qualified flavor, found ${qualified.size} — " +
            "storage budgets resolve the qualified lane"
    }
    return qualified.single()
}
