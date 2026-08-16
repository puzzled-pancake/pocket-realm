package com.pocketrealm.client

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.pocketrealm.addons.AddonRuntimeProjector
import com.pocketrealm.ingame.BindingsFileCodec
import com.pocketrealm.ingame.ConfigWtfCodec
import com.pocketrealm.ingame.GameSettingsDeliveryEntry
import com.pocketrealm.ingame.GameSettingsDeliveryPlanner
import com.pocketrealm.ingame.InGameSettingsEditLock
import com.pocketrealm.ingame.InGameSettingsFiles
import com.pocketrealm.ingame.ManagedConfigPolicy
import com.pocketrealm.ingame.SavedVariablesCodec
import com.pocketrealm.ingame.WowGameSettingsConfig
import com.pocketrealm.ingame.WowUvarValueForm
import com.pocketrealm.ingame.WowVanillaSettingsCatalog
import com.pocketrealm.log.AppLog
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.wine.WineSpikeNative
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class EffectiveClientTweaks(
    val config: ClientTweaksConfig,
    val fallback: Boolean,
)

/** Resolve byte-addressed tweaks only after hashing the leased pristine executable. */
internal fun resolveEffectiveClientTweaks(
    requested: ClientTweaksConfig,
    pristineSha256: String,
): EffectiveClientTweaks {
    val effective = requested.effectiveForExecutable(pristineSha256)
    return EffectiveClientTweaks(effective, fallback = effective != requested)
}

/** Pure Config.wtf projection for the optional executable sound-channel patch. */
internal fun managedSoundChannelsConfigLine(
    audioMode: String,
    effectiveTweaks: ClientTweaksConfig,
): String = if (audioMode == "on" && effectiveTweaks.soundChannelsEnabled) {
    "SET SoundSoftwareChannels \"${effectiveTweaks.soundChannels}\"\n"
} else ""

/** Exact immutable identity of one physical ARM Wine prefix/cache generation. */
internal data class ArmGraphicsGenerationIdentity(
    val runtimeBuildId: String,
    val rendererBuildId: String,
    val prefixSchema: Int,
    val translatorId: String,
    val rendererId: String,
    val managedClientId: String,
    val managedClientGeneration: String,
    val managedClientManifestSha256: String,
    val managedClientExecutableSha256: String,
    val rendererPackageId: String? = null,
    val rendererPackageBuildId: String? = null,
    val rendererPackageDxvkVersion: String? = null,
    val rendererPackageSystem32Sha256: String? = null,
    val rendererPackageSyswow64Sha256: String? = null,
    val vulkanDriverId: String? = null,
    val vulkanDriverBuildId: String? = null,
    val vulkanDriverLibrarySha256: String? = null,
    val vulkanDriverIcdSha256: String? = null,
    val gladioPackageId: String? = null,
    val gladioPackageBuildId: String? = null,
    val gladioClientSha256: String? = null,
    val gladioServerBuildId: String? = null,
    val virglPackageId: String? = null,
    val virglPackageBuildId: String? = null,
    val virglClientSha256: String? = null,
    val virglServerBuildId: String? = null,
) {
    init {
        require(prefixSchema > 0)
        require(values().all { it.isNotBlank() })
        require(SHA256.matches(managedClientManifestSha256) &&
            SHA256.matches(managedClientExecutableSha256))
        when (rendererId) {
            "dxvk" -> {
                require(listOf(
                    rendererPackageId, rendererPackageBuildId, rendererPackageDxvkVersion,
                    rendererPackageSystem32Sha256, rendererPackageSyswow64Sha256,
                    vulkanDriverId, vulkanDriverBuildId, vulkanDriverLibrarySha256,
                    vulkanDriverIcdSha256,
                ).all { !it.isNullOrBlank() })
                require(listOf(
                    rendererPackageSystem32Sha256, rendererPackageSyswow64Sha256,
                    vulkanDriverLibrarySha256, vulkanDriverIcdSha256,
                ).all { SHA256.matches(requireNotNull(it)) })
                require(listOf(
                    gladioPackageId, gladioPackageBuildId, gladioClientSha256,
                    gladioServerBuildId, virglPackageId, virglPackageBuildId,
                    virglClientSha256, virglServerBuildId,
                ).all { it == null })
            }
            "opengl" -> {
                require(listOf(
                    rendererPackageId, rendererPackageBuildId, rendererPackageDxvkVersion,
                    rendererPackageSystem32Sha256, rendererPackageSyswow64Sha256,
                    vulkanDriverId, vulkanDriverBuildId, vulkanDriverLibrarySha256,
                    vulkanDriverIcdSha256,
                ).all { it == null })
                require(listOf(
                    gladioPackageId, gladioPackageBuildId, gladioClientSha256,
                    gladioServerBuildId,
                ).all { !it.isNullOrBlank() })
                require(SHA256.matches(requireNotNull(gladioClientSha256)))
                require(listOf(
                    virglPackageId, virglPackageBuildId, virglClientSha256, virglServerBuildId,
                ).all { it == null })
            }
            "virgl" -> {
                require(listOf(
                    rendererPackageId, rendererPackageBuildId, rendererPackageDxvkVersion,
                    rendererPackageSystem32Sha256, rendererPackageSyswow64Sha256,
                    vulkanDriverId, vulkanDriverBuildId, vulkanDriverLibrarySha256,
                    vulkanDriverIcdSha256, gladioPackageId, gladioPackageBuildId,
                    gladioClientSha256, gladioServerBuildId,
                ).all { it == null })
                require(listOf(
                    virglPackageId, virglPackageBuildId, virglClientSha256, virglServerBuildId,
                ).all { !it.isNullOrBlank() })
                require(SHA256.matches(requireNotNull(virglClientSha256)))
            }
            else -> error("unsupported ARM generation renderer: $rendererId")
        }
    }

    /** Short physical name; the full collision-resistant tuple is attested in the manifest. */
    val generationName: String by lazy {
        val canonical = values().joinToString(separator = "") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        "g-${digest.take(32)}"
    }

    fun toJson(): JSONObject {
        val value = JSONObject()
        .put("runtime_build_id", runtimeBuildId)
        .put("renderer_build_id", rendererBuildId)
        .put("prefix_schema", prefixSchema)
        .put("translator", translatorId)
        .put("renderer", rendererId)
        .put("managed_client_id", managedClientId)
        .put("managed_client_generation", managedClientGeneration)
        .put("managed_client_manifest_sha256", managedClientManifestSha256)
        .put("managed_client_executable_sha256", managedClientExecutableSha256)
        if (rendererId == "dxvk") {
            value.put("renderer_package_id", rendererPackageId)
                .put("renderer_package_build_id", rendererPackageBuildId)
                .put("renderer_package_dxvk_version", rendererPackageDxvkVersion)
                .put("renderer_package_system32_sha256", rendererPackageSystem32Sha256)
                .put("renderer_package_syswow64_sha256", rendererPackageSyswow64Sha256)
                .put("vulkan_driver_id", vulkanDriverId)
                .put("vulkan_driver_build_id", vulkanDriverBuildId)
                .put("vulkan_driver_library_sha256", vulkanDriverLibrarySha256)
                .put("vulkan_driver_icd_sha256", vulkanDriverIcdSha256)
        } else if (rendererId == "opengl") {
            value.put("gladio_package_id", gladioPackageId)
                .put("gladio_package_build_id", gladioPackageBuildId)
                .put("gladio_client_sha256", gladioClientSha256)
                .put("gladio_server_build_id", gladioServerBuildId)
        } else {
            value.put("virgl_package_id", virglPackageId)
                .put("virgl_package_build_id", virglPackageBuildId)
                .put("virgl_client_sha256", virglClientSha256)
                .put("virgl_server_build_id", virglServerBuildId)
        }
        return value
    }

    fun matchesManifest(manifest: JSONObject): Boolean = runCatching {
        val actual = manifest.getJSONObject("compatibility")
        val expected = toJson()
        actual.length() == expected.length() && expected.keys().asSequence().all { key ->
            actual.has(key) && actual.opt(key) == expected.opt(key)
        }
    }.getOrDefault(false)

    private fun values(): List<String> = listOf(
        runtimeBuildId,
        rendererBuildId,
        prefixSchema.toString(),
        translatorId,
        rendererId,
        managedClientId,
        managedClientGeneration,
        managedClientManifestSha256,
        managedClientExecutableSha256,
        rendererPackageId ?: "-",
        rendererPackageBuildId ?: "-",
        rendererPackageDxvkVersion ?: "-",
        rendererPackageSystem32Sha256 ?: "-",
        rendererPackageSyswow64Sha256 ?: "-",
        vulkanDriverId ?: "-",
        vulkanDriverBuildId ?: "-",
        vulkanDriverLibrarySha256 ?: "-",
        vulkanDriverIcdSha256 ?: "-",
        gladioPackageId ?: "-",
        gladioPackageBuildId ?: "-",
        gladioClientSha256 ?: "-",
        gladioServerBuildId ?: "-",
        virglPackageId ?: "-",
        virglPackageBuildId ?: "-",
        virglClientSha256 ?: "-",
        virglServerBuildId ?: "-",
    )

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** Cross-process lease that prevents pruning a prepared or running ARM generation. */
internal class ArmGraphicsGenerationLease private constructor(
    private val file: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock,
    val generationRoot: File,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isHeld: Boolean get() = !closed.get() && lock.isValid

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { file.close() }
    }

    companion object {
        const val FILE_NAME = ".generation.lease"
        private const val PARENT_LOCK_FILE_NAME = ".generation-index.lock"
        private const val ACQUIRE_RETRY_MS = 25L

        /** Bounded acquire (de-vibe A6): the old loop spun a Binder thread
         *  every 25 ms forever when the lease could not be taken. Escalate the
         *  backoff and fail loudly instead of wedging the launch path. */
        private const val ACQUIRE_DEADLINE_MS = 30_000L
        private const val ACQUIRE_MAX_BACKOFF_MS = 500L
        private val GENERATION_NAME = Regex("g-[0-9a-f]{32}")
        private val parentMonitors = ConcurrentHashMap<String, Any>()

        /**
         * Acquire only the lease file currently reachable through generationRoot.
         * A miss closes its descriptor before dropping the stable parent lock;
         * it never waits on an inode that pruning could rename out from under it.
         */
        fun acquire(generationRoot: File): ArmGraphicsGenerationLease {
            val deadline = System.currentTimeMillis() + ACQUIRE_DEADLINE_MS
            var backoff = ACQUIRE_RETRY_MS
            while (true) {
                val acquired = withParentLock(generationRoot) { current ->
                    ensureCurrentGenerationRoot(current)
                    tryOpenCurrentLease(current)
                }
                if (acquired != null) return acquired
                if (System.currentTimeMillis() >= deadline) {
                    throw IllegalStateException(
                        "generation lease not acquirable within ${ACQUIRE_DEADLINE_MS / 1000}s " +
                            "(contended by another preparation?)",
                    )
                }
                Thread.sleep(backoff)
                backoff = minOf(backoff * 2, ACQUIRE_MAX_BACKOFF_MS)
            }
        }

        fun tryAcquire(generationRoot: File): ArmGraphicsGenerationLease? =
            withParentLock(generationRoot) { current ->
                if (!isPlainCurrentGenerationRoot(current)) null else tryOpenCurrentLease(current)
            }

        /** Atomically prove inactivity and retire the exact currently named directory. */
        fun retireIfInactive(generationRoot: File, retiredTarget: File): Boolean =
            withParentLock(generationRoot) { current ->
                if (!isPlainCurrentGenerationRoot(current)) return@withParentLock false
                val target = retiredTarget.absoluteFile
                val requestedTargetParent = checkNotNull(target.parentFile)
                val targetParent = requestedTargetParent.canonicalFile
                check(Files.isDirectory(requestedTargetParent.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(requestedTargetParent.toPath())) {
                    "retired ARM graphics root is absent or unsafe"
                }
                check(requestedTargetParent.canonicalFile == targetParent &&
                    !Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "retired ARM graphics target is unsafe or already exists"
                }
                val lease = tryOpenCurrentLease(current) ?: return@withParentLock false
                // Keep the stable parent lock while releasing the per-generation
                // descriptor and renaming. A waiter cannot open either the old
                // inode or a newly recreated current path during this interval.
                lease.close()
                check(isPlainCurrentGenerationRoot(current)) {
                    "ARM graphics generation changed before retirement"
                }
                Files.move(current.toPath(), target.toPath())
                true
            }

        private fun tryOpenCurrentLease(current: File): ArmGraphicsGenerationLease? {
            check(isPlainCurrentGenerationRoot(current)) {
                "ARM graphics generation root is absent or unsafe"
            }
            val leaseFile = File(current, FILE_NAME)
            if (!leaseFile.exists()) check(leaseFile.createNewFile()) {
                "ARM graphics generation lease file could not be created"
            }
            val expectedLease = File(current.canonicalFile, FILE_NAME)
            check(leaseFile.isFile && !Files.isSymbolicLink(leaseFile.toPath()) &&
                leaseFile.canonicalFile == expectedLease.canonicalFile) {
                "ARM graphics generation lease file is unsafe"
            }
            val file = RandomAccessFile(leaseFile, "rw")
            try {
                val channel = file.channel
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    channel.close()
                    file.close()
                    return null
                }
                check(isPlainCurrentGenerationRoot(current) &&
                    leaseFile.canonicalFile == File(current.canonicalFile, FILE_NAME).canonicalFile) {
                    "ARM graphics generation path changed during lease acquisition"
                }
                return ArmGraphicsGenerationLease(
                    file,
                    channel,
                    lock,
                    current.canonicalFile,
                )
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }

        private fun ensureCurrentGenerationRoot(current: File) {
            val path = current.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
            check(isPlainCurrentGenerationRoot(current)) {
                "ARM graphics generation root is absent or unsafe"
            }
        }

        private fun isPlainCurrentGenerationRoot(current: File): Boolean =
            Files.isDirectory(current.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(current.toPath()) &&
                current.canonicalFile.parentFile == current.parentFile?.canonicalFile

        private fun <T> withParentLock(
            generationRoot: File,
            block: (current: File) -> T,
        ): T {
            val requested = generationRoot.absoluteFile
            require(GENERATION_NAME.matches(requested.name)) {
                "invalid ARM graphics generation name"
            }
            val requestedParent = checkNotNull(requested.parentFile)
            val parent = requestedParent.canonicalFile
            check(Files.isDirectory(requestedParent.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(requestedParent.toPath()) &&
                requestedParent.canonicalFile == parent) {
                "ARM graphics generations root is absent or unsafe"
            }
            val current = File(parent, requested.name)
            val monitor = parentMonitors.computeIfAbsent(parent.absolutePath) { Any() }
            return synchronized(monitor) {
                val coordinationFile = File(parent, PARENT_LOCK_FILE_NAME)
                if (!coordinationFile.exists()) coordinationFile.createNewFile()
                check(coordinationFile.isFile &&
                    !Files.isSymbolicLink(coordinationFile.toPath()) &&
                    coordinationFile.canonicalFile == File(parent, PARENT_LOCK_FILE_NAME).canonicalFile) {
                    "ARM graphics generation coordination lock is unsafe"
                }
                RandomAccessFile(coordinationFile, "rw").use { coordination ->
                    coordination.channel.use { coordinationChannel ->
                        coordinationChannel.lock().use { block(current) }
                    }
                }
            }
        }
    }
}

/** Immutable-code / mutable-data staging used by the production runtime. */
internal class WineRuntimeStore(private val context: Context) {
    data class Prepared(
        val clientId: String,
        val prefixId: String,
        val root: File,
        val tree: File,
        val prefix: File,
        val cache: File,
        val tmp: File,
        val executable: File,
        val workingDir: File,
        val selfTest: Boolean,
        val armTranslator: ArmTranslationBackend? = null,
        val armRenderer: String? = null,
        val armRendererPackageId: String? = null,
        val armVulkanDriverId: String? = null,
        val audioMode: String = "off",
        val tweaksJson: String = ClientTweaksConfig().toJson(),
        val tweaksSignature: String = "",
        val tweaksFallback: Boolean = false,
        val realmEndpoint: RealmEndpoint = RealmEndpoint.LOCAL,
        val displayProfileId: String = ClientDisplayProfile.BALANCED.id,
        val frameCap: Int = ClientFrameCap.FPS_30.fps,
        val managedClient: ManagedClientStore.ManagedClient? = null,
        val clientLease: ClientGenerationLease? = null,
        val armGenerationIdentity: ArmGraphicsGenerationIdentity? = null,
        val armGenerationLease: ArmGraphicsGenerationLease? = null,
        val selectedExecutableSize: Long = 0L,
        val selectedExecutableSha256: String = "",
        /**
         * Exact bytes written to WTF/Config.wtf by this prepare's merge, or
         * null when enforcement was skipped (self-test). Attestation
         * byte-compares the live file against this capture.
         */
        val managedConfigText: String? = null,
    ) : AutoCloseable {
        override fun close() {
            try {
                armGenerationLease?.close()
            } finally {
                clientLease?.close()
            }
        }
    }

    init { WineSpikeNative.load() }

    fun paths(
        clientId: String,
        armTranslator: ArmTranslationBackend = ArmTranslationBackend.BOX64,
        armRenderer: String = "dxvk",
        armRendererPackageId: String? = null,
        armVulkanDriverId: String? = null,
    ): Prepared {
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            return armPaths(
                clientId, armTranslator, armRenderer, armRendererPackageId, armVulkanDriverId,
            )
        }
        val selfTest = clientId == ClientRuntimeContract.SELF_TEST_ID
        val leased = if (selfTest) null else ManagedClientStore(context).acquireRuntime(clientId)
        val managed = leased?.client
        val root = File(
            context.noBackupFilesDir,
            // AF_UNIX sun_path is only 108 bytes on Linux. Keep the physical
            // generation name compact; the full pinned build/client identity
            // remains in prefix-manifest.json and prefixId.
            "wine/w11w64-v1/${if (selfTest) "selftest" else "wow5875"}/p${ClientRuntimeContract.PREFIX_SCHEMA}",
        )
        return try { Prepared(
            clientId = clientId,
            prefixId = listOfNotNull(
                ClientRuntimeContract.RUNTIME_BUILD_ID,
                ClientRuntimeContract.RENDERER_BUILD_ID.takeUnless { selfTest },
                clientId,
                ClientRuntimeContract.PREFIX_SCHEMA.toString(),
            ).joinToString(":"),
            root = root,
            tree = File(root, "wine-tree"),
            prefix = File(root, "wine-prefix"),
            cache = File(root, "wine-pe-cache"),
            tmp = File(root, "tmp"),
            executable = managed?.executable ?: File(root, "wine-tree/pocket_selftest.exe"),
            workingDir = managed?.root ?: root,
            selfTest = selfTest,
            managedClient = managed,
            clientLease = leased?.lease,
            selectedExecutableSize = managed?.executableSize ?: 0L,
            selectedExecutableSha256 = managed?.executableSha256.orEmpty(),
        ) } catch (error: Throwable) {
            leased?.close()
            throw error
        }
    }

    fun prepare(
        clientId: String,
        renderer: String,
        audioMode: String,
        armTranslator: ArmTranslationBackend = ArmTranslationBackend.BOX64,
        inputSafeMode: Boolean = false,
        armRendererPackageId: String? = null,
        armVulkanDriverId: String? = null,
        displayProfileId: String = ClientDisplayProfile.BALANCED.id,
        frameCap: Int = ClientFrameCap.FPS_30.fps,
        tweaksJson: String = "",
        realmEndpoint: RealmEndpoint = RealmEndpoint.LOCAL,
    ): Prepared {
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            return prepareArm(
                clientId,
                renderer,
                audioMode,
                armTranslator,
                inputSafeMode,
                armRendererPackageId,
                armVulkanDriverId,
                displayProfileId,
                frameCap,
                tweaksJson,
                realmEndpoint,
            )
        }
        val displayProfile = ClientDisplayProfile.requireId(displayProfileId)
        val selectedFrameCap = ClientFrameCap.requireFps(frameCap)
        require(renderer == "wined3d") { "The bundled compatibility check supports WineD3D only" }
        require(audioMode in setOf("off", "on")) { "only off/on audioMode is supported" }
        require(audioMode == "off") { "x86 direct Wine audio is not supported" }
        val tweaks = if (clientId == ClientRuntimeContract.SELF_TEST_ID && tweaksJson.isBlank()) {
            ClientTweaksConfig()
        } else {
            ClientTweaksConfig.fromControlJson(tweaksJson)
        }
        val p = paths(clientId).copy(
            audioMode = audioMode,
            tweaksJson = tweaks.toJson(),
            realmEndpoint = realmEndpoint,
            displayProfileId = displayProfile.id,
            frameCap = selectedFrameCap.fps,
        )
        return try {
        p.root.mkdirs(); p.prefix.mkdirs(); p.tmp.mkdirs()
        ensureSharedCaches(p)
        File(p.tmp, ".X11-unix").mkdirs()

        val staging = readAsset("staging-manifest.json")
        check(WineSpikeNative.buildSymlinkTreeNative(
            p.tree.absolutePath, context.applicationInfo.nativeLibraryDir, staging) == 0) {
            "Wine logical tree could not be built"
        }
        prepareData(p)
        materializePeCaches(p)

        val manifestFile = File(p.root, "prefix-manifest.json")
        val compatible = manifestFile.isFile && runCatching {
            val old = JSONObject(manifestFile.readText())
            old.getString("runtime_build_id") == ClientRuntimeContract.RUNTIME_BUILD_ID &&
                (p.selfTest || old.optString("renderer_build_id") == ClientRuntimeContract.RENDERER_BUILD_ID) &&
                old.getInt("prefix_schema") == ClientRuntimeContract.PREFIX_SCHEMA &&
                old.getString("windows_arch") == "win32-on-wow64" &&
                old.getString("renderer") == renderer
        }.getOrDefault(false)

        if (!compatible && p.prefix.listFiles()?.isNotEmpty() == true) {
            val preserved = File(p.root, "wine-prefix-preserved-${System.currentTimeMillis()}")
            check(p.prefix.renameTo(preserved)) { "Incompatible prefix could not be preserved" }
            p.prefix.mkdirs()
            prunePreservedPrefixes(p.root)
        }

        if (!prefixReady(p.prefix, 1_000)) initializePrefix(p)
        check(prefixReady(p.prefix, 1_000)) { "Wine prefix did not become ready" }
        check(p.executable.isFile) { "Authorized client executable is absent" }
        val effectivePrepared = applyTweaks(p, tweaks)
        val enforced = if (!p.selfTest) enforceManagedSafeMode(
            effectivePrepared, renderer, displayProfile, inputSafeMode, audioMode, realmEndpoint,
        ) else effectivePrepared

        val manifest = JSONObject()
            .put("runtime_build_id", ClientRuntimeContract.RUNTIME_BUILD_ID)
            .put("prefix_schema", ClientRuntimeContract.PREFIX_SCHEMA)
            .put("windows_arch", "win32-on-wow64")
            .put("renderer", renderer)
            .put("client_id", clientId)
            .put("code_location", "apk-nativeLibraryDir")
            .put("code_immutable", true)
            .put("prefix_quota_bytes", ClientRuntimeContract.PREFIX_QUOTA_BYTES)
            .put("preserved_prefix_quota_bytes", ClientRuntimeContract.PRESERVED_PREFIX_QUOTA_BYTES)
            .put("max_preserved_prefixes", ClientRuntimeContract.MAX_PRESERVED_PREFIXES)
            .put("cache_quota_bytes", ClientRuntimeContract.CACHE_QUOTA_BYTES)
            .put("log_quota_bytes", ClientRuntimeContract.LOG_QUOTA_BYTES)
        if (!p.selfTest) {
            val managed = checkNotNull(p.managedClient) { "managed client identity is absent" }
            val identity = managed.manifest.getJSONObject("identity")
            manifest
                .put("renderer_build_id", ClientRuntimeContract.RENDERER_BUILD_ID)
                .put("renderer_provider", JSONObject()
                    .put("client", "gladio-eaa2a8d")
                    .put("client_sha256", "7b60dafa5e071e11187c0936840201920e141160f0897609ce530cb6f69b60b6")
                    .put("server", "pocket-gladio-o07v1")
                    .put("server_sha256", "2d20db2c12b007b2251edce9421264ea168da0bb463718d9baa8f2c02403584f")
                    .put("api", "OpenGL 3.0 / GLSL 1.30 over GLES 3.0")
                    .put("internal_format_queries", true)
                    .put("modern_instancing", false))
                .put("client_executable_sha256", identity.getString("sha256"))
                .put("client_version", identity.getString("version"))
                .put("working_directory", "app-private-managed-client")
                .put("safe_profile", JSONObject()
                    .put("resolution_ceiling", resolveVirtualDisplay(displayProfile).resolution)
                    .put("qualified_effective_resolution", "800x600")
                    .put("fps_cap", selectedFrameCap.fps)
                    .put("realm_endpoint", realmEndpoint.address)
                    .put("addons", if (inputSafeMode) "safe-mode-off" else "project-managed-at-launch"))
                .put("known_deviations", JSONArray()
                    .put("GLES shader target is 300 es")
                    .put("renderer advertises a constrained GL 3.0 capability subset")
                    .put("texture copy uses GLES readback/upload instead of glCopyTexImage2D"))
        }
        writeAtomic(manifestFile, manifest.toString(2))
        enforceQuotas(p)
        enforced
        } catch (error: Throwable) {
            p.close()
            throw error
        }
    }

    private fun armPaths(
        clientId: String,
        translator: ArmTranslationBackend,
        renderer: String,
        rendererPackageId: String?,
        vulkanDriverId: String?,
    ): Prepared {
        check(clientId == ClientRuntimeContract.WOW_5875_ID) {
            "ARM translated runtime currently authorizes only the imported build-5875 client"
        }
        val leased = ManagedClientStore(context).acquireRuntime(clientId)
        val managed = leased.client
        var generationLease: ArmGraphicsGenerationLease? = null
        return try {
        require(translator == ArmTranslationBackend.BOX64) {
            "Box64 is the only supported ARM translator"
        }
        val rendererSelection = ArmClientRendererCatalog.requireRuntimeRenderer(
            renderer,
            if (renderer != "dxvk") runCatching {
                AndroidGladioCapabilityProbe.probe(context)
            } else null,
        )
        if (rendererSelection != ArmClientRenderer.DXVK) {
            val (libraryName, expectedDigest) = when (rendererSelection) {
                ArmClientRenderer.LEGACY_GLADIO ->
                    "libgladiorenderer.so" to ArmClientRendererCatalog.GLADIO_SERVER_SHA256
                ArmClientRenderer.MESA_VIRGL ->
                    "libvirglrenderer.so" to ArmClientRendererCatalog.VIRGL_SERVER_SHA256
                ArmClientRenderer.DXVK -> error("unreachable renderer server attestation")
            }
            val server = File(context.applicationInfo.nativeLibraryDir, libraryName)
            check(server.isFile && !Files.isSymbolicLink(server.toPath()) &&
                sha256(server) == expectedDigest) {
                "selected ${rendererSelection.label} Android server is absent or changed"
            }
        }
        val rendererPackage = if (rendererSelection == ArmClientRenderer.DXVK) {
            requireNotNull(RendererPackageCatalog.requireForRequest(
                translator, renderer, rendererPackageId,
            ))
        } else {
            require(rendererPackageId == null) { "$renderer does not accept a DXVK package" }
            null
        }
        val vulkanDriver = if (rendererSelection == ArmClientRenderer.DXVK) {
            VulkanDriverCatalog.requireForRequest(vulkanDriverId)
        } else {
            require(vulkanDriverId == null) { "$renderer does not accept a Vulkan driver" }
            null
        }
        val generationIdentity = ArmGraphicsGenerationIdentity(
            runtimeBuildId = ClientRuntimeContract.armRuntimeBuildId(translator),
            rendererBuildId = ClientRuntimeContract.armRendererBuildId(
                translator,
                renderer,
                rendererPackage?.id,
                vulkanDriver?.id,
            ),
            prefixSchema = ClientRuntimeContract.PREFIX_SCHEMA,
            translatorId = translator.id,
            rendererId = renderer,
            managedClientId = managed.id,
            managedClientGeneration = managed.generation,
            managedClientManifestSha256 = managed.manifestSha256,
            managedClientExecutableSha256 = managed.executableSha256,
            rendererPackageId = rendererPackage?.id,
            rendererPackageBuildId = rendererPackage?.buildId,
            rendererPackageDxvkVersion = rendererPackage?.dxvkVersion,
            rendererPackageSystem32Sha256 = rendererPackage?.system32Sha256,
            rendererPackageSyswow64Sha256 = rendererPackage?.syswow64Sha256,
            vulkanDriverId = vulkanDriver?.id,
            vulkanDriverBuildId = vulkanDriver?.buildId,
            vulkanDriverLibrarySha256 = vulkanDriver?.librarySha256,
            vulkanDriverIcdSha256 = vulkanDriver?.icdSha256,
            gladioPackageId = ArmClientRendererCatalog.GLADIO_PACKAGE_ID.takeIf {
                rendererSelection == ArmClientRenderer.LEGACY_GLADIO
            },
            gladioPackageBuildId = ArmClientRendererCatalog.GLADIO_BUILD_ID.takeIf {
                rendererSelection == ArmClientRenderer.LEGACY_GLADIO
            },
            gladioClientSha256 = ArmClientRendererCatalog.GLADIO_CLIENT_SHA256.takeIf {
                rendererSelection == ArmClientRenderer.LEGACY_GLADIO
            },
            gladioServerBuildId = ArmClientRendererCatalog.GLADIO_SERVER_BUILD_ID.takeIf {
                rendererSelection == ArmClientRenderer.LEGACY_GLADIO
            },
            virglPackageId = ArmClientRendererCatalog.VIRGL_PACKAGE_ID.takeIf {
                rendererSelection == ArmClientRenderer.MESA_VIRGL
            },
            virglPackageBuildId = ArmClientRendererCatalog.VIRGL_BUILD_ID.takeIf {
                rendererSelection == ArmClientRenderer.MESA_VIRGL
            },
            virglClientSha256 = ArmClientRendererCatalog.VIRGL_CLIENT_SHA256.takeIf {
                rendererSelection == ArmClientRenderer.MESA_VIRGL
            },
            virglServerBuildId = ArmClientRendererCatalog.VIRGL_SERVER_BUILD_ID.takeIf {
                rendererSelection == ArmClientRenderer.MESA_VIRGL
            },
        )
        val root = File(
            context.noBackupFilesDir,
            "arm-translated/winlator-ca3d735",
        )
        val rootfs = File(root, "rootfs")
        val generations = File(root, "generations")
        requirePlainDirectory(File(context.noBackupFilesDir, "arm-translated"))
        requirePlainDirectory(root)
        requirePlainDirectory(generations)
        val generationRoot = File(generations, generationIdentity.generationName)
        val generationParent = checkNotNull(generationRoot.absoluteFile.parentFile)
        check(generationParent.canonicalFile == generations.canonicalFile) {
            "ARM graphics generation escaped its app-owned root"
        }
        generationLease = ArmGraphicsGenerationLease.acquire(generationRoot)
        val prefix = File(generationRoot, ARM_PREFIX_DIRECTORY)
        Prepared(
            clientId = clientId,
            prefixId = listOf(
                generationIdentity.runtimeBuildId,
                generationIdentity.rendererBuildId,
                "schema=${generationIdentity.prefixSchema}",
                "client=${generationIdentity.managedClientId}",
                "clientGeneration=${generationIdentity.managedClientGeneration}",
                "clientManifest=${generationIdentity.managedClientManifestSha256}",
                "renderer=${generationIdentity.rendererId}",
                "payload=${generationIdentity.rendererPackageId ?: generationIdentity.gladioPackageId ?: generationIdentity.virglPackageId}",
            ).joinToString(":"),
            root = root,
            tree = rootfs,
            prefix = prefix,
            cache = File(generationRoot, ARM_CACHE_DIRECTORY),
            // The pinned Box64 rootfs resolves :0 beneath rootfs/tmp.
            tmp = File(rootfs, "tmp"),
            executable = managed.executable,
            workingDir = managed.root,
            selfTest = false,
            armTranslator = translator,
            armRenderer = renderer,
            armRendererPackageId = rendererPackage?.id,
            armVulkanDriverId = vulkanDriver?.id,
            managedClient = managed,
            clientLease = leased.lease,
            armGenerationIdentity = generationIdentity,
            armGenerationLease = generationLease,
            selectedExecutableSize = managed.executableSize,
            selectedExecutableSha256 = managed.executableSha256,
        )
        } catch (error: Throwable) {
            generationLease?.close()
            leased.close()
            throw error
        }
    }

    private fun prepareArm(
        clientId: String,
        renderer: String,
        audioMode: String,
        translator: ArmTranslationBackend,
        inputSafeMode: Boolean,
        rendererPackageId: String?,
        vulkanDriverId: String?,
        displayProfileId: String,
        frameCap: Int,
        tweaksJson: String = "",
        realmEndpoint: RealmEndpoint,
    ): Prepared {
        val displayProfile = ClientDisplayProfile.requireId(displayProfileId)
        val selectedFrameCap = ClientFrameCap.requireFps(frameCap)
        require(translator == ArmTranslationBackend.BOX64) {
            "Box64 is the only supported ARM translator"
        }
        require(renderer == "dxvk" || renderer == "opengl" || renderer == "virgl") {
            "unsupported Box64 ARM renderer: $renderer"
        }
        retireRemovedArmRuntimeState()
        require(audioMode in setOf("off", "on")) { "only off/on audioMode is supported" }
        val rendererPackage = if (renderer == "dxvk") {
            requireNotNull(RendererPackageCatalog.requireForRequest(
                translator, renderer, rendererPackageId,
            ))
        } else {
            require(rendererPackageId == null) { "$renderer does not accept a DXVK package" }
            null
        }
        val vulkanDriver = if (renderer == "dxvk") {
            VulkanDriverCatalog.requireForRequest(vulkanDriverId)
        } else {
            require(vulkanDriverId == null) { "$renderer does not accept a Vulkan driver" }
            null
        }
        val tweaks = ClientTweaksConfig.fromControlJson(tweaksJson)
        val p = armPaths(
            clientId, translator, renderer, rendererPackage?.id, vulkanDriver?.id,
        ).copy(
            audioMode = audioMode,
            tweaksJson = tweaks.toJson(),
            realmEndpoint = realmEndpoint,
            displayProfileId = displayProfile.id,
            frameCap = selectedFrameCap.fps,
        )
        return try {
        ArmRootfsProvisioner(context).ensure(p.root)
        val nativeBox64 = File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
        val wine = File(p.tree, "opt/wine/bin/wine")
        check(nativeBox64.isFile) { "APK-managed Box64 is missing" }
        check(File(p.tree, ".pocket-rootfs-ready").isFile && wine.isFile) {
            "pinned Winlator rootfs is not provisioned"
        }
        installArmRuntimeAliases(p.tree)
        patchWinlatorPackagePaths(p.tree)
        validateArmGenerationBeforeReuse(p)
        ensureBox64RendererPrefix(p)
        linkArmBuiltins(p)
        when (renderer) {
            "dxvk" -> installPinnedArmGraphics(
                p, checkNotNull(rendererPackage), checkNotNull(vulkanDriver),
            )
            "opengl" -> installPinnedArmGladio(p)
            "virgl" -> installPinnedArmVirgl(p)
        }
        p.tmp.mkdirs()
        ensureArmCacheDirectories(p)
        if (renderer == "dxvk") {
            writeAtomic(
                File(p.cache, ClientRuntimeContract.DXVK_CONFIG_FILE_NAME),
                ClientRuntimeContract.dxvkFrameCapConfig(selectedFrameCap.fps),
            )
        }
        val runAlias = File(p.root, "run")
        if (runAlias.isDirectory && !Files.isSymbolicLink(runAlias.toPath()) &&
            runAlias.listFiles().isNullOrEmpty()) {
            check(runAlias.delete()) { "empty ARM runtime run directory could not be replaced" }
        }
        if (!Files.exists(runAlias.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(runAlias.toPath(), p.tmp.toPath())
        }
        check(prefixReady(p.prefix, 1_000)) { "pinned ARM Wine prefix is incomplete" }
        check(p.executable.isFile) { "authorized build-5875 client executable is absent" }
        if (audioMode == "on") validateArmAudioRuntime(p)
        val effectivePrepared = applyTweaks(p, tweaks)

        val dosDevices = File(p.prefix, "dosdevices").apply { mkdirs() }
        val z = File(dosDevices, "z:").toPath()
        if (Files.exists(z, LinkOption.NOFOLLOW_LINKS)) Files.delete(z)
        Files.createSymbolicLink(z, File("/").toPath())
        val enforced = enforceManagedSafeMode(
            effectivePrepared, renderer, displayProfile, inputSafeMode, audioMode, realmEndpoint,
        )

        val generationIdentity = checkNotNull(p.armGenerationIdentity) {
            "ARM graphics generation identity is absent"
        }
        val manifest = JSONObject()
            .put("manifest_schema", ARM_PREFIX_MANIFEST_SCHEMA)
            .put("generation", generationIdentity.generationName)
            .put("compatibility", generationIdentity.toJson())
            .put("runtime_build_id", ClientRuntimeContract.armRuntimeBuildId(translator))
            .put("renderer_build_id", ClientRuntimeContract.armRendererBuildId(
                translator,
                renderer,
                rendererPackage?.id,
                vulkanDriver?.id,
            ))
            .put("provider", ClientRuntimeProvider.ARM_TRANSLATED_WINE.id)
            .put("translator", translator.id)
            .put("translator_code_location", "apk-nativeLibraryDir")
            .put("translator_immutable", true)
            .put("rootfs_generation", "winlator-ca3d735")
            .put("wine_version", "10.10")
            .put("package_path_adaptation", "com.winlator/rootfs -> com.pocketrealm/rfs")
            .put("prefix_schema", ClientRuntimeContract.PREFIX_SCHEMA)
            .put("windows_arch", "win32-on-wow64")
            .put("renderer", renderer)
            .put("display_profile", displayProfile.id)
            .put("resolution", resolveVirtualDisplay(displayProfile).resolution)
            .put("fps_cap", selectedFrameCap.fps)
            .put("graphics_driver", when (renderer) {
                "dxvk" -> checkNotNull(vulkanDriver).buildId
                "opengl" -> ArmClientRendererCatalog.GLADIO_BUILD_ID
                else -> ArmClientRendererCatalog.VIRGL_BUILD_ID
            })
            .put("dx_wrapper", when (renderer) {
                "dxvk" -> "dxvk-${checkNotNull(rendererPackage).dxvkVersion}"
                "opengl" -> "disabled-native-client-opengl"
                else -> "wined3d-mesa-virpipe"
            })
            .put("client_id", clientId)
            .put("managed_client_generation", generationIdentity.managedClientGeneration)
            .put("managed_client_manifest_sha256", generationIdentity.managedClientManifestSha256)
            .put("working_directory", "app-private-managed-client")
        if (renderer == "dxvk") {
            val dxvkPackage = checkNotNull(rendererPackage)
            val driver = checkNotNull(vulkanDriver)
            manifest.put("renderer_package_id", dxvkPackage.id)
                .put("renderer_package_qualification", dxvkPackage.qualification)
                .put("vulkan_driver_id", driver.id)
                .put("vulkan_driver_qualification", driver.qualification)
                .put("cache_layout", JSONObject()
                    .put("dxvk_state", "$ARM_CACHE_DIRECTORY/dxvk")
                    .put("mesa_shader", "$ARM_CACHE_DIRECTORY/mesa")
                    .put("xdg", "$ARM_CACHE_DIRECTORY/xdg"))
        } else if (renderer == "opengl") {
            manifest.put("gladio_package_id", ArmClientRendererCatalog.GLADIO_PACKAGE_ID)
                .put("gladio_client_sha256", ArmClientRendererCatalog.GLADIO_CLIENT_SHA256)
                .put("gladio_server_build_id", ArmClientRendererCatalog.GLADIO_SERVER_BUILD_ID)
                .put("cache_layout", JSONObject()
                    .put("gladio", "$ARM_CACHE_DIRECTORY/gladio")
                    .put("wine", "$ARM_CACHE_DIRECTORY/wine")
                    .put("xdg", "$ARM_CACHE_DIRECTORY/xdg"))
        } else {
            manifest.put("virgl_package_id", ArmClientRendererCatalog.VIRGL_PACKAGE_ID)
                .put("virgl_client_sha256", ArmClientRendererCatalog.VIRGL_CLIENT_SHA256)
                .put("virgl_server_build_id", ArmClientRendererCatalog.VIRGL_SERVER_BUILD_ID)
                .put("virgl_environment_id", ArmClientRendererCatalog.VIRGL_ENVIRONMENT_ID)
                .put("cache_layout", JSONObject()
                    .put("mesa_shader", "$ARM_CACHE_DIRECTORY/virgl")
                    .put("xdg", "$ARM_CACHE_DIRECTORY/xdg"))
        }
        writeAtomic(File(p.prefix.parentFile, "prefix-manifest.json"), manifest.toString(2))
        pruneInactiveArmGenerations(p)
        enforced
        } catch (error: Throwable) {
            p.close()
            throw error
        }
    }

    /** A ready prefix/cache may be reused only after its complete tuple manifest matches. */
    private fun validateArmGenerationBeforeReuse(p: Prepared) {
        val identity = checkNotNull(p.armGenerationIdentity) {
            "ARM graphics generation identity is absent"
        }
        val lease = checkNotNull(p.armGenerationLease) {
            "ARM graphics generation lease is absent"
        }
        check(lease.isHeld) { "ARM graphics generation lease was released before preparation" }
        val generationRoot = checkNotNull(p.prefix.parentFile)
        check(generationRoot.name == identity.generationName &&
            p.cache.parentFile?.canonicalFile == generationRoot.canonicalFile) {
            "ARM prefix/cache physical generation identity mismatch"
        }
        val payload = generationRoot.listFiles().orEmpty().any {
            it.name != ArmGraphicsGenerationLease.FILE_NAME
        }
        if (!payload) return
        val manifestFile = File(generationRoot, ARM_PREFIX_MANIFEST_FILE)
        val prefixReusable = Files.isDirectory(p.prefix.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(p.prefix.toPath()) && prefixReady(p.prefix, 1_000)
        val cacheSafe = plainDirectoryIfPresent(p.cache) && armCacheSubdirectories(p).all {
            plainDirectoryIfPresent(File(p.cache, it))
        }
        val compatible = manifestFile.isFile && !Files.isSymbolicLink(manifestFile.toPath()) &&
            prefixReusable && cacheSafe &&
            runCatching {
                val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
                manifest.getInt("manifest_schema") == ARM_PREFIX_MANIFEST_SCHEMA &&
                    manifest.getString("generation") == identity.generationName &&
                    identity.matchesManifest(manifest)
            }.getOrDefault(false)
        if (compatible) return

        // The exclusive generation lease proves this payload is inactive. Keep
        // one recoverable retired copy, then rebuild the exact tuple in place.
        val retiredRoot = File(p.root, ARM_RETIRED_GENERATIONS_DIRECTORY)
        requirePlainDirectory(retiredRoot)
        val retired = File(
            retiredRoot,
            "${identity.generationName}-incompatible-${System.currentTimeMillis()}-${System.nanoTime()}",
        )
        check(retired.mkdir()) { "incompatible ARM generation could not be preserved" }
        generationRoot.listFiles().orEmpty()
            .filterNot { it.name == ArmGraphicsGenerationLease.FILE_NAME }
            .forEach { child ->
                check(child.renameTo(File(retired, child.name))) {
                    "incompatible ARM generation payload could not be preserved: ${child.name}"
                }
            }
        pruneRetiredArmGenerations(retiredRoot)
    }

    private fun plainDirectoryIfPresent(directory: File): Boolean {
        val path = directory.toPath()
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
            (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    }

    /** DXVK, Mesa and XDG own real tuple-local directories, never shared symlinks. */
    private fun ensureArmCacheDirectories(p: Prepared) {
        val generationRoot = checkNotNull(p.prefix.parentFile).canonicalFile
        check(p.cache.parentFile?.canonicalFile == generationRoot) {
            "ARM cache escaped its prefix generation"
        }
        requirePlainDirectory(p.cache)
        for (name in armCacheSubdirectories(p)) {
            val directory = File(p.cache, name)
            requirePlainDirectory(directory)
            check(directory.canonicalFile.parentFile == p.cache.canonicalFile) {
                "ARM cache directory escaped its tuple: $name"
            }
        }
    }

    private fun attestArmCacheDirectories(p: Prepared) {
        val generationRoot = checkNotNull(p.prefix.parentFile).canonicalFile
        val subdirectories = armCacheSubdirectories(p)
        val directories = listOf(p.cache) + subdirectories.map { File(p.cache, it) }
        directories.forEach { directory ->
            val path = directory.toPath()
            check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path)) {
                "prepared ARM cache directory is absent or unsafe: ${directory.name}"
            }
        }
        check(p.cache.canonicalFile.parentFile == generationRoot &&
            subdirectories.all {
                File(p.cache, it).canonicalFile.parentFile == p.cache.canonicalFile
            }) {
            "prepared ARM cache layout escaped its tuple generation"
        }
    }

    private fun armCacheSubdirectories(p: Prepared): List<String> = when (p.armRenderer) {
        "dxvk" -> listOf("dxvk", "mesa", "xdg")
        "opengl" -> listOf("gladio", "wine", "xdg")
        "virgl" -> listOf("virgl", "xdg")
        else -> error("unsupported ARM cache renderer: ${p.armRenderer}")
    }

    /** Retain a small working set; prune only unlocked, noncurrent generations. */
    private fun pruneInactiveArmGenerations(p: Prepared) {
        val identity = checkNotNull(p.armGenerationIdentity)
        val generations = checkNotNull(p.prefix.parentFile?.parentFile)
        check(generations.name == ARM_GENERATIONS_DIRECTORY &&
            !Files.isSymbolicLink(generations.toPath())) {
            "ARM graphics generations root is unsafe"
        }
        val inactive = generations.listFiles().orEmpty()
            .filter {
                it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                    ARM_GENERATION_NAME.matches(it.name) && it.name != identity.generationName
            }
            .sortedByDescending { generationLastModified(it) }
        val retiredRoot = File(p.root, ARM_RETIRED_GENERATIONS_DIRECTORY)
        for (candidate in inactive.drop(MAX_RETAINED_INACTIVE_ARM_GENERATIONS)) {
            check(candidate.canonicalFile.parentFile == generations.canonicalFile) {
                "refusing to prune an ARM generation outside its owner root"
            }
            requirePlainDirectory(retiredRoot)
            val target = File(
                retiredRoot,
                "${candidate.name}-pruned-${System.currentTimeMillis()}-${System.nanoTime()}",
            )
            if (ArmGraphicsGenerationLease.retireIfInactive(candidate, target)) {
                pruneRetiredArmGenerations(retiredRoot)
            }
        }
    }

    private fun generationLastModified(root: File): Long =
        File(root, ARM_PREFIX_MANIFEST_FILE).takeIf { it.isFile }?.lastModified()
            ?: root.lastModified()

    private fun pruneRetiredArmGenerations(retiredRoot: File) {
        val root = retiredRoot.canonicalFile
        retiredRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RETAINED_RETIRED_ARM_GENERATIONS)
            .forEach { retired ->
                check(retired.canonicalFile.parentFile == root) {
                    "refusing to prune retired ARM content outside its owner root"
                }
                deleteTreeNoFollow(retired.toPath())
            }
    }

    private fun deleteTreeNoFollow(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun requirePlainDirectory(directory: File) {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path)
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "app-owned runtime directory is absent or unsafe: ${directory.name}"
        }
    }

    /** One-way storage migration for runtime generations that can no longer launch. */
    private fun retireRemovedArmRuntimeState() {
        val armRoot = File(context.noBackupFilesDir, "arm-translated").canonicalFile
        ArmRuntimeRetirement.retireFexGeneration(armRoot)
        val retired = listOf(
            File(armRoot, "winlator-ca3d735/prefixes/opengl"),
            File(armRoot, "winlator-ca3d735/cache/opengl"),
        )
        for (target in retired) {
            check(target.canonicalFile.toPath().startsWith(armRoot.toPath())) {
                "refusing to retire runtime state outside the ARM root: $target"
            }
            if (target.exists()) check(target.deleteRecursively()) {
                "removed ARM runtime state could not be retired: $target"
            }
        }
    }

    private fun ensureBox64RendererPrefix(p: Prepared) {
        if (prefixReady(p.prefix, 1_000)) return
        val base = File(p.tree, "home/xuser/.wine")
        check(prefixReady(base, 1_000)) { "Box64 base prefix is incomplete" }
        p.prefix.parentFile!!.mkdirs()
        if (p.prefix.exists()) check(p.prefix.deleteRecursively()) {
            "incomplete Box64 renderer prefix could not be replaced"
        }
        runCheckedProcess(
            listOf("/system/bin/cp", "-a", base.absolutePath, p.prefix.absolutePath),
            p.root,
            timeoutSeconds = 300,
        )
        check(prefixReady(p.prefix, 1_000)) { "Box64 renderer prefix copy is incomplete" }
    }

    private fun runCheckedProcess(
        command: List<String>,
        workingDirectory: File,
        timeoutSeconds: Long,
        environment: Map<String, String> = emptyMap(),
    ) {
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "runtime preparation command timed out: ${command.first()}"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.takeLast(2_000)
        check(process.exitValue() == 0) {
            "runtime preparation command failed (${process.exitValue()}): $output"
        }
    }

    /** Install the pinned Adreno Vulkan ICD and the matching DXVK D3D9 DLLs.
     *
     * The signed APK is the immutable source. Each destination is replaced
     * atomically and verified after publication, including when it replaces a
     * Wine builtin symlink in an already-created prefix.
     */
    private fun installPinnedArmGraphics(
        p: Prepared,
        rendererPackage: RendererPackage,
        vulkanDriver: VulkanDriverPackage,
    ) {
        check(rendererPackage.translator == ArmTranslationBackend.BOX64) {
            "Box64 graphics installer received ${rendererPackage.translator.id} package"
        }
        val system32Asset = requireNotNull(rendererPackage.system32Asset) {
            "renderer package lacks the system32 D3D9 asset"
        }
        val system32Sha256 = requireNotNull(rendererPackage.system32Sha256)
        val syswow64Asset = requireNotNull(rendererPackage.syswow64Asset) {
            "renderer package lacks the syswow64 D3D9 asset"
        }
        val syswow64Sha256 = requireNotNull(rendererPackage.syswow64Sha256)
        val knownDriverFiles = listOf(
            File(p.tree, "usr/lib/libvulkan_vortek.so"),
            File(p.tree, "usr/lib/libvulkan_freedreno.so"),
            File(p.tree, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json"),
            File(p.tree, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"),
        )
        val selectedDriverFiles = setOf(vulkanDriver.libraryName, vulkanDriver.icdFileName)
        knownDriverFiles.filterNot { it.name in selectedDriverFiles }.forEach { stale ->
            if (Files.exists(stale.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                check(stale.delete()) { "stale Vulkan driver asset could not be retired: ${stale.name}" }
            }
        }
        val files = listOf(
            PinnedAsset(
                vulkanDriver.libraryAsset,
                File(p.tree, "usr/lib/${vulkanDriver.libraryName}"),
                vulkanDriver.librarySha256,
                executable = true,
            ),
            PinnedAsset(
                vulkanDriver.icdAsset,
                File(p.tree, "usr/share/vulkan/icd.d/${vulkanDriver.icdFileName}"),
                vulkanDriver.icdSha256,
            ),
            PinnedAsset(
                system32Asset,
                File(p.prefix, "drive_c/windows/system32/d3d9.dll"),
                system32Sha256,
            ),
            PinnedAsset(
                syswow64Asset,
                File(p.prefix, "drive_c/windows/syswow64/d3d9.dll"),
                syswow64Sha256,
            ),
        )
        files.forEach(::installPinnedAsset)
    }

    /** Install Gladio only inside the selected graphics generation.
     * The shared Winlator rootfs is never used as a payload destination. */
    private fun installPinnedArmGladio(p: Prepared) {
        check(p.armRenderer == "opengl" && p.armRendererPackageId == null &&
            p.armVulkanDriverId == null) {
            "Gladio installer received a non-OpenGL graphics identity"
        }
        val generationRoot = checkNotNull(p.prefix.parentFile).canonicalFile
        val gladioDirectory = File(generationRoot, GLADIO_PAYLOAD_DIRECTORY)
        requirePlainDirectory(File(generationRoot, "graphics"))
        requirePlainDirectory(gladioDirectory)
        check(gladioDirectory.canonicalFile.toPath().startsWith(generationRoot.toPath())) {
            "Gladio payload escaped its tuple generation"
        }
        val library = File(gladioDirectory, GLADIO_CLIENT_FILE_NAME)
        installPinnedAsset(PinnedAsset(
            ArmClientRendererCatalog.GLADIO_CLIENT_ASSET,
            library,
            ArmClientRendererCatalog.GLADIO_CLIENT_SHA256,
            executable = true,
        ))
        for (aliasName in listOf("libGL.so", "libGL.so.1")) {
            val alias = File(gladioDirectory, aliasName).toPath()
            if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isSymbolicLink(alias) &&
                    Files.readSymbolicLink(alias) == Paths.get(GLADIO_CLIENT_FILE_NAME)) {
                    "Gladio library alias is unsafe: $aliasName"
                }
            } else {
                Files.createSymbolicLink(alias, Paths.get(GLADIO_CLIENT_FILE_NAME))
            }
        }
    }

    /** Install the matched Mesa virpipe guest only inside its renderer generation. */
    private fun installPinnedArmVirgl(p: Prepared) {
        check(p.armRenderer == "virgl" && p.armRendererPackageId == null &&
            p.armVulkanDriverId == null) {
            "VirGL installer received a Vulkan/DXVK graphics identity"
        }
        val generationRoot = checkNotNull(p.prefix.parentFile).canonicalFile
        val virglDirectory = File(generationRoot, VIRGL_PAYLOAD_DIRECTORY)
        requirePlainDirectory(File(generationRoot, "graphics"))
        requirePlainDirectory(virglDirectory)
        check(virglDirectory.canonicalFile.toPath().startsWith(generationRoot.toPath())) {
            "VirGL payload escaped its tuple generation"
        }
        val library = File(virglDirectory, VIRGL_CLIENT_FILE_NAME)
        installPinnedAsset(PinnedAsset(
            ArmClientRendererCatalog.VIRGL_CLIENT_ASSET,
            library,
            ArmClientRendererCatalog.VIRGL_CLIENT_SHA256,
            executable = true,
        ))
        for (aliasName in listOf("libGL.so", "libGL.so.1")) {
            val alias = File(virglDirectory, aliasName).toPath()
            if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
                check(Files.isSymbolicLink(alias) &&
                    Files.readSymbolicLink(alias) == Paths.get(VIRGL_CLIENT_FILE_NAME)) {
                    "VirGL library alias is unsafe: $aliasName"
                }
            } else {
                Files.createSymbolicLink(alias, Paths.get(VIRGL_CLIENT_FILE_NAME))
            }
        }
    }

    private data class PinnedAsset(
        val assetPath: String,
        val target: File,
        val expectedSha256: String,
        val executable: Boolean = false,
    )

    private fun installPinnedAsset(asset: PinnedAsset) {
        if (asset.target.isFile && sha256(asset.target) == asset.expectedSha256) return
        asset.target.parentFile!!.mkdirs()
        val temporary = File(asset.target.parentFile, ".${asset.target.name}.pocket.tmp")
        if (temporary.exists()) check(temporary.delete()) { "stale runtime-asset staging file could not be removed" }
        context.assets.open(asset.assetPath).use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        temporary.setReadable(true, false)
        if (asset.executable) temporary.setExecutable(true, false)
        check(sha256(temporary) == asset.expectedSha256) {
            "APK-managed runtime asset digest mismatch: ${asset.assetPath}"
        }
        android.system.Os.rename(temporary.absolutePath, asset.target.absolutePath)
        check(sha256(asset.target) == asset.expectedSha256) {
            "installed runtime asset digest mismatch: ${asset.target.name}"
        }
    }

    /** Fail closed on the provider-matched mixed-ABI audio closure. */
    private fun validateArmAudioRuntime(p: Prepared) {
        data class Required(val relative: String, val digest: String, val elfMachine: Int)
        val required = listOf(
            Required("usr/lib/libasound.so.2.0.0", BOX64_LIBASOUND_SHA256, ELF_MACHINE_AARCH64),
            Required("usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so",
                BOX64_ANDROID_ASERVER_SHA256, ELF_MACHINE_AARCH64),
            Required("opt/wine/lib/wine/x86_64-unix/winealsa.so", BOX64_WINEALSA_SHA256,
                ELF_MACHINE_X86_64),
        )
        required.forEach { item ->
            val file = File(p.tree, item.relative)
            check(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                sha256(file) == item.digest && elfMachine(file) == item.elfMachine) {
                "provider-matched audio component is missing or changed: ${item.relative}"
            }
        }
        val config = File(p.tree, "usr/share/alsa/alsa.conf")
        check(config.isFile) { "provider ALSA configuration is missing" }
        val text = config.readText(Charsets.UTF_8)
        check(!text.contains("/data/data/com.winlator/files/rootfs")) {
            "Box64 ALSA configuration still targets the Winlator package"
        }
        check(text.contains("/data/data/com.pocketrealm/files/rfs")) {
            "Box64 ALSA configuration was not adapted to Pocket Realm"
        }
        check(File(p.tree, "etc/alsa/conf.d/android_aserver.conf").isFile) {
            "Box64 android_aserver routing configuration is missing"
        }
    }

    private fun elfMachine(file: File): Int {
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                check(read > 0) { "truncated ELF header: ${file.name}" }
                offset += read
            }
        }
        check(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4] == 2.toByte() && header[5] == 1.toByte()) {
            "unsupported ELF header: ${file.name}"
        }
        return ByteBuffer.wrap(header, 18, 2).order(ByteOrder.LITTLE_ENDIAN)
            .short.toInt() and 0xffff
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * O23 vanilla-tweaks patch step (A.5). Produces a root-level
     * `WoW.exe.patched` sibling of the pristine managed exe only when its full
     * SHA-256 identifies the byte layout supported by the patch model. A valid
     * imported 1.12.1 build-5875 executable does not need to match that one
     * reference hash when no binary tweak is requested. The upstream patcher
     * output is finalized with the Pocket Realm nearby-loot companion patch
     * and must then match the independent byte-for-byte patch model before
     * publication. Idempotent via the authenticated manifest sidecar;
     * self-tests are never patched.
     */
    private fun applyTweaks(p: Prepared, tweaks: ClientTweaksConfig): Prepared {
        if (p.selfTest) return p
        val pristine = p.executable
        check(pristine.name.equals("WoW.exe", ignoreCase = true)) {
            "managed build-5875 executable has an unexpected name"
        }
        val pristineSha = sha256(pristine)
        val pristineBytes = pristine.readBytes()
        checkPeX86(pristineBytes)
        val resolution = resolveEffectiveClientTweaks(tweaks, pristineSha)
        val effective = resolution.config
        val canonical = effective.toJson()
        val signature = sha256(
            canonical + "\u0001" + VANILLA_TWEAKS_VERSION + "\u0001" + pristineSha,
        )
        if (!effective.hasAnyPatch()) {
            return p.copy(
                tweaksJson = canonical,
                tweaksSignature = signature,
                tweaksFallback = resolution.fallback,
                selectedExecutableSize = pristine.length(),
                selectedExecutableSha256 = pristineSha,
            )
        }
        check(effective.acceptsExecutableForLaunch(pristineSha)) {
            "effective optional client tweaks do not match the leased WoW.exe"
        }
        val expectedBytes = ClientTweaksConfig.expectedPublishedPatchedBytes(pristineBytes, effective)
        val parent = checkNotNull(pristine.parentFile) { "managed client parent is absent" }
        val patched = File(parent, "WoW.exe.patched")
        val manifestFile = File(parent, "WoW.exe.patched.manifest.json")
        val cacheValid = patched.isFile && manifestFile.isFile && runCatching {
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val patchedBytes = patched.readBytes()
            manifest.getInt("schema") == 1 &&
                manifest.getString("signature") == signature &&
                manifest.getString("pristineSha256") == pristineSha &&
                manifest.getString("patchedSha256") == sha256(patched) &&
                patchedBytes.contentEquals(expectedBytes).also {
                    if (it) checkPeX86(patchedBytes)
                }
        }.getOrDefault(false)
        if (cacheValid) {
            return p.copy(
                executable = patched,
                tweaksJson = canonical,
                tweaksSignature = signature,
                tweaksFallback = resolution.fallback,
                selectedExecutableSize = patched.length(),
                selectedExecutableSha256 = sha256(patched),
            )
        }
        patched.delete()
        manifestFile.delete()
        val temporary = File(parent, ".WoW.exe.patched.${android.os.Process.myPid()}.tmp")
        temporary.delete()
        try {
            runPatcher(pristine, temporary, parent, effective.toFlags())
            // Companion patch owned by Pocket Realm (upstream patcher output is
            // verified against it together with the vanilla-tweaks model below).
            val actualBytes = ClientTweaksConfig.applyNearbyLootAcceptPatch(temporary.readBytes())
            RandomAccessFile(temporary, "rw").use {
                it.write(actualBytes)
                it.fd.sync()
            }
            check(actualBytes.size == pristineBytes.size) { "patched executable size changed" }
            checkPeX86(actualBytes)
            check(actualBytes.contentEquals(expectedBytes)) {
                "native tweak output differs from the authorized patch model"
            }
            val patchedSha = sha256(temporary)
            android.system.Os.rename(temporary.absolutePath, patched.absolutePath)
            writeAtomic(
                manifestFile,
                JSONObject()
                    .put("schema", 1)
                    .put("signature", signature)
                    .put("pristineSha256", pristineSha)
                    .put("patchedSha256", patchedSha)
                    .put("nearbyLootAccept", true)
                    .put("config", JSONObject(canonical))
                    .toString(),
            )
        } finally {
            temporary.delete()
        }
        return p.copy(
            executable = patched,
            tweaksJson = canonical,
            tweaksSignature = signature,
            tweaksFallback = resolution.fallback,
            selectedExecutableSize = patched.length(),
            selectedExecutableSha256 = sha256(patched),
        )
    }

    /** Re-attest the exact prepared generation and executable immediately before spawn. */
    fun attestForLaunch(p: Prepared) {
        val executable = p.executable.canonicalFile
        check(executable.isFile && !Files.isSymbolicLink(p.executable.toPath())) {
            "prepared client executable is absent or unsafe"
        }
        if (p.selfTest) {
            checkPeX86(executable.readBytes())
            return
        }

        val managed = checkNotNull(p.managedClient) { "prepared managed-client identity is absent" }
        val lease = checkNotNull(p.clientLease) { "prepared managed-client lease is absent" }
        ManagedClientStore(context).attestUnderLease(managed, lease)
        check(executable.toPath().startsWith(managed.root.canonicalFile.toPath()) &&
            executable.parentFile == managed.root.canonicalFile) {
            "prepared executable escaped its leased client generation"
        }
        check(executable.length() == p.selectedExecutableSize &&
            sha256(executable) == p.selectedExecutableSha256) {
            "prepared executable changed after validation"
        }
        checkPeX86(executable.readBytes())

        val config = ClientTweaksConfig.fromControlJson(p.tweaksJson)
        if (config.hasAnyPatch()) {
            check(executable.name == "WoW.exe.patched") { "patched executable identity mismatch" }
            check(managed.executableSha256 == ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256) {
                "patched executable pristine identity mismatch"
            }
            val sidecar = File(managed.root, "WoW.exe.patched.manifest.json")
            check(sidecar.isFile && !Files.isSymbolicLink(sidecar.toPath())) {
                "patched executable manifest is absent or unsafe"
            }
            val manifest = JSONObject(sidecar.readText(Charsets.UTF_8))
            val manifestConfig = ClientTweaksConfig.fromControlJson(
                manifest.getJSONObject("config").toString(),
            )
            check(manifest.getInt("schema") == 1 &&
                manifest.getString("signature") == p.tweaksSignature &&
                manifest.getString("pristineSha256") == managed.executableSha256 &&
                manifest.getString("patchedSha256") == p.selectedExecutableSha256 &&
                manifestConfig == config) {
                "patched executable manifest changed after preparation"
            }
        } else {
            check(executable == managed.executable.canonicalFile) {
                "vanilla launch must use the pristine managed executable"
            }
        }

        val endpointFile = File(managed.root, "realmlist.wtf")
        check(endpointFile.isFile && !Files.isSymbolicLink(endpointFile.toPath()) &&
            endpointFile.readText().trim() == "set realmlist ${p.realmEndpoint.address}") {
            "prepared realm endpoint changed after preparation"
        }

        val displayProfile = ClientDisplayProfile.requireId(p.displayProfileId)
        ClientFrameCap.requireFps(p.frameCap)

        // The prepared Config is attested against the exact bytes this
        // prepare's merge wrote (captured in Prepared), not a recomputed
        // template — under the merge model the base file varies per install,
        // and byte-capture is equivalent fail-closed strength.
        val expectedConfig = p.managedConfigText
        if (expectedConfig != null) {
            val configFile = File(managed.root, "WTF/Config.wtf")
            check(configFile.isFile && !Files.isSymbolicLink(configFile.toPath()) &&
                configFile.readText(Charsets.UTF_8) == expectedConfig) {
                "prepared WoW display/audio configuration changed after preparation"
            }
        }

        if (p.armRenderer != null) {
            val identity = checkNotNull(p.armGenerationIdentity) {
                "prepared ARM graphics generation identity is absent"
            }
            val generationLease = checkNotNull(p.armGenerationLease) {
                "prepared ARM graphics generation lease is absent"
            }
            check(generationLease.isHeld) { "prepared ARM graphics generation is no longer leased" }
            val generationRoot = checkNotNull(p.prefix.parentFile)
            check(generationRoot.name == identity.generationName &&
                p.prefix.name == ARM_PREFIX_DIRECTORY &&
                p.cache.name == ARM_CACHE_DIRECTORY &&
                p.cache.parentFile?.canonicalFile == generationRoot.canonicalFile) {
                "prepared ARM prefix/cache generation identity changed after preparation"
            }
            attestArmCacheDirectories(p)
            val manifestFile = File(p.prefix.parentFile, ARM_PREFIX_MANIFEST_FILE)
            check(manifestFile.isFile && !Files.isSymbolicLink(manifestFile.toPath())) {
                "prepared ARM prefix manifest is absent or unsafe"
            }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val cacheLayout = manifest.getJSONObject("cache_layout")
            check(manifest.getInt("manifest_schema") == ARM_PREFIX_MANIFEST_SCHEMA &&
                manifest.getString("generation") == identity.generationName &&
                identity.matchesManifest(manifest) &&
                manifest.getString("display_profile") == p.displayProfileId &&
                manifest.getString("resolution") == resolveVirtualDisplay(displayProfile).resolution &&
                manifest.getInt("fps_cap") == p.frameCap) {
                "prepared graphics/display manifest changed after preparation"
            }
            when (p.armRenderer) {
                "dxvk" -> {
                    val rendererPackage = requireNotNull(
                        RendererPackageCatalog.find(p.armRendererPackageId),
                    ) { "prepared renderer package is unavailable" }
                    val driver = requireNotNull(VulkanDriverCatalog.find(p.armVulkanDriverId)) {
                        "prepared Vulkan driver package is unavailable"
                    }
                    val pinned = listOf(
                        Triple(File(p.tree, "usr/lib/${driver.libraryName}"),
                            driver.librarySha256, "Vulkan driver library"),
                        Triple(File(p.tree, "usr/share/vulkan/icd.d/${driver.icdFileName}"),
                            driver.icdSha256, "Vulkan driver manifest"),
                        Triple(File(p.prefix, "drive_c/windows/system32/d3d9.dll"),
                            requireNotNull(rendererPackage.system32Sha256), "DXVK system32 D3D9"),
                        Triple(File(p.prefix, "drive_c/windows/syswow64/d3d9.dll"),
                            requireNotNull(rendererPackage.syswow64Sha256), "DXVK syswow64 D3D9"),
                    )
                    pinned.forEach { (file, digest, label) ->
                        check(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                            sha256(file) == digest) { "$label changed after preparation" }
                    }
                    val dxvkConfig = File(p.cache, ClientRuntimeContract.DXVK_CONFIG_FILE_NAME)
                    check(dxvkConfig.isFile && !Files.isSymbolicLink(dxvkConfig.toPath()) &&
                        dxvkConfig.readText(Charsets.UTF_8) ==
                            ClientRuntimeContract.dxvkFrameCapConfig(p.frameCap)) {
                        "prepared DXVK frame limiter changed after preparation"
                    }
                    check(manifest.getString("renderer_package_id") == rendererPackage.id &&
                        manifest.getString("vulkan_driver_id") == driver.id &&
                        cacheLayout.length() == 3 &&
                        cacheLayout.getString("dxvk_state") == "$ARM_CACHE_DIRECTORY/dxvk" &&
                        cacheLayout.getString("mesa_shader") == "$ARM_CACHE_DIRECTORY/mesa" &&
                        cacheLayout.getString("xdg") == "$ARM_CACHE_DIRECTORY/xdg") {
                        "prepared DXVK cache manifest changed after preparation"
                    }
                }
                "opengl" -> {
                    check(p.armRendererPackageId == null && p.armVulkanDriverId == null) {
                        "prepared Gladio launch carries Vulkan/DXVK identities"
                    }
                    val gladioDirectory = File(generationRoot, GLADIO_PAYLOAD_DIRECTORY)
                    val library = File(gladioDirectory, GLADIO_CLIENT_FILE_NAME)
                    check(library.isFile && !Files.isSymbolicLink(library.toPath()) &&
                        sha256(library) == ArmClientRendererCatalog.GLADIO_CLIENT_SHA256) {
                        "generation-local Gladio client changed after preparation"
                    }
                    for (aliasName in listOf("libGL.so", "libGL.so.1")) {
                        val alias = File(gladioDirectory, aliasName).toPath()
                        check(Files.isSymbolicLink(alias) &&
                            Files.readSymbolicLink(alias) == Paths.get(GLADIO_CLIENT_FILE_NAME)) {
                            "generation-local Gladio alias changed: $aliasName"
                        }
                    }
                    check(manifest.getString("gladio_package_id") ==
                        ArmClientRendererCatalog.GLADIO_PACKAGE_ID &&
                        manifest.getString("gladio_client_sha256") ==
                            ArmClientRendererCatalog.GLADIO_CLIENT_SHA256 &&
                        manifest.getString("gladio_server_build_id") ==
                            ArmClientRendererCatalog.GLADIO_SERVER_BUILD_ID &&
                        cacheLayout.length() == 3 &&
                        cacheLayout.getString("gladio") == "$ARM_CACHE_DIRECTORY/gladio" &&
                        cacheLayout.getString("wine") == "$ARM_CACHE_DIRECTORY/wine" &&
                        cacheLayout.getString("xdg") == "$ARM_CACHE_DIRECTORY/xdg") {
                        "prepared Gladio payload/cache manifest changed after preparation"
                    }
                }
                "virgl" -> {
                    check(p.armRendererPackageId == null && p.armVulkanDriverId == null) {
                        "prepared VirGL launch carries Vulkan/DXVK identities"
                    }
                    val virglDirectory = File(generationRoot, VIRGL_PAYLOAD_DIRECTORY)
                    val library = File(virglDirectory, VIRGL_CLIENT_FILE_NAME)
                    check(library.isFile && !Files.isSymbolicLink(library.toPath()) &&
                        sha256(library) == ArmClientRendererCatalog.VIRGL_CLIENT_SHA256) {
                        "generation-local VirGL client changed after preparation"
                    }
                    for (aliasName in listOf("libGL.so", "libGL.so.1")) {
                        val alias = File(virglDirectory, aliasName).toPath()
                        check(Files.isSymbolicLink(alias) &&
                            Files.readSymbolicLink(alias) == Paths.get(VIRGL_CLIENT_FILE_NAME)) {
                            "generation-local VirGL alias changed: $aliasName"
                        }
                    }
                    check(manifest.getString("virgl_package_id") ==
                        ArmClientRendererCatalog.VIRGL_PACKAGE_ID &&
                        manifest.getString("virgl_client_sha256") ==
                            ArmClientRendererCatalog.VIRGL_CLIENT_SHA256 &&
                        manifest.getString("virgl_server_build_id") ==
                            ArmClientRendererCatalog.VIRGL_SERVER_BUILD_ID &&
                        manifest.getString("virgl_environment_id") ==
                            ArmClientRendererCatalog.VIRGL_ENVIRONMENT_ID &&
                        cacheLayout.length() == 2 &&
                        cacheLayout.getString("mesa_shader") == "$ARM_CACHE_DIRECTORY/virgl" &&
                        cacheLayout.getString("xdg") == "$ARM_CACHE_DIRECTORY/xdg") {
                        "prepared VirGL payload/cache manifest changed after preparation"
                    }
                }
                else -> error("unsupported prepared ARM renderer: ${p.armRenderer}")
            }
        }
    }

    private fun runPatcher(pristine: File, patched: File, workingDir: File, flags: List<String>) {
        val binary = File(context.applicationInfo.nativeLibraryDir, PATCHER_LIB)
        check(binary.isFile && binary.canExecute()) { "vanilla-tweaks patcher is absent from nativeLibraryDir" }
        val cmd = buildList {
            add(binary.absolutePath)
            add(pristine.absolutePath)
            add("-o"); add(patched.absolutePath)
            addAll(flags)
        }
        runCheckedProcess(cmd, workingDir, PATCHER_TIMEOUT_SECONDS)
    }

    private fun checkPeX86(bytes: ByteArray) {
        require(ClientTweaksConfig.peMagicOk(bytes)) { "client executable MZ header is invalid" }
        val peOffset = ByteBuffer.wrap(bytes, 0x3c, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(peOffset >= 0 && peOffset + 6 <= bytes.size) { "client PE header is out of range" }
        require(bytes.copyOfRange(peOffset, peOffset + 4).contentEquals(
            byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 0, 0),
        )) { "client PE signature is invalid" }
        val machine = ByteBuffer.wrap(bytes, peOffset + 4, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        require(machine == 0x014c) { "client executable is not PE32 x86" }
    }

    companion object {
        private const val TAG = "WineRuntimeStore"
        private const val ARM_PREFIX_MANIFEST_SCHEMA = 2
        private const val ARM_GENERATIONS_DIRECTORY = "generations"
        private const val ARM_RETIRED_GENERATIONS_DIRECTORY = "retired-generations"
        private const val ARM_PREFIX_DIRECTORY = "wine-prefix"
        private const val ARM_CACHE_DIRECTORY = "cache"
        private const val ARM_PREFIX_MANIFEST_FILE = "prefix-manifest.json"
        private const val MAX_RETAINED_INACTIVE_ARM_GENERATIONS = 3
        private const val MAX_RETAINED_RETIRED_ARM_GENERATIONS = 1
        internal const val GLADIO_PAYLOAD_DIRECTORY = "graphics/gladio"
        internal const val GLADIO_CLIENT_FILE_NAME = "libGL.so.1.7.0"
        internal const val VIRGL_PAYLOAD_DIRECTORY = "graphics/virgl"
        internal const val VIRGL_CLIENT_FILE_NAME = "libGL.so.1.7.0"
        private val ARM_GENERATION_NAME = Regex("g-[0-9a-f]{32}")
        private const val VANILLA_TWEAKS_VERSION = "1.6.0"
        private const val PATCHER_LIB = "libpocket_vanilla_tweaks.so"
        private const val PATCHER_TIMEOUT_SECONDS = 120L
        private const val BOX64_LIBASOUND_SHA256 =
            "593ff5247c19882402b67f6472711791646ffaec5a4764b061f1eacb999ca3b3"
        private const val BOX64_ANDROID_ASERVER_SHA256 =
            "209927b86066863fbe4f3607273577d4af1534036d3b5b59f87b882b15f3346c"
        private const val BOX64_WINEALSA_SHA256 =
            "11b0c5cc03dfbbad0370b08264dd480b9927a1ef87e3d642c38046d098579b61"
        private const val ELF_MACHINE_X86_64 = 0x003e
        private const val ELF_MACHINE_AARCH64 = 0x00b7
    }

    private fun linkArmBuiltins(p: Prepared) {
        val pairs = listOf(
            File(p.tree, "opt/wine/lib/wine/x86_64-windows") to
                File(p.prefix, "drive_c/windows/system32"),
            File(p.tree, "opt/wine/lib/wine/i386-windows") to
                File(p.prefix, "drive_c/windows/syswow64"),
        )
        for ((source, destination) in pairs) {
            check(source.isDirectory) { "Wine builtin source is missing: ${source.name}" }
            destination.mkdirs()
            for (file in source.listFiles().orEmpty().filter { it.isFile }) {
                val target = File(destination, file.name).toPath()
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(target, file.toPath())
                }
            }
        }
        for (name in ClientRuntimeContract.ARM_REQUIRED_WINE_GUEST_DLLS) {
            check(File(p.prefix, "drive_c/windows/syswow64/$name").isFile) {
                "32-bit Wine system dependency was not linked: $name"
            }
        }
    }

    private fun installArmRuntimeAliases(rootfs: File) {
        val aliases = listOf(
            File(context.filesDir, "rfs").toPath() to rootfs.toPath(),
            File(context.filesDir, "ld").toPath() to
                File(rootfs, "usr/lib/ld-linux-aarch64.so.1").toPath(),
        )
        for ((alias, target) in aliases) {
            if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(alias) && Files.readSymbolicLink(alias) == target) continue
                error("ARM runtime alias collision: $alias")
            }
            Files.createSymbolicLink(alias, target)
        }
    }

    /** The pinned Winlator payload embeds its original package root in a
     * bounded set of config/ELF strings. The replacement is exactly the same
     * byte length, so no ELF offsets or cache records move. */
    private fun patchWinlatorPackagePaths(rootfs: File) {
        val old = "/data/data/com.winlator/files/rootfs".toByteArray()
        val replacement = "/data/data/com.pocketrealm/files/rfs".toByteArray()
        check(old.size == replacement.size)
        val relativeFiles = listOf(
            "etc/fonts/fonts.conf", "etc/fonts/conf.d/README", "etc/ld.so.cache",
            "etc/pulse/client.conf", "bin/localedef", "bin/locale", "var/db/Makefile",
            "usr/share/alsa/alsa.conf",
            "usr/lib/libX11.so.6.4.0", "usr/lib/libxcb.so.1.1.0",
            "opt/wine/bin/wineserver", "opt/wine/lib/wine/x86_64-unix/nsiproxy.so",
            "opt/wine/lib/wine/x86_64-unix/ntdll.so",
        )
        var requiredPatched = 0
        for (relative in relativeFiles) {
            val file = File(rootfs, relative)
            if (!file.isFile) continue
            val bytes = file.readBytes()
            var offset = 0
            var changed = false
            while (offset <= bytes.size - old.size) {
                var match = true
                for (index in old.indices) {
                    if (bytes[offset + index] != old[index]) { match = false; break }
                }
                if (match) {
                    replacement.copyInto(bytes, offset)
                    offset += replacement.size
                    changed = true
                } else offset++
            }
            if (changed) {
                val executable = file.canExecute()
                val temporary = File(file.parentFile, ".${file.name}.pocket-path.tmp")
                temporary.writeBytes(bytes)
                temporary.setReadable(true, true)
                temporary.setWritable(true, true)
                if (executable) temporary.setExecutable(true, true)
                android.system.Os.rename(temporary.absolutePath, file.absolutePath)
            }
            if (relative == "usr/lib/libX11.so.6.4.0" ||
                relative == "usr/lib/libxcb.so.1.1.0" ||
                relative == "usr/share/alsa/alsa.conf" ||
                relative == "opt/wine/bin/wineserver" ||
                relative == "opt/wine/lib/wine/x86_64-unix/ntdll.so") {
                val remaining = containsBytes(file.readBytes(), old)
                check(!remaining) { "Winlator package root remains in $relative" }
                requiredPatched++
            }
        }
        check(requiredPatched == 5) { "required Wine/X11/ALSA package-path adaptations are absent" }
    }

    private fun containsBytes(value: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || value.size < needle.size) return false
        for (offset in 0..value.size - needle.size) {
            var match = true
            for (index in needle.indices) {
                if (value[offset + index] != needle[index]) { match = false; break }
            }
            if (match) return true
        }
        return false
    }

    private fun initializePrefix(p: Prepared) {
        val wineboot = File(p.tree, "lib/wine/x86_64-windows/wineboot.exe")
        check(wineboot.isFile) { "wineboot.exe is not cache-backed" }
        val raw = WineSpikeNative.runWineDirectNative(
            context.applicationInfo.nativeLibraryDir, wineboot.absolutePath,
            p.prefix.absolutePath, p.prefix.absolutePath, "", "--init",
            "LD_DEBUG=;WINEDEBUG=-all;WINEDLLOVERRIDES=winex11.drv=d",
            60_000,
        )
        val result = parseWineRunResult(raw)
        check(result.rc == 0 && result.exitedCleanly && result.exitCode == 0 && !result.timedOut) {
            "wineboot failed: rc=${result.rc} exit=${result.exitCode} timeout=${result.timedOut} " +
                result.stderr.takeLast(800)
        }
        check(prefixReady(p.prefix)) { "wineboot registry transaction did not stabilize" }
        val wineserver = File(p.tree, "bin/wineserver")
        for (arg in listOf("-k", "-w")) {
            val serverResult = parseWineRunResult(WineSpikeNative.runWineViaProotNative(
                context.applicationInfo.nativeLibraryDir, wineserver.absolutePath, "wineserver",
                p.prefix.absolutePath, "", arg, "", 15_000,
            ))
            check(serverResult.exitedCleanly && serverResult.exitCode == 0 && !serverResult.timedOut) {
                "wineserver $arg failed"
            }
        }
        linkBuiltins(p)
    }

    /**
     * Panel-resolved desktop for a profile. Deterministic for one device, so
     * the launch-side Config.wtf, the safe-profile record, and the later
     * integrity checks all agree without threading the geometry around.
     */
    private fun resolveVirtualDisplay(profile: ClientDisplayProfile): ClientVirtualDisplay =
        ClientDisplayCapabilities.physicalLandscapeBounds(context)
            .let { (width, height) -> profile.resolveFor(width, height) }

    private fun enforceManagedSafeMode(
        p: Prepared,
        renderer: String,
        displayProfile: ClientDisplayProfile,
        inputSafeMode: Boolean,
        audioMode: String,
        realmEndpoint: RealmEndpoint,
    ): Prepared {
        check(p.clientId == ClientRuntimeContract.WOW_5875_ID) { "safe profile target mismatch" }
        require(renderer == "dxvk" || renderer == "opengl" || renderer == "virgl" ||
            renderer == "wined3d") {
            "unsupported managed renderer: $renderer"
        }
        // [p] is the value returned by applyTweaks. Its canonical tweaks JSON
        // is launch-effective and may intentionally differ from the user's
        // persisted request when the imported executable is unqualified.
        val effectiveTweaks = ClientTweaksConfig.fromControlJson(p.tweaksJson)
        val resolution = resolveVirtualDisplay(displayProfile).resolution
        val workingDir = p.workingDir
        val configFile = File(workingDir, "WTF/Config.wtf")
        val recordFile = File(workingDir, "managed-safe-profile.json")

        // Read side: the user's staged overrides (UI process is the queue's
        // only writer; this process reads, never writes) and the previous
        // launch's delivery record, consumed before it is overwritten.
        val settings = runBlocking { com.pocketrealm.storage.Settings(context).flow.first() }
        val previousRecord = runCatching {
            JSONObject(recordFile.readText(Charsets.UTF_8))
        }.getOrNull()
        val previousAudio = previousRecord?.optString("audio")?.takeIf { it.isNotEmpty() }
        val previousPreparedAtRevision = previousRecord?.optLong("preparedAtRevision") ?: 0L
        val previousDelivered = GameSettingsDeliveryEntry.listFromJson(
            previousRecord?.opt("applied_overrides"),
        )

        val enforced = ManagedConfigPolicy.enforcedKeys(
            ManagedConfigPolicy.LaunchConditions(
                renderer = renderer,
                resolution = resolution,
                gameMaximized = displayProfile.gameMaximized,
                frameCap = p.frameCap,
                audioMode = audioMode,
                realmLoopback = realmEndpoint.isLoopback,
                soundChannelsEnabled = effectiveTweaks.soundChannelsEnabled,
                soundChannels = effectiveTweaks.soundChannels,
            ),
        ).toMutableList()
        ManagedConfigPolicy.masterSoundTransitionDelete(
            previousAudioMode = previousAudio,
            currentAudioMode = audioMode,
            previousPreparedAtRevision = previousPreparedAtRevision,
            directEditRevisions = settings.gameSettingsDirectEditRevisions,
        )?.let { enforced += it }

        val plan = GameSettingsDeliveryPlanner.plan(
            config = settings.gameSettings,
            // Every enforced key — written or deleted — is off-limits to
            // queued overrides this launch; the merge would skip them anyway.
            enforcedCvarKeys = enforced.map { it.key }.toSet(),
            uvarScopeExists = { scope ->
                InGameSettingsFiles.accountSavedVariables(workingDir, scope).isFile
            },
            bindingScopeExists = { scope ->
                InGameSettingsFiles.bindingsForScope(workingDir, scope).isFile
            },
            previousDelivered = previousDelivered,
        )

        val delivered = mutableListOf<GameSettingsDeliveryEntry>()
        val configText: String
        InGameSettingsEditLock.acquire(stableClientRoot()).use {
            ClientRealmEndpointProjection.project(
                File(workingDir, "realmlist.wtf"), realmEndpoint,
            )
            File(workingDir, "WTF").mkdirs()
            val base = if (configFile.isFile) configFile.readText(Charsets.UTF_8) else null
            val merged = ConfigWtfCodec.merge(base, enforced, plan.cvarWrites)
            configText = merged.text
            writeAtomic(configFile, configText)
            delivered += plan.delivered.filter {
                it.scope == WowGameSettingsConfig.SCOPE_CONFIG
            }
            // uvar deliveries: only scalar assignments in files that exist.
            plan.uvarWrites.forEach { (scope, assignments) ->
                val file = InGameSettingsFiles.accountSavedVariables(workingDir, scope)
                if (!file.isFile) return@forEach
                val text = file.readText(Charsets.UTF_8)
                var next = text
                val appliedNames = mutableSetOf<String>()
                assignments.forEach { (name, value) ->
                    val updated = SavedVariablesCodec.assign(
                        next, name, value,
                        numberForm = WowVanillaSettingsCatalog.byKey(name)
                            ?.uvarValueForm == WowUvarValueForm.NUMBER,
                    )
                    if (updated != null) {
                        next = updated
                        appliedNames += name
                    }
                }
                if (next != text) writeAtomic(file, next)
                delivered += plan.delivered.filter { entry ->
                    entry.scope == scope &&
                        WowVanillaSettingsCatalog.byId(entry.key)?.key in appliedNames
                }
            }
            // binding deliveries: enforce the staged two-slot state per command.
            plan.bindingWrites.forEach { (scope, assignments) ->
                val file = InGameSettingsFiles.bindingsForScope(workingDir, scope)
                if (!file.isFile) return@forEach
                val text = file.readText(Charsets.UTF_8)
                var next = text
                assignments.forEach { assignment ->
                    BindingsFileCodec.keysForCommand(next, assignment.command).forEach { old ->
                        next = BindingsFileCodec.assign(next, old, null)
                    }
                    listOfNotNull(assignment.primary, assignment.secondary).forEach { key ->
                        next = BindingsFileCodec.assign(next, key, assignment.command)
                    }
                }
                if (next != text) writeAtomic(file, next)
                delivered += plan.delivered.filter { entry -> entry.scope == scope }
            }
        }
        val activeAddons = AddonRuntimeProjector(context).project(workingDir, inputSafeMode)
        val carriedForward = GameSettingsDeliveryPlanner.carryForward(
            previousDelivered,
            delivered.distinct(),
            settings.gameSettings,
        )
        val record = JSONObject()
            .put("schema", 1).put("client_id", p.clientId)
            .put("renderer", renderer).put("resolution", resolution)
            .put("fps_cap", p.frameCap).put("audio", audioMode)
            .put("game_windowed", true).put("game_maximized", displayProfile.gameMaximized)
            .put("realm_endpoint", realmEndpoint.address)
            .put("realm_name", if (realmEndpoint.isLoopback) "MaNGOS" else JSONObject.NULL)
            .put("addon_safe_mode", inputSafeMode)
            .put("addon_folders", JSONArray(activeAddons))
            .put("passwords_stored", false).put("source_modified", false)
            .put("preparedAtRevision", settings.gameSettingsRevision)
            .put("applied_overrides", GameSettingsDeliveryEntry.listToJson(carriedForward))
            .put("config_sha256", sha256Text(configText))
        writeAtomic(recordFile, record.toString(2))
        return p.copy(managedConfigText = configText)
    }

    private fun sha256Text(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun stableClientRoot(): File = File(context.noBackupFilesDir, "client")

    private fun materializePeCaches(p: Prepared) {
        val extracted = File(context.cacheDir, "client-runtime-assets")
        if (extracted.exists()) extracted.deleteRecursively()
        extracted.mkdirs()
        extract("wine-pe", File(extracted, "wine-pe"))
        extract("guest-pe", File(extracted, "guest-pe"))
        copyAsset("wine-pe-manifest.json", File(extracted, "wine-pe-manifest.json"))
        copyAsset("guest-pe-manifest.json", File(extracted, "guest-pe-manifest.json"))
        val peManifest = readAsset("wine-pe-manifest.json")
        check(WineSpikeNative.materializePeCacheIntoTreeNative(
            p.cache.absolutePath, peManifest, extracted.absolutePath, p.tree.absolutePath) == 0)
        check(WineSpikeNative.verifyPeCacheNative(p.cache.absolutePath, peManifest) == 0)
        val guestManifest = readAsset("guest-pe-manifest.json")
        check(WineSpikeNative.materializePeCacheIntoTreeNative(
            p.cache.absolutePath, guestManifest, extracted.absolutePath, p.tree.absolutePath) == 0)
        check(WineSpikeNative.verifyPeCacheNative(p.cache.absolutePath, guestManifest) == 0)
        // Extraction is transient input to the canonical hash-verified cache.
        // Keeping it would duplicate ~600 MiB in cacheDir without an owner.
        extracted.deleteRecursively()
    }

    /** PE/data caches are immutable and keyed by RUNTIME_BUILD_ID, not by the
     * proprietary client. Prefixes stay isolated; only reproducible caches are
     * shared. This also migrates the legacy O06 per-selftest cache layout. */
    private fun ensureSharedCaches(p: Prepared) {
        val generationRoot = File(context.noBackupFilesDir, "wine/w11w64-v1")
        val sharedRoot = File(generationRoot, "shared").apply { mkdirs() }
        val legacySelfTest = File(generationRoot, "selftest/p${ClientRuntimeContract.PREFIX_SCHEMA}")
        for (name in listOf("wine-pe-cache", "wine-data-cache")) {
            val shared = File(sharedRoot, name)
            val legacy = File(legacySelfTest, name)
            if (!shared.exists()) {
                if (legacy.isDirectory && !Files.isSymbolicLink(legacy.toPath())) {
                    check(legacy.renameTo(shared)) { "legacy $name cache migration failed" }
                } else shared.mkdirs()
            }
            val link = File(p.root, name)
            val correct = Files.isSymbolicLink(link.toPath()) &&
                Files.readSymbolicLink(link.toPath()) == shared.toPath()
            if (!correct) {
                if (Files.isSymbolicLink(link.toPath())) Files.delete(link.toPath())
                else if (link.exists()) link.deleteRecursively()
                Files.createSymbolicLink(link.toPath(), shared.toPath())
            }
        }
    }

    private fun prepareData(p: Prepared) {
        val extracted = File(context.cacheDir, "client-runtime-data")
        if (extracted.exists()) extracted.deleteRecursively()
        extracted.mkdirs()
        extract("wine-data", File(extracted, "wine-data"))
        copyAsset("wine-data-manifest.json", File(extracted, "wine-data-manifest.json"))
        val manifest = readAsset("wine-data-manifest.json")
        val cache = File(p.root, "wine-data-cache")
        check(WineSpikeNative.materializePeCacheIntoTreeNative(
            cache.absolutePath, manifest, extracted.absolutePath, p.tree.absolutePath) == 0)
        check(WineSpikeNative.verifyPeCacheNative(cache.absolutePath, manifest) == 0)
        val alias = File(context.applicationInfo.dataDir, "wine").toPath()
        val target = File(cache, "wine-data").toPath()
        if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS) &&
            (!Files.isSymbolicLink(alias) || Files.readSymbolicLink(alias) != target)) {
            Files.delete(alias)
        }
        if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(alias, target)
        extracted.deleteRecursively()
    }

    private fun linkBuiltins(p: Prepared) {
        val system32 = File(p.prefix, "drive_c/windows/system32").apply { mkdirs() }
        val syswow64 = File(p.prefix, "drive_c/windows/syswow64").apply { mkdirs() }
        val entries = JSONObject(readAsset("wine-pe-manifest.json")).getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val destinationDir = when (entry.getString("arch")) {
                "x86_64-windows" -> system32
                "i386-windows" -> syswow64
                else -> continue
            }
            val source = File(p.cache, entry.getString("asset_path"))
            val destination = File(destinationDir, source.name).toPath()
            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(destination, source.toPath())
            }
        }
    }

    private fun prefixReady(prefix: File, timeoutMs: Long = 30_000): Boolean {
        val required = listOf(".update-timestamp", "system.reg", "user.reg", "userdef.reg")
            .map { File(prefix, it) }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous = ""; var stable = 0
        do {
            val ready = required.all { it.isFile && it.length() > 0 } &&
                File(prefix, "dosdevices").isDirectory && File(prefix, "drive_c/windows").isDirectory
            val signature = if (ready) required.joinToString { "${it.length()}:${it.lastModified()}" } else ""
            stable = if (ready && signature == previous) stable + 1 else 0
            if (stable >= 4 || (timeoutMs <= 1_000 && ready)) return true
            previous = signature
            Thread.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun enforceQuotas(p: Prepared) {
        val prefixBytes = sizeOf(p.prefix)
        val preserved = p.root.listFiles { file ->
            file.isDirectory && file.name.startsWith("wine-prefix-preserved-")
        }.orEmpty()
        val preservedBytes = preserved.sumOf(::sizeOf)
        val cacheBytes = sizeOf(File(context.noBackupFilesDir, "wine/w11w64-v1/shared"))
        check(prefixBytes <= ClientRuntimeContract.PREFIX_QUOTA_BYTES) { "prefix quota exceeded" }
        check(preserved.size <= ClientRuntimeContract.MAX_PRESERVED_PREFIXES) {
            "preserved prefix generation limit exceeded"
        }
        check(preservedBytes <= ClientRuntimeContract.PRESERVED_PREFIX_QUOTA_BYTES) {
            "preserved prefix quota exceeded"
        }
        check(cacheBytes <= ClientRuntimeContract.CACHE_QUOTA_BYTES) { "cache quota exceeded" }
    }

    private fun prunePreservedPrefixes(root: File) {
        val preserved = root.listFiles { file ->
            file.isDirectory && file.name.startsWith("wine-prefix-preserved-")
        }.orEmpty().sortedByDescending { it.lastModified() }
        preserved.drop(ClientRuntimeContract.MAX_PRESERVED_PREFIXES).forEach { it.deleteRecursively() }
    }

    private fun sizeOf(root: File): Long {
        if (!root.exists()) return 0
        // A Wine prefix contains dosdevices/z: -> /. Kotlin FileTreeWalk can
        // descend directory symlinks and accidentally charge the whole device
        // to the prefix. Files.walk does not follow links unless explicitly
        // asked, and regular-file checks are NOFOLLOW_LINKS.
        return Files.walk(root.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .mapToLong { Files.size(it) }.sum()
        }
    }

    private fun readAsset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
    private fun copyAsset(name: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
    }
    private fun extract(prefix: String, destination: File) {
        destination.mkdirs()
        for (name in context.assets.list(prefix).orEmpty()) {
            val source = "$prefix/$name"
            val children = context.assets.list(source).orEmpty()
            if (children.isNotEmpty()) extract(source, File(destination, name))
            else copyAsset(source, File(destination, name))
        }
    }
    private fun writeAtomic(target: File, value: String) {
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(value)
        check(temp.renameTo(target)) { "atomic manifest replace failed" }
    }
}
