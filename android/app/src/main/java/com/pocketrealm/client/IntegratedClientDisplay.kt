package com.pocketrealm.client

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.pocketrealm.log.AppLog
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
}

/**
 * The X transport and SurfaceView must live in the application/UI process, not
 * in :supervisor or :client. This non-exported service gives the supervisor a
 * fixed token-scoped lifecycle while Compose attaches the in-process view.
 */
class ClientDisplayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ownerToken: String? = null
    private var runtime: X86DirectWineRuntime? = null
    private var host: ClientDisplayHost? = null
    private var sessionId: UUID? = null
    private var pendingWindow = false

    private val binder = object : IClientDisplayControl.Stub() {
        override fun prepare(runtimeRoot: String, instanceToken: String): String = guarded {
            require(TOKEN.matches(instanceToken)) { "invalid client generation token" }
            val root = File(runtimeRoot).canonicalFile
            val allowed = File(noBackupFilesDir, "wine").canonicalFile
            require(root.toPath().startsWith(allowed.toPath())) { "runtime root is outside app-owned Wine storage" }
            releaseInternal()
            ownerToken = instanceToken
            runtime = X86DirectWineRuntime(applicationContext)
            val display = ClientDisplayHost(applicationContext, root.absolutePath) {
                val id = sessionId
                if (id == null) pendingWindow = true
                else scope.launch { reportVisible(id) }
            }
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
            JSONObject().put("ok", true).put("prepared", host != null)
                .put("windowVisible", host?.windowVisible == true)
                .put("rendererReady", host?.rendererReady == true)
                .put("hasOwner", ownerToken != null)
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
            JSONObject().put("ok", true).put("released", true)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseInternal()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun reportVisible(id: UUID) {
        runCatching { runtime?.reportWindowVisible(id) }
            .onFailure { AppLog.w(TAG, "window-ready handoff failed: ${it.javaClass.simpleName}") }
        pendingWindow = false
    }

    private fun requireOwner(value: String) {
        check(ownerToken != null && ownerToken == value) { "client display ownership mismatch" }
    }

    private fun releaseInternal() {
        IntegratedClientDisplay.publish(null)
        host?.close()
        host = null
        runtime?.close()
        runtime = null
        sessionId = null
        pendingWindow = false
        ownerToken = null
    }

    private inline fun guarded(block: () -> JSONObject): String = try {
        block().toString()
    } catch (error: Throwable) {
        AppLog.e(TAG, "display control request failed", error)
        JSONObject().put("ok", false).put("errorClass", error.javaClass.simpleName)
            .put("error", (error.message ?: "display request failed").take(512)).toString()
    }

    companion object {
        private const val TAG = "ClientDisplay"
        private const val RENDERER_READY_TIMEOUT_MS = 15_000L
        private val TOKEN = Regex("[0-9a-f]{64}")
    }
}
