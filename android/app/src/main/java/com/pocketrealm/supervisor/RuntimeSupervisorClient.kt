package com.pocketrealm.supervisor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.pocketrealm.realm.RealmState
import com.pocketrealm.realm.ClientLaunchState
import com.pocketrealm.service.RealmService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Main-process observer for the foreground supervisor living in :supervisor. */
class RuntimeSupervisorClient(context: Context) {
    private val appContext = context.applicationContext

    fun observeRealmState(): Flow<RealmState> = callbackFlow {
        var remote: IRuntimeSupervisorControl? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                remote = IRuntimeSupervisorControl.Stub.asInterface(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) { remote = null }
            override fun onBindingDied(name: ComponentName?) { remote = null }
        }
        val bound = appContext.bindService(
            Intent(appContext, RealmService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound) { "RuntimeSupervisor bind failed" }
        // failure emission (design realm-status-browser.md) the poll
        // must never go SILENT on a dead lane. Binder death after a live
        // connection, a status that never arrives within the startup grace,
        // and a decode failure (an unknown phase enum from a newer
        // supervisor — the valueOf throw this loop used to swallow) each
        // emit an explicit SupervisorUnreachable within one poll interval,
        // so a degraded upstream can never read as a clean idle.
        val poller = launch {
            var connectedOnce = false
            var startupGracePolls = STARTUP_GRACE_POLLS
            while (isActive) {
                val decode = runCatching {
                    remote?.status()?.let(::decodeRealmState)
                }
                val state = decode.getOrNull()
                if (state != null) {
                    connectedOnce = true
                    startupGracePolls = STARTUP_GRACE_POLLS
                    trySend(state)
                } else {
                    val reason = unreachableReason(
                        decodeFailed = decode.isFailure,
                        remoteMissing = remote == null,
                        connectedOnce = connectedOnce,
                        startupGracePollsLeft = startupGracePolls,
                    )
                    startupGracePolls = (startupGracePolls - 1).coerceAtLeast(0)
                    if (reason != null) {
                        trySend(RealmState.SupervisorUnreachable(reason))
                    }
                }
                delay(250)
            }
        }
        awaitClose {
            poller.cancel()
            runCatching { appContext.unbindService(connection) }
        }
    }

    // Remote friend accounts (design-remote-accounts /).
    suspend fun provisionFriendAccount(
        username: String,
        sHex: String,
        vHex: String,
        allowExistingAccount: Boolean = false,
    ): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.provisionFriendAccount(username, sHex, vHex, allowExistingAccount)) }
    }

    suspend fun banFriendAccount(username: String, duration: String, reason: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.banFriendAccount(username, duration, reason)) }
    }

    suspend fun unbanFriendAccount(username: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.unbanFriendAccount(username)) }
    }

    suspend fun internetJoinStatus(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.internetJoinStatus()) }
    }

    suspend fun publicListingStatus(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.publicListingStatus()) }
    }

    suspend fun signupAccounts(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.signupAccounts()) }
    }

    suspend fun removeSignupAccount(username: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.removeSignupAccount(username)) }
    }

    suspend fun publicPlayers(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.publicPlayers()) }
    }

    suspend fun moderatePublicPlayer(action: String, target: String, reason: String): JSONObject =
        withContext(Dispatchers.IO) {
            transact { remote -> JSONObject(remote.moderatePublicPlayer(action, target, reason)) }
        }

    suspend fun exportCharacter(characterName: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.exportCharacter(characterName)) }
    }

    suspend fun importCharacter(dumpText: String, account: String, newName: String): JSONObject =
        withContext(Dispatchers.IO) {
            transact { remote -> JSONObject(remote.importCharacter(dumpText, account, newName)) }
        }

    suspend fun setFriendGmLevel(username: String, level: Int): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.setFriendGmLevel(username, level)) }
    }

    suspend fun friendRoster(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.friendRoster()) }
    }

    suspend fun friendCharacters(username: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.friendCharacters(username)) }
    }

    suspend fun friendAccessState(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.friendAccessState()) }
    }

    suspend fun createFriendInvite(note: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.createFriendInvite(note)) }
    }

    suspend fun revokeFriendInvite(inviteId: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.revokeFriendInvite(inviteId)) }
    }

    suspend fun rejectFriendRequest(inviteId: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.rejectFriendRequest(inviteId)) }
    }

    suspend fun approveFriendRequest(inviteId: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.approveFriendRequest(inviteId)) }
    }

    suspend fun createAccount(username: String, password: String, gmLevel: Int): JSONObject =
        withContext(Dispatchers.IO) {
            transact { remote -> JSONObject(remote.createAccount(username, password, gmLevel)) }
        }

    /**
     * F9 (census-backend-tools): the observe-only world presence sample for
     * the LAN screen's joined-mode players line — the DesktopAppModel
     * .lanPlayersOnline twin's transport leg. Null when the supervisor
     * cannot be reached (a dead lane renders nothing, never a guess).
     */
    suspend fun worldPresence(): JSONObject? = withContext(Dispatchers.IO) {
        runCatching { transact { remote -> JSONObject(remote.worldPresence()) } }.getOrNull()
    }

    suspend fun relaunchClient(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.relaunchClient()) }
    }

    suspend fun setCompanionMode(enabled: Boolean): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.setCompanionMode(enabled)) }
    }

    suspend fun createBackup(name: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.createBackup(name)) }
    }

    suspend fun listBackups(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.listBackups()) }
    }

    suspend fun restoreBackup(snapshotId: String): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.restoreBackup(snapshotId)) }
    }

    suspend fun backupStatus(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.backupStatus()) }
    }

    private suspend fun <T> transact(block: (IRuntimeSupervisorControl) -> T): T {
        lateinit var connection: ServiceConnection
        val remote = suspendCancellableCoroutine { continuation ->
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    if (continuation.isActive)
                        continuation.resume(IRuntimeSupervisorControl.Stub.asInterface(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) = Unit
                override fun onNullBinding(name: ComponentName?) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("RuntimeSupervisor returned null Binder"))
                    runCatching { appContext.unbindService(connection) }
                }
                override fun onBindingDied(name: ComponentName?) {
                    // the :supervisor process died before publishing its
                    // binder: without this the continuation never resumes and
                    // the caller hangs on Dispatchers.IO forever
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("supervisor process died before binding"))
                    runCatching { appContext.unbindService(connection) }
                }
            }
            if (!appContext.bindService(
                    Intent(appContext, RealmService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                continuation.resumeWithException(IllegalStateException("RuntimeSupervisor bind failed"))
            }
            continuation.invokeOnCancellation { runCatching { appContext.unbindService(connection) } }
        }
        // ServiceConnection callbacks are delivered on the main thread: only
        // the resume happens there, the binder transaction itself runs on IO
        // so long remote calls (createAccount waits on :world for up to 30 s)
        // cannot stall the main thread
        return try {
            withContext(Dispatchers.IO) { block(remote) }
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    companion object {
        internal fun decodeRealmState(raw: String): RealmState {
            val value = JSONObject(raw)
            check(value.optBoolean("ok"))
            val phase = RuntimePhase.valueOf(value.getString("phase"))
            val generationActive = value.optBoolean("supervisorGenerationActive")
            val rawError = value.optString("lastError").trim().takeIf { it.isNotEmpty() }
            // The UNVERIFIED_ORPHAN refusal and the DB_OWNED_BY_DEAD_SESSION
            // wedge carry a dedicated repair affordance; detect both on the
            // raw detail before humanizing.
            val unverifiedOrphan = rawError?.contains("UNVERIFIED_ORPHAN") == true
            val dbOwnedByDeadSession = rawError?.contains("DB_OWNED_BY_DEAD_SESSION") == true
            val lastError = rawError
                // Raw details (UNVERIFIED_ORPHAN, timeout classes)
                // go to logs; the UI sees human copy.
                ?.let(RuntimeFailureCopy::humanize)
            return when (phase) {
                RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED -> {
                    if (value.optBoolean("clean") && lastError == null) {
                        RealmState.Idle(
                            value.optString("stopReason").takeIf(String::isNotEmpty)
                                ?.let(RuntimeFailureCopy::humanize),
                        )
                    }                    else RealmState.Failed(
                        lastError ?: "Previous runtime needs recovery before it can start.",
                        unverifiedOrphan = unverifiedOrphan,
                        dbOwnedByDeadSession = dbOwnedByDeadSession,
                    )
                }
                RuntimePhase.PREPARING, RuntimePhase.DB_STARTING, RuntimePhase.REALM_STARTING,
                RuntimePhase.WORLD_STARTING, RuntimePhase.CLIENT_STARTING -> {
                    if (generationActive) RealmState.Starting(1)
                    else RealmState.Failed(
                        "The previous start was interrupted. Tap Start to recover safely and try again.",
                    )
                }
                // companion mode keeps the world process alive: surface it as a
                // running realm whose ticking is deliberately paused
                RuntimePhase.WORLD_READY, RuntimePhase.RUNNING, RuntimePhase.PAUSED, RuntimePhase.CLIENT_FAILED -> {
                    if (generationActive) RealmState.Running(
                        value.optLong("updatedAtWallMs", System.currentTimeMillis()),
                        RuntimeMode.valueOf(value.optString("runtimeMode", RuntimeMode.LOCAL.name)),
                        value.optString("realmEndpoint", RealmEndpoint.LOOPBACK_ADDRESS),
                        value.optString("advertisedAddress").takeIf { it.isNotEmpty() },
                        clientState = when (phase) {
                            RuntimePhase.WORLD_READY -> ClientLaunchState.NOT_STARTED
                            RuntimePhase.CLIENT_FAILED -> ClientLaunchState.FAILED
                            else -> ClientLaunchState.READY
                        },
                        clientFailure = lastError.takeIf { phase == RuntimePhase.CLIENT_FAILED },
                    )
                    else RealmState.Failed("Previous runtime was interrupted. Tap Start to recover safely.")
                }
                RuntimePhase.STOPPING -> if (generationActive) RealmState.Stopping(false)
                else RealmState.Failed("The previous stop was interrupted. Tap Start to recover safely.")
                RuntimePhase.RECOVERING -> if (generationActive)
                    RealmState.Recovering(value.optString("lastDurableAction"))
                else RealmState.Failed("The previous recovery was interrupted. Tap Start to recover safely.")
                RuntimePhase.ERROR -> RealmState.Failed(
                    lastError ?: "runtime failed",
                    unverifiedOrphan = unverifiedOrphan,
                    dbOwnedByDeadSession = dbOwnedByDeadSession,
                )
            }
        }

        /**
         * The emission decision (pure; pinned by
         * SupervisorUnreachableEmissionTest): which dead-lane shape this
         * poll tick is in, and whether it must emit the explicit
         * SupervisorUnreachable state instead of silence.
         *
         *  - a DECODE failure (an unknown phase enum from a newer
         *    supervisor — the valueOf throw the loop used to swallow)
         *    emits immediately: the failure is deterministic, so silence
         *    would last forever;
         *  - binder death AFTER a live connection emits immediately (the
         *    :supervisor process died mid-poll);
         *  - a status that never arrives exhausts the startup grace (the
         *    silence-before-first-bind fails LOUD too) and then emits;
         *  - anything else (still within the grace) stays silent — the
         *    pre-lane state, not a failure claim.
         */
        internal fun unreachableReason(
            decodeFailed: Boolean,
            remoteMissing: Boolean,
            connectedOnce: Boolean,
            startupGracePollsLeft: Int,
        ): String? = when {
            decodeFailed -> "supervisor status could not be decoded"
            remoteMissing && connectedOnce -> "the supervisor process disconnected"
            startupGracePollsLeft <= 0 -> "the supervisor never published its status"
            else -> null
        }

        /**
         * The startup grace: a:supervisor that has not published its
         * first status within this many polls (2 s at the 250 ms cadence)
         * stops being "starting up" and becomes explicitly unreachable —
         * the silence-before-first-bind must also fail LOUD, not wait
         * forever. Every successful status resets the grace.
         */
        internal const val STARTUP_GRACE_POLLS = 8
    }
}
