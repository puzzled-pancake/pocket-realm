package com.pocketrealm.importer

import com.pocketrealm.importer.inno.InnoSetupReader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the installer-payload lane seam: the quick-check verdict,
 * the scratch extractor over a staged ZIP, and the ImportSource adapter
 * with its chunk-ordered inventory.
 */
class InnoLaneTest {

    @get:Rule val temp = TemporaryFolder()

    private fun installerDir(): File {
        val dir = temp.newFolder()
        SyntheticInnoInstaller.build(
            dir,
            listOf(
                SyntheticInnoInstaller.FileSpec(
                    "WoW.exe", SyntheticClientArchives.syntheticPe(), callFiltered = true,
                ),
                SyntheticInnoInstaller.FileSpec("realmlist.wtf", "set realmlist lane\n".toByteArray()),
                SyntheticInnoInstaller.FileSpec("Data/base.MPQ", SyntheticClientArchives.mpqStub("base")),
                SyntheticInnoInstaller.FileSpec("Data/dbc.MPQ", SyntheticClientArchives.mpqStub("dbc")),
                SyntheticInnoInstaller.FileSpec("Data/fonts.MPQ", SyntheticClientArchives.mpqStub("fonts")),
            ),
        )
        return dir
    }

    @Test fun quickCheckRoutesInstallerShapedZipsToTheInstallerLane() {
        val names = listOf("Wowinstall classic/setup.exe", "Wowinstall classic/setup-1.bin")
        val verdict = ArchiveQuickCheck.evaluate(ArchiveFormat.ZIP, names)
        assertTrue(verdict is ArchiveQuickCheck.Verdict.InstallerPayload)
    }

    @Test fun scratchExtractorUnpacksAZippedInstallerAndTheReaderParsesIt() {
        val installer = installerDir()
        val wrapper = temp.newFolder()
        val zipFile = SyntheticClientArchives.zip(
            temp.newFile("installer.zip"),
            listOf(
                SyntheticClientArchives.Entry(
                    "Wowinstall classic/setup.exe",
                    File(installer, "setup.exe").readBytes(),
                ),
                SyntheticClientArchives.Entry(
                    "Wowinstall classic/setup-1.bin",
                    File(installer, "setup-1.bin").readBytes(),
                ),
            ),
        )
        val scratch = temp.newFolder()
        ScratchArchiveExtractor.extract(zipFile, ArchiveFormat.ZIP, scratch, ImportLimits())
        assertTrue(File(scratch, "Wowinstall classic/setup.exe").isFile)
        InnoSetupReader.open(scratch).use { reader ->
            assertEquals("Synthetic Client", reader.appName)
            assertTrue(reader.files().any { it.path == "WoW.exe" })
        }
    }

    @Test fun innoArchiveSourceServesChunkOrderedInventoryWithExactBytes() {
        val installer = installerDir()
        val reader = InnoSetupReader.open(installer)
        val located = ArchiveClientScanner(ImportLimits(minFiles = 1, minTotalBytes = 1))
            .locate(InnoPayloadDetection.rawEntries(reader.files()))
        val source = InnoArchiveSource(reader, located.classified)
        source.use {
            val inventory = it.inventory()
            // Chunk order, not alphabetical: the payload's install order.
            assertEquals(reader.files().map { file -> file.path }, inventory.entries.map { e -> e.relativePath })
            assertEquals(reader.files().size, inventory.fileCount)
            assertEquals(reader.files().sumOf { file -> file.size }, inventory.totalBytes)
            val fingerprint = inventory.fingerprint
            assertEquals(fingerprint, it.inventory().fingerprint)
            reader.files().forEach { file ->
                val entry = inventory.entries.first { e -> e.relativePath == file.path }
                val bytes = it.open(entry).use { stream -> stream.readBytes() }
                when (file.path) {
                    "WoW.exe" -> assertTrue(bytes.contentEquals(SyntheticClientArchives.syntheticPe()))
                    "realmlist.wtf" ->
                        assertEquals("set realmlist lane\n", String(bytes, Charsets.US_ASCII))
                    else -> assertTrue(bytes.size > 0)
                }
            }
        }
    }

    @Test fun nonInnoInstallerFailsClosedAtTheReader() {
        // setup.exe plus junk slices: shape matches, headers do not.
        val dir = temp.newFolder()
        File(dir, "setup.exe").writeBytes(ByteArray(512) { it.toByte() })
        File(dir, "setup-1.bin").writeBytes(ByteArray(4096) { (it % 7).toByte() })
        var threw = false
        try {
            InnoSetupReader.open(dir).use {}
        } catch (expected: com.pocketrealm.importer.inno.InnoFormatException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun encryptedInstallerEntrySurfacesAsAnInnoFailureThroughTheSource() {
        val dir = temp.newFolder()
        SyntheticInnoInstaller.build(
            dir,
            listOf(
                SyntheticInnoInstaller.FileSpec(
                    "WoW.exe", SyntheticClientArchives.syntheticPe(), callFiltered = true,
                ),
                SyntheticInnoInstaller.FileSpec("realmlist.wtf", "set realmlist lane\n".toByteArray()),
                SyntheticInnoInstaller.FileSpec("Data/base.MPQ", SyntheticClientArchives.mpqStub("base")),
                SyntheticInnoInstaller.FileSpec("Data/dbc.MPQ", SyntheticClientArchives.mpqStub("dbc")),
            ),
        ) { encryptFirstEntry = true }
        InnoSetupReader.open(dir).use { reader ->
            val located = ArchiveClientScanner(ImportLimits(minFiles = 1, minTotalBytes = 1))
                .locate(InnoPayloadDetection.rawEntries(reader.files()))
            InnoArchiveSource(reader, located.classified).use { source ->
                val inventory = source.inventory()
                val encrypted = reader.files().first()
                val entry = inventory.entries.first { it.relativePath == encrypted.path }
                val error = runCatching { source.open(entry).use { it.readBytes() } }.exceptionOrNull()
                assertTrue("expected InnoFormatException, got $error", error is com.pocketrealm.importer.inno.InnoFormatException)
                assertTrue(error!!.message!!.contains("encrypted"))
            }
        }
    }

    @Test fun inventoryCountMismatchFailsClosedWithVal13() {
        val installer = installerDir()
        InnoSetupReader.open(installer).use { reader ->
            val located = ArchiveClientScanner(ImportLimits(minFiles = 1, minTotalBytes = 1))
                .locate(InnoPayloadDetection.rawEntries(reader.files()))
            val drifted = ImportExtractionPolicy.Classified(
                located.classified.entries, emptyList(),
                located.classified.fileCount + 1, located.classified.totalBytes,
            )
            InnoArchiveSource(reader, drifted).use { source ->
                val error = runCatching { source.inventory() }.exceptionOrNull()
                assertTrue("expected rejection, got $error", error is ImportRejected)
                assertTrue(error!!.message!!.contains("VAL-13"))
            }
        }
    }

    @Test fun wrapperLayoutPayloadIsLocatedAndRebasedToTheClientRoot() {
        val dir = temp.newFolder()
        SyntheticInnoInstaller.build(
            dir,
            listOf(
                SyntheticInnoInstaller.FileSpec(
                    "Wow112/WoW.exe", SyntheticClientArchives.syntheticPe(), callFiltered = true,
                ),
                SyntheticInnoInstaller.FileSpec("Wow112/realmlist.wtf", "set realmlist lane\n".toByteArray()),
                SyntheticInnoInstaller.FileSpec("Wow112/Data/base.MPQ", SyntheticClientArchives.mpqStub("base")),
                SyntheticInnoInstaller.FileSpec("Wow112/Data/dbc.MPQ", SyntheticClientArchives.mpqStub("dbc")),
            ),
        )
        InnoSetupReader.open(dir).use { reader ->
            val located = ArchiveClientScanner(ImportLimits(minFiles = 1, minTotalBytes = 1))
                .locate(InnoPayloadDetection.rawEntries(reader.files()))
            assertEquals("Wow112", located.rootPrefix.trimEnd('/'))
            InnoArchiveSource(reader, located.classified, located.rootPrefix).use { source ->
                val inventory = source.inventory()
                assertTrue(inventory.entries.all { !it.relativePath.startsWith("Wow112/") })
                val exe = inventory.entries.first { it.relativePath == "WoW.exe" }
                assertTrue(
                    source.open(exe).use { it.readBytes() }
                        .contentEquals(SyntheticClientArchives.syntheticPe()),
                )
            }
        }
    }
}
