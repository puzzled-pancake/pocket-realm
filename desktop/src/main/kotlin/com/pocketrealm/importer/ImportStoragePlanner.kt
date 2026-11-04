package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import kotlin.math.ceil

/**
 * the desktop storage budget planner — the JVM twin of the Android
 * importer/ImportStoragePlanner (Context + android.os.storage.StorageManager)
 * with the identical [calculate] arithmetic over `java.io.File.getUsableSpace`
 * on the managed-content volume (the register's named substitution).
 *
 * The budget's owners are the same on both platforms: the source (folder lane)
 * or staged archive + installer scratch (archive lanes), the flavor's measured
 * extraction estimate, the database datadir, and the platform caps (the wine
 * estimate row is retained from the Android formula so the desktop refuses no
 * later than Android would — on Windows it simply over-reserves by design).
 */
class ImportStoragePlanner(
    private val roots: DesktopStorageRoots,
    // Flavor data: the qualified flavor's measured extraction budget
    // (schemas/flavor.json storage.extracted_estimate_bytes); the same
    // flavor block's storage.max_total_bytes is the import-total cap,
    // routed through ImportLimits.maxTotalBytes' default (ImportModel.kt).
    private val extractedEstimate: Long = flavorExtractedEstimate(),
    // The prepared-server-data OUTPUT the
    // flow writes after the import (the o11-server generation) — the
    // flavor's measured figure (schemas/flavor.json
    // storage.prepared_estimate_bytes); without this row a fresh-device
    // import→prep under-budgeted by the full prepared size.
    private val preparedEstimate: Long = flavorPreparedEstimate(),
    // wineEstimate and minimumReserve are platform caps, not flavor data.
    private val wineEstimate: Long = 1L * ImportLimits.GIB,
    private val minimumReserve: Long = 2L * ImportLimits.GIB,
    // The allocatable-volume read is injectable so the pipeline tests can pin
    // the arithmetic without borrowing the host TEMP volume's real headroom;
    // production keeps the StorageManager twin's named substitution.
    private val allocatableBytes: () -> Long = { roots.content.usableSpace.coerceAtLeast(0L) },
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
        // BOTH providers' live state counts —
        // the SHIPPED-DEFAULT MariaDB datadir (roots.mariadbDatadir, the
        // seeded world database) and the SQLite datadir — and the snapshot
        // floor measures the REAL snapshot store (roots.databaseSnapshots),
        // maxed over the live databases (the android twin measures
        // databaseDatadir AND databaseSnapshots). Measuring only the sqlite
        // datadir under-counted the durable state on the shipped lane, and
        // maxOf(same, same) was a no-op.
        val database = directoryBytes(roots.mariadbDatadir) + directoryBytes(roots.sqliteDatadir)
        val snapshot = maxOf(directoryBytes(roots.databaseSnapshots), database)
        val allocatable = allocatableBytes()
        return calculate(
            sourceBytes, stagedArchiveBytes, scratchArchiveBytes, extractedEstimate,
            preparedEstimate, database, wineEstimate, snapshot, minimumReserve, allocatable,
        )
    }

    private fun directoryBytes(root: File): Long {
        if (!root.exists()) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        internal fun calculate(
            source: Long,
            stagedArchive: Long,
            scratchArchive: Long,
            extracted: Long,
            prepared: Long = 0L,
            database: Long,
            wine: Long,
            snapshot: Long,
            minimumReserve: Long,
            allocatable: Long,
            contentOnRemovable: Boolean = false,
            contentAllocatable: Long = 0L,
        ): StoragePlan {
            require(
                listOf(
                    source, stagedArchive, scratchArchive, extracted, prepared, database,
                    wine, snapshot, minimumReserve, allocatable, contentAllocatable,
                ).all { it >= 0 },
            )
            // The Android twin's removable-volume split, kept so the shared
            // planner test pins one formula; the desktop lane never sets it.
            if (contentOnRemovable) {
                val contentSubtotal = Math.addExact(
                    Math.addExact(
                        Math.addExact(Math.addExact(source, stagedArchive), scratchArchive),
                        extracted,
                    ),
                    prepared,
                )
                val internalSubtotal = Math.addExact(Math.addExact(database, wine), snapshot)
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
            return StoragePlan(
                source, extracted, prepared, database, wine, snapshot, margin,
                Math.addExact(subtotal, margin), allocatable,
            )
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
