package com.pocketrealm.importer

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.ManagedClientStore
import com.pocketrealm.desktop.DesktopStorageRoots
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-97: the desktop ARCHIVE lane end to end — the shared
 * StagedArchive/ArchiveFormatSniffer/InnoSetupReader stack driven from a
 * picked file: sniff → quick check → detection (desktop ArchiveClientScanner
 * over the shared identity parse) → Inno scratch extraction → the same
 * copy/verify/publish pipeline as the folder lane. All fixtures are
 * synthetic (no Blizzard bytes committed).
 */
class ManagedClientImporterArchiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The synthetic client is small; relax the floor exactly like the
     * Android fixture lanes, keeping the caps. */
    private val limits = ImportLimits(minFiles = 1, minTotalBytes = 0L)

    /** These lanes exercise copy/verify/publish, not the storage budget: a
     * fixed generous allocatable keeps the preflight off the host TEMP
     * volume's real headroom (the planner seam). */
    private fun injectedImporter(roots: DesktopStorageRoots) =
        ManagedClientImporter(roots, limits, allocatableBytes = { 64L * ImportLimits.GIB })

    private fun roots(): DesktopStorageRoots =
        DesktopStorageRoots(folder.newFolder("roots")).also { it.ensureDirectories() }

    private fun zipOf(name: String, entries: List<SyntheticClientArchives.Entry>): File =
        SyntheticClientArchives.zip(folder.newFile(name), entries)

    private fun assertPublishes(roots: DesktopStorageRoots, result: ManagedClientImporter.ImportResult) {
        assertEquals(result.generation, ClientGenerationStore(roots).activeGeneration())
        // THE assertion: the fail-closed reader accepts the imported archive.
        val managed = ManagedClientStore(roots).load(ClientFlavors.VANILLA.clientId)
        assertEquals(result.generation, managed.generation)
        assertTrue(File(managed.root, "Data/base.MPQ").isFile)
    }

    @Test
    fun aZippedClientImportsAndPublishesIncludingAWrapperFolder() {
        val roots = roots()
        val archive = zipOf(
            "client.zip",
            SyntheticClientArchives.clientEntries(wrapper = "WoW_Classic_1.12.1"),
        )
        val result = runBlocking { injectedImporter(roots).runArchive(archive) }
        assertPublishes(roots, result)
        assertEquals(
            "the wrapper is rebased: WoW.exe lands at the generation root",
            "WoW.exe",
            result.inventory.entries.first { it.relativePath.endsWith("WoW.exe") }.relativePath,
        )
    }

    @Test
    fun aSevenZipClientImportsAndPublishes() {
        val roots = roots()
        val archive = SyntheticClientArchives.sevenZip(
            folder.newFile("client.7z"),
            SyntheticClientArchives.clientEntries(wrapper = null),
        )
        val result = runBlocking { injectedImporter(roots).runArchive(archive) }
        assertPublishes(roots, result)
    }

    @Test
    fun anInnoInstallerPayloadExtractsDetectsAndPublishes() {
        val roots = roots()
        // Build the synthetic installer (setup.exe + setup-N.bin slices), zip
        // it under a wrapper: the quick check must route it to the Inno lane.
        // The full qualified MPQ union rides along: the post-copy
        // verifyExtracted gate checks the complete set.
        val installer = SyntheticInnoInstaller.build(
            folder.newFolder("installer"),
            listOf(
                SyntheticInnoInstaller.FileSpec(
                    "WoW.exe",
                    SyntheticClientArchives.syntheticPe(),
                    callFiltered = true,
                ),
                SyntheticInnoInstaller.FileSpec("realmlist.wtf", "set realmlist lane\n".toByteArray()),
            ) + SyntheticClientArchives.BASE_MPQS.map { mpq ->
                SyntheticInnoInstaller.FileSpec("Data/$mpq", SyntheticClientArchives.mpqStub(mpq))
            },
        )
        val archive = zipOf(
            "installer.zip",
            listOf(
                SyntheticClientArchives.Entry("Wowinstall classic/setup.exe", File(installer, "setup.exe").readBytes()),
                SyntheticClientArchives.Entry("Wowinstall classic/setup-1.bin", File(installer, "setup-1.bin").readBytes()),
            ),
        )
        var scratchTicks = 0
        val result = runBlocking {
            injectedImporter(roots).runArchive(archive, onScratchEntry = { scratchTicks = it })
        }
        assertTrue("the scratch extraction must tick", scratchTicks > 0)
        assertPublishes(roots, result)
    }

    @Test
    fun aForeignBuildArchiveIsRefusedWithTheVal03CopyBeforeAnyCopy() {
        val roots = roots()
        val archive = zipOf(
            "wrong-build.zip",
            SyntheticClientArchives.clientEntries(wrapper = null, build = 6005),
        )
        try {
            runBlocking { injectedImporter(roots).runArchive(archive) }
            fail("a non-qualified archive must be rejected")
        } catch (expected: ImportRejected) {
            val message = expected.message ?: ""
            assertTrue("must carry VAL-03: $message", "VAL-03" in message)
            assertTrue("must name the detected version: $message", "1.12.1.6005" in message)
        }
        assertEquals("the rejection must not publish a pointer", null, ClientGenerationStore(roots).activeGeneration())
    }

    @Test
    fun anEncryptedArchiveIsRefusedWithTheVal13Copy() {
        val roots = roots()
        val archive = SyntheticClientArchives.patchEncryptionBits(
            zipOf("encrypted.zip", SyntheticClientArchives.clientEntries(wrapper = null)),
        )
        try {
            runBlocking { injectedImporter(roots).runArchive(archive) }
            fail("an encrypted archive must be rejected")
        } catch (expected: ImportRejected) {
            assertTrue(
                "must carry the VAL-13 password copy: ${expected.message}",
                (expected.message ?: "").startsWith("VAL-13: the archive is password-protected"),
            )
        }
    }

    @Test
    fun aLauncherOnlyArchiveIsRefusedWithTheVal01Copy() {
        val roots = roots()
        val archive = zipOf(
            "launcher.zip",
            listOf(
                SyntheticClientArchives.Entry("Launcher/Bootstrap.dll", ByteArray(64) { it.toByte() }),
                SyntheticClientArchives.Entry("Launcher/Readme.txt", "downloader".toByteArray()),
            ),
        )
        try {
            runBlocking { injectedImporter(roots).runArchive(archive) }
            fail("a launcher-only archive must be rejected")
        } catch (expected: ImportRejected) {
            assertTrue(
                "must carry the archive VAL-01 copy: ${expected.message}",
                "VAL-01: no WoW.exe anywhere in the archive" in (expected.message ?: ""),
            )
        }
    }

    @Test
    fun anUnreadableRarStubIsRefusedWithTheVal13Copy() {
        val roots = roots()
        // DESK-35/64: the RAR lane is real now (junrar twin); a stub that
        // carries only the RAR5 signature has no parseable headers, so the
        // walk refuses as VAL-13 unreadable before anything is staged.
        val archive = folder.newFile("client.rar")
            .apply {
                writeBytes(
                    SyntheticClientArchives.rarSignatureStub(rar5 = true) +
                        ByteArray(ArchiveFormatSniffer.HEADER_BYTES + 16),
                )
            }
        try {
            runBlocking { injectedImporter(roots).runArchive(archive) }
            fail("an unreadable RAR archive must be refused honestly")
        } catch (expected: ImportRejected) {
            val message = expected.message ?: ""
            assertTrue("must carry the VAL-13 RAR refusal: $message", "VAL-13" in message && "RAR" in message)
        }
        // The refusal leaves nothing behind (scratch or pointer).
        assertEquals(null, ClientGenerationStore(roots).activeGeneration())
    }

    @Test
    fun anIsoImageIsRefusedWithTheVal11Copy() {
        val roots = roots()
        val archive = folder.newFile("client.iso")
            .apply { writeBytes(SyntheticClientArchives.isoStub()) }
        try {
            runBlocking { injectedImporter(roots).runArchive(archive) }
            fail("a disc image must be rejected")
        } catch (expected: ImportRejected) {
            assertTrue(
                "must carry the VAL-11 copy: ${expected.message}",
                (expected.message ?: "").startsWith("VAL-11: disc images are not supported"),
            )
        }
    }

    @Test
    fun aShortUnknownFormatFileIsRefusedWithTheVal13CopyNotABareEof() {
        // Both ends of the short-file hazard: below the sniffer's 16-byte
        // header, and past it but far short of the ISO probe offset 0x8001.
        // The sniff reads must tolerate the short file so the quick check —
        // not a bare EOFException from RandomAccessFile.readFully — answers.
        listOf(
            ByteArray(8) { 0x20 },
            ByteArray(4096) { (it % 251).toByte() },
        ).forEach { bytes ->
            // Fresh roots per lane run (the class's idiom): a second archive
            // import over a journal whose latest row is FAILED is a separate
            // beginStagingOrResume hazard, not this pin.
            val roots = DesktopStorageRoots(folder.newFolder("roots-${bytes.size}"))
                .also { it.ensureDirectories() }
            val archive = folder.newFile("unknown-${bytes.size}.bin").apply { writeBytes(bytes) }
            try {
                runBlocking { injectedImporter(roots).runArchive(archive) }
                fail("a short unrecognized file (${bytes.size} bytes) must be refused")
            } catch (expected: ImportRejected) {
                assertTrue(
                    "must carry the VAL-13 unsupported copy: ${expected.message}",
                    (expected.message ?: "").startsWith(ArchiveQuickCheck.VAL13_UNSUPPORTED),
                )
            }
            assertEquals("the refusal must not publish a pointer", null, ClientGenerationStore(roots).activeGeneration())
        }
    }

    @Test
    fun aMissingArchiveIsRefusedHonestly() {
        val roots = roots()
        try {
            runBlocking { injectedImporter(roots).runArchive(File(folder.root, "absent.zip")) }
            fail("a missing archive must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the refusal must name the unreadable archive: ${expected.message}",
                "VAL-13" in (expected.message ?: ""),
            )
        }
    }
}
