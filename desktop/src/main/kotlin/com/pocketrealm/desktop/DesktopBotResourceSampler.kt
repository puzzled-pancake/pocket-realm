package com.pocketrealm.desktop

import com.pocketrealm.bots.ThermalLevel
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.WorldNative
import java.io.File
import java.lang.management.ManagementFactory

/**
 * One desktop bot-admission resource sample — the JVM twin of the Android
 * `BotRuntimeMetrics` (bots/BotResourceSampler.kt, unshared because it is
 * android.os-coupled). Everything the shared [com.pocketrealm.bots.BotAdmissionController]
 * consumes is here with the same meaning; the desktop-only process
 * surfaces (process CPU, the OS bean's committed-memory footprint) ride
 * along for the Bots verdict card.
 *
 * DESK-8's thermal input is a phone-ism: desktop always reports
 * [ThermalLevel.NONE] ("normal" on the Android thermal scale), the same
 * plugged-in constant posture the desktop already uses for
 * ChatterPowerMonitor.
 */
data class DesktopBotResourceMetrics(
    val sampledAtElapsedMs: Long,
    /** Performance-lane (performanceStatusNative) tick window. */
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
    /** CharacterDatabase probe RTT in ms; 0 = never sampled, UINT32_MAX = gate closed. */
    val dbProbeDelayMs: Long,
    /** Status-lane (statusNative) tick surface — the world's own tick view. */
    val worldState: Long,
    val statusTickCount: Long,
    val statusLastTickMs: Long,
    val statusMaxTickMs: Long,
    /** com.sun.management.OperatingSystemMXBean process CPU load, 0..1 (-1 unknown). */
    val processCpuLoad: Double,
    /** The OS bean's committed-memory footprint (MiB) — the JVM's process
     * memory surface (a true RSS read would need a PSAPI call; this is the
     * honest MXBean surface the desktop parity plan names). */
    val committedMemoryMiB: Long,
    /** System-wide free physical memory (MiB) — the ActivityManager.availMem analog. */
    val freeMemoryMiB: Long,
    val freeStorageMiB: Long,
    val thermal: ThermalLevel,
)

/**
 * the desktop sampler leg of the shared bot admission
 * controller — the JVM twin of the Android `BotResourceSampler`. The tick
 * lanes come from the world status surface exactly as the parity plans
 * name it: `WorldNative.performanceStatusNative()` carries the tick
 * p50/p95/p99 + hard-stall counters (the same 10-wide lane the Android
 * service feeds its sampler, already proven on desktop by RpHost.kt), and
 * `WorldNative.statusNative()` carries the world tick state. Process CPU
 * and memory come from the `com.sun.management.OperatingSystemMXBean`,
 * storage from `File.getUsableSpace` on the storage root. Thermal is the
 * constant normal level (NONE) — desktop has no thermal status.
 *
 * [read] is pure JVM given the two native arrays (tests inject fakes);
 * [readFromWorld] is the production pull used by the admission loop.
 */
class DesktopBotResourceSampler(private val storageRoot: File) {
    private val osBean = ManagementFactory.getPlatformMXBean(
        com.sun.management.OperatingSystemMXBean::class.java,
    )

    /** Android-shaped read: the caller supplies the two status arrays. */
    fun read(performance: LongArray, status: LongArray): DesktopBotResourceMetrics {
        require(performance.size == PERFORMANCE_STATUS_SIZE) {
            "world performance status contract mismatch: ${performance.size}"
        }
        require(status.size == WORLD_STATUS_WIDTH) {
            "world native status contract mismatch: ${status.size}"
        }
        val storageBytes = runCatching { storageRoot.usableSpace }.getOrDefault(0L)
        return DesktopBotResourceMetrics(
            sampledAtElapsedMs = System.currentTimeMillis(),
            tickSamples = performance[1],
            worldP50Ms = performance[2].toInt(),
            worldP95Ms = performance[3].toInt(),
            worldP99Ms = performance[4].toInt(),
            worldMaxMs = performance[5].toInt(),
            hardStallCount = performance[6].toInt(),
            hardStallTotal = performance[7],
            lastHardStallElapsedMs = performance[8],
            dbProbeDelayMs = performance[9],
            worldState = status[1],
            statusTickCount = status[4],
            statusLastTickMs = status[5],
            statusMaxTickMs = status[6],
            processCpuLoad = runCatching { osBean.processCpuLoad }.getOrDefault(-1.0),
            committedMemoryMiB = runCatching {
                osBean.committedVirtualMemorySize / MIB
            }.getOrDefault(0L),
            freeMemoryMiB = runCatching { osBean.freePhysicalMemorySize / MIB }.getOrDefault(0L),
            freeStorageMiB = storageBytes / MIB,
            thermal = ThermalLevel.NONE,
        )
    }

    /** The production pull: both world status verbs, then [read]. */
    fun readFromWorld(): DesktopBotResourceMetrics =
        read(WorldNative.performanceStatusNative(), WorldNative.statusNative())

    companion object {
        private const val MIB = 1024L * 1024L

        /** The shared ABI's performance lane width (the Android world
         * service's PERFORMANCE_STATUS_SIZE contract). */
        const val PERFORMANCE_STATUS_SIZE = 10

        /** The shared ABI's statusNative width (DesktopRuntimeBackend's
         * WORLD_STATUS_WIDTH contract). */
        const val WORLD_STATUS_WIDTH = 8
    }
}
