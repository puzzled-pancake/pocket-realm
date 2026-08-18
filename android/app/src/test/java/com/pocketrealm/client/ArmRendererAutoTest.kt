package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ArmRendererAutoTest {
    private val modern = RendererPackageCatalog.BOX64_DEFAULT
    private val legacy = RendererPackageCatalog.BOX64_LEGACY

    @Test
    fun autoIsAlwaysTheDxvkRoute() {
        assertEquals(ArmClientRenderer.DXVK, ArmRendererAuto.resolve())
    }

    @Test
    fun autoKeepsTheSelectedPackageWhenTheApiFloorAllowsIt() {
        val vulkan13 = SystemVulkanCapabilities(vulkanVersion(1, 3), false, emptySet())
        assertEquals(
            modern,
            ArmRendererAuto.resolveAutoDxvkPackageId(
                modern, adrenoGpu = false, probe = { vulkan13 },
            ),
        )
    }

    @Test
    fun adrenoNeverProbesBecauseTurnipHasNoSystemFloor() {
        assertEquals(
            modern,
            ArmRendererAuto.resolveAutoDxvkPackageId(
                modern,
                adrenoGpu = true,
                probe = { error("the system Vulkan probe must not run for Turnip") },
            ),
        )
    }

    @Test
    fun olderVulkanStepsAutoDownToLegacyDxvkBeforeFailing() {
        val vulkan11 = SystemVulkanCapabilities(vulkanVersion(1, 1), false, emptySet())
        assertEquals(
            legacy,
            ArmRendererAuto.resolveAutoDxvkPackageId(
                modern, adrenoGpu = false, probe = { vulkan11 },
            ),
        )
        assertEquals(
            legacy,
            ArmRendererAuto.resolveAutoDxvkPackageId(
                legacy, adrenoGpu = false, probe = { vulkan11 },
            ),
        )
    }

    @Test
    fun brokenProbeKeepsTheSelectionSoLaunchGatesFailClosed() {
        assertEquals(
            modern,
            ArmRendererAuto.resolveAutoDxvkPackageId(
                modern, adrenoGpu = false, probe = { null },
            ),
        )
    }

    @Test
    fun preVulkan11DeviceKeepsTheSelectionBecauseNoLowerPackageExists() {
        val vulkan10 = SystemVulkanCapabilities(vulkanVersion(1, 0), false, emptySet())
        assertEquals(
            modern,
            ArmRendererAuto.resolveAutoDxvkPackageId(
                modern, adrenoGpu = false, probe = { vulkan10 },
            ),
        )
        assertEquals(
            legacy,
            ArmRendererAuto.resolveAutoDxvkPackageId(
                legacy, adrenoGpu = false, probe = { vulkan10 },
            ),
        )
    }

    @Test
    fun unknownPackageIdStaysUnknownForTheFailClosedGates() {
        assertEquals(
            "future-package",
            ArmRendererAuto.resolveAutoDxvkPackageId(
                "future-package",
                adrenoGpu = false,
                probe = { SystemVulkanCapabilities(vulkanVersion(1, 3), false, emptySet()) },
            ),
        )
    }
}
