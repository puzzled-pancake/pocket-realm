package com.pocketrealm.importer

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketrealm.BuildConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Developer-lane bridge from the debug DocumentsProvider's external staging
 * root to the real managed-client and normal-play data pipelines.
 *
 * The host copy is deleted only after the importer has re-inventoried, verified,
 * fsynced, and atomically published the app-private generation. This avoids
 * retaining two 5-GiB source copies while DBC/maps/vmaps/mmaps are extracted on
 * the bounded large AVD; production UI imports continue to use the normal,
 * more-conservative storage planner. The destructive bridge is skipped unless
 * it is run against `realmRuntime` with `-e o11HostPrepare true`.
 */
@RunWith(AndroidJUnit4::class)
class O11HostClientPreparationTest {
    @Test
    fun importHostStagingAndPrepareNormalData() = runBlocking {
        assumeTrue(
            "explicit -e o11HostPrepare true opt-in is required",
            InstrumentationRegistry.getArguments().getString("o11HostPrepare") == "true",
        )
        assumeTrue(
            "host-client preparation is restricted to realmRuntime",
            BuildConfig.BUILD_TYPE == "realmRuntime",
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostRoot = File(context.getExternalFilesDir(null), "wow")
        assertTrue("debug host staging root is absent", File(hostRoot, "WoW.exe").isFile)

        val importer = ManagedClientImporter(
            context = context,
            storagePlanner = ImportStoragePlanner(
                context = context,
                extractedEstimate = 2L * ImportLimits.GIB,
                wineEstimate = 0,
                minimumReserve = 0,
            ),
        )
        val result = try {
            importer.run(
                Uri.parse("content://com.pocketrealm.o11fixture/tree/host"),
                afterRenameBeforeActivate = {
                    check(hostRoot.deleteRecursively()) { "verified host staging could not be released" }
                },
            )
        } finally {
            importer.close()
        }

        val clientPointer = File(context.noBackupFilesDir, "client/active.json")
        val dataPointer = File(context.filesDir, "content/o11-server/active.json")
        assertTrue("managed client pointer missing", clientPointer.isFile)
        assertTrue("normal-play data pointer missing", dataPointer.isFile)
        val data = JSONObject(dataPointer.readText())
        assertTrue(data.getString("generation").isNotBlank())
        assertTrue(result.generation.isNotBlank())
    }
}
