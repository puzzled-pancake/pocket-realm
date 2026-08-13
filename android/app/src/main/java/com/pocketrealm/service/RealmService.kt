package com.pocketrealm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import com.pocketrealm.R
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.AndroidRuntimeBackend
import com.pocketrealm.supervisor.AtomicSupervisorJournal
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.DurableRuntimeSupervisor
import com.pocketrealm.supervisor.IRuntimeSupervisorControl
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeOperation
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.LanInterfacePolicy
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var activeGeneration = false
    @Volatile private var foregroundActive = false
    private val operationCoordinator = ServiceOperationCoordinator()
    @Volatile private var maintenanceStatus = JSONObject().put("ok", true)
        .put("phase", "IDLE").toString()

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        backend = AndroidRuntimeBackend(applicationContext)
        supervisor = DurableRuntimeSupervisor(backend, AtomicSupervisorJournal(applicationContext))
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
                    RuntimeLaunchSpec.lanHost(
                        intent.getStringExtra(EXTRA_PROFILE_ID) ?: AndroidRuntimeBackend.INTEGRATED_PROFILE,
                        LanInterfacePolicy.selectForHosting().address,
                        includeClient = intent.getBooleanExtra(EXTRA_INCLUDE_CLIENT, false),
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
        override fun recover(): String = accepted("recover") { supervisor.recover() }
        override fun createAccount(username: String, password: String, gmLevel: Int): String =
            runBlocking(Dispatchers.IO) {
                val result = supervisor.provisionAccount(username, password, gmLevel)
                AppLog.i(TAG, "account control code=${result.code} id=${result.accountId} gm=${result.gmLevel}")
                JSONObject().put("ok", result.ok).put("code", result.code)
                    .put("accountId", result.accountId).put("gmLevel", result.gmLevel)
                    .put("detail", result.detail).toString()
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
        while (scope.isActive) {
            delay(1_000)
            if (!activeGeneration) continue
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
                        return@runReserved null
                    }
                    val lanHostInterfaceLost = snapshot.runtimeMode == RuntimeMode.LAN_HOST &&
                        !LanInterfacePolicy.isCurrentPrivateInterface(snapshot.realmEndpoint)
                    if (lanHostInterfaceLost) {
                        AppLog.w(TAG, "LAN host interface changed; generation will save and stop")
                        return@runReserved supervisor.stop(StopMode.GRACEFUL)
                    }
                    val monitored = RuntimeComponent.entries.filter {
                        snapshot.components.getValue(it).state == ComponentLifecycle.READY
                    }
                    for (component in monitored) {
                        val expected = snapshot.components.getValue(component)
                        if (expected.state != ComponentLifecycle.READY) continue
                        val observed = runCatching { backend.observe(component) }.getOrNull()
                        if (observed == null || !observed.ready ||
                            observed.owner?.instanceToken != expected.instanceToken) {
                            return@runReserved supervisor.componentFailed(
                                component,
                                "health/ownership probe failed",
                            )
                        }
                    }
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
            RuntimePhase.RUNNING, RuntimePhase.WORLD_READY, RuntimePhase.CLIENT_FAILED -> when (snapshot.runtimeMode) {
                RuntimeMode.LOCAL -> "Local realm running"
                RuntimeMode.LAN_HOST -> "LAN realm running (experimental)"
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
        private const val EXTRA_PROFILE_ID = "com.pocketrealm.extra.PROFILE_ID"
        private const val EXTRA_INCLUDE_CLIENT = "com.pocketrealm.extra.INCLUDE_CLIENT"
        private const val EXTRA_LAN_ADDRESS = "com.pocketrealm.extra.LAN_ADDRESS"
        private const val TAG = "RuntimeSupervisor"
        private val MONITORED_PHASES = setOf(RuntimePhase.WORLD_READY, RuntimePhase.RUNNING, RuntimePhase.CLIENT_FAILED)

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
        ) {
            require(profileId == AndroidRuntimeBackend.INTEGRATED_PROFILE ||
                com.pocketrealm.bots.BotProfiles.find(profileId) != null) { "unsupported LAN host profile" }
            context.startForegroundService(
                Intent(context, RealmService::class.java).setAction(ACTION_HOST_LAN)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_INCLUDE_CLIENT, includeClient),
            )
        }

        fun joinLan(context: Context, address: String) {
            // Validate before an Intent is created; the service validates independently.
            com.pocketrealm.supervisor.RealmEndpoint.parseLan(address)
            context.startForegroundService(
                Intent(context, RealmService::class.java).setAction(ACTION_JOIN_LAN)
                    .putExtra(EXTRA_LAN_ADDRESS, address),
            )
        }

        fun saveExit(context: Context) {
            context.startService(Intent(context, RealmService::class.java).setAction(ACTION_SAVE_EXIT))
        }
    }
}

class SaveExitReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) RealmService.saveExit(context)
    }
    companion object { const val ACTION = "com.pocketrealm.action.SAVE_EXIT" }
}
