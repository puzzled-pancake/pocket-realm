package com.pocketrealm.pkg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure host-JVM tests for the runner's host-driver-facing log format.
 * The full runPkg06 path needs the :pkg child process (device-only); what is
 * pinnable on the host is the tick-line text the host driver greps from logcat.
 */
class PackagingExperimentRunnerTest {

    @Test
    fun `pkg06 tick line keeps the tab-separated format the host driver greps`() {
        // Pinned: tools/run_pkg_experiments.py matches the "PKG-06 TICK" substring
        // in logcat to recover the full per-tick history; the field order and
        // tab separators are that contract.
        assertEquals(
            "PKG-06 TICK\ttick=7\tt=1728000000123\thello=pocket-realm-pkg-ok\tpid=4242\tpageSize=16384",
            PackagingExperimentRunner.pkg06TickLine(
                tick = 7, atMs = 1_728_000_000_123L,
                hello = "pocket-realm-pkg-ok", pid = 4242, pageSize = 16384,
            ),
        )
    }
}
