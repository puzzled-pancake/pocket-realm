package com.pocketrealm.importer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit live-device bridge for resuming an already checkpointed O11 data
 * preparation after the original host staging copy has been released.
 *
 * It never imports, deletes, or rewrites the managed client. The one existing
 * staging generation and its durable ImportJournal checkpoints remain the
 * authority, while the currently verified build-5875 generation supplies the
 * read-only MPQs needed by the remaining extractors.
 */
@RunWith(AndroidJUnit4::class)
class O11LiveDataResumeTest {
    @Test fun resumeSingleCheckpointedGeneration() = runBlocking {
        assumeTrue(
            "explicit -e o11LiveResume true opt-in is required",
            InstrumentationRegistry.getArguments().getString("o11LiveResume") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clientPointer = JSONObject(
            File(context.noBackupFilesDir, "client/active.json").readText(),
        )
        val clientRoot = File(
            context.noBackupFilesDir,
            "client/generations/${clientPointer.getString("generation")}",
        )
        assertTrue(File(clientRoot, "WoW.exe").isFile)

        val generations = File(context.filesDir, "content/o11-server/generations")
        val staging = generations.listFiles().orEmpty().filter {
            it.isDirectory && it.name.matches(Regex("\\.staging-[0-9a-f-]{36}"))
        }
        check(staging.size == 1) {
            "expected exactly one checkpointed O11 staging generation, found ${staging.map { it.name }}"
        }
        val importId = staging.single().name.removePrefix(".staging-")
        ImportJournal(context).use { journal ->
            val published = DataPreparationStore(context, journal).prepare(importId, clientRoot)
            assertTrue(File(published.root, "data-manifest.json").isFile)
        }
        val active = JSONObject(File(context.filesDir, "content/o11-server/active.json").readText())
        assertTrue(active.getString("generation") == importId)
    }
}
