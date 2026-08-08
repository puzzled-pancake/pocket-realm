package com.pocketrealm.o14

import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketrealm.client.ClientDisplayHost
import com.pocketrealm.client.ClientManifest
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.client.ClientState
import com.pocketrealm.client.DeviceCaps
import com.pocketrealm.client.InputContract
import com.pocketrealm.client.LaunchRequest
import com.pocketrealm.client.PrefixRequest
import com.pocketrealm.client.X86DirectWineRuntime
import com.pocketrealm.ui.MainActivity
import com.pocketrealm.ui.TouchOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-path O14 touch/controller acceptance over the live Wine/X surface.
 * This intentionally uses Compose semantics to press the actual overlay rather
 * than calling its host methods directly.
 */
class O14TouchOverlayAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var runtime: X86DirectWineRuntime
    private var host: ClientDisplayHost? = null

    @After
    fun tearDown() {
        compose.runOnUiThread { host?.close() }
        if (::runtime.isInitialized) runtime.close()
    }

    @Test
    fun touchOverlayAndSyntheticControllerReachWin32Probe() {
        val context = compose.activity.applicationContext
        runtime = X86DirectWineRuntime(context)
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        val client = ClientManifest(ClientRuntimeContract.SELF_TEST_ID)
        val prefix = runBlocking {
            val caps = runtime.probe(
                DeviceCaps(Build.SUPPORTED_ABIS.first(), Build.VERSION.SDK_INT, pageSize), client,
            )
            assertTrue("runtime unsupported: ${caps.reason}", caps.supported)
            withTimeout(180_000) { runtime.preparePrefix(PrefixRequest(client)) }
        }
        assertTrue(prefix.ok)

        val mapped = AtomicBoolean(false)
        compose.runOnUiThread {
            host = ClientDisplayHost(context, prefix.runtimeRoot) { mapped.set(true) }
        }
        compose.activity.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { host!!.container }, modifier = Modifier.fillMaxSize())
                TouchOverlay(host!!)
            }
        }
        compose.runOnIdle { host!!.onResume() }
        assertTrue(host!!.awaitRendererReady(15_000))

        val session = runBlocking { runtime.launch(LaunchRequest(prefix.prefixId)) }
        runBlocking {
            withTimeout(60_000) {
                while (!(mapped.get() && host!!.xServer.windowManager.mappedClientWindows.isNotEmpty())) delay(100)
            }
            runtime.reportWindowVisible(session.sessionId)
            delay(1_000)
        }

        // The Win32 probe is a small top-level window rather than a full-screen
        // game. Seed the X pointer inside it so production relative camera
        // events are delivered to the probe instead of the X root window.
        compose.runOnIdle {
            val window = host!!.xServer.windowManager.mappedClientWindows.first()
            val transform = host!!.view.renderer.viewTransformation
            val x = transform.viewOffsetX + (window.x + window.width / 2f) * transform.aspect
            val y = transform.viewOffsetY + (window.y + window.height / 2f) * transform.aspect
            val now = SystemClock.uptimeMillis()
            val move = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, x, y, 0)
            host!!.dispatchPointer(move)
            move.recycle()
        }
        runBlocking { delay(200) }

        compose.onNodeWithTag("touch-key-W").assertExists().performTouchInput {
            down(center)
            advanceEventTime(180)
            up()
        }
        compose.onNodeWithTag("touch-camera-region").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(48f, 24f), 300)
        }
        runBlocking { delay(500) }

        compose.onNodeWithTag("touch-overlay-toggle").performClick()
        compose.onNodeWithTag("touch-key-W").assertDoesNotExist()
        compose.onNodeWithTag("touch-overlay-toggle").performClick()
        compose.onNodeWithTag("touch-key-W").assertExists()

        compose.onNodeWithTag("touch-chat").performClick()
        compose.waitUntil(5_000) { host!!.inputContract.isImeActive }
        compose.runOnIdle { host!!.onPause() }
        compose.waitUntil(5_000) { !host!!.inputContract.isImeActive }
        compose.runOnIdle { host!!.onResume() }

        compose.onNodeWithTag("touch-pointer-capture").performClick()
        compose.waitUntil(5_000) { host!!.isPointerCaptured }
        compose.onNodeWithTag("touch-pointer-capture").performClick()
        compose.waitUntil(5_000) { !host!!.isPointerCaptured }

        repeat(3) {
            val joystick = joystickEvent(deviceId = GAMEPAD_DEVICE, leftX = 0.9f, rightX = 0.75f)
            compose.runOnIdle { assertTrue(host!!.view.dispatchGenericMotionEvent(joystick)) }
            joystick.recycle()
            runBlocking { delay(100) }
        }
        val neutral = joystickEvent(deviceId = GAMEPAD_DEVICE, leftX = 0f, rightX = 0f)
        compose.runOnIdle { assertTrue(host!!.view.dispatchGenericMotionEvent(neutral)) }
        neutral.recycle()

        val wheel = mouseEvent(MotionEvent.ACTION_SCROLL, verticalScroll = 1f)
        compose.runOnIdle { assertTrue(host!!.view.dispatchGenericMotionEvent(wheel)) }
        wheel.recycle()
        val rightDown = mouseEvent(
            MotionEvent.ACTION_BUTTON_PRESS,
            buttonState = MotionEvent.BUTTON_SECONDARY,
        )
        compose.runOnIdle { assertTrue(host!!.view.dispatchGenericMotionEvent(rightDown)) }
        rightDown.recycle()
        compose.runOnIdle { host!!.releaseInput(MOUSE_DEVICE) }

        val now = SystemClock.uptimeMillis()
        val buttonDown = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X, 0, 0,
            GAMEPAD_DEVICE, 0, 0, InputDevice.SOURCE_GAMEPAD,
        )
        compose.runOnIdle { assertTrue(compose.activity.dispatchKeyEvent(buttonDown)) }
        runBlocking { delay(300) }
        compose.runOnIdle { host!!.releaseInput(GAMEPAD_DEVICE) }
        val unplug = host!!.inputDiagnostics().lastRelease
        assertEquals(InputContract.ReleaseReason.DEVICE_REMOVED, unplug.reason)
        assertTrue("hot-unplug must release the held mapped button", unplug.keyCount >= 1)
        runBlocking { delay(500) }

        val terminal = runBlocking {
            assertTrue(runtime.requestClose(session.sessionId).requested)
            withTimeout(30_000) { runtime.observe(session.sessionId).last() }
        }
        assertEquals(ClientState.EXITED, terminal.state)
        val diagnostics = runBlocking { runtime.collectDiagnostics(session.sessionId) }
        assertTrue("overlay/controller key did not reach Win32", diagnostics.keyboardSeen)
        assertTrue("overlay/gamepad camera motion did not reach Win32", diagnostics.relativeMotionSeen)
        assertTrue("physical-mouse generic wheel route did not reach Win32", diagnostics.wheelSeen)
        assertTrue("physical-mouse secondary button route did not reach Win32", diagnostics.rightButtonSeen)
        assertTrue("self-test did not close cleanly", diagnostics.cleanExit)

        val evidence = JSONObject()
            .put("schema", 1)
            .put("test", "O14TouchOverlayAcceptanceTest")
            .put("touchMovement", true)
            .put("touchCamera", true)
            .put("overlayHideShow", true)
            .put("productionImeRequested", true)
            .put("pauseClosedImeAndResumedInput", true)
            .put("pointerCaptureRoundTrip", true)
            .put("syntheticGamepadMotion", true)
            .put("syntheticGamepadButton", true)
            .put("syntheticPhysicalMouseWheel", true)
            .put("syntheticPhysicalMouseSecondaryButton", true)
            .put("hotUnplugReleaseKeys", unplug.keyCount)
            .put("keyboardSeen", diagnostics.keyboardSeen)
            .put("relativeMotionSeen", diagnostics.relativeMotionSeen)
            .put("wheelSeen", diagnostics.wheelSeen)
            .put("rightButtonSeen", diagnostics.rightButtonSeen)
            .put("cleanExit", diagnostics.cleanExit)
        val out = File(context.getExternalFilesDir(null), "evidence/O14_TOUCH_OVERLAY_EVIDENCE.json")
        out.parentFile!!.mkdirs()
        out.writeText(evidence.toString(2))
    }

    private fun joystickEvent(deviceId: Int, leftX: Float, rightX: Float): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = 0f
            y = 0f
            setAxisValue(MotionEvent.AXIS_X, leftX)
            setAxisValue(MotionEvent.AXIS_RX, rightX)
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            now, now, MotionEvent.ACTION_MOVE, 1,
            arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_JOYSTICK, 0,
        )
    }

    private fun mouseEvent(
        action: Int,
        verticalScroll: Float = 0f,
        buttonState: Int = 0,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = host!!.view.width / 2f
            y = host!!.view.height / 2f
            setAxisValue(MotionEvent.AXIS_VSCROLL, verticalScroll)
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            now, now, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, buttonState, 1f, 1f, MOUSE_DEVICE, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    private companion object {
        const val GAMEPAD_DEVICE = 77
        const val MOUSE_DEVICE = 78
    }
}
