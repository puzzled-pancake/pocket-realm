package com.pocketrealm.service

import android.content.Context
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ActiveFlavorState
import com.pocketrealm.discovery.iroh.HostGatewayPolicy
import com.pocketrealm.discovery.iroh.InternetHostGateway
import com.pocketrealm.discovery.iroh.InternetJoinGateway
import com.pocketrealm.discovery.iroh.JoinHello
import com.pocketrealm.discovery.iroh.JoinPasscode
import com.pocketrealm.discovery.iroh.JoinPeer
import com.pocketrealm.discovery.iroh.SignupProtocol
import com.pocketrealm.discovery.nostr.HostListingPublisher
import com.pocketrealm.discovery.nostr.Hex
import com.pocketrealm.discovery.nostr.NostrFilter
import com.pocketrealm.discovery.nostr.NostrPrivateKey
import com.pocketrealm.discovery.nostr.RelayPool
import com.pocketrealm.discovery.nostr.RelayPoolCarrier
import com.pocketrealm.discovery.nostr.RelayUrls
import com.pocketrealm.log.AppLog
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.DurableRuntimeSupervisor
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import com.pocketrealm.supervisor.PublicHostModerationStore
import com.pocketrealm.supervisor.PublicHostModerator
import com.pocketrealm.supervisor.SignupAccountStore
import com.pocketrealm.storage.StorageRoots
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Host tab's "Publish to the realm browser" lane inside :supervisor:
 * wires [HostListingPublisher] to the persisted switch, the hosting
 * session, the world's presence sample and the in-app iroh host endpoint
 * ([InternetHostGateway]). Lives exactly as long as the service; every
 * stop path drops a gate, which retracts the listing and closes the
 * endpoint.
 */
internal class PublicListingHost(
    context: Context,
    private val scope: CoroutineScope,
    private val supervisor: DurableRuntimeSupervisor,
) {
    private val settings = Settings(context.applicationContext)
    private val signups = SignupAccountStore(
        java.io.File(StorageRoots(context.applicationContext).realmData, SignupAccountStore.FILE_NAME),
    )
    /** Player records and bans for the public realm (device, node and
     * account bans; the per-device sign-up limits). */
    private val moderation = PublicHostModerationStore(
        java.io.File(StorageRoots(context.applicationContext).realmData, PublicHostModerationStore.FILE_NAME),
    )
    private val moderator = PublicHostModerator(
        store = moderation,
        banAccount = { name, reason ->
            supervisor.banFriendAccount(name, PublicHostModerator.PERMANENT, reason)
        },
        unbanAccount = { name -> supervisor.unbanFriendAccount(name) },
        disconnect = { device, node -> InternetHostGateway.disconnect(device, node) },
    )
    /** One sign-up decision at a time: the cap check and the create are
     * one step, so two racing joiners cannot both take the last slot. */
    private val signupMutex = Mutex()
    private var jobs: List<Job> = emptyList()
    @Volatile private var signingKey: NostrPrivateKey? = null
    @Volatile private var latestGates =
        HostListingPublisher.Gates(online = false, listed = true, hostingWorldUp = false)

    /** The host endpoint's node id once it is up — the join password's
     * proof is derived from it (blank = the endpoint never came up). */
    @Volatile private var hostNodeHex: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()

    val publisher = HostListingPublisher(
        scope = scope,
        seams = HostListingPublisher.Seams(
            clockMs = System::currentTimeMillis,
            relays = { RelayUrls.DEFAULTS },
            identity = ::realmIdentity,
            content = ::beatContent,
            ensureHost = ::ensureHost,
            stopHost = { InternetHostGateway.stop() },
            hostReady = { InternetHostGateway.state.value is InternetHostGateway.State.Ready },
            carrierFactory = { relays ->
                RelayPoolCarrier(
                    RelayPool(
                        client = client,
                        scope = scope,
                        signingKeyProvider = { signingKey },
                        log = { AppLog.i(TAG, it) },
                    ).also { it.start(relays, NostrFilter()) },
                )
            },
        ),
    )

    fun start() {
        if (jobs.isNotEmpty()) return
        InternetHostGateway.peerSignupHandler = ::answerSignup
        InternetHostGateway.admissionHandler = { peer, request ->
            answerAdmission(peer, request).also { if (it.ok) moderation.markConnected(peer) }
        }
        InternetHostGateway.peerEvents = { event ->
            when (event.kind) {
                JoinHello.EVENT_LOGIN -> event.account?.let { moderation.recordLogin(it, event.peer) }
                JoinHello.EVENT_LEAVE -> moderation.markLeft(event.peer)
                else -> Unit
            }
        }
        jobs = listOf(
            scope.launch {
                combine(supervisor.state, settings.flow) { snapshot, s ->
                    HostListingPublisher.Gates(s.onlineHosting, s.listInBrowser, hostingWorldUp(snapshot))
                    // Sequential and de-duplicated: the identity writes a beat
                    // makes re-emit the settings flow, and must not cancel
                    // the beat that made them.
                }.distinctUntilChanged().collect { gates ->
                    latestGates = gates
                    publisher.evaluate(gates)
                }
            },
            // Presence, endpoint health and the 5-minute beat change
            // outside the two flows: re-evaluate on the gate grain.
            scope.launch {
                while (isActive) {
                    delay(GATE_POLL_MS)
                    runCatching { publisher.evaluate(latestGates) }
                }
            },
        )
    }

    /** Service teardown: bounded retract, then the endpoint closes. */
    fun close() {
        InternetHostGateway.peerSignupHandler = null
        InternetHostGateway.admissionHandler = null
        InternetHostGateway.peerEvents = null
        moderation.clearLive()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        runBlocking { withTimeoutOrNull(CLOSE_BUDGET_MS) { publisher.shutdown() } }
        InternetHostGateway.stop()
    }

    fun statusJson(): String {
        val state = publisher.state.value
        return JSONObject()
            .put("ok", true)
            .put("phase", state.phase.name)
            .put("copy", HostListingPublisher.statusCopy(state))
            .put("relayHost", state.descriptor?.relayHost() ?: "")
            .put("peers", InternetHostGateway.connectedPeers)
            .put("online", moderation.onlineAccounts().size)
            .put("beats", state.beats)
            // The unlisted-game invite code (present whenever the endpoint
            // is up, listed or not).
            .also { json ->
                state.descriptor?.let { descriptor ->
                    json.put(
                        "joinCode",
                        com.pocketrealm.discovery.iroh.InviteCode.encode(descriptor),
                    )
                }
            }
            .toString()
    }

    /**
     * One hello admission: bans first, then the join password (the proof
     * binds this joiner's node id), then the player limit — all inside
     * [PublicHostModerationStore.admit].
     */
    private suspend fun answerAdmission(peer: JoinPeer, request: JoinHello.Request): SignupProtocol.Reply {
        val s = settings.flow.first()
        val locked = s.gamePassword.isNotBlank()
        val hostNode = hostNodeHex
        val expectedProof = if (locked && hostNode != null) {
            JoinPasscode.proof(
                JoinPasscode.keyHex(s.gamePassword),
                hostNode,
                peer.nodeHex,
            )
        } else {
            null
        }
        return moderation.admit(
            peer,
            request,
            locked = locked,
            expectedProof = expectedProof,
            playerLimit = com.pocketrealm.server.ServerRuntimeContract.WORLD_PLAYER_LIMIT,
            onlinePlayers = InternetHostGateway.connectedPeers,
        )
    }

    /**
     * One self sign-up from a joiner: only while publishing with sign-up
     * open, under the cap, for a name not already signed up; the account
     * is created through the supervisor's validated verifier path (the
     * host flavor's expansion, gmlevel 0 — sign-up accounts are never GM).
     */
    private suspend fun answerSignup(request: SignupProtocol.Request, peer: JoinPeer?): SignupProtocol.Reply =
        signupMutex.withLock {
            val s = settings.flow.first()
            SignupProtocol.gate(s.onlineHosting && s.signupOpen, s.signupCap, signups.names(), request)
                ?.let { return@withLock it }
            moderation.signupGate(peer)?.let { return@withLock it }
            val result = supervisor.provisionFriendAccount(
                request.username, request.sHex, request.vHex, allowExistingAccount = false,
            )
            if (result.ok) {
                signups.add(request.username, System.currentTimeMillis())
                peer?.let { moderation.recordSignup(request.username, it) }
            }
            AppLog.i(TAG, "sign-up ${if (result.ok) "created" else "refused (${result.code})"}")
            SignupProtocol.fromProvisionCode(result.ok, result.code, request.username)
        }

    /**
     * The D7 LAN sign-up lane (the friend-access port's kind=signup
     * request): the same gate and account creation as the internet
     * sign-up, with the LAN-players switch and the game password's proof
     * in front. No device identity rides this lane — the listener's
     * per-source rate limiter is the abuse bound.
     */
    suspend fun answerLanSignup(
        request: com.pocketrealm.server.FriendProvisionProtocol.SignupRequest,
    ): SignupProtocol.Reply = signupMutex.withLock {
        val s = settings.flow.first()
        if (!s.signupOpen || !s.allowLanPlayers) {
            return@withLock SignupProtocol.Reply(
                false,
                SignupProtocol.SIGNUP_CLOSED,
                "This realm is not taking sign-ups right now.",
            )
        }
        if (s.gamePassword.isNotBlank()) {
            val expected = com.pocketrealm.server.FriendProvisionProtocol.lanSignupPass(s.gamePassword)
            if (!JoinPasscode.matches(expected, request.pass)) {
                return@withLock SignupProtocol.PASSWORD_WRONG_REPLY
            }
        }
        val ask = SignupProtocol.Request(request.username, request.sHex, request.vHex)
        SignupProtocol.gate(open = true, s.signupCap, signups.names(), ask)
            ?.let { return@withLock it }
        val result = supervisor.provisionFriendAccount(
            ask.username, ask.sHex, ask.vHex, allowExistingAccount = false,
        )
        if (result.ok) {
            signups.add(ask.username, System.currentTimeMillis())
        }
        AppLog.i(TAG, "LAN sign-up ${if (result.ok) "created" else "refused (${result.code})"}")
        SignupProtocol.fromProvisionCode(result.ok, result.code, ask.username)
    }

    /** The host's signed-up accounts list (names and times only). */
    fun signupAccountsJson(): String {
        val rows = JSONArray()
        signups.list().forEach { entry ->
            rows.put(JSONObject().put("username", entry.username).put("createdAtMs", entry.createdAtMs))
        }
        return JSONObject().put("ok", true).put("accounts", rows).toString()
    }

    /** Remove = a permanent ban (the world has no account delete) and the
     * name leaves the list, freeing a cap slot. */
    fun removeSignupAccount(username: String): String = runBlocking {
        if (!signups.names().any { it.equals(username, ignoreCase = true) }) {
            return@runBlocking JSONObject().put("ok", false).put("detail", "not a sign-up account").toString()
        }
        val ban = supervisor.banFriendAccount(username, PERMANENT_BAN, "removed from sign-ups")
        if (ban.ok) {
            signups.remove(username)
            moderation.recordAccountBan(username, "removed from sign-ups")
        }
        JSONObject().put("ok", ban.ok)
            .put("detail", if (ban.ok) "removed" else "the realm must be running to remove an account (${ban.detail})")
            .toString()
    }

    /** The host's players list (sign-ups and anyone seen logging in over
     * the internet) with ban state, plus the device bans. */
    fun publicPlayersJson(): String = moderation.playersJson(signups.list())

    /** One host ban verb: BAN_ACCOUNT, UNBAN_ACCOUNT, BAN_DEVICE,
     * UNBAN_DEVICE or BAN_BOTH, on an account name (or a device token
     * for UNBAN_DEVICE). */
    fun moderatePublicPlayer(action: String, target: String, reason: String): String = runBlocking {
        val verb = runCatching { PublicHostModerator.Action.valueOf(action) }.getOrNull()
            ?: return@runBlocking JSONObject().put("ok", false).put("detail", "unknown action").toString()
        val result = moderator.act(verb, target, reason)
        AppLog.i(TAG, "public moderation $verb ${if (result.ok) "applied" else "refused"}")
        JSONObject().put("ok", result.ok).put("detail", result.detail).toString()
    }

    private fun hostingWorldUp(snapshot: RuntimeSnapshot): Boolean =
        snapshot.runtimeMode == RuntimeMode.LAN_HOST && snapshot.phase in WORLD_UP_PHASES

    /** The realm's Nostr identity, generated and persisted at first use;
     * a corrupt stored key is replaced (the old listing ages out). */
    private suspend fun realmIdentity(): NostrPrivateKey? {
        val stored = settings.flow.first().nostrPrivateKeyHex
        val parsed = stored.takeIf { it.isNotBlank() }
            ?.let { runCatching { NostrPrivateKey.fromHex(it) }.getOrNull() }
        val key = parsed ?: NostrPrivateKey.generate().also { fresh ->
            settings.update { it.copy(nostrPrivateKeyHex = Hex.encode(fresh.bytes)) }
            AppLog.i(TAG, "realm listing identity generated (never displayed)")
        }
        signingKey = key
        return key
    }

    /** A refused name or an unresolvable lane throws; the publisher puts
     * the reason on the card. */
    private suspend fun beatContent(): com.pocketrealm.discovery.nostr.RealmListingContent? {
        val s = settings.flow.first()
        val presence = supervisor.worldPresence()
        val worldStarted = supervisor.state.value.components[RuntimeComponent.WORLD]?.startedAtWallMs
        val flavor = ActiveFlavorState(s.selectedFlavorOrNull())
            .resolve(ActiveFlavor.qualifiedFlavors, "the public listing names the hosted lane")
        return HostListingPublisher.contentFor(
            realmName = s.publicRealmName,
            flavorId = flavor.flavorId,
            // The stage the world actually runs (the stored "" = the
            // flavor's final patch); browsers refuse an empty stage.
            timeframeStage = com.pocketrealm.client.TimeframeStages.confValueFor(s.timeframeStage, flavor.flavorId),
            worldReady = presence.optString("state") == "READY",
            realPlayers = presence.optInt("realPlayers"),
            playerbotsEnabled = presence.optBoolean("playerbotsEnabled"),
            onlinePlayers = presence.optInt("onlinePlayers"),
            uptimeSeconds = worldStarted?.let { (System.currentTimeMillis() - it) / MILLIS_PER_SECOND },
            signupOpen = s.signupOpen,
            description = s.publicRealmDescription,
            maxPlayers = com.pocketrealm.server.ServerRuntimeContract.WORLD_PLAYER_LIMIT,
            appVersion = com.pocketrealm.BuildConfig.VERSION_NAME,
            locked = s.gamePassword.isNotBlank(),
        )
    }

    private suspend fun ensureHost(): Result<com.pocketrealm.discovery.iroh.IrohJoinDescriptor> {
        if (!InternetJoinGateway.transportAvailable()) {
            return Result.failure(IllegalStateException(
                "this build does not include the internet transport (libiroh_ffi.so)"))
        }
        val stored = settings.flow.first().irohGatewaySecretHex
        val (secret, fresh) = HostGatewayPolicy.secretOrFresh(stored)
        if (fresh) settings.update { it.copy(irohGatewaySecretHex = secret) }
        val hostAddress = supervisor.state.value.realmEndpoint.address
        return when (val ready = InternetHostGateway.ensure(scope, secret, hostAddress)) {
            is InternetHostGateway.State.Ready -> {
                hostNodeHex = ready.descriptor.nodeHex
                Result.success(ready.descriptor)
            }
            is InternetHostGateway.State.Failed ->
                Result.failure(IllegalStateException("${ready.code}: ${ready.detail}"))
            else -> Result.failure(IllegalStateException("the endpoint is still starting"))
        }
    }

    private companion object {
        const val TAG = "PublicListing"
        const val GATE_POLL_MS = 30_000L
        const val CLOSE_BUDGET_MS = 4_000L
        const val TIMEOUT_SECONDS = 5L
        const val PING_SECONDS = 30L
        const val MILLIS_PER_SECOND = 1_000L
        /** The relay's permanent-ban duration convention. */
        const val PERMANENT_BAN = "0"
        val WORLD_UP_PHASES = setOf(
            RuntimePhase.WORLD_READY,
            RuntimePhase.CLIENT_STARTING,
            RuntimePhase.RUNNING,
            RuntimePhase.PAUSED,
            RuntimePhase.CLIENT_FAILED,
        )
    }
}
