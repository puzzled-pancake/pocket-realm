package com.pocketrealm.importer.inno

import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Locates the Inno loader offset table inside setup.exe: either a PE
 * .rsrc resource named 11111 (5.1.5+) or the pointer at file offset 0x30
 * (earlier builds), then CRC-checks the table. The table yields the header
 * offset (setup data) and, for single-file installers, the embedded data
 * offset — external-slice installers report zero and the data lives in the
 * setup-N.bin siblings.
 */
internal object InnoLoaderOffsets {

    class Offsets(val headerOffset: Long, val dataOffset: Long)

    /** (magic, table-carries-revision) for every loader generation we accept. */
    private val knownMagics: Map<List<Byte>, Boolean> = buildMap {
        fun put(magic: String, revision: Boolean) {
            put(magic.toByteArray(Charsets.ISO_8859_1).toList(), revision)
        }
        // Pointer-era tables (pre 5.1.5).
        put("rDlPtS02\u0087eVx", false)
        put("rDlPtS04\u0087eVx", false)
        put("rDlPtS05\u0087eVx", false)
        put("rDlPtS06\u0087eVx", false)
        put("rDlPtS07\u0087eVx", false)
        // Resource-era tables (5.1.5+).
        put("rDlPtS\u00cd\u00e6\u00d7{\u000b*", true)
        put("nS5W7dT\u0083\u00aa\u001b\u000fj", true)
    }

    fun load(exe: RandomAccessFile): Offsets =
        readResourceTable(exe) ?: readPointerTable(exe)
            ?: throw InnoFormatException("no Inno loader offset table in setup.exe")

    private fun readPointerTable(exe: RandomAccessFile): Offsets? {
        val header = ByteArray(12)
        exe.seek(0x30)
        if (exe.read(header) != 12) return null
        if (!String(header, 0, 4, Charsets.US_ASCII).equals("Inno", ignoreCase = true)) return null
        val pointer = le32(header, 4)
        val notPointer = le32(header, 8)
        if (pointer != notPointer.inv()) return null
        return readTableAt(exe, pointer)
    }

    private fun readResourceTable(exe: RandomAccessFile): Offsets? {
        val resourceOffset = findInstallerResource(exe) ?: return null
        return readTableAt(exe, resourceOffset)
    }

    private fun readTableAt(exe: RandomAccessFile, position: Long): Offsets? {
        val magic = ByteArray(12)
        exe.seek(position)
        if (exe.read(magic) != 12) return null
        val withRevision = knownMagics[magic.toList()]
            ?: throw InnoFormatException("unrecognized Inno loader table magic")
        val crc = CRC32()
        crc.update(magic)
        var revision = 0L
        if (withRevision) {
            val rev = ByteArray(4)
            if (exe.read(rev) != 4) return null
            crc.update(rev)
            revision = le32(rev, 0)
            if (revision != 1L) {
                throw InnoFormatException("unsupported Inno loader table revision $revision")
            }
        }
        // skip, exe offset, exe uncompressed size, exe CRC, header, data —
        // all six feed the table checksum; the trailing expected CRC does not.
        val fields = ByteArray(24)
        if (exe.read(fields) != fields.size) return null
        crc.update(fields)
        val headerOffset = le32(fields, 16)
        val dataOffset = le32(fields, 20)
        val expected = ByteArray(4)
        if (exe.read(expected) != 4) return null
        if (crc.value != le32(expected, 0)) {
            throw InnoFormatException("Inno loader table checksum mismatch")
        }
        return Offsets(headerOffset, dataOffset)
    }

    /**
     * Walks the PE .rsrc directory tree for the integer-named resource 11111
     * (level 1) and returns the file offset of its data entry.
     */
    private fun findInstallerResource(exe: RandomAccessFile): Long? {
        val mz = ByteArray(2)
        exe.seek(0)
        if (exe.read(mz) != 2 || mz[0] != 'M'.code.toByte() || mz[1] != 'Z'.code.toByte()) return null
        val peOffsetField = ByteArray(4)
        exe.seek(0x3c)
        if (exe.read(peOffsetField) != 4) return null
        val peOffset = le32(peOffsetField, 0)
        val pe = ByteArray(24)
        exe.seek(peOffset)
        if (exe.read(pe) != 24) return null
        if (String(pe, 0, 4, Charsets.US_ASCII) != "PE\u0000\u0000") return null
        val sectionCount = (pe[6].toInt() and 0xff) or ((pe[7].toInt() and 0xff) shl 8)
        val optionalSize = (pe[20].toInt() and 0xff) or ((pe[21].toInt() and 0xff) shl 8)
        val sectionTable = peOffset + 24 + optionalSize

        class Section(val name: String, val va: Long, val raw: Long, val rawSpan: Long)

        val sections = ArrayList<Section>()
        for (i in 0 until sectionCount) {
            val row = ByteArray(40)
            exe.seek(sectionTable + i * 40L)
            if (exe.read(row) != 40) return null
            val name = String(row, 0, 8, Charsets.US_ASCII).trimEnd('\u0000')
            val virtualSize = le32(row, 8)
            val va = le32(row, 12)
            val rawSize = le32(row, 16)
            val raw = le32(row, 20)
            if (va != 0L || raw != 0L) {
                sections.add(Section(name, va, raw, maxOf(rawSize, virtualSize)))
            }
        }
        fun rvaToOffset(rva: Long): Long? =
            sections.firstOrNull { rva >= it.va && rva < it.va + it.rawSpan }
                ?.let { it.raw + (rva - it.va) }

        val rsrc = sections.firstOrNull { it.name.equals(".rsrc", ignoreCase = true) } ?: return null
        val base = rsrc.raw

        fun walkDirectory(dirOffset: Long, level: Int): Long? {
            val header = ByteArray(16)
            exe.seek(dirOffset)
            if (exe.read(header) != 16) return null
            val namedCount = (header[12].toInt() and 0xff) or ((header[13].toInt() and 0xff) shl 8)
            val idCount = (header[14].toInt() and 0xff) or ((header[15].toInt() and 0xff) shl 8)
            for (i in 0 until (namedCount + idCount)) {
                if (i < namedCount) continue // string-named entries are never ours
                val entry = ByteArray(8)
                exe.seek(dirOffset + 16 + i * 8L)
                if (exe.read(entry) != 8) return null
                val id = le32(entry, 0)
                val offsetField = le32(entry, 4)
                val target = base + (offsetField and 0x7fffffffL)
                if ((offsetField and 0x80000000L) != 0L) {
                    if (level == 1 && id != RESOURCE_INSTALLER_ID.toLong()) continue
                    if (level >= 2) continue
                    walkDirectory(target, level + 1)?.let { return it }
                } else if (level == 2) {
                    // Language leaf: the data entry is RVA + size + padding.
                    val dataEntry = ByteArray(8)
                    exe.seek(target)
                    if (exe.read(dataEntry) != 8) return null
                    return rvaToOffset(le32(dataEntry, 0))
                }
            }
            return null
        }
        return walkDirectory(base, 0)
    }

    private const val RESOURCE_INSTALLER_ID = 11111
}

internal fun le32(buffer: ByteArray, offset: Int): Long =
    (buffer[offset].toLong() and 0xff) or
        ((buffer[offset + 1].toLong() and 0xff) shl 8) or
        ((buffer[offset + 2].toLong() and 0xff) shl 16) or
        ((buffer[offset + 3].toLong() and 0xff) shl 24)
