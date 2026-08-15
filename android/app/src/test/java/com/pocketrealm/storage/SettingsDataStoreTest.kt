package com.pocketrealm.storage

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.client.VulkanDriverCatalog
import com.pocketrealm.client.ArmClientRenderer
import com.pocketrealm.client.ArmClientRendererCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataStoreTest {
    private val driverKey = stringPreferencesKey("arm_vulkan_driver_package")
    private val schemaKey = intPreferencesKey("arm_vulkan_driver_selection_schema")
    private val noticeKey = intPreferencesKey("arm_vulkan_driver_migration_notice")
    private val unrelatedKey = stringPreferencesKey("account_name")
    private val rendererKey = stringPreferencesKey("renderer")
    private val rendererSchemaKey = intPreferencesKey("arm_renderer_selection_schema")

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

    @Test
    fun schemaTwoRp6SystemMigratesOnceToTurnipAndPreservesOtherSettings() = runTest {
        val migration = vulkanSelectionMigration("Retroid Pocket 6")
        val prior = mutablePreferencesOf(
            driverKey to VulkanDriverCatalog.SYSTEM_DEFAULT,
            schemaKey to 2,
            unrelatedKey to "kept-account",
        )

        assertTrue(migration.shouldMigrate(prior))
        val migrated = migration.migrate(prior)
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, migrated[driverKey])
        assertEquals(VulkanDriverCatalog.SELECTION_SCHEMA, migrated[schemaKey])
        assertEquals(1, migrated[noticeKey])
        assertEquals("kept-account", migrated[unrelatedKey])
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
    }

    @Test
    fun missingRp6SelectionMigratesToTurnipWithVisibleNoticeMarker() = runTest {
        val migration = vulkanSelectionMigration("Retroid Pocket 6")
        val empty = mutablePreferencesOf(unrelatedKey to "kept-account")

        val migrated = migration.migrate(empty)
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, migrated[driverKey])
        assertEquals(VulkanDriverCatalog.SELECTION_SCHEMA, migrated[schemaKey])
        assertEquals(1, migrated[noticeKey])
        assertEquals("kept-account", migrated[unrelatedKey])
    }

    @Test
    fun partialRendererSchemasResetToDxvkOnceAndPreserveOtherSettings() = runTest {
        val migration = rendererSelectionMigration()
        for (legacy in listOf("legacy-gladio", "mesa-virgl", "unknown")) {
            val prior = mutablePreferencesOf(
                rendererKey to legacy,
                rendererSchemaKey to 1,
                unrelatedKey to "kept-account",
            )
            assertTrue(migration.shouldMigrate(prior))
            val migrated = migration.migrate(prior)
            assertEquals(ArmClientRenderer.DXVK.id, migrated[rendererKey])
            assertEquals(ArmClientRendererCatalog.SELECTION_SCHEMA, migrated[rendererSchemaKey])
            assertEquals("kept-account", migrated[unrelatedKey])
            assertFalse(migration.shouldMigrate(migrated))
            assertEquals(migrated, migration.migrate(migrated))
        }
        val missing = mutablePreferencesOf(unrelatedKey to "kept-account")
        val migratedMissing = migration.migrate(missing)
        assertEquals(ArmClientRenderer.DXVK.id, migratedMissing[rendererKey])
        assertEquals(
            ArmClientRendererCatalog.SELECTION_SCHEMA,
            migratedMissing[rendererSchemaKey],
        )
        assertEquals("kept-account", migratedMissing[unrelatedKey])
    }

    @Test
    fun completedRendererSchemaRetainsEveryExplicitKnownSelection() = runTest {
        val migration = rendererSelectionMigration()
        for (renderer in ArmClientRenderer.entries) {
            val current = mutablePreferencesOf(
                rendererKey to renderer.id,
                rendererSchemaKey to ArmClientRendererCatalog.SELECTION_SCHEMA,
                unrelatedKey to "kept-account",
            )
            assertFalse(migration.shouldMigrate(current))
            assertEquals(current, migration.migrate(current))
        }
    }
}
