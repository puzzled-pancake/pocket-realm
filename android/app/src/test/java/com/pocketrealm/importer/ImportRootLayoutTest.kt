package com.pocketrealm.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Census lanes item 17: the per-flavor [RootLayout] rows and the
 * union-classification wiring. The rows are derived from the measured staged
 * clients (census §0; remeasured for this pin):
 *
 *  - tbc `ExcaliburWoW Burning Crusade 2.4.3`: root files Wow.exe,
 *    WowError.exe, Repair.exe, Launcher.exe, realmlist.wtf, DivxDecoder.dll,
 *    ijl15.dll, Scan.dll, unicows.dll over Data/ Interface/ WTF/ — every
 *    entry already inside the vanilla layout.
 *  - wotlk `World.of.Warcraft.3.3.5a.Truewow`: the vanilla-style files plus
 *    Battle.net.dll, Blizzard Updater.exe, BNUpdate.exe,
 *    Microsoft.VC80.CRT.manifest, msvcr80.dll, Patch.html, Patch.txt over
 *    Data/ WTF/ (Cache/ and Logs/ are runtime debris, excluded like the
 *    staged vanilla client's Logs/ and WDB/).
 *
 * G3-10 add-only: the vanilla row golden below IS the pre-item-17 allowlist
 * byte-for-byte, so a later-era row can only ever ADD entries to the union —
 * retuning the classic lane in place fails this suite. Locale stays
 * infer-not-gate (VAL-05): `Data/<locale>/…` entries carry a separator and
 * are never root-gated by any layout.
 */
class ImportRootLayoutTest {

    private fun entries(vararg names: String): List<ImportExtractionPolicy.RawEntry> =
        names.map { ImportExtractionPolicy.RawEntry(it, false, 10) }

    private fun dirs(vararg names: String): List<ImportExtractionPolicy.RawEntry> =
        names.map { ImportExtractionPolicy.RawEntry(it, true, 0) }

    private fun excludedPaths(policy: ImportExtractionPolicy, entries: List<ImportExtractionPolicy.RawEntry>): Set<String> =
        policy.classify(entries).excluded.map { it.relativePath }.toSet()

    @Test
    fun `vanilla row is the pre-item-17 allowlist byte-for-byte`() {
        assertEquals(
            setOf(
                "wow.exe", "wowerror.exe", "repair.exe", "launcher.exe", "backgrounddownloader.exe",
                "realmlist.wtf", "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll",
                "scan.dll", "unicows.dll",
            ),
            RootLayout.VANILLA.rootFiles,
        )
        assertEquals(
            setOf(
                "data", "wtf", "interface", "fonts", "screenshots", "documentation", "errors",
                "movies", "music", "utilities",
            ),
            RootLayout.VANILLA.rootDirectories,
        )
    }

    @Test
    fun `declared rows are add-only over the vanilla layout`() {
        assertEquals(listOf("vanilla", "tbc", "wotlk"), RootLayout.DECLARED.map { it.flavorId })
        for (row in RootLayout.DECLARED) {
            assertTrue("row ${row.flavorId} dropped vanilla root files", RootLayout.VANILLA.rootFiles.all { it in row.rootFiles })
            assertTrue("row ${row.flavorId} dropped vanilla root dirs", RootLayout.VANILLA.rootDirectories.all { it in row.rootDirectories })
        }
        // The measured later-era additions, exactly (tbc measured inside the
        // vanilla layout — no additions; wotlk adds the seven measured files).
        assertEquals(emptySet<String>(), RootLayout.TBC.rootFiles - RootLayout.VANILLA.rootFiles)
        assertEquals(
            setOf(
                "battle.net.dll", "blizzard updater.exe", "bnupdate.exe",
                "microsoft.vc80.crt.manifest", "msvcr80.dll", "patch.html", "patch.txt",
            ),
            RootLayout.WOTLK.rootFiles - RootLayout.VANILLA.rootFiles,
        )
        assertEquals(emptySet<String>(), RootLayout.WOTLK.rootDirectories - RootLayout.VANILLA.rootDirectories)
    }

    @Test
    fun `default policy admits the measured wotlk root and still excludes junk`() {
        val policy = ImportExtractionPolicy()
        val excluded = excludedPaths(
            policy,
            entries(
                "WoW.exe", "WowError.exe", "Repair.exe", "Scan.dll", "dbghelp.dll", "DivxDecoder.dll",
                "ijl15.dll", "unicows.dll", "BackgroundDownloader.exe",
                "Battle.net.dll", "Blizzard Updater.exe", "BNUpdate.exe",
                "Microsoft.VC80.CRT.manifest", "msvcr80.dll", "Patch.html", "Patch.txt",
                "Data/common.MPQ", "Data/enUS/locale-enUS.MPQ", "launch.bat", "dxvk.conf",
            ) + dirs("Data", "WTF", "Cache", "Logs", "dxvk"),
        )
        // None of the measured wotlk root files may be excluded.
        for (name in listOf(
            "WoW.exe", "Battle.net.dll", "Blizzard Updater.exe", "BNUpdate.exe",
            "Microsoft.VC80.CRT.manifest", "msvcr80.dll", "Patch.html", "Patch.txt",
            "Data/enUS/locale-enUS.MPQ",
        )) {
            assertTrue("measured wotlk root entry '$name' was excluded", name !in excluded)
        }
        // Junk roots keep today's exclude-with-reason posture.
        assertEquals(setOf("launch.bat", "dxvk.conf", "Cache", "Logs", "dxvk"), excluded)
    }

    @Test
    fun `a vanilla-only policy excludes the later-era wotlk root files`() {
        val policy = ImportExtractionPolicy(rootLayouts = listOf(RootLayout.VANILLA))
        val excluded = excludedPaths(
            policy,
            entries("WoW.exe", "Battle.net.dll", "BNUpdate.exe", "Patch.html") + dirs("Data", "WTF"),
        )
        assertEquals(setOf("Battle.net.dll", "BNUpdate.exe", "Patch.html"), excluded)
    }

    @Test
    fun `the measured tbc root is admitted by the vanilla layout alone`() {
        val policy = ImportExtractionPolicy(rootLayouts = listOf(RootLayout.VANILLA))
        val excluded = excludedPaths(
            policy,
            entries(
                "Wow.exe", "WowError.exe", "Repair.exe", "Launcher.exe", "realmlist.wtf",
                "DivxDecoder.dll", "ijl15.dll", "Scan.dll", "unicows.dll",
                "Data/common.MPQ", "Data/patch-2.MPQ", "Data/enUS/expansion-locale-enUS.MPQ",
            ) + dirs("Data", "Interface", "WTF", "Cache", "Logs"),
        )
        // The staged TBC root measures inside the vanilla layout; only the
        // runtime debris dirs are excluded-with-reason.
        assertEquals(setOf("Cache", "Logs"), excluded)
    }

    @Test
    fun `locale subfolders are never root-gated by any single layout`() {
        // VAL-05 infer-not-gate: Data\<locale>\ entries carry a separator, so
        // every per-flavor layout admits them — the locale is inferred later
        // by the identity scan, not gated here.
        for (layout in RootLayout.DECLARED) {
            val policy = ImportExtractionPolicy(rootLayouts = listOf(layout))
            val excluded = excludedPaths(
                policy,
                entries("WoW.exe", "Data/enUS/locale-enUS.MPQ", "Data/patch-enUS-3.MPQ") + dirs("Data"),
            )
            assertEquals("layout ${layout.flavorId} gated locale subfolders", emptySet<String>(), excluded)
        }
    }
}
