package com.pocketrealm.supervisor

import com.pocketrealm.bots.BotProfiles
import kotlinx.coroutines.delay
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
    private val orphanHeal = OrphanSelfHealPolicy(tokens)
    /** Consecutive ownerless-orphan sightings per component (monitor lane, plan F1). */
    private val orphanGraceTicks = mutableMapOf<RuntimeComponent, Int>()
    /**
     * Session ids minted by THIS supervisor instance. A DATABASE claim whose
     * recorded session id is in this set, and is not the live session, is
     * provably this device's own ended session: session ids are unguessable,
     * only starts through this instance create claims carrying them, and a
     * live claim's lease binder belongs to this process - so the recording
     * supervisor epoch is this one. A process-recreated supervisor starts
     * empty and can prove nothing; that case stays with the consented verb.
     */
    private val mintedSessions = mutableSetOf<String>()
    // Foreground-promotion driver state (plan B5); guarded by operationLock.
    private var foregroundPromoted = false
    private var foregroundEmptySamples = 0
    private var foregroundSessionId: String? = null
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

        val sessionId = mintSessionId()
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
        // A client failure during companion mode strands the native pause flag
        // with no journaled PAUSED phase left to exit from: clear it
        // best-effort before claiming RUNNING, so the resumed world actually
        // ticks. A no-op when the world was never paused.
        ownerOf(RuntimeComponent.WORLD)?.let { world ->
            runCatching { backend.setCompanionMode(world, false) }
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

    /**
     * Companion mode: a journaled RUNNING <-> PAUSED transition that
     * pauses world ticking and switches the LLM runtime profile. Pause is a
     * pause, never a stop - the world process stays alive so save/stop still
     * work from PAUSED and resume needs no reconfiguration.
     */
    suspend fun setCompanionMode(enabled: Boolean): RuntimeOperation = operationLock.withLock {
        // exit is also legal from CLIENT_FAILED: a client failure during
        // companion mode discards the PAUSED phase while the native pause
        // flag stays set, so the resume verb must remain reachable there
        val from = if (enabled) RuntimePhase.RUNNING else RuntimePhase.PAUSED
        val to = if (enabled) RuntimePhase.PAUSED else RuntimePhase.RUNNING
        if (enabled && _state.value.phase != from) {
            return@withLock operation(false, "companion mode requires phase $from (currently ${_state.value.phase})")
        }
        if (!enabled && _state.value.phase != RuntimePhase.PAUSED && _state.value.phase != RuntimePhase.CLIENT_FAILED) {
            return@withLock operation(false, "companion exit requires phase PAUSED (currently ${_state.value.phase})")
        }
        val world = ownerOf(RuntimeComponent.WORLD)
        if (world == null) {
            return@withLock operation(false, "companion mode requires a running world component")
        }
        // the phase actually journaled before this operation (differs from
        // `from` when exiting via CLIENT_FAILED) - the rollback target and,
        // on a successful exit, the terminal phase: leaving companion mode
        // only clears the pause aspect, a dead client stays CLIENT_FAILED
        val actualFrom = _state.value.phase
        val exitPhase = if (actualFrom == RuntimePhase.CLIENT_FAILED) RuntimePhase.CLIENT_FAILED else RuntimePhase.RUNNING
        publish(_state.value.copy(
            phase = if (enabled) to else exitPhase,
            clean = false,
            lastDurableAction = if (enabled) "companion-entered" else "companion-resumed",
        ))
        val result = runCatching { backend.setCompanionMode(world, enabled) }
            .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (!result.ok) {
            // journal the failed transition back to the phase we actually
            // found; the world pause flag was never flipped
            publish(_state.value.copy(phase = actualFrom, lastDurableAction = "companion-failed"))
            return@withLock operation(false, result.detail)
        }
        operation(result.ok, result.detail)
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
     * Player-consented repair lane for the pinned UNVERIFIED_ORPHAN refusal
     * (dirtyRecoveryNeverKillsAnUnverifiedOwner): recovery, the health
     * monitor, and stop all correctly refuse to kill a running component
     * whose ownership cannot be verified against the journal. This verb
     * exists ONLY behind the Home failure surface's explicit "Force stop
     * realm" confirmation and is never reached from any automatic path.
     *
     * Each running component is stopped under the owner the component
     * itself currently reports - the service-side requireOwner gate still
     * verifies every kill - adopting first when the component is ownerless
     * (the plan-F1 heal). :database is never killed while a generation is
     * live (killing it without engine.close() orphans mariadbd): the next
     * start's recovery lane owns its engine-ordered shutdown and the
     * DB-RECOVERY prepare heal, which is why the verb commits a dirty
     * STOPPED journal instead of a clean one. The one addition: a DATABASE
     * claim the journal cannot verify (an ended session's leftover lock)
     * is released here under the owner the database itself reports -
     * engine-ordered stop first, the process kill only while no live
     * generation exists.
     */
    suspend fun consentedForceStopOrphanStack(): RuntimeOperation = operationLock.withLock {
        val current = _state.value
        if (current.phase == RuntimePhase.STOPPED && current.clean) {
            return@withLock operation(true, "already stopped")
        }
        publish(current.copy(
            phase = RuntimePhase.STOPPING,
            clean = false,
            lastDurableAction = "consented-orphan-force-stop-requested",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        ))
        // :database stays with the engine-ordered recovery lane; everything
        // a force-stop can retire goes in report order (client first while
        // the world is still up, then world -> realm).
        for (component in STOP_ORDER) {
            if (component == RuntimeComponent.DATABASE) continue
            val observed = runCatching { backend.observe(component) }.getOrElse {
                fail(RuntimePhase.ERROR, "consented stop observe $component failed: ${it.message}")
                return@withLock operation(false, _state.value.lastError ?: "consented stop failed")
            }
            if (observed.state == ComponentLifecycle.STOPPED) continue
            val stopped = if (observed.owner == null) {
                // Ownerless running component: the sanctioned adopt-then-
                // forceStop heal under a freshly adopted owner.
                adoptAndForceStopOrphan(component, current.sessionId ?: mintSessionId())
            } else {
                // Owned by a session this journal cannot verify - exactly the
                // pinned refusal case. The player consented, so stop under the
                // owner the component itself reports; forceStopOwned's
                // requireOwner gate on the service side still checks it.
                runCatching {
                    withTimeout(timeouts.stop(component)) { backend.forceStop(component, observed.owner) }
                }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            }
            if (!stopped.ok) {
                fail(RuntimePhase.ERROR, "consented force stop failed for $component: ${stopped.detail}")
                return@withLock operation(false, _state.value.lastError ?: "consented stop failed")
            }
        }
        // The DATABASE-side sibling of this wedge family: with the engine
        // down, the service-side claim can still name a prior session that
        // ended without releasing it - the class the automatic start lane
        // releases only on minted-session proof. The player consented, so a
        // claim the journal cannot verify is released under the owner the
        // database itself reports: the engine-ordered graceful stop first,
        // and the process kill only while no live generation exists.
        val database = runCatching { backend.observe(RuntimeComponent.DATABASE) }.getOrElse {
            fail(RuntimePhase.ERROR, "consented stop observe DATABASE failed: ${it.message}")
            return@withLock operation(false, _state.value.lastError ?: "consented stop failed")
        }
        val staleDatabaseOwner = database.owner?.takeIf { it != ownerOf(RuntimeComponent.DATABASE) }
        if (staleDatabaseOwner != null) {
            val released = runCatching {
                withTimeout(timeouts.stop(RuntimeComponent.DATABASE)) {
                    backend.stop(RuntimeComponent.DATABASE, staleDatabaseOwner)
                }
            }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            val settled = if (released.ok) released
            else if (database.state in ENDED_DATABASE_STATES) {
                runCatching { backend.forceStop(RuntimeComponent.DATABASE, staleDatabaseOwner) }
                    .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            } else released
            if (!settled.ok) {
                fail(RuntimePhase.ERROR, "consented database owner release failed: ${settled.detail}")
                return@withLock operation(false, _state.value.lastError ?: "consented stop failed")
            }
        }
        demoteForegroundStack()
        // Dirty on purpose: the database generation is still unsealed, so
        // the next start must run recovery (and its DB prepare heal) before
        // the ordinary start stages - exactly the normal start path.
        publish(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = false,
            components = RuntimeSnapshot.stoppedComponents(),
            lastDurableAction = "consented-orphan-force-stop-committed",
            recoverability = Recoverability.RECOVERY_REQUIRED,
        ))
        operation(true, "orphan stack force-stopped; start again to recover the database")
    }

    /**
     * One health-monitor tick of the orphan self-heal lane (plan F1) for one
     * component the monitor observed as ownerless-but-running. Consecutive
     * sightings accumulate grace ticks (the component's own owner-loss
     * teardown may be mid-save); after the bounded grace the orphan is
     * adopted and force-stopped under the ADOPTED owner, then the stack is
     * drained. DATABASE orphans never adopt or kill - they route to the
     * existing recovery lane, which owns engine-ordered shutdown. Returns
     * null while the outcome is still pending (healthy, grace running, or
     * component transiently unobservable).
     */
    suspend fun selfHealOrphan(component: RuntimeComponent): RuntimeOperation? = operationLock.withLock {
        val current = _state.value
        if (current.clean || current.sessionId == null) {
            orphanGraceTicks.clear()
            return@withLock null
        }
        val observed = runCatching { backend.observe(component) }.getOrNull() ?: return@withLock null
        if (!OrphanSelfHealPolicy.isHealableOrphan(observed.state, observed.owner)) {
            orphanGraceTicks.remove(component)
            return@withLock null
        }
        val graceTicksSeen = (orphanGraceTicks[component] ?: 0) + 1
        orphanGraceTicks[component] = graceTicksSeen
        if (OrphanSelfHealPolicy.shouldReobserve(graceTicksSeen)) return@withLock null
        orphanGraceTicks.remove(component)
        if (component == RuntimeComponent.DATABASE) {
            // Never kill :database directly - killing it without
            // engine.close() orphans mariadbd. Route to the existing
            // recovery lane instead.
            val recovered = recoverLocked()
            return@withLock operation(
                recovered,
                if (recovered) "database orphan routed to recovery"
                else _state.value.lastError ?: "database orphan recovery failed",
            )
        }
        val healed = adoptAndForceStopOrphan(component, checkNotNull(current.sessionId))
        if (!healed.ok) {
            fail(RuntimePhase.ERROR, healed.detail)
            return@withLock operation(false, _state.value.lastError ?: "orphan heal failed")
        }
        updateComponent(component, ComponentLifecycle.STOPPED, detail = "orphan self-healed under adopted owner")
        stopLocked(StopMode.FORCED, RuntimePhase.ERROR, "orphan self-healed: $component")
    }

    /**
     * One health-monitor tick of the foreground-promotion policy (plan B5):
     * :world and :database are promoted to specialUse FGS the moment a real
     * player is present (immediate edge) and demoted only after three
     * consecutive playerless samples (asymmetric hysteresis).
     */
    suspend fun reconcileForegroundPromotion() = operationLock.withLock {
        val snapshot = _state.value
        if (snapshot.sessionId != foregroundSessionId) {
            foregroundSessionId = snapshot.sessionId
            foregroundPromoted = false
            foregroundEmptySamples = 0
        }
        // Never bind :world just to sample presence: a stopped world is
        // definitively playerless; an unobservable live world is empty too
        // (the safe direction for teardown).
        val sample = if (snapshot.components.getValue(RuntimeComponent.WORLD).state != ComponentLifecycle.STOPPED) {
            runCatching { backend.observeWorldPresence() }.getOrDefault(WorldPresenceSample.EMPTY)
        } else WorldPresenceSample.EMPTY
        if (ForegroundPromotionPolicy.shouldPromote(
                sample.state, sample.realPlayers, sample.playerbotsEnabled,
                sample.onlinePlayers, foregroundPromoted,
            )
        ) {
            foregroundPromoted = true
            foregroundEmptySamples = 0
            runCatching { backend.promoteToForeground(RuntimeComponent.WORLD) }
            runCatching { backend.promoteToForeground(RuntimeComponent.DATABASE) }
        } else if (ForegroundPromotionPolicy.playersPresent(
                sample.realPlayers, sample.playerbotsEnabled, sample.onlinePlayers,
            )
        ) {
            foregroundEmptySamples = 0
        } else {
            foregroundEmptySamples++
            if (ForegroundPromotionPolicy.shouldDemote(
                    sample.state, sample.realPlayers, sample.playerbotsEnabled,
                    sample.onlinePlayers, foregroundEmptySamples, foregroundPromoted,
                )
            ) {
                demoteForegroundStack()
            }
        }
    }

    /** Drops any supervisor-driven FGS promotion of :world/:database (plan B5). */
    private suspend fun demoteForegroundStack() {
        foregroundEmptySamples = 0
        if (!foregroundPromoted) return
        foregroundPromoted = false
        listOf(RuntimeComponent.WORLD, RuntimeComponent.DATABASE).forEach { component ->
            runCatching { backend.demoteToForeground(component) }
        }
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
        }.getOrElse { error ->
            // The DATABASE-side wedge of the orphan family: the service-side
            // claim still names a prior session that ended without releasing
            // it (its engine is already down, so every stop lane observed
            // "already stopped" and skipped the claim release), and each new
            // start's claim is rejected forever. Release it automatically
            // ONLY on minted-session proof; an unprovable owner keeps the
            // automatic refusal and, when the class is proven but the release
            // fails, the consented force-stop repair surface.
            if (component != RuntimeComponent.DATABASE || !isCrossSessionClaimRejection(error)) {
                failStage(component, "${error.javaClass.simpleName}: ${error.message}")
                return false
            }
            when (val reclaim = reclaimDatabaseClaimFromEndedOwnSession(owner)) {
                DatabaseReclaim.NOT_PROVEN -> {
                    failStage(component, "${error.javaClass.simpleName}: ${error.message}")
                    return false
                }
                is DatabaseReclaim.RELEASE_FAILED -> {
                    failStage(component, reclaim.detail)
                    return false
                }
                DatabaseReclaim.RELEASED -> runCatching {
                    withTimeout(timeouts.start(component, BotProfiles.find(spec.profileId) != null)) {
                        backend.start(component, owner, spec)
                    }
                }.getOrElse { retry ->
                    failStage(
                        component,
                        if (isCrossSessionClaimRejection(retry))
                            "DB_OWNED_BY_DEAD_SESSION: database claim was rejected again " +
                                "after the ended session's owner was released"
                        else "${retry.javaClass.simpleName}: ${retry.message}",
                    )
                    return false
                }
            }
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

    /** Outcome of the automatic DATABASE stale-claim release below. */
    private sealed interface DatabaseReclaim {
        /** The recorded owner is not provably this instance's own ended session. */
        data object NOT_PROVEN : DatabaseReclaim
        /** The stale claim was released; the caller retries the claim once. */
        data object RELEASED : DatabaseReclaim
        /** Proof held but the release failed; [detail] carries the failure-class marker. */
        data class RELEASE_FAILED(val detail: String) : DatabaseReclaim
    }

    /**
     * Releases a DATABASE ownership claim left behind by one of THIS
     * supervisor instance's own prior runtime sessions. The wedge is real:
     * the engine is already down, so the stop lanes all short-circuit on
     * "already stopped" without ever clearing the service-side claim, and
     * every later start's claim is rejected forever - plain retries cannot
     * heal it. Automatic release is legal only on proof: the claim's session
     * id was minted by this instance (same supervisor epoch) and has since
     * been replaced, and the engine observably has no live generation. A
     * foreign or unverifiable session id is NOT proven - the automatic lanes
     * refuse it exactly like the pinned UNVERIFIED_ORPHAN law - and only the
     * consented force-stop verb may release it.
     */
    private suspend fun reclaimDatabaseClaimFromEndedOwnSession(next: ComponentOwner): DatabaseReclaim {
        val observed = runCatching { backend.observe(RuntimeComponent.DATABASE) }.getOrNull()
            ?: return DatabaseReclaim.NOT_PROVEN
        val stale = observed.owner ?: return DatabaseReclaim.NOT_PROVEN
        if (stale == next || stale.sessionId == next.sessionId) return DatabaseReclaim.NOT_PROVEN
        if (stale.sessionId !in mintedSessions) return DatabaseReclaim.NOT_PROVEN
        if (observed.state !in ENDED_DATABASE_STATES) return DatabaseReclaim.NOT_PROVEN
        publish(_state.value.copy(
            lastDurableAction = "database-ended-own-session-owner-release-requested"))
        val stopped = runCatching {
            withTimeout(timeouts.stop(RuntimeComponent.DATABASE)) {
                backend.stop(RuntimeComponent.DATABASE, stale)
            }
        }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (stopped.ok) return DatabaseReclaim.RELEASED
        // The engine is proven down, so killing the recycling :database
        // process releases the claim and orphans nothing - the same verb the
        // recovery lane uses for a journaled database owner.
        val killed = runCatching { backend.forceStop(RuntimeComponent.DATABASE, stale) }
            .getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (killed.ok) return DatabaseReclaim.RELEASED
        return DatabaseReclaim.RELEASE_FAILED(
            "DB_OWNED_BY_DEAD_SESSION: ended own-session database owner could not " +
                "be released: ${killed.detail}",
        )
    }

    /** The service-side cross-session claim rejection contract (ComponentOwnership). */
    private fun isCrossSessionClaimRejection(error: Throwable): Boolean =
        error.message?.contains("owned by another runtime session") == true

    private fun mintSessionId(): String = tokens.sessionId().also { mintedSessions += it }

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
            var observation = runCatching { backend.observe(component) }.getOrElse {
                fail(RuntimePhase.ERROR, "recovery observe $component failed: ${it.message}")
                return false
            }
            if (observation.state == ComponentLifecycle.STOPPED) continue
            // Null-owner orphan (plan F1): binder death cleared the claim and
            // the component-side owner-loss teardown may still be mid-flight.
            // resolveRecoveryOrphan applies the bounded grace and the
            // adopt-then-forceStop heal; a non-null owner falls through to
            // the exact-owner/mismatch checks.
            if (OrphanSelfHealPolicy.isHealableOrphan(observation.state, observation.owner)) {
                when (val outcome = resolveRecoveryOrphan(component, session, observation)) {
                    is OrphanResolution.Recheck -> observation = outcome.observation
                    OrphanResolution.Handled -> continue
                    is OrphanResolution.Failed -> {
                        fail(RuntimePhase.ERROR, outcome.detail)
                        return false
                    }
                }
            }
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
        if (!recoverDatabaseAfterComponents(prior)) return false
        demoteForegroundStack()
        publish(RuntimeSnapshot(
            phase = RuntimePhase.STOPPED,
            clean = true,
            lastDurableAction = "dirty-recovery-complete",
            updatedAtWallMs = clock.wallMs(),
            updatedAtElapsedMs = clock.elapsedMs(),
        ))
        return true
    }

    private suspend fun recoverDatabaseAfterComponents(prior: RuntimeSnapshot): Boolean {
        if (prior.runtimeMode == RuntimeMode.LAN_JOIN) return true
        val database = runCatching {
            withTimeout(timeouts.recoveryMs) { backend.recoverDatabase() }
        }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
        if (!database.ok) fail(RuntimePhase.ERROR, "database recovery failed: ${database.detail}")
        return database.ok
    }

    private sealed interface OrphanResolution {
        /** The claim reappeared; recovery re-checks the exact-owner/mismatch branches. */
        data class Recheck(val observation: ComponentObservation) : OrphanResolution
        /** Fully handled (stopped, routed, or healed); recovery continues past the component. */
        data object Handled : OrphanResolution
        data class Failed(val detail: String) : OrphanResolution
    }

    /**
     * Recovery-lane orphan resolution (plan F1): bounded re-observe grace
     * first (the component-side owner-loss teardown may be mid-save), then
     * adopt-then-forceStop under the adopted owner. DATABASE never adopts or
     * kills - the existing database recovery lane owns it (killing
     * :database without engine.close() orphans mariadbd).
     */
    private suspend fun resolveRecoveryOrphan(
        component: RuntimeComponent,
        session: String,
        initial: ComponentObservation,
    ): OrphanResolution {
        var observation = initial
        var graceTicksSeen = 1
        while (OrphanSelfHealPolicy.shouldReobserve(graceTicksSeen)) {
            delay(ORPHAN_REOBSERVE_INTERVAL_MS)
            observation = runCatching { backend.observe(component) }.getOrElse {
                return OrphanResolution.Failed("recovery re-observe $component failed: ${it.message}")
            }
            if (observation.state == ComponentLifecycle.STOPPED ||
                !OrphanSelfHealPolicy.isHealableOrphan(observation.state, observation.owner)
            ) break
            graceTicksSeen++
        }
        return when {
            observation.state == ComponentLifecycle.STOPPED -> OrphanResolution.Handled
            observation.owner != null -> OrphanResolution.Recheck(observation)
            component == RuntimeComponent.DATABASE -> OrphanResolution.Handled
            else -> {
                val healed = adoptAndForceStopOrphan(component, session)
                if (healed.ok) OrphanResolution.Handled else OrphanResolution.Failed(healed.detail)
            }
        }
    }

    /** Claims an ownerless running component, then force-stops it under the adopted owner. */
    private suspend fun adoptAndForceStopOrphan(
        component: RuntimeComponent,
        session: String,
    ): RuntimeActionResult {
        val adopted = orphanHeal.adoptOwner(session)
        val claim = runCatching { backend.adopt(component, adopted) }
            .getOrElse { RuntimeActionResult(false, "${it.javaClass.simpleName}: ${it.message}") }
        return if (!claim.ok) {
            RuntimeActionResult(false, "orphan adoption failed for $component: ${claim.detail}")
        } else {
            val killed = runCatching {
                withTimeout(timeouts.stop(component)) { backend.forceStop(component, adopted) }
            }.getOrElse { RuntimeActionResult(false, it.message ?: it.javaClass.simpleName) }
            if (killed.ok) RuntimeActionResult(true, "orphan $component self-healed under adopted owner")
            else RuntimeActionResult(false, "orphan $component heal stop failed: ${killed.detail}")
        }
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
        demoteForegroundStack()
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
        // Re-observe cadence inside the recovery-lane orphan grace (plan F1);
        // mirrors the 1s health-monitor tick.
        private const val ORPHAN_REOBSERVE_INTERVAL_MS = 1_000L
        // A stale DATABASE ownership claim may be released (automatically or
        // consented-and-killed) only while the engine observably has no live
        // generation; a live one belongs to the engine-ordered stop lanes.
        private val ENDED_DATABASE_STATES = setOf(ComponentLifecycle.STOPPED, ComponentLifecycle.FAILED)
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
