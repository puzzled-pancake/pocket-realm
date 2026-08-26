package com.pocketrealm.importer

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 0 characterization of the synthetic archive fixture factory: every
 * builder must round-trip through commons-compress exactly as the detection
 * and extraction code will read real archives. Also pins the committed
 * libarchive-corpus RAR fixtures (BSD-2, no Blizzard bytes).
 */
class SyntheticClientArchivesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun zipOf(entries: List<SyntheticClientArchives.Entry>, zip64: Boolean = false): File {
        val target = SyntheticClientArchives.uniqueFile(folder.newFolder(), "client", ".zip")
        return SyntheticClientArchives.zip(target, entries, zip64 = zip64)
    }

    @Test
    fun syntheticPeCarriesThe5875IdentityMarkers() {
        val bytes = SyntheticClientArchives.syntheticPe(5875)
        assertEquals('M'.code.toByte(), bytes[0])
        assertEquals('Z'.code.toByte(), bytes[1])
        // PE\0\0 at e_lfanew, I386 machine, PE32 optional magic, 1.12.1.5875.
        assertTrue(bytes.copyOfRange(0x100, 0x104).contentEquals(byteArrayOf(0x50, 0x45, 0, 0)))
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x14c, buffer.getShort(0x104).toInt() and 0xffff)
        assertEquals(0x10b, buffer.getShort(0x118).toInt() and 0xffff)
        assertEquals(0xfeef04bdL, buffer.getInt(0x300).toLong() and 0xffffffffL)
        assertEquals((1 shl 16) or 12, buffer.getInt(0x308))
        assertEquals((1 shl 16) or 5875, buffer.getInt(0x30c))
    }

    @Test
    fun validClientZipRoundTripsWithWrapperRebasedPaths() {
        val file = zipOf(SyntheticClientArchives.clientEntries())
        ZipFile.builder().setFile(file).get().use { zip ->
            val names = zip.entries.asSequence().map { it.name }.toList()
            assertTrue(names.any { it.endsWith("WoW.exe") })
            SyntheticClientArchives.BASE_MPQS.forEach { mpq ->
                val entry = zip.getEntry("WoW_Classic_1.12.1/Data/$mpq")
                assertTrue("missing $mpq", entry != null)
                zip.getInputStream(entry!!).use { input ->
                    val header = ByteArray(4)
                    assertEquals(4, input.read(header))
                    assertTrue(header.contentEquals(byteArrayOf(0x4d, 0x50, 0x51, 0x1a)))
                }
            }
        }
    }

    @Test
    fun zip64FlagProducesAZip64ArchiveThatStillReads() {
        val file = zipOf(SyntheticClientArchives.clientEntries(), zip64 = true)
        ZipFile.builder().setFile(file).get().use { zip ->
            assertTrue(zip.entries.asSequence().any { it.name.endsWith("WoW.exe") })
        }
    }

    @Test
    fun encryptedBitPatchMarksEveryEntryEncrypted() {
        val file = zipOf(SyntheticClientArchives.clientEntries())
        SyntheticClientArchives.patchEncryptionBits(file)
        ZipFile.builder().setFile(file).get().use { zip ->
            val entries = zip.entries.asSequence().toList()
            assertTrue(entries.isNotEmpty())
            entries.forEach { entry ->
                assertTrue("entry not flagged: ${entry.name}", entry.generalPurposeBit.usesEncryption())
            }
        }
    }

    @Test
    fun sevenZipCopyMethodFixtureReadsWithoutXzOnTheClasspath() {
        val target = SyntheticClientArchives.uniqueFile(folder.newFolder(), "client", ".7z")
        SyntheticClientArchives.sevenZip(target, SyntheticClientArchives.clientEntries())
        SevenZFile.builder().setFile(target).get().use { archive ->
            var sawExe = false
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.name.endsWith("WoW.exe")) sawExe = true
            }
            assertTrue(sawExe)
        }
    }

    @Test
    fun signatureStubsCarryExactMagicBytes() {
        assertTrue(
            SyntheticClientArchives.rarSignatureStub(rar5 = false)
                .contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)),
        )
        assertTrue(
            SyntheticClientArchives.rarSignatureStub(rar5 = true)
                .contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)),
        )
        val iso = SyntheticClientArchives.isoStub()
        assertEquals("CD001", String(iso, 0x8001, 5, Charsets.US_ASCII))
    }

    @Test
    fun libarchiveCorpusRarFixturesArePresentWithCorrectMagic() {
        fun fixture(name: String, magic: String) {
            val stream = javaClass.getResourceAsStream("/fixtures/rar/$name")
            assertTrue("missing fixture $name", stream != null)
            stream!!.use { input ->
                val header = ByteArray(magic.length / 2)
                assertEquals(header.size, input.read(header))
                assertEquals(magic, header.joinToString("") { "%02x".format(it) })
            }
        }
        fixture("rar4-client.rar", "526172211a0700")
        fixture("rar5-compressed.rar", "526172211a070100")
        fixture("rar4-encrypted.rar", "526172211a0700")
        fixture("rar4-multivolume-part1.rar", "526172211a0700")
    }

    @Test
    fun backslashSeparatorVariantMatchesTheRealStonetavernLayout() {
        val entries = SyntheticClientArchives.clientEntries(backslash = true)
        assertTrue(entries.none { it.path.contains('/') })
        val wow = entries.single { it.path.endsWith("WoW.exe") }
        assertTrue(wow.path.contains('\\'))
    }
}
