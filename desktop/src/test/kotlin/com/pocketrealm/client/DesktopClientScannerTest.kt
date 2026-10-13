package com.pocketrealm.client

import com.pocketrealm.importer.SyntheticClientArchives
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-52/68: the desktop folder scanner's identity gates — the desktop
 * pins of the VAL copy the Android JVM suite pins for SafClientScanner
 * (SafClientScannerMessageTest), driven over the SHARED ClientPeIdentity
 * parse with the synthetic-PE fixtures (no Blizzard bytes committed).
 */
class DesktopClientScannerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scanner = DesktopClientScanner()

    /** Materialize the synthetic vanilla-layout client (wrapper-free) into a
     * real directory tree the scanner can walk. */
    private fun materializeClient(root: File, build: Int = ClientFlavors.VANILLA.build): File {
        root.mkdirs()
        SyntheticClientArchives.clientEntries(wrapper = null, build = build).forEach { entry ->
            val target = File(root, entry.path.replace('\\', '/'))
            if (entry.directory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.writeBytes(entry.bytes)
            }
        }
        return root
    }

    @Test
    fun aSyntheticBuild5875ClientFolderIsSupported() {
        val scan = scanner.scan(materializeClient(folder.newFolder("client")))
        assertTrue("the fixture client must pass: ${scan.failures}", scan.supported)
        assertEquals(ClientFlavors.VANILLA.clientId, scan.clientId)
        assertEquals(ClientFlavors.VANILLA.versionResource, scan.version)
        assertEquals(ClientFlavors.VANILLA.build, scan.build)
        assertTrue("the accepted-build warning must ride along: ${scan.warnings}",
            scan.warnings.any { it.startsWith("VAL-03") })
    }

    @Test
    fun missingWowExeRefusesWithVal01() {
        val root = folder.newFolder("empty")
        File(root, "readme.txt").writeText("nothing here")
        val scan = scanner.scan(root)
        assertFalse(scan.supported)
        assertTrue(
            "must carry the plain VAL-01 copy: ${scan.failures}",
            scan.failures.any { it == "VAL-01: direct WoW.exe is absent" },
        )
    }

    @Test
    fun launcherOnlySelectionRefusesWithTheLauncherOnlyCopy() {
        val root = folder.newFolder("installer")
        File(root, "Launcher.exe").writeText("an installer, not the client")
        val scan = scanner.scan(root)
        assertFalse(scan.supported)
        assertTrue(
            "must carry the launcher-only VAL-01 copy: ${scan.failures}",
            scan.failures.any { it == DESKTOP_VAL01_LAUNCHER_ONLY_SELECTION },
        )
        assertTrue(
            "the pinned wording must keep the VAL-01 prefix and the launcher-only phrase",
            DESKTOP_VAL01_LAUNCHER_ONLY_SELECTION.startsWith("VAL-01: ") &&
                "launcher-only" in DESKTOP_VAL01_LAUNCHER_ONLY_SELECTION,
        )
    }

    @Test
    fun oversizedWowExeRefusesWithTheVal02SizeCopy() {
        // R6-7 (W4 code-red round 3): the charter's negative-matrix
        // "oversized -> VAL codes" leg — a WoW.exe whose size exceeds the
        // 32 MiB fast-scan cap must refuse with the exact VAL-02 size
        // copy, on the size check alone (the synthetic PE bytes are
        // valid; the refusal is the declared entry size).
        val root = materializeClient(folder.newFolder("client"))
        val exe = File(root, "WoW.exe")
        val valid = exe.readBytes()
        exe.writeBytes(valid + ByteArray(33 * 1024 * 1024))
        val scan = scanner.scan(root)
        assertFalse(scan.supported)
        assertTrue(
            "must carry the exact VAL-02 size copy: \${scan.failures}",
            scan.failures.any {
                it == "VAL-02: selected executable exceeds fast-scan limit"
            },
        )
    }

    fun genuineTbcTreeWithLocaleMpqsScansSupported() {
        // W4 (the locale-MPQ defect the tbc E2E surfaced): a genuine tbc
        // tree stages its locale MPQs in Data/enUS/ — the scanner's
        // bounded-depth walk + basename match must accept it, and a tree
        // missing a locale MPQ must still refuse naming the slashed key.
        val root = materializeClient(folder.newFolder("tbc-client"), build = 8606)
        val exe = File(root, "WoW.exe")
        exe.writeBytes(
            com.pocketrealm.importer.SyntheticClientArchives.syntheticPe(
                build = 8606, versionMajor = 2, versionMinor = 4, versionRev = 3,
            ),
        )
        val locale = File(root, "Data/enUS").apply { mkdirs() }
        val localeNames = listOf(
            "locale-enus", "speech-enus", "expansion-locale-enus",
            "expansion-speech-enus", "patch-enus", "patch-enus-2",
        )
        for (name in localeNames) {
            val source = File(root, "Data/$name.mpq")
            if (source.isFile) {
                source.renameTo(File(locale, "$name.MPQ"))
            } else {
                File(locale, "$name.MPQ").writeBytes(
                    com.pocketrealm.importer.SyntheticClientArchives.mpqStub("$name.MPQ"),
                )
            }
        }
        val scan = scanner.scan(root)
        assertTrue(
            "a genuine tbc tree with locale MPQs must scan supported: " +
                "\${scan.failures}",
            scan.supported,
        )
        assertEquals("tbc", scan.clientId)

        // the negative leg: drop one locale MPQ -> VAL-04 names it
        File(locale, "expansion-locale-enus.MPQ").delete()
        val refused = scanner.scan(root)
        assertFalse(refused.supported)
        assertTrue(
            "the refusal names the slashed registry key: \${refused.failures}",
            refused.failures.any { "enus/expansion-locale-enus.mpq" in it },
        )
        // R2-R4-2: a PATH-DOCTORED set (the required locale MPQs under
        // Data/decoy/, no Data/enUS/) must refuse - the genuine client
        // loads MPQs from Data\enUS\ only, so the accepted generation
        // could not boot its data.
        val decoy = File(root, "Data/decoy").apply { mkdirs() }
        for (name in localeNames) {
            val moved = File(locale, "$name.MPQ")
            if (moved.isFile) moved.renameTo(File(decoy, "$name.MPQ"))
        }
        val pathDoctored = scanner.scan(root)
        assertFalse(
            "a path-doctored MPQ set must refuse: ${pathDoctored.failures}",
            pathDoctored.supported,
        )
    }

    @Test
    fun nonPeWowExeRefusesWithVal02() {
        val root = materializeClient(folder.newFolder("client"))
        // Replace the synthetic PE with junk: the parse must refuse, naming VAL-02.
        File(root, "WoW.exe").writeBytes("definitely not a portable executable".toByteArray())
        val scan = scanner.scan(root)
        assertFalse(scan.supported)
        assertTrue(
            "must carry the PE-refusal copy: ${scan.failures}",
            scan.failures.any { it.contains("VAL-02") && it.contains("not a PE executable") },
        )
    }

    @Test
    fun wrongBuildClientRefusesWithTheVal03ExpectedDetectedCopy() {
        val root = materializeClient(folder.newFolder("client"), build = 6005)
        val scan = scanner.scan(root)
        assertFalse("a non-qualified build must refuse", scan.supported)
        val failure = scan.failures.firstOrNull { it.startsWith("VAL-03") }
        assertTrue("must carry the expected/detected copy: ${scan.failures}", failure != null)
        assertTrue(
            "must name the expected qualified version: $failure",
            ClientFlavors.VANILLA.versionResource in failure!!,
        )
        assertTrue("must name the detected version: $failure", "1.12.1.6005" in failure)
        assertTrue("must say no qualified flavor matched: $failure", "no qualified flavor matches" in failure)
    }

    @Test
    fun missingDataDirectoryRefusesWithVal04() {
        val root = materializeClient(folder.newFolder("client"))
        File(root, "Data").deleteRecursively()
        val scan = scanner.scan(root)
        assertFalse(scan.supported)
        assertTrue(
            "must carry the Data-refusal copy: ${scan.failures}",
            scan.failures.any { it == "VAL-04: Data directory is absent" },
        )
    }

    @Test
    fun missingBaseMpqSetRefusesWithVal04AndForeignClientsSkipTheMpqUnion() {
        // A qualified client missing MPQs names the missing set...
        val root = materializeClient(folder.newFolder("client"))
        File(root, "Data/base.MPQ").delete()
        val qualified = scanner.scan(root)
        assertTrue(
            "missing base MPQs must be named: ${qualified.failures}",
            qualified.failures.any { it.startsWith("VAL-04: missing base MPQ set") && "base.mpq" in it },
        )
        // ...while a foreign client (VAL-03 already failed) never compares
        // the qualified MPQ union (the Android scanner's noted invariant):
        // with Data present but its MPQs gone, only VAL-03 remains.
        val foreign = materializeClient(folder.newFolder("foreign"), build = 6005)
        File(foreign, "Data").listFiles().orEmpty().forEach { it.delete() }
        val foreignScan = scanner.scan(foreign)
        assertEquals(
            "the foreign client must fail VAL-03 only: ${foreignScan.failures}",
            1,
            foreignScan.failures.count { it.startsWith("VAL-0") },
        )
        assertTrue(
            "the single failure is the VAL-03 identity refusal: ${foreignScan.failures}",
            foreignScan.failures.single { it.startsWith("VAL-0") }.startsWith("VAL-03"),
        )
    }

    @Test
    fun customRootDllsWarnWithVal10() {
        val root = materializeClient(folder.newFolder("client"))
        File(root, "evil.dll").writeBytes(byteArrayOf(1))
        val scan = scanner.scan(root)
        assertTrue(scan.supported)
        assertTrue(
            "unrecognized root DLLs must warn: ${scan.warnings}",
            scan.warnings.any { it.startsWith("VAL-10") && "evil.dll" in it },
        )
        assertTrue("the standard DLLs never warn: ${scan.warnings}",
            scan.warnings.none { "fmod.dll" in it })
    }

    @Test
    fun anUnreadableDirectoryRefusesHonestly() {
        val scan = scanner.scan(File(folder.root, "does-not-exist"))
        assertFalse(scan.supported)
        assertTrue(
            "the unreadable selection must refuse: ${scan.failures}",
            scan.failures.single().contains("VAL-01"),
        )
    }
}
