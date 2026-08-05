package com.pocketrealm.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** UI-process proxy for the versioned :client AIDL protocol. */
class X86DirectWineRuntime(context: Context) : ClientRuntime, AutoCloseable {
    private val appContext = context.applicationContext
    @Volatile private var remote: IClientRuntimeControl? = null
    @Volatile private var connection: ServiceConnection? = null

    private suspend fun control(): IClientRuntimeControl {
        remote?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val value = IClientRuntimeControl.Stub.asInterface(binder)
                    remote = value
                    if (continuation.isActive) continuation.resume(value)
                }
                override fun onServiceDisconnected(name: ComponentName?) { remote = null }
                override fun onBindingDied(name: ComponentName?) { remote = null }
            }
            connection = c
            if (!appContext.bindService(Intent(appContext, ClientRuntimeService::class.java), c, Context.BIND_AUTO_CREATE)) {
                connection = null
                continuation.resumeWithException(IllegalStateException("ClientRuntimeService bind failed"))
            }
            continuation.invokeOnCancellation {
                runCatching { appContext.unbindService(c) }
                if (connection === c) connection = null
            }
        }
    }

    override suspend fun probe(device: DeviceCaps, client: ClientManifest): ClientCaps = transact {
        val response = json(it.probe(JSONObject().put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("abi", device.abi).put("api", device.api).put("pageSize", device.pageSize)
            .put("clientId", client.id).toString()))
        ClientCaps(response.getBoolean("supported"), response.getString("runtimeBuildId"),
            response.getString("reason"), response.getBoolean("immutableCode"))
    }

    override suspend fun preparePrefix(request: PrefixRequest): PrefixResult = transact {
        val response = json(it.preparePrefix(JSONObject().put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("clientId", request.client.id).put("renderer", request.renderer)
            .put("audioMode", request.audioMode).toString()))
        PrefixResult(true, response.getString("prefixId"), response.getString("runtimeRoot"),
            response.getString("prefixPath"), response.getString("detail"))
    }

    override suspend fun launch(request: LaunchRequest): ClientSession = transact {
        val response = json(it.launch(JSONObject().put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("prefixId", request.prefixId).put("display", request.display)
            .put("audioMode", request.audioMode).toString()))
        ClientSession(UUID.fromString(response.getString("sessionId")), ClientState.valueOf(response.getString("state")))
    }

    override suspend fun requestClose(sessionId: UUID): CloseResult = transact {
        val response = json(it.requestClose(sessionId.toString()))
        CloseResult(response.getBoolean("requested"), ClientState.valueOf(response.getString("state")),
            response.optString("detail"))
    }

    override suspend fun forceStop(sessionId: UUID) = transact<Unit> {
        json(it.forceStop(sessionId.toString())); Unit
    }

    override fun observe(sessionId: UUID): Flow<ClientEvent> = flow {
        var sequence = -1L
        while (true) {
            val response = transact { json(it.status(sessionId.toString())) }
            val state = ClientState.valueOf(response.getString("state"))
            val next = response.getLong("sequence")
            if (next != sequence) {
                sequence = next
                emit(ClientEvent(next, state, response.optString("detail")))
            }
            if (state in setOf(ClientState.EXITED, ClientState.FORCE_STOPPED, ClientState.FAILED)) break
            delay(200)
        }
    }

    override suspend fun collectDiagnostics(sessionId: UUID): ClientDiagnostics = try {
        transact { decodeDiagnostics(json(it.collectDiagnostics(sessionId.toString()))) }
    } catch (failure: Throwable) {
        val saved = File(appContext.noBackupFilesDir, "wine/last-session.json")
        if (!saved.isFile) throw failure
        val value = JSONObject(saved.readText())
        check(value.optString("sessionId") == sessionId.toString()) { "stale diagnostics record" }
        decodeDiagnostics(value)
    }

    suspend fun reportWindowVisible(sessionId: UUID) = transact<Unit> {
        json(it.reportWindowVisible(sessionId.toString())); Unit
    }

    private fun decodeDiagnostics(v: JSONObject) = ClientDiagnostics(
        UUID.fromString(v.getString("sessionId")), ClientState.valueOf(v.getString("state")),
        v.optBoolean("cleanExit"), v.optBoolean("forced"), v.optBoolean("windowVisible"),
        v.optBoolean("focusSeen"), v.optBoolean("audioOff"), v.optBoolean("keyboardSeen"), v.optBoolean("mouseSeen"),
        v.optBoolean("rightButtonSeen"), v.optBoolean("middleButtonSeen"),
        v.optBoolean("wheelSeen"), v.optBoolean("relativeMotionSeen"),
        v.optString("stdoutTail"), v.optString("stderrTail"), v.optString("detail"),
    )

    private fun json(value: String): JSONObject {
        val result = JSONObject(value)
        check(result.optBoolean("ok")) { result.optString("error", "client runtime request failed") }
        return result
    }

    private suspend fun <T> transact(block: (IClientRuntimeControl) -> T): T =
        withContext(Dispatchers.IO) { block(control()) }

    override fun close() {
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null; remote = null
    }
}
