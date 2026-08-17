package com.pocketrealm.o14

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
import com.pocketrealm.client.ControlScheme
import com.pocketrealm.storage.Settings
import com.pocketrealm.ui.loadProfileForControls
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device proof for the durable profile record and aspect-reset contract. */
@RunWith(AndroidJUnit4::class)
class O14InputProfilePersistenceTest {
    @Test
    fun vanillaConsolePortIsOptionalAndCompareRestoresThePriorProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pocket_input_profile", Context.MODE_PRIVATE)
        val keys = listOf(
            "profile_v12", "profile_v11", "profile_v10", "profile_v9", "profile_v8", "profile_v7", "profile_v6", "profile_v5", "profile_v4", "profile_v3", "profile_v2",
            "vanilla_console_port_prior_v1", "vanilla_console_port_applied_v1",
        )
        val originals = keys.associateWith { prefs.getString(it, null) }
        try {
            prefs.edit().apply { keys.forEach(::remove) }.commit()
            val store = InputProfileStore(context)
            val prior = InputProfile.DEFAULT.copy(cameraSensitivity = 1.35f)
            store.save(prior)

            val enabled = store.enableAndroidPort()
            assertEquals(ControlScheme.ANDROID_PORT, enabled.scheme)
            assertTrue(store.hasManagedAndroidPort())
            assertEquals(prior, store.disableAndroidPort())
            assertFalse(store.hasManagedAndroidPort())

            store.enableAndroidPort()
            val customized = store.loadStoredOrDefault().copy(cameraSensitivity = 1.7f)
            store.save(customized)
            assertEquals(customized, store.disableAndroidPort())
            assertFalse(store.hasManagedAndroidPort())
        } finally {
            val edit = prefs.edit()
            originals.forEach { (key, original) ->
                if (original == null) edit.remove(key) else edit.putString(key, original)
            }
            edit.commit()
        }
    }

    @Test
    fun profileSurvivesStoreReloadAndFlagsAspectChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pocket_input_profile", Context.MODE_PRIVATE)
        val keys = listOf(
            "profile_v12", "profile_v11", "profile_v10", "profile_v9", "profile_v8", "profile_v7", "profile_v6", "profile_v5", "profile_v4", "profile_v3", "profile_v2",
        )
        val originals = keys.associateWith { prefs.getString(it, null) }
        try {
            val store = InputProfileStore(context)
            val tuned = InputProfile(
                version = InputProfile.CURRENT_VERSION,
                deadZone = 0.2f,
                aspectIdentity = "16:9",
                cameraSensitivity = 1.6f,
                overlayOpacity = 0.7f,
            )
            store.save(tuned)
            val reloaded = store.load("16:9")
            assertFalse(reloaded.resetForAspect)
            assertEquals(tuned, reloaded.profile)

            val changed = store.load("20:9")
            assertTrue(changed.resetForAspect)
            assertEquals("20:9", changed.profile.aspectIdentity)
        } finally {
            val edit = prefs.edit()
            originals.forEach { (key, original) ->
                if (original == null) edit.remove(key) else edit.putString(key, original)
            }
            edit.commit()
        }
    }

    @Test
    fun nonWidescreenProfileSurvivesAndCorruptCurrentFallsBackToLegacy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pocket_input_profile", Context.MODE_PRIVATE)
        val keys = listOf(
            "profile_v12", "profile_v11", "profile_v10", "profile_v9", "profile_v8", "profile_v7", "profile_v6", "profile_v5", "profile_v4", "profile_v3", "profile_v2",
        )
        val originals = keys.associateWith { prefs.getString(it, null) }
        try {
            val store = InputProfileStore(context)
            val nonWidescreen = InputProfile.DEFAULT.copy(
                aspectIdentity = "16:10",
                cameraSensitivity = 1.4f,
            )
            store.save(nonWidescreen)
            assertEquals(nonWidescreen, store.load("16:10").profile)
            assertEquals(nonWidescreen, loadProfileForControls(context))
            // A hostless settings read must be non-mutating and must not
            // manufacture the default 16:9 identity.
            assertEquals("16:10", InputProfile.fromJson(
                org.json.JSONObject(prefs.getString("profile_v12", null)!!),
            ).aspectIdentity)

            prefs.edit()
                // Remove the valid current record so this test genuinely
                // exercises legacy fallback without contaminating profile_v12.
                .remove("profile_v12")
                .putString("profile_v11", "{not valid json")
                .putString("profile_v10", "{not valid legacy json")
                .putString("profile_v9", "{not valid legacy json")
                .putString("profile_v8", "{not valid legacy json")
                .putString("profile_v7", "{not valid legacy json")
                .putString("profile_v6", InputProfile.toJson(
                    nonWidescreen.copy(version = InputProfile.CURRENT_VERSION),
                ).put("version", 6).toString())
                .commit()
            val fallback = store.load("16:10")
            assertFalse(fallback.resetForAspect)
            assertEquals("16:10", fallback.profile.aspectIdentity)
            assertEquals(1.4f, fallback.profile.cameraSensitivity)
        } finally {
            val edit = prefs.edit()
            originals.forEach { (key, original) ->
                if (original == null) edit.remove(key) else edit.putString(key, original)
            }
            edit.commit()
        }
    }

    @Test
    fun inputSafeModePersistsWithoutTouchingRuntimeGenerations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(context)
        val original = settings.flow.first()
        try {
            settings.update { it.copy(inputSafeMode = true) }
            assertTrue(Settings(context).flow.first().inputSafeMode)
            settings.update { it.copy(inputSafeMode = false) }
            assertFalse(Settings(context).flow.first().inputSafeMode)
        } finally {
            settings.update { original }
        }
    }
}
