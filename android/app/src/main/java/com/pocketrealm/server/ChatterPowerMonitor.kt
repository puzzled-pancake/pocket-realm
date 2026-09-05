package com.pocketrealm.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import java.io.File

/**
 * World chatter: the app-side half of the power state.
 *
 * The native scheduler (PlayerbotLlmChatter) runs inside the world
 * process; this monitor stages the power file (flat `enabled` / `dim` /
 * `rung` / `at` lines — the native layer reads `enabled` and `rung`, the
 * rest are diagnostics — re-read by the native layer every scheduler
 * tick).
 * [refreshOnce] runs at every world start. The master ambience toggle is
 * the switch — the user asked for a loud world or they did not, and no
 * sensor second-guesses that. The only automatic quieting is a low-battery
 * courtesy dim (a dying phone still playing gets a slower cadence, not
 * silence). Thermal throttling is the OS's job: Android already slows the
 * CPU when hot, which naturally paces generation without a second,
 * coarser throttle in our code.
 *
 * States (DIM is a courtesy, not a ladder):
 *  - OFF     ambience toggle off (no file content matters; native stays silent)
 *  - DIM     ambience on AND battery <= 15% off the charger
 *  - NORMAL  ambience on, everything else
 *
 * Charging rescues the dim state and an unreadable battery read never dims
 * on its own. Connectivity and thermals are not inputs: an offline or hot
 * phone with chatter on gets chatter, paced by the OS underneath.
 */
internal object ChatterPowerMonitor {

    /** Native pocketllm::ChatterRung values — must move with the C++ enum. */
    const val RUNG_OFF = 0
    const val RUNG_EMERGENCY = 1
    const val RUNG_CRITICAL = 2
    const val RUNG_CONSTRAINED = 3
    const val RUNG_NORMAL = 4

    /**
     * Battery dim threshold (percent). Only bites off the charger and well
     * down the gauge: the dim exists to stretch a dying phone, not to
     * ration a half-charged one.
     */
    internal const val LOW_BATTERY_PCT = 15

    private const val FILE_NAME = "chatter-power.txt"

    /** The power-file location (same run dir as the generated conf). */
    fun powerFile(context: Context): File =
        File(File(File(context.applicationContext.noBackupFilesDir, "server"), "run"), FILE_NAME)

    /**
     * Pure state computation (unit-tested): dim only when the ambience
     * switch is on, the phone is off the charger, and the battery reads at
     * or below the threshold. A null (unreadable) battery never dims.
     */
    fun computeRung(
        charging: Boolean,
        batteryPct: Int?,
        @Suppress("UNUSED_PARAMETER") online: Boolean? = null,
        @Suppress("UNUSED_PARAMETER") thermalStatus: Int? = null,
    ): Int {
        if (charging) return RUNG_NORMAL
        if (batteryPct == null) return RUNG_NORMAL
        return if (batteryPct <= LOW_BATTERY_PCT) RUNG_CONSTRAINED else RUNG_NORMAL
    }

    /** Reads the live device state; null battery where the API is missing. */
    fun currentRung(context: Context): Int {
        val appContext = context.applicationContext
        val batterySticky = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batterySticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val batteryPct = readBatteryPercent(appContext, batterySticky)
        return computeRung(charging, batteryPct)
    }

    /**
     * One synchronous staging of the power file at world start. `enabled`
     * is the ambience toggle; the dim flag is the low-battery courtesy.
     * The `at` line is a diagnostic stamp (write time); the native side
     * reads only `enabled` and `rung` — writer and world live and die in
     * the same process, so there is no staleness protocol to keep.
     */
    fun refreshOnce(context: Context, enabled: Boolean): File {
        val target = powerFile(context)
        val rung = if (enabled) currentRung(context) else RUNG_OFF
        val dimmed = enabled && rung == RUNG_CONSTRAINED
        target.parentFile?.mkdirs()
        val content =
            "enabled=${if (enabled) 1 else 0}\n" +
                "dim=${if (dimmed) 1 else 0}\n" +
                "rung=$rung\n" +
                "at=${System.currentTimeMillis() / 1000L}\n"
        // The native scheduler re-reads this file every tick: skip the
        // write when enabled+dim are unchanged so a battery broadcast that
        // changes nothing doesn't bump mtime and wake the reader for nothing.
        val current = runCatching { target.readText() }.getOrNull()
        if (current != null && samePowerState(current, content)) return target
        val temp = File(
            target.parentFile,
            ".$FILE_NAME.${android.os.Process.myPid()}.${System.nanoTime()}.tmp",
        )
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    private fun samePowerState(current: String, next: String): Boolean {
        fun field(text: String, key: String): String? =
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$key=") }
        return field(current, "enabled") == field(next, "enabled") &&
            field(current, "dim") == field(next, "dim")
    }

    /**
     * The battery-event refresh: no per-minute writer (that thread was the
     * collapsed design's first deletion). The file is staged at world start
     * by [refreshOnce] (via ServerRuntimeFiles) and re-staged only when the
     * battery picture can actually change — low battery, plugged in, or
     * unplugged. Each event recomputes the full state, so the refresh is
     * idempotent. The refresh hops to a background thread: the enabled
     * provider does a blocking DataStore read, and broadcasts arrive on the
     * main thread. Registered on the application context; [stopPeriodic]
     * unregisters at world stop (graceful and forced).
     */
    @Volatile private var powerReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var receiverContext: Context? = null

    fun startPeriodic(context: Context, enabledProvider: () -> Boolean) {
        val appContext = context.applicationContext
        stopPeriodic(appContext)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                Thread {
                    runCatching { refreshOnce(appContext, enabledProvider()) }
                }.apply { name = "chatter-power-refresh"; isDaemon = true }.start()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { appContext.registerReceiver(receiver, filter) }
            .onSuccess {
                powerReceiver = receiver
                receiverContext = appContext
            }
        runCatching { refreshOnce(appContext, enabledProvider()) }
    }

    fun stopPeriodic(context: Context? = null) {
        val receiver = powerReceiver ?: return
        val appContext = context?.applicationContext ?: receiverContext
        if (appContext != null) runCatching { appContext.unregisterReceiver(receiver) }
        powerReceiver = null
        receiverContext = null
    }

    private fun readBatteryPercent(context: Context, sticky: Intent?): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 0..100) return pct
        // fall back to the sticky broadcast's scaled level
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }
}
