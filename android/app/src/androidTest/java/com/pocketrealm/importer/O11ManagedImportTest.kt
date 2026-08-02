package com.pocketrealm.importer

import android.content.Context
import android.app.ActivityManager
import android.net.Uri
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

@RunWith(AndroidJUnit4::class)
class O11ManagedImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val valid = Uri.parse("content://com.pocketrealm.o11fixture/tree/valid")
    private val wrong = Uri.parse("content://com.pocketrealm.o11fixture/tree/wrong")

    @Before fun cleanState() {
        File(context.noBackupFilesDir, "importer").deleteRecursively()
        File(context.noBackupFilesDir, "client").deleteRecursively()
    }

    @Test fun tenWorkerDeathsResumeRepairAndPublishOneImmutableGeneration() {
        // Death 1 leaves one fully fsynced journal entry.
        ImportWorkerService.start(context, valid, testProfile = true, interruptAfter = 1)
        waitFor(20_000) { status().optInt("filesProcessed") >= 1 }
        waitWorkerGone()
        corruptOneVerifiedFile()

        // Death 2 repairs that same verified entry, so the processed count
        // correctly stays at one even though its journal attempt increments.
        ImportWorkerService.start(context, valid, testProfile = true, interruptAfter = 1)
        Thread.sleep(1_000)
        waitWorkerGone()
        assertEquals(1, status().getInt("filesProcessed"))

        // Deaths 3..8 advance six more durable entries.
        for (target in 2..7) {
            ImportWorkerService.start(context, valid, testProfile = true, interruptAfter = 1)
            waitFor(20_000) { status().optInt("filesProcessed") >= target }
            waitWorkerGone()
        }

        // Death 9 lands after full re-verification but before generation publish.
        ImportWorkerService.start(context, valid, testProfile = true,
            interruptPoint = ImportWorkerService.INTERRUPT_BEFORE_PUBLISH)
        waitFor(30_000) { status().optString("phase") == ImportPhase.PUBLISHING.name }
        waitWorkerGone(30_000)
        assertFalse(File(context.noBackupFilesDir, "client/active.json").exists())
        assertEquals(1, File(context.noBackupFilesDir, "client/generations").listFiles()
            .orEmpty().count { it.name.startsWith(".staging-") })

        // Death 10 lands after the fsynced rename but before active-pointer activation.
        ImportWorkerService.start(context, valid, testProfile = true,
            interruptPoint = ImportWorkerService.INTERRUPT_AFTER_RENAME)
        waitFor(30_000) { File(context.noBackupFilesDir, "client/generations").listFiles()
            .orEmpty().any { it.isDirectory && !it.name.startsWith(".staging-") } }
        waitWorkerGone(30_000)
        assertFalse(File(context.noBackupFilesDir, "client/active.json").exists())
        assertEquals(1, File(context.noBackupFilesDir, "client/generations").listFiles()
            .orEmpty().count { it.isDirectory && !it.name.startsWith(".staging-") })

        ImportWorkerService.start(context, valid, testProfile = true)
        waitFor(60_000) { status().optString("phase") == ImportPhase.COMPLETE.name }

        val pointer = JSONObject(File(context.noBackupFilesDir, "client/active.json").readText())
        val generation = File(context.noBackupFilesDir, "client/generations/${pointer.getString("generation")}")
        val manifest = JSONObject(File(generation, "client-manifest.json").readText())
        assertTrue(manifest.getBoolean("complete"))
        assertEquals(2, manifest.getInt("schema"))
        assertTrue(manifest.getJSONObject("journal").getInt("maxAttempt") >= 2)
        assertEquals(1, File(context.noBackupFilesDir, "client/generations").listFiles()
            .orEmpty().count { it.isDirectory && !it.name.startsWith(".staging-") })
        assertFalse(generation.walkTopDown().any { it.name.contains(".partial.") })

        // Re-import is a new immutable generation, never an overlay. Retention
        // stays bounded to current + previous and the previous pointer is durable.
        val firstId = pointer.getString("generation")
        ImportWorkerService.start(context, valid, testProfile = true)
        waitFor(60_000) {
            val active = File(context.noBackupFilesDir, "client/active.json")
            active.isFile && JSONObject(active.readText()).getString("generation") != firstId &&
                status().optString("phase") == ImportPhase.COMPLETE.name
        }
        val secondId = JSONObject(File(context.noBackupFilesDir, "client/active.json").readText())
            .getString("generation")
        assertTrue(secondId != firstId)
        assertEquals(firstId, JSONObject(File(context.noBackupFilesDir, "client/previous.json").readText())
            .getString("generation"))
        assertEquals(2, File(context.noBackupFilesDir, "client/generations").listFiles()
            .orEmpty().count { it.isDirectory && !it.name.startsWith(".staging-") })

        ImportWorkerService.start(context, valid, testProfile = true)
        waitFor(60_000) {
            val active = File(context.noBackupFilesDir, "client/active.json")
            active.isFile && JSONObject(active.readText()).getString("generation") != secondId &&
                status().optString("phase") == ImportPhase.COMPLETE.name
        }
        assertEquals(secondId, JSONObject(File(context.noBackupFilesDir, "client/previous.json").readText())
            .getString("generation"))
        assertEquals(2, File(context.noBackupFilesDir, "client/generations").listFiles()
            .orEmpty().count { it.isDirectory && !it.name.startsWith(".staging-") })
        assertFalse(File(context.noBackupFilesDir, "client/generations/$firstId").exists())
    }

    @Test fun wrongBuildNeverCreatesManagedGeneration() {
        val importer = ManagedClientImporter(
            context, ImportLimits(minFiles = 3, minTotalBytes = 1, maxFiles = 128, maxTotalBytes = 64L shl 20),
            storagePlanner = ImportStoragePlanner(context, 0, 0, 16L shl 20),
            prepareData = false,
        )
        val result = runCatching { kotlinx.coroutines.runBlocking { importer.run(wrong) } }
        importer.close()
        assertTrue(result.exceptionOrNull() is ImportRejected)
        assertFalse(File(context.noBackupFilesDir, "client/active.json").exists())
    }

    private fun corruptOneVerifiedFile() {
        val staging = File(context.noBackupFilesDir, "client/generations").listFiles()
            .orEmpty().single { it.name.startsWith(".staging-") }
        val file = staging.walkTopDown().first { it.isFile }
        file.writeText("corrupt")
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
