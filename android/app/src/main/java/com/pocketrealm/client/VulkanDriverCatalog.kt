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
    /** Winlator-style system Vortek bridge over the device's own Vulkan driver. */
    const val SYSTEM_DEFAULT = "system-vulkan-vortek-2.1"
    const val TURNIP_26_1 = "turnip-26.1.0"

    /** Resolved at read time from the device GPU vendor (see [resolveDefault]). */
    const val AUTO_ID = "auto"
    const val SELECTION_SCHEMA = 4
    const val VORTEK_BRIDGE_MAX_API_VERSION: Int = (1 shl 22) or (3 shl 12) or 128

    private val packages = GeneratedVulkanDriverCatalog.packages.also { generated ->
        check(GeneratedVulkanDriverCatalog.DEFAULT_ID == TURNIP_26_1)
        check(GeneratedVulkanDriverCatalog.SELECTION_POLICY == "exact-request-fail-closed")
        check(generated.map(VulkanDriverPackage::id).toSet() == setOf(SYSTEM_DEFAULT, TURNIP_26_1))
    }
    private val byId = packages.associateBy(VulkanDriverPackage::id)

    fun all(): List<VulkanDriverPackage> = packages

    /** Every catalog entry is offered; availability text explains the GPU match. */
    fun userSelectable(): List<VulkanDriverPackage> = packages

    fun default(): VulkanDriverPackage = checkNotNull(byId[TURNIP_26_1])

    fun find(id: String?): VulkanDriverPackage? = id?.let(byId::get)

    /**
     * Winlator auto-selection: Adreno GPUs get the packaged Turnip ICD, every
     * other vendor (Mali, PowerVR, Xclipse...) gets the system Vortek bridge.
     */
    fun resolveDefault(adrenoGpu: Boolean): VulkanDriverPackage =
        checkNotNull(byId[if (adrenoGpu) TURNIP_26_1 else SYSTEM_DEFAULT])

    fun autoDriverId(adrenoGpu: Boolean): String = resolveDefault(adrenoGpu).id

    fun availability(driver: VulkanDriverPackage, adrenoGpu: Boolean): VulkanDriverAvailability =
        when (driver.kind) {
            VulkanDriverKind.TURNIP -> if (adrenoGpu) {
                VulkanDriverAvailability(
                    true,
                    "Packaged Turnip ICD matches this device's Adreno GPU.",
                )
            } else {
                VulkanDriverAvailability(
                    false,
                    "Packaged Turnip is an Adreno driver and cannot run on this GPU; " +
                        "the system Vortek bridge is the automatic choice here.",
                )
            }
            VulkanDriverKind.SYSTEM -> VulkanDriverAvailability(
                true,
                "System Vortek bridge over this device's own Vulkan driver; " +
                    "capability-checked against DXVK at launch.",
            )
        }

    fun availability(requestedId: String?, adrenoGpu: Boolean): VulkanDriverAvailability {
        val driver = resolveId(requestedId, adrenoGpu)
            ?: return VulkanDriverAvailability(
                available = false,
                reason = "Unknown Vulkan driver package: ${requestedId ?: "none"}.",
            )
        return availability(driver, adrenoGpu)
    }

    /** "auto" resolves to the vendor default; explicit ids stay exact. */
    fun resolveId(requestedId: String?, adrenoGpu: Boolean): VulkanDriverPackage? =
        when (requestedId) {
            null, AUTO_ID -> resolveDefault(adrenoGpu)
            else -> find(requestedId)
        }

    fun normalize(requestedId: String?, adrenoGpu: Boolean): String =
        if (requestedId == null || requestedId == AUTO_ID) {
            resolveDefault(adrenoGpu).id
        } else {
            requestedId
        }

    /**
     * Schema 4 adopts Winlator-style auto-selection. Schema-0/1/2 RP6 System
     * selections keep migrating to the pinned Turnip package; schema-3
     * selections pass through unchanged; an absent key now persists as
     * "auto" (fresh installs follow the catalog default and the vendor rule
     * if they change later) instead of materializing the concrete default.
     */
    fun resolvePersistedSelection(
        requestedId: String?,
        selectionSchema: Int,
        adrenoGpu: Boolean,
    ): PersistedVulkanDriverSelection {
        val legacyAdrenoSystem = selectionSchema < SELECTION_SCHEMA && adrenoGpu &&
            requestedId == SYSTEM_DEFAULT
        val resolvedId = when {
            legacyAdrenoSystem -> TURNIP_26_1
            requestedId == null -> AUTO_ID
            else -> requestedId
        }
        return PersistedVulkanDriverSelection(
            driverId = resolvedId,
            migrated = selectionSchema < SELECTION_SCHEMA,
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
        adrenoGpu: Boolean,
    ): VulkanDriverPackage {
        val driver = requireForRequest(requestedId)
        val availability = availability(driver, adrenoGpu)
        require(availability.available) { availability.reason }
        return driver
    }

    /** Exact release and capability gate for a selected driver/DXVK identity. */
    fun availabilityForPair(
        requestedDriverId: String?,
        requestedRendererId: String?,
        adrenoGpu: Boolean,
        system: SystemVulkanCapabilities? = null,
    ): VulkanDriverAvailability {
        val driver = resolveId(requestedDriverId, adrenoGpu)
            ?: return VulkanDriverAvailability(
                false,
                "Unknown Vulkan driver package: ${requestedDriverId ?: "none"}.",
            )
        val renderer = RendererPackageCatalog.find(requestedRendererId)
            ?: return VulkanDriverAvailability(
                false,
                "Unknown DXVK package: ${requestedRendererId ?: "none"}.",
            )
        val release = availability(driver, adrenoGpu)
        if (!release.available) return release
        val compatibility = compatibility(driver, renderer, system)
        return VulkanDriverAvailability(compatibility.compatible, compatibility.reason)
    }

    /** Control-boundary pair gate. It never substitutes either package. */
    fun requireAvailableCompatiblePair(
        requestedDriverId: String?,
        renderer: RendererPackage,
        adrenoGpu: Boolean,
        system: SystemVulkanCapabilities? = null,
    ): Pair<VulkanDriverPackage, VulkanRendererCompatibility> {
        val driver = requireAvailableForRequest(requestedDriverId, adrenoGpu)
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
     * Evaluate an exact immutable driver/renderer pair with wow-mobile
     * semantics: the only gate on the system Vortek bridge is the Vulkan
     * API-version floor of the selected DXVK package. There is deliberately
     * no BC-texture or device-extension capability profile — mobile GPUs
     * (Mali reports textureCompressionBC = VK_FALSE) run Vortek fine.
     * Packaged Turnip does not use Vortek at all.
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
        return VulkanRendererCompatibility(
            true,
            "System Vulkan meets ${renderer.label}'s minimum " +
                "(Vulkan ${formatVulkan(renderer.minimumSystemVulkanApi)}).",
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
}
