package com.pocketrealm.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class SettingsDataStoreAndroidTest {
    @Test
    fun usesExistingDatastorePathAndOneInstanceInThisProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = File(context.filesDir, "datastore/$POCKET_SETTINGS_FILE_NAME")

        assertEquals(expected.canonicalFile, pocketSettingsDataFile(context).canonicalFile)
        assertSame(
            pocketSettingsStore(context),
            pocketSettingsStore(ContextWrapper(context)),
        )
    }
}
