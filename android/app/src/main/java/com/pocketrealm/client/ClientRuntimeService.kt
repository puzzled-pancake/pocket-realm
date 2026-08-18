package com.pocketrealm.client

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.pocketrealm.log.AppLog
import com.pocketrealm.service.RealmService
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.ComponentOwnership
import com.pocketrealm.wine.WineSpikeNative
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Non-exported :client process. It owns Wine and every native child; the UI
 * process owns only the X server/surface and sends a versioned control protocol.
 */
class ClientRuntimeService : Service() {
    private val lock = Any()
    private val prepareLaunchLock = ReentrantLock()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: WineRuntimeStore
    private lateinit var ownership: ComponentOwnership
    private var prepared: WineRuntimeStore.Prepared? = null
    private var session: SessionRecord? = null
    private var sessionForegroundActive = false

    /**
     * True while a preparePrefix call is between its validation and its
     * prepared-ticket publication — the window in which Config.uvar/binding
     * writes may be in flight even though no session exists yet. The
     * in-game settings editor treats this as "not stopped" (plan §5.3).
     */
    @Volatile
    private var prepareInFlight: Boolean = false

    private data class SessionRecord(
        val id: UUID,
        val prepared: WineRuntimeStore.Prepared,
        val closeFile: File,
        var state: ClientState = ClientState.STARTING,
        var sequence: Long = 1,
        var detail: String = "launch accepted",
        var windowVisible: Boolean = false,
        var forced: Boolean = false,
        var cleanExit: Boolean = false,
        var stdout: String = "",
        var stderr: String = "",
        var rendererProofDeadlineElapsedMs: Long = 0,
        var graphicsTransportContexts: Int = 0,
        var graphicsRendererContexts: Int = 0,
        var graphicsPresentedFrames: Long = 0,
        var runtimeFinished: Boolean = false,
        var processTreeStarted: Boolean = false,
        var processTreeDrained: Boolean = false,
        val runtimeFinishedSignal: CountDownLatch = CountDownLatch(1),
    )

    override fun onCreate() {
        super.onCreate()
        store = WineRuntimeStore(applicationContext)
        ownership = ComponentOwnership("client") {
            Thread({
                val current = synchronized(lock) { session }
                val drained = current == null || runCatching {
                    forceRuntimeAndAwait(current!!).drained
                }.getOrDefault(false)
                if (drained) {
                    stopSelf()
                    Process.killProcess(Process.myPid())
                } else {
                    AppLog.e(TAG, "owner loss could not prove client process-tree drain")
                }
            }, "client-owner-loss").start()
        }
        AppLog.i(TAG, "ClientRuntimeService started pid=${android.os.Process.myPid()}")
    }

    /**
     * FGS promotion while a Wine session is live: a bound-only service sits
     * at cache priority whenever the UI is backgrounded, but this process
     * hosts the entire Box64/Wine/game tree. The supervisor (itself an FGS)
     * sends ACTION_START_SESSION_FOREGROUND when it binds and the stop action
     * when it unbinds; the runtime-finished transition below also drops the
     * foreground state so the notification never outlives the session.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION_FOREGROUND -> {
                RealmService.ensureChannel(this)
                startForeground(SESSION_NOTIF_ID, buildSessionNotification())
                sessionForegroundActive = true
            }
            ACTION_STOP_SESSION_FOREGROUND -> stopSessionForeground()
        }
        return START_NOT_STICKY
    }

    private fun stopSessionForeground() {
        if (sessionForegroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            sessionForegroundActive = false
        }
    }

    private fun buildSessionNotification(): Notification =
        NotificationCompat.Builder(this, RealmService.CHANNEL_ID)
            .setSmallIcon(com.pocketrealm.R.drawable.ic_launcher_foreground)
            .setContentTitle("Pocket Realm client")
            .setContentText("Game client session active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private val binder = object : IClientRuntimeControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder): String =
            guarded(instanceToken) { ownership.claim(sessionId, instanceToken, ownerLease) }

        override fun probe(requestJson: String): String = guarded(requestJson) {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            val clientId = request.getString("clientId")
            val provider = request.optString("provider", ClientRuntimeProvider.X86_DIRECT_WINE.id)
            val translator = ArmTranslationBackend.parse(request.getString("translator"))
            val x86Provider = provider == ClientRuntimeProvider.X86_DIRECT_WINE.id
            val armProvider = provider == ClientRuntimeProvider.ARM_TRANSLATED_WINE.id
            val providerSupported = x86Provider || armProvider
            val baseSupported = when {
                x86Provider -> Build.SUPPORTED_ABIS.contains("x86_64") && Build.VERSION.SDK_INT >= 26 &&
                    File(applicationInfo.nativeLibraryDir, "libwine_loader_preloader.so").isFile &&
                    File(applicationInfo.nativeLibraryDir, "libwine_spike.so").isFile
                armProvider -> Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" &&
                    ArmTranslatedWineRuntime.isProviderMarkerPresent(applicationContext, translator)
                else -> false
            }
            var clientFailure: String? = null
            val clientSupported = when (clientId) {
                ClientRuntimeContract.SELF_TEST_ID -> true
                ClientRuntimeContract.WOW_5875_ID -> runCatching {
                    ManagedClientStore(applicationContext).load(clientId)
                }.onFailure { clientFailure = it.message ?: it.javaClass.simpleName }.isSuccess
                else -> false
            }
            val supported = baseSupported && clientSupported
            JSONObject()
                .put("ok", true).put("supported", supported)
                .put("provider", provider)
                .put("translator", translator.id)
                .put("runtimeBuildId", if (provider == ClientRuntimeProvider.ARM_TRANSLATED_WINE.id) {
                    ClientRuntimeContract.armRuntimeBuildId(translator)
                } else ClientRuntimeContract.RUNTIME_BUILD_ID)
                .put("immutableCode", true)
                .put("reason", when {
                    supported && armProvider ->
                        "ARM64 ${translator.id}/Wine runtime and authorized client available"
                    supported -> "x86_64 runtime and authorized client available"
                    !providerSupported -> "runtime provider unavailable: $provider"
                    !baseSupported -> "runtime/ABI unavailable"
                    clientFailure != null -> "managed client unavailable: $clientFailure"
                    else -> "unsupported client identity"
                })
                .put("requestedAbi", request.optString("abi"))
        }

        override fun preparePrefix(requestJson: String): String = guarded(requestJson) {
            prepareLaunchLock.withLock {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            checkNoActiveSession()
            val retired = synchronized(lock) { prepared.also { prepared = null } }
            retired?.close()
            val translator = ArmTranslationBackend.parse(request.getString("translator"))
            val renderer = request.optString("renderer", "wined3d")
            val rendererPackageId = request.optionalString("rendererPackageId")
            val vulkanDriverId = request.optionalString("vulkanDriverId")
            val displaySelection = ClientDisplayCapabilities.requireSelection(
                applicationContext,
                request.getString("displayProfileId"),
                request.getInt("frameCap"),
            )
            if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
                val rendererSelection = ArmClientRendererCatalog.requireRuntimeRenderer(
                    renderer,
                    if (renderer != "dxvk") runCatching {
                        AndroidGladioCapabilityProbe.probe(applicationContext)
                    } else null,
                )
                if (rendererSelection == ArmClientRenderer.DXVK) {
                    requireNotNull(rendererPackageId) { "DXVK requires an explicit renderer package" }
                    val rendererPackage = requireNotNull(RendererPackageCatalog.requireForRequest(
                        translator, renderer, rendererPackageId,
                    ))
                    val requestedDriver = VulkanDriverCatalog.requireForRequest(vulkanDriverId)
                    VulkanDriverCatalog.requireAvailableCompatiblePair(
                        vulkanDriverId,
                        rendererPackage,
                        ArmRendererAuto.isAdrenoGpu(),
                        if (requestedDriver.kind == VulkanDriverKind.SYSTEM) {
                            AndroidSystemVulkanProbe.probe()
                        } else null,
                    )
                } else {
                    require(rendererPackageId == null && vulkanDriverId == null) {
                        "$renderer does not accept Vulkan/DXVK package identities"
                    }
                }
            } else {
                require(vulkanDriverId == null) {
                    "x86 direct Wine does not accept an ARM Vulkan driver"
                }
            }
            val audioMode = request.getString("audioMode").also {
                require(it == "off" || it == "on") { "unsupported audio mode" }
            }
            val tweaksJson = ClientTweaksConfig.fromControlJson(
                request.getString("tweaks"),
            ).toJson()
            val realmEndpoint = RealmEndpoint.parseStored(request.getString("realmEndpoint"))
            prepareInFlight = true
            val base = try {
                store.prepare(
                    clientId = request.getString("clientId"),
                    renderer = renderer,
                    audioMode = audioMode,
                    armTranslator = translator,
                    inputSafeMode = request.optBoolean("inputSafeMode", false),
                    armRendererPackageId = rendererPackageId,
                    armVulkanDriverId = vulkanDriverId,
                    displayProfileId = displaySelection.profile.id,
                    frameCap = displaySelection.frameCap.fps,
                    tweaksJson = tweaksJson,
                    realmEndpoint = realmEndpoint,
                )
            } finally {
                prepareInFlight = false
            }
            val p = base.copy(prefixId = "${base.prefixId}:${UUID.randomUUID()}")
            synchronized(lock) { prepared = p }
            JSONObject().put("ok", true).put("prefixId", p.prefixId)
                .put("runtimeRoot", p.root.absolutePath).put("prefixPath", p.prefix.absolutePath)
                .put("renderer", p.armRenderer ?: "wined3d")
                .put("vulkanDriverId", p.armVulkanDriverId ?: JSONObject.NULL)
                .put("rendererPackageId", p.armRendererPackageId ?: JSONObject.NULL)
                .put("displayProfileId", p.displayProfileId)
                .put("virtualWidth", displaySelection.virtualWidth)
                .put("virtualHeight", displaySelection.virtualHeight)
                .put("frameCap", p.frameCap)
                .put("effectiveTweaks", p.tweaksJson)
                .put("tweaksFallback", p.tweaksFallback)
                .put("detail", "prefix ready and manifest-compatible")
            }
        }

        override fun launch(requestJson: String): String = guarded(requestJson) {
            prepareLaunchLock.withLock {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            val p = synchronized(lock) {
                check(session == null || (session!!.runtimeFinished && session!!.processTreeDrained)) {
                    "a client runtime or undrained process tree is already active"
                }
                checkNotNull(prepared) { "preparePrefix must succeed before launch" }
            }
            check(request.getString("prefixId") == p.prefixId) { "prefix identity mismatch" }
            check(request.getString("audioMode") == p.audioMode) { "audio identity mismatch" }
            val requestedTweaks = ClientTweaksConfig.fromControlJson(
                request.getString("tweaks"),
            ).toJson()
            check(requestedTweaks == p.tweaksJson) { "client tweak identity mismatch" }
            check(ArmTranslationBackend.parse(request.getString("translator")) ==
                (p.armTranslator ?: ArmTranslationBackend.BOX64)) {
                "translator identity mismatch"
            }
            if (p.armRenderer != null) {
                check(request.optString("renderer") == p.armRenderer) {
                    "renderer identity mismatch"
                }
                check(request.optionalString("rendererPackageId") == p.armRendererPackageId) {
                    "renderer package identity mismatch"
                }
                check(request.optionalString("vulkanDriverId") == p.armVulkanDriverId) {
                    "Vulkan driver identity mismatch"
                }
            } else {
                check(request.optionalString("rendererPackageId") == null) {
                    "x86 direct Wine does not accept an ARM renderer package"
                }
                check(request.optionalString("vulkanDriverId") == null) {
                    "x86 direct Wine does not accept an ARM Vulkan driver"
                }
            }
            check(request.getString("displayProfileId") == p.displayProfileId) {
                "display profile identity mismatch"
            }
            check(request.getInt("frameCap") == p.frameCap) {
                "frame-cap identity mismatch"
            }
            check(request.optString("display", ":0") == ":0") { "only the app-private :0 display is authorized" }
            val socket = File(p.tmp, ".X11-unix/X0")
            check(socket.exists()) { "display surface/transport must exist before launch" }
            store.attestForLaunch(p)

            val id = UUID.randomUUID()
            val closeFile = File(p.root, "sessions/$id/close.request")
            closeFile.parentFile!!.mkdirs(); closeFile.delete()
            val record = SessionRecord(id, p, closeFile)
            try {
                synchronized(lock) {
                    check(prepared === p) { "prepared launch ticket was replaced" }
                    prepared = null
                    session = record
                    persist(record)
                }
                executor.execute {
                    try {
                        runSession(record)
                    } catch (error: Throwable) {
                        synchronized(lock) {
                            record.stderr = "${error.javaClass.simpleName}: ${error.message}"
                                .takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
                            if (record.state !in TERMINAL_STATES) {
                                runCatching { transition(record, ClientState.FAILED, "client runtime threw before completion") }
                                    .onFailure {
                                        record.state = ClientState.FAILED
                                        record.detail = "client runtime threw before completion"
                                        record.sequence++
                                    }
                            }
                        }
                    } finally {
                        try {
                            record.prepared.close()
                        } finally {
                            synchronized(lock) {
                                record.runtimeFinished = true
                                runCatching { persist(record) }
                                    .onFailure { AppLog.e(TAG, "final client-session persistence failed", it) }
                            }
                            record.runtimeFinishedSignal.countDown()
                        }
                    }
                }
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (session === record) session = null
                    if (prepared === p) prepared = null
                }
                try {
                    record.prepared.close()
                } finally {
                    throw error
                }
            }
            JSONObject().put("ok", true).put("sessionId", id.toString()).put("state", record.state.name)
            }
        }

        override fun requestClose(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                if (r.state !in TERMINAL_STATES) {
                    r.closeFile.parentFile!!.mkdirs()
                    r.closeFile.writeText(r.id.toString())
                    transition(r, ClientState.CLOSE_REQUESTED, "token-scoped WM_CLOSE requested")
                }
            }
            JSONObject().put("ok", true).put("requested", r.state == ClientState.CLOSE_REQUESTED)
                .put("state", r.state.name).put("detail", r.detail)
        }

        override fun forceStop(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            val outcome = forceRuntimeAndAwait(r)
            check(outcome.drained) { "client process tree did not drain before timeout" }
            JSONObject().put("ok", true)
                .put("cancelled", outcome.cancellationObserved)
                .put("runtimeFinished", outcome.runtimeFinished)
                .put("processTreeDrained", outcome.processTreeDrained)
                .put("state", r.state.name)
        }

        override fun status(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) { maybePromoteRunningLocked(r) }
            eventJson(r)
        }

        override fun collectDiagnostics(sessionId: String): String = guarded(sessionId) {
            diagnosticsJson(requireSession(sessionId))
        }

        override fun reportWindowVisible(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                r.windowVisible = true
                if (r.rendererProofDeadlineElapsedMs == 0L) {
                    r.rendererProofDeadlineElapsedMs =
                        SystemClock.elapsedRealtime() + RENDERER_PROOF_TIMEOUT_MS
                }
                maybePromoteRunningLocked(r)
            }
            eventJson(r)
        }

        override fun reportGraphicsProof(
            sessionId: String,
            renderer: String,
            transportContexts: Int,
            rendererContexts: Int,
            presentedFrames: Long,
        ): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                check(renderer == r.prepared.armRenderer) { "graphics renderer identity mismatch" }
                require(transportContexts >= 0 && rendererContexts >= 0 && presentedFrames >= 0) {
                    "graphics proof counters must be non-negative"
                }
                // These are live renderer milestones, not historical telemetry.
                // A disconnected context or restarted VirGL client must revoke
                // readiness until the currently active route proves itself.
                r.graphicsTransportContexts = transportContexts
                r.graphicsRendererContexts = rendererContexts
                r.graphicsPresentedFrames = presentedFrames
                maybePromoteRunningLocked(r)
            }
            eventJson(r)
        }

        override fun statusCurrent(): String = guarded("") {
            val value = synchronized(lock) {
                session?.also(::maybePromoteRunningLocked)?.let { eventJsonUnsafe(it) }
                    ?: JSONObject().put("ok", true)
                        .put("sequence", 0).put("state", ClientState.EXITED.name)
                        .put("detail", "no active client session")
                        .put("runtimeFinished", true)
                        .put("processTreeDrained", true)
                // A finished session record lingers until the next launch;
                // the prepared-ticket truth must come from the field, not
                // the session payload (plan 5.3's stopped-check).
            }.put("preparedTicket", synchronized(lock) { prepared != null })
            value.put("prepareInFlight", prepareInFlight)
            ownership.decorate(value)
        }

        override fun closeOwned(instanceToken: String): String = guarded(instanceToken) {
            ownership.requireOwner(instanceToken)
            val r = synchronized(lock) { checkNotNull(session) { "no client session" } }
            synchronized(lock) {
                if (r.state !in TERMINAL_STATES) {
                    r.closeFile.parentFile!!.mkdirs()
                    r.closeFile.writeText(r.id.toString())
                    transition(r, ClientState.CLOSE_REQUESTED, "owned graceful close requested")
                }
            }
            eventJson(r)
        }

        override fun releaseOwned(instanceToken: String): String = guarded(instanceToken) {
            ownership.requireOwner(instanceToken)
            val r = synchronized(lock) { checkNotNull(session) { "no client session" } }
            check(r.state in TERMINAL_STATES && r.runtimeFinished) {
                "client runtime has not finished"
            }
            check(r.processTreeDrained) { "client process tree has not drained" }
            ownership.clear(instanceToken)
            JSONObject().put("ok", true).put("released", true).put("state", r.state.name)
        }

        override fun forceStopOwned(instanceToken: String): String = guarded(instanceToken) {
            ownership.requireOwner(instanceToken)
            val r = synchronized(lock) { session }
            val outcome = r?.let(::forceRuntimeAndAwait) ?: ForcedDrainOutcome.noSession()
            check(outcome.drained) { "client process tree did not drain before timeout" }
            synchronized(lock) { prepared.also { prepared = null } }?.close()
            ownership.clear(instanceToken)
            Thread({
                Thread.sleep(150)
                stopSelf()
                Process.killProcess(Process.myPid())
            }, "client-force-retire").start()
            JSONObject().put("ok", true)
                .put("state", r?.state?.name ?: ClientState.EXITED.name)
                .put("hadSession", r != null)
                .put("runtimeFinished", outcome.runtimeFinished)
                .put("processTreeDrained", outcome.processTreeDrained)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(lock) { session }?.takeIf { !it.runtimeFinished }?.let { current ->
            runCatching { forceRuntimeAndAwait(current) }
                .onFailure { AppLog.e(TAG, "client teardown drain failed", it) }
        }
        synchronized(lock) { prepared.also { prepared = null } }?.close()
        executor.shutdown()
        super.onDestroy()
    }

    private fun runSession(r: SessionRecord) {
        if (synchronized(lock) { r.forced }) {
            synchronized(lock) { r.processTreeDrained = true }
            return
        }
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            runArmSession(r)
            return
        }
        val windowsClosePath = "Z:" + r.closeFile.absolutePath.replace('/', '\\')
        val env = buildList {
            add("LD_DEBUG=")
            add("WINEDEBUG=-all")
            add("POCKET_AUDIO_MODE=off")
            add("WINEDLLOVERRIDES=winealsa.drv=d,winepulse.drv=d")
            if (r.prepared.selfTest) {
                add("POCKET_SELFTEST_INTERACTIVE=1"); add("POCKET_CLOSE_FILE=$windowsClosePath")
            } else {
                add("WINEESYNC=0"); add("WINEFSYNC=0")
                add("POCKET_GLADIO_X11_SOCKET=${File(r.prepared.tmp, ".X11-unix/X0").absolutePath}")
            }
        }.joinToString(";")
        if (synchronized(lock) { r.forced }) {
            synchronized(lock) { r.processTreeDrained = true }
            return
        }
        val raw = try {
            synchronized(lock) { r.processTreeStarted = true }
            WineSpikeNative.runWineDirectNative(
                applicationInfo.nativeLibraryDir, r.prepared.executable.absolutePath,
                r.prepared.prefix.absolutePath, r.prepared.workingDir.absolutePath,
                ":0", "", env, 6 * 60 * 60 * 1000,
            )
        } catch (t: Throwable) {
            synchronized(lock) {
                r.stderr = "${t.javaClass.simpleName}: ${t.message}"
                if (!r.forced) transition(r, ClientState.FAILED, "native launcher threw")
            }
            return
        }
        val result = parseWineRunResult(raw)
        synchronized(lock) {
            r.processTreeDrained = result.processTreeDrained
            r.stdout = result.stdout.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.stderr = result.stderr.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.cleanExit = result.rc == 0 && result.exitedCleanly && result.exitCode == 0 &&
                result.processTreeDrained &&
                !result.timedOut && (!r.prepared.selfTest || r.stdout.contains("POCKET_SELFTEST_OK"))
            if (!r.forced) {
                transition(
                    r,
                    if (r.cleanExit) ClientState.EXITED else ClientState.FAILED,
                    "rc=${result.rc} exit=${result.exitCode} timeout=${result.timedOut}",
                )
            } else persist(r)
        }
    }

    private fun runArmSession(r: SessionRecord) {
        check((r.prepared.armTranslator ?: ArmTranslationBackend.BOX64) ==
            ArmTranslationBackend.BOX64) {
            "Box64 is the only supported ARM translator"
        }
        runArmBox64Session(r)
    }

    private fun runArmBox64Session(r: SessionRecord) {
        if (synchronized(lock) { r.forced }) {
            synchronized(lock) { r.processTreeDrained = true }
            return
        }
        val rootfs = r.prepared.tree
        val nativeDir = File(applicationInfo.nativeLibraryDir)
        val box64 = File(nativeDir, "libbox64.so")
        val wine = File(rootfs, "opt/wine/bin/wine")
        val armLib = File(rootfs, "usr/lib")
        val x86Lib = File(rootfs, "lib/x86_64-linux-gnu")
        val home = File(rootfs, "home/xuser")
        val renderer = checkNotNull(r.prepared.armRenderer) { "ARM renderer identity missing" }
        check(renderer == "dxvk" || renderer == "opengl" || renderer == "virgl") {
            "unsupported Box64 ARM renderer: $renderer"
        }
        val dxvkConfig = File(r.prepared.cache, ClientRuntimeContract.DXVK_CONFIG_FILE_NAME)
        if (renderer == "dxvk") {
            check(dxvkConfig.isFile && dxvkConfig.readText() ==
                ClientRuntimeContract.dxvkFrameCapConfig(r.prepared.frameCap)) {
                "app-owned DXVK frame cap is missing or stale"
            }
        }
        File(r.prepared.tmp, "shm").mkdirs()
        val audioOn = r.prepared.audioMode == "on"
        val driverEnv = if (renderer == "dxvk") {
            val driver = checkNotNull(VulkanDriverCatalog.find(r.prepared.armVulkanDriverId)) {
                "ARM Vulkan driver identity missing"
            }
            when (driver.kind) {
                VulkanDriverKind.SYSTEM -> listOf(
                    "VK_ICD_FILENAMES=${File(rootfs, "usr/share/vulkan/icd.d/${driver.icdFileName}").absolutePath}",
                )
                VulkanDriverKind.TURNIP -> buildList {
                    add("VK_ICD_FILENAMES=${File(rootfs, "usr/share/vulkan/icd.d/${driver.icdFileName}").absolutePath}")
                    add("MESA_VK_WSI_PRESENT_MODE=mailbox")
                    add("MESA_VK_WSI_USE_HWBUF=1")
                    add(if (Build.MODEL.trim().equals("Retroid Pocket 6", ignoreCase = true)) {
                        "TU_DEBUG=noconform,sysmem"
                    } else {
                        "TU_DEBUG=noconform"
                    })
                }
            }
        } else emptyList()
        val rendererEnv = when (renderer) {
            "dxvk" -> listOf(
                "DXVK_STATE_CACHE_PATH=${File(r.prepared.cache, "dxvk").absolutePath}",
                "MESA_SHADER_CACHE_DIR=${File(r.prepared.cache, "mesa").absolutePath}",
                "DXVK_CONFIG_FILE=${dxvkConfig.absolutePath}",
                "DXVK_LOG_PATH=${File(r.prepared.root, "sessions/${r.id}").absolutePath}",
                "DXVK_LOG_LEVEL=info",
                "vblank_mode=0",
            )
            "opengl" -> listOf(
                "POCKET_GLADIO_X11_SOCKET=${File(rootfs, "tmp/.X11-unix/X0").absolutePath}",
            )
            "virgl" -> listOf(
                "GALLIUM_DRIVER=virpipe",
                "VIRGL_NO_READBACK=true",
                "VIRGL_SERVER_PATH=${File(rootfs, "tmp/.virgl/V0").absolutePath}",
                "MESA_DEBUG=silent",
                "MESA_NO_ERROR=1",
                "MESA_EXTENSION_OVERRIDE=-GL_KHR_debug -GL_EXT_vertex_array_bgra",
                "MESA_GL_VERSION_OVERRIDE=3.1",
                "MESA_SHADER_CACHE_DIR=${File(r.prepared.cache, "virgl").absolutePath}",
            )
            else -> error("unsupported Box64 ARM renderer: $renderer")
        }
        val env = listOf(
            "HOME=${home.absolutePath}",
            "USER=xuser",
            "DISPLAY=:0",
            // Guest timestamps (WoW Errors/ files, Wine logs) otherwise render
            // in UTC, 12 hours off on NZST devices.
            "TZ=${ClientRuntimeContract.posixTzNow()}",
            "ANDROID_SYSVSHM_SERVER=${File(rootfs, "tmp/.sysvshm/SM0").absolutePath}",
            "WINEPREFIX=${r.prepared.prefix.absolutePath}",
            "WINEDEBUG=-all",
            "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER=1",
            "WINEESYNC=0",
            "WINEFSYNC=0",
            // Use the proven Wine loader order; readiness below independently
            // attests the fresh DXVK/Turnip session log.
            "WINEDLLOVERRIDES=${ClientRuntimeContract.armWineDllOverrides(renderer, audioOn)}",
            "BOX64_NOBANNER=1",
            "BOX64_DYNAREC=1",
            "BOX64_UNITYPLAYER=0",
            // Match Winlator's conservative preset.  The generic Box64 defaults are
            // too aggressive for Wine's mixed 32/64-bit process tree on Android.
            "BOX64_DYNAREC_SAFEFLAGS=2",
            "BOX64_DYNAREC_FASTNAN=0",
            "BOX64_DYNAREC_FASTROUND=0",
            "BOX64_DYNAREC_X87DOUBLE=1",
            "BOX64_DYNAREC_BIGBLOCK=1",
            "BOX64_DYNAREC_STRONGMEM=1",
            "BOX64_DYNAREC_FORWARD=128",
            "BOX64_DYNAREC_CALLRET=0",
            "BOX64_DYNAREC_WAIT=1",
            "BOX64_DYNAREC_NATIVEFLAGS=0",
            "BOX64_DYNAREC_WEAKBARRIER=1",
            "BOX64_X11GLX=1",
            "BOX64_LD_LIBRARY_PATH=${x86Lib.absolutePath}",
            "BOX64_PATH=${File(rootfs, "opt/wine/bin").absolutePath}",
            "XDG_CACHE_HOME=${File(r.prepared.cache, "xdg").absolutePath}",
            "FONTCONFIG_FILE=${File(rootfs, "etc/fonts/fonts.conf").absolutePath}",
            "FONTCONFIG_PATH=${File(rootfs, "etc/fonts").absolutePath}",
        ) + driverEnv + rendererEnv + (if (audioOn) listOf(
            // Audio: route libasound to the on-device ALSA server (bound by
            // ClientDisplayHost at <transportRoot>/.sound/AS0) via the android_aserver
            // plugin. The plugin is installed into the ca3d735 rootfs at
            // usr/lib/alsa-lib/ (libasound's standard plugin dir); the rootfs already
            // carries the stock alsa.conf + android_aserver default routing. SHM is
            // mandatory for the matched ca3d735 protocol.
            "ANDROID_ALSA_SERVER=${File(r.prepared.tmp, ".sound/AS0").absolutePath}",
            "ANDROID_ASERVER_USE_SHM=true",
            "ALSA_CONFIG_PATH=${File(rootfs, "usr/share/alsa/alsa.conf").absolutePath}",
            "ALSA_PLUGIN_DIR=${File(rootfs, "usr/lib/alsa-lib").absolutePath}",
        ) else emptyList())
        File(r.prepared.root, "sessions/${r.id}").mkdirs()
        if (synchronized(lock) { r.forced }) {
            synchronized(lock) { r.processTreeDrained = true }
            return
        }
        val launchEnvironment = env + listOf(
            "TMPDIR=${r.prepared.tmp.absolutePath}",
            "PATH=" + listOf(
                File(rootfs, "opt/wine/bin"),
                File(rootfs, "usr/local/bin"),
                File(rootfs, "usr/bin"),
                File(rootfs, "bin"),
            ).joinToString(":") { it.absolutePath },
        )
        val raw = try {
            synchronized(lock) { r.processTreeStarted = true }
            WineSpikeNative.runTrackedBionicProgramNative(
                nativeDir.absolutePath,
                box64.absolutePath,
                box64.absolutePath,
                r.prepared.workingDir.absolutePath,
                r.prepared.root.absolutePath,
                when (renderer) {
                    "opengl" -> listOf(
                        File(checkNotNull(r.prepared.prefix.parentFile),
                            WineRuntimeStore.GLADIO_PAYLOAD_DIRECTORY).absolutePath,
                        armLib.absolutePath,
                    ).joinToString(":")
                    "virgl" -> listOf(
                        File(checkNotNull(r.prepared.prefix.parentFile),
                            WineRuntimeStore.VIRGL_PAYLOAD_DIRECTORY).absolutePath,
                        armLib.absolutePath,
                    ).joinToString(":")
                    else -> armLib.absolutePath
                },
                (listOf(wine.absolutePath) + ClientRuntimeContract.armClientArguments(
                    r.prepared.executable.absolutePath,
                    renderer,
                )).joinToString("\n"),
                launchEnvironment.joinToString("\n"),
                "",
                0,
                true,
            )
        } catch (t: Throwable) {
            synchronized(lock) {
                r.stderr = "${t.javaClass.simpleName}: ${t.message}"
                if (!r.forced) transition(r, ClientState.FAILED, "ARM Box64 launcher threw")
            }
            return
        }
        val result = parseWineRunResult(raw)
        synchronized(lock) {
            r.processTreeDrained = result.processTreeDrained
            r.stdout = result.stdout.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.stderr = result.stderr.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.cleanExit = result.rc == 0 && result.exitedCleanly && result.exitCode == 0 &&
                result.processTreeDrained && !result.timedOut
            if (!r.forced) {
                transition(
                    r,
                    if (r.cleanExit) ClientState.EXITED else ClientState.FAILED,
                    "box64 rc=${result.rc} exit=${result.exitCode} timeout=${result.timedOut}",
                )
            } else persist(r)
            // The Wine session is over (clean or failed); drop the FGS
            // promotion regardless of whether the supervisor's stop intent
            // has arrived yet.
            stopSessionForeground()
        }
    }

    private fun cancelActiveRuntime(r: SessionRecord): Boolean =
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            WineSpikeNative.cancelActiveTrackedBionicProgramNative()
        } else {
            WineSpikeNative.cancelActiveDirectNative()
        }

    private data class ForcedDrainOutcome(
        val runtimeFinished: Boolean,
        val processTreeDrained: Boolean,
        val cancellationObserved: Boolean,
    ) {
        val drained: Boolean get() = runtimeFinished && processTreeDrained

        companion object {
            fun noSession() = ForcedDrainOutcome(
                runtimeFinished = true,
                processTreeDrained = true,
                cancellationObserved = false,
            )
        }
    }

    /**
     * Mark cancellation before looking for a process, then keep retrying until
     * the executor publishes and terminates any launch racing this Binder call.
     * The display owner consumes both returned proofs before releasing X/Vulkan,
     * SysV SHM, or ALSA resources.
     */
    private fun forceRuntimeAndAwait(r: SessionRecord): ForcedDrainOutcome {
        synchronized(lock) { r.forced = true }
        var cancellationObserved = false
        val deadline = System.nanoTime() + FORCE_DRAIN_TIMEOUT_MS * 1_000_000L
        while (true) {
            cancellationObserved = cancelActiveRuntime(r) || cancellationObserved
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            val completed = try {
                r.runtimeFinishedSignal.await(
                    minOf(FORCE_DRAIN_POLL_MS * 1_000_000L, remaining),
                    TimeUnit.NANOSECONDS,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (completed || Thread.currentThread().isInterrupted) break
        }

        val runtimeFinished = synchronized(lock) { r.runtimeFinished }
        val processTreeDrained = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            synchronized(lock) {
                if (runtimeFinished && WineSpikeNative.isTrackedBionicProcessGroupDrainedNative()) {
                    r.processTreeDrained = true
                }
                r.processTreeDrained
            }
        } else {
            synchronized(lock) { r.processTreeDrained }
        }
        if (runtimeFinished && processTreeDrained) synchronized(lock) {
            if (r.state != ClientState.FORCE_STOPPED) {
                transition(
                    r,
                    ClientState.FORCE_STOPPED,
                    if (cancellationObserved) "client process tree killed and drained"
                    else "client runtime finished before forced teardown",
                )
            }
        }
        return ForcedDrainOutcome(runtimeFinished, processTreeDrained, cancellationObserved)
    }

    private fun readTail(file: File): String = if (!file.isFile) "" else
        file.readText().takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)

    /** Promote an ARM window only after proving that the selected pinned DXVK
     * package, rather than WineD3D, created this exact session's D3D9 device. */
    private fun maybePromoteRunningLocked(r: SessionRecord) {
        if (r.state !in setOf(ClientState.STARTING, ClientState.RUNNING) || !r.windowVisible) return
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            if (r.state == ClientState.STARTING) {
                transition(r, ClientState.RUNNING, "mapped client window visible")
            }
            return
        }
        if (r.prepared.armRenderer == "opengl") {
            if (experimentalRendererProofReady(
                    r.graphicsTransportContexts,
                    r.graphicsRendererContexts,
                    r.graphicsPresentedFrames,
                )) {
                if (r.state == ClientState.STARTING) {
                    transition(
                        r,
                        ClientState.RUNNING,
                        "mapped client window with live Gladio GLX and presented frame",
                    )
                }
            } else if (experimentalRendererProofRevoked(
                    r.state,
                    r.graphicsTransportContexts,
                    r.graphicsRendererContexts,
                    r.graphicsPresentedFrames,
                )) {
                transition(
                    r,
                    ClientState.FAILED,
                    "Gladio live proof revoked: transport=${r.graphicsTransportContexts} " +
                        "glx=${r.graphicsRendererContexts} frames=${r.graphicsPresentedFrames}",
                )
            } else if (r.rendererProofDeadlineElapsedMs > 0L &&
                SystemClock.elapsedRealtime() >= r.rendererProofDeadlineElapsedMs) {
                transition(
                    r,
                    ClientState.FAILED,
                    "Gladio readiness timed out: transport=${r.graphicsTransportContexts} " +
                        "glx=${r.graphicsRendererContexts} frames=${r.graphicsPresentedFrames}",
                )
            }
            return
        }
        if (r.prepared.armRenderer == "virgl") {
            if (experimentalRendererProofReady(
                    r.graphicsTransportContexts,
                    r.graphicsRendererContexts,
                    r.graphicsPresentedFrames,
                )) {
                if (r.state == ClientState.STARTING) {
                    transition(
                        r,
                        ClientState.RUNNING,
                        "mapped client window with initialized VirGL caps and validated flush",
                    )
                }
            } else if (experimentalRendererProofRevoked(
                    r.state,
                    r.graphicsTransportContexts,
                    r.graphicsRendererContexts,
                    r.graphicsPresentedFrames,
                )) {
                transition(
                    r,
                    ClientState.FAILED,
                    "VirGL live proof revoked: connections=${r.graphicsTransportContexts} " +
                        "capsReady=${r.graphicsRendererContexts} flushes=${r.graphicsPresentedFrames}",
                )
            } else if (r.rendererProofDeadlineElapsedMs > 0L &&
                SystemClock.elapsedRealtime() >= r.rendererProofDeadlineElapsedMs) {
                transition(
                    r,
                    ClientState.FAILED,
                    "VirGL readiness timed out: connections=${r.graphicsTransportContexts} " +
                        "capsReady=${r.graphicsRendererContexts} flushes=${r.graphicsPresentedFrames}",
                )
            }
            return
        }
        if (r.state != ClientState.STARTING) return
        check(r.prepared.armRenderer == "dxvk") {
            "unsupported ARM renderer readiness route: ${r.prepared.armRenderer}"
        }
        val rendererPackage = RendererPackageCatalog.find(r.prepared.armRendererPackageId)
        val driver = VulkanDriverCatalog.find(r.prepared.armVulkanDriverId)
        val executableName = r.prepared.executable.name
        val proof = readPrefix(File(
            r.prepared.root,
            "sessions/${r.id}/${ClientRuntimeContract.armDxvkLogFileName(executableName)}",
        ))
        if (rendererPackage != null && driver != null &&
            ClientRuntimeContract.isArmDxvkLogAttested(
                proof, rendererPackage.dxvkVersion, driver, executableName,
            )) {
            transition(
                r,
                ClientState.RUNNING,
                "mapped client window with ${driver.id} and DXVK ${rendererPackage.dxvkVersion}",
            )
        } else if (r.rendererProofDeadlineElapsedMs > 0L &&
            SystemClock.elapsedRealtime() >= r.rendererProofDeadlineElapsedMs) {
            transition(r, ClientState.FAILED, "mapped ARM window lacks pinned DXVK proof")
        }
    }

    private fun readPrefix(file: File, maximumBytes: Int = 8 * 1024): String {
        if (!file.isFile) return ""
        return runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(maximumBytes)
                val count = input.read(bytes).coerceAtLeast(0)
                String(bytes, 0, count, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }

    private fun transition(r: SessionRecord, state: ClientState, detail: String) {
        r.state = state; r.detail = detail; r.sequence++
        persist(r)
        AppLog.i(TAG, "session=${r.id} state=$state detail=$detail")
    }

    private fun checkNoActiveSession() = synchronized(lock) {
        check(session == null || (session!!.runtimeFinished && session!!.processTreeDrained)) {
            "cannot prepare while a client runtime or undrained process tree is active"
        }
    }

    private fun requireProtocol(request: JSONObject) {
        check(request.optInt("protocol", -1) == ClientRuntimeContract.PROTOCOL_VERSION) {
            "unsupported ClientRuntime protocol"
        }
    }

    private fun JSONObject.optionalString(name: String): String? =
        takeIf { has(name) && !isNull(name) }?.getString(name)?.also {
            require(it.isNotBlank()) { "$name must not be blank" }
        }

    private fun requireSession(value: String): SessionRecord = synchronized(lock) {
        val id = UUID.fromString(value)
        checkNotNull(session?.takeIf { it.id == id }) { "unknown or stale session token" }
    }

    private fun eventJson(r: SessionRecord) = synchronized(lock) {
        eventJsonUnsafe(r)
    }

    private fun eventJsonUnsafe(r: SessionRecord) = JSONObject().put("ok", true)
        .put("sessionId", r.id.toString())
        .put("sequence", r.sequence).put("state", r.state.name).put("detail", r.detail)
        .put("cleanExit", r.cleanExit).put("forced", r.forced)
        .put("windowVisible", r.windowVisible).put("runtimeFinished", r.runtimeFinished)
        .put("processTreeDrained", r.processTreeDrained)
        .put("preparedTicket", false)
        .put("renderer", r.prepared.armRenderer ?: "wined3d")
        .put("graphicsTransportContexts", r.graphicsTransportContexts)
        .put("graphicsRendererContexts", r.graphicsRendererContexts)
        .put("graphicsPresentedFrames", r.graphicsPresentedFrames)
        .put("rendererPackageId", r.prepared.armRendererPackageId ?: JSONObject.NULL)
        .put("vulkanDriverId", r.prepared.armVulkanDriverId ?: JSONObject.NULL)
        .put("displayProfileId", r.prepared.displayProfileId)
        .put("frameCap", r.prepared.frameCap)

    private fun diagnosticsJson(r: SessionRecord) = synchronized(lock) {
        JSONObject().put("ok", true).put("sessionId", r.id.toString()).put("state", r.state.name)
            .put("cleanExit", r.cleanExit).put("forced", r.forced).put("windowVisible", r.windowVisible)
            .put("renderer", r.prepared.armRenderer ?: "wined3d")
            .put("graphicsTransportContexts", r.graphicsTransportContexts)
            .put("graphicsRendererContexts", r.graphicsRendererContexts)
            .put("graphicsPresentedFrames", r.graphicsPresentedFrames)
            .put("rendererPackageId", r.prepared.armRendererPackageId ?: JSONObject.NULL)
            .put("vulkanDriverId", r.prepared.armVulkanDriverId ?: JSONObject.NULL)
            .put("displayProfileId", r.prepared.displayProfileId)
            .put("frameCap", r.prepared.frameCap)
            .put("focusSeen", r.stdout.contains("POCKET_SELFTEST_FOCUS gained"))
            .put("audioOff", r.stdout.contains("POCKET_SELFTEST_AUDIO skipped"))
            .put("keyboardSeen", r.stdout.contains("POCKET_SELFTEST_KEY "))
            .put("mouseSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE "))
            .put("rightButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
                r.stdout.contains("btn=r"))
            .put("middleButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
                r.stdout.contains("btn=m"))
            .put("wheelSeen", r.stdout.contains("POCKET_SELFTEST_WHEEL "))
            .put("relativeMotionSeen", r.stdout.contains("POCKET_SELFTEST_RELMOVE "))
            .put("charSeen", r.stdout.contains("POCKET_SELFTEST_CHAR "))
            .put("charCount", countOccurrences(r.stdout, "POCKET_SELFTEST_CHAR "))
            .put("stdoutTail", r.stdout).put("stderrTail", r.stderr).put("detail", r.detail)
    }

    private fun persist(r: SessionRecord) {
        val out = File(noBackupFilesDir, "wine/last-session.json")
        out.parentFile!!.mkdirs()
        val temp = File(out.parentFile, ".last-session.tmp")
        temp.writeText(diagnosticsJsonUnsafe(r).toString())
        if (!temp.renameTo(out)) { out.delete(); temp.renameTo(out) }
        trimSessionLogs(r.prepared.root)
    }

    private fun diagnosticsJsonUnsafe(r: SessionRecord) = JSONObject()
        .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION).put("sessionId", r.id.toString())
        .put("state", r.state.name).put("sequence", r.sequence).put("detail", r.detail)
        .put("cleanExit", r.cleanExit).put("forced", r.forced).put("windowVisible", r.windowVisible)
        .put("renderer", r.prepared.armRenderer ?: "wined3d")
        .put("graphicsTransportContexts", r.graphicsTransportContexts)
        .put("graphicsRendererContexts", r.graphicsRendererContexts)
        .put("graphicsPresentedFrames", r.graphicsPresentedFrames)
        .put("rendererPackageId", r.prepared.armRendererPackageId ?: JSONObject.NULL)
        .put("vulkanDriverId", r.prepared.armVulkanDriverId ?: JSONObject.NULL)
        .put("displayProfileId", r.prepared.displayProfileId)
        .put("frameCap", r.prepared.frameCap)
        .put("focusSeen", r.stdout.contains("POCKET_SELFTEST_FOCUS gained"))
        .put("audioOff", r.stdout.contains("POCKET_SELFTEST_AUDIO skipped"))
        .put("keyboardSeen", r.stdout.contains("POCKET_SELFTEST_KEY "))
        .put("mouseSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE "))
        .put("rightButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
            r.stdout.contains("btn=r"))
        .put("middleButtonSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE ") &&
            r.stdout.contains("btn=m"))
        .put("wheelSeen", r.stdout.contains("POCKET_SELFTEST_WHEEL "))
        .put("relativeMotionSeen", r.stdout.contains("POCKET_SELFTEST_RELMOVE "))
        .put("charSeen", r.stdout.contains("POCKET_SELFTEST_CHAR "))
        .put("charCount", countOccurrences(r.stdout, "POCKET_SELFTEST_CHAR "))
        .put("stdoutTail", r.stdout.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS))
        .put("stderrTail", r.stderr.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS))

    private fun trimSessionLogs(root: File) {
        val dir = File(root, "sessions")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(8).forEach { it.deleteRecursively() }
    }

    /** Count non-overlapping occurrences of [needle] in [haystack]. */
    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) { count++; idx = haystack.indexOf(needle, idx + needle.length) }
        return count
    }

    private inline fun guarded(input: String, block: () -> JSONObject): String {
        if (input.toByteArray().size > ClientRuntimeContract.MAX_CONTROL_BYTES) {
            return JSONObject().put("ok", false).put("error", "control payload too large").toString()
        }
        return try { block().toString() }
        catch (t: Throwable) {
            AppLog.e(TAG, "control request failed", t)
            JSONObject().put("ok", false).put("error", "${t.javaClass.simpleName}: ${t.message}").toString()
        }
    }

    companion object {
        private const val TAG = "ClientRuntime"
        private const val FORCE_DRAIN_TIMEOUT_MS = 15_000L
        private const val FORCE_DRAIN_POLL_MS = 50L
        const val ACTION_START_SESSION_FOREGROUND =
            "com.pocketrealm.action.CLIENT_SESSION_FOREGROUND_START"
        const val ACTION_STOP_SESSION_FOREGROUND =
            "com.pocketrealm.action.CLIENT_SESSION_FOREGROUND_STOP"
        const val SESSION_NOTIF_ID = 2
        private const val RENDERER_PROOF_TIMEOUT_MS = 15_000L
        private val TERMINAL_STATES = setOf(ClientState.EXITED, ClientState.FORCE_STOPPED, ClientState.FAILED)
    }
}

internal fun experimentalRendererProofReady(
    transportContexts: Int,
    rendererContexts: Int,
    presentedFrames: Long,
): Boolean = transportContexts > 0 && rendererContexts > 0 && presentedFrames > 0

internal fun experimentalRendererProofRevoked(
    state: ClientState,
    transportContexts: Int,
    rendererContexts: Int,
    presentedFrames: Long,
): Boolean = state == ClientState.RUNNING && !experimentalRendererProofReady(
    transportContexts, rendererContexts, presentedFrames,
)
