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
 * Packaging experiments, run on-device by
 * the host driver tools/run_pkg_experiments.py on each AVD lane.
 *
 * Deterministic by default; the genuine 30-minute smoke acceptance runs are
 * launched by the host driver with smokeSeconds=1800 (separate from this fast
 * path). Exit-criteria-relevant results are surfaced to logcat for the host
 * driver to capture and check into tests/avd/<lane>/evidence/.
 *
 * Instrumentation arguments (set by the host driver):
 *   lane          one of legacy|modern|16k
 *   smokeSeconds  smoke run duration (default 10; 1800 for the genuine acceptance run)
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
        // Write the capability JSON to a file the host driver pulls + compares.
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
        // The launcher experiment must PASS in one of two honest shapes:
        //   - experiment variant: launcher executed  -> ok=true  code=OK
        //   - production variant : no fs exec path    -> ok=true  code=NO_EXECUTABLE_FS_PATH
        // A FAILED runPkg01 (ok=false) must fail the test even if it nominally
        // reports NO_EXECUTABLE_FS_PATH — e.g. the experiment variant expected
        // extraction but the launcher was missing. Gate on ok, not on the code.
        assertTrue(
            "launcher experiment did not pass: ok=${r.ok} code=${r.code} :: ${r.detail} :: ${r.evidence}",
            r.ok && (r.code == "OK" || r.code == "NO_EXECUTABLE_FS_PATH")
        )
    }

    @Test
    fun t3_pkg02_crash_containment_and_restart() {
        val r = kotlinx.coroutines.runBlocking { runner.runPkg02() }
        announce(r)
        assertTrue("containment not proven: ${r.detail} :: ${r.evidence}", r.ok)
        assertEquals("CONTAINMENT_PROVEN", r.code)
    }

    @Test
    fun t4_pkg06_smoke_loads_all_libs() {
        val r = kotlinx.coroutines.runBlocking { runner.runPkg06(smokeSeconds()) }
        announce(r)
        assertTrue("smoke run failed: ${r.detail} :: ${r.evidence}", r.ok)
    }
}
