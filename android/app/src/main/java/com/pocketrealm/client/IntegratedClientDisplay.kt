package com.pocketrealm.client

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.ComponentOwnership
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** UI-process state used by Compose to attach the supervisor-owned X surface. */
object IntegratedClientDisplay {
    private val mutableHost = MutableStateFlow<ClientDisplayHost?>(null)
    val host: StateFlow<ClientDisplayHost?> = mutableHost.asStateFlow()
    internal fun publish(value: ClientDisplayHost?) { mutableHost.value = value }

    /** Resolve only the currently published host; stale activity intents fail closed. */
    fun currentHost(generation: Long): ClientDisplayHost? =
        mutableHost.value?.takeIf { it.generation == generation }
}

/**
 * The X transport and SurfaceView must live in the application/UI process, not
 * in :supervisor or :client. This non-exported service gives the supervisor a
 * fixed token-scoped lifecycle while Compose attaches the in-process view.
 */
class ClientDisplayService : Service() {
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ownership: ComponentOwnership
    private var runtime: X86DirectWineRuntime? = null
    private var host: ClientDisplayHost? = null
    private var sessionId: UUID? = null
    private var pendingWindow = false

    override fun onCreate() {
        super.onCreate()
        ownership = ComponentOwnership("client-display") {
            synchronized(stateLock) { releaseInternal() }
            stopSelf()
        }
    }

    private val binder = object : IClientDisplayControl.Stub() {
        override fun claim(sessionId: String, instanceToken: String, ownerLease: IBinder): String =
            guarded { ownership.claim(sessionId, instanceToken, ownerLease) }

        override fun prepare(
            runtimeRoot: String,
            instanceToken: String,
            autoLoginUsername: String,
            autoLoginPassword: String,
            autoLoginTimingJson: String,
            audioMode: String,
            clientId: String,
            vulkanDriverId: String,
            rendererPackageId: String,
            displayProfileId: String,
            frameCap: Int,
        ): String = guarded {
            ownership.requireOwner(instanceToken)
            require(clientId == ClientRuntimeContract.WOW_5875_ID) { "unauthorized display client" }
            val root = File(runtimeRoot).canonicalFile
            val allowedRoots = listOf("wine", "arm-translated")
                .map { File(noBackupFilesDir, it).canonicalFile.toPath() }
            require(allowedRoots.any { root.toPath().startsWith(it) }) {
                "runtime root is outside app-owned Wine storage"
            }
            releaseInternal()
            runtime = X86DirectWineRuntime(applicationContext)
            val displaySelection = ClientDisplayCapabilities.requireSelection(
                applicationContext, displayProfileId, frameCap,
            )
            val arm = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
            val driverId = vulkanDriverId.takeIf(String::isNotEmpty)
            val rendererId = rendererPackageId.takeIf(String::isNotEmpty)
            val driver = if (arm) VulkanDriverCatalog.requireForRequest(driverId) else {
                require(driverId == null) { "x86 display does not accept an ARM Vulkan driver" }
                null
            }
            val rendererPackage = if (arm) RendererPackageCatalog.requireForRequest(
                ArmTranslationBackend.BOX64, "dxvk", rendererId,
            ) else {
                require(rendererId == null) { "x86 display does not accept an ARM renderer package" }
                null
            }
            if (driver != null && rendererPackage != null) {
                VulkanDriverCatalog.requireAvailableCompatiblePair(
                    driver.id,
                    rendererPackage,
                    Build.MODEL,
                    if (driver.kind == VulkanDriverKind.SYSTEM) {
                        AndroidSystemVulkanProbe.probe()
                    } else null,
                )
            }
            require(autoLoginUsername.isEmpty() == autoLoginPassword.isEmpty()) {
                "auto-login identity is incomplete"
            }
            val autoLoginCredentials = autoLoginUsername.takeIf { it.isNotEmpty() }?.let {
                require(it.length in 1..16 && it.all { c -> c.isLetterOrDigit() && c.code < 128 }) {
                    "auto-login username is invalid"
                }
                require(autoLoginPassword.length in 1..16 &&
                    autoLoginPassword.all { c -> c.isLetterOrDigit() && c.code < 128 }) {
                    "auto-login password is invalid"
                }
                SinglePlayerAutoLoginCredentials(it, autoLoginPassword)
            }
            val timings = Settings.AutoLoginTimings.fromControlJson(autoLoginTimingJson)
            require(audioMode == "off" || audioMode == "on") {
                "display audio mode is invalid"
            }
            val display = ClientDisplayHost(
                context = applicationContext,
                runtimeRoot = root.absolutePath,
                onWindowVisible = {
                    val id = sessionId
                    if (id == null) pendingWindow = true
                    else scope.launch { reportVisible(id) }
                },
                displayProfile = displaySelection.profile,
                frameCap = displaySelection.frameCap.fps,
                vulkanDriverId = driverId,
                rendererPackageId = rendererId,
                autoLoginCredentials = autoLoginCredentials,
                timings = timings,
                audioEnabled = audioMode == "on",
            )
            host = display
            IntegratedClientDisplay.publish(display)
            val rendererReady = try {
                display.awaitRendererReady(RENDERER_READY_TIMEOUT_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!rendererReady) {
                releaseInternal()
                error("DISPLAY_SURFACE_NOT_READY: Android EGL share context was not published")
            }
            AppLog.i(TAG, "renderer EGL share context ready before Wine launch")
            JSONObject().put("ok", true).put("display", ":0")
                .put("displayProfile", display.displayProfile.id)
                .put("virtualWidth", display.displayProfile.virtualWidth)
                .put("virtualHeight", display.displayProfile.virtualHeight)
                .put("frameCap", display.frameCap)
                .put("vulkanDriverId", driverId ?: JSONObject.NULL)
                .put("rendererPackageId", rendererId ?: JSONObject.NULL)
                .put("vulkanBridgeReady", display.vulkanBridgeReady)
                .put("audioMode", audioMode)
                .put("transportReady", true).put("rendererReady", true)
        }

        override fun attachSession(instanceToken: String, value: String): String = guarded {
            requireOwner(instanceToken)
            val id = UUID.fromString(value)
            sessionId = id
            if (pendingWindow || host?.windowVisible == true) scope.launch { reportVisible(id) }
            JSONObject().put("ok", true).put("sessionId", id.toString())
                .put("windowVisible", host?.windowVisible == true)
        }

        override fun status(): String = guarded {
            ownership.decorate(JSONObject().put("ok", true).put("prepared", host != null)
                .put("windowVisible", host?.windowVisible == true)
                .put("rendererReady", host?.rendererReady == true)
                .put("rendererSurfaceGeneration", host?.rendererSurfaceGeneration ?: 0L)
                .put("displayProfile", host?.displayProfile?.id ?: "")
                .put("virtualWidth", host?.displayProfile?.virtualWidth ?: 0)
                .put("virtualHeight", host?.displayProfile?.virtualHeight ?: 0)
                .put("frameCap", host?.frameCap ?: 0)
                .put("vulkanDriverId", host?.vulkanDriverId ?: "")
                .put("rendererPackageId", host?.rendererPackageId ?: "")
                .put("vulkanBridgeReady", host?.vulkanBridgeReady == true)
                .put("presentationFrameRateHint", host?.presentationFrameRateHint ?: 0f))
        }

        override fun requestClose(instanceToken: String): String = guarded {
            requireOwner(instanceToken)
            check(host != null) { "client display is not prepared" }
            host!!.requestClose()
            JSONObject().put("ok", true).put("requested", true)
        }

        override fun release(instanceToken: String): String = guarded {
            requireOwner(instanceToken)
            releaseInternal()
            ownership.clear(instanceToken)
            JSONObject().put("ok", true).put("released", true)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        synchronized(stateLock) { releaseInternal() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun reportVisible(id: UUID) {
        runCatching { runtime?.reportWindowVisible(id) }
            .onFailure { AppLog.w(TAG, "window-ready handoff failed: ${it.javaClass.simpleName}") }
        pendingWindow = false
    }

    private fun requireOwner(value: String) {
        ownership.requireOwner(value)
    }

    private fun releaseInternal() {
        IntegratedClientDisplay.publish(null)
        host?.close()
        host = null
        runtime?.close()
        runtime = null
        sessionId = null
        pendingWindow = false
    }

    private inline fun guarded(block: () -> JSONObject): String = synchronized(stateLock) {
        try {
            block().toString()
        } catch (error: Throwable) {
            AppLog.e(TAG, "display control request failed", error)
            JSONObject().put("ok", false).put("errorClass", error.javaClass.simpleName)
                .put("error", (error.message ?: "display request failed").take(512)).toString()
        }
    }

    companion object {
        private const val TAG = "ClientDisplay"
        private const val RENDERER_READY_TIMEOUT_MS = 15_000L
    }
}
