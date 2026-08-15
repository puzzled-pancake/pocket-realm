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
        assertEquals(VulkanDriverCatalog.RELEASE_DEFAULT, GeneratedVulkanDriverCatalog.DEFAULT_ID)
        assertEquals("exact-request-fail-closed", GeneratedVulkanDriverCatalog.SELECTION_POLICY)
        assertEquals(GeneratedVulkanDriverCatalog.packages, VulkanDriverCatalog.all())
    }

    @Test
    fun releaseDefaultIsQualifiedTurnipAndSystemIsNotInNormalRp6Choices() {
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, VulkanDriverCatalog.default().id)
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            VulkanDriverCatalog.normalize(null, "Retroid Pocket 6"),
        )
        assertEquals(
            listOf(VulkanDriverCatalog.TURNIP_26_1),
            VulkanDriverCatalog.userSelectable("Retroid Pocket 6").map { it.id },
        )
    }

    @Test
    fun systemVortekIsRetainedButExperimentalAndFailsTheReleaseGate() {
        val availability = VulkanDriverCatalog.availability(
            VulkanDriverCatalog.SYSTEM_DEFAULT,
            "Pixel 10",
        )
        assertFalse(availability.available)
        assertEquals(VulkanDriverCatalog.SYSTEM_EXPERIMENTAL_REASON, availability.reason)
        assertTrue(runCatching {
            VulkanDriverCatalog.requireAvailableForRequest(
                VulkanDriverCatalog.SYSTEM_DEFAULT,
                "Pixel 10",
            )
        }.isFailure)
        assertTrue(runCatching {
            VulkanDriverCatalog.requireAvailableCompatiblePair(
                VulkanDriverCatalog.SYSTEM_DEFAULT,
                modernDxvk,
                "Pixel 10",
            )
        }.isFailure)
    }

    @Test
    fun turnipIsAcceptedOnlyOnQualifiedRp6Lane() {
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            VulkanDriverCatalog.requireAvailableForRequest(
                VulkanDriverCatalog.TURNIP_26_1,
                "Retroid Pocket 6",
            ).id,
        )
        val other = VulkanDriverCatalog.availability(
            VulkanDriverCatalog.TURNIP_26_1,
            "Pixel 10",
        )
        assertFalse(other.available)
        assertEquals(VulkanDriverCatalog.TURNIP_UNQUALIFIED_REASON, other.reason)
        assertTrue(runCatching {
            VulkanDriverCatalog.requireAvailableForRequest(
                VulkanDriverCatalog.TURNIP_26_1,
                "Pixel 10",
            )
        }.isFailure)
    }

    @Test
    fun explicitSelectionIsNeverSilentlyReplaced() {
        assertEquals(
            VulkanDriverCatalog.SYSTEM_DEFAULT,
            VulkanDriverCatalog.normalize(
                VulkanDriverCatalog.SYSTEM_DEFAULT,
                "Retroid Pocket 6",
            ),
        )
        assertEquals(
            "future-driver",
            VulkanDriverCatalog.normalize("future-driver", "Retroid Pocket 6"),
        )
        assertEquals(
            VulkanDriverCatalog.TURNIP_26_1,
            VulkanDriverCatalog.normalize(null, "Retroid Pocket 6"),
        )
    }

    @Test
    fun preSchemaRp6SystemMigratesOnceToQualifiedTurnipWithNotice() {
        for (legacy in listOf(null, VulkanDriverCatalog.SYSTEM_DEFAULT)) {
            val resolved = VulkanDriverCatalog.resolvePersistedSelection(
                requestedId = legacy,
                selectionSchema = 0,
                deviceModel = "Retroid Pocket 6",
            )
            assertEquals(VulkanDriverCatalog.TURNIP_26_1, resolved.driverId)
            assertTrue(resolved.migrated)
            assertEquals(VulkanDriverCatalog.RP6_SYSTEM_MIGRATION_NOTICE, resolved.notice)
        }
    }

    @Test
    fun schemaOneTurnipIsPreservedBecauseExplicitIntentCannotBeDistinguished() {
        val marked = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = VulkanDriverCatalog.TURNIP_26_1,
            selectionSchema = 1,
            deviceModel = "Retroid Pocket 6",
        )
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, marked.driverId)
        assertTrue(marked.migrated)
        assertEquals(null, marked.notice)
    }

    @Test
    fun migrationPreservesUnknownAndNonRp6IdentitiesExactly() {
        val unknown = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = "future-custom-driver",
            selectionSchema = 2,
            deviceModel = "Retroid Pocket 6",
        )
        assertEquals("future-custom-driver", unknown.driverId)
        assertTrue(unknown.migrated)
        assertEquals(null, unknown.notice)

        val nonRp6System = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = VulkanDriverCatalog.SYSTEM_DEFAULT,
            selectionSchema = 2,
            deviceModel = "Pixel 10",
        )
        assertEquals(VulkanDriverCatalog.SYSTEM_DEFAULT, nonRp6System.driverId)
        assertTrue(nonRp6System.migrated)
        assertEquals(null, nonRp6System.notice)
    }

    @Test
    fun schemaStampedExplicitSystemRemainsExactButFailsProductionAvailability() {
        val resolved = VulkanDriverCatalog.resolvePersistedSelection(
            requestedId = VulkanDriverCatalog.SYSTEM_DEFAULT,
            selectionSchema = VulkanDriverCatalog.SELECTION_SCHEMA,
            deviceModel = "Retroid Pocket 6",
        )
        assertEquals(VulkanDriverCatalog.SYSTEM_DEFAULT, resolved.driverId)
        assertFalse(resolved.migrated)
        assertTrue(runCatching {
            VulkanDriverCatalog.requireAvailableCompatiblePair(
                resolved.driverId,
                modernDxvk,
                "Retroid Pocket 6",
            )
        }.isFailure)
    }

    @Test
    fun systemCompatibilityIsVendorNeutralAndSelectsDxvkByHostApi() {
        val completeCapabilities = SystemVulkanCapabilities(
            apiVersion = vulkanVersion(1, 3),
            nativeTextureCompressionBC = true,
            deviceExtensions = VulkanDriverCatalog.VORTEK_REQUIRED_DEVICE_EXTENSIONS,
        )
        assertTrue(VulkanDriverCatalog.compatibility(
            systemDriver, modernDxvk, completeCapabilities,
        ).compatible)

        val olderSystemVulkan = completeCapabilities.copy(apiVersion = vulkanVersion(1, 1))
        assertFalse(
            olderSystemVulkan.deviceExtensions.contains("VK_KHR_timeline_semaphore"),
        )
        assertFalse(VulkanDriverCatalog.compatibility(
            systemDriver, modernDxvk, olderSystemVulkan,
        ).compatible)
        assertTrue(VulkanDriverCatalog.compatibility(
            systemDriver, legacyDxvk, olderSystemVulkan,
        ).compatible)
    }

    @Test
    fun systemCompatibilityRequiresNativeBcAndEveryBridgeCapability() {
        val completeCapabilities = SystemVulkanCapabilities(
            apiVersion = vulkanVersion(1, 3),
            nativeTextureCompressionBC = true,
            deviceExtensions = VulkanDriverCatalog.VORTEK_REQUIRED_DEVICE_EXTENSIONS,
        )
        assertFalse(VulkanDriverCatalog.compatibility(
            systemDriver,
            modernDxvk,
            completeCapabilities.copy(nativeTextureCompressionBC = false),
        ).compatible)

        VulkanDriverCatalog.VORTEK_REQUIRED_DEVICE_EXTENSIONS.forEach { missing ->
            val result = VulkanDriverCatalog.compatibility(
                systemDriver,
                modernDxvk,
                completeCapabilities.copy(
                    deviceExtensions = completeCapabilities.deviceExtensions - missing,
                ),
            )
            assertFalse("missing $missing must fail closed", result.compatible)
            assertTrue(result.reason.contains(missing))
        }
    }

    @Test
    fun systemCompatibilityCapsVortekAtMinimumOfHostAndBridge() {
        val extensions = VulkanDriverCatalog.VORTEK_REQUIRED_DEVICE_EXTENSIONS
        val newerHost = SystemVulkanCapabilities(vulkanVersion(1, 4), true, extensions)
        assertEquals(
            VulkanDriverCatalog.VORTEK_BRIDGE_MAX_API_VERSION,
            VulkanDriverCatalog.requireCompatiblePair(
                systemDriver, modernDxvk, newerHost,
            ).vkMaxVersion,
        )

        val olderHost = SystemVulkanCapabilities(vulkanVersion(1, 3), true, extensions)
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
        val vulkan11 = SystemVulkanCapabilities(
            vulkanVersion(1, 1),
            true,
            VulkanDriverCatalog.VORTEK_REQUIRED_DEVICE_EXTENSIONS,
        )
        val modern = VulkanDriverCatalog.compatibility(systemDriver, modernDxvk, vulkan11)
        val legacy = VulkanDriverCatalog.compatibility(systemDriver, legacyDxvk, vulkan11)
        assertFalse(modern.compatible)
        assertTrue(legacy.compatible)
        assertTrue(modern.reason.contains("1.3"))
    }
}
