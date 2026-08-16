package com.pocketrealm.importer

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Curated device identity for benchmark labeling. Cooling is not derivable
 * from any Android API, so known handhelds carry a small curated entry keyed
 * by [Build.MODEL]; everything else falls back to Build fields.
 */
object DeviceProfile {
    data class Info(
        val label: String,
        val model: String,
        val soc: String,
        val activelyCooled: Boolean,
        val abi: String,
        val api: Int,
        val cores: Int,
        val ramBytes: Long,
    )

    private data class Curated(val soc: String, val activelyCooled: Boolean)

    private val curated = mapOf(
        // Retroid Pocket 6: Snapdragon 8 Gen 2 (kalama) with an internal fan.
        "Retroid Pocket 6" to Curated("Snapdragon 8 Gen 2", activelyCooled = true),
    )

    /**
     * A measured full-import run kept in code as the reference yardstick for
     * future devices and regressions. Numbers are read from the journal, never
     * hand-estimated.
     */
    data class Baseline(
        val deviceLabel: String,
        val totalMs: Long,
        val copyMs: Long,
        val dataMs: Long,
        val mmapMs: Long,
        val mmapMaps: Int,
        val mmapThreads: Int,
    )

    // First full 43-map import on the RP6 (journal import bb562f86, 2026-08-16):
    // all stages on-device, navmesh generation dominated at 6 threads.
    internal val RETROID_POCKET_6_BASELINE = Baseline(
        deviceLabel = "Retroid Pocket 6",
        totalMs = 1_120_336L,
        copyMs = 106_251L,
        dataMs = 1_014_085L,
        mmapMs = 975_281L,
        mmapMaps = 43,
        mmapThreads = 6,
    )

    fun current(context: Context): Info = from(
        model = Build.MODEL ?: Build.DEVICE ?: "Android device",
        socHint = socFromBuild(),
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        api = Build.VERSION.SDK_INT,
        cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        ramBytes = totalRamBytes(context),
    )

    internal fun from(model: String, socHint: String, abi: String, api: Int, cores: Int, ramBytes: Long): Info {
        val known = curated[model]
        return Info(
            label = model,
            model = model,
            soc = known?.soc ?: socHint.ifBlank { "unknown" },
            activelyCooled = known?.activelyCooled == true,
            abi = abi,
            api = api,
            cores = cores,
            ramBytes = ramBytes,
        )
    }

    private fun socFromBuild(): String {
        if (Build.VERSION.SDK_INT >= 31) {
            val parts = listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { !it.isNullOrBlank() }
            if (parts.isNotEmpty()) return parts.joinToString(" ")
        }
        return Build.HARDWARE ?: ""
    }

    private fun totalRamBytes(context: Context): Long = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)
}
