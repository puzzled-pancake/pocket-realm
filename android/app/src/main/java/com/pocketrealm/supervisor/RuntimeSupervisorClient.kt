package com.pocketrealm.supervisor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.pocketrealm.realm.RealmState
import com.pocketrealm.service.RealmService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        val poller = launch {
            while (isActive) {
                val state = runCatching { remote?.status()?.let(::decodeRealmState) }.getOrNull()
                if (state != null) trySend(state)
                delay(250)
            }
        }
        awaitClose {
            poller.cancel()
            runCatching { appContext.unbindService(connection) }
        }
    }

    private fun decodeRealmState(raw: String): RealmState {
        val value = JSONObject(raw)
        check(value.optBoolean("ok"))
        val phase = RuntimePhase.valueOf(value.getString("phase"))
        val generationActive = value.optBoolean("supervisorGenerationActive")
        return when (phase) {
            RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED -> RealmState.Idle
            RuntimePhase.PREPARING, RuntimePhase.DB_STARTING, RuntimePhase.REALM_STARTING,
            RuntimePhase.WORLD_STARTING, RuntimePhase.CLIENT_STARTING -> RealmState.Starting(1)
            RuntimePhase.WORLD_READY, RuntimePhase.RUNNING, RuntimePhase.CLIENT_FAILED -> {
                if (generationActive) RealmState.Running(
                    value.optLong("updatedAtWallMs", System.currentTimeMillis()))
                else RealmState.Failed("Previous runtime was interrupted. Tap Start to recover safely.")
            }
            RuntimePhase.STOPPING -> RealmState.Stopping(false)
            RuntimePhase.RECOVERING -> RealmState.Recovering(value.optString("lastDurableAction"))
            RuntimePhase.ERROR -> RealmState.Failed(value.optString("lastError", "runtime failed"))
        }
    }
}
