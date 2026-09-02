package com.pocketrealm.server

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S10/E6: the power-ladder rung table (plan §4.6b). The mapping is pure
 * ([ChatterPowerMonitor.computeRung]) and pinned here against the ladder
 * conditions; the constants mirror the native pocketllm::ChatterRung
 * enum, which the host battery pins on the C++ side.
 */
class ChatterPowerMonitorTest {

    private fun rung(
        charging: Boolean = true,
        battery: Int? = 80,
        online: Boolean = true,
        thermal: Int? = PowerManager.THERMAL_STATUS_NONE,
        headroom: Int? = 50,
    ): Int = ChatterPowerMonitor.computeRung(charging, battery, online, thermal, headroom)

    @Test
    fun normalRequiresOnlinePlusPowerHeadroomPlusCoolThermals() {
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung())
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung(charging = false, battery = 60))
        // thermal forecast already spent steps down before the status trips
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(headroom = 0))
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(headroom = -5))
    }

    @Test
    fun constrainedOnOfflineMidBatteryModerateThermalOrSpentForecast() {
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(online = false))
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(battery = 40))
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(battery = 25))
        assertEquals(
            ChatterPowerMonitor.RUNG_CONSTRAINED,
            rung(thermal = PowerManager.THERMAL_STATUS_MODERATE),
        )
        // light thermal stress alone stays NORMAL (the ladder's first
        // step is MODERATE)
        assertEquals(
            ChatterPowerMonitor.RUNG_NORMAL,
            rung(thermal = PowerManager.THERMAL_STATUS_LIGHT),
        )
    }

    @Test
    fun criticalOnLowBatteryOrSevereThermal() {
        assertEquals(ChatterPowerMonitor.RUNG_CRITICAL, rung(battery = 19))
        assertEquals(
            ChatterPowerMonitor.RUNG_CRITICAL,
            rung(thermal = PowerManager.THERMAL_STATUS_SEVERE),
        )
        // charging does not rescue a severe-thermal die
        assertEquals(
            ChatterPowerMonitor.RUNG_CRITICAL,
            rung(thermal = PowerManager.THERMAL_STATUS_SEVERE, charging = true),
        )
    }

    @Test
    fun emergencyOnlyOnTheCompoundedWorstCase() {
        assertEquals(
            ChatterPowerMonitor.RUNG_EMERGENCY,
            rung(battery = 9, online = false, thermal = PowerManager.THERMAL_STATUS_SEVERE),
        )
        // any single recovery lifts it out of EMERGENCY - but a battery
        // under 20% is CRITICAL by its own condition regardless
        assertEquals(
            ChatterPowerMonitor.RUNG_CRITICAL,
            rung(battery = 9, online = false, thermal = PowerManager.THERMAL_STATUS_MODERATE),
        )
        assertEquals(
            ChatterPowerMonitor.RUNG_CRITICAL,
            rung(battery = 9, online = true, thermal = PowerManager.THERMAL_STATUS_SEVERE),
        )
        // a recovered battery off the charger offline lands CONSTRAINED
        assertEquals(
            ChatterPowerMonitor.RUNG_CONSTRAINED,
            rung(battery = 30, online = false, thermal = PowerManager.THERMAL_STATUS_NONE),
        )
    }

    @Test
    fun unknownBatteryNeverBlocksNormalAloneAndUnknownNetworkIsOffline() {
        // a device reporting no battery API on the charger, online, cool
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung(battery = null))
        // unknown network state is treated as offline (silence-safe)
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(battery = null, online = false))
    }
}
