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
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import com.pocketrealm.supervisor.RuntimeSnapshotJson
import com.pocketrealm.supervisor.StopMode
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Foreground durable RuntimeSupervisor, isolated in the :supervisor process. */
class RealmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var backend: AndroidRuntimeBackend
    private lateinit var supervisor: DurableRuntimeSupervisor
    private lateinit var operationWakeLock: PowerManager.WakeLock
    private var monitor: Job? = null
    @Volatile private var activeGeneration = false
    @Volatile private var foregroundActive = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        backend = AndroidRuntimeBackend(applicationContext)
        supervisor = DurableRuntimeSupervisor(backend, AtomicSupervisorJournal(applicationContext))
        operationWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:runtime-operation")
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
            ACTION_START -> launchOperation("start") {
                supervisor.start(AndroidRuntimeBackend.DEFAULT_PROFILE, includeClient = false)
            }
            ACTION_SAVE_EXIT -> launchOperation("save-stop") { supervisor.stop(StopMode.GRACEFUL) }
            ACTION_STOP -> launchOperation("forced-stop") { supervisor.stop(StopMode.FORCED) }
        }
        return START_NOT_STICKY
    }

    private val binder = object : IRuntimeSupervisorControl.Stub() {
        override fun status(): String = RuntimeSnapshotJson.encode(supervisor.state.value)
            .put("supervisorGenerationActive", activeGeneration)
            .toString()

        override fun start(profileId: String, includeClient: Boolean): String = accepted("start") {
            supervisor.start(profileId, includeClient)
        }

        override fun stop(forced: Boolean): String = accepted("stop") {
            supervisor.stop(if (forced) StopMode.FORCED else StopMode.GRACEFUL)
        }

        override fun relaunchClient(): String = accepted("relaunch-client") { supervisor.relaunchClient() }
        override fun recover(): String = accepted("recover") { supervisor.recover() }

        override fun forceComponentForTest(component: String): String {
            val selected = RuntimeComponent.valueOf(component.uppercase())
            val snapshot = supervisor.state.value
            val token = checkNotNull(snapshot.components.getValue(selected).instanceToken)
            val owner = ComponentOwner(checkNotNull(snapshot.sessionId), token)
            return accepted("force-${selected.name.lowercase()}") {
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
        // A Binder client may be the first caller, so promote the service before
        // accepting any finite lifecycle operation.
        foregroundActive = true
        startForeground(NOTIF_ID, buildNotification(supervisor.state.value))
        val operationId = UUID.randomUUID().toString()
        launchOperation("$name/$operationId", block)
        return JSONObject().put("ok", true).put("accepted", true)
            .put("operationId", operationId).toString()
    }

    private fun launchOperation(name: String, block: suspend () -> RuntimeOperation) {
        scope.launch {
            operationWakeLock.acquire(OPERATION_WAKE_LOCK_TIMEOUT_MS)
            val result = try {
                runCatching { block() }.getOrElse {
                    AppLog.e(TAG, "operation $name failed", it)
                    return@launch
                }
            } finally {
                runCatching { operationWakeLock.release() }
            }
            activeGeneration = result.snapshot.phase in setOf(
                RuntimePhase.WORLD_READY, RuntimePhase.RUNNING, RuntimePhase.CLIENT_FAILED)
            AppLog.i(TAG, "operation $name ok=${result.ok} phase=${result.snapshot.phase} detail=${result.detail}")
            if (result.snapshot.phase == RuntimePhase.STOPPED && result.snapshot.clean) {
                foregroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun monitorComponents() {
        while (scope.isActive) {
            delay(1_000)
            if (!activeGeneration) continue
            val snapshot = supervisor.state.value
            if (snapshot.phase !in MONITORED_PHASES) continue
            for (component in listOf(RuntimeComponent.DATABASE, RuntimeComponent.REALM, RuntimeComponent.WORLD)) {
                val expected = snapshot.components.getValue(component)
                if (expected.state != ComponentLifecycle.READY) continue
                val observed = runCatching { backend.observe(component) }.getOrNull()
                if (observed == null || !observed.ready || observed.owner?.instanceToken != expected.instanceToken) {
                    supervisor.componentFailed(component, "health/ownership probe failed")
                    break
                }
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
            RuntimePhase.RUNNING, RuntimePhase.WORLD_READY, RuntimePhase.CLIENT_FAILED -> "Local realm running"
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
            .also { if (active) it.addAction(0, getString(R.string.action_save_exit), saveExitIntent) }
            .build()
    }

    override fun onDestroy() {
        // No save or clean mark is legal here: force-stop/process death has no
        // callback guarantee. Dropping the bindings lets component services
        // apply their safe owner-loss policy; the dirty journal drives recovery.
        monitor?.cancel()
        scope.cancel()
        supervisor.close()
        AppLog.i(TAG, "supervisor destroyed with phase=${supervisor.state.value.phase}")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pocket_realm_status"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.pocketrealm.action.START"
        const val ACTION_SAVE_EXIT = "com.pocketrealm.action.SAVE_EXIT"
        const val ACTION_STOP = "com.pocketrealm.action.STOP"
        private const val TAG = "RuntimeSupervisor"
        private const val OPERATION_WAKE_LOCK_TIMEOUT_MS = 5 * 60 * 1_000L
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

        fun start(context: Context) {
            val intent = Intent(context, RealmService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
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
