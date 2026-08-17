package com.pocketrealm.ui

import org.junit.Assert.assertEquals
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
}
