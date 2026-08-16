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
import com.pocketrealm.client.ArmRendererAuto
import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.client.ClientTeardownGate
import com.pocketrealm.client.ClientAudioPolicy
import com.pocketrealm.client.ClientDisplayService
import com.pocketrealm.client.ClientDisplayCapabilities
import com.pocketrealm.client.ClientRuntimeSelector
import com.pocketrealm.client.ClientRuntimeService
import com.pocketrealm.client.ArmTranslationBackend
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import com.pocketrealm.client.AndroidGladioCapabilityProbe
import com.pocketrealm.client.AndroidSystemVulkanProbe
import com.pocketrealm.client.IClientDisplayControl
import com.pocketrealm.client.IClientRuntimeControl
import com.pocketrealm.client.ManagedClientStore
import com.pocketrealm.client.RendererPackageCatalog
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.client.VulkanDriverKind
import com.pocketrealm.database.DatabaseService
import com.pocketrealm.database.IDatabaseControl
import com.pocketrealm.log.AppLog
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
    private val client = ServiceHandle(appContext, ClientRuntimeService::class.java) {
        IClientRuntimeControl.Stub.asInterface(it)
    }
    private val display = ServiceHandle(appContext, ClientDisplayService::class.java) {
        IClientDisplayControl.Stub.asInterface(it)
    }

    override suspend fun preflight(spec: RuntimeLaunchSpec): RuntimeActionResult = withContext(Dispatchers.IO) {
        val profileId = spec.profileId
        if (profileId !in setOf(DEFAULT_PROFILE, INTEGRATED_PROFILE) && BotProfiles.find(profileId) == null) {
            return@withContext RuntimeActionResult(false, "unknown profile")
        }
        if (spec.mode == RuntimeMode.LAN_JOIN && profileId != INTEGRATED_PROFILE) {
            return@withContext RuntimeActionResult(false, "LAN join uses the normal client profile")
        }
        if (spec.requiresClient) {
            val runtimeSelection = ClientRuntimeSelector.select(appContext, ArmTranslationBackend.BOX64)
            if (!runtimeSelection.supported) {
                return@withContext RuntimeActionResult(false, runtimeSelection.reason)
            }
            runCatching { ManagedClientStore(appContext).load(CLIENT_BUILD_ID) }
                .getOrElse { return@withContext RuntimeActionResult(false, "managed client: ${it.message}") }
            if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
                val runtimeSettings = Settings(appContext).flow.first()
                runCatching {
                    // The selection the user made: auto resolves per GPU like
                    // Winlator; manual ids stay exact so their gates check the
                    // renderer the user actually chose.
                    val selectedId = runtimeSettings.selectedArmRendererId()
                    val autoSelection = ArmClientRendererCatalog.isAutoSelection(selectedId)
                    val effective = if (autoSelection) {
                        ArmRendererAuto.resolve()
                    } else {
                        ArmClientRendererCatalog.requireSelection(selectedId)
                    }
                    val resolvedDriverId = requireNotNull(
                        ArmRendererAuto.resolveVulkanDriverId(
                            runtimeSettings.selectedVulkanDriverId()
                        ),
                    ) { "The saved Vulkan driver is unknown. Choose a packaged driver." }
                    when (effective) {
                        ArmClientRenderer.DXVK -> {
                            // Auto degrades the DXVK package to the 1.10.3
                            // fallback when the device Vulkan version is below
                            // the selected package's floor; manual stays exact.
                            val requestedPackageId = if (autoSelection) {
                                ArmRendererAuto.resolveAutoDxvkPackageId(
                                    runtimeSettings.selectedDxvkPackageId()
                                )
                            } else {
                                runtimeSettings.selectedDxvkPackageId()
                            }
                            val renderer = RendererPackageCatalog.requireForRequest(
                                ArmTranslationBackend.BOX64,
                                effective.runtimeRenderer,
                                requestedPackageId,
                            )
                            val driver = VulkanDriverCatalog.requireForRequest(resolvedDriverId)
                            VulkanDriverCatalog.requireAvailableCompatiblePair(
                                resolvedDriverId,
                                renderer,
                                ArmRendererAuto.isAdrenoGpu(),
                                if (driver.kind == VulkanDriverKind.SYSTEM) {
                                    AndroidSystemVulkanProbe.probe()
                                } else null,
                            )
                        }
                        else -> ArmClientRendererCatalog.requireRuntimeRenderer(
                            effective.runtimeRenderer,
                            runCatching { AndroidGladioCapabilityProbe.probe(appContext) },
                            Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                        )
                    }
                }.getOrElse {
                    return@withContext RuntimeActionResult(
                        false,
                        "graphics selection: ${it.message ?: "capability check failed"}",
                    )
                }
            }
        }
        if (spec.mode == RuntimeMode.LAN_JOIN) {
            return@withContext RuntimeActionResult(true, "client and private LAN endpoint verified")
        }
        if (spec.mode == RuntimeMode.LAN_HOST) {
            val runtimeSettings = Settings(appContext).flow.first()
            if (!runtimeSettings.allowLanPlayers) {
                return@withContext RuntimeActionResult(false, "Allow LAN players is disabled in Settings")
            }
            if (!LanInterfacePolicy.isCurrentPrivateInterface(spec.endpoint)) {
                return@withContext RuntimeActionResult(false,
                    "LAN host address is not an active private IPv4 interface")
            }
        }
        val db = json(database.api().status())
        if (!db.optBoolean("providerReady")) {
            return@withContext RuntimeActionResult(false, "pinned database provider is incomplete")
        }
        if (profileId != DEFAULT_PROFILE) {
            runCatching {
                PreparedDataStore(File(StorageRoots.get(appContext).content, "o11-server"))
                    .requireActiveEnvelope()
            }
                .getOrElse { return@withContext RuntimeActionResult(false, "prepared data: ${it.message}") }
        } else {
            val data = File(StorageRoots.get(appContext).content, "o09-server/active/BUILD_PROVENANCE.json")
            if (!data.isFile) return@withContext RuntimeActionResult(
                false,
                "Prepared server world data is missing. Open Game files and finish preparing it, then try again.",
            )
        }
        val native = File(appContext.applicationInfo.nativeLibraryDir)
        val required = listOf("libpocket_realmd_runtime.so", "libpocket_world_runtime.so")
        if (required.any { !File(native, it).isFile }) return@withContext RuntimeActionResult(false, "native realm runtime is incomplete")
        RuntimeActionResult(
            true,
            if (spec.requiresClient) "server and client prerequisites verified"
            else "profile, database, data, and native server runtimes verified",
        )
    }

    override suspend fun observe(component: RuntimeComponent): ComponentObservation = withContext(Dispatchers.IO) {
        when (component) {
            RuntimeComponent.DATABASE -> observation(component, json(database.api().status()), "RUNNING")
            RuntimeComponent.REALM -> observation(component, json(realm.api().status()), "READY")
            RuntimeComponent.WORLD -> observation(component, json(world.api().status()), "READY")
            RuntimeComponent.CLIENT -> observeClientComposite()
        }
    }

    override suspend fun start(
        component: RuntimeComponent,
        owner: ComponentOwner,
        spec: RuntimeLaunchSpec,
    ): ComponentObservation = withContext(Dispatchers.IO) {
        val profileId = spec.profileId
        when (component) {
            RuntimeComponent.DATABASE -> {
                json(database.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                prepareDatabaseForStart()
                json(database.api().start())
                observation(component, json(database.api().status()), "RUNNING")
            }
            RuntimeComponent.REALM -> {
                if (spec.mode == RuntimeMode.LAN_HOST) check(
                    LanInterfacePolicy.isCurrentPrivateInterface(spec.endpoint)) {
                    "LAN host interface disappeared before realmd start"
                }
                json(realm.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                json(realm.api().startAt(spec.endpoint.address))
                waitReady(component) { json(realm.api().status()) }
            }
            RuntimeComponent.WORLD -> {
                if (spec.mode == RuntimeMode.LAN_HOST) check(
                    LanInterfacePolicy.isCurrentPrivateInterface(spec.endpoint)) {
                    "LAN host interface disappeared before world start"
                }
                json(world.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
                val nearbyInteractTriggerGuardMs = Settings(appContext).flow.first()
                    .nearbyInteractTriggerGuardMs
                when {
                    BotProfiles.find(profileId) != null ->
                        json(world.api().startBotProfileAt(
                            profileId, spec.endpoint.address, nearbyInteractTriggerGuardMs))
                    profileId == INTEGRATED_PROFILE -> json(world.api().startNormalAt(
                        spec.endpoint.address, nearbyInteractTriggerGuardMs))
                    else -> json(world.api().startAt(
                        spec.endpoint.address, nearbyInteractTriggerGuardMs))
                }
                waitReady(component) { json(world.api().status()) }
            }
            RuntimeComponent.CLIENT -> startClient(owner, spec)
        }
    }

    override suspend fun projectRealmEndpoint(
        databaseOwner: ComponentOwner,
        endpoint: RealmEndpoint,
    ): RuntimeActionResult = withContext(Dispatchers.IO) {
        val projected = json(database.api().projectRealmEndpoint(
            databaseOwner.instanceToken,
            endpoint.address,
            RealmEndpoint.WORLD_PORT,
        ))
        RuntimeActionResult(projected.getBoolean("ok"), projected.optString("operation"))
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
                RuntimeComponent.CLIENT -> forceStopClientAndThenDisplay(owner)
            }
            // Do not let a newly started component inherit a Binder generation
            // that is still finishing the old supervisor's unbind/onDestroy.
            delay(500)
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
        if (created.optString("code") == "ACCOUNT_EXISTS") {
            // Never adopt or mutate an existing identity before proving its password.
            val verified = JSONObject(world.api().verifyAccountPassword(username, password))
            if (!verified.optBoolean("passwordVerified")) {
                return@withContext AccountProvisionResult(false, "ACCOUNT_PASSWORD_MISMATCH")
            }
            return@withContext AccountProvisionResult(
                true,
                "ACCOUNT_VERIFIED",
                verified.optLong("accountId", created.optLong("accountId")),
                verified.optInt("gmLevel", created.optInt("gmLevel", 0)),
            )
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
            "ACCOUNT_CREATED",
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
        prepareDatabaseForStart()
        RuntimeActionResult(true, "database transactions recovered and stopped generation prepared")
    }

    /**
     * First-run, upgrade and interrupted-transaction preparation. This runs
     * inside RealmService's already-reserved foreground operation and wake
     * lease. DatabaseEngine remains the independent authority for every seal,
     * snapshot and process-drain check.
     */
    private suspend fun prepareDatabaseForStart() {
        repeat(MAX_DATABASE_PREPARATION_STEPS) {
            val status = json(database.api().status())
            when (DatabaseStartPreparation.next(status)) {
                DatabaseStartPreparation.Action.ROLLBACK_PENDING_RESTORE ->
                    json(database.api().rollbackPendingRestore())
                DatabaseStartPreparation.Action.RESUME_INITIALIZATION,
                DatabaseStartPreparation.Action.INITIALIZE ->
                    json(database.api().initialize())
                DatabaseStartPreparation.Action.RESUME_MIGRATIONS,
                DatabaseStartPreparation.Action.APPLY_PINNED_MIGRATIONS ->
                    json(database.api().applyPinnedMigrations())
                DatabaseStartPreparation.Action.RECOVER_DIRTY_GENERATION ->
                    json(database.api().recover())
                DatabaseStartPreparation.Action.READY -> return
            }
        }
        error("database preparation did not converge")
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
        // Bounded (de-vibe A5): a component wedged in STARTING used to spin a
        // supervisor coroutine forever; surface a FAILED observation instead.
        val deadline = System.currentTimeMillis() + WAIT_READY_TIMEOUT_MS
        while (true) {
            val value = read()
            val result = observation(component, value, "READY")
            if (result.ready || result.state == ComponentLifecycle.FAILED) return result
            if (System.currentTimeMillis() >= deadline) {
                return observation(
                    component,
                    value.put("state", "FAILED").put(
                        "exitReason",
                        "supervisor waitReady timeout after ${WAIT_READY_TIMEOUT_MS / MINUTES_MS}m",
                    ),
                    "READY",
                )
            }
            delay(100)
        }
    }

    private suspend fun startClient(owner: ComponentOwner, spec: RuntimeLaunchSpec): ComponentObservation {
        val profileId = spec.profileId
        if (profileId != INTEGRATED_PROFILE && BotProfiles.find(profileId) == null) {
            return ComponentObservation(RuntimeComponent.CLIENT, ComponentLifecycle.FAILED, false, owner,
                detail = "This client is not compatible with the selected game profile.")
        }
        val runtimeSettings = Settings(appContext).flow.first()
        val translator = ArmTranslationBackend.BOX64
        val runtimeSelection = ClientRuntimeSelector.select(appContext, translator)
        check(runtimeSelection.supported) { runtimeSelection.reason }
        val audioModeLiteral = ClientAudioPolicy.effectiveMode(
            runtimeSelection.provider,
            runtimeSettings.audioMode.name.lowercase(),
        )
        val requestedTweaksJson = runtimeSettings.tweaks.toJson()
        val armClient = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val autoRendererSelection = armClient && runtimeSettings.isAutoRenderer()
        val armRenderer = if (armClient) runtimeSettings.effectiveRenderer() else null
        val renderer = armRenderer?.runtimeRenderer ?: "wined3d"
        if (armRenderer != null) {
            ArmClientRendererCatalog.requireRuntimeRenderer(
                renderer,
                if (armRenderer != ArmClientRenderer.DXVK) runCatching {
                    AndroidGladioCapabilityProbe.probe(appContext)
                } else null,
            )
        }
        val requestedPackageId = if (autoRendererSelection) {
            ArmRendererAuto.resolveAutoDxvkPackageId(runtimeSettings.selectedDxvkPackageId())
        } else {
            runtimeSettings.selectedDxvkPackageId()
        }
        val rendererPackageId = requestedPackageId.takeIf {
            armRenderer == ArmClientRenderer.DXVK
        }
        if (autoRendererSelection && rendererPackageId != null &&
            rendererPackageId != runtimeSettings.selectedDxvkPackageId()
        ) {
            AppLog.w(
                TAG,
                "auto renderer: DXVK ${runtimeSettings.selectedDxvkPackageId()} exceeds this " +
                    "device's Vulkan version; launching with $rendererPackageId instead",
            )
        }
        val vulkanDriverId = ArmRendererAuto.resolveVulkanDriverId(
            runtimeSettings.selectedVulkanDriverId()
        ).takeIf { armRenderer == ArmClientRenderer.DXVK }
        if (armRenderer == ArmClientRenderer.DXVK) {
            val rendererPackage = RendererPackageCatalog.requireForRequest(
                translator,
                renderer,
                rendererPackageId,
            )
            val requestedDriver = VulkanDriverCatalog.requireForRequest(vulkanDriverId)
            VulkanDriverCatalog.requireAvailableCompatiblePair(
                vulkanDriverId,
                rendererPackage,
                ArmRendererAuto.isAdrenoGpu(),
                if (requestedDriver.kind == VulkanDriverKind.SYSTEM) {
                    AndroidSystemVulkanProbe.probe()
                } else null,
            )
        }
        var clientClaimed = false
        var clientReady = false
        try {
            json(client.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
            clientClaimed = true
            json(display.api().claim(owner.sessionId, owner.instanceToken, ownerLease))
            val probe = json(client.api().probe(JSONObject()
                .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
                .put("provider", runtimeSelection.provider.id)
                .put("translator", translator.id)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty()).put("api", Build.VERSION.SDK_INT)
                .put("pageSize", android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
                .put("clientId", CLIENT_BUILD_ID).toString()))
            check(probe.getBoolean("supported")) {
                probe.optString("reason", "client runtime unavailable")
            }
            val displaySelection = ClientDisplayCapabilities.requireSelection(
                appContext,
                runtimeSettings.displayProfileId,
                runtimeSettings.clientFrameCap,
            )
            val prepareRequest = JSONObject()
                .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
                .put("clientId", CLIENT_BUILD_ID).put("renderer", renderer)
                .put("audioMode", audioModeLiteral).put("translator", translator.id)
                .put("inputSafeMode", runtimeSettings.inputSafeMode)
                .put("realmEndpoint", spec.endpoint.address)
                .put("displayProfileId", displaySelection.profile.id)
                .put("frameCap", displaySelection.frameCap.fps)
                .put("tweaks", requestedTweaksJson)
            rendererPackageId?.let { prepareRequest.put("rendererPackageId", it) }
            vulkanDriverId?.let { prepareRequest.put("vulkanDriverId", it) }
            val prepared = json(client.api().preparePrefix(prepareRequest.toString()))
            val effectiveTweaksJson = ClientTweaksConfig.fromControlJson(
                prepared.getString("effectiveTweaks"),
            ).toJson()
            if (prepared.optBoolean("tweaksFallback")) {
                AppLog.w(
                    TAG,
                    "optional client tweaks skipped: imported build 5875 has an unqualified byte layout",
                )
            }
            // Prefix preparation can be slow. Load and verify the secret only at the
            // final handoff boundary, after every non-secret preparation step has passed.
            val userStore = if (spec.mode == RuntimeMode.LAN_JOIN) null else UserAccountStore(appContext)
            val candidate = userStore?.loadOrQuarantine()
            val autoLogin = AutoLoginPolicy.resolveAutoLogin(
                profileId = profileId,
                autoLoginOnLaunch = runtimeSettings.autoLoginOnLaunch,
                userAccountProvisioned = candidate != null,
                isBotProfile = { BotProfiles.find(it) != null },
            )
            var verifiedAccount: UserAccountStore.UserAccount? = null
            if (candidate != null && autoLogin.singlePlayerAutoLogin) {
                val worldStatus = observation(
                    RuntimeComponent.WORLD,
                    json(world.api().status()),
                    "READY",
                )
                val worldOwner = worldStatus.owner
                check(worldStatus.ready && worldOwner != null &&
                    worldOwner.sessionId == owner.sessionId) {
                    "Auto-login could not verify the running realm. " +
                        "Your saved account was kept; tap Retry game."
                }
                val proof = JSONObject(
                    world.api().verifyAccountPassword(candidate.username, candidate.password),
                )
                when (AutoLoginCredentialProof.evaluate(
                    expectedOwner = worldOwner,
                    expectedAccountId = candidate.accountId,
                    expectedGmLevel = candidate.gmLevel,
                    responseOk = proof.optBoolean("ok"),
                    passwordVerified = proof.optBoolean("passwordVerified"),
                    accountExists = proof.optBoolean("accountExists"),
                    accountId = proof.optLong("accountId"),
                    gmLevel = proof.optInt("gmLevel", -1),
                    ownerSessionId = proof.optString("ownerSessionId").takeIf(String::isNotEmpty),
                    ownerInstanceToken = proof.optString("instanceToken").takeIf(String::isNotEmpty),
                )) {
                    AutoLoginCredentialProof.Disposition.ACCEPT -> verifiedAccount = candidate
                    // Keep the record for explicit correction/clear. A realm
                    // proof can race a user replacing the saved account in the
                    // UI; deleting here could erase the newer credential based
                    // on rejection of the older candidate.
                    AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL -> error(
                        "The saved account no longer matches this realm. " +
                            "Create or verify it again, then tap Retry game. " +
                            "The saved account was kept.",
                    )
                    AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE -> error(
                        "Auto-login verification changed while the game was starting. " +
                            "Your saved account was kept; tap Retry game.",
                    )
                }
            }
            val displayPrepared = json(display.api().prepare(
                prepared.getString("runtimeRoot"),
                owner.instanceToken,
                verifiedAccount?.username.orEmpty(),
                verifiedAccount?.password.orEmpty(),
                runtimeSettings.effectiveAutoLoginTimings().toControlJson(),
                audioModeLiteral,
                CLIENT_BUILD_ID,
                renderer,
                vulkanDriverId.orEmpty(),
                rendererPackageId.orEmpty(),
                displaySelection.profile.id,
                displaySelection.frameCap.fps,
            ))
            check(prepared.getString("displayProfileId") ==
                displayPrepared.getString("displayProfile") &&
                prepared.getInt("virtualWidth") == displayPrepared.getInt("virtualWidth") &&
                prepared.getInt("virtualHeight") == displayPrepared.getInt("virtualHeight") &&
                prepared.getInt("frameCap") == displayPrepared.getInt("frameCap")) {
                "client runtime and Android display selected different display identities"
            }
            check(prepared.getString("renderer") == renderer &&
                displayPrepared.getString("renderer") == renderer) {
                "client runtime and Android display selected different renderer lanes"
            }
            check(displayPrepared.getBoolean("glxEnabled") ==
                (renderer == "opengl" || renderer == "virgl" || renderer == "wined3d")) {
                "Android display GLX lifecycle does not match the selected renderer"
            }
            if (vulkanDriverId != null) {
                check(prepared.getString("vulkanDriverId") == vulkanDriverId &&
                    displayPrepared.getString("vulkanDriverId") == vulkanDriverId) {
                    "client runtime and Android display selected different Vulkan drivers"
                }
                check(prepared.getString("rendererPackageId") == rendererPackageId &&
                    displayPrepared.getString("rendererPackageId") == rendererPackageId) {
                    "client runtime and Android display selected different renderer packages"
                }
                val driver = VulkanDriverCatalog.requireForRequest(vulkanDriverId)
                check(driver.kind != VulkanDriverKind.SYSTEM ||
                    displayPrepared.getBoolean("vulkanBridgeReady")) {
                    "Android system Vulkan bridge is not ready"
                }
            } else if (armClient) {
                check(prepared.isNull("vulkanDriverId") &&
                    displayPrepared.isNull("vulkanDriverId") &&
                    prepared.isNull("rendererPackageId") &&
                    displayPrepared.isNull("rendererPackageId")) {
                    "non-DXVK ARM renderer carried Vulkan/DXVK identities"
                }
            }
            val launchRequest = JSONObject()
                .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
                .put("prefixId", prepared.getString("prefixId"))
                .put("display", ":0").put("audioMode", audioModeLiteral)
                .put("translator", translator.id).put("renderer", renderer)
                .put("displayProfileId", displaySelection.profile.id)
                .put("frameCap", displaySelection.frameCap.fps)
                .put("tweaks", effectiveTweaksJson)
            rendererPackageId?.let { launchRequest.put("rendererPackageId", it) }
            vulkanDriverId?.let { launchRequest.put("vulkanDriverId", it) }
            val launched = json(client.api().launch(launchRequest.toString()))
            val launchedSessionId = launched.getString("sessionId")
            json(display.api().attachSession(owner.instanceToken, launchedSessionId))
            // Bounded (de-vibe A5): a client wedged mid-start used to poll
            // forever while holding the launch path.
            val clientStartDeadline = System.currentTimeMillis() + CLIENT_START_TIMEOUT_MS
            while (true) {
                if (renderer == "opengl" || renderer == "virgl") {
                    val graphics = json(display.api().status())
                    check(graphics.optString("renderer") == renderer &&
                        graphics.optBoolean("glxEnabled")) {
                        "$renderer display identity changed while starting"
                    }
                    submitExperimentalGraphicsProof(launchedSessionId, renderer, graphics)
                }
                val observed = observeClientComposite()
                if (observed.ready) {
                    clientReady = true
                    return observed
                }
                if (observed.state == ComponentLifecycle.FAILED) return observed
                if (System.currentTimeMillis() >= clientStartDeadline) {
                    error("client failed to reach READY within ${CLIENT_START_TIMEOUT_MS / MINUTES_MS}m")
                }
                delay(100)
            }
        } finally {
            if (!clientReady) {
                // A failed/aborted client start must not leave a published black surface or
                // an ownership claim that makes the advertised relaunch action impossible.
                // Wine can still call X/Vulkan/SysV/ALSA, so the display is released only
                // after forceStopOwned returns both runtime and process-tree drain proofs.
                if (clientClaimed) {
                    val cleanup = forceStopClientAndThenDisplay(owner)
                    check(cleanup.ok) { "failed client runtime cleanup: ${cleanup.detail}" }
                }
            }
        }
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
            if (clientGracefulReleaseReady(
                    rawState,
                    status.optBoolean("cleanExit"),
                    status.optBoolean("runtimeFinished"),
                )) {
                json(client.api().releaseOwned(owner.instanceToken))
                val displayCleanup = cleanupDisplayAfterClientDrain(owner)
                return if (displayCleanup.ok) {
                    RuntimeActionResult(true, "client exited after graceful close")
                } else displayCleanup
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
            "EXITED", "FORCE_STOPPED" -> clientTerminalLifecycle(raw, value.optBoolean("hasOwner"))
            "FAILED" -> ComponentLifecycle.FAILED
            else -> ComponentLifecycle.UNKNOWN
        }
        val owner = if (value.optBoolean("hasOwner")) ComponentOwner(
            value.getString("ownerSessionId"), value.getString("instanceToken")) else null
        return ComponentObservation(RuntimeComponent.CLIENT, state, state == ComponentLifecycle.READY,
            owner, detail = value.optString("detail", raw).take(512))
    }

    private suspend fun observeClientComposite(): ComponentObservation {
        var runtimeValue = json(client.api().statusCurrent())
        val displayValue = runCatching { json(display.api().status()) }.getOrElse { failure ->
            return compositeClientObservation(
                clientObservation(runtimeValue),
                display = null,
                displayUnavailableDetail = "client display unavailable: ${failure.javaClass.simpleName}",
            )
        }
        if (runtimeValue.optString("state") == "RUNNING") {
            val renderer = runtimeValue.optString("renderer")
            if (renderer == "opengl" || renderer == "virgl") {
                check(displayValue.optString("renderer") == renderer &&
                    displayValue.optBoolean("glxEnabled")) {
                    "$renderer live display identity changed"
                }
                submitExperimentalGraphicsProof(
                    runtimeValue.getString("sessionId"), renderer, displayValue,
                )
                // reportGraphicsProof can revoke RUNNING synchronously. Observe
                // the updated state in this same health-monitor pass.
                runtimeValue = json(client.api().statusCurrent())
            }
        }
        val runtime = clientObservation(runtimeValue)
        val displayHealth = runCatching {
            displayHealth(displayValue)
        }.getOrElse { failure ->
            return compositeClientObservation(
                runtime,
                display = null,
                displayUnavailableDetail = "client display unavailable: ${failure.javaClass.simpleName}",
            )
        }
        return compositeClientObservation(runtime, displayHealth)
    }

    private suspend fun submitExperimentalGraphicsProof(
        sessionId: String,
        renderer: String,
        graphics: JSONObject,
    ) {
        val proof = experimentalGraphicsProof(renderer, graphics)
        json(client.api().reportGraphicsProof(
            sessionId,
            renderer,
            proof.transportContexts,
            proof.rendererContexts,
            proof.presentedFrames,
        ))
    }

    private fun displayHealth(value: JSONObject): ClientDisplayHealth = ClientDisplayHealth(
        owner = if (value.optBoolean("hasOwner")) ComponentOwner(
            value.getString("ownerSessionId"),
            value.getString("instanceToken"),
        ) else null,
        prepared = value.optBoolean("prepared"),
        rendererReady = value.optBoolean("rendererReady"),
    )

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

    private suspend fun forceStopClientAndThenDisplay(owner: ComponentOwner): RuntimeActionResult {
        val current = runCatching { json(client.api().statusCurrent()) }.getOrElse { failure ->
            return RuntimeActionResult(false,
                "client ownership could not be verified before stop: ${failure.javaClass.simpleName}")
        }
        val currentOwner = if (current.optBoolean("hasOwner")) ComponentOwner(
            current.getString("ownerSessionId"),
            current.getString("instanceToken"),
        ) else null
        when {
            currentOwner == owner -> {
                val response = try {
                    JSONObject(client.api().forceStopOwned(owner.instanceToken))
                } catch (failure: Throwable) {
                    return RuntimeActionResult(false,
                        "client stop did not return a runtime drain proof: ${failure.javaClass.simpleName}")
                }
                if (!ClientTeardownGate.mayReleaseDisplay(
                        controlSucceeded = response.optBoolean("ok"),
                        runtimeFinished = response.optBoolean("runtimeFinished"),
                        processTreeDrained = response.optBoolean("processTreeDrained"),
                    )) {
                    return RuntimeActionResult(false,
                        response.optString("error", "client process tree did not drain; display retained"))
                }
            }
            currentOwner != null -> return RuntimeActionResult(
                false,
                "client ownership mismatch; stop withheld",
            )
            !clientAlreadyDrained(
                state = current.optString("state"),
                hasOwner = false,
                runtimeFinished = current.optBoolean("runtimeFinished"),
                detail = current.optString("detail"),
            ) -> return RuntimeActionResult(false, "client drain could not be proven")
        }
        client.close()
        return cleanupDisplayAfterClientDrain(owner)
    }

    private suspend fun cleanupDisplayAfterClientDrain(owner: ComponentOwner): RuntimeActionResult {
        val health = runCatching { displayHealth(json(display.api().status())) }.getOrElse { failure ->
            return RuntimeActionResult(false,
                "client drained but display state is unavailable: ${failure.javaClass.simpleName}")
        }
        return when (displayCleanupAction(owner, health)) {
            DisplayCleanupAction.ALREADY_RELEASED -> {
                display.close()
                RuntimeActionResult(true, "client drained and display was already released")
            }
            DisplayCleanupAction.RELEASE_EXACT_OWNER -> {
                val released = runCatching {
                    json(display.api().release(owner.instanceToken))
                }.getOrElse { failure ->
                    return RuntimeActionResult(false,
                        "client drained but display release failed: ${failure.javaClass.simpleName}")
                }
                check(released.optBoolean("ok"))
                display.close()
                RuntimeActionResult(true, "client process tree drained before display release")
            }
            DisplayCleanupAction.REJECT -> RuntimeActionResult(
                false,
                "client drained but display ownership/preparation did not match; release withheld",
            )
        }
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
        // Generous ceilings (de-vibe A5): real starts complete in well under a
        // minute; these exist so a wedged component surfaces as a failure
        // instead of an infinite supervisor poll.
        private const val MINUTES_MS = 60_000L
        private const val WAIT_READY_TIMEOUT_MS = 5 * MINUTES_MS
        private const val CLIENT_START_TIMEOUT_MS = 5 * MINUTES_MS

        private const val TAG = "AndroidRuntimeBackend"
        private const val MAX_DATABASE_PREPARATION_STEPS = 12
        const val DEFAULT_PROFILE = "mobile-low-v1"
        const val INTEGRATED_PROFILE = "mobile-low-v2-normal"
        const val BOT_LOW_25_PROFILE = "mobile-low-b1-25-v1"
        const val BOT_LIVELY_700_PROFILE = "mobile-lively-b700-v2"
        const val CLIENT_BUILD_ID = ClientRuntimeContract.WOW_5875_ID
    }
}

internal fun clientGracefulReleaseReady(
    state: String,
    cleanExit: Boolean,
    runtimeFinished: Boolean,
): Boolean = state == "EXITED" && cleanExit && runtimeFinished

internal fun clientTerminalLifecycle(state: String, hasOwner: Boolean): ComponentLifecycle {
    require(state == "EXITED" || state == "FORCE_STOPPED") { "not a terminal client state" }
    return if (hasOwner) ComponentLifecycle.STOPPING else ComponentLifecycle.STOPPED
}

internal data class ExperimentalGraphicsProof(
    val transportContexts: Int,
    val rendererContexts: Int,
    val presentedFrames: Long,
)

/**
 * Convert live display statistics to the renderer service's generic proof.
 * A VirGL server stop or any real Gladio context in a VirGL session revokes
 * the proof immediately by reporting zero live milestones.
 */
internal fun experimentalGraphicsProof(
    renderer: String,
    graphics: JSONObject,
): ExperimentalGraphicsProof = when (renderer) {
    "opengl" -> ExperimentalGraphicsProof(
        graphics.optInt("glxTransportContexts").coerceAtLeast(0),
        graphics.optInt("glxContexts").coerceAtLeast(0),
        graphics.optLong("glxPresentedFrames").coerceAtLeast(0),
    )
    "virgl" -> {
        val invalidRoute = !graphics.optBoolean("virglServerStarted") ||
            graphics.optInt("glxTransportContexts") != 0 ||
            graphics.optInt("glxContexts") != 0
        if (invalidRoute) {
            ExperimentalGraphicsProof(0, 0, 0)
        } else {
            ExperimentalGraphicsProof(
                graphics.optInt("virglActiveConnections").coerceAtLeast(0),
                minOf(
                    graphics.optInt("virglInitializedConnections").coerceAtLeast(0),
                    graphics.optInt("virglCapsReadyConnections").coerceAtLeast(0),
                ),
                graphics.optLong("virglSuccessfulFlushes").coerceAtLeast(0),
            )
        }
    }
    else -> error("unsupported experimental renderer proof: $renderer")
}

internal data class ClientDisplayHealth(
    val owner: ComponentOwner?,
    val prepared: Boolean,
    val rendererReady: Boolean,
)

internal fun compositeClientObservation(
    runtime: ComponentObservation,
    display: ClientDisplayHealth?,
    displayUnavailableDetail: String = "client display unavailable",
): ComponentObservation {
    require(runtime.component == RuntimeComponent.CLIENT)
    if (runtime.state in setOf(ComponentLifecycle.STARTING, ComponentLifecycle.READY) &&
        runtime.owner != null) {
        val failure = when {
            display == null -> displayUnavailableDetail
            display.owner == null -> "client display has no supervisor owner"
            display.owner != runtime.owner -> "client runtime and display ownership do not match"
            !display.prepared -> "client display is not prepared"
            !display.rendererReady -> "client display renderer is not ready"
            else -> null
        }
        return if (failure == null) runtime.copy(ready = runtime.state == ComponentLifecycle.READY)
        else runtime.copy(state = ComponentLifecycle.FAILED, ready = false, detail = failure.take(512))
    }
    if (runtime.state == ComponentLifecycle.STOPPED && display != null) {
        if (display.owner == null && !display.prepared) return runtime.copy(ready = false)
        return ComponentObservation(
            component = RuntimeComponent.CLIENT,
            state = ComponentLifecycle.FAILED,
            ready = false,
            owner = runtime.owner ?: display.owner,
            detail = "client display remained after the client runtime stopped",
        )
    }
    return runtime.copy(ready = false)
}

internal enum class DisplayCleanupAction { ALREADY_RELEASED, RELEASE_EXACT_OWNER, REJECT }

internal fun displayCleanupAction(
    expectedOwner: ComponentOwner,
    display: ClientDisplayHealth,
): DisplayCleanupAction = when {
    display.owner == expectedOwner -> DisplayCleanupAction.RELEASE_EXACT_OWNER
    display.owner != null -> DisplayCleanupAction.REJECT
    !display.prepared -> DisplayCleanupAction.ALREADY_RELEASED
    else -> DisplayCleanupAction.REJECT
}

internal fun clientAlreadyDrained(
    state: String,
    hasOwner: Boolean,
    runtimeFinished: Boolean,
    detail: String,
): Boolean = !hasOwner && state in setOf("EXITED", "FORCE_STOPPED") &&
    (runtimeFinished || detail == "no active client session")

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
