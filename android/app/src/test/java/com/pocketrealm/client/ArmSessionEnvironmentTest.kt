package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Characterization net: the exact environment variables the packaged
 * graphics lanes emit. The user-imported Vulkan driver lane must layer on top
 * of these without changing any pinned value.
 */
class ArmSessionEnvironmentTest {
    private val rootfs = File("/pocket-rootfs")
    private val cache = File("/pocket-cache")
    private val dxvkConfig = File(cache, "dxvk.conf")
    private val sessionDir = File("/pocket-root/sessions/1")

    private fun driverEnv(renderer: String, driverId: String?, model: String = "Generic Phone") =
        ArmSessionEnvironment.driverEnv(renderer, driverId, rootfs, model)

    @Test
    fun systemVortekDxvkRouteEmitsExactlyTheIcdVariable() {
        assertEquals(
            listOf(
                "VK_ICD_FILENAMES=" +
                    File(rootfs, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json").absolutePath,
            ),
            driverEnv("dxvk", VulkanDriverCatalog.SYSTEM_DEFAULT),
        )
    }

    @Test
    fun turnipDxvkRouteEmitsIcdPlusMesaTuning() {
        assertEquals(
            listOf(
                "VK_ICD_FILENAMES=" +
                    File(rootfs, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json").absolutePath,
                "MESA_VK_WSI_PRESENT_MODE=mailbox",
                "MESA_VK_WSI_USE_HWBUF=1",
                "TU_DEBUG=noconform",
            ),
            driverEnv("dxvk", VulkanDriverCatalog.TURNIP_26_1),
        )
    }

    @Test
    fun retroidPocket6AddsTheSysmemTuDebugVariant() {
        val rp6 = driverEnv("dxvk", VulkanDriverCatalog.TURNIP_26_1, "Retroid Pocket 6")
        assertTrue(rp6.last().endsWith("TU_DEBUG=noconform,sysmem"))
        // The model match is trim + case-insensitive, like the service.
        assertEquals(
            rp6,
            driverEnv("dxvk", VulkanDriverCatalog.TURNIP_26_1, "  retroid pocket 6 "),
        )
        assertEquals(
            listOf("TU_DEBUG=noconform"),
            driverEnv("dxvk", VulkanDriverCatalog.TURNIP_26_1, "Retroid Pocket 5")
                .filter { it.startsWith("TU_DEBUG=") },
        )
    }

    @Test
    fun nonDxvkRoutesCarryNoVulkanDriverEnvironment() {
        for (route in listOf("virgl", "opengl")) {
            for (driver in listOf(
                VulkanDriverCatalog.SYSTEM_DEFAULT,
                VulkanDriverCatalog.TURNIP_26_1,
            )) {
                assertEquals(emptyList<String>(), driverEnv(route, driver))
            }
        }
    }

    @Test
    fun loaderEraVkDriverFilesNameIsNeverEmitted() {
        val all = VulkanDriverCatalog.all().flatMap {
            driverEnv("dxvk", it.id) + ArmSessionEnvironment.rendererEnv(
                "dxvk", dxvkConfig, cache, sessionDir, rootfs,
            )
        }
        assertTrue(all.none { it.startsWith("VK_DRIVER_FILES=") })
    }

    @Test
    fun missingDriverIdentityOnTheDxvkRouteFailsClosed() {
        val failure = runCatching {
            driverEnv("dxvk", "not-a-catalog-driver")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("ARM Vulkan driver identity missing", failure?.message)
    }

    @Test
    fun dxvkRendererEnvIsPinnedExactly() {
        assertEquals(
            listOf(
                "DXVK_STATE_CACHE_PATH=${File(cache, "dxvk").absolutePath}",
                "MESA_SHADER_CACHE_DIR=${File(cache, "mesa").absolutePath}",
                "DXVK_CONFIG_FILE=${dxvkConfig.absolutePath}",
                "DXVK_LOG_PATH=${sessionDir.absolutePath}",
                "DXVK_LOG_LEVEL=info",
                "vblank_mode=0",
            ),
            ArmSessionEnvironment.rendererEnv(
                "dxvk", dxvkConfig, cache, sessionDir, rootfs,
            ),
        )
    }

    @Test
    fun gladioRendererEnvIsPinnedExactlyAndCarriesNoVulkanVariables() {
        val env = ArmSessionEnvironment.rendererEnv(
            "opengl", dxvkConfig, cache, sessionDir, rootfs,
        )
        assertEquals(
            listOf("POCKET_GLADIO_X11_SOCKET=${File(rootfs, "tmp/.X11-unix/X0").absolutePath}"),
            env,
        )
        assertTrue(env.none { it.startsWith("VK_") || it.startsWith("TU_") || it.startsWith("MESA_") })
    }

    @Test
    fun virglRendererEnvIsPinnedExactlyAndCarriesNoVulkanIcdVariable() {
        assertEquals(
            listOf(
                "GALLIUM_DRIVER=virpipe",
                "VIRGL_NO_READBACK=true",
                "VIRGL_SERVER_PATH=${File(rootfs, "tmp/.virgl/V0").absolutePath}",
                "MESA_DEBUG=silent",
                "MESA_NO_ERROR=1",
                "MESA_EXTENSION_OVERRIDE=-GL_KHR_debug -GL_EXT_vertex_array_bgra",
                "MESA_GL_VERSION_OVERRIDE=3.1",
                "MESA_SHADER_CACHE_DIR=${File(cache, "virgl").absolutePath}",
            ),
            ArmSessionEnvironment.rendererEnv("virgl", dxvkConfig, cache, sessionDir, rootfs),
        )
    }

    @Test
    fun unsupportedRendererNameIsRejected() {
        assertTrue(
            runCatching {
                ArmSessionEnvironment.rendererEnv(
                    "wined3d", dxvkConfig, cache, sessionDir, rootfs,
                )
            }.isFailure,
        )
    }
}
