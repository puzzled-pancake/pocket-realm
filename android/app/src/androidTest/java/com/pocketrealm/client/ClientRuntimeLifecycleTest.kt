package com.pocketrealm.client

import android.content.Intent
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ClientRuntimeLifecycleTest {
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

    @Test fun fullLifecycleAndForcedStop() = runBlocking {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        val client = ClientManifest(ClientRuntimeContract.SELF_TEST_ID)
        val caps = runtime.probe(DeviceCaps(Build.SUPPORTED_ABIS.first(), Build.VERSION.SDK_INT, pageSize), client)
        assertTrue(caps.supported)
        assertTrue(caps.immutableCode)

        val prefix = withTimeout(180_000) { runtime.preparePrefix(PrefixRequest(client)) }
        assertTrue(prefix.ok)
        val prefixDir = File(prefix.prefixPath)
        assertTrue(File(prefixDir, "system.reg").isFile)
        val durabilityMarker = File(prefixDir, "pocket-relaunch-marker.txt").apply { writeText("preserve") }
        val secondPrepare = withTimeout(180_000) { runtime.preparePrefix(PrefixRequest(client)) }
        assertNotEquals(prefix.prefixId, secondPrepare.prefixId)
        assertTrue(runCatching { runtime.launch(LaunchRequest(prefix.prefixId)) }.isFailure)
        assertEquals("preserve", durabilityMarker.readText())
        assertTrue(File(prefix.runtimeRoot, "prefix-manifest.json").isFile)

        val mapped = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            host = ClientDisplayHost(context, prefix.runtimeRoot) { mapped.set(true) }
            activity.addContentView(
                host!!.container,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 720),
            )
            host!!.onResume()
        }
        waitUntil(10_000) { File(prefix.runtimeRoot, "tmp/.X11-unix/X0").exists() }

        val session = runtime.launch(LaunchRequest(secondPrepare.prefixId))
        waitUntil(60_000) { mapped.get() && host!!.xServer.windowManager.mappedClientWindows.isNotEmpty() }
        runtime.reportWindowVisible(session.sessionId)
        // MapNotify precedes the client's final event-mask/focus setup. The
        // 16 KB emulator exposes that race more readily than the 4 KB lane.
        delay(1_000)

        instrumentation.runOnMainSync {
            host!!.view.requestFocus()
            val window = host!!.xServer.windowManager.mappedClientWindows.first()
            val transform = host!!.view.renderer.viewTransformation
            val clientX = window.x + window.width / 2f
            val clientY = window.y + window.height / 2f
            val viewX = transform.viewOffsetX + clientX * transform.aspect
            val viewY = transform.viewOffsetY + clientY * transform.aspect
            val now = android.os.SystemClock.uptimeMillis()
            host!!.dispatchPointer(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, viewX, viewY, 0))
            host!!.dispatchPointer(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, viewX, viewY, 0))
        }
        delay(150)
        instrumentation.runOnMainSync {
            host!!.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        delay(150)
        instrumentation.runOnMainSync {
            host!!.dispatchKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
            host!!.releaseInput()
        }
        delay(500)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            FileOutputStream(File(context.cacheDir, "client-runtime-proof.png")).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val close = runtime.requestClose(session.sessionId)
        assertTrue(close.requested)
        val terminal = withTimeout(30_000) { runtime.observe(session.sessionId).last() }
        assertEquals(ClientState.EXITED, terminal.state)
        val diagnostics = runtime.collectDiagnostics(session.sessionId)
        assertTrue(diagnostics.cleanExit)
        assertTrue(diagnostics.windowVisible)
        assertTrue(diagnostics.focusSeen)
        assertTrue(diagnostics.audioOff)
        assertTrue(diagnostics.keyboardSeen)
        assertTrue(diagnostics.mouseSeen)
        assertFalse(diagnostics.forced)
        android.util.Log.i(
            "O06Acceptance",
            "CLIENT_RUNTIME_ACCEPTANCE clean=true window=${diagnostics.windowVisible} " +
                "focus=${diagnostics.focusSeen} audioOff=${diagnostics.audioOff} " +
                "keyboard=${diagnostics.keyboardSeen} mouse=${diagnostics.mouseSeen}",
        )

        mapped.set(false)
        val forcedPrefix = withTimeout(180_000) { runtime.preparePrefix(PrefixRequest(client)) }
        val forcedSession = runtime.launch(LaunchRequest(forcedPrefix.prefixId))
        waitUntil(60_000) { host!!.xServer.windowManager.mappedClientWindows.isNotEmpty() }
        runtime.reportWindowVisible(forcedSession.sessionId)
        runtime.forceStop(forcedSession.sessionId)
        val forced = runtime.collectDiagnostics(forcedSession.sessionId)
        assertEquals(ClientState.FORCE_STOPPED, forced.state)
        assertTrue(forced.forced)
        // forceStop now returns only after the native process group has been
        // killed/reaped and the executor has published runtimeFinished. A new
        // prefix generation must therefore be accepted immediately, proving
        // there is no orphan generation blocking reconnect/relaunch.
        val afterForcedDrain = withTimeout(180_000) {
            runtime.preparePrefix(PrefixRequest(client))
        }
        assertTrue(afterForcedDrain.ok)
        android.util.Log.i("O06Acceptance", "CLIENT_RUNTIME_FORCE_STOP state=${forced.state} forced=${forced.forced}")
        Unit
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(100)
        }
    }
}
