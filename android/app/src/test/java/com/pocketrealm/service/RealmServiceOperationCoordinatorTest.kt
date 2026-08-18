package com.pocketrealm.service

import com.pocketrealm.supervisor.Recoverability
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentSnapshot
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealmServiceOperationCoordinatorTest {
    @Test fun startAndMaintenanceCannotBothReserveTheService() {
        val coordinator = ServiceOperationCoordinator()

        val start = coordinator.tryReserve()
        val maintenance = coordinator.tryReserve()

        assertNotNull(start)
        assertNull(maintenance)
        assertTrue(coordinator.isOccupied)
        start!!.release()
        assertFalse(coordinator.isOccupied)
        assertNotNull(coordinator.tryReserve())
    }

    @Test fun throwBeforeGenerationReleasesPowerGateAndForeground() = runTest {
        val coordinator = ServiceOperationCoordinator()
        val reservation = checkNotNull(coordinator.tryReserve())
        var wakeLockHeld = false
        var foregroundActive = true

        val execution = coordinator.runReserved(
            reservation = reservation,
            acquirePower = { check(!wakeLockHeld); wakeLockHeld = true },
            releasePower = { check(wakeLockHeld); wakeLockHeld = false },
        ) {
            error("injected operation failure")
        }
        val cleanVisibleError = RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = true,
            lastError = "IllegalStateException: injected operation failure",
            recoverability = Recoverability.RETRY,
        )
        val disposition = RealmServiceOperationPolicy.after(cleanVisibleError, generationWasActive = false)
        if (disposition.removeForeground) foregroundActive = false

        assertTrue(execution.isFailure)
        assertFalse(wakeLockHeld)
        assertFalse(coordinator.isOccupied)
        assertFalse(foregroundActive)
        assertTrue(disposition.stopService)
        assertNotNull(coordinator.tryReserve())
    }

    @Test fun gateReleasesEvenWhenPowerAcquisitionFails() = runTest {
        val coordinator = ServiceOperationCoordinator()
        val reservation = checkNotNull(coordinator.tryReserve())
        var releaseCalled = false

        val execution = coordinator.runReserved(
            reservation = reservation,
            acquirePower = { error("power manager unavailable") },
            releasePower = { releaseCalled = true },
        ) { "never" }

        assertTrue(execution.isFailure)
        assertFalse(releaseCalled)
        assertFalse(coordinator.isOccupied)
        assertNotNull(coordinator.tryReserve())
    }

    @Test fun dirtyActiveFailureKeepsForegroundAndGenerationVisible() {
        val active = RuntimeSnapshot(
            phase = RuntimePhase.ERROR,
            clean = false,
            sessionId = "123e4567-e89b-12d3-a456-426614174000",
            components = RuntimeSnapshot.stoppedComponents() + (RuntimeComponent.WORLD to
                ComponentSnapshot(
                    state = ComponentLifecycle.READY,
                    instanceToken = "aa".repeat(32),
                )),
            lastError = "active operation failed",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        )

        val disposition = RealmServiceOperationPolicy.after(active, generationWasActive = true)

        assertTrue(disposition.generationActive)
        assertFalse(disposition.removeForeground)
        assertFalse(disposition.stopService)
    }
}
