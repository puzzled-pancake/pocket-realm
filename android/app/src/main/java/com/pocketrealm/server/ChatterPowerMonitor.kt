package com.pocketrealm.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * World chatter: the app-side half of the power ladder.
 *
 * The native scheduler (PlayerbotLlmChatter) runs inside the world
 * process and cannot read PowerManager; this monitor computes the ladder
 * rung from the device state and publishes it to the chatter power file
 * (flat `enabled` / `rung` / `at` lines, read by the native layer every
 * scheduler tick). [refreshOnce] runs at every world start; a single
 * epoch-keyed worker thread refreshes every [PERIOD_MS] while the realm
 * runs — if the app process dies, the native layer sees the file go
 * stale and degrades to the authored floor, never to unbounded
 * generation. The master ambience toggle is re-read on every refresh, so
 * flipping the switch takes effect mid-session (the conf key alone would
 * only apply at world start).
 *
 * Rung mapping (EMERGENCY is the worst):
 *  - EMERGENCY battery <10% AND offline AND thermal SEVERE (generation
 *    stops; the native authored event floor only)
 *  - CRITICAL  battery <20% OR thermal SEVERE (global-channel layer only)
 *  - CONSTRAINED offline OR battery <=40% OR thermal >= MODERATE or the
 *    [PowerManager.getThermalHeadroom] forecast already spent
 *  - NORMAL    online AND (charging OR battery >40%) AND thermal below
 *    MODERATE
 */
internal object ChatterPowerMonitor {

    /** Native pocketllm::ChatterRung values — must move with the C++ enum. */
    const val RUNG_OFF = 0
    const val RUNG_EMERGENCY = 1
    const val RUNG_CRITICAL = 2
    const val RUNG_CONSTRAINED = 3
    const val RUNG_NORMAL = 4

    private const val PERIOD_MS = 60_000L
    private const val FILE_NAME = "chatter-power.txt"

    private val epoch = AtomicLong(0)

    /** The power-file location (same run dir as the generated conf). */
    fun powerFile(context: Context): File =
        File(File(File(context.applicationContext.noBackupFilesDir, "server"), "run"), FILE_NAME)

    /**
     * Pure rung computation (unit-tested): every input is optional because
     * every read can fail on some device — a null battery or missing
     * thermal API degrades conservatively only where a rung condition
     * names it (an unknown battery never blocks NORMAL on its own, but an
     * unknown network state is treated as offline — silence-safe).
     */
    fun computeRung(
        charging: Boolean,
        batteryPct: Int?,
        online: Boolean,
        thermalStatus: Int?,
        headroomForecast: Int?,
    ): Int {
        val severe = thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val moderate = thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val forecastSpent = headroomForecast != null && headroomForecast <= 0
        val lowBattery = batteryPct != null && batteryPct < 20
        val nearEmpty = batteryPct != null && batteryPct < 10
        val midBattery = batteryPct != null && batteryPct <= 40
        return when {
            nearEmpty && !online && severe -> RUNG_EMERGENCY
            lowBattery || severe -> RUNG_CRITICAL
            !online || midBattery || moderate || forecastSpent -> RUNG_CONSTRAINED
            // NORMAL requires the full condition: online, power headroom
            // (charging or >40%), thermals below MODERATE
            online && (charging || (batteryPct == null || batteryPct > 40)) -> RUNG_NORMAL
            else -> RUNG_CONSTRAINED
        }
    }

    /** Reads the live device state; null where the API is missing. */
    fun currentRung(context: Context): Int {
        val appContext = context.applicationContext
        val batterySticky = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batterySticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val batteryPct = readBatteryPercent(appContext, batterySticky)
        val online = isOnline(appContext)
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalStatus: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { power?.currentThermalStatus }.getOrNull()
        } else {
            null
        }
        val headroom: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Thermal-headroom gate: the forecast says the thermal budget
            // is already spent even before the status flag trips
            runCatching { power?.getThermalHeadroom(0)?.toInt() }.getOrNull()
        } else {
            null
        }
        return computeRung(charging, batteryPct, online, thermalStatus, headroom)
    }

    /**
     * One synchronous refresh of the power file; the world-start path and
     * the periodic worker both land here. `enabled` is the ambience
     * toggle re-read live, so the master switch kills the layer
     * mid-session (the generated conf key alone would only apply at
     * world start).
     */
    fun refreshOnce(context: Context, enabled: Boolean): File {
        val target = powerFile(context)
        val rung = if (enabled) currentRung(context) else RUNG_OFF
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".$FILE_NAME.${android.os.Process.myPid()}.tmp")
        temp.writeText(
            "enabled=${if (enabled && rung != RUNG_OFF) 1 else 0}\n" +
                "rung=$rung\n" +
                "at=${System.currentTimeMillis() / 1000L}\n",
        )
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /**
     * Starts (or replaces) the single periodic refresher. Safe to call at
     * every world start; the previous worker exits on its next tick when
     * its epoch is superseded. INTENT: the refresher lives for the APP
     * PROCESS lifetime (not the realm's) - the world is a child of this
     * process, so a dead app kills the world and the staleness rule is
     * moot; an alive app keeps the file fresh even between realms, which
     * is harmless (the conf gates the native side). One wake per minute
     * against a battery-budgeted feature.
     */
    fun startPeriodic(context: Context, enabledProvider: () -> Boolean) {
        val myEpoch = epoch.incrementAndGet()
        val appContext = context.applicationContext
        Thread({
            while (epoch.get() == myEpoch) {
                runCatching { refreshOnce(appContext, enabledProvider()) }
                // the sleep sits inside its own catch: an interrupt must
                // never escape the bare thread lambda (that kills the
                // process on Android's default handler)
                try {
                    repeat((PERIOD_MS / 1000L).toInt()) {
                        if (epoch.get() != myEpoch) return@Thread
                        Thread.sleep(1000)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "chatter-power").start()
    }

    fun stopPeriodic() {
        epoch.incrementAndGet()
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

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching { cm.activeNetwork != null }.getOrDefault(false)
    }
}
