package com.pocketrealm.importer.inno

import com.pocketrealm.importer.SyntheticClientArchives
import com.pocketrealm.importer.SyntheticInnoInstaller
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reader tests over synthetic Inno installers produced by the fixture
 * writer: header parse, {app} projection, session extraction with digest
 * verification (including the x86 call-filter roundtrip), solid and
 * per-file chunk layouts, stored chunks, and the fail-closed paths.
 */
class InnoSetupReaderTest {

    @get:Rule val temp = TemporaryFolder()

    private fun clientFiles(callFiltered: Boolean = true) = listOf(
        SyntheticInnoInstaller.FileSpec(
            "WoW.exe",
            SyntheticClientArchives.syntheticPe(),
            callFiltered = callFiltered,
        ),
        SyntheticInnoInstaller.FileSpec("realmlist.wtf", "set realmlist test\n".toByteArray()),
        SyntheticInnoInstaller.FileSpec(
            "Data/base.MPQ",
            SyntheticClientArchives.mpqStub("base"),
        ),
        SyntheticInnoInstaller.FileSpec(
            "Data/dbc.MPQ",
            SyntheticClientArchives.mpqStub("dbc"),
        ),
        SyntheticInnoInstaller.FileSpec("fmod.dll", ByteArray(64) { it.toByte() }),
    )

    private fun build(
        configure: SyntheticInnoInstaller.Builder.() -> Unit = {},
    ): Pair<File, List<SyntheticInnoInstaller.FileSpec>> {
        val dir = temp.newFolder()
        val files = clientFiles()
        SyntheticInnoInstaller.build(dir, files, configure)
        return dir to files
    }

    private fun extractAll(reader: InnoSetupReader): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        reader.session().use { session ->
            for (file in reader.files()) {
                session.open(file).use { stream -> out[file.path] = stream.readBytes() }
            }
        }
        return out
    }

    @Test fun parsesSolidLzma1InstallerAndProjectsAppPaths() {
        val (dir, files) = build()
        InnoSetupReader.open(dir).use { reader ->
            assertEquals("5.3.5", reader.version.toString())
            assertEquals("Synthetic Client", reader.appName)
            assertEquals("1.12.1", reader.appVersion)
            assertEquals(files.size, reader.files().size)
            assertEquals(
                listOf("WoW.exe", "realmlist.wtf", "Data/base.MPQ", "Data/dbc.MPQ", "fmod.dll"),
                reader.files().map { it.path },
            )
        }
    }

    @Test fun sessionExtractsEveryFileByteExactWithDigests() {
        val (dir, files) = build()
        InnoSetupReader.open(dir).use { reader ->
            val extracted = extractAll(reader)
            files.forEach { spec ->
                val path = spec.path
                assertTrue("missing $path", extracted.containsKey(path))
                assertTrue(
                    "content mismatch for $path",
                    extracted[path]!!.contentEquals(spec.bytes),
                )
            }
        }
    }

    @Test fun perFileChunksAndStoredChunksExtractToo() {
        listOf(
            { b: SyntheticInnoInstaller.Builder -> b.solid = false },
            { b: SyntheticInnoInstaller.Builder -> b.lzma1Chunks = false },
            { b: SyntheticInnoInstaller.Builder ->
                b.solid = false; b.lzma1Chunks = false
            },
        ).forEach { configure ->
            val dir = temp.newFolder()
            val files = clientFiles()
            SyntheticInnoInstaller.build(dir, files) { configure(this) }
            InnoSetupReader.open(dir).use { reader ->
                val extracted = extractAll(reader)
                files.forEach { spec ->
                    assertTrue(extracted[spec.path]!!.contentEquals(spec.bytes))
                }
            }
        }
    }

    @Test fun storedHeaderBlocksAreReadable() {
        val (dir, files) = build { blockCompression = false }
        InnoSetupReader.open(dir).use { reader ->
            val extracted = extractAll(reader)
            files.forEach { spec ->
                assertTrue(extracted[spec.path]!!.contentEquals(spec.bytes))
            }
        }
    }

    @Test fun multipleSlicesAreTraversedInChunkOrder() {
        val dir = temp.newFolder()
        val files = clientFiles()
        // Force several tiny slices so chunk reads cross slice boundaries.
        SyntheticInnoInstaller.build(dir, files) { sliceBytes = 32 }
        InnoSetupReader.open(dir).use { reader ->
            assertTrue(dir.resolve("setup-2.bin").isFile)
            val extracted = extractAll(reader)
            files.forEach { spec ->
                assertTrue(extracted[spec.path]!!.contentEquals(spec.bytes))
            }
        }
    }

    @Test fun entriesOutsideAppDirectoryAreNotPayload() {
        val dir = temp.newFolder()
        SyntheticInnoInstaller.build(
            dir,
            listOf(
                SyntheticInnoInstaller.FileSpec("WoW.exe", SyntheticClientArchives.syntheticPe()),
                SyntheticInnoInstaller.FileSpec(
                    "System/driver.sys", ByteArray(8), directoryConstant = "sys",
                ),
            ),
        )
        InnoSetupReader.open(dir).use { reader ->
            assertEquals(listOf("WoW.exe"), reader.files().map { it.path })
        }
    }

    @Test fun encryptedEntryFailsClosedOnOpen() {
        val (dir, files) = build { encryptFirstEntry = true }
        InnoSetupReader.open(dir).use { reader ->
            val first = reader.files().first()
            var threw = false
            try {
                reader.session().use { it.open(first).use { s -> s.readBytes() } }
            } catch (expected: InnoFormatException) {
                threw = true
            }
            assertTrue(threw)
        }
    }

    @Test fun corruptedSetupExeIsRejected() {
        val (dir, _) = build()
        val exe = dir.resolve("setup.exe")
        val bytes = exe.readBytes()
        // Flip a byte inside the primary header block payload.
        bytes[0x300] = (bytes[0x300].toInt() xor 0x41).toByte()
        exe.writeBytes(bytes)
        var threw = false
        try {
            InnoSetupReader.open(dir).use {}
        } catch (expected: InnoFormatException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun truncatedSlicesAreRejected() {
        val (dir, _) = build()
        val bin = dir.resolve("setup-1.bin")
        val bytes = bin.readBytes()
        bin.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
        var threw = false
        try {
            InnoSetupReader.open(dir).use { reader -> extractAll(reader) }
        } catch (expected: Exception) {
            threw = expected is InnoFormatException || expected is java.io.EOFException
        }
        assertTrue(threw)
    }

    @Test fun sessionSurvivesARewindRequest() {
        val (dir, files) = build()
        InnoSetupReader.open(dir).use { reader ->
            reader.session().use { session ->
                val all = reader.files()
                // Drain the last file, then request the first again: the
                // session re-opens the chunk and skips forward once.
                session.open(all.last()).use { it.readBytes() }
                val again = session.open(all.first()).use { it.readBytes() }
                assertTrue(again.contentEquals(files.first().bytes))
            }
        }
    }
}
