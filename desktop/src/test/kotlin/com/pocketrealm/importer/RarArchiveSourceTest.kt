package com.pocketrealm.importer

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the desktop RAR lane's refusal gates. The fixtures are crafted
 * RAR4 block sequences (marker + MAIN_HEAD with the pinned flag bits) — a RAR
 * ARCHIVER cannot exist in this repo (the unrar license forbids it and the
 * synthetic-archive fixtures only generate zip/7z/Inno), but the refusal
 * policy lives entirely in the header walk, so header-level fixtures pin it
 * exactly: encrypted (MHD_PASSWORD), multi-volume (MHD_VOLUME), solid RAR4
 * (MHD_SOLID), and the unreadable/corrupt payload that must refuse as VAL-13
 * instead of falling through to the generic no-client rejection.
 */
class RarArchiveSourceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun rar4(vararg blocks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(RAR4_MARKER)
        blocks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun fixture(name: String, bytes: ByteArray): File =
        folder.newFile(name).apply { writeBytes(bytes) }

    private fun assertRefused(archive: File, expectedCopy: String) {
        try {
            listRarEntries(archive)
            fail("the RAR lane must refuse: ${archive.name}")
        } catch (expected: ImportRejected) {
            assertEquals(expectedCopy, expected.message)
        }
    }

    @Test
    fun `a password-protected RAR4 refuses with the VAL-13 encrypted copy`() {
        assertRefused(
            fixture("encrypted.rar", rar4(mainHead(MHD_PASSWORD))),
            VAL13_RAR_ENCRYPTED,
        )
    }

    @Test
    fun `a multi-volume RAR4 refuses with the VAL-13 multi-volume copy`() {
        assertRefused(
            fixture("multivolume.rar", rar4(mainHead(MHD_VOLUME))),
            VAL13_RAR_MULTIVOLUME,
        )
    }

    @Test
    fun `a solid RAR4 refuses with the VAL-13 solid copy`() {
        assertRefused(
            fixture("solid.rar", rar4(mainHead(MHD_SOLID))),
            VAL13_RAR_SOLID_RAR4,
        )
    }

    @Test
    fun `a solid RAR5 marker is not refused by the solid-RAR4 gate`() {
        // RAR5 (marker version byte 0x01) with an RAR4 MAIN_HEAD body behind
        // it is not a parseable archive — it must refuse as UNREADABLE, but
        // never through the RAR4-only solid gate.
        val out = ByteArrayOutputStream()
        out.write(RAR5_MARKER)
        out.write(mainHead(MHD_SOLID))
        try {
            listRarEntries(fixture("solid5.rar", out.toByteArray()))
            fail("a truncated RAR5 must be refused")
        } catch (expected: ImportRejected) {
            assertEquals(VAL13_RAR_UNREADABLE, expected.message)
        }
    }

    @Test
    fun `a truncated RAR stub refuses as unreadable instead of the generic no-client rejection`() {
        // The ManagedClientImporterArchiveTest stub: RAR5 signature + zero
        // padding. Its headers never parse, so the refusal must stay VAL-13.
        val stub = RAR5_MARKER + ByteArray(ArchiveFormatSniffer.HEADER_BYTES + 16)
        assertRefused(fixture("stub.rar", stub), VAL13_RAR_UNREADABLE)
    }

    @Test
    fun `a non-RAR file refuses as unreadable`() {
        assertRefused(
            fixture("garbage.rar", ByteArray(64) { it.toByte() }),
            // The exact junrar message varies; the pinned prefix is the contract.
            VAL13_RAR_UNREADABLE,
        )
    }

    @Test
    fun `a clean single-volume RAR4 with no entries passes the gate with an empty listing`() {
        val archive = fixture("empty.rar", rar4(mainHead(0)))
        assertEquals(emptyList<ArchiveClientScanner.RawEntry>(), listRarEntries(archive))
    }

    @Test
    fun `the isRar4Magic probe mirrors the sniffer's version byte`() {
        assertTrue(isRar4Magic(fixture("v4.rar", rar4(mainHead(0)))))
        assertTrue(!isRar4Magic(fixture("v5.rar", RAR5_MARKER + ByteArray(16))))
    }

    // ------------------------------------------------------------------
    // RAR4 block crafting (marker + MAIN_HEAD).
    // ------------------------------------------------------------------

    private val RAR4_MARKER = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)
    private val RAR5_MARKER = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)

    /** junrar BaseBlock flag bits (MHD_*), mirrored here so the fixtures pin
     * the exact bits the reader interprets. */
    private val MHD_VOLUME = 0x0001
    private val MHD_SOLID = 0x0008
    private val MHD_PASSWORD = 0x0080

    /** MAIN_HEAD: CRC(2) TYPE(1)=0x73 FLAGS(2) SIZE(2)=13 HighPosAv(2) PosAv(4). */
    private fun mainHead(flags: Int): ByteArray {
        val body = ByteArray(11)
        body[0] = 0x73 // MAIN_HEAD
        body[1] = (flags and 0xff).toByte()
        body[2] = ((flags shr 8) and 0xff).toByte()
        body[3] = 13 // header size
        body[4] = 0
        // HighPosAv (2) and PosAv (4) stay zero.
        val crc = rarCrc16(body)
        return byteArrayOf(
            (crc and 0xff).toByte(),
            ((crc shr 8) and 0xff).toByte(),
        ) + body
    }

    /** RAR's CRC-16 (poly 0xA001, init 0) over TYPE..header-end. */
    private fun rarCrc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xffff
    }
}
