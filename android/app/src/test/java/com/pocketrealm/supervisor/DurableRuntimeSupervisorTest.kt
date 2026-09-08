package com.pocketrealm.supervisor

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    @Test fun nullOwnerOrphanHealsAfterBoundedGraceViaMonitorLane() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            requestedProfile = "mobile-low-v1",
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.WORLD to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "world")),
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.WORLD] = ComponentObservation(
                RuntimeComponent.WORLD, ComponentLifecycle.READY, true, null, 999,
                "binder death cleared the claim")
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        // Grace window: the component-side teardown may be mid-flight.
        assertNull(runtime.selfHealOrphan(RuntimeComponent.WORLD))
        assertNull(runtime.selfHealOrphan(RuntimeComponent.WORLD))
        assertTrue(backend.actions.isEmpty())

        val healed = runtime.selfHealOrphan(RuntimeComponent.WORLD)

        assertNotNull(healed)
        assertEquals(RuntimePhase.ERROR, healed!!.snapshot.phase)
        assertTrue(backend.actions.indexOf("adopt:WORLD") < backend.actions.indexOf("force:WORLD"))
        // forceStop ran under the ADOPTED owner, never the stale journal token.
        assertEquals(
            backend.adoptOwners[RuntimeComponent.WORLD],
            backend.forceOwners.single { it.first == RuntimeComponent.WORLD }.second,
        )
        assertNotEquals(owner.instanceToken, backend.adoptOwners[RuntimeComponent.WORLD]!!.instanceToken)
        assertTrue(healed.snapshot.components.getValue(RuntimeComponent.WORLD).state == ComponentLifecycle.STOPPED)
    }

    @Test fun orphanGraceResetsWhenTheComponentRecoversMidWindow() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.WORLD to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "world")),
        )
        val backend = FakeBackend()
        val runtime = runtime(backend, MemoryJournal(initial))

        fun observeOrphan(ownerOrNull: ComponentOwner?) {
            backend.observations[RuntimeComponent.WORLD] = ComponentObservation(
                RuntimeComponent.WORLD, ComponentLifecycle.READY, true, ownerOrNull, 999, "probe")
        }

        observeOrphan(null)
        assertNull(runtime.selfHealOrphan(RuntimeComponent.WORLD))
        // The claim reappears (teardown finished or never started): grace resets.
        observeOrphan(owner)
        assertNull(runtime.selfHealOrphan(RuntimeComponent.WORLD))
        assertTrue(backend.actions.isEmpty())

        // A fresh orphan streak needs its full grace again.
        observeOrphan(null)
        assertNull(runtime.selfHealOrphan(RuntimeComponent.WORLD))
        assertNull(runtime.selfHealOrphan(RuntimeComponent.WORLD))
        assertTrue(backend.actions.isEmpty())
        assertNotNull(runtime.selfHealOrphan(RuntimeComponent.WORLD))
        assertTrue(backend.actions.contains("adopt:WORLD"))
    }

    @Test fun monitorLaneDatabaseOrphanRoutesToRecoveryNeverAdoptsOrKills() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.DATABASE to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "database")),
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.DATABASE] = ComponentObservation(
                RuntimeComponent.DATABASE, ComponentLifecycle.READY, true, null, 999, "ownerless")
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        assertNull(runtime.selfHealOrphan(RuntimeComponent.DATABASE))
        assertNull(runtime.selfHealOrphan(RuntimeComponent.DATABASE))

        val routed = runtime.selfHealOrphan(RuntimeComponent.DATABASE)

        assertNotNull(routed)
        assertTrue(routed!!.ok)
        assertTrue(routed.snapshot.clean)
        assertEquals(RuntimePhase.STOPPED, routed.snapshot.phase)
        assertTrue(backend.actions.contains("recover:DATABASE"))
        assertFalse(backend.actions.contains("adopt:DATABASE"))
        assertFalse(backend.actions.any { it.startsWith("force:") })
    }

    @Test fun recoveryHealsOwnerlessWorldOrphanAfterGraceAndCompletes() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            requestedProfile = "mobile-low-v1",
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.WORLD to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "world")),
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.WORLD] = ComponentObservation(
                RuntimeComponent.WORLD, ComponentLifecycle.READY, true, null, 999, "ownerless")
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        val recovered = runtime.recover()

        assertTrue(recovered.ok)
        assertTrue(recovered.snapshot.clean)
        assertEquals(RuntimePhase.STOPPED, recovered.snapshot.phase)
        assertTrue(backend.actions.indexOf("adopt:WORLD") < backend.actions.indexOf("force:WORLD"))
        assertTrue(backend.actions.contains("recover:DATABASE"))
    }

    @Test fun recoveryRoutesOwnerlessDatabaseToExistingRecoveryLaneWithoutKilling() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.DATABASE to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "database")),
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.DATABASE] = ComponentObservation(
                RuntimeComponent.DATABASE, ComponentLifecycle.READY, true, null, 999, "ownerless")
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        val recovered = runtime.recover()

        assertTrue(recovered.ok)
        assertTrue(recovered.snapshot.clean)
        assertEquals(listOf("recover:DATABASE"), backend.actions)
    }

    @Test fun orphanThatResolvesToForeignOwnerDuringGraceStillRefuses() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val foreign = ComponentOwner(SESSION, "bb".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.WORLD to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "world")),
        )
        val backend = FakeBackend().apply {
            scriptedObservations[RuntimeComponent.WORLD] = ArrayDeque(listOf(
                ComponentObservation(
                    RuntimeComponent.WORLD, ComponentLifecycle.READY, true, null, 999, "ownerless"),
                ComponentObservation(
                    RuntimeComponent.WORLD, ComponentLifecycle.READY, true, foreign, 999, "foreign owner"),
            ))
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        val recovered = runtime.recover()

        assertFalse(recovered.ok)
        assertEquals(RuntimePhase.ERROR, recovered.snapshot.phase)
        assertTrue(recovered.snapshot.lastError!!.contains("UNVERIFIED_ORPHAN"))
        assertTrue(backend.actions.none { it.startsWith("adopt:") || it.startsWith("force:") })
    }

    @Test fun consentedForceStopUnblocksTheUnverifiedOrphanTheAutomaticLanesRefuse() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val foreign = ComponentOwner(SESSION, "bb".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.WORLD to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "world")),
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.WORLD] = ComponentObservation(
                RuntimeComponent.WORLD, ComponentLifecycle.READY, true, foreign, 999,
                "foreign owner left by the failed start")
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        // The pinned refusal, unchanged on this exact fixture: the automatic
        // recovery lane still will not kill the unverified owner.
        val refused = runtime.recover()
        assertFalse(refused.ok)
        assertTrue(refused.snapshot.lastError!!.contains("UNVERIFIED_ORPHAN"))
        assertTrue(backend.actions.none { it.startsWith("force:") })

        val stopped = runtime.consentedForceStopOrphanStack()

        assertTrue(stopped.ok)
        assertEquals(RuntimePhase.STOPPED, stopped.snapshot.phase)
        assertFalse(stopped.snapshot.clean)
        assertEquals("consented-orphan-force-stop-committed", stopped.snapshot.lastDurableAction)
        // The kill ran under the owner the world itself reports - never the
        // stale journal token - and :database stayed with its recovery lane.
        assertEquals(listOf("force:WORLD"), backend.actions.filter { it.startsWith("force:") })
        assertEquals(foreign, backend.forceOwners.single().second)
        assertTrue(backend.actions.none { it.contains("DATABASE") })

        // The repair unblocks the normal start path: the next start runs
        // recovery (database heal included) and reaches WORLD_READY.
        backend.actions.clear()
        val restarted = runtime.start("mobile-low-v1", includeClient = false)

        assertTrue(restarted.ok)
        assertEquals(RuntimePhase.WORLD_READY, restarted.snapshot.phase)
        assertTrue(backend.actions.contains("recover:DATABASE"))
    }

    @Test fun consentedForceStopAdoptsAnOwnerlessOrphanUnderAFreshOwner() = runTest {
        val owner = ComponentOwner(SESSION, "aa".repeat(32))
        val initial = RuntimeSnapshot(
            sessionId = SESSION,
            phase = RuntimePhase.RUNNING,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents() +
                (RuntimeComponent.WORLD to ComponentSnapshot(
                    ComponentLifecycle.READY, owner.instanceToken, 1, "world")),
        )
        val backend = FakeBackend().apply {
            observations[RuntimeComponent.WORLD] = ComponentObservation(
                RuntimeComponent.WORLD, ComponentLifecycle.READY, true, null, 999, "ownerless")
        }
        val runtime = runtime(backend, MemoryJournal(initial))

        val stopped = runtime.consentedForceStopOrphanStack()

        assertTrue(stopped.ok)
        assertEquals(RuntimePhase.STOPPED, stopped.snapshot.phase)
        assertTrue(backend.actions.indexOf("adopt:WORLD") < backend.actions.indexOf("force:WORLD"))
        // forceStop ran under the ADOPTED owner, never the stale journal token.
        assertEquals(
            backend.adoptOwners[RuntimeComponent.WORLD],
            backend.forceOwners.single { it.first == RuntimeComponent.WORLD }.second,
        )
        assertNotEquals(owner.instanceToken, backend.adoptOwners[RuntimeComponent.WORLD]!!.instanceToken)
    }

    @Test fun startReleasesAProvenEndedOwnSessionDatabaseClaimAndReachesWorldReady() = runTest {
        val tokens = SequentialSessionTokens()
        val backend = FakeBackend()
        val journal = MemoryJournal()
        val runtime = runtime(backend, journal, tokens)

        // Session one runs. Under load its world dies and the database engine
        // stops with the service-side claim never released: every later stop
        // lane observes "already stopped" and skips the claim release, so the
        // journal is torn down cleanly while the claim survives.
        val first = runtime.start("mobile-low-v1", includeClient = false)
        assertTrue(first.ok)
        val staleOwner = ComponentOwner(
            first.snapshot.sessionId!!,
            first.snapshot.components.getValue(RuntimeComponent.DATABASE).instanceToken!!,
        )
        backend.observations[RuntimeComponent.DATABASE] = ComponentObservation(
            RuntimeComponent.DATABASE, ComponentLifecycle.STOPPED, false, staleOwner, 999,
            "engine stopped; claim never released",
        )
        backend.databaseStaleOwner = staleOwner
        val stopped = runtime.stop(StopMode.GRACEFUL)
        assertTrue(stopped.ok)
        assertTrue(stopped.snapshot.clean)

        // The next start's claim is rejected, the ended own-session claim is
        // proven (minted by this supervisor instance, engine down) and
        // released, and the retried claim carries the start to WORLD_READY.
        tokens.session = "22222222-2222-4222-8222-222222222222"
        backend.actions.clear()
        val restarted = runtime.start("mobile-low-v1", includeClient = false)

        assertTrue(restarted.ok)
        assertEquals(RuntimePhase.WORLD_READY, restarted.snapshot.phase)
        assertEquals(2, backend.actions.count { it == "start:DATABASE" })
        assertTrue(backend.actions.indexOf("stop:DATABASE") <
            backend.actions.lastIndexOf("start:DATABASE"))
        // The release ran under the observed stale owner and nothing was killed.
        assertEquals(staleOwner, backend.stopOwners.single { it.first == RuntimeComponent.DATABASE }.second)
        assertNull(backend.databaseStaleOwner)
        assertTrue(backend.actions.none { it.startsWith("force:") })
        assertTrue(journal.writes.any {
            it.lastDurableAction == "database-ended-own-session-owner-release-requested"
        })
    }

    @Test fun startRefusesToReleaseADatabaseClaimItCannotProve() = runTest {
        val foreign = ComponentOwner("99999999-9999-4999-8999-999999999999", "ee".repeat(32))
        val backend = FakeBackend().apply {
            databaseStaleOwner = foreign
            observations[RuntimeComponent.DATABASE] = ComponentObservation(
                RuntimeComponent.DATABASE, ComponentLifecycle.STOPPED, false, foreign, 999,
                "engine stopped; foreign claim",
            )
        }
        val runtime = runtime(backend)

        val rejected = runtime.start("mobile-low-v1", includeClient = false)

        // Not minted by this supervisor instance: the automatic path refuses
        // to touch it - no stop, no kill, no dedicated failure class.
        assertFalse(rejected.ok)
        assertEquals(RuntimePhase.ERROR, rejected.snapshot.phase)
        assertTrue(rejected.snapshot.lastError!!.contains("database is owned by another runtime session"))
        assertFalse(rejected.snapshot.lastError!!.contains("DB_OWNED_BY_DEAD_SESSION"))
        assertTrue(backend.actions.none { it.startsWith("stop:DATABASE") || it.startsWith("force:") })
        assertEquals(foreign, backend.databaseStaleOwner)
    }

    @Test fun startNeverReleasesAnEndedOwnSessionClaimWhileTheEngineIsLive() = runTest {
        val tokens = SequentialSessionTokens()
        val backend = FakeBackend()
        val journal = MemoryJournal()
        val runtime = runtime(backend, journal, tokens)
        val first = runtime.start("mobile-low-v1", includeClient = false)
        assertTrue(first.ok)
        val owner = ComponentOwner(
            first.snapshot.sessionId!!,
            first.snapshot.components.getValue(RuntimeComponent.DATABASE).instanceToken!!,
        )
        // The wedge teardown: engine down at stop time, so the claim is
        // skipped and the journal commits a clean stop without it...
        backend.observations[RuntimeComponent.DATABASE] = ComponentObservation(
            RuntimeComponent.DATABASE, ComponentLifecycle.STOPPED, false, owner, 999,
            "engine stopped; claim never released",
        )
        backend.databaseStaleOwner = owner
        assertTrue(runtime.stop(StopMode.GRACEFUL).ok)
        // ...but by the next claim the engine observably runs again: a live
        // generation belongs to the engine-ordered stop lanes, never to the
        // automatic release.
        backend.observations[RuntimeComponent.DATABASE] = ComponentObservation(
            RuntimeComponent.DATABASE, ComponentLifecycle.READY, true, owner, 999,
            "engine live under the ended session",
        )

        tokens.session = "22222222-2222-4222-8222-222222222222"
        backend.actions.clear()
        val rejected = runtime.start("mobile-low-v1", includeClient = false)

        assertFalse(rejected.ok)
        assertEquals(RuntimePhase.ERROR, rejected.snapshot.phase)
        assertFalse(rejected.snapshot.lastError!!.contains("DB_OWNED_BY_DEAD_SESSION"))
        assertTrue(backend.actions.none { it.startsWith("stop:DATABASE") || it.startsWith("force:") })
        assertEquals(owner, backend.databaseStaleOwner)
        assertTrue(journal.writes.none {
            it.lastDurableAction == "database-ended-own-session-owner-release-requested"
        })
    }

    @Test fun consentedForceStopReleasesTheDatabaseClaimTheStartLaneCouldNot() = runTest {
        val tokens = SequentialSessionTokens()
        val backend = FakeBackend()
        val runtime = runtime(backend, tokens = tokens)
        val first = runtime.start("mobile-low-v1", includeClient = false)
        assertTrue(first.ok)
        val staleOwner = ComponentOwner(
            first.snapshot.sessionId!!,
            first.snapshot.components.getValue(RuntimeComponent.DATABASE).instanceToken!!,
        )
        backend.observations[RuntimeComponent.DATABASE] = ComponentObservation(
            RuntimeComponent.DATABASE, ComponentLifecycle.STOPPED, false, staleOwner, 999,
            "engine stopped; claim never released",
        )
        backend.databaseStaleOwner = staleOwner
        assertTrue(runtime.stop(StopMode.GRACEFUL).ok)

        // The proven release itself fails on both verbs: the wedge surfaces
        // under its dedicated class for the consented repair.
        backend.failedStops += RuntimeComponent.DATABASE
        backend.failedForces += RuntimeComponent.DATABASE
        tokens.session = "22222222-2222-4222-8222-222222222222"
        val rejected = runtime.start("mobile-low-v1", includeClient = false)
        assertFalse(rejected.ok)
        assertEquals(RuntimePhase.ERROR, rejected.snapshot.phase)
        assertTrue(rejected.snapshot.lastError!!.contains("DB_OWNED_BY_DEAD_SESSION"))

        backend.failedStops.clear()
        backend.failedForces.clear()
        backend.actions.clear()
        val stopped = runtime.consentedForceStopOrphanStack()

        assertTrue(stopped.ok)
        assertEquals(RuntimePhase.STOPPED, stopped.snapshot.phase)
        assertFalse(stopped.snapshot.clean)
        assertEquals("consented-orphan-force-stop-committed", stopped.snapshot.lastDurableAction)
        // The release ran under the owner the database itself reports - the
        // journal has no token for it anymore - and cleared the claim.
        assertEquals(staleOwner, backend.stopOwners.last { it.first == RuntimeComponent.DATABASE }.second)
        assertNull(backend.databaseStaleOwner)
        assertTrue(backend.actions.none { it.startsWith("force:") })

        // A plain start afterwards reaches WORLD_READY through the existing
        // recovery + database prepare heal.
        tokens.session = "33333333-3333-4333-8333-333333333333"
        val restarted = runtime.start("mobile-low-v1", includeClient = false)
        assertTrue(restarted.ok)
        assertEquals(RuntimePhase.WORLD_READY, restarted.snapshot.phase)
        assertTrue(backend.actions.contains("recover:DATABASE"))
    }

    @Test fun foregroundPromotesWorldAndDatabaseImmediatelyOnRealPlayerPresence() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        backend.actions.clear()
        backend.worldPresence = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 1, playerbotsEnabled = true, onlinePlayers = 41)

        runtime.reconcileForegroundPromotion()

        assertEquals(listOf("promote:WORLD", "promote:DATABASE"), backend.actions)
        // Already promoted: the immediate edge never re-fires.
        runtime.reconcileForegroundPromotion()
        assertEquals(listOf("promote:WORLD", "promote:DATABASE"), backend.actions)
    }

    @Test fun botOnlyPresenceNeverPromotes() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        backend.actions.clear()
        // Naive reading: onlinePlayers counts bots; with bots enabled it
        // must never fire promotion.
        backend.worldPresence = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 50)

        repeat(5) { runtime.reconcileForegroundPromotion() }

        assertTrue(backend.actions.isEmpty())
    }

    @Test fun botlessRealmPromotesOnAnyOnlineSession() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        backend.actions.clear()
        // Naive reading in the other direction: realPlayers alone would
        // never fire on a botless realm while a player is connecting.
        backend.worldPresence = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 0, playerbotsEnabled = false, onlinePlayers = 1)

        runtime.reconcileForegroundPromotion()

        assertEquals(listOf("promote:WORLD", "promote:DATABASE"), backend.actions)
    }

    @Test fun foregroundDemotesOnlyAfterThreeConsecutiveEmptySamples() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        backend.worldPresence = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 2, playerbotsEnabled = true, onlinePlayers = 42)
        runtime.reconcileForegroundPromotion()
        backend.actions.clear()
        // Every player logged out; the bots stay online.
        backend.worldPresence = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 40)

        runtime.reconcileForegroundPromotion()
        runtime.reconcileForegroundPromotion()
        assertTrue(backend.actions.none { it.startsWith("demote:") })

        runtime.reconcileForegroundPromotion()

        assertEquals(listOf("demote:WORLD", "demote:DATABASE"), backend.actions)
        // Stays demoted: no repeated demotion intents.
        runtime.reconcileForegroundPromotion()
        assertEquals(listOf("demote:WORLD", "demote:DATABASE"), backend.actions)
    }

    @Test fun emptySampleStreakResetsWhenAPlayerReturns() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        val players = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 1, playerbotsEnabled = true, onlinePlayers = 40)
        val playerless = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 0, playerbotsEnabled = true, onlinePlayers = 40)
        backend.worldPresence = players
        runtime.reconcileForegroundPromotion()
        backend.actions.clear()

        backend.worldPresence = playerless
        runtime.reconcileForegroundPromotion()
        runtime.reconcileForegroundPromotion()
        backend.worldPresence = players
        runtime.reconcileForegroundPromotion()
        backend.worldPresence = playerless
        runtime.reconcileForegroundPromotion()
        runtime.reconcileForegroundPromotion()
        assertTrue(backend.actions.none { it.startsWith("demote:") })
    }

    @Test fun saveExitDemotesPromotedForegroundAfterComponentStops() = runTest {
        val backend = FakeBackend()
        val runtime = runtime(backend)
        assertTrue(runtime.start("mobile-low-v1", includeClient = false).ok)
        backend.worldPresence = WorldPresenceSample(
            ComponentLifecycle.READY, realPlayers = 1, playerbotsEnabled = true, onlinePlayers = 40)
        runtime.reconcileForegroundPromotion()
        backend.actions.clear()

        val stopped = runtime.stop(StopMode.GRACEFUL)

        assertTrue(stopped.ok)
        assertTrue(backend.actions.contains("demote:WORLD"))
        assertTrue(backend.actions.contains("demote:DATABASE"))
        // Demotion trails the component stops so it can never resurrect a
        // freshly stopped service (the save&exit race).
        assertTrue(backend.actions.indexOf("demote:WORLD") > backend.actions.indexOf("stop:WORLD"))
        assertTrue(backend.actions.indexOf("demote:DATABASE") > backend.actions.indexOf("stop:DATABASE"))
    }

    private fun runtime(
        backend: FakeBackend,
        journal: MemoryJournal = MemoryJournal(),
        tokens: RuntimeTokenSource = DeterministicTokens(),
    ) = DurableRuntimeSupervisor(
        backend = backend,
        journal = journal,
        tokens = tokens,
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

    /** Distinct session ids per supervisor start, for minted-session proofs. */
    private class SequentialSessionTokens : RuntimeTokenSource {
        var session = SESSION
        private var next = 0
        override fun sessionId() = session
        override fun instanceToken(): String = (++next).toString(16).padStart(2, '0').repeat(32)
    }

    private class FakeBackend : RuntimeBackend {
        val actions = mutableListOf<String>()
        val observations = RuntimeComponent.entries.associateWith {
            ComponentObservation(it, ComponentLifecycle.STOPPED, false)
        }.toMutableMap()
        /** Observations returned once each before falling back to [observations]. */
        val scriptedObservations = mutableMapOf<RuntimeComponent, ArrayDeque<ComponentObservation>>()
        val adoptOwners = mutableMapOf<RuntimeComponent, ComponentOwner>()
        val forceOwners = mutableListOf<Pair<RuntimeComponent, ComponentOwner>>()
        val stopOwners = mutableListOf<Pair<RuntimeComponent, ComponentOwner>>()
        /** While set, start(DATABASE) rejects the claim (the stale service-side lock). */
        var databaseStaleOwner: ComponentOwner? = null
        val failedStarts = mutableSetOf<RuntimeComponent>()
        val failedStops = mutableSetOf<RuntimeComponent>()
        val failedForces = mutableSetOf<RuntimeComponent>()
        val pidOnly = mutableSetOf<RuntimeComponent>()
        var projectionFails = false
        var preflightAllowed = true
        var preflightDetail = "preflight"
        var worldPresence = WorldPresenceSample.EMPTY

        override suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeActionResult {
            actions += "preflight:${spec.mode}"
            return RuntimeActionResult(
                preflightAllowed && spec.profileId == "mobile-low-v1",
                preflightDetail,
            )
        }

        override suspend fun observe(component: RuntimeComponent): ComponentObservation {
            scriptedObservations[component]?.let { script ->
                val value = script.removeFirst()
                observations[component] = value
                if (script.isEmpty()) scriptedObservations.remove(component)
                return value
            }
            return observations.getValue(component)
        }

        override suspend fun start(
            component: RuntimeComponent,
            owner: ComponentOwner,
            spec: RuntimeLaunchSpec,
        ): ComponentObservation {
            actions += "start:$component"
            // The service-side ComponentOwnership check at the claim boundary.
            if (component == RuntimeComponent.DATABASE && databaseStaleOwner != null) {
                throw IllegalStateException("database is owned by another runtime session")
            }
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
            stopOwners += component to owner
            if (component in failedStops) return RuntimeActionResult(false, "injected timeout")
            if (component == RuntimeComponent.DATABASE && owner == databaseStaleOwner) {
                databaseStaleOwner = null
            }
            observations[component] = ComponentObservation(component, ComponentLifecycle.STOPPED, false)
            return RuntimeActionResult(true, "stopped")
        }

        override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
            actions += "force:$component"
            forceOwners += component to owner
            if (component in failedForces) return RuntimeActionResult(false, "injected drain failure")
            if (component == RuntimeComponent.DATABASE && owner == databaseStaleOwner) {
                databaseStaleOwner = null
            }
            observations[component] = ComponentObservation(component, ComponentLifecycle.STOPPED, false)
            return RuntimeActionResult(true, "forced")
        }

        override suspend fun adopt(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
            val current = observations.getValue(component)
            return if (OrphanSelfHealPolicy.isHealableOrphan(current.state, current.owner)) {
                actions += "adopt:$component"
                adoptOwners[component] = owner
                observations[component] = current.copy(owner = owner)
                RuntimeActionResult(true, "adopted")
            } else {
                actions += "adopt-rejected:$component"
                RuntimeActionResult(false, "component is not an ownerless running orphan")
            }
        }

        override suspend fun observeWorldPresence(): WorldPresenceSample = worldPresence

        override suspend fun promoteToForeground(component: RuntimeComponent): RuntimeActionResult {
            actions += "promote:$component"
            return RuntimeActionResult(true, "promoted")
        }

        override suspend fun demoteToForeground(component: RuntimeComponent): RuntimeActionResult {
            actions += "demote:$component"
            return RuntimeActionResult(true, "demoted")
        }

        override suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult {
            actions += "save:WORLD"
            return RuntimeActionResult(true, "saved")
        }

        override suspend fun setCompanionMode(owner: ComponentOwner, enabled: Boolean): RuntimeActionResult {
            actions += "companion:$enabled"
            return RuntimeActionResult(true, "companion")
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
