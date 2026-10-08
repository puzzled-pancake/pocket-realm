package com.pocketrealm.supervisor

import com.pocketrealm.realm.RealmState
import com.pocketrealm.realm.ClientLaunchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun anInterfaceLossStopDecodesAsIdleWithTheHumanizedWhy() {
        // Design-lan D9: the Android loss-stop producer rides the snapshot's
        // stopReason; the decode humanizes it into the shared copy so a
        // network change mid-hosting never presents as a clean, unexplained
        // stop. An ordinary stop carries no note.
        val lossStop = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = true,
            stopReason = "LAN_HOST_INTERFACE_LOST",
        ))) as RealmState.Idle
        assertEquals(
            RuntimeFailureCopy.humanize("LAN_HOST_INTERFACE_LOST"),
            lossStop.stopCopy,
        )
        assertTrue(lossStop.stopCopy!!.contains("network connection changed"))
        val tunnelStop = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = true,
            stopReason = "TUNNEL_HOST_INTERFACE_LOST",
        ))) as RealmState.Idle
        assertTrue(tunnelStop.stopCopy!!.contains("tunnel connection"))
        val ordinary = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = true,
        ))) as RealmState.Idle
        assertEquals(null, ordinary.stopCopy)
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

    @Test fun rawOrphanAndTimeoutDetailsAreHumanizedAtTheDecodeBoundary() {
        val orphan = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = false,
            lastError = "UNVERIFIED_ORPHAN: WORLD ownership did not match",
        )))

        assertTrue(orphan is RealmState.Failed)
        assertEquals(
            RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match"),
            (orphan as RealmState.Failed).message,
        )
        assertFalse(orphan.message.contains("UNVERIFIED_ORPHAN"))

        val timedOut = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = false,
            lastError = "WORLD: TimeoutCancellationException: Timed out waiting for 120000 ms",
        )))

        assertTrue(timedOut is RealmState.Failed)
        assertEquals(
            RuntimeFailureCopy.humanize("WORLD: TimeoutCancellationException: Timed out waiting for 120000 ms"),
            (timedOut as RealmState.Failed).message,
        )
    }

    @Test fun onlyTheUnverifiedOrphanFailureCarriesTheForceStopRepairFlag() {
        val orphan = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = false,
            lastError = "UNVERIFIED_ORPHAN: WORLD ownership did not match",
        ))) as RealmState.Failed

        assertTrue(orphan.unverifiedOrphan)
        assertEquals(
            RuntimeFailureCopy.humanize("UNVERIFIED_ORPHAN: WORLD ownership did not match"),
            orphan.message,
        )

        // Every other failure keeps the generic error surface: no
        // force-stop affordance for timeouts or plain dirty stops.
        val timedOut = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = false,
            lastError = "WORLD: TimeoutCancellationException: Timed out waiting for 120000 ms",
        ))) as RealmState.Failed
        assertFalse(timedOut.unverifiedOrphan)

        val dirtyStopped = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = false,
            recoverability = Recoverability.RECOVERY_REQUIRED,
        ))) as RealmState.Failed
        assertFalse(dirtyStopped.unverifiedOrphan)
    }

    @Test fun dbOwnedByDeadSessionFailureCarriesTheForceStopRepairFlag() {
        val raw = "DATABASE: DB_OWNED_BY_DEAD_SESSION: ended own-session database owner " +
            "could not be released: injected drain failure"
        val stale = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = false,
            lastError = raw,
        ))) as RealmState.Failed

        assertTrue(stale.dbOwnedByDeadSession)
        assertFalse(stale.unverifiedOrphan)
        assertEquals(RuntimeFailureCopy.humanize(raw), stale.message)
        assertFalse(stale.message.contains("DB_OWNED_BY_DEAD_SESSION"))

        // The refusal case - an owner this supervisor cannot prove as its own
        // ended session - keeps the generic error surface with no repair flag.
        val refused = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = false,
            lastError = "DATABASE: IllegalStateException: database is owned by another runtime session",
        ))) as RealmState.Failed
        assertFalse(refused.dbOwnedByDeadSession)
        assertFalse(refused.unverifiedOrphan)

        // And the flag survives the dirty-stopped surface, like the orphan's.
        val dirtyStopped = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = false,
            lastError = raw,
        ))) as RealmState.Failed
        assertTrue(dirtyStopped.dbOwnedByDeadSession)
    }

    @Test fun tunnelAdvertisingSessionCarriesTheAdvertisedAddressThroughTheStatusDecode() {
        val running = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.RUNNING,
            runtimeMode = RuntimeMode.LAN_HOST,
            realmEndpoint = RealmEndpoint.parseLan("192.168.1.50"),
            advertisedAddress = "100.64.12.34",
        ), generationActive = true)) as RealmState.Running
        assertEquals(
            "design-tunnel §5: the UI renders the live advertisement",
            "100.64.12.34",
            running.advertisedAddress,
        )
        assertEquals("192.168.1.50", running.endpointAddress)

        val plain = RuntimeSupervisorClient.decodeRealmState(encoded(RuntimeSnapshot(
            phase = RuntimePhase.RUNNING,
        ), generationActive = true)) as RealmState.Running
        assertEquals("absent key decodes as the plain advertisement", null, plain.advertisedAddress)
    }

    private fun encoded(snapshot: RuntimeSnapshot, generationActive: Boolean = false): String =
        RuntimeSnapshotJson.encode(snapshot)
            .put("supervisorGenerationActive", generationActive)
            .toString()
}
