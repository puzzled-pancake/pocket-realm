package com.pocketrealm.server

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.win32.W32APIOptions
import java.io.File

/**
 * The desktop chatter-power twin of the Android
 * `server/ChatterPowerMonitor` (DESK-81): the same staged-file contract —
 * flat `enabled` / `dim` / `rung` / `at` lines the native scheduler re-reads
 * every tick — with the battery read swapped from BatteryManager broadcasts
 * to the Win32 `GetSystemPowerStatus` call (JNA). A Windows laptop off the
 * charger with a low gauge now gets the same courtesy pacing an Android
 * phone does, instead of the historical always-NORMAL constant.
 *
 * States (the Android twin's, verbatim):
 *  - OFF        ambience toggle off (no file content matters; native silent)
 *  - CONSTRAINED ambience on AND battery <= 15% off the charger
 *  - NORMAL     ambience on, everything else
 *
 * Charging rescues the dim state and an unreadable power read never dims on
 * its own (the Android posture).
 */
internal object DesktopChatterPowerMonitor {

    /** Native pocketllm::ChatterRung values — must move with the C++ enum. */
    const val RUNG_OFF = 0
    const val RUNG_CONSTRAINED = 3
    const val RUNG_NORMAL = 4

    /**
     * Battery dim threshold (percent). Only bites off the charger and well
     * down the gauge (the Android twin's constant).
     */
    internal const val LOW_BATTERY_PCT = 15

    internal const val FILE_NAME = "chatter-power.conf"

    /** One honest read of the machine's power state; null fields are
     * unreadable (an unreadable read never dims — the Android posture). */
    data class PowerReading(val acOnline: Boolean?, val batteryPct: Int?)

    /** The power-read seam: production calls Win32; tests fake it. */
    fun interface PowerSource {
        fun read(): PowerReading
    }

    /**
     * Pure state computation (the Android `computeRung` twin): CONSTRAINED
     * only when the machine is off the charger with a readable gauge at or
     * below the threshold. A null (unreadable) read stays NORMAL.
     */
    fun computeRung(reading: PowerReading): Int {
        val acOnline = reading.acOnline
        if (acOnline == null || acOnline) return RUNG_NORMAL
        val batteryPct = reading.batteryPct ?: return RUNG_NORMAL
        return if (batteryPct <= LOW_BATTERY_PCT) RUNG_CONSTRAINED else RUNG_NORMAL
    }

    /**
     * Apply the per-preset rung cap (the Android `applyRungCap` twin): a cap
     * outside the rung band is ignored (follow); inside the band it may only
     * LOWER the computed rung — never a resurrection of a disabled state.
     */
    fun applyRungCap(computed: Int, cap: Int): Int =
        if (cap in RUNG_OFF..RUNG_NORMAL) minOf(computed, cap) else computed

    /**
     * One synchronous staging of the power file at world start (the Android
     * `refreshOnce` contract): `enabled` is the ambience toggle; `rung` is
     * the computed rung capped by the selected profile's per-preset cap. The
     * native scheduler re-reads the file every tick, so the write is skipped
     * when enabled+dim+rung are unchanged (no pointless reader wake-ups).
     */
    fun refreshOnce(
        runDir: File,
        enabled: Boolean,
        rungCap: Int = -1,
        powerSource: PowerSource = SYSTEM,
    ): File {
        val target = File(runDir, FILE_NAME)
        val rung = if (!enabled) RUNG_OFF else applyRungCap(computeRung(powerSource.read()), rungCap)
        val dimmed = enabled && rung == RUNG_CONSTRAINED
        target.parentFile?.mkdirs()
        val content =
            "enabled=${if (enabled) 1 else 0}\n" +
                "dim=${if (dimmed) 1 else 0}\n" +
                "rung=$rung\n" +
                "at=${System.currentTimeMillis() / 1000L}\n"
        val current = runCatching { target.readText() }.getOrNull()
        if (current != null && samePowerState(current, content)) return target
        return DesktopChatterPowerFileWriter.write(target, content)
    }

    /** The enabled/dim/rung fields decide the skip (the `at` stamp never does). */
    private fun samePowerState(current: String, next: String): Boolean {
        fun field(text: String, key: String): String? =
            text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$key=") }
        return field(current, "enabled") == field(next, "enabled") &&
            field(current, "dim") == field(next, "dim") &&
            field(current, "rung") == field(next, "rung")
    }

    /**
     * The Win32 read: `GetSystemPowerStatus` via JNA (the same technique the
     * app already uses for SendInput/window verbs). ACLineStatus 1 = AC
     * powered, 0 = battery, 255 = unknown; BatteryLifePercent 255 = unknown.
     * Any load or call failure reads as the unreadable case (never dims).
     */
    val SYSTEM: PowerSource = PowerSource {
        runCatching {
            val kernel32 = Native.load(
                "kernel32",
                ExtendedKernel32::class.java,
                W32APIOptions.DEFAULT_OPTIONS,
            )
            val status = ExtendedKernel32.SYSTEM_POWER_STATUS()
            if (!kernel32.GetSystemPowerStatus(status)) {
                PowerReading(acOnline = null, batteryPct = null)
            } else {
                val ac = when (status.ACLineStatus.toInt() and 0xff) {
                    1 -> true
                    0 -> false
                    else -> null // 255 = unknown
                }
                val percentRaw = status.BatteryLifePercent.toInt() and 0xff
                val percent = if (percentRaw in 0..100) percentRaw else null
                PowerReading(acOnline = ac, batteryPct = percent)
            }
        }.getOrDefault(PowerReading(acOnline = null, batteryPct = null))
    }

    /** The Kernel32 additions JNA's stock interface does not declare. */
    private interface ExtendedKernel32 : Kernel32 {
        fun GetSystemPowerStatus(status: SYSTEM_POWER_STATUS): Boolean

        @Structure.FieldOrder(
            "ACLineStatus",
            "BatteryFlag",
            "BatteryLifePercent",
            "Reserved1",
            "BatteryLifeTime",
            "BatteryFullLifeTime",
        )
        class SYSTEM_POWER_STATUS : Structure() {
            @JvmField
            var ACLineStatus: Byte = 0

            @JvmField
            var BatteryFlag: Byte = 0

            @JvmField
            var BatteryLifePercent: Byte = 0

            @JvmField
            var Reserved1: Byte = 0

            @JvmField
            var BatteryLifeTime: Int = 0

            @JvmField
            var BatteryFullLifeTime: Int = 0
        }
    }
}

/** Atomic single-writer staging (the pid-temp + fsync + rename discipline). */
private object DesktopChatterPowerFileWriter {
    fun write(target: File, content: String): File {
        target.parentFile?.mkdirs()
        val temp = File(
            target.parentFile,
            ".${target.name}.${ProcessHandle.current().pid()}.tmp",
        )
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return target
    }
}
