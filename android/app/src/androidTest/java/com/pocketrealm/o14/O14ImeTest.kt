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
import com.pocketrealm.client.ClientState
import com.pocketrealm.client.DeviceCaps
import com.pocketrealm.client.ImeCharMap
import com.pocketrealm.client.InputContract
import com.pocketrealm.client.LaunchRequest
import com.pocketrealm.client.PrefixRequest
import com.pocketrealm.client.X86DirectWineRuntime
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * O14 increment-2 instrumentation: Android IME committed text reaches the Win32
 * probe through the [InputContract]'s generation-gated `imeCommit` path.
 *
 * Lane: AVD-Large-x86_64-v1 (physical AVD O11-Large-x86_64, emulator-5556).
 *
 * The test embeds [ClientDisplayHost.imeView] (the IME-capable wrapper) and
 * commits the fixed public test phrase through the host's IME path, then verifies
 * the Win32 probe observed the expected characters via `WM_CHAR`. It also proves:
 * held-input release on IME open, stale-generation rejection, and clean shutdown.
 */
@RunWith(AndroidJUnit4::class)
class O14ImeTest {
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

    @Test fun imeCommittedTextReachesWin32Probe() = runBlocking {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        val client = ClientManifest(ClientRuntimeContract.SELF_TEST_ID)
        val caps = runtime.probe(DeviceCaps(Build.SUPPORTED_ABIS.first(), Build.VERSION.SDK_INT, pageSize), client)
        assertTrue("runtime unsupported: ${caps.reason}", caps.supported)
        val prefix = withTimeout(180_000) { runtime.preparePrefix(PrefixRequest(client)) }
        assertTrue(prefix.ok)

        val mapped = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            host = ClientDisplayHost(context, prefix.runtimeRoot) { mapped.set(true) }
            // Embed the XServerView for rendering (the verified path).
            activity.addContentView(
                host!!.view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 720),
            )
            // Add the IME target overlay (zero-size, focusable for soft keyboard).
            activity.addContentView(
                host!!.imeView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            host!!.onResume()
        }
        withTimeout(15_000) { host!!.awaitRendererReady(15_000) }

        val session = runtime.launch(LaunchRequest(prefix.prefixId))
        withTimeout(60_000) {
            host!!.let { h ->
                while (!(mapped.get() && h.xServer.windowManager.mappedClientWindows.isNotEmpty())) delay(100)
            }
        }
        runtime.reportWindowVisible(session.sessionId)
        delay(1_000)

        // ---- 1. Baseline keyboard/pointer still works (regression) ----------
        injectKeyboardAndPointer(host!!)
        delay(300)

        // ---- 2. IME commit the fixed test phrase ----------------------------
        host!!.imeCommit(ImeCharMap.TEST_PHRASE)
        delay(500)

        // ---- 3. IME Backspace (delete) --------------------------------------
        host!!.inputContract.imeDelete(3, host!!.generation)
        delay(300)

        // ---- 4. IME open releases held input --------------------------------
        instrumentation.runOnMainSync {
            host!!.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            host!!.dispatchRightButton(pressed = true)
        }
        delay(200)
        val releaseReport = host!!.inputContract.imeOpened(host!!.generation)
        assertTrue("IME open should release held key", releaseReport.keyCount >= 1)
        assertTrue("IME open should release held button", releaseReport.buttonCount >= 1)
        delay(300)

        // ---- 5. Commit while IME is open ------------------------------------
        host!!.imeCommit("Hi")
        delay(300)

        // ---- 6. Close IME; verify neutral state -----------------------------
        host!!.inputContract.imeClosed(host!!.generation)
        assertFalse("IME should be inactive", host!!.inputContract.isImeActive)
        delay(200)

        // ---- 7. Stale-generation IME commit rejected ------------------------
        val staleGen = host!!.generation + 999L
        val staleBefore = host!!.inputDiagnostics().rejectedStaleEventCount
        host!!.inputContract.imeCommit("stale", staleGen)
        delay(200)
        val staleAfter = host!!.inputDiagnostics().rejectedStaleEventCount
        assertTrue("stale IME commit must be rejected", staleAfter > staleBefore)

        // ---- Close and collect diagnostics ----------------------------------
        val close = runtime.requestClose(session.sessionId)
        assertTrue(close.requested)
        val terminal = withTimeout(30_000) { runtime.observe(session.sessionId).last() }
        assertEquals(ClientState.EXITED, terminal.state)
        val d = runtime.collectDiagnostics(session.sessionId)
        assertTrue("cleanExit", d.cleanExit)
        assertTrue("focusSeen", d.focusSeen)
        assertTrue("audioOff", d.audioOff)

        // Regression assertions.
        assertTrue("keyboardSeen", d.keyboardSeen)
        assertTrue("mouseSeen", d.mouseSeen)

        // O14 increment-2 assertion: WM_CHAR observed with the expected count.
        // The test phrase + "Hi" = 20 + 2 = 22 characters committed.
        // Backspace does not produce WM_CHAR (it's WM_KEYDOWN KEYCODE_DEL).
        assertTrue(
            "WM_CHAR should be observed (charSeen)\n${d.stdoutTail.takeLast(500)}",
            d.charSeen,
        )
        assertTrue(
            "charCount should be >= 22 (test phrase 20 + 'Hi' 2), got ${d.charCount}\n${d.stdoutTail.takeLast(500)}",
            d.charCount >= 22,
        )
        assertFalse("forced", d.forced)

        // Evidence.
        val outDir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(outDir, "O14_IME_EVIDENCE.json").writeText(
            """{"schema":1,"test":"O14ImeTest","charSeen":${d.charSeen},"charCount":${d.charCount},"keyboardSeen":${d.keyboardSeen},"mouseSeen":${d.mouseSeen},"cleanExit":${d.cleanExit},"releaseKeyCount":${releaseReport.keyCount},"releaseButtonCount":${releaseReport.buttonCount},"staleRejected":${staleAfter - staleBefore},"phrase":"${ImeCharMap.TEST_PHRASE}"}""".trimIndent(),
        )
        android.util.Log.i("O14ImeAcceptance", "O14_IME_ACCEPTANCE charSeen=${d.charSeen} charCount=${d.charCount}")
        Unit
    }

    private suspend fun injectKeyboardAndPointer(host: ClientDisplayHost) {
        instrumentation.runOnMainSync {
            host.view.requestFocus()
            val window = host.xServer.windowManager.mappedClientWindows.first()
            val t = host.view.renderer.viewTransformation
            val cx = window.x + window.width / 2f
            val cy = window.y + window.height / 2f
            val vx = t.viewOffsetX + cx * t.aspect
            val vy = t.viewOffsetY + cy * t.aspect
            val now = SystemClock.uptimeMillis()
            host.dispatchPointer(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, vx, vy, 0))
            host.dispatchPointer(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, vx, vy, 0))
        }
        delay(200)
        instrumentation.runOnMainSync {
            host.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        }
        delay(150)
        instrumentation.runOnMainSync {
            host.dispatchKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
        }
    }
}
