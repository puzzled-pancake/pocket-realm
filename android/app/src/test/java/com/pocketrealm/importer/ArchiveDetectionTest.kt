package com.pocketrealm.importer

import com.pocketrealm.client.SafClientScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

class ArchiveFormatSnifferTest {
    @Test
    fun sniffsEverySupportedFormatByMagic() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormatSniffer.sniff(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormatSniffer.sniff(byteArrayOf(0x50, 0x4b, 0x05, 0x06)))
        assertEquals(
            ArchiveFormat.SEVEN_ZIP,
            ArchiveFormatSniffer.sniff(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)),
        )
        assertEquals(
            ArchiveFormat.RAR4,
            ArchiveFormatSniffer.sniff(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)),
        )
        assertEquals(
            ArchiveFormat.RAR5,
            ArchiveFormatSniffer.sniff(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)),
        )
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveFormatSniffer.sniff(ByteArray(16)))
    }

    @Test
    fun isoProbeMatchesCd001() {
        val probe = SyntheticClientArchives.isoStub().copyOfRange(0x8001, 0x8001 + 5)
        assertTrue(ArchiveFormatSniffer.isIso(probe))
        assertFalse(ArchiveFormatSniffer.isIso(ByteArray(5)))
    }
}

class ArchiveQuickCheckTest {
    @Test
    fun isoIsRejectedWithTheVal11InstallerExplanation() {
        val verdict = ArchiveQuickCheck.evaluate(ArchiveFormat.ISO, null)
        assertTrue(verdict is ArchiveQuickCheck.Verdict.Reject)
        assertTrue((verdict as ArchiveQuickCheck.Verdict.Reject).failure.startsWith("Disc images are not supported"))
    }

    @Test
    fun blizzardInstallerPatternRoutesToTheInstallerLane() {
        val names = listOf(
            "Wowinstall classic/setup.exe",
            "Wowinstall classic/setup-1.bin",
            "Wowinstall classic/setup-2.bin",
        )
        val verdict = ArchiveQuickCheck.evaluate(ArchiveFormat.RAR5, names)
        assertTrue(verdict is ArchiveQuickCheck.Verdict.InstallerPayload)
        assertEquals(ArchiveFormat.RAR5, (verdict as ArchiveQuickCheck.Verdict.InstallerPayload).format)
    }

    @Test
    fun installerDetectionSurvivesBackslashesAndRootLevel() {
        assertTrue(ArchiveQuickCheck.looksLikeInstaller(listOf("setup.exe", "setup-1a2b.bin")))
        assertTrue(ArchiveQuickCheck.looksLikeInstaller(listOf("Wowinstall classic\\setup.exe", "Wowinstall classic\\setup-2.bin")))
        assertFalse(ArchiveQuickCheck.looksLikeInstaller(listOf("Client/WoW.exe", "Client/Data/base.MPQ")))
    }

    @Test
    fun clientArchiveWithABundledInstallerFolderStaysOnTheClientLane() {
        // A real client always wins: the normal lane imports it and excludes
        // the bundled installer files instead of misrouting the archive.
        val names = listOf(
            "WoW.exe",
            "Data/base.MPQ",
            "Extras/Installer/setup.exe",
            "Extras/Installer/setup-1.bin",
        )
        val verdict = ArchiveQuickCheck.evaluate(ArchiveFormat.ZIP, names)
        assertTrue(verdict is ArchiveQuickCheck.Verdict.Accept)
    }

    @Test
    fun archiveWithoutAnyWowExeFailsFastWithVal01() {
        val verdict = ArchiveQuickCheck.evaluate(
            ArchiveFormat.ZIP,
            listOf("Client/Launcher.exe", "Client/Patch.dat"),
        )
        assertTrue((verdict as ArchiveQuickCheck.Verdict.Reject).failure.startsWith("WoW.exe was not found anywhere in the archive"))
    }

    @Test
    fun rarWithoutNameEnumerationIsAcceptedForPostStagingDetection() {
        val verdict = ArchiveQuickCheck.evaluate(ArchiveFormat.RAR4, null)
        assertTrue(verdict is ArchiveQuickCheck.Verdict.Accept)
        assertEquals(ArchiveFormat.RAR4, (verdict as ArchiveQuickCheck.Verdict.Accept).format)
    }

    @Test
    fun unknownFormatIsRejectedWithVal13() {
        val verdict = ArchiveQuickCheck.evaluate(ArchiveFormat.UNKNOWN, null)
        assertTrue((verdict as ArchiveQuickCheck.Verdict.Reject).failure.startsWith("Unsupported or unrecognized archive"))
    }
}

class ImportExtractionPolicyTest {
    /** flip fixture: the sole-qualified degrade is retired; the
     * extraction-policy lanes pin the VANILLA lane (holder select). */
    @Before
    fun pinTheVanillaLaneForTheFlipExtraction() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlipExtraction() {
        ActiveFlavor.reset()
    }

 // Flip: getter — the ImportLimits default resolves the active lane
    // and fields initialize before the @Before pin.
    private val policy: ImportExtractionPolicy get() = ImportExtractionPolicy()

    private fun clientEntries(vararg extra: String): List<ImportExtractionPolicy.RawEntry> {
        val base = mutableListOf(
            ImportExtractionPolicy.RawEntry("WoW.exe", false, 4775986),
            ImportExtractionPolicy.RawEntry("realmlist.wtf", false, 24),
            ImportExtractionPolicy.RawEntry("Data", true, 0),
            ImportExtractionPolicy.RawEntry("Data/base.MPQ", false, 11),
            ImportExtractionPolicy.RawEntry("Data/patch-3.mpq", false, 403),
        )
        extra.forEach { base += ImportExtractionPolicy.RawEntry(it, false, 10) }
        return base
    }

    @Test
    fun vanillaLayoutCopiesEverythingAndKeepsExtraPatchMpqs() {
        val classified = policy.classify(clientEntries())
        assertEquals(0, classified.excluded.size)
        assertTrue(classified.entries.any { it.relativePath == "Data/patch-3.mpq" })
        assertEquals(4, classified.fileCount)
    }

    @Test
    fun hackFolderAndLauncherScriptsAreExcludedWithReasons() {
        val classified = policy.classify(
            clientEntries("!1.8 Hack/wow.exe", "!1.8 Hack/patch-4.MPQ", "launch.bat", "detect-display.ps1", "dxvk.conf")
                + ImportExtractionPolicy.RawEntry("!1.8 Hack", true, 0)
                + ImportExtractionPolicy.RawEntry("dxvk", true, 0)
                + ImportExtractionPolicy.RawEntry("dxvk/d3d9.dll", false, 10),
        )
        val excluded = classified.excluded.map { it.relativePath }
        assertTrue("!1.8 Hack" in excluded)
        assertTrue("!1.8 Hack/wow.exe" in excluded)
        assertTrue("launch.bat" in excluded)
        assertTrue("detect-display.ps1" in excluded)
        assertTrue("dxvk" in excluded)
        assertTrue("dxvk/d3d9.dll" in excluded)
        assertTrue(classified.entries.none { it.relativePath.startsWith("!1.8 Hack") })
    }

    @Test
    fun backslashEntriesNormalizeBeforeTheAllowList() {
        val classified = policy.classify(listOf(ImportExtractionPolicy.RawEntry("WoW.exe", false, 4775986)) +
            SyntheticClientArchives.hackFolderEntries.map { ImportExtractionPolicy.RawEntry(it.path, it.directory, it.bytes.size.toLong()) })
        assertTrue(classified.excluded.any { it.relativePath == "!1.8 Hack/wow.exe" })
    }

    @Test
    fun trailingDotOrSpaceComponentIsSkippedNotCopied() {
        // Such a name never exists on Windows (NTFS aliases "x." onto "x"):
        // the entry is skipped with its reason instead of failing the import.
        val result = policy.classify(listOf(ImportExtractionPolicy.RawEntry("WoW.exe ", false, 1)))
        assertTrue(result.entries.isEmpty())
        assertTrue(result.excluded.single().reason.contains("trailing dot or space"))
    }

    @Test
    fun dirFileTypeConflictKeepsTheFirstAndSkipsTheDuplicate() {
        val result = policy.classify(
            listOf(
                ImportExtractionPolicy.RawEntry("Data", true, 0),
                ImportExtractionPolicy.RawEntry("data", false, 5),
            ),
        )
        assertTrue(result.entries.single().directory)
        assertTrue(result.excluded.single().reason.contains("differing only in case"))
    }

    @Test
    fun caseFoldCollisionKeepsTheFirstSpelling() {
        val result = policy.classify(
            listOf(
                ImportExtractionPolicy.RawEntry("WTF/Config.wtf", false, 5),
                ImportExtractionPolicy.RawEntry("wtf/config.wtf", false, 5),
            ),
        )
        assertTrue(result.entries.single().relativePath == "WTF/Config.wtf")
        assertTrue(result.excluded.single().relativePath == "wtf/config.wtf")
    }
}

class ArchiveClientScannerTest {
    /** flip fixture: the sole-qualified degrade is retired;
     * the scanner lanes pin the VANILLA lane (holder select). */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    @get:Rule
    val folder = TemporaryFolder()

    private fun scanZip(entries: List<SyntheticClientArchives.Entry>, extras: List<SyntheticClientArchives.Entry> = emptyList()): ArchiveClientScanner.Result {
        val file = SyntheticClientArchives.zip(
            SyntheticClientArchives.uniqueFile(folder.newFolder(), "client"),
            entries + extras,
        )
        val zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(file).get()
        val raw = zip.entries.asSequence().map { entry ->
            ArchiveClientScanner.RawEntry(entry.name, entry.isDirectory, entry.size)
        }.toList()
        val reader = ArchiveClientScanner.EntryReader { name, maxBytes ->
            val entry = zip.getEntry(name)
            zip.getInputStream(entry!!).use { input -> input.readNBytes(maxBytes) }
        }
        try {
            return ArchiveClientScanner().scan(raw, reader)
        } finally {
            zip.close()
        }
    }

    @Test
    fun validWrappedZipDetects5875WithVariantAndRealmTarget() {
        val result = scanZip(
            SyntheticClientArchives.clientEntries(
                wrapper = "WoW_Classic_ENG_1.12.1",
                configWtf = "SET locale \"enUS\"\nSET readTOS \"1\"",
            ),
        )
        assertTrue(result.scan.supported)
        assertEquals("1.12.1.5875", result.scan.version)
        assertEquals("WoW_Classic_ENG_1.12.1", result.variant)
        assertEquals("enUS", result.locale)
        assertEquals("us.logon.worldofwarcraft.com", result.realmTarget)
    }

    @Test
    fun engStyleContaminationIsExcludedAndDoesNotBreakWoWExeResolution() {
        val result = scanZip(
            SyntheticClientArchives.clientEntries(wrapper = "WoW_Classic_ENG_1.12.1"),
            extras = SyntheticClientArchives.hackFolderEntries,
        )
        assertTrue(result.scan.supported)
        assertTrue(result.excluded.any { it.relativePath == "!1.8 Hack" || it.relativePath == "!1.8 Hack/wow.exe" })
    }

    @Test
    fun wrongBuildIsReportedUnsupportedWithVal03() {
        val result = scanZip(SyntheticClientArchives.clientEntries(wrapper = "Client", build = 4222))
        assertFalse(result.scan.supported)
        assertTrue(result.scan.failures.any { it.startsWith("Unsupported game version") })
    }

    @Test
    fun aSiblingBuildTheRealmAcceptsImportsWithAWarning() {
        val result = scanZip(SyntheticClientArchives.clientEntries(wrapper = "Client", build = 6005))
        assertTrue(result.scan.failures.toString(), result.scan.supported)
        assertTrue(result.scan.warnings.any { "6005 accepted" in it })
    }

    @Test
    fun missingMpqIsReportedWithVal04() {
        val entries = SyntheticClientArchives.clientEntries(wrapper = "Client")
            .filterNot { it.path.endsWith("base.MPQ") }
        val result = scanZip(entries)
        assertFalse(result.scan.supported)
        assertTrue(result.scan.failures.any { it.startsWith("Missing game data files") })
    }

    @Test
    fun launcherOnlyArchiveIsRejectedWithVal01() {
        org.junit.Assert.assertThrows(ImportRejected::class.java) {
            scanZip(listOf(SyntheticClientArchives.Entry("Client/Launcher.exe", ByteArray(16))))
        }
    }

    @Test
    fun backslashWrappedZipMatchesTheRealStonetavernLayout() {
        val result = scanZip(
            SyntheticClientArchives.clientEntries(wrapper = "Stonetavern-Classic-1.12.1", backslash = true) +
                listOf(
                    SyntheticClientArchives.Entry("Stonetavern-Classic-1.12.1\\launch.bat", ByteArray(4)),
                    SyntheticClientArchives.Entry("Stonetavern-Classic-1.12.1\\AGENTS.md", ByteArray(4)),
                ),
        )
        assertTrue(result.scan.supported)
        assertEquals("Stonetavern-Classic-1.12.1", result.variant)
        assertTrue(result.excluded.any { it.relativePath == "launch.bat" })
    }

    @Test
    fun scannerResultReusesFolderLaneWarningForCustomRootDlls() {
        val result = scanZip(
            SyntheticClientArchives.clientEntries(wrapper = "Enhanced"),
            extras = listOf(
                SyntheticClientArchives.Entry("Enhanced/nampower.dll", ByteArray(4)),
                SyntheticClientArchives.Entry("Enhanced/UnitXP_SP3.dll", ByteArray(4)),
            ),
        )
        // The policy drops the non-standard root DLLs before the scanner sees
        // them, so the folder lane's VAL-10 warning does not double-report.
        assertTrue(result.scan.supported)
        assertTrue(result.excluded.any { it.relativePath == "nampower.dll" })
    }

    /** The live RP6 owner-session signature: a real-world zip that stores
     * NO explicit directory entries (every file carries its full path) and
     * names the executable "Wow.exe". The Data directory is implied by the
     * file paths; root-finding must accept it. */
    @Test
    fun archiveWithoutExplicitDirectoryEntriesStillFindsTheClientRoot() {
        val result = scanZip(
            SyntheticClientArchives.clientEntries(wrapper = null).map { entry ->
                if (entry.path == "WoW.exe") entry.copy(path = "Wow.exe") else entry
            }.filterNot { it.directory },
        )
        assertTrue(result.scan.supported)
        assertEquals(null, result.variant)
    }

    /** The W7 vanilla re-pack signature: every entry carries a leading
     * "./" segment. Detection comparisons run through the shared cleaner,
     * and the reader resolves cleaned documentIds back to the raw entry. */
    @Test
    fun dotSlashArchiveNamesDetectRebaseAndRead() {
        val entries = SyntheticClientArchives.clientEntries(wrapper = null)
            .map { entry -> entry.copy(path = "./" + entry.path) }
        val file = SyntheticClientArchives.zip(
            SyntheticClientArchives.uniqueFile(folder.newFolder(), "client"),
            entries,
        )
        val zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(file).get()
        val raw = zip.entries.asSequence().map { entry ->
            ArchiveClientScanner.RawEntry(entry.name, entry.isDirectory, entry.size)
        }.toList()
        val reader = ArchiveClientScanner.EntryReader { name, maxBytes ->
            val entry = zip.entries.asSequence()
                .firstOrNull { cleanArchiveName(it.name) == cleanArchiveName(name) }
            zip.getInputStream(entry!!).use { input -> input.readNBytes(maxBytes) }
        }
        val result = try {
            ArchiveClientScanner().scan(raw, reader)
        } finally {
            zip.close()
        }
        assertTrue(result.scan.supported)
        assertEquals(null, result.variant)
        assertTrue(result.classified.entries.isNotEmpty())
        assertTrue(result.classified.entries.none { it.relativePath.startsWith("./") })
    }
}

/** The copy lane over a real-world-shaped zip: keys keep the container's
 * raw "./" names, and the source resolves them through the shared cleaner
 * (the W7 vanilla re-pack the owner's RP6 session must import). */
class ArchiveSourceCleanLookupTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun pinTheVanillaLane() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLane() {
        ActiveFlavor.reset()
    }

    @Test
    fun dotSlashZipSourceInventoriesAndStreamsByCleanNames() {
        val entries = SyntheticClientArchives.clientEntries(wrapper = null)
            .map { record -> record.copy(path = "./" + record.path) }
        val file = SyntheticClientArchives.zip(
            SyntheticClientArchives.uniqueFile(folder.newFolder(), "client"),
            entries,
        )
        val zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(file).get()
        val raw = zip.entries.asSequence().map { entry ->
            ArchiveClientScanner.RawEntry(entry.name, entry.isDirectory, entry.size)
        }.toList()
        val located = ArchiveClientScanner().locate(raw)
        assertTrue(located.classified.entries.isNotEmpty())
        zip.close()

        ZipArchiveSource(file, located.classified).use { source ->
            val inventory = source.inventory()
            assertEquals(located.classified.fileCount, inventory.fileCount)
            val mpq = inventory.entries.first { it.relativePath.endsWith(".MPQ") }
            source.open(mpq).use { input ->
                val header = input.readNBytes(4)
                assertTrue(header.contentEquals(byteArrayOf(0x4d, 0x50, 0x51, 0x1a)))
            }
        }
    }
}
