package com.pocketrealm.storage

import com.pocketrealm.client.ClientTweaksConfig
import com.pocketrealm.desktop.DesktopSettingsStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-13: the desktop twin of the Android `ClientTweaksDefaultsMigrationTest`
 * over the pure-JVM migration ([Settings.clientTweaksDefaultsMigration] — the
 * DataStore migration shell is android-side) plus the desktop codec/store
 * wiring legs: the fromJson migration trigger, the write-path stamp, and the
 * fresh-install store path.
 */
class ClientTweaksDefaultsMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun neverConfiguredGainsWidescreenDefaultsOnce() {
        val migrated = Settings.clientTweaksDefaultsMigration(
            resolvesWidescreen = { true },
            storedSchema = null, // a missing stamp: every pre-DESK-13 desktop file
            storedTweaksJson = null, // and the missing-file fresh install
        )
        assertTrue(migrated.fovEnabled)
        assertTrue(migrated.quicklootEnabled)
        assertTrue(migrated.cameraSkipFixEnabled)
        assertTrue(migrated.maxCameraDistanceEnabled)
        // The untouched non-default fields stay upstream-default.
        assertFalse(migrated.soundChannelsEnabled)
        assertFalse(migrated.soundInBackgroundEnabled)
    }

    @Test
    fun explicitSchemaOneChoicesArePreservedAndOnlyStamped() {
        // The user turned everything off deliberately at schema 1.
        val explicit = ClientTweaksConfig(
            fovEnabled = false,
            quicklootEnabled = false,
            cameraSkipFixEnabled = false,
            maxCameraDistanceEnabled = false,
        )
        assertEquals(
            explicit,
            Settings.clientTweaksDefaultsMigration(
                resolvesWidescreen = { true },
                storedSchema = 1,
                storedTweaksJson = explicit.toJson(),
            ),
        )
    }

    @Test
    fun schemaTwoDoesNotMigrateAgain() {
        val current = ClientTweaksConfig(
            fovEnabled = true,
            fov = 2.0f,
            quicklootEnabled = false,
        )
        assertEquals(
            current,
            Settings.clientTweaksDefaultsMigration(
                resolvesWidescreen = { true },
                storedSchema = Settings.TWEAKS_SCHEMA_VERSION,
                storedTweaksJson = current.toJson(),
            ),
        )
    }

    @Test
    fun narrowDisplayGainsNothing() {
        assertEquals(
            ClientTweaksConfig(),
            Settings.clientTweaksDefaultsMigration(
                resolvesWidescreen = { false },
                storedSchema = null,
                storedTweaksJson = null,
            ),
        )
    }

    @Test
    fun fromJsonOfAnUnstampedFileAppliesTheMigrationAndRestampsOnWrite() {
        // A pre-DESK-13 desktop settings file: no tweaksSchema key at all.
        val root = org.json.JSONObject(Settings.Snapshot().toJson())
        root.remove("tweaksSchema")
        val unstamped = root.toString()
        assertFalse(org.json.JSONObject(unstamped).has("tweaksSchema"))

        val decoded = Settings.Snapshot.fromJson(unstamped, resolvesWidescreen = { true })
        assertTrue(decoded.tweaks.fovEnabled)
        assertTrue(decoded.tweaks.quicklootEnabled)
        assertTrue(decoded.tweaks.cameraSkipFixEnabled)
        assertTrue(decoded.tweaks.maxCameraDistanceEnabled)

        // The next write persists the fresh stamp (the Android update()
        // contract), so the migration has run once, ever.
        val written = org.json.JSONObject(decoded.toJson())
        assertEquals(Settings.TWEAKS_SCHEMA_VERSION, written.getInt("tweaksSchema"))

        // A stamped file never re-migrates: the user's post-migration choice
        // (everything back off) survives even with a widescreen display.
        val userEdited = org.json.JSONObject(decoded.toJson())
            .put("tweaks", org.json.JSONObject(ClientTweaksConfig().toJson()))
            .toString()
        val redecoded = Settings.Snapshot.fromJson(userEdited, resolvesWidescreen = { true })
        assertEquals(ClientTweaksConfig(), redecoded.tweaks)
    }

    @Test
    fun stampedFileNeverReMigrates() {
        val stamped = Settings.Snapshot().toJson() // toJson stamps the constant
        assertEquals(
            ClientTweaksConfig(),
            Settings.Snapshot.fromJson(stamped, resolvesWidescreen = { true }).tweaks,
        )
    }

    @Test
    fun freshInstallStoreRunsTheMigrationOverTheEmptyStore() {
        // The Android DataStore runs its migrations on first open of empty
        // preferences; the missing-file desktop path mirrors that.
        val widescreen = DesktopSettingsStore(File(folder.root, "missing.json")) { true }.load()
        assertTrue(widescreen.tweaks.fovEnabled)
        assertTrue(widescreen.tweaks.quicklootEnabled)
        assertTrue(widescreen.tweaks.cameraSkipFixEnabled)
        assertTrue(widescreen.tweaks.maxCameraDistanceEnabled)
        // A narrow display (the Classic-4:3 analog) gains nothing; every
        // other default is unchanged.
        val narrow = DesktopSettingsStore(File(folder.root, "missing2.json")) { false }.load()
        assertEquals(ClientTweaksConfig(), narrow.tweaks)
        assertTrue(narrow.audioMode == Settings.AudioMode.ON)
    }
}
