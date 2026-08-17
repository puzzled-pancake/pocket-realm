package com.pocketrealm.storage

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketrealm.client.ClientTweaksConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTweaksDefaultsMigrationTest {
    private val tweaksKey = tweaksPreference
    private val schemaKey = tweaksSchemaPreference
    private val unrelatedKey = stringPreferencesKey("unrelated")

    private fun migration() = clientTweaksDefaultsMigration(resolvesWidescreen = { true })

    @Test
    fun neverConfiguredGainsWidescreenDefaultsOnce() = runTest {
        val empty = mutablePreferencesOf(unrelatedKey to "kept")

        val migrated = migration().migrate(empty)

        val config = ClientTweaksConfig.fromJson(migrated[tweaksKey])!!
        assertTrue(config.fovEnabled)
        assertTrue(config.quicklootEnabled)
        assertTrue(config.cameraSkipFixEnabled)
        assertTrue(config.maxCameraDistanceEnabled)
        assertEquals(TWEAKS_SCHEMA_VERSION, migrated[schemaKey])
        assertEquals("kept", migrated[unrelatedKey])
    }

    @Test
    fun explicitSchemaOneChoicesArePreservedAndOnlyStamped() = runTest {
        // The user turned everything off deliberately at schema 1.
        val prior = mutablePreferencesOf(
            tweaksKey to ClientTweaksConfig().toJson(),
            schemaKey to 1,
        )

        val migrated = migration().migrate(prior)

        val config = ClientTweaksConfig.fromJson(migrated[tweaksKey])!!
        assertFalse(config.fovEnabled)
        assertFalse(config.quicklootEnabled)
        assertFalse(config.cameraSkipFixEnabled)
        assertFalse(config.maxCameraDistanceEnabled)
        assertEquals(TWEAKS_SCHEMA_VERSION, migrated[schemaKey])
    }

    @Test
    fun schemaTwoDoesNotMigrateAgain() = runTest {
        val current = mutablePreferencesOf(schemaKey to 2)

        assertFalse(migration().shouldMigrate(current))
        assertNull(migration().migrate(current)[tweaksKey])
    }

    @Test
    fun narrowDisplayGainsNothing() = runTest {
        val empty = mutablePreferencesOf(unrelatedKey to "kept")
        val narrow = clientTweaksDefaultsMigration(resolvesWidescreen = { false })

        val migrated = narrow.migrate(empty)

        assertNull(migrated[tweaksKey])
        assertEquals(TWEAKS_SCHEMA_VERSION, migrated[schemaKey])
    }
}
