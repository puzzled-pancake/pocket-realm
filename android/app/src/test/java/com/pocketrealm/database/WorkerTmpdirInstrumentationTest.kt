package com.pocketrealm.database

import org.junit.Test
import java.io.File

/**
 * R6-r6-arch-1 (W1 code-red round 6): MEASURES the java.io.tmpdir a real
 * AGP 9 AndroidUnitTest worker resolves — the recorded owner-visible
 * residual (out/completion/w1/WAVE-EXIT.md, Deferred section) was
 * code-derived ("not yet instrumented"); this test converts it into a
 * measured value on every canonical android run.
 *
 * The test deliberately asserts NOTHING about the value: whether the worker
 * tmpdir sits on E: (the round-2 R2-4-2 intent) or falls back to the OS
 * %TEMP% is the residual's open question, and the reconciliation gate
 * (test_wave_evidence.py::test_android_worker_tmpdir_measurement_matches_the_residual)
 * holds the WAVE-EXIT record to whatever is measured here. The value is
 * written relative to the worker's working directory (the app module dir),
 * never to the tmpdir itself — a wrong tmpdir must not cause a C: write.
 */
class WorkerTmpdirInstrumentationTest {

    @Test
    fun recordTheResolvedWorkerTmpdirForTheExitGate() {
        val tmpdir = System.getProperty("java.io.tmpdir") ?: "<unset>"
        val out = File("build/tmpdir-instrumentation.txt")
        out.parentFile?.mkdirs()
        out.writeText(tmpdir + "\n")
    }
}
