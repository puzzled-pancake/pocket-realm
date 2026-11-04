package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the shared watchdog-wording contract, pinned on the desktop now
 * that the file compiles in the shared set (the reason tokens are inlined
 * literals — the android sampler's `const val`s — so both platforms word a
 * killed import identically).
 */
class DesktopImportWatchdogNoticesTest {
    @Test
    fun `low-memory deaths get the close-other-apps advice`() {
        val notice = watchdogRestartNotice(EXIT_REASON_LOW_MEMORY, attempt = 2, maxRestarts = 5)
        assertTrue("must name the memory cause: $notice", "free memory" in notice)
        assertTrue("must carry the restart counter: $notice", "(2/5)" in notice)
        val stopped = workerStoppedNotice(EXIT_REASON_LOW_MEMORY)
        assertTrue("must keep the resumable promise: $stopped", "Nothing already imported is lost" in stopped)
    }

    @Test
    fun `crash deaths get the resume-from-checkpoint advice`() {
        val stopped = workerStoppedNotice(EXIT_REASON_CRASH)
        assertTrue("must name the crash: $stopped", "crashed" in stopped)
        assertTrue("must offer resume: $stopped", "Resume" in stopped)
    }

    @Test
    fun `other deaths get the neutral system wording`() {
        assertTrue("system" in workerStoppedNotice("SIGNALED"))
        assertTrue("system" in workerStoppedNotice(null))
        assertEquals(
            "the restart notice is counter-only for non-low-memory deaths",
            "The system stopped the import worker — restarting it automatically (1/3).",
            watchdogRestartNotice("SIGNALED", attempt = 1, maxRestarts = 3),
        )
    }
}
