package com.pocketrealm.client

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    private lateinit var translator: ArmTranslationBackend
    private lateinit var displaySelection: ClientDisplaySelection
    private var rendererPackageId: String? = null
    private var vulkanDriverId: String? = null
    private var audioMode: String = "off"
    private var host: ClientDisplayHost? = null
    private var session: UUID? = null

    @Before fun setUp() {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        translator = requestedTranslator()
        displaySelection = requestedDisplaySelection()
        rendererPackageId = requestedDxvkPackage()
        vulkanDriverId = requestedVulkanDriver()
        audioMode = requestedAudioMode()
        runtime = X86DirectWineRuntime(
            context,
            if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
                ClientRuntimeProvider.ARM_TRANSLATED_WINE
            } else ClientRuntimeProvider.X86_DIRECT_WINE,
            translator,
        )
    }

    @After fun tearDown() {
        session?.let { runBlocking { runCatching { runtime.forceStop(it) } } }
        closeDisplay()
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
        val renderer = requestedRenderer()
        val prefixRequest = PrefixRequest(
            client,
            renderer = renderer,
            audioMode = audioMode,
            rendererPackageId = rendererPackageId,
            vulkanDriverId = vulkanDriverId,
            displayProfileId = displaySelection.profile.id,
            frameCap = displaySelection.frameCap.fps,
        )
        val prefix = withTimeout(240_000) { runtime.preparePrefix(prefixRequest) }
        assertTrue(prefix.ok)

        val first = launchAndAwait(prefix, "first", renderer, rendererPackageId)
        runtime.forceStop(first.first)
        session = null
        delay(1_000)
        closeDisplay()

        val relaunchPrefix = withTimeout(240_000) { runtime.preparePrefix(prefixRequest) }
        assertTrue(relaunchPrefix.ok)
        assertNotEquals("relaunch must use a newly prepared one-shot ticket", prefix.prefixId,
            relaunchPrefix.prefixId)
        val second = launchAndAwait(relaunchPrefix, "relaunch", renderer, rendererPackageId)
        val managedRoot = ManagedClientStore(context).load(ClientRuntimeContract.WOW_5875_ID).root
        val config = File(managedRoot, "WTF/Config.wtf").readText()
        val realm = File(managedRoot, "realmlist.wtf").readText().trim()
        val expectedProfile = displaySelection.profile
        assertEquals("set realmlist 127.0.0.1", realm)
        assertTrue(config.contains("SET gxApi \"d3d\""))
        assertTrue(config.contains("SET gxResolution \"${expectedProfile.resolution}\""))
        assertTrue(config.contains("SET gxWindow \"1\""))
        assertTrue(config.contains(
            "SET gxMaximize \"${if (expectedProfile.gameMaximized) 1 else 0}\"",
        ))
        assertTrue(config.contains("SET maxFPS \"${displaySelection.frameCap.fps}\""))
        // Vanilla's default 48 MiB script ceiling is too small for database
        // add-ons such as pfQuest. Zero is the client-supported unlimited
        // setting exposed by the character-select AddOns screen.
        assertTrue(config.contains("SET scriptMemory \"0\""))
        assertTrue(config.contains(
            "SET Sound_EnableAllSound \"${if (audioMode == "on") 1 else 0}\"",
        ))
        if (audioMode == "on") {
            assertTrue(config.contains("SET SoundMixRate \"48000\""))
            assertTrue(config.contains("SET SoundBufferSize \"100\""))
        } else {
            assertTrue(!config.contains("SET SoundMixRate"))
            assertTrue(!config.contains("SET SoundBufferSize"))
        }
        // This request uses the default all-off tweak set. Do not silently
        // spend CPU on the optional 64-channel executable/config tweak.
        assertTrue(!config.contains("SET SoundSoftwareChannels"))

        val evidence = JSONObject()
            .put("schema", 1)
            .put("clientId", ClientRuntimeContract.WOW_5875_ID)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("api", Build.VERSION.SDK_INT)
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("renderer", renderer)
            .put("translator", translator.id)
            .put("dxvkPackageId", rendererPackageId ?: JSONObject.NULL)
            .put("dxvkVersion", rendererPackageId?.let {
                RendererPackageCatalog.find(it)?.dxvkVersion
            } ?: JSONObject.NULL)
            .put("vulkanDriverId", vulkanDriverId ?: JSONObject.NULL)
            .put("displayProfile", expectedProfile.id)
            .put("safeModeResolutionCeiling", expectedProfile.resolution)
            .put("gameWindowed", true)
            .put("gameMaximized", expectedProfile.gameMaximized)
            .put("effectiveResolution", wowResolution(second.second))
            .put("fpsCap", displaySelection.frameCap.fps)
            .put("audio", audioMode)
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

    /** The original-client test uses the same fixed renderer as production. */
    private fun requestedRenderer(): String {
        val arm = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val default = if (arm) "dxvk" else "wined3d"
        val requested = InstrumentationRegistry.getArguments().getString("pocketRenderer") ?: default
        assertTrue("renderer override is not supported: $requested", requested == default)
        return requested
    }

    private fun requestedTranslator(): ArmTranslationBackend {
        val arm = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val requested = InstrumentationRegistry.getArguments().getString("pocketTranslator") ?: "box64"
        val translator = ArmTranslationBackend.parse(requested)
        if (!arm && translator != ArmTranslationBackend.BOX64) {
            throw AssertionError("${translator.id} is only selectable on the ARM translated client lane")
        }
        return translator
    }

    /** Instrumentation may select either installed DXVK package, never an arbitrary identity. */
    private fun requestedDxvkPackage(): String? {
        val requested = InstrumentationRegistry.getArguments().getString("pocketDxvkPackage")
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            require(requested == null) { "pocketDxvkPackage is only valid on the ARM DXVK lane" }
            return null
        }
        val selected = requested ?: RendererPackageCatalog.default(translator).id
        return requireNotNull(RendererPackageCatalog.requireForRequest(
            translator,
            "dxvk",
            selected,
        )).id
    }

    private fun requestedDisplaySelection(): ClientDisplaySelection {
        val args = InstrumentationRegistry.getArguments()
        val default = ClientDisplaySelection.defaultForDevice(
            Build.SUPPORTED_ABIS.asList(), Build.MODEL,
        )
        val profile = args.getString("pocketDisplayProfile")
            ?.let(ClientDisplayProfile::requireId) ?: default.profile
        val cap = args.getString("pocketFrameCap")?.toIntOrNull()
            ?.let(ClientFrameCap::requireFps) ?: default.frameCap
        return ClientDisplayCapabilities.requireSelection(
            context, profile.id, cap.fps,
        )
    }

    private fun requestedVulkanDriver(): String? {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return null
        val requested = InstrumentationRegistry.getArguments().getString("pocketVulkanDriver")
            ?: VulkanDriverCatalog.normalize(null, Build.MODEL)
        val driver = VulkanDriverCatalog.requireForRequest(requested)
        val renderer = requireNotNull(RendererPackageCatalog.find(rendererPackageId))
        return VulkanDriverCatalog.requireAvailableCompatiblePair(
            requested,
            renderer,
            Build.MODEL,
            if (driver.kind == VulkanDriverKind.SYSTEM) {
                AndroidSystemVulkanProbe.probe()
            } else null,
        ).first.id
    }

    private fun requestedAudioMode(): String {
        val requested = InstrumentationRegistry.getArguments().getString("pocketAudio") ?: "off"
        require(requested == "off" || requested == "on") { "unsupported audio test mode" }
        return if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") requested else "off"
    }

    private suspend fun launchAndAwait(
        prefix: PrefixResult,
        label: String,
        renderer: String,
        rendererPackageId: String?,
    ): Triple<UUID, List<String>, Int> {
        val mapped = AtomicBoolean(false)
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        }
        instrumentation.runOnMainSync {
            // Each relaunch gets a fresh host Activity.  Reusing a finishing
            // MainActivity leaves GLSurfaceView detached and destroys the
            // EGL share root before the second client can create GLX state.
            host = ClientDisplayHost(
                context = context,
                runtimeRoot = prefix.runtimeRoot,
                displayProfile = displaySelection.profile,
                frameCap = displaySelection.frameCap.fps,
                vulkanDriverId = vulkanDriverId,
                rendererPackageId = rendererPackageId,
                audioEnabled = audioMode == "on",
                onWindowVisible = { mapped.set(true) },
            )
            activity.addContentView(
                host!!.container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            host!!.onResume()
        }
        waitUntil(15_000) {
            host!!.view.isAttachedToWindow && host!!.view.width > 0 && host!!.view.height > 0 &&
                host!!.view.holder.surface.isValid
        }
        // Wait on the same Box64 transport directory selected by the display host.
        waitUntil(10_000) { File(host!!.transportRoot, ".X11-unix/X0").exists() }
        assertTrue("renderer EGL share context was not ready", host!!.awaitRendererReady(15_000))
        assertTrue("renderer surface generation was not published", host!!.rendererSurfaceGeneration > 0)
        val launchStartedWallMs = System.currentTimeMillis()
        val launched = runtime.launch(LaunchRequest(
            prefix.prefixId,
            audioMode = audioMode,
            renderer = renderer,
            rendererPackageId = rendererPackageId,
            vulkanDriverId = vulkanDriverId,
            displayProfileId = displaySelection.profile.id,
            frameCap = displaySelection.frameCap.fps,
        ))
        session = launched.sessionId
        waitForMappedWindowOrFailure(launched.sessionId, mapped, 180_000)
        runtime.reportWindowVisible(launched.sessionId)
        val ready = withTimeout(20_000) {
            runtime.observe(launched.sessionId).first {
                it.state == ClientState.RUNNING || it.state in setOf(
                    ClientState.EXITED,
                    ClientState.FORCE_STOPPED,
                    ClientState.FAILED,
                )
            }
        }
        assertEquals(
            "$label service did not attest the client as RUNNING: ${ready.detail}",
            ClientState.RUNNING,
            ready.state,
        )
        val runningDiagnostics = runtime.collectDiagnostics(launched.sessionId)
        assertEquals(
            "$label service diagnostics were not RUNNING: ${runningDiagnostics.detail}",
            ClientState.RUNNING,
            runningDiagnostics.state,
        )
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            val rendererPackage = requireNotNull(RendererPackageCatalog.find(rendererPackageId))
            val driver = requireNotNull(VulkanDriverCatalog.find(vulkanDriverId))
            assertEquals("the live lane changed the requested Vulkan identity",
                vulkanDriverId, driver.id)
            if (driver.kind == VulkanDriverKind.SYSTEM) {
                assertTrue("the hardened Vortek bridge was not ready", host!!.vulkanBridgeReady)
            }
            val log = File(prefix.runtimeRoot, "sessions/${launched.sessionId}/WoW_d3d9.log")
            assertTrue("$label DXVK attestation log is absent", log.isFile)
            assertTrue(
                "$label DXVK attestation log predates this one-shot launch",
                log.lastModified() >= launchStartedWallMs,
            )
            val proof = log.readText(Charsets.UTF_8)
            assertTrue(
                "$label log does not attest exact DXVK ${rendererPackage.dxvkVersion} and ${driver.id}",
                ClientRuntimeContract.isArmDxvkLogAttested(
                    proof,
                    rendererPackage.dxvkVersion,
                    driver,
                ),
            )
        }
        // Login rendering must remain mapped rather than flashing a startup
        // window and dying. Realm connectivity is intentionally absent here.
        delay(15_000)
        val stableDiagnostics = runtime.collectDiagnostics(launched.sessionId)
        assertEquals(
            "$label service left RUNNING before capture: ${stableDiagnostics.detail}",
            ClientState.RUNNING,
            stableDiagnostics.state,
        )
        val windows = host!!.xServer.windowManager.mappedClientWindows.map {
            "${it.name}|${it.className}|${it.width}x${it.height}|pid=${it.processId}"
        }
        assertTrue(windows.joinToString(), wowResolution(windows) != null)
        val screenshot = captureDisplay()
        val pixels = screenshot.getPixelsCopy()
        val nonBlackPixels = pixels.count { (it and 0x00ffffff) != 0 }
        assertTrue("$label renderer remained black ($nonBlackPixels non-black pixels)", nonBlackPixels > pixels.size / 100)
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

    /**
     * x86 retains its qualified 1280x720-or-lower topology. The measured RP6
     * ARM lane requires its exact single anonymous native-panel login surface.
     */
    private fun wowResolution(windows: List<String>): String? {
        // Branch on ABI before considering the legacy x86 matcher. Otherwise a
        // named 800x600 ARM helper can falsely satisfy the x86 acceptance path
        // while the required RP6 native-panel surface is absent.
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            val profile = displaySelection.profile
            val record = Regex("^.*\\|.*\\|(\\d+)x(\\d+)\\|pid=(-?\\d+)$")
            val dimensions = windows.mapNotNull { window ->
                val match = record.matchEntire(window) ?: return@mapNotNull null
                val pid = match.groupValues[3].toInt()
                // The Android-backed X root/desktop surface is reported as a
                // mapped 1920x1080 window with pid=0. It is not a client
                // topology member; evaluate only Wine-owned windows here.
                if (pid == 0) null
                else match.groupValues[1].toInt() to match.groupValues[2].toInt()
            }
            val targets = dimensions.filter {
                it.first == profile.virtualWidth && it.second == profile.virtualHeight
            }
            if (targets.size == 1 && dimensions.all { it == targets.single() ||
                    (it.first <= 16 && it.second <= 16) }) {
                return "${profile.virtualWidth}x${profile.virtualHeight}"
            }
            return null
        }

        val pattern = Regex("\\|wow\\.exe\\|(\\d+)x(\\d+)\\|", RegexOption.IGNORE_CASE)
        for (window in windows) {
            val match = pattern.find(window) ?: continue
            val width = match.groupValues[1].toInt()
            val height = match.groupValues[2].toInt()
            if (width == displaySelection.virtualWidth &&
                height == displaySelection.virtualHeight) return "${width}x$height"
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
        var hardwarePromptAnswered = false
        var nextWindowTrace = 0L
        withTimeout(timeoutMs) {
            while (true) {
                if (mapped.get() && host!!.xServer.windowManager.mappedClientWindows.any {
                        it.width >= 640 && it.height >= 480
                    }) return@withTimeout
                val hardwarePrompt = hardwareChangePrompt()
                if (!hardwarePromptAnswered && hardwarePrompt != null) {
                    pulseEnter()
                    hardwarePromptAnswered = true
                    android.util.Log.i(
                        "O07Acceptance",
                        "O07_HARDWARE_CHANGE_PROMPT answered=true action=default-enter window=$hardwarePrompt",
                    )
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now >= nextWindowTrace) {
                    val windows = host!!.xServer.windowManager.mappedClientWindows.joinToString {
                        "${it.name.take(32)}|${it.className.take(32)}|${it.width}x${it.height}|" +
                            "pid=${it.processId}|renderable=${it.isRenderable}"
                    }
                    android.util.Log.i("O07Windows", "mapped=[$windows]")
                    nextWindowTrace = now + 5_000
                }
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

    /** Build 5875 asks once when Wine's reported adapter fingerprint changes.
     * Acknowledge only that exact modal topology through the production input
     * bridge; the unchanged 800x600/non-black gates still decide acceptance. */
    private fun hardwareChangePrompt(): String? {
        val windows = host!!.xServer.windowManager.mappedClientWindows
        if (windows.any { it.width >= 640 && it.height >= 480 }) return null
        return windows.firstOrNull { it.width.toInt() == 265 && it.height.toInt() == 63 }?.let {
            "${it.name.take(64)}|${it.className.take(64)}|${it.width}x${it.height}|pid=${it.processId}"
        }
    }

    private fun pulseEnter() {
        instrumentation.runOnMainSync {
            val now = android.os.SystemClock.uptimeMillis()
            host!!.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        }
        Thread.sleep(80)
        instrumentation.runOnMainSync {
            val now = android.os.SystemClock.uptimeMillis()
            host!!.dispatchKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
        }
    }

    private fun closeDisplay() {
        instrumentation.runOnMainSync {
            host?.close()
            host = null
            activity.finish()
        }
    }
}
