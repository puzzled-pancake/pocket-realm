package com.pocketrealm.client

import com.pocketrealm.supervisor.RealmEndpoint
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
    val vulkanDriverId: String? = null,
    val displayProfileId: String = ClientDisplayProfile.BALANCED.id,
    val frameCap: Int = ClientFrameCap.FPS_30.fps,
    val tweaksJson: String = ClientTweaksConfig().toJson(),
    val realmEndpoint: RealmEndpoint = RealmEndpoint.LOCAL,
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
    val vulkanDriverId: String? = null,
    val displayProfileId: String = ClientDisplayProfile.BALANCED.id,
    val frameCap: Int = ClientFrameCap.FPS_30.fps,
    val tweaksJson: String = ClientTweaksConfig().toJson(),
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

    fun armRuntimeBuildId(translator: ArmTranslationBackend): String {
        require(translator == ArmTranslationBackend.BOX64) {
            "Box64 is the only supported ARM translator"
        }
        return ARM_TRANSLATED_RUNTIME_BUILD_ID
    }
    const val RENDERER_BUILD_ID = "gladio-eaa2a8d-o12-gles30-v2"
    const val DXVK_CONFIG_FILE_NAME = "dxvk.conf"
    val ARM_REQUIRED_WINE_GUEST_DLLS = setOf("kernel32.dll", "opengl32.dll")

    fun armRendererBuildId(
        translator: ArmTranslationBackend,
        renderer: String,
        rendererPackageId: String? = null,
        vulkanDriverId: String? = null,
    ): String {
        require(translator == ArmTranslationBackend.BOX64) {
            "Box64 is the only supported ARM translator"
        }
        require(renderer == "dxvk") { "unsupported ARM renderer: $renderer" }
        val dxvk = requireNotNull(RendererPackageCatalog.requireForRequest(
            translator, renderer, rendererPackageId,
        ))
        val driver = VulkanDriverCatalog.requireForRequest(vulkanDriverId)
        return "${driver.buildId}-${dxvk.buildId}"
    }

    /** Arguments passed after Wine for the fixed Box64 + DXVK route. */
    fun armClientArguments(executable: String, renderer: String): List<String> {
        require(renderer == "dxvk") { "unsupported ARM renderer: $renderer" }
        return listOf(executable)
    }

    /** Wine DLL policy for the fixed ARM D3D9 route.
     *
     * This is the loader order used by the qualified Box64/DXVK sessions.
     * Runtime readiness separately requires a fresh, version-matched DXVK log,
     * so a builtin fallback can never be reported as a successful ARM client.
     */
    fun armWineDllOverrides(audioOn: Boolean): String = buildList {
        add("d3d9=n,b")
        add("dxgi=n,b")
        if (!audioOn) add("winealsa.drv=d")
        add("winepulse.drv=d")
    }.joinToString(";")

    fun isArmDxvkLogAttested(
        text: String,
        dxvkVersion: String,
        driver: VulkanDriverPackage,
    ): Boolean =
        text.contains("Game: WoW.exe") &&
            text.contains("DXVK: v$dxvkVersion") &&
            when (driver.kind) {
                VulkanDriverKind.SYSTEM -> text.contains("Vortek (")
                VulkanDriverKind.TURNIP -> text.contains("Turnip Adreno")
            }

    /** Exact app-owned limiter used in addition to the legacy WoW cvar. */
    fun dxvkFrameCapConfig(frameCap: Int): String {
        ClientFrameCap.requireFps(frameCap)
        return """
            # Pocket Realm generated; applied before DXVK creates the D3D9 device.
            d3d9.maxFrameRate = $frameCap
            dxgi.maxFrameRate = $frameCap
        """.trimIndent() + "\n"
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
