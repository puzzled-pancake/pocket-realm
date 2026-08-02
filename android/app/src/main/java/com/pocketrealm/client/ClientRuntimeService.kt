package com.pocketrealm.client

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pocketrealm.log.AppLog
import com.pocketrealm.wine.WineSpikeNative
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Non-exported :client process. It owns Wine and every native child; the UI
 * process owns only the X server/surface and sends a versioned control protocol.
 */
class ClientRuntimeService : Service() {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: WineRuntimeStore
    private var prepared: WineRuntimeStore.Prepared? = null
    private var session: SessionRecord? = null

    private data class SessionRecord(
        val id: UUID,
        val prepared: WineRuntimeStore.Prepared,
        val closeFile: File,
        var state: ClientState = ClientState.STARTING,
        var sequence: Long = 1,
        var detail: String = "launch accepted",
        var windowVisible: Boolean = false,
        var forced: Boolean = false,
        var cleanExit: Boolean = false,
        var stdout: String = "",
        var stderr: String = "",
    )

    override fun onCreate() {
        super.onCreate()
        store = WineRuntimeStore(applicationContext)
        AppLog.i(TAG, "ClientRuntimeService started pid=${android.os.Process.myPid()}")
    }

    private val binder = object : IClientRuntimeControl.Stub() {
        override fun probe(requestJson: String): String = guarded(requestJson) {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            val supported = Build.SUPPORTED_ABIS.contains("x86_64") && Build.VERSION.SDK_INT >= 26 &&
                File(applicationInfo.nativeLibraryDir, "libwine_loader_preloader.so").isFile &&
                File(applicationInfo.nativeLibraryDir, "libwine_spike.so").isFile
            JSONObject()
                .put("ok", true).put("supported", supported)
                .put("runtimeBuildId", ClientRuntimeContract.RUNTIME_BUILD_ID)
                .put("immutableCode", true)
                .put("reason", if (supported) "x86_64 APK-managed Wine closure available" else "runtime/ABI unavailable")
                .put("requestedAbi", request.optString("abi"))
        }

        override fun preparePrefix(requestJson: String): String = guarded(requestJson) {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            checkNoActiveSession()
            val p = store.prepare(
                request.getString("clientId"), request.optString("renderer", "wined3d"),
                request.optString("audioMode", "off"),
            )
            synchronized(lock) { prepared = p }
            JSONObject().put("ok", true).put("prefixId", p.prefixId)
                .put("runtimeRoot", p.root.absolutePath).put("prefixPath", p.prefix.absolutePath)
                .put("detail", "prefix ready and manifest-compatible")
        }

        override fun launch(requestJson: String): String = guarded(requestJson) {
            val request = JSONObject(requestJson)
            requireProtocol(request)
            val p = synchronized(lock) {
                check(session == null || session!!.state in TERMINAL_STATES) { "a client session is already active" }
                checkNotNull(prepared) { "preparePrefix must succeed before launch" }
            }
            check(request.getString("prefixId") == p.prefixId) { "prefix identity mismatch" }
            check(request.optString("display", ":0") == ":0") { "only the app-private :0 display is authorized" }
            check(request.optString("audioMode", "off") == "off") { "O06 requires audio-off" }
            val socket = File(p.tmp, ".X11-unix/X0")
            check(socket.exists()) { "display surface/transport must exist before launch" }

            val id = UUID.randomUUID()
            val closeFile = File(p.root, "sessions/$id/close.request")
            closeFile.parentFile!!.mkdirs(); closeFile.delete()
            val record = SessionRecord(id, p, closeFile)
            synchronized(lock) { session = record; persist(record) }
            executor.execute { runSession(record) }
            JSONObject().put("ok", true).put("sessionId", id.toString()).put("state", record.state.name)
        }

        override fun requestClose(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                if (r.state !in TERMINAL_STATES) {
                    r.closeFile.parentFile!!.mkdirs()
                    r.closeFile.writeText(r.id.toString())
                    transition(r, ClientState.CLOSE_REQUESTED, "token-scoped WM_CLOSE requested")
                }
            }
            JSONObject().put("ok", true).put("requested", r.state == ClientState.CLOSE_REQUESTED)
                .put("state", r.state.name).put("detail", r.detail)
        }

        override fun forceStop(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                check(r.state !in TERMINAL_STATES) { "session is already terminal" }
            }
            val cancelled = WineSpikeNative.cancelActiveDirectNative()
            check(cancelled) { "active Wine process group was not found" }
            synchronized(lock) {
                r.forced = true
                transition(r, ClientState.FORCE_STOPPED, "session process group killed")
            }
            JSONObject().put("ok", true).put("cancelled", cancelled).put("state", r.state.name)
        }

        override fun status(sessionId: String): String = guarded(sessionId) {
            eventJson(requireSession(sessionId))
        }

        override fun collectDiagnostics(sessionId: String): String = guarded(sessionId) {
            diagnosticsJson(requireSession(sessionId))
        }

        override fun reportWindowVisible(sessionId: String): String = guarded(sessionId) {
            val r = requireSession(sessionId)
            synchronized(lock) {
                r.windowVisible = true
                if (r.state == ClientState.STARTING) transition(r, ClientState.RUNNING, "mapped client window visible")
            }
            eventJson(r)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { WineSpikeNative.cancelActiveDirectNative() }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSession(r: SessionRecord) {
        val windowsClosePath = "Z:" + r.closeFile.absolutePath.replace('/', '\\')
        val env = listOf(
            "LD_DEBUG=", "WINEDEBUG=-all", "POCKET_AUDIO_MODE=off",
            "POCKET_SELFTEST_INTERACTIVE=1", "POCKET_CLOSE_FILE=$windowsClosePath",
            "WINEDLLOVERRIDES=winealsa.drv=d,winepulse.drv=d",
        ).joinToString(";")
        val raw = try {
            WineSpikeNative.runWineDirectNative(
                applicationInfo.nativeLibraryDir, r.prepared.selfTest.absolutePath,
                r.prepared.prefix.absolutePath, ":0", "", env, 6 * 60 * 60 * 1000,
            )
        } catch (t: Throwable) {
            synchronized(lock) {
                r.stderr = "${t.javaClass.simpleName}: ${t.message}"
                if (!r.forced) transition(r, ClientState.FAILED, "native launcher threw")
            }
            return
        }
        val result = parseWineRunResult(raw)
        synchronized(lock) {
            r.stdout = result.stdout.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.stderr = result.stderr.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS)
            r.cleanExit = result.rc == 0 && result.exitedCleanly && result.exitCode == 0 &&
                !result.timedOut && r.stdout.contains("POCKET_SELFTEST_OK")
            if (!r.forced) {
                transition(
                    r,
                    if (r.cleanExit) ClientState.EXITED else ClientState.FAILED,
                    "rc=${result.rc} exit=${result.exitCode} timeout=${result.timedOut}",
                )
            } else persist(r)
        }
    }

    private fun transition(r: SessionRecord, state: ClientState, detail: String) {
        r.state = state; r.detail = detail; r.sequence++
        persist(r)
        AppLog.i(TAG, "session=${r.id} state=$state detail=$detail")
    }

    private fun checkNoActiveSession() = synchronized(lock) {
        check(session == null || session!!.state in TERMINAL_STATES) { "cannot prepare while a session is active" }
    }

    private fun requireProtocol(request: JSONObject) {
        check(request.optInt("protocol", -1) == ClientRuntimeContract.PROTOCOL_VERSION) {
            "unsupported ClientRuntime protocol"
        }
    }

    private fun requireSession(value: String): SessionRecord = synchronized(lock) {
        val id = UUID.fromString(value)
        checkNotNull(session?.takeIf { it.id == id }) { "unknown or stale session token" }
    }

    private fun eventJson(r: SessionRecord) = synchronized(lock) {
        JSONObject().put("ok", true).put("sequence", r.sequence).put("state", r.state.name)
            .put("detail", r.detail)
    }

    private fun diagnosticsJson(r: SessionRecord) = synchronized(lock) {
        JSONObject().put("ok", true).put("sessionId", r.id.toString()).put("state", r.state.name)
            .put("cleanExit", r.cleanExit).put("forced", r.forced).put("windowVisible", r.windowVisible)
            .put("focusSeen", r.stdout.contains("POCKET_SELFTEST_FOCUS gained"))
            .put("audioOff", r.stdout.contains("POCKET_SELFTEST_AUDIO skipped"))
            .put("keyboardSeen", r.stdout.contains("POCKET_SELFTEST_KEY "))
            .put("mouseSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE "))
            .put("stdoutTail", r.stdout).put("stderrTail", r.stderr).put("detail", r.detail)
    }

    private fun persist(r: SessionRecord) {
        val out = File(noBackupFilesDir, "wine/last-session.json")
        out.parentFile!!.mkdirs()
        val temp = File(out.parentFile, ".last-session.tmp")
        temp.writeText(diagnosticsJsonUnsafe(r).toString())
        if (!temp.renameTo(out)) { out.delete(); temp.renameTo(out) }
        trimSessionLogs(r.prepared.root)
    }

    private fun diagnosticsJsonUnsafe(r: SessionRecord) = JSONObject()
        .put("protocol", ClientRuntimeContract.PROTOCOL_VERSION).put("sessionId", r.id.toString())
        .put("state", r.state.name).put("sequence", r.sequence).put("detail", r.detail)
        .put("cleanExit", r.cleanExit).put("forced", r.forced).put("windowVisible", r.windowVisible)
        .put("focusSeen", r.stdout.contains("POCKET_SELFTEST_FOCUS gained"))
        .put("audioOff", r.stdout.contains("POCKET_SELFTEST_AUDIO skipped"))
        .put("keyboardSeen", r.stdout.contains("POCKET_SELFTEST_KEY "))
        .put("mouseSeen", r.stdout.contains("POCKET_SELFTEST_MOUSE "))
        .put("stdoutTail", r.stdout.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS))
        .put("stderrTail", r.stderr.takeLast(ClientRuntimeContract.MAX_DIAGNOSTIC_CHARS))

    private fun trimSessionLogs(root: File) {
        val dir = File(root, "sessions")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(8).forEach { it.deleteRecursively() }
    }

    private inline fun guarded(input: String, block: () -> JSONObject): String {
        if (input.toByteArray().size > ClientRuntimeContract.MAX_CONTROL_BYTES) {
            return JSONObject().put("ok", false).put("error", "control payload too large").toString()
        }
        return try { block().toString() }
        catch (t: Throwable) {
            AppLog.e(TAG, "control request failed", t)
            JSONObject().put("ok", false).put("error", "${t.javaClass.simpleName}: ${t.message}").toString()
        }
    }

    companion object {
        private const val TAG = "ClientRuntime"
        private val TERMINAL_STATES = setOf(ClientState.EXITED, ClientState.FORCE_STOPPED, ClientState.FAILED)
    }
}
