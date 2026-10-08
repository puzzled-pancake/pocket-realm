package com.pocketrealm.server

import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.server.DesktopChatterPowerMonitor.PowerReading
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-81: the desktop chatter-power monitor — the staged-file contract over
 * fake power sources (the plan's "unit test with a fake power source"). The
 * Android ChatterPowerMonitor semantics, verbatim: dim only when ambience is
 * on, the machine is off the charger, and the gauge reads <= 15%; an
 * unreadable read never dims; the per-preset cap may only lower.
 */
class DesktopChatterPowerMonitorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun source(acOnline: Boolean?, batteryPct: Int?): DesktopChatterPowerMonitor.PowerSource =
        DesktopChatterPowerMonitor.PowerSource { PowerReading(acOnline, batteryPct) }

    private fun staged(
        enabled: Boolean,
        source: DesktopChatterPowerMonitor.PowerSource,
        rungCap: Int = -1,
    ): Pair<File, String> {
        val runDir = folder.newFolder("run-${System.nanoTime()}")
        val file = DesktopChatterPowerMonitor.refreshOnce(runDir, enabled, rungCap, source)
        return file to file.readText()
    }

    @Test
    fun acPowerKeepsNormalRung() {
        val (_, body) = staged(enabled = true, source = source(acOnline = true, batteryPct = 80))
        assertEquals("enabled=1", field(body, "enabled"))
        assertEquals("rung=4", field(body, "rung"))
        assertEquals("dim=0", field(body, "dim"))
    }

    @Test
    fun lowBatteryOffChargerDimsToConstrained() {
        val (_, body) = staged(enabled = true, source = source(acOnline = false, batteryPct = 10))
        assertEquals("enabled=1", field(body, "enabled"))
        assertEquals("rung=3", field(body, "rung"))
        assertEquals("dim=1", field(body, "dim"))
    }

    @Test
    fun healthyBatteryOffChargerStaysNormal() {
        val (_, body) = staged(enabled = true, source = source(acOnline = false, batteryPct = 50))
        assertEquals("rung=4", field(body, "rung"))
        assertEquals("dim=0", field(body, "dim"))
    }

    @Test
    fun unreadablePowerReadNeverDims() {
        val (_, body) = staged(enabled = true, source = source(acOnline = null, batteryPct = null))
        assertEquals("rung=4", field(body, "rung"))
        assertEquals("dim=0", field(body, "dim"))
    }

    @Test
    fun unreadableGaugeOffChargerStaysNormal() {
        val (_, body) = staged(enabled = true, source = source(acOnline = false, batteryPct = null))
        assertEquals("rung=4", field(body, "rung"))
    }

    @Test
    fun ambienceOffStagesTheOffRung() {
        val (_, body) = staged(enabled = false, source = source(acOnline = false, batteryPct = 5))
        assertEquals("enabled=0", field(body, "enabled"))
        assertEquals("rung=0", field(body, "rung"))
        assertEquals("dim=0", field(body, "dim"))
    }

    @Test
    fun perPresetCapLowersButNeverRaises() {
        assertEquals(0, DesktopChatterPowerMonitor.applyRungCap(4, 0))
        assertEquals(2, DesktopChatterPowerMonitor.applyRungCap(4, 2))
        assertEquals(3, DesktopChatterPowerMonitor.applyRungCap(3, 4))
        // A cap outside the band is ignored (follow).
        assertEquals(4, DesktopChatterPowerMonitor.applyRungCap(4, -1))
        assertEquals(4, DesktopChatterPowerMonitor.applyRungCap(4, 9))
        // Never a resurrection of a disabled or dimmed state.
        assertEquals(0, DesktopChatterPowerMonitor.applyRungCap(0, 4))
    }

    @Test
    fun chargeRescuesTheDimState() {
        // Charging with a near-empty gauge: the Android twin's "charging
        // rescues the dim state".
        assertEquals(4, DesktopChatterPowerMonitor.computeRung(PowerReading(true, 3)))
    }

    @Test
    fun unchangedPowerStateSkipsTheRewrite() {
        val runDir = folder.newFolder("run-skip")
        val first = DesktopChatterPowerMonitor.refreshOnce(
            runDir, enabled = true, rungCap = -1,
            powerSource = source(acOnline = true, batteryPct = 80),
        )
        val firstStamp = field(first.readText(), "at")
        // The `at` stamp is second-resolution: give it room to differ if a
        // rewrite DID happen, then assert the stamp (hence the file) did not.
        Thread.sleep(1_100)
        val second = DesktopChatterPowerMonitor.refreshOnce(
            runDir, enabled = true, rungCap = -1,
            powerSource = source(acOnline = true, batteryPct = 80),
        )
        assertEquals(firstStamp, field(second.readText(), "at"))
        // A rung change (the cap participates) must restage.
        val capped = DesktopChatterPowerMonitor.refreshOnce(
            runDir, enabled = true, rungCap = 0,
            powerSource = source(acOnline = true, batteryPct = 80),
        )
        assertEquals("rung=0", field(capped.readText(), "rung"))
    }

    @Test
    fun stagingWritesUnderTheRequestedRunDirWithTheContractName() {
        val roots = DesktopStorageRoots(folder.newFolder("roots"))
        val runDir = File(roots.runtime, "server/run").apply { mkdirs() }
        val file = DesktopChatterPowerMonitor.refreshOnce(
            runDir, enabled = true, rungCap = -1,
            powerSource = source(acOnline = true, batteryPct = 80),
        )
        assertEquals(File(runDir, ServerRuntimeFiles.CHATTER_POWER_FILE_NAME), file)
        assertTrue(file.isFile)
    }

    private fun field(body: String, key: String): String =
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?: throw AssertionError("missing $key= line in:\n$body")
}
