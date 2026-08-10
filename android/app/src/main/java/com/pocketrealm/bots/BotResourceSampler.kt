package com.pocketrealm.bots

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import java.io.File

data class BotRuntimeMetrics(
    val sampledAtElapsedMs: Long,
    val tickSamples: Long,
    val worldP50Ms: Int,
    val worldP95Ms: Int,
    val worldP99Ms: Int,
    val worldMaxMs: Int,
    /** Rolling 2,048-tick diagnostic window; never use as an event counter. */
    val hardStallCount: Int,
    /** Monotonic process-lifetime count used to detect newly observed stalls. */
    val hardStallTotal: Long,
    val lastHardStallElapsedMs: Long,
    val worldPssMiB: Long,
    val freeMemoryMiB: Long,
    val freeStorageMiB: Long,
    val thermal: ThermalLevel,
)

/** Reads only process/system counters available to an ordinary app domain. */
class BotResourceSampler(context: Context, private val storageRoot: File) {
    private val activity = context.getSystemService(ActivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)

    fun read(performance: LongArray): BotRuntimeMetrics {
        require(performance.size == 9)
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val storage = StatFs(storageRoot.absolutePath)
        return BotRuntimeMetrics(
            sampledAtElapsedMs = SystemClock.elapsedRealtime(),
            tickSamples = performance[1],
            worldP50Ms = performance[2].toInt(),
            worldP95Ms = performance[3].toInt(),
            worldP99Ms = performance[4].toInt(),
            worldMaxMs = performance[5].toInt(),
            hardStallCount = performance[6].toInt(),
            hardStallTotal = performance[7],
            lastHardStallElapsedMs = performance[8],
            worldPssMiB = Debug.getPss() / 1024L,
            freeMemoryMiB = memory.availMem / MIB,
            freeStorageMiB = storage.availableBytes / MIB,
            thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalLevel(power.currentThermalStatus)
            } else {
                ThermalLevel.NONE
            },
        )
    }

    private fun thermalLevel(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
        else -> ThermalLevel.NONE
    }

    companion object { private const val MIB = 1024L * 1024L }
}
