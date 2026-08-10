package com.pocketrealm.client

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.pocketrealm.addons.AddonRuntimeProjector
import com.pocketrealm.wine.WineSpikeNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
    )

    init { WineSpikeNative.load() }

    fun paths(
        clientId: String,
        armTranslator: ArmTranslationBackend = ArmTranslationBackend.BOX64,
        armRenderer: String = "dxvk",
        armRendererPackageId: String? = null,
    ): Prepared {
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            return armPaths(clientId, armTranslator, armRenderer, armRendererPackageId)
        }
        val selfTest = clientId == ClientRuntimeContract.SELF_TEST_ID
        val managed = if (selfTest) null else ManagedClientStore(context).load(clientId)
        val root = File(
            context.noBackupFilesDir,
            // AF_UNIX sun_path is only 108 bytes on Linux. Keep the physical
            // generation name compact; the full pinned build/client identity
            // remains in prefix-manifest.json and prefixId.
            "wine/w11w64-v1/${if (selfTest) "selftest" else "wow5875"}/p${ClientRuntimeContract.PREFIX_SCHEMA}",
        )
        return Prepared(
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
        )
    }

    fun prepare(
        clientId: String,
        renderer: String,
        audioMode: String,
        armTranslator: ArmTranslationBackend = ArmTranslationBackend.BOX64,
        inputSafeMode: Boolean = false,
        armRendererPackageId: String? = null,
    ): Prepared {
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            return prepareArm(
                clientId,
                renderer,
                audioMode,
                armTranslator,
                inputSafeMode,
                armRendererPackageId,
            )
        }
        val displayProfile =
            ClientDisplayProfile.forDevice(Build.SUPPORTED_ABIS.asList(), Build.MODEL)
        require(renderer == "wined3d") { "O06 qualifies WineD3D only" }
        require(audioMode == "off") { "O06 requires the audio-off diagnostic profile" }
        val p = paths(clientId)
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
                old.getString("renderer") == renderer && old.getString("audio_mode") == audioMode
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
        if (!p.selfTest) enforceManagedSafeMode(p, renderer, displayProfile, inputSafeMode)

        val manifest = JSONObject()
            .put("runtime_build_id", ClientRuntimeContract.RUNTIME_BUILD_ID)
            .put("prefix_schema", ClientRuntimeContract.PREFIX_SCHEMA)
            .put("windows_arch", "win32-on-wow64")
            .put("renderer", renderer)
            .put("audio_mode", audioMode)
            .put("client_id", clientId)
            .put("code_location", "apk-nativeLibraryDir")
            .put("code_immutable", true)
            .put("prefix_quota_bytes", ClientRuntimeContract.PREFIX_QUOTA_BYTES)
            .put("preserved_prefix_quota_bytes", ClientRuntimeContract.PRESERVED_PREFIX_QUOTA_BYTES)
            .put("max_preserved_prefixes", ClientRuntimeContract.MAX_PRESERVED_PREFIXES)
            .put("cache_quota_bytes", ClientRuntimeContract.CACHE_QUOTA_BYTES)
            .put("log_quota_bytes", ClientRuntimeContract.LOG_QUOTA_BYTES)
        if (!p.selfTest) {
            val managed = ManagedClientStore(context).load(clientId)
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
                    .put("resolution_ceiling", displayProfile.resolution)
                    .put("qualified_effective_resolution", "800x600")
                    .put("fps_cap", displayProfile.initialFrameCap)
                    .put("audio", "off")
                    .put("realm_endpoint", "127.0.0.1")
                    .put("addons", if (inputSafeMode) "safe-mode-off" else "project-managed-at-launch"))
                .put("known_deviations", JSONArray()
                    .put("GLES shader target is 300 es")
                    .put("renderer advertises a constrained GL 3.0 capability subset")
                    .put("texture copy uses GLES readback/upload instead of glCopyTexImage2D"))
        }
        writeAtomic(manifestFile, manifest.toString(2))
        enforceQuotas(p)
        return p
    }

    private fun armPaths(
        clientId: String,
        translator: ArmTranslationBackend,
        renderer: String,
        rendererPackageId: String?,
    ): Prepared {
        check(clientId == ClientRuntimeContract.WOW_5875_ID) {
            "ARM translated runtime currently authorizes only the imported build-5875 client"
        }
        val managed = ManagedClientStore(context).load(clientId)
        require(renderer == "dxvk" || renderer == "opengl") {
            "unsupported ARM renderer: $renderer"
        }
        val rendererPackage = RendererPackageCatalog.requireForRequest(
            translator,
            renderer,
            rendererPackageId,
        )
        val rendererGeneration = RendererPackageCatalog.runtimeGeneration(
            translator,
            renderer,
            rendererPackage?.id,
        )
        val root = File(
            context.noBackupFilesDir,
            if (translator == ArmTranslationBackend.FEX) {
                "arm-translated/fexcore-2608"
            } else {
                "arm-translated/winlator-ca3d735"
            },
        )
        val rootfs = File(root, "rootfs")
        val prefix = File(root, "prefixes/$rendererGeneration/wine-prefix")
        return Prepared(
            clientId = clientId,
            prefixId = listOf(
                ClientRuntimeContract.armRuntimeBuildId(translator),
                ClientRuntimeContract.armRendererBuildId(
                    translator,
                    renderer,
                    rendererPackage?.id,
                ),
                clientId,
                ClientRuntimeContract.PREFIX_SCHEMA.toString(),
            ).joinToString(":"),
            root = root,
            tree = rootfs,
            prefix = prefix,
            cache = File(root, "cache/$rendererGeneration"),
            // The pinned Winlator glibc/X11 clients resolve :0 beneath the
            // rootfs /tmp path. Hosting the app-private X socket elsewhere
            // leaves WineD3D with no adapter even though the Java X server is
            // running.
            // The pinned ARM64EC imagefs libxcb resolves local X11 sockets as
            // $TMPDIR/.X11-unix/X<n>, and Winlator's matching environment uses
            // <imagefs>/usr/tmp. The older Box64 rootfs uses <rootfs>/tmp.
            tmp = File(rootfs, if (translator == ArmTranslationBackend.FEX) "usr/tmp" else "tmp"),
            executable = managed.executable,
            workingDir = managed.root,
            selfTest = false,
            armTranslator = translator,
            armRenderer = renderer,
            armRendererPackageId = rendererPackage?.id,
        )
    }

    private fun prepareArm(
        clientId: String,
        renderer: String,
        audioMode: String,
        translator: ArmTranslationBackend,
        inputSafeMode: Boolean,
        rendererPackageId: String?,
    ): Prepared {
        val displayProfile =
            ClientDisplayProfile.forDevice(Build.SUPPORTED_ABIS.asList(), Build.MODEL)
        check(displayProfile == ClientDisplayProfile.QUALITY) {
            "ARM translated runtime requires the Quality display profile"
        }
        require(renderer == "dxvk" || renderer == "opengl") {
            "unsupported ARM renderer: $renderer"
        }
        require(audioMode == "off") { "ARM bring-up requires audio off" }
        val rendererPackage = RendererPackageCatalog.requireForRequest(
            translator,
            renderer,
            rendererPackageId,
        )
        val p = armPaths(clientId, translator, renderer, rendererPackage?.id)
        val nativeBox64 = File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
        val wine = File(
            p.tree,
            if (translator == ArmTranslationBackend.FEX) {
                "opt/proton-9.0-arm64ec/bin/wine"
            } else {
                "opt/wine/bin/wine"
            },
        )
        when (translator) {
            ArmTranslationBackend.BOX64 -> {
                check(nativeBox64.isFile) { "APK-managed Box64 is missing" }
                check(File(p.tree, ".pocket-rootfs-ready").isFile && wine.isFile) {
                    "pinned Winlator rootfs is not provisioned"
                }
                installPinnedArmGladio(File(p.tree, "usr/lib/libGL.so.1.7.0"))
                installArmRuntimeAliases(p.tree)
                patchWinlatorPackagePaths(p.tree)
                ensureBox64RendererPrefix(p)
                linkArmBuiltins(p)
                if (renderer == "dxvk") installPinnedArmGraphics(p, requireNotNull(rendererPackage))
            }
            ArmTranslationBackend.FEX -> {
                ensureFexCoreRuntime(p)
                installFexCoreAliases(p.tree)
                patchFexCorePackagePaths(p.tree)
                ensureFexCorePrefix(p)
                linkFexCoreBuiltins(p)
                if (renderer == "dxvk") installFexCoreDxvk(p, requireNotNull(rendererPackage))
            }
        }
        p.tmp.mkdirs(); p.cache.mkdirs()
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

        val dosDevices = File(p.prefix, "dosdevices").apply { mkdirs() }
        val z = File(dosDevices, "z:").toPath()
        if (Files.exists(z, LinkOption.NOFOLLOW_LINKS)) Files.delete(z)
        Files.createSymbolicLink(z, File("/").toPath())
        enforceManagedSafeMode(p, renderer, displayProfile, inputSafeMode)

        val manifest = JSONObject()
            .put("runtime_build_id", ClientRuntimeContract.armRuntimeBuildId(translator))
            .put("renderer_build_id", ClientRuntimeContract.armRendererBuildId(
                translator,
                renderer,
                rendererPackage?.id,
            ))
            .put("provider", ClientRuntimeProvider.ARM_TRANSLATED_WINE.id)
            .put("translator", translator.id)
            .put("translator_code_location", "apk-nativeLibraryDir")
            .put("translator_immutable", true)
            .put("rootfs_generation", if (translator == ArmTranslationBackend.FEX) {
                "winlator-bionic-v3.1.h-fexcore-2608"
            } else "winlator-ca3d735")
            .put("wine_version", if (translator == ArmTranslationBackend.FEX) {
                "Proton 9 ARM64EC"
            } else "10.10")
            .put("package_path_adaptation", if (translator == ArmTranslationBackend.FEX) {
                "Winlator imagefs aliases -> com.pocketrealm/files/fex0 + fex000000"
            } else "com.winlator/rootfs -> com.pocketrealm/rfs")
            .put("prefix_schema", ClientRuntimeContract.PREFIX_SCHEMA)
            .put("windows_arch", "win32-on-wow64")
            .put("renderer", renderer)
            .put("display_profile", displayProfile.id)
            .put("resolution", displayProfile.resolution)
            .put("fps_cap", displayProfile.initialFrameCap)
            .put("graphics_driver", rendererPackage?.let { "turnip-${it.turnipVersion}" }
                ?: "android-gles-via-gladio")
            .put("dx_wrapper", rendererPackage?.let { "dxvk-${it.dxvkVersion}" }
                ?: "disabled-client-opengl")
            .put("audio_mode", audioMode)
            .put("client_id", clientId)
            .put("working_directory", "app-private-managed-client")
        rendererPackage?.let {
            manifest.put("renderer_package_id", it.id)
                .put("renderer_package_qualification", it.qualification)
        }
        writeAtomic(File(p.prefix.parentFile, "prefix-manifest.json"), manifest.toString(2))
        return p
    }

    private fun ensureFexCoreRuntime(p: Prepared) {
        val marker = File(p.tree, ".pocket-fexcore-runtime")
        val wine = File(p.tree, "opt/proton-9.0-arm64ec/bin/wine")
        val gladio = File(p.tree, "usr/lib/libGL.so.1.5.0")
        if (marker.isFile && marker.readText().trim() == ClientRuntimeContract.ARM_FEX_RUNTIME_BUILD_ID &&
            wine.isFile && gladio.isFile && sha256(gladio) == FEXCORE_GLADIO_SHA256) {
            return
        }

        check(!p.root.exists() || p.root.canonicalFile.toPath().startsWith(
            File(context.noBackupFilesDir, "arm-translated/fexcore-2608").canonicalFile.toPath()
        )) { "refusing to replace an unexpected FEXCore runtime root" }
        if (p.root.exists()) check(p.root.deleteRecursively()) {
            "stale FEXCore runtime could not be replaced"
        }
        p.root.mkdirs()
        val compressed = File(p.root, "fexcore-runtime.tar.zst")
        copyAsset("arm-translated/fexcore/fexcore-runtime.tar.zst", compressed)
        check(sha256(compressed) == FEXCORE_RUNTIME_SHA256) {
            "APK-managed FEXCore runtime archive digest mismatch"
        }
        val unpacked = File(p.root, "fexcore-runtime.tar")
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val zstd = File(nativeDir, "libpocket_zstd_exec.so")
        check(zstd.isFile) { "APK-managed Bionic zstd executable is missing" }
        runCheckedProcess(
            listOf(zstd.absolutePath, "-d", "-f", "-q", "-o", unpacked.absolutePath,
                compressed.absolutePath),
            p.root,
            timeoutSeconds = 600,
            environment = mapOf("LD_LIBRARY_PATH" to nativeDir.absolutePath),
        )
        runCheckedProcess(
            listOf("/system/bin/tar", "-xf", unpacked.absolutePath, "-C", p.root.absolutePath),
            p.root,
            timeoutSeconds = 600,
        )
        compressed.delete()
        unpacked.delete()
        check(marker.isFile && marker.readText().trim() == ClientRuntimeContract.ARM_FEX_RUNTIME_BUILD_ID) {
            "FEXCore runtime generation marker is missing"
        }
        check(wine.isFile && wine.canExecute()) { "native ARM64EC Wine is missing" }
        check(File(p.tree,
            "home/xuser/.wine/drive_c/windows/system32/libwow64fex.dll").isFile) {
            "FEXCore WoW64 DLL is missing"
        }
        check(gladio.isFile && sha256(gladio) == FEXCORE_GLADIO_SHA256) {
            "Bionic ARM64 Gladio client is missing or mismatched"
        }
    }

    private fun ensureFexCorePrefix(p: Prepared) {
        if (prefixReady(p.prefix, 1_000)) return
        val base = File(p.tree, "home/xuser/.wine")
        check(prefixReady(base, 1_000)) { "ARM64EC base prefix is incomplete" }
        p.prefix.parentFile!!.mkdirs()
        if (p.prefix.exists()) check(p.prefix.deleteRecursively()) {
            "incomplete ARM64EC renderer prefix could not be replaced"
        }
        runCheckedProcess(
            listOf("/system/bin/cp", "-a", base.absolutePath, p.prefix.absolutePath),
            p.root,
            timeoutSeconds = 300,
        )
        check(prefixReady(p.prefix, 1_000)) { "ARM64EC renderer prefix copy is incomplete" }
    }

    /** Reproduce Winlator's ARM64EC container-finalization step.
     *
     * The shipped container pattern contains registry state and only a small
     * seed set of Windows files. Winlator then materializes every ARM64EC and
     * i386 Wine builtin into system32/syswow64. Without this step Wine itself
     * starts, but its first 32-bit process fails with STATUS_DLL_NOT_FOUND
     * (c0000135 / process exit 53). Symlinks keep the per-renderer prefixes
     * small while retaining the exact pinned Proton payload as the source.
     */
    private fun linkFexCoreBuiltins(p: Prepared) {
        val pairs = listOf(
            File(p.tree, "opt/proton-9.0-arm64ec/lib/wine/aarch64-windows") to
                File(p.prefix, "drive_c/windows/system32"),
            File(p.tree, "opt/proton-9.0-arm64ec/lib/wine/i386-windows") to
                File(p.prefix, "drive_c/windows/syswow64"),
        )
        for ((source, destination) in pairs) {
            check(source.isDirectory) { "ARM64EC Wine builtin source is missing: $source" }
            destination.mkdirs()
            for (file in source.listFiles().orEmpty().filter { it.isFile }) {
                val target = File(destination, file.name).toPath()
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(target, file.toPath())
                }
            }
        }
        for (required in listOf("ntdll.dll", "kernel32.dll", "cmd.exe")) {
            check(File(p.prefix, "drive_c/windows/syswow64/$required").isFile) {
                "32-bit ARM64EC Wine builtin was not linked: $required"
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

    private fun installFexCoreDxvk(p: Prepared, rendererPackage: RendererPackage) {
        check(rendererPackage.id == RendererPackageCatalog.FEX_DEFAULT &&
            rendererPackage.translator == ArmTranslationBackend.FEX) {
            "unsupported FEXCore renderer package: ${rendererPackage.id}"
        }
        val component = File(p.tree, "opt/pocket-components/dxvk")
        val names = listOf("d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
        for (directory in listOf("system32", "syswow64")) {
            val sourceRoot = File(component, directory)
            val targetRoot = File(p.prefix, "drive_c/windows/$directory").apply { mkdirs() }
            for (name in names) {
                val source = File(sourceRoot, name)
                val target = File(targetRoot, name)
                check(source.isFile) { "ARM64EC DXVK component is missing: $directory/$name" }
                source.inputStream().use { input ->
                    val temporary = File(targetRoot, ".$name.pocket.tmp")
                    temporary.outputStream().use { input.copyTo(it) }
                    check(sha256(temporary) == sha256(source)) {
                        "ARM64EC DXVK copy digest mismatch: $directory/$name"
                    }
                    android.system.Os.rename(temporary.absolutePath, target.absolutePath)
                }
            }
        }
    }

    private fun installFexCoreAliases(rootfs: File) {
        for (name in listOf("fex0", "fex000000")) {
            val alias = File(context.filesDir, name).toPath()
            if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(alias) && Files.readSymbolicLink(alias) == rootfs.toPath()) continue
                Files.delete(alias)
            }
            Files.createSymbolicLink(alias, rootfs.toPath())
        }
    }

    private fun patchFexCorePackagePaths(rootfs: File) {
        val marker = File(rootfs, ".pocket-package-paths-v1")
        if (marker.isFile) return
        val replacements = listOf(
            "/data/data/com.winlator/files/imagefs".toByteArray() to
                "/data/data/com.pocketrealm/files/fex0".toByteArray(),
            "/data/data/com.winlator.cmod/files/imagefs".toByteArray() to
                "/data/data/com.pocketrealm/files/fex000000".toByteArray(),
        )
        replacements.forEach { (old, replacement) -> check(old.size == replacement.size) }
        var changedFiles = 0
        Files.walk(rootfs.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .forEach { path ->
                    val file = path.toFile()
                    if (file.length() > 128L * 1024L * 1024L) return@forEach
                    val bytes = file.readBytes()
                    var changed = false
                    for ((old, replacement) in replacements) {
                        var offset = 0
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
                    }
                    if (changed) {
                        val executable = file.canExecute()
                        val temporary = File(file.parentFile, ".${file.name}.pocket-path.tmp")
                        temporary.writeBytes(bytes)
                        temporary.setReadable(true, false)
                        temporary.setWritable(true, true)
                        if (executable) temporary.setExecutable(true, false)
                        android.system.Os.rename(temporary.absolutePath, file.absolutePath)
                        changedFiles++
                    }
                }
        }
        check(changedFiles > 0) { "no Winlator Bionic package paths were patched" }
        writeAtomic(marker, "$changedFiles\n")
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
    private fun installPinnedArmGraphics(p: Prepared, rendererPackage: RendererPackage) {
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
        val files = listOf(
            PinnedAsset(
                "arm-translated/turnip/libvulkan_freedreno.so",
                File(p.tree, "usr/lib/libvulkan_freedreno.so"),
                "f4d09b00d5d7e463f1af76a9974bdd4f2d8298951de9ae2bfc7678a3631e7ab0",
                executable = true,
            ),
            PinnedAsset(
                "arm-translated/turnip/freedreno_icd.aarch64.json",
                File(p.tree, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"),
                "8ab797c2c31441271acee4b2423106683eb9e500de6e168ceb035f02c30aeb92",
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
        if (temporary.exists()) check(temporary.delete()) { "stale graphics staging file could not be removed" }
        context.assets.open(asset.assetPath).use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        temporary.setReadable(true, false)
        if (asset.executable) temporary.setExecutable(true, false)
        check(sha256(temporary) == asset.expectedSha256) {
            "APK-managed graphics asset digest mismatch: ${asset.assetPath}"
        }
        android.system.Os.rename(temporary.absolutePath, asset.target.absolutePath)
        check(sha256(asset.target) == asset.expectedSha256) {
            "installed graphics asset digest mismatch: ${asset.target.name}"
        }
    }

    /** Install the source-matched AArch64 Gladio client from the signed APK.
     *
     * The client and Android renderer share Pocket Realm's versioned transient
     * draw protocol, so they must be built and pinned together. Keep the client
     * behind an exact digest and publish it atomically so an APK reinstall
     * cannot silently leave a mismatched protocol version behind.
     */
    private fun installPinnedArmGladio(target: File) {
        val expected = "1d9663bb23ffe6083cf94925e6ffde4523888d52051c9bf934c87aad4bae4680"
        if (target.isFile && sha256(target) == expected) return

        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.pocket.tmp")
        context.assets.open("arm-translated/libGL.so.1").use { input ->
            temporary.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        temporary.setReadable(true, false)
        temporary.setExecutable(true, false)
        check(sha256(temporary) == expected) {
            "APK-managed ARM Gladio client digest mismatch"
        }
        android.system.Os.rename(temporary.absolutePath, target.absolutePath)
        check(sha256(target) == expected) {
            "installed ARM Gladio client digest mismatch"
        }
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

    companion object {
        private const val FEXCORE_RUNTIME_SHA256 =
            "3fc7d01d79c05c60f59cdddf478b2f6de17d641da5e61bc0eb9e396d7039d975"
        private const val FEXCORE_GLADIO_SHA256 =
            "378e5bb98a818205da90c5642d8cb38da365c83604f2046293907caa8f0c9075"
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
        check(File(p.prefix, "drive_c/windows/syswow64/kernel32.dll").isFile) {
            "32-bit Wine kernel32 builtin was not linked"
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
            "usr/lib/libX11.so.6.4.0", "usr/lib/libxcb.so.1.1.0",
            "usr/lib/libGL.so.1.7.0",
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
                relative == "usr/lib/libGL.so.1.7.0" ||
                relative == "opt/wine/bin/wineserver" ||
                relative == "opt/wine/lib/wine/x86_64-unix/ntdll.so") {
                val remaining = containsBytes(file.readBytes(), old)
                check(!remaining) { "Winlator package root remains in $relative" }
                requiredPatched++
            }
        }
        check(requiredPatched == 5) { "required Wine/X11 package-path adaptations are absent" }
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

    private fun enforceManagedSafeMode(
        p: Prepared,
        renderer: String,
        displayProfile: ClientDisplayProfile,
        inputSafeMode: Boolean,
    ) {
        check(p.clientId == ClientRuntimeContract.WOW_5875_ID) { "safe profile target mismatch" }
        val realmlist = "set realmlist 127.0.0.1\r\n"
        val gameMaximize = if (displayProfile.gameMaximized) "1" else "0"
        // WoW 1.12 persists its graphics backend in Config.wtf. The command-line
        // switch is kept as a compatibility hint, but it is not authoritative for
        // every build-5875 client. Keep the persisted mode aligned with the
        // independently selected ARM renderer so OpenGL cannot fall back to D3D.
        val gameGraphicsApi = when (renderer) {
            "opengl" -> "opengl"
            "dxvk", "wined3d" -> "d3d"
            else -> error("unsupported managed renderer: $renderer")
        }
        // Vanilla WoW's M2 shader path is a known OpenGL/Wine failure mode:
        // the UI and background render while character/object model passes are
        // absent. Keep this as an OpenGL-only managed fallback so the DXVK/D3D
        // lane remains unchanged and the experiment is reversible by changing
        // the selected renderer.
        val m2ShaderFallback = if (renderer == "opengl") {
            "SET M2UseShaders \"0\"\n"
        } else {
            ""
        }
        val config = """SET readTOS "1"
SET readEULA "1"
SET readScanning "1"
SET movie "0"
SET gxApi "$gameGraphicsApi"
SET gxResolution "${displayProfile.resolution}"
SET gxWindowedResolution "${displayProfile.resolution}"
SET gxWindow "1"
SET gxMaximize "$gameMaximize"
SET gxVSync "0"
SET gxMultisample "1"
SET gxMultisampleQuality "0.000000"
SET maxFPS "${displayProfile.initialFrameCap}"
SET Sound_EnableAllSound "0"
SET Sound_EnableMusic "0"
SET Sound_EnableSFX "0"
SET Sound_EnableAmbience "0"
SET ffxGlow "0"
$m2ShaderFallback
SET ffxDeath "0"
SET farclip "177"
SET realmName "MaNGOS"
""".replace("\n", "\r\n")
        writeAtomic(File(p.workingDir, "realmlist.wtf"), realmlist)
        File(p.workingDir, "WTF").mkdirs()
        writeAtomic(File(p.workingDir, "WTF/Config.wtf"), config)
        val activeAddons = AddonRuntimeProjector(context).project(p.workingDir, inputSafeMode)
        val record = JSONObject()
            .put("schema", 1).put("client_id", p.clientId)
            .put("renderer", renderer).put("resolution", displayProfile.resolution)
            .put("fps_cap", displayProfile.initialFrameCap).put("audio", "off")
            .put("game_windowed", true).put("game_maximized", displayProfile.gameMaximized)
            .put("realm_endpoint", "127.0.0.1").put("realm_name", "MaNGOS")
            .put("addon_safe_mode", inputSafeMode)
            .put("addon_folders", JSONArray(activeAddons))
            .put("passwords_stored", false).put("source_modified", false)
        writeAtomic(File(p.workingDir, "managed-safe-profile.json"), record.toString(2))
    }

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
