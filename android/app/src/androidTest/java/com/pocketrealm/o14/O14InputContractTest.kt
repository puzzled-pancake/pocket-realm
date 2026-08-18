package com.pocketrealm.o14

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.client.ClientDisplayHost
import com.pocketrealm.client.ClientManifest
import com.pocketrealm.client.ClientRuntimeContract
import com.pocketrealm.client.DeviceCaps
import com.pocketrealm.client.InputContract
import com.pocketrealm.client.LaunchRequest
import com.pocketrealm.client.PrefixRequest
import com.pocketrealm.client.X86DirectWineRuntime
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Input-contract acceptance: the versioned [InputContract] routes right/middle
 * mouse buttons, wheel pulses, and relative pointer motion through Android →
 * contract → X server → Wine → the project-owned Win32 self-test probe, and
 * rejects stale-generation input. Existing keyboard, absolute-pointer, and
 * left-click behavior is re-asserted as regression coverage.
 *
 * Lane: AVD-Large-x86_64-v1 (physical AVD O11-Large-x86_64, emulator-5556).
 * Makes no claim for 16 KiB, ARM, physical controllers, or AVD-Modern.
 *
 * The probe runs in interactive mode (waits for the close sentinel), so this
 * test drives all four new inputs then closes. If any input does not reach the
 * Win32 probe, the test fails on its own assertion (per the failure-handling
 * rule: no success claimed from an X-server call alone).
 */
@RunWith(AndroidJUnit4::class)
class O14InputContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var runtime: X86DirectWineRuntime
    private var host: ClientDisplayHost? = null

    @Before fun setUp() {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        runtime = X86DirectWineRuntime(context)
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { host?.close(); activity.finish() }
        runtime.close()
    }

    @Test fun contractRoutesNewInputsToWin32Probe() = runBlocking {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        val client = ClientManifest(ClientRuntimeContract.SELF_TEST_ID)
        val caps = runtime.probe(DeviceCaps(Build.SUPPORTED_ABIS.first(), Build.VERSION.SDK_INT, pageSize), client)
        assertTrue("runtime unsupported: ${caps.reason}", caps.supported)

        val prefix = withTimeout(180_000) { runtime.preparePrefix(PrefixRequest(client)) }
        assertTrue(prefix.ok)

        val mapped = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            host = ClientDisplayHost(context, prefix.runtimeRoot) { mapped.set(true) }
            activity.addContentView(
                host!!.container,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 720),
            )
            host!!.onResume()
        }
        withTimeout(15_000) { host!!.awaitRendererReady(15_000) }
        assertTrue(File(prefix.runtimeRoot, "tmp/.X11-unix/X0").exists())

        val session = runtime.launch(LaunchRequest(prefix.prefixId))
        withTimeout(60_000) {
            host!!.let { h ->
                while (!(mapped.get() && h.xServer.windowManager.mappedClientWindows.isNotEmpty())) delay(100)
            }
        }
        runtime.reportWindowVisible(session.sessionId)
        // MapNotify precedes the client's final event-mask/focus setup.
        delay(1_000)

        // ---- Regression: existing keyboard + left-click still works ----------
        instrumentation.runOnMainSync {
            host!!.view.requestFocus()
            val window = host!!.xServer.windowManager.mappedClientWindows.first()
            val t = host!!.view.renderer.viewTransformation
            val cx = window.x + window.width / 2f
            val cy = window.y + window.height / 2f
            val vx = t.viewOffsetX + cx * t.aspect
            val vy = t.viewOffsetY + cy * t.aspect
            val now = SystemClock.uptimeMillis()
            host!!.dispatchPointer(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, vx, vy, 0))
            host!!.dispatchPointer(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, vx, vy, 0))
        }
        delay(200)
        instrumentation.runOnMainSync {
            host!!.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        delay(150)
        instrumentation.runOnMainSync {
            host!!.dispatchKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        }
        delay(200)

        // ---- right-button down/up -------------------------------------------
        instrumentation.runOnMainSync {
            host!!.dispatchRightButton(pressed = true)
        }
        delay(150)
        instrumentation.runOnMainSync {
            host!!.dispatchRightButton(pressed = false)
        }
        delay(200)

        // ---- middle-button down/up ------------------------------------------
        instrumentation.runOnMainSync {
            host!!.dispatchMiddleButton(pressed = true)
        }
        delay(150)
        instrumentation.runOnMainSync {
            host!!.dispatchMiddleButton(pressed = false)
        }
        delay(200)

        // ---- wheel up then down ---------------------------------------------
        instrumentation.runOnMainSync {
            host!!.dispatchWheel(vTicks = -1) // up
        }
        delay(150)
        instrumentation.runOnMainSync {
            host!!.dispatchWheel(vTicks = 1) // down
        }
        delay(200)

        // ---- relative pointer burst (camera-look / captured mouse) ----------
        instrumentation.runOnMainSync {
            host!!.dispatchRelativePointer(15, 0)
        }
        delay(50)
        instrumentation.runOnMainSync {
            host!!.dispatchRelativePointer(0, 10)
        }
        delay(300)

        // ---- hold right button + a key, trigger focus loss, verify both releases ----
        instrumentation.runOnMainSync {
            host!!.dispatchRightButton(pressed = true)
            host!!.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
        }
        delay(200)
        val focusReleaseReport = host!!.inputContract.focusLost()
        assertEquals(InputContract.ReleaseReason.FOCUS_LOSS, focusReleaseReport.reason)
        assertTrue("right button should be in release report", focusReleaseReport.buttonCount >= 1)
        assertTrue("key should be in release report", focusReleaseReport.keyCount >= 1)
        delay(300)

        // ---- stale-generation rejection --------------------------------------
        val staleGen = host!!.generation + 999L
        val beforeReject = host!!.inputDiagnostics().rejectedStaleEventCount
        instrumentation.runOnMainSync {
            host!!.dispatchRightButton(pressed = true) // valid (current gen)
        }
        delay(100)
        // Inject directly with a stale generation through the contract.
        host!!.inputContract.pointerButton(
            src = -1,
            button = InputContract.PointerButton.RIGHT,
            pressed = true,
            generation = staleGen,
        )
        delay(100)
        val afterReject = host!!.inputDiagnostics().rejectedStaleEventCount
        assertTrue("stale-generation event must be rejected", afterReject > beforeReject)

        // ---- Close cleanly and collect diagnostics --------------------------
        val close = runtime.requestClose(session.sessionId)
        assertTrue(close.requested)
        val terminal = withTimeout(30_000) { runtime.observe(session.sessionId).last() }
        assertEquals(com.pocketrealm.client.ClientState.EXITED, terminal.state)
        val d = runtime.collectDiagnostics(session.sessionId)
        assertTrue("cleanExit", d.cleanExit)
        assertTrue("windowVisible", d.windowVisible)
        assertTrue("focusSeen", d.focusSeen)
        assertTrue("audioOff", d.audioOff)

        // Regression assertions (existing behavior preserved).
        assertTrue("keyboardSeen (regression)", d.keyboardSeen)
        assertTrue("mouseSeen (regression)", d.mouseSeen)

        // Assertions: each new input path observed through the Win32 probe.
        assertTrue("rightButtonSeen\n${d.stdoutTail}", d.rightButtonSeen)
        assertTrue("middleButtonSeen\n${d.stdoutTail}", d.middleButtonSeen)
        assertTrue("wheelSeen\n${d.stdoutTail}", d.wheelSeen)
        assertTrue("relativeMotionSeen\n${d.stdoutTail}", d.relativeMotionSeen)

        assertFalse("forced", d.forced)

        // Capture proof artifacts for the evidence record.
        val outDir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(outDir, "o14-input-contract.PROOF.txt").writeText(
            """
            O14_INPUT_CONTRACT_ACCEPTANCE
            cleanExit=${d.cleanExit}
            windowVisible=${d.windowVisible}
            focusSeen=${d.focusSeen}
            audioOff=${d.audioOff}
            keyboardSeen=${d.keyboardSeen}
            mouseSeen=${d.mouseSeen}
            rightButtonSeen=${d.rightButtonSeen}
            middleButtonSeen=${d.middleButtonSeen}
            wheelSeen=${d.wheelSeen}
            relativeMotionSeen=${d.relativeMotionSeen}
            forced=${d.forced}
            rejectedStaleEventCount=${host!!.inputDiagnostics().rejectedStaleEventCount}
            lastReleaseReason=${host!!.inputDiagnostics().lastRelease.reason}
            lastReleaseKeys=${host!!.inputDiagnostics().lastRelease.keyCount}
            lastReleaseButtons=${host!!.inputDiagnostics().lastRelease.buttonCount}
            generation=${host!!.generation}
            profileReset=${host!!.inputDiagnostics().profileReset}
            """.trimIndent(),
        )
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(outDir, "o14-input-contract-proof.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        android.util.Log.i("O14Acceptance", "O14_INPUT_CONTRACT_ACCEPTANCE $d")
        Unit
    }
}
