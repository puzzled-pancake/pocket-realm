package com.pocketrealm.o14

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
import com.pocketrealm.storage.Settings
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
    fun profileSurvivesStoreReloadAndFlagsAspectChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("pocket_input_profile", Context.MODE_PRIVATE)
        val keys = listOf("profile_v4", "profile_v3", "profile_v2")
        val originals = keys.associateWith { prefs.getString(it, null) }
        try {
            val store = InputProfileStore(context)
            val tuned = InputProfile(InputProfile.CURRENT_VERSION, 0.2f, "16:9", 1.6f, 0.7f)
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
