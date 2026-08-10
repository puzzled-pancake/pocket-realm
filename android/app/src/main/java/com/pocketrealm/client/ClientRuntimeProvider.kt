package com.pocketrealm.client

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Runtime-provider identity is part of the client capability contract.
 *
 * The x86_64 lane executes the pinned Wine WoW64 provider natively.  ARM64
 * devices must use the translated Box64 + Wine provider; they must never fall
 * through to the x86 direct implementation merely because the device also
 * advertises 32-bit ARM ABIs.
 */
enum class ClientRuntimeProvider(val id: String) {
    X86_DIRECT_WINE("x86DirectWine"),
    ARM_TRANSLATED_WINE("armTranslatedWine"),
}

data class ClientRuntimeSelection(
    val provider: ClientRuntimeProvider,
    val supported: Boolean,
    val reason: String,
)

object ClientRuntimeSelector {
    /** Pure selection rule used by both the UI boundary and host tests. */
    fun selectForAbis(
        supportedAbis: Iterable<String>,
        armTranslatedWineAvailable: Boolean = false,
    ): ClientRuntimeSelection {
        val abis = supportedAbis.toSet()
        return when {
            "x86_64" in abis -> ClientRuntimeSelection(
                ClientRuntimeProvider.X86_DIRECT_WINE,
                supported = true,
                reason = "x86_64 native Wine provider selected",
            )
            "arm64-v8a" in abis -> ClientRuntimeSelection(
                ClientRuntimeProvider.ARM_TRANSLATED_WINE,
                supported = armTranslatedWineAvailable,
                reason = if (armTranslatedWineAvailable) {
                    "ARM64 translated Wine provider available"
                } else {
                    "ARM64 device detected; translated Wine provider is not packaged and prepared"
                },
            )
            else -> ClientRuntimeSelection(
                ClientRuntimeProvider.X86_DIRECT_WINE,
                supported = false,
                reason = "no supported x86_64 or arm64-v8a runtime ABI",
            )
        }
    }

    /**
     * Select without executing anything.  The ARM marker is intentionally
     * conservative: a future provider must package both immutable Box64 and a
     * provider manifest before it can be considered present.
     */
    fun select(
        context: Context,
        translator: ArmTranslationBackend = ArmTranslationBackend.BOX64,
    ): ClientRuntimeSelection {
        val armMarker = ArmTranslatedWineRuntime.isProviderMarkerPresent(context, translator)
        return selectForAbis(Build.SUPPORTED_ABIS.asList(), armMarker)
    }
}

/**
 * Fail-closed ARM provider boundary.  The actual Box64/Wine execution layer is
 * a separate provider implementation; until its pinned native closure and
 * self-tests are installed, every operation reports an honest unsupported
 * capability instead of invoking the x86 direct launcher on ARM.
 */
class ArmTranslatedWineRuntime(context: Context) : ClientRuntime, AutoCloseable {
    private val delegate = X86DirectWineRuntime(context, ClientRuntimeProvider.ARM_TRANSLATED_WINE)

    override suspend fun probe(device: DeviceCaps, client: ClientManifest) = delegate.probe(device, client)
    override suspend fun preparePrefix(request: PrefixRequest) = delegate.preparePrefix(request)
    override suspend fun launch(request: LaunchRequest) = delegate.launch(request)
    override suspend fun requestClose(sessionId: UUID) = delegate.requestClose(sessionId)
    override suspend fun forceStop(sessionId: UUID) = delegate.forceStop(sessionId)
    override fun observe(sessionId: UUID): Flow<ClientEvent> = delegate.observe(sessionId)
    override suspend fun collectDiagnostics(sessionId: UUID) = delegate.collectDiagnostics(sessionId)
    override fun close() = delegate.close()

    companion object {
        private const val BOX64_LIBRARY = "libbox64.so"

        /**
         * The selector requires both signed APK code and the hash-pinned rootfs
         * generation materialized by provisioning. A compressed asset alone is
         * never treated as an executable provider.
         */
        fun isProviderMarkerPresent(
            context: Context,
            translator: ArmTranslationBackend = ArmTranslationBackend.BOX64,
        ): Boolean =
            runCatching {
                val nativeBox64 = java.io.File(context.applicationInfo.nativeLibraryDir, BOX64_LIBRARY)
                val rootfs = java.io.File(
                    context.noBackupFilesDir,
                    "arm-translated/winlator-ca3d735/rootfs",
                )
                val translatorPresent = when (translator) {
                    ArmTranslationBackend.BOX64 -> nativeBox64.isFile
                    ArmTranslationBackend.FEX -> {
                        val zstd = java.io.File(
                            context.applicationInfo.nativeLibraryDir,
                            "libpocket_zstd_exec.so",
                        )
                        zstd.isFile && runCatching {
                            context.assets.open(
                                "arm-translated/fexcore/BUILD_PROVENANCE.json"
                            ).close()
                            context.assets.open(
                                "arm-translated/fexcore/fexcore-runtime.tar.zst"
                            ).close()
                            true
                        }.getOrDefault(false)
                    }
                }
                translatorPresent && (translator == ArmTranslationBackend.FEX ||
                    (java.io.File(rootfs, ".pocket-rootfs-ready").isFile &&
                        java.io.File(rootfs, "opt/wine/bin/wine").isFile))
            }.getOrDefault(false)
    }
}
