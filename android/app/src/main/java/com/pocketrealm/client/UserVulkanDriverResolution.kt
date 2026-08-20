package com.pocketrealm.client

import org.json.JSONObject
import java.io.File

/**
 * The single seam where the user-imported Vulkan driver lane meets the launch
 * chain. Catalog ids keep their closed, fail-closed semantics untouched
 * (I1); `user-` namespace ids resolve through the app-private registry only
 * when the lane is enabled, and every failure carries its exact reason (I8).
 */
object UserVulkanDriverResolution {

    /** Canonical user-lane status strings (I8): the seam and the UI share these. */
    const val ADRENO_ONLY_REASON =
        "Imported Turnip drivers are Adreno-only and cannot run on this GPU; " +
            "the system Vortek bridge is the automatic choice here."

    fun quarantinedDriverReason(driver: UserVulkanDriver): String =
        "Imported driver ${driver.label} is quarantined: ${driver.quarantineReason}"

    /** What a validated request id resolved to, catalog or user lane. */
    sealed interface SessionDriver {
        val id: String
        val kind: VulkanDriverKind
        val icdFileName: String

        data class CatalogDriver(val driver: VulkanDriverPackage) : SessionDriver {
            override val id: String get() = driver.id
            override val kind: VulkanDriverKind get() = driver.kind
            override val icdFileName: String get() = driver.icdFileName
        }

        /** A user Turnip ICD behaves like the packaged Turnip lane. */
        data class UserDriver(val driver: UserVulkanDriver) : SessionDriver {
            override val id: String get() = driver.id
            override val kind: VulkanDriverKind get() = VulkanDriverKind.TURNIP
            override val icdFileName: String get() = UserVulkanDriver.ICD_FILE_NAME
        }
    }

    /**
     * Kind lookup for paths that only need display/readiness semantics:
     * user drivers are Turnip ICDs, catalog ids keep their kind, anything
     * else is unknown (null).
     */
    fun kindOf(requestedId: String?): VulkanDriverKind? = when {
        UserVulkanDriver.isUserId(requestedId) -> VulkanDriverKind.TURNIP
        else -> VulkanDriverCatalog.find(requestedId)?.kind
    }

    /**
     * Launch-chain gate (preflight, prefix preparation, generation identity):
     * the opt-in toggle is enforced here. Throws IllegalArgumentException
     * with the exact reason — never substitutes (I1).
     */
    fun requireSessionDriver(
        requestedId: String?,
        registry: UserVulkanDriverRegistry,
        allowUserDrivers: Boolean,
        adrenoGpu: Boolean,
    ): SessionDriver {
        val userId = requestedId?.takeIf(UserVulkanDriver::isUserId)
        if (userId == null) {
            return SessionDriver.CatalogDriver(
                VulkanDriverCatalog.requireForRequest(requestedId),
            )
        }
        require(allowUserDrivers) {
            "Imported Vulkan drivers are disabled; enable 'Allow imported drivers' " +
                "in Settings to use the selected driver."
        }
        val driver = requireRegisteredDriver(userId, registry)
        require(adrenoGpu) { ADRENO_ONLY_REASON }
        return SessionDriver.UserDriver(driver)
    }

    /**
     * Display-process gate: the toggle was already enforced before the id
     * reached the display host; here the id must simply be a registered,
     * non-quarantined driver (or a catalog package).
     */
    fun requireDisplayDriver(
        requestedId: String?,
        registry: UserVulkanDriverRegistry,
    ): SessionDriver {
        val userId = requestedId?.takeIf(UserVulkanDriver::isUserId)
        if (userId == null) {
            return SessionDriver.CatalogDriver(
                VulkanDriverCatalog.requireForRequest(requestedId),
            )
        }
        return SessionDriver.UserDriver(requireRegisteredDriver(userId, registry))
    }

    private fun requireRegisteredDriver(
        requestedId: String,
        registry: UserVulkanDriverRegistry,
    ): UserVulkanDriver {
        val driver = registry.find(requestedId)
            ?: throw IllegalArgumentException(
                "Imported Vulkan driver $requestedId is not registered; " +
                    "it may have been deleted. Choose another driver.",
            )
        require(!driver.quarantined) { quarantinedDriverReason(driver) }
        return driver
    }

    /**
     * Deterministic ICD text for the shared rootfs: the stored manifest with
     * `ICD.library_path` rewritten to the absolute staged-library path. The
     * bytes are a pure function of (stored manifest, path), so the digest
     * recorded in the generation identity is stable across launches.
     */
    fun icdForRootfs(storedIcdJson: String, libraryAbsolutePath: String): String {
        val stored = JSONObject(storedIcdJson)
        val icd = stored.getJSONObject("ICD")
        val rewrittenIcd = JSONObject()
            .put("library_path", libraryAbsolutePath)
            .put("library_arch", "64")
        icd.optString("api_version").takeIf { it.isNotBlank() }?.let {
            rewrittenIcd.put("api_version", it)
        }
        return JSONObject()
            .put("ICD", rewrittenIcd)
            .put("file_format_version", "1.0.1")
            .toString()
    }

    /** Where the user-driver library is installed inside the shared rootfs. */
    fun rootfsLibraryFile(rootfs: File): File =
        File(rootfs, "usr/lib/${UserVulkanDriver.LIBRARY_FILE_NAME}")

    /** Where the user-driver ICD manifest is installed inside the shared rootfs. */
    fun rootfsIcdFile(rootfs: File): File =
        File(rootfs, "usr/share/vulkan/icd.d/${UserVulkanDriver.ICD_FILE_NAME}")
}
