package com.pocketrealm.supervisor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableRuntimeSupervisorTest {
    @Test fun preflightFailureIsCleanUnconfiguredAndCorrectedStartSkipsRecovery() = runTest {
        val backend = FakeBackend().apply {
            preflightAllowed = false
            preflightDetail = "client files are not imported"
        }
        val runtime = runtime(backend)

        val rejected = runtime.start(RuntimeLaunchSpec.lanHost(
            "mobile-low-v1",
            "192.168.50.7",
            includeClient = true,
        ))

        assertFalse(rejected.ok)
        assertEquals(RuntimePhase.UNCONFIGURED, rejected.snapshot.phase)
        assertTrue(rejected.snapshot.clean)
        assertNull(rejected.snapshot.sessionId)
        assertEquals("mobile-low-v1", rejected.snapshot.requestedProfile)
        assertEquals(RuntimeMode.LAN_HOST, rejected.snapshot.runtimeMode)
        assertEquals("192.168.50.7", rejected.snapshot.realmEndpoint.address)
        assertEquals("client files are not imported", rejected.snapshot.lastError)
        assertEquals(Recoverability.USER_ACTION_REQUIRED, rejected.snapshot.recoverability)
        assertTrue(rejected.snapshot.components.values.all {
            it.state == ComponentLifecycle.STOPPED && it.instanceToken == null
        })

        backend.preflightAllowed = true
        backend.actions.clear()
        val corrected = runtime.start("mobile-low-v1", includeClient = false)

        assertTrue(corrected.ok)
        assertEquals(RuntimePhase.WORLD_READY, corrected.snapshot.phase)
        assertFalse(backend.actions.contains("recover:DATABASE"))
        assertEquals("preflight:LOCAL", backend.actions.first())
    }

    @Test fun unexpectedFailureBeforeGenerationIsCleanButActiveGenerationStaysDirty() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)

        val beforeStart = runtime.unexpectedOperationFailure("service operation exploded")
        assertEquals(RuntimePhase.ERROR, beforeStart.snapshot.phase)
        assertTrue(beforeStart.snapshot.clean)
        assertNull(beforeStart.snapshot.sessionId)
        assertEquals(Recoverability.RETRY, beforeStart.snapshot.recoverability)

        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        val whileActive = runtime.unexpectedOperationFailure("service operation exploded again")
        assertEquals(RuntimePhase.ERROR, whileActive.snapshot.phase)
        assertFalse(whileActive.snapshot.clean)
        assertEquals(Recoverability.RECOVERY_REQUIRED, whileActive.snapshot.recoverability)
        assertTrue(whileActive.snapshot.components.values.any { it.state == ComponentLifecycle.READY })
    }

    @Test fun dependencyReadinessAndExactShutdownOrder() = runTest {
        val backend = FakeBackend()
        val journal = MemoryJournal()
        val runtime = runtime(backend, journal)

        val started = runtime.start("mobile-low-v1", includeClient = true)
        assertTrue(started.ok)
        assertEquals(RuntimePhase.RUNNING, started.snapshot.phase)
        assertEquals(listOf(
            "preflight:LOCAL", "start:DATABASE", "project:127.0.0.1",
            "start:REALM", "start:WORLD", "start:CLIENT",
        ),
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
        assertTrue(backend.actions.contains("force:CLIENT"))
        assertNull(first.snapshot.components.getValue(RuntimeComponent.CLIENT).instanceToken)

        backend.failedStarts.clear()
        val relaunched = runtime.relaunchClient()
        assertTrue(relaunched.ok)
        assertEquals(RuntimePhase.RUNNING, relaunched.snapshot.phase)
        assertEquals(2, backend.actions.count { it == "start:CLIENT" })
    }

    @Test fun clientCleanupFailureNeverAdvertisesRetry() = runTest {
        val backend = FakeBackend().apply {
            failedStarts += RuntimeComponent.CLIENT
            failedForces += RuntimeComponent.CLIENT
        }
        val runtime = runtime(backend)

        val failed = runtime.start("mobile-low-v1", includeClient = true)

        assertFalse(failed.ok)
        assertEquals(RuntimePhase.ERROR, failed.snapshot.phase)
        assertEquals(Recoverability.RECOVERY_REQUIRED, failed.snapshot.recoverability)
        assertTrue(backend.actions.contains("force:CLIENT"))
    }

    @Test fun lostClientDisplayIsDrainedBeforeFailureAndRealmSessionIsPreserved() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        val running = runtime.start("mobile-low-v1", includeClient = true)
        val session = running.snapshot.sessionId
        val serverTokens = listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD)
            .associateWith { running.snapshot.components.getValue(it).instanceToken }
        backend.actions.clear()

        val isolated = runtime.componentFailed(RuntimeComponent.CLIENT, "display service lost")

        assertTrue(isolated.ok)
        assertEquals(RuntimePhase.CLIENT_FAILED, isolated.snapshot.phase)
        assertEquals(session, isolated.snapshot.sessionId)
        assertEquals("mobile-low-v1", isolated.snapshot.requestedProfile)
        assertEquals(listOf("force:CLIENT"), backend.actions)
        serverTokens.forEach { (component, token) ->
            assertEquals(ComponentLifecycle.READY, isolated.snapshot.components.getValue(component).state)
            assertEquals(token, isolated.snapshot.components.getValue(component).instanceToken)
        }
    }

    @Test fun retryDefensivelyDrainsRetainedFailedOwnerBeforeClaimingFreshToken() = runTest {
        val retainedOwner = ComponentOwner(SESSION, "aa".repeat(32))
        val readyComponents = RuntimeSnapshot.stoppedComponents().toMutableMap().apply {
            listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD)
                .forEachIndexed { index, component ->
                    this[component] = ComponentSnapshot(
                        ComponentLifecycle.READY,
                        (index + 1).toString(16).padStart(2, '0').repeat(32),
                    )
                }
            this[RuntimeComponent.CLIENT] = ComponentSnapshot(
                ComponentLifecycle.FAILED,
                retainedOwner.instanceToken,
            )
        }
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.CLIENT_FAILED,
            requestedProfile = "mobile-low-v1",
            clean = false,
            components = readyComponents,
            recoverability = Recoverability.RELAUNCH_CLIENT,
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.CLIENT] = ComponentObservation(
                RuntimeComponent.CLIENT,
                ComponentLifecycle.FAILED,
                false,
                retainedOwner,
            )
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        val relaunched = runtime.relaunchClient()

        assertTrue(relaunched.ok)
        assertEquals(RuntimePhase.RUNNING, relaunched.snapshot.phase)
        assertEquals(SESSION, relaunched.snapshot.sessionId)
        assertTrue(backend.actions.indexOf("force:CLIENT") < backend.actions.indexOf("start:CLIENT"))
        assertNotEquals(
            retainedOwner.instanceToken,
            relaunched.snapshot.components.getValue(RuntimeComponent.CLIENT).instanceToken,
        )
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

    @Test fun lanJoinIsClientOnlyAndNeverTouchesServerOrLocalAccountControl() = runTest {
        val backend = FakeBackend()
        val journal = MemoryJournal()
        val runtime = runtime(backend, journal)

        val result = runtime.start(RuntimeLaunchSpec.lanJoin("mobile-low-v1", "192.168.50.4"))

        assertTrue(result.ok)
        assertEquals(RuntimePhase.RUNNING, result.snapshot.phase)
        assertEquals(RuntimeMode.LAN_JOIN, result.snapshot.runtimeMode)
        assertEquals("192.168.50.4", result.snapshot.realmEndpoint.address)
        assertEquals(listOf("preflight:LAN_JOIN", "start:CLIENT"), backend.actions)
        assertTrue(RuntimeComponent.entries.filter { it != RuntimeComponent.CLIENT }.all {
            result.snapshot.components.getValue(it).state == ComponentLifecycle.STOPPED
        })
        assertFalse(journal.writes.joinToString().contains("password", ignoreCase = true))
    }

    @Test fun dirtyLanJoinRecoveryNeverRecoversDatabase() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.CLIENT_FAILED,
            requestedProfile = "mobile-low-v1",
            runtimeMode = RuntimeMode.LAN_JOIN,
            realmEndpoint = RealmEndpoint.parseLan("10.0.0.8"),
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.CLIENT to ComponentSnapshot(
                    ComponentLifecycle.STOPPED, owner.instanceToken, 1, "stopped")),
        )
        val backend = FakeBackend()
        val runtime = runtime(backend, MemoryJournal(initial))

        assertTrue(runtime.recover().ok)
        assertFalse(backend.actions.contains("recover:DATABASE"))
        assertTrue(backend.actions.none { it.contains("DATABASE") || it.contains("REALM") || it.contains("WORLD") })
    }

    @Test fun endpointProjectionFailureStopsDatabaseAndNeverStartsRealmd() = runTest {
        val backend = FakeBackend().apply { projectionFails = true }
        val runtime = runtime(backend)

        val result = runtime.start("mobile-low-v1", includeClient = true)

        assertFalse(result.ok)
        assertTrue(backend.actions.contains("start:DATABASE"))
        assertTrue(backend.actions.contains("project:127.0.0.1"))
        assertFalse(backend.actions.contains("start:REALM"))
        assertTrue(backend.actions.contains("stop:DATABASE"))
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
        val failedForces = mutableSetOf<RuntimeComponent>()
        val pidOnly = mutableSetOf<RuntimeComponent>()
        var projectionFails = false
        var preflightAllowed = true
        var preflightDetail = "preflight"

        override suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeActionResult {
            actions += "preflight:${spec.mode}"
            return RuntimeActionResult(
                preflightAllowed && spec.profileId == "mobile-low-v1",
                preflightDetail,
            )
        }

        override suspend fun observe(component: RuntimeComponent) = observations.getValue(component)

        override suspend fun start(
            component: RuntimeComponent,
            owner: ComponentOwner,
            spec: RuntimeLaunchSpec,
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

        override suspend fun projectRealmEndpoint(
            databaseOwner: ComponentOwner,
            endpoint: RealmEndpoint,
        ): RuntimeActionResult {
            actions += "project:${endpoint.address}"
            return RuntimeActionResult(!projectionFails, if (projectionFails) "injected failure" else "projected")
        }

        override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
            actions += "stop:$component"
            if (component in failedStops) return RuntimeActionResult(false, "injected timeout")
            observations[component] = ComponentObservation(component, ComponentLifecycle.STOPPED, false)
            return RuntimeActionResult(true, "stopped")
        }

        override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
            actions += "force:$component"
            if (component in failedForces) return RuntimeActionResult(false, "injected drain failure")
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
