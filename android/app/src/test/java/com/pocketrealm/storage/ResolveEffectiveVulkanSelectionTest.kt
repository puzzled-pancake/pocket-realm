package com.pocketrealm.storage

import com.pocketrealm.client.UserVulkanDriver
import com.pocketrealm.client.VulkanDriverCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase C settings rule: a persisted user-driver selection only stays
 * effective while the user lane is enabled — a disabled lane resolves to
 * Auto visibly, never a launch-time silent swap (plan I1).
 */
class ResolveEffectiveVulkanSelectionTest {

    @Test
    fun disabledLaneResetsAUserSelectionToAuto() {
        assertEquals(
            VulkanDriverCatalog.AUTO_ID,
            resolveEffectiveVulkanSelection("user-turnip-26-3", allowUserVulkanDrivers = false),
        )
    }

    @Test
    fun enabledLaneKeepsTheUserSelectionExact() {
        assertEquals(
            "user-turnip-26-3",
            resolveEffectiveVulkanSelection("user-turnip-26-3", allowUserVulkanDrivers = true),
        )
    }

    @Test
    fun catalogAndAutoSelectionsAreUnaffectedByTheToggle() {
        for (toggle in listOf(true, false)) {
            assertEquals(
                VulkanDriverCatalog.TURNIP_26_1,
                resolveEffectiveVulkanSelection(VulkanDriverCatalog.TURNIP_26_1, toggle),
            )
            assertEquals(
                VulkanDriverCatalog.SYSTEM_DEFAULT,
                resolveEffectiveVulkanSelection(VulkanDriverCatalog.SYSTEM_DEFAULT, toggle),
            )
            assertEquals(
                VulkanDriverCatalog.AUTO_ID,
                resolveEffectiveVulkanSelection(VulkanDriverCatalog.AUTO_ID, toggle),
            )
        }
    }

    @Test
    fun userNamespaceDetectionCannotMatchCatalogIds() {
        assertEquals(false, UserVulkanDriver.isUserId(VulkanDriverCatalog.TURNIP_26_1))
        assertEquals(true, UserVulkanDriver.isUserId("user-anything"))
    }
}
