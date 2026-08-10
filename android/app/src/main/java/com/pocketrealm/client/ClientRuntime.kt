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
    val inputSafeMode: Boolean = false,
    val rendererPackageId: String? = null,
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
    val renderer: String = "wined3d",
    val rendererPackageId: String? = null,
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
    val rightButtonSeen: Boolean = false,
    val middleButtonSeen: Boolean = false,
    val wheelSeen: Boolean = false,
    val relativeMotionSeen: Boolean = false,
    val charSeen: Boolean = false,
    val charCount: Int = 0,
    val stdoutTail: String,
    val stderrTail: String,
    val detail: String,
)

object ClientRuntimeContract {
    const val PROTOCOL_VERSION = 1
    const val RUNTIME_BUILD_ID = "kron4ek-wine-11.14-amd64-wow64-vanilla"
    const val ARM_TRANSLATED_RUNTIME_BUILD_ID = "winlator-ca3d735-box64-0.4.0-wine-10.10"
    const val ARM_FEX_RUNTIME_BUILD_ID = "winlator-bionic-v3.1.h-fexcore-2608-proton-9-arm64ec"

    fun armRuntimeBuildId(translator: ArmTranslationBackend): String = when (translator) {
        ArmTranslationBackend.BOX64 -> ARM_TRANSLATED_RUNTIME_BUILD_ID
        ArmTranslationBackend.FEX -> ARM_FEX_RUNTIME_BUILD_ID
    }
    const val RENDERER_BUILD_ID = "gladio-eaa2a8d-o12-gles30-v2"
    const val ARM_RENDERER_BUILD_ID = "turnip-26.1.0-dxvk-2.4.1-d3d9"
    const val ARM_OPENGL_RENDERER_BUILD_ID = "gladio-eaa2a8d-arm64-glibc-android-gles"
    const val ARM_FEX_DXVK_RENDERER_BUILD_ID = "turnip-26.2.0-dxvk-2.3.1-arm64ec"
    const val ARM_FEX_OPENGL_RENDERER_BUILD_ID =
        "gladio-eaa2a8d-arm64-bionic-378e5bb9-android-gles"

    fun armRendererBuildId(
        translator: ArmTranslationBackend,
        renderer: String,
        rendererPackageId: String? = null,
    ): String = when {
        renderer == "dxvk" -> RendererPackageCatalog.requireForRequest(
            translator, renderer, rendererPackageId,
        )!!.buildId
        translator == ArmTranslationBackend.BOX64 && renderer == "opengl" -> {
            require(rendererPackageId == null) { "OpenGL does not accept a DXVK package" }
            ARM_OPENGL_RENDERER_BUILD_ID
        }
        translator == ArmTranslationBackend.FEX && renderer == "opengl" -> {
            require(rendererPackageId == null) { "OpenGL does not accept a DXVK package" }
            ARM_FEX_OPENGL_RENDERER_BUILD_ID
        }
        else -> error("unsupported ARM renderer: $renderer")
    }

    /** Arguments passed after Wine. Client OpenGL is a WoW mode, not DXVK. */
    fun armClientArguments(executable: String, renderer: String): List<String> = when (renderer) {
        "dxvk" -> listOf(executable)
        "opengl" -> listOf(executable, "-opengl")
        else -> error("unsupported ARM renderer: $renderer")
    }

    /** Launch an ARM64EC client inside Wine's owned virtual desktop.
     *
     * The ARM64EC/new-WoW64 X11 driver is initialized by explorer. Launching a
     * 32-bit game directly can leave its first CreateWindow call on Wine's
     * no-driver fallback even though the Android X server is already live.
     * This mirrors the pinned Winlator launcher and keeps the desktop process
     * as the lifetime owner of the game and its graphics driver.
     */
    fun armFexClientArguments(
        executable: String,
        renderer: String,
        resolution: String,
    ): List<String> {
        require(executable.startsWith('/')) { "FEX client path must be absolute" }
        require(Regex("^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$").matches(resolution)) {
            "invalid FEX virtual desktop resolution"
        }
        val winePath = "Z:" + executable.replace('/', '\\')
        return listOf("explorer", "/desktop=shell,$resolution", winePath) +
            armClientArguments(executable, renderer).drop(1)
    }
    const val SELF_TEST_ID = "pocket-selftest-v1"
    const val WOW_5875_ID = "wow-1.12.1-5875"
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
