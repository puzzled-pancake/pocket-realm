package com.pocketrealm.desktop.nostr

import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopLog
import com.pocketrealm.desktop.realmstatus.RealmStatusController
import com.pocketrealm.desktop.realmstatus.RealmStatusVisibility
import com.pocketrealm.storage.Settings
import com.pocketrealm.discovery.nostr.NostrPrivateKey
import com.pocketrealm.discovery.nostr.NostrFilter
import com.pocketrealm.discovery.nostr.RealmListingContent
import com.pocketrealm.discovery.nostr.RealmListingContract
import com.pocketrealm.discovery.nostr.RealmListingEvent
import com.pocketrealm.discovery.nostr.RelayPool
import com.pocketrealm.discovery.nostr.RelayUrls
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * W6-N1 (docs/NOSTR-IROH-BUILD-PLAN.md): the host-side listing heartbeat.
 *
 * The lifecycle contract, fail-closed by construction:
 *  - a listing is published ONLY while ALL of: the host's discovery
 *    switch is ON (a persisted default OFF), the realm-status visibility
 *    is PUBLIC (never while OFF/FRIENDS), and the captured realm-status
 *    payload says the realm is online;
 *  - the content derives from THE realm-status payload builder (the
 *    same JSON the status page serves) — one source of truth; a payload
 *    whose players split is not live skips the beat (bots are never
 *    padded, and a listing is never published on padded numbers);
 *  - while the gates hold, the listing republishes every 5 minutes
 *    ([RealmListingContract.HEARTBEAT_SECONDS]);
 *  - ANY gate drop (switch off, visibility lost, world down) retracts the
 *    listing with a NIP-09 deletion — the graceful-stop path too (the
 *    model's close drives [shutdown]); a crash publishes nothing further
 *    and the 10-minute client freshness expires the stale listing.
 *
 * The realm's Nostr key is generated once at first enable and lives in
 * the settings store; it is an identity, never displayed.
 */
/**
 * One reviewed bundle of everything the suite injects (clock, HTTP
 * client, gate providers, cadences): production passes
 * [Seams.production]; the lifecycle suite passes fixed versions so its
 * evaluations are deterministic and socket-local.
 */
private const val GATE_POLL_MS = 30_000L
private const val PROD_TIMEOUT_SECONDS = 5L
private const val PROD_PING_SECONDS = 30L

/** Production's first-beat wait for the host gateway's READY (the test
 * bundles keep the seam at 0 — a frozen injected clock must never spin). */
private const val PROD_HOST_DESCRIPTOR_WAIT_MS = 5_000L

internal data class Seams(
    val clockMs: () -> Long,
    val client: OkHttpClient,
    val visibilityProvider: () -> RealmStatusVisibility,
    val payloadProvider: () -> JSONObject?,
    val gatePollMs: Long,
    val heartbeatSeconds: Long,
    /**
     * W6-N3: the host gateway's ensure/stop + the descriptor the listing's
     * `join` field carries. Production wires the model's iroh gateway
     * manager (the child-process transport); the lifecycle suite's default
     * bundle leaves them inert (no process, join:null beats — exactly the
     * N1 pins).
     */
    val ensureHostGateway: (secretHex: String) -> Unit = {},
    val stopHostGateway: () -> Unit = {},
    val joinDescriptorProvider: () -> com.pocketrealm.discovery.iroh.IrohJoinDescriptor? = { null },
    /**
     * The first-beat wait for a still-starting gateway, in INJECTED-CLOCK
     * milliseconds. Production gives the endpoint 5s to reach READY; the
     * test bundles keep 0 — a wait measured on a FROZEN test clock would
     * never terminate (the R2 breaker clock only advances when the test
     * bumps it), so the wait itself is a seam.
     */
    val hostDescriptorWaitMs: Long = 0L,
) {
    companion object {
        /** The production bundle: the wall clock, a bounded client, the
         * realm-status controller's own visibility/payload gates, and the
         * frozen cadences; the gateway host rides the SAME gate ladder. */
        internal fun production(
            model: com.pocketrealm.desktop.DesktopAppModel,
            realmStatus: RealmStatusController,
        ): Seams = Seams(
            clockMs = System::currentTimeMillis,
            client = OkHttpClient.Builder()
                .connectTimeout(PROD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pingInterval(PROD_PING_SECONDS, TimeUnit.SECONDS)
                .build(),
            visibilityProvider = { realmStatus.uiState.value.visibility },
            payloadProvider = {
                runCatching { realmStatus.captureRender(hostView = false)?.payload }.getOrNull()
            },
            gatePollMs = GATE_POLL_MS,
            heartbeatSeconds = RealmListingContract.HEARTBEAT_SECONDS,
            ensureHostGateway = { secret -> model.irohGateway.ensureHost(secret) },
            stopHostGateway = { model.irohGateway.stopHost() },
            joinDescriptorProvider = { model.irohGateway.hostDescriptor() },
            hostDescriptorWaitMs = PROD_HOST_DESCRIPTOR_WAIT_MS,
        )

    }
}

// The orchestrator seam: one verb per surface, per the app-model idiom
// (the RealmStatusController precedent).
@Suppress("TooManyFunctions")
internal class NostrListingPublisher(
    private val model: DesktopAppModel,
    private val realmStatus: RealmStatusController,
    /** The injected seams (clock, HTTP client, gate providers, cadences):
     * production passes the default bundle — the lifecycle suite passes
     * fixed versions so its evaluations are deterministic and
     * socket-local (and spawn no gateway process). */
    private val seams: Seams = Seams.production(model, realmStatus),
) {

    private val clockMs get() = seams.clockMs

    data class UiState(
        val enabled: Boolean = false,
        val active: Boolean = false,
        val relays: List<String> = emptyList(),
        val relayReports: List<RelayPool.RelayReport> = emptyList(),
        val listingEventId: String? = null,
        val lastPublishedAtMs: Long? = null,
        val beats: Int = 0,
        val errorCopy: String? = null,
        /** The current beat's write-lane outcome: OK acks vs
         * rejections per relay for [listingEventId]. The 'Live' claim is
         * only honest once at least one relay accepted the frame. */
        val lastBeatOkCount: Int = 0,
        val lastBeatRejectCount: Int = 0,
        val lastBeatOutcomeCopy: String? = null,
        /** R5r2-3: publishing is paused (the live player split went
         * unavailable) — the 'Live' copy must not claim 5-minute
         * republishing while no beats run. */
        val paused: Boolean = false,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** R5-R3-1: the lifecycle serializer — evaluateGates is driven by
     * three independent coroutines; without this mutex two overlapping
     * evaluations can double-build the relay pool (orphaning the loser's
     * sockets) and double-publish beats. */
    private val gateMutex = kotlinx.coroutines.sync.Mutex()

    /** Pool-construction count (the test's seam). */
    internal var poolCreations: Int = 0
        private set

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pool: RelayPool? = null

    private var heartbeatJob: Job? = null

    /** The live pool's reports/acks collectors — tracked so a pool
     * recycle or deactivate cancels BOTH (R4-4: no retired-collector leak
     * per visibility flap). */
    private var reportsCollectorJob: Job? = null

    private var acksCollectorJob: Job? = null

    /** The current beat's per-relay write outcome, by relay URL. */
    private val beatAcks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** The relay set the CURRENT (or most recently retired) pool was built
     * over — the retract carrier uses it so a deletion reaches the relays
     * the listing was actually published to, even after a set edit. */
    private var lastPoolRelays: List<String> = emptyList()

    @Volatile private var publishedEvent: com.pocketrealm.discovery.nostr.NostrEvent? = null

    /** Round-6 R4-r6-2: the host-stop latch — true from an ensure until
     * the next deactivate consumes it; deactivation is idempotent. */
    @Volatile private var hostStopArmed: Boolean = true

    /** Starts the gate watcher. Idempotent. */
    fun boot() {
        if (watcherJob?.isActive == true) return
        watcherJob = scope.launch {
            model.settings.collect { settings ->
                val enabled = settings.nostrDiscoveryEnabled
                val relays = effectiveRelays(settings)
                _uiState.value = _uiState.value.copy(enabled = enabled, relays = relays)
                if (!enabled) {
                    deactivate(retract = _uiState.value.active || publishedEvent != null)
                } else {
                    realmIdentity()
                    runCatching { evaluateGates() }.onFailure { failure ->
                        DesktopLog.w(TAG, "nostr gate evaluation failed: ${failure.message}")
                    }
                }
            }
        }
        // The visibility/world gates change outside the settings flow —
        // poll on the gate grain (production: 30 s; beats stay 5 min).
        watcherTicker = scope.launch {
            while (isActive) {
                delay(seams.gatePollMs)
                runCatching { evaluateGates() }
            }
        }
    }

    private var watcherJob: Job? = null

    private var watcherTicker: Job? = null

    /** The host's switch verb (persists; the watcher does the rest). */
    fun setEnabled(enabled: Boolean) {
        model.updateSettings { it.copy(nostrDiscoveryEnabled = enabled) }
    }

    /** The user-editable relay set (owner decision 4). Fails loud with
     * the VAL copy; the stored list persists only when valid. While
     * publishing is ACTIVE the pool is recycled (R3-6/R4-4): the next
     * beat re-resolves the stored set, so a removed relay stops receiving
     * the host's frames immediately instead of at the next gate drop. */
    fun setRelays(raw: List<String>) {
        val validated = RelayUrls.sanitizeList(raw)
        model.updateSettings { it.copy(nostrRelays = validated) }
        recyclePool()
    }

    /** Tears the current pool down (collectors with it); the next beat
     * builds a fresh one over the CURRENT stored relay set. */
    private fun recyclePool() {
        reportsCollectorJob?.cancel()
        reportsCollectorJob = null
        acksCollectorJob?.cancel()
        acksCollectorJob = null
        pool?.stop()
        pool = null
        // lastPoolRelays is deliberately KEPT: the retract carrier needs
        // the set the retired pool served.
        _uiState.value = _uiState.value.copy(relayReports = emptyList())
    }

    /**
     * The graceful-stop retract (NIP-09) + teardown: called from the
     * model's close. Bounded — the deletion write gets a short drain, the
     * process never hangs on a dead relay.
     */
    fun shutdown() {
        runCatching {
            publishedEvent?.let { listing ->
                realmIdentity()?.let { key ->
                    val deletion = RealmListingEvent.buildDeletion(
                        listing = listing,
                        key = key,
                        nowSeconds = clockMs() / MILLIS_PER_SECOND,
                    )
                    val delivered = retractThroughPool(deletion)
                    if (delivered) {
                        DesktopLog.i(TAG, "nostr listing retracted (NIP-09) on graceful shutdown")
                    } else {
                        DesktopLog.w(
                            TAG,
                            "nostr NIP-09 retract could not be delivered (no relay pool) — " +
                                "the listing ages out via client-side freshness",
                        )
                    }
                }
            }
        }.onFailure { DesktopLog.w(TAG, "nostr shutdown retract failed: ${it.message}") }
        close()
    }

    /**
     * Publishes the NIP-09 deletion through the live pool — or, when the
     * pool was recycled (a relay edit while active — R4-r2-2/R2B-2),
     * through a fresh pool over the CURRENT stored relay set, so the
     * retract reaches the relays the listing was published to. Returns
     * whether a pool actually carried the frame; the 'retracted' log may
     * only fire when this is true.
     */
    private fun retractThroughPool(deletion: com.pocketrealm.discovery.nostr.NostrEvent): Boolean =
        retractCarrier()?.let { carrier ->
            carrier.publish(deletion)
            val drained = drainRetractCarrier(carrier)
            // A recycled carrier is NOT the cached pool: stop it here (the
            // cached pool's lifecycle stays with deactivate/close). A
            // carrier that failed to drain keeps running until close()
            // reaps the scope — its sockets may still carry the frame.
            if (drained && carrier !== pool) {
                carrier.stop()
            }
            drained
        } ?: false

    /** The live pool, or — when it was recycled — a fresh carrier. */
    private fun retractCarrier(): RelayPool? = pool ?: buildRetractCarrier()

    private fun buildRetractCarrier(): RelayPool? {
        // Prefer the RETIRED pool's relay set — the
        // listing was published THERE, and a just-made edit must not
        // silently re-point the retraction.
        val relays = lastPoolRelays.takeIf { it.isNotEmpty() }
            ?: effectiveRelays(model.settings.value)
        if (relays.isEmpty()) return null
        return RelayPool(
            client = seams.client,
            scope = scope,
            signingKeyProvider = ::realmIdentity,
            nowSeconds = { clockMs() / MILLIS_PER_SECOND },
            log = { DesktopLog.i(TAG, it) },
        ).also { it.start(relays, NostrFilter()) }
    }

    /**
     * Bounded flush: give the sockets a moment to carry the deletion
     * out (a freshly built carrier needs its connect, too). R2-3: a
     * drain timeout means the frame never left — reported to the
     * caller so the loss surfaces, never swallowed.
     */
    private fun drainRetractCarrier(carrier: RelayPool): Boolean {
        var drained = true
        runBlocking {
            try {
                withTimeout(SHUTDOWN_DRAIN_MS) {
                    while (carrier.reports.value.values.any {
                            it.state == RelayPool.RelayState.CONNECTING
                        }
                    ) {
                        delay(SHUTDOWN_POLL_MS)
                    }
                    delay(SHUTDOWN_SETTLE_MS)
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                drained = false
                DesktopLog.w(TAG, "retract drain timed out: ${timeout.message}")
            }
        }
        return drained
    }

    /** Teardown without a retract (the non-graceful paths). */
    fun close() {
        deactivate(retract = false)
        scope.cancel()
    }

    // --------------------------------------------------------- internals

    /** One evaluation's verdict — the gate ladder in review order. */
    private sealed class Gates {
        /** All gates green: publish this content when the beat is due. */
        class Publish(val content: RealmListingContent) : Gates()

        /** A gate dropped: retract any live listing. */
        object Retract : Gates()

        /** Green but not publishable this instant (the beat is not due —
         * never a padded listing). */
        object Skip : Gates()

        /** R5r2-3: the players split went non-native WHILE a listing was
         * live — publishing pauses (no padded beats), and the host card
         * must say so instead of claiming 5-minute republishing while the
         * listing silently ages out of every browser. */
        object Paused : Gates()
    }

    /**
     * One gate evaluation: publish a beat when everything is green and
     * the beat is due; retract the moment anything drops. Internal: the
     * lifecycle suite drives evaluations against the injected seams
     * instead of waiting on the wall-clock loops.
     */
    internal suspend fun evaluateGates() {
        gateMutex.withLock {
        // a user-typed banned MOTD must not kill the watcher or
        // vanish — the failure lands on the card's error channel and the
        // gate ladder stays alive for the next beat.
        runCatching {
            when (val verdict = readGates()) {
                is Gates.Publish -> publishIfDue(verdict.content)
                Gates.Retract -> deactivate(retract = publishedEvent != null)
                Gates.Paused -> _uiState.value = _uiState.value.copy(paused = true)
                Gates.Skip -> Unit
            }
        }.onFailure { failure ->
            DesktopLog.w(TAG, "nostr gate evaluation failed: ${failure.message}")
            _uiState.value = _uiState.value.copy(
                errorCopy = failure.message ?: "nostr gate evaluation failed",
            )
        }
        }
    }

    /** One relay's OK/rejection for the current beat: the beat's
     * outcome counts drive the honest 'Live' copy. */
    private fun onWriteAck(ack: RelayPool.WriteAck) {
        val beatId = publishedEvent?.idHex ?: return
        if (ack.eventId != beatId) return
        beatAcks[ack.relayUrl] = ack.ok
        val ok = beatAcks.values.count { it }
        val rejected = beatAcks.values.count { !it }
        val outcomeCopy = when {
            ok == 0 && rejected > 0 ->
                "every relay rejected the current listing so far " +
                    "($rejected rejection(s))"
            ok > 0 ->
                "accepted by $ok relay(s)" +
                    (if (rejected > 0) ", rejected by $rejected" else "")
            else -> null
        }
        _uiState.value = _uiState.value.copy(
            lastBeatOkCount = ok,
            lastBeatRejectCount = rejected,
            lastBeatOutcomeCopy = outcomeCopy,
        )
    }

    /** R5-3: the payload → frozen-schema content resolution — a banned
     * realm name/MOTD makes the builder THROW (VAL-NOSTR-CONTENT); the
     * reason surfaces on the card's error channel and the ladder keeps
     * running (fail-closed, never silent). */
    private fun resolveContent(realmOnline: Boolean, payload: JSONObject?): RealmListingContent? {
        if (!realmOnline) return null
        return runCatching { payload?.let { RealmListingContent.fromStatusPayload(it) } }.getOrElse { failure ->
            _uiState.value = _uiState.value.copy(
                errorCopy = failure.message ?: "the listing content was refused",
            )
            DesktopLog.w(TAG, "nostr beat refused: ${failure.message}")
            null
        }
    }

    /** The fail-closed gate ladder. NEVER published while visibility is
     * OFF/FRIENDS; any dropped gate retracts (a non-public realm must not
     * stay discoverable); a payload whose players split is not live
     * skips the beat — bots are never padded, and a stale live listing
     * still expires client-side within the 10-minute freshness window. */
    private fun readGates(): Gates {
        val enabled = model.settings.value.nostrDiscoveryEnabled
        val publicVisibility = seams.visibilityProvider() == RealmStatusVisibility.PUBLIC
        val payload = seams.payloadProvider()
        val realmOnline = payload?.optJSONObject("supervisor")?.optBoolean("realmOnline", false) == true
        val content = resolveContent(realmOnline, payload)
        val beatDue = run {
            val last = _uiState.value.lastPublishedAtMs
            publishedEvent == null || last == null ||
                clockMs() - last >= seams.heartbeatSeconds * MILLIS_PER_SECOND
        }
        return when {
            !enabled || !publicVisibility || !realmOnline -> Gates.Retract
            // R5r2-3: a live listing whose players split went non-native
            // PAUSES (never padded) — the card says so instead of claiming
            // 5-minute republishing while the listing ages out.
            content == null && publishedEvent != null -> Gates.Paused
            content == null -> {
                if (_uiState.value.errorCopy == null) {
                    DesktopLog.i(TAG, "nostr beat skipped: players split not live (no padded listing)")
                }
                Gates.Skip
            }

            !beatDue -> Gates.Skip
            else -> Gates.Publish(content)
        }
    }

    private suspend fun publishIfDue(content: RealmListingContent) {
        val key = realmIdentity() ?: run {
            _uiState.value = _uiState.value.copy(errorCopy = "VAL-NOSTR-KEY: no realm identity")
            return
        }
        val activePool = ensurePool()
        // the host gateway runs under the SAME gate ladder as the
        // listing it serves. Ensure it before the beat; the beat carries
        // the descriptor only when the endpoint actually reached READY (a
        // still-starting gateway publishes join:null — the honest
        // not-joinable listing — and the next beat attaches it).
        runCatching {
            val secret = model.ensureIrohGatewayIdentity()
            if (secret != null) {
                seams.ensureHostGateway(secret)
                // the ensure (RE)ARMS the host-stop
                // latch — deactivate stops the gateway exactly once per
                // armed cycle. Without the latch, the identity settings
                // write re-fires the (unmutexed) settings collector into
                // an extra evaluateGates that can land AFTER a gate drop
                // and take the retract leg again — a second stop that,
                // interleaved with the relaunch beat, kills a
                // freshly relaunched gateway.
                hostStopArmed = true
            }
        }.onFailure {
            DesktopLog.w(TAG, "iroh host gateway ensure failed: ${it.message}")
        }
        val descriptor = awaitHostDescriptorBriefly()
        val event = RealmListingEvent.build(
            content = content.copy(join = descriptor),
            key = key,
            nowSeconds = clockMs() / MILLIS_PER_SECOND,
        )
        activePool.publish(event)
        publishedEvent = event
        beatAcks.clear()
        _uiState.value = _uiState.value.copy(
            active = true,
            paused = false,
            listingEventId = event.idHex,
            lastPublishedAtMs = clockMs(),
            beats = _uiState.value.beats + 1,
            lastBeatOkCount = 0,
            lastBeatRejectCount = 0,
            lastBeatOutcomeCopy = null,
            // a good beat clears any stale failure copy - the
            // card must never render "Live ... VAL-NOSTR-..." contradictions.
            errorCopy = null,
        )
        // The 5-minute republish loop arms with the first beat and rides
        // while the gates hold.
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(seams.heartbeatSeconds * MILLIS_PER_SECOND)
                    runCatching { evaluateGates() }
                }
            }
        }
        val prefix = event.idHex.take(LOG_PREFIX_CHARS)
        DesktopLog.i(TAG, "nostr listing published ($prefix…), beat ${_uiState.value.beats}")
    }

    /**
     * Bounded first-beat wait for the host gateway's READY (the endpoint's
     * relay registration is normally sub-second). The WAIT BUDGET is the
     * [Seams.hostDescriptorWaitMs] seam — measured on the INJECTED clock,
     * so a frozen test clock simply means zero wait, and production's
     * wall clock bounds the wait at 5s. The beat publishes with whatever
     * the gateway state honestly is either way.
     */
    private suspend fun awaitHostDescriptorBriefly(): com.pocketrealm.discovery.iroh.IrohJoinDescriptor? {
        val deadline = clockMs() + seams.hostDescriptorWaitMs
        var descriptor = seams.joinDescriptorProvider()
        while (descriptor == null && clockMs() < deadline) {
            delay(HOST_DESCRIPTOR_POLL_MS)
            descriptor = seams.joinDescriptorProvider()
        }
        return descriptor
    }

    private fun deactivate(retract: Boolean) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        reportsCollectorJob?.cancel()
        reportsCollectorJob = null
        acksCollectorJob?.cancel()
        acksCollectorJob = null
        beatAcks.clear()
        _uiState.value = _uiState.value.copy(paused = false)
        // a gate drop stops the host gateway too — no listing, no
        // discovery surface, no listening endpoint (a public realm's
        // transport exists exactly as long as its listing does).
        // the stop is LATCHED — exactly one stop per armed
        // cycle (the latch rearms only on the next ensure), so the
        // settings-collector's extra evaluation after a drop can never
        // double-stop (and never kill a freshly relaunched gateway).
        if (hostStopArmed) {
            hostStopArmed = false
            runCatching { seams.stopHostGateway() }
        }
        if (retract) {
            runCatching {
                val listing = publishedEvent ?: return
                val key = realmIdentity() ?: return
                // R2B-2: the retract must survive a recycled pool (a relay
                // edit followed by this gate drop) — rebuild the carrier.
                val delivered = retractThroughPool(
                    RealmListingEvent.buildDeletion(
                        listing = listing,
                        key = key,
                        nowSeconds = clockMs() / MILLIS_PER_SECOND,
                    ),
                )
                if (delivered) {
                    DesktopLog.i(TAG, "nostr listing retracted (NIP-09) after a gate drop")
                } else {
                    // the loss is never silent — the host sees why
                    // the listing is still circulating.
                    _uiState.value = _uiState.value.copy(
                        errorCopy = "the NIP-09 retract could not be delivered " +
                            "(no relay connected) — the listing stays discoverable " +
                            "until its 10-minute freshness expires",
                    )
                    DesktopLog.w(TAG, "nostr gate-drop retract lost: no relay connected")
                }
            }
        }
        publishedEvent = null
        pool?.stop()
        pool = null
        _uiState.value = _uiState.value.copy(
            active = false,
            listingEventId = null,
            relayReports = emptyList(),
        )
    }

    private fun ensurePool(): RelayPool {
        pool?.let { return it }
        val relays = effectiveRelays(model.settings.value)
        val newPool = RelayPool(
            client = seams.client,
            scope = scope,
            signingKeyProvider = ::realmIdentity,
            nowSeconds = { clockMs() / MILLIS_PER_SECOND },
            log = { DesktopLog.i(TAG, it) },
        )
        newPool.start(relays, NostrFilter())
        lastPoolRelays = relays
        reportsCollectorJob = scope.launch {
            newPool.reports.collect { reports ->
                _uiState.value = _uiState.value.copy(
                    relayReports = reports.values.sortedBy { it.url },
                )
            }
        }
        // the write lane's outcome is visible — each OK/rejection of
        // the current beat updates the honest status copy.
        acksCollectorJob = scope.launch {
            newPool.acks.collect { ack -> onWriteAck(ack) }
        }
        pool = newPool
        poolCreations += 1
        return newPool
    }

    /** The realm identity: reads the stored key, generating + persisting
     * it once at first enable (a BIP-340 identity, never displayed).
     * R2B-3: a CORRUPT stored key (hand-edited settings.json) self-heals —
     * it is replaced with a fresh identity instead of bricking publishing
     * forever (the R6-6 degrade-don't-stick posture for stored secrets). */
    private fun realmIdentity(): NostrPrivateKey? {
        val stored = model.settings.value.nostrPrivateKeyHex
        if (stored.isNotBlank()) {
            val parsed = runCatching { NostrPrivateKey.fromHex(stored) }.getOrNull()
            if (parsed != null) return parsed
            DesktopLog.w(
                TAG,
                "nostr stored identity is corrupt — regenerating (the old listing " +
                    "ages out via client-side freshness; new listings carry the new identity)",
            )
        }
        val fresh = NostrPrivateKey.generate()
        model.updateSettings {
            it.copy(nostrPrivateKeyHex = com.pocketrealm.discovery.nostr.Hex.encode(fresh.bytes))
        }
        DesktopLog.i(TAG, "nostr realm identity generated (never displayed)")
        return fresh
    }

    companion object {
        private const val TAG = "NostrListing"

    /**
     * The host card's publish-status copy (R2-6/R6-4): a pure projection
     * of the UiState the composable renders verbatim. The 'Live' claim is
     * honest — it appears only while the switch is on AND (no relay has
     * rejected the current beat, or at least one relay accepted it).
     */
    // The status-copy ladder IS the honesty contract:
    // one branch per host-visible truth.
    @Suppress("CyclomaticComplexMethod")
    fun publishStatusCopy(state: UiState): String {
        val suffix = state.errorCopy?.let { " $it" } ?: ""
        val allRejected = state.lastBeatRejectCount > 0 && state.lastBeatOkCount == 0
        // the beat was SENT but NO relay has answered (zero
        // acks AND zero rejections — dead sockets never send ack frames).
        val zeroAnswered = state.active && state.lastBeatOkCount == 0 &&
            state.lastBeatRejectCount == 0
        return when {
            !state.enabled -> OFF_COPY
            state.paused -> PAUSED_COPY + suffix
            allRejected -> "The listing is being sent, but every relay " +
                "rejected it so far — hosts will not see your realm until a " +
                "relay accepts it. Check the relays below " +
                "(${state.lastBeatRejectCount} rejection(s) on the current " +
                "beat).$suffix"
            zeroAnswered && state.relayReports.isNotEmpty() &&
                state.relayReports.all { it.state == RelayPool.RelayState.FAILED } ->
                "The listing was sent, but every relay is unreachable right " +
                    "now — hosts will not see your realm until a relay accepts " +
                    "it. Check your connection; publishing retries every 5 " +
                    "minutes.$suffix"
            zeroAnswered ->
                "The listing has been sent, but no relay has answered yet — " +
                    "hosts will not see your realm until a relay accepts it." +
                    suffix
            state.active -> LIVE_COPY + suffix
            else -> WAITING_COPY + suffix
        }
    }

    /** R6-2: the per-relay publish reports as renderable lines — each
     * relay's state/rejection reason reaches the host card (the data was
     * fetched and dropped before this projection existed). */
    fun relayReportLines(state: UiState): List<String> = state.relayReports.map { report ->
        val ack = when {
            report.lastAckOk == true -> "accepted"
            report.lastAckOk == false -> "rejected: ${report.lastAckMessage}"
            else -> "no answer"
        }
        val caps = listOfNotNull(
            report.detail?.takeIf { report.detail != report.lastAckMessage },
        )
        "${report.url} — ${report.state}${caps.let { if (it.isEmpty()) "" else " (${it[0]})" }}; " +
            "last write: $ack"
    }

        /** The event-id prefix length in the beat log line (brevity, not
         * secrecy — the id is not sensitive but full ids in logs invite
         * copy-paste mistakes). */
        private const val LOG_PREFIX_CHARS = 12

        private const val OFF_COPY =
            "Off. When on, a world-readable listing is published to the " +
                "relays below every 5 minutes while your realm is up and " +
                "Public: your realm name, description (MOTD), flavor and " +
                "client build, timeframe stage, rates and player counts — " +
                "plus the optional status-page link (a public " +
                "trycloudflare.com hostname) when the showcase tunnel is " +
                "live, and the join descriptor (the realm's iroh node id " +
                "and its home-relay hostname) while the gateway runs. " +
                "No IP address is ever in a listing. The Nostr relays and " +
                "the iroh home relay see your IP, and a player who " +
                "connects to your realm directly may learn it."

        private const val PAUSED_COPY =
            "Paused: the live player split is unavailable right now, so no " +
                "beats are running (the listing is never published on padded " +
                "numbers). Your previously published listing ages out of " +
                "browsers within 10 minutes; publishing resumes automatically " +
                "when the split returns."

        private const val LIVE_COPY =
            "Live — republished every 5 minutes while the realm is up and the " +
                "status page is Public. Retracted when any of that stops being " +
                "true or the app closes."

        private const val WAITING_COPY =
            "Waiting for the gates: the realm up and the realm-status visibility " +
                "set to Public. Nothing is published until then."

        private const val MILLIS_PER_SECOND = 1_000L
        private const val SHUTDOWN_DRAIN_MS = 2_000L
        private const val SHUTDOWN_POLL_MS = 100L
        private const val SHUTDOWN_SETTLE_MS = 500L

        /** W6-N3: the first-beat wait for the host gateway's READY. */
        private const val HOST_DESCRIPTOR_POLL_MS = 250L
    }
}

/** The effective relay set (owner decision 4): the stored user-edited
 * list, else the shipped defaults. File-level so both the publisher and
 * the app model read ONE resolution. */
internal fun effectiveRelays(settings: Settings.Snapshot): List<String> =
    settings.nostrRelays.ifEmpty { RelayUrls.DEFAULTS }
