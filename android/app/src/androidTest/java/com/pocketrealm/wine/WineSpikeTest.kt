package com.pocketrealm.wine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * O06 Phase-1 Wine feasibility spike measurements (S-1/S-2/S-3), run on-device
 * by the host driver tools/run_wine_spike.py on each AVD lane (Modern 4KB, 16K).
 *
 * Each test runs one measurement and announces structured logcat lines the host
 * driver greps:
 *   WINE_SPIKE_S1_RESULT   ok=true   code=LOADER_PROVEN
 *   WINE_SPIKE_S1_EVIDENCE key=value
 *
 * Instrumentation arguments (set by the host driver):
 *   lane          modern|16k
 *   variant       pkgExperiment|debug
 *   smokeSeconds  (reserved for S-3 timeout)
 *
 * All runtime variants use the PKG-01-qualified extracted packaging model;
 * pkgExperiment remains the historical regression lane.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WineSpikeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val runner = WineSpikeRunner(ctx)
    private val args get() = InstrumentationRegistry.getArguments()

    @Test
    fun t1_s1_effective_loader() = runBlocking {
        val result = runner.runS1()
        assertTrue(
            "S-1 (effective loader) failed: ${result.code} :: ${result.detail.joinToString()}",
            result.ok
        )
    }

    @Test
    fun t2_s2_wineboot_pe_resolution() = runBlocking {
        val result = runner.runS2()
        assertTrue(
            "S-2 (wineboot PE resolution) failed: ${result.code} :: ${result.detail.joinToString()}",
            result.ok
        )
    }

    @Test
    fun t3_s3_x11_gdi_window() = runBlocking {
        val result = runner.runS3()
        // S-3 is DEFERRED until the X-server harness is vendored. We do NOT
        // weaken acceptance — the test fails honestly, recording the deferral.
        // When the harness is ready, this assertion flips to result.ok.
        assertTrue(
            "S-3 (X11/GDI window) not yet passing: ${result.code} :: ${result.detail.joinToString()}",
            result.ok
        )
    }
}
