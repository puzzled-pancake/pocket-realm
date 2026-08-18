package com.pocketrealm.server

import org.junit.Assert.assertThrows
import org.junit.Test

class StartLogRotationPolicyTest {
    @Test
    fun stoppedRuntimeMayPrepareRestartLogs() {
        StartLogRotationPolicy.requireStopped("world", ServerRuntimeContract.STOPPED)
    }

    @Test
    fun liveOrTransitioningRuntimeCannotRotateLogs() {
        listOf(
            ServerRuntimeContract.STARTING,
            ServerRuntimeContract.READY,
            ServerRuntimeContract.SAVING,
            ServerRuntimeContract.STOPPING,
            ServerRuntimeContract.FAILED,
        ).forEach { state ->
            assertThrows(IllegalStateException::class.java) {
                StartLogRotationPolicy.requireStopped("world", state)
            }
        }
    }
}
