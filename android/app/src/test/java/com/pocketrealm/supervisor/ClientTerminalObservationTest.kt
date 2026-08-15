package com.pocketrealm.supervisor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTerminalObservationTest {
    @Test fun gracefulReleaseWaitsForExecutorFinishedProof() {
        assertFalse(clientGracefulReleaseReady("EXITED", cleanExit = true, runtimeFinished = false))
        assertTrue(clientGracefulReleaseReady("EXITED", cleanExit = true, runtimeFinished = true))
        assertFalse(clientGracefulReleaseReady("FAILED", cleanExit = true, runtimeFinished = true))
    }

    @Test fun ownedTerminalClientRemainsStoppingUntilOwnershipIsReleased() {
        assertTrue(clientTerminalLifecycle("EXITED", hasOwner = true) == ComponentLifecycle.STOPPING)
        assertTrue(clientTerminalLifecycle("FORCE_STOPPED", hasOwner = true) == ComponentLifecycle.STOPPING)
        assertTrue(clientTerminalLifecycle("EXITED", hasOwner = false) == ComponentLifecycle.STOPPED)
    }

    @Test fun clientReadyRequiresPreparedRendererAndExactDisplayOwner() {
        val owner = ComponentOwner("123e4567-e89b-12d3-a456-426614174000", "aa".repeat(32))
        val runtime = ComponentObservation(
            RuntimeComponent.CLIENT,
            ComponentLifecycle.READY,
            ready = true,
            owner = owner,
        )

        assertTrue(compositeClientObservation(
            runtime,
            ClientDisplayHealth(owner, prepared = true, rendererReady = true),
        ).ready)
        listOf(
            null,
            ClientDisplayHealth(null, prepared = true, rendererReady = true),
            ClientDisplayHealth(owner.copy(instanceToken = "bb".repeat(32)), true, true),
            ClientDisplayHealth(owner, prepared = false, rendererReady = true),
            ClientDisplayHealth(owner, prepared = true, rendererReady = false),
        ).forEach { display ->
            val combined = compositeClientObservation(runtime, display)
            assertFalse(combined.ready)
            assertEquals(ComponentLifecycle.FAILED, combined.state)
            assertEquals(owner, combined.owner)
        }

        val starting = runtime.copy(state = ComponentLifecycle.STARTING, ready = false)
        assertEquals(
            ComponentLifecycle.FAILED,
            compositeClientObservation(starting, display = null).state,
        )
        assertEquals(
            ComponentLifecycle.STARTING,
            compositeClientObservation(
                starting,
                ClientDisplayHealth(owner, prepared = true, rendererReady = true),
            ).state,
        )
    }

    @Test fun displayCleanupAcceptsOnlyExactOwnerOrFullyUnownedUnpreparedState() {
        val owner = ComponentOwner("123e4567-e89b-12d3-a456-426614174000", "aa".repeat(32))
        assertEquals(
            DisplayCleanupAction.RELEASE_EXACT_OWNER,
            displayCleanupAction(owner, ClientDisplayHealth(owner, true, true)),
        )
        assertEquals(
            DisplayCleanupAction.ALREADY_RELEASED,
            displayCleanupAction(owner, ClientDisplayHealth(null, false, false)),
        )
        assertEquals(
            DisplayCleanupAction.REJECT,
            displayCleanupAction(owner, ClientDisplayHealth(null, true, true)),
        )
        assertEquals(
            DisplayCleanupAction.REJECT,
            displayCleanupAction(
                owner,
                ClientDisplayHealth(owner.copy(instanceToken = "bb".repeat(32)), true, true),
            ),
        )
    }

    @Test fun onlyAnUnownedTerminalGenerationCanBeAcceptedAsAlreadyDrained() {
        assertTrue(clientAlreadyDrained("EXITED", false, true, "clean exit"))
        assertTrue(clientAlreadyDrained("EXITED", false, false, "no active client session"))
        assertFalse(clientAlreadyDrained("RUNNING", false, true, "owner loss"))
        assertFalse(clientAlreadyDrained("EXITED", true, true, "still owned"))
    }

    @Test fun experimentalProofUsesLiveCountersAndRevokesInvalidVirglRoute() {
        val healthy = JSONObject()
            .put("virglServerStarted", true)
            .put("glxTransportContexts", 0)
            .put("glxContexts", 0)
            .put("virglActiveConnections", 1)
            .put("virglInitializedConnections", 1)
            .put("virglCapsReadyConnections", 1)
            .put("virglSuccessfulFlushes", 3)
        assertEquals(ExperimentalGraphicsProof(1, 1, 3),
            experimentalGraphicsProof("virgl", healthy))

        assertEquals(ExperimentalGraphicsProof(0, 1, 3),
            experimentalGraphicsProof("virgl", JSONObject(healthy.toString())
                .put("virglActiveConnections", 0)))
        assertEquals(ExperimentalGraphicsProof(0, 0, 0),
            experimentalGraphicsProof("virgl", JSONObject(healthy.toString())
                .put("virglServerStarted", false)))
        assertEquals(ExperimentalGraphicsProof(0, 0, 0),
            experimentalGraphicsProof("virgl", JSONObject(healthy.toString())
                .put("glxContexts", 1)))

        val gladio = experimentalGraphicsProof("opengl", JSONObject()
            .put("glxTransportContexts", 1)
            .put("glxContexts", 2)
            .put("glxPresentedFrames", 4))
        assertEquals(ExperimentalGraphicsProof(1, 2, 4), gladio)
    }
}
