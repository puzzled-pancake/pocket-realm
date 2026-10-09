package com.pocketrealm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.io.File
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.pocketrealm.R
import com.pocketrealm.log.AppLog
import com.pocketrealm.storage.Settings
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.supervisor.AndroidRuntimeBackend
import com.pocketrealm.supervisor.AtomicSupervisorJournal
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.DurableRuntimeSupervisor
import com.pocketrealm.supervisor.FriendAccessController
import com.pocketrealm.supervisor.FriendInviteStore
import com.pocketrealm.supervisor.ProvisioningListener
import com.pocketrealm.supervisor.IRuntimeSupervisorControl
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeOperation
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.LanInterfacePolicy
import com.pocketrealm.supervisor.InterfaceWatch
import com.pocketrealm.supervisor.TunnelInterfacePolicy
import com.pocketrealm.supervisor.OrphanSelfHealPolicy
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import com.pocketrealm.supervisor.RuntimeSnapshotJson
import com.pocketrealm.supervisor.StopMode
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.pocketrealm.BuildConfig

/** Synchronous admission plus balanced power-lease cleanup for service work. */
internal class ServiceOperationCoordinator {
    private val occupied = AtomicBoolean(false)

    internal class Reservation internal constructor(
        private val coordinator: ServiceOperationCoordinator,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) coordinator.occupied.set(false)
        }
    }

    val isOccupied: Boolean get() = occupied.get()

    fun tryReserve(): Reservation? =
        if (occupied.compareAndSet(false, true)) Reservation(this) else null

    suspend fun <T> runReserved(
        reservation: Reservation,
        acquirePower: () -> Unit,
        releasePower: () -> Unit,
        block: suspend () -> T,
    ): Result<T> {
        var powerAcquired = false
        return try {
            acquirePower()
            powerAcquired = true
            Result.success(block())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Result.failure(error)
        } finally {
            if (powerAcquired) runCatching { releasePower() }
            reservation.release()
        }
    }
}

internal data class ServiceOperationDisposition(
    val generationActive: Boolean,
    val removeForeground: Boolean,
    val stopService: Boolean,
)

internal object RealmServiceOperationPolicy {
    private val activePhases = setOf(
        RuntimePhase.PREPARING,
        RuntimePhase.DB_STARTING,
        RuntimePhase.REALM_STARTING,
        RuntimePhase.WORLD_STARTING,
        RuntimePhase.WORLD_READY,
        RuntimePhase.CLIENT_STARTING,
        RuntimePhase.RUNNING,
        RuntimePhase.PAUSED,
        RuntimePhase.CLIENT_FAILED,
        RuntimePhase.STOPPING,
        RuntimePhase.RECOVERING,
    )
    private val inactiveTerminalPhases = setOf(
        RuntimePhase.STOPPED,
        RuntimePhase.UNCONFIGURED,
        RuntimePhase.ERROR,
    )

    fun after(snapshot: RuntimeSnapshot, generationWasActive: Boolean): ServiceOperationDisposition {
        val componentStillActive = snapshot.components.values.any {
            it.state != ComponentLifecycle.STOPPED
        }
        val generationActive = snapshot.phase in activePhases || componentStillActive ||
            (generationWasActive && snapshot.phase !in inactiveTerminalPhases)
        val inactiveCleanTerminal = !generationActive && snapshot.clean &&
            snapshot.phase in inactiveTerminalPhases
        return ServiceOperationDisposition(
            generationActive = generationActive,
            removeForeground = inactiveCleanTerminal,
            stopService = inactiveCleanTerminal,
        )
    }
}

/** Foreground durable RuntimeSupervisor, isolated in the :supervisor process. */
class RealmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var backend: AndroidRuntimeBackend
    private lateinit var supervisor: DurableRuntimeSupervisor
    private lateinit var operationWakeLock: PowerManager.WakeLock
    private var monitor: Job? = null
    private lateinit var friendAccess: FriendAccessController
    @Volatile private var activeGeneration = false
    @Volatile private var foregroundActive = false
    private val operationCoordinator = ServiceOperationCoordinator()
    @Volatile private var maintenanceStatus = JSONObject().put("ok", true)
        .put("phase", "IDLE").toString()

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // Saved bot presets must resolve in the supervisor process so
        // usr5 launch identities validate identically to the UI process.
        com.pocketrealm.bots.BotCustomPresets.install(java.io.File(filesDir, "bots"))
        backend = AndroidRuntimeBackend(applicationContext)
        supervisor = DurableRuntimeSupervisor(backend, AtomicSupervisorJournal(applicationContext))
        // Remote friend accounts (design-remote-accounts 6/11): the shared
        // hub owns the invite store, the rate limiter, and the provisioning
        // listener; the collector below keeps the listener a property of a
        // hosting session on THIS platform too - bound only while a
        // LAN_HOST start with friend access enabled is up, stopped with the
        // realm, on every stop path (the collector watches the state).
        friendAccess = FriendAccessController(
            storeDirectory = File(StorageRoots(applicationContext).realmData, FRIEND_ACCESS_STORE_DIR),
            backend = backend,
            supervisor = supervisor,
            logWarning = { AppLog.w(TAG, it) },
            logInfo = { AppLog.i(TAG, it) },
        )
        scope.launch {
            // The listener is a property of (hosting session × friend-access
            // setting), not of supervisor state alone: combining BOTH flows
            // means a mid-session toggle takes effect immediately — binding
            // 3725 the moment the toggle turns ON during hosting, and
            // UNbinding it the moment the toggle turns OFF (design-remote-
            // accounts §6's exposure invariant, "with friend-access off it
            // is never bound"). The desktop watcher's exact shape
            // (DesktopAppModel friendAccessWatcher); a settings-only
            // re-read that waits for the next unrelated state emission
            // would leave the socket bound after the host revoked it.
            combine(
                supervisor.state,
                Settings(applicationContext).flow,
            ) { snapshot, settings -> snapshot to settings }.collectLatest { (snapshot, settings) ->
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
        operationWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:runtime-operation")
            .apply { setReferenceCounted(false) }
        scope.launch {
            supervisor.state.collectLatest { snapshot ->
                val notifications = getSystemService(NotificationManager::class.java)
                if (foregroundActive) notifications.notify(NOTIF_ID, buildNotification(snapshot))
                else notifications.cancel(NOTIF_ID)
            }
        }
        monitor = scope.launch { monitorComponents() }
        AppLog.i(TAG, "durable supervisor created pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foregroundActive = true
        startForeground(NOTIF_ID, buildNotification(supervisor.state.value))
        when (intent?.action) {
            ACTION_START -> submitOperation("start") {
                supervisor.start(
                    intent.getStringExtra(EXTRA_PROFILE_ID) ?: AndroidRuntimeBackend.INTEGRATED_PROFILE,
                    includeClient = intent.getBooleanExtra(EXTRA_INCLUDE_CLIENT, false),
                )
            }
            ACTION_HOST_LAN -> submitOperation("host-lan") {
                runCatching {
                    // Design-tunnel §4/§5: the optional ADVERTISED tunnel
                    // address rides the same start — when set, the servers
                    // bind the wildcard and the one realmlist row carries
                    // this address. The typed grammar validates it here; a
                    // malformed value refuses the start honestly.
                    RuntimeLaunchSpec.lanHost(
                        intent.getStringExtra(EXTRA_PROFILE_ID) ?: AndroidRuntimeBackend.INTEGRATED_PROFILE,
                        LanInterfacePolicy.selectForHosting().address,
                        includeClient = intent.getBooleanExtra(EXTRA_INCLUDE_CLIENT, false),
                        advertised = intent.getStringExtra(EXTRA_ADVERTISED_ADDRESS)
                            ?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }.fold(
                    onSuccess = { supervisor.start(it) },
                    onFailure = { RuntimeOperation(false, supervisor.state.value,
                        it.message ?: "LAN host endpoint is unavailable") },
                )
            }
            ACTION_JOIN_LAN -> submitOperation("join-lan") {
                runCatching {
                    RuntimeLaunchSpec.lanJoin(
                        AndroidRuntimeBackend.INTEGRATED_PROFILE,
                        checkNotNull(intent.getStringExtra(EXTRA_LAN_ADDRESS)),
                    )
                }.fold(
                    onSuccess = { supervisor.start(it) },
                    onFailure = { RuntimeOperation(false, supervisor.state.value,
                        it.message ?: "invalid LAN endpoint") },
                )
            }
            ACTION_SAVE_EXIT -> submitOperation("save-stop") { supervisor.stop(StopMode.GRACEFUL) }
            ACTION_STOP -> submitOperation("forced-stop") { supervisor.stop(StopMode.FORCED) }
            // Player-consented repair for the refusals the automatic lanes
            // keep: the UNVERIFIED_ORPHAN stack and the DB_OWNED_BY_DEAD_SESSION
            // database lock. Sent only by Home's confirmed "Force stop realm"
            // action, never by an automatic lane.
            ACTION_CONSENTED_ORPHAN_STOP ->
                submitOperation("consented-orphan-force-stop") {
                    supervisor.consentedForceStopOrphanStack()
                }
            else -> applyOperationDisposition(supervisor.state.value)
        }
        return START_NOT_STICKY
    }

    private val binder = object : IRuntimeSupervisorControl.Stub() {
        override fun status(): String = RuntimeSnapshotJson.encode(supervisor.state.value)
            .put("supervisorGenerationActive", activeGeneration)
            .toString()

        override fun start(profileId: String, includeClient: Boolean): String =
            accepted("start") { supervisor.start(profileId, includeClient) }

        override fun startSpec(launchSpecJson: String): String {
            val spec = runCatching { RuntimeLaunchSpec.fromJson(launchSpecJson) }.getOrElse {
                return JSONObject().put("ok", false)
                    .put("error", (it.message ?: "invalid launch spec").take(256)).toString()
            }
            return accepted("start-${spec.mode.name.lowercase()}") { supervisor.start(spec) }
        }

        override fun stop(forced: Boolean): String = accepted("stop") {
            supervisor.stop(if (forced) StopMode.FORCED else StopMode.GRACEFUL)
        }

        override fun relaunchClient(): String = accepted("relaunch-client") { supervisor.relaunchClient() }
        override fun setCompanionMode(enabled: Boolean): String =
            accepted("companion-mode") { supervisor.setCompanionMode(enabled) }
        override fun recover(): String = accepted("recover") { supervisor.recover() }
        override fun createAccount(username: String, password: String, gmLevel: Int): String =
            runBlocking(Dispatchers.IO) {
                val result = supervisor.provisionAccount(username, password, gmLevel)
                AppLog.i(TAG, "account control code=${result.code} id=${result.accountId} gm=${result.gmLevel}")
                JSONObject().put("ok", result.ok).put("code", result.code)
                    .put("accountId", result.accountId).put("gmLevel", result.gmLevel)
                    .put("detail", result.detail).toString()
            }
        // F9: the observe-only presence read for the LAN screen's
        // joined-mode players line (the desktop lanPlayersOnline twin);
        // runBlocking on IO like every other suspend supervisor verb here.
        override fun worldPresence(): String =
            runBlocking(Dispatchers.IO) { supervisor.worldPresence().toString() }
        // Remote friend accounts (design-remote-accounts): the same AIDL
        // seam carries the new verbs.
        override fun provisionFriendAccount(
            username: String,
            sHex: String,
            vHex: String,
            allowExistingAccount: Boolean,
        ): String = runBlocking(Dispatchers.IO) {
                val result = supervisor.provisionFriendAccount(
                    username, sHex, vHex, allowExistingAccount)
                AppLog.i(TAG, "friend account code=${result.code} id=${result.accountId}")
                JSONObject().put("ok", result.ok).put("code", result.code)
                    .put("accountId", result.accountId).put("detail", result.detail).toString()
            }
        override fun banFriendAccount(username: String, duration: String, reason: String): String =
            runBlocking(Dispatchers.IO) {
                val result = supervisor.banFriendAccount(username, duration, reason)
                JSONObject().put("ok", result.ok).put("detail", result.detail).toString()
            }
        override fun unbanFriendAccount(username: String): String = runBlocking(Dispatchers.IO) {
            val result = supervisor.unbanFriendAccount(username)
            JSONObject().put("ok", result.ok).put("detail", result.detail).toString()
        }
        override fun setFriendGmLevel(username: String, level: Int): String = runBlocking(Dispatchers.IO) {
            val ok = runCatching { supervisor.setFriendGmLevel(username, level) }.getOrDefault(false)
            JSONObject().put("ok", ok).toString()
        }
        override fun friendRoster(): String = runBlocking(Dispatchers.IO) {
            val read = runCatching { friendAccess.roster() }.getOrElse {
                return@runBlocking JSONObject().put("ok", false)
                    .put("reason", it.javaClass.simpleName).toString()
            }
            val rows = org.json.JSONArray()
            read.accounts.forEach { row ->
                rows.put(JSONObject()
                    .put("id", row.id).put("username", row.username).put("gmlevel", row.gmLevel)
                    .put("failedLogins", row.failedLogins).put("banned", row.banned)
                    .put("lastLogon", row.lastLogon).put("online", row.online))
            }
            JSONObject().put("ok", read.reason == null).put("reason", read.reason ?: "")
                .put("accounts", rows).toString()
        }
        override fun friendCharacters(username: String): String = runBlocking(Dispatchers.IO) {
            val read = runCatching { friendAccess.characters(username) }.getOrElse {
                return@runBlocking JSONObject().put("ok", false)
                    .put("reason", it.javaClass.simpleName).toString()
            }
            val rows = org.json.JSONArray()
            read.characters.forEach { row ->
                rows.put(JSONObject()
                    .put("guid", row.guid).put("name", row.name)
                    .put("race", row.race).put("class", row.klass).put("level", row.level))
            }
            JSONObject().put("ok", read.reason == null).put("reason", read.reason ?: "")
                .put("characters", rows).toString()
        }
        override fun friendAccessState(): String {
            friendAccess.refresh()
            val invites = org.json.JSONArray()
            friendAccess.store.rows().forEach { row ->
                invites.put(JSONObject()
                    .put("inviteId", row.inviteId).put("state", row.state.name)
                    .put("note", row.note).put("username", row.username ?: "")
                    .put("sourceIp", row.sourceIp ?: "").put("accountId", row.accountId ?: 0L)
                    .put("createdAtMs", row.createdAtMs))
            }
            val listener = friendAccess.listenerState.value
            val listenerJson = JSONObject().put("kind", listener.javaClass.simpleName)
            if (listener is ProvisioningListener.ListenerState.Listening)
                listenerJson.put("address", listener.address).put("port", listener.port)
            if (listener is ProvisioningListener.ListenerState.Failed)
                listenerJson.put("reason", listener.reason)
            return JSONObject().put("ok", true)
                .put("listener", listenerJson).put("invites", invites).toString()
        }
        override fun createFriendInvite(note: String): String {
            val created = friendAccess.createInvite(note)
            return if (created != null) JSONObject().put("ok", true)
                .put("inviteId", created.first).put("token", created.second).toString()
            else JSONObject().put("ok", false).put("error", "open-invite-cap").toString()
        }
        override fun revokeFriendInvite(inviteId: String): String {
            friendAccess.revokeInvite(inviteId)
            return JSONObject().put("ok", true).toString()
        }
        override fun rejectFriendRequest(inviteId: String): String {
            friendAccess.rejectInvite(inviteId)
            return JSONObject().put("ok", true).toString()
        }
        override fun approveFriendRequest(inviteId: String): String = runBlocking(Dispatchers.IO) {
            val verdict = friendAccess.approveInvite(inviteId)
            val row = friendAccess.store.rows().firstOrNull { it.inviteId == inviteId }
            JSONObject().put("ok", row?.state == FriendInviteStore.State.PROVISIONED)
                .put("verdict", verdict)
                .put("state", row?.state?.name ?: "")
                .put("accountId", row?.accountId ?: 0L).toString()
        }
        override fun createBackup(name: String): String = maintenance("backup") {
            backend.createNamedBackup(name)
        }
        override fun listBackups(): String = runBlocking(Dispatchers.IO) {
            backend.listBackups().toString()
        }
        override fun restoreBackup(snapshotId: String): String = maintenance("restore") {
            val begun = backend.beginRestore(snapshotId)
            val token = begun.getString("restoreToken")
            try {
                val started = supervisor.start(AndroidRuntimeBackend.INTEGRATED_PROFILE, includeClient = false)
                check(started.ok && started.snapshot.phase == RuntimePhase.WORLD_READY) {
                    "restored candidate did not reach world-ready: ${started.detail}"
                }
                val stopped = supervisor.stop(StopMode.GRACEFUL)
                check(stopped.ok && stopped.snapshot.clean) {
                    "restored candidate did not clean-stop: ${stopped.detail}"
                }
                backend.commitRestore(token).put("worldReadyVerified", true)
            } catch (error: Throwable) {
                runCatching { supervisor.stop(StopMode.FORCED) }
                runCatching { backend.rollbackRestore(token) }
                throw error
            }
        }
        override fun backupStatus(): String = maintenanceStatus

        override fun forceComponentForTest(component: String): String {
            check(BuildConfig.DEBUG) { "fault injection is debug-only" }
            val selected = RuntimeComponent.valueOf(component.uppercase())
            return accepted("force-${selected.name.lowercase()}") {
                val snapshot = supervisor.state.value
                val token = checkNotNull(snapshot.components.getValue(selected).instanceToken)
                val owner = ComponentOwner(checkNotNull(snapshot.sessionId), token)
                val killed = backend.forceStop(selected, owner)
                if (!killed.ok) RuntimeOperation(false, supervisor.state.value, killed.detail)
                else supervisor.componentFailed(selected, "fault injection: owned process killed")
            }
        }

        override fun killSupervisorForTest(): String {
            check(BuildConfig.DEBUG) { "test process kill is debug-only" }
            Thread({
                Thread.sleep(150)
                Process.killProcess(Process.myPid())
            }, "supervisor-kill-test").start()
            return JSONObject().put("ok", true).put("scheduled", true).toString()
        }
    }

    private fun accepted(name: String, block: suspend () -> RuntimeOperation): String {
        val reservation = operationCoordinator.tryReserve()
            ?: return busyOperationResponse()
        // A Binder client may be the first caller, so promote the service before
        // accepting any finite lifecycle operation.
        try {
            foregroundActive = true
            startForeground(NOTIF_ID, buildNotification(supervisor.state.value))
        } catch (error: Throwable) {
            foregroundActive = false
            reservation.release()
            AppLog.e(TAG, "could not promote operation $name to foreground", error)
            return JSONObject().put("ok", false)
                .put("error", (error.message ?: "foreground service unavailable").take(256))
                .toString()
        }
        val operationId = UUID.randomUUID().toString()
        launchOperation("$name/$operationId", reservation, block)
        return JSONObject().put("ok", true).put("accepted", true)
            .put("operationId", operationId).toString()
    }

    private fun maintenance(name: String, block: suspend () -> JSONObject): String {
        val reservation = operationCoordinator.tryReserve()
            ?: return busyOperationResponse()
        val snapshot = supervisor.state.value
        if (snapshot.phase != RuntimePhase.STOPPED || !snapshot.clean) {
            reservation.release()
            return JSONObject().put("ok", false).put("error", "maintenance requires a clean stopped runtime")
                .toString()
        }
        try {
            foregroundActive = true
            startForeground(NOTIF_ID, buildNotification(snapshot))
        } catch (error: Throwable) {
            foregroundActive = false
            reservation.release()
            AppLog.e(TAG, "could not promote maintenance $name to foreground", error)
            return JSONObject().put("ok", false)
                .put("error", (error.message ?: "foreground service unavailable").take(256))
                .toString()
        }
        val operationId = UUID.randomUUID().toString()
        maintenanceStatus = JSONObject().put("ok", true).put("phase", "RUNNING")
            .put("kind", name).put("operationId", operationId).toString()
        scope.launch(Dispatchers.IO) {
            val execution = operationCoordinator.runReserved(
                reservation = reservation,
                acquirePower = ::acquireOperationWakeLock,
                releasePower = ::releaseOperationWakeLock,
                block = block,
            )
            maintenanceStatus = execution.fold(
                onSuccess = { result ->
                    result.put("phase", "COMPLETE").put("kind", name)
                        .put("operationId", operationId).toString()
                },
                onFailure = { error ->
                    AppLog.e(TAG, "$name maintenance failed", error)
                    JSONObject().put("ok", false).put("phase", "FAILED").put("kind", name)
                        .put("operationId", operationId).put("errorClass", error.javaClass.simpleName)
                        .put("error", (error.message ?: "$name failed").take(512)).toString()
                },
            )
            applyOperationDisposition(supervisor.state.value)
        }
        return JSONObject().put("ok", true).put("accepted", true)
            .put("operationId", operationId).toString()
    }

    private fun submitOperation(name: String, block: suspend () -> RuntimeOperation) {
        val reservation = operationCoordinator.tryReserve()
        if (reservation == null) {
            AppLog.w(TAG, "operation $name rejected: another lifecycle or maintenance operation is active")
            return
        }
        launchOperation(name, reservation, block)
    }

    private fun busyOperationResponse(): String = JSONObject()
        .put("ok", false)
        .put("error", "another lifecycle or maintenance operation is active")
        .toString()

    private fun launchOperation(
        name: String,
        reservation: ServiceOperationCoordinator.Reservation,
        block: suspend () -> RuntimeOperation,
    ) {
        // A freshly recreated service deliberately starts with no ownership of
        // the dirty journal it loaded. Claim this process generation before
        // publishing any asynchronous recovery/start progress so observers can
        // distinguish live work from an interrupted persisted STARTING phase.
        activeGeneration = true
        scope.launch {
            val execution = operationCoordinator.runReserved(
                reservation = reservation,
                acquirePower = ::acquireOperationWakeLock,
                releasePower = ::releaseOperationWakeLock,
            ) {
                try {
                    block()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    AppLog.e(TAG, "operation $name failed", error)
                    supervisor.unexpectedOperationFailure(
                        "${error.javaClass.simpleName}: ${error.message ?: "$name failed"}",
                    )
                }
            }
            val result = execution.getOrNull()
            val snapshot = result?.snapshot ?: supervisor.state.value
            if (result != null) {
                AppLog.i(TAG,
                    "operation $name ok=${result.ok} phase=${result.snapshot.phase} detail=${result.detail}")
            } else {
                AppLog.e(TAG, "operation $name cleanup completed after an unrecoverable coordinator failure",
                    execution.exceptionOrNull())
            }
            applyOperationDisposition(snapshot)
        }
    }

    private fun acquireOperationWakeLock() {
        if (!operationWakeLock.isHeld) operationWakeLock.acquire()
    }

    private fun releaseOperationWakeLock() {
        if (operationWakeLock.isHeld) operationWakeLock.release()
    }

    private fun applyOperationDisposition(snapshot: RuntimeSnapshot) {
        val disposition = RealmServiceOperationPolicy.after(snapshot, activeGeneration)
        activeGeneration = disposition.generationActive
        if (disposition.removeForeground) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        if (disposition.stopService) stopSelf()
    }

    private suspend fun monitorComponents() {
        // Handheld Wi-Fi drivers routinely tear down and rebuild wlan0 for a
        // few seconds. A single missing-interface sample must not end the
        // session; only loss sustained past the grace window does. The
        // session identity anchors the window so a later generation never
        // inherits elapsed loss time from an earlier one, even when monitor
        // ticks are skipped across the generation boundary.
        var lanLossSessionId: String? = null
        var lanLossEndpoint: RealmEndpoint? = null
        var lanLossSinceMs = 0L
        while (scope.isActive) {
            delay(1_000)
            if (!activeGeneration) {
                lanLossSessionId = null
                lanLossEndpoint = null
                continue
            }
            // A health transition is a lifecycle operation too. If user work
            // owns the gate, leave it untouched and retry from fresh state.
            val reservation = operationCoordinator.tryReserve() ?: continue
            val checked = operationCoordinator.runReserved(
                reservation = reservation,
                acquirePower = ::acquireOperationWakeLock,
                releasePower = ::releaseOperationWakeLock,
            ) {
                try {
                    val snapshot = supervisor.state.value
                    if (!activeGeneration || snapshot.phase !in MONITORED_PHASES) {
                        lanLossSessionId = null
                        lanLossEndpoint = null
                        return@runReserved null
                    }
                    if (snapshot.runtimeMode == RuntimeMode.LAN_HOST) {
                        // Mode-aware watch (design-tunnel §10, the shared
                        // InterfaceWatch reconciliation): plain LAN hosting
                        // watches the private LAN endpoint (D2 verbatim); a
                        // tunnel-advertised session watches the TUNNEL
                        // adapter — physical-LAN churn must never kill a
                        // tunnel realm whose path never touched the LAN —
                        // and a loopback advertisement (the cloudflared
                        // recipe) has no interface to lose.
                        when (val watch = InterfaceWatch.forSession(snapshot.advertisedAddress)) {
                            InterfaceWatch.None -> {
                                lanLossSessionId = null
                                lanLossEndpoint = null
                            }
                            InterfaceWatch.Lan -> {
                                val endpoint = snapshot.realmEndpoint
                                if (LanInterfacePolicy.isCurrentPrivateInterface(endpoint)) {
                                    lanLossSessionId = null
                                    lanLossEndpoint = null
                                } else {
                                    if (lanLossEndpoint != endpoint || lanLossSessionId != snapshot.sessionId) {
                                        lanLossSessionId = snapshot.sessionId
                                        lanLossEndpoint = endpoint
                                        lanLossSinceMs = SystemClock.elapsedRealtime()
                                        AppLog.w(TAG,
                                            "LAN host interface $endpoint absent; loss grace window of " +
                                                "${LAN_HOST_INTERFACE_LOSS_GRACE_MS / 1000}s starts")
                                    }
                                    val lostForMs = SystemClock.elapsedRealtime() - lanLossSinceMs
                                    if (lostForMs >= LAN_HOST_INTERFACE_LOSS_GRACE_MS) {
                                        lanLossSessionId = null
                                        lanLossEndpoint = null
                                        AppLog.w(TAG,
                                            "LAN host interface lost for ${lostForMs / 1000}s; " +
                                                "generation will save and stop")
                                        // Design-lan D9: the stop carries its
                                        // reason so the host UI says WHY (the
                                        // desktop twin's stop reason, shared copy).
                                        return@runReserved supervisor.stop(
                                            StopMode.GRACEFUL,
                                            reason = "LAN_HOST_INTERFACE_LOST",
                                        )
                                    }
                                }
                            }
                            is InterfaceWatch.Tunnel -> {
                                if (TunnelInterfacePolicy.isCurrentTunnelInterface(watch.advertised)) {
                                    lanLossSessionId = null
                                    lanLossEndpoint = null
                                } else {
                                    if (lanLossEndpoint != null || lanLossSessionId != snapshot.sessionId) {
                                        lanLossSessionId = snapshot.sessionId
                                        lanLossEndpoint = null
                                        lanLossSinceMs = SystemClock.elapsedRealtime()
                                        AppLog.w(TAG,
                                            "Tunnel host address ${watch.advertised.value} absent; " +
                                                "loss grace window of " +
                                                "${LAN_HOST_INTERFACE_LOSS_GRACE_MS / 1000}s starts")
                                    }
                                    val lostForMs = SystemClock.elapsedRealtime() - lanLossSinceMs
                                    if (lostForMs >= LAN_HOST_INTERFACE_LOSS_GRACE_MS) {
                                        lanLossSessionId = null
                                        lanLossEndpoint = null
                                        AppLog.w(TAG,
                                            "Tunnel host address lost for ${lostForMs / 1000}s; " +
                                                "generation will save and stop " +
                                                "(TUNNEL_HOST_INTERFACE_LOST)")
                                        return@runReserved supervisor.stop(
                                            StopMode.GRACEFUL,
                                            reason = "TUNNEL_HOST_INTERFACE_LOST",
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        lanLossSessionId = null
                        lanLossEndpoint = null
                    }
                    val monitored = RuntimeComponent.entries.filter {
                        snapshot.components.getValue(it).state == ComponentLifecycle.READY
                    }
                    for (component in monitored) {
                        val expected = snapshot.components.getValue(component)
                        if (expected.state != ComponentLifecycle.READY) continue
                        val observed = runCatching { backend.observe(component) }.getOrNull()
                        // Ownerless-but-running is the healable orphan (plan
                        // F1): binder death cleared the claim and the
                        // component-side teardown may still be mid-flight.
                        // The heal lane counts grace ticks, then adopts and
                        // force-stops under the adopted owner; a DATABASE
                        // orphan routes to the existing recovery lane.
                        if (observed != null &&
                            OrphanSelfHealPolicy.isHealableOrphan(observed.state, observed.owner)
                        ) {
                            val healed = supervisor.selfHealOrphan(component)
                            if (healed != null) return@runReserved healed
                        } else if (observed == null || !observed.ready ||
                            observed.owner?.instanceToken != expected.instanceToken
                        ) {
                            return@runReserved supervisor.componentFailed(
                                component,
                                "health/ownership probe failed",
                            )
                        }
                    }
                    // Drive :world/:database foreground promotion from
                    // the pure presence policy (promote immediate, demote
                    // after three empty samples).
                    supervisor.reconcileForegroundPromotion()
                    null
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    AppLog.e(TAG, "runtime health monitor failed", error)
                    supervisor.unexpectedOperationFailure(
                        "health monitor ${error.javaClass.simpleName}: ${error.message ?: "failed"}",
                    )
                }
            }
            val transition = checked.getOrNull()
            if (transition != null) applyOperationDisposition(transition.snapshot)
            else checked.exceptionOrNull()?.let {
                AppLog.e(TAG, "runtime health monitor could not acquire its operation lease", it)
            }
        }
    }

    private fun buildNotification(snapshot: RuntimeSnapshot): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val saveExitIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, SaveExitReceiver::class.java).setAction(SaveExitReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = snapshot.phase !in setOf(RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED, RuntimePhase.ERROR)
        val title = when (snapshot.phase) {
            RuntimePhase.PAUSED -> "Companion conversation (world paused)"
            RuntimePhase.RUNNING, RuntimePhase.WORLD_READY, RuntimePhase.CLIENT_FAILED -> when (snapshot.runtimeMode) {
                RuntimeMode.LOCAL -> "Local realm running"
                RuntimeMode.LAN_HOST -> "LAN realm running"
                RuntimeMode.LAN_JOIN -> "LAN client running"
            }
            else -> "Pocket Realm: ${snapshot.phase.name.lowercase().replace('_', ' ')}"
        }
        val text = buildString {
            append(snapshot.requestedProfile ?: AndroidRuntimeBackend.DEFAULT_PROFILE)
            append(" - ")
            append(snapshot.lastDurableAction.replace('-', ' '))
            if (snapshot.phase == RuntimePhase.CLIENT_FAILED) append(" - client relaunch available")
        }.take(160)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
            .also { if (active) it.addAction(
                0,
                if (snapshot.runtimeMode == RuntimeMode.LAN_JOIN) "Exit client"
                else getString(R.string.action_save_exit),
                saveExitIntent,
            ) }
            .build()
    }

    override fun onDestroy() {
        // No save or clean mark is legal here: force-stop/process death has no
        // callback guarantee. Dropping the bindings lets component services
        // apply their safe owner-loss policy; the dirty journal drives recovery.
        monitor?.cancel()
        scope.cancel()
        // The provisioning socket dies with the service (paragraph 6).
        runCatching { friendAccess.close() }
        releaseOperationWakeLock()
        supervisor.close()
        AppLog.i(TAG, "supervisor destroyed with phase=${supervisor.state.value.phase}")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pocket_realm_status"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.pocketrealm.action.START"
        const val ACTION_HOST_LAN = "com.pocketrealm.action.HOST_LAN"
        const val ACTION_JOIN_LAN = "com.pocketrealm.action.JOIN_LAN"
        const val ACTION_SAVE_EXIT = "com.pocketrealm.action.SAVE_EXIT"
        const val ACTION_STOP = "com.pocketrealm.action.STOP"
        const val ACTION_CONSENTED_ORPHAN_STOP = "com.pocketrealm.action.CONSENTED_ORPHAN_STOP"
        private const val EXTRA_PROFILE_ID = "com.pocketrealm.extra.PROFILE_ID"
        private const val EXTRA_INCLUDE_CLIENT = "com.pocketrealm.extra.INCLUDE_CLIENT"
        private const val EXTRA_LAN_ADDRESS = "com.pocketrealm.extra.LAN_ADDRESS"
        private const val EXTRA_ADVERTISED_ADDRESS = "com.pocketrealm.extra.ADVERTISED_ADDRESS"
        private const val TAG = "RuntimeSupervisor"
private const val FRIEND_ACCESS_STORE_DIR = "friend-access"
        private const val LAN_HOST_INTERFACE_LOSS_GRACE_MS = 10_000L
        private val MONITORED_PHASES = setOf(RuntimePhase.WORLD_READY, RuntimePhase.RUNNING, RuntimePhase.PAUSED, RuntimePhase.CLIENT_FAILED)

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_realm),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                })
            }
        }

        fun start(
            context: Context,
            profileId: String = AndroidRuntimeBackend.INTEGRATED_PROFILE,
            includeClient: Boolean = false,
        ) {
            require(profileId == AndroidRuntimeBackend.INTEGRATED_PROFILE ||
                com.pocketrealm.bots.BotProfiles.find(profileId) != null) { "unsupported start profile" }
            val intent = Intent(context, RealmService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_INCLUDE_CLIENT, includeClient)
            context.startForegroundService(intent)
        }

        fun hostLan(
            context: Context,
            profileId: String = AndroidRuntimeBackend.INTEGRATED_PROFILE,
            includeClient: Boolean = false,
            /** Design-tunnel §4/§5: the advertised tunnel address (typed
             * parse at the service; null/blank = plain LAN hosting). */
            advertised: String? = null,
        ) {
            require(profileId == AndroidRuntimeBackend.INTEGRATED_PROFILE ||
                com.pocketrealm.bots.BotProfiles.find(profileId) != null) { "unsupported LAN host profile" }
            context.startForegroundService(
                Intent(context, RealmService::class.java).setAction(ACTION_HOST_LAN)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_INCLUDE_CLIENT, includeClient)
                    .apply { advertised?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_ADVERTISED_ADDRESS, it) } },
            )
        }

        fun joinLan(context: Context, address: String) {
            // Validate before an Intent is created; the service validates
            // independently. Endpoint model v2 (design-tunnel §4.4.3): the
            // join grammar takes LAN or tunnel (CGNAT) addresses.
            com.pocketrealm.supervisor.RealmEndpoint.parseJoinTarget(address)
            context.startForegroundService(
                Intent(context, RealmService::class.java).setAction(ACTION_JOIN_LAN)
                    .putExtra(EXTRA_LAN_ADDRESS, address),
            )
        }

        fun saveExit(context: Context) {
            context.startService(Intent(context, RealmService::class.java).setAction(ACTION_SAVE_EXIT))
        }

        /**
         * Player-consented repair behind Home's confirmed "Force stop realm"
         * action: the supervisor force-stops the leftover stack it cannot
         * verify as its own, releases a database claim the journal cannot
         * verify (an ended session's leftover lock), and leaves the engine to
         * the next start's recovery + prepare heal.
         */
        fun consentedOrphanStop(context: Context) {
            context.startService(
                Intent(context, RealmService::class.java).setAction(ACTION_CONSENTED_ORPHAN_STOP),
            )
        }
    }
}

class SaveExitReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) RealmService.saveExit(context)
    }
    companion object { const val ACTION = "com.pocketrealm.action.SAVE_EXIT" }
}
