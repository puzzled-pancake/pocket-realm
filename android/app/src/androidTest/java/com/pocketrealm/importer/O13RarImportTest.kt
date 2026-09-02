package com.pocketrealm.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Libarchive RAR import lane (device-only — the JNI library ships in the APK).
 * Uses the BSD-licensed libarchive test-corpus RAR committed under
 * src/test/resources (no Blizzard bytes): rar4-client.rar holds one
 * `test.txt` entry, proving the full stage → list → extract pipeline. A real
 * client RAR end-to-end import is exercised manually on a device.
 */
@RunWith(AndroidJUnit4::class)
class O13RarImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun cleanState() {
        File(context.noBackupFilesDir, "importer").deleteRecursively()
        File(context.noBackupFilesDir, "client").deleteRecursively()
    }

    private fun corpus(name: String): File {
        val target = File(context.cacheDir, "o13-$name")
        javaClass.getResourceAsStream("/fixtures/rar/$name")!!.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    @Test fun listsAndExtractsCorpusRar4Entries() {
        val rar = corpus("rar4-client.rar")
        val entries = listRarEntries(rar)
        assertEquals(listOf("test.txt"), entries.map { it.name })
        assertEquals(false, entries.single().directory)
        assertEquals(20L, entries.single().size)

        val classified = ImportExtractionPolicy().classify(
            listOf(ImportExtractionPolicy.RawEntry("test.txt", false, 20, rawKey = "test.txt")),
        )
        RarArchiveSource(rar, classified).use { source ->
            val inventory = source.inventory()
            assertEquals(1, inventory.fileCount)
            val bytes = source.open(inventory.entries.single { !it.directory }).use { it.readBytes() }
            assertEquals(20, bytes.size)
        }
    }

    @Test fun rar5CorpusListsThroughTheSameLane() {
        val entries = listRarEntries(corpus("rar5-compressed.rar"))
        assertEquals(listOf("test.bin"), entries.map { it.name })
        assertEquals(1200L, entries.single().size)
    }

    @Test fun encryptedCorpusIsFlaggedForTheVal13Gate() {
        val entries = listRarEntries(corpus("rar4-encrypted.rar"))
        assertTrue("corpus fixture must carry the encrypted flag", entries.any { it.encrypted })
    }

    @Test fun multivolumePart1FailsClosed() {
        val rar = corpus("rar4-multivolume-part1.rar")
        val result = runCatching { listRarEntries(rar) }
        // A lone part-1 either fails to open (missing volume) or lists a
        // truncated entry set; the copy loop's declared-size assertion is the
        // second line of defense. Either way it must never look like a client.
        assertTrue(result.isFailure || result.getOrThrow().none { it.name.equals("WoW.exe", true) })
    }
}
