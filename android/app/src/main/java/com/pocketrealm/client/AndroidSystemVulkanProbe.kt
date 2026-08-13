package com.pocketrealm.client

import com.winlator.core.GPUHelper

/** One fail-closed snapshot from Android's public Vulkan loader. */
object AndroidSystemVulkanProbe {
    fun probe(): SystemVulkanCapabilities {
        val native = GPUHelper.probeSystemVulkan()
        val extensions = native.deviceExtensions.toSet()
        require(extensions.isNotEmpty() && extensions.none(String::isBlank)) {
            "Android system Vulkan returned an invalid extension inventory"
        }
        return SystemVulkanCapabilities(
            apiVersion = native.apiVersion,
            nativeTextureCompressionBC = native.nativeTextureCompressionBC,
            deviceExtensions = extensions,
        )
    }
}
