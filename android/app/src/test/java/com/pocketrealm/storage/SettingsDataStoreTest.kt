package com.pocketrealm.storage

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    fun setupCompletionKeepsItsOwnIntegerKey() {
        assertEquals("setup_complete", setupCompletePreference.name)
    }

    @Test
    fun gameSettingsKeysFollowTheTweaksPatternWithAnIndependentCounterKey() {
        val queue = stringPreferencesKey("game_settings_queue")
        val queueSchema = intPreferencesKey("game_settings_queue_schema")
        val revision = longPreferencesKey("game_settings_revision")
        val directEdits = stringPreferencesKey("game_settings_direct_edit_revisions")
        assertEquals("game_settings_queue", queue.name)
        assertEquals("game_settings_queue_schema", queueSchema.name)
        // The revision counter must live outside the queue JSON's key pair
        // so no queue fallback can regress it.
        assertEquals("game_settings_revision", revision.name)
        assertEquals("game_settings_direct_edit_revisions", directEdits.name)
    }

    @Test
    fun schemaOneMarkedTurnipIsPreservedAndMigrationIsIdempotent() = runTest {
        val migration = vulkanSelectionMigration(adrenoGpu = true)
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
        val migration = vulkanSelectionMigration(adrenoGpu = true)
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
        val migration = vulkanSelectionMigration(adrenoGpu = true)
        val prior = mutablePreferencesOf(
            driverKey to VulkanDriverCatalog.SYSTEM_DEFAULT,
            schemaKey to 2,
            unrelatedKey to "kept-account",
        )

        assertTrue(migration.shouldMigrate(prior))
        val migrated = migration.migrate(prior)
        assertEquals(VulkanDriverCatalog.TURNIP_26_1, migrated[driverKey])
        assertEquals(VulkanDriverCatalog.SELECTION_SCHEMA, migrated[schemaKey])
        assertEquals(null, migrated[noticeKey])
        assertEquals("kept-account", migrated[unrelatedKey])
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
    }

    @Test
    fun missingSelectionMigratesToAutoFollowingTheCatalogDefault() = runTest {
        val migration = vulkanSelectionMigration(adrenoGpu = true)
        val empty = mutablePreferencesOf(unrelatedKey to "kept-account")

        val migrated = migration.migrate(empty)
        assertEquals(VulkanDriverCatalog.AUTO_ID, migrated[driverKey])
        assertEquals(VulkanDriverCatalog.SELECTION_SCHEMA, migrated[schemaKey])
        assertEquals("kept-account", migrated[unrelatedKey])
    }

    @Test
    fun partialRendererSchemasResetToAutoOnceAndPreserveOtherSettings() = runTest {
        val migration = rendererSelectionMigration()
        for (legacy in listOf("legacy-gladio", "mesa-virgl", "unknown")) {
            val prior = mutablePreferencesOf(
                rendererKey to legacy,
                rendererSchemaKey to 1,
                unrelatedKey to "kept-account",
            )
            assertTrue(migration.shouldMigrate(prior))
            val migrated = migration.migrate(prior)
            assertEquals(ArmClientRendererCatalog.AUTO_ID, migrated[rendererKey])
            assertEquals(ArmClientRendererCatalog.SELECTION_SCHEMA, migrated[rendererSchemaKey])
            assertEquals("kept-account", migrated[unrelatedKey])
            assertFalse(migration.shouldMigrate(migrated))
            assertEquals(migrated, migration.migrate(migrated))
        }
        val missing = mutablePreferencesOf(unrelatedKey to "kept-account")
        val migratedMissing = migration.migrate(missing)
        assertEquals(ArmClientRendererCatalog.AUTO_ID, migratedMissing[rendererKey])
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
