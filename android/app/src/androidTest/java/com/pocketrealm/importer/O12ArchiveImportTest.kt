package com.pocketrealm.importer

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The archive import lane mirrors the integrated-runtime lane's death-and-resume
 * discipline. The debug
 * fixture provider serves synthetic archives (SyntheticClientArchives — no
 * Blizzard bytes) under the `archives` root as single documents.
 */
@RunWith(AndroidJUnit4::class)
class O12ArchiveImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val archivesDir = File(context.getExternalFilesDir(null), "archives")

    @Before fun cleanState() {
        File(context.noBackupFilesDir, "importer").deleteRecursively()
        File(context.noBackupFilesDir, "client").deleteRecursively()
        archivesDir.deleteRecursively()
        archivesDir.mkdirs()
    }

    private fun seedArchive(name: String, entries: List<SyntheticClientArchives.Entry>): Pair<Uri, Long> {
        val file = SyntheticClientArchives.zip(File(archivesDir, name), entries)
        val uri = DocumentsContract.buildDocumentUri("com.pocketrealm.o11fixture", "archives:$name")
        return uri to file.length()
    }

    private fun validClient() = SyntheticClientArchives.clientEntries(
        wrapper = "WoW_Classic_1.12.1",
        configWtf = "SET locale \"enUS\"\n",
    )

    @Test fun archiveDeathsResumeThroughStagingDetectionExtractionAndPublish() {
        val (uri, size) = seedArchive("client.zip", validClient())

        // Death 1: right after the staged copy completes.
        ImportWorkerService.startArchive(context, uri, size, testProfile = true,
            interruptPoint = ImportWorkerService.INTERRUPT_AFTER_STAGING)
        waitFor(20_000) { status().optLong("stagedBytes") == size || status().optString("phase") != "STAGING" }
        waitFor(30_000) { status().optString("phase") != "IDLE" && status().optString("phase") != "STAGING" }
        waitWorkerGone(30_000)

        // Death 2: right after detection pinned the identity.
        ImportWorkerService.startArchive(context, uri, size, testProfile = true,
            interruptPoint = ImportWorkerService.INTERRUPT_AFTER_DETECTION)
        waitFor(30_000) { status().optString("phase") == ImportPhase.EXTRACTING.name }
        waitWorkerGone(30_000)

        // Death 3: one verified extraction entry.
        ImportWorkerService.startArchive(context, uri, size, testProfile = true, interruptAfter = 1)
        waitFor(30_000) { status().optInt("filesProcessed") >= 1 }
        waitWorkerGone()

        // Final run completes; staged file is gone and the generation publishes.
        ImportWorkerService.startArchive(context, uri, size, testProfile = true)
        waitFor(60_000) { status().optString("phase") == ImportPhase.COMPLETE.name }
        val incoming = File(File(context.noBackupFilesDir, "client"), "incoming")
        assertFalse("staged archive must not outlive the import", incoming.listFiles().orEmpty().isNotEmpty())

        val pointer = JSONObject(File(context.noBackupFilesDir, "client/active.json").readText())
        val generation = File(context.noBackupFilesDir, "client/generations/${pointer.getString("generation")}")
        val manifest = JSONObject(File(generation, "client-manifest.json").readText())
        assertEquals(5875, manifest.getJSONObject("identity").getInt("build"))
        assertEquals("1.12.1.5875", manifest.getJSONObject("identity").getString("version"))
        assertTrue(File(generation, "WoW.exe").isFile)
        assertTrue(File(generation, "Data/base.MPQ").isFile)
        assertFalse(generation.walkTopDown().any { it.name.contains(".partial.") })
        assertFalse(generation.walkTopDown().any { it.name == "launch.bat" })
    }

    @Test fun installerArchiveIsRejectedAndLeavesNoStagedOrphan() {
        val entries = listOf(
            SyntheticClientArchives.Entry("Wowinstall classic", directory = true),
            SyntheticClientArchives.Entry("Wowinstall classic/setup.exe", ByteArray(64)),
            SyntheticClientArchives.Entry("Wowinstall classic/setup-1.bin", ByteArray(1024)),
            SyntheticClientArchives.Entry("Wowinstall classic/setup-2.bin", ByteArray(1024)),
        )
        val (uri, size) = seedArchive("installer.zip", entries)
        val importer = ManagedClientImporter(
            context, ImportLimits(minFiles = 1, minTotalBytes = 1, maxFiles = 128, maxTotalBytes = 64L shl 20),
            storagePlanner = ImportStoragePlanner(context, 0, 0, 16L shl 20),
            prepareData = false,
        )
        val result = runCatching { kotlinx.coroutines.runBlocking { importer.runArchive(uri, size) } }
        importer.close()
        val error = result.exceptionOrNull()
        assertTrue("expected rejection, got $error", error is ImportRejected)
        assertTrue(error!!.message!!.contains("VAL-12"))
        assertFalse(File(context.noBackupFilesDir, "client/active.json").exists())
        val incoming = File(File(context.noBackupFilesDir, "client"), "incoming")
        assertFalse("rejection must delete the staged copy", incoming.listFiles().orEmpty().isNotEmpty())
    }

    @Test fun innoInstallerArchiveExtractsTheClientAndPublishes() {
        // A full synthetic client packed as an Inno installer, zipped up the
        // way the real WoW-1.12.1_install.rar ships it.
        val installerDir = File(archivesDir, "installer-payload")
        val specs = validClient().filter { !it.directory }.map { entry ->
            SyntheticInnoInstaller.FileSpec(
                entry.path.removePrefix("WoW_Classic_1.12.1/"),
                entry.bytes,
                callFiltered = entry.path.endsWith("WoW.exe"),
            )
        }
        SyntheticInnoInstaller.build(installerDir, specs)
        val (uri, size) = seedArchive(
            "wow-install.zip",
            listOf(
                SyntheticClientArchives.Entry("Wowinstall classic", directory = true),
                SyntheticClientArchives.Entry(
                    "Wowinstall classic/setup.exe",
                    File(installerDir, "setup.exe").readBytes(),
                ),
                SyntheticClientArchives.Entry(
                    "Wowinstall classic/setup-1.bin",
                    File(installerDir, "setup-1.bin").readBytes(),
                ),
            ),
        )

        ImportWorkerService.startArchive(context, uri, size, testProfile = true)
        waitFor(60_000) { status().optString("phase") == ImportPhase.COMPLETE.name }
        val incoming = File(File(context.noBackupFilesDir, "client"), "incoming")
        assertFalse(
            "staged archive and scratch dir must not outlive the import",
            incoming.listFiles().orEmpty().isNotEmpty(),
        )

        val pointer = JSONObject(File(context.noBackupFilesDir, "client/active.json").readText())
        val generation = File(context.noBackupFilesDir, "client/generations/${pointer.getString("generation")}")
        val manifest = JSONObject(File(generation, "client-manifest.json").readText())
        assertEquals(5875, manifest.getJSONObject("identity").getInt("build"))
        assertTrue(File(generation, "WoW.exe").isFile)
        assertTrue(File(generation, "Data/base.MPQ").isFile)
        // The installer's stored WoW.exe is call-filtered: byte equality with
        // the fixture proves the inverse transform ran during extraction.
        assertTrue(
            File(generation, "WoW.exe").readBytes().contentEquals(
                SyntheticClientArchives.syntheticPe(),
            ),
        )
    }

    @Test fun installerArchiveSurvivesDeathDuringScratchExtraction() {
        val installerDir = File(archivesDir, "installer-payload-death")
        val specs = validClient().filter { !it.directory }.map { entry ->
            SyntheticInnoInstaller.FileSpec(
                entry.path.removePrefix("WoW_Classic_1.12.1/"),
                entry.bytes,
                callFiltered = entry.path.endsWith("WoW.exe"),
            )
        }
        SyntheticInnoInstaller.build(installerDir, specs)
        val (uri, size) = seedArchive(
            "wow-install-death.zip",
            listOf(
                SyntheticClientArchives.Entry("Wowinstall classic", directory = true),
                SyntheticClientArchives.Entry(
                    "Wowinstall classic/setup.exe",
                    File(installerDir, "setup.exe").readBytes(),
                ),
                SyntheticClientArchives.Entry(
                    "Wowinstall classic/setup-1.bin",
                    File(installerDir, "setup-1.bin").readBytes(),
                ),
            ),
        )

        // Death mid-scratch-extraction leaves a partial, unmarked scratch dir.
        ImportWorkerService.startArchive(
            context, uri, size, testProfile = true,
            interruptAfter = 1, interruptPoint = ImportWorkerService.INTERRUPT_DURING_SCRATCH,
        )
        waitFor(30_000) { status().optLong("stagedBytes") == size }
        waitWorkerGone(30_000)

        // The resume must re-extract the partial scratch (not terminally
        // reject it as a non-Inno payload), then complete and publish.
        ImportWorkerService.startArchive(context, uri, size, testProfile = true)
        waitFor(60_000) { status().optString("phase") == ImportPhase.COMPLETE.name }
        val incoming = File(File(context.noBackupFilesDir, "client"), "incoming")
        assertFalse(incoming.listFiles().orEmpty().isNotEmpty())
        val pointer = JSONObject(File(context.noBackupFilesDir, "client/active.json").readText())
        val generation = File(context.noBackupFilesDir, "client/generations/${pointer.getString("generation")}")
        assertEquals(5875, JSONObject(File(generation, "client-manifest.json").readText())
            .getJSONObject("identity").getInt("build"))
    }

    @Test fun encryptedArchiveIsRejectedWithVal13() {
        val (uri, size) = seedArchive("encrypted.zip", validClient())
        SyntheticClientArchives.patchEncryptionBits(File(archivesDir, "encrypted.zip"))
        val importer = ManagedClientImporter(
            context, ImportLimits(minFiles = 1, minTotalBytes = 1, maxFiles = 128, maxTotalBytes = 64L shl 20),
            storagePlanner = ImportStoragePlanner(context, 0, 0, 16L shl 20),
            prepareData = false,
        )
        val result = runCatching { kotlinx.coroutines.runBlocking { importer.runArchive(uri, size) } }
        importer.close()
        val error = result.exceptionOrNull()
        assertTrue(error is ImportRejected)
        assertTrue(error!!.message!!.contains("VAL-13"))
        assertFalse(File(context.noBackupFilesDir, "client/active.json").exists())
    }

    private fun status(): JSONObject = ImportWorkerService.readStatus(context)
    private fun waitWorkerGone(timeoutMs: Long = 10_000) = waitFor(timeoutMs) {
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .none { it.processName == "${context.packageName}:import" }
    }
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(100)
        }
        error("timed out; status=${runCatching { status() }}")
    }
}
