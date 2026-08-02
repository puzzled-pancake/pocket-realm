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
    fun plan(sourceBytes: Long): StoragePlan {
        require(sourceBytes >= 0)
        val roots = StorageRoots.get(context)
        val database = directoryBytes(roots.databaseDatadir)
        val snapshot = maxOf(directoryBytes(roots.databaseSnapshots), database)
        val allocatable = context.getSystemService(StorageManager::class.java)
            .getAllocatableBytes(StorageManager.UUID_DEFAULT)
        return calculate(sourceBytes, extractedEstimate, database, wineEstimate, snapshot,
            minimumReserve, allocatable)
    }

    private fun directoryBytes(root: java.io.File): Long {
        if (!root.exists()) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        internal fun calculate(
            source: Long, extracted: Long, database: Long, wine: Long, snapshot: Long,
            minimumReserve: Long, allocatable: Long,
        ): StoragePlan {
            require(listOf(source, extracted, database, wine, snapshot, minimumReserve, allocatable)
                .all { it >= 0 })
            val subtotal = Math.addExact(Math.addExact(source, extracted),
                Math.addExact(Math.addExact(database, wine), snapshot))
            val margin = maxOf(minimumReserve, ceil(subtotal * 0.20).toLong())
            return StoragePlan(source, extracted, database, wine, snapshot, margin,
                Math.addExact(subtotal, margin), allocatable)
        }
    }
}
