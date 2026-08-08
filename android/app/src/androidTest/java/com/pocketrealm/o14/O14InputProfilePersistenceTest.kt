package com.pocketrealm.o14

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketrealm.client.InputProfile
import com.pocketrealm.client.InputProfileStore
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
        val original = prefs.getString("profile_v2", null)
        try {
            val store = InputProfileStore(context)
            val tuned = InputProfile(InputProfile.CURRENT_VERSION, 0.2f, "16:9", 1.6f, 0.7f)
            store.save(tuned)
            val reloaded = store.load("16:9")
            assertFalse(reloaded.resetForAspect)
            assertEquals(tuned, reloaded.profile)

            val changed = store.load("20:9")
            assertTrue(changed.resetForAspect)
            assertEquals("16:9", changed.profile.aspectIdentity)
        } finally {
            val edit = prefs.edit()
            if (original == null) edit.remove("profile_v2") else edit.putString("profile_v2", original)
            edit.commit()
        }
    }
}
