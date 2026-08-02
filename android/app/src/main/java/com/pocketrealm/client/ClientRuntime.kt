package com.pocketrealm.client

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Report §15.3 runtime boundary. Proprietary client execution remains O07. */
interface ClientRuntime {
    suspend fun probe(device: DeviceCaps, client: ClientManifest): ClientCaps
    suspend fun preparePrefix(request: PrefixRequest): PrefixResult
    suspend fun launch(request: LaunchRequest): ClientSession
    suspend fun requestClose(sessionId: UUID): CloseResult
    suspend fun forceStop(sessionId: UUID)
    fun observe(sessionId: UUID): Flow<ClientEvent>
    suspend fun collectDiagnostics(sessionId: UUID): ClientDiagnostics
}

data class DeviceCaps(val abi: String, val api: Int, val pageSize: Int)
data class ClientManifest(val id: String, val windowsArch: String = "win32")
data class ClientCaps(
    val supported: Boolean,
    val runtimeBuildId: String,
    val reason: String,
    val immutableCode: Boolean,
)
data class PrefixRequest(
    val client: ClientManifest,
    val renderer: String = "wined3d",
    val audioMode: String = "off",
)
data class PrefixResult(
    val ok: Boolean,
    val prefixId: String,
    val runtimeRoot: String,
    val prefixPath: String,
    val detail: String,
)
data class LaunchRequest(
    val prefixId: String,
    val display: String = ":0",
    val audioMode: String = "off",
)
data class ClientSession(val sessionId: UUID, val state: ClientState)
data class CloseResult(val requested: Boolean, val state: ClientState, val detail: String)
enum class ClientState { PREPARING, READY, STARTING, RUNNING, CLOSE_REQUESTED, EXITED, FORCE_STOPPED, FAILED }
data class ClientEvent(val sequence: Long, val state: ClientState, val detail: String)
data class ClientDiagnostics(
    val sessionId: UUID,
    val state: ClientState,
    val cleanExit: Boolean,
    val forced: Boolean,
    val windowVisible: Boolean,
    val focusSeen: Boolean,
    val audioOff: Boolean,
    val keyboardSeen: Boolean,
    val mouseSeen: Boolean,
    val stdoutTail: String,
    val stderrTail: String,
    val detail: String,
)

object ClientRuntimeContract {
    const val PROTOCOL_VERSION = 1
    const val RUNTIME_BUILD_ID = "kron4ek-wine-11.14-amd64-wow64-vanilla"
    const val SELF_TEST_ID = "pocket-selftest-v1"
    const val PREFIX_SCHEMA = 1
    const val MAX_CONTROL_BYTES = 64 * 1024
    const val MAX_DIAGNOSTIC_CHARS = 64 * 1024
    // Wine 11.14's initialized WoW64 prefix is ~637 MiB because wineboot
    // installs both 64-bit system32 and 32-bit syswow64 builtins.
    const val PREFIX_QUOTA_BYTES = 768L * 1024L * 1024L
    const val PRESERVED_PREFIX_QUOTA_BYTES = 768L * 1024L * 1024L
    const val MAX_PRESERVED_PREFIXES = 1
    const val CACHE_QUOTA_BYTES = 768L * 1024L * 1024L
    const val LOG_QUOTA_BYTES = 4L * 1024L * 1024L
}
