package com.pocketrealm.client

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.SinglePlayerCredentialStore
import com.pocketrealm.supervisor.ComponentOwnership
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
            singlePlayerAutoLogin: Boolean,
            clientId: String,
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
            val displayProfile =
                ClientDisplayProfile.forDevice(Build.SUPPORTED_ABIS.asList(), Build.MODEL)
            val autoLoginCredentials = if (singlePlayerAutoLogin) {
                val credentials = requireNotNull(
                    SinglePlayerCredentialStore(applicationContext).loadProvisioned(),
                ) { "single-player account is not provisioned" }
                SinglePlayerAutoLoginCredentials(credentials.username, credentials.password)
            } else null
            val display = ClientDisplayHost(
                context = applicationContext,
                runtimeRoot = root.absolutePath,
                onWindowVisible = {
                    val id = sessionId
                    if (id == null) pendingWindow = true
                    else scope.launch { reportVisible(id) }
                },
                displayProfile = displayProfile,
                autoLoginCredentials = autoLoginCredentials,
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
                .put("frameCap", display.displayProfile.initialFrameCap)
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
                .put("frameCap", host?.displayProfile?.initialFrameCap ?: 0))
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
