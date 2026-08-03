package com.pocketrealm.supervisor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableRuntimeSupervisorTest {
    @Test fun dependencyReadinessAndExactShutdownOrder() = runTest {
        val backend = FakeBackend()
        val journal = MemoryJournal()
        val runtime = runtime(backend, journal)

        val started = runtime.start("mobile-low-v1", includeClient = true)
        assertTrue(started.ok)
        assertEquals(RuntimePhase.RUNNING, started.snapshot.phase)
        assertEquals(listOf("preflight", "start:DATABASE", "start:REALM", "start:WORLD", "start:CLIENT"),
            backend.actions)
        val assigned = started.snapshot.components.values.mapNotNull { it.instanceToken }
        assertEquals(4, assigned.distinct().size)
        assertTrue(assigned.all { it.matches(Regex("[0-9a-f]{64}")) })

        backend.actions.clear()
        val stopped = runtime.stop(StopMode.GRACEFUL)
        assertTrue(stopped.ok)
        assertTrue(stopped.snapshot.clean)
        assertEquals(RuntimePhase.STOPPED, stopped.snapshot.phase)
        assertEquals(listOf("stop:CLIENT", "save:WORLD", "stop:WORLD", "stop:REALM", "stop:DATABASE"),
            backend.actions)
        assertEquals("clean-stop-committed", journal.last!!.lastDurableAction)
    }

    @Test fun pidWithoutReadinessNeverPromotesDependency() = runTest {
        val backend = FakeBackend().apply { pidOnly += RuntimeComponent.DATABASE }
        val runtime = runtime(backend)

        val result = runtime.start("mobile-low-v1", includeClient = false)

        assertFalse(result.ok)
        assertEquals(RuntimePhase.ERROR, result.snapshot.phase)
        assertTrue(result.snapshot.lastError!!.contains("readiness/ownership proof rejected"))
        assertFalse(backend.actions.contains("start:REALM"))
    }

    @Test fun dirtyRecoveryNeverKillsAnUnverifiedOwner() = runTest {
        val oldOwner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = oldOwner.sessionId,
            phase = RuntimePhase.RUNNING,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.DATABASE to ComponentSnapshot(
                    ComponentLifecycle.READY, oldOwner.instanceToken, 1, "old database")),
            lastDurableAction = "database-ready",
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.DATABASE] = ComponentObservation(
                RuntimeComponent.DATABASE, ComponentLifecycle.READY, true,
                ComponentOwner(SESSION, "bb".repeat(32)), 999, "different process generation")
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        val recovered = runtime.recover()

        assertFalse(recovered.ok)
        assertEquals(RuntimePhase.ERROR, recovered.snapshot.phase)
        assertTrue(recovered.snapshot.lastError!!.contains("UNVERIFIED_ORPHAN"))
        assertTrue(backend.actions.none { it.startsWith("force:") })
        assertFalse(backend.actions.contains("recover:DATABASE"))
    }

    @Test fun clientFailureRetainsRealmAndCanRelaunch() = runTest {
        val backend = FakeBackend().apply { failedStarts += RuntimeComponent.CLIENT }
        val runtime = runtime(backend)

        val first = runtime.start("mobile-low-v1", includeClient = true)
        assertFalse(first.ok)
        assertEquals(RuntimePhase.CLIENT_FAILED, first.snapshot.phase)
        assertTrue(listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD)
            .all { first.snapshot.components.getValue(it).state == ComponentLifecycle.READY })
        assertTrue(backend.actions.none { it.startsWith("stop:") })

        backend.failedStarts.clear()
        val relaunched = runtime.relaunchClient()
        assertTrue(relaunched.ok)
        assertEquals(RuntimePhase.RUNNING, relaunched.snapshot.phase)
        assertEquals(2, backend.actions.count { it == "start:CLIENT" })
    }

    @Test fun realmFatalFailureStopsEveryOwnedDependencyDirty() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = true).ok)
        backend.actions.clear()

        val failed = runtime.componentFailed(RuntimeComponent.WORLD, "world process died")

        assertTrue(failed.ok)
        assertEquals(RuntimePhase.ERROR, failed.snapshot.phase)
        assertFalse(failed.snapshot.clean)
        assertEquals(Recoverability.RECOVERY_REQUIRED, failed.snapshot.recoverability)
        assertEquals(listOf("force:CLIENT", "force:WORLD", "force:REALM", "force:DATABASE"),
            backend.actions)
    }

    @Test fun gracefulTimeoutEscalatesOnlyAfterOwnershipProof() = runTest {
        val backend = FakeBackend().apply { failedStops += RuntimeComponent.WORLD }
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        backend.actions.clear()

        val stopped = runtime.stop(StopMode.GRACEFUL)

        assertFalse(stopped.snapshot.clean)
        assertTrue(backend.actions.indexOf("stop:WORLD") < backend.actions.indexOf("force:WORLD"))
        assertTrue(backend.actions.containsAll(listOf("stop:REALM", "stop:DATABASE")))
    }

    @Test fun accountProvisioningRequiresOwnedWorldAndNeverJournalsCredentials() = runTest {
        val backend = FakeBackend()
        val journal = MemoryJournal()
        val runtime = runtime(backend, journal)

        assertEquals("WORLD_NOT_READY", runtime.provisionAccount("PLAYER", "Secret7", 0).code)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)

        val created = runtime.provisionAccount("PLAYER", "Secret7", 3)

        assertTrue(created.ok)
        assertEquals(7, created.accountId)
        assertEquals(3, created.gmLevel)
        assertEquals("account-provisioned-core-command", journal.last!!.lastDurableAction)
        assertFalse(journal.writes.joinToString().contains("PLAYER"))
        assertFalse(journal.writes.joinToString().contains("Secret7"))
        assertEquals("account:3", backend.actions.last())
    }

    @Test fun invalidAccountInputIsRejectedBeforeBackendInvocation() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        backend.actions.clear()

        val rejected = runtime.provisionAccount("PLAYER", "bad password", 0)

        assertFalse(rejected.ok)
        assertEquals("ACCOUNT_INVALID", rejected.code)
        assertTrue(backend.actions.isEmpty())
    }

    private fun runtime(
        backend: FakeBackend,
        journal: MemoryJournal = MemoryJournal(),
    ) = DurableRuntimeSupervisor(
        backend = backend,
        journal = journal,
        tokens = DeterministicTokens(),
        clock = FakeClock(),
        timeouts = RuntimeTimeouts(1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000),
    )

    private class MemoryJournal(initial: RuntimeSnapshot? = null) : SupervisorJournal {
        var last = initial
        val writes = mutableListOf<RuntimeSnapshot>()
        override fun read() = last
        override fun write(snapshot: RuntimeSnapshot) { last = snapshot; writes += snapshot }
    }

    private class FakeClock : RuntimeClock {
        private var value = 1L
        override fun wallMs() = value++
        override fun elapsedMs() = value++
    }

    private class DeterministicTokens : RuntimeTokenSource {
        private var next = 0
        override fun sessionId() = SESSION
        override fun instanceToken(): String = (++next).toString(16).padStart(2, '0').repeat(32)
    }

    private class FakeBackend : RuntimeBackend {
        val actions = mutableListOf<String>()
        val observations = RuntimeComponent.entries.associateWith {
            ComponentObservation(it, ComponentLifecycle.STOPPED, false)
        }.toMutableMap()
        val failedStarts = mutableSetOf<RuntimeComponent>()
        val failedStops = mutableSetOf<RuntimeComponent>()
        val pidOnly = mutableSetOf<RuntimeComponent>()

        override suspend fun preflight(profileId: String): RuntimeActionResult {
            actions += "preflight"
            return RuntimeActionResult(profileId == "mobile-low-v1", "preflight")
        }

        override suspend fun observe(component: RuntimeComponent) = observations.getValue(component)

        override suspend fun start(
            component: RuntimeComponent,
            owner: ComponentOwner,
            profileId: String,
        ): ComponentObservation {
            actions += "start:$component"
            val result = when {
                component in failedStarts -> ComponentObservation(
                    component, ComponentLifecycle.FAILED, false, owner, 100, "injected start failure")
                component in pidOnly -> ComponentObservation(
                    component, ComponentLifecycle.STARTING, false, owner, 100, "pid exists; readiness absent")
                else -> ComponentObservation(component, ComponentLifecycle.READY, true, owner, 100, "ready")
            }
            observations[component] = result
            return result
        }

        override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
            actions += "stop:$component"
            if (component in failedStops) return RuntimeActionResult(false, "injected timeout")
            observations[component] = ComponentObservation(component, ComponentLifecycle.STOPPED, false)
            return RuntimeActionResult(true, "stopped")
        }

        override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
            actions += "force:$component"
            observations[component] = ComponentObservation(component, ComponentLifecycle.STOPPED, false)
            return RuntimeActionResult(true, "forced")
        }

        override suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult {
            actions += "save:WORLD"
            return RuntimeActionResult(true, "saved")
        }

        override suspend fun provisionAccount(
            owner: ComponentOwner,
            username: String,
            password: String,
            gmLevel: Int,
        ): AccountProvisionResult {
            actions += "account:$gmLevel"
            return AccountProvisionResult(true, "ACCOUNT_CREATED", 7, gmLevel)
        }

        override suspend fun recoverDatabase(): RuntimeActionResult {
            actions += "recover:DATABASE"
            return RuntimeActionResult(true, "recovered")
        }
    }

    companion object { private const val SESSION = "123e4567-e89b-12d3-a456-426614174000" }
}
