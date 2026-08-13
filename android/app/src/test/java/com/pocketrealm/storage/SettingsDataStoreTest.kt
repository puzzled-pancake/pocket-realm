package com.pocketrealm.storage

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.client.VulkanDriverCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataStoreTest {
    private val driverKey = stringPreferencesKey("arm_vulkan_driver_package")
    private val schemaKey = intPreferencesKey("arm_vulkan_driver_selection_schema")
    private val noticeKey = intPreferencesKey("arm_vulkan_driver_migration_notice")

    @Test
    fun keepsExistingPreferencesFileNameAndSchema() {
        assertEquals("pocket_settings", POCKET_SETTINGS_STORE_NAME)
        assertEquals("pocket_settings.preferences_pb", POCKET_SETTINGS_FILE_NAME)
        assertEquals("arm_vulkan_driver_package", driverKey.name)
        assertEquals("arm_vulkan_driver_selection_schema", schemaKey.name)
        assertEquals("arm_vulkan_driver_migration_notice", noticeKey.name)
    }

    @Test
    fun schemaOneMarkedTurnipIsPreservedAndMigrationIsIdempotent() = runTest {
        val migration = vulkanSelectionMigration("Retroid Pocket 6")
        val legacy = mutablePreferencesOf(
            driverKey to VulkanDriverCatalog.TURNIP_26_1,
            schemaKey to 1,
            noticeKey to 1,
        )

        assertTrue(migration.shouldMigrate(legacy))
        val migrated = migration.migrate(legacy)
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, migrated[driverKey])
        assertEquals(VulkanDriverCatalog.SELECTION_SCHEMA, migrated[schemaKey])
        assertEquals(null, migrated[noticeKey])

        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
    }

    @Test
    fun schemaOneExplicitTurnipIsPreservedWhileSchemaIsStamped() = runTest {
        val migration = vulkanSelectionMigration("Retroid Pocket 6")
        val explicit = mutablePreferencesOf(
            driverKey to VulkanDriverCatalog.TURNIP_26_1,
            schemaKey to 1,
        )
        assertTrue(migration.shouldMigrate(explicit))
        val migrated = migration.migrate(explicit)
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, migrated[driverKey])
        assertEquals(VulkanDriverCatalog.SELECTION_SCHEMA, migrated[schemaKey])
        assertEquals(null, migrated[noticeKey])
    }
}
