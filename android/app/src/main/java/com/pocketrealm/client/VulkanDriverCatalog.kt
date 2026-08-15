package com.pocketrealm.client

/** How the translated Windows Vulkan calls reach an Android Vulkan driver. */
enum class VulkanDriverKind {
    /** Winlator's Vortek guest/server bridge opens Android's system libvulkan. */
    SYSTEM,

    /** A pinned Mesa Turnip ICD runs from the app-owned rootfs. */
    TURNIP,
}

/**
 * Closed, APK-installed Vulkan-driver catalog.
 *
 * Native GPU drivers execute inside the client process and therefore cannot be
 * accepted from an arbitrary URL or pathname. Adding another Turnip release is
 * intentionally a catalog/build-provenance change; the Settings UI will expose
 * every catalog entry without changing the runtime control protocol.
 */
data class VulkanDriverPackage(
    val id: String,
    val label: String,
    val version: String,
    val kind: VulkanDriverKind,
    val summary: String,
    val qualification: String,
    val libraryAsset: String,
    val libraryName: String,
    val librarySha256: String,
    val icdAsset: String,
    val icdFileName: String,
    val icdSha256: String,
) {
    init {
        require(ID.matches(id)) { "invalid Vulkan driver package id" }
        require(version.isNotBlank()) { "Vulkan driver version is absent" }
        require(libraryAsset.startsWith("arm-translated/vulkan-drivers/$id/"))
        require(icdAsset.startsWith("arm-translated/vulkan-drivers/$id/"))
        require(".." !in libraryAsset && ".." !in icdAsset)
        require(FILE_NAME.matches(libraryName) && FILE_NAME.matches(icdFileName))
        require(SHA256.matches(librarySha256) && SHA256.matches(icdSha256))
    }

    val buildId: String get() = when (kind) {
        VulkanDriverKind.SYSTEM -> "system-vulkan-vortek-$version"
        VulkanDriverKind.TURNIP -> "turnip-$version"
    }

    val requiresVortekServer: Boolean get() = kind == VulkanDriverKind.SYSTEM

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9.-]{2,63}")
        private val FILE_NAME = Regex("[A-Za-z0-9_.-]{3,96}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** Release/device gate kept separate from the immutable package identity. */
data class VulkanDriverAvailability(
    val available: Boolean,
    val reason: String,
)

/** Vendor-neutral capabilities returned by Android's public libvulkan loader. */
data class SystemVulkanCapabilities(
    val apiVersion: Int,
    val nativeTextureCompressionBC: Boolean,
    val deviceExtensions: Set<String>,
)

data class VulkanRendererCompatibility(
    val compatible: Boolean,
    val reason: String,
    /** Exact Vortek API exposure: never greater than the host or bridge. */
    val vkMaxVersion: Int = 0,
)

data class PersistedVulkanDriverSelection(
    val driverId: String,
    val migrated: Boolean,
    val notice: String? = null,
)

object VulkanDriverCatalog {
    /** Experimental Vortek bridge retained for developer qualification only. */
    const val SYSTEM_DEFAULT = "system-vulkan-vortek-2.1"
    const val TURNIP_26_1 = "turnip-26.1.0"
    const val RELEASE_DEFAULT = TURNIP_26_1
    const val TURNIP_UNQUALIFIED_REASON =
        "Packaged Turnip is not yet supported on this device's graphics hardware."
    const val SYSTEM_EXPERIMENTAL_REASON =
        "System Vortek is experimental and cannot be selected."
    const val RP6_SYSTEM_MIGRATION_NOTICE =
        "An experimental driver was replaced with the supported Turnip 26.1.0 driver on this device."
    const val SELECTION_SCHEMA = 3
    const val VORTEK_BRIDGE_MAX_API_VERSION: Int = (1 shl 22) or (3 shl 12) or 128

    val VORTEK_REQUIRED_DEVICE_EXTENSIONS: Set<String> = setOf(
        "VK_KHR_swapchain",
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_KHR_external_memory",
        "VK_KHR_external_memory_fd",
        "VK_KHR_external_semaphore",
        "VK_KHR_external_semaphore_fd",
        "VK_KHR_external_fence",
        "VK_KHR_external_fence_fd",
    )

    private val packages = GeneratedVulkanDriverCatalog.packages.also { generated ->
        check(GeneratedVulkanDriverCatalog.DEFAULT_ID == RELEASE_DEFAULT)
        check(GeneratedVulkanDriverCatalog.SELECTION_POLICY == "exact-request-fail-closed")
        check(generated.map(VulkanDriverPackage::id).toSet() == setOf(SYSTEM_DEFAULT, TURNIP_26_1))
    }
    private val byId = packages.associateBy(VulkanDriverPackage::id)

    fun all(): List<VulkanDriverPackage> = packages

    /** Normal Settings UI: experimental packages never appear as selectable choices. */
    fun userSelectable(deviceModel: String): List<VulkanDriverPackage> =
        packages.filter { availability(it, deviceModel).available }

    fun default(): VulkanDriverPackage = checkNotNull(byId[RELEASE_DEFAULT])

    fun find(id: String?): VulkanDriverPackage? = id?.let(byId::get)

    fun availability(driver: VulkanDriverPackage, deviceModel: String): VulkanDriverAvailability =
        when (driver.kind) {
            VulkanDriverKind.SYSTEM -> VulkanDriverAvailability(
                available = false,
                reason = SYSTEM_EXPERIMENTAL_REASON,
            )
            VulkanDriverKind.TURNIP -> if (isQualifiedRp6(deviceModel)) {
                VulkanDriverAvailability(available = true, reason = driver.qualification)
            } else {
                VulkanDriverAvailability(available = false, reason = TURNIP_UNQUALIFIED_REASON)
            }
        }

    fun availability(requestedId: String?, deviceModel: String): VulkanDriverAvailability {
        val driver = find(requestedId) ?: return VulkanDriverAvailability(
            available = false,
            reason = "Unknown Vulkan driver package: ${requestedId ?: "none"}.",
        )
        return availability(driver, deviceModel)
    }

    /** Initial release selection. Pair compatibility is checked separately and never substituted. */
    fun normalize(requestedId: String?, deviceModel: String = ""): String =
        requestedId ?: RELEASE_DEFAULT

    /**
     * Schema 3 removes experimental System/Vortek from the normal RP6 path.
     * Existing schema-0/1/2 RP6 System selections are migrated once to the
     * qualified pinned Turnip package. Post-schema explicit identities remain
     * exact, but an experimental System request still fails the release gate.
     */
    fun resolvePersistedSelection(
        requestedId: String?,
        selectionSchema: Int,
        deviceModel: String,
    ): PersistedVulkanDriverSelection {
        val migrateRp6System = selectionSchema < SELECTION_SCHEMA &&
            isQualifiedRp6(deviceModel) &&
            (requestedId == null || requestedId == SYSTEM_DEFAULT)
        val resolvedId = if (migrateRp6System) TURNIP_26_1 else normalize(requestedId, deviceModel)
        val needsSchemaStamp = selectionSchema < SELECTION_SCHEMA
        return PersistedVulkanDriverSelection(
            driverId = resolvedId,
            migrated = needsSchemaStamp,
            notice = RP6_SYSTEM_MIGRATION_NOTICE.takeIf { migrateRp6System },
        )
    }

    /** Control requests never silently change the requested native driver. */
    fun requireForRequest(requestedId: String?): VulkanDriverPackage {
        requireNotNull(requestedId) { "ARM DXVK requires an explicit Vulkan driver package" }
        return requireNotNull(find(requestedId)) { "unknown Vulkan driver package: $requestedId" }
    }

    /** Exact release request gate. It never substitutes another driver. */
    fun requireAvailableForRequest(
        requestedId: String?,
        deviceModel: String,
    ): VulkanDriverPackage {
        val driver = requireForRequest(requestedId)
        val availability = availability(driver, deviceModel)
        require(availability.available) { availability.reason }
        return driver
    }

    /** Exact release and capability gate for a selected driver/DXVK identity. */
    fun availabilityForPair(
        requestedDriverId: String?,
        requestedRendererId: String?,
        deviceModel: String,
        system: SystemVulkanCapabilities? = null,
    ): VulkanDriverAvailability {
        val driver = find(requestedDriverId) ?: return VulkanDriverAvailability(
            false,
            "Unknown Vulkan driver package: ${requestedDriverId ?: "none"}.",
        )
        val renderer = RendererPackageCatalog.find(requestedRendererId)
            ?: return VulkanDriverAvailability(
                false,
                "Unknown DXVK package: ${requestedRendererId ?: "none"}.",
            )
        val release = availability(driver, deviceModel)
        if (!release.available) return release
        val compatibility = compatibility(driver, renderer, system)
        return VulkanDriverAvailability(compatibility.compatible, compatibility.reason)
    }

    /** Control-boundary pair gate. It never substitutes either package. */
    fun requireAvailableCompatiblePair(
        requestedDriverId: String?,
        renderer: RendererPackage,
        deviceModel: String,
        system: SystemVulkanCapabilities? = null,
    ): Pair<VulkanDriverPackage, VulkanRendererCompatibility> {
        val driver = requireAvailableForRequest(requestedDriverId, deviceModel)
        return driver to requireCompatiblePair(driver, renderer, system)
    }

    fun runtimeGeneration(rendererPackageId: String, vulkanDriverId: String): String {
        val renderer = requireNotNull(RendererPackageCatalog.find(rendererPackageId)) {
            "unknown renderer package: $rendererPackageId"
        }
        val driver = requireForRequest(vulkanDriverId)
        return "${renderer.id}--${driver.id}"
    }

    /**
     * Evaluate an exact immutable driver/renderer pair without changing the
     * production release gate. This keeps the experimental portability matrix
     * testable by developer qualification tools while normal launch stays off.
     */
    fun compatibility(
        driver: VulkanDriverPackage,
        renderer: RendererPackage,
        system: SystemVulkanCapabilities? = null,
    ): VulkanRendererCompatibility {
        if (driver.kind != VulkanDriverKind.SYSTEM) {
            return VulkanRendererCompatibility(true, "packaged Vulkan driver does not use Vortek")
        }
        val capabilities = system ?: return VulkanRendererCompatibility(
            false, "System Vortek requires a checked Android Vulkan capability probe.",
        )
        if (capabilities.apiVersion < renderer.minimumSystemVulkanApi) {
            return VulkanRendererCompatibility(
                false,
                "${renderer.label} requires System Vulkan ${formatVulkan(renderer.minimumSystemVulkanApi)} or newer.",
            )
        }
        if (!capabilities.nativeTextureCompressionBC) {
            return VulkanRendererCompatibility(false, "System Vortek requires native BC texture compression.")
        }
        val missing = VORTEK_REQUIRED_DEVICE_EXTENSIONS - capabilities.deviceExtensions
        if (missing.isNotEmpty()) {
            return VulkanRendererCompatibility(
                false,
                "System Vortek is missing required device capabilities: ${missing.sorted().joinToString()}.",
            )
        }
        return VulkanRendererCompatibility(
            true,
            "System Vulkan and ${renderer.label} meet the hardened Vortek capability profile.",
            minOf(capabilities.apiVersion, VORTEK_BRIDGE_MAX_API_VERSION),
        )
    }

    fun requireCompatiblePair(
        driver: VulkanDriverPackage,
        renderer: RendererPackage,
        system: SystemVulkanCapabilities? = null,
    ): VulkanRendererCompatibility = compatibility(driver, renderer, system).also {
        require(it.compatible) { it.reason }
    }

    private fun formatVulkan(version: Int): String =
        "${version ushr 22}.${(version ushr 12) and 0x3ff}"

    private fun isQualifiedRp6(deviceModel: String): Boolean =
        deviceModel.trim().equals("Retroid Pocket 6", ignoreCase = true)
}
