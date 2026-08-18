package com.pocketrealm.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanBridgeReadinessTest {
    @Test
    fun `system driver accepts a ready component`() {
        assertTrue(resolveVulkanBridgeReady(true, VulkanDriverKind.SYSTEM))
    }

    @Test
    fun `system driver rejects an unready component`() {
        assertFalse(resolveVulkanBridgeReady(false, VulkanDriverKind.SYSTEM))
    }

    @Test
    fun `system driver rejects a missing component`() {
        assertFalse(resolveVulkanBridgeReady(null, VulkanDriverKind.SYSTEM))
    }

    @Test
    fun `turnip does not require a Vortek component`() {
        assertTrue(resolveVulkanBridgeReady(null, VulkanDriverKind.TURNIP))
    }

    @Test
    fun `no selected Vulkan driver does not require a Vortek component`() {
        assertTrue(resolveVulkanBridgeReady(null, null))
    }
}
