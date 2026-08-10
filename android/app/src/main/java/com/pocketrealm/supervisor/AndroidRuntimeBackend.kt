package com.pocketrealm.supervisor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Binder
import android.os.IBinder
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.client.ClientDisplayService
import com.pocketrealm.client.ClientRuntimeSelector
import com.pocketrealm.client.ClientRuntimeService
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.IClientDisplayControl
import com.pocketrealm.client.IClientRuntimeControl
import com.pocketrealm.client.ManagedClientStore
import com.pocketrealm.database.DatabaseService
import com.pocketrealm.database.IDatabaseControl
import com.pocketrealm.server.IRealmControl
import com.pocketrealm.server.IWorldControl
import com.pocketrealm.server.PreparedDataStore
import com.pocketrealm.server.RealmRuntimeService
import com.pocketrealm.server.WorldRuntimeService
import com.pocketrealm.storage.StorageRoots
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

internal enum class AutomaticAccountCreateAction { INITIALIZE_CREATED, ROTATE_COLLISION, FAIL }

/** Pure decision boundary: a collision is never eligible for privilege mutation. */
internal fun automaticAccountCreateAction(result: JSONObject): AutomaticAccountCreateAction =
    when (result.optString("code")) {
        "ACCOUNT_CREATED" -> if (result.optLong("accountId") > 0) {
            AutomaticAccountCreateAction.INITIALIZE_CREATED
        } else AutomaticAccountCreateAction.FAIL
        "ACCOUNT_EXISTS" -> AutomaticAccountCreateAction.ROTATE_COLLISION
        else -> AutomaticAccountCreateAction.FAIL
    }

/** Binder adapter that preserves the O08/O09 process fault boundaries. */
class AndroidRuntimeBackend(context: Context) : RuntimeBackend {
    private val appContext = context.applicationContext
    private val singlePlayerCredentials = SinglePlayerCredentialStore(appContext)
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
    private val client = ServiceHandle(appContext, ClientRuntimeService::class.java) {
        IClientRuntimeControl.Stub.asInterface(it)
    }
    private val display = ServiceHandle(appContext, ClientDisplayService::class.java) {
        IClientDisplayControl.Stub.asInterface(it)
    }

    override suspend fun preflight(profileId: String): RuntimeActionResult = withContext(Dispatchers.IO) {
        if (profileId !in setOf(DEFAULT_PROFILE, INTEGRATED_PROFILE) && BotProfiles.find(profileId) == null) {
            return@withContext RuntimeActionResult(false, "unknown profile")
        }
        val runtimeSettings = Settings(appContext).flow.first()
        val translator = when (runtimeSettings.provider) {
            Settings.RuntimeProvider.BOX64 -> ArmTranslationBackend.BOX64
            Settings.RuntimeProvider.FEX -> ArmTranslationBackend.FEX
        }
        val runtimeSelection = ClientRuntimeSelector.select(appContext, translator)
        if (!runtimeSelection.supported) {
            return@withContext RuntimeActionResult(false, runtimeSelection.reason)
        }
        val db = json(database.api().status())
        if (!db.optBoolean("initialized")) return@withContext RuntimeActionResult(false, "database is not initialized")
        if (profileId != DEFAULT_PROFILE) {
            runCatching {
                PreparedDataStore(File(StorageRoots.get(appContext).content, "o11-server")).requireActive()
            }
                .getOrElse { return@withContext RuntimeActionResult(false, "prepared data: ${it.message}") }
            runCatching { ManagedClientStore(appContext).load(CLIENT_BUILD_ID) }
                .getOrElse { return@withContext RuntimeActionResult(false, "managed client: ${it.message}") }
        } else {
            val data = File(StorageRoots.get(appContext).content, "o09-server/active/BUILD_PROVENANCE.json")
            if (!data.isFile) return@withContext RuntimeActionResult(false, "verified O09 server data is missing")
        }
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
            RuntimeComponent.CLIENT -> clientObservation(json(client.api().statusCurrent()))
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
                observation(component, json(database.api().status()), "RUNNING")
            }
            RuntimeComponent.REALM -> {
                json(realm.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                json(realm.api().start())
                waitReady(component) { json(realm.api().status()) }
            }
            RuntimeComponent.WORLD -> {
                json(world.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                when {
                    BotProfiles.find(profileId) != null -> json(world.api().startBotProfile(profileId))
                    profileId == INTEGRATED_PROFILE -> json(world.api().startNormal())
                    else -> json(world.api().start())
                }
                waitReady(component) { json(world.api().status()) }
            }
            RuntimeComponent.CLIENT -> startClient(owner, profileId)
        }
    }

    override suspend fun stop(component: RuntimeComponent, owner: ComponentOwner): RuntimeActionResult =
        withContext(Dispatchers.IO) {
            val value = when (component) {
                RuntimeComponent.DATABASE -> json(database.api().stopOwned(owner.instanceToken))
                RuntimeComponent.REALM -> json(realm.api().stopOwned(owner.instanceToken))
                RuntimeComponent.WORLD -> json(world.api().stopOwned(owner.instanceToken))
                RuntimeComponent.CLIENT -> return@withContext stopClient(owner)
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
                RuntimeComponent.CLIENT -> {
                    runCatching { display.api().release(owner.instanceToken) }
                    killBinder { client.api().forceStopOwned(owner.instanceToken) }.also { client.close() }
                }
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

    override suspend fun provisionAccount(
        owner: ComponentOwner,
        username: String,
        password: String,
        gmLevel: Int,
    ): AccountProvisionResult = withContext(Dispatchers.IO) {
        val status = observation(RuntimeComponent.WORLD, json(world.api().status()), "READY")
        if (status.owner != owner || !status.ready) {
            return@withContext AccountProvisionResult(false, "WORLD_NOT_READY")
        }
        val created = JSONObject(world.api().createAccount(username, password))
        if (!created.optBoolean("ok")) {
            return@withContext AccountProvisionResult(false, created.optString("code", "ACCOUNT_REJECTED"))
        }
        var result = created
        if (created.optInt("gmLevel", -1) != gmLevel) {
            result = JSONObject(world.api().setAccountGmLevel(username, gmLevel))
            if (!result.optBoolean("ok")) {
                return@withContext AccountProvisionResult(false, result.optString("code", "ACCOUNT_REJECTED"))
            }
        }
        AccountProvisionResult(
            true,
            if (created.optString("code") == "ACCOUNT_EXISTS") "ACCOUNT_EXISTS" else "ACCOUNT_CREATED",
            result.optLong("accountId", created.optLong("accountId")),
            result.optInt("gmLevel", gmLevel),
        )
    }

    override suspend fun recoverDatabase(): RuntimeActionResult = withContext(Dispatchers.IO) {
        // Recovery is a process-generation boundary. Drop every observation
        // binding first so Android completes old owner-loss teardown before a
        // fresh DatabaseService can launch a new MariaDB generation.
        database.close(); realm.close(); world.close(); client.close(); display.close()
        delay(750)
        val status = json(database.api().status())
        if (status.getString("state") == "RUNNING") {
            return@withContext RuntimeActionResult(false, "unowned database is still running")
        }
        if (status.optBoolean("restorePending")) {
            json(database.api().rollbackPendingRestore())
            return@withContext RuntimeActionResult(true, "interrupted restore rolled back to safety copy")
        }
        if (status.getBoolean("cleanMarker")) return@withContext RuntimeActionResult(true, "database generation is clean")
        val recovered = json(database.api().recover())
        RuntimeActionResult(recovered.getBoolean("ok"), "database recovery classified")
    }

    suspend fun createNamedBackup(name: String): JSONObject = withContext(Dispatchers.IO) {
        json(database.api().createNamedBackup(name))
    }

    suspend fun listBackups(): JSONObject = withContext(Dispatchers.IO) {
        json(database.api().listBackups())
    }

    suspend fun beginRestore(snapshotId: String): JSONObject = withContext(Dispatchers.IO) {
        json(database.api().beginRestore(snapshotId))
    }

    suspend fun commitRestore(token: String): JSONObject = withContext(Dispatchers.IO) {
        json(database.api().commitRestore(token))
    }

    suspend fun rollbackRestore(token: String): JSONObject = withContext(Dispatchers.IO) {
        json(database.api().rollbackRestore(token))
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

    private suspend fun startClient(owner: ComponentOwner, profileId: String): ComponentObservation {
        if (profileId != INTEGRATED_PROFILE && BotProfiles.find(profileId) == null) {
            return ComponentObservation(RuntimeComponent.CLIENT, ComponentLifecycle.FAILED, false, owner,
                detail = "client is not authorized for the O10 baseline profile")
        }
        val runtimeSettings = Settings(appContext).flow.first()
        val translator = when (runtimeSettings.provider) {
            Settings.RuntimeProvider.BOX64 -> ArmTranslationBackend.BOX64
            Settings.RuntimeProvider.FEX -> ArmTranslationBackend.FEX
        }
        val runtimeSelection = ClientRuntimeSelector.select(appContext, translator)
        check(runtimeSelection.supported) { runtimeSelection.reason }
        val singlePlayerAutoLogin = BotProfiles.find(profileId) != null
        if (singlePlayerAutoLogin) ensureSinglePlayerAccount(owner)
        json(client.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
        json(display.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
        val probe = json(client.api().probe(JSONObject()
            .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("provider", runtimeSelection.provider.id)
            .put("translator", translator.id)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty()).put("api", Build.VERSION.SDK_INT)
            .put("pageSize", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
            .put("clientId", CLIENT_BUILD_ID).toString()))
        check(probe.getBoolean("supported")) { probe.optString("reason", "client runtime unavailable") }
        val renderer = when {
            Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" -> "wined3d"
            runtimeSettings.renderer == Settings.Renderer.DXVK -> "dxvk"
            else -> "opengl"
        }
        val rendererPackageId = runtimeSettings.selectedDxvkPackageId()
            .takeIf { renderer == "dxvk" && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" }
        val prepareRequest = JSONObject()
            .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("clientId", CLIENT_BUILD_ID).put("renderer", renderer)
            .put("audioMode", "off").put("translator", translator.id)
            .put("inputSafeMode", runtimeSettings.inputSafeMode)
        rendererPackageId?.let { prepareRequest.put("rendererPackageId", it) }
        val prepared = json(client.api().preparePrefix(prepareRequest.toString()))
        json(display.api().prepare(
            prepared.getString("runtimeRoot"),
            owner.instanceToken,
            singlePlayerAutoLogin,
            CLIENT_BUILD_ID,
        ))
        val launchRequest = JSONObject()
            .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("prefixId", prepared.getString("prefixId"))
            .put("display", ":0").put("audioMode", "off")
            .put("translator", translator.id).put("renderer", renderer)
        rendererPackageId?.let { launchRequest.put("rendererPackageId", it) }
        val launched = json(client.api().launch(launchRequest.toString()))
        json(display.api().attachSession(owner.instanceToken, launched.getString("sessionId")))
        while (true) {
            val observed = clientObservation(json(client.api().statusCurrent()))
            if (observed.ready || observed.state == ComponentLifecycle.FAILED) return observed
            delay(100)
        }
    }

    /**
     * Ensure that the randomly named app-owned account still resolves to the
     * same database identity before its secret is made available to display.
     * A first-run ACCOUNT_EXISTS or a changed account id is a collision and is
     * rejected; no unknown password is ever guessed.
     */
    private suspend fun ensureSinglePlayerAccount(clientOwner: ComponentOwner) {
        val status = observation(RuntimeComponent.WORLD, json(world.api().status()), "READY")
        val worldOwner = status.owner
        check(status.ready && worldOwner != null && worldOwner.sessionId == clientOwner.sessionId) {
            "single-player account provisioning requires the owned ready world"
        }
        var credentials = singlePlayerCredentials.loadOrCreate()
        if (credentials.provisioned) {
            val current = json(world.api().accountStatus(credentials.username))
            if (current.optBoolean("accountExists")) {
                check(current.optLong("accountId") == credentials.accountId) {
                    "single-player account identity mismatch"
                }
                check(current.optInt("gmLevel", -1) == 0) {
                    "single-player account privilege mismatch"
                }
                return
            }
        }
        repeat(MAX_SINGLE_PLAYER_ACCOUNT_ATTEMPTS) {
            // Use the raw create surface here. The general provisionAccount()
            // helper may adjust GM level for ACCOUNT_EXISTS, which is correct
            // for an explicit user request but must never mutate an unrelated
            // random-name collision during automatic provisioning.
            val created = json(world.api().createAccount(credentials.username, credentials.password))
            val code = created.optString("code")
            when (automaticAccountCreateAction(created)) {
            AutomaticAccountCreateAction.INITIALIZE_CREATED -> {
                if (created.optInt("gmLevel", -1) != 0) {
                    val normalized = json(world.api().setAccountGmLevel(credentials.username, 0))
                    check(normalized.optInt("gmLevel", -1) == 0) {
                        "single-player account privilege initialization failed"
                    }
                }
                singlePlayerCredentials.markProvisioned(credentials, created.getLong("accountId"))
                return
            }
            AutomaticAccountCreateAction.ROTATE_COLLISION -> {
                credentials = singlePlayerCredentials.rotateUnprovisioned(credentials)
            }
            AutomaticAccountCreateAction.FAIL -> {
                error("single-player account provisioning failed: ${code.take(64)}")
            }
            }
        }
        error("single-player account provisioning exhausted collision retries")
    }

    private suspend fun stopClient(owner: ComponentOwner): RuntimeActionResult {
        val observed = clientObservation(json(client.api().statusCurrent()))
        if (observed.owner != owner) return RuntimeActionResult(false, "client ownership mismatch")
        json(display.api().requestClose(owner.instanceToken))
        json(client.api().closeOwned(owner.instanceToken))
        // WoW can take a little over ten seconds to acknowledge WM_DELETE and
        // Wine's native launcher then keeps its capture pipes open for a bounded
        // five-second drain.  Keep this below the supervisor's 60-second
        // component-stop deadline, but do not race that normal drain with a
        // forced-stop classification.
        repeat(450) {
            val status = json(client.api().statusCurrent())
            val rawState = status.optString("state")
            if (rawState == "EXITED" && status.optBoolean("cleanExit")) {
                json(client.api().releaseOwned(owner.instanceToken))
                json(display.api().release(owner.instanceToken))
                return RuntimeActionResult(true, "client exited after graceful close")
            }
            if (rawState == "FAILED" || rawState == "FORCE_STOPPED") {
                return RuntimeActionResult(false,
                    "client did not exit cleanly: ${status.optString("detail", rawState)}")
            }
            delay(100)
        }
        return RuntimeActionResult(false, "client graceful close timed out")
    }

    private fun clientObservation(value: JSONObject): ComponentObservation {
        val raw = value.optString("state", "EXITED")
        val state = when (raw) {
            "PREPARING", "READY", "STARTING" -> ComponentLifecycle.STARTING
            "RUNNING" -> ComponentLifecycle.READY
            "CLOSE_REQUESTED" -> ComponentLifecycle.STOPPING
            "EXITED", "FORCE_STOPPED" -> ComponentLifecycle.STOPPED
            "FAILED" -> ComponentLifecycle.FAILED
            else -> ComponentLifecycle.UNKNOWN
        }
        val owner = if (value.optBoolean("hasOwner")) ComponentOwner(
            value.getString("ownerSessionId"), value.getString("instanceToken")) else null
        return ComponentObservation(RuntimeComponent.CLIENT, state, state == ComponentLifecycle.READY,
            owner, detail = value.optString("detail", raw).take(512))
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
        database.close(); realm.close(); world.close(); client.close(); display.close()
    }

    companion object {
        const val DEFAULT_PROFILE = "mobile-low-v1"
        const val INTEGRATED_PROFILE = "mobile-low-v2-normal"
        const val BOT_LOW_25_PROFILE = "mobile-low-b1-25-v1"
        const val BOT_LIVELY_700_PROFILE = "mobile-lively-b700-v2"
        const val CLIENT_BUILD_ID = ClientRuntimeContract.WOW_5875_ID
        private const val MAX_SINGLE_PLAYER_ACCOUNT_ATTEMPTS = 3
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
