package com.pocketrealm.supervisor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Report sections 6.4 and 18 implemented as the single durable state owner. */
class DurableRuntimeSupervisor(
    private val backend: RuntimeBackend,
    private val journal: SupervisorJournal,
    private val tokens: RuntimeTokenSource = SecureRuntimeTokenSource(),
    private val clock: RuntimeClock = AndroidRuntimeClock,
    private val timeouts: RuntimeTimeouts = RuntimeTimeouts(),
) : AutoCloseable {
    private val operationLock = Mutex()
    private val _state = MutableStateFlow(journal.read() ?: RuntimeSnapshot())
    val state: StateFlow<RuntimeSnapshot> = _state.asStateFlow()

    suspend fun preflight(profileId: String): RuntimeOperation = operationLock.withLock {
        val result = runCatching { backend.preflight(profileId) }
            .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (!result.ok) {
            publish(_state.value.copy(
                phase = RuntimePhase.UNCONFIGURED,
                lastError = bounded(result.detail),
                lastDurableAction = "preflight-failed",
                recoverability = Recoverability.USER_ACTION_REQUIRED,
            ))
        }
        RuntimeOperation(result.ok, _state.value, result.detail)
    }

    suspend fun start(profileId: String, includeClient: Boolean): RuntimeOperation = operationLock.withLock {
        require(PROFILE.matches(profileId)) { "invalid profile identity" }
        // A process-recreated supervisor may load a dirty active-looking phase
        // from the prior generation. Recover that journal before applying the
        // ordinary "already active" guard; persisted phase is not liveness.
        if (!_state.value.clean && !recoverLocked()) {
            return@withLock operation(false, _state.value.lastError ?: "recovery failed")
        }
        if (_state.value.phase !in STARTABLE) return@withLock operation(false, "runtime is already active")
        val preflight = runCatching { backend.preflight(profileId) }
            .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (!preflight.ok) {
            fail(RuntimePhase.UNCONFIGURED, preflight.detail, Recoverability.USER_ACTION_REQUIRED)
            return@withLock operation(false, preflight.detail)
        }

        val sessionId = tokens.sessionId()
        publish(RuntimeSnapshot(
            sessionId = sessionId,
            requestedProfile = profileId,
            clean = false,
            lastDurableAction = "start-accepted",
            updatedAtWallMs = clock.wallMs(),
            updatedAtElapsedMs = clock.elapsedMs(),
        ))
        for ((component, phase) in SERVER_START_ORDER) {
            if (!startStage(component, phase, sessionId, profileId)) {
                stopStartedAfterFailure(component)
                return@withLock operation(false, _state.value.lastError ?: "$component start failed")
            }
        }
        if (!includeClient) {
            publish(_state.value.copy(
                phase = RuntimePhase.WORLD_READY,
                lastDurableAction = "world-ready-client-not-requested",
                recoverability = Recoverability.RELAUNCH_CLIENT,
            ))
            return@withLock operation(true, "native realm ready; client not requested")
        }
        if (!startStage(RuntimeComponent.CLIENT, RuntimePhase.CLIENT_STARTING, sessionId, profileId)) {
            publish(_state.value.copy(
                phase = RuntimePhase.CLIENT_FAILED,
                lastDurableAction = "client-start-failed-realm-retained",
                recoverability = Recoverability.RELAUNCH_CLIENT,
            ))
            return@withLock operation(false, _state.value.lastError ?: "client start failed")
        }
        publish(_state.value.copy(
            phase = RuntimePhase.RUNNING,
            lastDurableAction = "client-window-ready",
            recoverability = Recoverability.NONE,
        ))
        operation(true, "runtime ready")
    }

    suspend fun relaunchClient(): RuntimeOperation = operationLock.withLock {
        val current = _state.value
        if (current.phase !in setOf(RuntimePhase.CLIENT_FAILED, RuntimePhase.WORLD_READY)) {
            return@withLock operation(false, "client relaunch is not available in ${current.phase}")
        }
        if (listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD)
                .any { current.components.getValue(it).state != ComponentLifecycle.READY }) {
            return@withLock operation(false, "server dependency is not ready")
        }
        val session = checkNotNull(current.sessionId)
        val profile = checkNotNull(current.requestedProfile)
        if (!startStage(RuntimeComponent.CLIENT, RuntimePhase.CLIENT_STARTING, session, profile)) {
            publish(_state.value.copy(
                phase = RuntimePhase.CLIENT_FAILED,
                lastDurableAction = "client-relaunch-failed",
                recoverability = Recoverability.RELAUNCH_CLIENT,
            ))
            return@withLock operation(false, _state.value.lastError ?: "client relaunch failed")
        }
        publish(_state.value.copy(
            phase = RuntimePhase.RUNNING,
            lastDurableAction = "client-relaunch-window-ready",
            recoverability = Recoverability.NONE,
        ))
        operation(true, "client relaunched")
    }

    suspend fun stop(mode: StopMode): RuntimeOperation = operationLock.withLock {
        if (_state.value.phase == RuntimePhase.STOPPED && _state.value.clean) {
            return@withLock operation(true, "already stopped")
        }
        stopLocked(mode, finalPhase = RuntimePhase.STOPPED, preserveError = null)
    }

    suspend fun recover(): RuntimeOperation = operationLock.withLock {
        if (_state.value.clean) return@withLock operation(true, "journal is already clean")
        val ok = recoverLocked()
        operation(ok, if (ok) "recovery complete" else _state.value.lastError ?: "recovery failed")
    }

    suspend fun componentFailed(component: RuntimeComponent, detail: String): RuntimeOperation =
        operationLock.withLock {
            if (component == RuntimeComponent.CLIENT) {
                updateComponent(component, ComponentLifecycle.FAILED, detail = detail)
                publish(_state.value.copy(
                    phase = RuntimePhase.CLIENT_FAILED,
                    lastError = bounded("CLIENT: $detail"),
                    lastDurableAction = "client-failed-realm-retained",
                    recoverability = Recoverability.RELAUNCH_CLIENT,
                ))
                return@withLock operation(true, "client failure isolated")
            }
            updateComponent(component, ComponentLifecycle.FAILED, detail = detail)
            stopLocked(StopMode.FORCED, RuntimePhase.ERROR, "$component: $detail")
        }

    private suspend fun startStage(
        component: RuntimeComponent,
        phase: RuntimePhase,
        sessionId: String,
        profileId: String,
    ): Boolean {
        val owner = ComponentOwner(sessionId, tokens.instanceToken())
        updateComponent(component, ComponentLifecycle.STARTING, owner, "launch requested")
        publish(_state.value.copy(
            phase = phase,
            lastDurableAction = "${component.name.lowercase()}-start-requested",
            recoverability = Recoverability.RETRY,
        ))
        val observation = runCatching {
            withTimeout(timeouts.start(component)) { backend.start(component, owner, profileId) }
        }.getOrElse {
            failStage(component, "${it.javaClass.simpleName}: ${it.message}")
            return false
        }
        val owned = observation.owner == owner
        if (!observation.ready || observation.state != ComponentLifecycle.READY || !owned) {
            failStage(component, "readiness/ownership proof rejected: ${observation.detail}")
            return false
        }
        updateComponent(component, ComponentLifecycle.READY, owner, observation.detail)
        publish(_state.value.copy(lastDurableAction = "${component.name.lowercase()}-ready"))
        return true
    }

    private suspend fun recoverLocked(): Boolean {
        val prior = _state.value
        publish(prior.copy(
            phase = RuntimePhase.RECOVERING,
            clean = false,
            lastDurableAction = "dirty-journal-recovery-started",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        ))
        for (component in STOP_ORDER) {
            val recorded = prior.components.getValue(component)
            val token = recorded.instanceToken ?: continue
            val session = prior.sessionId ?: continue
            val owner = ComponentOwner(session, token)
            val observation = runCatching { backend.observe(component) }.getOrElse {
                fail(RuntimePhase.ERROR, "recovery observe $component failed: ${it.message}")
                return false
            }
            if (observation.state == ComponentLifecycle.STOPPED) continue
            if (observation.owner != owner) {
                fail(RuntimePhase.ERROR, "UNVERIFIED_ORPHAN: $component ownership did not match")
                return false
            }
            val stopped = runCatching {
                withTimeout(timeouts.stop(component)) { backend.forceStop(component, owner) }
            }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            if (!stopped.ok) {
                fail(RuntimePhase.ERROR, "owned $component recovery stop failed: ${stopped.detail}")
                return false
            }
        }
        val database = runCatching {
            withTimeout(timeouts.recoveryMs) { backend.recoverDatabase() }
        }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (!database.ok) {
            fail(RuntimePhase.ERROR, "database recovery failed: ${database.detail}")
            return false
        }
        publish(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = true,
            lastDurableAction = "dirty-recovery-complete",
            updatedAtWallMs = clock.wallMs(),
            updatedAtElapsedMs = clock.elapsedMs(),
        ))
        return true
    }

    private suspend fun stopStartedAfterFailure(failed: RuntimeComponent) {
        val detail = _state.value.lastError ?: "$failed failed"
        stopLocked(StopMode.GRACEFUL, RuntimePhase.ERROR, detail)
    }

    private suspend fun stopLocked(
        mode: StopMode,
        finalPhase: RuntimePhase,
        preserveError: String?,
    ): RuntimeOperation {
        publish(_state.value.copy(
            phase = RuntimePhase.STOPPING,
            clean = false,
            lastDurableAction = "stop-requested",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        ))
        var durable = mode == StopMode.GRACEFUL
        // Exact report order: client close/exit first while the world remains
        // available, then save the world, then world -> realm -> database.
        val clientOwner = ownerOf(RuntimeComponent.CLIENT)
        if (clientOwner != null &&
            _state.value.components.getValue(RuntimeComponent.CLIENT).state != ComponentLifecycle.STOPPED) {
            updateComponent(RuntimeComponent.CLIENT, ComponentLifecycle.STOPPING, clientOwner, "stop requested")
            val graceful = if (mode == StopMode.GRACEFUL)
                stopOwned(RuntimeComponent.CLIENT, clientOwner)
            else RuntimeActionResult(false, "forced stop requested")
            if (!graceful.ok) {
                durable = false
                val forced = forceOwned(RuntimeComponent.CLIENT, clientOwner)
                if (!forced.ok) {
                    fail(RuntimePhase.ERROR, "cannot stop owned CLIENT: ${forced.detail}")
                    return operation(false, _state.value.lastError ?: "stop failed")
                }
            }
            updateComponent(RuntimeComponent.CLIENT, ComponentLifecycle.STOPPED,
                detail = if (graceful.ok) "clean stop" else "forced stop")
            publish(_state.value.copy(lastDurableAction = "client-stopped"))
        }
        val world = ownerOf(RuntimeComponent.WORLD)
        if (world != null && _state.value.components.getValue(RuntimeComponent.WORLD).state == ComponentLifecycle.READY) {
            val saved = runCatching {
                withTimeout(timeouts.componentStopMs) { backend.saveWorld(world) }
            }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            durable = durable && saved.ok
            publish(_state.value.copy(lastDurableAction = if (saved.ok) "world-save-acknowledged" else "world-save-failed"))
        }
        for (component in STOP_ORDER.drop(1)) {
            val owner = ownerOf(component) ?: continue
            val recorded = _state.value.components.getValue(component)
            if (recorded.state == ComponentLifecycle.STOPPED) continue
            updateComponent(component, ComponentLifecycle.STOPPING, owner, "stop requested")
            val graceful = if (mode == StopMode.GRACEFUL) stopOwned(component, owner) else RuntimeActionResult(false, "forced stop requested")
            if (!graceful.ok) {
                durable = false
                val forced = forceOwned(component, owner)
                if (!forced.ok) {
                    fail(RuntimePhase.ERROR, "cannot stop owned $component: ${forced.detail}")
                    return operation(false, _state.value.lastError ?: "stop failed")
                }
            }
            updateComponent(component, ComponentLifecycle.STOPPED, detail = if (graceful.ok) "clean stop" else "forced stop")
            publish(_state.value.copy(lastDurableAction = "${component.name.lowercase()}-stopped"))
        }
        val finalClean = durable && preserveError == null
        publish(_state.value.copy(
            phase = finalPhase,
            clean = finalClean,
            components = RuntimeSnapshot.stoppedComponents(),
            lastDurableAction = if (finalClean) "clean-stop-committed" else "dirty-stop-committed",
            lastError = preserveError?.let(::bounded) ?: if (finalClean) null else _state.value.lastError,
            recoverability = if (finalClean) Recoverability.NONE else Recoverability.RECOVERY_REQUIRED,
        ))
        return operation(finalClean || finalPhase == RuntimePhase.ERROR,
            if (finalClean) "clean stop complete" else "stack stopped dirty")
    }

    private suspend fun stopOwned(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
        val observed = runCatching { backend.observe(component) }
            .getOrElse { return RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (observed.state == ComponentLifecycle.STOPPED) return RuntimeActionResult(true, "already stopped")
        if (observed.owner != owner) return RuntimeActionResult(false, "ownership mismatch; signal withheld")
        return runCatching {
            withTimeout(timeouts.stop(component)) { backend.stop(component, owner) }
        }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
    }

    private suspend fun forceOwned(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult {
        val observed = runCatching { backend.observe(component) }
            .getOrElse { return RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (observed.state == ComponentLifecycle.STOPPED) return RuntimeActionResult(true, "already stopped")
        if (observed.owner != owner) return RuntimeActionResult(false, "ownership mismatch; kill withheld")
        return runCatching { backend.forceStop(component, owner) }
            .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
    }

    private fun ownerOf(component: RuntimeComponent): ComponentOwner? {
        val current = _state.value
        val session = current.sessionId ?: return null
        val token = current.components.getValue(component).instanceToken ?: return null
        return ComponentOwner(session, token)
    }

    private fun failStage(component: RuntimeComponent, detail: String) {
        updateComponent(component, ComponentLifecycle.FAILED, detail = detail)
        publish(_state.value.copy(
            lastError = bounded("$component: $detail"),
            lastDurableAction = "${component.name.lowercase()}-start-failed",
            recoverability = if (component == RuntimeComponent.CLIENT)
                Recoverability.RELAUNCH_CLIENT else Recoverability.RETRY,
        ))
    }

    private fun fail(
        phase: RuntimePhase,
        detail: String,
        recoverability: Recoverability = Recoverability.RECOVERY_REQUIRED,
    ) {
        publish(_state.value.copy(
            phase = phase,
            clean = false,
            lastError = bounded(detail),
            lastDurableAction = "error-recorded",
            recoverability = recoverability,
        ))
    }

    private fun updateComponent(
        component: RuntimeComponent,
        lifecycle: ComponentLifecycle,
        owner: ComponentOwner? = null,
        detail: String = "",
    ) {
        val current = _state.value
        val previous = current.components.getValue(component)
        val next = previous.copy(
            state = lifecycle,
            instanceToken = owner?.instanceToken ?: if (lifecycle == ComponentLifecycle.STOPPED) null else previous.instanceToken,
            startedAtWallMs = if (lifecycle == ComponentLifecycle.STARTING) clock.wallMs() else previous.startedAtWallMs,
            detail = bounded(detail),
        )
        publish(current.copy(components = current.components + (component to next)))
    }

    private fun publish(snapshot: RuntimeSnapshot) {
        val timed = snapshot.copy(updatedAtWallMs = clock.wallMs(), updatedAtElapsedMs = clock.elapsedMs())
        journal.write(timed)
        _state.value = timed
    }

    private fun operation(ok: Boolean, detail: String) = RuntimeOperation(ok, _state.value, bounded(detail))
    private fun bounded(value: String) = value.take(512)

    override fun close() = backend.close()

    companion object {
        private val PROFILE = Regex("[A-Za-z0-9._-]{1,64}")
        private val STARTABLE = setOf(RuntimePhase.STOPPED, RuntimePhase.ERROR, RuntimePhase.UNCONFIGURED)
        private val SERVER_START_ORDER = listOf(
            RuntimeComponent.DATABASE to RuntimePhase.DB_STARTING,
            RuntimeComponent.REALM to RuntimePhase.REALM_STARTING,
            RuntimeComponent.WORLD to RuntimePhase.WORLD_STARTING,
        )
        private val STOP_ORDER = listOf(
            RuntimeComponent.CLIENT,
            RuntimeComponent.WORLD,
            RuntimeComponent.REALM,
            RuntimeComponent.DATABASE,
        )
    }
}
