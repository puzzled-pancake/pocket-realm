package com.pocketrealm.supervisor

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The host's friend-access hub (design-remote-accounts -), SHARED by
 * the desktop app and the Android service: owns the [FriendInviteStore],
 * the [ProvisionRateLimiter], and the [ProvisioningListener], and exposes
 * the host actions the Players surfaces render. The owner (the desktop
 * app model / the Android RealmService) feeds it the runtime state on
 * every change — the listener's lifecycle is a property of a HOSTING
 * SESSION: bound only while a LAN_HOST start with friend access
 * enabled is up (from WORLD_READY), stopped with the realm, never bound
 * otherwise.
 *
 * All moderation/provisioning verbs route through the shared
 * [DurableRuntimeSupervisor] ("provision/ban/gm verbs publish durable
 * actions the way provisionAccount already does") and append rows to the
 * host audit store (invites/provisioning/approvals/bans/GM changes —
 * no secrets, token hash only).
 */
// TooManyFunctions: the hub owns the design's host verb set (invites,
// approvals, moderation, gm, roster) by design — splitting it
// would scatter one state machine across files.
@Suppress("TooManyFunctions")
class FriendAccessController(
    storeDirectory: File,
    private val backend: RuntimeBackend,
    private val supervisor: DurableRuntimeSupervisor,
    logWarning: (String) -> Unit = {},
    logInfo: (String) -> Unit = {},
    storeOverride: FriendInviteStore? = null,
    /** The D7 LAN sign-up lane's host-side decision, passed through to the
     * listener (null = the lane answers its closed refusal). */
    private val signupLane: ((com.pocketrealm.server.FriendProvisionProtocol.SignupRequest) -> com.pocketrealm.discovery.iroh.SignupProtocol.Reply)? = null,
) {
    val store: FriendInviteStore =
        storeOverride ?: FriendInviteStore(storeDirectory)

    private val rateLimiter = ProvisionRateLimiter(store)

    private val makeListener: (String) -> ProvisioningListener = { address ->
        ProvisioningListener(
            store, rateLimiter, address,
            signupLane = signupLane,
            logWarning = logWarning, logInfo = logInfo,
        )
    }

    private val _listenerState = MutableStateFlow<ProvisioningListener.ListenerState>(
        ProvisioningListener.ListenerState.Off,
    )
    val listenerState: StateFlow<ProvisioningListener.ListenerState> = _listenerState.asStateFlow()

    private val _invites = MutableStateFlow<List<FriendInviteStore.InviteRow>>(emptyList())
    val invites: StateFlow<List<FriendInviteStore.InviteRow>> = _invites.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    @Volatile private var listener: ProvisioningListener? = null

    /** Set by [close]: once the owner is tearing the hub down, a late
     * [onRuntimeChanged] (a watcher body already in flight when the owner
     * cancelled its collector — cancel cannot interrupt a body with no
     * suspension points) must not re-bind the provisioning socket. The
     * flag is checked inside the SAME lock as the start/stop act, so the
     * exposure invariant ( bound only while a friend-access session is
     * up, stopped with the realm, never bound otherwise) holds on every
     * teardown path. */
    @Volatile private var closed = false

    /** Serializes the listener check-then-act (onRuntimeChanged vs
     * stopListener/close). */
    private val lifecycleLock = Any()

    /** The freshly created token, shown ONCE by the host UI (never stored). */
    private val _freshToken = MutableStateFlow<Pair<String, String>?>(null)
    val freshToken: StateFlow<Pair<String, String>?> = _freshToken.asStateFlow()

    // ------------------------------------------------------------------
    // Lifecycle — the owner calls this on every runtime/settings change.
    // ------------------------------------------------------------------

    /**
     * Aligns the listener with the runtime: bound exactly while a LAN_HOST
     * hosting session with friend access enabled has its world up
     * (WORLD_READY or later host phases); unbound otherwise. A bind failure
     * surfaces in [listenerState] as Failed — the UI shows it, never fakes.
     */
    fun onRuntimeChanged(phase: RuntimePhase, mode: RuntimeMode, friendAccessEnabled: Boolean, endpointAddress: String) {
        val worldUp = phase == RuntimePhase.WORLD_READY ||
            phase == RuntimePhase.RUNNING ||
            phase == RuntimePhase.PAUSED ||
            phase == RuntimePhase.CLIENT_FAILED
        val shouldListen = worldUp && mode == RuntimeMode.LAN_HOST &&
            friendAccessEnabled && endpointAddress.isNotEmpty()
        synchronized(lifecycleLock) {
            if (closed) return
            val current = listener
            if (shouldListen) {
                if (current == null || current.currentState() is ProvisioningListener.ListenerState.Off) {
                    current?.stop()
                    val next = makeListener(endpointAddress)
                    listener = next
                    _listenerState.value = next.start()
                }
            } else {
                current?.stop()
                if (current != null) {
                    listener = null
                    _listenerState.value = ProvisioningListener.ListenerState.Off
                }
            }
        }
        refresh()
    }

    /** The realm is going down (stop/close paths): drop the socket. */
    fun stopListener() {
        synchronized(lifecycleLock) {
            listener?.stop()
            listener = null
            _listenerState.value = ProvisioningListener.ListenerState.Off
        }
    }

    fun refresh() {
        // The listener can leave Listening on its own (a live-socket accept
        // failure publishes Failed): re-sync the flow from the live listener
        // so the hub/UI never shows Listening over a dead socket .
        listener?.let { _listenerState.value = it.currentState() }
        _invites.value = store.rows()
    }

    // ------------------------------------------------------------------
    // Host actions (the Players surfaces)
    // ------------------------------------------------------------------

    /** Generates an invite; the returned pair is (inviteId, token) with the
     * token shown ONCE. Null on the cap refusal. */
    fun createInvite(note: String): Pair<String, String>? {
        val result = store.create(note)
        refresh()
        return when (result) {
            is FriendInviteStore.CreateResult.Created -> {
                _freshToken.value = result.inviteId to result.tokenBase64Url
                _status.value = "Invite ${result.inviteId} created — copy the code now; it is shown only once."
                result.inviteId to result.tokenBase64Url
            }
            is FriendInviteStore.CreateResult.Refused -> {
                _status.value = result.reason
                null
            }
        }
    }

    fun revokeInvite(inviteId: String) {
        store.revoke(inviteId)
        refresh()
    }

    /** The once-shown invite code was dismissed (copied or closed). */
    fun dismissFreshToken() {
        _freshToken.value = null
    }

    fun rejectInvite(inviteId: String) {
        store.reject(inviteId)
        refresh()
        val rejectedName = store.rows().firstOrNull { it.inviteId == inviteId }?.username ?: inviteId
        _status.value = "Request from $rejectedName rejected."
    }

    /**
     * The approval leg : markApproved → the supervisor's verifier
     * provision (the durable-journal seam) → PROVISIONED (or back to
     * SUBMITTED on a relay failure, so the host can retry; the friend is
     * never punished for a host-side error).
     */
    suspend fun approveInvite(inviteId: String): String = withContext(Dispatchers.IO) {
        // the attempt bookkeeping
        // IS the retry-vs-collision discriminator. The first approval is this
        // invite's one provisioning attempt; a re-approval (after
        // approvalFailed) authorizes the ACCOUNT_EXISTS convergence onto OUR
        // attempt-1 row. A FIRST approval that lands on an existing account
        // is an identity COLLISION: refused (REJECTED, no provisioning, no
        // addon write — the seam refuses without the authorization), which
        // is the contract this controller's own doc records.
        val allowExistingAccount = store.hasPriorAttempt(inviteId)
        val verifier = store.markApproved(inviteId)
        refresh()
        if (verifier == null) {
            "Approve refused: the request is not inside its decision window."
        } else {
            val provision = supervisor.provisionFriendAccount(
                verifier.username, verifier.sHex, verifier.vHex, allowExistingAccount,
            )
            if (provision.ok) {
                store.markProvisioned(inviteId, provision.accountId)
                refresh()
                "Approved: ${verifier.username} is account ${provision.accountId}."
            } else if (provision.code == "ACCOUNT_EXISTS" && !allowExistingAccount) {
                store.refuseCollision(inviteId)
                refresh()
                "Refused: ${verifier.username} already exists on this realm — " +
                    "the friend picks another name (the request was not a retry)."
            } else {
                store.approvalFailed(inviteId, provision.code)
                refresh()
                val extra = provision.detail.takeIf { it != provision.code }?.let { ": $it" } ?: ""
                "Approval failed (${provision.code})$extra — the request returned to pending; " +
                    "you can retry or reject."
            }
        }
    }

    /** The friend's pending username already on the realm (the approval
     * card re-checks against account.username UNIQUE — the relay op returns
     * ACCOUNT_EXISTS and the host rejects;). */
    fun pendingRows(): List<FriendInviteStore.InviteRow> = store.pending()

    suspend fun roster(): RosterRead = backend.accountRoster()

    suspend fun characters(username: String): CharactersRead = backend.accountCharacters(username)

    /** Ban through the supervisor's journal seam, then the audit row. */
    suspend fun ban(username: String, duration: String, reason: String): Pair<Boolean, String> {
        val result = supervisor.banFriendAccount(username, duration, reason)
        store.auditModeration(
            FriendInviteStore.ACTION_BANNED, username = username, accountId = null,
            outcome = if (result.ok) "banned ($duration): ${result.detail}" else "ban-failed: ${result.detail}",
        )
        return result.ok to result.detail
    }

    /** Unban through the supervisor's journal seam, then the audit row. */
    suspend fun unban(username: String): Pair<Boolean, String> {
        val result = supervisor.unbanFriendAccount(username)
        store.auditModeration(
            FriendInviteStore.ACTION_UNBANNED, username = username, accountId = null,
            outcome = if (result.ok) "unbanned: ${result.detail}" else "unban-failed: ${result.detail}",
        )
        return result.ok to result.detail
    }

    /** Per-friend gmlevel 0..3 through the supervisor's journal seam,
     * then the audit row. */
    suspend fun setGmLevel(username: String, level: Int): Boolean {
        val ok = supervisor.setFriendGmLevel(username, level)
        store.auditModeration(
            FriendInviteStore.ACTION_GM_CHANGED, username = username, accountId = null,
            outcome = if (ok) "gmlevel=$level" else "gm-change-failed (level=$level)",
        )
        return ok
    }

    fun rateRows(): List<FriendInviteStore.RateRow> = store.rateRows()

    fun auditTail(): List<org.json.JSONObject> = store.auditTail()

    fun close() {
        synchronized(lifecycleLock) { closed = true }
        stopListener()
    }
}
