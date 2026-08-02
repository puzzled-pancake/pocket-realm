package com.pocketrealm.client

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.winlator.XServerDisplayActivity
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xserver.Pointer
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XClientConnectionHandler
import com.winlator.xserver.XClientRequestHandler
import com.winlator.xserver.XServer
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
                if (window !== xServer.windowManager.rootWindow && !reportedWindow) {
                    reportedWindow = true
                    view.post(onWindowVisible)
                }
            }
        })
        connector.start()
    }

    fun releaseInput(source: Int? = null) = input.releaseAll(source)
    fun dispatchKey(event: KeyEvent): Boolean = input.dispatchKey(event)
    fun dispatchPointer(event: MotionEvent): Boolean = input.dispatchPointer(event)
    fun onPause() { input.releaseAll(); view.onPause() }
    fun onResume() { view.onResume(); view.requestFocus() }

    override fun close() {
        input.releaseAll()
        connector.destroy()
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
                // This app is the window manager: a tap must also transfer X
                // input focus before subsequent physical-key events.
                xServer.windowManager.findPointWindow(x.toShort(), y.toShort(), true)?.let { window ->
                    if (window !== xServer.windowManager.rootWindow) {
                        xServer.windowManager.setFocus(window, WindowManager.FocusRevertTo.POINTER_ROOT)
                    }
                }
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
