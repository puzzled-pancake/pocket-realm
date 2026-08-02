package com.pocketrealm.client

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Report section 20.3/X3: direct build-5875 login-window and relaunch proof. */
@RunWith(AndroidJUnit4::class)
class ClientBuild5875LoginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: MainActivity
    private lateinit var runtime: X86DirectWineRuntime
    private var host: ClientDisplayHost? = null
    private var session: UUID? = null

    @Before fun setUp() {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        runtime = X86DirectWineRuntime(context)
    }

    @After fun tearDown() {
        session?.let { runBlocking { runCatching { runtime.forceStop(it) } } }
        instrumentation.runOnMainSync { host?.close(); activity.finish() }
        runtime.close()
    }

    @Test fun loginWindowSurvivesCleanRelaunch() = runBlocking {
        val client = ClientManifest(ClientRuntimeContract.WOW_5875_ID)
        val caps = runtime.probe(
            DeviceCaps(
                Build.SUPPORTED_ABIS.first(), Build.VERSION.SDK_INT,
                Os.sysconf(OsConstants._SC_PAGESIZE).toInt(),
            ),
            client,
        )
        assertTrue(caps.reason, caps.supported)
        val prefix = withTimeout(240_000) { runtime.preparePrefix(PrefixRequest(client)) }
        assertTrue(prefix.ok)

        val first = launchAndAwait(prefix, "first")
        runtime.forceStop(first.first)
        session = null
        delay(1_000)
        instrumentation.runOnMainSync { host?.close(); host = null }

        val second = launchAndAwait(prefix, "relaunch")
        val config = File(context.noBackupFilesDir, "client/active/WTF/Config.wtf").readText()
        val realm = File(context.noBackupFilesDir, "client/active/realmlist.wtf").readText().trim()
        assertEquals("set realmlist 127.0.0.1", realm)
        assertTrue(config.contains("SET gxResolution \"1280x720\""))
        assertTrue(config.contains("SET maxFPS \"30\""))
        assertTrue(config.contains("SET Sound_EnableAllSound \"0\""))

        val evidence = JSONObject()
            .put("schema", 1)
            .put("clientId", ClientRuntimeContract.WOW_5875_ID)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("api", Build.VERSION.SDK_INT)
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("renderer", "wined3d")
            .put("safeModeResolutionCeiling", "1280x720")
            .put("effectiveResolution", wowResolution(second.second))
            .put("fpsCap", 30)
            .put("audio", "off")
            .put("realmEndpoint", "127.0.0.1")
            .put("firstWindows", JSONArray(first.second))
            .put("relaunchWindows", JSONArray(second.second))
            .put("firstNonBlackPixels", first.third)
            .put("relaunchNonBlackPixels", second.third)
            .put("firstScreenshot", "o07-login-first.png")
            .put("relaunchScreenshot", "o07-login-relaunch.png")
            .put("stableLoginWindow", true)
            .put("relaunchPassed", true)
        File(requireNotNull(context.getExternalFilesDir(null)), "o07-login-evidence.json")
            .writeText(evidence.toString(2))
        android.util.Log.i(
            "O07Acceptance",
            "O07_LOGIN_ACCEPTANCE client=1.12.1.5875 first=${first.second} relaunch=${second.second}",
        )
        runtime.forceStop(second.first)
        session = null
    }

    private suspend fun launchAndAwait(prefix: PrefixResult, label: String): Triple<UUID, List<String>, Int> {
        val mapped = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            host = ClientDisplayHost(context, prefix.runtimeRoot) { mapped.set(true) }
            activity.addContentView(
                host!!.view,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 720),
            )
            host!!.onResume()
        }
        waitUntil(10_000) { File(prefix.runtimeRoot, "tmp/.X11-unix/X0").exists() }
        val launched = runtime.launch(LaunchRequest(prefix.prefixId))
        session = launched.sessionId
        waitForMappedWindowOrFailure(launched.sessionId, mapped, 180_000)
        runtime.reportWindowVisible(launched.sessionId)
        // Login rendering must remain mapped rather than flashing a startup
        // window and dying. Realm connectivity is intentionally absent here.
        delay(15_000)
        val windows = host!!.xServer.windowManager.mappedClientWindows.map {
            "${it.name}|${it.className}|${it.width}x${it.height}|pid=${it.processId}"
        }
        assertTrue(windows.joinToString(), wowResolution(windows) != null)
        val screenshot = captureDisplay()
        val nonBlackPixels = screenshot.getPixelsCopy().count { (it and 0x00ffffff) != 0 }
        assertTrue("$label renderer remained black ($nonBlackPixels non-black pixels)", nonBlackPixels > screenshot.width * screenshot.height / 100)
        FileOutputStream(
            File(requireNotNull(context.getExternalFilesDir(null)), "o07-login-$label.png"),
        ).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Triple(launched.sessionId, windows, nonBlackPixels)
    }

    /** Read the XServerView framebuffer on its owning GLES thread. Android's
     * UiAutomation screenshot can omit a GLSurfaceView's separate surface and
     * therefore turn a rendered client into a misleading black rectangle. */
    private fun captureDisplay(): Bitmap {
        val width = host!!.view.width
        val height = host!!.view.height
        assertTrue("client display has no size", width > 0 && height > 0)
        val result = AtomicReference<Bitmap>()
        val done = CountDownLatch(1)
        host!!.view.queueEvent {
            try {
                val pixels = host!!.view.renderer.getPixelsARGB(0, 0, width, height, true)
                result.set(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888))
            } finally {
                done.countDown()
            }
        }
        host!!.view.requestRender()
        assertTrue("renderer capture timed out", done.await(10, TimeUnit.SECONDS))
        return requireNotNull(result.get()) { "renderer capture unavailable" }
    }

    private fun Bitmap.getPixelsCopy(): IntArray = IntArray(width * height).also {
        getPixels(it, 0, width, 0, 0, width, height)
    }

    /** O07 permits 1280x720 or lower. Build 5875 selects its supported
     * 800x600 mode on this fixed AVD even though the managed ceiling remains
     * 1280x720, so qualify the real direct-client window rather than its title. */
    private fun wowResolution(windows: List<String>): String? {
        val pattern = Regex("\\|wow\\.exe\\|(\\d+)x(\\d+)\\|", RegexOption.IGNORE_CASE)
        for (window in windows) {
            val match = pattern.find(window) ?: continue
            val width = match.groupValues[1].toInt()
            val height = match.groupValues[2].toInt()
            if (width in 640..1280 && height in 480..720) return "${width}x$height"
        }
        return null
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(200)
        }
    }

    private suspend fun waitForMappedWindowOrFailure(
        sessionId: UUID,
        mapped: AtomicBoolean,
        timeoutMs: Long,
    ) {
        withTimeout(timeoutMs) {
            while (true) {
                if (mapped.get() && host!!.xServer.windowManager.mappedClientWindows.any {
                        it.width >= 640 && it.height >= 480
                    }) return@withTimeout
                val diagnostics = runtime.collectDiagnostics(sessionId)
                if (diagnostics.state in setOf(
                        ClientState.EXITED,
                        ClientState.FORCE_STOPPED,
                        ClientState.FAILED,
                    )) {
                    throw AssertionError(
                        "client terminated before mapping a login window: " +
                            "${diagnostics.state} ${diagnostics.detail}\n" +
                            diagnostics.stderrTail.takeLast(8_192),
                    )
                }
                delay(200)
            }
        }
    }
}
