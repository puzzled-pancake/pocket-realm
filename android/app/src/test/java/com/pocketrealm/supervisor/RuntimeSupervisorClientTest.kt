package com.pocketrealm.supervisor

import com.pocketrealm.realm.RealmState
import com.pocketrealm.realm.ClientLaunchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSupervisorClientTest {
    @Test fun unconfiguredErrorIsFailedWhilePristineTerminalStatesAreIdle() {
        val rejected = RuntimeSnapshot(
            phase = RuntimePhase.UNCONFIGURED,
            clean = true,
            lastError = "Import a compatible WoW client before starting.",
            recoverability = Recoverability.USER_ACTION_REQUIRED,
        )

        val decoded = RuntimeSupervisorClient.decodeRealmState(encoded(rejected))

        assertTrue(decoded is RealmState.Failed)
        assertEquals("Import a compatible WoW client before starting.",
            (decoded as RealmState.Failed).message)
        assertTrue(RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot())) is RealmState.Idle)
        assertTrue(RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.UNCONFIGURED,
            clean = true,
        ))) is RealmState.Idle)
    }

    @Test fun dirtyStoppedStateWithoutMessageIsNotPresentedAsIdle() {
        val decoded = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = false,
            recoverability = Recoverability.RECOVERY_REQUIRED,
        )))

        assertTrue(decoded is RealmState.Failed)
    }

    @Test fun clientFailureRetainsOnlineRealmAndExposesRetryReason() {
        val decoded = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.CLIENT_FAILED,
            clean = false,
            lastError = "CLIENT: pinned ARM rootfs is missing home/xuser/.wine/system.reg",
        ), generationActive = true))

        assertTrue(decoded is RealmState.Running)
        assertEquals(
            "CLIENT: pinned ARM rootfs is missing home/xuser/.wine/system.reg",
            (decoded as RealmState.Running).clientFailure,
        )
        assertEquals(ClientLaunchState.FAILED, decoded.clientState)
    }

    @Test fun worldReadyMeansAccountCanBeCreatedBeforeStartingClient() {
        val decoded = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.WORLD_READY,
            clean = false,
        ), generationActive = true))

        assertTrue(decoded is RealmState.Running)
        assertEquals(ClientLaunchState.NOT_STARTED, (decoded as RealmState.Running).clientState)
    }

    @Test fun interruptedStartingJournalDoesNotRemainAnEndlessSpinner() {
        val interrupted = RuntimeSnapshot(
            phase = RuntimePhase.WORLD_STARTING,
            clean = false,
            recoverability = Recoverability.RETRY,
        )

        val decoded = RuntimeSupervisorClient.decodeRealmState(
            encoded(interrupted, generationActive = false),
        )

        assertTrue(decoded is RealmState.Failed)
        assertEquals(
            "The previous start was interrupted. Tap Start to recover safely and try again.",
            (decoded as RealmState.Failed).message,
        )
    }

    @Test fun liveStartingGenerationStillShowsProgress() {
        val decoded = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.WORLD_STARTING,
            clean = false,
            recoverability = Recoverability.RETRY,
        ), generationActive = true))

        assertTrue(decoded is RealmState.Starting)
    }

    @Test fun interruptedStopJournalDoesNotTrapTheStopButton() {
        val interrupted = RuntimeSnapshot(
            phase = RuntimePhase.STOPPING,
            clean = false,
            lastDurableAction = "stop-requested",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        )

        val decoded = RuntimeSupervisorClient.decodeRealmState(
            encoded(interrupted, generationActive = false),
        )

        assertTrue(decoded is RealmState.Failed)
        assertEquals(
            "The previous stop was interrupted. Tap Start to recover safely.",
            (decoded as RealmState.Failed).message,
        )
    }

    @Test fun liveStoppingGenerationStillShowsStopping() {
        val decoded = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.STOPPING,
            clean = false,
            lastDurableAction = "stop-requested",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        ), generationActive = true))

        assertTrue(decoded is RealmState.Stopping)
    }

    @Test fun interruptedRecoveryJournalDoesNotRemainRecoveringForever() {
        val interrupted = RuntimeSnapshot(
            phase = RuntimePhase.RECOVERING,
            clean = false,
            lastDurableAction = "dirty-journal-recovery-started",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        )

        val decoded = RuntimeSupervisorClient.decodeRealmState(
            encoded(interrupted, generationActive = false),
        )

        assertTrue(decoded is RealmState.Failed)
        assertEquals(
            "The previous recovery was interrupted. Tap Start to recover safely.",
            (decoded as RealmState.Failed).message,
        )
    }

    private fun encoded(snapshot: RuntimeSnapshot, generationActive: Boolean = false): String =
        RuntimeSnapshotJson.encode(snapshot)
            .put("supervisorGenerationActive", generationActive)
            .toString()
}
