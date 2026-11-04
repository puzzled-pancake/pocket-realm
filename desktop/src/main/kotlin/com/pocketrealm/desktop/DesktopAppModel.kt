package com.pocketrealm.desktop

import com.pocketrealm.bots.BotCustomPresets
import com.pocketrealm.storage.Settings
import com.pocketrealm.ui.LanJoinProbe
import com.pocketrealm.ui.TunnelInvite
import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.supervisor.AccountProvisionResult
import com.pocketrealm.supervisor.AutoLoginCredentialProof
import com.pocketrealm.supervisor.AutoLoginPolicy
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.DurableRuntimeSupervisor
import com.pocketrealm.supervisor.FriendAccessController
import com.pocketrealm.supervisor.FriendRosterJson
import com.pocketrealm.supervisor.GuestCredentials
import com.pocketrealm.supervisor.ModerationResult
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimeOperation
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import com.pocketrealm.supervisor.StopMode
import com.pocketrealm.supervisor.UserAccountStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * the interface-loss monitor — the desktop twin of the
 * Android RealmService monitor loop (RealmService.kt:440-498). A 1 s tick
 * that, while the runtime hosts on the LAN (`spec.mode == LAN_HOST`) and the
 * phase is one of the Android MONITORED_PHASES, checks
 * [DesktopLanInterfaces.isCurrentPrivateInterface] and accumulates loss per
 * SESSION id (the session-anchored grace: a later generation never inherits
 * elapsed loss time from an earlier one). Sustained loss past the grace
 * window runs the graceful stop — the Android log lines are kept verbatim.
 *
 * The desktop has no operation-coordinator wake-lock dance; the shared
 * supervisor's operation lock IS the "never stops underneath a user
 * operation" guarantee: [stop] queues behind whatever operation owns the
 * gate, then runs.
 */
@Suppress("LongParameterList") // the monitor seam: snapshot + predicates + action + clock/log are all test seams
internal class LanInterfaceLossMonitor(
    /** The snapshot source (the supervisor's state flow value). */
    private val snapshot: () -> com.pocketrealm.supervisor.RuntimeSnapshot?,
    /** The -filtered liveness predicate. */
    private val liveInterface: (com.pocketrealm.supervisor.RealmEndpoint) -> Boolean,
    /** Design-tunnel: the tunnel-adapter liveness leg — the watched
     * predicate when the session advertises a tunnel address. */
    private val liveTunnelInterface: (com.pocketrealm.supervisor.AdvertisedAddress) -> Boolean =
        { com.pocketrealm.supervisor.TunnelInterfacePolicy.isCurrentTunnelInterface(it) },
    /** The grace-elapsed action (the model's save-and-stop with the
     * stop reason). Runs on the monitor thread. */
    private val onGraceElapsed: () -> Unit,
    private val graceMs: Long = GRACE_MS,
    private val tickMs: Long = TICK_MS,
    private val clock: () -> Long = System::nanoTime,
    private val logWarning: (String) -> Unit = { DesktopLog.w(TAG, it) },
) {
    /** One loss window: anchored to the session id + endpoint it started on. */
    internal data class LossWindow(val sessionId: String?, val endpointAddress: String, val sinceNs: Long)

    @Volatile
    private var window: LossWindow? = null

    @Volatile
    private var thread: Thread? = null

    /** Set by [stop]; the loop checks it between ticks and right before
     * running the grace-elapsed stop, so close() never queues a NEW stop
     * behind teardown. */
    @Volatile
    private var stopRequested = false

    /** True while [onGraceElapsed] (the graceful interface-loss stop) is
     * executing on the worker thread — [stop] must let it settle rather
     * than interrupting the save mid-flight. */
    private val inGraceStop = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One tick, exposed for tests (the loop body calls exactly this).
     * True when the grace elapsed and [onGraceElapsed] must run (the loop
     * version runs it; tests assert the verdict instead).
     *
     * Mode-aware (design-tunnel) through the SHARED decision table —
     * [com.pocketrealm.supervisor.InterfaceWatch.forSession], the exact
     * function the Android monitor and both platforms' start-legs run, so
     * the two platforms can no longer disagree (design decision 6: one
     * implementation, not two). The watched input is the SESSION
     * SNAPSHOT's own advertisement — the value the launch spec froze at
     * start — never a live settings re-read, which a mid-session Settings
     * edit would otherwise re-target against a running realm. Plain
     * LAN_HOST watches the LAN endpoint (D2 verbatim); a tunnel-advertised
     * session watches the TUNNEL adapter — physical-LAN churn must never
     * kill a tunnel realm, whose path never touched the LAN; a loopback
     * advertisement (the cloudflared recipe, wildcard bind) has NO
     * interface to lose — its loss signal is the upstream tool's health,
     * not a scan. */
    internal fun tickOnce(
        snapshotValue: com.pocketrealm.supervisor.RuntimeSnapshot,
        nowNs: Long = clock(),
    ): Boolean {
        val monitored = snapshotValue.runtimeMode == com.pocketrealm.supervisor.RuntimeMode.LAN_HOST &&
            snapshotValue.phase in MONITORED_PHASES
        val watch = com.pocketrealm.supervisor.InterfaceWatch.forSession(
            snapshotValue.realmEndpoint.address,
            snapshotValue.advertisedAddress,
        )
        val tunnelLeg = watch is com.pocketrealm.supervisor.InterfaceWatch.Tunnel
        val watchedLeg: Pair<Boolean, String>? = when {
            !monitored || snapshotValue.sessionId == null -> null
            watch is com.pocketrealm.supervisor.InterfaceWatch.None -> true to "" // cloudflared: nothing to lose
            watch is com.pocketrealm.supervisor.InterfaceWatch.Tunnel ->
                liveTunnelInterface(watch.advertised) to watch.advertised.value
            else -> liveInterface(snapshotValue.realmEndpoint) to snapshotValue.realmEndpoint.address
        }
        if (watchedLeg == null || watchedLeg.first) {
            window = null
            return false
        }
        val watchedAddress = watchedLeg.second
        val current = window
        if (current == null || current.sessionId != snapshotValue.sessionId ||
            current.endpointAddress != watchedAddress
        ) {
            window = LossWindow(snapshotValue.sessionId, watchedAddress, nowNs)
            logWarning(
                lossStartLine(tunnelLeg, watchedAddress),
            )
            return false
        }
        val lostForMs = (nowNs - current.sinceNs) / 1_000_000
        if (lostForMs < graceMs) return false
        window = null
        logWarning(
            lossStopLine(tunnelLeg, lostForMs),
        )
        return true
    }

    /** The verbatim Android lines for LAN; the tunnel leg mirrors their
     * shape so a log reader sees the same story per mode. */
    private fun lossStartLine(tunnel: Boolean, address: String): String =
        if (tunnel) {
            "Tunnel host address $address absent; loss grace window of ${graceMs / 1000}s starts"
        } else {
            "LAN host interface $address absent; loss grace window of ${graceMs / 1000}s starts"
        }

    private fun lossStopLine(tunnel: Boolean, lostForMs: Long): String =
        if (tunnel) {
            "Tunnel host address lost for ${lostForMs / 1000}s; generation will save and stop"
        } else {
            "LAN host interface lost for ${lostForMs / 1000}s; generation will save and stop"
        }

    fun start() {
        synchronized(this) {
            if (thread?.isAlive == true) return
            stopRequested = false
            val worker = Thread({ runLoop() }, "lan-interface-monitor")
            worker.isDaemon = true
            thread = worker
            worker.start()
        }
    }

    fun stop() {
        synchronized(this) {
            stopRequested = true
            thread?.let { worker ->
                // An in-flight interface-loss stop is mid save/stop against
                // the live backend: it must SETTLE, never be killed under
                // teardown (interrupting it would cancel the graceful stop
                // mid-save), so the interrupt only fires for the idle tick
                // loop and the join waits out the supervisor's stop budgets.
                if (!inGraceStop.get()) worker.interrupt()
                runCatching { worker.join(SETTLE_JOIN_TIMEOUT_MS) }
            }
            thread = null
            window = null
        }
    }

    private fun runLoop() {
        while (!Thread.currentThread().isInterrupted && !stopRequested) {
            try {
                val snapshotValue = snapshot()
                if (snapshotValue != null && tickOnce(snapshotValue) && !stopRequested) {
                    inGraceStop.set(true)
                    try {
                        onGraceElapsed()
                    } finally {
                        inGraceStop.set(false)
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                // The monitor must never die from one bad tick.
                runCatching { logWarning("interface monitor tick failed: ${failure.message}") }
            }
            try {
                Thread.sleep(tickMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    companion object {
        /** The Android RealmService grace constant (10 s). */
        const val GRACE_MS = 10_000L
        const val TICK_MS = 1_000L
        /** [stop]'s join cap. An in-flight interface-loss stop is bounded by
         * the supervisor's own stop budgets (componentStopMs + databaseStopMs
         * + the control timeout), so the join waits those out instead of the
         * old 2 s cap that abandoned a mid-save stop to teardown. The idle
         * case returns in milliseconds (the interrupt wakes the tick sleep).
         */
        private const val SETTLE_JOIN_TIMEOUT_MS = 120_000L

        /** The Android monitor's MONITORED_PHASES, verbatim. */
        private val MONITORED_PHASES = setOf(
            com.pocketrealm.supervisor.RuntimePhase.WORLD_READY,
            com.pocketrealm.supervisor.RuntimePhase.RUNNING,
            com.pocketrealm.supervisor.RuntimePhase.PAUSED,
            com.pocketrealm.supervisor.RuntimePhase.CLIENT_FAILED,
        )
        private const val TAG = "LanMonitor"
    }
}

/**
 * GAP-1 (was DESKPROD-1): the explicit restore gate the Diagnostics card
 * consumes — a restore is a destructive datadir swap (success REPLACES the
 * current realm databases and deletes the quarantined pre-restore state),
 * so the UI enables its restore buttons only on [Ready] and routes every
 * tap through a consent dialog, while [DesktopAppModel.restoreBackup]
 * re-checks the same gate so the model refuses with the same reason the UI
 * disabled with. The backend's runtimes-at-rest refusal stays the
 * enforcement; this type is the consent surface.
 */
sealed interface RestoreGate {
    /** The supervisor holds no world — a restore may begin once confirmed. */
    data object Ready : RestoreGate

    /** The supervisor phase holds a world (or a mid-transition) — a restore
     * can only be refused; [reason] is the honest why, surfaced both by the
     * disabled buttons and by the model's own refusal. */
    data class Blocked(val reason: String) : RestoreGate
}

/**
 * The desktop app's state holder: the shared DurableRuntimeSupervisor over
 * the DesktopRuntimeBackend (one JVM, no IPC — the Android app's
 * RealmService/RuntimeSupervisorClient layer collapses into direct calls),
 * the settings store, the stored account, and the bot preset store.
 *
 * Every screen reads from this model; mutations go through its verbs so
 * the supervisor's operation serialization stays intact.
 */
@Suppress("TooManyFunctions") // the app-model seam: one verb per supervisor/store operation
/** the snapshot-flavor writer's pure half — a pinned boot lane
 * REPLACES the snapshot's flavor (vanilla default); no world running keeps
 * the supervisor's own value. */
internal fun RuntimeSnapshot.withBootLane(lane: ClientFlavor?): RuntimeSnapshot =
    lane?.let { copy(flavor = it.flavorId) } ?: this

class DesktopAppModel(
    val roots: DesktopStorageRoots = DesktopStorageRoots(),
    private val clientDirOverride: File? = null,
    /** the spent-world app relauncher (injectable for the JVM
     * tests; the production default resolves the running jpackage image
     * through the updater's code-source probe). */
    private val relauncher: DesktopAppRelauncher = DesktopAppRelauncher(),
) : AutoCloseable {
    /**
     * DESK-113 single-owner runtime domain: the lock is acquired FIRST, so
     * a second app instance refuses before a second supervisor/journal can
     * share %LOCALAPPDATA% with a live one (last-writer-wins journal
     * replacement, recovery guards reading the wrong process's state).
     * Throws [AppInstanceLockHeld] naming the holder pid; released in
     * [close] (and by the OS at process death either way).
     */
    private val instanceLock: AppInstanceLock

    /** Retained handle for the repository warm-up (see the init block);
     * cancelled in [close] so the warm-up cannot outlive the model. */
    private var addonRepositoryWarmUp: kotlinx.coroutines.Job? = null

    init {
        roots.ensureDirectories()
        instanceLock = AppInstanceLock.acquire(roots.realm)
        // construct the addon repository once at app start so the
        // fresh-install seed (built-in Android Port) runs even if the Add-ons
        // destination is never opened; the launch-boundary projector reads
        // its registry.json at client launch. Off the UI thread — init does
        // registry I/O (the Android MainActivity.onCreate posture). The job
        // is retained so the fire-and-forget scope cannot be collected
        // before the repository warm-up runs, and its FAILURE is logged —
        // the seed purpose dying with zero trace is the defect this closes.
        addonRepositoryWarmUp = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO,
        ).launch { warmUpAddonRepository() }
    }

    /** The warm-up body: the constructed repository, or the honest WARN —
     * never a silent swallow. The constructor thunk is the seam the JVM
     * test pins (the repository is a process singleton — not constructible
     * twice in one test JVM). */
    internal fun warmUpAddonRepository(
        // The construct resolves the
        // process-singleton companion directly, NOT the `addons` lazy
        // delegate — the delegate's backing field is assigned in
        // declaration order ~230 lines after the init block, so this IO
        // coroutine could read it mid-construction and die with
        // "Lazy.getValue ... <local1> is null" (the seed silently lost;
        // measured in every W4/W7 e2e probe log). get(roots) is the
        // idempotent singleton the lazy would create anyway.
        construct: () -> com.pocketrealm.addons.DesktopAddonRepository = {
            com.pocketrealm.addons.DesktopAddonRepository.get(roots)
        },
    ) {
        runCatching(construct).onFailure {
            DesktopLog.w(TAG, "addon repository warm-up failed: ${it.message}")
        }
    }


    private val settingsStore = DesktopSettingsStore(roots.settingsFile)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<Settings.Snapshot> = _settings.asStateFlow()

    /** Serializes read-transform-write settings updates: the file-picker
     * worker thread writes from outside the UI thread, and two concurrent
     * updates must not interleave their read-modify-write (the store's
     * temp file is unique per call; the lost-update race is what this
     * lock closes). */
    private val settingsLock = Any()

    val accountStore = UserAccountStore(File(roots.root, "user-account"))
    private val _storedAccount = MutableStateFlow(accountStore.loadOrQuarantine())
    val storedAccount: StateFlow<UserAccountStore.UserAccount?> = _storedAccount.asStateFlow()

    /**
     * D3/D2 (design-lan): the one desktop LAN interface seam —
     * the Settings quick-picks, the backend's D1 liveness re-checks, and
     * this model's D2 monitor all filter the SAME candidate list.
     */
    val lanInterfaces = DesktopLanInterfaces()

    val backend = DesktopRuntimeBackend(roots, clientDirOverride, lanInterfaces = lanInterfaces)
    val supervisor = DurableRuntimeSupervisor(
        backend,
        DesktopSupervisorJournal(roots.supervisorJournalDir),
    )
    /** (desktop-twin) the RUNNING world's flavor rides
     * the observed snapshot — the backend's pinned boot lane, folded into
     * every emission (the journal field G3-4 defined but NO code ever
     * produced: the realm-status worldUp arm, the PUBLIC realm.flavor
     * payload and auto-login all read a perpetual vanilla default). No
     * world running keeps the supervisor's own value. */
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<RuntimeSnapshot> = combine(
        supervisor.state,
        backend.bootLaneFlow,
    ) { snapshot, lane -> snapshot.withBootLane(lane) }
        .stateIn(modelScope, SharingStarted.Eagerly, supervisor.state.value)

    /**
     * D9/D2 (design-lan): the last interface-loss stop reason (raw
     * marker, decoded at render through the shared [RuntimeFailureCopy]);
     * set by the monitor's graceful stop, cleared on the next successful
     * start — so the Home card's STOPPED copy can say WHY hosting ended.
     */
    private val _lanStopReason = MutableStateFlow<String?>(null)
    val lanStopReason: StateFlow<String?> = _lanStopReason.asStateFlow()

    /** The monitor thread; started with the model, stopped in [close].
     * Mode-aware per design-tunnel through the SHARED
     * [com.pocketrealm.supervisor.InterfaceWatch] decision: plain LAN_HOST
     * watches the LAN endpoint (D2 verbatim); a tunnel-advertised session
     * watches the TUNNEL adapter instead (physical-LAN churn must not kill
     * a tunnel realm); a loopback advertisement (cloudflared) has no
     * interface to lose and skips the liveness leg. The watched input is
     * the supervisor snapshot's frozen advertisement — the exact value the
     * launch spec projected — so a mid-session Settings edit can never
     * re-target the watchdog of a live session (design decision 6: one
     * implementation with Android, not two). */
    private val lanInterfaceMonitor = LanInterfaceLossMonitor(
        snapshot = { supervisor.state.value },
        liveInterface = { endpoint -> lanInterfaces.isCurrentPrivateInterface(endpoint) },
        liveTunnelInterface = { advertised ->
            com.pocketrealm.supervisor.TunnelInterfacePolicy.isCurrentTunnelInterface(advertised)
        },
        onGraceElapsed = {
            // Mode-aware stop reason (design-tunnel): the watch that
            // fired derived from the session snapshot, so the user-facing
            // decoded copy must be too — a tunnel-advertised session's
            // loss signal is a dropped tunnel, and the LAN remedy ("pick
            // your current address") is the wrong instruction for them.
            val tunnelSession = supervisor.state.value.advertisedAddress != null
            _lanStopReason.value =
                if (tunnelSession) "TUNNEL_HOST_INTERFACE_LOST" else "LAN_HOST_INTERFACE_LOST"
            kotlinx.coroutines.runBlocking {
                runCatching { supervisor.stop(StopMode.GRACEFUL) }
                    .onSuccess { operation ->
                        if (operation.ok) DesktopLog.i(TAG, "interface loss: ${operation.detail}")
                        else DesktopLog.w(TAG, "interface-loss stop FAILED: ${operation.detail}")
                    }
            }
        },
    )

    init {
        // Field order note: the monitor's constructor lambdas close over
        // supervisor/lanInterfaces above, which are initialized by now.
        lanInterfaceMonitor.start()
    }

    // ------------------------------------------------------------------
    // Remote friend accounts (design-remote-accounts): the host's
    // invite/approve/provision hub, and the collector that keeps the
    // provisioning listener a property of a hosting session — bound
    // only while a LAN_HOST start with friend access enabled is up, stopped
    // with the realm, on EVERY stop path because the collector watches the
    // supervisor state rather than hooking individual verbs.
    // ------------------------------------------------------------------

    @Volatile private var friendAccessInstance: FriendAccessController? = null

    /** Serializes the first construction (double-checked): the state
     * watcher coroutine and a UI/ban path can both touch [friendAccess]
     * on their first emission — without the lock, both would construct a
     * controller and the loser's StateFlows/audit writes would land on an
     * instance nobody drives. */
    private val friendAccessLock = Any()
    val friendAccess: FriendAccessController
        get() = friendAccessInstance ?: synchronized(friendAccessLock) {
            friendAccessInstance ?: FriendAccessController(
                storeDirectory = File(roots.realm, FRIEND_ACCESS_STORE_DIR),
                backend = backend,
                supervisor = supervisor,
                signupLane = { request ->
                    // The D7 LAN sign-up lane rides the friend-access port;
                    // the decision is this model's answerLanSignup.
                    kotlinx.coroutines.runBlocking { answerLanSignup(request) }
                },
                logWarning = { DesktopLog.w(FRIEND_ACCESS_LOG_TAG, it) },
                logInfo = { DesktopLog.i(FRIEND_ACCESS_LOG_TAG, it) },
            ).also { friendAccessInstance = it }
        }

    private val friendAccessWatcher = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob(),
    ).launch {
        kotlinx.coroutines.flow.combine(supervisor.state, _settings) { snapshot, settings ->
            snapshot to settings
        }.collect { (snapshot, settings) ->
            runCatching {
                friendAccess.onRuntimeChanged(
                    phase = snapshot.phase,
                    mode = snapshot.runtimeMode,
                    friendAccessEnabled = settings.friendAccessEnabled,
                    endpointAddress = snapshot.realmEndpoint.address,
                )
            }
        }
    }

    /**
     * the in-game settings editor over the configured
     * client's WTF tree — shared codecs + shared catalogs, the edit lock kept
     * for contract parity (on the desktop's single JVM it degenerates to an
     * in-process reentrant mutex). The queue file rides the app's runtime
     * root; the WTF root resolves through the managed-generation-first
     * client selection, re-resolved at every operation (the editor holds a
     * supplier, not a snapshot) so a client configured after the lazy
     * initialized — or a re-import retiring the old generation — is
     * honored without an app restart. The launch-conditions seam
     * re-resolves the DESK-50
     * inputs at drain time (persisted audio mode, the next launch's endpoint
     * per the runtime mode, the launch-effective tweaks over the resolved
     * client's executable) so the drain blocks queued edits to keys the
     * launch overlay owns.
     */
    val inGameSettings: com.pocketrealm.ingame.DesktopInGameSettingsEditor by lazy {
        com.pocketrealm.ingame.DesktopInGameSettingsEditor(
            clientRoot = { resolveClientDir() },
            queueFile = File(roots.runtime, "ingame-settings/queue.json"),
            clientRunning = { backend.clientAlive() },
            launchConditions = {
                val snapshot = DesktopSettingsStore(roots.settingsFile).load()
                // The launch-effective tweaks without the publish side effect:
                // the pure resolution over the resolved client's executable,
                // exactly what publishForLaunch will resolve at the next
                // launch (a non-qualified executable falls back to the
                // requested set's effective form, never a stale one).
                val effectiveTweaks = resolveClientDir()?.let { dir ->
                    val pristine = File(dir, "WoW.exe")
                    if (pristine.isFile) {
                        com.pocketrealm.client.resolveEffectiveClientTweaks(
                            snapshot.tweaks,
                            com.pocketrealm.fs.FileDigests.sha256(pristine),
                        ).config
                    } else {
                        null
                    }
                } ?: snapshot.tweaks
                com.pocketrealm.ingame.DesktopConfigWtf.conditions(
                    audioMode = if (snapshot.audioMode == Settings.AudioMode.OFF) "off" else "on",
                    endpoint = launchSpec(includeClient = true).endpoint,
                    effectiveTweaks = effectiveTweaks,
                )
            },
        )
    }

    /** the versioned input profile store — the desktop twin's
     * file-backed lane, under the app settings root. */
    val inputProfileStore: com.pocketrealm.client.InputProfileStore by lazy {
        com.pocketrealm.client.InputProfileStore(File(roots.settings, "input-profile.json"))
    }

    /**
     * the addon manager — one app-scoped repository over
     * %LOCALAPPDATA%\PocketRealm\addons (registry + packages + the two-file
     * transaction journal), reconciling the input profile with the same
     * file-backed store the Controls editor uses. The launch-boundary
     * projector reads the registry this manages.
     */
    val addons: com.pocketrealm.addons.DesktopAddonRepository by lazy {
        com.pocketrealm.addons.DesktopAddonRepository.get(roots)
    }

    /** the Win32 gamepad bridge — injects the profile's mapped keys
     * into WoW.exe while it is foreground. */
    val gamepadBridge: Win32GamepadBridge by lazy {
        Win32GamepadBridge(
            profile = { inputProfileStore.loadStoredOrDefault() },
            clientPid = { backend.clientPid() },
        )
    }

    /** Start the gamepad bridge after a client launch (idempotent; honestly
     * inert when no XInput DLL is present). */
    fun startGamepadBridgeIfUseful() {
        runCatching { gamepadBridge.start() }
            .onFailure { DesktopLog.w(TAG, "gamepad bridge start failed: ${it.message}") }
    }

    /** The bot preset store installed by the backend (filesDir/bots twin). */
    val presets: com.pocketrealm.bots.BotPresetStore
        get() = requireNotNull(BotCustomPresets.store()) { "preset store not installed" }

    /**
     * Resolve the folder WoW.exe launches from, for the Home status line:
     * the constructor override, else the backend's managed-generation-first
     * + identity-gated resolution. Null when nothing usable
     * resolves — the exact state that shows the client picker.
     */
    fun resolveClientDir(): File? {
        clientDirOverride?.let { return it.takeIf { dir -> File(dir, "WoW.exe").isFile } }
        return backend.resolveClientDir()
    }

    /**
     * the pure state behind the client-selection affordance. The
     * "Change game folder…"/import control renders in every state; what it
     * says depends on this: MANAGED (an active generation is live),
     * FOLDER (a legacy folder passes the identity scan), or NONE (the
     * picker is the only path forward).
     */
    fun clientSelectionState(): ClientSelectionState {
        val dir = resolveClientDir()
        val generation = activeClientGeneration()
        return when {
            generation != null -> ClientSelectionState.MANAGED_GENERATION
            dir != null -> ClientSelectionState.FOLDER
            else -> ClientSelectionState.NONE
        }
    }

    enum class ClientSelectionState { MANAGED_GENERATION, FOLDER, NONE }

    /**
     * the active managed client generation (id, verified identity,
     * activated-at), or null when no per-flavor pointer is published. A
     * pointer whose generation fails the fail-closed reader reports
     * [ClientGenerationStatus.broken] with the reader's refusal.
     *
     * the lane resolves through the
     * [com.pocketrealm.client.ActiveFlavor] machine — the Home switcher's
     * selection drives the pointer namespace when several flavors qualify,
     * replacing the former `qualified().single()` landmine that crashed
     * the client-picker state at the second qualification.
     */
    fun activeClientGeneration(): ClientGenerationStatus? {
        val store = com.pocketrealm.importer.ClientGenerationStore(roots)
 // Flip: the render/status path resolves through the
        // machine's canonical displayedId — no persisted pick under the
        // flipped registry is the honest "no generation" state, never an
        // ambiguous-registry crash mid-composition.
        val flavor = com.pocketrealm.client.ActiveFlavor.displayedId?.let { laneId ->
            com.pocketrealm.client.ClientFlavors.ALL.first { it.flavorId == laneId }
        } ?: return null
        store.activeGeneration(flavor) ?: return null
        return try {
            val managed = com.pocketrealm.client.ManagedClientStore(roots).load(flavor.clientId)
            ClientGenerationStatus(
                generation = managed.generation,
                clientId = managed.id,
                version = managed.manifest.getJSONObject("identity").optString("version"),
                manifestSha256 = managed.manifestSha256,
                root = managed.root,
                broken = null,
            )
        } catch (failure: Throwable) {
            ClientGenerationStatus(
                generation = store.activeGeneration(flavor) ?: "unknown",
                clientId = flavor.clientId,
                version = null,
                manifestSha256 = null,
                root = null,
                broken = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    /** The active generation's immutable facts for the Import screen. */
    data class ClientGenerationStatus(
        val generation: String,
        val clientId: String,
        val version: String?,
        val manifestSha256: String?,
        val root: File?,
        val broken: String?,
    )

    /**
     * import the client folder at [sourceRoot] into a managed
     * generation (staging → hash-verify → fsync → immutable publication →
     * atomic per-flavor pointer switch). Runs on IO; [onPhase] receives
     * coarse progress wording for the Import screen. Throws
     * [com.pocketrealm.importer.ImportRejected] with the VAL copy when the
     * selection fails the identity gates.
     */
    suspend fun importClientFolder(
        sourceRoot: File,
        onPhase: (String) -> Unit = {},
    ): com.pocketrealm.importer.ManagedClientImporter.ImportResult = withContext(Dispatchers.IO) {
        val importer = com.pocketrealm.importer.ManagedClientImporter(roots)
        try {
            importer.run(
                sourceRoot,
                afterVerified = { count -> onPhase("copied $count files") },
                beforePublish = { onPhase("publishing generation") },
            )
        } finally {
            importer.close()
        }
    }

    /**
     * import the client from a picked ARCHIVE (.zip /.7z /.rar, or
     * an Inno Setup installer payload) into a managed generation. Same
     * semantics as [importClientFolder]; the archive lane sniffs the format,
     * detects the client (wrapper layouts included), and extracts installer
     * payloads into the staged store's scratch directory. DESK-35/64: the RAR
     * lane runs through the junrar twin; password-protected, multi-volume and
     * solid-RAR4 archives refuse with the pinned VAL-13 copy.
     */
    suspend fun importClientArchive(
        pickedArchive: File,
        onPhase: (String) -> Unit = {},
    ): com.pocketrealm.importer.ManagedClientImporter.ImportResult = withContext(Dispatchers.IO) {
        val importer = com.pocketrealm.importer.ManagedClientImporter(roots)
        try {
            importer.runArchive(
                pickedArchive,
                afterVerified = { count -> onPhase("copied $count files") },
                beforePublish = { onPhase("publishing generation") },
                onScratchEntry = { count -> onPhase("extracted $count installer files") },
            )
        } finally {
            importer.close()
        }
    }

    /**
     * the durable import view for the Import screen — the journal
     * twin's latest row and data stages rendered through the SHARED
     * [ImportProgressPresentation.fromJson] (the exact presentation contract
     * the Android import page renders, so both platforms word progress
     * identically). Idle when nothing is journaled. I/O: run off the UI
     * thread (the screen polls this from a worker coroutine).
     */
    fun importProgress(): com.pocketrealm.importer.ImportProgressPresentation {
        val journal = com.pocketrealm.importer.ImportJournal(roots)
        try {
            val latest = journal.latest()
            val importId = latest.importId
                ?: return com.pocketrealm.importer.ImportProgressPresentation.idle()
            val payload = org.json.JSONObject()
                .put("phase", latest.phase.name)
                .put("filesProcessed", latest.filesProcessed)
                .put("filesTotal", latest.filesTotal)
                .put("bytesCopied", latest.bytesCopied)
                .put("bytesTotal", latest.bytesTotal)
                .put("updatedAtMs", latest.updatedAtMs)
            latest.sourceUri?.let { payload.put("sourceUri", it) }
            latest.lastRelativePath?.let { payload.put("lastRelativePath", it) }
            latest.lastError?.let { payload.put("lastError", it) }
            journal.copyingFile(importId)?.let { copying ->
                val copied = journal.file(importId, copying.relativePath)?.bytesCopied ?: 0L
                payload.put(
                    "activeFile",
                    org.json.JSONObject()
                        .put("relativePath", copying.relativePath)
                        .put("copiedBytes", copied)
                        .put("expectedBytes", copying.expectedBytes),
                )
            }
            payload.put(
                "dataStages",
                org.json.JSONArray().apply {
                    journal.dataStages(importId).forEach { stage ->
                        put(
                            org.json.JSONObject()
                                .put("stage", stage.stage.name)
                                .put("state", stage.state.name)
                                .put("processed", stage.processed)
                                .put("total", stage.total)
                                .put("bytesWritten", stage.bytesWritten)
                                .put("checkpoint", stage.checkpoint)
                                .put("startedAtMs", stage.startedAtMs)
                                .put("completedAtMs", stage.completedAtMs)
                                .put("updatedAtMs", stage.updatedAtMs),
                        )
                    }
                },
            )
            return com.pocketrealm.importer.ImportProgressPresentation.fromJson(payload)
        } finally {
            journal.close()
        }
    }

    // ------------------------------------------------------------------
    // in-app world-data preparation. The app owns the lane
    // end-to-end; the composition lives in the DataPreparationHost (the
    // DESK-102-named host) so the Home, Settings and Diagnostics cards all
    // render the same lane, journal, and copy.
    // ------------------------------------------------------------------

    private val dataPreparationHost = DataPreparationHost(roots) { activeClientGeneration() }

    /** The prepared-data lane's fail-soft status for the preparation cards. */
    internal fun worldDataStatus(): DataPreparationHost.WorldDataStatus = dataPreparationHost.status()

    /** Prepare (or resume) the server world data from the active managed
     * client generation — see [DataPreparationHost.prepare]. */
    internal suspend fun prepareWorldData(
        onPhase: (String) -> Unit = {},
    ): com.pocketrealm.importer.DataPreparationStore.PublishedData =
        dataPreparationHost.prepare(onPhase)


    fun updateSettings(transform: (Settings.Snapshot) -> Settings.Snapshot) {
        synchronized(settingsLock) {
            val next = transform(_settings.value)
            settingsStore.save(next)
            _settings.value = next
        }
    }

    // ------------------------------------------------------------------
    // first-run tutorial state. The overlay is
    // gated on the previously dead `setupComplete` settings field (shown
    // while it is unset, hidden once the flow completes) and is
    // replayable from the Settings Setup card through this process-wide
    // counter (a StateFlow, not an event, so a bump is never dropped).
    // ------------------------------------------------------------------

    private val _tutorialReplayRequests = MutableStateFlow(0)
    val tutorialReplayRequests: StateFlow<Int> = _tutorialReplayRequests.asStateFlow()

    /** Settings → Setup → "Show first-run setup guide". */
    fun replayTutorial() {
        _tutorialReplayRequests.value += 1
    }

    /** Consumed exactly once per shown guide, on finish/skip. */
    fun consumeTutorialReplay() {
        _tutorialReplayRequests.value = 0
    }

    /** Seals setup (the overlay's finish/skip): persists `setupComplete`,
     * so the guide never reappears on its own after this. */
    fun completeTutorial() {
        updateSettings { it.copy(setupComplete = true) }
    }

    /** whether the seeded realm databases are in place — the
     * prepare-world-data step's live status (the first start refuses with
     * the honest seed instruction when not). */
    fun datadirSeeded(): Boolean = backend.isDatadirSeeded()

    // The join auto-stop and the launch gate each return an early honest
    // verdict; the supervisor start is the fall-through.
    //
    // GAP-2 (was DESKPROD-2): [profileOverride] lets a caller freeze the
    // boot's bot profile instead of inheriting the settings resolution —
    // the restore proof passes [DESKTOP_PROFILE] (the bots-disabled
    // profile), so the committed state is the snapshot the user picked -
    // never a snapshot plus a freshly provisioned bot population.
    // Ordinary starts pass null and keep the settings-resolved profile.
    @Suppress("ReturnCount")
    suspend fun startRealm(
        includeClient: Boolean,
        profileOverride: String? = null,
    ): RuntimeOperation {
        // clash guard (the symmetric half of startIrohJoin's) an
        // internet join owns 3724/8085 on this instance — starting a realm
        // stack now would bind-clash mid-boot. Refuse loudly; the user
        // stops the join first (never a silent stop of either lane).
        val joinClash = com.pocketrealm.desktop.iroh.IrohJoinGuards.realmStartRefusal(
            irohGateway.joinState.value,
        )
        if (joinClash != null) {
            return RuntimeOperation(false, supervisor.state.value, joinClash)
        }
        // join mode = stop realm/world + launch the client against
        // the remote IP. A join while a local runtime is still up stops that
        // runtime first (graceful, with the world save) — the shared
        // supervisor itself only refuses an active runtime, so the join
        // affordance owns the stop, desktop-side.
        if (_settings.value.runtimeMode == RuntimeMode.LAN_JOIN &&
            supervisor.state.value.phase !in STARTABLE_PHASES
        ) {
            val stopped = supervisor.stop(StopMode.GRACEFUL)
            if (!stopped.ok) {
                return stopped
            }
        }
        // the topology launch spec from the stored settings —
        // LOCAL loopback, LAN_HOST binding the chosen private IPv4 (gated on
        // the stored allowLanPlayers authorization: hosting with the toggle
        // off is refused here, before the supervisor is engaged), or
        // LAN_JOIN (client-only against the remote host). The bind threads
        // through the supervisor into realmdConfig/worldConfig (BindIP) and
        // the realmlist projection; the shared RuntimeTopology factories and
        // constructor validate the endpoint (canonical numeric
        // private/link-local IPv4, fixed ports, no hostnames) and the
        // authorization — a malformed or unauthorized spec is refused here.
        val spec = try {
            launchSpec(includeClient, profileOverride)
        } catch (failure: IllegalArgumentException) {
            return RuntimeOperation(
                false,
                supervisor.state.value,
                "launch refused: ${failure.message}",
            )
        }
        // The launch spec carries the RESOLVED bot profile id (not the
        // constant "local") when a profile is selected: the supervisor's
        // bot-aware world budget keys off spec.profileId, and a bots-
        // enabled boot provisions the population's accounts before READY.
        return supervisor.start(spec).also { operation ->
            // The supervisor's failure details live only in the journal
            // (which a later recovery overwrites) - the app log is the
            // durable record a diagnosis can actually find.
            if (operation.ok) {
                DesktopLog.i(TAG, "start ok: " + operation.detail)
                // a fresh start supersedes any previous session's
                // interface-loss stop reason.
                _lanStopReason.value = null
            } else {
                DesktopLog.w(
                    TAG,
                    "start FAILED: " + operation.detail +
                        " (lastError=" + operation.snapshot.lastError + ")",
                )
            }
        }.also { operation ->
            // with the client up, the gamepad bridge begins feeding
            // the profile's mapped keys whenever the game is foreground.
            if (operation.ok && includeClient) startGamepadBridgeIfUseful()
        }
    }

    /** The launch spec for one start: mode + endpoint + the stored
     * allowLanPlayers authorization from the settings, the resolved bot
     * profile id, the client wish (LAN_JOIN is client-only by contract —
     * the factory forces its client), and — design-tunnel — the stored
     * tunnel advertised address when set (LAN_HOST only; the shared v2
     * grammar validates it, so a malformed value refuses at this gate).
     * LAN_HOST is built directly rather than through the lanHost() factory
     * because that factory hard-codes allowLanPlayers = true — the user's
     * toggle IS the authorization the shared constructor requires. */
    private fun launchSpec(includeClient: Boolean, profileOverride: String? = null): RuntimeLaunchSpec {
        val snapshot = _settings.value
        // GAP-2 (was DESKPROD-2): an explicit override (the restore proof's
        // bots-disabled DESKTOP_PROFILE) wins over the settings resolution,
        // so the proof boot's spec never carries the user's bot selection.
        val profileId = profileOverride ?: backend.activeBotProfileId() ?: DESKTOP_PROFILE
        return when (snapshot.runtimeMode) {
            RuntimeMode.LOCAL -> RuntimeLaunchSpec.local(profileId, includeClient)
            RuntimeMode.LAN_HOST -> RuntimeLaunchSpec(
                mode = RuntimeMode.LAN_HOST,
                profileId = profileId,
                endpoint = RealmEndpoint.parseLan(snapshot.lanAddress),
                includeClient = includeClient,
                allowLanPlayers = snapshot.allowLanPlayers,
                advertisedAddress = snapshot.tunnelAdvertisedAddress
                    .takeIf { it.isNotBlank() }
                    ?.let { com.pocketrealm.supervisor.AdvertisedAddress.parse(it) },
            )
            RuntimeMode.LAN_JOIN -> RuntimeLaunchSpec.lanJoin(profileId, snapshot.lanAddress)
        }
    }

    /**
     * The ONLINE host launch spec (the Host-online button): the game binds
     * loopback and the internet gateway forwards to it — hosting needs no
     * LAN. With LAN players on and a private interface up, the same session
     * also serves direct LAN joins (exactly today's LAN host).
     */
    internal fun onlineHostLaunchSpec(
        includeClient: Boolean,
        lanPlayers: Boolean,
        availableLan: List<RealmEndpoint> = lanInterfaces.available(),
    ): RuntimeLaunchSpec {
        val profileId = backend.activeBotProfileId() ?: DESKTOP_PROFILE
        return RuntimeLaunchSpec.lanHost(
            profileId,
            com.pocketrealm.supervisor.HostOnlineEndpointPolicy.selectAddress(lanPlayers, availableLan),
            includeClient = includeClient,
        )
    }

    /** Host online (the shared online-first path): start the realm on the
     * [onlineHostLaunchSpec], refusing a mid-join start exactly like
     * [startRealm]. */
    suspend fun hostOnline(
        includeClient: Boolean = false,
        lanPlayers: Boolean = false,
    ): RuntimeOperation {
        val joinClash = com.pocketrealm.desktop.iroh.IrohJoinGuards.realmStartRefusal(
            irohGateway.joinState.value,
        )
        if (joinClash != null) {
            return RuntimeOperation(false, supervisor.state.value, joinClash)
        }
        val spec = try {
            onlineHostLaunchSpec(includeClient, lanPlayers)
        } catch (failure: IllegalArgumentException) {
            return RuntimeOperation(
                false,
                supervisor.state.value,
                "launch refused: ${failure.message}",
            )
        }
        return supervisor.start(spec).also { operation ->
            if (operation.ok) {
                DesktopLog.i(TAG, "host online ok: " + operation.detail)
                _lanStopReason.value = null
            } else {
                DesktopLog.w(TAG, "host online FAILED: " + operation.detail)
            }
        }
    }

    suspend fun relaunchClient(): RuntimeOperation =
        supervisor.relaunchClient().also { operation ->
            logOperation(operation)
            // the relaunched client gets the gamepad bridge too.
            if (operation.ok) startGamepadBridgeIfUseful()
        }

    suspend fun saveAndExit(): RuntimeOperation =
        supervisor.stop(StopMode.GRACEFUL).also { operation ->
            logOperation(operation)
            if (operation.ok) {
                // no client is owed injected keys after the stop.
                gamepadBridge.stop()
                // a clean stop is when the queued in-game
                // settings deliver (the queue-while-running lane).
                drainQueuedInGameSettings()
            }
        }

    /**
     * DESK-25 spent-world state: a world reached READY once in this app
     * process, so the pinned one-lifetime contract means its next lifetime
     * needs a fresh app process (the embedded lane refuses a second start
     * with the WRONG_STATE in-process-restart error). While the realm is
     * stopped with a spent lifetime, the Home card offers the app-relaunch
     * action instead of a Start that can only refuse.
     */
    fun worldLifetimeSpent(): Boolean = backend.worldLifetimeSpent

    /**
     * GAP-1 (was DESKPROD-1): the restore consent gate — the surface the
     * Diagnostics restore buttons enable on, and the one [restoreBackup]
     * re-checks before delegating so the model refuses with the SAME reason
     * the UI disabled with (defense in depth; the backend's
     * runtimes-at-rest refusal inside beginSnapshotRestore remains the final
     * arbiter — a busy-flag race must still surface RESTORE-REFUSED, never
     * a swap). Phase-only by design: the proof boot goes through the
     * supervisor's own start, which accepts exactly [STARTABLE_PHASES] and
     * recovers a dirty journal itself, so every other phase is one where a
     * restore tap could only ever surface the backend's refusal copy.
     */
    fun restoreGate(): RestoreGate {
        val phase = supervisor.state.value.phase
        return if (phase in STARTABLE_PHASES) {
            RestoreGate.Ready
        } else {
            RestoreGate.Blocked(
                "runtime is not at rest (phase=$phase); stop the realm before restoring a backup",
            )
        }
    }

    /**
     * DESK-25 short-term leg: the spent-world app relaunch. Spawns the
     * running jpackage image's launcher (the fresh process re-derives the
     * SAME storage roots from the inherited environment), then drains this
     * session through the ONE save-exit contract and ends the process —
     * the UpdateApply.relaunchAndExit precedent: spawn FIRST, so a refusal
     * (dev run, spawn failure) leaves this app alive with the honest
     * detail, and a success always exits.
     */
    internal suspend fun relaunchApp(): DesktopAppRelauncher.RelaunchResult = withContext(Dispatchers.IO) {
        val result = relauncher.relaunch()
        if (!result.ok) {
            DesktopLog.w(TAG, "app relaunch refused: ${result.detail}")
            return@withContext result
        }
        DesktopLog.i(TAG, result.detail)
        // The save-exit drain behind the window close and the shutdown hook
        // (graceful stop -> supervisor close), then this process ends for
        // the fresh one.
        runCatching { saveAndExit() }
        runCatching { close() }
        DesktopLog.i(TAG, "relaunch drain settled; exiting")
        kotlin.system.exitProcess(0)
    }

    /** Deliver the edits staged while the client ran. Honest on failure:
     * the queue file persists for the next clean stop. */
    private fun drainQueuedInGameSettings() {
        val summary = runCatching { inGameSettings.applyQueuedOnCleanStop() }
            .onFailure { DesktopLog.w(TAG, "in-game settings drain failed: ${it.message}") }
            .getOrNull() ?: return
        DesktopLog.i(TAG, "in-game settings drain: $summary")
    }

    suspend fun recover(): RuntimeOperation =
        supervisor.recover().also(::logOperation)

    suspend fun consentedForceStopOrphanStack(): RuntimeOperation =
        supervisor.consentedForceStopOrphanStack().also(::logOperation)

    // ------------------------------------------------------------------
    // the live world-control surface — the app
    // model's verbs over the supervisor (companion mode) and the backend's
    // live-control members (the Android world service's op set). The world
    // owner resolves from the supervisor snapshot exactly like the
    // auto-login credential proof does.
    // ------------------------------------------------------------------

    /** the live bot admission verdict (null while no
     * bot-profile world runs in this process); the Bots screen renders it. */
    val botAdmission: kotlinx.coroutines.flow.StateFlow<DesktopBotAdmissionVerdict?>
        get() = backend.botAdmissionVerdicts()

    /** The native world pause flag (the phase-card label's resume state). */
    fun worldPaused(): Boolean = backend.worldPaused()

    private fun worldOwner(): ComponentOwner? = supervisor.state.value.let { snapshot ->
        snapshot.sessionId?.let { sessionId ->
            snapshot.components[RuntimeComponent.WORLD]?.instanceToken?.let { token ->
                ComponentOwner(sessionId, token)
            }
        }
    }

    private fun noWorldOwner(): RuntimeActionResult =
        RuntimeActionResult(false, "WORLD_NOT_READY: no running world session owns the world")

    private fun logResult(operation: String, result: RuntimeActionResult) {
        if (result.ok) {
            DesktopLog.i(TAG, "$operation ok: ${result.detail}")
        } else {
            DesktopLog.w(TAG, "$operation FAILED: ${result.detail}")
        }
    }

    /**
     * companion mode enter/exit — the shared supervisor's
     * journaled RUNNING→PAUSED→RUNNING phase operation (the Android
     * RealmService "companion-mode" maintenance op); the Home phase card
     * is its desktop surface and the phase machine + journal copy come
     * free from the shared supervisor.
     */
    suspend fun setCompanionMode(enabled: Boolean): RuntimeOperation =
        supervisor.setCompanionMode(enabled).also(::logOperation)

    /** the world pause primitive (the Android setWorldPaused(1)
     * op) — the lane for world-up phases where the companion transition
     * does not apply (WORLD_READY / CLIENT_FAILED). */
    suspend fun pauseWorld(): RuntimeActionResult {
        val owner = worldOwner() ?: return noWorldOwner()
        return backend.pauseWorld(owner).also { logResult("pause-world", it) }
    }

    /** the world resume primitive (setWorldPaused(0)). */
    suspend fun resumeWorld(): RuntimeActionResult {
        val owner = worldOwner() ?: return noWorldOwner()
        return backend.resumeWorld(owner).also { logResult("resume-world", it) }
    }

    /** a direct bot-target retarget on the running world —
     * refused while a measured admission profile owns the target (the
     * Android setBotTarget check); use [applyBotProfile] for those. */
    suspend fun setBotTarget(target: Int): RuntimeActionResult {
        val owner = worldOwner() ?: return noWorldOwner()
        return backend.setBotTarget(owner, target).also { logResult("set-bot-target", it) }
    }

    /**
     * apply the settings' resolved bot selection to the
     * RUNNING world through the admission-generation handshake — the
     * Android startBotProfile op; the Bots screen's "Apply now" action.
     */
    suspend fun applyBotProfile(): RuntimeActionResult {
        val owner = worldOwner() ?: return noWorldOwner()
        val profileId = backend.activeBotProfileId()
            ?: return RuntimeActionResult(
                false,
                "NO_BOT_SELECTION: choose a bot profile before applying it to the running world",
            )
        return backend.startBotProfile(owner, profileId).also { logResult("apply-bot-profile", it) }
    }

    /** the characterPersistence op (the Android world service's
     * app verb): one character's persistence facts from the live world. */
    suspend fun characterPersistence(username: String, characterName: String): RuntimeActionResult {
        val owner = worldOwner() ?: return noWorldOwner()
        return backend.characterPersistence(owner, username, characterName)
            .also { logResult("character-persistence", it) }
    }

    /** the worldChat op — one chat line through the real chat
     * opcode handler; the native owns validation and answers its own ok. */
    suspend fun worldChat(
        characterName: String,
        channel: String,
        target: String,
        text: String,
    ): RuntimeActionResult {
        val owner = worldOwner() ?: return noWorldOwner()
        return backend.worldChat(owner, characterName, channel, target, text)
            .also { logResult("world-chat", it) }
    }

    // ------------------------------------------------------------------
    // the host guest-account surface. LAN
    // provisioning is the bounded direct handoff: the host types the
    // username, the password is GENERATED (shared [GuestCredentials],
    // gmLevel = 0 always), and the credential pair is displayed ONLY in
    // the Guests card with the in-person handoff copy — never embedded in
    // invite text, never saved into the stored-account slot (the guest
    // secret must not become this host's auto-login identity).
    // ------------------------------------------------------------------

    /** One successful guest-account creation: the handoff pair + the
     * world's provision verdict (accountId et al). */
    data class GuestAccountCreated(
        val username: String,
        val password: String,
        val provision: AccountProvisionResult,
    )

    /**
     * Create the guest account the host will hand to a friend in the room:
     * generated 16-symbol ~80-bit password, `gmLevel = 0` (GM outranks the
     * login queue and grants console-class powers — elevating a guest is a
     * deliberate act of the remote design's surface, never this one), and
     * NO write to the stored-account slot. Refuses honestly when no live
     * world session owns the world (the [worldOwner] guard shape).
     */
    suspend fun createGuestAccount(username: String): GuestAccountCreated {
        val owner = worldOwner()
            ?: return GuestAccountCreated(username, "", AccountProvisionResult(ok = false, code = "WORLD_NOT_READY"))
        val password = GuestCredentials.generatePassword()
        val provision = supervisor.provisionAccount(username, password, gmLevel = 0)
        logResult("create-guest-account", RuntimeActionResult(provision.ok, provision.code))
        return GuestAccountCreated(username, password, provision)
    }

    /**
     * D5a/D7 (design-lan): the players-online facts —
     * `activeSessions` from the world status telemetry plus `realPlayers`
     * from [DesktopRuntimeBackend.observeWorldPresence], rendered by the
     * Guests card as "N players in world (you + M guests)". Null while no
     * live world reports (the card renders nothing rather than guessing).
     */
    data class LanPlayersOnline(
        val activeSessions: Long,
        val realPlayers: Int,
        /** Whether the bot lane is armed: with bots on, [activeSessions]
         * counts bot sessions too — never render it as "players"
         * (round-6 R6-1: bots labeled, never padded). */
        val playerbotsEnabled: Boolean = false,
    ) {
        /** The guests beyond the host's own session (never negative). */
        val guests: Int get() = (realPlayers - 1).coerceAtLeast(0)
    }

    suspend fun lanPlayersOnline(): LanPlayersOnline? = withContext(Dispatchers.IO) {
        val presence = runCatching { backend.observeWorldPresence() }.getOrNull()
        if (presence == null || presence.state == com.pocketrealm.supervisor.ComponentLifecycle.STOPPED) {
            return@withContext null
        }
        val activeSessions = runCatching { backend.worldTelemetry() }
            .getOrNull()
            ?.optLong("activeSessions")
            ?: 0L
        LanPlayersOnline(
            activeSessions = activeSessions,
            realPlayers = presence.realPlayers,
            playerbotsEnabled = presence.playerbotsEnabled,
        )
    }

    // ------------------------------------------------------------------
    // guest
    // revocation = ban/unban as DIRECT account_banned rows on the realmd
    // database (realmd enforces the row at every logon challenge,
    // AuthSocket.cpp:466-477) — no native change. Kick (disconnecting a
    // live guest) is deferred (the owner-recorded F8 decision); the UI
    // shows it as unavailable with honest copy.
    //
    // F7: the lane serves BOTH database providers. The direct rows are
    // the SQLITE-provider lane (the realm's realmd database IS the SQLite
    // file). On MARIADB — the shipped default — the server confs point
    // the realm at the MariaDB named pipe and a direct SQLite row would
    // silently no-op (a ban the realm never enforces), so the verbs and
    // the revocation list ride the PROVIDER-AGNOSTIC engine relay the
    // Players moderation surface already uses ([DesktopRuntimeBackend]'s
    // banFriendAccount/unbanFriendAccount/accountRoster over
    // WorldNative's exported banAccountNative/unbanAccountNative,
    // world_runtime.cpp:1680/1689). The retired guestBanLaneRefusal used
    // to refuse this lane outright and point at a provider switch; no
    // provider switch is ever demanded for a guest ban now.
    // ------------------------------------------------------------------

    /** The guests ban store over this root's realmd database (lazy: the
     * datadir path resolves once; the store itself re-proves the file). */
    private val guestBanStore: DesktopGuestBans by lazy {
        DesktopGuestBans(
            realmdDatabase = com.pocketrealm.database.DatabaseSqliteControlPlane.databaseFile(
                roots.sqliteDatadir,
                com.pocketrealm.server.ServerRuntimeFiles.serverDatabases().realmd,
            ),
            // design-remote-accounts: the Guests-card ban path lands the
            // same no-secret audit rows as the Players moderation verbs.
            audit = { action, username, accountId, outcome ->
                friendAccess.store.auditModeration(action, username, accountId, outcome)
            },
        )
    }

    /**
     * F7: the provider probe for the guest ban lane — the durable
     * active-provider marker (the same marker the timeframe overlay's
     * lane guard reads) names which realmd database the realm actually
     * enforces. On MARIADB the ban/unban/suspend verbs and the
     * revocation list ride the engine relay (see the relay legs below);
     * anything else (no marker yet, or an explicit SQLITE selection)
     * keeps the direct [DesktopGuestBans] rows — on that lane the SQLite
     * file IS the live realmd database.
     */
    private fun guestBansRideTheEngineRelay(): Boolean {
        val markerFile = File(
            roots.sqliteDatadir.parentFile,
            com.pocketrealm.database.DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME,
        )
        val marker = com.pocketrealm.database.DatabaseDurableState.parseActiveProviderMarker(
            markerFile.takeIf(File::isFile)?.readText(),
        )
        return marker?.mode == com.pocketrealm.database.DatabaseDurableState.ProviderMode.MARIADB
    }

    /** Every active guest ban (the Guests card's revocation list).
     * SQLITE lane: the store's rows. The read holds the backend's
     * transition lock like the writes below: the list's open realmd
     * SQLite handle must never overlap an at-rest datadir swap (a
     * snapshot restore's directory move would fail on the held handle).
     * MARIADB lane (F7): the engine's own enforced view — the roster
     * read's banned flag applies the exact predicate realmd enforces at
     * the logon challenge (world_runtime.cpp account_list: active = 1
     * AND (expires_at > now OR expires_at = banned_at)), so the list can
     * never name a row the realm ignores, and never lists the stale
     * SQLite-era rows this lane stopped enforcing. Empty while the relay
     * is not answering (the world down): no live view, no list. */
    internal fun guestBans(): List<DesktopGuestBans.BanRow> {
        if (guestBansRideTheEngineRelay()) return relayGuestBans()
        return runCatching { backend.withDatadirQuiesced { guestBanStore.listBans() } }
            .getOrDefault(emptyList())
    }

    /**
     * F7: the MARIADB lane's revocation list — the engine's own enforced
     * view, exactly as the comment above promises. The roster's ban flag
     * carries identity only (no expiry, no reason — the predicate is
     * boolean by design), so a relay row renders through the permanent
     * branch with the reason naming its source; a lapsed suspension drops
     * out of the roster on its own (the expiry predicate realmd applies).
     * Any refusal reason (the relay not answering) is an empty list: no
     * live view, no list.
     */
    @Suppress("ReturnCount") // the degrade legs ARE the shape: no relay answer, no list
    private fun relayGuestBans(): List<DesktopGuestBans.BanRow> {
        val roster = runCatching { runBlocking { backend.accountRoster() } }.getOrNull()
            ?: return emptyList()
        if (roster.reason != null) return emptyList()
        return roster.accounts
            .filter { it.banned }
            .map { account ->
                DesktopGuestBans.BanRow(
                    accountId = account.id,
                    username = account.username,
                    // banned_at == expires_at is the permanent FORM; the
                    // roster flag carries no expiry to fill, and a
                    // fabricated lift time would be a lie the host acts on.
                    bannedAt = 0L,
                    expiresAt = 0L,
                    reason = RELAY_BAN_ROW_REASON,
                )
            }
    }

    /** Permanently ban a guest account: refused at their next logon
     * attempt (AUTH_LOGON_FAILED_BANNED). A guest who is online at the
     * moment of the ban stays in world until they log out — kick is the
     * immediate lever and is deferred (see above). SQLITE lane: the write
     * holds the backend's transition lock (the at-rest swaps' exclusion)
     * so the ban's open SQLite handle can never overlap a snapshot
     * backup/restore directory move. MARIADB lane (F7): the engine relay
     * (see [relayModeration]) — the realm must be up, because the relay
     * rides the live console. */
    internal fun banGuest(username: String, reason: String): DesktopGuestBans.BanOutcome {
        if (guestBansRideTheEngineRelay()) {
            return relayBanLeg("ban", username, RELAY_PERMANENT_DURATION, reason)
        }
        return backend.withDatadirQuiesced { guestBanStore.ban(username, reason) }
    }

    /** Clear every active ban for a guest account (transition-locked on
     * the SQLITE lane, as [banGuest]; the engine relay on MARIADB). */
    internal fun unbanGuest(username: String): DesktopGuestBans.BanOutcome {
        if (guestBansRideTheEngineRelay()) {
            val verdict = relayModeration(RELAY_AUDIT_UNBANNED, username) {
                backend.unbanFriendAccount(username.trim())
            }
            return if (verdict.ok) {
                // The relay verdict carries no account id or prior state —
                // the enforced view is the roster the list reads next.
                DesktopGuestBans.BanOutcome.Unbanned(accountId = 0L, wasBanned = false)
            } else {
                DesktopGuestBans.BanOutcome.Refused(relayRefusal("unban", username, verdict.detail))
            }
        }
        return backend.withDatadirQuiesced { guestBanStore.unban(username) }
    }

    /** Design-tunnel: the timed-suspension preset (duration presets —
     * expires_at = now + [durationSeconds]), the middle lever between
     * "talk to them" and a permanent ban. Same next-logon enforcement on
     * both lanes: the direct store on SQLITE (transition-locked, as
     * [banGuest]), the engine relay with the duration rendered in the
     * console's TimeStringToSecs shape on MARIADB. */
    internal fun suspendGuest(
        username: String,
        durationSeconds: Long,
        reason: String,
    ): DesktopGuestBans.BanOutcome {
        if (guestBansRideTheEngineRelay()) {
            return relayBanLeg("suspend", username, renderRelayBanDuration(durationSeconds), reason)
        }
        return backend.withDatadirQuiesced { guestBanStore.suspendFor(username, durationSeconds, reason) }
    }

    /**
     * F7: the MARIADB lane's ban/suspend leg — ONE engine `ban account`
     * verb (duration "0" = the engine's permanent form, a
     * TimeStringToSecs-shaped preset otherwise) through the same
     * provider-agnostic relay the Players moderation surface rides, so
     * the row lands in the realmd database the realm actually enforces on
     * this provider. The verdict carries no account id — the enforced
     * view the Guests card lists next is the roster read.
     */
    private fun relayBanLeg(
        verb: String,
        username: String,
        duration: String,
        reason: String,
    ): DesktopGuestBans.BanOutcome {
        val verdict = relayModeration(RELAY_AUDIT_BANNED, username) {
            backend.banFriendAccount(username.trim(), duration, reason.trim())
        }
        return if (verdict.ok) {
            DesktopGuestBans.BanOutcome.Banned(accountId = 0L, alreadyBanned = false)
        } else {
            DesktopGuestBans.BanOutcome.Refused(relayRefusal(verb, username, verdict.detail))
        }
    }

    /**
     * F7: the MARIADB-lane verbs' shared shape — run the provider-agnostic
     * engine relay verb (bridged onto the calling thread: the Guests
     * card's verbs keep the sync shape the card already calls), land the
     * no-secret audit row on EVERY settled call (the same rows the
     * SQLITE lane's store and the Players moderation verbs write), and
     * convert any native failure to an honest refusal verdict — never a
     * crash into the UI path.
     */
    private fun relayModeration(
        action: String,
        username: String,
        verb: suspend () -> ModerationResult,
    ): ModerationResult {
        val settled = runCatching { runBlocking { verb() } }.getOrElse { failure ->
            val detail = failure.message ?: failure.javaClass.simpleName
            friendAccess.store.auditModeration(action, username, null, "relay-failed: $detail")
            return ModerationResult(false, "the realm's console relay did not answer ($detail)")
        }
        friendAccess.store.auditModeration(
            action,
            username,
            null,
            if (settled.ok) "relay: ${settled.detail}" else "relay-failed: ${settled.detail}",
        )
        return settled
    }

    /** The relay refusal's honest copy: the not-ready leg names the REAL
     * remedy (start the realm — never a provider switch), every other
     * verdict rides the engine's own detail verbatim. */
    private fun relayRefusal(verb: String, username: String, detail: String): String =
        if (detail == RELAY_NOT_READY) {
            "the realm is not running — on this database provider a $verb rides the live " +
                "realm's console (start the realm, then $verb '$username' again)"
        } else {
            "$verb '$username' refused: $detail"
        }

    // ------------------------------------------------------------------
    // Design realm-status-browser.md: the read-only realm-status page —
    // the visibility machine's live controller (page server, page-only
    // cloudflared child, orphan gate, telemetry). Constructed lazily
    // at the first hosting-card render; [boot] applies its boot
    // semantics (a persisted FRIENDS restores, a persisted PUBLIC
    // re-arms without resuming). The ONE lifecycle rule: NOTHING
    // here keys off stopReason — realm stops never touch the page.
    // ------------------------------------------------------------------

    private val realmStatusLazy = lazy {
        com.pocketrealm.desktop.realmstatus.RealmStatusController(this).apply { boot() }
    }

    /** The realm-status page controller (the hosting card's model). */
    internal val realmStatus: com.pocketrealm.desktop.realmstatus.RealmStatusController
        get() = realmStatusLazy.value

    // ------------------------------------------------------------------
    // W6-N1 (docs/NOSTR-IROH-BUILD-PLAN.md): the host-side Nostr listing
    // heartbeat — publishes the realm listing while the realm is up AND
    // the realm-status visibility is PUBLIC AND the host's discovery
    // switch is on; retracts with NIP-09 on any gate drop and on graceful
    // shutdown. Constructed lazily beside the realm-status controller it
    // captures from (the payload builder is the single source of truth).
    // ------------------------------------------------------------------

    private val nostrListingLazy = lazy {
        com.pocketrealm.desktop.nostr.NostrListingPublisher(this, realmStatus).apply { boot() }
    }

    /** The Nostr listing publisher (the browser card's host controls). */
    internal val nostrListing: com.pocketrealm.desktop.nostr.NostrListingPublisher
        get() = nostrListingLazy.value

    /** The effective relay set (the stored list, else the shipped
     * defaults) — the read side and the write side always agree. */
    internal fun effectiveNostrRelays(): List<String> =
        com.pocketrealm.desktop.nostr.effectiveRelays(_settings.value)

    // ------------------------------------------------------------------
    // the iroh gateway lane — the join verbs over the CHILD
    // PROCESS transport (the manager owns host + join process lifecycles;
    // the publisher reads the host descriptor for the listing's `join`
    // field). The refusal ladder IS the ask's host+join contract: one
    // instance owns 3724/8085 at a time.
    // ------------------------------------------------------------------

    private val irohGatewayLazy = lazy {
        com.pocketrealm.desktop.iroh.IrohGatewayManager().also { manager ->
            // Self sign-up requests the host gateway relays from joiners.
            manager.hostSignupHandler = { request -> runBlocking { answerSignup(request) } }
            manager.hostPeerSignupHandler = { request, peer -> runBlocking { answerSignup(request, peer) } }
            // Bans, the join password and the player limit: the host
            // gateway asks before serving a joiner.
            manager.hostAdmissionHandler = { peer, passwordProof ->
                val s = _settings.value
                val locked = s.gamePassword.isNotBlank()
                val hostNode = manager.hostDescriptor()?.nodeHex
                val expectedProof = if (locked && hostNode != null) {
                    com.pocketrealm.discovery.iroh.JoinPasscode.proof(
                        com.pocketrealm.discovery.iroh.JoinPasscode.keyHex(s.gamePassword),
                        hostNode,
                        peer.nodeHex,
                    )
                } else {
                    null
                }
                publicModeration.admit(
                    peer,
                    com.pocketrealm.discovery.iroh.JoinHello.Request(
                        com.pocketrealm.discovery.iroh.JoinHello.SCHEMA,
                        peer.deviceToken,
                        passwordProof,
                    ),
                    locked = locked,
                    expectedProof = expectedProof,
                    playerLimit = com.pocketrealm.server.ServerRuntimeContract.WORLD_PLAYER_LIMIT,
                    onlinePlayers = publicModeration.connectedPeers(),
                ).also { if (it.ok) publicModeration.markConnected(peer) }
            }
            manager.hostPeerEvents = { event ->
                when (event.kind) {
                    com.pocketrealm.discovery.iroh.JoinHello.EVENT_LOGIN ->
                        event.account?.let { publicModeration.recordLogin(it, event.peer) }
                    com.pocketrealm.discovery.iroh.JoinHello.EVENT_LEAVE -> publicModeration.markLeft(event.peer)
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public hosting moderation: who joined from which device, device /
    // node / account bans, and the per-device sign-up limits (shared with
    // the Android host).
    // ------------------------------------------------------------------

    private val publicModeration by lazy {
        com.pocketrealm.supervisor.PublicHostModerationStore(
            File(roots.realm, com.pocketrealm.supervisor.PublicHostModerationStore.FILE_NAME),
        )
    }

    private val publicModerator by lazy {
        com.pocketrealm.supervisor.PublicHostModerator(
            store = publicModeration,
            banAccount = { name, reason ->
                supervisor.banFriendAccount(name, com.pocketrealm.supervisor.PublicHostModerator.PERMANENT, reason)
            },
            unbanAccount = { name -> supervisor.unbanFriendAccount(name) },
            disconnect = { device, node ->
                if (irohGatewayLazy.isInitialized()) irohGateway.dropHostPeer(device, node)
            },
        )
    }

    /** The host's players list (sign-ups plus anyone seen logging in over
     * the internet) with ban state. */
    internal fun publicPlayers(): List<com.pocketrealm.supervisor.PublicHostModerationStore.PlayerRow> =
        publicModeration.players(signupStore.list())

    internal fun publicDeviceBans(): List<com.pocketrealm.supervisor.PublicHostModerationStore.DeviceBan> =
        publicModeration.deviceBans()

    /** One host ban verb (account / device / both, or lifting one). */
    internal suspend fun moderatePublicPlayer(
        action: com.pocketrealm.supervisor.PublicHostModerator.Action,
        target: String,
    ): ModerationResult = withContext(Dispatchers.IO) {
        publicModerator.act(action, target).also { result ->
            DesktopLog.i(TAG, "public moderation $action ${if (result.ok) "applied" else "refused"}")
        }
    }

    /** This machine's device secret: Windows' MachineGuid (it survives an
     * app reinstall) or, without one, this install's random id. Only ever
     * hashed into per-host tokens; the raw value never leaves the app. */
    private val deviceSecret: ByteArray by lazy {
        val installId = com.pocketrealm.discovery.iroh.DeviceIdentity.loadOrCreateInstallId(
            File(roots.settings, DEVICE_INSTALL_ID_FILE),
        )
        val machineGuid = runCatching {
            com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE,
                "SOFTWARE\\Microsoft\\Cryptography",
                "MachineGuid",
            )
        }.getOrNull()
        com.pocketrealm.discovery.iroh.DeviceIdentity.deviceSecret(installId, machineGuid)
    }

    // ------------------------------------------------------------------
    // Self sign-up for the published realm (the Android twin's lane):
    // strangers create their own account through the tunnel, gated by the
    // host's switch + cap, created through the supervisor's validated
    // verifier path (the host flavor's expansion, gmlevel 0 — never GM).
    // ------------------------------------------------------------------

    private val signupStore by lazy {
        com.pocketrealm.supervisor.SignupAccountStore(
            File(roots.realm, com.pocketrealm.supervisor.SignupAccountStore.FILE_NAME),
        )
    }

    /** One decision at a time: the cap check and the create are one step. */
    private val signupMutex = kotlinx.coroutines.sync.Mutex()

    internal suspend fun answerSignup(
        request: com.pocketrealm.discovery.iroh.SignupProtocol.Request,
        /** The joiner's identity from its hello (null = none, and then a
         * host that checks devices refuses the sign-up). */
        peer: com.pocketrealm.discovery.iroh.JoinPeer? = null,
        /** Off for the legacy no-identity path the tests drive. */
        checkDevice: Boolean = peer != null,
    ): com.pocketrealm.discovery.iroh.SignupProtocol.Reply = signupMutex.withLock {
        val s = _settings.value
        com.pocketrealm.discovery.iroh.SignupProtocol.gate(
            s.onlineHosting && s.signupOpen, s.signupCap, signupStore.names(), request,
        )?.let { return@withLock it }
        if (checkDevice) publicModeration.signupGate(peer)?.let { return@withLock it }
        val result = supervisor.provisionFriendAccount(
            request.username, request.sHex, request.vHex, allowExistingAccount = false,
        )
        if (result.ok) {
            signupStore.add(request.username, System.currentTimeMillis())
            peer?.let { publicModeration.recordSignup(request.username, it) }
        }
        DesktopLog.i(TAG, "sign-up ${if (result.ok) "created" else "refused (${result.code})"}")
        com.pocketrealm.discovery.iroh.SignupProtocol.fromProvisionCode(result.ok, result.code, request.username)
    }

    /**
     * The D7 LAN sign-up lane (the friend-access port's kind=signup
     * request): the internet sign-up's gate and account creation with the
     * LAN-players switch and the game password's proof in front. No device
     * identity rides this lane — the listener's per-source rate limiter is
     * the abuse bound. (The Android twin is PublicListingHost.answerLanSignup.)
     */
    internal suspend fun answerLanSignup(
        request: com.pocketrealm.server.FriendProvisionProtocol.SignupRequest,
    ): com.pocketrealm.discovery.iroh.SignupProtocol.Reply = signupMutex.withLock {
        val s = _settings.value
        if (!s.signupOpen || !s.allowLanPlayers) {
            return@withLock com.pocketrealm.discovery.iroh.SignupProtocol.Reply(
                false,
                com.pocketrealm.discovery.iroh.SignupProtocol.SIGNUP_CLOSED,
                "This realm is not taking sign-ups right now.",
            )
        }
        if (s.gamePassword.isNotBlank()) {
            val expected = com.pocketrealm.server.FriendProvisionProtocol.lanSignupPass(s.gamePassword)
            if (!com.pocketrealm.discovery.iroh.JoinPasscode.matches(expected, request.pass)) {
                return@withLock com.pocketrealm.discovery.iroh.SignupProtocol.PASSWORD_WRONG_REPLY
            }
        }
        val ask = com.pocketrealm.discovery.iroh.SignupProtocol.Request(request.username, request.sHex, request.vHex)
        com.pocketrealm.discovery.iroh.SignupProtocol.gate(open = true, s.signupCap, signupStore.names(), ask)
            ?.let { return@withLock it }
        val result = supervisor.provisionFriendAccount(
            ask.username, ask.sHex, ask.vHex, allowExistingAccount = false,
        )
        if (result.ok) {
            signupStore.add(ask.username, System.currentTimeMillis())
        }
        DesktopLog.i(TAG, "LAN sign-up ${if (result.ok) "created" else "refused (${result.code})"}")
        com.pocketrealm.discovery.iroh.SignupProtocol.fromProvisionCode(result.ok, result.code, ask.username)
    }

    /** Realms this machine already signed up on (host node ids), so the
     * sign-up form only shows on the first join. */
    private val signedUpRealmsFile by lazy { File(roots.realm, "public-realm-signups.txt") }

    internal fun hasSignedUpOn(nodeHex: String): Boolean =
        runCatching { signedUpRealmsFile.readLines().any { it.trim() == nodeHex } }.getOrDefault(false)

    internal fun rememberSignupOn(nodeHex: String) {
        if (hasSignedUpOn(nodeHex)) return
        runCatching {
            signedUpRealmsFile.parentFile?.mkdirs()
            signedUpRealmsFile.appendText(nodeHex + "\n")
        }
    }

    /** The signed-up accounts (names and times only). */
    internal fun signupAccounts(): List<com.pocketrealm.supervisor.SignupAccountStore.Entry> = signupStore.list()

    /** Remove = a permanent ban (the world has no account delete); the
     * name leaves the list, freeing a cap slot. */
    internal suspend fun removeSignupAccount(username: String): ModerationResult {
        if (signupStore.names().none { it.equals(username, ignoreCase = true) }) {
            return ModerationResult(false, "not a sign-up account")
        }
        val ban = supervisor.banFriendAccount(username, PERMANENT_BAN_DURATION, "removed from sign-ups")
        if (ban.ok) {
            signupStore.remove(username)
            publicModeration.recordAccountBan(username, "removed from sign-ups")
        }
        return ban
    }

    /** The gateway process manager (host + join). */
    internal val irohGateway: com.pocketrealm.desktop.iroh.IrohGatewayManager
        get() = irohGatewayLazy.value

    /** The join lane's render state (idle/starting/ready/error). */
    internal val irohJoinState:
        kotlinx.coroutines.flow.StateFlow<com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState>
        get() = irohGateway.joinState

    /**
     * Joins a PUBLIC realm over iroh: launches the join gateway against
     * [descriptor]'s node id + home relay, and on READY launches the local
     * client with realmlist pointed at 127.0.0.1 (the joiner gateway's own
     * loopback listeners — the SMSG_REALM_LIST rewrite the gateway does is
     * what makes the game dial them). The refusal ladder is fail-closed:
     * a running realm stack (ports), an already-running join, a flavor
     * mismatch, or a gateway launch failure each refuse LOUDLY before any
     * side effect.
     */
    suspend fun startIrohJoin(
        descriptor: com.pocketrealm.discovery.iroh.IrohJoinDescriptor,
        listingFlavorId: String,
        /** Self sign-up sent on the READY tunnel before the client
         * launches (name + locally derived salt/verifier only). */
        signup: com.pocketrealm.discovery.iroh.SignupProtocol.Request? = null,
        /** The join password's derived key (64 hex) when the game is
         * locked — stdin to the gateway process after the device token;
         * the password itself never crosses. */
        passwordKeyHex: String? = null,
        /** The account to sign in with once the game opens (name to
         * password), or null to leave the login screen to the player. */
        login: Pair<String, String>? = null,
    ): RuntimeOperation {
        val outcome = prepareIrohJoin(descriptor, listingFlavorId, passwordKeyHex)
            ?: awaitJoinReadyAndLaunch(descriptor, listingFlavorId, signup, login)
        if (outcome.ok) return outcome
        DesktopLog.w(TAG, "internet join failed: ${outcome.detail}")
        return outcome.copy(detail = joinFailureForPlayer(outcome.detail))
    }

    /** The join failure as the player reads it: no internal codes. */
    private fun joinFailureForPlayer(detail: String): String = when {
        detail.startsWith(com.pocketrealm.desktop.iroh.IrohJoinGuards.VAL_IROH_JOIN_CLASH) ->
            "Stop your own realm or the other game you joined first, then try again."
        detail.startsWith("The gateway did not become ready") ->
            "Couldn't reach that game. The host may be offline; try again in a minute."
        else -> detail.replace(Regex("^[A-Z][A-Z0-9_-]+:\\s*"), "").replaceFirstChar { it.uppercase() }
    }

    /** True when a game folder for [flavorId] is ready to start for a join
     * (an imported client, the folder chosen for joining, or the Home game
     * folder of that exact version). */
    fun joinClientReady(flavorId: String): Boolean =
        backend.resolveJoinClientDir(flavorId, _settings.value.joinClientDirs) != null

    /** Remembers [dir] as the game folder for joining [flavorId] games.
     * Returns why it was refused, or null when it was saved. */
    fun chooseJoinClientDir(flavorId: String, dir: java.io.File): String? {
        val flavor = com.pocketrealm.client.ClientFlavors.ALL.firstOrNull { it.flavorId == flavorId }
            ?: return "This game version isn't supported."
        if (!backend.joinClientMatches(dir, flavor.build)) {
            return "That folder doesn't have the right version of the game. " +
                "Pick the folder with WoW.exe for ${flavor.expansion}."
        }
        updateSettings { it.copy(joinClientDirs = it.joinClientDirs + (flavorId to dir.absolutePath)) }
        return null
    }

    /** The pre-flight ladder: the pure refusal table (clash + flavor
     * rows), then the gateway process launch — the FIRST failure wins and
     * nothing downstream runs. Null = cleared to dial. */
    private fun prepareIrohJoin(
        descriptor: com.pocketrealm.discovery.iroh.IrohJoinDescriptor,
        listingFlavorId: String,
        passwordKeyHex: String? = null,
    ): RuntimeOperation? {
        // A join starts the client of the GAME's version from its own
        // folder, so the Home version pick does not matter here; what does
        // is that a folder of that version exists before anything is dialed.
        if (!joinClientReady(listingFlavorId)) {
            return RuntimeOperation(false, supervisor.state.value, JOIN_NEEDS_GAME_FOLDER)
        }
        val refusal = com.pocketrealm.desktop.iroh.IrohJoinGuards.joinRefusal(
            phase = supervisor.state.value.phase,
            joinState = irohGateway.joinState.value,
            activeFlavorId = listingFlavorId,
            listingFlavorId = listingFlavorId,
        ) ?: irohGateway.startJoin(
            descriptor = descriptor,
            flavorId = listingFlavorId,
            relayOnly = _settings.value.irohRelayOnly,
            // This machine's per-host key and device token (a host's bans
            // key on the token; the raw machine id never leaves the app).
            identity = runCatching {
                com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinIdentity.forHost(deviceSecret, descriptor.nodeHex)
            }.getOrNull(),
            passwordKeyHex = passwordKeyHex?.takeIf {
                com.pocketrealm.discovery.iroh.JoinPasscode.isKeyHex(it)
            },
        )
        return refusal?.let { RuntimeOperation(false, supervisor.state.value, it) }
    }

    /** Bounded wait for READY (the manager lands Ready/Error on the state
     * flow; this verb joins the outcome so the caller sees it), then the
     * client launch. A timeout stops the half-open join loudly. The whole
     * wait runs on Dispatchers.IO and SUSPENDS between polls (
     * a Thread.sleep poll here blocked the Compose UI
     * thread for up to 45 s — the verb is suspend exactly so the caller's
     * thread never owns this wait). */
    private suspend fun awaitJoinReadyAndLaunch(
        descriptor: com.pocketrealm.discovery.iroh.IrohJoinDescriptor,
        flavorId: String,
        signup: com.pocketrealm.discovery.iroh.SignupProtocol.Request? = null,
        login: Pair<String, String>? = null,
    ): RuntimeOperation = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + JOIN_READY_TIMEOUT_MS
        var outcome: RuntimeOperation? = null
        while (outcome == null && System.currentTimeMillis() < deadline) {
            outcome = when (val state = irohGateway.joinState.value) {
                is com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Ready -> {
                    DesktopLog.i(
                        TAG,
                        "iroh join READY (node " +
                            "${descriptor.nodeHex.take(IROH_LOG_NODE_CHARS)}…)",
                    )
                    signupThenLaunch(flavorId, signup, login)
                }

                is com.pocketrealm.desktop.iroh.IrohGatewayManager.JoinState.Error ->
                    RuntimeOperation(
                        false,
                        supervisor.state.value,
                        if (state.code == com.pocketrealm.discovery.iroh.GatewayContract.ERR_JOIN_REFUSED) {
                            state.detail
                        } else {
                            "${state.code}: ${state.detail}"
                        },
                    )

                else -> {
                    delay(JOIN_READY_POLL_MS)
                    null
                }
            }
        }
        if (outcome == null) {
            irohGateway.stopJoin()
            outcome = RuntimeOperation(
                false,
                supervisor.state.value,
                "The gateway did not become ready within " +
                    "${JOIN_READY_TIMEOUT_MS / JOIN_READY_TIMEOUT_SECONDS_DIVISOR}s " +
                    "(host offline? relay unreachable?)",
            )
        }
        outcome
    }

    /** The client launch after the tunnel is READY: the directory resolves
     * through the SAME managed-generation-first, identity-gated seam the
     * local lane uses ( R6-N3-1: the legacy settings clientDir
     * alone launched a wrong-flavor or unmanaged binary against the joined
     * realm); realmlist then projects to 127.0.0.1 (the joiner gateway's
     * listeners) — the game sees a loopback realm. */
    /** A refused sign-up ends the join (the player picks another name). */
    private fun signupThenLaunch(
        flavorId: String,
        signup: com.pocketrealm.discovery.iroh.SignupProtocol.Request?,
        login: Pair<String, String>?,
    ): RuntimeOperation {
        if (signup != null) {
            val reply = irohGateway.joinSignup(signup)
            lastJoinSignup = reply
            if (!reply.ok) {
                irohGateway.stopJoin()
                return RuntimeOperation(false, supervisor.state.value, reply.detail)
            }
        }
        return launchIrohJoinClient(flavorId, login)
    }

    /** The last internet join's sign-up answer (the browser's copy). */
    @Volatile internal var lastJoinSignup: com.pocketrealm.discovery.iroh.SignupProtocol.Reply? = null

    private fun launchIrohJoinClient(flavorId: String, login: Pair<String, String>?): RuntimeOperation {
        val resolvedDir = backend.resolveJoinClientDir(flavorId, _settings.value.joinClientDirs)
            ?: return run {
                irohGateway.stopJoin()
                RuntimeOperation(false, supervisor.state.value, JOIN_NEEDS_GAME_FOLDER)
            }
        // The client launcher refuses honestly (exe missing, already
        // running, before any side effect on the client directory.
        return try {
            val launcher = com.pocketrealm.desktop.DesktopClientLauncher(
                settingsFile = roots.settingsFile,
                clientDirOverride = resolvedDir,
            )
            val observation = launcher.launch(
                com.pocketrealm.supervisor.RealmEndpoint.LOCAL,
                owner = null,
            )
            DesktopLog.i(
                TAG,
                "iroh join client launched (pid ${observation.pid}, " +
                    "dir ${resolvedDir.name})",
            )
            login?.let { (name, password) -> signInJoinedClient(observation.pid, name, password, flavorId) }
            RuntimeOperation(true, supervisor.state.value, "joined over the internet (tunnel up)")
        } catch (failure: IllegalStateException) {
            irohGateway.stopJoin()
            RuntimeOperation(
                false,
                supervisor.state.value,
                "The game couldn't start: ${failure.message}",
            )
        }
    }

    /** Types the joined game's account into the client's login screen in
     * the background; a failure leaves the login screen to the player. */
    private fun signInJoinedClient(pid: Int?, username: String, password: String, flavorId: String) {
        val clientPid = pid ?: return
        modelScope.launch(Dispatchers.IO) {
            val failure = Win32AutoLogin.tryLogin(
                clientPid,
                username,
                password,
                _settings.value.effectiveAutoLoginTimings(),
                flavorId,
            )
            if (failure != null) {
                DesktopLog.w(TAG_AUTO_LOGIN, "joined game sign-in skipped: $failure")
            } else {
                DesktopLog.i(TAG_AUTO_LOGIN, "joined game sign-in delivered")
            }
        }
    }

    /** Stops the internet join (the gateway process; the game session ends
     * with the tunnel). Safe when nothing is running. */
    fun stopIrohJoin() {
        irohGateway.stopJoin()
    }

    /** The relay-only switch verb (persisted; default ON — the join lane
     * reads it per start). */
    fun setIrohRelayOnly(relayOnly: Boolean) {
        updateSettings { it.copy(irohRelayOnly = relayOnly) }
    }

    /** The host gateway's descriptor for the listing's `join` field — the
     * publisher's seam. Ensures the persisted transport identity exists
     * (generated once; the node id derives from it and must survive
     * restarts). */
    @Synchronized
    internal fun ensureIrohGatewayIdentity(): String? {
        val stored = _settings.value.irohGatewaySecretHex
        if (stored.length == IROH_SECRET_HEX_LENGTH) return stored
        val fresh = ByteArray(IROH_SECRET_BYTES)
            .also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        updateSettings { it.copy(irohGatewaySecretHex = fresh) }
        return fresh
    }

    // ------------------------------------------------------------------
    // Design-tunnel / /: the host-over-the-internet (tunnel) lane's
    // model helpers — the stored advertised address as typed state, the
    // effective advertisement for display/invite, and the invite
    // artifact (address block only). All settings-derived: the same source
    // launchSpec builds from, so the card can never promise an address the
    // next start would not project.
    // ------------------------------------------------------------------

    /** The STORED tunnel advertisement as typed state, or null when this
     * is not a LAN_HOST session with a valid tunnel address set. The
     * settings-derived leg only — the RUNNING session's watchdog AND the
     * invite/display surfaces read the supervisor snapshot's frozen
     * advertisement first ([advertisedForInvite], design-tunnel). */
    private fun storedTunnelAdvertisement(): com.pocketrealm.supervisor.AdvertisedAddress? {
        val snapshot = _settings.value
        if (snapshot.runtimeMode != RuntimeMode.LAN_HOST) return null
        val raw = snapshot.tunnelAdvertisedAddress
        if (raw.isBlank()) return null
        return runCatching {
            com.pocketrealm.supervisor.AdvertisedAddress.parse(raw)
        }.getOrNull()
    }

    /**
     * The advertisement the invite/display surfaces name (design-tunnel:
     * "the live advertised address, whichever mode is active"). While a
     * LAN_HOST session is up this is the SNAPSHOT's frozen advertisement —
     * the value the projection actually wrote into the realmlist row and
     * the watchdog watches — so a mid-session settings edit can never make
     * the copied invite name an address the running realm never projected.
     * With no live advertisement (stopped, or a plain-LAN session) it falls
     * through to the settings-derived effective advertisement, which is
     * what the NEXT start would project.
     */
    private fun advertisedForInvite(): String? {
        supervisor.state.value.advertisedAddress?.let { live ->
            return live
        }
        return storedTunnelAdvertisement()?.value
    }

    /**
     * The effective advertisement for display ( "the live advertised
     * address, whichever mode is active"): the live session's frozen
     * advertisement when one is up, else the settings-derived tunnel
     * override when set and valid, else the bind LAN address, in LAN_HOST;
     * null outside hosting modes (LOCAL has no advertisement to show).
     * Display-only — the honest projected row
     * ([DesktopRuntimeBackend.projectedRealmAddress]) stays the invite
     * line's primary source.
     */
    fun advertisedOrDefaultForDisplay(): String? {
        val snapshot = _settings.value
        if (snapshot.runtimeMode != RuntimeMode.LAN_HOST) return null
        advertisedForInvite()?.let { return it }
        return runCatching { RealmEndpoint.parseLan(snapshot.lanAddress) }.getOrNull()?.address
    }

    /**
     * The invite artifact, rendered from the effective advertisement
     * (the tunnel address in tunnel mode — never the LAN IP then), the
     * projected realm name (fail-soft generic header), and the tunnel-kind
     * split. Carries NO credential (the Guests card's handoff pair is the
     * separate sensitive step, design-lan D5a / this doc).
     *
     * Mode-honest: with NO tunnel advertisement set this is the LAN lane,
     * and the artifact is [TunnelInvite.lanInviteText] — the LAN address
     * plus a no-tunnel setup step — never tunnel-kind copy dressed around
     * a LAN address (the invite is the artifact friends follow).
     */
    fun tunnelInviteBlock(): String {
        val realmName = backend.projectedRealmName() ?: "my realm"
        val advertised = advertisedForInvite()
        if (advertised == null) {
            val lan = advertisedOrDefaultForDisplay() ?: RealmEndpoint.LOOPBACK_ADDRESS
            return TunnelInvite.lanInviteText(realmName = realmName, lanAddress = lan)
        }
        val kind = runCatching {
            TunnelInvite.tunnelKindFor(
                com.pocketrealm.supervisor.AdvertisedAddress.parse(advertised),
            )
        }.getOrDefault(TunnelInvite.TunnelKind.OTHER)
        return TunnelInvite.inviteText(
            realmName = realmName,
            advertised = advertised,
            kind = kind,
        )
    }

    /**
     * the one-shot pre-join reachability probe against
     * the stored join address (TCP 3724, ~2 s). Blocking socket work — the
     * caller runs it off the UI thread. A failure renders through the
     * shared [RuntimeFailureCopy] `LAN-PROBE-UNREACHABLE` entry (firewall
     * remedy included); it never auto-launches or auto-joins anything.
     */
    fun probeJoinTarget(): LanJoinProbe.ProbeResult =
        LanJoinProbe.probe(_settings.value.lanAddress)

    // ------------------------------------------------------------------
    // the transactional database restore. Mirrors the
    // Android RealmService.restoreBackup exactly: BEGIN (the backend
    // hash-restores a candidate snapshot and swaps it in, quarantining the
    // live datadir) → PROVE (boot db+realm+world to WORLD_READY) → clean
    // stop → COMMIT; any failure force-stops and ROLLS the original
    // datadir back. The boot proof is a seam only so the transaction
    // discipline (commit vs rollback) is unit-testable without the native
    // lanes; production always passes [bootRestoredStackWorldReadyProof].
    // ------------------------------------------------------------------

    suspend fun restoreBackup(snapshotId: String): RuntimeOperation {
        // GAP-1 (was DESKPROD-1): defense in depth — the same gate the UI
        // disables its restore buttons on refuses HERE, with the same
        // reason, before the backend is engaged (the backend's
        // runtimes-at-rest refusal inside beginSnapshotRestore stays the
        // final arbiter; a busy-flag race still surfaces RESTORE-REFUSED,
        // never a swap).
        val gate = restoreGate()
        if (gate is RestoreGate.Blocked) {
            return RuntimeOperation(
                false,
                supervisor.state.value,
                "RESTORE-REFUSED: ${gate.reason}",
            ).also(::logOperation)
        }
        return restoreBackup(snapshotId, ::bootRestoredStackWorldReadyProof)
    }

    /** The transaction with the proof injected (tests pin both outcomes). */
    internal suspend fun restoreBackup(
        snapshotId: String,
        bootProof: suspend () -> String?,
    ): RuntimeOperation {
        val begun = try {
            backend.beginSnapshotRestore(snapshotId)
        } catch (failure: Throwable) {
            return RuntimeOperation(
                false,
                supervisor.state.value,
                "RESTORE-REFUSED: ${failure.message}",
            ).also(::logOperation)
        }
        DesktopLog.i(TAG, "restore ${begun.snapshotId}: candidate active; proving world-ready")
        return try {
            val proofFailure = bootProof()
            check(proofFailure == null) {
                "restored candidate did not reach world-ready: $proofFailure"
            }
            val stopped = supervisor.stop(StopMode.GRACEFUL)
            check(stopped.ok && stopped.snapshot.clean) {
                "restored candidate did not clean-stop: ${stopped.detail}"
            }
            val commit = backend.commitSnapshotRestore(begun.token)
            check(commit.ok) { commit.detail }
            // GAP-2 (was DESKPROD-2): the proof reached WORLD_READY, and
            // DESK-25's contract is that READY spends the process's one
            // world lifetime — a passed proof ALWAYS leaves it spent, so
            // the completion copy must disclose the consequence instead of
            // letting the user discover WRONG_STATE on the next Start.
            RuntimeOperation(
                true,
                supervisor.state.value,
                "restored ${begun.snapshotId} (world-ready proven, bots disabled; digest " +
                    "${begun.digest.take(12)}…) — the proof spent this app session's one " +
                    "world start, so the next start needs an app relaunch",
            ).also(::logOperation)
        } catch (failure: Throwable) {
            runCatching { supervisor.stop(StopMode.FORCED) }
            val rollback = runCatching { backend.rollbackSnapshotRestore(begun.token) }
            val detail = "RESTORE-ROLLED-BACK: ${failure.message}; " + rollback.fold(
                { it.detail },
                { "rollback also failed: ${it.message} (inspect restore-original-* under ${roots.database})" },
            )
            DesktopLog.w(TAG, detail)
            RuntimeOperation(false, supervisor.state.value, detail)
        }
    }

    /**
     * The production world-ready proof: boot the swapped-in candidate
     * through the app's launch gate and supervisor, client not requested —
     * the supervisor's contract puts a completed start at WORLD_READY.
     * Returns null on proof, else the honest failure detail.
     *
     * GAP-2 (was DESKPROD-2): the proof boots a MINIMAL spec, not the
     * ordinary one — [DesktopAppModel.DESKTOP_PROFILE] is the explicit
     * bots-disabled profile (no bot conf is staged, no population accounts
     * are provisioned into the candidate before READY), so the committed
     * state is exactly the snapshot the user picked. The proof still
     * reaches WORLD_READY through the ordinary supervisor path, so the
     * DESK-25 one-lifetime spend is real and durable by contract — the
     * restore's completion copy discloses the relaunch requirement instead
     * of resetting it. (Internal so the JVM legs can pin the spec the
     * proof builds; the boot itself is the native lanes' job.)
     */
    internal suspend fun bootRestoredStackWorldReadyProof(): String? {
        val started = startRealm(includeClient = false, profileOverride = DESKTOP_PROFILE)
        if (!started.ok) return started.detail
        val phase = supervisor.state.value.phase
        return if (phase == RuntimePhase.WORLD_READY) null else "phase=$phase after start"
    }

    /** The named backups for the Diagnostics card (newest first). */
    fun listBackups(): List<DesktopRuntimeBackend.BackupEntry> =
        runCatching { backend.listBackups() }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // the realm data archive — one plain-ZIP file
    // (a stopped-state snapshot + the paired credentials when opted in +
    // a manifest) that moves characters between installs, cross-compatible
    // with the Android app through the SHARED RealmDataArchive codec.
    // Exports land in the exports root — the ONLY user-facing output root.
    // ------------------------------------------------------------------

    /** One export's facts (the Settings card's status line). */
    data class RealmArchiveExport(val file: File, val snapshotId: String, val digest: String)

    /**
     * Export the current characters: named backup of the stopped datadir
     * (the at-rest gate applies), then the shared single-file ZIP into the
     * exports root. Credentials ride ONLY when [includeAccount] is set —
     * an exported ZIP travels wherever the user sends it; re-entering a
     * password on import is cheap, un-leaking a credentials file is not
     * (the Android Settings default).
     */
    suspend fun exportRealmArchive(includeAccount: Boolean): RealmArchiveExport =
        withContext(Dispatchers.IO) {
            val backup = backend.createNamedBackup("export")
            check(backup.ok && backup.snapshotId != null) { backup.detail }
            val snapshotId = requireNotNull(backup.snapshotId)
            val snapshotDir = File(roots.databaseSnapshots, snapshotId)
            check(snapshotDir.isDirectory) { "snapshot directory missing: $snapshotId" }
            roots.exports.mkdirs()
            val target = File(roots.exports, "realm-characters-$snapshotId.zip")
            val accountFile = if (includeAccount) {
                File(roots.root, "user-account/account.json").takeIf { it.isFile }
            } else {
                null
            }
            val meta = com.pocketrealm.ui.RealmDataArchive.meta(
                snapshotId,
                System.currentTimeMillis(),
                APP_VERSION,
                System.getProperty("os.arch") ?: "unknown",
            )
            target.outputStream().use { output ->
                com.pocketrealm.ui.RealmDataArchive.writeArchive(output, snapshotDir, accountFile, meta)
            }
            DesktopLog.i(TAG, "realm archive exported: ${target.name} (snapshot $snapshotId)")
            RealmArchiveExport(target, snapshotId, requireNotNull(backup.digest))
        }

    /** Read only the archive manifest, without extracting anything. */
    internal fun inspectRealmArchive(archive: File): com.pocketrealm.ui.RealmDataArchive.ArchiveInfo? =
        runCatching { archive.inputStream().use { com.pocketrealm.ui.RealmDataArchive.inspect(it) } }
            .getOrNull()

    /**
     * Import a characters archive: extract its snapshot into the snapshots
     * root (digest-verified by the shared codec BEFORE publication), then
     * run the full transactional restore, then restore the paired
     * credentials when the archive carried them. The Windows lane has no
     * chmod leg: the record lives under the user profile, private by NTFS
     * profile ACLs (the UserAccountStore posture).
     */
    suspend fun importRealmArchive(archive: File): RuntimeOperation =
        importRealmArchive(archive, ::bootRestoredStackWorldReadyProof)

    /** The import with the restore's world-ready proof injected (tests pin
     * the transaction without the native lanes). */
    internal suspend fun importRealmArchive(
        archive: File,
        bootProof: suspend () -> String?,
    ): RuntimeOperation = withContext(Dispatchers.IO) {
        val info = inspectRealmArchive(archive)
        if (info == null) {
            return@withContext RuntimeOperation(
                false, supervisor.state.value, "Could not read the selected archive.",
            )
        }
        if (info.snapshotId.isBlank()) {
            return@withContext RuntimeOperation(
                false, supervisor.state.value, "Archive has no snapshot id.",
            )
        }
        // The id names the extraction root, and it comes out of the ARCHIVE —
        // so the shared id gate must run HERE, before target.mkdirs(): an id
        // like "..\..\escaped" would otherwise walk File(roots.databaseSnapshots, id)
        // and the mkdirs/extract behind it out of the snapshots tree, because
        // the store's own refusal fires only at restore time, after the write.
        // RealmDataArchive.validSnapshotId is the codec's copy of the store's
        // validId grammar — one shared literal, pinned by RealmDataArchiveTest.
        if (!com.pocketrealm.ui.RealmDataArchive.validSnapshotId(info.snapshotId)) {
            return@withContext RuntimeOperation(
                false, supervisor.state.value, "Archive has an invalid snapshot id.",
            )
        }
        val target = File(roots.databaseSnapshots, info.snapshotId)
        if (target.exists()) {
            return@withContext RuntimeOperation(
                false,
                supervisor.state.value,
                "Snapshot ${info.snapshotId} already exists on this machine.",
            )
        }
        val accountBytes = try {
            check(target.mkdirs()) { "could not create snapshot directory" }
            archive.inputStream().use { input ->
                com.pocketrealm.ui.RealmDataArchive.extractSnapshot(input, target).second
            }
        } catch (failure: Throwable) {
            target.deleteRecursively()
            return@withContext RuntimeOperation(
                false,
                supervisor.state.value,
                "Import failed: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
        val restored = restoreBackup(info.snapshotId, bootProof)
        if (!restored.ok) {
            // The extract existed only to feed this restore — a refusal or
            // rollback must not leave the imported tree published in (or,
            // pre-validation, out of) the snapshots root.
            target.deleteRecursively()
            return@withContext restored
        }
        // The archive's account bytes are user-picked input: they replace
        // this machine's saved auto-login record only after passing the
        // store's own decode. An invalid record rejects ONLY this leg —
        // the database import above stays — and so does the leg's own IO
        // failure: folded into the note, never thrown past the committed
        // restore above as an "Import failed" (see importedAccountNote).
        val accountNote = importedAccountNote(accountBytes)
        RuntimeOperation(
            true,
            supervisor.state.value,
            "Realm data imported (snapshot ${info.snapshotId}, from app ${info.appVersionName}).$accountNote",
        ).also(::logOperation)
    }

    /** The import's account-leg verdict, as the trailing note on the
     * committed import's detail: no account bytes → no note; a valid
     * record written → the replaced-login note; an invalid record (the
     * store's own decode refuses it) → the rejected note. The write's own
     * IO failures (the raw FileOutputStream + Files.move legs of
     * [writeImportedAccount]) are folded into the note too, because the
     * character restore has already committed by the time this runs: a
     * throw here would reach the UI as "Import failed" for characters
     * that were in fact restored, and the committed success would never
     * be journaled through [logOperation]. */
    private fun importedAccountNote(bytes: ByteArray?): String =
        if (bytes == null) {
            ""
        } else {
            runCatching { writeImportedAccount(bytes) }.fold(
                { written ->
                    if (written) " Saved login replaced from the archive."
                    else " The archive's saved login was invalid and was not restored."
                },
                { failure ->
                    " Characters restored, but the saved login could not be written: " +
                        (failure.message ?: failure.javaClass.simpleName)
                },
            )
        }

    /** Atomic write of an imported credentials record + the state refresh.
     * The bytes must first pass the store's own decode
     * (RealmDataArchive.isValidAccountRecord → UserAccountStore.decodeRecord);
     * the write itself is the UserAccountStore atomic discipline, byte-form.
     * Returns false (writing nothing) when the record is invalid. */
    private fun writeImportedAccount(bytes: ByteArray): Boolean {
        if (!com.pocketrealm.ui.RealmDataArchive.isValidAccountRecord(bytes)) return false
        val directory = File(roots.root, "user-account")
        directory.mkdirs()
        val recordFile = File(directory, "account.json")
        val temp = File(directory, ".user-account.${ProcessHandle.current().pid()}.tmp")
        java.io.FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(), recordFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), recordFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        _storedAccount.value = accountStore.loadOrQuarantine()
        return true
    }

    private fun logOperation(operation: RuntimeOperation) {
        if (operation.ok) {
            DesktopLog.i(TAG, operation.detail)
        } else {
            DesktopLog.w(TAG, "operation FAILED: " + operation.detail)
        }
    }

    /**
     * Create (or verify) the local account through the supervisor's control
     * channel and remember it for auto-login on success — the Android Home
     * account-card contract. Secrets never leave this model's callers.
     */
    suspend fun createAccount(
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult {
        val result = supervisor.provisionAccount(username, password, gmLevel)
        if (result.ok && result.accountId > 0 &&
            result.code in setOf("ACCOUNT_CREATED", "ACCOUNT_VERIFIED")
        ) {
            withContext(Dispatchers.IO) {
                _storedAccount.value = accountStore.save(username, password, result.accountId, result.gmLevel)
            }
        }
        return result
    }

    fun clearAccount() {
        accountStore.clear()
        _storedAccount.value = null
    }

    /**
     * Best-effort auto-login after a client launch: the shared
     * [AutoLoginPolicy] gate, then a live-world READ-ONLY credential proof
     * through the shared [AutoLoginCredentialProof] dispositions — the
     * proof never creates or mutates an identity (a credential saved from
     * a friend-join can never mint a phantom local account at a LOCAL
     * start), and an INVALID_CREDENTIAL verdict KEEPS the stored record
     * (the Android disposition) instead of re-typing or destroying it —
     * and only then the pinned-login-window typed entry with the DESK-133
     * timing surface ([Win32AutoLogin.tryLogin]). Runs on IO; every
     * failure degrades to the manual login screen and is logged, never
     * thrown — the twin-aligned outcome: the Android startClient skips
     * auto-login through the shared
     * [AutoLoginCredentialProof.credentialForLaunch] hand-off rather than
     * failing its launch, so a stale saved account degrades identically on
     * both platforms.
     */
    suspend fun autoLoginIfEnabled() {
        // the gate decision resolves through the shared, tested
        // AutoLoginPolicy (master switch AND user-provisioned identity — no
        // synthesized fallback), the same seam the Android client stack
        // drives; the desktop no longer re-implements the truth table.
        val snapshot = _settings.value
        val account = _storedAccount.value
        val decision = AutoLoginPolicy.resolveAutoLogin(
            profileId = backend.activeBotProfileId() ?: DESKTOP_PROFILE,
            autoLoginOnLaunch = snapshot.autoLoginOnLaunch,
            userAccountProvisioned = account != null,
            isBotProfile = { id -> com.pocketrealm.bots.BotProfiles.find(id) != null },
        )
        if (decision.off || account == null) return
        val timings = snapshot.effectiveAutoLoginTimings()
        withContext(Dispatchers.IO) {
            // Bounded wait for the observable client component (the old
            // fixed 60 s/1 s constants are the DESK-133 timing surface now).
            val deadline = System.currentTimeMillis() + timings.sessionTimeoutMs
            var pid: Int? = null
            while (System.currentTimeMillis() < deadline) {
                val client = runCatching { backend.observe(RuntimeComponent.CLIENT) }.getOrNull()
                if (client != null && client.ready) {
                    pid = client.pid
                    break
                }
                Thread.sleep(timings.pollIntervalMs)
            }
            val target = pid ?: run {
                DesktopLog.w(
                    TAG_AUTO_LOGIN,
                    "client never became observable within ${timings.sessionTimeoutMs / 1000} s; " +
                        "skipping (the saved account is kept)",
                )
                return@withContext
            }
            // prove the stored credential against the live world
            // owner BEFORE any keystroke — the same verdict order the
            // Android backend drives, through the same READ-ONLY seam
            // ([supervisor.verifyAccountCredentials]): the proof NEVER
            // creates or mutates an identity, so a credential saved from a
            // friend-join can never mint a phantom local account at a
            // LOCAL start (the join-lifecycle parity fix). Only an
            // authoritative answer from the exact world owner may reject
            // the record; everything else degrades to manual login and
            // keeps it.
            val world = runCatching { backend.observe(RuntimeComponent.WORLD) }.getOrNull()
            val snapshotState = supervisor.state.value
            val expectedOwner = snapshotState.sessionId?.let { sessionId ->
                snapshotState.components[RuntimeComponent.WORLD]?.instanceToken?.let { token ->
                    ComponentOwner(sessionId, token)
                }
            }
            val proof = supervisor.verifyAccountCredentials(account.username, account.password)
            val disposition = credentialProofDisposition(
                storedAccountId = account.accountId,
                storedGmLevel = account.gmLevel,
                expectedOwner = expectedOwner,
                reportedOwner = world?.owner,
                provision = proof,
            )
            when (disposition) {
                AutoLoginCredentialProof.Disposition.ACCEPT -> Unit // proceed to the typed entry
                AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL -> {
                    // The world answered authoritatively that this stored
                    // record no longer matches it. The record is KEPT —
                    // exactly the Android disposition ("The saved account
                    // was kept"): a realm proof can race a user replacing
                    // the saved account in the UI, and deleting here could
                    // erase the newer credential based on rejection of the
                    // older candidate. Auto-login simply stays off until
                    // the user creates or verifies the account again — and
                    // the launch outcome is the Android disposition too:
                    // startClient hands its display no credential through
                    // the shared credentialForLaunch seam and keeps the
                    // client up at the manual login screen, so a stale
                    // saved account degrades identically on both platforms.
                    DesktopLog.w(
                        TAG_AUTO_LOGIN,
                        "saved account rejected by the realm (${proof.code}); " +
                            "the saved record is kept - create or verify it again " +
                            "to re-enable auto-login",
                    )
                    return@withContext
                }
                AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE -> {
                    DesktopLog.w(
                        TAG_AUTO_LOGIN,
                        "auto-login verification unavailable (${proof.code}); " +
                            "the saved account is kept and login stays manual",
                    )
                    return@withContext
                }
            }
            // the typed entry polls for the pinned login-frame
            // signature (never the first visible window) with the honest
            // "login window never appeared" timeout verdict.
            val failure = Win32AutoLogin.tryLogin(
                target,
                account.username,
                account.password,
                timings,
                snapshotState.flavor,
            )
            if (failure != null) {
                DesktopLog.w(TAG_AUTO_LOGIN, "auto-login skipped: $failure")
            } else {
                DesktopLog.i(TAG_AUTO_LOGIN, "auto-login keystrokes delivered")
            }
        }
    }

    override fun close() {
        // the graceful-stop leg of the listing lifecycle — the
        // NIP-09 retract rides out BEFORE the scopes die (bounded inside).
        if (nostrListingLazy.isInitialized()) {
            runCatching { nostrListing.shutdown() }
        }
        // the gateway child processes die with the model (both the
        // host endpoint and any live join — never an orphaned iroh relay
        // registration after app exit).
        if (irohGatewayLazy.isInitialized()) {
            runCatching { irohGateway.close() }
        }
        modelScope.cancel()
        // The realm-status page dies with the app process (the rule's
        // app-exit leg); only when the controller was ever constructed.
        if (realmStatusLazy.isInitialized()) {
            runCatching { realmStatus.close() }
        }
        // the monitor never outlives the model.
        runCatching { lanInterfaceMonitor.stop() }
        // Remote accounts: the provisioning socket dies with the app.
        // The cancel JOINS: a collector body already inside
        // onRuntimeChanged has no suspension points, so cancel alone
        // cannot stop it — without the join it could re-bind the
        // provisioning socket after controller.close() stopped it. (The
        // controller's closed-flag is the second belt: an Android-side
        // scope.cancel() has no joinable thread either.)
        runCatching {
            friendAccessWatcher.cancel()
            kotlinx.coroutines.runBlocking { friendAccessWatcher.join() }
        }
        // The init-block addon warm-up dies with the model too (the
        // friendAccessWatcher posture beside it): the retained job is
        // cancelled so the fresh-install seed cannot run against
        // torn-down roots after close().
        runCatching { addonRepositoryWarmUp?.cancel() }
        friendAccessInstance?.let { controller -> runCatching { controller.close() } }
        // never leave the bridge injecting across teardown.
        runCatching { gamepadBridge.stop() }
        supervisor.close()
        // Released last: the single-instance lock guards the whole model.
        instanceLock.close()
    }

    companion object {
        /** The supervisor launch profile id (not the bot profile — the bot
         * selection rides the settings snapshot into world start). */
        const val DESKTOP_PROFILE = "local"

        /** join-verb budgets: the READY wait (the gateway dial + the
         * loopback binds) and its poll grain; the persisted transport
         * identity's byte/hex lengths; the READY log line's node prefix. */
        const val JOIN_READY_TIMEOUT_MS = 45_000L

        /** The join refusal when no game folder of the game's version is
         * known; the join screen asks for one when it sees this. */
        const val JOIN_NEEDS_GAME_FOLDER = "Choose your game folder for this version to join."
        const val JOIN_READY_POLL_MS = 200L
        /** The relay's permanent-ban duration convention (sign-up removal). */
        const val PERMANENT_BAN_DURATION = "0"
        /** This install's random id (the device secret's fallback). */
        const val DEVICE_INSTALL_ID_FILE = "device-install-id"
        const val JOIN_READY_TIMEOUT_SECONDS_DIVISOR = 1_000L
        const val IROH_SECRET_HEX_LENGTH = 64
        const val IROH_SECRET_BYTES = 32
        const val IROH_LOG_NODE_CHARS = 12

        /** Phases a start is accepted in (the shared supervisor's private
         * STARTABLE set, mirrored for the join auto-stop decision: a join
         * while any other phase is up stops the runtime first). */
        private val STARTABLE_PHASES = setOf(
            RuntimePhase.STOPPED,
            RuntimePhase.UNCONFIGURED,
            RuntimePhase.ERROR,
        )

        private const val TAG = "AppModel"
        private const val FRIEND_ACCESS_STORE_DIR = "friend-access"
        private const val FRIEND_ACCESS_LOG_TAG = "FriendAccess"
        private const val TAG_AUTO_LOGIN = "AutoLogin"

        // F7: the guest-ban engine-relay lane's fixed strings. The audit
        // actions are the rows the SQLITE lane's store and the Players
        // moderation verbs write (FriendInviteStore.ACTION_BANNED et al. —
        // hardcoded here like DesktopGuestBans does; the store's consts are
        // internal to the android build's own compilation).
        internal const val RELAY_PERMANENT_DURATION = "0"
        internal const val RELAY_NOT_READY = "world-not-ready"
        private const val RELAY_AUDIT_BANNED = "friend-banned"
        private const val RELAY_AUDIT_UNBANNED = "friend-unbanned"
        private const val RELAY_BAN_ROW_REASON = "banned on the live realm"

        /** the [com.pocketrealm.client.ActiveFlavor] lane suffix
         * naming the active-generation gate. */

        /** The READ-ONLY proof outcomes that ARE an authoritative world
         * answer about the stored identity. Everything else (world not
         * ready or not owned by this session, control-channel failure,
         * generic rejection — and the provision-only ACCOUNT_CREATED,
         * which the proof leg can no longer produce) is transport-class,
         * never grounds to reject a stored credential. */
        private val AUTHORITATIVE_ANSWER_CODES = setOf(
            "ACCOUNT_VERIFIED",
            "ACCOUNT_PASSWORD_MISMATCH",
            "ACCOUNT_UNKNOWN",
        )

        /**
         * DESK-57's disposition table (pure; pinned by
         * DesktopAutoLoginProofTest): the world's READ-ONLY answer for the
         * stored identity feeds the shared
         * [AutoLoginCredentialProof.evaluate] so the desktop and Android
         * share one acceptance rule — and one consequence rule: the record
         * is never destroyed by a realm proof.
         *
         * Leg mapping (documented contract, not inference):
         * - ACCOUNT_VERIFIED        -> answered; password proven; row
         *                             exists. ACCEPT only when the
         *                             answer's id/gm still describe the
         *                             stored record.
         * - ACCOUNT_PASSWORD_MISMATCH -> answered; password REFUTED; row
         *                             exists -> INVALID_CREDENTIAL
         *                             (record kept, both platforms).
         * - ACCOUNT_UNKNOWN         -> the world never knew the identity
         *                             (the read-only answer; no row is
         *                             fabricated) -> accountExists=false
         *                             -> INVALID_CREDENTIAL (record
         *                             kept; one manual create re-syncs).
         * - anything else           -> not authoritative ->
         *                             responseOk=false ->
         *                             AUTHORITY_UNAVAILABLE (keep).
         * A missing session/world owner never grounds a rejection either.
         */
        internal fun credentialProofDisposition(
            storedAccountId: Long,
            storedGmLevel: Int,
            expectedOwner: ComponentOwner?,
            reportedOwner: ComponentOwner?,
            provision: AccountProvisionResult,
        ): AutoLoginCredentialProof.Disposition {
            if (expectedOwner == null || reportedOwner == null) {
                return AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE
            }
            val answered = provision.code in AUTHORITATIVE_ANSWER_CODES
            return AutoLoginCredentialProof.evaluate(
                expectedOwner = expectedOwner,
                expectedAccountId = storedAccountId,
                expectedGmLevel = storedGmLevel,
                responseOk = answered,
                passwordVerified = answered && provision.code !in
                    setOf("ACCOUNT_PASSWORD_MISMATCH", "ACCOUNT_UNKNOWN"),
                accountExists = answered && provision.code != "ACCOUNT_UNKNOWN",
                accountId = provision.accountId,
                gmLevel = provision.gmLevel,
                ownerSessionId = reportedOwner.sessionId,
                ownerInstanceToken = reportedOwner.instanceToken,
            )
        }
    }
}

/**
 * F7: render a suspension duration in the engine relay's duration shape —
 * TimeStringToSecs (Util.cpp:234) parses `<number><unit>` runs with units
 * d/h/m/s ONLY, and a bare digit run with no trailing unit parses to ZERO
 * (the pending buffer is discarded at end-of-string) — and a zero duration
 * is the console verb's PERMANENT ban. So the render is NEVER unit-less
 * ("3600" would ban the guest forever) and never uses the wider unit
 * letters the relay's input gate admits but the parser zero-pads ('w'/'M'/
 * 'y' hit the parser's bad-format return). Bounded to the relay's 8-char
 * input gate by dropping the smallest components, which rounds the lift
 * EARLIER, never later.
 */
internal fun renderRelayBanDuration(durationSeconds: Long): String {
    require(durationSeconds > 0) { "suspension duration must be positive" }
    // Decompose largest-unit-first (the parser accumulates per-unit, so
    // the order is presentation only)…
    val components = ArrayList<String>(RELAY_DURATION_UNITS.size)
    var remaining = durationSeconds
    for ((unitSeconds, unit) in RELAY_DURATION_UNITS) {
        val count = remaining / unitSeconds
        if (count > 0L) {
            components += "$count$unit"
            remaining -= count * unitSeconds
        }
    }
    check(components.isNotEmpty()) { "a positive duration always renders at least its seconds" }
    // …then fill the relay's input gate until the next component would
    // overflow it (dropping the smallest units rounds the lift earlier).
    val rendered = StringBuilder()
    for (component in components) {
        if (rendered.length + component.length > RELAY_DURATION_MAX_CHARS) break
        rendered.append(component)
    }
    // An absurd duration whose LARGEST component alone overflows the gate
    // (a >8-char day count — millennia) is returned verbatim: truncating
    // the number would mint a different duration, and the engine's own
    // input gate refuses it honestly (invalid-duration).
    return if (rendered.isEmpty()) components.first() else rendered.toString()
}

/** The renderer's decomposition, largest unit first (the parser
 * accumulates per-unit, so order is presentation only). */
@Suppress("MagicNumber") // 86_400/3_600/60 ARE the d/h/m definitions — naming them adds nothing
private val RELAY_DURATION_UNITS = listOf(
    86_400L to 'd',
    3_600L to 'h',
    60L to 'm',
    1L to 's',
)

/** The engine relay's own duration input gate (world_runtime.cpp
 * ban_account: `duration.size() > 8` is the invalid-duration refusal). */
private const val RELAY_DURATION_MAX_CHARS = 8
