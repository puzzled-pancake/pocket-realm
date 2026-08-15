package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanDriverCatalogTest {
    private val systemDriver = requireNotNull(
        VulkanDriverCatalog.find(VulkanDriverCatalog.SYSTEM_DEFAULT),
    )
    private val modernDxvk = requireNotNull(
        RendererPackageCatalog.find(RendererPackageCatalog.BOX64_DEFAULT),
    )
    private val legacyDxvk = requireNotNull(
        RendererPackageCatalog.find(RendererPackageCatalog.BOX64_LEGACY),
    )

    @Test
    fun runtimeCatalogIsTheGeneratedClosedReviewedCatalog() {
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, GeneratedVulkanDriverCatalog.DEFAULT_ID)
        assertEquals("exact-request-fail-closed", GeneratedVulkanDriverCatalog.SELECTION_POLICY)
        assertEquals(GeneratedVulkanDriverCatalog.packages, VulkanDriverCatalog.all())
    }

    @Test
    fun winlatorAutoDefaultFollowsTheGpuVendor() {
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, VulkanDriverCatalog.default().id)
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            VulkanDriverCatalog.normalize(null, adrenoGpu = true),
        )
        assertEquals(
            VulkanDriverCatalog.SYSTEM_DEFAULT,
            VulkanDriverCatalog.normalize(null, adrenoGpu = false),
        )
        assertEquals(
            listOf(VulkanDriverCatalog.SYSTEM_DEFAULT, VulkanDriverCatalog.TURNIP_26_1),
            VulkanDriverCatalog.userSelectable().map { it.id }.sorted(),
        )
    }

    @Test
    fun systemVortekIsSelectableAndAvailabilityNeverGatesOnDeviceModel() {
        val availability = VulkanDriverCatalog.availability(
            VulkanDriverCatalog.SYSTEM_DEFAULT,
            adrenoGpu = false,
        )
        assertTrue(availability.available)
        assertEquals(
            VulkanDriverCatalog.SYSTEM_DEFAULT,
            VulkanDriverCatalog.requireAvailableForRequest(
                VulkanDriverCatalog.SYSTEM_DEFAULT,
                adrenoGpu = false,
            ).id,
        )
    }

    @Test
    fun turnipRunsOnAdrenoGpusOnly() {
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            VulkanDriverCatalog.requireAvailableForRequest(
                VulkanDriverCatalog.TURNIP_26_1,
                adrenoGpu = true,
            ).id,
        )
        val other = VulkanDriverCatalog.availability(
            VulkanDriverCatalog.TURNIP_26_1,
            adrenoGpu = false,
        )
        assertFalse(other.available)
        assertTrue(runCatching {
            VulkanDriverCatalog.requireAvailableForRequest(
                VulkanDriverCatalog.TURNIP_26_1,
                adrenoGpu = false,
            )
        }.isFailure)
    }

    @Test
    fun explicitSelectionIsNeverSilentlyReplaced() {
        assertEquals(
            VulkanDriverCatalog.SYSTEM_DEFAULT,
            VulkanDriverCatalog.normalize(
                VulkanDriverCatalog.SYSTEM_DEFAULT,
                adrenoGpu = true,
            ),
        )
        assertEquals(
            "future-driver",
            VulkanDriverCatalog.normalize("future-driver", adrenoGpu = true),
        )
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            VulkanDriverCatalog.normalize(null, adrenoGpu = true),
        )
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            VulkanDriverCatalog.normalize(
                VulkanDriverCatalog.AUTO_ID,
                adrenoGpu = true,
            ),
        )
    }

    @Test
    fun preSchemaAdrenoSystemMigratesOnceToTurnip() {
        for (legacy in listOf(null, VulkanDriverCatalog.SYSTEM_DEFAULT)) {
            val resolved = VulkanDriverCatalog.resolvePersistedSelection(
                requestedId = legacy,
                selectionSchema = 0,
                adrenoGpu = true,
            )
            assertEquals(VulkanDriverCatalog.TURNIP_26_1, resolved.driverId)
            assertTrue(resolved.migrated)
        }
    }

    @Test
    fun schemaOneTurnipIsPreservedBecauseExplicitIntentCannotBeDistinguished() {
        val marked = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = VulkanDriverCatalog.TURNIP_26_1,
            selectionSchema = 1,
            adrenoGpu = true,
        )
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, marked.driverId)
        assertTrue(marked.migrated)
    }

    @Test
    fun migrationPreservesUnknownIdentitiesExactly() {
        val unknown = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = "future-custom-driver",
            selectionSchema = 2,
            adrenoGpu = true,
        )
        assertEquals("future-custom-driver", unknown.driverId)
        assertTrue(unknown.migrated)

        val nonAdrenoSystem = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = VulkanDriverCatalog.SYSTEM_DEFAULT,
            selectionSchema = 2,
            adrenoGpu = false,
        )
        assertEquals(VulkanDriverCatalog.SYSTEM_DEFAULT, nonAdrenoSystem.driverId)
        assertTrue(nonAdrenoSystem.migrated)
    }

    @Test
    fun schemaStampedAutoStaysAutoAndExplicitSystemStaysExact() {
        val auto = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = VulkanDriverCatalog.AUTO_ID,
            selectionSchema = VulkanDriverCatalog.SELECTION_SCHEMA,
            adrenoGpu = false,
        )
        assertEquals(VulkanDriverCatalog.AUTO_ID, auto.driverId)
        assertFalse(auto.migrated)

        val resolved = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = VulkanDriverCatalog.SYSTEM_DEFAULT,
            selectionSchema = VulkanDriverCatalog.SELECTION_SCHEMA,
            adrenoGpu = false,
        )
        assertEquals(VulkanDriverCatalog.SYSTEM_DEFAULT, resolved.driverId)
        assertFalse(resolved.migrated)
        assertEquals(
            VulkanDriverCatalog.SYSTEM_DEFAULT,
            VulkanDriverCatalog.requireAvailableCompatiblePair(
                resolved.driverId,
                modernDxvk,
                adrenoGpu = false,
                system = SystemVulkanCapabilities(vulkanVersion(1, 3), true, emptySet()),
            ).first.id,
        )
    }

    @Test
    fun systemCompatibilityIsVendorNeutralAndSelectsDxvkByHostApi() {
        val maliCapabilities = SystemVulkanCapabilities(
            apiVersion = vulkanVersion(1, 3),
            nativeTextureCompressionBC = false,
            deviceExtensions = emptySet(),
        )
        assertTrue(VulkanDriverCatalog.compatibility(
            systemDriver, modernDxvk, maliCapabilities,
        ).compatible)

        val olderSystemVulkan = maliCapabilities.copy(apiVersion = vulkanVersion(1, 1))
        assertFalse(VulkanDriverCatalog.compatibility(
            systemDriver, modernDxvk, olderSystemVulkan,
        ).compatible)
        assertTrue(VulkanDriverCatalog.compatibility(
            systemDriver, legacyDxvk, olderSystemVulkan,
        ).compatible)
    }

    @Test
    fun systemCompatibilityIgnoresBcAndDeviceExtensionInventory() {
        // wow-mobile semantics: Mali GPUs report textureCompressionBC =
        // VK_FALSE and still run the Vortek bridge; only the API floor gates.
        val noBcNoExtensions = SystemVulkanCapabilities(
            apiVersion = vulkanVersion(1, 3),
            nativeTextureCompressionBC = false,
            deviceExtensions = emptySet(),
        )
        val result = VulkanDriverCatalog.compatibility(systemDriver, modernDxvk, noBcNoExtensions)
        assertTrue(result.compatible)
    }

    @Test
    fun systemCompatibilityCapsVortekAtMinimumOfHostAndBridge() {
        val newerHost = SystemVulkanCapabilities(vulkanVersion(1, 4), false, emptySet())
        assertEquals(
            VulkanDriverCatalog.VORTEK_BRIDGE_MAX_API_VERSION,
            VulkanDriverCatalog.requireCompatiblePair(
                systemDriver, modernDxvk, newerHost,
            ).vkMaxVersion,
        )

        val olderHost = SystemVulkanCapabilities(vulkanVersion(1, 3), false, emptySet())
        assertEquals(
            olderHost.apiVersion,
            VulkanDriverCatalog.requireCompatiblePair(
                systemDriver, modernDxvk, olderHost,
            ).vkMaxVersion,
        )
    }

    @Test
    fun exactPairNeverFallsBackWhenSystemCapabilitiesAreAbsent() {
        val result = VulkanDriverCatalog.compatibility(systemDriver, modernDxvk)
        assertFalse(result.compatible)
        assertTrue(runCatching {
            VulkanDriverCatalog.requireCompatiblePair(systemDriver, modernDxvk)
        }.isFailure)
    }

    @Test
    fun experimentalCompatibilityModelKeepsExactDxvkFloorForQualification() {
        val vulkan11 = SystemVulkanCapabilities(vulkanVersion(1, 1), false, emptySet())
        val modern = VulkanDriverCatalog.compatibility(systemDriver, modernDxvk, vulkan11)
        val legacy = VulkanDriverCatalog.compatibility(systemDriver, legacyDxvk, vulkan11)
        assertFalse(modern.compatible)
        assertTrue(legacy.compatible)
        assertTrue(modern.reason.contains("1.3"))
    }
}
