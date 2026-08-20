package com.pocketrealm.client

import java.io.File

/**
 * Pure construction of the ARM Box64 session's Vulkan-driver and renderer
 * environment blocks. Extracted verbatim from ClientRuntimeService so the
 * emitted variable list can be characterized by JVM tests: the packaged
 * lanes (SYSTEM/TURNIP x dxvk/virgl/opengl) must stay byte-identical while
 * the user-imported driver lane is layered on top.
 */
object ArmSessionEnvironment {
    /**
     * Vulkan driver variables for the DXVK route only; virgl/opengl carry no
     * driver environment. The ICD path always points into the app-owned
     * rootfs icd.d directory (see WineRuntimeStore.installPinnedArmGraphics).
     *
     * [userIcdFileName] is non-null only for the user-imported lane, which
     * stages its ICD under that name; the loader-era `VK_DRIVER_FILES` alias
     * is emitted for the user lane only — packaged lanes stay byte-identical.
     */
    fun driverEnv(
        renderer: String,
        vulkanDriverId: String?,
        rootfs: File,
        deviceModel: String,
        userIcdFileName: String? = null,
    ): List<String> = if (renderer == "dxvk") {
        if (userIcdFileName != null) {
            val icd = icdPath(rootfs, userIcdFileName)
            buildList {
                add("VK_ICD_FILENAMES=$icd")
                add("VK_DRIVER_FILES=$icd")
                add("MESA_VK_WSI_PRESENT_MODE=mailbox")
                add("MESA_VK_WSI_USE_HWBUF=1")
                add(if (deviceModel.trim().equals("Retroid Pocket 6", ignoreCase = true)) {
                    "TU_DEBUG=noconform,sysmem"
                } else {
                    "TU_DEBUG=noconform"
                })
            }
        } else {
            val driver = checkNotNull(VulkanDriverCatalog.find(vulkanDriverId)) {
                "ARM Vulkan driver identity missing"
            }
            when (driver.kind) {
                VulkanDriverKind.SYSTEM -> listOf(
                    "VK_ICD_FILENAMES=${icdPath(rootfs, driver.icdFileName)}",
                )
                VulkanDriverKind.TURNIP -> buildList {
                    add("VK_ICD_FILENAMES=${icdPath(rootfs, driver.icdFileName)}")
                    add("MESA_VK_WSI_PRESENT_MODE=mailbox")
                    add("MESA_VK_WSI_USE_HWBUF=1")
                    add(if (deviceModel.trim().equals("Retroid Pocket 6", ignoreCase = true)) {
                        "TU_DEBUG=noconform,sysmem"
                    } else {
                        "TU_DEBUG=noconform"
                    })
                }
            }
        }
    } else emptyList()

    /** Renderer-specific variables; independent of the Vulkan driver. */
    fun rendererEnv(
        renderer: String,
        dxvkConfig: File,
        cacheRoot: File,
        sessionLogDir: File,
        rootfs: File,
    ): List<String> = when (renderer) {
        "dxvk" -> listOf(
            "DXVK_STATE_CACHE_PATH=${File(cacheRoot, "dxvk").absolutePath}",
            "MESA_SHADER_CACHE_DIR=${File(cacheRoot, "mesa").absolutePath}",
            "DXVK_CONFIG_FILE=${dxvkConfig.absolutePath}",
            "DXVK_LOG_PATH=${sessionLogDir.absolutePath}",
            "DXVK_LOG_LEVEL=info",
            "vblank_mode=0",
        )
        "opengl" -> listOf(
            "POCKET_GLADIO_X11_SOCKET=${File(rootfs, "tmp/.X11-unix/X0").absolutePath}",
        )
        "virgl" -> listOf(
            "GALLIUM_DRIVER=virpipe",
            "VIRGL_NO_READBACK=true",
            "VIRGL_SERVER_PATH=${File(rootfs, "tmp/.virgl/V0").absolutePath}",
            "MESA_DEBUG=silent",
            "MESA_NO_ERROR=1",
            "MESA_EXTENSION_OVERRIDE=-GL_KHR_debug -GL_EXT_vertex_array_bgra",
            "MESA_GL_VERSION_OVERRIDE=3.1",
            "MESA_SHADER_CACHE_DIR=${File(cacheRoot, "virgl").absolutePath}",
        )
        else -> error("unsupported Box64 ARM renderer: $renderer")
    }

    private fun icdPath(rootfs: File, icdFileName: String): String =
        File(rootfs, "usr/share/vulkan/icd.d/$icdFileName").absolutePath
}
