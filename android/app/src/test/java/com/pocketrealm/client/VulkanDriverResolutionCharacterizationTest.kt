package com.pocketrealm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization net: Vulkan driver id resolution and the persisted
 * selection migration table (schema 0..4). The user lane must extend — never
 * change — these outcomes.
 */
class VulkanDriverResolutionCharacterizationTest {
    private val catalogIds = listOf(
        VulkanDriverCatalog.SYSTEM_DEFAULT,
        VulkanDriverCatalog.TURNIP_26_1,
    )

    @Test
    fun autoResolvesToTheVendorDefaultAndCatalogIdsStayExact() {
        for (adreno in listOf(true, false)) {
            assertEquals(
                if (adreno) VulkanDriverCatalog.TURNIP_26_1 else VulkanDriverCatalog.SYSTEM_DEFAULT,
                VulkanDriverCatalog.resolveId(VulkanDriverCatalog.AUTO_ID, adreno)?.id,
            )
            assertEquals(
                if (adreno) VulkanDriverCatalog.TURNIP_26_1 else VulkanDriverCatalog.SYSTEM_DEFAULT,
                VulkanDriverCatalog.resolveId(null, adreno)?.id,
            )
            for (id in catalogIds) {
                assertEquals(id, VulkanDriverCatalog.resolveId(id, adreno)?.id)
                assertEquals(id, VulkanDriverCatalog.requireForRequest(id).id)
            }
        }
    }

    @Test
    fun unknownIdFailsClosedWithTheExactReasons() {
        val unknown = "future-custom-driver"
        assertNull(VulkanDriverCatalog.resolveId(unknown, adrenoGpu = true))
        assertEquals(
            "Unknown Vulkan driver package: $unknown.",
            VulkanDriverCatalog.availability(unknown, adrenoGpu = true).reason,
        )
        val rejected = runCatching { VulkanDriverCatalog.requireForRequest(unknown) }
            .exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        assertEquals("unknown Vulkan driver package: $unknown", rejected?.message)
        val missing = runCatching { VulkanDriverCatalog.requireForRequest(null) }
            .exceptionOrNull()
        assertEquals("ARM DXVK requires an explicit Vulkan driver package", missing?.message)
    }

    @Test
    fun persistedSelectionMigrationTableIsPinnedForSchemasZeroThroughFour() {
        // (requested, schema, adreno) -> (resolved id, migrated)
        val expectations = buildMap {
            for (schema in 0..VulkanDriverCatalog.SELECTION_SCHEMA) {
                val migrated = schema < VulkanDriverCatalog.SELECTION_SCHEMA
                for (adreno in listOf(true, false)) {
                    put(Triple(null, schema, adreno), VulkanDriverCatalog.AUTO_ID to migrated)
                    put(Triple(VulkanDriverCatalog.AUTO_ID, schema, adreno),
                        VulkanDriverCatalog.AUTO_ID to migrated)
                    put(Triple(VulkanDriverCatalog.TURNIP_26_1, schema, adreno),
                        VulkanDriverCatalog.TURNIP_26_1 to migrated)
                    put(Triple("future-custom-driver", schema, adreno),
                        "future-custom-driver" to migrated)
                    val system = VulkanDriverCatalog.SYSTEM_DEFAULT
                    if (schema < VulkanDriverCatalog.SELECTION_SCHEMA && adreno) {
                        // The pre-auto era's Adreno System selections migrate to Turnip.
                        put(Triple(system, schema, adreno), VulkanDriverCatalog.TURNIP_26_1 to true)
                    } else {
                        put(Triple(system, schema, adreno), system to migrated)
                    }
                }
            }
        }
        expectations.forEach { (input, expected) ->
            val (requested, schema, adreno) = input
            val resolved = VulkanDriverCatalog.resolvePersistedSelection(
                requestedId = requested,
                selectionSchema = schema,
                adrenoGpu = adreno,
            )
            assertEquals("requested=$requested schema=$schema adreno=$adreno",
                expected.first, resolved.driverId)
            assertEquals("requested=$requested schema=$schema adreno=$adreno",
                expected.second, resolved.migrated)
        }
    }
}
