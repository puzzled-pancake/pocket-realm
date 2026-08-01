package com.pocketrealm.pkg

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * O05 G0 packaging experiments (report §8.4 PKG-01/02/06), run on-device by
 * the host driver tools/run_pkg_experiments.py on each AVD lane.
 *
 * Deterministic by default; the genuine 30-minute PKG-06 acceptance runs are
 * launched by the host driver with smokeSeconds=1800 (separate from this fast
 * path). Exit-criteria-relevant results are surfaced to logcat for the host
 * driver to capture and check into tests/avd/<lane>/evidence/.
 *
 * Instrumentation arguments (set by the host driver):
 *   lane          one of legacy|modern|16k
 *   smokeSeconds  PKG-06 duration (default 10; 1800 for the genuine acceptance run)
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PackagingExperimentTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val runner = PackagingExperimentRunner(ctx)
    private val args get() = InstrumentationRegistry.getArguments()

    private fun smokeSeconds(): Long =
        (args.getString("smokeSeconds", "10") ?: "10").toLongOrNull() ?: 10L

    private fun announce(result: ExperimentResult) {
        // Surfaced to logcat for the host driver; prefixed for easy grep.
        println("PKG_EXPERIMENT_RESULT\t${result.experiment}\tok=${result.ok}\tcode=${result.code}")
        result.evidence.forEach { (k, v) -> println("PKG_EXPERIMENT_EVIDENCE\t${result.experiment}\t$k=$v") }
    }

    @Test
    fun t1_capability_report_probes_device_fields() {
        val report = CapabilityReport.probe(ctx, PkgRunIds.current(ctx))
        // Sanity: the probe must return real device facts, not blanks.
        assertTrue("api level must be > 0", report.sdkInt > 0)
        assertTrue("page size must be > 0", report.pageSizeBytes > 0)
        assertTrue("abilist must be non-empty", report.abilist.isNotEmpty())
        assertNotNull("testRunId must be set", report.testRunId)
        // Write the report to a file the host driver pulls + compares.
        val f = report.writeToFile(ctx)
        println("PKG_CAPABILITY\tapi=${report.sdkInt}\tpage=${report.pageSizeBytes}\t" +
            "abilist=${report.abilist.joinToString(",")}\tallocatable=${report.allocatableBytes}\t" +
            "glVendor=${report.glVendor}\tglRenderer=${report.glRenderer}\trunId=${report.testRunId}\t" +
            "reportPath=${f.absolutePath}")
    }

    @Test
    fun t2_pkg01_launcher_executes_or_documents_no_path() {
        val r = kotlinx.coroutines.runBlocking { runner.runPkg01() }
        announce(r)
        // PKG-01 must either execute (experiment variant) or document the
        // production-variant no-executable-path behavior honestly. A genuine
        // failure (exec error / nonzero exit) fails the test.
        val acceptable = r.ok || r.code == "NO_EXECUTABLE_FS_PATH"
        assertTrue("PKG-01 failed unexpectedly: ${r.detail} :: ${r.evidence}", acceptable)
    }

    @Test
    fun t3_pkg02_crash_containment_and_restart() {
        val r = kotlinx.coroutines.runBlocking { runner.runPkg02() }
        announce(r)
        assertTrue("PKG-02 containment not proven: ${r.detail} :: ${r.evidence}", r.ok)
        assertEquals("CONTAINMENT_PROVEN", r.code)
    }

    @Test
    fun t4_pkg06_smoke_loads_all_libs() {
        val r = kotlinx.coroutines.runBlocking { runner.runPkg06(smokeSeconds()) }
        announce(r)
        assertTrue("PKG-06 smoke failed: ${r.detail} :: ${r.evidence}", r.ok)
    }
}
