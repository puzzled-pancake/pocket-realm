package com.pocketrealm.discovery.nostr

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * W6-N1 (docs/NOSTR-IROH-BUILD-PLAN.md stage 1): the relay pool — one
 * OkHttp WebSocket per relay, WRITE-ALL / READ-ALL (the consult's
 * partitioning rule); the merge itself happens in ListingStore by event
 * id / (pubkey, d) dedupe.
 *
 * Accountless extras, exactly as adopted (NOSTR-IROH-BUILD-PLAN.md
 * corrections list):
 *  - NIP-11 capability probe per open relay (an `Accept:
 *    application/nostr+json` GET) — REPORTED, never load-bearing (the
 *    client-side 10-minute freshness rule is the expiration story);
 *  - NIP-42 AUTH challenges answered with the realm's own key (a key, not
 *    a signup); a write refused with auth-required is re-sent after the
 *    auth event;
 *  - NIP-13 PoW ONLY when a chosen relay demands it (an OK rejection
 *    carrying "pow" + a difficulty) — mined, re-signed, re-sent once,
 *    bounded, loud (VAL-NOSTR-POW) on exhaustion.
 *
 * No auto-reconnect in v1: the pool is explicit — [start]/[refresh]/
 * [stop]. The heartbeat's own cadence re-drives publishes and the browser
 * lane refreshes on entry, so a dead socket is re-opened by the next
 * explicit cycle, not by magic.
 */
class RelayPool(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    /** The realm key for NIP-42 AUTH and PoW re-signing (null on the
     * keyless browser lane — read-only relays need no auth; a demanding
     * write relay simply refuses, honestly surfaced in the ack lane). */
    private val signingKeyProvider: () -> NostrPrivateKey?,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val log: (String) -> Unit = {},
) {

    /** One relay's observable state (the browser renders this honestly). */
    data class RelayReport(
        val url: String,
        val state: RelayState,
        val supportedNips: List<Int>,
        val lastAckOk: Boolean?,
        val lastAckMessage: String?,
        val detail: String?,
        /** R6-4-1: the relay CLOSED our read subscription (the socket may
         * still be OPEN) — emptiness is never provable off this relay. */
        val readRefused: Boolean = false,
    )

    enum class RelayState { CONNECTING, OPEN, CLOSED, FAILED }

    data class WriteAck(val relayUrl: String, val eventId: String, val ok: Boolean, val message: String)

    /** One read-lane delivery: the event PLUS the relay that delivered it
     * (the merge evidence the browser's "seen on N relays" copy renders —
     * R2-5). */
    data class RelayEnvelope(val relayUrl: String, val event: NostrEvent)

    // R4-2: DROP_OLDEST bounds the in-flight queue under a flood — a
    // slow serial verify-consumer makes emit drop the oldest envelope
    // instead of suspending the per-frame launch (no unbounded growth).
    private val _events =
        MutableSharedFlow<RelayEnvelope>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Read-lane events as they arrive from any relay (UNVERIFIED — the
     * signature/id verification is ListingStore's job). */
    val events: SharedFlow<RelayEnvelope> = _events.asSharedFlow()

    private val _acks = MutableSharedFlow<WriteAck>(extraBufferCapacity = 128)
    /** Write-lane OK/rejection acks, per relay. */
    val acks: SharedFlow<WriteAck> = _acks.asSharedFlow()

    /** Live-collector counts (internal: the mock-relay suite subscribes
     * deterministically before any socket can deliver — replay is 0 by
     * design, so a late collector would legitimately miss early events). */
    internal val eventsSubscriptionCount: StateFlow<Int> = _events.subscriptionCount
    internal val acksSubscriptionCount: StateFlow<Int> = _acks.subscriptionCount

    private val _reports = MutableStateFlow<Map<String, RelayReport>>(emptyMap())
    val reports: StateFlow<Map<String, RelayReport>> = _reports.asStateFlow()

    private val sessions = LinkedHashMap<String, RelaySession>()

    /** R4-1: the pool generation — incremented on every stop(), stamped on
     * each session; a RETIRED session's async terminal callbacks (close/
     * failure/NIP-11 probe) are ignored instead of overwriting the live
     * session's report. */
    @Volatile private var generation = 0L

    /** The browse subscription id (stable across relays, fresh per pool). */
    private val subscriptionId: String =
        "pocketrealm-" + System.nanoTime().toString(SUBSCRIPTION_RADIX)

    @Volatile private var activeFilter: NostrFilter? = null

    @Volatile private var stopped = false

    /** Opens one WebSocket per relay and issues the browse REQ on open. */
    fun start(relayUrls: List<String>, filter: NostrFilter) {
        stopped = false
        activeFilter = filter
        relayUrls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { url ->
            // A hand-edited settings file can carry a URL OkHttp cannot
            // parse (R6-6): the failure is a FAILED report on that relay —
            // never an exception through the caller's composition.
            val clean = runCatching { RelayUrls.validate(url) }.getOrElse { failure ->
                val dead = RelaySession(url, generation)
                dead.fail(failure.message ?: "VAL-NOSTR-RELAY: invalid relay URL")
                _reports.value = _reports.value +
                    (dead.report(RelayState.FAILED).url to dead.report(RelayState.FAILED))
                return@forEach
            }
            if (sessions.containsKey(clean)) return@forEach
            val session = RelaySession(clean, generation)
            synchronized(sessions) { sessions[clean] = session }
            session.publishReport(RelayState.CONNECTING)
            val request = runCatching { Request.Builder().url(clean).build() }.getOrElse { failure ->
                session.fail(failure.message ?: "unparseable relay URL")
                session.publishReport(RelayState.FAILED)
                return@forEach
            }
            session.attach(client.newWebSocket(request, session.listener))
        }
    }

    /** Tears down and re-opens every relay with a fresh subscription. */
    fun refresh(relayUrls: List<String>, filter: NostrFilter) {
        stop()
        start(relayUrls, filter)
    }

    /**
     * WRITE-ALL: queues ["EVENT", ev] to every session — open sockets send
     * now, connecting sockets send on open (after any NIP-42 auth gate).
     * The event is remembered per session so an auth/PoW refusal can be
     * re-signed and re-sent.
     */
    fun publish(event: NostrEvent) {
        val frame = event.toEventFrame()
        synchronized(sessions) {
            sessions.values.forEach { session -> session.send(frame, event) }
        }
    }

    fun stop() {
        stopped = true
        generation += 1
        synchronized(sessions) {
            sessions.values.forEach { session ->
                runCatching { session.socket?.close(NORMAL_CLOSE_CODE, "pool stopped") }
            }
            sessions.clear()
        }
        _reports.value = emptyMap()
    }

    private fun RelaySession.publishReport(newState: RelayState) {
        // R4-1: a retired session's late callback never overwrites the
        // live session's report (the same URL key would clobber it).
        if (gen != generation) return
        _reports.value = _reports.value + (url to report(newState))
    }



    // ------------------------------------------------------------------
    // Per-relay session: the WS listener + the auth/PoW retry machine.
    // ------------------------------------------------------------------

    // The per-relay protocol state machine: each handler is one frame
    // type's contract leg (the orchestrator-seam idiom).
    @Suppress("TooManyFunctions")
    private inner class RelaySession internal constructor(
        val url: String,
        /** The pool generation this session belongs to (R4-1). */
        val gen: Long,
    ) {

        @Volatile var socket: WebSocket? = null

        @Volatile var state: RelayState = RelayState.CONNECTING

        @Volatile var supportedNips: List<Int> = emptyList()

        @Volatile var lastAckOk: Boolean? = null

        @Volatile var lastAckMessage: String? = null

        @Volatile var detail: String? = null

        /** The NIP-42 challenge this relay sent, if any. */
        @Volatile private var authChallenge: String? = null

        /** Events sent (or queued to send) whose write was refused with
         * auth-required — re-sent ONCE after the auth event goes out (the
         * R2-3/R3-3 cap: a relay that rejects again gets no third frame). */
        private val awaitingAuth = LinkedHashMap<String, NostrEvent>()

        /** The pow rejections this session has answered — the 'mined and
         * re-sent ONCE, bounded' contract's enforcement: the mined re-send
         * carries a NEW id (the nonce tag), so the cap is a per-session
         * COUNTER, never an id set. */
        private var powRejections = 0

        /** Set when the pow cap fired for this session (the report
         * surfaces it). */
        @Volatile var powCapMessage: String? = null

        /** Set when the auth cap fired for this session. */
        @Volatile var authCapMessage: String? = null

        /** R6-4-1: the relay CLOSED our subscription (an auth-walled or
         * refused read) — the socket stays OPEN, so the honest empty-state
         * must NOT claim the network is provably empty. */
        @Volatile var readRefused = false

        /** R3-6/R6-3-6: UNSOLICITED challenge answers sent this session —
         * each costs a Schnorr sign + a REQ re-issue that invites the
         * relay to re-deliver its window; capped like every retry lane. */
        private var authChallengesAnswered = 0

        /** Every event handed to [send] while this session lives, by id —
         * the re-sign/re-send source for auth and PoW retries. */
        private val sentEvents = LinkedHashMap<String, NostrEvent>()

        /** Frames queued before the socket opened (REQ + early publishes). */
        private val outboundQueue = ArrayDeque<String>()

        fun attach(ws: WebSocket) {
            this.socket = ws
        }

        /** Marks a session dead without a socket (an unparseable stored
         * URL — R6-6): the report carries the reason, nothing throws. */
        fun fail(reason: String) {
            state = RelayState.FAILED
            detail = reason
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped) {
                    webSocket.close(NORMAL_CLOSE_CODE, "pool stopped")
                    return
                }
                state = RelayState.OPEN
                detail = null
                publishReport(RelayState.OPEN)
                probeNip11()
                val queued = synchronized(this@RelaySession) {
                    activeFilter?.let { outboundQueue.addLast(reqFrame(it)) }
                    buildList {
                        while (outboundQueue.isNotEmpty()) add(outboundQueue.removeFirst())
                    }
                }
                queued.forEach { webSocket.send(it) }
                log("relay open: $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text, webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                state = RelayState.CLOSED
                detail = "closed ($code)"
                publishReport(RelayState.CLOSED)
                log("relay closed: $url ($code)")
            }

            /** The socket-level close path (the transport died); kept
             * distinct from the READ refusal (subscription CLOSED). */

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                state = RelayState.FAILED
                detail = t.message ?: "connection failed"
                publishReport(RelayState.FAILED)
                log("relay failed: $url (${t.message})")
            }
        }

        fun send(frame: String, event: NostrEvent?) {
            // One lock covers the state check AND the send/queue choice, so
            // a publish racing the open drain can neither skip delivery nor
            // strand its frame in a queue nobody drains again.
            synchronized(this) {
                if (event != null) sentEvents[event.idHex] = event
                if (state == RelayState.OPEN) {
                    socket?.send(frame)
                } else {
                    outboundQueue.addLast(frame)
                }
            }
        }

        fun report(newState: RelayState): RelayReport = RelayReport(
            url = url,
            state = newState,
            supportedNips = supportedNips,
            lastAckOk = lastAckOk,
            lastAckMessage = lastAckMessage ?: powCapMessage ?: authCapMessage,
            detail = detail ?: powCapMessage ?: authCapMessage,
            readRefused = readRefused,
        )

        /** NIP-11: the capability probe — reported, never load-bearing. A
         * missing/unparseable doc degrades honestly: the relay still
         * speaks NIP-01 on the socket. */
        private fun probeNip11() {
            val httpUrl = url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .build()
            client.newBuilder()
                .callTimeout(NIP11_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val nips = runCatching {
                                // R4-r2-1: the read is BOUNDED — peekBody
                                // buffers at most NIP11_MAX_BYTES of a
                                // hostile document; the full body is never
                                // consumed into memory and the consumed
                                // byte count surfaces on the pool for the
                                // capped-probe test to hold.
                                val body = it.peekBody(NIP11_MAX_BYTES.toLong())
                                    .string()
                                RelayPool.lastNip11ProbeBytes =
                                    body.toByteArray().size
                                if (!it.isSuccessful || body.isEmpty()) {
                                    return@runCatching emptyList()
                                }
                                parseSupportedNips(body)
                            }.getOrDefault(emptyList())
                            supportedNips = nips
                            publishReport(state)
                            log("relay nip-11: $url supports $nips")
                        }
                    }

                    override fun onFailure(call: Call, e: java.io.IOException) {
                        log("relay nip-11 probe failed (non-fatal): $url")
                    }
                })
        }

        @Suppress("ReturnCount") // the NIP-01 frame-type dispatch IS the protocol contract: one exit per frame type
        private fun handleFrame(text: String, webSocket: WebSocket) {
            // R4-2: the frame-size gate runs BEFORE any parse — a hostile
            // multi-MB frame costs one length check, not 3-4x its size in
            // buffers (listings are a few KB; 256 KB is 100x headroom).
            if (text.length > MAX_FRAME_CHARS) {
                log("relay frame dropped: $url (${text.length} chars > $MAX_FRAME_CHARS)")
                return
            }
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
            when (arr.optString(0)) {
                "EVENT" -> {
                    if (arr.length() < SUBSCRIPTION_FRAME_ARITY) return
                    val event = runCatching { NostrEvent.fromJson(arr.getJSONObject(2).toString()) }
                        .getOrNull() ?: return
                    scope.launch { _events.emit(RelayEnvelope(url, event)) }
                }

                "OK" -> handleOk(arr, webSocket)

                "AUTH" -> handleAuthChallenge(arr.optString(1, ""), webSocket)

                "NOTICE" -> log("relay notice: $url ${arr.optString(1, "")}")

                "CLOSED" -> {
                    // R6-4-1: a subscription CLOSED frame means the READ was
                    // refused (auth-walled/moderated) even though the socket
                    // stays OPEN — surfaced so emptiness is never claimed
                    // off a failed read.
                    if (arr.optString(1) == subscriptionId) {
                        readRefused = true
                        detail = "subscription closed: ${arr.optString(2, "")}"
                        publishReport(state)
                    }
                    log("relay closed sub: $url ${arr.optString(1, "")}")
                }

                else -> Unit
            }
        }

        @Suppress("ReturnCount") // the OK ladder (accept / auth-required / pow / plain reject) IS the write contract
        private fun handleOk(arr: JSONArray, webSocket: WebSocket) {
            // NIP-01: ["OK", <event id>, true|false, <message>].
            if (arr.length() < OK_FRAME_ARITY) return
            val eventId = arr.optString(OK_EVENT_ID_INDEX)
            val ok = arr.optBoolean(OK_RESULT_INDEX, false)
            val message = arr.optString(OK_MESSAGE_INDEX, "")
            lastAckOk = ok
            lastAckMessage = message
            if (ok) {
                publishReport(state)
                scope.launch { _acks.emit(WriteAck(url, eventId, true, message)) }
                return
            }
            val lowered = message.lowercase()
            when {
                "auth-required" in lowered -> handleAuthRequired(eventId, webSocket)

                "pow" in lowered -> handlePowDemand(eventId, message)

                else -> {
                    publishReport(state)
                    scope.launch { _acks.emit(WriteAck(url, eventId, false, message)) }
                    log("relay rejected event: $url ($message)")
                }
            }
        }

        /** The R2-3 cap: auth is answered ONCE per session. A relay that
         * demands auth again after the signed auth event went out ends the
         * exchange, loudly. */
        private fun handleAuthRequired(eventId: String, webSocket: WebSocket) {
            if (authCapMessage != null) {
                log("VAL-NOSTR-RELAY: $url demanded auth again after the auth event — giving up")
                return
            }
            synchronized(this) { awaitingAuth[eventId] = sentEvents[eventId] ?: return }
            val challenge = authChallenge
            if (challenge != null) {
                authCapMessage = "auth re-demanded after the signed auth event"
                sendAuth(challenge, webSocket)
                resendAfterAuth(webSocket)
            } else {
                // R2-4: the refusal surfaces on the write lane even
                // while the challenge is pending.
                lastAckOk = false
                lastAckMessage = "auth-required (challenge pending)"
                publishReport(state)
                scope.launch {
                    _acks.emit(WriteAck(url, eventId, false, lastAckMessage!!))
                }
                log("relay auth-required (waiting for the challenge): $url")
            }
        }

        /** The R2-3/R3-3 cap: mined ONCE per session. A relay that rejects
         * the mined event again gets nothing further, and the refusal
         * surfaces on the ack lane. R4-5: mining never runs on the WS
         * reader thread — the socket keeps reading (events, acks,
         * challenges) while the retry is prepared off-thread. */
        private fun handlePowDemand(eventId: String, message: String) {
            synchronized(this) {
                powRejections += 1
                if (powRejections > 1) {
                    powCapMessage =
                        "VAL-NOSTR-POW: relay $url demanded pow again after the mined " +
                            "re-send — giving up"
                    log(powCapMessage!!)
                    lastAckOk = false
                    lastAckMessage = powCapMessage
                    publishReport(state)
                    scope.launch {
                        _acks.emit(WriteAck(url, eventId, false, powCapMessage!!))
                    }
                    return
                }
            }
            val event = synchronized(this) { sentEvents[eventId] }
            val difficulty = PowMiner.parseDifficulty(message)
            if (event == null || difficulty == null) {
                // R2-4: the refusal surfaces on the write lane even when
                // the demand is unparseable or above the miner cap.
                lastAckOk = false
                lastAckMessage = message
                publishReport(state)
                scope.launch {
                    _acks.emit(WriteAck(url, eventId, false, "pow demand refused: $message"))
                }
                log(
                    "relay pow rejection unparseable: $url " +
                        "(eventKnown=${event != null}, difficulty=$difficulty, " +
                        "wanted=$eventId, msg=$message)",
                )
                return
            }
            scope.launch {
                val mined = runCatching {
                    PowMiner.mine(event, difficulty, signingKeyProvider())
                }.getOrElse { failure ->
                    log("relay pow refused: $url (${failure.message})")
                    lastAckOk = false
                    lastAckMessage = failure.message
                    publishReport(state)
                    scope.launch {
                        _acks.emit(WriteAck(url, event.idHex, false, "pow refused: ${failure.message}"))
                    }
                    return@launch
                }
                log("relay pow: mined difficulty $difficulty for $url")
                synchronized(this@RelaySession) { sentEvents[mined.idHex] = mined }
                val send: WebSocket? = socket
                if (send != null && state == RelayState.OPEN) {
                    send.send(mined.toEventFrame())
                }
            }
        }

        private fun handleAuthChallenge(challenge: String, webSocket: WebSocket) {
            // R3-6/R6-3-6: unprovoked challenges are answered ONCE - a
            // flood of forged challenges must not drive an unbounded
            // sign+re-fetch loop (the R2-3 pattern applied to the
            // unsolicited path the auth-required cap does not cover).
            synchronized(this) {
                authChallengesAnswered += 1
                if (authChallengesAnswered > 1) {
                    authCapMessage =
                        "VAL-NOSTR-RELAY: relay $url sent repeated unsolicited " +
                            "auth challenges - ignoring further ones"
                    log(authCapMessage!!)
                    return
                }
            }
            authChallenge = challenge
            sendAuth(challenge, webSocket)
            // NIP-42: after auth the relay re-evaluates BOTH the write AND
            // the subscription — re-issue the REQ, then the refused writes.
            val filter = activeFilter
            if (filter != null) webSocket.send(reqFrame(filter))
            resendAfterAuth(webSocket)
        }

        private fun resendAfterAuth(webSocket: WebSocket) {
            val retries = synchronized(this) {
                val drained = ArrayList(awaitingAuth.values)
                awaitingAuth.clear()
                drained
            }
            retries.forEach { webSocket.send(it.toEventFrame()) }
        }

        private fun sendAuth(challenge: String, webSocket: WebSocket) {
            val key = signingKeyProvider() ?: run {
                log("relay auth challenge with no realm key: $url (write stays refused)")
                return
            }
            val authEvent = NostrEvent.create(
                key = key,
                pending = PendingEvent(
                    createdAtSeconds = nowSeconds(),
                    kind = RealmListingContract.AUTH_KIND,
                    tags = listOf(listOf("relay", url), listOf("challenge", challenge)),
                    content = "",
                ),
            )
            webSocket.send("[\"AUTH\"," + authEvent.toWireJson() + "]")
            log("relay auth sent: $url")
        }

        private fun reqFrame(filter: NostrFilter): String {
            val since = nowSeconds() - filter.sinceMarginSeconds
            return "[\"REQ\"," + NostrJson.quote(subscriptionId) +
                ",{\"kinds\":[" + RealmListingContract.KIND + "]" +
                ",\"#t\":[" + NostrJson.quote(RealmListingContract.T_TAG) + "]" +
                ",\"since\":" + since + "}]"
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NORMAL_CLOSE_CODE = 1000
        private const val NIP11_TIMEOUT_SECONDS = 5L

        /** R4-r2-1: the NIP-11 capability probe's BYTE cap — a hostile
         * relay's arbitrarily large document is truncated at this bound
         * before it ever reaches a String (the exit record's "12 MB cap",
         * now the code that proves it). */
        const val NIP11_MAX_BYTES = 12 * 1024 * 1024

        /** R4-r2-1: the observability seam the capped-probe test asserts
         * through — the number of bytes the LAST probe actually consumed
         * (-1 = no probe completed yet; last probe wins, and the capped
         * suite uses single-relay pools). An unbounded read would exceed
         * [NIP11_MAX_BYTES] for an over-cap body, so the test can
         * distinguish truncation from parse failure — the distinction the
         * old report-existence loop could not make. */
        @Volatile
        internal var lastNip11ProbeBytes: Int = -1

        /** NIP-11 supported_nips extraction (parse only; never gates). A
         * missing or unparseable doc is an honest empty list. */
        internal fun parseSupportedNips(body: String): List<Int> = runCatching {
            val arr = JSONObject(body).optJSONArray("supported_nips") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val v = arr.opt(i)
                (v as? Int) ?: (v as? Long)?.toInt()
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * The browse filter (the adopted correction: kind + `t` tag + `since` —
 * NIP-50 search is the wrong tool). The kind/t values are the frozen
 * contract's; only the since margin is configurable.
 */
/** NIP-01 OK frame: ["OK", <id>, <bool>, <message>]. */
private const val OK_FRAME_ARITY = 4
private const val OK_EVENT_ID_INDEX = 1
private const val OK_RESULT_INDEX = 2
private const val OK_MESSAGE_INDEX = 3

/** Byte/leading-zero-bit arithmetic in PowMiner. */
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val INT_PADDING_BITS = 24

/** The subscription-id radix (a compact, unique-per-pool suffix). */
private const val SUBSCRIPTION_RADIX = 36

/** The dialable port ceiling (R2-2's parser round-trip bound). */
private const val PORT_MAX = 65_535

/** R4-2: the inbound frame gate — 256 KB is ~100x the largest honest
 * listing; anything bigger is dropped before parse. */
private const val MAX_FRAME_CHARS = 262_144

/** The NIP-01 subscription-response frame arity (["EVENT", sub, event]). */
private const val SUBSCRIPTION_FRAME_ARITY = 3

data class NostrFilter(val sinceMarginSeconds: Long = DEFAULT_SINCE_MARGIN_SECONDS) {
    companion object {
        /** The REQ asks for slightly more than the freshness window so a
         * skewed relay clock cannot hide a still-fresh listing; the store
         * still applies the strict 10-minute rule. */
        const val DEFAULT_SINCE_MARGIN_SECONDS: Long = 15L * 60L
    }
}

/** The user-editable relay URL set: validation is fail-loud (VAL-NOSTR-
 * RELAY) and the defaults live beside it (owner decision 4: damus.io,
 * nos.lol + two more, user-editable). A ws:// URL on a LAN address is
 * legitimate — that is the documented self-hosted-relay escape hatch —
 * so validation checks SHAPE, never the address family. */
object RelayUrls {

    const val MAX_RELAYS: Int = 8

    val DEFAULTS: List<String> = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.nostr.band",
    )

    /** Accepts ws:// and wss:// URLs with a non-empty host (explicit ports
     * allowed, scheme implied ports kept); refuses everything else. */
    fun validate(raw: String): String {
        val trimmed = raw.trim()
        val scheme = when {
            trimmed.startsWith("wss://") -> "wss://"
            trimmed.startsWith("ws://") -> "ws://"
            else -> throw IllegalArgumentException(
                "VAL-NOSTR-RELAY: relay URL must start with wss:// or ws:// ($raw)",
            )
        }
        val rest = trimmed.removePrefix(scheme)
        val hostPort = rest.substringBefore('/').substringBefore('?')
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':', missingDelimiterValue = "")
        require(host.isNotEmpty() && (port.isEmpty() || port.all(Char::isDigit))) {
            "VAL-NOSTR-RELAY: relay URL needs a host (host:port) after $scheme ($raw)"
        }
        // R2-2: the shape checks above are not enough — the URL must ALSO
        // round-trip through the same HTTP parser OkHttp's Request.Builder
        // uses, so an out-of-range port (":70000"), port 0, or a space in
        // the host is refused HERE at the settings boundary instead of
        // throwing uncaught from RelayPool.start later. A ws(s) URL parses
        // as its http(s) equivalent — same host/port authority rules.
        val parsed = cleanedForParser(trimmed).toHttpUrlOrNull()
        if (parsed == null || parsed.port !in 1..PORT_MAX) {
            throw IllegalArgumentException(
                "VAL-NOSTR-RELAY: relay URL is not dialable by the HTTP client " +
                    "($raw) — check the host and the port (1..$PORT_MAX)",
            )
        }
        return trimmed.removeSuffix("/")
    }

    /** OkHttp parses ws(s) URLs through the http(s) authority; keep the
     * original scheme in errors, the parser only needs the shape. */
    private fun cleanedForParser(url: String): String =
        url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")

    /** Normalizes a user-edited list: validated, deduped, capped, order kept. */
    fun sanitizeList(raw: List<String>): List<String> {
        val cleaned = raw.map(::validate).distinct()
        require(cleaned.isNotEmpty()) {
            "VAL-NOSTR-RELAY: the relay set is empty — add at least one relay"
        }
        require(cleaned.size <= MAX_RELAYS) {
            "VAL-NOSTR-RELAY: at most $MAX_RELAYS relays are supported (got ${cleaned.size})"
        }
        return cleaned
    }
}

/** NIP-13: mine a nonce tag until the id has [difficulty] leading zero
 * bits, then re-sign with the realm key. Bounded; loud on exhaustion. */
object PowMiner {

    const val MAX_DIFFICULTY: Int = 24
    const val MAX_ITERATIONS: Long = 5_000_000L

    /** Pulls the demanded difficulty out of a pow rejection message
     * ("pow: difficulty 20 required.", "minimum difficulty 8", ...). */
    fun parseDifficulty(message: String): Int? =
        Regex("(\\d+)").findAll(message)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .firstOrNull { it in 1..MAX_DIFFICULTY }

    fun mine(event: NostrEvent, difficulty: Int, key: NostrPrivateKey?): NostrEvent {
        require(difficulty in 1..MAX_DIFFICULTY) {
            "VAL-NOSTR-POW: demanded difficulty $difficulty is outside 1..$MAX_DIFFICULTY"
        }
        require(key != null) {
            "VAL-NOSTR-POW: a pow-demanding relay needs the realm key to re-sign"
        }
        val baseTags = event.tags.filterNot { it.isNotEmpty() && it[0] == "nonce" }
        var nonce = 0L
        while (nonce < MAX_ITERATIONS) {
            val tags = baseTags + listOf(
                listOf("nonce", nonce.toString(), difficulty.toString()),
            )
            val serialized = NostrEvent.serializedForId(
                event.pubkeyHex, event.createdAtSeconds, event.kind, tags, event.content,
            )
            val id = NostrEvent.computeId(serialized)
            if (leadingZeroBits(Hex.decode(id, "VAL-NOSTR-POW")) >= difficulty) {
                val sig = key.signSchnorr(Hex.decode(id, "VAL-NOSTR-POW"))
                return NostrEvent(
                    id, event.pubkeyHex, event.createdAtSeconds, event.kind, tags,
                    event.content, Hex.encode(sig),
                )
            }
            nonce++
        }
        throw IllegalArgumentException(
            "VAL-NOSTR-POW: exhausted $MAX_ITERATIONS nonces at difficulty $difficulty",
        )
    }

    internal fun leadingZeroBits(bytes: ByteArray): Int {
        var bits = 0
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            if (v == 0) {
                bits += BITS_PER_BYTE
            } else {
                // The int's 32-bit leading zeros minus the 24 sign-padding
                // bits = this byte's own leading zero count.
                bits += Integer.numberOfLeadingZeros(v) - INT_PADDING_BITS
                break
            }
        }
        return bits
    }
}
