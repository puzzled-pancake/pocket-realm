package com.pocketrealm.supervisor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Binder
import android.os.IBinder
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.database.DatabaseService
import com.pocketrealm.database.IDatabaseControl
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.server.RealmRuntimeService
import com.pocketrealm.server.WorldRuntimeService
import com.pocketrealm.storage.StorageRoots
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Binder adapter that preserves the O08/O09 process fault boundaries. */
class AndroidRuntimeBackend(context: Context) : RuntimeBackend {
    private val appContext = context.applicationContext
    private val ownerLease = Binder()
    private val database = ServiceHandle(appContext, DatabaseService::class.java) {
        IDatabaseControl.Stub.asInterface(it)
    }
    private val realm = ServiceHandle(appContext, RealmRuntimeService::class.java) {
        IRealmControl.Stub.asInterface(it)
    }
    private val world = ServiceHandle(appContext, WorldRuntimeService::class.java) {
        IWorldControl.Stub.asInterface(it)
    }

    override suspend fun preflight(profileId: String): RuntimeActionResult = withContext(Dispatchers.IO) {
        if (profileId != DEFAULT_PROFILE) return@withContext RuntimeActionResult(false, "unknown profile")
        if (!Build.SUPPORTED_ABIS.contains("x86_64")) return@withContext RuntimeActionResult(false, "x86_64 ABI unavailable")
        val db = json(database.api().status())
        if (!db.optBoolean("initialized")) return@withContext RuntimeActionResult(false, "database is not initialized")
        val data = File(StorageRoots.get(appContext).content, "o09-server/active/BUILD_PROVENANCE.json")
        if (!data.isFile) return@withContext RuntimeActionResult(false, "verified O09 server data is missing")
        val native = File(appContext.applicationInfo.nativeLibraryDir)
        val required = listOf("libpocket_realmd_runtime.so", "libpocket_world_runtime.so")
        if (required.any { !File(native, it).isFile }) return@withContext RuntimeActionResult(false, "native realm runtime is incomplete")
        RuntimeActionResult(true, "profile, database, data, and native runtimes verified")
    }

    override suspend fun observe(component: RuntimeComponent): ComponentObservation = withContext(Dispatchers.IO) {
        when (component) {
            RuntimeComponent.DATABASE -> observation(component, json(database.api().status()), "RUNNING")
            RuntimeComponent.REALM -> observation(component, json(realm.api().status()), "READY")
            RuntimeComponent.WORLD -> observation(component, json(world.api().status()), "READY")
            RuntimeComponent.CLIENT -> ComponentObservation(component, ComponentLifecycle.STOPPED, false,
                detail = "client surface is not attached to O10 server-only service")
        }
    }

    override suspend fun start(
        component: RuntimeComponent,
        owner: ComponentOwner,
        profileId: String,
    ): ComponentObservation = withContext(Dispatchers.IO) {
        when (component) {
            RuntimeComponent.DATABASE -> {
                json(database.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                val before = json(database.api().status())
                if (!before.getBoolean("cleanMarker")) json(database.api().recover())
                json(database.api().start())
                json(database.api().queryHealth())
                observation(component, json(database.api().status()), "RUNNING")
            }
            RuntimeComponent.REALM -> {
                json(realm.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                json(realm.api().start())
                waitReady(component) { json(realm.api().status()) }
            }
            RuntimeComponent.WORLD -> {
                json(world.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                json(world.api().start())
                waitReady(component) { json(world.api().status()) }
            }
            RuntimeComponent.CLIENT -> ComponentObservation(
                component, ComponentLifecycle.FAILED, false, owner,
                detail = "client launch requires the O12 display/session attachment",
            )
        }
    }

    override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val value = when (component) {
                RuntimeComponent.DATABASE -> json(database.api().stopOwned(owner.instanceToken))
                RuntimeComponent.REALM -> json(realm.api().stopOwned(owner.instanceToken))
                RuntimeComponent.WORLD -> json(world.api().stopOwned(owner.instanceToken))
                RuntimeComponent.CLIENT -> return@withContext RuntimeActionResult(true, "no O10 client session")
            }
            RuntimeActionResult(value.getBoolean("ok"), value.optString("operation", "stopped"))
        }

    override suspend fun forceStop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val result = when (component) {
                RuntimeComponent.DATABASE -> killBinder { database.api().forceStopOwned(owner.instanceToken) }
                    .also { database.close() }
                RuntimeComponent.REALM -> killBinder { realm.api().forceStopOwned(owner.instanceToken) }
                    .also { realm.close() }
                RuntimeComponent.WORLD -> killBinder { world.api().forceStopOwned(owner.instanceToken) }
                    .also { world.close() }
                RuntimeComponent.CLIENT -> RuntimeActionResult(true, "no O10 client session")
            }
            // Do not let a newly started component inherit a Binder generation
            // that is still finishing the old supervisor's unbind/onDestroy.
            if (component != RuntimeComponent.CLIENT) delay(500)
            result
        }

    override suspend fun saveWorld(owner: ComponentOwner): RuntimeActionResult = withContext(Dispatchers.IO) {
        val status = observation(RuntimeComponent.WORLD, json(world.api().status()), "READY")
        if (status.owner != owner) return@withContext RuntimeActionResult(false, "world ownership mismatch; save withheld")
        val saved = json(world.api().save())
        RuntimeActionResult(saved.getBoolean("ok"), saved.optString("error"))
    }

    override suspend fun recoverDatabase(): RuntimeActionResult = withContext(Dispatchers.IO) {
        // Recovery is a process-generation boundary. Drop every observation
        // binding first so Android completes old owner-loss teardown before a
        // fresh DatabaseService can launch a new MariaDB generation.
        database.close(); realm.close(); world.close()
        delay(750)
        val status = json(database.api().status())
        if (status.getString("state") == "RUNNING") {
            return@withContext RuntimeActionResult(false, "unowned database is still running")
        }
        if (status.getBoolean("cleanMarker")) return@withContext RuntimeActionResult(true, "database generation is clean")
        val recovered = json(database.api().recover())
        RuntimeActionResult(recovered.getBoolean("ok"), "database recovery classified")
    }

    private suspend fun waitReady(
        component: RuntimeComponent,
        read: suspend () -> JSONObject,
    ): ComponentObservation {
        while (true) {
            val value = read()
            val result = observation(component, value, "READY")
            if (result.ready || result.state == ComponentLifecycle.FAILED) return result
            delay(100)
        }
    }

    private fun observation(component: RuntimeComponent, value: JSONObject, readyState: String): ComponentObservation {
        val raw = value.getString("state")
        val state = when (raw) {
            "STOPPED" -> ComponentLifecycle.STOPPED
            "STARTING" -> ComponentLifecycle.STARTING
            readyState -> ComponentLifecycle.READY
            "STOPPING" -> ComponentLifecycle.STOPPING
            "FAILED" -> ComponentLifecycle.FAILED
            else -> ComponentLifecycle.UNKNOWN
        }
        val owner = if (value.optBoolean("hasOwner")) ComponentOwner(
            value.getString("ownerSessionId"), value.getString("instanceToken")) else null
        return ComponentObservation(
            component = component,
            state = state,
            ready = state == ComponentLifecycle.READY,
            owner = owner,
            pid = value.optInt("pid").takeIf { it > 0 },
            detail = value.optString("detail", raw).take(512),
        )
    }

    private fun json(raw: String): JSONObject = JSONObject(raw).also {
        check(it.optBoolean("ok")) { it.optString("error", "component control request failed") }
    }

    private inline fun killBinder(block: () -> String): RuntimeActionResult = try {
        val response = block()
        if (response.isBlank()) RuntimeActionResult(true, "owned process terminated")
        else RuntimeActionResult(JSONObject(response).optBoolean("ok"), "owned process terminated")
    } catch (_: android.os.DeadObjectException) {
        RuntimeActionResult(true, "owned process terminated")
    } catch (_: android.os.RemoteException) {
        RuntimeActionResult(true, "owned process binder died")
    }

    override fun close() {
        database.close(); realm.close(); world.close()
    }

    companion object {
        const val DEFAULT_PROFILE = "mobile-low-v1"
        const val CLIENT_BUILD_ID = ClientRuntimeContract.WOW_5875_ID
    }
}

private class ServiceHandle<T>(
    private val context: Context,
    private val serviceType: Class<*>,
    private val convert: (IBinder) -> T,
) : AutoCloseable {
    private val lock = Mutex()
    @Volatile private var remote: T? = null
    private var connection: ServiceConnection? = null
    private var pending: CompletableDeferred<T>? = null

    suspend fun api(): T {
        remote?.let { return it }
        val deferred = lock.withLock {
            remote?.let { return it }
            pending?.let { return@withLock it }
            CompletableDeferred<T>().also { wait ->
                pending = wait
                val candidate = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                        val value = convert(binder)
                        remote = value
                        pending?.complete(value)
                        pending = null
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = disconnected(this)
                    override fun onBindingDied(name: ComponentName?) = disconnected(this)
                    override fun onNullBinding(name: ComponentName?) {
                        pending?.completeExceptionally(IllegalStateException("${serviceType.simpleName} returned null Binder"))
                        disconnected(this)
                    }
                }
                connection = candidate
                if (!context.bindService(Intent(context, serviceType), candidate, Context.BIND_AUTO_CREATE)) {
                    connection = null
                    pending = null
                    wait.completeExceptionally(IllegalStateException("${serviceType.simpleName} bind failed"))
                }
            }
        }
        return deferred.await()
    }

    private fun disconnected(candidate: ServiceConnection) {
        remote = null
        if (connection === candidate) {
            runCatching { context.unbindService(candidate) }
            connection = null
        }
    }

    override fun close() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        remote = null
        pending?.cancel()
        pending = null
    }
}
