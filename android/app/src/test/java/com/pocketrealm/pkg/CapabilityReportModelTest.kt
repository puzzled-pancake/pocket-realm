package com.pocketrealm.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure host-JVM model tests for the O05 capability/packaging result types.
 * No Android framework; mirrors the RealmNativeTest pattern.
 */
class CapabilityReportModelTest {

    @Test
    fun `RealmSoInfo isLoaded reflects loaded field`() {
        val loaded = PkgNative.RealmSoInfo(1, 0, "/apk/libpocketrealm.so",
            "libpocketrealm.so", "realm_err_str", 0x7f00000000L)
        assertTrue(loaded.isLoaded)
        val notLoaded = PkgNative.RealmSoInfo(0, 1, "", "libpocketrealm.so",
            "realm_err_str", 0L)
        assertFalse(notLoaded.isLoaded)
    }

    @Test
    fun `ExperimentResult ok and fail carry distinct evidence`() {
        val ok = ExperimentResult.ok("PKG-01", "run-1", mapOf("exitCode" to "0"), 12)
        assertTrue(ok.ok)
        assertEquals("OK", ok.code)
        val fail = ExperimentResult.fail("PKG-02", "run-1", "CONTAINMENT_NOT_PROVEN",
            listOf("newPid=false"), mapOf("childPidBeforeCrash" to "100"), 5)
        assertFalse(fail.ok)
        assertEquals("CONTAINMENT_NOT_PROVEN", fail.code)
        assertNotEquals(ok.code, fail.code)
    }

    @Test
    fun `crash kind constants are stable`() {
        // Pinned: 0=abort (the report-named PKG-02 trigger).
        assertEquals(0, PkgNative.CRASH_ABORT)
        assertEquals(1, PkgNative.CRASH_NULL_DEREF)
        assertEquals(2, PkgNative.CRASH_STACK_GUARD)
    }

    @Test
    fun `testRunId is non-blank and stable within a process`() {
        // PkgRunIds.current() must yield a non-blank id; repeated calls in one
        // process return the same id (a test-run id, not per-call).
        val a = PkgRunIds.current()
        val b = PkgRunIds.current()
        assertTrue("run id must be non-blank", a.isNotBlank())
        assertEquals("run id must be stable within a process", a, b)
    }
}
