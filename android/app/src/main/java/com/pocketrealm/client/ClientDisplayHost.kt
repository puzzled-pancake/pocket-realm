package com.pocketrealm.client

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.pocketrealm.log.AppLog
import com.winlator.XServerDisplayActivity
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.sysvshm.SysVSHMConnectionHandler
import com.winlator.sysvshm.SysVSHMRequestHandler
import com.winlator.sysvshm.SysVSharedMemory
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
    autoLoginCredentials: SinglePlayerAutoLoginCredentials? = null,
    private val onWindowVisible: () -> Unit,
) : AutoCloseable {
    /**
     * Physical transport root. The Box64 image resolves :0 below rootfs/tmp;
     * the ARM64EC/FEX image uses imagefs/usr/tmp. Selecting this from the
     * pinned rootfs layout keeps the Java X socket, SYSV-SHM endpoint, and
     * Wine's X11 environment on the same filesystem namespace.
     */
    val transportRoot: java.io.File = resolveTransportRoot(runtimeRoot).apply { mkdirs() }
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
    val windowVisible: Boolean get() = reportedWindow
    val rendererReady: Boolean get() = view.renderer.isSurfaceReady
    val rendererSurfaceGeneration: Long get() = view.renderer.surfaceGeneration

    private fun resolveTransportRoot(runtimeRoot: String): java.io.File {
        val root = java.io.File(runtimeRoot)
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            return java.io.File(root, "tmp")
        }
        val rootfs = java.io.File(root, "rootfs")
        val fexWine = java.io.File(rootfs, "opt/proton-9.0-arm64ec/bin/wine")
        return if (fexWine.isFile) java.io.File(rootfs, "usr/tmp")
        else java.io.File(rootfs, "tmp")
    }

    init {
        System.loadLibrary("winlator")
        generation = nextGeneration()
        val tmp = transportRoot
        java.io.File(tmp, ".X11-unix").mkdirs()
        java.io.File(tmp, ".sysvshm").mkdirs()
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
        }
        xServer.setRenderer(view.renderer)
        contract = InputContract(
            XServerInputSink(xServer),
            ImePulseScheduler { delayMillis, action ->
                view.postDelayed(action, delayMillis)
            },
        )
        // Attach with no session yet; the bridge stamps every event with this
        // host's generation. A session id is informational only and may be set
        // later without changing the generation (the host IS the generation).
        val persistedProfile = profileStore.load(InputProfile.DEFAULT_ASPECT_IDENTITY).profile
        contract.attach(
            sessionId = null,
            generation = generation,
            newProfile = persistedProfile,
            aspectIdentity = aspectIdentityFor(view),
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
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                val aspect = InputProfile.aspectIdentity(width, height)
                if (aspect != contract.activeProfile.aspectIdentity) {
                    val stored = profileStore.load(aspect).profile
                    contract.switchProfile(stored, aspect, generation)
                    profileStore.save(contract.activeProfile)
                    mutableProfile.value = contract.activeProfile
                }
            }
        }
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
                    window.processId > 0 && window.width.toInt() in 640..1280 &&
                    window.height.toInt() in 480..720
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
        else -> false
    }

    /** Request/release Android pointer capture for physical mouse camera-look. */
    fun setPointerCapture(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (enabled) {
            view.requestFocus()
            view.requestPointerCapture()
        } else {
            view.releasePointerCapture()
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
        val aspect = aspectIdentityFor(view)
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
                view.removeCallbacks(closeRetry)
                val imm = imeView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(imeView.windowToken, 0)
                imeView.clearFocus()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) view.releasePointerCapture()
                inputManager.unregisterInputDeviceListener(inputDeviceListener)
                connector.destroy()
                sysvConnector.destroy()
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

        /** Derive the active aspect identity from the rendered view's transformation. */
        private fun aspectIdentityFor(view: XServerView): String {
            val w = view.width.coerceAtLeast(0)
            val h = view.height.coerceAtLeast(0)
            return if (w == 0 || h == 0) {
                // Both qualified desktop profiles use the fixed 16:9 aspect.
                InputProfile.DEFAULT_ASPECT_IDENTITY
            } else {
                InputProfile.aspectIdentity(w, h)
            }
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
        if (isAndroidSystemKey(event.keyCode)) return false
        ensureKeyboardFocus()
        val layout = gamepadLayout(event.device)
        return if (
            event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)
        ) {
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
        val t = view.renderer.viewTransformation
        val aspect = if (t.aspect > 0f) t.aspect else 1f
        val x = ((event.x - t.viewOffsetX) / aspect).roundToInt()
            .coerceIn(0, virtualWidth - 1)
        val y = ((event.y - t.viewOffsetY) / aspect).roundToInt()
            .coerceIn(0, virtualHeight - 1)
        contract.pointerAbsolute(event.deviceId, x, y, generation)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
        contract.gamepadAxis(source, InputContract.GamepadAxis.LEFT_X,
            event.getAxisValue(MotionEvent.AXIS_X), generation, layout)
        contract.gamepadAxis(source, InputContract.GamepadAxis.LEFT_Y,
            event.getAxisValue(MotionEvent.AXIS_Y), generation, layout)
        val rp6 = layout == InputContract.GamepadLayout.RETROID_POCKET_6
        val rightXAxis = if (rp6 && device?.getMotionRange(
                MotionEvent.AXIS_Z, android.view.InputDevice.SOURCE_JOYSTICK,
            ) != null) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX
        val rightYAxis = if (rp6 && device?.getMotionRange(
                MotionEvent.AXIS_RZ, android.view.InputDevice.SOURCE_JOYSTICK,
            ) != null) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
        contract.gamepadAxis(source, InputContract.GamepadAxis.RIGHT_X,
            event.getAxisValue(rightXAxis), generation, layout)
        contract.gamepadAxis(source, InputContract.GamepadAxis.RIGHT_Y,
            event.getAxisValue(rightYAxis), generation, layout)
        if (rp6) {
            contract.gamepadAxis(source, InputContract.GamepadAxis.HAT_X,
                event.getAxisValue(MotionEvent.AXIS_HAT_X), generation, layout)
            contract.gamepadAxis(source, InputContract.GamepadAxis.HAT_Y,
                event.getAxisValue(MotionEvent.AXIS_HAT_Y), generation, layout)
        }
        return true
    }

    private fun dispatchCapturedPointer(event: MotionEvent): Boolean {
        if (!event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) return false
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            dispatchMouseWheel(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            dispatchMouseButton(event, event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS)
            return true
        }
        val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X).roundToInt()
        val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y).roundToInt()
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
            contract.releaseAll(InputContract.ReleaseReason.EXPLICIT_RELEASE_INPUT)
        } else {
            contract.releaseSource(source)
        }
    }

    companion object {
        private const val RP6_CONTROLLER_NAME = "Retroid Pocket Controller"
        private const val RP6_CONTROLLER_DESCRIPTOR = "dc75afea56e3c3a269b97967aa26b8c93c0bd3fb"
        private const val RP6_VENDOR_ID = 0x2022
        private const val RP6_PRODUCT_ID = 0x3001

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
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_APP_SWITCH,
        )

        private fun gamepadLayout(device: android.view.InputDevice?): InputContract.GamepadLayout =
            if (device != null && isRetroidPocketController(
                    device.name, device.descriptor, device.vendorId, device.productId,
                )) InputContract.GamepadLayout.RETROID_POCKET_6
            else InputContract.GamepadLayout.GENERIC
    }
}
