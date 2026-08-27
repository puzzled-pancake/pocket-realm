package com.pocketrealm.importer

import android.content.Context
import android.os.storage.StorageManager
import com.pocketrealm.storage.StorageRoots
import kotlin.math.ceil

class ImportStoragePlanner(
    private val context: Context,
    private val extractedEstimate: Long = 4L * ImportLimits.GIB,
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
