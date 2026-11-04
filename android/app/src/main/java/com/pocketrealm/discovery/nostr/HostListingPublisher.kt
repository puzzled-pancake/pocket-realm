package com.pocketrealm.discovery.nostr

import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The Android host's listing heartbeat (the desktop NostrListingPublisher's
 * twin, run inside :supervisor next to the realm it lists):
 *  - a listing is published only while the host's publish switch is ON and
 *    a LAN-hosted realm's world is up, and only with a READY iroh join
 *    descriptor — an Android listing exists to be joined, so a host
 *    endpoint that cannot come up publishes nothing and says why;
 *  - the content goes through [RealmListingContent.fromStatusPayload] (the
 *    one sanitizing builder, the same banned-content fence the desktop
 *    payload rides) — humans and bots are reported separately, never
 *    padded, and an unobservable world pauses the beats;
 *  - the listing republishes every [Seams.heartbeatSeconds] while the
 *    gates hold; any gate drop retracts it (NIP-09) and stops the host
 *    endpoint — a crash publishes nothing further and the 10-minute
 *    browser freshness ages the listing out.
 */
class HostListingPublisher(
    private val scope: CoroutineScope,
    private val seams: Seams,
) {

    /** The relay write lane the publisher drives ([RelayPool] in
     * production; a recording fake in the unit lane). */
    interface Carrier {
        val reports: StateFlow<Map<String, RelayPool.RelayReport>>
        val acks: SharedFlow<RelayPool.WriteAck>
        fun publish(event: NostrEvent)
        fun stop()
    }

    class Seams(
        val clockMs: () -> Long,
        val relays: () -> List<String>,
        /** The realm's Nostr identity (the service reads/generates and
         * persists it). */
        val identity: suspend () -> NostrPrivateKey?,
        /** This beat's content, or null when the world's player split is
         * not observable (the beat pauses, never padded). */
        val content: suspend () -> RealmListingContent?,
        /** Brings the host endpoint up; the READY descriptor, or a failure
         * whose message names the reason. */
        val ensureHost: suspend () -> Result<IrohJoinDescriptor>,
        val stopHost: () -> Unit,
        /** False once a started endpoint died: the next evaluation
         * re-ensures it and republishes instead of waiting for the beat. */
        val hostReady: () -> Boolean = { true },
        val carrierFactory: (List<String>) -> Carrier,
        val heartbeatSeconds: Long = RealmListingContract.HEARTBEAT_SECONDS,
        /** Retract flush budget (the deletion must leave the sockets). */
        val retractDrainMs: Long = RETRACT_DRAIN_MS,
    )

    enum class Phase { OFF, WAITING, STARTING, UNLISTED, LIVE, PAUSED, FAILED }

    data class UiState(
        val phase: Phase = Phase.OFF,
        val detail: String? = null,
        val descriptor: IrohJoinDescriptor? = null,
        val listingEventId: String? = null,
        val lastPublishedAtMs: Long? = null,
        val beats: Int = 0,
        val acceptedBy: Int = 0,
        val rejectedBy: Int = 0,
        val relaysOpen: Int = 0,
        val relaysTotal: Int = 0,
    )

    /** One evaluation's inputs: the online-hosting switch, whether the
     * game also shows in the game list, and whether a hosted realm's
     * world is up. The internet endpoint runs on `online` alone — the
     * listing publishes only with `listed` too. */
    data class Gates(val online: Boolean, val listed: Boolean, val hostingWorldUp: Boolean)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var carrier: Carrier? = null
    private var carrierJobs: List<Job> = emptyList()
    private var published: NostrEvent? = null
    /** The content of the live listing before its join descriptor was
     * attached (the change-republish comparison). */
    private var publishedContent: RealmListingContent? = null
    private var hostUp = false
    private val beatAcks = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** One gate evaluation: publish when green and due, retract on a
     * drop. Serialized — the settings watcher, runtime watcher and ticker
     * all drive it. */
    suspend fun evaluate(gates: Gates): Unit = mutex.withLock {
        when {
            !gates.online -> deactivate(Phase.OFF, null)
            !gates.hostingWorldUp -> deactivate(
                Phase.WAITING,
                "Waiting for a hosted realm: start hosting on this tab and the listing goes up " +
                    "once the world is ready.",
            )
            // Online but unlisted: the endpoint joins carry stays UP (the
            // invite code keeps working); no listing exists and any old
            // one retracts.
            !gates.listed -> runCatching { hostUnlisted() }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _state.value = _state.value.copy(
                    phase = Phase.FAILED,
                    detail = failure.message ?: "the host endpoint did not come up",
                )
            }
            else -> runCatching { beatIfDue() }.onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                _state.value = _state.value.copy(
                    phase = Phase.FAILED,
                    detail = failure.message ?: "the listing beat failed",
                )
            }
        }
    }

    /** The unlisted state: the internet endpoint is up (re-ensured here),
     * no listing is out, and the carrier is dropped until listing turns
     * back on. */
    private suspend fun hostUnlisted() {
        if (!hostUp || !seams.hostReady()) {
            _state.value = _state.value.copy(phase = Phase.STARTING, detail = "Opening the internet endpoint…")
            val descriptor = seams.ensureHost().getOrElse { failure ->
                hostUp = false
                _state.value = _state.value.copy(
                    phase = Phase.FAILED,
                    descriptor = null,
                    detail = "Not online: the internet endpoint did not come up (${failure.message}). " +
                        "Retrying shortly.",
                )
                return
            }
            hostUp = true
            _state.value = _state.value.copy(descriptor = descriptor)
        }
        if (retractPublished()) {
            // the deletion must leave the sockets before the carrier drops
            carrier?.let { runCatching { drain(it) } }
        }
        carrierJobs.forEach { it.cancel() }
        carrierJobs = emptyList()
        carrier?.stop()
        carrier = null
        beatAcks.clear()
        _state.value = _state.value.copy(
            phase = Phase.UNLISTED,
            detail = null,
            listingEventId = null,
            lastPublishedAtMs = null,
        )
    }

    /** Graceful teardown (switch off or service stop): retract, then stop. */
    suspend fun shutdown(): Unit = mutex.withLock { deactivate(Phase.OFF, null) }

    private suspend fun beatIfDue() {
        val last = _state.value.lastPublishedAtMs
        val due = published == null || last == null ||
            seams.clockMs() - last >= seams.heartbeatSeconds * MILLIS_PER_SECOND ||
            (hostUp && !seams.hostReady())
        // Between beats, a listing whose live facts changed (players
        // online, sign-up) republishes once the change window is open.
        val changeWindow = RealmListingContract.changeWindowOpen(last, seams.clockMs())
        if (!due && !changeWindow) return
        val content = seams.content()
        if (!due && !RealmListingContract.factsChanged(publishedContent, content)) return
        if (content == null) {
            _state.value = _state.value.copy(
                phase = if (published != null) Phase.PAUSED else Phase.STARTING,
                detail = PAUSED_DETAIL,
            )
            return
        }
        val key = seams.identity() ?: error("no realm identity")
        if (!hostUp) {
            _state.value = _state.value.copy(phase = Phase.STARTING, detail = "Opening the internet endpoint…")
        }
        val descriptor = seams.ensureHost().getOrElse { failure ->
            hostUp = false
            // A listing whose endpoint is gone must not keep advertising it.
            retractPublished()
            _state.value = _state.value.copy(
                phase = Phase.FAILED,
                descriptor = null,
                detail = "Not listed: the internet endpoint did not come up (${failure.message}). " +
                    "Retrying shortly.",
            )
            return
        }
        hostUp = true
        val event = RealmListingEvent.build(
            content = content.copy(join = descriptor),
            key = key,
            nowSeconds = seams.clockMs() / MILLIS_PER_SECOND,
        )
        published = event
        publishedContent = content
        beatAcks.clear()
        ensureCarrier().publish(event)
        _state.value = _state.value.copy(
            phase = Phase.LIVE,
            detail = null,
            descriptor = descriptor,
            listingEventId = event.idHex,
            lastPublishedAtMs = seams.clockMs(),
            beats = _state.value.beats + 1,
            acceptedBy = 0,
            rejectedBy = 0,
        )
    }

    private suspend fun deactivate(phase: Phase, detail: String?) {
        val hadListing = retractPublished()
        if (hostUp || hadListing) runCatching { seams.stopHost() }
        hostUp = false
        carrierJobs.forEach { it.cancel() }
        carrierJobs = emptyList()
        carrier?.stop()
        carrier = null
        beatAcks.clear()
        _state.value = UiState(phase = phase, detail = detail, beats = _state.value.beats)
    }

    /** NIP-09 deletion of the live listing, if any; true when there was one. */
    private suspend fun retractPublished(): Boolean {
        val listing = published ?: return false
        published = null
        publishedContent = null
        val key = seams.identity()
        val active = carrier
        if (key != null && active != null) {
            runCatching {
                active.publish(
                    RealmListingEvent.buildDeletion(
                        listing = listing,
                        key = key,
                        nowSeconds = seams.clockMs() / MILLIS_PER_SECOND,
                    ),
                )
                drain(active)
            }
        }
        return true
    }

    /** Bounded flush: let connecting relays open, then a short settle. */
    private suspend fun drain(active: Carrier) {
        if (seams.retractDrainMs <= 0) return
        withTimeoutOrNull(seams.retractDrainMs) {
            while (active.reports.value.values.any { it.state == RelayPool.RelayState.CONNECTING }) {
                delay(DRAIN_POLL_MS)
            }
            delay(DRAIN_SETTLE_MS)
        }
    }

    private fun ensureCarrier(): Carrier {
        carrier?.let { return it }
        val created = seams.carrierFactory(seams.relays())
        carrierJobs = listOf(
            scope.launch {
                created.reports.collect { reports ->
                    _state.value = _state.value.copy(
                        relaysOpen = reports.values.count { it.state == RelayPool.RelayState.OPEN },
                        relaysTotal = reports.size,
                    )
                }
            },
            scope.launch {
                created.acks.collect { ack ->
                    if (ack.eventId != published?.idHex) return@collect
                    beatAcks[ack.relayUrl] = ack.ok
                    _state.value = _state.value.copy(
                        acceptedBy = beatAcks.values.count { it },
                        rejectedBy = beatAcks.values.count { !it },
                    )
                }
            },
        )
        carrier = created
        return created
    }

    companion object {
        /** The listing name when the host left the field empty. */
        const val DEFAULT_REALM_NAME = "Pocket Realm"

        private const val MILLIS_PER_SECOND = 1_000L
        private const val RETRACT_DRAIN_MS = 2_500L
        private const val DRAIN_POLL_MS = 100L
        private const val DRAIN_SETTLE_MS = 500L

        private const val PAUSED_DETAIL =
            "Paused: the world's player count is not readable right now, so no listing is sent " +
                "(it is never published on guessed numbers)."

        /** What publishing reveals, shown beside the switch before the
         * first publish (the desktop OFF copy's twin). */
        const val DISCLOSURE_COPY: String =
            "When on, a public listing is shared while you host and refreshed as players come " +
                "and go: the realm name and description, game version, content patch, players " +
                "and bots online, the player limit, uptime, whether sign-up is open, the app " +
                "version, and this realm's internet join code. No IP address is ever in a " +
                "listing. The relays that carry the listing and the connection see this " +
                "device's internet address, and a player whose connection goes direct may " +
                "learn it. Players still need an account on this realm to log in."

        /**
         * The listing content for this beat from the hosting session's
         * facts, through the one sanitizing builder; null when the world's
         * split is not observable or the name is refused by the fence
         * (the fence's reason is thrown, so the card shows it).
         */
        fun contentFor(
            realmName: String,
            flavorId: String,
            timeframeStage: String,
            worldReady: Boolean,
            realPlayers: Int,
            playerbotsEnabled: Boolean,
            onlinePlayers: Int,
            uptimeSeconds: Long? = null,
            signupOpen: Boolean = false,
            description: String = "",
            maxPlayers: Int? = null,
            appVersion: String? = null,
            locked: Boolean = false,
        ): RealmListingContent? {
            if (!worldReady) return null
            // onlinePlayers counts bots when playerbots run; realPlayers is
            // the human count only then.
            val humans = if (playerbotsEnabled) realPlayers else onlinePlayers
            val bots = if (playerbotsEnabled) (onlinePlayers - realPlayers).coerceAtLeast(0) else 0
            val payload = JSONObject()
                .put(
                    "realm",
                    JSONObject()
                        .put("name", realmName.trim().ifEmpty { DEFAULT_REALM_NAME })
                        .put("description", description.trim())
                        .put("flavor", flavorId)
                        .put("timeframeStageId", timeframeStage),
                )
                .put(
                    "players",
                    JSONObject()
                        .put("source", "native")
                        .put("humansOnline", humans.coerceAtLeast(0))
                        .put("botsOnline", bots),
                )
            val built = try {
                RealmListingContent.fromStatusPayload(payload)
            } catch (refused: IllegalArgumentException) {
                // The fence's reason in the host's words.
                throw IllegalArgumentException(
                    (refused.message ?: "the listing was refused")
                        .replace("listing motd", "The description")
                        .replace("listing name", "The realm name"),
                    refused,
                )
            }
            return built?.copy(
                uptimeSeconds = uptimeSeconds?.coerceIn(0L, RealmListingContent.UPTIME_SECONDS_CEILING),
                signupOpen = signupOpen,
                maxPlayers = maxPlayers?.takeIf { RealmListingContent.isValidMaxPlayers(it) },
                appVersion = RealmListingContent.appVersionTag(appVersion),
                locked = locked,
            )
        }

        private fun liveCopy(state: UiState): String = when {
            state.acceptedBy > 0 ->
                "Listed — accepted by ${state.acceptedBy} relay(s)" +
                    (if (state.rejectedBy > 0) ", rejected by ${state.rejectedBy}" else "") +
                    ". Refreshed as players come and go; removed when you stop hosting or turn this off."
            state.rejectedBy > 0 ->
                "Sent, but every relay rejected it so far (${state.rejectedBy}) — players " +
                    "will not see the realm until a relay accepts it."
            else -> "Sent — waiting for a relay to accept the listing " +
                "(${state.relaysOpen}/${state.relaysTotal} relays connected)."
        }

        /** The Host tab's one-line status, verbatim per phase. */
        fun statusCopy(state: UiState): String = when (state.phase) {
            Phase.OFF -> "Not published."
            Phase.WAITING -> state.detail ?: "Waiting for a hosted realm."
            Phase.STARTING -> state.detail ?: "Starting…"
            Phase.UNLISTED -> "Online, not listed — share your invite code."
            Phase.PAUSED -> state.detail ?: "Paused."
            Phase.FAILED -> state.detail ?: "Not listed."
            Phase.LIVE -> liveCopy(state)
        }
    }
}

/** Adapts the shared [RelayPool] to the publisher's carrier seam. */
class RelayPoolCarrier(private val pool: RelayPool) : HostListingPublisher.Carrier {
    override val reports: StateFlow<Map<String, RelayPool.RelayReport>> get() = pool.reports
    override val acks: SharedFlow<RelayPool.WriteAck> get() = pool.acks
    override fun publish(event: NostrEvent) = pool.publish(event)
    override fun stop() = pool.stop()
}
