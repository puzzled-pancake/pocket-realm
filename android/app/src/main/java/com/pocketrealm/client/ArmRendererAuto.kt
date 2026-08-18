package com.pocketrealm.client

/**
 * Winlator/wow-mobile automatic graphics selection. The renderer axis is
 * always DXVK; the Vulkan-driver axis follows the GPU vendor (Adreno gets
 * the packaged Turnip ICD, every other vendor gets the system Vortek
 * bridge). Legacy OpenGL (Gladio) and Mesa VirGL are manual-only
 * experimental selections and are never chosen automatically; if the system
 * Vulkan probe is entirely broken the launch gates fail closed with the
 * probe error instead of silently switching lanes.
 */
object ArmRendererAuto {
    private const val PROBE_TIMEOUT_MS = 3_000L

    @Volatile
    private var cachedAdreno: Boolean? = null

    @Volatile
    private var cachedProbe: Result<SystemVulkanCapabilities>? = null

    /** ro.hardware.egl is "adreno" / "mali" / vendor string; no EGL needed. */
    fun isAdrenoGpu(): Boolean = cachedAdreno ?: detectAdreno().also { cachedAdreno = it }

    private fun detectAdreno(): Boolean = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java)
        val eglHardware = get.invoke(systemProperties, "ro.hardware.egl") as? String
        eglHardware?.contains("adreno", ignoreCase = true) == true
    }.getOrDefault(false)

    /** Auto is always the DXVK route; see [ArmClientRendererCatalog]. */
    fun resolve(): ArmClientRenderer = ArmClientRenderer.DXVK

    /**
     * Winlator-style graceful degradation for the AUTO renderer selection:
     * honour the selected DXVK package when the system Vulkan API floor
     * allows it, then step down to the legacy DXVK 1.10.3 package (Vulkan
     * 1.1 floor) before ever surfacing a launch error. A failed or timed-out
     * probe returns the selection unchanged so the launch gates stay
     * fail-closed on the real probe failure.
     */
    fun resolveAutoDxvkPackageId(requestedId: String): String =
        resolveAutoDxvkPackageId(requestedId, isAdrenoGpu()) { probeSystemVulkanOnce() }

    internal fun resolveAutoDxvkPackageId(
        requestedId: String,
        adrenoGpu: Boolean,
        probe: () -> SystemVulkanCapabilities?,
    ): String {
        val requested = RendererPackageCatalog.find(requestedId) ?: return requestedId
        if (adrenoGpu) return requested.id
        val capabilities = probe() ?: return requested.id
        val driver = VulkanDriverCatalog.resolveDefault(adrenoGpu = false)
        if (VulkanDriverCatalog.compatibility(driver, requested, capabilities).compatible) {
            return requested.id
        }
        val legacy = RendererPackageCatalog.find(RendererPackageCatalog.BOX64_LEGACY)
            ?: return requested.id
        if (legacy.id != requested.id &&
            VulkanDriverCatalog.compatibility(driver, legacy, capabilities).compatible
        ) {
            return legacy.id
        }
        return requested.id
    }

    /**
     * Probe the system Vulkan loader at most once per process under a hard
     * watchdog; a timeout or failure is remembered as a failure rather than
     * retried on every launch.
     */
    private fun probeSystemVulkanOnce(): SystemVulkanCapabilities? {
        cachedProbe?.let { return it.getOrNull() }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val result = try {
            runCatching {
                executor.submit<SystemVulkanCapabilities> { AndroidSystemVulkanProbe.probe() }
                    .get(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } finally {
            executor.shutdownNow()
        }
        cachedProbe = result
        return result.getOrNull()
    }

    /**
     * Effective Vulkan driver id for a possibly-"auto" persisted selection.
     * "auto"/null resolve to the vendor default; an unknown manual id returns
     * null so the launch gates fail closed instead of substituting a driver.
     */
    fun resolveVulkanDriverId(requestedId: String?): String? = when {
        requestedId == null || requestedId == VulkanDriverCatalog.AUTO_ID ->
            VulkanDriverCatalog.autoDriverId(isAdrenoGpu())
        else -> VulkanDriverCatalog.find(requestedId)?.id
    }
}
