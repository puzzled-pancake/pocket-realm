package com.pocketrealm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pocketrealm.R
import com.pocketrealm.log.AppLog
import com.pocketrealm.realm.RealmHealth
import com.pocketrealm.realm.RealmState
import com.pocketrealm.realm.RealmSupervisor
import com.pocketrealm.realm.SaveReason
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that owns the realm lifecycle while the realm or client is
 * active. Started on user "Start Adventure" and torn down once the realm reaches
 * Idle after a save.
 *
 * Correctness rules honored here:
 *  - This service is the foreground contract, NOT a durability guarantee. The OS
 *    may kill the process at any instruction; dirty-start recovery (O08) is the
 *    real safety boundary. We do not save on onDestroy.
 *  - The notification always reflects the current [RealmState] and offers
 *    Save & Exit (the documented user-facing fast path).
 *  - A single supervisor coroutine serializes state transitions.
 */
class RealmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val supervisor = RealmSupervisor()
    private val transitionLock = Mutex()
    private var startupSim: Job? = null

    val state get() = supervisor.state

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        AppLog.i(TAG, "service created")
        // Observe state and keep the notification + UI bridge in sync.
        scope.launch {
            supervisor.state.collectLatest { s ->
                RealmBridge.publish(s)
                updateNotification(s)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always promote to foreground immediately. This service is only ever
        // started via startForegroundService (RealmService.start); promoting
        // unconditionally also covers a system-recreated entry (null intent)
        // before the ~5s FGS-promotion deadline.
        //
        // We return START_NOT_STICKY: if the system kills the process we do NOT
        // want a half-state resurrection. Per the durability model, dirty-start
        // recovery (O08) — not service recreation — is the real safety boundary,
        // and the next user launch drives a clean restart.
        startForeground(NOTIF_ID, buildNotification(supervisor.state.value))
        when (intent?.action) {
            ACTION_START -> startRealm()
            ACTION_SAVE_EXIT -> saveExit()
            ACTION_STOP -> stopRealm(forced = false)
        }
        return START_NOT_STICKY
    }

    private fun startRealm() {
        // Cancel any in-flight (simulated) bring-up before launching a new one,
        // and remember the Job so a later teardown can actually cancel it.
        startupSim?.cancel()
        startupSim = scope.launch {
            transitionLock.withLock {
                if (!supervisor.requestStart()) return@withLock
                // Placeholder startup sequence. O04-O05 will replace this with the
                // real native realm bring-up; the state transitions stay identical.
                bringUpHealthSimulated()
            }
        }
    }

    private fun saveExit() {
        scope.launch {
            transitionLock.withLock {
                if (!supervisor.requestSave(SaveReason.USER_SAVE_EXIT)) return@withLock
                delay(SAVE_SIM_MS) // O06+ replaces with real durable write.
                // Save complete: flow Saving -> Stopping -> Idle so the
                // notification reflects teardown before the foreground slot is
                // released. Do NOT markIdle() first, or requestStop() rejects and
                // the Stopping state is never observed.
                teardownLocked(forced = false)
            }
        }
    }

    private fun stopRealm(forced: Boolean) {
        scope.launch {
            transitionLock.withLock {
                teardownLocked(forced)
            }
        }
    }

    /**
     * Tear down the realm and stop the service. Caller MUST hold
     * [transitionLock]. Transitions the current non-Idle state -> Stopping ->
     * Idle, cancels any in-flight bring-up, then releases the foreground slot.
     */
    private suspend fun teardownLocked(forced: Boolean) {
        supervisor.requestStop(forced) // -> Stopping (no-op if already Idle)
        startupSim?.cancel()
        startupSim = null
        supervisor.markIdle()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Simulated bring-up that walks every health condition. This exists so the
     * shell is fully demonstrable before the native realm exists (O03-O05).
     */
    private suspend fun bringUpHealthSimulated() {
        val conds = RealmHealth.EMPTY.conditions.toMutableMap()
        // Drive each condition true in order; only mark Running once all hold.
        for (c in enumValuesOrdered()) {
            delay(STARTUP_STEP_MS)
            conds[c] = true
            AppLog.d(TAG, "health: ${c.name}=true")
        }
        supervisor.markRunning(RealmHealth(conds))
    }

    private fun enumValuesOrdered() = listOf(
        com.pocketrealm.realm.HealthCondition.DATABASE_OPEN,
        com.pocketrealm.realm.HealthCondition.SCHEMA_COMPATIBLE,
        com.pocketrealm.realm.HealthCondition.AUTH_READY,
        com.pocketrealm.realm.HealthCondition.LOCAL_ENDPOINTS_LISTENING,
        com.pocketrealm.realm.HealthCondition.WORLD_LOOP_RUNNING,
        com.pocketrealm.realm.HealthCondition.BOT_SUBSYSTEM_INITIALIZED,
    )

    private fun updateNotification(state: RealmState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(state))
    }

    private fun buildNotification(state: RealmState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val saveExitIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, SaveExitReceiver::class.java).setAction(SaveExitReceiver.ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val (title, text) = describe(state)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(state !is RealmState.Idle)
            .setOnlyAlertOnce(true)
            .also { if (state is RealmState.Running || state is RealmState.Saving) it.addAction(0, getString(R.string.action_save_exit), saveExitIntent) }
            .build()
    }

    private fun describe(state: RealmState): Pair<String, String> {
        val title = getString(R.string.app_name)
        val text = when (state) {
            is RealmState.Idle -> "Idle"
            is RealmState.Starting -> "Starting realm… (${state.attempt})"
            is RealmState.Running -> "Running · tap to play"
            is RealmState.Saving -> "Saving (${state.reason.name.lowercase()})…"
            is RealmState.Stopping -> "Stopping…"
            is RealmState.Recovering -> "Recovering: ${state.note}"
            is RealmState.Failed -> "Failed: ${state.message}"
        }
        return title to text
    }

    override fun onDestroy() {
        // Intentionally NOT saving here. Activity/service destruction is not a
        // durability guarantee (android rules). The durable path is the
        // dirty-generation marker + recovery (O08).
        AppLog.i(TAG, "service destroyed (state=${supervisor.state.value})")
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "pocket_realm_status"
        const val NOTIF_ID = 1
        const val ACTION_START = "com.pocketrealm.action.START"
        const val ACTION_SAVE_EXIT = "com.pocketrealm.action.SAVE_EXIT"
        const val ACTION_STOP = "com.pocketrealm.action.STOP"
        private const val TAG = "RealmService"
        private const val STARTUP_STEP_MS = 350L
        private const val SAVE_SIM_MS = 600L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_channel_realm),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = context.getString(R.string.notification_channel_desc)
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(ch)
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, RealmService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun saveExit(context: Context) {
            context.startService(Intent(context, RealmService::class.java).setAction(ACTION_SAVE_EXIT))
        }
    }
}

/**
 * Receiver backing the notification's "Save & Exit" action. Decouples the
 * notification tap from the UI so the user can save without the app foreground.
 */
class SaveExitReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) {
            RealmService.saveExit(context)
        }
    }
    companion object { const val ACTION = "com.pocketrealm.action.SAVE_EXIT" }
}
