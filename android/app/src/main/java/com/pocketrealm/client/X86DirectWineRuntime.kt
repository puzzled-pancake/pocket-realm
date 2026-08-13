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
class X86DirectWineRuntime(
    context: Context,
    private val provider: ClientRuntimeProvider = ClientRuntimeProvider.X86_DIRECT_WINE,
    private val translator: ArmTranslationBackend = ArmTranslationBackend.BOX64,
) : ClientRuntime, AutoCloseable {
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
            .put("provider", provider.id)
            .put("translator", translator.id)
            .put("abi", device.abi).put("api", device.api).put("pageSize", device.pageSize)
            .put("clientId", client.id).toString()))
        ClientCaps(response.getBoolean("supported"), response.getString("runtimeBuildId"),
            response.getString("reason"), response.getBoolean("immutableCode"))
    }

    override suspend fun preparePrefix(request: PrefixRequest): PrefixResult = transact {
        if (provider != ClientRuntimeProvider.ARM_TRANSLATED_WINE) {
            require(request.vulkanDriverId == null) {
                "x86 direct Wine does not accept an ARM Vulkan driver"
            }
        }
        val rendererPackageId = requestRendererPackageId(
            request.renderer,
            request.rendererPackageId,
        )
        val payload = JSONObject().put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("clientId", request.client.id).put("renderer", request.renderer)
            .put("translator", translator.id)
            .put("audioMode", request.audioMode)
            .put("inputSafeMode", request.inputSafeMode)
            .put("realmEndpoint", request.realmEndpoint.address)
            .put("tweaks", request.tweaksJson)
        rendererPackageId?.let { payload.put("rendererPackageId", it) }
        request.vulkanDriverId?.let { payload.put("vulkanDriverId", it) }
        payload.put("displayProfileId", request.displayProfileId)
            .put("frameCap", request.frameCap)
        val response = json(it.preparePrefix(payload.toString()))
        PrefixResult(true, response.getString("prefixId"), response.getString("runtimeRoot"),
            response.getString("prefixPath"), response.getString("detail"))
    }

    override suspend fun launch(request: LaunchRequest): ClientSession = transact {
        if (provider != ClientRuntimeProvider.ARM_TRANSLATED_WINE) {
            require(request.vulkanDriverId == null) {
                "x86 direct Wine does not accept an ARM Vulkan driver"
            }
        }
        val rendererPackageId = requestRendererPackageId(
            request.renderer,
            request.rendererPackageId,
        )
        val payload = JSONObject().put("protocol", ClientRuntimeContract.PROTOCOL_VERSION)
            .put("prefixId", request.prefixId).put("display", request.display)
            .put("translator", translator.id)
            .put("audioMode", request.audioMode).put("renderer", request.renderer)
            .put("tweaks", request.tweaksJson)
        rendererPackageId?.let { payload.put("rendererPackageId", it) }
        request.vulkanDriverId?.let { payload.put("vulkanDriverId", it) }
        payload.put("displayProfileId", request.displayProfileId)
            .put("frameCap", request.frameCap)
        val response = json(it.launch(payload.toString()))
        ClientSession(UUID.fromString(response.getString("sessionId")), ClientState.valueOf(response.getString("state")))
    }

    private fun requestRendererPackageId(renderer: String, requestedId: String?): String? {
        if (provider != ClientRuntimeProvider.ARM_TRANSLATED_WINE) {
            require(requestedId == null) { "x86 direct Wine does not accept an ARM renderer package" }
            return null
        }
        return RendererPackageCatalog.requireForRequest(translator, renderer, requestedId)?.id
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
        v.optBoolean("charSeen"), v.optInt("charCount"),
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
