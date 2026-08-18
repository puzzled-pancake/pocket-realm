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

    suspend fun createAccount(username: String, password: String, gmLevel: Int): JSONObject =
        withContext(Dispatchers.IO) {
            transact { remote -> JSONObject(remote.createAccount(username, password, gmLevel)) }
        }

    suspend fun relaunchClient(): JSONObject = withContext(Dispatchers.IO) {
        transact { remote -> JSONObject(remote.relaunchClient()) }
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

    private suspend fun <T> transact(block: (IRuntimeSupervisorControl) -> T): T =
        suspendCancellableCoroutine { continuation ->
            lateinit var connection: ServiceConnection
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    try {
                        val value = block(IRuntimeSupervisorControl.Stub.asInterface(service))
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        runCatching { appContext.unbindService(connection) }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName?) = Unit
                override fun onNullBinding(name: ComponentName?) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("RuntimeSupervisor returned null Binder"))
                    runCatching { appContext.unbindService(connection) }
                }
            }
            if (!appContext.bindService(
                    Intent(appContext, RealmService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                continuation.resumeWithException(IllegalStateException("RuntimeSupervisor bind failed"))
            }
            continuation.invokeOnCancellation { runCatching { appContext.unbindService(connection) } }
        }

    companion object {
        internal fun decodeRealmState(raw: String): RealmState {
            val value = JSONObject(raw)
            check(value.optBoolean("ok"))
            val phase = RuntimePhase.valueOf(value.getString("phase"))
            val generationActive = value.optBoolean("supervisorGenerationActive")
            val lastError = value.optString("lastError").trim().takeIf { it.isNotEmpty() }
            return when (phase) {
                RuntimePhase.STOPPED, RuntimePhase.UNCONFIGURED -> {
                    if (value.optBoolean("clean") && lastError == null) RealmState.Idle
                    else RealmState.Failed(lastError ?: "Previous runtime needs recovery before it can start.")
                }
                RuntimePhase.PREPARING, RuntimePhase.DB_STARTING, RuntimePhase.REALM_STARTING,
                RuntimePhase.WORLD_STARTING, RuntimePhase.CLIENT_STARTING -> {
                    if (generationActive) RealmState.Starting(1)
                    else RealmState.Failed(
                        "The previous start was interrupted. Tap Start to recover safely and try again.",
                    )
                }
                RuntimePhase.WORLD_READY, RuntimePhase.RUNNING, RuntimePhase.CLIENT_FAILED -> {
                    if (generationActive) RealmState.Running(
                        value.optLong("updatedAtWallMs", System.currentTimeMillis()),
                        RuntimeMode.valueOf(value.optString("runtimeMode", RuntimeMode.LOCAL.name)),
                        value.optString("realmEndpoint", RealmEndpoint.LOOPBACK_ADDRESS),
                        clientState = when (phase) {
                            RuntimePhase.WORLD_READY -> ClientLaunchState.NOT_STARTED
                            RuntimePhase.CLIENT_FAILED -> ClientLaunchState.FAILED
                            else -> ClientLaunchState.READY
                        },
                        clientFailure = lastError.takeIf { phase == RuntimePhase.CLIENT_FAILED },
                    )
                    else RealmState.Failed("Previous runtime was interrupted. Tap Start to recover safely.")
                }
                RuntimePhase.STOPPING -> RealmState.Stopping(false)
                RuntimePhase.RECOVERING -> RealmState.Recovering(value.optString("lastDurableAction"))
                RuntimePhase.ERROR -> RealmState.Failed(lastError ?: "runtime failed")
            }
        }
    }
}
