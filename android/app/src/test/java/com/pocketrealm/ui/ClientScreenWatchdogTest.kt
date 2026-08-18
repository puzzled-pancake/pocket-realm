package com.pocketrealm.ui

import com.pocketrealm.importer.watchdogRestartNotice
import com.pocketrealm.importer.workerStoppedNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientScreenWatchdogTest {
    // Base facts describe a stalled worker that should be restarted: busy
    // journal phase, worker process gone for over a minute, data-prep lane,
    // restart budget available, and the journal's source URI known.
    private val stalled = ImportWatchdogFacts(
        phaseBusy = true,
        workerPresent = false,
        updatedAgeSeconds = 60,
        dataPreparationEnabled = true,
        restartsUsed = 0,
        msSinceLastRestart = Long.MAX_VALUE,
        hasSourceUri = true,
    )

    private fun facts(mutate: ImportWatchdogFacts.() -> ImportWatchdogFacts): ImportWatchdogFacts =
        stalled.copy().mutate()

    @Test fun idleOrAliveWorkersNeverTrigger() {
        assertEquals(ImportWatchdogAction.NONE, importWatchdogAction(facts {
            copy(workerPresent = true)
        }))
        assertEquals(ImportWatchdogAction.NONE, importWatchdogAction(facts {
            copy(phaseBusy = false)
        }))
        assertEquals(ImportWatchdogAction.NONE, importWatchdogAction(facts {
            copy(updatedAgeSeconds = 25)
        }))
    }

    @Test fun stalledWorkerRestartsWithJournalSourceUri() {
        assertEquals(ImportWatchdogAction.RESTART, importWatchdogAction(stalled))
    }

    @Test fun noSourceUriFallsBackToManualResume() {
        assertEquals(
            ImportWatchdogAction.SHOW_MANUAL_RESUME,
            importWatchdogAction(facts { copy(hasSourceUri = false) }),
        )
    }

    @Test fun attemptCapAndRateLimitFallBackToManualResume() {
        assertEquals(
            ImportWatchdogAction.SHOW_MANUAL_RESUME,
            importWatchdogAction(facts { copy(restartsUsed = 4) }),
        )
        assertEquals(
            ImportWatchdogAction.SHOW_MANUAL_RESUME,
            importWatchdogAction(facts { copy(msSinceLastRestart = 59_999) }),
        )
    }

    @Test fun databaseLaneNeverAutoRestarts() {
        // The database lane intentionally never prepares data; a stalled
        // worker there must not be restarted automatically.
        assertEquals(
            ImportWatchdogAction.SHOW_MANUAL_RESUME,
            importWatchdogAction(facts { copy(dataPreparationEnabled = false) }),
        )
    }

    // F8 B: the OS-recorded death reason drives the user-visible wording. A
    // lowmemorykiller storm must tell the user to close other apps instead of
    // implying a resume tap is all it takes.
    @Test fun restartNoticeNamesMemoryPressureWhenOsRecordsIt() {
        val lmk = watchdogRestartNotice("LOW_MEMORY", attempt = 2, maxRestarts = 4)
        assertTrue(lmk.contains("free memory"))
        assertTrue(lmk.contains("(2/4)"))
        assertTrue(lmk.contains("Closing other apps"))

        val generic = watchdogRestartNotice(null, attempt = 1, maxRestarts = 4)
        assertTrue(generic.contains("(1/4)"))
        assertFalse(generic.contains("free memory"))
    }

    @Test fun stoppedNoticeAdvisesClosingAppsOnlyForMemoryKills() {
        val lmk = workerStoppedNotice("LOW_MEMORY")
        assertTrue(lmk.contains("Resume"))
        assertTrue(lmk.contains("closing other apps"))
        assertTrue(lmk.contains("Nothing already imported is lost"))

        val crash = workerStoppedNotice("CRASH")
        assertTrue(crash.contains("crashed"))
        assertTrue(crash.contains("Resume"))

        val generic = workerStoppedNotice(null)
        assertEquals("Worker was stopped by the system — tap Resume to continue.", generic)
    }
}
