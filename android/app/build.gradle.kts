import java.security.MessageDigest
import java.io.RandomAccessFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class ValidateExtractedNativePackagingTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun validatePackaging() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(mergedManifest.get().asFile)
        val application = document.getElementsByTagName("application").item(0)
            ?: error("Merged manifest has no application element")
        val extractNativeLibs = application.attributes
            .getNamedItemNS("http://schemas.android.com/apk/res/android", "extractNativeLibs")
            ?.nodeValue
        check(extractNativeLibs == "true") {
            "Packaged native executables require android:extractNativeLibs=true; " +
                "merged manifest resolved to ${extractNativeLibs ?: "<absent>"}"
        }
    }
}

/**
 * Execution-time boundary in front of AGP's connected instrumentation tasks.
 *
 * The guard deliberately classifies the selected target from ANDROID_SERIAL
 * without querying adb.  An absent or ambiguous target therefore fails closed
 * before AGP can discover, install to, or uninstall from any attached device.
 */
abstract class ValidateConnectedAndroidTestTargetTask : DefaultTask() {
    @get:Input
    abstract val targetSerial: Property<String>

    @get:Input
    abstract val hardwareQualification: Property<Boolean>

    @get:Input
    abstract val hardwareAllowlistPath: Property<String>

    @get:Input
    abstract val hardwareAcknowledgement: Property<String>

    @get:Input
    abstract val selectedAbi: Property<String>

    @get:Input
    abstract val safeTestClass: Property<String>

    @get:Input
    abstract val requestedInstrumentationArguments: MapProperty<String, String>

    @get:Input
    abstract val taskSerialOptionPresent: Property<Boolean>

    @TaskAction
    fun validateTarget() {
        // Configuration-cache cross-check: taskSerialOptionPresent
        // was captured from gradle.startParameter at configuration time, which a
        // cached configuration can serve stale. Under a Gradle daemon the
        // launcher JVM is the daemon itself, so its command line carries no
        // build arguments and this check cannot verify anything — it stays
        // useful for no-daemon runs only.
        val launcherCommand = System.getProperty("sun.java.command", "")
        val launcherIsDaemon = launcherCommand.contains("GradleDaemon")
        val launcherSerialOptionPresent = launcherCommand
            .split(" ").any { it == "--serial" || it.startsWith("--serial=") }
        if (!launcherIsDaemon && launcherSerialOptionPresent != taskSerialOptionPresent.get()) {
            throw GradleException(
                "BLOCKED: --serial option detection is stale under the configuration " +
                    "cache (configuration saw ${taskSerialOptionPresent.get()}, execution " +
                    "saw $launcherSerialOptionPresent). Re-run without --serial (select the " +
                    "target via ANDROID_SERIAL) or add --no-configuration-cache.",
            )
        }
        val serial = targetSerial.get().trim()
        if (taskSerialOptionPresent.get()) {
            throw GradleException(
                "BLOCKED: connected-test --serial options can override ANDROID_SERIAL inside " +
                    "AGP. Remove every --serial option and select exactly one target only " +
                    "through ANDROID_SERIAL.",
            )
        }
        if (!hardwareQualification.get()) {
            if (!Regex("^emulator-[0-9]+$").matches(serial)) {
                throw GradleException(
                    "BLOCKED: connected Android tests may target only one explicitly selected " +
                        "emulator. Set ANDROID_SERIAL=emulator-<port>. Physical, wireless, " +
                        "missing, and unrecognised serials are refused before deployment; " +
                        "received ${serial.ifEmpty { "<unset>" }}.",
                )
            }
            logger.lifecycle("Connected-test safety: approved emulator target $serial")
            return
        }

        if (serial.isEmpty() || Regex("^emulator-[0-9]+$").matches(serial)) {
            throw GradleException(
                "BLOCKED: the RP6 hardware qualification path requires one explicit " +
                    "non-emulator ANDROID_SERIAL; received ${serial.ifEmpty { "<unset>" }}.",
            )
        }
        if (selectedAbi.get() != "arm64-v8a") {
            throw GradleException(
                "BLOCKED: RP6 hardware qualification requires -PpocketAbi=arm64-v8a.",
            )
        }

        val allowlist = File(hardwareAllowlistPath.get())
        if (!allowlist.isFile) {
            throw GradleException(
                "BLOCKED: no local hardware serial allowlist exists at $allowlist. " +
                    "Create it deliberately with one exact device serial per line.",
            )
        }
        val allowedSerials = allowlist.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
        if (serial !in allowedSerials) {
            throw GradleException(
                "BLOCKED: ANDROID_SERIAL '$serial' is not an exact entry in $allowlist.",
            )
        }

        val expectedAcknowledgement =
            "I_ACKNOWLEDGE_CONNECTED_ANDROID_TEST_MAY_WIPE_COM_POCKETREALM_DATA_ON:$serial"
        if (hardwareAcknowledgement.get() != expectedAcknowledgement) {
            throw GradleException(
                "BLOCKED: hardware instrumentation can replace/uninstall com.pocketrealm and " +
                    "wipe its private data. Re-run only after preserving required data, with " +
                    "-PpocketHardwareQualificationAcknowledgement=\"$expectedAcknowledgement\".",
            )
        }

        val argumentPrefix = "android.testInstrumentationRunnerArguments."
        val unsafeArguments = requestedInstrumentationArguments.get().filter { (key, value) ->
            key.removePrefix(argumentPrefix) != "class" || value.trim() != safeTestClass.get()
        }
        if (unsafeArguments.isNotEmpty()) {
            throw GradleException(
                "BLOCKED: the hardware path is locked to ${safeTestClass.get()} and refuses " +
                    "external instrumentation arguments: ${unsafeArguments.keys.sorted()}.",
            )
        }
        logger.lifecycle(
            "!!! PHYSICAL DEVICE QUALIFICATION APPROVED FOR $serial !!! " +
                "Only ${safeTestClass.get()} is selected; com.pocketrealm data may be wiped.",
        )
    }
}

/**
 * Configuration-cache-safe verifier for the selected native closure.
 *
 * Keeping the verification logic on a real task type is important here: a
 * Kotlin DSL `doLast { ... }` closure retains the build-script instance and
 * cannot be serialized into Gradle's configuration cache.  The task inputs
 * are explicit, so an x86 and an ARM invocation can never reuse one another's
 * closure or validation result.
 */
abstract class ValidateSelectedNativeClosureTask : DefaultTask() {
    @get:Input
    abstract val selectedAbi: Property<String>

    @get:Input
    abstract val repoRootPath: Property<String>

    @get:Input
    abstract val nativeRootSuffix: Property<String>

    @get:Input
    abstract val prootRootSuffix: Property<String>

    @get:Input
    abstract val expectedMachine: Property<Int>

    @get:Input
    abstract val compat32Machine: Property<Int>

    @get:Input
    abstract val lane: Property<String>

    // The provider selection is a task INPUT: the class doc mandates
    // explicit properties so this task stays configuration-cache
    // serializable - a script-instance reference from the action
    // would break the store.
    @get:Input
    abstract val sqliteProvider: Property<Boolean>

    @TaskAction
    fun validateClosure() {
        val abi = selectedAbi.get()
        val repoRoot = File(repoRootPath.get())
        val nativeBuildRoot = File(repoRoot, "native/.build-${nativeRootSuffix.get()}")
        val isSqliteProvider = sqliteProvider.get()

        fun elfMachine(file: File): Int {
            check(file.isFile) { "Missing native artifact for $abi: $file" }
            return file.inputStream().use { input ->
                val header = ByteArray(20)
                check(input.read(header) == header.size &&
                    header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
                    "Expected ELF artifact for $abi: $file"
                }
                check(header[5] == 1.toByte()) {
                    "Only little-endian ELF artifacts are supported: $file"
                }
                (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            }
        }

        fun requireAbi(file: File, allowCompat32: Boolean = false) {
            val machine = elfMachine(file)
            val allowed = machine == expectedMachine.get() ||
                (allowCompat32 && machine == compat32Machine.get())
            check(allowed) {
                "Cross-ABI native artifact rejected for $abi: $file has ELF " +
                    "e_machine=$machine, expected ${expectedMachine.get()}" +
                    if (allowCompat32) " (or compatibility helper ${compat32Machine.get()})" else ""
            }
        }

        fun requirePeMachine(file: File, expected: Int) {
            check(file.isFile) { "Missing PE artifact: $file" }
            val machine = RandomAccessFile(file, "r").use { input ->
                check(input.readUnsignedByte() == 'M'.code && input.readUnsignedByte() == 'Z'.code) {
                    "Expected PE artifact: $file"
                }
                input.seek(0x3c)
                val peOffset = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                input.seek(peOffset)
                check(input.readUnsignedByte() == 'P'.code && input.readUnsignedByte() == 'E'.code &&
                    input.readUnsignedByte() == 0 && input.readUnsignedByte() == 0) {
                    "Invalid PE signature: $file"
                }
                input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            }
            check(machine == expected) {
                "Cross-architecture PE rejected: $file has machine=0x${machine.toString(16)}, " +
                    "expected 0x${expected.toString(16)}"
            }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val selectedLane = lane.get()
        check(selectedLane == "full" || selectedLane == "database") {
            "Unsupported native packaging lane: $selectedLane"
        }
        val required = buildList {
            add(File(nativeBuildRoot, "wine-spike-build/libwine_spike.so"))
            if (selectedLane == "full") {
                // On-device vanilla-tweaks patcher (produces WoW.exe.patched).
                add(File(repoRoot,
                    "native/.build-vanilla-tweaks-$abi/staging/jniLibs/$abi/libpocket_vanilla_tweaks.so"))
                add(File(nativeBuildRoot, "pocket-runtime-build/libpocketrealm.so"))
                add(File(nativeBuildRoot, "packaging-build/libpocketpkgtest.so"))
                add(File(nativeBuildRoot, "packaging-build/libpocket_pkg_launcher.so"))
                add(File(nativeBuildRoot, "wine-spike-build/libwine_trampoline.so"))
                add(File(nativeBuildRoot, "xserver-winlator-build/libwinlator.so"))
                // Source-matched GLX is used by x86 WineD3D and the explicit,
                // capability-gated ARM Legacy OpenGL (Gladio) lane.
                if (abi == "x86_64" || abi == "arm64-v8a") {
                    add(File(nativeBuildRoot, "xserver-winlator-build/libgladiorenderer.so"))
                }
                if (abi == "arm64-v8a") {
                    add(File(nativeBuildRoot, "xserver-winlator-build/libvortekrenderer.so"))
                    add(File(nativeBuildRoot, "xserver-winlator-build/libvirglrenderer.so"))
                    // Vendored llama.cpp runtime staged alongside the world
                    // runtime it links into (in-process LLM backend). The
                    // staging root follows the provider selection: under
                    // -PsqliteProvider the APK packages the SIBLING staging's
                    // llama closure, so the gate must validate the sibling
                    // bytes, not the MariaDB-root ones.
                    val llamaRealmRoot = if (isSqliteProvider) "realm-staging-sqlite" else "realm-staging"
                    for (name in listOf("libllama.so", "libllama-common.so", "libggml.so",
                            "libggml-base.so", "libggml-cpu.so")) {
                        add(File(repoRoot,
                            "native/.build-o09-$abi/$llamaRealmRoot/jniLibs/$abi/$name"))
                    }
                }
            }
            // The glibc access(2) shim belongs only to the x86_64 direct-Wine
            // spike. ARM uses a separate translated-Wine provider boundary;
            // never make an ARM APK appear complete by copying this x86/glibc
            // artifact into its closure.
            if (abi == "x86_64") {
                add(File(nativeBuildRoot, "wine-spike-build/libwine_android_shim.so"))
            }
        }
        val wineStaging = File(nativeBuildRoot, "wine-staging/jniLibs")
        val prootStage = File(repoRoot, "native/.build-${prootRootSuffix.get()}/proot-stage")
        val mariadbStage = File(nativeBuildRoot, "mariadb-staging/jniLibs/$abi")
        val realmStage = File(
            repoRoot,
            if (isSqliteProvider) {
                "native/.build-o09-$abi/realm-staging-sqlite/jniLibs/$abi"
            } else {
                "native/.build-o09-$abi/realm-staging/jniLibs/$abi"
            },
        )
        val extractorStage = File(
            repoRoot,
            "native/.build-o11-$abi/extractor-staging/jniLibs/$abi",
        )
        val serverLibraries = listOf(
            File(realmStage, "libpocket_realmd_runtime.so"),
            File(realmStage, "libpocket_world_runtime.so"),
        )
        val extractors = listOf(
            "libpocket_ad.so", "libpocket_vmap_extractor.so",
            "libpocket_vmap_assembler.so", "libpocket_movemapgen.so",
        ).map { File(extractorStage, it) }

        // The database lane is deliberately client-runtime-free. Extractor
        // data preparation is deferred to the full runtime lane, where the
        // realm provider and its memory budget are available.
        val laneSpecific = if (selectedLane == "full") serverLibraries + extractors else emptyList()
        val armGraphicsData = if (abi == "arm64-v8a" && selectedLane == "full") listOf(
            File(nativeBuildRoot, "xserver-winlator-build/VIRGL_BUILD_PROVENANCE.json"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/vulkan-drivers/catalog.json"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/vulkan-drivers/system-vulkan-vortek-2.1/libvulkan_vortek.so"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/vulkan-drivers/system-vulkan-vortek-2.1/vortek_icd.aarch64.json"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/vulkan-drivers/turnip-26.1.0/libvulkan_freedreno.so"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/vulkan-drivers/turnip-26.1.0/freedreno_icd.aarch64.json"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/catalog.json"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/box64-dxvk-2.4.1/system32/d3d9.dll"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/box64-dxvk-2.4.1/syswow64/d3d9.dll"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/box64-dxvk-1.10.3/system32/d3d9.dll"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/box64-dxvk-1.10.3/syswow64/d3d9.dll"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/box64-gladio-eaa2a8d/libGL.so.1"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/box64-virgl-23.1.9/libGL.so.1"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated/renderer-packages/box64-virgl-23.1.9/BUILD_PROVENANCE.json"),
        ) else emptyList()
        val armRuntimeData = if (abi == "arm64-v8a" && selectedLane == "full") listOf(
            File(nativeBuildRoot, "wine-staging/assets/arm-translated-wine/rootfs.tzst"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated-wine/rootfs_patches.tzst"),
            File(nativeBuildRoot, "wine-staging/assets/arm-translated-wine/container_pattern.tzst"),
        ) else emptyList()
        val missing = (required + laneSpecific + armGraphicsData + armRuntimeData).filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Incomplete native closure for $abi. Build every selected-ABI provider; missing: " +
                missing.joinToString()
        }
        required.forEach { requireAbi(it) }
        if (selectedLane == "full") {
            val patcher = File(repoRoot,
                "native/.build-vanilla-tweaks-$abi/staging/jniLibs/$abi/libpocket_vanilla_tweaks.so")
            val lock = File(repoRoot, if (abi == "x86_64") {
                "schemas/vanilla-tweaks-lockfile.json"
            } else {
                "schemas/vanilla-tweaks-lockfile-$abi.json"
            })
            check(lock.isFile) { "Missing vanilla-tweaks lockfile for $abi" }
            val record = JsonSlurper().parse(lock) as Map<*, *>
            check((record["schema"] as Number).toInt() == 1 && record["abi"] == abi) {
                "vanilla-tweaks lockfile identity mismatch for $abi"
            }
            val artifacts = record["artifacts"] as List<*>
            check(artifacts.size == 1) { "vanilla-tweaks lockfile must contain exactly one artifact" }
            val artifact = artifacts.single() as Map<*, *>
            check((artifact["size"] as Number).toLong() == patcher.length() &&
                artifact["sha256"] == sha256(patcher)) {
                "vanilla-tweaks artifact differs from its pinned size/SHA-256"
            }
        }
        if (abi == "arm64-v8a" && selectedLane == "full") {
            // The vendored llama.cpp shared libraries must byte-match the
            // pinned kai build in native/llm/prebuilt before they ride the
            // realm staging dir into the APK.
            val llamaLock = File(repoRoot, "native/llm/lockfile-arm64-v8a.json")
            check(llamaLock.isFile) { "Missing vendored llama lockfile: $llamaLock" }
            val llamaRecord = JsonSlurper().parse(llamaLock) as Map<*, *>
            check(llamaRecord["abi"] == "arm64-v8a") { "Vendored llama lockfile ABI mismatch" }
            val llamaLibs = llamaRecord["libs"] as Map<*, *>
            check(llamaLibs.size == 5) { "Unexpected vendored llama lib set: ${llamaLibs.keys}" }
            val llamaRealmRoot = if (isSqliteProvider) "realm-staging-sqlite" else "realm-staging"
            for ((name, metaAny) in llamaLibs) {
                val meta = metaAny as Map<*, *>
                val staged = File(repoRoot,
                    "native/.build-o09-$abi/$llamaRealmRoot/jniLibs/$abi/$name")
                check((meta["bytes"] as Number).toLong() == staged.length() &&
                    meta["sha256"] == sha256(staged)) {
                    "Vendored llama library $name differs from its pinned size/SHA-256"
                }
            }
        }
        if (abi == "arm64-v8a" && selectedLane == "full") {
            val gladioServer = File(nativeBuildRoot,
                "xserver-winlator-build/libgladiorenderer.so")
            // Pinned build carries the share-group lifetime fix
            // (refcounted GLSharedObjectState in gl_client_state.h).
            check(gladioServer.length() == 1_314_512L && sha256(gladioServer) ==
                "4e722a89c8871fb59627f36a5a48446e7d7a0e96e4136bf27188f0a041ebc705") {
                "ARM Gladio server differs from the reviewed source build"
            }
            val virglServer = File(nativeBuildRoot,
                "xserver-winlator-build/libvirglrenderer.so")
            check(virglServer.length() == 1_959_112L && sha256(virglServer) ==
                "c6895de1a5407fc60f3098e4d650689fb6c45e89126560cce8867d98e56286ae") {
                "ARM VirGL server differs from the reviewed source build"
            }
            val virglServerProvenance = JsonSlurper().parse(File(nativeBuildRoot,
                "xserver-winlator-build/VIRGL_BUILD_PROVENANCE.json")) as Map<*, *>
            check(virglServerProvenance["winlator_commit"] ==
                "ca3d735a60d653a787daf16d14fafef28d9c2c23" &&
                virglServerProvenance["upstream_virgl_source_tree_id"] ==
                    "44f73c34d4a2cf4e21fcdbcfc4fc37a44837e1b9" &&
                virglServerProvenance["adapted_source_sha256"] ==
                    // adapted_source_sha256 covers the provenance generator
                    // itself: the build script self-hashes into the
                    // provenance, so tooling changes regenerate it while the
                    // libvirglrenderer.so binary stays byte-identical
                    // (sha/size pins above unchanged).
                    "ecde987136d772e99c289e882c19df18796e760fef2a232b963694ee544f1e15" &&
                virglServerProvenance["ndk_version"] == "30.0.15729638" &&
                (virglServerProvenance["size"] as Number).toLong() == virglServer.length() &&
                virglServerProvenance["sha256"] == sha256(virglServer) &&
                virglServerProvenance["max_page_size"] == "0x4000") {
                "ARM VirGL server provenance does not match the adapted binary"
            }
            val rootfsArchive = armRuntimeData[0]
            check(rootfsArchive.length() == 65_251_198L &&
                sha256(rootfsArchive) ==
                    "8b5110f248e84f2aee4df37dab8bac4c4bf2bdc7b400c0643a0778ca8e7e40c2") {
                "Box64 rootfs archive differs from the audio/config-qualified provider"
            }
            val rootfsPatches = armRuntimeData[1]
            check(rootfsPatches.length() == 4_173_700L &&
                sha256(rootfsPatches) ==
                    "44b73e37587ea827a12a34753632feb6e2a9c127089e342774167dd91aba8210") {
                "Box64 rootfs patch archive differs from its pinned provider"
            }
            val containerPattern = armRuntimeData[2]
            check(containerPattern.length() == 7_399_363L &&
                sha256(containerPattern) ==
                    "8ae3a4fee33e86da26826395650bb07c6f49ce94629ea4b9442bc633b6b8ca33") {
                "Box64 Wine prefix template differs from its pinned provider"
            }
            val driverCatalogFile = File(nativeBuildRoot,
                "wine-staging/assets/arm-translated/vulkan-drivers/catalog.json")
            val reviewedDriverCatalogFile = File(repoRoot, "schemas/vulkan-driver-catalog.json")
            check(driverCatalogFile.readBytes().contentEquals(reviewedDriverCatalogFile.readBytes())) {
                "Packaged Vulkan driver catalog differs from the reviewed source catalog"
            }
            val driverCatalog = JsonSlurper().parse(driverCatalogFile) as Map<*, *>
            check((driverCatalog["schema"] as Number).toInt() == 2 &&
                driverCatalog["default"] == "turnip-26.1.0" &&
                driverCatalog["selection_policy"] == "exact-request-fail-closed") {
                "Unsupported Vulkan driver catalog schema/default"
            }
            val driverRecords = (driverCatalog["drivers"] as List<*>)
                .map { it as Map<*, *> }
            val expectedDriverIds = setOf("system-vulkan-vortek-2.1", "turnip-26.1.0")
            check(driverRecords.size == expectedDriverIds.size &&
                driverRecords.map { it["id"] as String }.toSet() == expectedDriverIds) {
                "Vulkan driver catalog is not the closed expected set"
            }
            val driverRecordsById = driverRecords.associateBy { it["id"] as String }
            val systemRelease = driverRecordsById.getValue("system-vulkan-vortek-2.1")["release"]
                as Map<*, *>
            val systemFloors = systemRelease["minimum_vulkan_by_renderer"] as Map<*, *>
            val systemExtensions = (systemRelease["required_device_extensions"] as List<*>)
                .map { it as String }.toSet()
            val requiredSystemExtensions = setOf(
                "VK_KHR_swapchain",
                "VK_ANDROID_external_memory_android_hardware_buffer",
                "VK_KHR_external_memory",
                "VK_KHR_external_memory_fd",
                "VK_KHR_external_semaphore",
                "VK_KHR_external_semaphore_fd",
                "VK_KHR_external_fence",
                "VK_KHR_external_fence_fd",
            )
            check(systemRelease["enabled"] == false && systemRelease["default"] == false &&
                systemRelease["experimental"] == true &&
                systemRelease["protocol_profile"] == "winlator-2.1-source-matched" &&
                systemFloors == mapOf(
                    "box64-dxvk-2.4.1" to "1.3",
                    "box64-dxvk-1.10.3" to "1.1",
                ) && systemExtensions == requiredSystemExtensions &&
                systemRelease["requires_native_texture_compression_bc"] == true) {
                "System/Vortek must remain experimental with the exact capability gate"
            }
            val turnipRelease = driverRecordsById.getValue("turnip-26.1.0")["release"]
                as Map<*, *>
            check(turnipRelease["enabled"] == true && turnipRelease["default"] == true &&
                turnipRelease["qualified_device_models"] == listOf("Retroid Pocket 6")) {
                "exact RP6 Turnip must remain the qualified production default"
            }
            val expectedRolesByDriver = mapOf(
                "system-vulkan-vortek-2.1" to setOf(
                    "guest-vulkan-bridge-library", "guest-vulkan-icd-manifest",
                ),
                "turnip-26.1.0" to setOf(
                    "guest-vulkan-icd-library", "guest-vulkan-icd-manifest",
                ),
            )
            val assetRoot = File(nativeBuildRoot, "wine-staging/assets")
            val canonicalAssetRoot = assetRoot.canonicalFile.toPath()
            driverRecords.forEach { driver ->
                val driverId = driver["id"] as String
                val files = (driver["files"] as List<*>).map { it as Map<*, *> }
                check(files.map { it["role"] as String }.toSet() ==
                    expectedRolesByDriver.getValue(driverId)) {
                    "Vulkan driver file roles changed for $driverId"
                }
                files.forEach { record ->
                    val assetPath = record["asset"] as String
                    val file = File(assetRoot, assetPath).canonicalFile
                    check(file.toPath().startsWith(canonicalAssetRoot) && file.isFile) {
                        "Vulkan driver asset path is absent or escapes the asset root: $assetPath"
                    }
                    val expectedSize = (record["size"] as Number).toLong()
                    val expectedSha256 = record["sha256"] as String
                    check(expectedSha256.matches(Regex("[0-9a-f]{64}")) &&
                        file.length() == expectedSize && sha256(file) == expectedSha256) {
                        "Vulkan driver asset differs from reviewed catalog: $assetPath"
                    }
                    if (file.name.endsWith(".so")) {
                        check((record["elf_machine"] as Number).toInt() == 0xB7) {
                            "Vulkan driver catalog ELF machine mismatch: $assetPath"
                        }
                        requireAbi(file)
                    }
                }
            }
            val vortekIcd = JsonSlurper().parse(
                File(assetRoot,
                    "arm-translated/vulkan-drivers/system-vulkan-vortek-2.1/vortek_icd.aarch64.json"),
            ) as Map<*, *>
            val vortekIcdBlock = vortekIcd["ICD"] as Map<*, *>
            check(vortekIcd["file_format_version"] == "1.0.1" &&
                vortekIcdBlock["api_version"] == "1.3.128" &&
                vortekIcdBlock["library_arch"] == "64" &&
                vortekIcdBlock["library_path"] ==
                    "/data/data/com.pocketrealm/files/rfs/lib/libvulkan_vortek.so" &&
                systemFloors.values.all { floor ->
                    val parts = (floor as String).split('.').map(String::toInt)
                    parts[0] < 1 || (parts[0] == 1 && parts[1] <= 3)
                }) {
                "Vortek ICD maximum must equal bridge 1.3.128 and cover every renderer floor"
            }

            val rendererCatalogFile = File(nativeBuildRoot,
                "wine-staging/assets/arm-translated/renderer-packages/catalog.json")
            val rendererCatalog = JsonSlurper().parse(rendererCatalogFile) as Map<*, *>
            check((rendererCatalog["schema"] as Number).toInt() == 1) {
                "Unsupported renderer package catalog schema"
            }
            val rendererRecords = (rendererCatalog["packages"] as List<*>)
                .map { it as Map<*, *> }
            val rendererIds = rendererRecords.map { it["id"] as String }.toSet()
            val expectedRendererIds = setOf(
                "box64-dxvk-2.4.1",
                "box64-dxvk-1.10.3",
            )
            check(rendererRecords.size == expectedRendererIds.size &&
                rendererIds == expectedRendererIds) {
                "Renderer package catalog is not the closed expected set: $rendererIds"
            }
            check(rendererRecords.all { it["backend"] == "dxvk" }) {
                "Renderer package catalog contains a non-DXVK package"
            }
            check(rendererRecords.all { it["translator"] == "box64" }) {
                "Renderer package catalog contains a removed translator"
            }
            val expectedFiles = mapOf(
                "arm-translated/renderer-packages/box64-dxvk-2.4.1/system32/d3d9.dll" to
                    Triple(3_743_758L,
                        "216058f9320d0667d551f4cea840ee539396449ef8c8e89fe481e4f0ddb628ae", 0x8664),
                "arm-translated/renderer-packages/box64-dxvk-2.4.1/syswow64/d3d9.dll" to
                    Triple(4_124_686L,
                        "cc556331fc3388989749620bceead4c2da95c3932ed38cf5cc24f3f0a878866e", 0x014c),
                "arm-translated/renderer-packages/box64-dxvk-1.10.3/system32/d3d9.dll" to
                    Triple(3_002_382L,
                        "7129d7e67b9abb06608fe1c30bec4c7a7c7f0649198e39425cd7ef322569c383", 0x8664),
                "arm-translated/renderer-packages/box64-dxvk-1.10.3/syswow64/d3d9.dll" to
                    Triple(3_305_486L,
                        "b6cfa2cd62af73b80d461085d126004b0e22dd3944c9246c58e3a68e747b56b6", 0x014c),
            )
            val catalogFiles = rendererRecords
                .flatMap { (it["files"] as List<*>).map { file -> file as Map<*, *> } }
                .associateBy { it["asset"] as String }
            check(catalogFiles.keys == expectedFiles.keys) {
                "Renderer package file catalog changed: ${catalogFiles.keys}"
            }
            expectedFiles.forEach { (assetPath, expected) ->
                val record = checkNotNull(catalogFiles[assetPath])
                val file = File(assetRoot, assetPath)
                check((record["size"] as Number).toLong() == expected.first &&
                    file.length() == expected.first) {
                    "Renderer package size mismatch: $assetPath"
                }
                check(record["sha256"] == expected.second && sha256(file) == expected.second) {
                    "Renderer package digest mismatch: $assetPath"
                }
                check((record["pe_machine"] as Number).toInt() == expected.third) {
                    "Renderer catalog PE machine mismatch: $assetPath"
                }
                requirePeMachine(file, expected.third)
            }
            val gladioClient = File(assetRoot,
                "arm-translated/renderer-packages/box64-gladio-eaa2a8d/libGL.so.1")
            check(gladioClient.length() == 498_656L && sha256(gladioClient) ==
                "c02fb7275463bebcc3aa3fcf3e8e6de668bd2e6f39bda57052d3352801636d08") {
                "Gladio client differs from its source-matched reviewed artifact"
            }
            requireAbi(gladioClient)
            val virglClient = File(assetRoot,
                "arm-translated/renderer-packages/box64-virgl-23.1.9/libGL.so.1")
            check(virglClient.length() == 14_379_544L && sha256(virglClient) ==
                "531e3dc809281feadcc2120abc6d9f88025d92d567ac32eed9c376bd9e4e04f6") {
                "Mesa virpipe client differs from the pinned ca3d735 artifact"
            }
            requireAbi(virglClient)
            val virglProvenance = JsonSlurper().parse(File(assetRoot,
                "arm-translated/renderer-packages/box64-virgl-23.1.9/BUILD_PROVENANCE.json")) as Map<*, *>
            check(virglProvenance["source_commit"] ==
                "ca3d735a60d653a787daf16d14fafef28d9c2c23" &&
                virglProvenance["mesa_source_commit"] ==
                    "71c57a2def7db3eb45cde5ee520f112de0fa6ec0" &&
                virglProvenance["sha256"] ==
                    "531e3dc809281feadcc2120abc6d9f88025d92d567ac32eed9c376bd9e4e04f6") {
                "Mesa VirGL provenance does not identify the exact paired source"
            }
        }
        if (selectedLane == "full") {
            val wineFiles = wineStaging.listFiles { file -> file.isFile && file.name.endsWith(".so") }
                ?.toList().orEmpty()
            check(wineFiles.isNotEmpty()) {
                if (abi == "arm64-v8a") {
                    "ARM translated-Wine closure is not available: build and pin the " +
                        "Box64 + x86_64 WoW64 Wine provider under $wineStaging before assembling RP6 APKs"
                } else {
                    "Wine staging contains no ELF closure: $wineStaging"
                }
            }
            wineFiles.forEach { requireAbi(it) }
            val prootFiles = prootStage.listFiles { file -> file.isFile && file.name.endsWith(".so") }
                ?.toList().orEmpty()
            check(prootFiles.isNotEmpty()) { "proot staging contains no ELF closure: $prootStage" }
            prootFiles.forEach { file ->
                requireAbi(file, allowCompat32 = file.name == "libproot_loader32.so")
            }
        }
        val mariaFiles = mariadbStage.listFiles { file -> file.isFile && file.name.endsWith(".so") }
            ?.toList().orEmpty()
        check(mariaFiles.isNotEmpty()) { "MariaDB staging contains no ELF closure: $mariadbStage" }
        mariaFiles.forEach { requireAbi(it) }
        if (selectedLane == "full") {
            serverLibraries.forEach { requireAbi(it) }
            extractors.forEach { requireAbi(it) }
        }
    }
}

/**
 * Build-time staleness fence for the staged realm runtime. Guards against
 * packaging a stale libpocket_world_runtime.so (one missing the world-chat,
 * reset-state and llm-memory-state JNI ops) that would pass a gate which
 * compares the staging bytes only against the lockfile the same stale lane
 * wrote. The fence verifies:
 *
 *  - every staged .so under native/.build-o09-<abi>/realm-staging[-sqlite]
 *    is rehashed and compared against the lane lockfile's artifact pins;
 *  - BUILD_PROVENANCE.json must exist and its artifact rows + source commits
 *    must agree with the lockfile;
 *  - the lockfile's cmangos_commit/playerbots_commit must still match the
 *    schemas/sources.json pins (a lane built from moved submodules fails);
 *  - staged files without a lockfile pin are refused.
 *
 * With requireStagedBytes=false only the committed-pin coherence is checked
 * (the CI unit-test lane has no untracked staging outputs to hash). The
 * semantics mirror the unit-tested contract
 * com.pocketrealm.server.NativeRuntimeFreshness (android unit tests) - keep
 * the two in sync in the same change. Skippable ONLY via
 * -PpocketSkipNativeFreshness=true, which logs a loud warning.
 */
abstract class ValidateNativeRuntimeFreshnessTask : DefaultTask() {
    @get:Input
    abstract val selectedAbi: Property<String>

    @get:Input
    abstract val databaseBackend: Property<String>

    @get:Input
    abstract val laneLabel: Property<String>

    @get:Input
    abstract val repoRootPath: Property<String>

    @get:Input
    abstract val lockfileRelativePath: Property<String>

    @get:Input
    abstract val stagingRelativeDir: Property<String>

    @get:Input
    abstract val expectedCmangosCommit: Property<String>

    @get:Input
    abstract val expectedPlayerbotsCommit: Property<String>

    @get:Input
    abstract val requireStagedBytes: Property<Boolean>

    @get:Input
    abstract val skipRequested: Property<String>

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun artifactPins(record: Map<*, *>): List<Triple<String, Long, String>> =
        (record["artifacts"] as? List<*>).orEmpty()
            .mapNotNull { row -> row as? Map<*, *> }
            .mapNotNull { row ->
                val path = row["path"] as? String ?: return@mapNotNull null
                val size = (row["size"] as? Number)?.toLong() ?: return@mapNotNull null
                val sha256 = row["sha256"] as? String ?: return@mapNotNull null
                Triple(path, size, sha256)
            }

    private fun fail(reasons: List<String>): Nothing =
        throw GradleException(
            "Native runtime freshness fence FAILED for ${laneLabel.get()}:\n" +
                reasons.joinToString("\n") { reason -> "  - $reason" } +
                "\n  Remedy: rerun the full lane build: " +
                "python tools/build_o09_realm_runtime.py " +
                "(matching -PpocketAbi/-PpocketLane)",
        )

    @TaskAction
    fun validate() {
        val skip = skipRequested.get().trim()
        if (skip == "true") {
            logger.warn(
                "!!! -PpocketSkipNativeFreshness=true: the native staleness fence is " +
                    "DISABLED for ${laneLabel.get()}; a stale realm-runtime .so can be " +
                    "packaged silently !!!",
            )
            return
        }
        check(skip.isEmpty()) {
            "Refusing ambiguous -PpocketSkipNativeFreshness='$skip'; pass exactly 'true' " +
                "to skip loudly, or drop the property to run the fence"
        }

        val abi = selectedAbi.get()
        val backend = databaseBackend.get()
        val repoRoot = File(repoRootPath.get())
        val lockfile = File(repoRoot, lockfileRelativePath.get())
        val reasons = ArrayList<String>()

        if (!lockfile.isFile) {
            fail(listOf("realm runtime lockfile is missing: $lockfileRelativePath"))
        }
        val record = runCatching { JsonSlurper().parse(lockfile) as Map<*, *> }.getOrElse {
            fail(listOf(
                "realm runtime lockfile is not parseable JSON: $lockfileRelativePath " +
                    "(${it.message ?: it.javaClass.simpleName})",
            ))
        }
        val lockAbi = record["abi"] as? String
        if (lockAbi != abi) {
            reasons += "$lockfileRelativePath pins abi '$lockAbi' but the build selected '$abi'"
        }
        val lockBackend = record["database_backend"] as? String
        if (lockBackend != backend) {
            reasons += "$lockfileRelativePath pins database_backend '$lockBackend' but the " +
                "build selected '$backend'"
        }
        val lockCmangos = record["cmangos_commit"] as? String
        val lockPlayerbots = record["playerbots_commit"] as? String
        if (lockCmangos != expectedCmangosCommit.get()) {
            reasons += "$lockfileRelativePath cmangos_commit '$lockCmangos' != " +
                "schemas/sources.json pin '${expectedCmangosCommit.get()}' (cmangos-core); " +
                "the lane build ran against STALE source pins"
        }
        if (lockPlayerbots != expectedPlayerbotsCommit.get()) {
            reasons += "$lockfileRelativePath playerbots_commit '$lockPlayerbots' != " +
                "schemas/sources.json pin '${expectedPlayerbotsCommit.get()}' (playerbots); " +
                "the lane build ran against STALE source pins"
        }
        val pins = artifactPins(record)
        if (pins.isEmpty()) {
            reasons += "$lockfileRelativePath has no artifact pins"
        }

        if (requireStagedBytes.get()) {
            val stagingDir = File(repoRoot, stagingRelativeDir.get())
            val stagingLibsDir = File(stagingDir, "jniLibs/$abi")
            val provenanceFile = File(stagingDir, "BUILD_PROVENANCE.json")
            var provenance: Map<*, *>? = null
            if (!provenanceFile.isFile) {
                reasons += "BUILD_PROVENANCE.json is missing under ${stagingRelativeDir.get()}; " +
                    "the staged runtime predates provenance recording or the full lane " +
                    "build never ran"
            } else {
                provenance = runCatching {
                    JsonSlurper().parse(provenanceFile) as Map<*, *>
                }.getOrNull()
                if (provenance == null) {
                    reasons += "BUILD_PROVENANCE.json under ${stagingRelativeDir.get()} is " +
                        "not parseable JSON"
                }
            }
            if (provenance != null) {
                val provCmangos = provenance["cmangos_commit"] as? String
                val provPlayerbots = provenance["playerbots_commit"] as? String
                if (provCmangos != lockCmangos) {
                    reasons += "BUILD_PROVENANCE.json cmangos_commit '$provCmangos' != " +
                        "lockfile pin '$lockCmangos' (${stagingRelativeDir.get()})"
                }
                if (provPlayerbots != lockPlayerbots) {
                    reasons += "BUILD_PROVENANCE.json playerbots_commit '$provPlayerbots' " +
                        "!= lockfile pin '$lockPlayerbots' (${stagingRelativeDir.get()})"
                }
                val provPins = artifactPins(provenance).associateBy { File(it.first).name }
                for ((path, size, sha256) in pins) {
                    val prov = provPins[File(path).name]
                    if (prov == null) {
                        reasons += "BUILD_PROVENANCE.json has no record for $path"
                    } else if (prov.second != size || !prov.third.equals(sha256, true)) {
                        reasons += "BUILD_PROVENANCE.json records ${File(path).name} as " +
                            "${prov.second} bytes / sha256 ${prov.third}, but the lockfile " +
                            "pins $size bytes / sha256 $sha256"
                    }
                }
            }
            val stagedFiles = stagingLibsDir
                .listFiles { file -> file.isFile && file.name.endsWith(".so") }
                ?.toList().orEmpty()
            val stagedByName = stagedFiles.associateBy { it.name }
            for ((path, size, sha256) in pins) {
                val staged = stagedByName[File(path).name]
                if (staged == null || staged.length() == 0L) {
                    reasons += "staged ${File(path).name} is missing or empty under " +
                        "${stagingRelativeDir.get()}/jniLibs/$abi (lockfile pins $size " +
                        "bytes, sha256 $sha256)"
                    continue
                }
                if (staged.length() != size) {
                    reasons += "staged ${staged.name} size ${staged.length()} != lockfile " +
                        "pin $size (${stagingRelativeDir.get()}): the staged .so is STALE " +
                        "relative to the lockfile lane pins"
                }
                val actualSha256 = sha256Hex(staged)
                if (!actualSha256.equals(sha256, true)) {
                    reasons += "staged ${staged.name} sha256 $actualSha256 != lockfile pin " +
                        "$sha256 (${stagingRelativeDir.get()}): the staged .so is STALE " +
                        "relative to the lockfile lane pins"
                }
            }
            val pinnedNames = pins.map { File(it.first).name }.toSet()
            stagedFiles.filter { it.name !in pinnedNames }.forEach { staged ->
                reasons += "staged ${staged.name} has no lockfile pin; unpinned native " +
                    "bytes must not ride into the APK " +
                    "(${stagingRelativeDir.get()}/jniLibs/$abi)"
            }
        }
        if (reasons.isNotEmpty()) {
            fail(reasons)
        }
        logger.lifecycle(
            "Native runtime freshness: OK for ${laneLabel.get()} " +
                "(mode=${if (requireStagedBytes.get()) "staged-bytes" else "pins-only"}, " +
                "${pins.size} pinned artifacts verified against $lockfileRelativePath)",
        )
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

// Static analysis, non-blocking (baseline captures existing
// findings; tighten over time instead of starting from zero).
detekt {
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
}

// There is intentionally no generic "allow physical devices" switch. The
// only physical path is this exact, loudly named root task, and it selects one
// non-destructive qualification class instead of the general instrumentation
// suite. Abbreviations and indirect dependencies do not activate the path.
val rp6HardwareQualificationTaskName = "rp6HardwareQualificationAndroidTest"
val rp6HardwareQualificationTestClass =
    "com.pocketrealm.client.ClientActivityManifestTest"
val rp6HardwareQualificationRequested = gradle.startParameter.taskNames.any { requested ->
    requested.substringAfterLast(':') == rp6HardwareQualificationTaskName
}
val connectedTestSerialOptionPresent = gradle.startParameter.taskRequests
    .flatMap { request -> request.args }
    .any { argument -> argument == "--serial" || argument.startsWith("--serial=") }

// Every APK build is a single-ABI build. Requiring the property avoids an
// accidental universal APK and keeps x86_64/ARM64 native outputs disjoint.
val supportedPocketAbis = setOf("x86_64", "arm64-v8a")
val supportedPocketLanes = setOf("full", "database")
val pocketAbi = providers.gradleProperty("pocketAbi").orNull?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: throw GradleException(
        "Missing required -PpocketAbi=<abi>; supported values: " +
            supportedPocketAbis.sorted().joinToString(),
    )
if (pocketAbi !in supportedPocketAbis) {
    throw GradleException(
        "Unsupported pocketAbi '$pocketAbi'; supported values: " +
            supportedPocketAbis.sorted().joinToString(),
    )
}
val pocketLane = providers.gradleProperty("pocketLane").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "full"
if (pocketLane !in supportedPocketLanes) {
    throw GradleException(
        "Unsupported pocketLane '$pocketLane'; supported values: " +
            supportedPocketLanes.sorted().joinToString(),
    )
}
// Dual-provider selection: with -PsqliteProvider the realm runtime comes
// from the SQLite sibling staging root (validated against the sibling
// lockfile schemas/realm-runtime-lockfile-<abi>-sqlite.json) and the seed
// transcripts ship as compressed assets. Unset (default) keeps the
// MariaDB-provider behavior byte-identical — that unchanged boot IS the
// rollback path. The MariaDB closure stays staged in BOTH modes: the old
// provider must still boot to export the sealed datadir.
val sqliteProvider = providers.gradleProperty("sqliteProvider").isPresent
// -PdifferentialTestLane is the differential parity build's explicit,
// separately named escape — it permits x86_64+sqliteProvider for
// DEBUG-type assemblies only (the resulting APK is never shippable; the
// shipping refusals below stay intact for every release-type variant).
val differentialTestLane = providers.gradleProperty("differentialTestLane").isPresent
if (differentialTestLane && pocketLane != "full") {
    throw GradleException(
        "refusing -PdifferentialTestLane for pocketLane=$pocketLane (full lane only)",
    )
}
if (sqliteProvider && pocketLane != "full") {
    // Only the full lane packages/validates the sqlite provider; refuse
    // the silently-inert combination instead of ignoring the flag.
    throw GradleException(
        "refusing -PsqliteProvider for pocketLane=$pocketLane (full lane only)",
    )
}
if (sqliteProvider && pocketAbi != "arm64-v8a" && !differentialTestLane) {
    throw GradleException(
        "The sqlite provider window is arm64-first; " +
            "refusing -PsqliteProvider for pocketAbi=$pocketAbi " +
            "(the x86_64 differential lane must ALSO pass -PdifferentialTestLane)",
    )
}
if (differentialTestLane && !sqliteProvider) {
    throw GradleException(
        "-PdifferentialTestLane exists only to permit x86_64 sqliteProvider " +
            "assemblies; pass both properties together",
    )
}
if (differentialTestLane && gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
    // The lane APK is NEVER shippable: debug-type assemblies only.
    throw GradleException(
        "-PdifferentialTestLane permits debug-type assemblies only; " +
            "a release build with the differential lane is a shipping-refusal violation",
    )
}
if (differentialTestLane && gradle.startParameter.taskNames.any {
        // Aggregate requests resolve release-type tasks through the task
        // graph without any requested name containing "Release" — refuse
        // them too. Qualified spellings keep their last path segment;
        // the Needed/Dependents lifecycle aggregates resolve release
        // work as well.
        val name = it.substringAfterLast(':')
        name.equals("build", ignoreCase = true) || name.equals("assemble", ignoreCase = true) ||
            name.equals("bundle", ignoreCase = true) || name.equals("check", ignoreCase = true) ||
            name.equals("test", ignoreCase = true) || name.equals("lint", ignoreCase = true) ||
            name.equals("assembleAndroidTest", ignoreCase = true) ||
            name.equals("buildNeeded", ignoreCase = true) || name.equals("buildDependents", ignoreCase = true) ||
            name.equals("assembleNeeded", ignoreCase = true) || name.equals("assembleDependents", ignoreCase = true) ||
            name.startsWith("connected", ignoreCase = true) || name.startsWith("install", ignoreCase = true)
    }) {
    throw GradleException(
        "-PdifferentialTestLane permits debug-type assemblies only; aggregate " +
            "tasks (build/assemble/bundle/check/test/lint/androidTest/needed/" +
            "dependents/connected*/install*) can resolve release-type work — " +
            "request debug-type tasks explicitly",
    )
}
if (pocketLane == "database" && pocketAbi != "arm64-v8a") {
    throw GradleException("The database-only lane is for the live arm64-v8a device; use pocketLane=full for x86_64")
}

// Existing native builders use the target triple in their root name
// (.build-arm64), while APK-facing directories retain the Android ABI name.
val pocketNativeRootSuffix = when (pocketAbi) {
    "x86_64" -> "x86_64"
    "arm64-v8a" -> "arm64"
    else -> error("validated above")
}
// build_proot.py uses the architecture triple for its isolated root on ARM
// (`aarch64`), while the other Android-native builders use `arm64`.
val pocketProotRootSuffix = when (pocketAbi) {
    "x86_64" -> "x86_64"
    "arm64-v8a" -> "aarch64"
    else -> error("validated above")
}
val pocketElfMachine = when (pocketAbi) {
    "x86_64" -> 62 // EM_X86_64
    "arm64-v8a" -> 183 // EM_AARCH64
    else -> error("validated above")
}
val pocketCompat32ElfMachine = when (pocketAbi) {
    "x86_64" -> 3 // EM_386
    "arm64-v8a" -> 40 // EM_ARM
    else -> error("validated above")
}
val pocketNdkLibraryTriple = when (pocketAbi) {
    "x86_64" -> "x86_64-linux-android"
    "arm64-v8a" -> "aarch64-linux-android"
    else -> error("validated above")
}

// ---------------------------------------------------------------------------
// Native runtime freshness: lockfile-derived telltale + staleness fence.
//
// The staged realm runtime enters the APK through stageNativeLibs (the
// realmStage source below). The fences registered here verify the staged
// bytes and provenance against the reviewed lane lockfile before anything
// packages the libs, and the same pins are baked into BuildConfig as a
// runtime telltale (relay ping / world-status runtimeBuildId) so a harness
// session detects a stale APK at attach time instead of failing mid-run on
// missing JNI ops.
// ---------------------------------------------------------------------------

// Lockfile selection mirrors validateRealmRuntime exactly: x86_64 has no ABI
// suffix, arm64 does, and the sqlite provider selects the sibling lockfile.
val realmLockfileRelativePath = when {
    sqliteProvider && pocketAbi == "x86_64" -> "schemas/realm-runtime-lockfile-sqlite.json"
    sqliteProvider -> "schemas/realm-runtime-lockfile-$pocketAbi-sqlite.json"
    pocketAbi == "x86_64" -> "schemas/realm-runtime-lockfile.json"
    else -> "schemas/realm-runtime-lockfile-$pocketAbi.json"
}
val realmStagingRelativeDir = "native/.build-o09-$pocketAbi/" +
    (if (sqliteProvider) "realm-staging-sqlite" else "realm-staging")

// providers.fileContents keeps these configuration-time reads tracked by the
// configuration cache: a lockfile or sources.json content change invalidates
// the cached configuration instead of silently reusing stale pins (the exact
// failure mode this fence kills, reborn at the configuration layer).
val repoRootForNativePins = layout.projectDirectory.dir("../..")
fun trackedJsonMap(relativePath: String): Map<*, *> {
    val file = objects.fileProperty()
    file.set(repoRootForNativePins.file(relativePath))
    val text = providers.fileContents(file).asText.get()
    check(text.isNotBlank()) {
        "Required native-provenance JSON is missing or empty: $relativePath"
    }
    return runCatching { JsonSlurper().parseText(text) as Map<*, *> }.getOrElse {
        throw GradleException(
            "Required native-provenance JSON is not parseable: $relativePath",
            it,
        )
    }
}

val realmLockfilePins = trackedJsonMap(realmLockfileRelativePath)
val realmDatabaseBackend = realmLockfilePins["database_backend"] as? String
    ?: throw GradleException(
        "$realmLockfileRelativePath has no database_backend pin; the lane lockfile is malformed",
    )
val expectedDatabaseBackend = if (sqliteProvider) "sqlite" else "mysql"
if (realmLockfilePins["abi"] != pocketAbi) {
    throw GradleException(
        "$realmLockfileRelativePath pins abi '${realmLockfilePins["abi"]}' but the build " +
            "selected -PpocketAbi=$pocketAbi",
    )
}
if (realmDatabaseBackend != expectedDatabaseBackend) {
    throw GradleException(
        "$realmLockfileRelativePath pins database_backend '$realmDatabaseBackend' but the " +
            "build selected '$expectedDatabaseBackend'",
    )
}
val realmCmangosCommit = realmLockfilePins["cmangos_commit"] as? String
    ?: throw GradleException("$realmLockfileRelativePath has no cmangos_commit pin")
val realmPlayerbotsCommit = realmLockfilePins["playerbots_commit"] as? String
    ?: throw GradleException("$realmLockfileRelativePath has no playerbots_commit pin")
val realmWorldRuntimePin = (realmLockfilePins["artifacts"] as? List<*>).orEmpty()
    .mapNotNull { it as? Map<*, *> }
    .firstOrNull { (it["path"] as? String)?.endsWith("libpocket_world_runtime.so") == true }
    ?: throw GradleException(
        "$realmLockfileRelativePath has no libpocket_world_runtime.so artifact pin",
    )
val realmWorldRuntimeSha256 = realmWorldRuntimePin["sha256"] as? String
    ?: throw GradleException(
        "$realmLockfileRelativePath libpocket_world_runtime.so pin has no sha256",
    )
// Changes whenever the pinned .so bytes OR the source-commit pins change, so
// a harness can compare it against the id derivable from the CURRENT
// lockfile and reject a stale APK at attach time.
val nativeRuntimeBuildId = "o09-$pocketAbi-$realmDatabaseBackend-cmangos-" +
    "${realmCmangosCommit.take(8)}-playerbots-${realmPlayerbotsCommit.take(8)}-" +
    realmWorldRuntimeSha256.take(12)

val sourcesPinsJson = trackedJsonMap("schemas/sources.json")
fun sourcesCommitPin(sourceId: String): String =
    (sourcesPinsJson["sources"] as? List<*>).orEmpty()
        .mapNotNull { it as? Map<*, *> }
        .firstOrNull { it["id"] == sourceId }
        ?.let { it["commit"] as? String }
        ?: throw GradleException("schemas/sources.json has no commit pin for '$sourceId'")
val pinnedCmangosCommit = sourcesCommitPin("cmangos-core")
val pinnedPlayerbotsCommit = sourcesCommitPin("playerbots")

// Pins-only fence: verifies the committed lockfile stays coherent with
// schemas/sources.json. Runs on every compile; the CI lane
// (:app:testDebugUnitTest :app:detekt) never packages the untracked staging
// outputs, so this is the strongest staleness check a fresh checkout can
// make, and it fails CI when a lane lockfile was written from stale source
// pins.
val validateNativeRuntimePins = tasks.register<ValidateNativeRuntimeFreshnessTask>(
    "validateNativeRuntimePins",
) {
    group = "verification"
    description = "Verify the realm runtime lockfile identity/source pins against " +
        "schemas/sources.json (no staged bytes required)."
    selectedAbi.set(pocketAbi)
    databaseBackend.set(expectedDatabaseBackend)
    laneLabel.set("$pocketAbi/$pocketLane pins")
    repoRootPath.set(repoRootForNativePins.asFile.absolutePath)
    lockfileRelativePath.set(realmLockfileRelativePath)
    stagingRelativeDir.set(realmStagingRelativeDir)
    expectedCmangosCommit.set(pinnedCmangosCommit)
    expectedPlayerbotsCommit.set(pinnedPlayerbotsCommit)
    requireStagedBytes.set(false)
    skipRequested.set(providers.gradleProperty("pocketSkipNativeFreshness").orElse(""))
}

// Full staged-bytes fence: wired ahead of every path that packages the realm
// libs (stageNativeLibs plus the per-variant merge/package/assemble edges
// below); a stale staging directory fails the build before any bytes move.
val validateNativeRuntimeFreshness = tasks.register<ValidateNativeRuntimeFreshnessTask>(
    "validateNativeRuntimeFreshness",
) {
    group = "verification"
    description = "Fail the build when the staged realm runtime .so bytes or provenance " +
        "disagree with the reviewed lane lockfile."
    selectedAbi.set(pocketAbi)
    databaseBackend.set(expectedDatabaseBackend)
    laneLabel.set("$pocketAbi/$pocketLane staged bytes")
    repoRootPath.set(repoRootForNativePins.asFile.absolutePath)
    lockfileRelativePath.set(realmLockfileRelativePath)
    stagingRelativeDir.set(realmStagingRelativeDir)
    expectedCmangosCommit.set(pinnedCmangosCommit)
    expectedPlayerbotsCommit.set(pinnedPlayerbotsCommit)
    requireStagedBytes.set(true)
    skipRequested.set(providers.gradleProperty("pocketSkipNativeFreshness").orElse(""))
}

tasks.matching { task ->
    task.name.startsWith("compile") &&
        (task.name.endsWith("Kotlin") || task.name.endsWith("JavaWithJavac"))
}.configureEach {
    dependsOn(validateNativeRuntimePins)
}

android {
    namespace = "com.pocketrealm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketrealm"
        minSdk = 26
        // Legacy target on purpose: targetSdk >= 29 places the app in the
        // strict untrusted_app SELinux domain, whose policy denies execmod on
        // app_data_file. Wine's PE loader maps x86_64-windows DLLs privately,
        // applies relocations, then mprotects .text to PROT_EXEC - the
        // modify-then-execute pattern SELinux gates with execmod. Verified on
        // Pixel 6a / Android 17 (avc denied { execmod } ntdll.dll, wine exits
        // 1 in ~4s); Android 13 (Retroid) policy allows it. targetSdk 27 runs
        // the app in the legacy permissive domain, matching how Winlator and
        // other on-device emulators ship.
        targetSdk = (project.findProperty("pocketTargetSdk") as String?)?.toInt() ?: 27
        // Bump-on-release discipline (update manifests compare codes).
        versionCode = 8
        versionName = "0.103.0-alpha"
        // Two-APK distribution: the default build is the stable
        // MariaDB-provider APK; -PsqliteProvider builds the experimental
        // SQLite-provider APK with a visible versionName suffix so the two
        // artifacts are distinguishable. The applicationId stays shared on
        // purpose - the SQLite APK must still boot the MariaDB provider to
        // export the sealed datadir, which reads the existing install's
        // data directory.
        versionNameSuffix = if (sqliteProvider) "-sqlite-experimental" else null
        buildConfigField(
            "boolean", "ENABLE_CLIENT_DATA_PREPARATION", (pocketLane == "full").toString(),
        )
        // Runtime staleness telltale generated from the reviewed lane
        // lockfile (never hand-maintained): changes whenever the pinned .so
        // bytes or source-commit pins change, so a harness comparing relay
        // ping/world-status runtimeBuildId against the current lockfile
        // catches a stale APK at attach time. Verified at build time by
        // validateNativeRuntimeFreshness/validateNativeRuntimePins.
        buildConfigField("String", "NATIVE_RUNTIME_BUILD_ID", "\"$nativeRuntimeBuildId\"")
        buildConfigField("String", "NATIVE_CMANGOS_COMMIT", "\"$realmCmangosCommit\"")
        buildConfigField("String", "NATIVE_PLAYERBOTS_COMMIT", "\"$realmPlayerbotsCommit\"")
        buildConfigField("String", "NATIVE_WORLD_RUNTIME_SHA256", "\"$realmWorldRuntimeSha256\"")
        if (rp6HardwareQualificationRequested) {
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            testInstrumentationRunnerArguments["class"] = rp6HardwareQualificationTestClass
        }

        // Qualification APKs are always explicit, single-ABI artifacts.
        ndk {
            abiFilters += pocketAbi
        }
    }

    // Executing an APK-packaged PIE launcher from nativeLibraryDir requires
    // AGP to extract native libraries to disk with executable permissions
    // (the packaging policy every build type gets below). The pkgExperiment
    // build type remains for regression qualification.
    buildTypes {
        getByName("debug") {
            // Ships with the same extraction policy as release (no overrides).
        }
        getByName("release") {
            isMinifyEnabled = false
            // Signature continuity: the installed builds are debug-signed,
            // and an in-place update must carry the same signature or Android
            // refuses it (the refusal protects the app's data). No dedicated
            // release keystore exists yet; until one is provisioned,
            // release builds sign with the debug keystore (path overridable
            // via POCKET_RELEASE_KEYSTORE etc.). No keystore material is
            // committed.
            val keystorePath = providers.gradleProperty("pocketReleaseKeystore")
                .orElse(providers.environmentVariable("POCKET_RELEASE_KEYSTORE").orElse(""))
                .get().ifBlank { null }
            if (keystorePath != null) {
                signingConfig = signingConfigs.create("pocketRelease") {
                    storeFile = file(keystorePath)
                    storePassword = providers.gradleProperty("pocketReleaseStorePassword")
                        .orElse(providers.environmentVariable("POCKET_RELEASE_STORE_PASSWORD").orElse(""))
                        .get()
                    keyAlias = providers.gradleProperty("pocketReleaseKeyAlias")
                        .orElse(providers.environmentVariable("POCKET_RELEASE_KEY_ALIAS").orElse(""))
                        .get()
                    keyPassword = providers.gradleProperty("pocketReleaseKeyPassword")
                        .orElse(providers.environmentVariable("POCKET_RELEASE_KEY_PASSWORD").orElse(""))
                        .get()
                }
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("pkgExperiment") {
            initWith(getByName("debug"))
            // The pocketrealm-llm library publishes only debug/release; all
            // custom lanes resolve their library dependency against debug.
            matchingFallbacks += listOf("debug")
            // Packaging qualification variant.
            isJniDebuggable = true
        }
        create("clientRuntime") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            // Qualified x86DirectWine product lane (the client runtime).
            isJniDebuggable = true
        }
        create("databaseRuntime") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            // The MariaDB/glibc ELFs execute from nativeLibraryDir, using the
            // same qualified immutable-code packaging model as clientRuntime.
            isJniDebuggable = true
        }
        create("realmRuntime") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            // Realm runtime lane: the executable MariaDB provider plus
            // Android-native realmd/world libraries in separate processes.
            isJniDebuggable = true
        }
    }

    // APK-managed PIE executables must be real, executable files beneath
    // nativeLibraryDir. A global policy is the smallest sound rule because the
    // selected full/database lane is configured before variants are created and
    // all of its build types package the same executable native closure.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Runtime providers are pinned and verified as complete ELF
            // artifacts. AGP's strip transform changes their bytes after the
            // lockfile gate (and can alter loader behavior), so preserve every
            // staged .so exactly as hashed by the Wine/MariaDB/realm manifests.
            keepDebugSymbols += "**/*.so"
        }
        // The :pocketrealm-llm module ships the embedded llama-server runtime
        // for the AI bot LLM feature. The database qualification lane must
        // stay free of the llama/Hexagon closure (the same separation the
        // staged-jniLibs discipline enforces for the app's own llama libs).
        // The module's DSP skels/build-info assets are excluded at the module
        // level for non-full / non-arm64 lanes (packaging.resources does not
        // govern the asset merger). The :llm feature itself degrades
        // gracefully in those lanes (HexagonProbe reports the libs missing).
        if (pocketLane == "database") {
            jniLibs.excludes += listOf(
                "**/libllamaserver.so",
                "**/libllama-server-impl.so",
                "**/libmtmd.so",
                "**/libggmlhex.so",
                "**/libllmexec.so",
            )
        }
    }

    sourceSets {
        getByName("main") {
            // Deterministic staged native closure (see stageNativeLibs task).
            // Never references ../../../native — staged inside the module.
            val stagedJniDir = if (pocketLane == "full") {
                "build/staged-jniLibs-$pocketAbi"
            } else {
                "build/staged-jniLibs-$pocketAbi-$pocketLane"
            }
            jniLibs.srcDir(stagedJniDir)
            // Wine-owned PE modules as APK assets (the hash-verified
            // guest-code cache source). Generated by tools/stage_wine_runtime.py
            // into native/.build-<selected-target>/wine-staging/assets/.
            assets.srcDir("../../native/.build-$pocketNativeRootSuffix/wine-staging/assets")
            if (pocketLane == "full") {
                // The normal debug/release product starts the database too;
                // its bootstrap, provider manifest, and migrations therefore
                // belong to the full source set, not only qualification types.
                assets.srcDir("../../native/.build-$pocketNativeRootSuffix/mariadb-staging/assets")
            }
            if (pocketLane == "full" && sqliteProvider) {
                // The SQLite provider's seed transcripts (gzip,
                // digest-pinned by the staging build's lockfile).
                // Lane-gated alongside the MariaDB assets above:
                // only the full lane's variants run validateRealmRuntime,
                // so only they may package the seed bytes.
                assets.srcDir("../../native/.build-o09-$pocketAbi/realm-staging-sqlite/assets")
            }
            // Winlator X-server (vendored at ca3d735; trimmed/stubbed).
            // See docs/patches/wine-provider-provenance.md for the trim list.
            java.srcDir("../../runtime/xserver-winlator")
        }
        getByName("databaseRuntime") {
            // Generated, deterministic provider support data + gzip migration
            // inputs. Executable code remains in nativeLibraryDir.
            assets.srcDir("../../native/.build-$pocketNativeRootSuffix/mariadb-staging/assets")
        }
        getByName("realmRuntime") {
            assets.srcDir("../../native/.build-$pocketNativeRootSuffix/mariadb-staging/assets")
            // The large-lane device preparation test imports a user-supplied
            // client through the bounded debug DocumentsProvider. Keep this
            // provider out of release/main while making it available to the
            // custom realmRuntime build type used by instrumentation.
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // IPkgIsolation AIDL crosses the :pkg process boundary.
        aidl = true
    }

    testOptions {
        // Instrumented tests run against the real supervisor/service on-device;
        // animations are disabled for deterministic lifecycle observation.
        animationsDisabled = true
        // Host JVM unit tests touch android.util.Log via AppLog; return defaults
        // rather than throwing "not mocked" so model/unit tests stay pure-JVM.
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

val validateEmulatorConnectedTestTarget =
    tasks.register<ValidateConnectedAndroidTestTargetTask>(
        "validateEmulatorConnectedTestTarget",
    ) {
        group = "verification"
        description = "Fail closed unless ANDROID_SERIAL names one emulator target."
        targetSerial.set(providers.environmentVariable("ANDROID_SERIAL").orElse(""))
        hardwareQualification.set(false)
        hardwareAllowlistPath.set("")
        hardwareAcknowledgement.set("")
        selectedAbi.set(pocketAbi)
        safeTestClass.set(rp6HardwareQualificationTestClass)
        requestedInstrumentationArguments.set(emptyMap())
        taskSerialOptionPresent.set(connectedTestSerialOptionPresent)
    }

val validateRp6HardwareQualificationTarget =
    tasks.register<ValidateConnectedAndroidTestTargetTask>(
        "validateRp6HardwareQualificationTarget",
    ) {
        group = "verification"
        description = "Require an allowlisted serial and destructive-device acknowledgement."
        targetSerial.set(providers.environmentVariable("ANDROID_SERIAL").orElse(""))
        hardwareQualification.set(true)
        hardwareAllowlistPath.set(
            File(
                gradle.gradleUserHomeDir,
                "pocket-realm-hardware-qualification-serials.txt",
            ).absolutePath,
        )
        hardwareAcknowledgement.set(
            providers.gradleProperty("pocketHardwareQualificationAcknowledgement").orElse(""),
        )
        selectedAbi.set(pocketAbi)
        safeTestClass.set(rp6HardwareQualificationTestClass)
        requestedInstrumentationArguments.set(
            providers.gradlePropertiesPrefixedBy("android.testInstrumentationRunnerArguments."),
        )
        taskSerialOptionPresent.set(connectedTestSerialOptionPresent)
    }

// AGP performs device discovery, installation and cleanup inside its connected
// task action. This dependency therefore runs first, and a rejected target is
// never handed to AGP. The physical gate is reachable only when the exact RP6
// wrapper task was a root request; flags cannot unlock an ordinary connected
// task.
tasks.matching {
    it.name.startsWith("connected") && it.name.endsWith("AndroidTest")
}.configureEach {
    dependsOn(
        if (rp6HardwareQualificationRequested) {
            validateRp6HardwareQualificationTarget
        } else {
            validateEmulatorConnectedTestTarget
        },
    )
}

tasks.register(rp6HardwareQualificationTaskName) {
    group = "verification"
    description =
        "DANGER: run only the narrow RP6 manifest/input qualification on an allowlisted device."
    dependsOn("connectedDebugAndroidTest")
}

// Resolve the NDK via the same ndk-link junction build_native.py creates, then
// fall back to a discovered ndk/<version> dir. Used only to locate
// libc++_shared.so for the staged closure.
val sdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
    .orElse(providers.environmentVariable("ANDROID_HOME")).orElse("")
val ndkLinkPath = sdkRoot.map { "$it/ndk-link" }
val ndkVersionsPath = sdkRoot.map { "$it/ndk" }
val ndkRootProvider = provider {
    val link = File(ndkLinkPath.get())
    if (link.isDirectory) return@provider link
    val versionsDir = File(ndkVersionsPath.get())
    if (versionsDir.isDirectory) {
        versionsDir.listFiles()?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }?.let { return@provider it }
    }
    error("NDK not found under ANDROID_SDK_ROOT/ndk[-link]. Run scripts/build_native.py first.")
}

val verifyGeneratedVulkanDriverCatalog = tasks.register<Exec>(
    "verifyGeneratedVulkanDriverCatalog",
) {
    group = "verification"
    description = "Verify Kotlin Vulkan identities are generated from the curated catalog."
    val repoRoot = layout.projectDirectory.dir("../..").asFile
    workingDir(repoRoot)
    commandLine("python", "tools/generate_vulkan_driver_catalog.py", "--check")
    inputs.files(
        File(repoRoot, "schemas/vulkan-driver-catalog.json"),
        File(repoRoot, "tools/generate_vulkan_driver_catalog.py"),
        File(repoRoot,
            "android/app/src/main/java/com/pocketrealm/client/GeneratedVulkanDriverCatalog.kt"),
    )
}

tasks.matching { task ->
    task.name.startsWith("compile") &&
        (task.name.endsWith("Kotlin") || task.name.endsWith("JavaWithJavac"))
}.configureEach {
    dependsOn(verifyGeneratedVulkanDriverCatalog)
}

val verifyGeneratedCommunityVulkanDrivers = tasks.register<Exec>(
    "verifyGeneratedCommunityVulkanDrivers",
) {
    group = "verification"
    description =
        "Verify the community driver list is generated from the reviewed manifest."
    val repoRoot = layout.projectDirectory.dir("../..").asFile
    workingDir(repoRoot)
    commandLine("python", "tools/generate_community_vulkan_drivers.py", "--check")
    inputs.files(
        File(repoRoot, "schemas/community-vulkan-drivers.json"),
        File(repoRoot, "tools/generate_community_vulkan_drivers.py"),
        File(repoRoot,
            "android/app/src/main/java/com/pocketrealm/client/GeneratedCommunityVulkanDrivers.kt"),
    )
}

tasks.matching { task ->
    task.name.startsWith("compile") &&
        (task.name.endsWith("Kotlin") || task.name.endsWith("JavaWithJavac"))
}.configureEach {
    dependsOn(verifyGeneratedCommunityVulkanDrivers)
}

val buildVortekGuest = if (pocketAbi == "arm64-v8a" && pocketLane == "full") {
    tasks.register<Exec>("buildVortekGuest") {
        group = "pocket realm"
        description = "Build the pinned Vortek 2.1 glibc guest with bounded map diagnostics."
        val repoRoot = layout.projectDirectory.dir("../..").asFile
        workingDir(repoRoot)
        commandLine("python", "tools/build_vortek_guest.py")
        inputs.files(
            File(repoRoot, "tools/build_vortek_guest.py"),
            File(repoRoot, "schemas/vulkan-driver-catalog.json"),
        )
        inputs.files(
            File(repoRoot,
                "native/xserver-winlator/cpp/vortekrenderer-winlator-2.1/include/vulkan/vulkan.h"),
            File(repoRoot,
                "native/xserver-winlator/cpp/vortekrenderer-winlator-2.1/include/vulkan/vulkan_core.h"),
            File(repoRoot,
                "native/xserver-winlator/cpp/vortekrenderer-winlator-2.1/include/vulkan/vk_platform.h"),
        )
        outputs.files(
            File(repoRoot, "native/.build-arm64/vortek-guest/libvulkan_vortek.so"),
            File(repoRoot, "native/.build-arm64/vortek-guest/BUILD_PROVENANCE.json"),
        )
    }
} else null

val stageRendererPackages = if (pocketAbi == "arm64-v8a" && pocketLane == "full") {
    tasks.register<Exec>("stageRendererPackages") {
        group = "pocket realm"
        description = "Stage the pinned, closed ARM DXVK package catalog."
        val repoRoot = layout.projectDirectory.dir("../..").asFile
        workingDir(repoRoot)
        commandLine("python", "tools/stage_renderer_packages.py")
        inputs.files(
            File(repoRoot, "tools/stage_renderer_packages.py"),
            File(repoRoot, "schemas/vulkan-driver-catalog.json"),
        )
        inputs.files(
            File(repoRoot, "native/.providers-extracted/winlator-app-ca3d735/app/src/main/assets/dxwrapper/dxvk-2.4.1.tzst"),
            File(repoRoot, "native/.providers-extracted/winlator-app-ca3d735/app/src/main/assets/dxwrapper/dxvk-1.10.3.tzst"),
            File(repoRoot, "native/.providers-extracted/winlator-app-ca3d735/app/src/main/assets/graphics_driver/turnip-26.1.0.tzst"),
            File(repoRoot, "native/.providers-extracted/winlator-app-ca3d735/app/src/main/assets/graphics_driver/vortek-2.1.tzst"),
            File(repoRoot, "native/.build-arm64/vortek-guest/libvulkan_vortek.so"),
            File(repoRoot, "native/.build-arm64/vortek-guest/BUILD_PROVENANCE.json"),
        )
        outputs.dir(File(repoRoot,
            "native/.build-arm64/wine-staging/assets/arm-translated/renderer-packages"))
        outputs.dir(File(repoRoot,
            "native/.build-arm64/wine-staging/assets/arm-translated/vulkan-drivers"))
        buildVortekGuest?.let { dependsOn(it) }
    }
} else null

val buildGladioClientArm = if (pocketAbi == "arm64-v8a" && pocketLane == "full") {
    tasks.register<Exec>("buildGladioClientArm") {
        group = "pocket realm"
        description = "Build and stage the pinned Box64/glibc Gladio client."
        val repoRoot = layout.projectDirectory.dir("../..").asFile
        workingDir(repoRoot)
        commandLine("python", "tools/build_gladio_client.py", "--abi", "arm64-v8a")
        inputs.files(
            File(repoRoot, "tools/build_gladio_client.py"),
            File(repoRoot, "native/xserver-winlator/cpp/gladiorenderer"),
        )
        outputs.files(
            File(repoRoot, "native/.build-arm64/gladio-client/libGL.so.1"),
            File(repoRoot, "native/.build-arm64/gladio-client/BUILD_PROVENANCE.json"),
            File(repoRoot,
                "native/.build-arm64/wine-staging/assets/arm-translated/renderer-packages/" +
                    "box64-gladio-eaa2a8d/libGL.so.1"),
        )
        // The DXVK catalog producer replaces renderer-packages atomically.
        // Re-publish Gladio afterwards so neither task can erase the other.
        stageRendererPackages?.let { dependsOn(it) }
    }
} else null

val stageVirglRendererArm = if (pocketAbi == "arm64-v8a" && pocketLane == "full") {
    tasks.register<Exec>("stageVirglRendererArm") {
        group = "pocket realm"
        description = "Stage the pinned ca3d735 Mesa virpipe guest client."
        val repoRoot = layout.projectDirectory.dir("../..").asFile
        workingDir(repoRoot)
        commandLine("python", "tools/stage_virgl_renderer.py")
        inputs.files(
            File(repoRoot, "tools/stage_virgl_renderer.py"),
            File(repoRoot,
                "native/.providers-extracted/winlator-app-ca3d735/app/src/main/assets/" +
                    "graphics_driver/virgl-23.1.9.tzst"),
        )
        outputs.files(
            File(repoRoot,
                "native/.build-arm64/wine-staging/assets/arm-translated/renderer-packages/" +
                    "box64-virgl-23.1.9/BUILD_PROVENANCE.json"),
            File(repoRoot,
                "native/.build-arm64/wine-staging/assets/arm-translated/renderer-packages/" +
                    "box64-virgl-23.1.9/libGL.so.1"),
        )
        // The DXVK catalog producer replaces renderer-packages atomically.
        stageRendererPackages?.let { dependsOn(it) }
    }
} else null

val removeRetiredArmClientAssets = if (pocketAbi == "arm64-v8a" && pocketLane == "full") {
    tasks.register("removeRetiredArmClientAssets") {
        group = "pocket realm"
        description = "Remove retired ARM FEX and legacy unscoped graphics assets before APK merge."
        val repoRoot = layout.projectDirectory.dir("../..").asFile
        doLast {
            val nativeRoot = File(repoRoot, "native").canonicalFile
            val stagingRoot = File(repoRoot, "native/.build-arm64/wine-staging").canonicalFile
            val retired = listOf(
                File(stagingRoot, "assets/arm-translated/fexcore"),
                File(stagingRoot, "assets/arm-translated/libGL.so.1"),
                File(stagingRoot, "assets/arm-translated/turnip"),
                File(stagingRoot, "assets/arm-translated/dxvk"),
                File(stagingRoot, "jniLibs/libpocket_zstd.so"),
                File(stagingRoot, "jniLibs/libpocket_zstd_exec.so"),
                File(nativeRoot, ".providers-extracted/fexcore-arm64ec"),
                File(nativeRoot, ".fex-inspect"),
                File(nativeRoot, ".obsolete-standalone-fex"),
                File(nativeRoot, ".build-arm64-bionic/gladio-client"),
            )
            retired.forEach { target ->
                check(target.canonicalFile.toPath().startsWith(nativeRoot.toPath())) {
                    "Refusing to remove an asset outside the native workspace: $target"
                }
                if (target.exists()) check(target.deleteRecursively()) {
                    "Retired ARM provider asset could not be removed: $target"
                }
            }
            check(!File(stagingRoot, "assets/arm-translated/turnip").exists() &&
                !File(stagingRoot, "assets/arm-translated/dxvk").exists()) {
                "retired ARM graphics assets remain outside the closed catalogs"
            }
        }
    }
} else null

val validateSelectedNativeClosure = tasks.register<ValidateSelectedNativeClosureTask>(
    "validateSelectedNativeClosure",
) {
    group = "pocket realm"
    description = "Fail fast when the $pocketAbi native closure is missing or cross-ABI."
    selectedAbi.set(pocketAbi)
    repoRootPath.set(layout.projectDirectory.dir("../..").asFile.absolutePath)
    nativeRootSuffix.set(pocketNativeRootSuffix)
    prootRootSuffix.set(pocketProotRootSuffix)
    expectedMachine.set(pocketElfMachine)
    compat32Machine.set(pocketCompat32ElfMachine)
    lane.set(pocketLane)
    sqliteProvider.set(providers.gradleProperty("sqliteProvider").isPresent)
    dependsOn(verifyGeneratedVulkanDriverCatalog)
    stageRendererPackages?.let { dependsOn(it) }
    buildGladioClientArm?.let { dependsOn(it) }
    stageVirglRendererArm?.let { dependsOn(it) }
    removeRetiredArmClientAssets?.let { dependsOn(it) }
}

stageRendererPackages?.let {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
        .configureEach { dependsOn(validateSelectedNativeClosure) }
    // Lint models read the generated main asset source directly, before merge
    // tasks. Give every lint/model/vital task the same producer + closure gate
    // so clean release/configuration-cache builds never observe missing assets.
    tasks.matching { task -> task.name.contains("lint", ignoreCase = true) }
        .configureEach { dependsOn(it, validateSelectedNativeClosure) }
}

// Stages the complete native dependency closure into an ABI-isolated build root.
// APK assembly depends on this task. Packages only app-supplied libs (the realm
// facade, the packaging JNI shim, the PIE launcher, the Wine spike helper,
// and the Wine/glibc ELFs); platform libs (libc/libm/libdl/liblog) are supplied
// by Android, never staged (except the glibc closure, which is a SEPARATE
// Linux/glibc namespace — those are Wine's deps, not Android's).
val stageNativeLibs by tasks.registering(Sync::class) {
    group = "pocket realm"
    description = "Stage the native .so closure for APK packaging ($pocketAbi/$pocketLane)."

    val repoRoot = layout.projectDirectory.dir("../..").asFile
    val nativeBuildRoot = File(repoRoot, "native/.build-$pocketNativeRootSuffix")
    val rtBuild = File(nativeBuildRoot, "pocket-runtime-build")
    val pkgBuild = File(nativeBuildRoot, "packaging-build")
    val wineSpikeBuild = File(nativeBuildRoot, "wine-spike-build")
    val xserverBuild = File(nativeBuildRoot, "xserver-winlator-build")
    val wineStaging = File(nativeBuildRoot, "wine-staging/jniLibs")
    val prootStage = File(repoRoot, "native/.build-$pocketProotRootSuffix/proot-stage")
    val mariadbStage = File(nativeBuildRoot, "mariadb-staging/jniLibs/$pocketAbi")
    // Under -PsqliteProvider the APK packages the SQLITE-linked realm
    // runtimes from the sibling staging root; the MariaDB closure above
    // stays staged in both modes (the old provider must still boot to
    // export the sealed datadir). Validation derives from the same
    // selection, so the bytes packaged and the bytes validated agree.
    val realmStage = File(
        repoRoot,
        if (sqliteProvider) {
            "native/.build-o09-$pocketAbi/realm-staging-sqlite/jniLibs/$pocketAbi"
        } else {
            "native/.build-o09-$pocketAbi/realm-staging/jniLibs/$pocketAbi"
        },
    )
    val extractorStage = File(repoRoot, "native/.build-o11-$pocketAbi/extractor-staging/jniLibs/$pocketAbi")
    val vanillaTweaksStage = File(repoRoot, "native/.build-vanilla-tweaks-$pocketAbi/staging/jniLibs/$pocketAbi")
    val stagedDirName = if (pocketLane == "full") "staged-jniLibs-$pocketAbi" else "staged-jniLibs-$pocketAbi-$pocketLane"
    val stagedLib = layout.buildDirectory.dir("$stagedDirName/$pocketAbi")
    dependsOn(validateSelectedNativeClosure)
    if (pocketLane == "full") {
        // The realm .so bytes this Sync copies must already have passed the
        // staleness fence: a stale staging dir never reaches the staged-jniLibs
        // output that feeds every variant's APK merge.
        dependsOn(validateNativeRuntimeFreshness)
    }

    into(stagedLib)
    // Real realm facade — large APK-native .so, loaded by SONAME.
    if (pocketLane == "full") {
    from(File(rtBuild, "libpocketrealm.so"))
    // PKG JNI shim + dlopen/crash helper.
    from(File(pkgBuild, "libpocketpkgtest.so"))
    // PIE launcher (a .so-named executable; extracted under the
    // experiment variant).
    from(File(pkgBuild, "libpocket_pkg_launcher.so"))
    }
    // Wine spike JNI helper (symlink-tree builder + loader launcher +
    // /proc maps probe + PE cache + SIGSYS diagnostic + trampoline launcher).
    // Runs in the Android/Bionic namespace.
    from(File(wineSpikeBuild, "libwine_spike.so"))
    if (pocketLane == "full") {
    // APK-packaged Bionic trampoline PIE (re-execve's the glibc
    // loader). Named lib*.so so AGP extracts it with the +x bit; it is a PIE
    // executable, not a shared library.
    from(File(wineSpikeBuild, "libwine_trampoline.so"))
    // Direct-glibc path: glibc LD_PRELOAD path/syscall shim, built in the
    // pinned Linux CGCT container by tools/build_wine_glibc_shim.py.  It is
    // x86_64-only; the ARM translated-Wine provider has a distinct closure.
    if (pocketAbi == "x86_64") {
        from(File(wineSpikeBuild, "libwine_android_shim.so"))
    }
    // proot fallback (termux/proot@a89b3732, Bionic PIE) + libtalloc.
    // Required because Android's untrusted_app seccomp filter kills the glibc
    // loader on its access(2) syscall (observed via ptrace diagnostic). proot
    // intercepts syscalls via ptrace and translates access->faccessat.
    from(File(prootStage, "libproot.so"))
    from(File(prootStage, "libtalloc.so"))
    // proot's in-tracee helper loader, staged APK-managed as
    // libproot_loader.so. PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so
    // makes proot use this immutable +x copy directly instead of extracting
    // one to writable PROOT_TMP_DIR (which the app domain forbids: noexec).
    from(File(prootStage, "libproot_loader.so"))
    from(File(prootStage, "libproot_loader32.so"))
    // Winlator X-server native transport (vendored source-matched from
    // ca3d735). libwinlator.so provides the epoll accept loop, buffered X11
    // input/output with SCM_RIGHTS fd-passing, and Drawable BGRA ops. JNI
    // methods match the vendored Java classes' package paths exactly
    // (com.winlator.xconnector.* + com.winlator.xserver.Drawable), so it is a
    // drop-in for System.loadLibrary("winlator").
    from(File(xserverBuild, "libwinlator.so"))
    // Source-matched GLX is loaded only when the selected display lane enables it.
    if (pocketAbi == "x86_64" || pocketAbi == "arm64-v8a") {
        from(File(xserverBuild, "libgladiorenderer.so"))
    }
    if (pocketAbi == "arm64-v8a") {
        from(File(xserverBuild, "libvortekrenderer.so"))
        from(File(xserverBuild, "libvirglrenderer.so"))
    }
    }
    // libc++_shared.so — the realm facade links ANDROID_STL=c++_shared, so its
    // runtime closure needs the shared C++ runtime. Sourced from the NDK, never
    // from a platform path. Platform libs (libc/libm/libdl/liblog) are excluded.
    from(provider {
        val ndk = ndkRootProvider.get()
        File(ndk, "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/$pocketNdkLibraryTriple/libc++_shared.so")
    })
    if (pocketLane == "full") {
    // The Wine + glibc ELF closure (53 files: glibc loader + libs, gcc-libs,
    // X11/font libs, Wine binaries + 36 unix .so modules). These run in the
    // SEPARATE Linux/glibc namespace (execve'd via the APK-managed loader).
    // Generated by tools/stage_wine_runtime.py.
    from(wineStaging) {
        if (pocketAbi == "arm64-v8a") {
            exclude("libpocket_zstd.so", "libpocket_zstd_exec.so")
        }
    }
    // Direct-glibc fallback: Wine's installed second-stage loader resolves
    // ntdll.so beside /proc/self/exe before WINEDLLPATH is initialized. Keep a
    // source-named APK-managed alias alongside the collision-safe staged name.
    from(File(wineStaging, "libwine_unix_ntdll.so")) {
        rename { "ntdll.so" }
    }
    }
    // Pinned MariaDB executables + their glibc DT_NEEDED closure. The
    // stage script assigns collision-safe lib*.so APK names and records every
    // source pathname/hash in BUILD_PROVENANCE.json.
    from(mariadbStage)
    if (pocketLane == "full") {
    // Android/Bionic, no-bot CMaNGOS components. Each library is loaded
    // only inside its dedicated :realm or :world process.
    from(realmStage)
    }
    if (pocketLane == "full") {
        // Finite, fixed-purpose Android/Bionic PIE extractors. These are
        // invoked only by the isolated import worker after managed-copy
        // publication in the full runtime lane.
        from(extractorStage)
    }
    if (pocketLane == "full") {
        // On-device vanilla-tweaks patcher belongs only to the client/full lane.
        from(vanillaTweaksStage)
    }
}

val validateDatabaseRuntime by tasks.registering {
    group = "pocket realm"
    description = "Require the generated MariaDB provider and migration assets for $pocketAbi."
    val repoRoot = layout.projectDirectory.dir("../..").asFile
    val selectedAbi = pocketAbi
    val nativeRootSuffix = pocketNativeRootSuffix
    val expectedMachine = pocketElfMachine
    doLast {
        val stage = File(repoRoot, "native/.build-$nativeRootSuffix/mariadb-staging")
        val required = listOf(
            File(stage, "jniLibs/$selectedAbi/libpocket_mariadbd.so"),
            File(stage, "jniLibs/$selectedAbi/libpocket_mariadb_client.so"),
            File(stage, "assets/database/provider/bootstrap.sql"),
            File(stage, "assets/database/provider/runtime-manifest.json"),
            File(stage, "assets/database/migrations/manifest.json"),
            File(stage, "BUILD_PROVENANCE.json"),
        )
        val missing = required.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "MariaDB staging incomplete for $selectedAbi. Run the ABI-aware MariaDB staging and " +
                "tools/stage_database_migrations.py. Missing: ${missing.joinToString { it.name }}"
        }
        required.take(2).forEach { file ->
            val header = file.inputStream().use { input -> ByteArray(20).also { input.read(it) } }
            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            check(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) &&
                machine == expectedMachine) {
                "MariaDB artifact is not $selectedAbi ELF (e_machine=$machine): $file"
            }
        }
        val runtimeManifest = JsonSlurper().parse(
            File(stage, "assets/database/provider/runtime-manifest.json"),
        ) as Map<*, *>
        val expectedProvider = when (selectedAbi) {
            "x86_64" -> "mariadb-11.5.2-termux-glibc"
            "arm64-v8a" -> "mariadb-12.3.2-termux-bionic-arm64"
            else -> error("Unsupported database ABI $selectedAbi")
        }
        check((runtimeManifest["schema"] as? Number)?.toInt() == 1 &&
            runtimeManifest["provider"] == expectedProvider &&
            runtimeManifest["abi"] == selectedAbi) {
            "MariaDB runtime manifest identity mismatch for $selectedAbi: " +
                File(stage, "assets/database/provider/runtime-manifest.json")
        }
    }
}

// Design note: this ad-hoc task deliberately declares NO
// outputs, so Gradle never marks it UP-TO-DATE — it executes on every
// build that wires it. If outputs are ever added, the provider mode
// must first become an explicit @Input (a stale-skip across
// -PsqliteProvider flips would otherwise be possible).
val validateRealmRuntime by tasks.registering {
    group = "pocket realm"
    description = "Require $pocketAbi realm ELF artifacts to match lockfile and generated provenance."
    val repoRoot = layout.projectDirectory.dir("../..").asFile
    val selectedAbi = pocketAbi
    val expectedMachine = pocketElfMachine
    // Hoisted at configuration time: a doLast closure referencing the
    // script-level val would retain the build-script instance and break
    // the configuration cache.
    val isSqliteProvider = sqliteProvider
    doLast {
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        // Under the sqlite provider the artifacts validate against the SIBLING
        // staging root + sibling lockfile; the MariaDB pins never move.
        val realmRoot = if (isSqliteProvider) "realm-staging-sqlite" else "realm-staging"
        val lockPath = "native/.build-o09-$selectedAbi/$realmRoot/jniLibs/$selectedAbi/"
        val lockfile = if (isSqliteProvider) {
            // The sibling-lockfile naming mirrors the MariaDB lockfiles:
            // x86_64 has NO ABI suffix (realm-runtime-lockfile-sqlite.json),
            // arm64 does (realm-runtime-lockfile-arm64-v8a-sqlite.json) -
            // mirror the special case below or the first x86_64 sqlite
            // assembly (the differential-test build) fails on a missing
            // lockfile path.
            if (selectedAbi == "x86_64") {
                File(repoRoot, "schemas/realm-runtime-lockfile-sqlite.json")
            } else {
                File(repoRoot, "schemas/realm-runtime-lockfile-$selectedAbi-sqlite.json")
            }
        } else if (selectedAbi == "x86_64") {
            File(repoRoot, "schemas/realm-runtime-lockfile.json")
        } else {
            File(repoRoot, "schemas/realm-runtime-lockfile-$selectedAbi.json")
        }
        check(lockfile.isFile) {
            "Realm runtime lockfile for $selectedAbi is missing: $lockfile"
        }
        val lockText = lockfile.readText()
        check(Regex("\"abi\"\\s*:\\s*\"${Regex.escape(selectedAbi)}\"").containsMatchIn(lockText)) {
            "Realm runtime lockfile declares a different ABI: $lockfile"
        }
        if (isSqliteProvider) {
            check(Regex("\"database_backend\"\\s*:\\s*\"sqlite\"").containsMatchIn(lockText)) {
                "sqlite provider lockfile does not record database_backend=sqlite: $lockfile"
            }
        }
        val provenance = File(repoRoot, "native/.build-o09-$selectedAbi/$realmRoot/BUILD_PROVENANCE.json")
        check(provenance.isFile) { "realm-runtime BUILD_PROVENANCE.json is missing; rebuild realm runtime" }
        val provenanceText = provenance.readText()
        val files = listOf("libpocket_realmd_runtime.so", "libpocket_world_runtime.so")
        for (name in files) {
            val relative = "$lockPath$name"
            val recordPattern = Regex(
                "(?s)\\{\\s*\"path\"\\s*:\\s*\"" +
                    Regex.escape(relative) +
                    "\"\\s*,\\s*\"size\"\\s*:\\s*(\\d+)\\s*,\\s*\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\""
            )
            val lock = recordPattern.find(lockText)
                ?: error("realm lockfile has no record for $relative")
            val provenanceRecord = recordPattern.find(provenanceText)
                ?: error("realm provenance has no record for $relative")
            check(lock.groupValues[1] == provenanceRecord.groupValues[1] &&
                lock.groupValues[2].equals(provenanceRecord.groupValues[2], ignoreCase = true)) {
                "realm provenance disagrees with lockfile for $name"
            }
            val artifact = File(repoRoot, relative)
            check(artifact.isFile) { "missing staged realm artifact: $artifact" }
            val header = artifact.inputStream().use { input -> ByteArray(20).also { input.read(it) } }
            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            check(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) &&
                machine == expectedMachine) {
                "Realm artifact is not $selectedAbi ELF (e_machine=$machine): $artifact"
            }
            check(artifact.length() == lock.groupValues[1].toLong()) {
                "realm artifact size disagrees with lockfile: $name"
            }
            check(sha256Hex(artifact).equals(lock.groupValues[2], ignoreCase = true)) {
                "realm artifact SHA-256 disagrees with lockfile: $name"
            }
        }
        if (isSqliteProvider) {
            // The seed assets must match the sibling lockfile's
            // seed_transcripts pins byte-for-byte, extending those pins
            // to the shipped APK assets.
            val seedDir = File(repoRoot, "native/.build-o09-$selectedAbi/realm-staging-sqlite/assets/seed")
            for (db in listOf("classicrealmd", "classiccharacters", "classiclogs", "classicmangos")) {
                // .sqlz (NOT .sql.gz): AGP's asset merge auto-gunzips
                // *.gz, so the migrations' convention applies here
                val gz = File(seedDir, "$db.sqlz")
                check(gz.isFile) { "missing staged seed asset: $gz" }
                val pin = Regex(
                    "\"$db\"\\s*:\\s*\\{[^}]*\"gzip_sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"[^}]*\"gzip_size\"\\s*:\\s*(\\d+)"
                ).find(lockText) ?: error("lockfile has no seed pin for $db")
                check(gz.length() == pin.groupValues[2].toLong()) {
                    "seed asset size disagrees with lockfile: $db"
                }
                check(sha256Hex(gz).equals(pin.groupValues[1], ignoreCase = true)) {
                    "seed asset SHA-256 disagrees with lockfile: $db"
                }
            }
            // Identity source: the APK's SQLite provider identity asset
            // must be BYTE-IDENTICAL to the committed sibling lockfile (the
            // staging script writes the lock-record form for exactly this
            // pin; loadAndVerifySqliteIdentity consumes it on device). Any
            // divergence between the committed lockfile and the shipped
            // identity fails the build here.
            val identityAsset = File(
                repoRoot,
                "native/.build-o09-$selectedAbi/realm-staging-sqlite/assets/database/provider-sqlite/BUILD_PROVENANCE.json",
            )
            check(identityAsset.isFile) {
                "missing staged SQLite identity asset: $identityAsset"
            }
            check(identityAsset.readBytes().contentEquals(lockfile.readBytes())) {
                "SQLite identity asset is not byte-identical to the sibling lockfile: $identityAsset"
            }
        }
    }
}

// Make every APK-producing variant depend on staging the native closure and
// validate the actual merged manifest consumed by packaging. The staging task
// retains its ABI/size/SHA-256 closure gates; extraction changes APK layout,
// not the pinned provider bytes.
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { c -> c.uppercase() }
        val validateExtractedPackaging = tasks.register<ValidateExtractedNativePackagingTask>(
            "validate${cap}ExtractedNativePackaging",
        ) {
            group = "verification"
            description = "Require extracted executable JNI packaging for ${variant.name}."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "merge${cap}JniLibFolders" }
            .configureEach { dependsOn(stageNativeLibs) }
        tasks.matching { it.name == "package${cap}" || it.name == "assemble${cap}" }
            .configureEach { dependsOn(validateExtractedPackaging) }
        tasks.matching { it.name.startsWith("assemble") && it.name.endsWith(cap) }
            .configureEach { dependsOn(stageNativeLibs) }
        if (pocketLane == "full") {
            tasks.matching { it.name == "merge${cap}JniLibFolders" ||
                it.name == "merge${cap}Assets" || it.name == "package${cap}" ||
                it.name == "assemble${cap}" }
                .configureEach {
                    // The realm byte-level gate (lockfile sha match,
                    // database_backend, seed pins under -PsqliteProvider)
                    // runs for every SHIPPING variant, not only the
                    // realmRuntime instrumentation type — debug/release
                    // must never package the realm runtimes without
                    // digest validation. validateNativeRuntimeFreshness adds
                    // the staleness fence: staged bytes, provenance, and
                    // sources.json commit pins must all agree first.
                    dependsOn(
                        validateDatabaseRuntime,
                        validateRealmRuntime,
                        validateNativeRuntimeFreshness,
                    )
                }
        }
        if (variant.name == "databaseRuntime") {
            tasks.matching { it.name == "merge${cap}JniLibFolders" ||
                it.name == "merge${cap}Assets" || it.name == "assemble${cap}" }
                .configureEach { dependsOn(validateDatabaseRuntime) }
        }
        if (variant.name == "realmRuntime") {
            tasks.matching { it.name == "merge${cap}JniLibFolders" ||
                it.name == "merge${cap}Assets" || it.name == "assemble${cap}" }
                .configureEach {
                    dependsOn(
                        validateDatabaseRuntime,
                        validateRealmRuntime,
                        validateNativeRuntimeFreshness,
                    )
                }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":pocketrealm-llm"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    // 7z LZMA/LZMA2 entry streams in commons-compress need the tukaani codec;
    // even COPY-method SevenZ files touch FilterOptions at open time.
    implementation(libs.xz)
    // RAR lane: libarchive JNI AAR (BSD-2 readers; see THIRD_PARTY_NOTICES).
    implementation(libs.libarchive.android)
    // Android must consume the AAR classifier: the plain JVM JAR contains
    // desktop resources and omits the APK jni/arm64-v8a library.
    implementation("com.github.luben:zstd-jni:${libs.versions.zstdJni.get()}@aar")
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    // Host JVM unit tests exercise the RealmNative shim graceful-degradation path.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
    // TLS mock server for the community downloader's https-only redirect hops.
    testImplementation(libs.okhttp.tls)
    testImplementation("org.json:json:20240303")
}
