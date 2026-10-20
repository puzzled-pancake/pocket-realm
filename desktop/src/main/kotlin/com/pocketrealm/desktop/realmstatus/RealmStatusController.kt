package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.TimeframeStages
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.Inputs
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.JoinKind
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.PlayerSource
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.StatusSource
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.TunnelState
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.WorldCounts
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimeSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Realm-status browser (design realm-status-browser.md): the host-side
 * orchestrator — the visibility machine's live instance over the app
 * model. Owns the page server, the page-only cloudflared child, the
 * orphan gate, the telemetry capture, and the settings verbs.
 *
 * The ONE lifecycle rule is structural here: NOTHING in the
 * supervisor-state watcher touches the page session except the uptime
 * record fold — a realm stop (user Save & exit, interface loss, crash
 * recovery) leaves the page up serving the graceful down state; the page
 * dies only when the switch goes OFF, the app exits, the server hits an
 * unrecoverable bind failure, or the supervisor process dies.
 */
@Suppress("TooManyFunctions") // the orchestrator seam: one verb per surface, per the app-model idiom
internal class RealmStatusController(
    private val model: DesktopAppModel,
    /** The process-table override — the desktop suite's unit-test
     * seam (the GAP-3 internal idiom); production passes nothing and
     * gets the bounded PowerShell probe. */
    tableOverride: TunnelOrphanSweep.ProcessTable? = null,
) {

    /** What the hosting card renders (one StateFlow; no event lost). */
    data class UiState(
        val visibility: RealmStatusVisibility = RealmStatusVisibility.OFF,
        val publicArmed: Boolean = false,
        val refusalCopy: String? = null,
        val refusalReason: RefusalReason? = null,
        val orphanPid: Long? = null,
        val tunnel: CloudflaredPageTunnel.TunnelStateReport =
            CloudflaredPageTunnel.TunnelStateReport(TunnelState.DOWN, null, null),
    )

    private val roots = model.roots

    private val records = TunnelChildRecords(
        File(roots.realm, "realm-status/tunnel-records.json"),
    )

    private val uptimeRecord = RealmUptimeRecord(File(roots.realm, "realm-status/uptime-record.json"))

    private val tunnel = CloudflaredPageTunnel(
        records = records,
        configuredPath = { model.settings.value.cloudflaredPath },
        logLine = { com.pocketrealm.desktop.DesktopLog.i(TAG, it) },
    )

    /** The live limiter — re-keyed from the settings test hook at
     * every transition that starts the server. */
    @Volatile private var limiter = newLimiter()

    @Suppress("MagicNumber") // the hook's bounds (1..10_000 req/min) are the reviewed envelope
    private fun newLimiter(): RealmStatusRateLimiter = RealmStatusRateLimiter(
        perVisitorPerMinute = model.settings.value.pageRateLimitPerMinute.coerceIn(1, 10_000),
        globalPerMinute = GLOBAL_LIMIT_PER_MINUTE,
        hostPerMinute = HOST_LIMIT_PER_MINUTE,
    )

    private val server: RealmStatusServer = RealmStatusServer(
        captureRender = { hostView -> captureRender(hostView) },
        servedGeneration = { server.currentGeneration() },
        visibilityAtEntry = { _uiState.value.visibility },
        limiterProvider = { limiter },
        logLine = { com.pocketrealm.desktop.DesktopLog.i(TAG, it) },
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The gate: held while a lost-record orphan is unacknowledged. */
    @Volatile private var orphanGatePid: Long? = null

    /** §5.3: the pids the host has EXPLICITLY acknowledged this app
     * lifetime — the accepts-the-exposure ack is per-pid, so a sweep that
     * re-detects an acknowledged pid does not re-trip the gate; only a NEW
     * record-less orphan does. */
    private val acknowledgedOrphanPids: MutableSet<Long> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The instance id (the sweep's liveness oracle + record ownership). */
    private val instanceId = java.util.UUID.randomUUID().toString()

    private val appStartUnixSeconds: Long = System.currentTimeMillis() / MS_PER_SECOND

    /** The process-table seam: the test override when given, else the
     * production Windows enumeration over cloudflared processes
     * (bounded PowerShell probe). */
    private val processTable: TunnelOrphanSweep.ProcessTable = tableOverride
        ?: object : TunnelOrphanSweep.ProcessTable {
            override fun cloudflaredProcesses(): List<TunnelOrphanSweep.ProcessEntry> =
                powerShellCloudflaredProcesses()
        }

    private val sweep = TunnelOrphanSweep(
        records = records,
        table = processTable,
        currentInstanceId = instanceId,
        appStartUnixSeconds = appStartUnixSeconds,
        isOwnLiveChild = { pid -> tunnel.isOwnLiveChild(pid) },
        killProcess = { pid -> killProcessByPid(pid) },
        logWarning = { com.pocketrealm.desktop.DesktopLog.w(TAG, it) },
    )

    /** The roster cache (re-read at most once per supervisor poll). */
    @Volatile private var rosterCache: Pair<Long, List<String>> = 0L to emptyList()

    /** The world-data cache (once per generation, keyed by pointer
     * identity — the envelope gate re-runs only on a real change). */
    @Volatile private var worldCache: WorldCacheEntry? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // The uptime record fold — the watcher's ONLY page-relevant act
        // (the §1 rule: realm stops never touch the visibility session).
        scope.launch {
            var wasWorldUp = false
            model.supervisor.state.collect { snapshot ->
                val up = isWorldUp(snapshot)
                if (wasWorldUp && !up) foldUptimeOnStop(snapshot)
                wasWorldUp = up
            }
        }
        // The tunnel report mirrors into the card state.
        scope.launch {
            tunnel.state.collect { report ->
                _uiState.value = _uiState.value.copy(tunnel = report)
            }
        }
    }

    /** The app-launch leg (boot semantics) a persisted FRIENDS
     * restores; a persisted PUBLIC re-arms WITHOUT auto-resuming. Also
     * runs the §5.3 sweep (every app start sweeps) and the §7 unclean-stop
     * recovery fold (the crash-terminated session credits the record). */
    fun boot() {
        runSweepAndGate()
        runCatching {
            uptimeRecord.foldUncleanFromSnapshot(
                com.pocketrealm.desktop.DesktopSupervisorJournal(roots.supervisorJournalDir).read(),
            )
        }
        val outcome = RealmVisibilityMachine.bootOutcome(model.settings.value.realmStatusVisibility)
        // The SERVED state stays OFF until the restore actually binds: a
        // boot-time bind failure degrades to OFF with the honest copy on
        // the card — never a half-start, never a phantom FRIENDS label on
        // a dead listener. Only the armed WISH is recorded up front.
        _uiState.value = _uiState.value.copy(
            visibility = RealmStatusVisibility.OFF,
            publicArmed = outcome.publicArmed,
        )
        when {
            outcome.restored == RealmStatusVisibility.FRIENDS -> {
                // No tunnel child, no new consent (the reachability is
                // unchanged); applyTransition flips the served state only
                // after the bind succeeds.
                applyTransition(RealmStatusVisibility.FRIENDS, bootRestore = true)
            }
            outcome.publicArmed -> {
                com.pocketrealm.desktop.DesktopLog.i(
                    TAG,
                    "public showcase was on — re-armed, not resumed (the consent flip is required)",
                )
            }
        }
    }

    /** The visibility switch verb. Refusals keep the prior state and land
     * the honest copy on the card (§4). */
    fun setVisibility(target: RealmStatusVisibility) {
        synchronized(transitionLock) {
            _uiState.value = _uiState.value.copy(refusalCopy = null, refusalReason = null)
            val verdict = RealmVisibilityMachine.transition(
                current = _uiState.value.visibility,
                target = target,
                hostingMode = model.supervisor.state.value.runtimeMode,
                bindAllowed = true, // the socket gate is enforced at apply time
                crossCheckOk = computeCrossCheckOk(),
                orphanGateClear = orphanGatePid == null,
            )
            when (verdict) {
                is RealmTransitionVerdict.Allowed ->
                    if (verdict.rebind || verdict.next != _uiState.value.visibility) {
                        applyTransition(verdict.next)
                    }
                is RealmTransitionVerdict.Refused ->
                    _uiState.value = _uiState.value.copy(
                        refusalCopy = verdict.copy,
                        refusalReason = verdict.reason,
                    )
            }
        }
    }

    /** §6.3: the MOTD write — clamped AT THE SETTINGS WRITE PATH. */
    fun setRealmDescription(raw: String) {
        model.updateSettings { it.copy(realmDescription = RealmStatusPayload.clampDescription(raw)) }
    }

    /** §6.3: the roster consent toggle (payload gate; §3.4). */
    fun setShowCharacterNames(enabled: Boolean) {
        model.updateSettings { it.copy(showCharacterNames = enabled) }
    }

    fun setCloudflaredPath(path: String) {
        model.updateSettings { it.copy(cloudflaredPath = path.trim()) }
    }

    /** The limiter test hook: persists; the next transition that
     * starts the server re-keys the limiter from the stored value. */
    @Suppress("MagicNumber") // the hook's bounds (1..10_000 req/min) are the reviewed envelope
    fun setPageRateLimitPerMinute(limit: Int) {
        model.updateSettings { it.copy(pageRateLimitPerMinute = limit.coerceIn(1, 10_000)) }
    }

    /** §5.3: clear the orphan notice WITHOUT adopting (the host stopped
     * the process out-of-band, or accepts the exposure). The ack is
     * remembered PER-PID for this app lifetime: the next sweep that still
     * sees THIS pid leaves the gate clear (the accepts-the-exposure case);
     * only a NEW record-less orphan re-trips it. */
    fun acknowledgeOrphan() {
        synchronized(transitionLock) {
            orphanGatePid?.let { acknowledgedOrphanPids.add(it) }
            orphanGatePid = null
            _uiState.value = _uiState.value.copy(orphanPid = null)
        }
    }

    /** §5.3: adopt the orphan into THIS instance's records under the same
     * kill rules (the gate clears; the child becomes our own). */
    fun adoptOrphan() {
        synchronized(transitionLock) {
            val pid = orphanGatePid ?: return
            val entry = powerShellCloudflaredProcesses()
                .firstOrNull { it.pid == pid }
            if (entry != null) {
                sweep.adopt(
                    TunnelOrphanSweep.ProcessEntry(
                        pid = entry.pid,
                        commandLine = entry.commandLine,
                        startUnixSeconds = entry.startUnixSeconds,
                    ),
                )
            }
            orphanGatePid = null
            _uiState.value = _uiState.value.copy(orphanPid = null)
        }
    }

    /** The sweep + gate state (runs at boot and before every
     * visibility transition that binds 18080). */
    private fun runSweepAndGate() {
        when (val outcome = sweep.sweep()) {
            is TunnelOrphanSweep.Outcome.OrphanDetected -> {
                // a pid the host explicitly acknowledged (the
                // accepts-the-exposure case — the process is still live on
                // purpose) does not re-trip the gate; only an
                // UNacknowledged orphan holds it.
                if (outcome.pid !in acknowledgedOrphanPids) {
                    orphanGatePid = outcome.pid
                    _uiState.value = _uiState.value.copy(orphanPid = outcome.pid)
                    com.pocketrealm.desktop.DesktopLog.w(
                        TAG,
                        "orphaned page tunnel detected: pid ${outcome.pid} (record-less)",
                    )
                }
            }
            is TunnelOrphanSweep.Outcome.OwnLeftoverKilled ->
                com.pocketrealm.desktop.DesktopLog.i(
                    TAG,
                    "orphan sweep: own-record leftover killed ${outcome.pids}",
                )
            TunnelOrphanSweep.Outcome.Clear -> Unit
        }
    }

    /** The machine's Allowed applied to the real sockets — serialized,
     * rebind ordered (§4): stop old server → drain → bind new → flip. */
    @Suppress("ReturnCount", "MagicNumber") // the refusal exits keep the PRIOR state; 10_000 is the hook's cap
    private fun applyTransition(
        target: RealmStatusVisibility,
        bootRestore: Boolean = false,
    ) {
        val prior = _uiState.value.visibility
        if (target != RealmStatusVisibility.OFF) {
            runSweepAndGate()
            // the sweep that runs before a binding transition gates
            // THAT transition — an orphan that appeared after the machine
            // read its (then-clear) input refuses HERE, not one sweep
            // late. Nothing has been torn down yet, so the prior listener
            // keeps serving untouched; only the refusal lands (a refused
            // boot restore keeps its OFF prior, exactly like a boot bind
            // conflict).
            if (orphanGatePid != null) return orphanGateRefusal(prior)
        }
        // the settings test hook re-keys the limiter for this session.
        limiter = newLimiter()
        // Stop old (bounded drain inside).
        server.stop()
        tunnel.stop()
        if (target == RealmStatusVisibility.OFF) {
            _uiState.value = _uiState.value.copy(
                visibility = RealmStatusVisibility.OFF,
                publicArmed = false,
                refusalCopy = null,
                refusalReason = null,
            )
            persistVisibility(RealmStatusVisibility.OFF)
            return
        }
        val outcome = server.start(bindWildcard = target == RealmStatusVisibility.FRIENDS)
        when (outcome) {
            is RealmStatusServer.StartOutcome.BindConflict ->
                // The refusal EXITS the transition: no tunnel child may
                // spawn and no served-state flip may follow a refusal
                // (the fix-round regression the review caught — without
                // the return, a refused PUBLIC fell through to
                // tunnel.start() + the PUBLIC flip over the refusal).
                return bindFailureRefusal(prior, REFUSAL_BIND)
            is RealmStatusServer.StartOutcome.Failed ->
                return bindFailureRefusal(prior, "The realm-status page could not start (${outcome.detail}).")
            RealmStatusServer.StartOutcome.Started -> Unit
        }
        if (target == RealmStatusVisibility.PUBLIC) {
            // a missing binary lands IN PUBLIC with tunnelState
            // binary-missing, url null, and the failure block rendered —
            // the pinned state-machine consequence (T15).
            tunnel.start()
        }
        _uiState.value = _uiState.value.copy(
            visibility = target,
            publicArmed = false, // the armed wish is consumed by the flip
            refusalCopy = null,
            refusalReason = null,
        )
        persistVisibility(target)
        if (bootRestore) {
            com.pocketrealm.desktop.DesktopLog.i(TAG, "friends page session restored (no tunnel child)")
        }
    }

    /**
     * The §5.3 apply-time orphan refusal: the fresh sweep that gates
     * every binding transition refused THIS one. Unlike
     * [bindFailureRefusal] the §4 teardown has not run yet — the prior
     * listener (if any) never stopped, so the prior state keeps serving
     * untouched and only the copy/reason land on the card.
     */
    private fun orphanGateRefusal(prior: RealmStatusVisibility) {
        _uiState.value = _uiState.value.copy(
            visibility = prior,
            refusalCopy = RealmVisibilityMachine.ORPHAN_PENDING_COPY,
            refusalReason = RefusalReason.ORPHAN_PENDING,
        )
        persistVisibility(prior)
    }

    /**
     * The §4 bind-failure refusal: visibility stays at its PRIOR state —
     * which must keep SERVING. The §4 rebind ordering stopped the prior
     * lane's listener before the new bind was attempted, so "stays prior"
     * means REBINDING the prior lane; a prior rebind that also fails
     * degrades to OFF (persisted) rather than leaving a phantom state
     * label on a dead listener. The copy names the port, no pid, no path.
     */
    private fun bindFailureRefusal(prior: RealmStatusVisibility, copy: String) {
        val restored = if (prior != RealmStatusVisibility.OFF) {
            when (server.start(bindWildcard = prior == RealmStatusVisibility.FRIENDS)) {
                is RealmStatusServer.StartOutcome.Started -> prior
                else -> RealmStatusVisibility.OFF
            }
        } else {
            RealmStatusVisibility.OFF
        }
        _uiState.value = _uiState.value.copy(
            visibility = restored,
            refusalCopy = copy,
            refusalReason = RefusalReason.BIND_CONFLICT,
        )
        persistVisibility(restored)
    }

    /** The persisted switch (the boot semantics' input for the NEXT run). */
    private fun persistVisibility(target: RealmStatusVisibility) {
        model.updateSettings { it.copy(realmStatusVisibility = target) }
    }

    // ------------------------------------------------------------------
    // The telemetry capture: ONE snapshot read per request.
    // ------------------------------------------------------------------

    @Suppress("LongMethod", "MagicNumber")
    // the capture is the field table read in order from ONE snapshot —
    // splitting it would pass state fragments around and invite a torn read.
    // internal (not private) is the desktop suite's unit-test seam —
    // the two-qualified-flavors lane pins read the rendered payload here.
    internal fun captureRender(hostView: Boolean): RealmStatusServer.CapturedRender? {
        val snapshot = model.supervisor.state.value // ONCE
        val settings = model.settings.value // the clamped stored set
        val visibility = _uiState.value.visibility
        val generatedAt = System.currentTimeMillis()
        val worldUp = isWorldUp(snapshot)
        val degraded = false // the desktop reads its own StateFlow: never stale
        val statusSource = if (degraded) StatusSource.DEGRADED else StatusSource.LIVE
        val providerMariaDb = activeProviderIsMariaDB()

        // The players block (fail-closed split; the world-down zeros
        // are structural).
        val players = buildPlayers(worldUp, settings, providerMariaDb)

        // the lane this page NAMES — the journaled session lane while
        // a world runs, else the ACTIVE lane (see [pageFlavorId]).
        val pageFlavor = pageFlavorId(snapshot, worldUp)

        // The conf-resolved facts (start-frozen stage, rates, bot target).
        val conf = readActiveConf(worldUp)
        val stage = resolveStage(pageFlavor, conf?.timeframeStageId)

        val world = readWorldBlock()

        val advertised = snapshot.advertisedAddress?.takeIf { it.isNotBlank() }
        val joinKind = when {
            visibility == RealmStatusVisibility.PUBLIC -> JoinKind.NONE
            advertised != null -> JoinKind.TUNNEL_INVITE
            else -> JoinKind.LAN_INVITE
        }
        // the payload is address-free in the plain-LAN lane; the LAN
        // address renders ONLY in the page's join section, and in
        // PUBLIC it appears NOWHERE (T18c).
        val pageLanAddress = when {
            visibility != RealmStatusVisibility.FRIENDS || advertised != null -> null
            else -> snapshot.realmEndpoint.address
        }
        // The roster rides inside buildPlayers (§3.4's gates live there:
        // running world + consent + SQLite provider — never in the down
        // state, where crash-stale online flags would list dead sessions).

        val tunnelReport = tunnel.state.value
        val inputs = Inputs(
            visibility = visibility,
            generatedAtWallMs = generatedAt,
            realmName = if (providerMariaDb) null else model.backend.projectedRealmName(),
            flavor = pageFlavor,
            timeframeStageId = stage.first,
            timeframeTitle = stage.second,
            timeframeIsFinal = stage.third,
            description = settings.realmDescription,
            worldRunning = worldUp,
            worldStartedAtWallMs = snapshot.components[RuntimeComponent.WORLD]?.startedAtWallMs,
            recordSeconds = uptimeRecord.recordSeconds(),
            rates = conf?.rates ?: RealmStatusPayload.Rates.notRunning(),
            players = players,
            worldPrepared = world.prepared,
            worldGeneration = world.generationPrefix,  // the 8-char prefix rides the cache
            worldCounts = world.counts,
            statusSource = statusSource,
            phase = snapshot.phase.name,
            mode = snapshot.runtimeMode.name,
            clean = snapshot.clean,
            advertisedAddress = advertised,
            joinKind = joinKind,
            publicUrl = tunnelReport.url,
            tunnelState = tunnelReport.state,
            characterNamesInPublic = settings.showCharacterNames,
        )
        return RealmStatusServer.CapturedRender(
            generation = server.currentGeneration(),
            payload = RealmStatusPayload.build(inputs),
            hostView = hostView,
            pageLanAddress = pageLanAddress,
        )
    }

    @Suppress("LongMethod", "ComplexCondition", "MagicNumber")
    // the disposition ladder in order: down → lane-unreadable →
    // online-lane-lost → cross-check-failed → native; one reviewed table
    private fun buildPlayers(
        worldUp: Boolean,
        settings: Settings.Snapshot,
        providerMariaDb: Boolean,
    ): RealmStatusPayload.Players {
        if (!worldUp) {
            // the down zeros are STRUCTURAL — no world, no players, no
            // bot-status lane read; labeled world-down, never "native".
            return RealmStatusPayload.Players(
                source = PlayerSource.WORLD_DOWN,
                crossCheckOk = true, // vacuous: no live lanes to disagree
                humansOnline = 0,
                botsOnline = 0,
                totalOnline = 0,
                botTarget = readActiveConf(worldUp = false)?.botTarget,
            )
        }
        // The two INDEPENDENT native lanes + the cross-check lane.
        val bot = runCatching { com.pocketrealm.server.WorldNative.botStatusNative() }
            .getOrElse { LongArray(0) }
        val laneReadable = bot.size == BOT_STATUS_SIZE &&
            bot.isNotEmpty() && bot[0] == com.pocketrealm.server.ServerRuntimeContract.ABI_VERSION
        val onlinePlayers = runCatching {
            com.pocketrealm.server.WorldNative.onlinePlayersNative()
        }.getOrDefault(-1)
        val activeSessions = runCatching { model.backend.worldTelemetry() }
            .getOrNull()?.optLong("activeSessions", -1L) ?: -1L
        val humans = if (laneReadable) {
            bot[BOT_STATUS_REAL_PLAYERS].toInt().coerceAtLeast(0)
        } else {
            0
        }
        val total = onlinePlayers.coerceAtLeast(0)
        val bots = (total - humans).coerceAtLeast(0)
        val botTarget = readActiveConf(worldUp = true)?.botTarget
        val botDelta = (botTarget ?: 0).coerceAtLeast(0)
        return when {
            !laneReadable -> RealmStatusPayload.Players(
                source = PlayerSource.DEGRADED,
                crossCheckOk = crossCheckHolds(total, activeSessions, botDelta),
                botTarget = botTarget,
            )
            onlinePlayers < 0 -> RealmStatusPayload.Players(
                source = PlayerSource.DEGRADED,
                crossCheckOk = false,
                botTarget = botTarget,
            )
            // the cross-check failing labels the lane — and blocks
            // the PUBLIC enable (the machine gate reads computeCrossCheckOk).
            !crossCheckHolds(total, activeSessions, botDelta) -> RealmStatusPayload.Players(
                source = PlayerSource.CROSS_CHECK_FAILED,
                crossCheckOk = false,
                botTarget = botTarget,
            )
            else -> RealmStatusPayload.Players(
                source = PlayerSource.NATIVE,
                crossCheckOk = true,
                humansOnline = humans,
                botsOnline = bots,
                totalOnline = total,
                botTarget = botTarget,
                characterNames = if (settings.showCharacterNames && !providerMariaDb) {
                    readRosterCached()
                } else {
                    emptyList()
                },
            )
        }
    }

    /** The cross-check invariant: totalOnline agrees with the world
     * telemetry's activeSessions lane WITHIN the bot-population delta —
     * either counting semantics (bots-in-sessions or humans-only) passes;
     * a disagreement beyond the whole bot population means a lane is lying.
     * (The exact counting-semantics pin is §10 item 1 — this bound is the
     * fail-closed reading until that pin lands.) */
    private fun crossCheckHolds(totalOnline: Int, activeSessions: Long, botDelta: Int): Boolean {
        if (activeSessions < 0) return false
        val delta = kotlin.math.abs(totalOnline - activeSessions)
        return delta <= botDelta || delta == 0L
    }

    /** The PUBLIC-enable gate's verdict (the machine's input). */
    private fun computeCrossCheckOk(): Boolean {
        val snapshot = model.supervisor.state.value
        if (!isWorldUp(snapshot)) return true // down: vacuous (structural zeros)
        val players = buildPlayers(worldUp = true, model.settings.value, activeProviderIsMariaDB())
        return players.crossCheckOk
    }

    /**
     * GAP-3 (owner census UIUX-3, the desktop status leg): the lane this
     * page NAMES — the journaled session lane (G3-4's truth about what is
     * running) while a world runs; otherwise the Home switcher's ACTIVE
     * lane through [ActiveFlavor], so with two qualified flavors the page
     * names the SELECTED lane instead of silently defaulting to vanilla.
     * The machine's ambiguous-state refusal keeps the fail-soft page
     * contract (§7: an unresolvable lane renders the journaled lane's
     * honest down state, never a broken page).
     */
    internal fun pageFlavorId(snapshot: RuntimeSnapshot, worldUp: Boolean): String =
        if (worldUp) snapshot.flavor
        else runCatching { ActiveFlavor.resolve(STATUS_PAGE_LANE).flavorId }.getOrNull() ?: snapshot.flavor

    /** The world-data block, cached per generation: the cheap
     * pointer read keys the cache; the envelope + counts parse runs only
     * when the generation identity changes.
     *
     * GAP-3: the cache key and pointer read resolve the ACTIVE lane through
     * the flavor machine — the former `qualified().single()` degraded
     * silently at the second qualification (flavor null → empty identity →
     * the world cache never keyed, every request re-reading the envelope);
     * the guarded resolve keeps the fail-soft page contract while honoring
     * the switcher's selection. */
    private fun readWorldBlock(): WorldCacheEntry {
        val flavor = runCatching { ActiveFlavor.resolve(STATUS_PAGE_LANE) }.getOrNull()
        val pointer = flavor?.let {
            File(roots.content, "o11-server/active-${it.flavorId}.json")
        }?.takeIf(File::isFile)
        val identity = runCatching {
            val value = JSONObject(pointer?.readText() ?: return@runCatching null)
            "${value.optString("generation")}:${value.optString("manifestSha256")}"
        }.getOrNull() ?: ""
        worldCache?.let { cached -> if (cached.key == identity && identity.isNotEmpty()) return cached }
        val entry = readWorldBlockSlow(identity)
        if (identity.isNotEmpty()) worldCache = entry
        return entry
    }

    private fun readWorldBlockSlow(key: String): WorldCacheEntry = try {
        val store = com.pocketrealm.server.PreparedDataStore(File(roots.content, "o11-server"))
        val active = store.requireActiveEnvelope()
        val counts = JSONObject(File(active.root, "data-manifest.json").readText())
            .optJSONObject("counts")
        WorldCacheEntry(
            key = key,
            prepared = true,
            generationPrefix = active.generation.take(GENERATION_PREFIX_LENGTH),
            counts = WorldCounts(
                dbc = counts?.optInt("dbc", 0) ?: 0,
                maps = counts?.optInt("maps", 0) ?: 0,
                vmapTrees = counts?.optInt("vmapTrees", 0) ?: 0,
                mmapMaps = counts?.optInt("mmapMaps", 0) ?: 0,
                mmapTiles = counts?.optInt("mmapTiles", 0) ?: 0,
            ),
        )
    } catch (@Suppress("TooGenericExceptionCaught") _: Throwable) {
        // The fail-soft world block: an unreadable envelope renders the
        // honest not-prepared state, never a broken page.
        WorldCacheEntry(key = key, prepared = false, generationPrefix = null, counts = null)
    }

    private data class WorldCacheEntry(
        val key: String,
        val prepared: Boolean,
        val generationPrefix: String?,
        val counts: WorldCounts?,
    )

    /** The roster (§3.4/§6.2): online characters only, cached once per
     * supervisor poll, read under the datadir-quiesce discipline the ban
     * lane uses — a roster read can never extend a backup/datadir swap.
     * The reads are single-value queries (the JNI seam's contract), so the
     * count + indexed-name walk is bounded by the conf's PlayerLimit. */
    private fun readRosterCached(): List<String> {
        val now = System.currentTimeMillis()
        val cached = rosterCache
        if (now - cached.first < ROSTER_CACHE_MS) return cached.second
        val names = runCatching {
            model.backend.withDatadirQuiesced {
                val database = com.pocketrealm.database.DatabaseSqliteControlPlane.databaseFile(
                    roots.sqliteDatadir,
                    com.pocketrealm.server.ServerRuntimeFiles.serverDatabases().characters,
                )
                if (!database.isFile) return@withDatadirQuiesced emptyList()
                com.pocketrealm.database.DesktopSqliteConnection(database.absolutePath).use { db ->
                    val online = db.queryLong("SELECT COUNT(*) FROM characters WHERE online = 1;")
                        ?.toInt()?.coerceIn(0, ROSTER_HARD_CAP) ?: 0
                    (0 until online).mapNotNull { index ->
                        db.queryText(
                            "SELECT name FROM characters WHERE online = 1 " +
                                "ORDER BY name LIMIT 1 OFFSET $index;",
                        )?.takeIf { name ->
                            name.isNotBlank() && name.length <= MAX_CHARACTER_NAME_LENGTH
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
        rosterCache = now to names
        return names
    }

    // ------------------------------------------------------------------
    // The staged conf facts: the ACTIVE run's mangosd.conf — the
    // start-frozen timeframe badge, the effective rates, the bot target.
    // ------------------------------------------------------------------

    private data class ConfFacts(
        val timeframeStageId: String?,
        val rates: RealmStatusPayload.Rates?,
        val botTarget: Int?,
    )

    @Volatile private var confCache: Pair<Pair<String, Long>, ConfFacts>? = null

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "MagicNumber")
    // the conf key table IS the parse (the mangosd.conf `Key = value`
    // grammar is one reviewed loop; each key is one arm)
    private fun readActiveConf(worldUp: Boolean): ConfFacts? {
        if (!worldUp) return null
        val conf = File(roots.runtime, SERVER_RUN_DIR + "/mangosd.conf")
        if (!conf.isFile) return null
        val stamp = conf.lastModified()
        val cached = confCache
        if (cached != null && cached.first == (conf.absolutePath to stamp)) return cached.second
        val facts = runCatching {
            var stageId: String? = null
            var botTarget: Int? = null
            var xpKill: Double? = null
            var xpQuest: Double? = null
            var xpHonor: Double? = null
            conf.readText().lines().forEach { line ->
                val trimmed = line.substringBefore('#').trim()
                val assignment = trimmed.split('=', limit = 2)
                if (assignment.size != 2) return@forEach
                val key = assignment[0].trim()
                val value = assignment[1].trim().trim('"')
                when (key) {
                    TimeframeStages.CONF_KEY -> stageId = value
                    "PocketRealm.BotTarget" -> botTarget = value.toIntOrNull()
                    "Rate.XP.Kill" -> xpKill = value.toDoubleOrNull()
                    "Rate.XP.Quest" -> xpQuest = value.toDoubleOrNull()
                    "Rate.Honor" -> xpHonor = value.toDoubleOrNull()
                }
            }
            ConfFacts(
                timeframeStageId = stageId,
                botTarget = botTarget,
                rates = if (xpKill == null && xpQuest == null && xpHonor == null) {
                    RealmStatusPayload.Rates.engineDefault()
                } else {
                    RealmStatusPayload.Rates(
                        xpKill = xpKill,
                        xpQuest = xpQuest,
                        xpHonor = xpHonor,
                        source = "conf",
                    )
                },
            )
        }.getOrNull()
        if (facts != null) confCache = (conf.absolutePath to stamp) to facts
        return facts
    }

    /** The start-frozen stage: the ACTIVE conf's stage id through the
     * shared registry — NEVER the settings' next-start wish. */
    private fun resolveStage(
        flavorId: String,
        confStageId: String?,
    ): Triple<String, String, Boolean> {
        val stage = confStageId?.let { TimeframeStages.byId(flavorId, it) }
            ?: TimeframeStages.byId(flavorId, TimeframeStages.defaultStageId(flavorId))
        return if (stage == null) {
            Triple("", "", true)
        } else {
            Triple(stage.id, stage.title, stage.isFinal)
        }
    }

    // ------------------------------------------------------------------
    // The helpers.
    // ------------------------------------------------------------------

    private fun isWorldUp(snapshot: RuntimeSnapshot): Boolean =
        snapshot.components[RuntimeComponent.WORLD]?.state in WORLD_UP_STATES

    /** its producer: on stop, fold `now − startedAtWallMs` (the world's
     * pinned start) into the durable record. */
    private fun foldUptimeOnStop(snapshot: RuntimeSnapshot) {
        val world = snapshot.components[RuntimeComponent.WORLD] ?: return
        val startedAt = world.startedAtWallMs ?: return
        val duration = (System.currentTimeMillis() - startedAt) / MS_PER_SECOND
        uptimeRecord.foldSession(duration)
        com.pocketrealm.desktop.DesktopLog.i(TAG, "uptime record folded: ${duration}s session")
    }

    private fun activeProviderIsMariaDB(): Boolean {
        val marker = com.pocketrealm.database.DatabaseDurableState.parseActiveProviderMarker(
            File(
                roots.sqliteDatadir.parentFile,
                com.pocketrealm.database.DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME,
            ).takeIf(File::isFile)?.readText(),
        )
        return marker?.mode == com.pocketrealm.database.DatabaseDurableState.ProviderMode.MARIADB
    }

    fun close() {
        scope.cancel()
        runCatching { tunnel.stop() }
        runCatching { server.stop() }
    }

    /** The production process table: a bounded PowerShell probe over
     * cloudflared processes (pid + full command line + creation time).
     * Any failure degrades to an EMPTY table — detection never breaks the
     * page; the kill rules never scan on a partial table. */
    @Suppress("ReturnCount", "MagicNumber", "MaxLineLength") // the fail-soft exits are the documented empty-table degrade; seconds are the units
    private fun powerShellCloudflaredProcesses(): List<TunnelOrphanSweep.ProcessEntry> {
        if (!isWindows()) {
            return emptyList()
        }
        return runCatching {
            val script = "Get-CimInstance Win32_Process -Filter \"Name like 'cloudflared%'\" | " +
                "Select-Object ProcessId,CommandLine,@{n='CreatedUnix';" +
                "e={[DateTimeOffset]::new(\$_.CreationDate.ToUniversalTime()).ToUnixTimeSeconds()}} | " +
                "ConvertTo-Json -Compress"
            val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return emptyList()
            }
            parseProcessTableJson(process.inputStream.readBytes().toString(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    @Suppress("MagicNumber") // the Cloudflare edge timestamp is seconds by definition
    private fun parseProcessTableJson(raw: String): List<TunnelOrphanSweep.ProcessEntry> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return emptyList()
        return runCatching {
            if (trimmed.startsWith("[")) {
                val array = org.json.JSONArray(trimmed)
                (0 until array.length()).mapNotNull { index ->
                    processEntry(array.optJSONObject(index))
                }
            } else {
                listOfNotNull(processEntry(JSONObject(trimmed)))
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("ReturnCount") // the guard chain is the JSON shape contract
    private fun processEntry(value: JSONObject?): TunnelOrphanSweep.ProcessEntry? {
        value ?: return null
        val pid = value.optLong("ProcessId", -1L)
        val commandLine = value.optString("CommandLine", "")
        if (pid <= 0 || commandLine.isEmpty()) return null
        return TunnelOrphanSweep.ProcessEntry(
            pid = pid,
            commandLine = commandLine,
            startUnixSeconds = if (value.isNull("CreatedUnix")) null else value.optLong("CreatedUnix"),
        )
    }

    /** The kill leg (own records only reach this) taskkill /F on the
     * verified pid. True when the tool reports success. */
    private fun killProcessByPid(pid: Int): Boolean = runCatching {
        val process = ProcessBuilder("taskkill", "/PID", pid.toString(), "/F", "/T")
            .redirectErrorStream(true)
            .start()
        process.waitFor(KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS) &&
            process.exitValue() == 0
    }.getOrDefault(false)

    companion object {
        private const val TAG = "RealmStatus"

        /** the [ActiveFlavor] lane suffix naming the status page's
         * flavor-resolving gates (the page copy and the world-data block). */
        private const val STATUS_PAGE_LANE = "the realm-status page resolves the qualified lane"

        private fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("windows")

        /** The page limiter's fixed tiers (§6.2 defaults): 60/min per
         * visitor, 300/min global, 120/min host budget. */
        private const val GLOBAL_LIMIT_PER_MINUTE = 300
        private const val HOST_LIMIT_PER_MINUTE = 120

        private const val BOT_STATUS_SIZE = 19
        private const val BOT_STATUS_REAL_PLAYERS = 9

        private const val SERVER_RUN_DIR = "server/run"

        private const val ROSTER_CACHE_MS = 250L

        /** The roster walk's hard cap (the conf's PlayerLimit is 10; this
         * bounds a pathological online flag beyond it). */
        private const val ROSTER_HARD_CAP = 16

        private const val MAX_CHARACTER_NAME_LENGTH = 12

        private const val PS_TIMEOUT_SECONDS = 10L

        /** The ms→s unit constant (the uptime folds' arithmetic). */
        private const val MS_PER_SECOND = 1_000L

        /** its 8-character generation prefix. */
        private const val GENERATION_PREFIX_LENGTH = 8
        private const val KILL_TIMEOUT_SECONDS = 10L

        private val WORLD_UP_STATES = setOf(
            ComponentLifecycle.READY,
            ComponentLifecycle.STARTING,
            ComponentLifecycle.STOPPING,
        )

        /** its bind-conflict copy (also the apply-time refusal's text). */
        private const val REFUSAL_BIND =
            "Port 18080 is already in use by another program, so the " +
                "realm-status page cannot start. Close the other program and try again."

        private val transitionLock = Any()
    }
}
