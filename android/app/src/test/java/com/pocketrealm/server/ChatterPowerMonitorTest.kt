package com.pocketrealm.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The collapsed chatter power states: on/off plus a low-battery courtesy
 * dim. The user asked for a loud world or they did not — no sensor
 * second-guesses that. Thermal throttling is the OS's job underneath.
 * The RUNG_* constants mirror the native pocketllm::ChatterRung enum,
 * which the host battery pins on the C++ side.
 *
 * Mapping: NORMAL = chatter on, CONSTRAINED = the dim courtesy (reuses
 * the stretched-cadence native row), OFF = toggle off. EMERGENCY/CRITICAL
 * are never produced by the collapsed computation.
 */
class ChatterPowerMonitorTest {

    private fun rung(
        charging: Boolean = true,
        battery: Int? = 80,
    ): Int = ChatterPowerMonitor.computeRung(charging, battery)

    @Test
    fun normalIsTheDefaultWheneverNothingDims() {
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung())
        // low battery on the charger is transient, not a dim
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung(charging = true, battery = 4))
        // off the charger, anything above the dim line is NORMAL
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung(charging = false, battery = 16))
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung(charging = false, battery = 60))
    }

    @Test
    fun dimOnlyOnLowOffChargerBattery() {
        // at or below the 15 line off the charger dims
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(charging = false, battery = 15))
        assertEquals(ChatterPowerMonitor.RUNG_CONSTRAINED, rung(charging = false, battery = 4))
        // charging rescues the dim state
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung(charging = true, battery = 10))
    }

    @Test
    fun unreadableBatteryNeverDims() {
        // an unreadable gauge is not a low gauge
        assertEquals(ChatterPowerMonitor.RUNG_NORMAL, rung(battery = null))
        assertEquals(
            ChatterPowerMonitor.RUNG_NORMAL,
            rung(charging = false, battery = null),
        )
    }

    @Test
    fun legacySensorsAreIgnored() {
        // connectivity and thermals are not chatter inputs: an offline or
        // hot phone with chatter on gets chatter. Signatures keep the
        // extra params so older call sites still compile.
        assertEquals(
            ChatterPowerMonitor.RUNG_NORMAL,
            ChatterPowerMonitor.computeRung(
                charging = true,
                batteryPct = 80,
                online = false,
                thermalStatus = android.os.PowerManager.THERMAL_STATUS_SEVERE,
            ),
        )
        assertEquals(
            ChatterPowerMonitor.RUNG_CONSTRAINED,
            ChatterPowerMonitor.computeRung(
                charging = false,
                batteryPct = 10,
                online = false,
                thermalStatus = android.os.PowerManager.THERMAL_STATUS_SEVERE,
            ),
        )
    }
}
