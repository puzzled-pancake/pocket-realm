package com.pocketrealm.importer

import com.pocketrealm.client.SafClientScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        assertTrue((verdict as ArchiveQuickCheck.Verdict.Reject).failure.startsWith("VAL-11:"))
    }

    @Test
    fun blizzardInstallerPatternIsRejectedWithVal12() {
        val names = listOf(
            "Wowinstall classic/setup.exe",
            "Wowinstall classic/setup-1.bin",
            "Wowinstall classic/setup-2.bin",
        )
        val verdict = ArchiveQuickCheck.evaluate(ArchiveFormat.RAR5, names)
        assertTrue((verdict as ArchiveQuickCheck.Verdict.Reject).failure.startsWith("VAL-12:"))
    }

    @Test
    fun installerDetectionSurvivesBackslashesAndRootLevel() {
        assertTrue(ArchiveQuickCheck.looksLikeInstaller(listOf("setup.exe", "setup-1a2b.bin")))
        assertTrue(ArchiveQuickCheck.looksLikeInstaller(listOf("Wowinstall classic\\setup.exe", "Wowinstall classic\\setup-2.bin")))
        assertFalse(ArchiveQuickCheck.looksLikeInstaller(listOf("Client/WoW.exe", "Client/Data/base.MPQ")))
    }

    @Test
    fun archiveWithoutAnyWowExeFailsFastWithVal01() {
        val verdict = ArchiveQuickCheck.evaluate(
            ArchiveFormat.ZIP,
            listOf("Client/Launcher.exe", "Client/Patch.dat"),
        )
        assertTrue((verdict as ArchiveQuickCheck.Verdict.Reject).failure.startsWith("VAL-01:"))
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
        assertTrue((verdict as ArchiveQuickCheck.Verdict.Reject).failure.startsWith("VAL-13:"))
    }
}

class ImportExtractionPolicyTest {
    private val policy = ImportExtractionPolicy()

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
    fun trailingDotOrSpaceComponentIsRejected() {
        val error = org.junit.Assert.assertThrows(ImportRejected::class.java) {
            policy.classify(listOf(ImportExtractionPolicy.RawEntry("WoW.exe ", false, 1)))
        }
        assertTrue(error.message!!.startsWith("VAL-07:"))
    }

    @Test
    fun dirFileTypeConflictIsRejectedAsVal06() {
        org.junit.Assert.assertThrows(ImportRejected::class.java) {
            policy.classify(
                listOf(
                    ImportExtractionPolicy.RawEntry("Data", true, 0),
                    ImportExtractionPolicy.RawEntry("data", false, 5),
                ),
            )
        }
    }

    @Test
    fun caseFoldCollisionIncludingDirectoriesIsRejectedAsVal06() {
        org.junit.Assert.assertThrows(ImportRejected::class.java) {
            policy.classify(
                listOf(
                    ImportExtractionPolicy.RawEntry("WTF/Config.wtf", false, 5),
                    ImportExtractionPolicy.RawEntry("wtf/config.wtf", false, 5),
                ),
            )
        }
    }
}

class ArchiveClientScannerTest {
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
        val result = scanZip(SyntheticClientArchives.clientEntries(wrapper = "Client", build = 6005))
        assertFalse(result.scan.supported)
        assertTrue(result.scan.failures.any { it.startsWith("VAL-03:") })
    }

    @Test
    fun missingMpqIsReportedWithVal04() {
        val entries = SyntheticClientArchives.clientEntries(wrapper = "Client")
            .filterNot { it.path.endsWith("base.MPQ") }
        val result = scanZip(entries)
        assertFalse(result.scan.supported)
        assertTrue(result.scan.failures.any { it.startsWith("VAL-04:") })
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
}
