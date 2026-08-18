package com.pocketrealm.pkg

import com.pocketrealm.log.AppLog

/**
 * Typed result of one packaging experiment. Carried
 * out-of-band from the UI: the host driver reads these from the device and
 * checks them into tests/avd/<lane>/evidence/. Honest by construction — a
 * failure sets [ok]=false and keeps the raw evidence rather than being hidden.
 */
data class ExperimentResult(
    val experiment: String,
    val ok: Boolean,
    val code: String,
    val evidence: Map<String, String>,
    val detail: List<String>,
    val durationMs: Long,
    val testRunId: String,
    val capturedAtEpochMs: Long,
) {
    fun toLogString(): String =
        "[$experiment] ok=$ok code=$code (${durationMs}ms) " +
            if (detail.isEmpty()) "" else detail.joinToString("; ")

    companion object {
        private const val TAG = "PkgExperiment"
        fun ok(exp: String, runId: String, evidence: Map<String, String>, ms: Long, code: String = "OK"): ExperimentResult {
            val r = ExperimentResult(exp, true, code, evidence, emptyList(), ms, runId, System.currentTimeMillis())
            AppLog.i(TAG, r.toLogString())
            return r
        }
        fun fail(exp: String, runId: String, code: String, detail: List<String>, evidence: Map<String, String>, ms: Long): ExperimentResult {
            val r = ExperimentResult(exp, false, code, evidence, detail, ms, runId, System.currentTimeMillis())
            AppLog.e(TAG, r.toLogString() + " :: " + detail.joinToString("; "))
            return r
        }
    }
}
