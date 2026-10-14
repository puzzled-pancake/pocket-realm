package com.pocketrealm.discovery.nostr

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * W6-N1: the public-realm browser — the shared read-side controller both
 * platforms render. Owns the relay pool (keyless: browsing reads, it never
 * writes) and the listing store, and exposes one render-ready state.
 *
 * The empty state is HONEST by construction: [State] carries the relay
 * connectivity so the UI can name an unreachable network instead of
 * claiming the network is provably empty (code-red R6-3), and the
 * "seen on N relays" merge evidence is REAL — each delivery carries its
 * source relay (R2-5).
 *
 * Collector hygiene (code-red R4-3): every collector this class launches
 * is tracked and cancelled by [browse]/[idle], and the class scope's job
 * is PARENTED to the caller's scope — a screen's rememberCoroutineScope
 * cancellation tears the browser down instead of leaking the collector
 * pair plus the retained pool/OkHttpClient per navigation.
 */
class PublicRealmBrowser(
    parentScope: CoroutineScope,
    relayUrls: List<String>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    client: OkHttpClient = defaultClient(),
) {

    /** The live relay set — mutable only through [browse]'s override, so
     * a user's relay edit re-opens the read side on the new set. */
    private var relayUrls: List<String> = relayUrls

    /** One render-ready row (the frozen schema's browsable projection). */
    data class ListingCard(
        val name: String,
        val motd: String,
        val flavor: String,
        val build: Int,
        val timeframeStage: String,
        val rates: RealmListingContent.ListingRates?,
        val humans: Int,
        val bots: Int,
        val publishedAtSeconds: Long,
        val relaysSeen: Int,
        val statusPageUrl: String?,
    )

    data class State(
        val browsing: Boolean = false,
        val listings: List<ListingCard> = emptyList(),
        val relays: List<RelayPool.RelayReport> = emptyList(),
    ) {
        val isEmpty: Boolean get() = listings.isEmpty()

        /** How many of the configured relays currently have an OPEN
         * socket (the honest empty-state input, R6-3). */
        val relaysOpen: Int get() = relays.count { it.state == RelayPool.RelayState.OPEN }

        /** R6-4-1: how many OPEN relays have actually ANSWERED THE READ —
         * a socket whose subscription was CLOSED (auth-walled/refused
         * read) never proves emptiness. */
        val relaysAnsweringRead: Int
            get() = relays.count {
                it.state == RelayPool.RelayState.OPEN && !it.readRefused
            }

        val relaysTotal: Int get() = relays.size
    }

    /** PARENTED to the caller's job (R4-3) and pinned to Dispatchers.IO
     * (R4-r2-1): the collectors run store.accept's verify ladder (SHA-256
     * + Schnorr + JSON parses) on a worker dispatcher, never on the UI
     * thread the caller's rememberCoroutineScope rides. */
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job) +
            kotlinx.coroutines.Dispatchers.IO,
    )

    private val store = ListingStore(nowMs)

    private val pool = RelayPool(
        client = client,
        scope = scope,
        signingKeyProvider = { null }, // the browser lane is keyless: read-only
        nowSeconds = { nowMs() / MILLIS_PER_SECOND },
        log = { },
    )

    private val _state = MutableStateFlow(State())

    /** The render state; [State.isEmpty] is the honest empty network. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Every collector is tracked here and cancelled on restart/idle —
     * a browse() twice no longer leaves the first pair running (R4-3). */
    private val collectors = mutableListOf<Job>()

    /** Starts (or restarts) the browse: fresh subscription, fresh merge.
     * An explicit [newRelays] replaces the live set (the user-editable
     * relay list); null re-uses the current one. */
    fun browse(newRelays: List<String>? = null) {
        newRelays?.let { relayUrls = it }
        cancelCollectors()
        store.clear()
        pool.refresh(relayUrls, NostrFilter())
        _state.value = State(browsing = true)
        synchronized(collectors) {
            collectors += scope.launch {
                pool.events.collect { delivery ->
                    store.accept(delivery.event.toWireJson(), delivery.relayUrl)
                }
            }
            collectors += scope.launch {
                pool.reports.collect { reports ->
                    _state.value = _state.value.copy(relays = reports.values.sortedBy { it.url })
                    reproject()
                }
            }
            collectors += scope.launch {
                store.updates.collect { reproject() }
            }
        }
    }

    /** The honest empty state without sockets (the screens' closed path):
     * every collector dies with it, not just the event lane. */
    fun idle() {
        cancelCollectors()
        pool.stop()
        store.clear()
        _state.value = State(browsing = false, listings = emptyList(), relays = emptyList())
    }

    fun close() {
        idle()
        scope.cancel()
    }

    private fun cancelCollectors() {
        synchronized(collectors) {
            collectors.forEach { it.cancel() }
            collectors.clear()
        }
    }

    private fun reproject() {
        val cards = store.live().map { entry ->
            ListingCard(
                name = entry.content.name,
                motd = entry.content.motd,
                flavor = entry.content.flavor,
                build = entry.content.build,
                timeframeStage = entry.content.timeframeStage,
                rates = entry.content.rates,
                humans = entry.content.humans,
                bots = entry.content.bots,
                publishedAtSeconds = entry.event.createdAtSeconds,
                relaysSeen = entry.seenOn.size,
                statusPageUrl = entry.content.statusPageUrl,
            )
        }
        _state.value = _state.value.copy(listings = cards)
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /** The browser's HTTP client: short connects (a dead relay fails
         * fast and shows FAILED, never hangs the screen). */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Keep the WS alive through NAT idle timeouts.
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 10L
        private const val PING_INTERVAL_SECONDS = 30L
    }
}
