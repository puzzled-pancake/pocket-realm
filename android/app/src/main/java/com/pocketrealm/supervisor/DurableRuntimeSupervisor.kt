package com.pocketrealm.supervisor

import com.pocketrealm.bots.BotProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Durable supervisor state machine implemented as the single state owner. */
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

    suspend fun preflight(profileId: String): RuntimeOperation =
        preflight(RuntimeLaunchSpec.local(profileId))

    suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeOperation = operationLock.withLock {
        if (_state.value.phase !in STARTABLE || !_state.value.clean) {
            return@withLock operation(false, "runtime must be clean and stopped before preflight")
        }
        val result = runCatching { backend.preflight(spec) }
            .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (!result.ok) {
            publishPreflightFailure(spec, result.detail)
        }
        RuntimeOperation(result.ok, _state.value, result.detail)
    }

    suspend fun start(profileId: String, includeClient: Boolean): RuntimeOperation =
        start(RuntimeLaunchSpec.local(profileId, includeClient))

    suspend fun start(spec: RuntimeLaunchSpec): RuntimeOperation = operationLock.withLock {
        // A process-recreated supervisor may load a dirty active-looking phase
        // from the prior generation. Recover that journal before applying the
        // ordinary "already active" guard; persisted phase is not liveness.
        if (!_state.value.clean) {
            if (spec.mode == RuntimeMode.LAN_JOIN && _state.value.runtimeMode != RuntimeMode.LAN_JOIN) {
                return@withLock operation(false, "recover the interrupted local runtime before joining LAN")
            }
            if (!recoverLocked()) {
                return@withLock operation(false, _state.value.lastError ?: "recovery failed")
            }
        }
        if (_state.value.phase !in STARTABLE) return@withLock operation(false, "runtime is already active")
        val preflight = runCatching { backend.preflight(spec) }
            .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (!preflight.ok) {
            publishPreflightFailure(spec, preflight.detail)
            return@withLock operation(false, preflight.detail)
        }

        val sessionId = tokens.sessionId()
        publish(RuntimeSnapshot(
            sessionId = sessionId,
            requestedProfile = spec.profileId,
            runtimeMode = spec.mode,
            realmEndpoint = spec.endpoint,
            clean = false,
            lastDurableAction = "start-accepted",
            updatedAtWallMs = clock.wallMs(),
            updatedAtElapsedMs = clock.elapsedMs(),
        ))
        for (component in spec.componentPlan()) {
            if (!startStage(component, phaseFor(component), sessionId, spec)) {
                if (component == RuntimeComponent.CLIENT) {
                    return@withLock isolateClientFailure(
                        detail = _state.value.lastError ?: "client start failed",
                        durableAction = if (spec.mode == RuntimeMode.LAN_JOIN)
                            "lan-client-start-failed" else "client-start-failed-realm-retained",
                        isolatedOperationOk = false,
                    )
                }
                stopStartedAfterFailure(component)
                return@withLock operation(false, _state.value.lastError ?: "$component start failed")
            }
            if (component == RuntimeComponent.DATABASE) {
                val projected = runCatching {
                    backend.projectRealmEndpoint(checkNotNull(ownerOf(component)), spec.endpoint)
                }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
                if (!projected.ok) {
                    failStage(component, "realm endpoint projection failed: ${projected.detail}")
                    stopStartedAfterFailure(component)
                    return@withLock operation(false, _state.value.lastError ?: "endpoint projection failed")
                }
                publish(_state.value.copy(lastDurableAction = "realm-endpoint-projected"))
            }
        }
        if (!spec.includeClient) {
            publish(_state.value.copy(
                phase = RuntimePhase.WORLD_READY,
                lastDurableAction = "world-ready-client-not-requested",
                recoverability = Recoverability.RELAUNCH_CLIENT,
            ))
            return@withLock operation(true, "native realm ready; client not requested")
        }
        publish(_state.value.copy(
            phase = RuntimePhase.RUNNING,
            lastDurableAction = "client-window-ready",
            recoverability = Recoverability.NONE,
        ))
        operation(true, if (spec.mode == RuntimeMode.LAN_JOIN) "LAN client ready" else "runtime ready")
    }

    suspend fun relaunchClient(): RuntimeOperation = operationLock.withLock {
        val current = _state.value
        if (current.phase !in setOf(RuntimePhase.CLIENT_FAILED, RuntimePhase.WORLD_READY)) {
            return@withLock operation(false, "client relaunch is not available in ${current.phase}")
        }
        if (current.runtimeMode != RuntimeMode.LAN_JOIN &&
            listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD)
                .any { current.components.getValue(it).state != ComponentLifecycle.READY }) {
            return@withLock operation(false, "server dependency is not ready")
        }
        val session = checkNotNull(current.sessionId)
        val spec = launchSpecOf(current, includeClient = true)
        val retainedOwner = ownerOf(RuntimeComponent.CLIENT)
        if (retainedOwner != null) {
            val drained = forceOwned(RuntimeComponent.CLIENT, retainedOwner)
            if (!drained.ok) {
                fail(
                    RuntimePhase.ERROR,
                    "CLIENT retry cleanup failed: ${drained.detail}",
                    Recoverability.RECOVERY_REQUIRED,
                )
                return@withLock operation(false, _state.value.lastError ?: "client retry cleanup failed")
            }
            updateComponent(RuntimeComponent.CLIENT, ComponentLifecycle.STOPPED,
                detail = "prior client owner drained before relaunch")
        }
        if (!startStage(RuntimeComponent.CLIENT, RuntimePhase.CLIENT_STARTING, session, spec)) {
            return@withLock isolateClientFailure(
                detail = _state.value.lastError ?: "client relaunch failed",
                durableAction = "client-relaunch-failed",
                isolatedOperationOk = false,
            )
        }
        publish(_state.value.copy(
            phase = RuntimePhase.RUNNING,
            lastDurableAction = "client-relaunch-window-ready",
            lastError = null,
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

    /**
     * Converts an exception which escaped a service operation into a durable,
     * visible terminal state. A failure before any generation was accepted is
     * safe to retry without recovery. Once a generation may own work, the
     * journal stays dirty so a later Start cannot skip ownership recovery.
     */
    suspend fun unexpectedOperationFailure(detail: String): RuntimeOperation = operationLock.withLock {
        val current = _state.value
        val generationMayBeActive = !current.clean || current.sessionId != null ||
            current.phase !in INACTIVE_TERMINAL_PHASES ||
            current.components.values.any { it.state != ComponentLifecycle.STOPPED }
        if (generationMayBeActive) {
            publish(current.copy(
                phase = RuntimePhase.ERROR,
                clean = false,
                lastError = bounded(detail),
                lastDurableAction = "unexpected-operation-failure",
                recoverability = Recoverability.RECOVERY_REQUIRED,
            ))
        } else {
            publish(RuntimeSnapshot(
                phase = RuntimePhase.ERROR,
                requestedProfile = current.requestedProfile,
                runtimeMode = current.runtimeMode,
                realmEndpoint = current.realmEndpoint,
                clean = true,
                components = RuntimeSnapshot.stoppedComponents(),
                lastDurableAction = "unexpected-operation-failure-before-generation",
                lastError = bounded(detail),
                recoverability = Recoverability.RETRY,
            ))
        }
        operation(false, detail)
    }

    suspend fun provisionAccount(
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult = operationLock.withLock {
        if (username.length !in 1..16 || !username.all { it.isLetterOrDigit() && it.code < 128 }) {
            return@withLock AccountProvisionResult(false, "ACCOUNT_INVALID", detail = "invalid username")
        }
        if (password.length !in 1..16 || !password.all { it.isLetterOrDigit() && it.code < 128 }) {
            return@withLock AccountProvisionResult(false, "ACCOUNT_INVALID", detail = "invalid password")
        }
        if (gmLevel !in 0..3) {
            return@withLock AccountProvisionResult(false, "ACCOUNT_INVALID", detail = "invalid GM level")
        }
        val current = _state.value
        if (current.phase !in setOf(RuntimePhase.WORLD_READY, RuntimePhase.RUNNING, RuntimePhase.CLIENT_FAILED)) {
            return@withLock AccountProvisionResult(false, "WORLD_NOT_READY")
        }
        val owner = ownerOf(RuntimeComponent.WORLD)
            ?: return@withLock AccountProvisionResult(false, "WORLD_NOT_OWNED")
        val result = runCatching { backend.provisionAccount(owner, username, password, gmLevel) }
            .getOrElse { AccountProvisionResult(false, "ACCOUNT_CONTROL_FAILED", detail = it.javaClass.simpleName) }
        publish(_state.value.copy(lastDurableAction = if (result.ok)
            "account-provisioned-core-command" else "account-provision-failed"))
        result
    }

    suspend fun componentFailed(component: RuntimeComponent, detail: String): RuntimeOperation =
        operationLock.withLock {
            if (component == RuntimeComponent.CLIENT) {
                return@withLock isolateClientFailure(
                    detail = "CLIENT: $detail",
                    durableAction = if (_state.value.runtimeMode == RuntimeMode.LAN_JOIN)
                        "lan-client-failed" else "client-failed-realm-retained",
                    isolatedOperationOk = true,
                )
            }
            updateComponent(component, ComponentLifecycle.FAILED, detail = detail)
            stopLocked(StopMode.FORCED, RuntimePhase.ERROR, "$component: $detail")
        }

    /** A relaunchable client failure is published only after exact-owner teardown succeeds. */
    private suspend fun isolateClientFailure(
        detail: String,
        durableAction: String,
        isolatedOperationOk: Boolean,
    ): RuntimeOperation {
        val retainedOwner = ownerOf(RuntimeComponent.CLIENT)
        if (retainedOwner != null) {
            val drained = forceOwned(RuntimeComponent.CLIENT, retainedOwner)
            if (!drained.ok) {
                fail(
                    RuntimePhase.ERROR,
                    "CLIENT cleanup failed: ${drained.detail}",
                    Recoverability.RECOVERY_REQUIRED,
                )
                return operation(false, _state.value.lastError ?: "client cleanup failed")
            }
            updateComponent(RuntimeComponent.CLIENT, ComponentLifecycle.STOPPED,
                detail = "failed client owner drained")
        }
        updateComponent(RuntimeComponent.CLIENT, ComponentLifecycle.FAILED, detail = detail)
        publish(_state.value.copy(
            phase = RuntimePhase.CLIENT_FAILED,
            lastError = bounded(detail),
            lastDurableAction = durableAction,
            recoverability = Recoverability.RELAUNCH_CLIENT,
        ))
        return operation(isolatedOperationOk, "client failure isolated")
    }

    private suspend fun startStage(
        component: RuntimeComponent,
        phase: RuntimePhase,
        sessionId: String,
        spec: RuntimeLaunchSpec,
    ): Boolean {
        val owner = ComponentOwner(sessionId, tokens.instanceToken())
        updateComponent(component, ComponentLifecycle.STARTING, owner, "launch requested")
        publish(_state.value.copy(
            phase = phase,
            lastDurableAction = "${component.name.lowercase()}-start-requested",
            recoverability = Recoverability.RETRY,
        ))
        val observation = runCatching {
            withTimeout(timeouts.start(component, BotProfiles.find(spec.profileId) != null)) {
                backend.start(component, owner, spec)
            }
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
        val recoveryOrder = if (prior.runtimeMode == RuntimeMode.LAN_JOIN)
            listOf(RuntimeComponent.CLIENT) else STOP_ORDER
        for (component in recoveryOrder) {
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
        if (prior.runtimeMode != RuntimeMode.LAN_JOIN) {
            val database = runCatching {
                withTimeout(timeouts.recoveryMs) { backend.recoverDatabase() }
            }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            if (!database.ok) {
                fail(RuntimePhase.ERROR, "database recovery failed: ${database.detail}")
                return false
            }
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
        val world = if (_state.value.runtimeMode == RuntimeMode.LAN_JOIN) null
            else ownerOf(RuntimeComponent.WORLD)
        if (world != null && _state.value.components.getValue(RuntimeComponent.WORLD).state == ComponentLifecycle.READY) {
            val saved = runCatching {
                withTimeout(timeouts.componentStopMs) { backend.saveWorld(world) }
            }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            durable = durable && saved.ok
            publish(_state.value.copy(lastDurableAction = if (saved.ok) "world-save-acknowledged" else "world-save-failed"))
        }
        val serverStopOrder = if (_state.value.runtimeMode == RuntimeMode.LAN_JOIN)
            emptyList() else STOP_ORDER.drop(1)
        for (component in serverStopOrder) {
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

    private fun publishPreflightFailure(spec: RuntimeLaunchSpec, detail: String) {
        publish(RuntimeSnapshot(
            phase = RuntimePhase.UNCONFIGURED,
            requestedProfile = spec.profileId,
            runtimeMode = spec.mode,
            realmEndpoint = spec.endpoint,
            clean = true,
            components = RuntimeSnapshot.stoppedComponents(),
            lastDurableAction = "preflight-failed",
            lastError = bounded(detail),
            recoverability = Recoverability.USER_ACTION_REQUIRED,
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

    private fun launchSpecOf(snapshot: RuntimeSnapshot, includeClient: Boolean): RuntimeLaunchSpec {
        val profile = checkNotNull(snapshot.requestedProfile)
        return when (snapshot.runtimeMode) {
            RuntimeMode.LOCAL -> RuntimeLaunchSpec.local(profile, includeClient)
            RuntimeMode.LAN_JOIN -> RuntimeLaunchSpec.lanJoin(profile, snapshot.realmEndpoint.address)
            RuntimeMode.LAN_HOST -> RuntimeLaunchSpec.lanHost(profile, snapshot.realmEndpoint.address, includeClient)
        }
    }

    private fun phaseFor(component: RuntimeComponent): RuntimePhase = when (component) {
        RuntimeComponent.DATABASE -> RuntimePhase.DB_STARTING
        RuntimeComponent.REALM -> RuntimePhase.REALM_STARTING
        RuntimeComponent.WORLD -> RuntimePhase.WORLD_STARTING
        RuntimeComponent.CLIENT -> RuntimePhase.CLIENT_STARTING
    }

    companion object {
        private val STARTABLE = setOf(RuntimePhase.STOPPED, RuntimePhase.ERROR, RuntimePhase.UNCONFIGURED)
        private val INACTIVE_TERMINAL_PHASES = setOf(
            RuntimePhase.STOPPED,
            RuntimePhase.UNCONFIGURED,
            RuntimePhase.ERROR,
        )
        private val STOP_ORDER = listOf(
            RuntimeComponent.CLIENT,
            RuntimeComponent.WORLD,
            RuntimeComponent.REALM,
            RuntimeComponent.DATABASE,
        )
    }
}
