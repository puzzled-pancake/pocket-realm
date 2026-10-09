package com.pocketrealm.client

import com.pocketrealm.importer.ArchiveClientScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R2 adversarial breaker (android-core round 1): doctored MPQ sets and
 * wrong-flavor client identities against the import scanners.
 *
 * Attack class: a client tree whose Data/ set is not the flavor's registered
 * set — missing members, corrupted members, EXTRA members — and a PE whose
 * version resource names a flavor the lane never qualified. Every wrong
 * input must land in an actionable VAL failure (port plan invariant 2), and
 * the wrong-flavor identity must be refused BEFORE any MPQ-set comparison
 * runs (the scanner's own stated rule).
 */
class R2DoctoredMpqScannerTest {

    // ------------------------------------------------------------------
    // Folder/archive lane: SafClientScanner.scanAccess over a synthetic tree
    // ------------------------------------------------------------------

    @Test
    fun `the genuine vanilla set is accepted`() {
        val access = tree(
            exe = vanillaExe(),
            mpqs = VANILLA_BASE_MPQS,
        )
        val result = SafClientScanner.scanAccess(access)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertTrue(result.supported)
        assertEquals("wow-1.12.1-5875", result.clientId)
    }

    @Test
    fun `a tbc identity is refused before any mpq set comparison`() {
        // Full TBC set present (including the enus/ members the vanilla
        // union knows nothing about): the identity gate must refuse on VAL-03
        // and the MPQ union must not be compared against a foreign flavor.
        val access = tree(
            exe = vanillaExe("2.4.3.8606"),
            mpqs = VANILLA_BASE_MPQS + TBC_LOCALE_MPQS,
        )
        val result = SafClientScanner.scanAccess(access)
        assertFalse(result.supported)
        assertTrue(result.failures.any { it.startsWith("VAL-03") })
        assertTrue(
            "a foreign identity must never be MPQ-compared",
            result.failures.none { it.startsWith("VAL-04") },
        )
        assertNull(result.clientId)
    }

    @Test
    fun `a missing base mpq is refused naming the file`() {
        val access = tree(exe = vanillaExe(), mpqs = VANILLA_BASE_MPQS - "terrain.mpq")
        val result = SafClientScanner.scanAccess(access)
        assertFalse(result.supported)
        val missing = result.failures.single { it.startsWith("VAL-04: missing base MPQ set") }
        assertTrue(missing.contains("terrain.mpq"))
    }

    @Test
    fun `a corrupted mpq header is refused naming the file`() {
        val access = tree(exe = vanillaExe(), mpqs = VANILLA_BASE_MPQS) {
            put("Data/texture.mpq", "NOTANMPQ".toByteArray())
        }
        val result = SafClientScanner.scanAccess(access)
        assertFalse(result.supported)
        assertTrue(
            result.failures.any { it.startsWith("VAL-04: invalid MPQ header") && it.contains("texture.mpq") },
        )
    }

    @Test
    fun `an unreadable mpq fails closed as an invalid header`() {
        val access = tree(exe = vanillaExe(), mpqs = VANILLA_BASE_MPQS, unreadable = "Data/sound.mpq")
        val result = SafClientScanner.scanAccess(access)
        assertFalse(result.supported)
        assertTrue(result.failures.any { it.startsWith("VAL-04: invalid MPQ header") })
    }

    @Test
    fun `an extra unknown patch mpq is accepted today documented gap`() {
        // The doctored-extra probe: a vanilla-identity tree carrying an
        // UNKNOWN patch-N member loads it silently in the client (patch MPQs
        // are applied by the engine in name order). The scanner checks the
        // MISSING direction and header validity only — the extra member is
        // not an exact-set rejection today. Port-plan correction C2 records
        // exact-set semantics for the S5 tbc/wotlk registration; this test
        // pins the CURRENT vanilla posture so any tightening flips it
        // deliberately instead of silently.
        val access = tree(exe = vanillaExe(), mpqs = VANILLA_BASE_MPQS + "patch-9.mpq")
        val result = SafClientScanner.scanAccess(access)
        assertTrue(result.supported)
        assertTrue(result.failures.isEmpty())
    }

    // ------------------------------------------------------------------
    // Post-extraction lane: ArchiveClientScanner.verifyExtracted
    // ------------------------------------------------------------------

    @Test
    fun `verifyExtracted refuses a wrong-flavor exe and skips the mpq union`() {
        val root = extractedTree(exeVersion = "3.3.5.12340", mpqNames = VANILLA_BASE_MPQS)
        try {
            val result = ArchiveClientScanner().verifyExtracted(root)
            assertFalse(result.supported)
            assertTrue(result.failures.any { it.startsWith("VAL-03") })
            assertTrue(result.failures.none { it.startsWith("VAL-04") })
            assertNull(result.clientId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `verifyExtracted refuses a missing base mpq`() {
        val root = extractedTree(exeVersion = "1.12.1.5875", mpqNames = VANILLA_BASE_MPQS - "misc.mpq")
        try {
            val result = ArchiveClientScanner().verifyExtracted(root)
            assertFalse(result.supported)
            assertTrue(result.failures.any { it.startsWith("VAL-04: missing base MPQ set") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `verifyExtracted refuses an absent Data directory`() {
        val root = extractedTree(exeVersion = "1.12.1.5875", mpqNames = VANILLA_BASE_MPQS, noDataDir = true)
        try {
            val result = ArchiveClientScanner().verifyExtracted(root)
            assertFalse(result.supported)
            assertTrue(result.failures.any { it == "VAL-04: Data directory is absent" })
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun assertNull(value: String?) {
        assertTrue("expected null clientId on a refused scan", value == null)
    }

    private fun mpqHeader(): ByteArray = byteArrayOf(0x4d, 0x50, 0x51, 0x1a) + ByteArray(4)

    /** A minimal PE whose VS_FIXEDFILEINFO stamp carries [version]. */
    private fun vanillaExe(version: String = "1.12.1.5875"): ByteArray {
        val buf = ByteArray(1024)
        buf[0] = 'M'.code.toByte()
        buf[1] = 'Z'.code.toByte()
        val pe = 0x80
        buf[0x3c] = pe.toByte()
        "PE\u0000\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(buf, pe)
        buf[pe + 4] = 0x4c // IMAGE_FILE_MACHINE_I386 LE
        buf[pe + 5] = 0x01
        buf[pe + 24] = 0x0b // PE32 optional magic LE
        buf[pe + 25] = 0x01
        val parts = version.split('.').map { it.toInt() }
        val ms = (parts[0] shl 16) or parts[1]
        val ls = (parts[2] shl 16) or parts[3]
        fun put32(offset: Int, value: Int) {
            buf[offset] = value.toByte()
            buf[offset + 1] = (value ushr 8).toByte()
            buf[offset + 2] = (value ushr 16).toByte()
            buf[offset + 3] = (value ushr 24).toByte()
        }
        put32(0x200, 0xFEEF04BDL.toInt()) // VS_FIXEDFILEINFO signature
        put32(0x208, ms)
        put32(0x20c, ls)
        return buf
    }

    private fun tree(
        exe: ByteArray,
        mpqs: Set<String>,
        unreadable: String? = null,
        overrides: MutableMap<String, ByteArray>.() -> Unit = {},
    ): SafClientScanner.Access {
        val files = mutableMapOf<String, ByteArray>("WoW.exe" to exe)
        mpqs.forEach { files["Data/$it"] = mpqHeader() }
        files.overrides()
        return FakeAccess(files, unreadable)
    }

    private fun extractedTree(exeVersion: String, mpqNames: Set<String>, noDataDir: Boolean = false): File {
        val root = Files.createTempDirectory("r2-mpq-extracted").toFile()
        File(root, "WoW.exe").writeBytes(vanillaExe(exeVersion))
        if (!noDataDir) {
            val data = File(root, "Data")
            data.mkdirs()
            mpqNames.forEach { File(data, it).writeBytes(mpqHeader()) }
        }
        return root
    }

    private class FakeAccess(
        private val files: Map<String, ByteArray>,
        private val unreadable: String?,
    ) : SafClientScanner.Access {
        private val dirs = mutableSetOf("Data")

        override val rootId: String = ""

        override fun children(parentId: String): List<SafClientScanner.Entry> {
            val prefix = if (parentId.isEmpty()) "" else "$parentId/"
            val dirEntries = dirs
                .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map { SafClientScanner.Entry(it, it.substringAfterLast('/'), true, 0) }
            val fileEntries = files
                .filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
                .map {
                    SafClientScanner.Entry(
                        it.key,
                        it.key.substringAfterLast('/'),
                        false,
                        it.value.size.toLong(),
                    )
                }
            return dirEntries + fileEntries
        }

        override fun read(documentId: String, maxBytes: Int, rejectTruncation: Boolean): ByteArray {
            if (documentId == unreadable) throw java.io.IOException("simulated unreadable document")
            return files.getValue(documentId).copyOf(minOf(files.getValue(documentId).size, maxBytes))
        }
    }

    private companion object {
        val VANILLA_BASE_MPQS: Set<String> = ClientFlavors.VANILLA.requiredMpqs
        val TBC_LOCALE_MPQS: Set<String> =
            setOf("enus/locale-enus.mpq", "enus/speech-enus.mpq", "enus/expansion-locale-enus.mpq")
    }
}
