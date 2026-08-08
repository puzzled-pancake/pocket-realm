package com.pocketrealm.o14

import android.content.Intent
import android.graphics.Bitmap
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * O14 input-across-generations regression: proves keyboard, absolute pointer,
 * and left-click delivery, held-input release, and stale-generation rejection
 * across two genuinely fresh [ClientDisplayHost] / [InputContract] lifecycles.
 *
 * This closes the test-infrastructure gap documented at commit f9e50a4: no
 * existing checked-in test combined multi-generation host replacement with
 * input delivery + stale rejection. It uses only public APIs present at
 * 40bdcb3 and changes no production code.
 *
 * Each generation launches a **separate** Wine self-test process, so the probe
 * stdout is naturally per-session — generation-N observations cannot satisfy
 * generation-N+1 assertions because they come from different sessionIds with
 * independent captured stdout.
 *
 * Lane: AVD-Large-x86_64-v1 (physical AVD O11-Large-x86_64, emulator-5556).
 */
@RunWith(AndroidJUnit4::class)
class O14InputContractRelaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var runtime: X86DirectWineRuntime

    @Before fun setUp() {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        runtime = X86DirectWineRuntime(context)
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { activity.finish() }
        runtime.close()
    }

    @Test fun inputSurvivesDisplayGenerationReplacement() = runBlocking {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        val client = ClientManifest(ClientRuntimeContract.SELF_TEST_ID)
        val caps = runtime.probe(DeviceCaps(Build.SUPPORTED_ABIS.first(), Build.VERSION.SDK_INT, pageSize), client)
        assertTrue("runtime unsupported: ${caps.reason}", caps.supported)
        val prefix = withTimeout(180_000) { runtime.preparePrefix(PrefixRequest(client)) }
        assertTrue(prefix.ok)

        // -------- Generation N ------------------------------------------------
        val genN = launchGeneration(prefix, label = "N")
        injectBaselineInput(genN.host, label = "N")
        // Hold one key + one button, then tear down through the normal lifecycle.
        instrumentation.runOnMainSync {
            genN.host.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_W))
            genN.host.dispatchRightButton(pressed = true)
        }
        delay(200)
        val releaseReportN = genN.host.inputContract.releaseAll(InputContract.ReleaseReason.ON_PAUSE)
        genN.host.view.onPause()
        assertTrue("genN teardown must release the held key", releaseReportN.keyCount >= 1)
        assertTrue("genN teardown must release the held button", releaseReportN.buttonCount >= 1)
        assertEquals(
            "genN teardown release reason",
            InputContract.ReleaseReason.ON_PAUSE,
            releaseReportN.reason,
        )

        val generationN = genN.host.generation
        val diagN = closeAndCollect(genN, label = "N")
        assertTrue("genN keyboardSeen\n${diagN.stdoutTail}", diagN.keyboardSeen)
        assertTrue("genN mouseSeen\n${diagN.stdoutTail}", diagN.mouseSeen)
        assertTrue("genN focusSeen\n${diagN.stdoutTail}", diagN.focusSeen)
        assertTrue("genN cleanExit\n${diagN.stdoutTail}", diagN.cleanExit)

        // Retain only the generation id as a safe stale token — no live host.
        val staleGenerationToken = generationN

        // -------- Generation N+1 ---------------------------------------------
        val genN1 = launchGeneration(prefix, label = "N+1")
        val generationN1 = genN1.host.generation
        assertNotEquals("generations must differ", generationN, generationN1)
        injectBaselineInput(genN1.host, label = "N+1")

        // -------- Stale-event rejection (while gen N+1 client is LIVE) --------
        val staleCountBeforeReject = genN1.host.inputDiagnostics().rejectedStaleEventCount
        genN1.host.inputContract.pointerButton(
            src = -1,
            button = InputContract.PointerButton.RIGHT,
            pressed = true,
            generation = staleGenerationToken, // generation N, not N+1
        )
        delay(200)
        val staleCountAfterReject = genN1.host.inputDiagnostics().rejectedStaleEventCount
        assertEquals(
            "stale generation-N event must increment rejectedStaleEventCount by exactly 1",
            staleCountBeforeReject + 1,
            staleCountAfterReject,
        )

        // Now close gen N+1 and collect probe output. The stale right-button
        // must NOT appear in generation N+1's probe output.
        val finalRelease = genN1.host.inputContract.releaseAll(InputContract.ReleaseReason.CLOSE)
        assertEquals("final release must have zero held keys", 0, finalRelease.keyCount)
        assertEquals("final release must have zero held buttons", 0, finalRelease.buttonCount)
        val diagN1 = closeAndCollect(genN1, label = "N+1")
        assertTrue("genN+1 keyboardSeen\n${diagN1.stdoutTail}", diagN1.keyboardSeen)
        assertTrue("genN+1 mouseSeen\n${diagN1.stdoutTail}", diagN1.mouseSeen)
        assertTrue("genN+1 focusSeen\n${diagN1.stdoutTail}", diagN1.focusSeen)
        assertTrue("genN+1 cleanExit\n${diagN1.stdoutTail}", diagN1.cleanExit)
        assertFalse(
            "stale gen-N right-button must not reach the gen-N+1 Win32 probe\n${diagN1.stdoutTail}",
            diagN1.stdoutTail.contains("btn=r"),
        )

        // -------- Final state -------------------------------------------------
        val procs = readClientProcessCount()
        if (procs >= 0) {
            assertEquals("no stale Pocket Realm client process must remain", 0, procs)
        }

        // -------- Evidence ----------------------------------------------------
        writeEvidence(
            genNDiag = diagN,
            genN1Diag = diagN1,
            generationN = generationN,
            generationN1 = generationN1,
            releaseReportN = releaseReportN,
            staleCountBeforeReject = staleCountBeforeReject,
            staleCountAfterReject = staleCountAfterReject,
            nonBlackN = genN.nonBlackPixels,
            nonBlackN1 = genN1.nonBlackPixels,
            finalCleanExit = diagN1.cleanExit,
            staleProcCount = procs,
        )
        Unit
    }

    /** Inject keyboard + absolute pointer + left-click on a live host. */
    private suspend fun injectBaselineInput(host: ClientDisplayHost, label: String) {
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
        delay(200)
    }

    /** Close the client session and collect probe diagnostics, then close the host. */
    private suspend fun closeAndCollect(gen: Gen, label: String): com.pocketrealm.client.ClientDiagnostics {
        val close = runtime.requestClose(gen.session.sessionId)
        assertTrue("gen $label close requested", close.requested)
        val terminal = withTimeout(30_000) { runtime.observe(gen.session.sessionId).last() }
        assertEquals("gen $label did not exit cleanly", ClientState.EXITED, terminal.state)
        val diag = runtime.collectDiagnostics(gen.session.sessionId)
        instrumentation.runOnMainSync { gen.host.close() }
        return diag
    }

    /**
     * One full display+client generation: create host, launch self-test, wait
     * for mapped non-black output. Returns the live host, session, and pixel
     * count. Caller is responsible for input, teardown, and closing the host.
     */
    private suspend fun launchGeneration(prefix: com.pocketrealm.client.PrefixResult, label: String): Gen {
        val mapped = AtomicBoolean(false)
        lateinit var host: ClientDisplayHost
        instrumentation.runOnMainSync {
            host = ClientDisplayHost(context, prefix.runtimeRoot) { mapped.set(true) }
            activity.addContentView(
                host.container,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 720),
            )
            host.onResume()
        }
        withTimeout(15_000) { host.awaitRendererReady(15_000) }

        val session = runtime.launch(LaunchRequest(prefix.prefixId))
        withTimeout(60_000) {
            while (!(mapped.get() && host.xServer.windowManager.mappedClientWindows.isNotEmpty())) delay(100)
        }
        runtime.reportWindowVisible(session.sessionId)
        delay(1_000) // MapNotify precedes final event-mask/focus setup.

        // Non-black proof: capture the rendered surface and count non-black pixels.
        val nonBlack = countNonBlackPixels(host)
        assertTrue("gen $label renderer remained black ($nonBlack non-black pixels)", nonBlack > 1000)
        return Gen(host, session, nonBlack)
    }

    /** Count non-black pixels from the host's rendered surface via glReadPixels. */
    private fun countNonBlackPixels(host: ClientDisplayHost): Int {
        val width = host.view.width
        val height = host.view.height
        if (width <= 0 || height <= 0) return 0
        val done = java.util.concurrent.CountDownLatch(1)
        val result = AtomicReference<Int>(0)
        host.view.queueEvent {
            try {
                host.view.renderer.onDrawFrame(null)
                val pixels = host.view.renderer.getPixelsARGB(0, 0, width, height, true)
                val nonBlack = pixels.count { (it and 0x00ffffff) != 0 }
                result.set(nonBlack)
            } finally {
                done.countDown()
            }
        }
        host.view.requestRender()
        assertTrue("gen pixel capture timed out", done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        return result.get() ?: 0
    }

    /**
     * Count Wine/Pocket Realm client processes still alive on the device. The
     * instrumentation test runs in the app process on the device, so
     * `Runtime.exec` runs device-side. Returns the count of /proc entries whose
     * cmdline contains the Wine/selftest identifiers, scoped to this app's uid.
     */
    private fun readClientProcessCount(): Int {
        return try {
            // Walk /proc and count processes whose cmdline mentions the Wine
            // selftest identifiers. No root: we can read /proc/<pid>/cmdline for
            // processes we own (same uid). This is more portable than `ps` flags.
            val myUid = android.os.Process.myUid()
            var count = 0
            File("/proc").listFiles()?.forEach { procDir ->
                val pid = procDir.name.toIntOrNull() ?: return@forEach
                try {
                    val status = File(procDir, "status").readText()
                    val uidLine = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
                    val procUid = uidLine?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
                    if (procUid == myUid) {
                        val cmdline = File(procDir, "cmdline").readText().replace('\u0000', ' ').trim()
                        if (cmdline.contains("pocket_selftest") || cmdline.contains("wine")) {
                            count++
                        }
                    }
                } catch (e: Exception) { /* permission or parse — skip */ }
            }
            count
        } catch (e: Exception) {
            -1 // unknown; rely on EXITED session state
        }
    }

    private fun writeEvidence(
        genNDiag: com.pocketrealm.client.ClientDiagnostics,
        genN1Diag: com.pocketrealm.client.ClientDiagnostics,
        generationN: Long,
        generationN1: Long,
        releaseReportN: InputContract.ReleaseReport,
        staleCountBeforeReject: Long,
        staleCountAfterReject: Long,
        nonBlackN: Int,
        nonBlackN1: Int,
        finalCleanExit: Boolean,
        staleProcCount: Int,
    ) {
        val outDir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        val evidence = JSONObject()
            .put("schema", 1)
            .put("test", "O14InputContractRelaunchTest")
            .put("commit", "see checked-in evidence")
            .put("startedUtc", java.time.Instant.now().toString())
            .put("generationN", generationN)
            .put("generationN1", generationN1)
            .put("generationsDiffer", generationN != generationN1)
            .put("nonBlackPixelsN", nonBlackN)
            .put("nonBlackPixelsN1", nonBlackN1)
            .put("genN_keyboardSeen", genNDiag.keyboardSeen)
            .put("genN_mouseSeen", genNDiag.mouseSeen)
            .put("genN_focusSeen", genNDiag.focusSeen)
            .put("genN_cleanExit", genNDiag.cleanExit)
            .put("genN1_keyboardSeen", genN1Diag.keyboardSeen)
            .put("genN1_mouseSeen", genN1Diag.mouseSeen)
            .put("genN1_focusSeen", genN1Diag.focusSeen)
            .put("genN1_cleanExit", genN1Diag.cleanExit)
            .put("genN1_rightButtonSeen", genN1Diag.rightButtonSeen)
            .put("genN1_stdoutTailLast200", genN1Diag.stdoutTail.takeLast(200))
            .put("releaseReportN_reason", releaseReportN.reason.name)
            .put("releaseReportN_keyCount", releaseReportN.keyCount)
            .put("releaseReportN_buttonCount", releaseReportN.buttonCount)
            .put("staleCountBeforeReject", staleCountBeforeReject)
            .put("staleCountAfterReject", staleCountAfterReject)
            .put("staleRejectedDelta", staleCountAfterReject - staleCountBeforeReject)
            .put("finalCleanExit", finalCleanExit)
            .put("staleProcCount", staleProcCount)
        File(outDir, "O14_RELAUNCH_EVIDENCE.json").writeText(evidence.toString(2))
    }

    private data class Gen(
        val host: ClientDisplayHost,
        val session: com.pocketrealm.client.ClientSession,
        val nonBlackPixels: Int,
    )
}
