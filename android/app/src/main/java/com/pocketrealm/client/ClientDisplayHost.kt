package com.pocketrealm.client

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.pocketrealm.log.AppLog
import com.winlator.XServerDisplayActivity
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xserver.Pointer
import com.winlator.xserver.Atom
import com.winlator.xserver.Drawable
import com.winlator.xserver.events.Event
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XClientConnectionHandler
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.XServer
import com.winlator.xserver.events.ClientMessage
import kotlin.math.roundToInt

/** UI-owned X transport, rendered SurfaceView, and normalized input bridge. */
class ClientDisplayHost(
    context: Context,
    runtimeRoot: String,
    private val onWindowVisible: () -> Unit,
) : AutoCloseable {
    val xServer: XServer
    val view: XServerView
    private val connector: XConnectorEpoll
    private val input: ClientInputBridge
    @Volatile private var reportedWindow = false
    @Volatile private var activeWindow: Window? = null
    @Volatile private var closeRequested = false
    private var closeAttempts = 0
    private var deleteTargetLogged = false
    private val closeRetry = Runnable { attemptClose() }
    val windowVisible: Boolean get() = reportedWindow

    /** Test/diagnostic view of the drawable selected for normal input and
     * graceful-close routing. Callers must only inspect it through renderer
     * queue operations because its texture is GLES-thread owned. */
    fun activeDrawable(): Drawable? = activeWindow?.content

    fun mappedWindows(): List<Window> = xServer.windowManager.mappedClientWindows.toList()

    fun windowSummary(): String {
        val selected = activeWindow
        return xServer.windowManager.mappedClientWindows.joinToString(";") { window ->
            val marker = if (window === selected) "*" else ""
            "$marker${window.id}:p=${window.parent?.id}:" +
                "xy=${window.rootX},${window.rootY}:${window.width}x${window.height}:" +
                "desktop=${window.isDesktopWindow}:" +
                "${window.name.take(32)}:${window.className.take(32)}"
        }
    }

    init {
        System.loadLibrary("winlator")
        val tmp = java.io.File(runtimeRoot, "tmp").apply { mkdirs() }
        java.io.File(tmp, ".X11-unix").mkdirs()
        xServer = XServer(XServerDisplayActivity(), ScreenInfo(1280, 720))
        view = XServerView(context, xServer).apply {
            contentDescription = "Pocket Realm client display"
            isFocusable = true
            isFocusableInTouchMode = true
        }
        xServer.setRenderer(view.renderer)
        input = ClientInputBridge(xServer, view)
        val config = UnixSocketConfig.create(tmp.absolutePath, ".X11-unix/X0")
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
                if (window !== xServer.windowManager.rootWindow && !reportedWindow) {
                    reportedWindow = true
                    view.post(onWindowVisible)
                }
            }

            override fun onUnmapWindow(window: Window) {
                if (activeWindow === window) activeWindow = null
            }
        })
        connector.start()
    }

    fun releaseInput(source: Int? = null) = input.releaseAll(source)
    fun dispatchKey(event: KeyEvent): Boolean {
        // DesktopHelper owns normal application focus on map and pointer
        // press. Preserve it; the deepest composited D3D child often selects
        // no keyboard events. Only repair focus when no usable target exists.
        val focused = xServer.windowManager.focusedWindow
        val target = focused?.takeIf { it.hasEventListenerFor(Event.KEY_PRESS) }
            ?: activeWindow?.takeIf { it.hasEventListenerFor(Event.KEY_PRESS) }
            ?: xServer.windowManager.mappedClientWindows.asSequence()
                .filter { it.originClient != null && it.hasEventListenerFor(Event.KEY_PRESS) }
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
        return input.dispatchKey(event)
    }
    fun dispatchPointer(event: MotionEvent): Boolean = input.dispatchPointer(event)
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
        if (!closeRequested) return
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
            ?: activeWindow?.takeIf { !it.isDesktopWindow && it.isRenderable }
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
        activeWindow = candidate ?: activeWindow
        activeWindow?.let { window ->
            xServer.windowManager.setFocus(window, WindowManager.FocusRevertTo.POINTER_ROOT)
        }
        val now = android.os.SystemClock.uptimeMillis()
        input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0, KeyEvent.META_ALT_ON))
        input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F4, 0, KeyEvent.META_ALT_ON))
        input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F4, 0, KeyEvent.META_ALT_ON))
        input.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0, 0))
        closeAttempts++
        if (closeRequested && closeAttempts < MAX_CLOSE_ATTEMPTS) {
            view.postDelayed(closeRetry, CLOSE_RETRY_MS)
        }
    }

    private fun supportsDelete(window: Window, protocols: Int, deleteWindow: Int): Boolean =
        window.getProperty(protocols)?.let { property ->
            (0 until property.data.capacity() / 4).any { property.getInt(it) == deleteWindow }
        } == true
    fun onPause() { input.releaseAll(); view.onPause() }
    fun onResume() { view.onResume(); view.requestFocus() }

    override fun close() {
        closeRequested = false
        view.removeCallbacks(closeRetry)
        input.releaseAll()
        connector.destroy()
    }

    companion object {
        private const val TAG = "ClientDisplay"
        private const val CLOSE_RETRY_MS = 500L
        private const val MAX_CLOSE_ATTEMPTS = 60
    }
}

/** Maintains pressed state per Android input source and synthesizes releases. */
internal class ClientInputBridge(private val xServer: XServer, private val view: XServerView) {
    private val keys = mutableMapOf<Int, MutableSet<Int>>()
    private val pointerDown = mutableSetOf<Int>()

    init {
        view.setOnKeyListener { _, _, event -> dispatchKey(event) }
        view.setOnTouchListener { _, event -> dispatchPointer(event) }
        view.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) releaseAll()
        }
    }

    fun dispatchKey(event: KeyEvent): Boolean {
        val source = event.deviceId
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keys.getOrPut(source) { mutableSetOf() }.add(event.keyCode)
            KeyEvent.ACTION_UP -> keys[source]?.remove(event.keyCode)
        }
        return xServer.keyboard.onKeyEvent(event)
    }

    fun dispatchPointer(event: MotionEvent): Boolean {
        val t = view.renderer.viewTransformation
        val aspect = if (t.aspect > 0f) t.aspect else 1f
        val x = ((event.x - t.viewOffsetX) / aspect).roundToInt().coerceIn(0, 1279)
        val y = ((event.y - t.viewOffsetY) / aspect).roundToInt().coerceIn(0, 719)
        xServer.injectPointerMove(x, y)
        val source = event.deviceId
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                pointerDown += source
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL -> {
                pointerDown -= source
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }
        }
        return true
    }

    fun releaseAll(source: Int? = null) {
        val sources = if (source == null) keys.keys.toList() else listOf(source)
        for (id in sources) {
            for (key in keys.remove(id).orEmpty().toList()) {
                xServer.keyboard.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }
        val pointerSources = if (source == null) pointerDown.toList() else listOf(source)
        if (pointerSources.any { pointerDown.remove(it) }) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        }
    }
}
