package com.pocketrealm.client

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.pocketrealm.log.AppLog
import com.pocketrealm.storage.Settings
import com.winlator.XServerDisplayActivity
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.sysvshm.SysVSHMConnectionHandler
import com.winlator.sysvshm.SysVSHMRequestHandler
import com.winlator.sysvshm.SysVSharedMemory
import com.winlator.alsaserver.ALSAClient
import com.winlator.alsaserver.ALSAClientConnectionHandler
import com.winlator.alsaserver.ALSARequestHandler
import com.winlator.xenvironment.components.VortekRendererComponent
import com.winlator.xserver.Atom
import com.winlator.xserver.events.Event
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.SHMSegmentManager
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XClientConnectionHandler
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.XServer
import com.winlator.xserver.events.ClientMessage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** UI-owned X transport, rendered SurfaceView, and normalized input bridge. */
class ClientDisplayHost(
    context: Context,
    runtimeRoot: String,
    /** Single-ABI selection preserves x86 Balanced and enables ARM64 Quality. */
    val displayProfile: ClientDisplayProfile =
        ClientDisplayProfile.forDevice(Build.SUPPORTED_ABIS.asList(), Build.MODEL),
    val frameCap: Int = ClientFrameCap.FPS_30.fps,
    val vulkanDriverId: String? = null,
    val rendererPackageId: String? = null,
    autoLoginCredentials: SinglePlayerAutoLoginCredentials? = null,
    private val timings: Settings.AutoLoginTimings = Settings.AutoLoginTimings(),
    private val audioEnabled: Boolean = false,
    private val onWindowVisible: () -> Unit,
) : AutoCloseable {
    /** Physical transport root used by both the fixed Box64 provider and x86 validation. */
    val transportRoot: java.io.File = resolveTransportRoot(runtimeRoot).apply { mkdirs() }
    /** The one ALSA filesystem node owned by this host. */
    private val alsaSocket = java.io.File(transportRoot, ".sound/AS0")
    val xServer: XServer
    val view: XServerView
    /** IME-capable wrapper around [view] for soft-keyboard embedding (O14 increment 2). */
    val imeView: ClientImeView
    /**
     * The complete attached display surface.  Keeping the X surface and the
     * focusable IME target in one container is important: a View that is only
     * constructed (but not attached to a window) cannot become an Android IME
     * target.  Callers should attach this container instead of attaching
     * [view] and [imeView] separately.
     */
    val container: FrameLayout
    private val connector: XConnectorEpoll
    private val sysvConnector: XConnectorEpoll
    private val alsaConnector: XConnectorEpoll?
    private val vortekComponent: VortekRendererComponent?
    private val sysvSharedMemory: SysVSharedMemory
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceChanged(deviceId: Int) = Unit
        override fun onInputDeviceRemoved(deviceId: Int) { releaseInput(deviceId) }
    }
    private val contract: InputContract
    private val input: ClientInputBridge
    private var autoLogin: SinglePlayerAutoLoginController? = null
    private val profileStore = InputProfileStore(context)
    private val mutableProfile = MutableStateFlow(InputProfile.DEFAULT)
    val profile: StateFlow<InputProfile> = mutableProfile.asStateFlow()
    private val mutableCameraLocked = MutableStateFlow(false)
    val cameraLocked: StateFlow<Boolean> = mutableCameraLocked.asStateFlow()
    private val mutablePointerCaptured = MutableStateFlow(false)
    val pointerCaptured: StateFlow<Boolean> = mutablePointerCaptured.asStateFlow()
    /**
     * Monotonic display/client generation owned by this host instance. A fresh
     * host (surface recreate or client relaunch) gets a new generation; the
     * [InputContract] rejects events stamped with any other generation so a
     * stale touch from a recycled surface cannot reach the new WoW window.
     */
    val generation: Long
    @Volatile private var reportedWindow = false
    @Volatile private var activeWindow: Window? = null
    @Volatile private var closeRequested = false
    private val closeStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed: Boolean get() = closeStarted.get()
    @Volatile private var paused = false
    private var closeAttempts = 0
    private var deleteTargetLogged = false
    private val closeRetry = Runnable { attemptClose() }
    private var gamepadPointerPumpRunning = false
    private val gamepadPointerFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!gamepadPointerPumpRunning || paused || closed) {
                gamepadPointerPumpRunning = false
                return
            }
            contract.pumpGamepadPointer(frameTimeNanos, generation)
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }
    val windowVisible: Boolean get() = reportedWindow
    val rendererReady: Boolean get() = view.renderer.isSurfaceReady
    val rendererSurfaceGeneration: Long get() = view.renderer.surfaceGeneration
    val vulkanBridgeReady: Boolean get() = vortekComponent?.isReady ?: vulkanDriverId == null ||
        VulkanDriverCatalog.find(vulkanDriverId)?.kind != VulkanDriverKind.SYSTEM
    @Volatile var presentationFrameRateHint: Float = 0f
        private set
    private lateinit var presentationCallback: android.view.SurfaceHolder.Callback

    private fun resolveTransportRoot(runtimeRoot: String): java.io.File {
        val root = java.io.File(runtimeRoot)
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            return java.io.File(root, "tmp")
        }
        val rootfs = java.io.File(root, "rootfs")
        return java.io.File(rootfs, "tmp")
    }

    init {
        ClientFrameCap.requireFps(frameCap)
        val arm = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val vulkanDriver = if (arm) VulkanDriverCatalog.requireForRequest(vulkanDriverId) else {
            require(vulkanDriverId == null) { "x86 display does not accept an ARM Vulkan driver" }
            null
        }
        val rendererPackage = if (arm) RendererPackageCatalog.requireForRequest(
            ArmTranslationBackend.BOX64, "dxvk", rendererPackageId,
        ) else {
            require(rendererPackageId == null) { "x86 display does not accept an ARM renderer package" }
            null
        }
        System.loadLibrary("winlator")
        generation = nextGeneration()
        val tmp = transportRoot
        java.io.File(tmp, ".X11-unix").mkdirs()
        java.io.File(tmp, ".sysvshm").mkdirs()
        if (audioEnabled) java.io.File(tmp, ".sound").mkdirs()
        if (vulkanDriver?.kind == VulkanDriverKind.SYSTEM) java.io.File(tmp, ".vortek").mkdirs()
        xServer = XServer(
            XServerDisplayActivity(),
            ScreenInfo(displayProfile.virtualWidth, displayProfile.virtualHeight),
        )
        sysvSharedMemory = SysVSharedMemory()
        xServer.setSHMSegmentManager(SHMSegmentManager(sysvSharedMemory))
        view = XServerView(context, xServer).apply {
            contentDescription = "Pocket Realm client display"
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setPointerCaptureObserver { captured ->
                    mutablePointerCaptured.value = captured
                }
            }
        }
        xServer.setRenderer(view.renderer)
        presentationCallback = object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) = applyFrameRateHint(holder)
            override fun surfaceChanged(
                holder: android.view.SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) = applyFrameRateHint(holder)
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                presentationFrameRateHint = 0f
            }
        }
        view.holder.addCallback(presentationCallback)
        contract = InputContract(
            XServerInputSink(xServer),
            ImePulseScheduler { delayMillis, action ->
                view.postDelayed(action, delayMillis)
            },
            imeKeyDwellMs = timings.imeKeyDwellMs,
            imeKeyGapMs = timings.imeKeyGapMs,
            fieldSettleMs = timings.fieldSettleMs,
            pointerDwellMs = timings.pointerDwellMs,
            onCameraLockChanged = { locked -> mutableCameraLocked.value = locked },
        )
        // Attach with no session yet; the bridge stamps every event with this
        // host's generation. A session id is informational only and may be set
        // later without changing the generation (the host IS the generation).
        val displayAspectIdentity = InputProfile.aspectIdentity(
            displayProfile.virtualWidth, displayProfile.virtualHeight,
        )
        val persistedProfile = profileStore.load(displayAspectIdentity).profile
        contract.attach(
            sessionId = null,
            generation = generation,
            newProfile = persistedProfile,
            aspectIdentity = displayAspectIdentity,
        )
        profileStore.save(contract.activeProfile)
        mutableProfile.value = contract.activeProfile
        input = ClientInputBridge(
            contract,
            view,
            generation,
            displayProfile.virtualWidth,
            displayProfile.virtualHeight,
            ::ensureKeyboardFocus,
        )
        // IntegratedClientDisplay constructs hosts from its Binder worker;
        // InputManager's null-handler overload assumes the calling thread has
        // a Looper. Device callbacks mutate the UI-owned input contract, so
        // bind them explicitly to the main Looper on every construction path.
        inputManager.registerInputDeviceListener(
            inputDeviceListener,
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
        imeView = ClientImeView(
            context = context,
            contractProvider = { contract },
            generationProvider = { generation },
            beforeImeInput = ::ensureKeyboardFocus,
            onImeOpened = {
                if (!paused && !closed) contract.imeOpened(generation)
            },
            onImeClosed = {
                if (!closed) {
                    contract.imeClosed(generation)
                    imeView.clearFocus()
                    imeView.isFocusable = false
                    imeView.isFocusableInTouchMode = false
                    if (!paused) view.requestFocus()
                }
            },
        )
        // The hidden editor is opt-in. If it participates in initial focus,
        // Android may reopen the keyboard as soon as the client task attaches.
        imeView.isFocusable = false
        imeView.isFocusableInTouchMode = false
        input.attachImeTarget(imeView)
        container = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(imeView, FrameLayout.LayoutParams(1, 1))
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) {
                    view.post { if (!closed && !paused) view.requestFocus() }
                }
                override fun onViewDetachedFromWindow(v: android.view.View) = Unit
            })
        }
        autoLogin = autoLoginCredentials?.let { credentials ->
            val lane = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
                AutoLoginWindowLane.ARM_TRANSLATED
            } else {
                AutoLoginWindowLane.X86_DIRECT
            }
            SinglePlayerAutoLoginController(
                username = credentials.username,
                password = credentials.password,
                generation = generation,
                lane = lane,
                expectedWidth = displayProfile.virtualWidth,
                expectedHeight = displayProfile.virtualHeight,
                bridge = object : AutoLoginBridge {
                    override fun isActive(generation: Long): Boolean =
                        this@ClientDisplayHost.generation == generation && !paused && !closed

                    override fun isRendererReady(): Boolean = rendererReady

                    override fun isInputNeutral(generation: Long): Boolean =
                        contract.isNeutral(generation)

                    override fun mappedWindows(): List<AutoLoginWindow> =
                        autoLoginTopologySnapshot()

                    override fun queueCredentials(
                        username: String,
                        password: String,
                        generation: Long,
                    ): Boolean {
                        val target = autoLoginTarget() ?: return false
                        xServer.windowManager.setFocus(
                            target,
                            WindowManager.FocusRevertTo.POINTER_ROOT,
                        )
                        if (xServer.windowManager.focusedWindow !== target) return false
                        return contract.queueSinglePlayerAutoLogin(
                            username,
                            password,
                            generation,
                            target.rootX.toInt(),
                            target.rootY.toInt(),
                            target.width.toInt(),
                            target.height.toInt(),
                        )
                    }

                    override fun isCredentialQueueIdle(): Boolean = contract.isImeInputIdle

                    override fun cancelCredentialQueue(generation: Long) {
                        contract.imeClosed(generation)
                    }
                },
                scheduler = object : AutoLoginScheduler {
                    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

                    override fun postDelayed(delayMs: Long, action: () -> Unit) {
                        view.postDelayed(action, delayMs)
                    }
                },
                onDiagnostic = { detail ->
                    val topology = xServer.windowManager.mappedClientWindows
                        .filter { it.parent === xServer.windowManager.rootWindow }
                        .joinToString(prefix = "[", postfix = "]", limit = 8) {
                            "${it.width.toInt()}x${it.height.toInt()}:" +
                                (if (it.isRenderable) "renderable" else "hidden") + ":" +
                                (if (it.isDesktopWindow) "desktop" else "client") + ":" +
                                it.name.take(24) + ":" + it.className.take(24)
                        }
                    com.pocketrealm.log.AppLog.i(
                        TAG,
                        "auto-login $detail topLevels=$topology",
                    )
                },
                onTerminal = { view.post { autoLogin = null } },
                timings = timings,
            )
        }
        autoLogin?.start(autoLoginTopologySnapshot())
        val config = UnixSocketConfig.create(tmp.absolutePath, ".X11-unix/X0")
        val sysvConfig = UnixSocketConfig.create(
            tmp.absolutePath,
            ".sysvshm/SM0",
        )
        sysvConnector = XConnectorEpoll(
            sysvConfig,
            SysVSHMConnectionHandler(sysvSharedMemory),
            SysVSHMRequestHandler(),
        ).apply {
            setInitialInputBufferCapacity(256)
            setInitialOutputBufferCapacity(256)
            setCanReceiveAncillaryMessages(true)
        }
        // O23 audio: Android-side ALSA server (peer to the Winlator android_aserver
        // The audio socket is a real blocking endpoint. Keep it entirely absent
        // when Settings says muted so an audio-off session has no idle connector,
        // thread, or stale socket to inherit on the next launch.
        // Remove only our exact endpoint. In particular, never recursively
        // delete .sound because other transports may share that directory.
        alsaSocket.delete()
        alsaConnector = if (audioEnabled) {
            val alsaConfig = UnixSocketConfig.create(tmp.absolutePath, ".sound/AS0")
            ALSAClient.assignFramesPerBuffer(context)
            XConnectorEpoll(
                alsaConfig,
                ALSAClientConnectionHandler(ALSAClient.Options()),
                ALSARequestHandler(),
            ).apply { setMultithreadedClients(true) }
        } else null
        vortekComponent = if (vulkanDriver?.kind == VulkanDriverKind.SYSTEM) {
            val systemCapabilities = AndroidSystemVulkanProbe.probe()
            val compatibility = VulkanDriverCatalog.requireCompatiblePair(
                vulkanDriver,
                requireNotNull(rendererPackage),
                systemCapabilities,
            )
            val options = VortekRendererComponent.Options().apply {
                vkMaxVersion = compatibility.vkMaxVersion
                hardenedSafeLane = true
            }
            VortekRendererComponent(
                context,
                xServer,
                UnixSocketConfig.create(tmp.absolutePath, ".vortek/V0"),
                options,
            )
        } else null
        connector = XConnectorEpoll(config, XClientConnectionHandler(xServer), XClientRequestHandler()).apply {
            setInitialInputBufferCapacity(4096)
            setInitialOutputBufferCapacity(4096)
            setCanReceiveAncillaryMessages(true)
        }
        xServer.windowManager.addOnWindowModificationListener(object : WindowManager.OnWindowModificationListener {
            override fun onMapWindow(window: Window) {
                if (window !== xServer.windowManager.rootWindow) {
                    val current = activeWindow
                    if (!window.isDesktopWindow && window.isRenderable &&
                        (current == null || current.isDesktopWindow ||
                            window.getWidth().toInt() * window.getHeight().toInt() >=
                            current.getWidth().toInt() * current.getHeight().toInt())) {
                        activeWindow = window
                    }
                }
                if (window !== xServer.windowManager.rootWindow && !reportedWindow &&
                    !window.isDesktopWindow && window.isRenderable &&
                    window.width.toInt() >= 640 && window.height.toInt() >= 480) {
                    reportedWindow = true
                    view.post(onWindowVisible)
                }
                val snapshot = autoLoginTopologySnapshot()
                AppLog.i(TAG, "auto-login topology map=" + snapshot.joinToString(
                    prefix = "[", postfix = "]", limit = 12,
                ) {
                    "${it.width}x${it.height}:" +
                        (if (it.topLevel) "top" else "child") + ":" +
                        (if (it.desktop) "desktop" else "client") + ":" +
                        (if (it.renderable) "renderable" else "hidden")
                })
                view.post { autoLogin?.onTopologyChanged(snapshot) }
            }

            override fun onUnmapWindow(window: Window) {
                if (activeWindow === window) activeWindow = null
                val snapshot = autoLoginTopologySnapshot()
                AppLog.i(TAG, "auto-login topology unmap=" + snapshot.joinToString(
                    prefix = "[", postfix = "]", limit = 12,
                ) {
                    "${it.width}x${it.height}:" +
                        (if (it.topLevel) "top" else "child") + ":" +
                        (if (it.desktop) "desktop" else "client") + ":" +
                        (if (it.renderable) "renderable" else "hidden")
                })
                view.post { autoLogin?.onTopologyChanged(snapshot) }
            }
        })
        sysvConnector.start()
        alsaConnector?.start()
        vortekComponent?.start()
        connector.start()
    }

    fun releaseInput(source: Int? = null) = input.releaseAll(source)
    fun awaitRendererReady(timeoutMs: Long): Boolean =
        view.renderer.awaitSurfaceReady(timeoutMs, TimeUnit.MILLISECONDS)

    /** Capture on the X callback thread so transient modals cannot disappear
     * before the main-thread state machine observes their topology. */
    private fun autoLoginTopologySnapshot(): List<AutoLoginWindow> =
        xServer.windowManager.mappedClientWindows.map { window ->
            AutoLoginWindow(
                name = window.name.orEmpty(),
                className = window.className.orEmpty(),
                width = window.width.toInt(),
                height = window.height.toInt(),
                processId = window.processId,
                renderable = window.isRenderable,
                topLevel = window.parent === xServer.windowManager.rootWindow,
                desktop = window.isDesktopWindow,
            )
        }.toList()

    /** Resolve the exact native Window represented by the accepted topology. */
    private fun autoLoginTarget(): Window? {
        val mapped = xServer.windowManager.mappedClientWindows
        val topLevel = mapped.filter { window ->
            window.isRenderable && window.parent === xServer.windowManager.rootWindow
        }
        val candidates = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            val topTargets = topLevel.filter { window ->
                !window.isDesktopWindow &&
                    window.width.toInt() == displayProfile.virtualWidth &&
                    window.height.toInt() == displayProfile.virtualHeight
            }
            val nestedTargets = mapped.filter { window ->
                window.parent !== xServer.windowManager.rootWindow &&
                    !window.isDesktopWindow &&
                    window.width.toInt() == displayProfile.virtualWidth &&
                    window.height.toInt() == displayProfile.virtualHeight
            }
            when {
                topTargets.size == 1 -> topTargets
                topTargets.isEmpty() && nestedTargets.size == 1 -> nestedTargets
                else -> emptyList()
            }
        } else {
            topLevel.filter { window ->
                window.name.isNullOrEmpty() && window.className.equals("wow.exe", ignoreCase = true) &&
                    window.processId > 0 &&
                    window.width.toInt() == displayProfile.virtualWidth &&
                    window.height.toInt() == displayProfile.virtualHeight
            }
        }
        val target = candidates.singleOrNull() ?: return null
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            val desktop = topLevel.filter {
                it.isDesktopWindow && it.width.toInt() == displayProfile.virtualWidth &&
                    it.height.toInt() == displayProfile.virtualHeight
            }
            if ((target.parent !== xServer.windowManager.rootWindow && desktop.size != 1) ||
                topLevel.any {
                    it !== target && it !in desktop &&
                        (it.width.toInt() > 16 || it.height.toInt() > 16)
                }) return null
        }
        return target
    }

    private fun ensureKeyboardFocus() {
        if (closed || paused) return
        // DesktopHelper owns normal application focus on map and pointer
        // press. Preserve it only when it is a real client window; the root
        // window can select KEY_PRESS for desktop bookkeeping but cannot
        // deliver text to Wine. The deepest composited D3D child often selects
        // no keyboard events, so fall back to the active/top-level WoW client.
        fun Window.acceptsClientKeys(): Boolean =
            this !== xServer.windowManager.rootWindow &&
                originClient != null &&
                hasEventListenerFor(Event.KEY_PRESS)
        val mapped = xServer.windowManager.mappedClientWindows
        fun Window.isLiveClientKeyTarget(): Boolean =
            isRenderable && mapped.contains(this) && acceptsClientKeys()
        val focused = xServer.windowManager.focusedWindow
        val target = focused?.takeIf { it.isLiveClientKeyTarget() }
            ?: activeWindow?.takeIf { it.isLiveClientKeyTarget() }
            ?: mapped.asSequence()
                .filter { it.isLiveClientKeyTarget() }
                .maxByOrNull { window ->
                    val identity = "${window.name} ${window.className}".lowercase()
                    val wowPreference = if ("wow" in identity || "world of warcraft" in identity) {
                        1_000_000_000L
                    } else 0L
                    val area = window.width.toLong().coerceAtLeast(0) *
                        window.height.toLong().coerceAtLeast(0)
                    wowPreference + area
                }
        target?.let { window ->
            xServer.windowManager.setFocus(window, WindowManager.FocusRevertTo.POINTER_ROOT)
        }
    }
    fun dispatchKey(event: KeyEvent): Boolean = input.dispatchKey(event)
    fun dispatchPointer(event: MotionEvent): Boolean = input.dispatchPointer(event)
    fun dispatchGamepad(event: MotionEvent): Boolean = input.dispatchGamepad(event)

    /** Send one logical virtual-control key through the same source tracking. */
    fun dispatchVirtualKey(keyCode: Int, pressed: Boolean, source: Int = VIRTUAL_SOURCE): Boolean =
        input.dispatchVirtualKey(keyCode, pressed, source)

    /** Dispatch one allowlisted touch-overlay action through the input contract. */
    fun dispatchVirtualAction(
        action: ControllerAction,
        pressed: Boolean,
        source: Int = VIRTUAL_SOURCE,
    ): Boolean = when {
        action == ControllerAction.DISABLED -> true
        action.keyCode != null -> input.dispatchVirtualKey(action.keyCode, pressed, source)
        action.pointer == ControlPointer.LEFT -> {
            contract.pointerButton(source, InputContract.PointerButton.LEFT, pressed, generation)
            true
        }
        action.pointer == ControlPointer.RIGHT -> {
            contract.pointerButton(source, InputContract.PointerButton.RIGHT, pressed, generation)
            true
        }
        action.cameraLockToggle -> {
            if (pressed) contract.toggleCameraLock(generation, source)
            true
        }
        else -> false
    }

    /** Toggle persistent right-mouse camera look for stick and touch movement. */
    fun toggleCameraLock(source: Int = VIRTUAL_SOURCE): Boolean =
        contract.toggleCameraLock(generation, source)

    fun setCameraLock(enabled: Boolean, source: Int = VIRTUAL_SOURCE): Boolean =
        contract.setCameraLock(enabled, generation, source)

    /** Request/release Android pointer capture for physical mouse camera-look. */
    fun setPointerCapture(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (enabled) {
            view.requestFocus()
            view.requestPointerCapture()
        } else {
            view.releasePointerCapture()
            mutablePointerCaptured.value = false
        }
        // requestPointerCapture() is asynchronous. Returning the requested
        // state lets the UI acknowledge the mode change immediately; callers
        // that need proof use [isPointerCaptured] after Android's callback.
        return enabled
    }

    val isPointerCaptured: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && view.hasPointerCapture()

    val activeProfile: InputProfile
        get() = contract.activeProfile

    /** Persist a profile only after the contract has atomically switched to it. */
    fun switchInputProfile(profile: InputProfile): InputContract.ReleaseReport {
        val aspect = InputProfile.aspectIdentity(
            displayProfile.virtualWidth, displayProfile.virtualHeight,
        )
        val report = contract.switchProfile(profile, aspect, generation)
        profileStore.save(contract.activeProfile)
        mutableProfile.value = contract.activeProfile
        return report
    }

    // ---- O14 increment 1: additional pointer entry points -----------------
    // These route through the same [InputContract] generation gate and pressed-
    // state tracking as touch/keyboard. They preserve the verified left-button
    // path unchanged and add right/middle/wheel/relative for O14.

    /** Right-button press/release (long-press / secondary). */
    fun dispatchRightButton(pressed: Boolean) {
        contract.pointerButton(DEFAULT_SOURCE, InputContract.PointerButton.RIGHT, pressed, generation)
    }
    /** Middle-button press/release. */
    fun dispatchMiddleButton(pressed: Boolean) {
        contract.pointerButton(DEFAULT_SOURCE, InputContract.PointerButton.MIDDLE, pressed, generation)
    }
    /** Wheel pulse. Positive vTicks = down, negative = up; hTicks right/left. */
    fun dispatchWheel(vTicks: Int, hTicks: Int = 0) {
        contract.wheel(DEFAULT_SOURCE, vTicks, hTicks, generation)
    }
    /** Relative pointer motion (camera-look / captured mouse), in X pixels. */
    fun dispatchRelativePointer(dx: Int, dy: Int) {
        contract.pointerRelative(DEFAULT_SOURCE, dx, dy, generation)
    }
    /**
     * Bounded diagnostics snapshot for tests. Returns the contract's cumulative
     * rejected-stale-event count and the last release report.
     */
    fun inputDiagnostics(): InputDiagnostics = InputDiagnostics(
        generation = generation,
        rejectedStaleEventCount = contract.rejectedStaleEventCount,
        lastRelease = contract.lastReport,
        releasedKeyCount = contract.releasedKeyCount,
        releasedButtonCount = contract.releasedButtonCount,
        profileReset = contract.isProfileReset,
    )

    /** Exposed for instrumentation tests that need to drive the contract directly. */
    val inputContract: InputContract get() = contract

    // ---- O14 increment 2: IME ---------------------------------------------
    /**
     * Show the Android soft IME targeting the [imeView]. The IME commits text
     * through the [InputContract]'s generation-gated `imeCommit` path.
     */
    fun showIme() {
        if (closed || paused) return
        val imm = imeView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        ensureKeyboardFocus()
        imeView.isFocusable = true
        imeView.isFocusableInTouchMode = true
        imeView.requestFocus()
        imm.showSoftInput(imeView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hide the soft IME if it is showing and restore gameplay focus. */
    fun hideIme() = hideIme(restoreGameplayFocus = true)

    private fun hideIme(restoreGameplayFocus: Boolean) {
        if (closed) return
        val imm = imeView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(imeView.windowToken, 0)
        contract.imeClosed(generation)
        imeView.clearFocus()
        imeView.isFocusable = false
        imeView.isFocusableInTouchMode = false
        if (restoreGameplayFocus) view.requestFocus()
    }

    /**
     * Commit text directly through the contract's IME path (for instrumentation
     * tests that need to bypass the real soft keyboard but still exercise the
     * contract's generation-gated imeCommit).
     */
    fun imeCommit(text: String) {
        contract.imeCommit(text, generation)
    }

    /** Bounded input diagnostics snapshot. */
    data class InputDiagnostics(
        val generation: Long,
        val rejectedStaleEventCount: Long,
        val lastRelease: InputContract.ReleaseReport,
        val releasedKeyCount: Long,
        val releasedButtonCount: Long,
        val profileReset: Boolean,
    )
    /** Best-effort user-facing graceful close. Wine translates Alt+F4 to the
     * focused Win32 top-level window; the supervisor retains a bounded forced
     * fallback if the application presents a confirmation dialog or stalls. */
    fun requestClose() {
        closeRequested = true
        view.removeCallbacks(closeRetry)
        view.post {
            closeAttempts = 0
            deleteTargetLogged = false
            attemptClose()
        }
    }

    private fun attemptClose() {
        if (!closeRequested || closed) return
        val protocols = Atom.internAtom("WM_PROTOCOLS")
        val deleteWindow = Atom.internAtom("WM_DELETE_WINDOW")
        val mapped = xServer.windowManager.mappedClientWindows
        val candidate = mapped.asSequence()
            .filter { !it.isDesktopWindow && it.originClient != null && it.isRenderable }
            .maxByOrNull { window ->
                val identity = "${window.name} ${window.className}".lowercase()
                val wowPreference = if ("wow" in identity || "world of warcraft" in identity) 1_000_000_000L else 0L
                val protocolPreference = if (supportsDelete(window, protocols, deleteWindow)) 500_000_000L else 0L
                val topLevelPreference = if (window.parent === xServer.windowManager.rootWindow) 100_000_000L else 0L
                val area = window.width.toLong().coerceAtLeast(0) * window.height.toLong().coerceAtLeast(0)
                wowPreference + protocolPreference + topLevelPreference + area
            }
        candidate?.let { window ->
            xServer.windowManager.setFocus(window, WindowManager.FocusRevertTo.POINTER_ROOT)
            val supported = supportsDelete(window, protocols, deleteWindow)
            if (closeAttempts == 0 || supported && !deleteTargetLogged) {
                AppLog.i(TAG, "graceful-close attempt=$closeAttempts window=${window.id} " +
                    "name=${window.name.take(64)} class=${window.className.take(64)} " +
                    "size=${window.width}x${window.height} wmDelete=$supported")
            }
            if (supported) {
                deleteTargetLogged = true
                window.originClient.sendEvent(ClientMessage(window, protocols, deleteWindow, 0))
            }
        }
        if (candidate == null && closeAttempts == 0) {
            AppLog.w(TAG, "no mapped client window yet; retaining graceful close request")
        }
        // Never retain a destroyed Window across retries. X teardown can race
        // this runnable after Wine has already removed its final client
        // window; Alt+F4 is meaningful only while this snapshot still has a
        // mapped target. InputDeviceManager independently tolerates the
        // smaller destroy-between-focus-and-injection race.
        activeWindow = candidate
        candidate?.let { window ->
            xServer.windowManager.setFocus(window, WindowManager.FocusRevertTo.POINTER_ROOT)
            val focused = xServer.windowManager.focusedWindow
            if (focused == null || !focused.isRenderable ||
                !xServer.windowManager.mappedClientWindows.contains(window)) {
                AppLog.w(TAG, "graceful-close target disappeared before Alt+F4; retrying")
                return@let
            }
            val now = android.os.SystemClock.uptimeMillis()
            input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0, KeyEvent.META_ALT_ON))
            input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F4, 0, KeyEvent.META_ALT_ON))
            input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F4, 0, KeyEvent.META_ALT_ON))
            input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0, 0))
        }
        closeAttempts++
        if (closeRequested && !closed && closeAttempts < MAX_CLOSE_ATTEMPTS) {
            view.postDelayed(closeRetry, CLOSE_RETRY_MS)
        }
    }

    private fun supportsDelete(window: Window, protocols: Int, deleteWindow: Int): Boolean =
        window.getProperty(protocols)?.let { property ->
            (0 until property.data.capacity() / 4).any { property.getInt(it) == deleteWindow }
        } == true
    fun onPause() {
        if (closed) return
        paused = true
        autoLogin?.cancel()
        stopGamepadPointerPump()
        setPointerCapture(false)
        // Lifecycle pause must close the contract-side IME state as well as
        // hiding the Android window. Otherwise a background/orientation round
        // trip can leave pointer and gamepad input suppressed after resume.
        hideIme(restoreGameplayFocus = false)
        contract.releaseAll(InputContract.ReleaseReason.ON_PAUSE)
        view.onPause()
    }
    fun onResume() {
        if (closed) return
        paused = false
        view.onResume()
        // RENDERMODE_WHEN_DIRTY does not redraw merely because Android
        // recreated/re-exposed the Surface. Re-present the latest X frame on
        // every foreground transition even when the guest produced no new
        // damage while backgrounded.
        view.requestRender()
        view.requestFocus()
        startGamepadPointerPump()
    }

    private fun startGamepadPointerPump() {
        if (gamepadPointerPumpRunning || paused || closed) return
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "gamepad pointer pump must start on the main thread"
        }
        gamepadPointerPumpRunning = true
        android.view.Choreographer.getInstance().postFrameCallback(gamepadPointerFrameCallback)
    }

    private fun stopGamepadPointerPump() {
        if (!gamepadPointerPumpRunning) return
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "gamepad pointer pump must stop on the main thread"
        }
        gamepadPointerPumpRunning = false
        android.view.Choreographer.getInstance().removeFrameCallback(gamepadPointerFrameCallback)
    }

    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        paused = true
        autoLogin?.cancel()
        closeRequested = false
        // Invalidate the input generation immediately on whichever thread won
        // the close race. Binder release and Compose disposal can arrive
        // concurrently, but only this caller owns teardown from here onward.
        contract.detach()
        val finished = java.util.concurrent.CountDownLatch(1)
        val cleanup = Runnable {
            try {
                stopGamepadPointerPump()
                view.removeCallbacks(closeRetry)
                val imm = imeView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(imeView.windowToken, 0)
                imeView.clearFocus()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) view.releasePointerCapture()
                inputManager.unregisterInputDeviceListener(inputDeviceListener)
                try {
                    alsaConnector?.destroy()
                } finally {
                    alsaSocket.delete()
                    alsaSocket.parentFile?.let { soundDirectory ->
                        if (soundDirectory.list()?.isEmpty() == true) soundDirectory.delete()
                    }
                }
                connector.destroy()
                vortekComponent?.close()
                sysvConnector.destroy()
                view.holder.removeCallback(presentationCallback)
                sysvSharedMemory.deleteAll()
                view.releaseRenderer()
            } finally {
                finished.countDown()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            cleanup.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(cleanup)
            if (!finished.await(5, TimeUnit.SECONDS)) {
                AppLog.w(TAG, "timed out waiting for main-thread display cleanup")
            }
        }
    }

    companion object {
        private const val TAG = "ClientDisplay"
        private const val CLOSE_RETRY_MS = 500L
        private const val MAX_CLOSE_ATTEMPTS = 60
        /** Default synthetic source id for programmatic pointer/wheel injection. */
        internal const val DEFAULT_SOURCE: Int = -1
        /** Stable source id for the on-screen touch overlay. */
        internal const val VIRTUAL_SOURCE: Int = -3

        private val generationCounter = java.util.concurrent.atomic.AtomicLong(0L)
        private fun nextGeneration(): Long = generationCounter.incrementAndGet()

    }

    private fun applyFrameRateHint(holder: android.view.SurfaceHolder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !holder.surface.isValid) return
        runCatching {
            holder.surface.setFrameRate(
                frameCap.toFloat(),
                android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            )
            presentationFrameRateHint = frameCap.toFloat()
        }.onFailure {
            presentationFrameRateHint = 0f
            AppLog.w(TAG, "Android presentation frame-rate hint was rejected")
        }
    }
}

/**
 * Android→[InputContract] adapter. Owns only the Android-view→X letterbox
 * transform (which is view-specific) and the source id; all pressed-state and
 * X-server injection now lives in the contract. Preserves the verified O06/O12
 * touch + keyboard behavior exactly: same transform math, same clamp, same
 * left-button mapping, same `keyboard.onKeyEvent(event)` forward path.
 *
 * Stale-generation rejection: events arrive with the host's [generation]; if the
 * surface was recreated (new host, new contract), the contract drops them before
 * injection. The Android `deviceId` is the per-source key, matching the prior
 * `keys`/`pointerDown` maps. An unmatched UP is dropped by the contract without
 * synthesizing a phantom DOWN.
 */
internal class ClientInputBridge(
    private val contract: InputContract,
    private val view: XServerView,
    private val generation: Long,
    private val virtualWidth: Int,
    private val virtualHeight: Int,
    private val ensureKeyboardFocus: () -> Unit,
) {
    private data class RelativeFraction(var x: Float = 0f, var y: Float = 0f)

    private val keyTraceBudget = java.util.concurrent.atomic.AtomicInteger(24)
    private val motionTraceBudget = java.util.concurrent.atomic.AtomicInteger(12)
    private val capturedPointerFractions = mutableMapOf<Int, RelativeFraction>()

    init {
        view.setOnKeyListener { _, _, event -> dispatchKey(event) }
        view.setOnTouchListener { _, event -> dispatchPointer(event) }
        view.setOnGenericMotionListener { _, event -> dispatchGenericMotion(event) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            view.setOnCapturedPointerListener { _, event -> dispatchCapturedPointer(event) }
        }
        view.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) contract.focusLost()
        }
    }

    /**
     * The hidden editor owns Android focus while the soft keyboard is open.
     * Route controller mouse and hardware-key events from that focus target as
     * well, otherwise right-stick/R3 input disappears until the IME closes.
     */
    fun attachImeTarget(imeTarget: android.view.View) {
        imeTarget.setOnKeyListener { _, _, event -> dispatchKey(event) }
        imeTarget.setOnGenericMotionListener { _, event -> dispatchGenericMotion(event) }
    }

    fun dispatchKey(event: KeyEvent): Boolean {
        if (isAndroidSystemEvent(event)) return false
        // Retroid exposes one combined KEYBOARD|GAMEPAD|JOYSTICK device. Its
        // BTN_* KeyEvents may carry SOURCE_KEYBOARD even though the owning
        // InputDevice is a gamepad, so classify using both the event and device
        // source masks. Treating those keycodes as keyboard keys makes every
        // face/shoulder button disappear in X11's keyboard map.
        val deviceSources = event.device?.sources ?: 0
        val isGamepad = isGamepadSource(event.source, deviceSources, event.keyCode)
        val layout = gamepadLayout(event.device)
        if (keyTraceBudget.getAndDecrement() > 0) {
            AppLog.i(
                INPUT_TAG,
                "key device=${event.deviceId} name=${event.device?.name?.take(48)} " +
                    "eventSource=0x${event.source.toString(16)} deviceSources=0x${deviceSources.toString(16)} " +
                    "code=${event.keyCode} action=${event.action} gamepad=$isGamepad layout=$layout",
            )
        }
        if (isGamepad && layout == InputContract.GamepadLayout.DISABLED) return false
        if (!isGamepad && contract.activeProfile.controllerFamily == ControllerFamily.TOUCH_ONLY) {
            return false
        }
        if (isGamepad && hasAnalogueTrigger(event.device, event.keyCode)) {
            // A number of pads emit both a digital trigger key and an analogue
            // axis. The axis owns the press so hysteresis cannot double-inject
            // Shift/Ctrl or leave one copy held after release.
            return true
        }
        if (isGamepad && shouldSuppressDigitalDpad(
                event.keyCode,
                hasAxis(event.device, MotionEvent.AXIS_HAT_X),
                hasAxis(event.device, MotionEvent.AXIS_HAT_Y),
            )) {
            // Some controllers publish both D-pad key events and HAT axes.
            // The HAT state owns each advertised direction so a key UP cannot
            // prematurely release the corresponding logical action.
            return true
        }
        ensureKeyboardFocus()
        return if (isGamepad) {
            contract.gamepadButton(
                event.deviceId,
                event.keyCode,
                event.action == KeyEvent.ACTION_DOWN,
                generation,
                layout,
            )
        } else {
            contract.key(event.deviceId, event, generation)
        }
    }

    fun dispatchPointer(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            return dispatchGamepad(event)
        }
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            contract.activeProfile.controllerFamily == ControllerFamily.TOUCH_ONLY) return false
        val t = view.renderer.viewTransformation
        val aspect = if (t.aspect > 0f) t.aspect else 1f
        val x = ((event.x - t.viewOffsetX) / aspect).roundToInt()
            .coerceIn(0, virtualWidth - 1)
        val y = ((event.y - t.viewOffsetY) / aspect).roundToInt()
            .coerceIn(0, virtualHeight - 1)
        contract.pointerAbsolute(event.deviceId, x, y, generation)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
                    !view.hasPointerCapture()) {
                    // Desktop-style physical-mouse behavior: clicking the game
                    // claims relative input automatically. Controller camera
                    // input does not depend on Android pointer capture.
                    view.requestFocus()
                    view.requestPointerCapture()
                }
                // Some Wine top-levels (notably WoW 1.12) advertise WM_CLASS
                // but no WM_NAME/window-group, so Winlator's DesktopHelper does
                // not classify them as application windows. Repair X focus
                // before ButtonPress; focusing only when the first key arrives
                // makes the click an activation click and leaves the Win32 edit
                // control unfocused.
                ensureKeyboardFocus()
                contract.pointerButton(event.deviceId, InputContract.PointerButton.LEFT, pressed = true, generation = generation)
            }
            MotionEvent.ACTION_UP ->
                contract.pointerButton(event.deviceId, InputContract.PointerButton.LEFT, pressed = false, generation = generation)
            MotionEvent.ACTION_BUTTON_PRESS -> dispatchMouseButton(event, pressed = true)
            MotionEvent.ACTION_BUTTON_RELEASE -> dispatchMouseButton(event, pressed = false)
            MotionEvent.ACTION_CANCEL -> contract.releaseSource(
                event.deviceId, InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT,
            )
        }
        return true
    }

    private fun dispatchGenericMotion(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            return dispatchGamepad(event)
        }
        if (!event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return false
        if (contract.activeProfile.controllerFamily == ControllerFamily.TOUCH_ONLY) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                dispatchMouseWheel(event)
                true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                // Preserve absolute pointer location, then route the actual
                // primary/secondary/tertiary button rather than treating every
                // physical mouse button as a left click.
                dispatchPointer(event)
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                dispatchPointer(event)
            }
            else -> false
        }
    }

    fun dispatchGamepad(event: MotionEvent): Boolean {
        if (!event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return true
        ensureKeyboardFocus()
        val source = event.deviceId
        val device = event.device
        val layout = gamepadLayout(device)
        if (layout == InputContract.GamepadLayout.DISABLED) return false
        if (motionTraceBudget.getAndDecrement() > 0) {
            AppLog.i(
                INPUT_TAG,
                "joystick device=$source name=${device?.name?.take(48)} history=${event.historySize} " +
                    "layout=$layout z=${event.getAxisValue(MotionEvent.AXIS_Z)} " +
                    "rz=${event.getAxisValue(MotionEvent.AXIS_RZ)}",
            )
        }
        // Android batches high-rate joystick samples between display frames.
        // Consume the historical points in order instead of throwing them away;
        // dropping two of every three RP6 samples produces the visible cursor
        // skips reported during a steady left-to-right sweep.
        for (historyIndex in 0 until event.historySize) {
            dispatchGamepadSample(event, device, layout, historyIndex)
        }
        dispatchGamepadSample(event, device, layout, CURRENT_SAMPLE)
        return true
    }

    private fun dispatchGamepadSample(
        event: MotionEvent,
        device: android.view.InputDevice?,
        layout: InputContract.GamepadLayout,
        historyIndex: Int,
    ) {
        val source = event.deviceId
        contract.gamepadAxis(source, InputContract.GamepadAxis.LEFT_X,
            axisValue(event, MotionEvent.AXIS_X, historyIndex), generation, layout)
        contract.gamepadAxis(source, InputContract.GamepadAxis.LEFT_Y,
            axisValue(event, MotionEvent.AXIS_Y, historyIndex), generation, layout)
        val rightXAxis = preferredAxis(device, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
        val rightYAxis = preferredAxis(device, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
        contract.gamepadAxis(source, InputContract.GamepadAxis.RIGHT_X,
            axisValue(event, rightXAxis, historyIndex), generation, layout)
        contract.gamepadAxis(source, InputContract.GamepadAxis.RIGHT_Y,
            axisValue(event, rightYAxis, historyIndex), generation, layout)
        if (hasAxis(device, MotionEvent.AXIS_HAT_X) || hasAxis(device, MotionEvent.AXIS_HAT_Y)) {
            contract.gamepadAxis(source, InputContract.GamepadAxis.HAT_X,
                axisValue(event, MotionEvent.AXIS_HAT_X, historyIndex), generation, layout)
            contract.gamepadAxis(source, InputContract.GamepadAxis.HAT_Y,
                axisValue(event, MotionEvent.AXIS_HAT_Y, historyIndex), generation, layout)
        }
        triggerValue(event, device, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_LTRIGGER, historyIndex)?.let { value ->
            contract.gamepadAxis(source, InputContract.GamepadAxis.LEFT_TRIGGER, value, generation, layout)
        }
        triggerValue(event, device, MotionEvent.AXIS_GAS, MotionEvent.AXIS_RTRIGGER, historyIndex)?.let { value ->
            contract.gamepadAxis(source, InputContract.GamepadAxis.RIGHT_TRIGGER, value, generation, layout)
        }
    }

    private fun axisValue(event: MotionEvent, axis: Int, historyIndex: Int): Float =
        if (historyIndex == CURRENT_SAMPLE) event.getAxisValue(axis)
        else event.getHistoricalAxisValue(axis, historyIndex)

    private fun dispatchCapturedPointer(event: MotionEvent): Boolean {
        if (!event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return false
        if (contract.activeProfile.controllerFamily == ControllerFamily.TOUCH_ONLY) return false
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            dispatchMouseWheel(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            dispatchMouseButton(event, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            capturedPointerFractions.remove(event.deviceId)
            contract.releaseSource(event.deviceId, InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
            return true
        }
        // Captured high-rate mice are commonly batched and may report
        // sub-pixel deltas. Consuming only the current sample and rounding each
        // one independently drops motion, producing the visible skips that were
        // reported during a steady sweep. Preserve every historical delta and
        // carry the fraction between events.
        val fraction = capturedPointerFractions.getOrPut(event.deviceId) { RelativeFraction() }
        var accumulatedX = fraction.x
        var accumulatedY = fraction.y
        for (historyIndex in 0 until event.historySize) {
            accumulatedX += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, historyIndex)
            accumulatedY += event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, historyIndex)
        }
        accumulatedX += event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        accumulatedY += event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        val dx = accumulatedX.toInt()
        val dy = accumulatedY.toInt()
        fraction.x = accumulatedX - dx
        fraction.y = accumulatedY - dy
        if (dx != 0 || dy != 0) contract.pointerRelative(event.deviceId, dx, dy, generation)
        return true
    }

    private fun dispatchMouseButton(event: MotionEvent, pressed: Boolean) {
        // Real Android button events expose actionButton. Some instrumentation
        // event factories expose only buttonState, so accept that as a press
        // fallback without changing real-device behavior.
        val actionButton = event.actionButton.takeIf { it != 0 } ?: event.buttonState
        val button = when {
            actionButton and MotionEvent.BUTTON_PRIMARY != 0 -> InputContract.PointerButton.LEFT
            actionButton and MotionEvent.BUTTON_SECONDARY != 0 -> InputContract.PointerButton.RIGHT
            actionButton and MotionEvent.BUTTON_TERTIARY != 0 -> InputContract.PointerButton.MIDDLE
            else -> return
        }
        contract.pointerButton(event.deviceId, button, pressed, generation)
    }

    private fun dispatchMouseWheel(event: MotionEvent) {
        val vertical = axisTicks(event.getAxisValue(MotionEvent.AXIS_VSCROLL), invert = true)
        val horizontal = axisTicks(event.getAxisValue(MotionEvent.AXIS_HSCROLL), invert = false)
        contract.wheel(event.deviceId, vertical, horizontal, generation)
    }

    private fun axisTicks(value: Float, invert: Boolean): Int {
        if (value == 0f) return 0
        val magnitude = kotlin.math.ceil(kotlin.math.abs(value).toDouble()).toInt()
        val signed = if (value > 0f) magnitude else -magnitude
        return if (invert) -signed else signed
    }

    fun dispatchVirtualKey(keyCode: Int, pressed: Boolean, source: Int): Boolean {
        ensureKeyboardFocus()
        val now = android.os.SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
        return contract.key(source, event, generation)
    }

    /**
     * Release a single source (hot-plug removal) or all sources. Kept for the
     * `releaseInput(source?)` public API on the host; routes through the
     * contract's deterministic exit path.
     */
    fun releaseAll(source: Int? = null) {
        if (source == null) {
            capturedPointerFractions.clear()
            contract.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        } else {
            capturedPointerFractions.remove(source)
            contract.releaseSource(source)
        }
    }

    private fun gamepadLayout(device: android.view.InputDevice?): InputContract.GamepadLayout =
        gamepadLayoutFor(
            contract.activeProfile.controllerFamily,
            device != null && isRetroidPocketController(
                device.name, device.descriptor, device.vendorId, device.productId,
            ),
        )

    companion object {
        private const val RP6_CONTROLLER_NAME = "Retroid Pocket Controller"
        private const val RP6_CONTROLLER_DESCRIPTOR = "dc75afea56e3c3a269b97967aa26b8c93c0bd3fb"
        private const val RP6_VENDOR_ID = 0x2022
        private const val RP6_PRODUCT_ID = 0x3001
        private const val INPUT_TAG = "ClientInput"
        private const val CURRENT_SAMPLE = -1

        internal fun isGamepadSource(eventSources: Int, deviceSources: Int, keyCode: Int): Boolean =
            hasSource(eventSources, android.view.InputDevice.SOURCE_GAMEPAD) ||
                hasSource(eventSources, android.view.InputDevice.SOURCE_JOYSTICK) ||
                (isControllerOwnedKey(keyCode) && (
                    hasSource(deviceSources, android.view.InputDevice.SOURCE_GAMEPAD) ||
                        hasSource(deviceSources, android.view.InputDevice.SOURCE_JOYSTICK)
                    ))

        private fun isControllerOwnedKey(keyCode: Int): Boolean =
            // Keep the explicit ranges for host-JVM policy tests and for
            // vendor KeyEvents whose framework classification is incomplete.
            KeyEvent.isGamepadButton(keyCode) ||
                keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
                keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ||
                keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
            )

        internal fun isAndroidSystemEvent(event: KeyEvent): Boolean =
            event.isSystem || isAndroidSystemKey(event.keyCode)

        private fun hasSource(sources: Int, requested: Int): Boolean =
            (sources and requested) == requested

        internal fun isRetroidPocketController(
            name: String?,
            descriptor: String?,
            vendorId: Int,
            productId: Int,
        ): Boolean = name == RP6_CONTROLLER_NAME &&
            (descriptor == RP6_CONTROLLER_DESCRIPTOR ||
                (vendorId == RP6_VENDOR_ID && productId == RP6_PRODUCT_ID))

        internal fun isAndroidSystemKey(keyCode: Int): Boolean = keyCode in setOf(
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_FOCUS,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_RECORD,
        )

        internal fun gamepadLayoutFor(
            family: ControllerFamily,
            isRetroidPocketController: Boolean,
        ): InputContract.GamepadLayout = when (family) {
            ControllerFamily.TOUCH_ONLY,
            ControllerFamily.KEYBOARD_MOUSE -> InputContract.GamepadLayout.DISABLED
            ControllerFamily.RETROID_POCKET_6 -> InputContract.GamepadLayout.RETROID_POCKET_6
            ControllerFamily.AUTO -> if (isRetroidPocketController) {
                InputContract.GamepadLayout.RETROID_POCKET_6
            } else InputContract.GamepadLayout.PROFILED
            ControllerFamily.XBOX,
            ControllerFamily.PLAYSTATION,
            ControllerFamily.GENERIC -> InputContract.GamepadLayout.PROFILED
        }

        internal fun shouldSuppressDigitalDpad(
            keyCode: Int,
            hasHatX: Boolean,
            hasHatY: Boolean,
        ): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> hasHatX
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> hasHatY
            else -> false
        }

        private fun hasAxis(device: android.view.InputDevice?, axis: Int): Boolean =
            device?.getMotionRange(axis, android.view.InputDevice.SOURCE_JOYSTICK) != null

        private fun preferredAxis(device: android.view.InputDevice?, preferred: Int, fallback: Int): Int =
            if (hasAxis(device, preferred)) preferred else fallback

        private fun triggerValue(
            event: MotionEvent,
            device: android.view.InputDevice?,
            preferred: Int,
            fallback: Int,
            historyIndex: Int,
        ): Float? {
            val axis = when {
                hasAxis(device, preferred) -> preferred
                hasAxis(device, fallback) -> fallback
                else -> return null
            }
            val range = device?.getMotionRange(axis, android.view.InputDevice.SOURCE_JOYSTICK)
                ?: return null
            val span = range.max - range.min
            if (span <= 0f) return 0f
            val raw = if (historyIndex == CURRENT_SAMPLE) event.getAxisValue(axis)
                else event.getHistoricalAxisValue(axis, historyIndex)
            return ((raw - range.min) / span).coerceIn(0f, 1f)
        }

        private fun hasAnalogueTrigger(device: android.view.InputDevice?, keyCode: Int): Boolean =
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_L2 ->
                    hasAxis(device, MotionEvent.AXIS_BRAKE) || hasAxis(device, MotionEvent.AXIS_LTRIGGER)
                KeyEvent.KEYCODE_BUTTON_R2 ->
                    hasAxis(device, MotionEvent.AXIS_GAS) || hasAxis(device, MotionEvent.AXIS_RTRIGGER)
                else -> false
            }

    }
}
